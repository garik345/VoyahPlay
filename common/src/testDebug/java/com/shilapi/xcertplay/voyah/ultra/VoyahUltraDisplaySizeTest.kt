package com.shilapi.xcertplay.voyah.ultra

import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class VoyahUltraDisplaySizeTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun setup() {
        context.getSharedPreferences("voyah_ultra_pilot", 0).edit().clear().commit()
        VoyahUltraSettings.saveEnabled(context, true)
    }
    @Test fun everySizeKeepsThemePixelsButChangesTheDensityAdvertisedToIphone() {
        for ((width, height) in listOf(250 to 94, 300 to 113, 350 to 131, 400 to 150)) {
            AirPlayPersistence.saveWidthPhysicalMm(context, width)
            val display = requireNotNull(VoyahUltraSettings.themeDisplay(context, 60))
            assertEquals(1920, display.widthPixels); assertEquals(720, display.heightPixels)
            val info = AirPlayInfoPlist.build(AirPlayConfig("Test", "11:22:33:44:55:66", "11:22:33:44:55:66", "950.7.1", display))
            val main = (info["displays"] as List<*>).first() as Map<*, *>
            assertEquals(width, main["widthPhysical"]); assertEquals(height, main["heightPhysical"])
            assertEquals(60, main["maxFPS"])
        }
    }
    @Test fun legacyThemeFlagsCannotDisableTheIntegratedProfileOrEnableItWithoutUltra() {
        context.getSharedPreferences("voyah_ultra_pilot", 0).edit().putBoolean("themeProbe", false).commit()
        assertNotNull(VoyahUltraSettings.themeDisplay(context, 60))
        VoyahUltraSettings.saveEnabled(context, false)
        context.getSharedPreferences("voyah_ultra_pilot", 0).edit()
            .putBoolean("themeProbe", true).putBoolean("panelProbe", true).commit()
        assertNull(VoyahUltraSettings.themeDisplay(context, 60))
        assertNull(VoyahUltraSettings.createExtension(context))
    }
}
