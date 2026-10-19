package com.shilapi.xcertplay.voyah

import android.content.Context

/** One-time migration: removed options must not keep an old enabled value invisibly. */
object VoyahSettingsProfile {
    @JvmStatic fun apply(context: Context) {
        val marker=context.getSharedPreferences("voyah_settings_profile",Context.MODE_PRIVATE)
        if(marker.getInt("version",0)>=2) return
        val prefs = context.getSharedPreferences("xcertplay_airplay",Context.MODE_PRIVATE)
        val edit = prefs.edit()
            .putBoolean("auto_start_on_boot",false)
            .putBoolean("disable_media_key_audio_focus",false)
            .putBoolean("audio_focus_auto_yield",false)
            .putBoolean("harman_fullscreen",false)
            .putBoolean("bt_suspend_during_carplay",false)
            .putString("mfi_target", "LOCAL")
        if (!prefs.contains("display_fps")) edit.putInt("display_fps", 60)
        for ((key, fallback) in listOf("manufacturer" to "Voyah", "model" to "Free", "oem_label" to "Voyah")) {
            val saved = prefs.getString(key, null)
            if (saved.isNullOrBlank() || saved.equals("DiPlay", true) || saved.equals("BYD", true)) {
                edit.putString(key, fallback)
            }
        }
        edit.apply()
        context.getSharedPreferences("diplay_side_panel",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).apply()
        context.getSharedPreferences("diplay_carplay_rotation",Context.MODE_PRIVATE).edit().putBoolean("enabled",false).apply()
        marker.edit().putInt("version",2).apply()
    }
}
