package com.shilapi.xcertplay

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec
import com.shilapi.xcertplay.voyah.root.VoyahRootModule
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoyahClusterProbeUiTest {
    @Test fun oldVideoFramesAreNotPresentedAsFramesFromANewOrClosedProbe() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.getSharedPreferences(VoyahClusterProbeActivity.PREFS, 0).edit()
            .putString("token", "old").putLong("frames", 75).commit()
        val report = VoyahClusterProbeSettings.localReport(host, "CLUSTER_PROBE_TOKEN=new\n")
        assertTrue(report.contains("PROBE_ACTIVITY_MATCHES_LEASE=false\nPROBE_MAP_FRAMES=0\n"))
        assertTrue(report.contains("PROBE_LAST_FRAMES=75"))
        assertTrue(VoyahClusterProbeSettings.localReport(host, "CLUSTER_PROBE_TOKEN=old\n").contains("PROBE_MAP_FRAMES=75"))
        assertTrue(VoyahClusterProbeSettings.localReport(host).contains("PROBE_MAP_FRAMES=0"))
        host.finish()
    }
    @Test fun mapTestDoesNotEnableAStreamOrReconnectWithoutAnExplicitSettingChange() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.setTheme(R.style.Theme_Xcertplay)
        AirPlayPersistence.saveClusterMapEnabled(host, false)
        VoyahClusterProbeSettings.run(host, VoyahRootModule.ClusterProbeAction.MAP)
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(host))
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull(dialog)
        dialog.dismiss(); host.finish()
    }

    @Test fun voyahProbeOffersTheStreamSettingEvenThoughTheBydCardIsHidden() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.setTheme(R.style.Theme_Xcertplay)
        AirPlayPersistence.saveClusterMapEnabled(host, false)
        assertFalse(com.shilapi.xcertplay.voyah.VoyahSettingsVisibility.isVisible("CLUSTER_MAP"))
        val content = LinearLayout(host)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "renderSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
            ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.VOYAH_CLUSTER_PROBE)))
        val control = descendants(content).filterIsInstance<Switch>().single()
        assertFalse(control.isChecked)
        control.isChecked = true
        assertTrue(AirPlayPersistence.loadClusterMapEnabled(host))
        control.isChecked = false
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(host))
        host.finish()
    }

    @Test fun elementSizeRequiresExplicitSaveAndDoesNotChangeTheMainScreenSize() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.setTheme(R.style.Theme_Xcertplay)
        AirPlayPersistence.saveVoyahClusterElementSize(host, 100)
        val mainWidth = AirPlayPersistence.loadWidthPhysicalMm(host)
        val content = LinearLayout(host)
        ReflectionHelpers.callInstanceMethod<Unit>(host, "renderSections",
            ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
            ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.VOYAH_CLUSTER_PROBE)))
        val button = descendants(content).filterIsInstance<Button>().single {
            it.text.startsWith(host.getString(R.string.settings_cluster_map_element_size))
        }
        button.performClick()
        var dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(dialog).clickOnItem(2)
        dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(100, AirPlayPersistence.loadVoyahClusterElementSize(host))
        button.performClick()
        dialog = ShadowAlertDialog.getLatestAlertDialog()
        shadowOf(dialog).clickOnItem(2)
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(125, AirPlayPersistence.loadVoyahClusterElementSize(host))
        assertEquals(mainWidth, AirPlayPersistence.loadWidthPhysicalMm(host))
        assertTrue(button.text.contains(host.getString(R.string.settings_cluster_map_size_value, 125)))
        AirPlayPersistence.saveVoyahClusterElementSize(host, 100)
        host.finish()
    }

    @Test fun zoomWithoutAVisibleMapDoesNotStartAnOutputOrEnableAStream() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        AirPlayPersistence.saveClusterMapEnabled(host, false)
        VoyahClusterProbeSettings.zoom(host, true)
        assertFalse(AirPlayPersistence.loadClusterMapEnabled(host))
        assertEquals(host.getString(R.string.settings_cluster_map_zoom_unavailable), ShadowToast.getTextOfLatestToast())
        val report = VoyahClusterProbeSettings.localReport(host)
        assertTrue(report.contains("PROBE_ZOOM_DIRECTION=in"))
        assertTrue(report.contains("PROBE_ZOOM_SENT=false"))
        assertTrue(report.contains("PROBE_ZOOM_REASON=no-active-session"))
        host.finish()
    }

    @Test fun anExportedProbeCannotRenderOnTheMainDisplayEvenWithWellFormedExtras() {
        val intent = Intent().putExtra(VoyahClusterProbeSpec.TOKEN, "01234567-89ab-cdef-0123-456789abcdef")
            .putExtra(VoyahClusterProbeSpec.MODE, "grid")
            .putExtra(VoyahClusterProbeSpec.DEADLINE, SystemClock.elapsedRealtime() + 60000)
        val controller = Robolectric.buildActivity(VoyahClusterProbeActivity::class.java, intent).create()
        assertTrue(controller.get().isFinishing)
        controller.destroy()
    }

    @Test fun controlsLiveOnlyInAdvancedAndFitLargeFontCompactFullAndRtlWindows() {
        assertEquals(setOf(SettingsCategory.ADVANCED), SettingsInformationArchitecture.sectionsByCategory
            .filterValues { SettingsSection.VOYAH_CLUSTER_PROBE in it }.keys)
        for ((name, widthDp, dpi, language) in listOf(
            listOf("compact-ru", "320", "160", "ru-RU"),
            listOf("full-ru", "1000", "320", "ru-RU"),
            listOf("compact-ar", "320", "160", "ar-SA"),
        )) {
            val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            host.setTheme(R.style.Theme_Xcertplay)
            host.applicationInfo.flags = host.applicationInfo.flags or android.content.pm.ApplicationInfo.FLAG_SUPPORTS_RTL
            val config = Configuration(host.resources.configuration).apply {
                fontScale = 1.5f; densityDpi = dpi.toInt(); screenWidthDp = widthDp.toInt(); screenHeightDp = 1200
                setLocale(Locale.forLanguageTag(language)); setLayoutDirection(Locale.forLanguageTag(language))
            }
            @Suppress("DEPRECATION")
            host.resources.updateConfiguration(config, host.resources.displayMetrics)
            val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL; layoutDirection = config.layoutDirection }
            ReflectionHelpers.callInstanceMethod<Unit>(host, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.VOYAH_CLUSTER_PROBE)))
            val buttons = descendants(content).filterIsInstance<Button>().filterNot { it is Switch }
            assertEquals(8, buttons.size)
            assertEquals(1, descendants(content).filterIsInstance<Switch>().size)
            assertTrue(buttons.all { it.isEnabled })
            val width = (widthDp.toInt() * host.resources.displayMetrics.density).toInt()
            screenshot(content, width, "settings-$name")
            for (button in buttons) {
                assertTrue(button.height >= button.minHeight)
                val text = button.layout
                assertTrue("Button text must fit $name: ${button.text}",
                    text.getLineBottom(text.lineCount - 1) + button.compoundPaddingTop + button.compoundPaddingBottom <= button.height)
            }
            host.finish()
        }
    }

    @Test fun gridRendersFourColoursAndAVisibleBorderInTheMeasuredCard() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val grid = VoyahClusterProbeGrid(host, SystemClock.elapsedRealtime() + 60000)
        grid.measure(View.MeasureSpec.makeMeasureSpec(574, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(464, View.MeasureSpec.EXACTLY))
        grid.layout(0, 0, 574, 464)
        val bitmap = Bitmap.createBitmap(574, 464, Bitmap.Config.ARGB_8888)
        try {
            grid.draw(Canvas(bitmap))
            assertEquals(Color.RED, bitmap.getPixel(50, 50)); assertEquals(Color.GREEN, bitmap.getPixel(500, 50))
            assertEquals(Color.BLUE, bitmap.getPixel(50, 400)); assertEquals(Color.YELLOW, bitmap.getPixel(500, 400))
            save(bitmap, "grid")
        } finally { bitmap.recycle(); host.finish() }
    }

    private fun screenshot(view: View, width: Int, name: String) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        view.layout(0, 0, width, view.measuredHeight)
        val bitmap = Bitmap.createBitmap(width, view.height, Bitmap.Config.ARGB_8888)
        try { view.draw(Canvas(bitmap)); save(bitmap, name) } finally { bitmap.recycle() }
    }
    private fun save(bitmap: Bitmap, name: String) {
        val file = File("build/reports/voyah-cluster-probe-ui/$name.png").apply { parentFile?.mkdirs() }
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
    private fun descendants(view: View): List<View> = buildList {
        add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(descendants(view.getChildAt(i)))
    }
}
