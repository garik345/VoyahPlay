package com.shilapi.xcertplay.airplay.ultra

import org.junit.Assert.*
import org.junit.Test

class VoyahHvacFanTest {
    @Test fun cacheLevelsUseTheOemOneBasedSetterAndCannotReachTheClamp() {
        for (level in 0L..7L) {
            assertEquals(level, UltraHvacControl.FAN.normalize(level))
            assertEquals(level.toInt() + 1, VoyahHvacFan.wireValue(level))
        }
        for (invalid in listOf(-1L, 8L, Long.MAX_VALUE)) {
            assertNull(UltraHvacControl.FAN.normalize(invalid))
            assertThrows(IllegalArgumentException::class.java) { VoyahHvacFan.wireValue(invalid) }
        }
    }
}
