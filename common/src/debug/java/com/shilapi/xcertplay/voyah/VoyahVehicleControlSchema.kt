package com.shilapi.xcertplay.voyah

import com.shilapi.xcertplay.airplay.ultra.UltraVehicleControl

/** Both the regular TX57 snapshot and VoyahControls read-back use identical state semantics. */
internal object VoyahVehicleControlSchema {
    /** Button payloads are press tokens, never CAN values. Resolve before entering the writer. */
    fun resolveAction(control: UltraVehicleControl, token: Int, readings: Map<UltraVehicleControl, Int>): Pair<UltraVehicleControl, Int>? {
        if (control.normalize(token) == null) return null
        return when (control) {
            UltraVehicleControl.AROMA_OFF -> UltraVehicleControl.AROMA_LEVEL to 0
            UltraVehicleControl.AMBIENT_NEXT_COLOR -> {
                val current = readings[UltraVehicleControl.AMBIENT_COLOR]?.takeIf { it in 1..64 } ?: return null
                UltraVehicleControl.AMBIENT_COLOR to (AMBIENT_PALETTE.firstOrNull { it > current } ?: AMBIENT_PALETTE.first())
            }
            else -> control to token
        }
    }
    private val AMBIENT_PALETTE = listOf(1, 8, 10, 19, 30, 49, 54, 61, 64)
    fun confirms(control: UltraVehicleControl, wanted: Int, states: Map<String, Int>): Boolean {
        val mode = control == UltraVehicleControl.DRIVE_MODE || control == UltraVehicleControl.HYBRID_MODE
        return readback(states)[control] == wanted && (!mode || states["MODE_CONFIRMED"] == 1) && (!control.isCabin || states["CABIN_CONFIRMED"] == 1)
    }
    data class Command(val name: String, val side: Int, val function: Int, val value: Int)
    fun command(control: UltraVehicleControl, value: Int): Command? {
        if (control.normalize(value) == null || control.isButton) return null
        return Command(when {
            control == UltraVehicleControl.LIGHT_MODE -> "light-mode"
            control == UltraVehicleControl.DRIVE_MODE -> "drive-mode"
            control == UltraVehicleControl.HYBRID_MODE -> "hybrid-mode"
            control == UltraVehicleControl.AMBIENT_SWITCH -> "ambient-switch"
            control == UltraVehicleControl.AMBIENT_BRIGHTNESS -> "ambient-brightness"
            control == UltraVehicleControl.AMBIENT_COLOR -> "ambient-color"
            control == UltraVehicleControl.AROMA_LEVEL -> "aroma-level"
            control == UltraVehicleControl.AROMA_TASTE -> "aroma-select"
            control == UltraVehicleControl.AROMA_TIME -> "aroma-duration"
            control.isProgram -> "seat-program"
            else -> "seat-level"
        }, if (control.isPassenger) 1 else 0, 2, value)
    }
    fun readings(comfort: VoyahComfortValues?): Map<UltraVehicleControl, Int> = buildMap {
        comfort?.ambientOn()?.let { put(UltraVehicleControl.AMBIENT_SWITCH, it) }
        comfort?.ambientBrightness()?.let { put(UltraVehicleControl.AMBIENT_BRIGHTNESS, it) }
        comfort?.ambientColor()?.let { put(UltraVehicleControl.AMBIENT_COLOR, it) }
        comfort?.aromaLevel()?.let { put(UltraVehicleControl.AROMA_LEVEL, it) }
        comfort?.aromaTaste()?.let { put(UltraVehicleControl.AROMA_TASTE, it) }
        comfort?.aromaTime()?.let { put(UltraVehicleControl.AROMA_TIME, it) }
        comfort?.driveMode()?.let { put(UltraVehicleControl.DRIVE_MODE, it) }
        comfort?.hybridMode()?.let { put(UltraVehicleControl.HYBRID_MODE, it) }
        for (side in 0..1) {
            comfort?.massageLevel(side)?.let { put(if (side == 0) UltraVehicleControl.DRIVER_MASSAGE else UltraVehicleControl.PASSENGER_MASSAGE, it) }
            comfort?.massageProgram(side)?.let { put(if (side == 0) UltraVehicleControl.DRIVER_MASSAGE_PROGRAM else UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM, it) }
        }
    }
    fun readback(states: Map<String, Int>): Map<UltraVehicleControl, Int> = buildMap {
        when (states["AMBIENT_SWITCH"]) { 1 -> put(UltraVehicleControl.AMBIENT_SWITCH, 0); 2 -> put(UltraVehicleControl.AMBIENT_SWITCH, 1) }
        states["AMBIENT_BRIGHTNESS"]?.takeIf { it in 1..10 }?.let { put(UltraVehicleControl.AMBIENT_BRIGHTNESS, it) }
        states["AMBIENT_COLOR"]?.takeIf { it in 1..64 }?.let { put(UltraVehicleControl.AMBIENT_COLOR, it) }
        VoyahComfortValues.effectiveLevel(states["AROMA_SWITCH"] ?: -1, states["AROMA_INTENSITY"] ?: -1)?.let { put(UltraVehicleControl.AROMA_LEVEL, it) }
        states["AROMA_TASTE"]?.takeIf { it in 1..3 }?.let { put(UltraVehicleControl.AROMA_TASTE, it) }
        states["AROMA_TIME"]?.takeIf { it in 0..2 }?.let { put(UltraVehicleControl.AROMA_TIME, it) }
        states["DRIVE"]?.takeIf { it in 1..6 }?.let { put(UltraVehicleControl.DRIVE_MODE, it) }
        states["HYBRID"]?.takeIf { it in 1..4 }?.let { put(UltraVehicleControl.HYBRID_MODE, it) }
        for (side in 0..1) {
            val suffix = if (side == 0) "_L" else "_R"
            VoyahComfortValues.effectiveLevel(states["MASS$suffix"] ?: -1, states["MASS_LEVEL$suffix"] ?: -1)
                ?.let { put(if (side == 0) UltraVehicleControl.DRIVER_MASSAGE else UltraVehicleControl.PASSENGER_MASSAGE, it) }
            states["MASS_PROGRAM$suffix"]?.takeIf { it in 1..2 }
                ?.let { put(if (side == 0) UltraVehicleControl.DRIVER_MASSAGE_PROGRAM else UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM, it) }
        }
    }
}
