package com.spamshield.app

import android.content.Context
import android.util.Log
import com.spamshield.app.model.ModelStore
import org.json.JSONObject
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

data class ClassificationResult(val isSpam: Boolean, val confidence: Float)

/**
 * Runs the on-device spam model. Text is turned into features by [TextFeaturizer], whose output
 * is guaranteed identical to the training pipeline by TextFeaturizerParityTest.
 *
 * This class used to load a `tokenizer.json` word index and map words to raw indices. That was
 * the source of two production failures: indices beyond the embedding's row count crashed TFLite
 * with "gather index out of bounds" on 21.5% of messages, and the `[^a-z0-9 ]` cleanup deleted
 * every Devanagari/Tamil character so Hindi spam arrived as an empty string. Both are gone -
 * there is no vocabulary asset any more, and features are hashed into a fixed range.
 */
class SpamClassifier(context: Context) {
    private var interpreter: Interpreter? = null

    /** Decision threshold tuned for best F1 during training, not the naive 0.5. */
    private var threshold: Float = DEFAULT_THRESHOLD

    companion object {
        private const val MODEL_ASSET = "spam_detector.tflite"
        private const val META_ASSET = "model_meta.json"
        private const val DEFAULT_THRESHOLD = 0.5f
        private const val TAG = "SpamShield"
    }

    /** 0 when running the model bundled in the APK, else the OTA version in use. */
    var modelVersion: Int = 0
        private set

    init {
        // Prefer an OTA-installed model; fall back to the APK asset when none has been
        // downloaded yet, or when a download was rejected by ModelStore's validation.
        val store = ModelStore.getInstance(context)
        val otaModel = store.activeModelFile()

        if (otaModel != null) {
            interpreter = Interpreter(otaModel)
            modelVersion = store.installedVersion()
            Log.i(TAG, "Loaded OTA model v$modelVersion (${otaModel.length() / 1024} KB)")
        } else {
            val afd = context.assets.openFd(MODEL_ASSET)
            FileInputStream(afd.fileDescriptor).use { stream ->
                val buffer: MappedByteBuffer = stream.channel.map(
                    FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength
                )
                interpreter = Interpreter(buffer)
            }
        }

        // Threshold lives in metadata so retraining can retune it without an app code change.
        // A missing/malformed file degrades to 0.5 rather than failing to classify at all.
        try {
            val json = store.activeMetaFile()?.readText()
                ?: context.assets.open(META_ASSET).bufferedReader().use { it.readText() }
            val meta = JSONObject(json)
            threshold = meta.optDouble("threshold", DEFAULT_THRESHOLD.toDouble()).toFloat()

            val buckets = meta.optInt("num_buckets", TextFeaturizer.NUM_BUCKETS)
            val maxFeatures = meta.optInt("max_features", TextFeaturizer.MAX_FEATURES)
            val featureVersion = meta.optInt("feature_version", TextFeaturizer.FEATURE_VERSION)
            if (buckets != TextFeaturizer.NUM_BUCKETS || maxFeatures != TextFeaturizer.MAX_FEATURES ||
                featureVersion != TextFeaturizer.FEATURE_VERSION) {
                // Fail loudly: the shipped model was trained against a different feature
                // contract than this build implements, which is exactly how the last bug shipped.
                // (An OTA-downloaded model can't reach this state - ModelStore already refused to
                // activate it. This only fires if the APK's own bundled asset and code disagree.)
                Log.e(TAG, "Feature contract mismatch! model expects buckets=$buckets " +
                        "maxFeatures=$maxFeatures featureVersion=$featureVersion but app has " +
                        "${TextFeaturizer.NUM_BUCKETS}/${TextFeaturizer.MAX_FEATURES}/" +
                        "${TextFeaturizer.FEATURE_VERSION}. Re-export the model and parity vectors.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "model_meta.json unreadable, using threshold $DEFAULT_THRESHOLD: ${e.message}")
        }
    }

    /** Convenience wrapper for callers that only care about the yes/no verdict. */
    fun classifyText(text: String): Boolean = classify(text).isSpam

    fun classify(text: String): ClassificationResult {
        val features = TextFeaturizer.featurize(text)

        val input = arrayOf(features)
        val output = arrayOf(FloatArray(1))
        interpreter?.run(input, output)

        val spamProbability = output[0][0]
        return ClassificationResult(
            isSpam = spamProbability > threshold,
            confidence = spamProbability
        )
    }

    fun close() {
        interpreter?.close()
        interpreter = null
    }
}
