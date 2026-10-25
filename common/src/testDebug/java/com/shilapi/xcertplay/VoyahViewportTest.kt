package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.CarPlayVideoLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class VoyahViewportTest {
    @Test fun ultraUsesTheWholeViewportForVideoAndTouchesButNormalCarPlayKeepsItsAspect() {
        val host = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        val display = CarPlaySessionDisplay(1920, 720, 0, false, false, 1775, 675, fillViewport = true)
        for ((width, height) in listOf(1775 to 675, 1666 to 675, 640 to 480, 1920 to 720)) {
            ReflectionHelpers.setField(host, "sessionDisplay", display)
            val rect = ReflectionHelpers.callInstanceMethod<CarPlayVideoLayout>(host, "contentRect",
                ClassParameter.from(Int::class.javaPrimitiveType, width),
                ClassParameter.from(Int::class.javaPrimitiveType, height))
            assertEquals(CarPlayVideoLayout(0f, 0f, width.toFloat(), height.toFloat()), rect)
            assertTrue(rect.contains(0f, 0f))
            assertTrue(rect.contains(width.toFloat(), height.toFloat()))
        }
        ReflectionHelpers.setField(host, "sessionDisplay", display.copy(fillViewport = false))
        val normal = ReflectionHelpers.callInstanceMethod<CarPlayVideoLayout>(host, "contentRect",
            ClassParameter.from(Int::class.javaPrimitiveType, 1666),
            ClassParameter.from(Int::class.javaPrimitiveType, 675))
        assertTrue(normal.top > 0f)
        assertEquals(1920f / 720, normal.width / normal.height, .0001f)
    }
}
