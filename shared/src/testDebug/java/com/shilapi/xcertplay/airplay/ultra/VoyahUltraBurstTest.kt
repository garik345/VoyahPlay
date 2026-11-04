package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.*
import org.junit.Test

class VoyahUltraBurstTest {
    @Test fun burstAndSampleFloodCannotCloseControlsOrPreventHomeDuringAStuckHeater() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val home = CountDownLatch(1)
        val writes = CopyOnWriteArrayList<UltraClimateControl>()
        val traces = CopyOnWriteArrayList<String>()
        val wheel = UltraClimateControl.WHEEL_HEAT
        val seat = UltraClimateControl.DRIVER_HEAT
        val exit = UltraVehicleControl.CAR_HOME
        val settings = UltraVehicleControl.VOYAH_SETTINGS
        val source = object : UltraClimateSource {
            override val supportedControls = setOf(wheel, seat)
            override val supportedVehicleControls = setOf(exit, settings)
            lateinit var publish: (UltraClimateSample?) -> Unit
            @Volatile var sample = UltraClimateSample(22.0, 1000, comfortLevels = mapOf(wheel to 0, seat to 0), comfortSampledAtMillis = 1000)
            override fun start(onSample: (UltraClimateSample?) -> Unit) { publish = onSample; publish(sample) }
            override fun write(control: UltraClimateControl, level: Int): UltraClimateWriteResult {
                writes += control
                if (control == wheel) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
                sample = sample.copy(comfortLevels = sample.comfortLevels + (control to level))
                publish(sample)
                if (control == wheel) finished.countDown()
                return UltraClimateWriteResult(true, "actual readback")
            }
            override fun writeVehicleControl(control: UltraVehicleControl, value: Int): UltraClimateWriteResult {
                assertEquals(exit, control); home.countDown(); return UltraClimateWriteResult(true, "opened HOME")
            }
            override fun close() {}
        }
        val p = VoyahUltraProtocol({ source }, {1000}, traces::add)
        val address = InetAddress.getByName("127.0.0.1")
        val rx = ByteArray(32) {it.toByte()}; val tx = ByteArray(32) {(it+35).toByte()}
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        val reply = p.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, rx, tx, address, emptyMap())
        p.attachPending()
        try {
            RcsChannel.connect(InetSocketAddress(address, reply["dataPort"] as Int), tx, rx).use { phone ->
                fun send(f: CafRcsFrame) = phone.send(f.asRcsMessage())
                fun next() = CarAccessoryMessages.reader(requireNotNull(phone.receiveNext(2000)).body)
                send(CarAccessoryMessages.registerRequest(0, 0, CafRegistration.Identifiers(listOf(wheel.levelIid, seat.levelIid))))
                assertEquals(CafCommand.REGISTER_RESPONSE, next().command)
                send(CarAccessoryMessages.writeRequest(0, 1, mapOf(wheel.levelIid to 1)))
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                send(CarAccessoryMessages.writeRequest(0, 99, mapOf(settings.valueIid to 1)))
                send(CarAccessoryMessages.writeRequest(0, 100, mapOf(exit.valueIid to 1)))
                repeat(500) { source.publish(source.sample) }
                for (id in 10L..39L) send(CarAccessoryMessages.writeRequest(0, id, mapOf(seat.levelIid to 2)))
                val responses = mutableMapOf<Long, Map<Long, Long>>()
                val wheelNotifications = mutableListOf<Any?>()
                while (responses.size < 33) {
                    val frame = next()
                    if (frame.command == CafCommand.WRITE_RESPONSE) responses[requireNotNull(frame.transactionId)] = frame.errors()
                    if (frame.command == CafCommand.UPDATE_NOTIFY && wheel.levelIid in frame.valuesMap())
                        wheelNotifications += frame.valuesMap()[wheel.levelIid]
                }
                assertEquals(0L, responses.getValue(100)[exit.valueIid])
                assertEquals(0L, home.count)
                assertEquals("The native call is still blocked", 1L, release.count)
                assertEquals(listOf(wheel), writes.toList())
                assertTrue("Busy must refresh the real state, not echo the requested heat", wheelNotifications.isNotEmpty())
                assertTrue(wheelNotifications.all { it == 0L })
                assertTrue(responses.filterKeys { it != 100L }.values.all { it.values.all { error -> error != 0L } })
                assertFalse(traces.any { it.contains("CAF queue full") || it.startsWith("RCS CLOSED") })
                release.countDown(); assertTrue(finished.await(2, TimeUnit.SECONDS))
                send(CarAccessoryMessages.readRequest(0, 201, listOf(wheel.levelIid)))
                var state = next()
                while (state.command != CafCommand.READ_RESPONSE || state.transactionId != 201L) state = next()
                assertEquals(1L, state.valuesMap()[wheel.levelIid])
                send(CarAccessoryMessages.writeRequest(0, 101, mapOf(seat.levelIid to 2)))
                var response = next()
                while (response.command != CafCommand.WRITE_RESPONSE || response.transactionId != 101L) response = next()
                assertEquals(0L, response.errors()[seat.levelIid])
                assertEquals(listOf(wheel, seat), writes.toList())
            }
        } finally { release.countDown(); p.close() }
    }
}
