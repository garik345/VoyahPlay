package com.shilapi.xcertplay.airplay

import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class CarPlayMediaModesTest {
    private fun session() = AirPlaySession(Socket(),
        AirPlayConfig("test", "test", "", "1", AirPlayDisplayConfig(800, 480)),
        AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {
            override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int = 1
        })
    private fun setup(session: AirPlaySession) {
        session.javaClass.getDeclaredMethod("handleStreams", List::class.java).apply { isAccessible = true }
            .invoke(session, listOf(mapOf("type" to 110L)))
    }
    @Test fun onlyTheCurrentMainSessionCanChangeOrResetNativeRadioAndSpeechFocus() {
        val events = mutableListOf<String>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onModesReset() { events += "reset" }
            override fun onNativeRadioSelected(selected: Boolean) { events += "radio=$selected" }
            override fun onModesChanged(params: Map<String, Any?>) { events += "modes" }
        })
        val first = session(); val next = session(); val probe = session()
        try {
            engine.onSetupResponseSent(probe); engine.onModesChanged(probe, emptyMap()); assertTrue(events.isEmpty())
            setup(first); first.nativeRadioSelected(true); engine.onSetupResponseSent(first)
            assertEquals(listOf("reset", "radio=true"), events)
            setup(next); engine.onSetupResponseSent(next)
            val count = events.size
            engine.onSetupResponseSent(first) // A later audio SETUP for the old main token cannot reclaim ownership.
            engine.onNativeRadioSelected(first, true); engine.onModesChanged(first, emptyMap()); engine.onSessionClosed(first)
            assertEquals(count, events.size)
            engine.onModesChanged(next, emptyMap()); engine.onNativeRadioSelected(next, true)
            assertEquals(listOf("modes", "radio=true"), events.takeLast(2))
            engine.onTeardown(next, 110); assertEquals("reset", events.last())
            val after = events.size
            engine.onModesChanged(next, emptyMap()); engine.onSessionClosed(next); assertEquals(after, events.size)
        } finally { first.close(); next.close(); probe.close() }
    }
    @Test fun replacingThePrimaryTokenInTheSameConnectionClearsSpeechAndReappliesRadioSelection() {
        var resets = 0; val radios = mutableListOf<Boolean>()
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onModesReset() { resets++ }
            override fun onNativeRadioSelected(selected: Boolean) { radios += selected }
        })
        val session = session()
        try {
            setup(session); engine.onSetupResponseSent(session); engine.onSetupResponseSent(session)
            assertEquals(1, resets)
            session.nativeRadioSelected(true); setup(session); engine.onSetupResponseSent(session)
            assertEquals(2, resets); assertEquals(listOf(false, true), radios)
            engine.onSessionClosed(session); assertEquals(3, resets)
        } finally { session.close() }
    }
}
