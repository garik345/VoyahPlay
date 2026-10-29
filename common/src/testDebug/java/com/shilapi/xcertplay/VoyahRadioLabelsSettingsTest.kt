package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.Switch
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.voyah.root.RadioLabelsSettings
import com.shilapi.xcertplay.voyah.root.VoyahRootModule
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahRadioLabelsSettingsTest {
    @Test fun rootHelperCardRemainsInAdvancedAndTheTogglePersistsOnlyAcknowledgedState() {
        assertEquals(setOf(SettingsCategory.ADVANCED), SettingsInformationArchitecture.sectionsByCategory
            .filterValues { SettingsSection.VOYAH_HELPER in it }.keys)
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.setTheme(R.style.Theme_Xcertplay)
        host.getSharedPreferences("voyah_radio_labels", 0).edit().clear().commit()
        val content = LinearLayout(host)
        val replies = mutableListOf<VoyahRootModule.Callback>()
        val requests = mutableListOf<Boolean>()
        RadioLabelsSettings.add(host, content, SettingsTheme.CARD, status = {}, change = { value, reply ->
            requests += value; replies += reply
        })
        val switch = switches(content).single()
        assertFalse(switch.isChecked)
        switch.isChecked = true
        assertEquals(listOf(true), requests)
        assertFalse(switch.isEnabled)
        assertFalse(host.getSharedPreferences("voyah_radio_labels", 0).getBoolean("enabled", false))
        replies.removeAt(0).complete(true, "RADIO_LABELS_ENABLED=1\n")
        assertTrue(switch.isChecked)
        assertTrue(host.getSharedPreferences("voyah_radio_labels", 0).getBoolean("enabled", false))
        switch.isChecked = false
        replies.removeAt(0).complete(true, "RADIO_LABELS_ENABLED=0\n")
        assertFalse(switch.isChecked)
        assertTrue(switch.isEnabled)
        assertEquals(listOf(true, false), requests)
        host.finish()
    }

    @Test fun theNewSettingFitsCompactFullAndRtlLayoutsAtLargeFontScale() {
        for ((name, widthDp, dpi, language) in listOf(
            listOf("compact-ru", "320", "160", "ru-RU"),
            listOf("full-ru", "1000", "320", "ru-RU"),
            listOf("compact-ar", "320", "160", "ar-SA"),
        )) {
            val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
            host.setTheme(R.style.Theme_Xcertplay)
            val configuration = Configuration(host.resources.configuration).apply {
                fontScale = 1.5f; densityDpi = dpi.toInt(); screenWidthDp = widthDp.toInt(); screenHeightDp = 640
                setLocale(Locale.forLanguageTag(language)); setLayoutDirection(Locale.forLanguageTag(language))
            }
            @Suppress("DEPRECATION")
            host.resources.updateConfiguration(configuration, host.resources.displayMetrics)
            val content = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = configuration.layoutDirection
                setBackgroundColor(Color.rgb(14, 18, 26))
            }
            RadioLabelsSettings.add(host, content, SettingsTheme.CARD, status = {}, change = { _, _ -> })
            val density = host.resources.displayMetrics.density
            val width = (widthDp.toInt() * density).toInt()
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            content.layout(0, 0, width, content.measuredHeight)
            assertTrue("Row must fit $name", content.height in 1..(640 * density).toInt())
            val bitmap = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
            try {
                content.draw(Canvas(bitmap))
                val output = File("build/reports/voyah-radio-labels-ui/$name.png").apply { parentFile?.mkdirs() }
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            host.finish()
        }
    }
    private fun switches(view: View): List<Switch> = buildList {
        if (view is Switch) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(switches(view.getChildAt(i)))
    }
}
