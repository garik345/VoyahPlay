package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VoyahUltraPilotTest {
    private var now = 1_000L
    private var sample: UltraClimateSample? = UltraClimateSample(22.5, now)
    private fun accessory() = VoyahClimateAccessory({ sample }, { now })
    private fun reader(frame: CafRcsFrame) = CarAccessoryMessages.reader(frame.rcsBody)

    @Test fun configContainsOnlyOneReadOnlyDriverSetPointAndKnownTypes() {
        val config = CafConfigTree.decode(accessory().config())
        val climate = config.accessories.first()
        assertEquals("Climate", CafTypeCatalog.find(CafTypeKind.ACCESSORY, climate.type)?.name)
        val service = climate.services.first()
        assertEquals("Temperature", CafTypeCatalog.find(CafTypeKind.SERVICE, service.type)?.name)
        val target = service.characteristics.first()
        assertEquals("TargetTemperature", CafTypeCatalog.find(CafTypeKind.CHARACTERISTIC, target.type)?.name)
        assertEquals(false, target.writable)
        assertEquals(CafFormatValue.Floating(22.5), target.initialValue)
        assertTrue(service.characteristics.all { it.writable == false })
        assertFalse(service.characteristics.any { it.type.value == 0x31000019L })
    }

    @Test fun invalidAndExpiredSamplesNeverBecomeZeroOrARealTemperature() {
        val a = accessory()
        fun target(): Any? = reader(a.handle(reader(CarAccessoryMessages.readRequest(0, 1, listOf(11))))!!).valuesMap()[11]
        sample = null; assertNull(target())
        sample = UltraClimateSample(Double.NaN, now); assertNull(target())
        sample = UltraClimateSample(33.0, now); assertNull(target())
        sample = UltraClimateSample(22.5, now + 1); assertNull(target())
        sample = UltraClimateSample(22.5, now); now += 6_001; assertNull(target())
    }

    @Test fun subscriptionsPublishChangesAndInvalidationButNotDuplicates() {
        val a = accessory()
        val initial = a.handle(reader(CarAccessoryMessages.registerRequest(0, 1)))!!
        assertEquals(22.5, reader(initial).valuesMap()[11])
        assertNull(a.changed())
        sample = UltraClimateSample(24.0, now)
        assertEquals(mapOf(11L to 24.0), reader(a.changed()!!).valuesMap())
        assertNull(a.changed())
        now += 6_001
        assertEquals(mapOf(11L to null), reader(a.changed()!!).valuesMap())
        assertNull(a.changed())
    }

    @Test fun unregisterStopsNotificationsAndNewInstanceStartsWithoutSubscription() {
        val a = accessory()
        a.handle(reader(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(11)))))
        a.handle(reader(CarAccessoryMessages.unregisterRequest(0, 2, listOf(11))))
        sample = UltraClimateSample(23.0, now)
        assertNull(a.changed()); assertNull(accessory().changed())
    }

    @Test fun writesControlsAndUnknownPluginsAreRejectedRatherThanAcknowledged() {
        val a = accessory()
        for (frame in listOf(CarAccessoryMessages.writeRequest(0, 2, mapOf(11L to 28.0)),
            CarAccessoryMessages.controlRequest(0, 3, mapOf(11L to 28.0)),
            CarAccessoryMessages.readRequest(77, 4, listOf(11)))) {
            val response = reader(a.handle(reader(frame))!!)
            if (frame.envelope.message.command == CafCommand.WRITE_REQUEST) {
                assertEquals(CafCommand.WRITE_RESPONSE, response.command)
                assertEquals(mapOf(11L to -50L), response.errors())
            } else {
                assertEquals(CafCommand.GENERAL_ERROR, response.command)
                assertEquals(-50L, response.requireError())
            }
        }
        assertEquals(22.5, sample?.driverTargetCelsius)
    }

    @Test fun mixedReadReturnsOnlyKnownIdsAndExplicitErrorForUnknownId() {
        val response = reader(accessory().handle(reader(CarAccessoryMessages.readRequest(0, 5, listOf(11, 999))))!!)
        assertEquals(mapOf(11L to 22.5), response.valuesMap())
        assertEquals(mapOf(999L to -50L), response.errors())
    }

    @Test fun normalReceiverInfoAndFeatureNegotiationStayUnchangedByDefault() {
        val config = config()
        val info = AirPlayInfoPlist.build(config)
        assertFalse(info.containsKey("vehicleStateProtocolInfo"))
        assertEquals(listOf("iAPChannel", "viewAreas"), setupEnabledFeatures(config, listOf("vehicleStateProtocol")))
    }

    @Test fun pilotOnlyAcknowledgesVspWhenProposedAndHasNoSyntheticClusterOrRadio() {
        val pilot = VoyahUltraProtocol({ FakeSource() }, { now }, {})
        val config = config().copy(protocolExtension = pilot)
        val info = AirPlayInfoPlist.build(config)
        assertEquals(1, (info["displays"] as List<*>).size)
        assertTrue(info.containsKey("vehicleStateProtocolInfo"))
        assertFalse(info.containsKey("uiSyncInfo"))
        assertEquals(listOf("iAPChannel", "viewAreas"), setupEnabledFeatures(config, emptyList<String>()))
        assertEquals(listOf("iAPChannel", "viewAreas", "vehicleStateProtocol"),
            setupEnabledFeatures(config, listOf("vehicleStateProtocol", "uiSync", "fileTransfer")))
        assertTrue(pilot.forSession() !== pilot)
    }

    @Test fun infoKeepsAudioAndMainScreenIdenticalOutsideTheVspSidecar() {
        val config = config().copy(microphone = true, hevc = true, mainBufferedAudio = true)
        val normal = AirPlayInfoPlist.build(config)
        val pilot = AirPlayInfoPlist.build(config.copy(protocolExtension = VoyahUltraProtocol({ FakeSource() }, { now }, {})))
        assertArrayEquals(BplistCodec.encode(normal), BplistCodec.encode(pilot - "vehicleStateProtocolInfo" - "displayPanels"))
    }

    @Test fun encryptedCafRoundTripReadsAndSubscribesToRealSourceUpdates() {
        val source = FakeSource()
        val trace = mutableListOf<String>()
        val protocol = VoyahUltraProtocol({ source }, { now }) { synchronized(trace) { trace.add(it) } }
        val rx = ByteArray(32) { it.toByte() }; val tx = ByteArray(32) { (it + 33).toByte() }
        protocol.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        try {
            val response = protocol.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, rx, tx,
                InetAddress.getByName("127.0.0.1"), mapOf("streamConnectionID" to 123L))
            protocol.attachPending()
            RcsChannel.connect(InetSocketAddress("127.0.0.1", response["dataPort"] as Int), tx, rx).use { phone ->
                phone.send(CarAccessoryMessages.configRequest(0, 1).asRcsMessage())
                val config = CarAccessoryMessages.reader(phone.receiveNext(3_000)!!.body)
                assertEquals(CafCommand.CONFIG_RESPONSE, config.command)
                assertEquals(2, config.configTree(CafFirmwareConfigTreeDecoder).accessories.size)
                source.emit(UltraClimateSample(24.5, now))
                phone.send(CarAccessoryMessages.registerRequest(0, 2, CafRegistration.Identifiers(listOf(11))).asRcsMessage())
                val initial = CarAccessoryMessages.reader(phone.receiveNext(3_000)!!.body)
                assertEquals(24.5, initial.valuesMap()[11])
                source.emit(UltraClimateSample(25.0, now))
                val update = CarAccessoryMessages.reader(phone.receiveNext(3_000)!!.body)
                assertEquals(CafCommand.UPDATE_NOTIFY, update.command)
                assertEquals(25.0, update.valuesMap()[11])
                phone.send(CarAccessoryMessages.writeRequest(0, 3, mapOf(11L to 28.0)).asRcsMessage())
                assertEquals(CafCommand.WRITE_RESPONSE, CarAccessoryMessages.reader(phone.receiveNext(3_000)!!.body).command)
            }
        } finally { protocol.close() }
        assertEquals(1, source.starts.get()); assertEquals(1, source.stops.get())
        assertTrue(synchronized(trace) { trace.any { it.contains("CAF RX") } })
    }

    @Test fun reconnectOwnsFreshSubscriptionAndClosingOneProtocolDoesNotReuseItsSource() {
        val created = mutableListOf<FakeSource>()
        val prototype = VoyahUltraProtocol({ FakeSource().also { created.add(it) } }, { now }, {})
        val a = prototype.forSession() as VoyahUltraProtocol
        val b = prototype.forSession() as VoyahUltraProtocol
        a.onFeaturesNegotiated(listOf("vehicleStateProtocol")); b.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        assertTrue(a !== b)
        a.close(); b.close()
        assertEquals(2, created.size)
        assertTrue(created.all { it.starts.get() == 1 && it.stops.get() == 1 }) // Warm up at VSP negotiation; session-owned.
    }

    @Test fun tearingDownOneCafStreamKeepsTheOtherAndDoesNotTargetTheIapTunnel() {
        val source = FakeSource()
        val protocol = VoyahUltraProtocol({ source }, { now }, {})
        protocol.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        val rx = ByteArray(32) { (it + 7).toByte() }; val tx = ByteArray(32) { (it + 71).toByte() }
        val address = InetAddress.getByName("127.0.0.1")
        val a = protocol.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, rx, tx, address, emptyMap())
        val b = protocol.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, rx, tx, address, emptyMap())
        protocol.attachPending()
        try {
            RcsChannel.connect(InetSocketAddress("127.0.0.1", a["dataPort"] as Int), tx, rx).use { first ->
                RcsChannel.connect(InetSocketAddress("127.0.0.1", b["dataPort"] as Int), tx, rx).use { second ->
                    for (phone in listOf(first, second)) {
                        phone.send(CarAccessoryMessages.registerRequest(0, 1).asRcsMessage())
                        assertEquals(CafCommand.REGISTER_RESPONSE, CarAccessoryMessages.reader(phone.receiveNext(3_000)!!.body).command)
                    }
                    val body = mapOf("streams" to listOf(mapOf("type" to 130, "streamID" to a["streamID"])))
                    assertEquals(emptyList<Int>(), teardownStreamTypes(body))
                    protocol.teardownChannels(body)
                    source.emit(UltraClimateSample(26.5, now))
                    assertEquals(26.5, CarAccessoryMessages.reader(second.receiveNext(3_000)!!.body).valuesMap()[11])
                    assertEquals(1, source.starts.get())
                }
            }
        } finally { protocol.close() }
        assertEquals(1, source.stops.get())
    }

    @Test fun cafTeardownByConnectionOrUuidNeverBecomesAnIapOrWholeSessionTeardown() {
        val protocol = VoyahUltraProtocol({ FakeSource() }, { now }, {})
        protocol.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        try {
            protocol.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA, ByteArray(32), ByteArray(32),
                InetAddress.getByName("127.0.0.1"), mapOf("streamConnectionID" to 1234L))
            val connection = mapOf("type" to 130, "streamConnectionID" to 1234L)
            val byConnection = mapOf("streams" to listOf(connection))
            assertEquals(listOf(130), teardownStreamTypes(byConnection))
            assertEquals(emptyList<Int>(), protocol.mediaTeardownTypes(byConnection, listOf(130)))
            protocol.teardownChannels(byConnection)
            assertEquals(emptyList<Int>(), protocol.mediaTeardownTypes(byConnection, listOf(130)))
            val byUuid = mapOf("streams" to listOf(mapOf("type" to 130,
                "clientTypeUUID" to RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2.uuid.toString())))
            assertEquals(emptyList<Int>(), protocol.mediaTeardownTypes(byUuid, listOf(130)))
            val mixed = mapOf("streams" to listOf(connection, mapOf("type" to 100)))
            assertEquals(listOf(100), protocol.mediaTeardownTypes(mixed, listOf(130, 100)))
            assertEquals(emptyList<Int>(), protocol.mediaTeardownTypes(mapOf("streams" to listOf(connection, emptyMap<String, Any?>())), listOf(130)))
            assertEquals(listOf(130), protocol.mediaTeardownTypes(mapOf("streams" to listOf(mapOf("type" to 130, "streamID" to 1))), listOf(130)))
            assertNull(protocol.mediaTeardownTypes(null, null))
        } finally { protocol.close() }
    }

    private fun config() = AirPlayConfig("VoyahPlay", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1", AirPlayDisplayConfig(1775, 675))
    private class FakeSource : UltraClimateSource {
        val starts = AtomicInteger(); val stops = AtomicInteger()
        val receiver = AtomicReference<((UltraClimateSample?) -> Unit)?>()
        override fun start(onSample: (UltraClimateSample?) -> Unit) { starts.incrementAndGet(); receiver.set(onSample) }
        fun emit(sample: UltraClimateSample?) { receiver.get()!!.invoke(sample) }
        override fun close() { stops.incrementAndGet(); receiver.set(null) }
    }
}
