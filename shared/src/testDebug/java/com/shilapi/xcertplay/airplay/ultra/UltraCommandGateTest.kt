package com.shilapi.xcertplay.airplay.ultra

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class UltraCommandGateTest {
    @Test fun pendingCallCannotBeDuplicatedAndAnExpiredTransactionNeverWrites() {
        val gate = UltraCommandGate()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val writes = AtomicInteger()
        fun deadline() = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200)
        try {
            assertFalse(gate.run(deadline()) { writes.incrementAndGet(); entered.countDown(); release.await(); UltraClimateWriteResult(true, "late") }.confirmed)
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            repeat(30) { assertFalse(gate.run(deadline()) { writes.incrementAndGet(); UltraClimateWriteResult(true, "duplicate") }.confirmed) }
            assertFalse(gate.run(0) { writes.incrementAndGet(); UltraClimateWriteResult(true, "expired") }.confirmed)
            assertEquals(1, writes.get())
            gate.close()
            assertFalse(gate.run(deadline()) { writes.incrementAndGet(); UltraClimateWriteResult(true, "closed") }.confirmed)
            assertEquals(1, writes.get())
        } finally { release.countDown(); gate.close() }
    }
}
