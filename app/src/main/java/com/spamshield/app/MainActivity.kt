package com.spamshield.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.spamshield.app.data.MessageRecord
import com.spamshield.app.data.MessageRepository
import kotlinx.coroutines.launch

/**
 * Shows every SMS the on-device model has screened (spam and safe alike), with the newest first.
 * Incoming messages are added by [SmsReceiver] in the background; this screen just observes the
 * shared [MessageRepository] and re-renders. A FAB lets you run inference on arbitrary text
 * without waiting for a real SMS, and a long-press on any row lets you correct a wrong verdict —
 * that correction is what actually gets reported to the backend for retraining.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var repository: MessageRepository
    private lateinit var adapter: MessageAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        repository = MessageRepository.getInstance(this)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        requestSmsPermissions()
        requestBatteryExemption()
        TelemetrySyncWorker.schedulePeriodic(this)

        setupList()
        setupSwipeRefresh()
        setupFab()

        repository.messages.observe(this) { messages ->
            adapter.submitList(messages)
            findViewById<TextView>(R.id.txtEmptyState).visibility =
                if (messages.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        lifecycleScope.launch { repository.refresh() }
    }

    private fun setupList() {
        adapter = MessageAdapter(onLongPress = ::showCorrectionDialog)
        findViewById<RecyclerView>(R.id.recyclerMessages).apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
        }
    }

    private fun setupSwipeRefresh() {
        findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).setOnRefreshListener {
            lifecycleScope.launch {
                repository.refresh()
                findViewById<SwipeRefreshLayout>(R.id.swipeRefresh).isRefreshing = false
            }
        }
    }

    private fun setupFab() {
        findViewById<FloatingActionButton>(R.id.fabTestMessage).setOnClickListener {
            showTestMessageDialog()
        }
    }

    private fun showTestMessageDialog() {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_test_message, null)
        val input = view.findViewById<EditText>(R.id.editTextInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.test_message_title)
            .setView(view)
            .setPositiveButton(R.string.run_inference) { _, _ ->
                val text = input.text.toString()
                if (text.isNotBlank()) classifyAndStore(text)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun classifyAndStore(text: String) {
        lifecycleScope.launch {
            val classifier = SpamClassifier(this@MainActivity)
            val result = try {
                classifier.classify(text)
            } finally {
                classifier.close()
            }

            val id = repository.recordMessage(
                sender = "Manual Test",
                body = text,
                isSpam = result.isSpam,
                confidence = result.confidence
            )

            if (result.isSpam) {
                val record = MessageRecord(
                    id = id,
                    sender = "Manual Test",
                    body = text,
                    timestamp = System.currentTimeMillis(),
                    isSpam = true,
                    confidence = result.confidence
                )
                if (TelemetryClient().report(record, source = "model")) {
                    repository.markSynced(id)
                }
            }
        }
    }

    private fun showCorrectionDialog(record: MessageRecord) {
        val currentVerdict = record.reviewedLabel ?: record.isSpam
        val options = arrayOf(
            getString(R.string.mark_spam),
            getString(R.string.mark_not_spam)
        )

        AlertDialog.Builder(this)
            .setTitle(record.body.take(60))
            .setItems(options) { _, which ->
                val correctedIsSpam = which == 0
                if (correctedIsSpam == currentVerdict) return@setItems
                lifecycleScope.launch {
                    repository.correctLabel(record.id, correctedIsSpam)
                    val corrected = record.copy(reviewedLabel = correctedIsSpam)
                    if (TelemetryClient().report(corrected, source = "user_correction")) {
                        repository.markSynced(record.id)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * SMS_RECEIVED still wakes the receiver during Doze, but the telemetry POST can be deferred
     * while the device is idle. Asking once for a Doze exemption keeps background screening
     * reporting promptly. Silently skipped if already exempt or unsupported by the ROM.
     */
    private fun requestBatteryExemption() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.fromParts("package", packageName, null))
            )
        } catch (e: Exception) {
            Log.w("SpamShield", "Battery optimization prompt unavailable: ${e.message}")
        }
    }

    private fun requestSmsPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS), 101)
        }
    }
}
