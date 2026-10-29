package com.shilapi.xcertplay

import android.os.Handler
import android.view.Surface
import android.widget.FrameLayout
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import java.util.concurrent.ExecutorService
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.util.concurrent.PausedExecutorService
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahCarPlayReturnTest {
    private lateinit var activity: CarPlayHostActivity
    private lateinit var controller: CarPlayController
    private lateinit var sink: AndroidMediaSink
    private val displaySize = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")

    @Before fun setup() {
        activity = Robolectric.buildActivity(CarPlayHostActivity::class.java).get()
        activity.getSharedPreferences("xcertplay_airplay", 0).edit().clear().commit()
        CarPlayBackgroundSession.clear()
        controller = mock(CarPlayController::class.java)
        sink = mock(AndroidMediaSink::class.java)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        set("teardownExecutor", PausedExecutorService())
        set("controller", controller)
        set("sink", sink)
        set("isActivityStarted", true)
        set("videoView", FrameLayout(activity))
        set("activeDisplaySize", size(1901, 701))
        set("sessionDisplay", display())
        CarPlayBackgroundSession.store(controller, sink, 1901, 701, activity, display()) { }
    }

    @After fun cleanup() {
        (field("mainHandler") as Handler).removeCallbacksAndMessages(null)
        (field("teardownExecutor") as ExecutorService).shutdownNow()
        (field("airPlayCommandExecutor") as ExecutorService).shutdownNow()
        CarPlayBackgroundSession.clear()
    }

    @Test fun ultraKeepsTheLiveSessionAcrossOemBarsAndLoweredScreenWithEitherPipSetting() {
        for (adapt in listOf(true, false)) {
            AirPlayPersistence.saveAdaptPipResolution(activity, adapt)
            for ((width, height) in listOf(1775 to 675, 1920 to 720, 1901 to 701,
                1775 to 485, 1920 to 530, 1901 to 511, 1901 to 701)) {
                invoke("applyDisplaySize", size(width, height))
                assertSame("Window $width x $height (adapt=$adapt) must keep the connection", controller, field("controller"))
                assertSame(sink, field("sink"))
                assertEquals(0, field("restartGeneration"))
                assertEquals(false, field("handshakeResetInProgress"))
                assertEquals(size(width, height), field("activeDisplaySize"))
            }
        }
    }

    @Test fun anUltraSessionStartedBeforeFullscreenDoesNotReconnectWhenTheWindowGrows() {
        set("sessionDisplay", display(windowWidth = 1775, windowHeight = 675))
        set("activeDisplaySize", size(1775, 675))
        AirPlayPersistence.saveAdaptPipResolution(activity, false)
        invoke("applyDisplaySize", size(1901, 701))
        assertSame(controller, field("controller"))
        assertEquals(0, field("restartGeneration"))
    }

    @Test fun aStoppedHostDoesNotRenegotiateForItsHiddenWindow() {
        set("isActivityStarted", false)
        invoke("applyDisplaySize", size(1775, 485))
        assertEquals(size(1901, 701), field("activeDisplaySize"))
        assertSame(controller, field("controller"))
    }

    @Test fun ordinaryCarPlayStillDetectsAnAspectChangeWhenPipAdaptationIsEnabled() {
        set("sessionDisplay", display().copy(fillViewport = false))
        AirPlayPersistence.saveAdaptPipResolution(activity, true)
        assertEquals(true, invoke("displayLayoutChanged", size(1775, 485)))
    }

    @Test fun ordinaryCarPlayStillReconnectsWhenItsNegotiatedWindowIsTooSmall() {
        set("sessionDisplay", display(windowWidth = 1775, windowHeight = 675).copy(fillViewport = false))
        set("activeDisplaySize", size(1775, 675))
        invoke("applyDisplaySize", size(1901, 701))
        assertNull(field("controller"))
        assertEquals(1, field("restartGeneration"))
        assertEquals(true, field("handshakeResetInProgress"))
    }

    @Test fun ultraStillDetectsActualBarAndComfortSettingChanges() {
        set("hideTopBar", false)
        assertEquals(true, invoke("displayLayoutChanged", size(1901, 701)))
        set("hideTopBar", true)
        set("hideBottomBar", false)
        assertEquals(true, invoke("displayLayoutChanged", size(1901, 701)))
        set("hideBottomBar", true)
        set("comfortHeight", 60)
        assertEquals(true, invoke("displayLayoutChanged", size(1901, 701)))
    }

    @Test fun attachingTheReturnedSurfaceAsksForAPictureAfterAttachingItToTheDecoder() {
        val surface = mock(Surface::class.java)
        invoke("attachSurface", surface)
        inOrder(sink).apply {
            verify(sink).setSurface(110, surface)
            verify(sink).refreshPicture(110)
        }
    }

    private fun display(windowWidth: Int = 1901, windowHeight: Int = 701) =
        CarPlaySessionDisplay(1920, 720, Surface.ROTATION_0, true, true,
            windowWidth, windowHeight, fillViewport = true)

    private fun size(width: Int, height: Int): Any =
        displaySize.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(width, height)

    private fun field(name: String): Any? =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(activity)

    private fun set(name: String, value: Any) =
        activity.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(activity, value)

    private fun invoke(name: String, vararg args: Any): Any? =
        activity.javaClass.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
}
