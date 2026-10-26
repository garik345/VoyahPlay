package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings
import java.io.File
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoyahUltraOptionsScreenshotsTest {
    private val screenshotTopGapDp = 24
    @Test fun climateAndAuthenticationShareOneSwitchAcrossLayoutsAndLocales() {
        assertEquals(setOf(SettingsCategory.ADVANCED), SettingsInformationArchitecture.sectionsByCategory
            .filterValues { SettingsSection.ULTRA_CLIMATE in it }.keys)
        for ((name, widthDp, dpi, language) in listOf(
            listOf("full-ru", "1000", "320", "ru-RU"),
            listOf("compact-ru", "320", "160", "ru-RU"),
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
            val content = LinearLayout(host).apply { orientation = LinearLayout.VERTICAL }
            ReflectionHelpers.callInstanceMethod<Unit>(host, "renderSections",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, content),
                ReflectionHelpers.ClassParameter.from(Set::class.java, setOf(SettingsSection.ULTRA_CLIMATE)))
            val texts = texts(content)
            assertEquals(1, texts.count { it == host.getString(R.string.voyah_ultra_toggle) })
            assertEquals(1, texts.count { it == host.getString(R.string.settings_ultra_radio) })
            assertFalse(texts.contains(host.getString(R.string.voyah_ultra_asset_probe_toggle)))
            assertTrue(VoyahUltraSettings.isRadioEnabled(host))
            val switches = switches(content)
            assertEquals("Only the combined Ultra switch and the independent Radio switch", 2, switches.size)
            switches.first().isChecked = true
            assertTrue(VoyahUltraSettings.isEnabled(host))
            assertTrue(VoyahUltraSettings.isMutualAuthEnabled(host))
            assertNotNull(VoyahUltraSettings.carPlayTheme(host))
            switches.first().isChecked = false
            assertFalse(VoyahUltraSettings.isEnabled(host))
            assertFalse(VoyahUltraSettings.isMutualAuthEnabled(host))
            assertNull(VoyahUltraSettings.carPlayTheme(host))
            switches.first().isChecked = true
            val root = ScrollView(host).apply {
                setBackgroundColor(Color.rgb(14, 18, 26)); isVerticalScrollBarEnabled = false
                addView(content); layoutDirection = configuration.layoutDirection
            }
            val density = host.resources.displayMetrics.density
            val width = (widthDp.toInt() * density).toInt(); val height = (640 * density).toInt()
            repeat(3) {
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            if (widthDp.toInt() <= 400) {
                val ultra = requireNotNull(findText(content, host.getString(R.string.voyah_ultra_toggle)))
                val bounds = Rect().also(ultra::getDrawingRect)
                root.offsetDescendantRectToMyCoords(ultra, bounds)
                root.scrollTo(0, (bounds.top - screenshotTopGapDp * density).toInt().coerceAtLeast(0))
                assertTrue("Combined Ultra row must be visible in compact $name: top=${bounds.top}, scroll=${root.scrollY}, child=${content.height}",
                    root.scrollY > 0 && bounds.top - root.scrollY in 0 until height)
            }
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                // This detached render has no parent to apply the ScrollView's scroll offset.
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.rgb(14, 18, 26))
                canvas.translate(0f, -root.scrollY.toFloat())
                content.draw(canvas)
                val output = File("build/reports/voyah-ultra-options-ui/$name.png").apply { parentFile?.mkdirs() }
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
            host.finish()
        }
    }
    private fun texts(view: View): List<String> = buildList {
        if (view is TextView) add(view.text.toString())
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(texts(view.getChildAt(i)))
    }
    private fun switches(view: View): List<Switch> = buildList {
        if (view is Switch) add(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) addAll(switches(view.getChildAt(i)))
    }
    private fun findText(view: View, wanted: String): TextView? {
        if (view is TextView && view.text.toString() == wanted) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findText(view.getChildAt(i), wanted)?.let { return it }
        return null
    }
}
