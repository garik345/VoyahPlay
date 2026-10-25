package com.shilapi.xcertplay.airplay

import java.math.BigInteger
import org.junit.Assert.*
import org.junit.Test

class ProtocolTraceFormatterTest {
    @Test fun hexPreservesEveryByteAndLeadingZero() {
        val bytes = ByteArray(256) { it.toByte() }
        val expected = BigInteger(1, bytes).toString(16).padStart(512, '0')
        assertEquals(expected, ProtocolTraceFormatter.hex(bytes))
        assertEquals("<empty>", ProtocolTraceFormatter.hex(byteArrayOf()))
    }

    @Test fun largeProtocolBodyIsStillLoggedCompletely() {
        val bytes = ByteArray(32768) { byteArrayOf(0, 127, -1, -128)[it % 4] }
        assertEquals("007fff80".repeat(8192), ProtocolTraceFormatter.hex(bytes))
    }

    @Test fun previewKeepsItsExistingSpacingAndClippingContract() {
        val bytes = byteArrayOf(0, 127, -1, -128)
        assertEquals("00 7f ff 80", ProtocolTraceFormatter.preview(bytes, 4))
        assertEquals("00 7f ... (+2B)", ProtocolTraceFormatter.preview(bytes, 2))
        assertEquals(" ... (+4B)", ProtocolTraceFormatter.preview(bytes, 0))
        assertEquals("<empty>", ProtocolTraceFormatter.preview(byteArrayOf(), 0))
        assertThrows(IllegalArgumentException::class.java) { ProtocolTraceFormatter.preview(bytes, -1) }
    }
}
