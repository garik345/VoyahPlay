package com.shilapi.xcertplay.voyah

import android.app.Activity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import com.shilapi.xcertplay.AirPlayPersistence
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "w1920dp-h720dp-land-mdpi", manifest = Config.NONE)
class VoyahShadeInsetsTest {
    @Test fun fullScreenReservesBothPhysicalGestureStripsAtDisplayDensity() {
        assertEquals(19, VoyahScreenGeometry.leftGutterPx(0, false, true, 1920, 0, 1920, 1f))
        assertEquals(38, VoyahScreenGeometry.leftGutterPx(0, false, true, 1920, 0, 1920, 2f))
        assertEquals(19, VoyahScreenGeometry.topGutterPx(0, false, true, 0, 720, 1f))
        assertEquals(38, VoyahScreenGeometry.topGutterPx(0, false, true, 0, 720, 2f))
        assertEquals(19, VoyahScreenGeometry.topGutterPx(0, false, true, 0, 530, Float.NaN))
        assertEquals(10, VoyahScreenGeometry.topGutterPx(0, false, true, 0, 40, 3f))
    }
    @Test fun passengerWindowedAndVisibleStatusBarDoNotGainTopInsets() {
        for (display in listOf(1, 2, 3)) assertEquals(0, VoyahScreenGeometry.topGutterPx(display, false, true, 0, 720, 1f))
        assertEquals(0, VoyahScreenGeometry.topGutterPx(0, true, true, 0, 720, 1f))
        assertEquals(0, VoyahScreenGeometry.topGutterPx(0, false, false, 0, 720, 1f))
        assertEquals(0, VoyahScreenGeometry.topGutterPx(0, false, true, 45, 675, 1f))
        assertEquals(0, VoyahScreenGeometry.topGutterPx(0, false, true, 0, 0, 1f))
    }
    @Test fun videoAndTouchShareTheInsetRectangleAndGuttersDoNotAcceptCarPlayTouches() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val host = controller.get()
        AirPlayPersistence.saveHideTopBar(host, true)
        AirPlayPersistence.saveHideBottomBar(host, true)
        host.getSharedPreferences("voyah_tweaks_fullscreen", 0).edit().putBoolean("enabled", true).commit()
        val received = mutableListOf<Pair<Float, Float>>()
        val child = View(host).apply { setOnTouchListener { _, e -> received += e.x to e.y; true } }
        val root = VoyahFullscreenContent.wrap(host, child) as FrameLayout
        root.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1920, 720)
        assertEquals(19, child.left); assertEquals(19, child.top)
        assertEquals(1901, child.width); assertEquals(701, child.height)
        fun down(x: Float, y: Float) = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, x, y, 0).let {
            try { root.dispatchTouchEvent(it) } finally { it.recycle() }
        }
        assertFalse(down(5f, 100f)); assertFalse(down(500f, 10f))
        assertTrue(received.isEmpty())
        assertTrue(down(50f, 90f)); assertEquals(listOf(31f to 71f), received)
        controller.pause().stop().destroy()
    }
}
