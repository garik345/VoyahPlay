package com.shilapi.xcertplay.airplay.ultra

import org.junit.Assert.*
import org.junit.Test

class VoyahVehicleOemTest {
    @Test fun batteryAndElectricRangeAreIndependentOfPetrolAndCombinedRange() {
        for (raw in listOf<Number>(-1, 1023, 4095, 1.5, Double.NaN))
            assertNull(VoyahVehicleOem.electricRangeKm(mapOf("mEVCruisingRang_real" to raw)))
        assertEquals(0L, VoyahVehicleOem.electricRangeKm(mapOf("mEVCruisingRang_real" to 0)))
        assertNull(VoyahVehicleOem.electricRangeKm(mapOf("mCanTravelMileage" to 568, "mEVCruisingRange" to 3943)))
        val v = VoyahVehicleOem.readings(41f, mapOf("mEVCruisingRang_real" to 53, "mCanTravelMileage" to 568),
            emptyMap(), null, null, battery = 65f)
        assertEquals(53L, v.electricRangeKm)
        assertEquals(65L, v.batteryPercent)
        assertEquals(41L, v.fuelPercent)
        assertEquals(568L, v.fuelRangeKm)
        assertEquals(621L, v.rangeKm)
    }

    @Test fun aTotalNeedsBothRangesAndNeverTreatsMissingOrInvalidFuelAsZero() {
        val electric = mapOf<String, Number>("mEVCruisingRang_real" to 75)
        assertEquals(629L, VoyahVehicleOem.rangeKm(electric + ("mCanTravelMileage" to 554)))
        assertEquals(75L, VoyahVehicleOem.rangeKm(electric + ("mCanTravelMileage" to 0)))
        assertNull(VoyahVehicleOem.rangeKm(electric))
        assertNull(VoyahVehicleOem.rangeKm(mapOf("mCanTravelMileage" to 554)))
        for (invalid in listOf<Number>(-1, 1023, 1.5, Double.NaN)) {
            assertNull(VoyahVehicleOem.fuelRangeKm(mapOf("mCanTravelMileage" to invalid)))
            assertNull(VoyahVehicleOem.rangeKm(electric + ("mCanTravelMileage" to invalid)))
        }
    }
    private fun tpms(fl: Float, fr: Float, rl: Float, rr: Float): Map<String, Number> =
        (0..6).associate { "tpmsStatus$it" to -1 as Number } +
            mapOf("leftFrontPressure" to fl, "rightFrontPressure" to fr, "leftRearPressure" to rl, "rightRearPressure" to rr)

    @Test fun tirePressuresInBarBecomeKilopascals() {
        // H97C log 28.01: 2.5 bar on every wheel; H97X 03.10: 2.6 / 2.6 / 2.6 / 2.7.
        assertEquals(listOf(260L, 260L, 260L, 270L), VoyahVehicleOem.tireKpa(tpms(2.6f, 2.6f, 2.6f, 2.7f)))
    }

    @Test fun aMissingOrImpossibleTirePressureIsNoReading() {
        assertEquals(listOf(null, null, null, 250L), VoyahVehicleOem.tireKpa(tpms(0f, Float.NaN, 26f, 2.5f)))
        assertEquals(listOf(null, null, null, null), VoyahVehicleOem.tireKpa(emptyMap()))
    }

    @Test fun odometerAndRangeFollowTheOemConventions() {
        val odometer = mapOf<String, Number>("mCanTravelMileage" to 188, "mBeenTravelingMileage" to 23347,
            "mTotalTravelingMileage" to 23347.0f, "mEVCruisingRange" to 122)
        assertEquals(23347L, VoyahVehicleOem.odometerKm(odometer))
        assertEquals(188L, VoyahVehicleOem.fuelRangeKm(odometer))
        assertNull(VoyahVehicleOem.rangeKm(odometer))
        // OEM log: mCanTravelMileage=1023 while the range was not available; 0 mileage is UNKNOWN.
        assertNull(VoyahVehicleOem.rangeKm(odometer + ("mCanTravelMileage" to 1023)))
        assertNull(VoyahVehicleOem.odometerKm(odometer + ("mBeenTravelingMileage" to 0)))
        assertNull(VoyahVehicleOem.rangeKm(emptyMap()))
    }

    @Test fun fuelIsThePercentageTheCarReports() {
        assertEquals(41L, VoyahVehicleOem.fuelPercent(40.6f))
        assertNull(VoyahVehicleOem.fuelPercent(null))
        assertNull(VoyahVehicleOem.fuelPercent(Float.NaN))
    }

    @Test fun theCarsObjectsBecomeThePhonesVehicleReadings() {
        val readings = VoyahVehicleOem.readings(40.6f, mapOf("mBeenTravelingMileage" to 15278, "mCanTravelMileage" to 1023),
            tpms(2.5f, 2.5f, 2.5f, 2.5f), 35L, false)
        assertEquals(listOf(250L, 250L, 250L, 250L), readings.tirePressureKpa)
        assertEquals(41L, readings.fuelPercent)
        assertEquals(15278L, readings.odometerKm)
        assertNull(readings.rangeKm)
        assertEquals(35L, readings.volumeLevel)
        assertEquals(false, readings.muted)
    }
}
