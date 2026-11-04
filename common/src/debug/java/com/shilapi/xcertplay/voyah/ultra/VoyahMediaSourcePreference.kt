package com.shilapi.xcertplay.voyah.ultra

import android.content.SharedPreferences
import com.shilapi.xcertplay.airplay.ultra.UltraMediaSource

/** A car-local choice, not track metadata, volume, Siri state or an instruction to autoplay the iPhone. */
internal class VoyahMediaSourcePreference(private val preferences: SharedPreferences) {
    fun read(): UltraMediaSource = when (runCatching { preferences.getString("lastSource", null) }.getOrNull()) {
        "RADIO" -> UltraMediaSource.RADIO
        else -> UltraMediaSource.CARPLAY
    }
    fun save(value: UltraMediaSource) {
        if (runCatching { preferences.getString("lastSource", null) }.getOrNull() != value.name)
            preferences.edit().putString("lastSource", value.name).apply()
    }
}
