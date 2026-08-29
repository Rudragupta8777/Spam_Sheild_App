package com.spamshield.app

import android.util.Log
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
 * Privacy rule: the raw message text is only ever included when the final label is "spam".
 * Messages that are (or turn out to be) ham never leave the device — only their hash does, so a
 * false-positive correction can still adjust the backend's counters without exposing the text.
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

    private fun buildRequest(record: MessageRecord, source: String): Request {
        val finalIsSpam = record.reviewedLabel ?: record.isSpam
        val label = if (finalIsSpam) "spam" else "ham"
        val hash = hashString(record.body)

        val payload = mutableMapOf<String, Any?>(
            "spamHash" to hash,
            "label" to label,
            "source" to source,
            "confidence" to record.confidence,
            "timestamp" to record.timestamp,
            "appVersion" to BuildConfig.VERSION_NAME
        )
        // Only spam text is ever transmitted; ham (including corrected false positives) is hash-only.
        if (finalIsSpam) {
            payload["messageText"] = record.body
        }

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
    suspend fun report(record: MessageRecord, source: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                client.newCall(buildRequest(record, source)).execute().use { response ->
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
