package com.shilapi.xcertplay.airplay.ultra

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class VoyahRadioAudioSyncTest {
    private fun radio(playing: Boolean?, text: String? = null) = UltraRadioSnapshot(true,
        VoyahRadioOem.decode(mapOf(VoyahRadioOem.BAND to "FM", VoyahRadioOem.FREQUENCY to 10170L),
            when (playing) { true -> 3; false -> 2; null -> 6 }).copy(radioText = text))

    @Test fun anAlreadyPlayingRadioWaitsForTheSessionAndMetadataCannotStealAudioAgain() {
        val takes = AtomicInteger()
        val results = LinkedBlockingQueue<String>()
        VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, { results.offer(it) }).use { sync ->
            sync.observe(radio(true))
            assertEquals(0, takes.get())
            sync.attach()
            awaitResult(results)
            assertEquals(1, takes.get())
            sync.observe(radio(true, "New track"))
            sync.observe(radio(null))
            sync.observe(radio(true))
            assertNull(results.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(1, takes.get())
            sync.observe(radio(false))
            sync.observe(radio(true))
            awaitResult(results)
            assertEquals(2, takes.get())
        }
    }

    @Test fun onlyPlayingRadioDucksForSpeechAndRestoresOnStopOrSessionClose() {
        val changes = mutableListOf<Boolean>()
        val sync = VoyahRadioAudioSync({ true }, {}, {}, speechDuckChanged = changes::add)
        val speech = mapOf("appStates" to listOf(mapOf("appStateID" to 1, "entity" to 1, "speechMode" to 2)))
        try {
            sync.attach()
            sync.observe(radio(false))
            sync.modes(speech)
            assertTrue(changes.isEmpty())
            sync.control(true) { sync.observe(radio(true)) }
            sync.modes(speech)
            sync.observe(radio(false))
            sync.control(true) { sync.observe(radio(true)) }
        } finally { sync.close() }
        assertEquals(listOf(true, false, true, false), changes)
    }

    @Test fun aNativeStopBeforeAttachmentCancelsThePendingSelection() {
        val takes = AtomicInteger()
        VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, {}).use { sync ->
            sync.observe(radio(true))
            sync.observe(radio(false))
            sync.attach()
            sync.control(false) { assertEquals(0, takes.get()) }
        }
    }

    @Test fun cafPlaybackEchoIsIgnoredButTheNextNativeStartStillWorks() {
        val takes = AtomicInteger()
        val results = LinkedBlockingQueue<String>()
        VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, { results.offer(it) }).use { sync ->
            sync.attach()
            sync.control(true) { sync.observe(radio(true)) }
            assertEquals(0, takes.get())
            sync.observe(radio(false))
            sync.observe(radio(true))
            awaitResult(results)
            assertEquals(1, takes.get())
        }
    }

    @Test fun aStopWhileWaitingForThePhoneReleasesALateGrantWithoutBlockingTheCallback() {
        val requested = CountDownLatch(1)
        val answer = CountDownLatch(1)
        val released = CountDownLatch(1)
        VoyahRadioAudioSync({ requested.countDown(); answer.await(2, TimeUnit.SECONDS) },
            { released.countDown() }, {}).use { sync ->
            sync.attach()
            sync.observe(radio(true))
            assertTrue(requested.await(1, TimeUnit.SECONDS))
            sync.observe(radio(false)) // Must return while takeAudio is still waiting.
            assertEquals(1L, answer.count)
            answer.countDown()
            assertTrue(released.await(1, TimeUnit.SECONDS))
        }
    }

    @Test fun deniedNativeStartDoesNotRetryOnPollingOrRdsUpdates() {
        val takes = AtomicInteger()
        val results = LinkedBlockingQueue<String>()
        VoyahRadioAudioSync({ takes.incrementAndGet(); false }, {}, { results.offer(it) }).use { sync ->
            sync.attach()
            sync.observe(radio(true))
            assertTrue(awaitResult(results).contains("accessoryGranted=false"))
            repeat(20) { sync.observe(radio(true, "Track $it")) }
            assertNull(results.poll(100, TimeUnit.MILLISECONDS))
            assertEquals(1, takes.get())
        }
    }

    @Test fun disconnectInvalidatesAPendingSelectionAndClosedSessionsIgnoreCallbacks() {
        val takes = AtomicInteger()
        val sync = VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, {})
        sync.observe(radio(true))
        sync.observe(UltraRadioSnapshot())
        sync.attach()
        sync.close()
        sync.observe(radio(true))
        sync.attach()
        assertEquals(0, takes.get())
    }

    private fun awaitResult(results: LinkedBlockingQueue<String>): String {
        while (true) {
            val result = results.poll(2, TimeUnit.SECONDS) ?: throw AssertionError("No ownership result")
            if (result.startsWith("RADIO EXTERNAL AUDIO")) return result
        }
    }

    @Test fun doorPausesOncePerOpeningWithoutChangingSourceOrResumingOnClose() {
        val pauses = LinkedBlockingQueue<Boolean>(); val saved = mutableListOf<UltraMediaSource>()
        VoyahRadioAudioSync({ true }, {}, {}, remember = saved::add,
            pauseRadio = { pauses.offer(true); true }).use { sync ->
            sync.attach(); sync.control(true) { sync.observe(radio(true)) }
            sync.driverDoor(true, 1000, 1000) // Initial open door is only a seed.
            assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
            sync.driverDoor(false, 1001, 1001); sync.driverDoor(true, 1002, 1002)
            assertEquals(true, pauses.poll(2, TimeUnit.SECONDS))
            repeat(10) { sync.driverDoor(true, 1002L + it, 1002L + it) }
            sync.driverDoor(false, 1100, 1100)
            assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
            sync.driverDoor(true, 1101, 1101)
            assertEquals(true, pauses.poll(2, TimeUnit.SECONDS))
            // The driver may deliberately start radio again while the door stays open.
            sync.control(true) { sync.observe(radio(true)) }
            sync.driverDoor(true, 1102, 1102)
            assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
            assertTrue(sync.radioSelected()); assertTrue(saved.isEmpty())
        }
    }

    @Test fun unknownStaleFutureAndOutOfOrderDoorSamplesCannotTriggerPause() {
        val pauses = LinkedBlockingQueue<Boolean>()
        VoyahRadioAudioSync({ true }, {}, {}, pauseRadio = { pauses.offer(true); true }).use { sync ->
            sync.attach(); sync.control(true) { sync.observe(radio(true)) }
            sync.driverDoor(false, 1000, 1000)
            sync.driverDoor(true, 1001, 4000) // Stale; cannot trigger a pause.
            sync.driverDoor(true, 5000, 4000) // Invalid future timestamp must not poison the watermark.
            assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
            sync.driverDoor(false, 4001, 4001); sync.driverDoor(null, 4002, 4002)
            sync.driverDoor(true, 4003, 4003) // Unknown -> open is only a seed.
            sync.driverDoor(false, 4001, 4003) // Old closed cache must not re-arm it.
            sync.driverDoor(true, 4004, 4004)
            assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
            sync.driverDoor(false, 4005, 4005)
            sync.driverDoor(false, 4005, 7006) // Repeated stale cache while CAN is reading.
            sync.driverDoor(true, 7006, 7006)
            assertEquals(true, pauses.poll(2, TimeUnit.SECONDS))
        }
    }

    @Test fun doorDoesNotPauseAnInactiveRadioAndClosingCancelsPendingWork() {
        val pauses = LinkedBlockingQueue<Boolean>()
        val sync = VoyahRadioAudioSync({ true }, {}, {}, pauseRadio = { pauses.offer(true); true })
        try {
            sync.observe(radio(false))
            sync.driverDoor(false, 1000, 1000); sync.driverDoor(true, 1001, 1001)
            sync.attach()
            assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
            sync.control(true) { sync.observe(radio(true)) }
            sync.driverDoor(false, 1002, 1002)
            sync.close(); sync.driverDoor(true, 1003, 1003)
            assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
        } finally { sync.close() }
    }

    @Test fun doorPauseWaitsForACafCommandAndSurvivesItsCompletionAndTuningEcho() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val pauses = LinkedBlockingQueue<Boolean>(); val takes = AtomicInteger()
        VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, {},
            pauseRadio = { pauses.offer(true); true }).use { sync ->
            sync.attach(); sync.control(true) { sync.observe(radio(true)) }
            sync.driverDoor(false, 1000, 1000)
            val command = Thread { sync.control(false) { entered.countDown(); release.await(2, TimeUnit.SECONDS) } }
            command.start()
            try {
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                sync.driverDoor(true, 1001, 1001) // Must return with the CAF write still blocked.
                sync.observe(radio(true).let { it.copy(state = it.state!!.copy(rawFrequency = 10230L)) })
                assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
                release.countDown(); command.join(1000)
                assertFalse(command.isAlive)
                assertEquals(true, pauses.poll(2, TimeUnit.SECONDS))
                assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
                assertEquals(0, takes.get())
            } finally { release.countDown(); command.join(1000) }
        }
    }

    @Test fun openingDoorCancelsALateAudioGrantAndThenPausesRadio() {
        val requested = CountDownLatch(1); val release = CountDownLatch(1)
        val returned = CountDownLatch(1); val paused = CountDownLatch(1)
        VoyahRadioAudioSync({ requested.countDown(); release.await(2, TimeUnit.SECONDS) },
            { returned.countDown() }, {}, pauseRadio = { paused.countDown(); true }).use { sync ->
            sync.attach(); sync.observe(radio(true)); assertTrue(requested.await(1, TimeUnit.SECONDS))
            sync.driverDoor(false, 1000, 1000); sync.driverDoor(true, 1001, 1001)
            release.countDown()
            assertTrue(returned.await(1, TimeUnit.SECONDS)); assertTrue(paused.await(1, TimeUnit.SECONDS))
            assertTrue(sync.radioSelected())
        }
    }

    @Test fun rememberedCarPlayPausesABootRadioOnceWithoutTakingAudioOrRememberingIt() {
        val takes = AtomicInteger(); val pauses = LinkedBlockingQueue<Boolean>(); val saved = mutableListOf<UltraMediaSource>()
        VoyahRadioAudioSync({ takes.incrementAndGet(); true }, {}, {}, preferred = { UltraMediaSource.CARPLAY },
            remember = saved::add, pauseRadio = { pauses.offer(true); true }).use { sync ->
            sync.observe(radio(false)); sync.observe(radio(true)) // Service restores playback while /info is connecting.
            sync.attach()
            assertTrue(pauses.poll(2, TimeUnit.SECONDS) == true)
            assertFalse(sync.radioSelected()); assertEquals(0, takes.get()); assertTrue(saved.isEmpty())
            repeat(10) { sync.observe(radio(true, "RDS $it")) }
            assertNull(pauses.poll(100, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun anExplicitNativeStartAfterStartupAndConfirmedCafPlayBothRememberRadio() {
        val takes = LinkedBlockingQueue<Boolean>(); val saved = mutableListOf<UltraMediaSource>()
        VoyahRadioAudioSync({ takes.offer(true); true }, {}, {}, preferred = { UltraMediaSource.CARPLAY }, remember = saved::add).use { sync ->
            sync.observe(radio(false)); sync.attach(); sync.observe(radio(true))
            assertTrue(takes.poll(2, TimeUnit.SECONDS) == true)
            assertTrue(sync.radioSelected()); assertEquals(listOf(UltraMediaSource.RADIO), saved)
            sync.iphonePlayback(true, initial = false)
            sync.control(true, confirmed = { ok: Boolean -> ok }) { false }
            assertFalse(sync.radioSelected())
            sync.control(true, confirmed = { ok: Boolean -> ok }) { true }
            assertTrue(sync.radioSelected())
            assertEquals(listOf(UltraMediaSource.RADIO, UltraMediaSource.CARPLAY, UltraMediaSource.RADIO), saved)
        }
    }
    @Test fun retainedInitialPlaybackDoesNotOverwriteRememberedRadioButAGenuineMusicStartDoes() {
        val saved = mutableListOf<UltraMediaSource>()
        VoyahRadioAudioSync({ true }, {}, {}, remember = saved::add).use { sync ->
            sync.iphonePlayback(true, initial = true); assertTrue(sync.radioSelected())
            sync.iphonePlayback(false, initial = false)
            assertTrue(sync.radioSelected()); assertTrue(saved.isEmpty())
            sync.iphonePlayback(true, initial = false)
            assertFalse(sync.radioSelected()); assertEquals(listOf(UltraMediaSource.CARPLAY), saved)
        }
    }
    @Test fun selectingIphoneMusicCancelsALateRadioGrantAndThenPausesTheNativePlayer() {
        val requested = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1); val paused = CountDownLatch(1)
        VoyahRadioAudioSync({ requested.countDown(); release.await(2, TimeUnit.SECONDS) }, { returned.countDown() }, {},
            pauseRadio = { paused.countDown(); true }).use { sync ->
            sync.attach(); sync.observe(radio(true)); assertTrue(requested.await(1, TimeUnit.SECONDS))
            sync.iphonePlayback(true, initial = false); release.countDown()
            assertTrue(returned.await(1, TimeUnit.SECONDS)); assertTrue(paused.await(1, TimeUnit.SECONDS))
            assertFalse(sync.radioSelected())
        }
    }
}
