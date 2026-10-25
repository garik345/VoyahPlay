package com.shilapi.xcertplay.voyah

import org.junit.Assert.*
import org.junit.Test

/** The conversions are tested in shared (VoyahVehicleOemTest); this checks the snapshot wiring only. */
class VoyahVehicleSchemaTest {
    @Test fun aSnapshotBecomesThePhonesVehicleReadings() {
        val tpms = (0..6).associate { "tpmsStatus$it" to -1 as Number } + mapOf("leftFrontPressure" to 2.5f,
            "rightFrontPressure" to 2.5f, "leftRearPressure" to 2.5f, "rightRearPressure" to 2.6f)
        val values = VoyahValues(50f, 40.6f, 22f, 22f, 3, 1000, null, null, null, null, null,
            mapOf("mBeenTravelingMileage" to 15278, "mCanTravelMileage" to 188), tpms)
        val readings = VoyahVehicleSchema.readings(values, 35L, false)
        assertEquals(listOf(250L, 250L, 250L, 260L), readings.tirePressureKpa)
        assertEquals(41L, readings.fuelPercent)
        assertEquals(15278L, readings.odometerKm)
        assertEquals(188L, readings.fuelRangeKm)
        assertNull(readings.rangeKm)
    }
}
