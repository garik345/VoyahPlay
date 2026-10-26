package com.shilapi.xcertplay.network

import java.io.IOException

object WirelessStartupPolicy {
    const val HOTSPOT_READY_MILLIS = 15_000L
    const val INTERFACE_POLL_MILLIS = 250L
    const val STABLE_SAMPLES = 3
    const val FIRST_TCP_MILLIS = 30_000L
    const val MAX_STARTUP_RETRIES = 5
    const val STABLE_SESSION_MILLIS = 60_000L
}

enum class WirelessStartupFailure {
    HOTSPOT_NOT_READY, FIRST_TCP_TIMEOUT, HOTSPOT_CONFIGURATION,

    /** The head unit's Android Bluetooth accepts connections that reach no phone. */
    BLUETOOTH_UNUSABLE,

    /** The car may restore its hotspot after waking; retry when it is actually on. */
    HOTSPOT_OFF;

    /** False when a timed retry alone should not be attempted. */
    val retryable: Boolean get() = this == HOTSPOT_NOT_READY || this == FIRST_TCP_TIMEOUT
}

class WirelessStartupException(val reason: WirelessStartupFailure, message: String) : IOException(message)
