package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.CafCharacteristicFormat

/** CAF's documented-by-LIVI volume kinds; ringtone uses its labelled custom channel (6). */
enum class UltraAudioChannel(val serviceIid: Long, val cafType: Long, val oemStream: Int) {
    NAVIGATION(2368, 1, 1), PHONE(2384, 2, 6), SIRI(2400, 3, 22), RINGTONE(2416, 6, 2);
    val valueIid get() = serviceIid + 1
}

internal object VoyahAudioChannels {
    fun ids(ranges: Map<UltraAudioChannel, IntRange>) = ranges.keys.flatMap { c ->
        (1L..if (c == UltraAudioChannel.RINGTONE) 3L else 2L).map { c.serviceIid + it }
    }.toSet()

    fun services(ranges: Map<UltraAudioChannel, IntRange>, values: Map<UltraAudioChannel, Int>, ringtoneLabel: String): List<Map<String, Any?>> =
        ranges.map { (c, range) ->
            require(range.first >= 0 && range.last <= 255 && !range.isEmpty())
            linkedMapOf("iid" to c.serviceIid, "type" to 0x13000002L, "characteristics" to listOfNotNull(
                characteristic(c.valueIid, 0x33000006, values[c]?.takeIf { it in range }?.toLong(), true, range),
                characteristic(c.serviceIid + 2, 0x33000005, c.cafType, false, 0..7),
                if (c == UltraAudioChannel.RINGTONE) characteristic(c.serviceIid + 3, 0x30000001, ringtoneLabel, false) else null))
        }

    fun value(iid: Long, ranges: Map<UltraAudioChannel, IntRange>, values: Map<UltraAudioChannel, Int>, ringtoneLabel: String): Any? {
        val c = ranges.keys.firstOrNull { iid in it.valueIid..(it.serviceIid + if (it == UltraAudioChannel.RINGTONE) 3 else 2) } ?: return null
        return when (iid) {
            c.valueIid -> values[c]?.takeIf { it in ranges.getValue(c) }?.toLong()
            c.serviceIid + 2 -> c.cafType
            else -> ringtoneLabel
        }
    }

    private fun characteristic(iid: Long, type: Long, value: Any?, writable: Boolean, range: IntRange? = null) =
        linkedMapOf<String, Any?>("iid" to iid, "type" to type,
            "format" to (if (value is String) CafCharacteristicFormat.STRING else CafCharacteristicFormat.UINT8).wireValue,
            "writable" to writable, "oemWritable" to true, "mutable" to writable, "priority" to 1L).apply {
            if (value != null) put("initialValue", value)
            if (value is String) put("maximumLength", 65535L)
            if (writable) put("disabled", false)
            range?.let { put("minimumValue", it.first.toLong()); put("maximumValue", it.last.toLong()); put("stepValue", 1L) }
        }
}
