package com.spamshield.app

import android.content.Context
import android.util.Log
import com.spamshield.app.data.AppPrefs
import com.spamshield.app.data.MessageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Reports screening results to the telemetry backend so newly-seen spam patterns (and any
 * corrections the user makes) can feed the next model retrain.
 *
 * Privacy rules:
 *  - Message text is only ever included when the final label is "spam", or a consented
 *    correction. Messages that are (and stay) ham never leave the device — only their hash does.
 *  - Whatever DOES get sent - text or hash - is computed from [TextFeaturizer.maskDigits], not
 *    the raw body. Digit runs of 3+ (OTPs, phone numbers, account numbers, amounts) become a
 *    fixed "XXXX" before anything leaves the device, so the actual digits never reach the
 *    backend even for spam text. This is the same masking [SpamClassifier] applies before
 *    classification, so what gets uploaded for retraining is exactly what the model was shown.
 */
class TelemetryClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val telemetryUrl = "${BuildConfig.SERVER_BASE_URL}/api/telemetry"

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun buildRequest(record: MessageRecord, source: String, context: Context): Request {
        val finalIsSpam = record.reviewedLabel ?: record.isSpam
        val label = if (finalIsSpam) "spam" else "ham"
        val prefs = AppPrefs.getInstance(context)

        // Masked BEFORE hashing too: two messages differing only in a swapped-out phone number
        // (a common smishing-campaign pattern) now hash identically, so device reports of the
        // same template correctly count as the same signal instead of fragmenting across variants.
        val maskedText = TextFeaturizer.maskDigits(record.body)
        val hash = hashString(maskedText)

        val payload = mutableMapOf<String, Any?>(
            "spamHash" to hash,
            "label" to label,
            "source" to source,
            "confidence" to record.confidence,
            "timestamp" to record.timestamp,
            "appVersion" to BuildConfig.VERSION_NAME,
            // Anonymous per-install id. The backend uses it only to count how many independent
            // installs reported the same message, which is what stops a single actor poisoning
            // the shared model.
            "deviceId" to prefs.deviceId
        )

        if (finalIsSpam) {
            // Spam text is always sent - reporting it is the point of the product.
            payload["messageText"] = maskedText
        } else if (source == "user_correction" && prefs.correctionConsentGranted) {
            // A false positive is only useful for retraining if the text comes with it, but this
            // is a message the model decided was NOT spam, i.e. potentially private. It is sent
            // only because the user deliberately tapped "Not Spam" on this specific message AND
            // opted in. Automatic screening never reaches this branch.
            payload["messageText"] = maskedText
            payload["textConsent"] = true
        }
        // Any other ham stays hash-only.

        val json = org.json.JSONObject(payload as Map<*, *>).toString()
        val requestBody = json.toRequestBody("application/json; charset=utf-8".toMediaType())

        return Request.Builder()
            .url(telemetryUrl)
            .addHeader("x-api-key", BuildConfig.TELEMETRY_API_KEY)
            .post(requestBody)
            .build()
    }

    /**
     * Sends one screening result to the backend. Suspends on [Dispatchers.IO], so it's safe to
     * call from a BroadcastReceiver's goAsync() coroutine or the retry WorkManager job alike.
     * Returns true only on a 2xx response — callers use this to decide whether to mark the row
     * as synced or leave it queued for retry.
     */
    suspend fun report(record: MessageRecord, source: String, context: Context): Boolean =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(buildRequest(record, source, context)).execute().use { response ->
                    if (response.isSuccessful) {
                        Log.i("Telemetry", "Reported ${record.id} to backend (label=${record.reviewedLabel ?: record.isSpam})")
                        true
                    } else {
                        Log.e("Telemetry", "Backend rejected request: ${response.code}")
                        false
                    }
                }
            } catch (e: IOException) {
                Log.e("Telemetry", "Failed to connect to backend: ${e.message}")
                false
            }
        }
}
