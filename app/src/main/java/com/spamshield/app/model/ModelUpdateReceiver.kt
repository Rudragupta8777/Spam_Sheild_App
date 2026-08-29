package com.spamshield.app.model

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug-only hook to run a model-update check on demand, so the OTA path can be exercised over
 * adb instead of waiting up to 12 hours for the periodic worker (which additionally only runs on
 * unmetered networks).
 *
 * The intent-filter lives in src/debug/AndroidManifest.xml, so release builds do not expose it.
 * Must be addressed explicitly, because Android 8+ withholds implicit broadcasts from
 * manifest-declared receivers:
 *
 *   adb shell am broadcast -n com.spamshield.app/.model.ModelUpdateReceiver \
 *     -a com.spamshield.app.CHECK_MODEL_UPDATE
 */
class ModelUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ModelUpdateWorker.ACTION_CHECK_NOW) return
        Log.i("ModelUpdate", "Manual model-update check requested")
        ModelUpdateWorker.checkNow(context)
    }
}
