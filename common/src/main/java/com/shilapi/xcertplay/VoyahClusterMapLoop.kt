package com.shilapi.xcertplay

import com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec as Spec

/** Main-thread state machine; a stale root reply cannot authorize the next phone/route. */
internal class VoyahClusterMapLoop(
    private val clock: () -> Long,
    private val root: (Boolean, (Boolean, String) -> Unit) -> Unit,
    private val report: (String) -> Unit,
) {
    data class Lease(val token: String, val display: Int, val deadline: Long) {
        companion object {
            fun parse(text: String, now: Long): Lease? {
                fun field(key: String) = text.lineSequence().singleOrNull { it.startsWith("$key=") }?.substringAfter('=')
                if (field("CLUSTER_PROBE_ACTIVE") != "1" || field("CLUSTER_PROBE_MODE") != "auto") return null
                val token = field("CLUSTER_PROBE_TOKEN") ?: return null
                val display = field("CLUSTER_PROBE_DISPLAY")?.toIntOrNull() ?: return null
                val deadline = field("CLUSTER_PROBE_DEADLINE")?.toLongOrNull() ?: return null
                return if (Spec.validLease(token, "auto", display, deadline, now)) Lease(token, display, deadline) else null
            }
        }
    }
    private var desired: Any? = null
    private var rootOwner: Any? = null
    private var busy = false
    private var stopNeeded = false
    private var nextAt = 0L
    private var failures = 0
    var lease: Lease? = null
        private set

    fun update(owner: Any?) {
        if (desired !== owner) {
            desired = owner
            lease = null
            if (rootOwner != null || busy) stopNeeded = true
            nextAt = 0
        }
        val now = clock()
        if (lease?.deadline?.let { it <= now } == true) lease = null
        if (busy || now < nextAt) return
        if (stopNeeded) {
            busy = true
            root(false) { ok, text ->
                busy = false
                report(text)
                if (ok) { rootOwner = null; stopNeeded = false; failures = 0; nextAt = 0 }
                else nextAt = clock() + retryDelay()
            }
        } else if (owner != null) {
            busy = true
            rootOwner = owner // A failed reply may still have executed; retire it before changing owners.
            root(true) { ok, text ->
                busy = false
                report(text)
                if (desired !== owner || stopNeeded) {
                    lease = null; stopNeeded = true; nextAt = 0
                } else {
                    val next = if (ok) Lease.parse(text, clock()) else null
                    if (next != null) { lease = next; failures = 0; nextAt = clock() + 15000 }
                    else nextAt = clock() + retryDelay()
                }
            }
        }
    }

    private fun retryDelay(): Long = when (++failures) { 1 -> 2000; 2 -> 5000; else -> 15000 }
}
