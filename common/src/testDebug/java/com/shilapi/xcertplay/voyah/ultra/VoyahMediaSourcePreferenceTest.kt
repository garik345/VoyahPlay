package com.shilapi.xcertplay.voyah.ultra

import com.shilapi.xcertplay.airplay.ultra.UltraMediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahMediaSourcePreferenceTest {
    @Test fun aFreshInstallAndUnknownOrMalformedPreferencesDoNotDefaultToRadio() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("source-test", 0)
        prefs.edit().clear().commit()
        val source = VoyahMediaSourcePreference(prefs)
        assertEquals(UltraMediaSource.CARPLAY, source.read())
        prefs.edit().putString("lastSource", "SIRI").commit()
        assertEquals(UltraMediaSource.CARPLAY, source.read())
        prefs.edit().putBoolean("lastSource", true).commit()
        assertEquals(UltraMediaSource.CARPLAY, source.read())
        source.save(UltraMediaSource.RADIO)
        assertEquals(UltraMediaSource.RADIO, source.read())
    }
    @Test fun bothSourcesSurviveANewAdapterAndUnrelatedVolumeSettingsAreUntouched() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("source-persistent-test", 0)
        prefs.edit().clear().putInt("volume", 7).commit()
        for (choice in listOf(UltraMediaSource.RADIO, UltraMediaSource.CARPLAY)) {
            VoyahMediaSourcePreference(prefs).save(choice)
            assertEquals(choice, VoyahMediaSourcePreference(prefs).read())
            assertEquals(7, prefs.getInt("volume", 0))
        }
    }
}
