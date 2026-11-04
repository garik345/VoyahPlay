package com.shilapi.xcertplay

/** Only a short phone-key event in the original, idle CarPlay session may be consumed. */
internal class VoyahPhoneKeyProbeGate(private val token: Any, private val began: Long) {
    @Volatile var enabled = true
    fun onKey(key: Int, action: Int, now: Long, current: Any?, calling: Boolean, queue: () -> Boolean): Boolean {
        if (!enabled || now < began || now - began >= 30_000 || current !== token || calling) return false
        if (key != 128 || action != 8192) return false
        return queue()
    }
}
