package com.spamshield.app.model

import org.json.JSONObject

/**
 * What `GET /api/model/latest` returns: a pointer to the model plus everything needed to decide
 * whether this build may safely use it.
 */
data class ModelManifest(
    val version: Int,
    val modelUrl: String,
    val sha256: String,
    val threshold: Float,
    val numBuckets: Int,
    val maxFeatures: Int,
    /**
     * Catches a SEMANTIC tokenizer change (e.g. digit masking) that numBuckets/maxFeatures can't
     * see, since the vector shape doesn't change - only what each id means. A model trained
     * under different tokenization semantics must never be loaded by code that disagrees with it.
     */
    val featureVersion: Int,
    val minAppVersionCode: Int,
    val realF1: Double?
) {
    /** Written next to the model so SpamClassifier can read the threshold back at load time. */
    fun toMetaJson(): String = JSONObject().apply {
        put("threshold", threshold.toDouble())
        put("num_buckets", numBuckets)
        put("max_features", maxFeatures)
        put("feature_version", featureVersion)
        put("model_version", version)
    }.toString()

    companion object {
        fun fromJson(json: String): ModelManifest {
            val o = JSONObject(json)
            val contract = o.getJSONObject("featureContract")
            val metrics = o.optJSONObject("metrics")
            return ModelManifest(
                version = o.getInt("version"),
                modelUrl = o.getString("modelUrl"),
                sha256 = o.getString("sha256"),
                threshold = o.getDouble("threshold").toFloat(),
                numBuckets = contract.getInt("numBuckets"),
                maxFeatures = contract.getInt("maxFeatures"),
                featureVersion = contract.getInt("featureVersion"),
                minAppVersionCode = o.optInt("minAppVersionCode", 1),
                realF1 = metrics?.optDouble("realF1")
            )
        }
    }
}
