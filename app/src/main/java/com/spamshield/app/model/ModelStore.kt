package com.spamshield.app.model

import android.content.Context
import android.util.Log
import com.spamshield.app.TextFeaturizer
import com.spamshield.app.data.AppPrefs
import java.io.File
import java.security.MessageDigest

/**
 * Owns which model file the classifier actually loads, and makes replacing it survivable.
 *
 * Layout in filesDir/models/:
 *   active.tflite / active_meta.json      - what SpamClassifier loads (absent => APK asset)
 *   previous.tflite / previous_meta.json  - the last known-good pair, kept for rollback
 *   staging.tflite                        - partially downloaded bytes, never loaded
 *
 * A downloaded model is only promoted to active after it has passed, in order:
 *   1. sha256 match against the manifest      - detects corruption and tampering in transit
 *   2. feature-contract match against this build's TextFeaturizer
 *   3. a live smoke test on known messages    - detects "loads fine, predicts nonsense"
 *
 * Check 2 is not paranoia. An earlier version of this app shipped a tokenizer whose vocabulary
 * disagreed with the model's embedding size and crashed on 21.5% of real messages while every
 * offline metric looked excellent. OTA updates make that failure mode far easier to hit, because
 * model and app can now be updated independently.
 */
class ModelStore private constructor(private val context: Context) {

    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val activeModel = File(dir, "active.tflite")
    private val activeMeta = File(dir, "active_meta.json")
    private val previousModel = File(dir, "previous.tflite")
    private val previousMeta = File(dir, "previous_meta.json")
    private val staging = File(dir, "staging.tflite")

    companion object {
        private const val TAG = "ModelStore"

        /**
         * Messages whose verdict must survive a model swap. Deliberately small and unambiguous:
         * this is a sanity check on a fresh download, not an accuracy benchmark.
         */
        private val SMOKE_TESTS = listOf(
            "Bhai lottery jeet gaya, turant click karo bit.ly/xy12" to true,
            "आपका बैंक खाता आज बंद हो जाएगा, तुरंत KYC पूरा करें" to true,
            "C0ngratu1ati0ns! U w0n Rs 50,000 c1ick n0w" to true,
            "Your SBI OTP is 448291. Valid 10 min. Do not share." to false,
            "yaar kal milte hain coffee ke liye" to false
        )

        @Volatile private var instance: ModelStore? = null

        fun getInstance(context: Context): ModelStore =
            instance ?: synchronized(this) {
                instance ?: ModelStore(context.applicationContext).also { instance = it }
            }
    }

    /** The .tflite to load, or null when no OTA model is installed and the APK asset should be used. */
    fun activeModelFile(): File? = activeModel.takeIf { it.exists() && it.length() > 0 }

    /** Metadata JSON that accompanies [activeModelFile], or null to fall back to the asset. */
    fun activeMetaFile(): File? = activeMeta.takeIf { it.exists() && it.length() > 0 }

    fun stagingFile(): File = staging

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Validates freshly downloaded bytes and promotes them to active.
     *
     * @return true if the model is now live. On any failure the previous model stays active and
     *         the staging file is discarded, so a bad release degrades to "no update" rather than
     *         to "spam detection is broken".
     */
    fun promoteStaging(manifest: ModelManifest): Boolean {
        if (!staging.exists() || staging.length() == 0L) {
            Log.e(TAG, "No staged bytes to promote")
            return false
        }

        try {
            // 1. integrity
            val actual = sha256(staging)
            if (!actual.equals(manifest.sha256, ignoreCase = true)) {
                Log.e(TAG, "sha256 mismatch: expected ${manifest.sha256.take(12)}… got ${actual.take(12)}…")
                return false
            }

            // 2. feature contract - refuse a model this build cannot correctly feed.
            // featureVersion catches a SEMANTIC tokenizer change (e.g. digit masking) that
            // numBuckets/maxFeatures alone can't see, since the vector shape is unchanged.
            if (manifest.numBuckets != TextFeaturizer.NUM_BUCKETS ||
                manifest.maxFeatures != TextFeaturizer.MAX_FEATURES ||
                manifest.featureVersion != TextFeaturizer.FEATURE_VERSION
            ) {
                Log.e(
                    TAG,
                    "Refusing model v${manifest.version}: feature contract " +
                            "${manifest.numBuckets}/${manifest.maxFeatures}/v${manifest.featureVersion} " +
                            "!= this build's ${TextFeaturizer.NUM_BUCKETS}/${TextFeaturizer.MAX_FEATURES}/" +
                            "v${TextFeaturizer.FEATURE_VERSION}. The app must be updated before this " +
                            "model can be used."
                )
                return false
            }

            // 3. does it actually behave? Run before touching the active slot.
            if (!smokeTest(staging, manifest.threshold)) {
                Log.e(TAG, "Refusing model v${manifest.version}: smoke test failed")
                return false
            }

            // Keep the outgoing model so rollback() has something to restore.
            if (activeModel.exists()) {
                activeModel.copyTo(previousModel, overwrite = true)
                if (activeMeta.exists()) activeMeta.copyTo(previousMeta, overwrite = true)
            }

            staging.copyTo(activeModel, overwrite = true)
            activeMeta.writeText(manifest.toMetaJson())
            staging.delete()

            AppPrefs.getInstance(context).installedModelVersion = manifest.version
            Log.i(TAG, "Model v${manifest.version} is now active (${activeModel.length() / 1024} KB)")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote staged model", e)
            return false
        } finally {
            if (staging.exists()) staging.delete()
        }
    }

    /**
     * Loads a candidate .tflite and checks it still classifies obvious cases correctly.
     * Catches a model that loads without throwing but predicts nonsense - the failure an
     * automated retrain loop is most likely to produce.
     */
    private fun smokeTest(modelFile: File, threshold: Float): Boolean {
        var interpreter: org.tensorflow.lite.Interpreter? = null
        return try {
            interpreter = org.tensorflow.lite.Interpreter(modelFile)
            for ((text, expectedSpam) in SMOKE_TESTS) {
                val input = arrayOf(TextFeaturizer.featurize(text))
                val output = arrayOf(FloatArray(1))
                interpreter.run(input, output)
                val isSpam = output[0][0] > threshold
                if (isSpam != expectedSpam) {
                    Log.e(TAG, "smoke test failed (p=${output[0][0]}, expected spam=$expectedSpam): ${text.take(40)}")
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "smoke test threw", e)
            false
        } finally {
            interpreter?.close()
        }
    }

    /** Restores the previous model. Used when a promoted model misbehaves in the field. */
    fun rollback(): Boolean {
        if (!previousModel.exists()) {
            Log.w(TAG, "No previous model to roll back to; clearing active so the APK asset is used")
            activeModel.delete()
            activeMeta.delete()
            AppPrefs.getInstance(context).installedModelVersion = 0
            return true
        }
        return try {
            previousModel.copyTo(activeModel, overwrite = true)
            if (previousMeta.exists()) previousMeta.copyTo(activeMeta, overwrite = true)
            Log.w(TAG, "Rolled back to the previous model")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Rollback failed", e)
            false
        }
    }

    fun installedVersion(): Int = AppPrefs.getInstance(context).installedModelVersion
}
