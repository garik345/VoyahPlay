package com.shilapi.xcertplay.transport

import org.junit.Assert.*
import org.junit.Test

class VoyahVehicleSpeedSourceTest {
    private var now = 1000L
    private val source = VoyahVehicleSpeedSource { now }

    @Test fun convertsCanSpeedAndEncodesActualGear() {
        source.start()
        source.update(36, 3, now)
        val reading = source.drain()!!
        assertEquals(VehicleGear.DRIVE, reading.gear)
        assertEquals(10.0, reading.samples.single().metersPerSecond, 0.001)
        assertTrue(PascdEncoder.encode(reading)!!.startsWith("\$PASCD,1.000,C,D,0,1,0.00,10.000*"))
        assertNull(source.drain())
    }

    @Test fun unknownGearOrSpeedDoesNotBecomeParkOrZeroSpeed() {
        source.start()
        source.update(20, 4, now)
        assertNull(source.drain())
        source.update(-1, 0, now)
        assertNull(source.drain())
    }

    @Test fun staleFutureAndDuplicateReadingsAreNotDelivered() {
        source.start()
        source.update(20, 3, now)
        source.update(90, 3, now)
        assertEquals(1, source.drain()!!.samples.size)
        source.update(10, 3, now + 1)
        assertNull(source.drain())
        now += 4000
        source.update(10, 3, 1000)
        assertNull(source.drain())
    }

    @Test fun gearChangeDropsSamplesFromThePreviousDirection() {
        source.start()
        source.update(20, 3, now)
        now += 100
        source.update(5, 1, now)
        val reading = source.drain()!!
        assertEquals(VehicleGear.REVERSE, reading.gear)
        assertEquals(1, reading.samples.size)
    }

    @Test fun stopAndExpiredBufferProduceNoData() {
        source.start()
        source.update(30, 3, now)
        now += 3001
        assertNull(source.drain())
        source.stop()
        source.update(30, 3, now)
        assertNull(source.drain())
    }
}
