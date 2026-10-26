package com.shilapi.xcertplay

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import com.shilapi.xcertplay.host.R
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
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class VoyahCarPlaySizeScreenshotsTest {
    @Test fun extraSmallCanBeSelectedAndDialogFitsLargeFontCompactFullAndRtl() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        host.setTheme(R.style.Theme_Xcertplay)
        for ((name, widthDp, densityDpi, tag) in listOf(
            listOf("full-ru", "600", "320", "ru-RU"),
            listOf("compact-ru", "320", "160", "ru-RU"),
            listOf("compact-ar", "320", "160", "ar-SA"),
        )) {
            val configuration = Configuration(host.resources.configuration).apply {
                fontScale = 1.5f; this.densityDpi = densityDpi.toInt()
                screenWidthDp = widthDp.toInt(); screenHeightDp = 600
                setLocale(Locale.forLanguageTag(tag)); setLayoutDirection(Locale.forLanguageTag(tag))
            }
            @Suppress("DEPRECATION")
            host.resources.updateConfiguration(configuration, host.resources.displayMetrics)
            AirPlayPersistence.saveWidthPhysicalMm(host, 350)
            val parent = LinearLayout(host)
            ReflectionHelpers.callInstanceMethod<Unit>(host, "carPlaySizeControl",
                ReflectionHelpers.ClassParameter.from(LinearLayout::class.java, parent))
            parent.getChildAt(0).performClick()
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            val shadow = shadowOf(dialog)
            assertEquals(4, shadow.items.size)
            shadow.clickOnItem(3)
            val decor = dialog.window!!.decorView
            decor.layoutDirection = configuration.layoutDirection
            val density = host.resources.displayMetrics.density
            val width = (widthDp.toInt() * density).toInt(); val height = (600 * density).toInt()
            repeat(3) {
                decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                decor.layout(0, 0, width, height)
            }
            clearDetachedScrollIndicators(decor)
            val image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                decor.draw(Canvas(image))
                val output = File("build/reports/voyah-size-ui/$name.png").apply { parentFile.mkdirs() }
                output.outputStream().use { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { image.recycle() }
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(400, AirPlayPersistence.loadWidthPhysicalMm(host))
            assertTrue((parent.getChildAt(0) as Button).text.toString().contains(host.getString(R.string.settings_carplay_size_extra_small)))
        }
        host.finish()
    }

    // Platform scrolling widgets otherwise access a ViewRoot that this render harness does not attach.
    private fun clearDetachedScrollIndicators(view: View) {
        view.scrollIndicators = 0
        if (view is ViewGroup) for (index in 0 until view.childCount) clearDetachedScrollIndicators(view.getChildAt(index))
    }
}
