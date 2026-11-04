package com.shilapi.xcertplay

import android.content.Context

/** Deliberate opt-in for the reviewed Voyah KeyManager; it does not enable accessibility key routing. */
object VoyahPhoneWheelSettings {
    private const val PREFS = "voyah_phone_wheel"
    private const val ENABLED = "enabled"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ENABLED, value).apply()
        VoyahPhoneWheel.refreshEnabled()
    }
}
