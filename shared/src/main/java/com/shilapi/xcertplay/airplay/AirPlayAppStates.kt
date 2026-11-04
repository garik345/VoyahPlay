package com.shilapi.xcertplay.airplay

/** Incremental ModesChanged app states, as defined by the receiver SDK's AirPlayCommon.h. */
internal class AirPlayAppStates {
    var speech = false
        private set
    var phoneCall = false
        private set

    fun accept(params: Map<*, *>) {
        val entries = (params["appStates"] as? List<*>)?.takeIf { it.size <= 32 }
            ?.filterIsInstance<Map<*, *>>() ?: return
        fun entry(number: Int, name: String) = entries.filter {
            it["appStateID"] == name || integer(it["appStateID"]) == number
        }.singleOrNull()
        entry(1, "speech")?.let { state ->
            val entity = entity(state["entity"]) ?: return@let
            when (state["speechMode"]) {
                "none" -> speech = false
                "speaking", "recognizing" -> speech = entity == 1
                else -> when (integer(state["speechMode"])) {
                    -1 -> speech = false
                    1, 2 -> speech = entity == 1
                }
            }
        }
        entry(2, "phoneCall")?.let { state -> entity(state["entity"])?.let { phoneCall = it != 0 } }
    }

    fun clear() { speech = false; phoneCall = false }

    private fun entity(value: Any?): Int? = when (value) {
        "controller" -> 1
        "accessory" -> 2
        "n/a" -> 0
        else -> integer(value)?.takeIf { it in 0..2 }
    }
    private fun integer(value: Any?): Int? = (value as? Number)?.toDouble()
        ?.takeIf { it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE && it == it.toInt().toDouble() }?.toInt()
}
