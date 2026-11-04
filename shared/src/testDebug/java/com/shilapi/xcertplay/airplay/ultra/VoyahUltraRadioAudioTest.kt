package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/** Exercises the real encrypted AirPlay event socket and CAF channel, not Android audio-focus mocks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahUltraRadioAudioTest {
    private val address = InetAddress.getByName("127.0.0.1")
    private val rx = ByteArray(32) { it.toByte() }
    private val tx = ByteArray(32) { (it + 35).toByte() }

    private class Source(override val preferredMediaSource: UltraMediaSource = UltraMediaSource.RADIO) : UltraClimateSource {
        val writes = java.util.concurrent.LinkedBlockingQueue<VoyahRadioOem.Command>()
        val remembered = java.util.concurrent.CopyOnWriteArrayList<UltraMediaSource>()
        override fun rememberMediaSource(value: UltraMediaSource) { remembered += value }
        @Volatile var calls = 0
        var confirm = true
        var echoPlayback = false
        private var listener: ((UltraClimateSample?) -> Unit)? = null
        private var radio = radio(false)
        private var door: Boolean? = null
        private var doorAt = 0L
        override val supportsRadio = true
        override fun start(onSample: (UltraClimateSample?) -> Unit) {
            listener = onSample
            emit(radio)
        }
        fun emit(value: UltraRadioSnapshot) {
            radio = value
            listener?.invoke(UltraClimateSample(23.0, 1000, radio = value, radioSampledAtMillis = 1000,
                driverDoorOpen = door, driverDoorSampledAtMillis = doorAt))
        }
        fun driverDoor(open: Boolean, at: Long) { door = open; doorAt = at; emit(radio) }
        override fun writeRadio(request: UltraRadioRequest): UltraClimateWriteResult {
            calls++
            writes.offer(request.command)
            if (echoPlayback && confirm) emit(radio.copy(state = radio.state?.copy(
                playing = request.command != VoyahRadioOem.Command.PAUSE)))
            return UltraClimateWriteResult(confirm, "OEM readback")
        }
        override fun close() {}

        companion object {
            fun radio(playing: Boolean) = UltraRadioSnapshot(true, VoyahRadioOem.decode(mapOf(
                VoyahRadioOem.BAND to "FM", VoyahRadioOem.FREQUENCY to 10170L,
                VoyahRadioOem.TITLE to "FM 101.7 MHz", VoyahRadioOem.STATION_NAME to "Station"),
                if (playing) 3 else 2))
        }
    }

    private inner class Peer(private val mediaHandler: AirPlayMediaHandler = object : AirPlayMediaHandler {}) : Closeable {
        val phone: Socket
        private val event: Socket
        private val cipher = ControlCipher(tx, rx)
        private var sealed = ByteArray(0)
        private var plain = ByteArray(0)
        val session = AirPlaySession(Socket(),
            AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1", AirPlayDisplayConfig(1920,720)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, mediaHandler)
        private val reader: Thread
        init {
            event = ServerSocket(0, 1, address).use { server ->
                phone = Socket(address, server.localPort).apply { soTimeout = 2500; tcpNoDelay = true }
                server.accept()
            }
            for ((name, value) in listOf("eventSocket" to event, "eventCipher" to ControlCipher(rx, tx)))
                AirPlaySession::class.java.getDeclaredField(name).apply { isAccessible = true }.set(session, value)
            reader = Thread {
                AirPlaySession::class.java.getDeclaredMethod("runEventRead", Socket::class.java)
                    .apply { isAccessible = true }.invoke(session, event)
            }.apply { isDaemon = true; start() }
        }
        fun receive(): RtspMessage.Request {
            val buffer = ByteArray(8192)
            while (true) {
                val parsed = RtspMessage.parseMessages(plain)
                if (parsed.messages.isNotEmpty()) {
                    assertEquals(1, parsed.messages.size)
                    plain = parsed.rest
                    return parsed.messages.single()
                }
                val count = phone.getInputStream().read(buffer)
                check(count > 0)
                sealed += buffer.copyOf(count)
                val decoded = cipher.decrypt(sealed)
                sealed = decoded.rest; plain += decoded.data
            }
        }
        fun send(bytes: ByteArray) { phone.getOutputStream().write(cipher.encrypt(bytes)); phone.getOutputStream().flush() }
        fun answer(request: RtspMessage.Request, granted: Boolean) {
            val body = BplistCodec.encode(if (granted) mapOf("status" to 0, "params" to mapOf("resources" to
                listOf(mapOf("resourceID" to 2, "entity" to 2)))) else mapOf("status" to -6727))
            send(RtspMessage.buildResponse(request, RtspMessage.Response(status = 200, body = body)))
        }
        override fun close() { session.close(); event.close(); phone.close(); reader.join(1000) }
    }

    private fun connect(p: VoyahUltraProtocol): RcsChannel {
        val reply = p.openChannel(RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2, rx, tx, address, emptyMap())
        p.attachPending()
        return RcsChannel.connect(InetSocketAddress(address, reply["dataPort"] as Int), tx, rx)
    }
    private fun receive(phone: RcsChannel) = CarAccessoryMessages.reader(phone.receiveNext(3000)!!.body)
    private fun play(phone: RcsChannel) = phone.send(CarAccessoryMessages.controlRequest(0, 1, mapOf(4360L to emptyMap<String,Any?>())).asRcsMessage())

    @Test fun driverDoorPausesNativeRadioAndPublishesPausedStatusWithoutSelectingIphone() {
        val source = Source().apply { echoPlayback = true }
        val now = java.util.concurrent.atomic.AtomicLong(1000)
        val p = VoyahUltraProtocol({ source }, now::get, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            source.driverDoor(false, now.get())
            connect(p).use { phone ->
                play(phone); peer.answer(peer.receive(), true)
                assertTrue(receive(phone).errors().isEmpty())
                assertEquals(VoyahRadioOem.Command.PLAY, source.writes.poll(2, TimeUnit.SECONDS))
                phone.send(CarAccessoryMessages.registerRequest(0, 2, CafRegistration.Identifiers(listOf(4359L))).asRcsMessage())
                assertEquals(1L, receive(phone).valuesMap()[4359L])
                source.driverDoor(true, now.incrementAndGet())
                assertEquals(VoyahRadioOem.Command.PAUSE, source.writes.poll(2, TimeUnit.SECONDS))
                assertEquals(0L, receive(phone).valuesMap()[4359L])
                source.driverDoor(false, now.incrementAndGet())
                assertNull(source.writes.poll(150, TimeUnit.MILLISECONDS))
                phone.send(CarAccessoryMessages.readRequest(0, 3, listOf(4354L)).asRcsMessage())
                assertEquals(VoyahRadioDescription.SOURCE, receive(phone).valuesMap()[4354L])
                assertTrue(source.remembered.isEmpty())
                peer.phone.soTimeout = 150
                assertThrows(java.net.SocketTimeoutException::class.java) { peer.receive() }
            }
        } } finally { p.close() }
    }

    @Test fun cafPlayWaitsForAnEncryptedOwnershipGrantBeforeCallingOemRadio() {
        val source = Source()
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            connect(p).use { phone ->
                play(phone)
                val take = peer.receive()
                val body = BplistCodec.decode(take.body) as Map<*, *>
                assertEquals("changeModes", body["type"])
                assertEquals(0, source.calls)
                val resources = (body["params"] as Map<*, *>)["resources"] as List<*>
                assertEquals(2L, (resources.single() as Map<*, *>)["resourceID"])
                peer.answer(take, true)
                assertTrue(receive(phone).errors().isEmpty())
                assertEquals(1, source.calls)
            }
        } } finally { p.close() }
    }

    @Test fun deniedOwnershipDoesNotStartRadioAndPauseDoesNotTakeAudio() {
        val source = Source()
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            connect(p).use { phone ->
                play(phone); peer.answer(peer.receive(), false)
                assertEquals(-6727L, receive(phone).errors()[4360L]); assertEquals(0, source.calls)
                val cancel = BplistCodec.decode(peer.receive().body) as Map<*, *>
                val resources = (cancel["params"] as Map<*, *>)["resources"] as List<*>
                assertEquals(2L, (resources.single() as Map<*, *>)["transferType"])
                phone.send(CarAccessoryMessages.controlRequest(0, 2, mapOf(4361L to emptyMap<String,Any?>())).asRcsMessage())
                assertTrue(receive(phone).errors().isEmpty()); assertEquals(1, source.calls)
                peer.phone.soTimeout = 100
                assertThrows(java.net.SocketTimeoutException::class.java) { peer.receive() }
            }
        } } finally { p.close() }
    }

    @Test fun connectingAndReadingTheRadioNeverTakesAudioFromIphone() {
        val source = Source()
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            connect(p).use { phone ->
                phone.send(CarAccessoryMessages.configRequest(0, 1).asRcsMessage())
                assertEquals(CafCommand.CONFIG_RESPONSE, receive(phone).command)
                phone.send(CarAccessoryMessages.readRequest(0, 2, listOf(4359L)).asRcsMessage())
                assertEquals(CafCommand.READ_RESPONSE, receive(phone).command)
                assertEquals(0, source.calls)
                peer.phone.soTimeout = 100
                assertThrows(java.net.SocketTimeoutException::class.java) { peer.receive() }
            }
        } } finally { p.close() }
    }

    @Test fun rememberedIphonePlaybackStopsABootRadioWithoutTakingMainAudioOrAnnouncingItAsNowPlaying() {
        val source = Source(UltraMediaSource.CARPLAY).apply { echoPlayback = true; emit(Source.radio(true)) }
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            assertEquals(VoyahRadioOem.Command.PAUSE, source.writes.poll(2, TimeUnit.SECONDS))
            connect(p).use { phone ->
                phone.send(CarAccessoryMessages.readRequest(0, 1, listOf(4098L, 4103L, 4354L)).asRcsMessage())
                val values = receive(phone).valuesMap()
                assertEquals(VoyahRadioDescription.SOURCE, values[4098L])
                assertEquals("", values[4354L]); assertFalse((values[4103L] as List<*>).isEmpty())
                assertTrue(source.remembered.isEmpty())
                peer.phone.soTimeout = 100
                assertThrows(java.net.SocketTimeoutException::class.java) { peer.receive() }
            }
        } } finally { p.close() }
    }

    @Test fun changingFromRadioToIphoneUpdatesCafAndRemembersOnlyGenuineMediaPlayback() {
        val source = Source().apply { echoPlayback = true; emit(Source.radio(true)) }
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            peer.answer(peer.receive(), true)
            connect(p).use { phone ->
                phone.send(CarAccessoryMessages.registerRequest(0, 1, CafRegistration.Identifiers(listOf(4354L))).asRcsMessage())
                assertEquals(VoyahRadioDescription.SOURCE, receive(phone).valuesMap()[4354L])
                p.onIphoneMediaPlayback(peer.session, true, initial = true)
                p.onModesChanged(peer.session, mapOf("appStates" to listOf(
                    mapOf("appStateID" to 1, "entity" to 1, "speechMode" to 2))))
                p.onModesChanged(peer.session, mapOf("appStates" to listOf(
                    mapOf("appStateID" to 2, "entity" to 1))))
                assertTrue(source.remembered.isEmpty()); assertNull(source.writes.poll(100, TimeUnit.MILLISECONDS))
                p.onIphoneMediaPlayback(peer.session, true, initial = false)
                assertEquals(VoyahRadioOem.Command.PAUSE, source.writes.poll(2, TimeUnit.SECONDS))
                assertEquals("", receive(phone).valuesMap()[4354L])
                assertEquals(listOf(UltraMediaSource.CARPLAY), source.remembered)
            }
        } } finally { p.close() }
    }

    @Test fun modesChangedOnTheEventChannelCompletesARequestAndIsAcknowledged() {
        Peer().use { peer ->
            val task = FutureTask { peer.session.takeAudioForRadio() }
            Thread(task).apply { isDaemon = true; start() }
            peer.receive()
            val body = BplistCodec.encode(mapOf("type" to "modesChanged", "params" to mapOf("resources" to
                listOf(mapOf("resourceID" to "mainAudio", "entity" to "accessory")))))
            val header = "POST /command RTSP/1.0\r\nCSeq: 71\r\nContent-Length: ${body.size}\r\n\r\n"
            peer.send(header.toByteArray() + body)
            assertTrue(task.get(2, TimeUnit.SECONDS))
            val ack = peer.receive(); assertEquals("RTSP/1.0", ack.method); assertEquals("71", ack.headers["cseq"])
        }
    }

    @Test fun speechModesOnTheEncryptedEventChannelAreAcknowledgedAndForwardedToTheMediaSink() {
        val delivered = java.util.concurrent.LinkedBlockingQueue<Map<String, Any?>>()
        Peer(object : AirPlayMediaHandler {
            override fun onModesChanged(session: AirPlaySession, params: Map<String, Any?>) { delivered.offer(params) }
        }).use { peer ->
            val body = BplistCodec.encode(mapOf("type" to "modesChanged", "params" to mapOf("appStates" to
                listOf(mapOf("appStateID" to 1, "entity" to 1, "speechMode" to 2)))))
            peer.send("POST /command RTSP/1.0\r\nCSeq: 81\r\nContent-Length: ${body.size}\r\n\r\n".toByteArray() + body)
            val ack = peer.receive(); assertEquals("81", ack.headers["cseq"])
            val modes = delivered.poll(1, TimeUnit.SECONDS) ?: throw AssertionError("No speech modes")
            assertEquals(2L, ((modes["appStates"] as List<*>).single() as Map<*, *>)["speechMode"])
        }
    }

    @Test fun failedOemStartReleasesTheTakenAudioWithoutClaimingSuccess() {
        val source = Source().apply { confirm = false }
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            connect(p).use { phone ->
                play(phone); peer.answer(peer.receive(), true)
                assertEquals(-6727L, receive(phone).errors()[4360L]); assertEquals(1, source.calls)
                val undo = BplistCodec.decode(peer.receive().body) as Map<*, *>
                val resources = (undo["params"] as Map<*, *>)["resources"] as List<*>
                assertEquals(2L, (resources.single() as Map<*, *>)["transferType"])
            }
        } } finally { p.close() }
    }

    @Test fun nativeRadioStartTakesAudioAndPublishesTheStationWithoutSendingOemPlay() {
        val source = Source()
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            connect(p).use { phone ->
                phone.send(CarAccessoryMessages.registerRequest(0, 1,
                    CafRegistration.Identifiers(listOf(4102L, 4103L, 4354L, 4356L, 4359L))).asRcsMessage())
                assertEquals(0L, receive(phone).valuesMap()[4359L])
                val active = Source.radio(true).let { it.copy(state = it.state!!.copy(radioText = "Artist - Song")) }
                source.emit(active) // Native/passenger-screen action, no CAF command.
                val take = peer.receive()
                val body = BplistCodec.decode(take.body) as Map<*, *>
                assertEquals("changeModes", body["type"])
                assertEquals(0, source.calls)
                peer.answer(take, true)
                val update = receive(phone)
                assertEquals(CafCommand.UPDATE_NOTIFY, update.command)
                assertEquals(1L, update.valuesMap()[4359L])
                assertEquals("Artist - Song", update.valuesMap()[4356L])
                phone.send(CarAccessoryMessages.readRequest(0, 2, listOf(4102L, 4103L, 4354L)).asRcsMessage())
                val values = receive(phone).valuesMap()
                assertEquals("FM:10170", values[4102L])
                assertEquals("VOYAH_RADIO", values[4354L])
                assertEquals("Station · FM 101.7 MHz", ((values[4103L] as List<*>).single() as Map<*, *>)["mediaItemName"])
                source.emit(active.copy(state = active.state!!.copy(radioText = "Next song")))
                peer.phone.soTimeout = 150
                assertThrows(java.net.SocketTimeoutException::class.java) { peer.receive() }
                assertEquals(0, source.calls)
            }
        } } finally { p.close() }
    }

    @Test fun aNativeStartBeforeTheSessionIsAttachedIsNotLost() {
        val source = Source().apply { emit(Source.radio(true)) }
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
            peer.phone.soTimeout = 100
            assertThrows(java.net.SocketTimeoutException::class.java) { peer.receive() }
            p.onSetupResponseSent(peer.session)
            peer.phone.soTimeout = 2500
            peer.answer(peer.receive(), true)
            assertEquals(0, source.calls)
        } } finally { p.close() }
    }

    @Test fun cafPlayEchoDoesNotSendASecondTakeAndNativeRestartAfterIphonePlaybackDoes() {
        val source = Source().apply { echoPlayback = true }
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            connect(p).use { phone ->
                play(phone); peer.answer(peer.receive(), true)
                assertTrue(receive(phone).errors().isEmpty())
                peer.phone.soTimeout = 150
                assertThrows(java.net.SocketTimeoutException::class.java) { peer.receive() }
                source.emit(Source.radio(false)) // OEM yields after new iPhone media playback.
                source.emit(Source.radio(true))
                peer.phone.soTimeout = 2500
                peer.answer(peer.receive(), true)
                assertEquals(1, source.calls)
            }
        } } finally { p.close() }
    }

    @Test fun nativeStopDuringTheOwnershipWaitCancelsALateGrant() {
        val source = Source()
        val p = VoyahUltraProtocol({ source }, { 1000L }, {})
        try { Peer().use { peer ->
            p.onFeaturesNegotiated(listOf("vehicleStateProtocol")); p.onSetupResponseSent(peer.session)
            source.emit(Source.radio(true))
            val take = peer.receive()
            source.emit(Source.radio(false))
            peer.answer(take, true)
            val cancel = BplistCodec.decode(peer.receive().body) as Map<*, *>
            val resources = (cancel["params"] as Map<*, *>)["resources"] as List<*>
            assertEquals(2L, (resources.single() as Map<*, *>)["transferType"])
            assertEquals(0, source.calls)
        } } finally { p.close() }
    }
}
