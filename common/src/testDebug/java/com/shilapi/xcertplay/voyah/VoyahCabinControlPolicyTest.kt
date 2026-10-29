package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.airplay.ultra.UltraVehicleControl.*
import org.junit.Assert.*
import org.junit.Test

class VoyahCabinControlPolicyTest {
    private val current = mapOf("AMBIENT_SWITCH" to 1, "AMBIENT_COLOR" to 19, "AMBIENT_BRIGHTNESS" to 5,
        "AMBIENT_MUSIC" to 1, "AROMA_SWITCH" to 1, "AROMA_INTENSITY" to 3, "AROMA_TASTE" to 2, "AROMA_TIME" to 0)
    private fun steps(command: String, value: Int) = VoyahCabinControlPolicy.plan(command, value, current)!!.map { it.field to it.value }

    @Test fun absoluteIntensityIsSetBeforeEnablingAndOffPreservesTheSelectedCartridgeAndIntensity() {
        assertEquals(listOf("AROMA_INTENSITY" to 1, "AROMA_SWITCH" to 2), steps("aroma-level", 1))
        assertEquals(listOf("AROMA_INTENSITY" to 3, "AROMA_SWITCH" to 2), steps("aroma-level", 3))
        assertEquals(listOf("AROMA_SWITCH" to 1), steps("aroma-level", 0))
        assertEquals(listOf("AROMA_TASTE" to 3), steps("aroma-select", 3))
        assertEquals(listOf("AROMA_TIME" to 2), steps("aroma-duration", 2))
    }
    @Test fun manualColourAndBrightnessDoNotTurnOnTheLightsOrDisableEffectsImplicitly() {
        assertEquals(listOf("AMBIENT_SWITCH" to 2), steps("ambient-switch", 1))
        assertEquals(listOf("AMBIENT_SWITCH" to 1), steps("ambient-switch", 0))
        assertEquals(listOf("AMBIENT_COLOR" to 64), steps("ambient-color", 64))
        assertEquals(listOf("AMBIENT_BRIGHTNESS" to 1), steps("ambient-brightness", 1))
        for (music in listOf(0, 2, 3)) for (command in listOf("ambient-color", "ambient-brightness"))
            assertNull(VoyahCabinControlPolicy.plan(command, 1, current + ("AMBIENT_MUSIC" to music)))
    }
    @Test fun invalidValuesUnknownStateAndUnrecognizedCommandsProduceNoPlan() {
        for ((command, values) in mapOf("ambient-switch" to listOf(-1,2), "ambient-color" to listOf(0,65),
            "ambient-brightness" to listOf(0,11), "aroma-level" to listOf(-1,4),
            "aroma-select" to listOf(0,4), "aroma-duration" to listOf(-1,3)))
            for (value in values) assertNull(VoyahCabinControlPolicy.plan(command,value,current))
        assertNull(VoyahCabinControlPolicy.plan("seat-level",1,current))
        assertNull(VoyahCabinControlPolicy.plan("aroma-level",1,current - "AROMA_SWITCH"))
        assertNull(VoyahCabinControlPolicy.plan("aroma-level",1,current - "AROMA_INTENSITY"))
        assertNotNull(VoyahCabinControlPolicy.plan("aroma-level",0,current - "AROMA_INTENSITY"))
    }
    @Test fun inactiveRetainedIntensityCannotLookEnabledAndUnknownCannotLookOff() {
        val comfort = VoyahComfortValues(mapOf("FCM_SW_REQ" to 1, "IVI_FRAG_CONCERNTION" to 3,
            "IVI_FRAG_TASTE" to 2, "FCM_DURATION_CONTROL" to 0, "VEHICLE_AMBIENT_LIGHT_SWITCH" to 1,
            "VEHICLE_AMBIENT_LIGHT_COLOR" to 64, "VEHICLE_AMBIENT_LIGHT_BRIGHTNESS_LEVEL" to 7),true)
        val values = VoyahVehicleControlSchema.readings(comfort)
        assertEquals(0,values[AROMA_LEVEL]); assertEquals(2,values[AROMA_TASTE]); assertEquals(0,values[AROMA_TIME])
        assertEquals(0,values[AMBIENT_SWITCH]); assertEquals(7,values[AMBIENT_BRIGHTNESS]); assertEquals(64,values[AMBIENT_COLOR])
        assertNull(VoyahComfortValues(emptyMap(),true).aromaLevel())
        assertNull(VoyahComfortValues(emptyMap(),true).ambientOn())
    }
    @Test fun aGuardedOrFailedRequestCannotBeConfirmedByACoincidentallyMatchingCachedValue() {
        val stale = current + ("CABIN_CONFIRMED" to 0)
        assertFalse(VoyahVehicleControlSchema.confirms(AMBIENT_COLOR,19,stale))
        assertTrue(VoyahVehicleControlSchema.confirms(AMBIENT_COLOR,19,current + ("CABIN_CONFIRMED" to 1)))
        assertFalse(VoyahVehicleControlSchema.confirms(AROMA_LEVEL,3,current + ("CABIN_CONFIRMED" to 1)))
        assertTrue(VoyahVehicleControlSchema.confirms(AROMA_LEVEL,0,current + ("CABIN_CONFIRMED" to 1)))
    }
    @Test fun eachNewControlHasItsOwnCommandAndNeverReachesTheSeatCyclePath() {
        for ((control, command) in mapOf(AMBIENT_SWITCH to "ambient-switch", AMBIENT_BRIGHTNESS to "ambient-brightness",
            AMBIENT_COLOR to "ambient-color", AROMA_LEVEL to "aroma-level", AROMA_TASTE to "aroma-select", AROMA_TIME to "aroma-duration")) {
            assertTrue(control.isCabin)
            assertEquals(command, VoyahVehicleControlSchema.command(control,control.minimum)!!.name)
        }
    }
}
