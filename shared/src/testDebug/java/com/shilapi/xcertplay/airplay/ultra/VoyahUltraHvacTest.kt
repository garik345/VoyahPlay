package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec as AP
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

class VoyahUltraHvacTest {
    private var now = 1000L
    private var sample: UltraClimateSample? = UltraClimateSample(22.5, now,
        mapOf(UltraClimateControl.DRIVER_HEAT to 0, UltraClimateControl.WHEEL_HEAT to 1), hvacValues = mapOf(
            UltraHvacControl.DRIVER_TARGET to 22.5, UltraHvacControl.PASSENGER_TARGET to 23.0,
            UltraHvacControl.POWER to true, UltraHvacControl.AC to false, UltraHvacControl.FAN to 3L,
            UltraHvacControl.AUTO to true, UltraHvacControl.RECIRCULATION to false,
            UltraHvacControl.FRONT_DEFROST to 0L, UltraHvacControl.REAR_DEFROST to 1L))
    private val writes = arrayListOf<Pair<UltraHvacControl, Any>>()
    private fun accessory(confirm: Boolean = true) = VoyahClimateAccessory({ sample }, { now },
        UltraClimateControl.entries.toSet(), { _, _ -> UltraClimateWriteResult(confirm, "readback") },
        UltraHvacControl.entries.toSet(), { c, v -> writes.add(c to v); UltraClimateWriteResult(confirm, "readback") })
    private fun reader(frame: CafRcsFrame) = CarAccessoryMessages.reader(frame.rcsBody)
    private fun write(a: VoyahClimateAccessory, values: Map<Long, Any?>) = reader(a.handle(reader(CarAccessoryMessages.writeRequest(0, 17, values)))!!)

