package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AirPlayInfoLatencyTest {
    @Test fun infoIsFullyFlushedBeforeTransmitDiagnosticsCanBlock() {
        for (encrypted in listOf(false, true)) for (padding in listOf(0, 40_446)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            Peer(padding, encrypted) { text ->
                if (text.startsWith("TRACE airplay control tx wireHex=")) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }.use { peer ->
                try {
                    peer.send(request(71))
                    assertTrue("TX diagnostics not reached", entered.await(3, TimeUnit.SECONDS))
                    val reply = peer.receive()
                    assertEquals("200", reply.path)
                    assertEquals("71", reply.headers["cseq"])
                    assertArrayEquals(peer.expectedBody, reply.body)
                    assertEquals("Reply must arrive while diagnostics are still blocked", 1L, release.count)
                } finally { release.countDown() }
            }
        }
    }

    @Test fun pipelinedEncryptedInfoKeepsBytesFullTracesAndNonceOrder() {
        val traces = CopyOnWriteArrayList<String>()
        val completed = CountDownLatch(2)
        Peer(40_446, true) { text ->
            traces += text
            if (text.startsWith("airplay info timing ")) completed.countDown()
        }.use { peer ->
            peer.send(request(81) + request(82))
            for (sequence in listOf(81, 82)) {
                val reply = peer.receive()
                assertEquals("RTSP/1.0", reply.method)
                assertEquals("200", reply.path)
                assertEquals(sequence.toString(), reply.headers["cseq"])
                assertArrayEquals(peer.expectedBody, reply.body)
            }
            assertTrue(completed.await(3, TimeUnit.SECONDS))
            val tx = traces.filter { it.startsWith("TRACE airplay control tx wireHex=") }
            assertEquals(2, tx.size)
            for ((index, sequence) in listOf(81, 82).withIndex()) {
                val expectedWire = RtspMessage.buildResponse(
                    RtspMessage.Request("GET", "/info", "RTSP/1.0", mapOf("cseq" to "$sequence"), ByteArray(0)),
                    RtspMessage.Response(headers = mapOf("Content-Type" to "application/x-apple-binary-plist"), body = peer.expectedBody),
                )
                val expectedHex = BigInteger(1, expectedWire).toString(16).padStart(expectedWire.size * 2, '0')
                assertEquals("TRACE airplay control tx wireHex=$expectedHex", tx[index])
            }
            // Empty request bodies retain their original empty hex rather than a preview marker.
            assertEquals(2, traces.count { it.startsWith("TRACE airplay control rx ") && it.endsWith("bodyHex=") })
        }
    }

    private fun request(sequence: Int) =
        "GET /info RTSP/1.0\r\nCSeq: $sequence\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.US_ASCII)

    private class Peer(padding: Int, encrypted: Boolean, trace: (String) -> Unit) : Closeable {
        private val phone: Socket
        private val session: AirPlaySession
        private val cipher: ControlCipher?
        private var sealed = ByteArray(0)
        private var plain = ByteArray(0)
        private val responses = ArrayDeque<RtspMessage.Request>()
        val expectedBody: ByteArray

        init {
            val address = InetAddress.getByName("127.0.0.1")
            val accepted = ServerSocket(0, 1, address).use { listener ->
                phone = Socket(address, listener.localPort).apply { soTimeout = 3000; tcpNoDelay = true }
                listener.accept()
            }
            val extension = object : AirPlayProtocolExtension {
                override fun forSession() = this
                override fun decorateInfo(info: MutableMap<String, Any?>) {
                    if (padding > 0) info["testBinary"] = ByteArray(padding) { it.toByte() }
                }
            }
            val config = AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1",
                AirPlayDisplayConfig(1556, 674), protocolExtension = extension)
            expectedBody = BplistCodec.encode(AirPlayInfoPlist.build(config))
            if (padding == 0) check(expectedBody.size < 8192) { "Small reply must fit the output buffer" }
            session = AirPlaySession(accepted, config, AirPlayIdentity.generate(), PairingStore(), null,
                object : AirPlaySessionListener { override fun onDebugLog(message: String) { trace(message) } },
                object : AirPlayMediaHandler {})
            val read = ByteArray(32) { it.toByte() }
            val write = ByteArray(32) { (it + 37).toByte() }
            cipher = if (encrypted) ControlCipher(write, read) else null
            if (encrypted) session.cipher = ControlCipher(read, write)
            session.start()
        }

        fun send(bytes: ByteArray) {
            phone.getOutputStream().write(cipher?.encrypt(bytes) ?: bytes)
            phone.getOutputStream().flush()
        }

        fun receive(): RtspMessage.Request {
            val buffer = ByteArray(65_536)
            while (true) {
                val parsed = RtspMessage.parseMessages(plain)
                plain = parsed.rest
                responses.addAll(parsed.messages)
                responses.pollFirst()?.let { return it }
                val count = phone.getInputStream().read(buffer)
                check(count >= 0) { "Peer closed before its full response" }
                val bytes = buffer.copyOf(count)
                if (cipher == null) plain += bytes else {
                    sealed += bytes
                    val decrypted = cipher.decrypt(sealed)
                    sealed = decrypted.rest
                    plain += decrypted.data
                }
            }
        }

        override fun close() { phone.close(); session.close() }
    }
}
