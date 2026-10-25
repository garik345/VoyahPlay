package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahUltraClimateStateTest {
    private val now = 1000L
    private val vent = UltraClimateControl.DRIVER_VENT
    private var sample = UltraClimateSample(23.0, now, comfortLevels = mapOf(vent to 0),
        comfortSelectedLevels = mapOf(vent to 2),
        hvacValues = mapOf(UltraHvacControl.RECIRCULATION to true),
        readings = mapOf(UltraClimateReading.RECIRCULATION_AUTO to false))
    private val writes = mutableListOf<Int>()
    private fun accessory() = VoyahClimateAccessory({ sample }, { now }, setOf(vent), { _, level ->
        writes += level
        sample = sample.copy(comfortLevels = mapOf(vent to level),
            comfortSelectedLevels = if (level > 0) mapOf(vent to level) else sample.comfortSelectedLevels)
        UltraClimateWriteResult(true, "readback")
    }, setOf(UltraHvacControl.RECIRCULATION), { _, _ -> UltraClimateWriteResult(true, "readback") })
    private fun read(frame: CafRcsFrame) = CarAccessoryMessages.reader(frame.rcsBody)
    private fun request(a: VoyahClimateAccessory, frame: CafRcsFrame) = read(a.handle(read(frame))!!)
    private fun follow(a: VoyahClimateAccessory, ids: List<Int>) =
        request(a, CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(ids.map(Int::toLong))))
    private fun write(a: VoyahClimateAccessory, fields: Map<Long, Any?>) =
        request(a, CarAccessoryMessages.writeRequest(0, 2, fields))

    @Test fun automaticModeIsDistinctFromBothManualModesAndNeverConfirmsAManualRequest() {
        assertEquals(VoyahRecirculation.State(true, false), VoyahRecirculation.decode(0))
        assertEquals(VoyahRecirculation.State(false, false), VoyahRecirculation.decode(1))
        assertEquals(VoyahRecirculation.State(false, true), VoyahRecirculation.decode(2))
        assertFalse(VoyahRecirculation.matchesManual(2, false))
        assertFalse(VoyahRecirculation.matchesManual(2, true))
        assertTrue(VoyahRecirculation.matchesManual(0, true))
        assertTrue(VoyahRecirculation.matchesManual(1, false))
        assertFalse(VoyahRecirculation.matchesManual(0, false))
        assertFalse(VoyahRecirculation.matchesManual(1, true))
        for (bad in listOf(null, -1, 3, 1.5, Double.NaN)) assertNull(VoyahRecirculation.decode(bad))
    }

    @Test fun automaticPolicyDoesNotAdvertiseAnActiveRecirculationIcon() {
        val a = accessory()
        val registered = follow(a, listOf(71,72))
        assertEquals(mapOf(71L to true), registered.valuesMap())
        assertEquals(mapOf(72L to -50L), registered.errors())
        sample = sample.copy(hvacValues = mapOf(UltraHvacControl.RECIRCULATION to false),
            readings = mapOf(UltraClimateReading.RECIRCULATION_AUTO to true))
        assertEquals(mapOf(71L to false), read(a.changed()!!).valuesMap())
        assertEquals(-50L, write(a, mapOf(72L to false)).errors()[72L])
        val service = CafConfigTree.decode(a.config()).accessories.flatMap { it.services }.single { it.iid.value == 70L }
        assertEquals(listOf(0x30000002L), service.characteristics.map { it.type.value })
        assertEquals(true, service.characteristics.single().writable)
    }

    @Test fun seatFanAdvertisesActualLevelIncludingOffWithoutAnIndependentSwitch() {
        val a = accessory()
        val service = CafConfigTree.decode(a.config()).accessories.flatMap { it.services }.single { it.iid.value == 230L }
        val level = service.characteristics.single { it.iid.value == 231L }
        assertEquals(0x31000012L, level.type.value)
        assertEquals(CafCharacteristicFormat.UINT8, level.format)
        assertEquals(CafFormatValue.Unsigned(java.math.BigInteger.ZERO, 8), level.minimumValue)
        assertFalse(service.characteristics.any { it.iid.value == 232L })
        assertEquals(mapOf(231L to 0L, 234L to false), follow(a, listOf(231,234)).valuesMap())
        assertEquals(-50L, write(a, mapOf(232L to true)).errors()[232L])
        assertEquals(-50L, write(a, mapOf(234L to true)).errors()[234L])
        assertTrue(writes.isEmpty())
    }

    @Test fun actualLevelCyclesDownAndOffWithoutTurningEveryPressIntoMaximum() {
        val a = accessory()
        follow(a, listOf(231))
        for (level in listOf(3,2,1,0,3)) {
            assertEquals(0L, write(a, mapOf(231L to level)).errors()[231L])
            assertEquals(mapOf(231L to level.toLong()), read(a.changed()!!).valuesMap())
        }
        assertEquals(listOf(3,2,1,0,3), writes)
        // A repeated absolute request stays idempotent and still gets a readback confirmation.
        write(a, mapOf(231L to 3))
        assertEquals(3, writes.last())
        assertEquals(mapOf(231L to 3L), read(a.changed()!!).valuesMap())
        // OEM controls changing the state must update the same characteristic.
        sample = sample.copy(comfortLevels = mapOf(vent to 2))
        assertEquals(mapOf(231L to 2L), read(a.changed()!!).valuesMap())
        sample = sample.copy(comfortLevels = mapOf(vent to 0), comfortSelectedLevels = mapOf(vent to 2))
        assertEquals(mapOf(231L to 0L), read(a.changed()!!).valuesMap())
    }

    @Test fun staleComfortStateCannotBeDisplayedAsAnActiveFan() {
        sample = sample.copy(comfortSampledAtMillis = -6000)
        val a = accessory()
        val values = follow(a, listOf(231,232))
        assertTrue(values.valuesMap().isEmpty())
        assertEquals(mapOf(231L to -6727L, 232L to -50L), values.errors())
    }
}
