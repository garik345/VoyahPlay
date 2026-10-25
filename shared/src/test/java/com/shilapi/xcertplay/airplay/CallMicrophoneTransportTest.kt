package com.shilapi.xcertplay.airplay

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A real SETUP/downlink/uplink round trip; the phone sees the source port it negotiated. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class CallMicrophoneTransportTest {
    @Test fun callUplinkUsesTheSetupDataPortAndEndsWithItsOwnAudioStream() {
        val host = InetAddress.getByName("127.0.0.1")
        val server = ServerSocket(0, 1, host)
        val client = Socket(host, server.localPort)
        val socket = server.accept()
        val microphone = AtomicReference<MicrophoneConfig>()
        val started = CountDownLatch(1)
        val engine = CarPlayMediaEngine(object : MediaSink {
            override fun onMicrophoneStarted(id: AudioStreamId, config: MicrophoneConfig) {
                microphone.set(config); started.countDown()
            }
        }, microphoneEnabled = true)
        val session = AirPlaySession(socket = socket, config = AirPlayConfig(
            deviceName = "test", deviceId = "02:00:00:00:00:02", btMac = "02:00:00:00:00:01", sourceVersion = "1.0",
            main = AirPlayDisplayConfig(widthPixels = 800, heightPixels = 480)), identity = AirPlayIdentity.generate(),
            pairings = PairingStore(), mfi = null, listener = object : AirPlaySessionListener {}, media = engine)
        val secret = ByteArray(32) { it.toByte() }
        session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }.set(session.pairVerify, secret)
        fun key(direction: String) = AirPlayCrypto.hkdfSha512(secret, "DataStream-Salt42".toByteArray(),
            "DataStream-$direction-Encryption-Key".toByteArray(), 32)
        DatagramSocket(0, host).use { phone ->
            phone.soTimeout = 3000
            try {
                val setup = engine.onAudio(session, 100, mapOf("audioType" to "telephony", "audioFormat" to 0x40000000L,
                    "streamConnectionID" to 42L, "input" to true, "dataPort" to phone.localPort))!!
                val dataPort = (setup.getValue("dataPort") as Number).toInt()
                val downlink = MicrophonePacketizer.sealPacket(key("Output"), 100, MicrophoneCounters(), ByteArray(16), 960)
                phone.send(DatagramPacket(downlink, downlink.size, host, dataPort))
                assertTrue("Downlink did not start capture", started.await(3, TimeUnit.SECONDS))
                val config = microphone.get()
                assertArrayEquals(key("Input"), config.key)
                val payload = byteArrayOf(1, 2, 3, 4)
                val uplink = MicrophonePacketizer.sealPacket(config.key, 100, MicrophoneCounters(), payload, 960)
                config.transport!!.send(uplink)
                val received = DatagramPacket(ByteArray(2048), 2048)
                phone.receive(received)
                assertEquals("Call input must come from the SETUP port", dataPort, received.port)
                assertArrayEquals(uplink, received.data.copyOf(received.length))
                engine.onTeardown(session, 100)
                assertThrows(IOException::class.java) { config.transport!!.send(uplink) }
                assertFalse("Receiver must never close the phone socket", phone.isClosed)
            } finally { engine.onSessionClosed(session); session.close(); client.close(); server.close() }
        }
    }
}
