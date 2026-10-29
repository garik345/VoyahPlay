package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahCabinControlsTest {
    private val controls = UltraVehicleControl.entries.filter { it.isCabin }.toSet()
    private var now = 10_000L
    private var sample = UltraClimateSample(Double.NaN,now,vehicleControlValues = controls.associateWith { it.minimum })
    private var confirmed = true
    private val writes = mutableListOf<Pair<UltraVehicleControl,Int>>()
    private fun accessory() = VoyahClimateAccessory({sample},{now},supportedVehicleControls=controls,vehicleControlWriter={c,v ->
        writes += c to v
        if(confirmed) sample=sample.copy(vehicleControlValues=sample.vehicleControlValues+(c to v),vehicleControlsSampledAtMillis=now)
        UltraClimateWriteResult(confirmed,"readback")
    })
    private fun request(a: VoyahClimateAccessory,f: CafRcsFrame) = CarAccessoryMessages.reader(a.handle(CarAccessoryMessages.reader(f.rcsBody))!!.rcsBody)

    @Test fun everyCabinControlAcceptsItsLimitsAndEchoesTheReadbackIncludingAnUnchangedValue() {
        val a=accessory()
        request(a,CarAccessoryMessages.registerRequest(0,1,CafRegistration.Identifiers(controls.map { it.valueIid })))
        for(c in controls) for(v in listOf(c.maximum,c.minimum,c.minimum)) {
            assertEquals(0L,request(a,CarAccessoryMessages.writeRequest(0,2,mapOf(c.valueIid to v))).errors()[c.valueIid])
            assertEquals(v.toLong(),CarAccessoryMessages.reader(a.changed()!!.rcsBody).valuesMap()[c.valueIid])
            assertEquals(c to v,writes.last())
        }
    }
    @Test fun invalidOrUnconfirmedWritesNeverPublishSuccess() {
        val a=accessory()
        for(c in controls) for(v in listOf<Any?>(c.minimum-1,c.maximum+1,true,"1",1.5,Double.NaN))
            assertEquals(-50L,request(a,CarAccessoryMessages.writeRequest(0,2,mapOf(c.valueIid to v))).errors()[c.valueIid])
        assertTrue(writes.isEmpty())
        val c=UltraVehicleControl.AROMA_LEVEL
        confirmed=false
        assertEquals(-6727L,request(a,CarAccessoryMessages.writeRequest(0,2,mapOf(c.valueIid to 3))).errors()[c.valueIid])
        assertEquals(0,sample.vehicleControlValues[c])
    }
    @Test fun cabinReadingsExpireIndependentlyOfFreshAudioAndLightEvents() {
        val a=accessory();now+=6_001
        sample=sample.copy(volumeSampledAtMillis=now,lightingSampledAtMillis=now)
        val ids=controls.map { it.valueIid }
        val reply=request(a,CarAccessoryMessages.readRequest(0,3,ids))
        assertTrue(ids.all { reply.errors()[it]==-6727L })
    }
    @Test fun familiarColourNamesAndDurationLabelsFollowOemCodes() {
        val c=UltraVehicleControl.AMBIENT_COLOR
        assertEquals("Red",VoyahVehicleControls.value(c.serviceIid+8,controls,mapOf(c to 64),UltraVehicleControlsText()))
        val t=UltraVehicleControl.AROMA_TIME
        assertEquals("30 min",VoyahVehicleControls.value(t.serviceIid+8,controls,mapOf(t to 1),UltraVehicleControlsText()))
    }
}
