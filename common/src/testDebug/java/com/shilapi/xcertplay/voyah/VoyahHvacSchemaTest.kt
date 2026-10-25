package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.airplay.ultra.UltraHvacControl as C
import org.junit.Assert.*
import org.junit.Test

class VoyahHvacSchemaTest {
    @Test fun targetUsesTenthsExactlyOnceAndIndependentZones() {
        assertEquals(225, VoyahHvacSchema.wireValue(C.DRIVER_TARGET, 22.5))
        assertEquals(155, VoyahHvacSchema.wireValue(C.PASSENGER_TARGET, 15.5))
        assertEquals(305, VoyahHvacSchema.wireValue(C.DRIVER_TARGET, 30.5))
        assertEquals(VoyahHvacSchema.Field(2, 3, "airLeftTemperature"), VoyahHvacSchema.field(C.DRIVER_TARGET))
        assertEquals(VoyahHvacSchema.Field(3, 4, "airRightTemperature"), VoyahHvacSchema.field(C.PASSENGER_TARGET))
    }
    @Test fun buttonCommandsUseOnePulseForEitherDesiredState() {
        for (c in listOf(C.POWER, C.AC, C.AUTO, C.RECIRCULATION)) {
            assertEquals(1, VoyahHvacSchema.wireValue(c, false)); assertEquals(1, VoyahHvacSchema.wireValue(c, true))
        }
        assertEquals(1, VoyahHvacSchema.wireValue(C.FRONT_DEFROST, 1))
        assertEquals(0, VoyahHvacSchema.wireValue(C.REAR_DEFROST, 0))
        assertEquals(8, VoyahHvacSchema.wireValue(C.FAN, 7))
        assertEquals(5, VoyahHvacSchema.wireValue(C.FAN, 4))
    }
    @Test fun circulationDoesNotRepeatAPulseWithoutConfirmedProgress() {
        assertTrue(VoyahHvacSchema.cycleAdvanced(0, 2))
        assertTrue(VoyahHvacSchema.cycleAdvanced(2, 1))
        assertFalse(VoyahHvacSchema.cycleAdvanced(1, 1))
        assertFalse(VoyahHvacSchema.cycleAdvanced(1, -1))
        assertFalse(VoyahHvacSchema.cycleAdvanced(1, null))
        assertEquals(1, VoyahHvacSchema.wireValue(C.FRONT_DEFROST, 0))
    }
    @Test fun automaticRecirculationHasItsOwnFlagAndIsNotAnUnknownManualState() {
        for (mode in 0..2) {
            val raw = mapOf("airCirculationMode" to mode)
            assertEquals(mode == 0, VoyahHvacSchema.decode(raw)[C.RECIRCULATION])
            assertEquals(mode == 2, VoyahHvacSchema.decodeReadings(raw)[
                com.shilapi.xcertplay.airplay.ultra.UltraClimateReading.RECIRCULATION_AUTO])
        }
        assertNull(VoyahHvacSchema.decode(mapOf("airCirculationMode" to -1))[C.RECIRCULATION])
    }
    @Test fun allReviewedAirEnumOrdinalsMatchSdkAndCanApk() {
        assertEquals(listOf(2,3,7,4,0,6,10,8,9), C.entries.map { VoyahHvacSchema.field(it).ordinal })
        assertEquals(listOf(3,4,8,5,1,7,11,9,10), C.entries.map { VoyahHvacSchema.field(it).id })
    }
    @Test fun realH97cCacheFlagsAreNotSeatSwitchOneTwoFlags() {
        val values = VoyahHvacSchema.decode(mapOf("airSWStatus" to 1,"airACStatus" to 0,"airMode" to 1,
            "airCirculationMode" to 1,"airLeftTemperature" to 22.5f,"airRightTemperature" to 23.0f,"airWindSpeed" to 4))
        assertEquals(true, values[C.POWER]); assertEquals(false, values[C.AC]); assertEquals(true, values[C.AUTO])
        assertEquals(false, values[C.RECIRCULATION]); assertEquals(22.5, values[C.DRIVER_TARGET]); assertEquals(4L, values[C.FAN])
        assertNull(values[C.FRONT_DEFROST])
    }
    @Test fun unknownAndInvalidStatesStayUnknown() {
        val values = VoyahHvacSchema.decode(mapOf("airSWStatus" to 2,"airCirculationMode" to 3,
            "airLeftTemperature" to Float.NaN,"airRightTemperature" to 0,"airWindSpeed" to 15))
        assertTrue(values.values.all { it == null })
        for ((c,v) in listOf(C.DRIVER_TARGET to 22.25,C.DRIVER_TARGET to 32.0,C.FAN to -1,C.AC to 2)) {
            try { VoyahHvacSchema.wireValue(c,v); fail() } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun snapshotCopiesRawCacheWithoutChangingLegacyDisplayRanges() {
        val raw = hashMapOf<String,Number>("airLeftTemperature" to 15.5f)
        val snapshot = VoyahValues(50f,25f,15.5f,24f,3,1000,null,null,null,raw)
        raw["airLeftTemperature"] = 29f
        assertNull(snapshot.left)
        assertEquals(15.5, VoyahHvacSchema.decode(snapshot.climateValues())[C.DRIVER_TARGET])
        try { (snapshot.climateValues() as MutableMap)["airLeftTemperature"] = 29f; fail() } catch (_: UnsupportedOperationException) { }
    }
}
