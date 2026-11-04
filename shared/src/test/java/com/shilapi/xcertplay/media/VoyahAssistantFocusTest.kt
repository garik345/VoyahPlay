package com.shilapi.xcertplay.media

import android.media.AudioManager
import com.shilapi.xcertplay.airplay.AirPlayAppStates
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahAssistantFocusTest {
    private fun speech(mode: Any, entity: Any = 1L) = mapOf("appStates" to listOf(
        mapOf("appStateID" to 1L, "entity" to entity, "speechMode" to mode)))
    private fun call(entity: Any) = mapOf("appStates" to listOf(mapOf("appStateID" to "phoneCall", "entity" to entity)))

    @Test fun nativeRadioDucksThroughListeningAndSpeakingAndThenRestoresItsPreviousRoute() {
        val events = mutableListOf<Int>()
        val focus = VoyahAssistantFocus(true, events::add)
        focus.base(3); focus.radio(true)
        focus.modes(speech(2L)); focus.modes(speech(1L))
        focus.modes(mapOf("resources" to listOf(mapOf("resourceID" to 2L, "entity" to 1L))))
        assertEquals(listOf(3, 22), events)
        focus.modes(speech(-1L, 0L))
        assertEquals(listOf(3, 22, 3), events)
    }
    @Test fun iphoneMusicAndOtherHeadUnitsKeepTheirExistingFocusPolicy() {
        for (enabled in listOf(true, false)) {
            val events = mutableListOf<Int>()
            val focus = VoyahAssistantFocus(enabled, events::add)
            focus.base(3); focus.modes(speech("recognizing", "controller"))
            assertEquals(listOf(3), events)
            if (!enabled) { focus.radio(true); assertEquals(listOf(3), events) }
        }
    }
    @Test fun callsHavePriorityAndAChangedBaseIsRestoredAfterSiri() {
        val events = mutableListOf<Int>()
        val focus = VoyahAssistantFocus(true, events::add)
        focus.radio(true); focus.modes(speech(2L)); focus.base(6)
        focus.modes(call(1L)); focus.base(1)
        assertEquals(listOf(22, 6, 1), events)
        focus.modes(speech(-1L)); focus.modes(call(0L))
        assertEquals(listOf(22, 6, 1), events)
    }
    @Test fun changingSourceAndClosingDuringSiriReleaseOnlyTheOwnedOverride() {
        val events = mutableListOf<Int>()
        val focus = VoyahAssistantFocus(true, events::add)
        focus.radio(true); focus.modes(speech(2L)); focus.radio(false); focus.radio(true)
        focus.close(); focus.modes(speech(1L)); focus.base(3); focus.close()
        assertEquals(listOf(22, -1, 22, -1), events)
    }
    @Test fun incrementalModesRejectMalformedDuplicatesAndRecognizeTheSdkStringForms() {
        val state = AirPlayAppStates()
        state.accept(speech("recognizing", "controller")); assertTrue(state.speech)
        state.accept(speech(0L)); assertTrue(state.speech) // NotApplicable is not an end-of-Siri event.
        state.accept(speech(true)); state.accept(speech(1.5)); assertTrue(state.speech)
        val duplicate = listOf(mapOf("appStateID" to 1, "entity" to 0, "speechMode" to -1),
            mapOf("appStateID" to "speech", "entity" to 0, "speechMode" to -1))
        state.accept(mapOf("appStates" to duplicate)); assertTrue(state.speech)
        state.accept(call("accessory")); assertTrue(state.phoneCall)
        state.accept(speech("none", "n/a")); assertFalse(state.speech); assertTrue(state.phoneCall)
        state.clear(); assertFalse(state.phoneCall)
    }
    @Test fun theRealSinkRequestsSiriFocusEvenWhenOptionalMediaFocusIsOffWithoutChangingUserVolume() {
        val context = RuntimeEnvironment.getApplication()
        val audio = context.getSystemService(AudioManager::class.java)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 6, 0)
        val events = mutableListOf<Int>()
        val sink = AndroidMediaSink(context = context, audioFocusEnabled = false, voyahAudioRouting = true,
            onAudioFocusStreamChanged = events::add)
        try {
            sink.onNativeRadioSelected(true); sink.onModesChanged(speech(2L))
            assertEquals(listOf(22), events)
            assertEquals(6, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            sink.onModesChanged(speech(-1L)); assertEquals(listOf(22, -1), events)
            assertEquals(6, audio.getStreamVolume(AudioManager.STREAM_MUSIC))
        } finally { sink.close() }
    }
}
