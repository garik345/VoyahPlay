package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*

/** OEM cache's airLeftTemperature is a set point, not a cabin temperature sensor. */
data class UltraClimateSample(val driverTargetCelsius: Double, val sampledAtMillis: Long)

/** One explicitly experimental, read-only CAF climate accessory. No CAN/Binder write path. */
internal class VoyahClimateAccessory(
    private val sample: () -> UltraClimateSample?,
    private val now: () -> Long,
) {
    private val subscriptions = linkedSetOf<Long>()
    private var lastSent: Map<Long, Any?> = emptyMap()

    fun config(): Map<String, Any?> = linkedMapOf(
        "accessories" to listOf(linkedMapOf(
            "type" to 0x01000001L, "iid" to 1L, "version" to "1.0",
            "services" to listOf(linkedMapOf(
                "type" to 0x11000002L, "iid" to 10L,
                "characteristics" to listOf(
                    characteristic(TARGET_IID, 0x31000017L, target()).apply {
                        put("minimumValue", 16.0); put("maximumValue", 32.0)
                        put("stepValue", 0.5); put("supportsInvalid", true)
                    },
                    characteristic(MIN_IID, 0x30000054L, 16.0),
                    characteristic(MAX_IID, 0x30000055L, 32.0),
                ),
            )),
        )),
    )

    private fun characteristic(iid: Long, type: Long, value: Double?): MutableMap<String, Any?> =
        linkedMapOf<String, Any?>(
            "iid" to iid, "type" to type, "format" to CafCharacteristicFormat.FLOAT.wireValue,
            "writable" to false, "mutable" to (iid == TARGET_IID),
        ).apply { if (value != null) put("initialValue", value) }

    fun handle(message: CafMessageReader): CafRcsFrame? {
        val transaction = message.transactionId
        if (message.pluginId != PLUGIN_ID) return transaction?.let {
            CarAccessoryMessages.generalError(message.pluginId, PARAMETER_ERROR, it)
        }
        return when (message.command) {
            CafCommand.CONFIG_REQUEST -> CarAccessoryMessages.configResponse(PLUGIN_ID, message.requireTransactionId(), config())
            CafCommand.READ_REQUEST -> {
                val ids = identifiers(message)
                CarAccessoryMessages.readResponse(PLUGIN_ID, message.requireTransactionId(), values(ids), errors(ids))
            }
            CafCommand.REGISTER_REQUEST -> {
                val ids = if (message.values() == "*") ALL_IIDS.toList() else identifiers(message)
                subscriptions.addAll(ids.filter { it in ALL_IIDS })
                val current = values(ids)
                lastSent = lastSent + current
                CarAccessoryMessages.registerResponse(PLUGIN_ID, message.requireTransactionId(), current, errors(ids))
            }
            CafCommand.UNREGISTER_REQUEST -> {
                subscriptions.removeAll(identifiers(message).toSet())
                CarAccessoryMessages.unregisterResponse(PLUGIN_ID, message.requireTransactionId())
            }
            CafCommand.WRITE_REQUEST, CafCommand.CONTROL_REQUEST ->
                CarAccessoryMessages.generalError(PLUGIN_ID, PARAMETER_ERROR, message.requireTransactionId())
            else -> null // Notifications/responses do not invent a successful vehicle operation.
        }
    }

    fun changed(): CafRcsFrame? {
        val values = values(subscriptions.toList())
        val changed = values.filter { (iid, value) -> !lastSent.containsKey(iid) || lastSent[iid] != value }
        if (changed.isEmpty()) return null
        lastSent = lastSent + changed
        return CarAccessoryMessages.updateNotify(PLUGIN_ID, changed)
    }

    private fun target(): Double? {
        val current = sample() ?: return null
        val age = now() - current.sampledAtMillis
        return current.driverTargetCelsius.takeIf { it.isFinite() && it in 16.0..32.0 && age in 0..MAX_AGE_MILLIS }
    }

    private fun identifiers(message: CafMessageReader): List<Long> =
        message.valuesList().map { CafNumbers.toLong(it, "climate IID") }

    private fun values(ids: List<Long>): Map<Long, Any?> = ids.filter { it in ALL_IIDS }.associateWith {
        when (it) { TARGET_IID -> target(); MIN_IID -> 16.0; else -> 32.0 }
    }
    private fun errors(ids: List<Long>): Map<Long, Long> =
        ids.filter { it !in ALL_IIDS }.associateWith { PARAMETER_ERROR }

    companion object {
        const val PLUGIN_ID = 1L // Local test ID, not a registered Voyah/Apple OEM identifier.
        const val TARGET_IID = 11L
        const val MIN_IID = 12L
        const val MAX_IID = 13L
        const val MAX_AGE_MILLIS = 6_000L
        const val PARAMETER_ERROR = -50L
        val ALL_IIDS = setOf(TARGET_IID, MIN_IID, MAX_IID)
    }
}
