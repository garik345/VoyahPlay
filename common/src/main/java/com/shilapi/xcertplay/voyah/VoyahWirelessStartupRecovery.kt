package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.network.WirelessStartupFailure

/** Slow recovery after the normal startup budget, using elapsed time that includes car sleep. */
internal class VoyahWirelessStartupRecovery {
    private var failure: WirelessStartupFailure? = null
    private var retryAtMillis = 0L
    private var slowRetries = 0

    val pending get() = failure != null
    val waitsForHotspot get() = failure == WirelessStartupFailure.HOTSPOT_OFF

    fun failed(reason: WirelessStartupFailure, nowMillis: Long): Boolean {
        failure = reason.takeIf { it.retryable || it == WirelessStartupFailure.HOTSPOT_OFF }
        retryAtMillis = nowMillis + when (slowRetries) {
            0 -> 60_000L
            1 -> 120_000L
            else -> 300_000L
        }
        return pending
    }

    /** Consume once. An off hotspot must actually become ready; a timer alone cannot retry it. */
    fun takeRetry(nowMillis: Long, hotspotReady: Boolean?): Boolean {
        if (!pending) return false
        val ready = if (waitsForHotspot) hotspotReady == true else nowMillis >= retryAtMillis
        if (!ready) return false
        if (!waitsForHotspot) slowRetries = (slowRetries + 1).coerceAtMost(2)
        attemptStarted()
        return true
    }

    fun attemptStarted() { failure = null }
    fun reset() { attemptStarted(); slowRetries = 0 }

    companion object { const val CHECK_INTERVAL_MILLIS = 5_000L }
}
