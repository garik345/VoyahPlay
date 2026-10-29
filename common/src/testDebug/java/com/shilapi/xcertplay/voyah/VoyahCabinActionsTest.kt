package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.airplay.ultra.UltraVehicleControl.*
import org.junit.Assert.*
import org.junit.Test

class VoyahCabinActionsTest {
    @Test fun colourPressSelectsNextNamedPresetAndWrapsFromRed() {
        for ((current, next) in listOf(1 to 8, 8 to 10, 10 to 19, 19 to 30, 30 to 49, 49 to 54, 54 to 61, 61 to 64, 64 to 1, 20 to 30)) {
            for (token in listOf(0, 1, 255)) assertEquals(AMBIENT_COLOR to next,
                VoyahVehicleControlSchema.resolveAction(AMBIENT_NEXT_COLOR, token, mapOf(AMBIENT_COLOR to current)))
        }
        assertNull(VoyahVehicleControlSchema.resolveAction(AMBIENT_NEXT_COLOR, 1, emptyMap()))
        assertNull(VoyahVehicleControlSchema.resolveAction(AMBIENT_NEXT_COLOR, 1, mapOf(AMBIENT_COLOR to -1)))
        assertNull(VoyahVehicleControlSchema.command(AMBIENT_NEXT_COLOR, 1))
    }
    @Test fun offButtonAlwaysResolvesToAbsoluteOffAndRequiresReadback() {
        for (token in listOf(0, 1, 255)) assertEquals(AROMA_LEVEL to 0,
            VoyahVehicleControlSchema.resolveAction(AROMA_OFF, token, emptyMap()))
        val command = VoyahVehicleControlSchema.command(AROMA_LEVEL, 0)!!
        assertEquals("aroma-level", command.name)
        assertEquals(0, command.value)
        assertNull(VoyahVehicleControlSchema.command(AROMA_OFF, 1))
        val states = mapOf("AROMA_SWITCH" to 1, "AROMA_INTENSITY" to 3)
        assertFalse(VoyahVehicleControlSchema.confirms(AROMA_LEVEL, 0, states))
        assertTrue(VoyahVehicleControlSchema.confirms(AROMA_LEVEL, 0, states + ("CABIN_CONFIRMED" to 1)))
    }
    @Test fun unverifiedSmartIsNotASelectablePowertrainMode() {
        assertNull(VoyahVehicleControlSchema.command(HYBRID_MODE, 1))
        for (mode in 2..4) assertEquals(mode, VoyahVehicleControlSchema.command(HYBRID_MODE, mode)!!.value)
    }
}
