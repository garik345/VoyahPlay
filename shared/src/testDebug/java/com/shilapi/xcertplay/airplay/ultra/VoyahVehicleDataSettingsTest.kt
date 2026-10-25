package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import org.junit.Assert.*
import org.junit.Test

class VoyahVehicleDataSettingsTest {
    private var now = 10_000L
    private var sample: UltraClimateSample? = UltraClimateSample(23.0, now, vehicle = UltraVehicleReadings(
        tirePressureKpa = listOf(250, 260, null, 270), rangeKm = 568, odometerKm = 6081, fuelPercent = 41,
        batteryPercent = 65, electricRangeKm = 53, driveMode = 2, fuelRangeKm = 515))
    private val text = UltraVehicleDataText(section = "Данные автомобиля", unavailable = "Нет данных",
        bar = "бар", kilometers = "км", localeTag = "ru-RU")
    private var writes = 0
    private fun accessory() = VoyahClimateAccessory({ sample }, { now },
        volumeWriter = { _, _ -> writes++; UltraClimateWriteResult(true, "unexpected write") }, vehicleDataText = text)
    private fun reader(frame: CafRcsFrame) = CarAccessoryMessages.reader(frame.rcsBody)
    private val valueIds get() = (0..10).map(VoyahVehicleDataSettings::valueId)
    private fun register(a: VoyahClimateAccessory) = reader(a.handle(reader(
        CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(valueIds))))!!)

    @Test fun nativeVehicleAppGetsASectionAndElevenReadOnlyLiveInformationRows() {
        val tree = CafConfigTree.decode(accessory().config())
        val allIds = tree.accessories.flatMap { a -> listOf(a.iid.value) + a.services.flatMap { s ->
            listOf(s.iid.value) + s.characteristics.map { it.iid.value } } }
        assertEquals(allIds.size, allIds.toSet().size)
        val services = tree.accessories.single { it.iid.value == 1280L }.services
        val section = services.single { it.iid.value == 1313L }
        assertEquals(0x16000020L, section.type.value)
        assertEquals(CafFormatValue.Text(text.section), section.characteristics.single { it.iid.value == 1316L }.initialValue)
        val rows = services.filter { s -> s.characteristics.any { it.iid.value in valueIds } }
        assertEquals(11, rows.size)
        assertTrue(rows.all { it.type.value == 0x16000015L })
        assertTrue(rows.flatMap { it.characteristics }.all { it.writable == false })
        assertTrue(rows.flatMap { it.characteristics }.filter { it.iid.value in valueIds }.all { it.mutable == true })
    }

    @Test fun phoneGetsLocalizedPressureRangeMileageAndUnknownSensorWithoutFakeZero() {
        val values = register(accessory()).valuesMap()
        assertEquals("2,5 бар", values[valueIds[0]])
        assertEquals("2,6 бар", values[valueIds[1]])
        assertEquals("Нет данных", values[valueIds[2]])
        assertEquals("2,7 бар", values[valueIds[3]])
        assertEquals("568 км", values[valueIds[4]])
        assertEquals("6\u00a0081 км", values[valueIds[5]])
        assertEquals("41 %", values[valueIds[6]])
        assertEquals("65 %", values[valueIds[7]])
        assertEquals("53 км", values[valueIds[8]])
        assertEquals("Comfort", values[valueIds[9]])
        assertEquals("515 км", values[valueIds[10]])
    }

    @Test fun subscribedRowsUpdateFromTheVehicleAndExpireIndependentlyOfTheClimate() {
        val a = accessory()
        register(a)
        assertNull(a.changed())
        sample = sample!!.copy(vehicle = sample!!.vehicle!!.copy(rangeKm = 567))
        assertEquals(mapOf(valueIds[4] to "567 км"), reader(a.changed()!!).valuesMap())
        now += VoyahClimateAccessory.MAX_AGE_MILLIS + 1
        sample = sample!!.copy(sampledAtMillis = now, hvacSampledAtMillis = now)
        assertEquals(valueIds.filterIndexed { row, _ -> row != 2 }.associateWith { "Нет данных" }, reader(a.changed()!!).valuesMap())
        sample = sample!!.copy(vehicleSampledAtMillis = now)
        assertEquals("567 км", reader(a.changed()!!).valuesMap()[valueIds[4]])
    }

    @Test fun informationRowsRejectWritesWithoutCallingAnyOemWriter() {
        val a = accessory()
        val response = reader(a.handle(reader(CarAccessoryMessages.writeRequest(0, 2,
            valueIds.associateWith { "0" })))!!)
        assertEquals(valueIds.associateWith { -50L }, response.errors())
        assertEquals(0, writes)
    }
}
