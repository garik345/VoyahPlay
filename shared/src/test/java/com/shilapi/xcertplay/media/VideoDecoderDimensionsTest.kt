package com.shilapi.xcertplay.media

import android.view.Surface
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verify the constructed decoder, not just a size-selection helper. No codec or worker starts. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VideoDecoderDimensionsTest {
    private fun dimensions(sink: AndroidMediaSink, type: Int, mirror: Boolean): Pair<Int, Int> {
        val method = sink.javaClass.getDeclaredMethod("newVideoDecoder", Int::class.javaPrimitiveType,
            Surface::class.java, String::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        val decoder = method.invoke(sink, type, null, if (mirror) " stream=$type mirror=test" else null, false)
        fun field(name: String) = decoder.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(decoder)
        return field("width") to field("height")
    }

    @Test fun clusterAndItsMirrorsUseTheirDeclaredSizeAtEveryElementScale() {
        for (size in listOf(760 to 616, 576 to 464, 456 to 368, 384 to 312)) {
            val sink = AndroidMediaSink(videoWidth = 1920, videoHeight = 720, clusterVideoSize = size)
            try {
                assertEquals(size, dimensions(sink, 111, false))
                assertEquals(size, dimensions(sink, 111, true))
                assertEquals(1920 to 720, dimensions(sink, 110, false))
                assertEquals(1920 to 720, dimensions(sink, 110, true))
            } finally { sink.close() }
        }
    }

    @Test fun callersWithoutAClusterSizeKeepTheExistingDimensions() {
        val sink = AndroidMediaSink(videoWidth = 1280, videoHeight = 720)
        try {
            assertEquals(1280 to 720, dimensions(sink, 111, false))
            assertEquals(1280 to 720, dimensions(sink, 111, true))
        } finally { sink.close() }
    }

    @Test fun invalidClusterDimensionsAreRejectedBeforeOpeningResources() {
        for (size in listOf(0 to 464, 576 to 0, -1 to 464)) {
            try { AndroidMediaSink(clusterVideoSize = size); fail("Invalid dimensions accepted: $size") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
