package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec as AP
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class VoyahUltraCafLatencyTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private val rx = ByteArray(32) { it.toByte() }
    private val tx = ByteArray(32) { (it + 35).toByte() }
    private open class Source : UltraClimateSource {
        override val supportedHvac = setOf(UltraHvacControl.DRIVER_TARGET)
        override fun start(onSample: (UltraClimateSample?) -> Unit) { onSample(UltraClimateSample(22.5, 1000)) }
        override fun close() {}
    }
    private fun protocol(source: Source = Source(), trace: (String) -> Unit = {}) =
        VoyahUltraProtocol({ source }, { 1000L }, trace).also {
            it.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        }
    private fun connect(p: VoyahUltraProtocol, client: RcsClientType = RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2): RcsChannel {
        val reply = p.openChannel(client, rx, tx, address, emptyMap())
        p.attachPending()
        return RcsChannel.connect(InetSocketAddress(address, reply["dataPort"] as Int), tx, rx)
    }
    private fun receive(phone: RcsChannel) = CarAccessoryMessages.reader(requireNotNull(phone.receiveNext(2000)).body)
    private fun followRoute(phone: RcsChannel) {
        phone.send(CarAccessoryMessages.registerRequest(0, 90, CafRegistration.Identifiers(listOf(262))).asRcsMessage())
        assertEquals(CafCommand.REGISTER_RESPONSE, receive(phone).command)
    }
    private fun barrier(phone: RcsChannel, request: CafRcsFrame, token: Long) {
        phone.send(request.asRcsMessage().copy(kind = AP.KIND_SYNC, token = token))
        val ack = requireNotNull(phone.receiveNext(2000))
        assertEquals(AP.KIND_RPLY, ack.kind)
        assertEquals(token, ack.token)
    }

    @Test fun routeEchoIsSentBeforeAnAlreadyQueuedOemWriteCanBlockTheWorker() {
        val firstSeen = CountDownLatch(1)
        val resumeFirst = CountDownLatch(1)
        val oemEntered = CountDownLatch(1)
        val resumeOem = CountDownLatch(1)
        val held = AtomicBoolean()
        val source = object : Source() {
            override fun writeHvac(control: UltraHvacControl, value: Any): UltraClimateWriteResult {
                oemEntered.countDown()
                check(resumeOem.await(5, TimeUnit.SECONDS))
                return UltraClimateWriteResult(false, "test write not confirmed")
            }
        }
        val p = protocol(source) { text ->
            if (text.startsWith("CAF RX") && "values={262=false}" in text && held.compareAndSet(false, true)) {
                firstSeen.countDown()
                check(resumeFirst.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            connect(p).use { phone ->
                followRoute(phone)
                phone.send(CarAccessoryMessages.writeRequest(0, 1, mapOf(262L to false)).asRcsMessage())
                assertTrue(firstSeen.await(2, TimeUnit.SECONDS))
                // The third transport ack proves the second request has entered the FIFO.
                barrier(phone, CarAccessoryMessages.writeRequest(0, 2, mapOf(11L to 25.0)), 102)
                barrier(phone, CarAccessoryMessages.readRequest(0, 3, listOf(11)), 103)
                resumeFirst.countDown()
                assertTrue(oemEntered.await(2, TimeUnit.SECONDS))
                val response = receive(phone)
                assertEquals(CafCommand.WRITE_RESPONSE, response.command)
                assertEquals(1L, response.transactionId)
                val echo = receive(phone)
                assertEquals(CafCommand.UPDATE_NOTIFY, echo.command)
                assertEquals(false, echo.valuesMap()[262])
                assertEquals("OEM operation must still be blocked when the first echo arrives", 1L, resumeOem.count)
            }
        } finally { resumeFirst.countDown(); resumeOem.countDown(); p.close() }
    }

    @Test fun repeatedRouteWritesStillEchoOnTheOtherSubscribedChannel() {
        val p = protocol()
        try {
            connect(p).use { subscribed ->
                connect(p, RcsClientTypes.CAR_PLAY_PROTOCOL_DATA).use { writer ->
                    followRoute(subscribed)
                    repeat(2) { index ->
                        writer.send(CarAccessoryMessages.writeRequest(0, index.toLong(), mapOf(262L to false)).asRcsMessage())
                        assertEquals(CafCommand.WRITE_RESPONSE, receive(writer).command)
                        val echo = receive(subscribed)
                        assertEquals(CafCommand.UPDATE_NOTIFY, echo.command)
                        assertEquals(false, echo.valuesMap()[262])
                    }
                }
            }
        } finally { p.close() }
    }

    @Test fun configAndImmediateEchoPreserveEachChannelsNegotiatedCarrier() {
        for (encoding in CafBodyEncoding.entries) {
            val p = protocol()
            try {
                connect(p).use { phone ->
                    fun send(frame: CafRcsFrame) = phone.send(CafRcsCodec.encode(frame.envelope, encoding).asRcsMessage())
                    fun next(): CafMessageReader {
                        val reply = requireNotNull(phone.receiveNext(2000))
                        assertEquals(encoding, CafRcsCodec.encoding(reply.body))
                        return CarAccessoryMessages.reader(reply.body)
                    }
                    send(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(262))))
                    assertEquals(CafCommand.REGISTER_RESPONSE, next().command)
                    val opaque = Long.MIN_VALUE + 127
                    send(CarAccessoryMessages.configRequest(0, opaque))
                    send(CarAccessoryMessages.writeRequest(0, 3, mapOf(262L to false)))
                    val config = next()
                    assertEquals(CafCommand.CONFIG_RESPONSE, config.command)
                    assertEquals(opaque, config.transactionId)
                    assertTrue(config.configTree(CafFirmwareConfigTreeDecoder).accessories.isNotEmpty())
                    assertEquals(CafCommand.WRITE_RESPONSE, next().command)
                    val echo = next()
                    assertEquals(CafCommand.UPDATE_NOTIFY, echo.command)
                    assertEquals(false, echo.valuesMap()[262])
                }
            } finally { p.close() }
        }
    }
}
