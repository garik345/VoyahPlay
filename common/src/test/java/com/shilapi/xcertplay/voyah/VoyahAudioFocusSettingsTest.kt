package com.shilapi.xcertplay.voyah

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahAudioFocusSettingsTest {
    @Test fun staleMuteOptionIsClearedWithoutChangingOtherAudioSettings() {
        val context: Context = RuntimeEnvironment.getApplication()
        val preferences = context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
        preferences.edit().putBoolean("audio_focus_auto_yield", true)
            .putBoolean("audio_focus_enabled", true).putInt("media_audio_channel", 14).commit()

        assertFalse(VoyahAudioFocusSettings.autoYield(context))
        assertFalse(preferences.getBoolean("audio_focus_auto_yield", true))
        assertTrue(preferences.getBoolean("audio_focus_enabled", false))
        assertEquals(14, preferences.getInt("media_audio_channel", 0))
        assertFalse(VoyahAudioFocusSettings.autoYield(context))
    }
}
