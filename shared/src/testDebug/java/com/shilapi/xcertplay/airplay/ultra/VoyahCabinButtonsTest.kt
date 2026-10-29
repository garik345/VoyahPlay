package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahCabinButtonsTest {
    @Test fun colourButtonShowsActualColourAndPublishesOnlyConfirmedPresses() {
        val c = UltraVehicleControl.AMBIENT_NEXT_COLOR
        var sample = UltraClimateSample(Double.NaN, 10_000, vehicleControlValues = mapOf(UltraVehicleControl.AMBIENT_COLOR to 19), vehicleControlsSampledAtMillis = 10_000)
        var confirmed = false
        val supported = setOf(c, UltraVehicleControl.AROMA_OFF)
        val a = VoyahClimateAccessory({sample}, {10_000}, supportedVehicleControls = supported, vehicleControlWriter = { _, _ ->
            if (confirmed) sample = sample.copy(vehicleControlValues = mapOf(UltraVehicleControl.AMBIENT_COLOR to 30))
            UltraClimateWriteResult(confirmed, "readback")
        })
        fun request(f: CafRcsFrame) = CarAccessoryMessages.reader(a.handle(CarAccessoryMessages.reader(f.rcsBody))!!.rcsBody)
        val tree = CafConfigTree.decode(a.config())
        val service = tree.accessories.flatMap { it.services }.single { it.iid.value == c.serviceIid }
        assertEquals(0x16000002L, service.type.value)
        assertEquals("Ambient colour · Green →", VoyahVehicleControls.value(c.serviceIid+2, supported, sample.vehicleControlValues, UltraVehicleControlsText()))
        request(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(c.valueIid, c.serviceIid+2))))
        assertEquals(-6727L, request(CarAccessoryMessages.writeRequest(0, 2, mapOf(c.valueIid to 1))).errors()[c.valueIid])
        confirmed = true
        assertEquals(0L, request(CarAccessoryMessages.writeRequest(0, 3, mapOf(c.valueIid to 1))).errors()[c.valueIid])
        val changed = CarAccessoryMessages.reader(a.changed()!!.rcsBody).valuesMap()
        assertEquals(1L, changed[c.valueIid])
        assertEquals("Ambient colour · Pink →", changed[c.serviceIid+2])
    }
}