    @Test fun fullClimateTreeHasUniqueIdsKnownTypesZonesAndCelsius() {
        val tree = CafConfigTree.decode(accessory().config())
        val ids = arrayListOf<Long>()
        for (a in tree.accessories) { ids.add(a.iid.value); for (s in a.services) { ids.add(s.iid.value); ids.addAll(s.characteristics.map { it.iid.value }) } }
        assertEquals(ids.size, ids.toSet().size)
        val services = tree.accessories.first().services
        assertTrue(services.any { it.type.value == 0x11000001L })
        assertTrue(services.any { it.type.value == 0x11000007L })
        assertEquals(2, services.count { it.type.value == 0x11000002L })
        val targets = services.flatMap { it.characteristics }.filter { it.type.value == 0x31000017L }
        assertEquals(2, targets.size); assertTrue(targets.all { it.writable == true && (it.units as Number).toInt() == 1082 })
        assertTrue(services.flatMap { it.characteristics }.none { it.type.value == 0x31000019L || it.type.value == 0x31000022L })
        assertTrue(tree.accessories.any { it.type.value == 0x01200002L })
    }
    @Test fun writesAreAbsoluteAndOnlyReadbackCountsAsSuccess() {
        val a = accessory(); val response = write(a, mapOf(11L to 24.5, 21L to 20.0, 51L to 8, 42L to true))
        assertTrue(response.errors().values.all { it == 0L }); assertEquals(4, writes.size)
        assertTrue(writes.contains(UltraHvacControl.DRIVER_TARGET to 24.5))
        // A successful writer does not fabricate a new cache reading.
        val read = reader(a.handle(reader(CarAccessoryMessages.readRequest(0, 20, listOf(11))))!!)
        assertEquals(22.5, read.valuesMap()[11])
        val failed = write(accessory(false), mapOf(11L to 24.5)); assertEquals(-6727L, failed.errors()[11])
    }
    @Test fun invalidInputsAndReadOnlyMetadataNeverReachTheCar() {
        val a = accessory()
        for ((iid, value) in listOf(11L to Double.NaN, 11L to 31.0, 11L to 22.25, 11L to true,
            42L to 1, 51L to 9, 51L to 2.5, 81L to 2, 102L to "fake", 999L to 1)) {
            assertEquals(-50L, write(a, mapOf(iid to value)).errors()[iid])
        }
        assertTrue(writes.isEmpty())
    }
    @Test fun contradictoryHvacCommandsDoNotCauseAnyButtonPulses() {
        val response = write(accessory(), mapOf(41L to false, 61L to true, 51L to 5))
        assertTrue(response.errors().values.all { it == -50L })
        assertTrue(writes.isEmpty())
    }
    @Test fun missingTransactionIsRejectedBeforeAnyWrite() {
        val malformed = CafMessageReader(CafEnvelope(0, CafMessage(CafCommand.WRITE_REQUEST, values = mapOf(11L to 24.0))))
        try { accessory().handle(malformed); fail() } catch (_: CafProtocolException) { }
        assertTrue(writes.isEmpty())
    }
    @Test fun readOnlySourceDoesNotAdvertiseOrPerformWrites() {
        val a = VoyahClimateAccessory({ sample }, { now })
        val chars = CafConfigTree.decode(a.config()).accessories.flatMap { it.services }.flatMap { it.characteristics }
        assertTrue(chars.all { it.writable == false })
        assertEquals(-50L, write(a, mapOf(11L to 24.0)).errors()[11])
        assertTrue(writes.isEmpty())
    }
    @Test fun staleHvacAndComfortInvalidateIndependentlyAndNeverBecomeOff() {
        val a = accessory()
        a.handle(reader(CarAccessoryMessages.registerRequest(0, 1)))
        sample = sample!!.copy(hvacSampledAtMillis = -6000, comfortSampledAtMillis = now)
        val hvac = reader(a.changed()!!).valuesMap()
        assertNull(hvac[11]); assertNull(hvac[42]); assertFalse(hvac.containsKey(31L))
        sample = sample!!.copy(comfortSampledAtMillis = -6000)
        assertNull(reader(a.changed()!!).valuesMap()[31]); assertNull(a.changed())
    }
    @Test fun conflictingComfortOnAndLevelIsRejectedAsAGroup() {
        var count = 0
        val a = VoyahClimateAccessory({ sample }, { now }, setOf(UltraClimateControl.DRIVER_HEAT),
            { _, _ -> count++; UltraClimateWriteResult(true, "") })
        assertEquals(mapOf(211L to -50L, 212L to -50L), write(a, mapOf(211L to 3, 212L to false)).errors())
        assertEquals(0, count)
        assertEquals(mapOf(211L to 0L, 212L to 0L), write(a, mapOf(211L to 3, 212L to true)).errors())
        assertEquals(1, count)
    }
    @Test fun displayPanelsReferOnlyToExistingVideoStreams() {
        val p = VoyahUltraProtocol({ Source() }, { now }, {})
        val normal = AirPlayConfig("VoyahPlay", "a", "b", "950.7.1", AirPlayDisplayConfig(1775, 675))
        val info = AirPlayInfoPlist.build(normal.copy(protocolExtension = p))
        val panels = info["displayPanels"] as List<*>
        assertEquals(1, panels.size); val panel = panels.single() as Map<*, *>
        assertEquals(1775, panel["widthPixels"]); assertEquals(675, panel["heightPixels"])
        val stream = (panel["initialVideoStreams"] as List<*>).single() as Map<*, *>
        assertEquals(AirPlayInfoPlist.MAIN_UUID, stream["uuid"])
        assertArrayEquals(BplistCodec.encode(AirPlayInfoPlist.build(normal)), BplistCodec.encode(info - "displayPanels" - "vehicleStateProtocolInfo"))
        p.close()
    }
    @Test fun sessionInfoAndCafConfigUseTheSameLiveTree() {
        val s = Source(); val p = VoyahUltraProtocol({ s }, { now }, {})
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); s.emit(sample)
        val info = mutableMapOf<String, Any?>(); p.decorateInfo(info)
        val vsp = info["vehicleStateProtocolInfo"] as Map<*, *>
        val plugin = (vsp["pluginConfigs"] as List<*>).single() as Map<*, *>
        assertEquals(0L, plugin["pluginID"]); assertEquals(VoyahClimateAccessory.PLUGIN_NAME, plugin["name"])
        val tree = CafConfigTree.decode(mapOf("accessories" to plugin["accessories"]))
        assertEquals(CafFormatValue.Floating(22.5), tree.accessories.first().services.first().characteristics.first().initialValue)
        p.close(); assertEquals(1, s.closed.get())
    }
    @Test fun opackAndWrappedBodiesRoundTripOpaque64BitTransactions() {
        val request = CarAccessoryMessages.readRequest(0, Long.MIN_VALUE + 123, listOf(11))
        for (encoding in CafBodyEncoding.entries) {
            val frame = CafRcsCodec.encode(request.envelope, encoding)
            assertEquals(encoding, CafRcsCodec.encoding(frame.rcsBody))
            assertEquals(Long.MIN_VALUE + 123, reader(frame).transactionId)
        }
        val transport = AP.decode(request.asRcsMessage().encoded())
        assertEquals(AP.KIND_ASYN, transport.kind); assertEquals(AP.MESSAGE_TYPE_CMND, transport.messageType)
        assertEquals(AP.MESSAGE_TYPE_COMM, AP.decode(IapChannelPayload.fromDevice(byteArrayOf(1,2,3)).encoded()).messageType)
    }
    @Test fun encryptedSyncGetsEmptyReplyBeforeTheCafResponseAndEchoesOpaqueToken() {
        val p = VoyahUltraProtocol({ Source() }, { now }, {})
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        val rx = ByteArray(32) { it.toByte() }; val tx = ByteArray(32) { (it + 35).toByte() }
        try {
            val channel = p.openChannel(com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes.CAR_PLAY_PROTOCOL_DATA,
                rx, tx, InetAddress.getByName("127.0.0.1"), emptyMap()); p.attachPending()
            RcsChannel.connect(InetSocketAddress("127.0.0.1", channel["dataPort"] as Int), tx, rx).use { phone ->
                val config = CarAccessoryMessages.configRequest(0, 17)
                phone.send(RcsMessage(AP.MESSAGE_TYPE_COMM, config.rcsBody, AP.KIND_SYNC, Long.MIN_VALUE + 71))
                val ack = phone.receiveNext(3000)!!
                assertEquals(AP.KIND_RPLY, ack.kind); assertEquals(Long.MIN_VALUE + 71, ack.token); assertTrue(ack.body.isEmpty())
                val caf = phone.receiveNext(3000)!!; assertEquals(AP.KIND_ASYN, caf.kind)
                assertEquals(CafCommand.CONFIG_RESPONSE, CarAccessoryMessages.reader(caf.body).command)
            }
        } finally { p.close() }
    }
    @Test fun actualInfoHandlerUsesTheSessionExtensionRatherThanThePrototype() {
        val child = object : AirPlayProtocolExtension {
            override fun forSession() = this
            override fun decorateInfo(info: MutableMap<String, Any?>) { info["testExtension"] = "session" }
        }
        val prototype = object : AirPlayProtocolExtension {
            override fun forSession() = child
            override fun decorateInfo(info: MutableMap<String, Any?>) { info["testExtension"] = "prototype" }
        }
        val session = AirPlaySession(java.net.Socket(),
            AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1", AirPlayDisplayConfig(1775,675), protocolExtension = prototype),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        try {
            val response = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
                .apply { isAccessible = true }.invoke(session,
                    RtspMessage.Request("GET", "rtsp://test/info", "RTSP/1.0", emptyMap(), ByteArray(0))) as RtspMessage.Response
            val info = BplistCodec.decode(response.body) as Map<*, *>
            assertEquals("session", info["testExtension"])
        } finally { session.close() }
    }
    private class Source : UltraClimateSource {
        val closed = AtomicInteger(); var callback: ((UltraClimateSample?) -> Unit)? = null
        override val supportedHvac = UltraHvacControl.entries.toSet()
        override fun start(onSample: (UltraClimateSample?) -> Unit) { callback = onSample }
        fun emit(s: UltraClimateSample?) { callback!!(s) }
        override fun close() { closed.incrementAndGet() }
    }
}
