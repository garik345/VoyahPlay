package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.airplay.AirPlayAppStates

/** Borrows Qinggan's Siri bus while native radio is selected, independently of optional media focus. */
internal class VoyahAssistantFocus(private val enabled: Boolean, private val publish: (Int) -> Unit) {
    private val states = AirPlayAppStates()
    private var base = -1
    private var radio = false
    private var last = -1
    private var closed = false

    @Synchronized fun base(stream: Int) { if (!closed) { base = stream; refresh() } }
    @Synchronized fun radio(selected: Boolean) { if (!closed) { radio = selected; refresh() } }
    @Synchronized fun modes(params: Map<*, *>) { if (!closed) { states.accept(params); refresh() } }
    @Synchronized fun reset() { states.clear(); radio = false; refresh() }
    @Synchronized fun close() { closed = true; base = -1; reset() }

    private fun refresh() {
        val stream = if (!closed && enabled && radio && states.speech && !states.phoneCall && base != 6) 22 else base
        if (stream != last) { last = stream; publish(stream) }
    }
}
