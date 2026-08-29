package com.spamshield.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import android.widget.Toast
import com.spamshield.app.data.MessageRecord
import com.spamshield.app.data.MessageRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SmsReceiver : BroadcastReceiver() {

    companion object {
        /**
         * Debug-only entry point so the full screening pipeline can be exercised without a second
         * phone. The matching intent-filter lives in src/debug/AndroidManifest.xml, so release
         * builds never expose it.
         */
        const val DEBUG_TEST_ACTION = "com.spamshield.app.DEBUG_TEST_SMS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sender: String
        val messageBody: String

        when (intent.action) {
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
                if (messages.isEmpty()) return

                // A single SMS_RECEIVED broadcast carries every fragment of one message, so join
                // them before classifying — scoring fragments separately splits text mid-sentence.
                sender = messages[0].displayOriginatingAddress ?: "unknown"
                messageBody = messages.joinToString("") { it.displayMessageBody ?: "" }
            }

            DEBUG_TEST_ACTION -> {
                if (!BuildConfig.DEBUG) return
                sender = intent.getStringExtra("sender") ?: "debug-sender"
                messageBody = intent.getStringExtra("body") ?: return
                Log.i("SpamShield", "Debug screening request for: $messageBody")
            }

            else -> return
        }

        if (messageBody.isBlank()) return

        // Every incoming SMS lands in the local history list (visible in MainActivity) whether or
        // not it's spam; only spam ever gets reported to the backend.
        // Keep the receiver alive across inference, DB write, and the telemetry POST; without
        // this the process can be torn down as soon as onReceive returns.
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val classifier = SpamClassifier(context)
                val result = try {
                    classifier.classify(messageBody)
                } finally {
                    classifier.close()
                }

                val repository = MessageRepository.getInstance(context)
                val recordId = repository.recordMessage(
                    sender = sender,
                    body = messageBody,
                    isSpam = result.isSpam,
                    confidence = result.confidence
                )

                if (!result.isSpam) {
                    Log.i("SpamShield", "Clean message from $sender")
                    return@launch
                }

                Log.w("SpamShield", "Intercepted Spam: $messageBody")
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Spam Blocked: $sender", Toast.LENGTH_LONG).show()
                }

                // Blocking so the report lands before the process is allowed to die; if it fails
                // (no signal, backend down) the row stays unsynced and TelemetrySyncWorker retries it.
                val record = MessageRecord(
                    id = recordId,
                    sender = sender,
                    body = messageBody,
                    timestamp = System.currentTimeMillis(),
                    isSpam = true,
                    confidence = result.confidence
                )
                if (TelemetryClient().report(record, source = "model")) {
                    repository.markSynced(recordId)
                }
                TelemetrySyncWorker.schedulePeriodic(context)
            } catch (e: Exception) {
                Log.e("SpamShield", "Failed to screen message from $sender", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
