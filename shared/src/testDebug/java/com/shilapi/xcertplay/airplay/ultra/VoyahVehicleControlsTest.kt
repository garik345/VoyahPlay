package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahVehicleControlsTest {
    private var now = 1_000L
    private val driver = UltraVehicleControl.DRIVER_MASSAGE
    private var sample = UltraClimateSample(22.0, now, vehicleControlValues = mapOf(
        driver to 2, UltraVehicleControl.PASSENGER_MASSAGE to 0,
        UltraVehicleControl.DRIVER_MASSAGE_PROGRAM to 1, UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM to 2,
        UltraVehicleControl.LIGHT_MODE to 3))
    private val writes = mutableListOf<Pair<UltraVehicleControl, Int>>()
    private var confirmed = true
    private fun accessory(supported: Set<UltraVehicleControl> = UltraVehicleControl.entries.toSet()) =
        VoyahClimateAccessory({ sample }, { now }, supportedVehicleControls = supported,
            vehicleControlWriter = { c, v ->
                writes += c to v
                if (confirmed && c != UltraVehicleControl.CAR_HOME) sample = sample.copy(
                    vehicleControlValues = sample.vehicleControlValues + (c to v), vehicleControlsSampledAtMillis = now)
                UltraClimateWriteResult(confirmed, "OEM readback")
            })
    private fun reader(f: CafRcsFrame) = CarAccessoryMessages.reader(f.rcsBody)
    private fun request(a: VoyahClimateAccessory, f: CafRcsFrame) = reader(a.handle(reader(f))!!)
    private fun write(a: VoyahClimateAccessory, iid: Long, v: Any?) = request(a,
        CarAccessoryMessages.writeRequest(0, 2, mapOf(iid to v)))

    @Test fun controlsHaveDistinctServicesAndIdsWithWritableValuesAndReadOnlyMetadata() {
        val tree = CafConfigTree.decode(accessory().config())
        val ids = tree.accessories.flatMap { listOf(it.iid.value) + it.services.flatMap { s ->
            listOf(s.iid.value) + s.characteristics.map { c -> c.iid.value } } }
        assertEquals(ids.size, ids.toSet().size)
        val services = tree.accessories.single { it.iid.value == 1280L }.services
        for (control in UltraVehicleControl.entries) {
            val s = services.single { it.iid.value == control.serviceIid }
            val value = s.characteristics.single { it.iid.value == control.valueIid }
            val button = control == UltraVehicleControl.CAR_HOME
            assertEquals(if (button) 0x16000002L else 0x16000007L, s.type.value)
            assertEquals(if (button) 0x36000010L else 0x30000010L, value.type.value)
            assertEquals(if (button) CafCharacteristicFormat.UINT8 else CafCharacteristicFormat.INT32, value.format)
            assertEquals(true, value.writable)
            assertTrue(s.characteristics.filter { it != value }.all { it.writable == false })
        }
        assertTrue(writes.isEmpty())
    }
    @Test fun levelsProgramsAndLightsAreAbsoluteAndEveryWriteIsEchoedFromReadback() {
        val a = accessory()
        request(a, CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(driver.valueIid))))
        for (level in listOf(3, 2, 1, 0, 0)) {
            assertEquals(mapOf(driver.valueIid to 0L), write(a, driver.valueIid, level).errors())
            assertEquals(mapOf(driver.valueIid to level.toLong()), reader(a.changed()!!).valuesMap())
        }
        for (c in listOf(UltraVehicleControl.PASSENGER_MASSAGE_PROGRAM, UltraVehicleControl.LIGHT_MODE)) {
            assertEquals(mapOf(c.valueIid to 0L), write(a, c.valueIid, 2).errors())
            assertEquals(c to 2, writes.last())
        }
    }
    @Test fun unknownAndExpiredStatesStayUnknownAndCannotBecomeOff() {
        val a = accessory()
        val ids = listOf(driver.valueIid, driver.serviceIid + 8)
        request(a, CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(ids)))
        now += 6_001
        sample = sample.copy(sampledAtMillis = now, vehicleSampledAtMillis = now)
        assertEquals(mapOf(ids[0] to null, ids[1] to "No data"), reader(a.changed()!!).valuesMap())
        val read = request(a, CarAccessoryMessages.readRequest(0, 3, ids))
        assertEquals(-6727L, read.errors()[driver.valueIid])
        assertFalse(read.valuesMap().containsKey(driver.valueIid))
    }
    @Test fun malformedUnsupportedAndMetadataWritesNeverReachTheCar() {
        val a = accessory(setOf(driver))
        for (bad in listOf<Any?>(null, true, "3", -1, 4, 1.5, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertEquals(-50L, write(a, driver.valueIid, bad).errors()[driver.valueIid])
        }
        for (iid in listOf(driver.serviceIid + 1, driver.serviceIid + 8, UltraVehicleControl.LIGHT_MODE.valueIid))
            assertEquals(-50L, write(a, iid, 1).errors()[iid])
        assertTrue(writes.isEmpty())
    }
    @Test fun aFailedCommandDoesNotPublishTheRequestedLevelAsConfirmed() {
        val a = accessory()
        request(a, CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(driver.valueIid))))
        confirmed = false
        assertEquals(-6727L, write(a, driver.valueIid, 3).errors()[driver.valueIid])
        assertNull(a.changed())
        assertEquals(2, sample.vehicleControlValues[driver])
    }
    @Test fun homeReturnsToReadyAfterEachActionAndNeverRunsOnReadOrRegister() {
        val a = accessory()
        val home = UltraVehicleControl.CAR_HOME
        request(a, CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(home.valueIid))))
        assertTrue(writes.isEmpty())
        repeat(2) {
            assertEquals(mapOf(home.valueIid to 0L), write(a, home.valueIid, 1).errors())
            assertEquals(mapOf(home.valueIid to 1L), reader(a.changed()!!).valuesMap())
            assertEquals(mapOf(home.valueIid to 0L), reader(a.changed()!!).valuesMap())
            assertNull(a.changed())
        }
        assertEquals(listOf(home to 1, home to 1), writes)
    }
    @Test fun failingToOpenHomeDoesNotEchoAnActionThatWasNeverApplied() {
        val a = accessory()
        val home = UltraVehicleControl.CAR_HOME
        request(a, CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(home.valueIid))))
        confirmed = false
        assertEquals(-6727L, write(a, home.valueIid, 1).errors()[home.valueIid])
        assertNull(a.changed())
    }
    @Test fun programmeZeroAndThreeAreRejectedRatherThanEnablingMassage() {
        val a = accessory()
        val program = UltraVehicleControl.DRIVER_MASSAGE_PROGRAM
        for (bad in listOf(0, 3)) assertEquals(-50L, write(a, program.valueIid, bad).errors()[program.valueIid])
        assertTrue(writes.isEmpty())
    }
    @Test fun aLightStatusEventCannotRefreshAnExpiredMassageState() {
        val a = accessory()
        now += 6_001
        sample = sample.copy(lightingSampledAtMillis = now)
        val light = UltraVehicleControl.LIGHT_MODE
        val response = request(a, CarAccessoryMessages.readRequest(0, 1, listOf(driver.valueIid, light.valueIid)))
        assertEquals(-6727L, response.errors()[driver.valueIid])
        assertEquals(3L, response.valuesMap()[light.valueIid])
    }
}
