package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.airplay.ultra.UltraVehicleControl.*
import org.junit.Assert.*
import org.junit.Test

class VoyahVehicleControlSchemaTest {
    @Test fun carplayMassageNeverRoutesToHeatingOrVentilationAndProgramDoesNotTurnItOn() {
        val driver = VoyahVehicleControlSchema.command(DRIVER_MASSAGE, 0)!!
        assertEquals("seat-level", driver.name); assertEquals(0, driver.side)
        assertEquals(2, driver.function); assertEquals(0, driver.value)
        val passenger = VoyahVehicleControlSchema.command(PASSENGER_MASSAGE, 3)!!
        assertEquals("seat-level", passenger.name); assertEquals(1, passenger.side); assertEquals(2, passenger.function)
        val program = VoyahVehicleControlSchema.command(PASSENGER_MASSAGE_PROGRAM, 2)!!
        assertEquals("seat-program", program.name); assertEquals(1, program.side); assertEquals(2, program.value)
        assertEquals("light-mode", VoyahVehicleControlSchema.command(LIGHT_MODE, 3)!!.name)
        assertNull(VoyahVehicleControlSchema.command(DRIVER_MASSAGE_PROGRAM, 0))
        assertNull(VoyahVehicleControlSchema.command(CAR_HOME, 1))
        assertNull(VoyahVehicleControlSchema.command(VOYAH_SETTINGS, 1))
    }
    private val raw = mapOf("FRONT_SEAT_MASS_SWITCH_LEFT" to 2, "FRONT_SEAT_MASS_INTEN_LEFT" to 2,
        "FRONT_SEAT_MASS_COMMAND_LEFT" to 1, "FRONT_SEAT_MASS_SWITCH_RIGHT" to 1,
        "FRONT_SEAT_MASS_INTEN_RIGHT" to 3, "FRONT_SEAT_MASS_COMMAND_RIGHT" to 2, "DRIVING_MODE_SET" to 2)

    @Test fun massageUsesItsOwnSwitchIntensityAndProgramForEachSide() {
        val actual = VoyahVehicleControlSchema.readings(VoyahComfortValues(raw, true))
        assertEquals(mapOf(DRIVER_MASSAGE to 2, DRIVER_MASSAGE_PROGRAM to 1,
            PASSENGER_MASSAGE to 0, PASSENGER_MASSAGE_PROGRAM to 2), actual)
        // An off seat retains intensity 3; this must not make the CarPlay control active.
        val feedback = mapOf("MASS_L" to 2, "MASS_LEVEL_L" to 2, "MASS_PROGRAM_L" to 1,
            "MASS_R" to 1, "MASS_LEVEL_R" to 3, "MASS_PROGRAM_R" to 2)
        assertEquals(actual, VoyahVehicleControlSchema.readback(feedback))
    }
    @Test fun missingSwitchInvalidIntensityAndMissingPermissionNeverBecomeOff() {
        assertTrue(VoyahVehicleControlSchema.readings(VoyahComfortValues(raw, false)).isEmpty())
        assertTrue(VoyahVehicleControlSchema.readings(null).isEmpty())
        val invalid = raw - "FRONT_SEAT_MASS_SWITCH_LEFT" + ("FRONT_SEAT_MASS_COMMAND_RIGHT" to 3)
        val actual = VoyahVehicleControlSchema.readings(VoyahComfortValues(invalid, true))
        assertFalse(actual.containsKey(DRIVER_MASSAGE))
        assertFalse(actual.containsKey(PASSENGER_MASSAGE_PROGRAM))
        assertFalse(VoyahVehicleControlSchema.readback(mapOf("MASS_L" to 2, "MASS_LEVEL_L" to 4)).containsKey(DRIVER_MASSAGE))
    }
    @Test fun batteryElectricRangeAndH97cDriveModeReachTheVehicleSnapshot() {
        val snapshot = VoyahValues(64.6f, 41f, 22f, 22f, 3, 1_000, null, null,
            VoyahComfortValues(raw, true), null, null,
            mapOf("mEVCruisingRang_real" to 53, "mEVCruisingRange" to 3943, "mCanTravelMileage" to 568), null)
        val v = VoyahVehicleSchema.readings(snapshot, null, null)
        assertEquals(65L, v.batteryPercent)
        assertEquals(53L, v.electricRangeKm)
        assertEquals(568L, v.fuelRangeKm)
        assertEquals(621L, v.rangeKm)
        assertEquals(2, v.driveMode)
        assertNull(VoyahComfortValues(mapOf("DRIVING_MODE_SET" to 0), true).driveMode())
        assertNull(VoyahComfortValues(mapOf("DRIVING_MODE_SET" to 7), true).driveMode())
    }
}
