package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahPiEquipmentTest {
    private val wheel = UltraClimateControl.WHEEL_HEAT
    private fun accessory(heatedWheel: Boolean, write: (UltraClimateControl, Int) -> UltraClimateWriteResult) =
        VoyahClimateAccessory({ UltraClimateSample(23.0, 1000,
            UltraClimateControl.entries.associateWith { 1 }) }, { 1000 },
            UltraClimateControl.entries.filter { heatedWheel || !it.isWheel }.toSet(), write)

    @Test fun piCatalogOmitsTheWholeWheelServiceAndItsCharacteristicsWhileRuRetainsThem() {
        for (equipped in listOf(false, true)) {
            val config = CafConfigTree.decode(accessory(equipped) { _, _ -> error("No writes on config") }.config())
            val services = config.accessories.flatMap { it.services }
            assertEquals(equipped, services.any { it.iid.value == wheel.serviceIid })
            val chars = services.flatMap { it.characteristics }.map { it.iid.value }
            for (id in listOf(wheel.onIid, wheel.levelIid))
                assertEquals(equipped, id in chars)
            assertFalse(wheel.layoutIid in chars || wheel.autoIid in chars)
            assertTrue(UltraClimateControl.entries.filterNot { it.isWheel }.all { it.levelIid in chars })
        }
    }

    @Test fun aStalePhoneWheelWriteOnPiCannotReachTheOemWriter() {
        var writes = 0
        val a = accessory(false) { _, _ -> writes++; UltraClimateWriteResult(true, "unexpected") }
        val request = CarAccessoryMessages.reader(CarAccessoryMessages.writeRequest(0, 3, mapOf(wheel.levelIid to 1)).rcsBody)
        val result = CarAccessoryMessages.reader(a.handle(request)!!.rcsBody)
        assertEquals(-50L, result.errors()[wheel.levelIid])
        assertEquals(0, writes)
    }
}
