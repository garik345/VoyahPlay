package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.settings.SettingsTheme
import com.shilapi.xcertplay.voyah.VoyahComfortLayout
import com.shilapi.xcertplay.voyah.VoyahComfortSettings
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoyahPiEquipmentUiTest {
    @Test fun unavailableWheelIsAbsentFromSettingsChoicesAndTheShortcutStillWorks() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.setTheme(R.style.Theme_Xcertplay)
        host.getSharedPreferences(VoyahComfortLayout.PREFS, 0).edit()
            .putString("comfort_settings_key", "wheel").commit()
        val content = LinearLayout(host)
        VoyahComfortSettings.add(host, content, SettingsTheme.CARD)
        val shortcut = buttons(content).single { it.text.startsWith("Открывать настройки удержанием") }
        assertTrue(shortcut.text.contains("Подогрев водителя"))
        assertEquals("heat_l", VoyahComfortLayout.settingsKey(host))
        shortcut.performClick()
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        val adapter = dialog.listView.adapter
        val choices = (0 until adapter.count).map { adapter.getItem(it).toString() }
        assertEquals(4, choices.size)
        assertFalse(choices.any { it.contains("руля") })
        dialog.dismiss()
        host.finish()
    }

    @Test fun equipmentFilteredSettingsRenderInCompactFullAndRtlWindowsAtLargeFont() {
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
            val content = LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL; layoutDirection = config.layoutDirection
                setBackgroundColor(Color.rgb(14, 18, 26))
            }
            VoyahComfortSettings.add(host, content, SettingsTheme.CARD)
            assertTrue(buttons(content).none { it.text.contains("руля") })
            val width = (widthDp.toInt() * host.resources.displayMetrics.density).toInt()
            content.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            content.layout(0, 0, width, content.measuredHeight)
            val bitmap = Bitmap.createBitmap(width, content.height, Bitmap.Config.ARGB_8888)
            try {
                content.draw(Canvas(bitmap))
                val output = File("build/reports/voyah-pi-equipment-ui/$name.png").apply { parentFile?.mkdirs() }
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            host.finish()
        }
    }

    private fun buttons(view: View): List<Button> = buildList {
        if (view is Button) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(buttons(view.getChildAt(i)))
    }
}
