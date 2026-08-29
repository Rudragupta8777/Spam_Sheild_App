package com.spamshield.app.data

import android.content.Context
import java.util.UUID

/**
 * Small settings store: the anonymous install id, the user's correction-sharing choice, and the
 * currently installed model version.
 */
class AppPrefs private constructor(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences("spamshield_prefs", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_DEVICE_ID = "device_id"
        private const val KEY_CONSENT_ASKED = "correction_consent_asked"
        private const val KEY_CONSENT_GRANTED = "correction_consent_granted"
        private const val KEY_MODEL_VERSION = "installed_model_version"

        @Volatile private var instance: AppPrefs? = null

        fun getInstance(context: Context): AppPrefs =
            instance ?: synchronized(this) {
                instance ?: AppPrefs(context).also { instance = it }
            }
    }

    /**
     * Random per-install id. Not a hardware identifier and not tied to the user: the backend only
     * uses it to count how many INDEPENDENT installs reported the same message, which is what
     * stops one person from steering the shared model. Reinstalling produces a new id, and that
     * is fine - the count only ever needs to be a lower bound on distinct reporters.
     */
    val deviceId: String
        get() = prefs.getString(KEY_DEVICE_ID, null) ?: synchronized(this) {
            prefs.getString(KEY_DEVICE_ID, null) ?: UUID.randomUUID().toString().also {
                prefs.edit().putString(KEY_DEVICE_ID, it).apply()
            }
        }

    /** Whether we have shown the one-time explanation for sharing corrected messages. */
    var correctionConsentAsked: Boolean
        get() = prefs.getBoolean(KEY_CONSENT_ASKED, false)
        set(v) = prefs.edit().putBoolean(KEY_CONSENT_ASKED, v).apply()

    /**
     * Opt-in for uploading the TEXT of a message the user explicitly corrected.
     *
     * Defaults to false, and nothing else in the app uploads ham text under any circumstance.
     * Automatic screening never sends a safe message anywhere; only a deliberate "this isn't
     * spam" tap can, and only while this is true.
     */
    var correctionConsentGranted: Boolean
        get() = prefs.getBoolean(KEY_CONSENT_GRANTED, false)
        set(v) = prefs.edit().putBoolean(KEY_CONSENT_GRANTED, v).apply()

    /** 0 means "still using the model bundled in the APK". */
    var installedModelVersion: Int
        get() = prefs.getInt(KEY_MODEL_VERSION, 0)
        set(v) = prefs.edit().putInt(KEY_MODEL_VERSION, v).apply()
}
