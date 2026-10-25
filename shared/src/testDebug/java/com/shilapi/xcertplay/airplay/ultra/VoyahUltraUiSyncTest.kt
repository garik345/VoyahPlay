package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec as AP
import com.shilapi.xcertplay.airplay.rcs.uisync.*
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class VoyahUltraUiSyncTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private val rx = ByteArray(32) { it.toByte() }
    private val tx = ByteArray(32) { (it + 35).toByte() }
    private val logs = Collections.synchronizedList(arrayListOf<String>())
    private val writes = AtomicInteger()
    private fun protocol(probe: Boolean = false) = VoyahUltraProtocol({ object : UltraClimateSource {
        override fun start(onSample: (UltraClimateSample?) -> Unit) { onSample(UltraClimateSample(22.5, 1000)) }
        override fun writeHvac(control: UltraHvacControl, value: Any): UltraClimateWriteResult {
            writes.incrementAndGet(); return UltraClimateWriteResult(false, "unexpected write")
        }
        override fun close() {}
    } }, { 1000L }, { logs.add(it) }, assetProbe = probe, infoWaitMillis = 0)

    private fun connect(response: Map<String, Any?>) = RcsChannel.connect(
        InetSocketAddress(address, response["dataPort"] as Int), tx, rx)
    private fun frame(type: Int, psn: Long = 1, extra: Map<String, Any?> = emptyMap()) =
        com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec.encode(mapOf("displayUUID" to "Cluster_Display",
            "data" to BplistCodec.encode(mapOf("type" to type, "ssn" to 1L, "psn" to psn, "asn" to 0L) + extra)))
    private fun send(phone: RcsChannel, type: Int, psn: Long = 1, extra: Map<String, Any?> = emptyMap()) =
        phone.send(RcsMessage.comm(frame(type, psn, extra)).copy(kind = AP.KIND_ASYN))
    private fun receive(phone: RcsChannel): Map<*, *> {
        val frame = com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec.decode(phone.receiveNext(3000)!!.body) as Map<*, *>
        assertEquals("Cluster_Display", frame["displayUUID"])
        return BplistCodec.decode(frame["data"] as ByteArray) as Map<*, *>
    }
    private fun handshake(phone: RcsChannel): Map<*, *> {
        assertNull("wait for the phone's panel RESET", phone.receiveNext(150))
        send(phone, 0, extra = mapOf("id" to "phone-id", "version" to 3))
        assertEquals(0L, receive(phone)["type"])
        send(phone, 1, extra = mapOf("version" to 3))
        val complete = receive(phone)
        assertEquals(1L, complete["type"])
        repeat(2) { assertEquals(2L, receive(phone)["type"]) }
        return complete
    }

    @Test fun firstInfoIsCapturedExactlyWithoutProbeAndAdvertisedIsNotNegotiated() {
        val captures = mutableListOf<Pair<String, ByteArray>>()
        val p = VoyahUltraProtocol({ object : UltraClimateSource {
            override fun start(onSample: (UltraClimateSample?) -> Unit) {}
            override fun close() {}
        } }, { 0L }, { logs.add(it) }, dump = { name, bytes -> captures.add(name to bytes) })
        val config = AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1",
            AirPlayDisplayConfig(1775, 675), protocolExtension = p)
        try {
            val info = AirPlayInfoPlist.build(config)
            assertEquals("receiver-info", captures.single().first)
            assertArrayEquals(BplistCodec.encode(info), captures.single().second)
            assertTrue(logs.any { "UISYNC_ADVERTISED=1 UISYNC_NEGOTIATED=0" in it })
            p.onFeaturesNegotiated(listOf("uiSync"))
            AirPlayInfoPlist.build(config)
            assertEquals("only the first /info per control session is captured", 1, captures.size)
            assertTrue(logs.any { "UISYNC_ADVERTISED=1 UISYNC_NEGOTIATED=1" in it })
            assertTrue(logs.any { it.startsWith("INFO PANELS") })
        } finally { p.close() }
    }

    @Test fun diagnosticCaptureFailureDoesNotPreventInfoOrFeatureNegotiation() {
        val p = VoyahUltraProtocol({ object : UltraClimateSource {
            override fun start(onSample: (UltraClimateSample?) -> Unit) {}
            override fun close() {}
        } }, { 0L }, { logs.add(it) }, dump = { _, _ -> throw IllegalStateException("disk unavailable") })
        try {
            val info = mutableMapOf<String, Any?>()
            p.decorateInfo(info)
            assertTrue(info.containsKey("vehicleStateProtocolInfo"))
            assertTrue(info.containsKey("uiSyncInfo"))
            assertTrue(logs.any { it.startsWith("INFO CAPTURE ERROR") })
            assertEquals(listOf("uiSync"), p.enabledFeatures(listOf("uiSync")))
        } finally { p.close() }
    }

    @Test fun encryptedUiSyncGetsTransportReplyThenResetCompleteAndCommandsGetPlistAcks() {
        val p = protocol()
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol", "uiSync"))
        val config = AirPlayConfig("VoyahPlay", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1",
            AirPlayDisplayConfig(1775, 675), protocolExtension = p)
        val info = AirPlayInfoPlist.build(config)
        assertEquals(emptyMap<String, Any?>(), info["uiSyncInfo"])
        try {
            val response = p.openUiSync(rx, tx, address, mapOf("streamConnectionID" to 41L))
            p.attachPending()
            connect(response).use { phone ->
                assertNull(phone.receiveNext(150))
                val token = Long.MIN_VALUE + 7
                phone.send(RcsMessage(AP.MESSAGE_TYPE_COMM,
                    frame(0, extra = mapOf("id" to "phone-id", "version" to 3)), AP.KIND_SYNC, token))
                val transportAck = phone.receiveNext(3000)!!
                assertEquals(AP.KIND_RPLY, transportAck.kind)
                assertEquals(token, transportAck.token)
                assertTrue(transportAck.body.isEmpty())
                val reset = receive(phone)
                assertEquals(0L, reset["type"])
                assertEquals("phone-id", reset["id"])
                send(phone, 1, extra = mapOf("version" to 3))
                assertEquals(1L, receive(phone)["type"])
                repeat(2) { assertEquals(2L, receive(phone)["type"]) }
                val command = BplistCodec.encode(mapOf("type" to 3,
                    "data" to mapOf("layoutID" to "iphone-layout")))
                send(phone, 2, 2, mapOf("data" to command))
                val ack = receive(phone)
                assertEquals(3L, ack["type"])
                assertEquals(2L, ack["asn"])
                send(phone, 3, 3)
                assertNull("commandAck never causes an ack loop", phone.receiveNext(150))
                send(phone, 2, 4, mapOf("data" to command, "noAck" to true))
                assertNull("noAck does not produce a plist acknowledgement", phone.receiveNext(150))
            }
            assertEquals(0, writes.get())
            assertTrue(synchronized(logs) { logs.any { "UI SYNC STATE ready" in it } })
            assertTrue(synchronized(logs) { logs.any { "UI SYNC COMMAND type=3" in it } })
            assertFalse(synchronized(logs) { logs.any { "ASSET PROBE RX client=CarPlayClusterControl" in it } })
        } finally { p.close() }
    }

    @Test fun uiSyncMustBeNegotiatedAndItsStateDoesNotLeakToTheNextAirPlaySession() {
        val p = protocol()
        try {
            assertThrows(IllegalStateException::class.java) { p.openUiSync(rx, tx, address, emptyMap()) }
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
            assertThrows(IllegalStateException::class.java) { p.openUiSync(rx, tx, address, emptyMap()) }
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol", "uiSync"))
            val child = p.forSession() as VoyahUltraProtocol
            try {
                assertThrows(IllegalStateException::class.java) { child.openUiSync(rx, tx, address, emptyMap()) }
            } finally { child.close() }
        } finally { p.close() }
        assertThrows(IllegalStateException::class.java) { p.openUiSync(rx, tx, address, emptyMap()) }
    }

    @Test fun uiSyncIsNotAProbeAndTeardownNeverTearsDownCafOrMedia() {
        val p = protocol(probe = true)
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol", "uiSync", "fileTransfer"))
        try {
            assertThrows(IllegalStateException::class.java) {
                p.openProbe(RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL, rx, tx, address, emptyMap())
            }
            val ui = p.openUiSync(rx, tx, address, mapOf("streamConnectionID" to 42L))
            val caf = p.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, rx, tx, address, emptyMap())
            p.attachPending()
            connect(ui).use { phone ->
                connect(caf).use { vehicle ->
                    handshake(phone)
                    val entry = mapOf("type" to 130, "streamConnectionID" to 42L)
                    val body = mapOf("streams" to listOf(entry))
                    assertEquals(emptyList<Int>(), p.mediaTeardownTypes(body, listOf(130)))
                    p.teardownChannels(body)
                    assertNull(phone.receiveNext(1000))
                    assertEquals(emptyList<Int>(), p.mediaTeardownTypes(body, listOf(130)))
                    val mixed = mapOf("streams" to listOf(entry, mapOf("type" to 110)))
                    assertEquals(listOf(110), p.mediaTeardownTypes(mixed, listOf(130, 110)))
                    vehicle.send(CarAccessoryMessages.readRequest(0, 1, listOf(11)).asRcsMessage())
                    assertEquals(22.5, CarAccessoryMessages.reader(vehicle.receiveNext(3000)!!.body).valuesMap()[11])
                }
            }
        } finally { p.close() }
    }

    @Test fun malformedUiSyncClosesOnlyItsOwnStreamAndReplacementStartsWithFreshReset() {
        val p = protocol()
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol", "uiSync"))
        try {
            val first = p.openUiSync(rx, tx, address, emptyMap())
            val caf = p.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, rx, tx, address, emptyMap())
            p.attachPending()
            connect(first).use { phone ->
                connect(caf).use { vehicle ->
                    handshake(phone)
                    phone.send(RcsMessage(AP.MESSAGE_TYPE_COMM, byteArrayOf(1, 2, 3), AP.KIND_ASYN))
                    assertNull(phone.receiveNext(1000))
                    assertTrue(phone.isPeerEnded)
                    vehicle.send(CarAccessoryMessages.configRequest(0, 1).asRcsMessage())
                    assertEquals(CafCommand.CONFIG_RESPONSE, CarAccessoryMessages.reader(vehicle.receiveNext(3000)!!.body).command)
                    val replacement = p.openUiSync(rx, tx, address, emptyMap())
                    p.attachPending()
                    connect(replacement).use { newPhone ->
                        assertEquals(2L, handshake(newPhone)["psn"])
                    }
                }
            }
            assertTrue(synchronized(logs) { logs.any { it.startsWith("UI SYNC ERROR") } })
            assertEquals(0, writes.get())
        } finally { p.close() }
    }
}
