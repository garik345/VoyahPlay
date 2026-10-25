package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import com.shilapi.xcertplay.host.R
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

/** Render the real projection preparation screen, including its settings entry hint at large font. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoyahProjectionScreenshotsTest {
    @Test fun hiddenComfortPanelKeepsSettingsAccessibleInCompactFullAndRtlLayouts() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        host.setTheme(R.style.Theme_Xcertplay)
        host.getSharedPreferences("voyah_vehicle", 0).edit().putBoolean("comfort_bar_enabled", false).commit()
        for ((name, widthDp, heightDp, language) in listOf(
            listOf("full-ru", "1000", "500", "ru-RU"),
            listOf("compact-ru", "320", "640", "ru-RU"),
            listOf("compact-ar", "320", "640", "ar-SA"),
        )) {
            val locale = Locale.forLanguageTag(language)
            val configuration = Configuration(host.resources.configuration).apply {
                fontScale = 1.5f
                densityDpi = if (name == "full-ru") 320 else 160
                screenWidthDp = widthDp.toInt()
                screenHeightDp = heightDp.toInt()
                setLocale(locale)
                setLayoutDirection(locale)
            }
            @Suppress("DEPRECATION")
            host.resources.updateConfiguration(configuration, host.resources.displayMetrics)
            val root = ReflectionHelpers.callInstanceMethod<View>(host, "buildContentView")
            val density = host.resources.displayMetrics.density
            val width = (widthDp.toInt() * density).toInt()
            val height = (heightDp.toInt() * density).toInt()
            // The first layout adjusts preparation fonts and button sizes to the viewport.
            // An unattached test view needs the follow-up traversals Android normally schedules.
            repeat(4) {
                forceMeasure(root)
                root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                root.layout(0, 0, width, height)
            }
            assertEquals(0, com.shilapi.xcertplay.voyah.VoyahComfortLayout.insets(host).left)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                val output = File("build/reports/voyah-projection-ui/$name.png")
                output.parentFile.mkdirs()
                output.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { bitmap.recycle() }
        }
        host.finish()
    }

    private fun forceMeasure(view: View) {
        view.forceLayout()
        if (view is ViewGroup) for (i in 0 until view.childCount) forceMeasure(view.getChildAt(i))
    }
}
