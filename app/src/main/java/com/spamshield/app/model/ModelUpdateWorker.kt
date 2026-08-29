package com.spamshield.app.model

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.spamshield.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Keeps the on-device model current: asks the backend for the latest manifest, and if it is newer
 * than what is installed, downloads it, verifies it and swaps it in.
 *
 * Deliberately conservative:
 *  - Runs on unmetered networks only. A 633 KB download is small, but nobody agreed to spend
 *    mobile data on a background model refresh.
 *  - Downloads to a staging file; [ModelStore] promotes it only after checksum, feature-contract
 *    and smoke-test checks pass. A failed update leaves the working model untouched.
 *  - Any failure returns retry rather than surfacing an error. Spam screening keeps working on
 *    the existing model, so a bad update is a non-event rather than an outage.
 */
class ModelUpdateWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = ModelStore.getInstance(applicationContext)

        try {
            val manifest = fetchManifest() ?: return@withContext Result.retry()

            if (manifest.version <= store.installedVersion()) {
                Log.i(TAG, "Model v${manifest.version} already installed; nothing to do")
                return@withContext Result.success()
            }

            // Checked before downloading so an app that is too old to run the model does not
            // waste the user's bandwidth every 12 hours.
            if (BuildConfig.VERSION_CODE < manifest.minAppVersionCode) {
                Log.w(TAG, "Model v${manifest.version} needs app versionCode " +
                        ">= ${manifest.minAppVersionCode}; this build is ${BuildConfig.VERSION_CODE}")
                return@withContext Result.success()
            }
            if (manifest.numBuckets != com.spamshield.app.TextFeaturizer.NUM_BUCKETS ||
                manifest.maxFeatures != com.spamshield.app.TextFeaturizer.MAX_FEATURES ||
                manifest.featureVersion != com.spamshield.app.TextFeaturizer.FEATURE_VERSION) {
                Log.w(TAG, "Model v${manifest.version} uses a different feature contract; " +
                        "skipping until the app is updated")
                return@withContext Result.success()
            }

            Log.i(TAG, "Downloading model v${manifest.version} …")
            if (!download(manifest.modelUrl, store)) return@withContext Result.retry()

            return@withContext if (store.promoteStaging(manifest)) {
                Log.i(TAG, "Updated to model v${manifest.version}")
                Result.success()
            } else {
                // Validation rejected it. Retrying an identical bad artifact will not help, but
                // the next scheduled run picks up whatever is published by then.
                Log.e(TAG, "Model v${manifest.version} failed validation; keeping current model")
                Result.success()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Model update failed", e)
            Result.retry()
        }
    }

    private fun fetchManifest(): ModelManifest? {
        val request = Request.Builder()
            .url("${BuildConfig.SERVER_BASE_URL}/api/model/latest")
            .addHeader("x-api-key", BuildConfig.TELEMETRY_API_KEY)
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) {
                Log.i(TAG, "Backend has no published model yet")
                return null
            }
            if (!response.isSuccessful) {
                Log.e(TAG, "Manifest fetch failed: ${response.code}")
                return null
            }
            val body = response.body?.string() ?: return null
            return ModelManifest.fromJson(body)
        }
    }

    private fun download(url: String, store: ModelStore): Boolean {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                Log.e(TAG, "Model download failed: ${response.code}")
                return false
            }
            val body = response.body ?: return false
            val staging = store.stagingFile()
            body.byteStream().use { input ->
                staging.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Downloaded ${staging.length() / 1024} KB to staging")
            return true
        }
    }

    companion object {
        private const val TAG = "ModelUpdate"
        private const val WORK_NAME = "model_update"

        const val ACTION_CHECK_NOW = "com.spamshield.app.CHECK_MODEL_UPDATE"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<ModelUpdateWorker>(12, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request
            )
        }

        /**
         * Immediate, user-initiated check. Drops the unmetered constraint on purpose: the
         * background schedule should never spend someone's mobile data, but a person who taps
         * "check for updates" has chosen to.
         */
        fun checkNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<ModelUpdateWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_now", androidx.work.ExistingWorkPolicy.REPLACE, request
            )
        }
    }
}
