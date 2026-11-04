package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class VoyahPhoneKeyProbeGateTest {
    @Test fun onlyTheCompletedShortPressCanOpenRecents() {
        val token = Any(); val gate = VoyahPhoneKeyProbeGate(token, 100)
        var requests = 0
        for (action in listOf(4096, 65536, 16384, 32768, 262144, 524288, 131072, 0, 1)) {
            assertFalse(gate.onKey(128, action, 200, token, false) { requests++; true })
        }
        assertEquals(0, requests)
        assertTrue(gate.onKey(128, 8192, 200, token, false) { requests++; true })
        assertEquals(1, requests)
    }

    @Test fun unrelatedWheelKeysAreNeverConsumed() {
        val token = Any(); val gate = VoyahPhoneKeyProbeGate(token, 100)
        for (key in listOf(1, 2, 3, 4, 5, 130, 176, 3090))
            assertFalse(gate.onKey(key, 8192, 200, token, false) { fail("other key routed"); true })
    }

    @Test fun noSessionReplacementSessionAndRingingCallKeepNativeHandling() {
        val token = Any(); val gate = VoyahPhoneKeyProbeGate(token, 100)
        for (current in listOf(null, Any()))
            assertFalse(gate.onKey(128, 8192, 200, current, false) { fail("wrong phone"); true })
        assertFalse(gate.onKey(128, 8192, 200, token, true) { fail("call changed"); true })
    }

    @Test fun expiryDisableAndClockRollbackAlwaysReleaseTheKey() {
        val token = Any(); val gate = VoyahPhoneKeyProbeGate(token, 100)
        for (time in listOf(99L, 30_100L, Long.MAX_VALUE))
            assertFalse(gate.onKey(128, 8192, time, token, false) { fail("expired"); true })
        assertTrue(gate.onKey(128, 8192, 30_099, token, false) { true })
        gate.enabled = false
        assertFalse(gate.onKey(128, 8192, 200, token, false) { fail("stopped"); true })
    }

    @Test fun queueFailureDoesNotSuppressTheNativePhoneAction() {
        val token = Any(); val gate = VoyahPhoneKeyProbeGate(token, 100)
        assertFalse(gate.onKey(128, 8192, 200, token, false) { false })
    }
}
