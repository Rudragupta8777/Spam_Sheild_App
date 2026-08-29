package com.spamshield.app

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.NetworkType
import com.spamshield.app.data.MessageRepository
import java.util.concurrent.TimeUnit

/**
 * Retries telemetry POSTs that failed at the time a spam message was screened (no signal, backend
 * briefly down, etc.). Runs periodically so a queued report from a Doze-restricted background
 * receiver eventually lands once the device has connectivity, instead of being lost.
 */
class TelemetrySyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val repository = MessageRepository.getInstance(applicationContext)
        val telemetryClient = TelemetryClient()

        val pending = repository.getPendingSync()
        var allSucceeded = true

        for (record in pending) {
            val source = if (record.reviewedLabel != null) "user_correction" else "model"
            val success = telemetryClient.report(record, source)
            if (success) {
                repository.markSynced(record.id)
            } else {
                allSucceeded = false
            }
        }

        return if (allSucceeded) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "telemetry_sync"

        fun schedulePeriodic(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<TelemetrySyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
