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
    fun isAssetProbeEnabled(context: Context): Boolean = false
    fun isMutualAuthEnabled(context: Context): Boolean = false
    fun carPlayTheme(context: Context): com.shilapi.xcertplay.transport.Iap2CarPlayTheme? = null
    fun themeDisplay(context: Context, fps: Int): com.shilapi.xcertplay.airplay.AirPlayDisplayConfig? = null
    fun vehicleInformation(context: Context): com.shilapi.xcertplay.transport.Iap2VehicleInformation? = null
    fun isRadioEnabled(context: Context): Boolean = false
    fun saveRadioEnabled(context: Context, enabled: Boolean) {}
    fun showStatus(activity: Activity) {}
    fun probeClimate(activity: Activity) {}
    fun exportReport(activity: Activity) {}
}
