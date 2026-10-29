package com.shilapi.xcertplay

import android.graphics.Matrix
import android.graphics.RectF
import android.view.View
import com.shilapi.xcertplay.airplay.CarPlayClusterDisplay
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec as Spec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahClusterMapLayoutTest {
    @Test fun theHostPassesTheNegotiatedClusterSizeToItsMediaSink() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val stream = VoyahClusterMapLayout.stream(125)
        val size = stream.widthPixels to stream.heightPixels
        val factory = host.javaClass.getDeclaredMethod("createMediaSink", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Pair::class.java).apply { isAccessible = true }
        val sink = factory.invoke(host, 1920, 720, 0, size) as com.shilapi.xcertplay.media.AndroidMediaSink
        try {
            val actual = sink.javaClass.getDeclaredField("clusterVideoSize").apply { isAccessible = true }.get(sink)
            assertEquals(size, actual)
        } finally { sink.close(); host.finish() }
    }

    @Test fun advertisedNavigationAreaUsesTheWholeFrameAndSymmetricMarginsAtEveryScale() {
        for (size in VoyahClusterMapLayout.elementSizes) {
            val stream = VoyahClusterMapLayout.stream(size)
            val info = AirPlayInfoPlist.build(AirPlayConfig("test", "test", "", "1",
                AirPlayDisplayConfig(1920, 720), cluster = stream))
            val display = (info["displays"] as List<*>).filterIsInstance<Map<*, *>>()
                .single { (it["type"] as Number).toInt() == 111 }
            val area = (display["viewAreas"] as List<*>).single() as Map<*, *>
            assertEquals(stream.widthPixels, area["widthPixels"])
            assertEquals(stream.heightPixels, area["heightPixels"])
            assertEquals(0, area["originXPixels"])
            assertEquals(0, area["originYPixels"])
            val safe = area["safeArea"] as Map<*, *>
            val safeCenter = (safe["originXPixels"] as Number).toDouble() + (safe["widthPixels"] as Number).toDouble() / 2
            assertEquals(stream.widthPixels / 2.0, safeCenter, .5)
        }
    }

    @Test fun fillingTheCardKeepsTheSourceCenterAtTheCardCenter() {
        for (aspect in listOf(16.0 / 9, 576.0 / 464, 384.0 / 312, 1.0, .5)) {
            val center = floatArrayOf(Spec.WIDTH / 2f, Spec.HEIGHT / 2f)
            VoyahClusterMapLayout.cover(Spec.WIDTH, Spec.HEIGHT, aspect).mapPoints(center)
            assertEquals(Spec.WIDTH / 2f, center[0], .001f)
            assertEquals(Spec.HEIGHT / 2f, center[1], .001f)
        }
    }

    @Test fun theDefaultStreamUsesTheCardShapeInsteadOfA16By9Canvas() {
        val stream = VoyahClusterMapLayout.stream(100)
        assertEquals(576, stream.widthPixels)
        assertEquals(464, stream.heightPixels)
        assertEquals(CarPlayClusterDisplay.MAP_URL, stream.initialUrl)
        assertEquals(0, stream.primaryInputDevice)
        assertTrue(kotlin.math.abs(stream.widthPixels.toDouble() / stream.heightPixels - Spec.WIDTH.toDouble() / Spec.HEIGHT) < .01)
    }

    @Test fun largerElementsRequestFewerPixelsWithTheSameCardShape() {
        var previous = Int.MAX_VALUE
        for (size in VoyahClusterMapLayout.elementSizes) {
            val stream = VoyahClusterMapLayout.stream(size)
            assertTrue(stream.widthPixels < previous)
            previous = stream.widthPixels
            assertEquals(0, stream.widthPixels % 8)
            assertEquals(0, stream.heightPixels % 8)
            assertTrue(kotlin.math.abs(stream.widthPixels.toDouble() / stream.heightPixels - Spec.WIDTH.toDouble() / Spec.HEIGHT) < .02)
        }
        assertEquals(384, VoyahClusterMapLayout.stream(150).widthPixels)
        assertEquals(312, VoyahClusterMapLayout.stream(150).heightPixels)
        assertEquals(VoyahClusterMapLayout.stream(100), VoyahClusterMapLayout.stream(Int.MAX_VALUE))
    }

    @Test fun aMapOfAnyNegotiatedShapeCoversBothEdgesWithoutStretching() {
        for (aspect in listOf(16.0 / 9, 576.0 / 464, 384.0 / 312, 1.0, 4.0, .5)) {
            val bounds = RectF(0f, 0f, Spec.WIDTH.toFloat(), Spec.HEIGHT.toFloat())
            VoyahClusterMapLayout.cover(Spec.WIDTH, Spec.HEIGHT, aspect).mapRect(bounds)
            assertTrue(bounds.left <= .01f && bounds.top <= .01f)
            assertTrue(bounds.right >= Spec.WIDTH - .01f && bounds.bottom >= Spec.HEIGHT - .01f)
            assertEquals(aspect, bounds.width().toDouble() / bounds.height(), .0001)
        }
    }

    @Test fun theActualTextureAndLabelFillTheSameClippedCardAsTheGrid() {
        val host = Robolectric.buildActivity(DiPlayActivity::class.java).get()
        val old = MapMirrors.STREAM_ASPECT
        try {
            MapMirrors.streamAspect = 16.0 / 9
            val card = VoyahClusterMapView(host)
            card.measure(View.MeasureSpec.makeMeasureSpec(Spec.WIDTH, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(Spec.HEIGHT, View.MeasureSpec.EXACTLY))
            card.layout(0, 0, Spec.WIDTH, Spec.HEIGHT)
            assertEquals(Spec.WIDTH, card.texture.width)
            assertEquals(Spec.HEIGHT, card.texture.height)
            assertEquals(Spec.HEIGHT, card.waiting.height)
            assertTrue(card.clipChildren)
            val bounds = RectF(0f, 0f, card.texture.width.toFloat(), card.texture.height.toFloat())
            card.texture.getTransform(Matrix()).mapRect(bounds)
            assertTrue(bounds.height() >= Spec.HEIGHT - .01f)
        } finally { MapMirrors.streamAspect = old; host.finish() }
    }
}
