package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.ultra.VoyahVehicleDescription as V
import org.junit.Assert.*
import org.junit.Test

class VoyahUltraVehicleTest {
    private var now = 10_000L
    private val car = UltraVehicleReadings(tirePressureKpa = listOf(250L, 260L, null, 270L), fuelPercent = 41L,
        odometerKm = 15_278L, rangeKm = 188L, volumeLevel = 35L, muted = false)
    private var sample: UltraClimateSample? = UltraClimateSample(22.0, now, vehicle = car)
    private val volumeWrites = arrayListOf<Pair<Int?, Boolean?>>()
    private fun accessory(volume: Boolean = true, confirm: Boolean = true, range: IntRange = 0..100) = VoyahClimateAccessory({ sample }, { now },
        volumeRange = range,
        volumeWriter = if (!volume) null else { level, mute -> volumeWrites += level to mute
            val v = sample!!.vehicle!!
            sample = sample!!.copy(vehicle = v.copy(volumeLevel = level?.toLong() ?: v.volumeLevel, muted = mute ?: v.muted))
            UltraClimateWriteResult(confirm, "readback") })
    private fun reader(frame: CafRcsFrame) = CarAccessoryMessages.reader(frame.rcsBody)
    private fun register(a: VoyahClimateAccessory, ids: List<Long>) =
        reader(a.handle(reader(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(ids))))!!)
    private fun write(a: VoyahClimateAccessory, values: Map<Long, Any?>) =
        reader(a.handle(reader(CarAccessoryMessages.writeRequest(0, 2, values)))!!)

    @Test fun theDescriptionCarriesTiresFuelTripComputerAndAudioInLiviShapes() {
        val tree = CafConfigTree.decode(accessory().config())
        val ids = tree.accessories.flatMap { a -> listOf(a.iid.value) + a.services.flatMap { s -> listOf(s.iid.value) + s.characteristics.map { it.iid.value } } }
        assertEquals("unique IIDs across the whole tree", ids.size, ids.toSet().size)
        fun accessory(type: Long) = tree.accessories.single { it.type.value == type }
        val tires = accessory(0x0A000001)
        assertEquals(4, tires.services.count { it.type.value == 0x1B000001L })
        val pressure = tires.services.flatMap { it.characteristics }.filter { it.type.value == 0x3000001BL }
        assertEquals(listOf(932, 932, 932, 932), pressure.map { (it.units as Number).toInt() })
        assertTrue(pressure.all { it.writable == false })
        val fuel = accessory(0x09000006).services.flatMap { it.characteristics }
        assertEquals(1205, (fuel.single { it.type.value == 0x41000002L }.units as Number).toInt())
        assertNull("no invented consumption", fuel.firstOrNull { it.type.value == 0x35000013L })
        val trip = accessory(0x04000001)
        assertEquals(setOf(0x1A00000BL, 0x1A00000AL), trip.services.map { it.type.value }.toSet())
        val audio = accessory(0x02000001).services.single().characteristics
        assertTrue(audio.single { it.type.value == 0x33000006L }.writable == true)
    }

    @Test fun thePhoneGetsTheCarsFactsAndNoValueForWhatIsUnknown() {
        val a = accessory()
        val ids = listOf(V.pressure(0), V.pressure(2), V.sensorState(0), V.sensorState(2), V.FUEL_LEVEL, V.ODOMETER_KM,
            V.ODOMETER_MILES, V.RANGE_KM, V.RANGE_HIDDEN, V.FUEL_RANGE_HIDDEN, V.VOLUME, V.MUTE)
        val response = register(a, ids)
        assertEquals(mapOf(V.pressure(0) to 250L, V.sensorState(0) to V.SENSOR_ACTIVE, V.sensorState(2) to V.SENSOR_WAITING,
            V.FUEL_LEVEL to 41L, V.ODOMETER_KM to 15_278L, V.ODOMETER_MILES to 9_493L, V.RANGE_KM to 188L,
            V.RANGE_HIDDEN to false, V.FUEL_RANGE_HIDDEN to true, V.VOLUME to 35L, V.MUTE to false), response.valuesMap())
        assertEquals(mapOf(V.pressure(2) to -6727L), response.errors())
    }

    @Test fun staleVehicleFactsAreWithdrawnAndTheRangeHidden() {
        val a = accessory()
        register(a, listOf(V.pressure(0), V.RANGE_KM, V.RANGE_HIDDEN))
        now += VoyahClimateAccessory.MAX_AGE_MILLIS + 1
        val update = reader(a.changed()!!).valuesMap()
        assertEquals(true, update[V.RANGE_HIDDEN])
        val read = reader(a.handle(reader(CarAccessoryMessages.readRequest(0, 3, listOf(V.pressure(0)))))!!)
        assertEquals(mapOf(V.pressure(0) to -6727L), read.errors())
    }

    @Test fun newCarReadingsReachTheFollowingPhone() {
        val a = accessory()
        register(a, listOf(V.pressure(1), V.FUEL_LEVEL))
        assertNull(a.changed())
        sample = sample!!.copy(vehicle = car.copy(tirePressureKpa = listOf(250L, 240L, null, 270L), fuelPercent = 40L))
        assertEquals(mapOf(V.pressure(1) to 240L, V.FUEL_LEVEL to 40L), reader(a.changed()!!).valuesMap())
    }

    @Test fun nativeMuteAndUnmuteReachThePhoneAsNotificationsWithoutAPhoneWrite() {
        val a = accessory()
        register(a, listOf(V.MUTE))
        sample = sample!!.copy(vehicle = car.copy(muted = true))
        assertEquals(mapOf(V.MUTE to true), reader(a.changed()!!).valuesMap())
        sample = sample!!.copy(vehicle = car.copy(muted = false))
        assertEquals(mapOf(V.MUTE to false), reader(a.changed()!!).valuesMap())
        assertTrue(volumeWrites.isEmpty())
    }

    @Test fun freshVolumeSurvivesAStaleCanCacheWithoutRefreshingTiresOrFuel() {
        sample = sample!!.copy(vehicleSampledAtMillis = -100_000, volumeSampledAtMillis = now)
        val a = accessory()
        val reply = register(a, listOf(V.VOLUME, V.MUTE, V.FUEL_LEVEL, V.pressure(0)))
        assertEquals(35L, reply.valuesMap()[V.VOLUME])
        assertEquals(false, reply.valuesMap()[V.MUTE])
        assertNull(reply.valuesMap()[V.FUEL_LEVEL])
        assertNull(reply.valuesMap()[V.pressure(0)])
    }

    @Test fun freshCanCacheCannotMakeStaleAudioLookCurrent() {
        sample = sample!!.copy(volumeSampledAtMillis = -100_000)
        val reply = register(accessory(), listOf(V.VOLUME, V.MUTE, V.FUEL_LEVEL))
        assertNull(reply.valuesMap()[V.VOLUME])
        assertNull(reply.valuesMap()[V.MUTE])
        assertEquals(41L, reply.valuesMap()[V.FUEL_LEVEL])
    }

    @Test fun theVolumeSliderSetsAndConfirmsTheAndroidVolume() {
        val a = accessory()
        register(a, listOf(V.VOLUME, V.MUTE))
        assertEquals(mapOf(V.VOLUME to 0L), write(a, mapOf(V.VOLUME to 60L)).errors())
        assertEquals(listOf<Pair<Int?, Boolean?>>(60 to null), volumeWrites)
        assertEquals(mapOf(V.VOLUME to 60L), reader(a.changed()!!).valuesMap())
        // The same level again is still confirmed; the phone reverts an unconfirmed write after 0.5 s.
        write(a, mapOf(V.VOLUME to 60L))
        assertEquals(mapOf(V.VOLUME to 60L), reader(a.changed()!!).valuesMap())
        assertEquals(mapOf(V.MUTE to 0L), write(a, mapOf(V.MUTE to true)).errors())
        assertEquals(mapOf(V.MUTE to true), reader(a.changed()!!).valuesMap())
    }

    @Test fun anUnconfirmedOrMalformedVolumeIsNotReportedAsSet() {
        assertEquals(mapOf(V.VOLUME to -6727L), write(accessory(confirm = false), mapOf(V.VOLUME to 20L)).errors())
        assertEquals(mapOf(V.VOLUME to -50L), write(accessory(), mapOf(V.VOLUME to 101L)).errors())
        assertEquals(mapOf(V.VOLUME to -50L), write(accessory(), mapOf(V.VOLUME to 20.5)).errors())
        assertEquals(mapOf(V.MUTE to -50L), write(accessory(), mapOf(V.MUTE to "yes")).errors())
        assertTrue(volumeWrites.isEmpty() || volumeWrites == listOf<Pair<Int?, Boolean?>>(20 to null))
    }

    @Test fun everyPressUsesOneNativeVolumeStepAndReportsTheSameReadback() {
        sample = sample!!.copy(vehicle = car.copy(volumeLevel = 10))
        val a = accessory(range = 0..30)
        val description = CafConfigTree.decode(a.config()).accessories.single { it.type.value == 0x02000001L }
            .services.single().characteristics.single { it.iid.value == V.VOLUME }
        assertEquals(0L, (description.minimumValue as CafFormatValue.Unsigned).value.toLong())
        assertEquals(30L, (description.maximumValue as CafFormatValue.Unsigned).value.toLong())
        assertEquals(1L, (description.stepValue as CafFormatValue.Unsigned).value.toLong())
        assertEquals(10L, register(a, listOf(V.VOLUME)).valuesMap()[V.VOLUME])
        for (level in listOf(11, 12, 11, 10)) {
            assertEquals(mapOf(V.VOLUME to 0L), write(a, mapOf(V.VOLUME to level)).errors())
            assertEquals(level to null, volumeWrites.last())
            assertEquals(mapOf(V.VOLUME to level.toLong()), reader(a.changed()!!).valuesMap())
        }
    }

    @Test fun nativeRangeRejectsOutOfRangeWritesBeforeChangingVolumeOrMute() {
        val a = accessory(range = 1..30)
        for (level in listOf(0, 31, 100)) {
            val errors = write(a, mapOf(V.VOLUME to level, V.MUTE to true)).errors()
            assertEquals(mapOf(V.VOLUME to -50L, V.MUTE to -50L), errors)
        }
        assertTrue(volumeWrites.isEmpty())
        assertEquals(mapOf(V.VOLUME to 0L), write(a, mapOf(V.VOLUME to 1)).errors())
        assertEquals(1 to null, volumeWrites.single())
    }

    @Test fun withoutAVolumeWriterTheSliderIsReadOnly() {
        val a = accessory(volume = false)
        val tree = CafConfigTree.decode(a.config())
        val volume = tree.accessories.single { it.type.value == 0x02000001L }.services.single().characteristics
            .single { it.type.value == 0x33000006L }
        assertEquals(false, volume.writable)
        assertEquals(mapOf(V.VOLUME to -50L), write(a, mapOf(V.VOLUME to 20L)).errors())
    }

    @Test fun carFactsAreNeverWritableByThePhone() {
        val response = write(accessory(), mapOf(V.FUEL_LEVEL to 90L, V.pressure(0) to 300L, V.ODOMETER_KM to 1L))
        assertTrue(response.errors().values.all { it == -50L })
    }
}
