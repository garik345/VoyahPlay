package com.shilapi.xcertplay.voyah.ultra

import android.app.Activity
import android.content.Context
import com.shilapi.xcertplay.airplay.AirPlayProtocolExtension

/** Release variant has no Ultra pilot, CAF server or diagnostic UI. */
object VoyahUltraSettings {
    const val available = false
    fun createExtension(context: Context): AirPlayProtocolExtension? = null
    fun isEnabled(context: Context): Boolean = false
    fun saveEnabled(context: Context, enabled: Boolean) {}
    fun showStatus(activity: Activity) {}
    fun probeClimate(activity: Activity) {}
    fun exportReport(activity: Activity) {}
}
