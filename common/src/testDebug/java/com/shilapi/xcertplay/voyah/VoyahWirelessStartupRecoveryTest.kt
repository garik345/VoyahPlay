package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.network.WirelessStartupFailure
import org.junit.Assert.*
import org.junit.Test

class VoyahWirelessStartupRecoveryTest {
    @Test fun exhaustedStartupWaitsBeforeOneSlowRetry() {
        val recovery = VoyahWirelessStartupRecovery()
        assertTrue(recovery.failed(WirelessStartupFailure.FIRST_TCP_TIMEOUT, 1_000))
        assertFalse(recovery.takeRetry(60_999, null))
        assertTrue(recovery.takeRetry(61_000, null))
        assertFalse(recovery.takeRetry(61_000, null))
        assertFalse(recovery.pending)
    }

    @Test fun repeatedFailuresBackOffInsteadOfRestartingTheFastRetryBurst() {
        val recovery = VoyahWirelessStartupRecovery()
        var now = 0L
        for (delay in listOf(60_000L, 120_000L, 300_000L, 300_000L)) {
            recovery.failed(WirelessStartupFailure.HOTSPOT_NOT_READY, now)
            assertFalse(recovery.takeRetry(now + delay - 1, true))
            now += delay
            assertTrue(recovery.takeRetry(now, true))
        }
    }

    @Test fun aLongCarSleepDoesNotRequireAnotherMinuteOfAwakeTime() {
        val recovery = VoyahWirelessStartupRecovery()
        recovery.failed(WirelessStartupFailure.FIRST_TCP_TIMEOUT, 0)
        assertTrue(recovery.takeRetry(8 * 60 * 60_000L, null))
    }

    @Test fun anOffHotspotWaitsForActualReadinessEvenAfterAnEntireNight() {
        val recovery = VoyahWirelessStartupRecovery()
        recovery.failed(WirelessStartupFailure.HOTSPOT_OFF, 0)
        assertTrue(recovery.waitsForHotspot)
        for (state in listOf(false, null)) assertFalse(recovery.takeRetry(8 * 60 * 60_000L, state))
        assertTrue(recovery.takeRetry(8 * 60 * 60_000L, true))
        assertFalse(recovery.takeRetry(8 * 60 * 60_000L, true))
    }

    @Test fun configurationAndUnsupportedBluetoothNeverBecomeTimedRetries() {
        val recovery = VoyahWirelessStartupRecovery()
        for (reason in listOf(WirelessStartupFailure.HOTSPOT_CONFIGURATION, WirelessStartupFailure.BLUETOOTH_UNUSABLE)) {
            assertFalse(recovery.failed(reason, 0))
            assertFalse(recovery.pending)
            assertFalse(recovery.takeRetry(Long.MAX_VALUE, true))
        }
    }

    @Test fun aNewAttemptCancelsTheOldTimerAndStableOrManualResetRestoresTheShortPause() {
        val recovery = VoyahWirelessStartupRecovery()
        recovery.failed(WirelessStartupFailure.FIRST_TCP_TIMEOUT, 0)
        assertTrue(recovery.takeRetry(60_000, null))
        recovery.failed(WirelessStartupFailure.FIRST_TCP_TIMEOUT, 60_000)
        recovery.attemptStarted()
        assertFalse(recovery.takeRetry(180_000, null))
        recovery.reset()
        recovery.failed(WirelessStartupFailure.FIRST_TCP_TIMEOUT, 180_000)
        assertTrue(recovery.takeRetry(240_000, null))
    }
}
