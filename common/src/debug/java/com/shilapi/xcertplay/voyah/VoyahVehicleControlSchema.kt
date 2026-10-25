package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.airplay.ultra.UltraVehicleControl

/** Both the regular TX57 snapshot and VoyahControls read-back use identical state semantics. */
internal object VoyahVehicleControlSchema {
    data class Command(val name: String, val side: Int, val function: Int, val value: Int)
    fun command(control: UltraVehicleControl, value: Int): Command? {
        if (control.normalize(value) == null || control == UltraVehicleControl.CAR_HOME) return null
        return Command(when {
            control == UltraVehicleControl.LIGHT_MODE -> "light-mode"
            control.isProgram -> "seat-program"
            else -> "seat-level"
        }, if (control.isPassenger) 1 else 0, 2, value)
    }
    fun readings(comfort: VoyahComfortValues?): Map<UltraVehicleControl, Int> = buildMap {
        for (side in 0..1) {
            comfort?.massageLevel(side)?.let { put(if (side == 0) UltraVehicleControl.DRIVER_MASSAGE else UltraVehicleControl.PASSENGER_MASSAGE, it) }
            comfort?.massageProgram(side)?.let { put(if (side == 0) UltraVehicleControl.DRIVER_MASSAGE_PROGRAM else UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM, it) }
        }
    }
    fun readback(states: Map<String, Int>): Map<UltraVehicleControl, Int> = buildMap {
        for (side in 0..1) {
            val suffix = if (side == 0) "_L" else "_R"
            VoyahComfortValues.effectiveLevel(states["MASS$suffix"] ?: -1, states["MASS_LEVEL$suffix"] ?: -1)
                ?.let { put(if (side == 0) UltraVehicleControl.DRIVER_MASSAGE else UltraVehicleControl.PASSENGER_MASSAGE, it) }
            states["MASS_PROGRAM$suffix"]?.takeIf { it in 1..2 }
                ?.let { put(if (side == 0) UltraVehicleControl.DRIVER_MASSAGE_PROGRAM else UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM, it) }
        }
    }
}
