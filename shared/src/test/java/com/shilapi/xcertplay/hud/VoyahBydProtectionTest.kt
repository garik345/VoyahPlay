package com.shilapi.xcertplay.hud

import android.content.Context
import com.shilapi.xcertplay.adb.AdbKeys
import com.shilapi.xcertplay.adb.LocalAdb
import com.shilapi.xcertplay.media.AmbientMusicSettings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoyahBydProtectionTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Test fun savedBydOptInsCannotEnableVehicleSideEffectsAndAreNotErased() {
        val prefs = context.getSharedPreferences("diplay_byd_outputs", Context.MODE_PRIVATE)
        val keys = listOf("navigation_enabled", "battery_to_iphone", "wheel_speed_to_iphone",
            "cluster_song", "carplay_calls", "phone_audio_route_experimental",
            "phone_audio_route_apps_experimental", "cluster_stream_pause", "video_while_parked")
        val editor = prefs.edit()
        keys.forEach { editor.putBoolean(it, true) }
        editor.commit()
        assertFalse(BydOutputSettings.enabled(context))
        assertFalse(BydOutputSettings.batteryToIphoneActive(context))
        assertFalse(BydOutputSettings.wheelSpeedToIphoneActive(context))
        assertFalse(BydOutputSettings.clusterSong(context))
        assertFalse(BydOutputSettings.carPlayCalls(context))
        assertFalse(BydOutputSettings.phoneAudioRoute(context))
        assertFalse(BydOutputSettings.phoneAudioRouteApps(context))
        assertFalse(BydOutputSettings.clusterStreamPause(context))
        assertFalse(BydOutputSettings.videoWhileParkedActive(context))
        keys.forEach { assertTrue(prefs.getBoolean(it, false)) }
    }

    @Test fun localAdbIsUnsupportedEvenWhenTheCallerRequestsApproval() {
        LocalAdb(AdbKeys.load(context), host = "127.0.0.1", port = 1).use {
            assertEquals(LocalAdb.Access.UNSUPPORTED, it.connect(mayAsk = true))
        }
    }

    @Test fun savedAmbientLightingOptInDoesNotBecomeEffective() {
        val prefs = context.getSharedPreferences("ambient_music", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("enabled", true).commit()
        assertFalse(AmbientMusicSettings.load(context).enabled)
        assertTrue(prefs.getBoolean("enabled", false))
    }
}
