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
import com.spamshield.app.data.AppPrefs
import com.spamshield.app.data.MessageRecord
import com.spamshield.app.data.MessageRepository
import com.spamshield.app.model.ModelUpdateWorker
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
        ModelUpdateWorker.schedulePeriodic(this)

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
                if (TelemetryClient().report(record, source = "model", context = this@MainActivity)) {
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
                applyCorrection(record, correctedIsSpam)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Records the correction locally, then reports it.
     *
     * Correcting a message to "not spam" is the only path in the app that can upload the text of
     * a non-spam message, so the first time it happens we ask. Marking something as spam needs no
     * prompt: reporting spam is what the app is for, and that text is already shared for any
     * message the model itself flags.
     */
    private fun applyCorrection(record: MessageRecord, correctedIsSpam: Boolean) {
        val prefs = AppPrefs.getInstance(this)
        if (!correctedIsSpam && !prefs.correctionConsentAsked) {
            showConsentDialog { prefs.correctionConsentAsked = true; persistCorrection(record, false) }
            return
        }
        persistCorrection(record, correctedIsSpam)
    }

    private fun persistCorrection(record: MessageRecord, correctedIsSpam: Boolean) {
        lifecycleScope.launch {
            repository.correctLabel(record.id, correctedIsSpam)
            val corrected = record.copy(reviewedLabel = correctedIsSpam)
            if (TelemetryClient().report(corrected, source = "user_correction",
                                         context = this@MainActivity)) {
                repository.markSynced(record.id)
            }
        }
    }

    /** One-time, explicit opt-in. Declining still records the correction locally. */
    private fun showConsentDialog(onDecided: () -> Unit) {
        val prefs = AppPrefs.getInstance(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.consent_title)
            .setMessage(R.string.consent_message)
            .setPositiveButton(R.string.consent_allow) { _, _ ->
                prefs.correctionConsentGranted = true
                onDecided()
            }
            .setNegativeButton(R.string.consent_deny) { _, _ ->
                prefs.correctionConsentGranted = false
                onDecided()
            }
            .setCancelable(false)
            .show()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: android.view.Menu): Boolean {
        menu.findItem(R.id.action_share_corrections)?.isChecked =
            AppPrefs.getInstance(this).correctionConsentGranted
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_share_corrections -> {
                val prefs = AppPrefs.getInstance(this)
                val enabled = !item.isChecked
                item.isChecked = enabled
                prefs.correctionConsentGranted = enabled
                prefs.correctionConsentAsked = true
                android.widget.Toast.makeText(
                    this,
                    if (enabled) R.string.consent_on else R.string.consent_off,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                true
            }
            R.id.action_check_update -> {
                ModelUpdateWorker.checkNow(this)
                android.widget.Toast.makeText(this, R.string.checking_update,
                    android.widget.Toast.LENGTH_SHORT).show()
                true
            }
            R.id.action_model_info -> {
                showModelInfo()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    /** Surfaces which model is actually running - useful when verifying an OTA update landed. */
    private fun showModelInfo() {
        lifecycleScope.launch {
            val classifier = SpamClassifier(this@MainActivity)
            val version = try { classifier.modelVersion } finally { classifier.close() }
            val label = if (version == 0) getString(R.string.model_bundled)
                        else getString(R.string.model_version_fmt, version)
            AlertDialog.Builder(this@MainActivity)
                .setTitle(R.string.action_model_info)
                .setMessage(label)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
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
