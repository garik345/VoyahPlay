package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import kotlin.math.abs

/** Elapsed timestamps and actual OEM cache values; no fabricated sensor readings. */
data class UltraClimateSample(
    val driverTargetCelsius: Double, val sampledAtMillis: Long,
    val comfortLevels: Map<UltraClimateControl, Int> = emptyMap(),
    val comfortSampledAtMillis: Long = sampledAtMillis,
    val hvacValues: Map<UltraHvacControl, Any?> = emptyMap(),
    val hvacSampledAtMillis: Long = sampledAtMillis,
)
enum class UltraClimateControl(val serviceIid: Long, val maxLevel: Int) {
    DRIVER_HEAT(210, 3), PASSENGER_HEAT(220, 3), DRIVER_VENT(230, 3), PASSENGER_VENT(240, 3), WHEEL_HEAT(30, 1);
    val levelIid get() = serviceIid + 1
    val onIid get() = serviceIid + 2
    val layoutIid get() = serviceIid + 3
    val isWheel get() = this == WHEEL_HEAT
    val isVent get() = this == DRIVER_VENT || this == PASSENGER_VENT
    val isPassenger get() = this == PASSENGER_HEAT || this == PASSENGER_VENT
}
/** The H97C service has no implementation of AC_MAX_SWITCH: it is deliberately absent. */
enum class UltraHvacControl(val iid: Long, val format: CafCharacteristicFormat) {
    DRIVER_TARGET(11, CafCharacteristicFormat.FLOAT), PASSENGER_TARGET(21, CafCharacteristicFormat.FLOAT),
    POWER(41, CafCharacteristicFormat.BOOL), AC(42, CafCharacteristicFormat.BOOL),
    FAN(51, CafCharacteristicFormat.UINT8), AUTO(61, CafCharacteristicFormat.BOOL), RECIRCULATION(71, CafCharacteristicFormat.BOOL),
    FRONT_DEFROST(81, CafCharacteristicFormat.UINT8), REAR_DEFROST(91, CafCharacteristicFormat.UINT8);
    fun normalize(value: Any?): Any? = when (format) {
        CafCharacteristicFormat.BOOL -> value as? Boolean
        CafCharacteristicFormat.FLOAT -> (value as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it in MIN_TARGET..MAX_TARGET && abs(it * 2 - kotlin.math.round(it * 2)) < 0.00001
        }
        else -> (value as? Number)?.toDouble()?.takeIf {
            it.isFinite() && it == kotlin.math.floor(it) && it in 0.0..(if (this == FAN) 8.0 else 1.0)
        }?.toLong()
    }
    companion object { const val MIN_TARGET = 15.5; const val MAX_TARGET = 30.5 }
}
data class UltraClimateWriteResult(val confirmed: Boolean, val message: String)

