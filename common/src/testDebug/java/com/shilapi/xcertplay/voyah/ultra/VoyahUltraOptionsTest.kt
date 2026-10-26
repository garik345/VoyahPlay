package com.shilapi.xcertplay.voyah.ultra

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import com.shilapi.xcertplay.DiPlayActivity
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.ultra.UltraVehicleControl
import com.shilapi.xcertplay.airplay.ultra.VoyahRadioOem
import com.shilapi.xcertplay.voyah.VoyahSettingsProfile
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VoyahUltraOptionsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun prefs(name: String) = app.getSharedPreferences(name, Context.MODE_PRIVATE)
    @Before fun reset() {
        for (name in listOf("voyah_ultra_pilot", "voyah_settings_profile", "xcertplay_airplay")) prefs(name).edit().clear().commit()
    }
    @Test fun ultraSwitchEnablesAndDisablesClimateAndAuthenticationTogether() {
        assertFalse(VoyahUltraSettings.isEnabled(app))
        assertFalse(VoyahUltraSettings.isMutualAuthEnabled(app))
        for (enabled in listOf(true, false)) {
            prefs("voyah_ultra_pilot").edit().putBoolean("mutualAuth", !enabled).commit()
            VoyahUltraSettings.saveEnabled(app, enabled)
            assertEquals(enabled, VoyahUltraSettings.isEnabled(app))
            assertEquals(enabled, VoyahUltraSettings.isMutualAuthEnabled(app))
            assertEquals(enabled, VoyahUltraSettings.carPlayTheme(app) != null)
            assertFalse(prefs("voyah_ultra_pilot").contains("mutualAuth"))
            assertFalse(prefs("voyah_ultra_pilot").contains("themeProbe"))
            assertFalse(prefs("voyah_ultra_pilot").contains("panelProbe"))
            // An old backup cannot turn authentication off independently of Ultra (or on alone).
            prefs("voyah_ultra_pilot").edit().putBoolean("mutualAuth", !enabled)
                .putBoolean("themeProbe", !enabled).putBoolean("panelProbe", !enabled).commit()
            assertEquals(enabled, VoyahUltraSettings.isMutualAuthEnabled(app))
            assertEquals(enabled, VoyahUltraSettings.carPlayTheme(app) != null)
        }
    }
    @Test fun freshUltraAnnouncesTheThemeAndReceiverDisplayWithoutAdbPreferences() {
        assertNull(VoyahUltraSettings.carPlayTheme(app))
        VoyahUltraSettings.saveEnabled(app, true)
        assertFalse(prefs("voyah_ultra_pilot").contains("themeProbe"))
        assertFalse(prefs("voyah_ultra_pilot").contains("panelProbe"))
        val theme = requireNotNull(VoyahUltraSettings.carPlayTheme(app))
        assertEquals("19.0", theme.sdkVersion)
        val display = requireNotNull(VoyahUltraSettings.themeDisplay(app, 60))
        val extension = requireNotNull(VoyahUltraSettings.createExtension(app))
        try {
            val config = AirPlayConfig("Test", "11:22:33:44:55:66", "11:22:33:44:55:66", "950.7.1", display,
                protocolExtension = extension, mutualAuthentication = VoyahUltraSettings.isMutualAuthEnabled(app))
            assertTrue(config.mutualAuthentication)
            val info = AirPlayInfoPlist.build(config)
            val main = (info["displays"] as List<*>).single() as Map<*, *>
            assertEquals("Center_Display", main["uuid"])
            assertEquals(1920, main["widthPixels"])
            assertEquals(720, main["heightPixels"])
            val panels = info["displayPanels"] as List<*>
            assertEquals(2, panels.size)
            val center = panels.map { it as Map<*, *> }.single { it["uid"] == "Center_Display" }
            val stream = (center["initialVideoStreams"] as List<*>).single() as Map<*, *>
            assertEquals(main["uuid"], stream["uuid"])
            assertNotNull(info["vehicleStateProtocolInfo"])
        } finally { (extension as java.io.Closeable).close() }
    }
    @Test fun upgradeKeepsTheClimateChoiceForEveryOldAuthenticationCombination() {
        for (version in listOf(3, 4)) for (enabled in listOf(false, true)) for (auth in listOf(false, true)) {
            prefs("voyah_settings_profile").edit().putInt("version", version).commit()
            prefs("voyah_ultra_pilot").edit().clear().putBoolean("enabled", enabled)
                .putBoolean("mutualAuth", auth).putBoolean("radioEnabled", false)
                .putBoolean("themeProbe", auth).putBoolean("panelProbe", !auth).commit()
            prefs("xcertplay_airplay").edit().putInt("display_width_physical_mm", 400)
                .putString("pairing_id", "paired-phone").commit()
            VoyahSettingsProfile.apply(app)
            assertEquals(enabled, VoyahUltraSettings.isEnabled(app))
            assertEquals(enabled, VoyahUltraSettings.isMutualAuthEnabled(app))
            assertFalse(prefs("voyah_ultra_pilot").contains("mutualAuth"))
            assertEquals(5, prefs("voyah_settings_profile").getInt("version", 0))
            assertFalse(VoyahUltraSettings.isRadioEnabled(app))
            assertFalse(prefs("voyah_ultra_pilot").contains("themeProbe"))
            assertFalse(prefs("voyah_ultra_pilot").contains("panelProbe"))
            assertEquals(enabled, VoyahUltraSettings.carPlayTheme(app) != null)
            assertEquals(400, prefs("xcertplay_airplay").getInt("display_width_physical_mm", 0))
            assertEquals("paired-phone", prefs("xcertplay_airplay").getString("pairing_id", null))
            VoyahSettingsProfile.apply(app)
            assertEquals(enabled, VoyahUltraSettings.isMutualAuthEnabled(app))
        }
    }
    @Test fun radioIsEnabledByDefaultAndDisabledChoiceSurvivesReading() {
        assertTrue(VoyahUltraSettings.isRadioEnabled(app))
        VoyahUltraSettings.saveRadioEnabled(app, false)
        assertFalse(VoyahUltraSettings.isRadioEnabled(app))
        VoyahUltraSettings.saveRadioEnabled(app, true)
        assertTrue(VoyahUltraSettings.isRadioEnabled(app))
    }
    @Test fun aPresentOemServiceCannotEnableTheRadioWhenTheUserDisabledIt() {
        val intent = Intent().setClassName(VoyahRadioOem.PACKAGE, VoyahRadioOem.SERVICE)
        val resolved = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = VoyahRadioOem.PACKAGE; name = VoyahRadioOem.SERVICE
                enabled = true; exported = true
                applicationInfo = ApplicationInfo().apply { enabled = true; packageName = VoyahRadioOem.PACKAGE }
            }
        }
        shadowOf(app.packageManager).addResolveInfoForIntent(intent, resolved)
        val enabled = VoyahUltraSettings.ClimateSource(app)
        assertTrue(enabled.supportsRadio)
        enabled.close()
        VoyahUltraSettings.saveRadioEnabled(app, false)
        val disabled = VoyahUltraSettings.ClimateSource(app)
        assertFalse(disabled.supportsRadio)
        assertTrue(disabled.supportedVehicleControls.containsAll(listOf(UltraVehicleControl.CAR_HOME, UltraVehicleControl.VOYAH_SETTINGS)))
        disabled.close()
    }
    @Test fun oldPacketCaptureCannotRemainEnabledAfterUpgradeOrRestore() {
        prefs("voyah_settings_profile").edit().putInt("version", 2).commit()
        prefs("voyah_ultra_pilot").edit().putBoolean("assetProbe", true).putBoolean("themeProbe", true).putBoolean("radioEnabled", false).commit()
        prefs("xcertplay_airplay").edit().putInt("display_width_physical_mm", 400).putBoolean("hide_top_bar", true).commit()
        VoyahSettingsProfile.apply(app)
        assertFalse(prefs("voyah_ultra_pilot").getBoolean("assetProbe", true))
        assertFalse(prefs("voyah_ultra_pilot").contains("themeProbe"))
        assertNull(VoyahUltraSettings.carPlayTheme(app))
        assertFalse(VoyahUltraSettings.isRadioEnabled(app))
        assertEquals(400, prefs("xcertplay_airplay").getInt("display_width_physical_mm", 0))
        assertTrue(prefs("xcertplay_airplay").getBoolean("hide_top_bar", false))
        prefs("voyah_ultra_pilot").edit().putBoolean("assetProbe", true).commit()
        assertFalse(VoyahUltraSettings.isAssetProbeEnabled(app))
    }
    @Test fun settingsActionHasAnExplicitOwnActivityAndNeverMapsToCan() {
        val intent = requireNotNull(VoyahUltraSettings.appActionIntent(app, UltraVehicleControl.VOYAH_SETTINGS))
        assertEquals(DiPlayActivity::class.java.name, intent.component!!.className)
        assertEquals("settings", intent.getStringExtra("page"))
        assertTrue(intent.getBooleanExtra("from_carplay_settings", false))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
        assertNull(VoyahUltraSettings.appActionIntent(app, UltraVehicleControl.LIGHT_MODE))
    }
}
