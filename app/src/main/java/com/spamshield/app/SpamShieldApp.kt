package com.spamshield.app

import android.app.Application
import com.google.android.material.color.DynamicColors

/**
 * Enables Material You dynamic color (wallpaper-derived theming) on Android 12+. Below API 31, or
 * on OEM builds that don't support it, [DynamicColors] is a no-op and every Activity simply keeps
 * the custom indigo palette defined in themes.xml/colors.xml - so this is purely additive, never a
 * regression risk.
 */
class SpamShieldApp : Application() {
    override fun onCreate() {
        super.onCreate()
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
