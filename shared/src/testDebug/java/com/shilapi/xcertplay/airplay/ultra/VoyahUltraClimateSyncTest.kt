package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.net.InetAddress
import java.net.InetSocketAddress
import org.junit.Assert.*
import org.junit.Test

class VoyahUltraClimateSyncTest {
    private val sync = UltraHvacControl.ZONES_SYNCED
    private var now = 1_000L
    private var sample: UltraClimateSample? = UltraClimateSample(22.5, now, hvacValues = mapOf(sync to false))
    private fun reader(frame: CafRcsFrame) = CarAccessoryMessages.reader(frame.rcsBody)
    private fun request(a: VoyahClimateAccessory, frame: CafRcsFrame) = reader(a.handle(reader(frame))!!)
    private fun follow(a: VoyahClimateAccessory) = request(a,
        CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(sync.iid))))
    private fun write(a: VoyahClimateAccessory, value: Any?) = request(a,
        CarAccessoryMessages.writeRequest(0, 2, mapOf(sync.iid to value)))
    private fun state(a: VoyahClimateAccessory) = request(a,
        CarAccessoryMessages.readRequest(0, 3, listOf(sync.iid)))

    @Test fun syncOnlyAdvertisesWritesWhenThatExactControlHasAWriter() {
        val writer = { _: UltraHvacControl, _: Any -> UltraClimateWriteResult(true, "readback") }
        for ((supported, send, writable) in listOf(
            Triple(setOf(sync), writer, true),
            Triple(setOf(UltraHvacControl.AC), writer, false),
            Triple(setOf(sync), null, false))) {
            val a = VoyahClimateAccessory({ sample }, { now }, supportedHvac = supported, hvacWriter = send)
            val service = CafConfigTree.decode(a.config()).accessories.flatMap { it.services }.single { it.iid.value == 95L }
            assertEquals(0x11000012L, service.type.value)
            val on = service.characteristics.single { it.iid.value == 96L }
            assertEquals(0x30000002L, on.type.value)
            assertEquals(CafCharacteristicFormat.BOOL, on.format)
            assertEquals(writable, on.writable)
            assertEquals(CafFormatValue.Bool(false), on.initialValue)
            assertEquals(CafFormatValue.Text("seat_front"), service.characteristics.single { it.iid.value == 97L }.initialValue)
            if (!writable) assertEquals(-50L, write(a, true).errors()[sync.iid])
        }
    }

    @Test fun bothSyncDirectionsReachTheVehicleAndConfirmTheActualState() {
        val writes = mutableListOf<Pair<UltraHvacControl, Any>>()
        val a = VoyahClimateAccessory({ sample }, { now }, supportedHvac = setOf(sync), hvacWriter = { control, value ->
            writes += control to value
            sample = sample!!.copy(hvacValues = mapOf(control to value))
            UltraClimateWriteResult(true, "actual DUAL readback")
        })
        assertEquals(mapOf(96L to false), follow(a).valuesMap())
        for (value in listOf(true, false, false)) {
            assertEquals(mapOf(96L to 0L), write(a, value).errors())
            assertEquals(mapOf(96L to value), reader(a.changed()!!).valuesMap())
            assertEquals(mapOf(96L to value), state(a).valuesMap())
            assertNull(a.changed())
        }
        assertEquals(listOf(sync to true, sync to false, sync to false), writes)
    }

    @Test fun failedOrLateSyncNeverEchoesTheRequestedStateAsApplied() {
        val a = VoyahClimateAccessory({ sample }, { now }, supportedHvac = setOf(sync), hvacWriter = { _, _ ->
            UltraClimateWriteResult(false, "pending or not confirmed")
        })
        follow(a)
        assertEquals(-6727L, write(a, true).errors()[96L])
        assertEquals(mapOf(96L to false), state(a).valuesMap())
        assertNull(a.changed())
        assertEquals(mapOf(96L to false), reader(a.refresh(listOf(96L))!!).valuesMap())
        sample = sample!!.copy(hvacValues = mapOf(sync to true))
        assertEquals(mapOf(96L to true), reader(a.changed()!!).valuesMap())
    }

    @Test fun staleUnknownAndMalformedSyncAreNotReportedAsOffOrSentAsCommands() {
        var writes = 0
        val a = VoyahClimateAccessory({ sample }, { now }, supportedHvac = setOf(sync), hvacWriter = { _, _ ->
            writes++; UltraClimateWriteResult(true, "")
        })
        follow(a)
        for (bad in listOf(0, 1, 0.5, "true", null)) assertEquals(-50L, write(a, bad).errors()[96L])
        assertEquals(0, writes)
        now += VoyahClimateAccessory.MAX_AGE_MILLIS + 1
        assertEquals(mapOf(96L to -6727L), state(a).errors())
        assertEquals(mapOf(96L to null), reader(a.changed()!!).valuesMap())
        sample = sample!!.copy(hvacValues = mapOf(sync to null), hvacSampledAtMillis = now)
        assertTrue(state(a).valuesMap().isEmpty())
        assertEquals(mapOf(96L to -6727L), state(a).errors())
    }

    @Test fun syncWritesAndOemUpdatesTravelAcrossBothEncryptedCafChannels() {
        val source = object : UltraClimateSource {
            override val supportedHvac = setOf(sync)
            lateinit var publish: (UltraClimateSample?) -> Unit
            override fun start(onSample: (UltraClimateSample?) -> Unit) { publish = onSample; publish(sample) }
            override fun writeHvac(control: UltraHvacControl, value: Any): UltraClimateWriteResult {
                assertEquals(sync, control)
                sample = sample!!.copy(hvacValues = mapOf(control to value))
                publish(sample)
                return UltraClimateWriteResult(true, "actual DUAL readback")
            }
            override fun close() {}
        }
        val p = VoyahUltraProtocol({ source }, { now }, {})
        val address = InetAddress.getByName("127.0.0.1")
        val rx = ByteArray(32) { it.toByte() }; val tx = ByteArray(32) { (it + 35).toByte() }
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        fun connect(type: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType): RcsChannel {
            val channel = p.openChannel(type, rx, tx, address, emptyMap())
            p.attachPending()
            return RcsChannel.connect(InetSocketAddress(address, channel["dataPort"] as Int), tx, rx)
        }
        fun next(phone: RcsChannel) = CarAccessoryMessages.reader(requireNotNull(phone.receiveNext(3_000)).body)
        fun update(phone: RcsChannel, expected: Boolean) {
            repeat(6) {
                val frame = next(phone)
                if (frame.command == CafCommand.UPDATE_NOTIFY && frame.valuesMap()[96L] == expected) return
            }
            fail("No actual SYNC update for $expected")
        }
        try {
            connect(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA).use { first ->
                connect(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2).use { second ->
                    for (phone in listOf(first, second)) {
                        phone.send(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(96L))).asRcsMessage())
                        assertEquals(mapOf(96L to false), next(phone).valuesMap())
                    }
                    first.send(CarAccessoryMessages.writeRequest(0, 2, mapOf(96L to true)).asRcsMessage())
                    assertEquals(mapOf(96L to 0L), next(first).errors())
                    update(first, true); update(second, true)
                    second.send(CarAccessoryMessages.writeRequest(0, 3, mapOf(96L to false)).asRcsMessage())
                    assertEquals(mapOf(96L to 0L), next(second).errors())
                    update(first, false); update(second, false)
                }
            }
        } finally { p.close() }
    }
}