/** One channel's subscriptions; success is exclusively a confirmed OEM readback. */
internal class VoyahClimateAccessory(
    private val sample: () -> UltraClimateSample?, private val now: () -> Long,
    private val supportedControls: Set<UltraClimateControl> = emptySet(),
    private val writer: ((UltraClimateControl, Int) -> UltraClimateWriteResult)? = null,
    private val supportedHvac: Set<UltraHvacControl> = emptySet(),
    private val hvacWriter: ((UltraHvacControl, Any) -> UltraClimateWriteResult)? = null,
) {
    private val subscriptions = linkedSetOf<Long>()
    private var lastSent: Map<Long, Any?> = emptyMap()
    private val knownIds: Set<Long> get() = FIXED_IDS + UltraHvacControl.entries.map { it.iid } + supportedControls.flatMap {
        listOf(it.levelIid, it.onIid) + if (it.isWheel) emptyList() else listOf(it.layoutIid) }
    fun config(): Map<String, Any?> {
        val climate = arrayListOf<Map<String, Any?>>(
            temperature(10, UltraHvacControl.DRIVER_TARGET, "seat_front_left"),
            temperature(20, UltraHvacControl.PASSENGER_TARGET, "seat_front_right"),
            service(40, 0x11000001, listOf(hvac(UltraHvacControl.POWER, 0x31000002), hvac(UltraHvacControl.AC, 0x31000003))),
            service(50, 0x11000007, listOf(hvac(UltraHvacControl.FAN, 0x31000012),
                characteristic(52, 0x30000002, CafCharacteristicFormat.BOOL, fanOn()),
                characteristic(53, 0x3000005F, CafCharacteristicFormat.BOOL, current(UltraHvacControl.AUTO)), layout(54, "seat_front"))),
            service(60, 0x11000008, listOf(hvac(UltraHvacControl.AUTO, 0x30000002))),
            service(70, 0x11000011, listOf(hvac(UltraHvacControl.RECIRCULATION, 0x30000002))),
            defrost(80, UltraHvacControl.FRONT_DEFROST, "windshield_front", 1),
            defrost(90, UltraHvacControl.REAR_DEFROST, "windshield_rear", 2))
        supportedControls.filter { it.isWheel }.forEach { climate.add(comfort(it)) }
        val accessories = arrayListOf(accessory(1, 0x01000001, climate), accessory(100, 0x01200002,
            listOf(service(101, 0x20000001, listOf(characteristic(102, 0x48000004, CafCharacteristicFormat.STRING, "5Seater", mutable = false))))))
        val seats = supportedControls.filterNot { it.isWheel }.map(::comfort)
        if (seats.isNotEmpty()) accessories.add(accessory(200, 0x01500001, seats))
        return linkedMapOf("accessories" to accessories)
    }
    private fun accessory(iid: Long, type: Long, services: List<Map<String, Any?>>) = linkedMapOf<String, Any?>(
        "type" to type, "iid" to iid, "version" to "1.0", "services" to services)
    private fun service(iid: Long, type: Long, chars: List<Map<String, Any?>>) = linkedMapOf<String, Any?>(
        "type" to type, "iid" to iid, "characteristics" to chars)
    private fun layout(iid: Long, key: String) = characteristic(iid, 0x36000065, CafCharacteristicFormat.STRING, key, mutable = false)
    private fun characteristic(iid: Long, type: Long, format: CafCharacteristicFormat, value: Any?,
        writable: Boolean = false, mutable: Boolean = true) = linkedMapOf<String, Any?>(
        "iid" to iid, "type" to type, "format" to format.wireValue, "writable" to writable,
        "oemWritable" to true, "mutable" to mutable, "priority" to 1L).apply {
        if (value != null) put("initialValue", value)
        if (mutable) put("supportsInvalid", true)
        if (format == CafCharacteristicFormat.STRING) put("maximumLength", 65535L)
    }
    private fun hvac(control: UltraHvacControl, type: Long) = characteristic(control.iid, type, control.format,
        current(control), control in supportedHvac && hvacWriter != null).apply {
        when (control.format) {
            CafCharacteristicFormat.FLOAT -> {
                put("minimumValue", UltraHvacControl.MIN_TARGET); put("maximumValue", UltraHvacControl.MAX_TARGET)
                put("stepValue", 0.5); put("units", 1082L)
            }
            CafCharacteristicFormat.UINT8 -> {
                put("minimumValue", 0L); put("maximumValue", if (control == UltraHvacControl.FAN) 8L else 1L); put("stepValue", 1L)
            }
            else -> Unit
        }
    }
    private fun temperature(iid: Long, control: UltraHvacControl, key: String) = service(iid, 0x11000002,
        listOf(hvac(control, 0x31000017),
            characteristic(iid + 2, 0x30000054, CafCharacteristicFormat.FLOAT, UltraHvacControl.MIN_TARGET, mutable = false),
            characteristic(iid + 3, 0x30000055, CafCharacteristicFormat.FLOAT, UltraHvacControl.MAX_TARGET, mutable = false),
            layout(iid + 4, key), characteristic(iid + 5, 0x30000002, CafCharacteristicFormat.BOOL, current(UltraHvacControl.POWER))))
    private fun defrost(iid: Long, control: UltraHvacControl, key: String, type: Long) = service(iid, 0x11000005,
        listOf(hvac(control, 0x30000004), layout(iid + 2, key),
            characteristic(iid + 3, 0x31000014, CafCharacteristicFormat.UINT8, type, mutable = false).apply {
                put("minimumValue", 0L); put("maximumValue", 7L); put("stepValue", 1L)
            }))
    private fun comfort(control: UltraClimateControl): Map<String, Any?> {
        val level = comfortLevel(control)
        val chars = arrayListOf<Map<String, Any?>>(characteristic(control.levelIid,
            if (control.isVent) 0x31000012 else 0x31000021,
            if (control.isVent) CafCharacteristicFormat.UINT8 else CafCharacteristicFormat.INT32,
            level?.toLong(), writer != null).apply { put("minimumValue", 0L); put("maximumValue", control.maxLevel.toLong()); put("stepValue", 1L) },
            characteristic(control.onIid, 0x30000002, CafCharacteristicFormat.BOOL, level?.let { it > 0 }, writer != null))
        if (!control.isWheel) chars.add(layout(control.layoutIid, if (control.isPassenger) "seat_front_right" else "seat_front_left"))
        return service(control.serviceIid, if (control.isWheel) 0x11000003 else if (control.isVent) 0x22000002 else 0x22000001, chars)
    }
    fun handle(message: CafMessageReader): CafRcsFrame? {
        if (message.pluginId != PLUGIN_ID) return message.transactionId?.let { CarAccessoryMessages.generalError(message.pluginId, PARAMETER_ERROR, it) }
        return when (message.command) {
            CafCommand.CONFIG_REQUEST -> CarAccessoryMessages.configResponse(PLUGIN_ID, message.requireTransactionId(), config())
            CafCommand.READ_REQUEST -> identifiers(message).let { CarAccessoryMessages.readResponse(PLUGIN_ID, message.requireTransactionId(), values(it), errors(it)) }
            CafCommand.REGISTER_REQUEST -> {
                val ids = if (message.values() == "*") knownIds.toList() else identifiers(message)
                val transaction = message.requireTransactionId()
                subscriptions.addAll(ids.filter { it in knownIds })
                val current = values(ids); lastSent = lastSent + current
                CarAccessoryMessages.registerResponse(PLUGIN_ID, transaction, current, errors(ids))
            }
            CafCommand.UNREGISTER_REQUEST -> {
                val transaction = message.requireTransactionId()
                subscriptions.removeAll(identifiers(message).toSet())
                CarAccessoryMessages.unregisterResponse(PLUGIN_ID, transaction)
            }
            CafCommand.WRITE_REQUEST -> write(message)
            CafCommand.CONTROL_REQUEST -> CarAccessoryMessages.generalError(PLUGIN_ID, PARAMETER_ERROR, message.requireTransactionId())
            else -> null
        }
    }
    private fun write(message: CafMessageReader): CafRcsFrame {
        val transaction = message.requireTransactionId() // Before any vehicle side effect.
        val requested = message.valuesMap()
        if (requested.size > 32) return CarAccessoryMessages.generalError(PLUGIN_ID, PARAMETER_ERROR, transaction)
        val results = requested.keys.associateWith { PARAMETER_ERROR }.toMutableMap()
        val sendHvac = hvacWriter
        val sendComfort = writer
        val conflictingHvac = requested[UltraHvacControl.POWER.iid] == false && (
            requested[UltraHvacControl.AC.iid] == true || requested[UltraHvacControl.AUTO.iid] == true ||
            ((requested[UltraHvacControl.FAN.iid] as? Number)?.toDouble() ?: 0.0) > 0 ||
            ((requested[UltraHvacControl.FRONT_DEFROST.iid] as? Number)?.toDouble() ?: 0.0) > 0)
        for (control in UltraHvacControl.entries) {
            if (conflictingHvac || control.iid !in requested || control !in supportedHvac || sendHvac == null) continue
            val wanted = control.normalize(requested[control.iid]) ?: continue
            val result = runCatching { sendHvac.invoke(control, wanted) }.getOrNull()
            results[control.iid] = if (result?.confirmed == true) 0L else UNAVAILABLE
        }
        for (control in supportedControls) {
            if (sendComfort == null) continue
            val ids = listOf(control.levelIid, control.onIid).filter { it in requested }
            if (ids.isEmpty()) continue
            val level = if (control.levelIid in requested) integerLevel(requested[control.levelIid], control.maxLevel) else null
            val on = requested[control.onIid] as? Boolean
            if (control.levelIid in requested && level == null || control.onIid in requested && on == null) continue
            if (level != null && on != null && on != (level > 0)) continue
            val wanted = level ?: if (on == false) 0 else comfortLevel(control)?.takeIf { it > 0 } ?: control.maxLevel
            val result = runCatching { sendComfort.invoke(control, wanted) }.getOrNull()
            ids.forEach { results[it] = if (result?.confirmed == true) 0L else UNAVAILABLE }
        }
        return CarAccessoryMessages.writeResponse(PLUGIN_ID, transaction, results)
    }
    private fun integerLevel(value: Any?, max: Int) = (value as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it in 0.0..max.toDouble() && it == kotlin.math.floor(it) }?.toInt()
    fun changed(): CafRcsFrame? {
        val current = values(subscriptions.toList())
        val changed = current.filter { (iid, value) -> !lastSent.containsKey(iid) || lastSent[iid] != value }
        if (changed.isEmpty()) return null
        lastSent = lastSent + changed
        return CarAccessoryMessages.updateNotify(PLUGIN_ID, changed)
    }
    private fun fresh(time: Long) = now() - time in 0..MAX_AGE_MILLIS
    private fun current(control: UltraHvacControl): Any? {
        val s = sample() ?: return null
        if (control == UltraHvacControl.DRIVER_TARGET && control !in s.hvacValues)
            return s.driverTargetCelsius.takeIf { fresh(s.sampledAtMillis) }?.let(control::normalize)
        return if (fresh(s.hvacSampledAtMillis)) control.normalize(s.hvacValues[control]) else null
    }
    private fun fanOn(): Boolean? {
        if (current(UltraHvacControl.POWER) == false) return false
        return (current(UltraHvacControl.FAN) as? Number)?.let { it.toInt() > 0 }
    }
    private fun comfortLevel(control: UltraClimateControl): Int? {
        val s = sample() ?: return null
        return s.comfortLevels[control]?.takeIf { fresh(s.comfortSampledAtMillis) && it in 0..control.maxLevel }
    }
    private fun identifiers(message: CafMessageReader) = message.valuesList().map { CafNumbers.toLong(it, "climate IID") }
    private fun values(ids: List<Long>): Map<Long, Any?> = ids.filter { it in knownIds }.associateWith { iid ->
        val hvac = UltraHvacControl.entries.find { it.iid == iid }
        val comfort = supportedControls.find { iid == it.levelIid || iid == it.onIid || !it.isWheel && iid == it.layoutIid }
        when {
            hvac != null -> current(hvac)
            comfort != null -> when (iid) {
                comfort.levelIid -> comfortLevel(comfort)?.toLong()
                comfort.onIid -> comfortLevel(comfort)?.let { it > 0 }
                else -> if (comfort.isPassenger) "seat_front_right" else "seat_front_left"
            }
            iid == 12L || iid == 22L -> UltraHvacControl.MIN_TARGET
            iid == 13L || iid == 23L -> UltraHvacControl.MAX_TARGET
            iid == 14L -> "seat_front_left"
            iid == 24L -> "seat_front_right"
            iid == 52L -> fanOn()
            iid == 53L -> current(UltraHvacControl.AUTO)
            iid == 54L -> "seat_front"
            iid == 82L -> "windshield_front"
            iid == 92L -> "windshield_rear"
            iid == 83L -> 1L
            iid == 93L -> 2L
            iid == 102L -> "5Seater"
            else -> current(UltraHvacControl.POWER)
        }
    }
    private fun errors(ids: List<Long>) = ids.filter { it !in knownIds }.associateWith { PARAMETER_ERROR }
    companion object {
        const val PLUGIN_ID = 0L
        const val PLUGIN_NAME = "org.voyahplay.climate.test"
        const val TARGET_IID = 11L
        const val MAX_AGE_MILLIS = 6_000L
        const val PARAMETER_ERROR = -50L
        const val UNAVAILABLE = -6727L
        private val FIXED_IDS = setOf(12L, 13L, 14L, 15L, 22L, 23L, 24L, 25L, 52L, 53L, 54L, 82L, 83L, 92L, 93L, 102L)
    }
}
