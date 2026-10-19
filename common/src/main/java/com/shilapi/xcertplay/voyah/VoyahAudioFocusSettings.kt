package com.shilapi.xcertplay.voyah

import android.content.Context

/** The removed transient-loss mute option is always off for Voyah. */
object VoyahAudioFocusSettings {
    @JvmStatic
    fun autoYield(context: Context): Boolean {
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        if (preferences.getBoolean("audio_focus_auto_yield", false)) {
            preferences.edit().putBoolean("audio_focus_auto_yield", false).apply()
        }
        return false
    }
}
