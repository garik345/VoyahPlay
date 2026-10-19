package com.shilapi.xcertplay.voyah

import android.content.Context
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahSettingsProfileTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String) = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    @Before fun reset() {
        for (name in listOf("xcertplay_airplay", "voyah_settings_profile", "diplay_side_panel", "diplay_carplay_rotation")) {
            prefs(name).edit().clear().commit()
        }
    }

    @Test fun freshDefaultsMatchVoyahWithoutCreatingPairingOrCertificateData() {
        VoyahSettingsProfile.apply(context)
        val p = prefs("xcertplay_airplay")
        assertEquals("Voyah", p.getString("manufacturer", null))
        assertEquals("Free", p.getString("model", null))
        assertEquals("Voyah", p.getString("oem_label", null))
        assertEquals(60, p.getInt("display_fps", 0))
        assertEquals("LOCAL", p.getString("mfi_target", null))
        assertFalse(p.contains("pairing_id"))
        assertFalse(p.contains("identity_private"))
    }

    @Test fun upgradeDisablesRemovedOptionsButPreservesPairingAndExplicitDisplayChoices() {
        prefs("voyah_settings_profile").edit().putInt("version", 1).commit()
        val p = prefs("xcertplay_airplay")
        val disabled = listOf("auto_start_on_boot", "disable_media_key_audio_focus", "audio_focus_auto_yield",
            "harman_fullscreen", "bt_suspend_during_carplay")
        p.edit().apply {
            for (key in disabled) putBoolean(key, true)
            putBoolean("audio_focus_enabled", true)
            putBoolean("smooth_video", true)
            putString("manufacturer", "Custom car")
            putString("model", "My model")
            putString("pairing_id", "paired-phone")
            putString("identity_private", "saved-key")
            putInt("display_fps", 30)
        }.commit()
        VoyahSettingsProfile.apply(context)
        for (key in disabled) assertFalse(key, p.getBoolean(key, true))
        assertTrue(p.getBoolean("audio_focus_enabled", false))
        assertTrue(p.getBoolean("smooth_video", false))
        assertEquals("paired-phone", p.getString("pairing_id", null))
        assertEquals("saved-key", p.getString("identity_private", null))
        assertEquals("Custom car", p.getString("manufacturer", null))
        assertEquals("My model", p.getString("model", null))
        assertEquals(30, p.getInt("display_fps", 0))
    }

    @Test fun migrationRunsOnceAndReplacesOnlyLegacyBrandDefaults() {
        val p = prefs("xcertplay_airplay")
        p.edit().putString("manufacturer", "DiPlay").putString("model", "DiPlay")
            .putString("oem_label", "BYD").commit()
        VoyahSettingsProfile.apply(context)
        assertEquals("Free", p.getString("model", null))
        p.edit().putString("model", "Owner's Free").putBoolean("audio_focus_enabled", true).commit()
        VoyahSettingsProfile.apply(context)
        assertEquals("Owner's Free", p.getString("model", null))
        assertTrue(p.getBoolean("audio_focus_enabled", false))
    }
}
