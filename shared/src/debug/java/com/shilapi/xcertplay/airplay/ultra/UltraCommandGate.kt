package com.shilapi.xcertplay.airplay.ultra

import java.io.Closeable
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One physical command at a time; a stuck Binder never owns the CAF protocol thread. */
internal class UltraCommandGate : Closeable {
    private val busy = AtomicBoolean()
    private val closed = AtomicBoolean()
    private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(1),
        { r -> Thread(r, "voyah-ultra-command").apply { isDaemon = true } })

    fun run(deadlineNanos: Long, action: () -> UltraClimateWriteResult): UltraClimateWriteResult {
        if (closed.get() || deadlineNanos <= System.nanoTime() || !busy.compareAndSet(false, true))
            return UltraClimateWriteResult(false, "Vehicle command busy or response budget expired")
        val done = CountDownLatch(1)
        var result = UltraClimateWriteResult(false, "Vehicle command pending; waiting for actual state")
        try {
            worker.execute {
                try {
                    if (!closed.get() && System.nanoTime() < deadlineNanos) result = try { action() }
                    catch (error: Exception) { UltraClimateWriteResult(false, "Vehicle command failed: ${error.javaClass.simpleName}") }
                } finally { busy.set(false); done.countDown() }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            busy.set(false)
            return UltraClimateWriteResult(false, "Vehicle command closed")
        }
        return try {
            if (done.await((deadlineNanos - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)) result
            else UltraClimateWriteResult(false, "Vehicle command pending; waiting for actual state")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            UltraClimateWriteResult(false, "Vehicle command wait interrupted")
        }
    }

    override fun close() { if (closed.compareAndSet(false, true)) worker.shutdownNow() }
}
