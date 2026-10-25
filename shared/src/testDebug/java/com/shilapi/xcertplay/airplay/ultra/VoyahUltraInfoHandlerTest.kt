package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageFactory
import com.shilapi.xcertplay.airplay.rcs.uisync.CarPlayUiSyncMessageType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs the real GET /info handler, which logs through android.util.Log, so it needs Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class VoyahUltraInfoHandlerTest {
    @Test fun dataStreamDispatchUsesNegotiatedUiSyncAndTheRealDirectionalKeys() {
        val trace = mutableListOf<String>()
        val p = VoyahUltraProtocol({ object : UltraClimateSource {
            override fun start(onSample: (UltraClimateSample?) -> Unit) {}
            override fun close() {}
        } }, { 0L }, { trace.add(it) }, assetProbe = true)
        val session = AirPlaySession(java.net.Socket(),
            AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1", AirPlayDisplayConfig(1775,675)),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        val shared = ByteArray(32) { (it + 77).toByte() }
        val entry = mapOf("type" to 130, "clientTypeUUID" to RcsClientTypes.CAR_PLAY_CLUSTER_CONTROL.uuid.toString(),
            "seed" to 1234L, "streamConnectionID" to 42L)
        try {
            assertNull(p.openDataStream(session, entry))
            p.onFeaturesNegotiated(listOf("uiSync"))
            assertNull(p.openDataStream(session, entry))
            session.pairVerify.javaClass.getDeclaredField("sharedSecret").apply { isAccessible = true }
                .set(session.pairVerify, shared)
            assertNull(p.openDataStream(session, entry - "seed"))
            assertTrue(trace.any { "RCS SETUP client=CarPlayClusterControl" in it })
            assertTrue(trace.any { "reason=uiSync not negotiated" in it })
            assertTrue(trace.any { "reason=missing AirPlay shared secret" in it })
            assertTrue(trace.any { "reason=missing or invalid seed" in it })
            val response = p.openDataStream(session, entry)!!
            fun key(label: String) = AirPlayCrypto.hkdfSha512(shared, "DataStream-Salt1234".toByteArray(), label.toByteArray(), 32)
            p.onSetupResponseSent(session)
            RcsChannel.connect(java.net.InetSocketAddress("127.0.0.1", response["dataPort"] as Int),
                key("DataStream-Input-Encryption-Key"), key("DataStream-Output-Encryption-Key")).use { phone ->
                assertNull("phone initiates UI Sync", phone.receiveNext(150))
                val body = com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec.encode(mapOf(
                    "displayUUID" to "Cluster_Display", "data" to BplistCodec.encode(mapOf(
                        "type" to 0, "ssn" to 1, "psn" to 1, "asn" to 0, "version" to 3))))
                phone.send(com.shilapi.xcertplay.airplay.rcs.RcsMessage.comm(body))
                val frame = com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec.decode(phone.receiveNext(3000)!!.body) as Map<*, *>
                val reset = BplistCodec.decode(frame["data"] as ByteArray) as Map<*, *>
                assertEquals(0L, reset["type"])
                assertEquals(2L, reset["version"])
            }
        } finally { p.close(); session.close() }
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

    @Test fun authSetupIsReportedToTheExtensionWithTheAuthType() {
        val seen = mutableListOf<String>()
        val extension = object : AirPlayProtocolExtension {
            override fun forSession() = this
            override fun onAuthSetup(headers: Map<String, String>, body: ByteArray, status: Int) {
                seen += "${headers["x-apple-at"]}/${body.size}/$status"
            }
        }
        val session = AirPlaySession(java.net.Socket(),
            AirPlayConfig("test", "02:00:00:00:00:01", "02:00:00:00:00:02", "950.7.1", AirPlayDisplayConfig(1775,675), protocolExtension = extension),
            AirPlayIdentity.generate(), PairingStore(), null, object : AirPlaySessionListener {}, object : AirPlayMediaHandler {})
        try {
            val response = AirPlaySession::class.java.getDeclaredMethod("handle", RtspMessage.Request::class.java)
                .apply { isAccessible = true }.invoke(session,
                    RtspMessage.Request("POST", "/auth-setup", "RTSP/1.0", mapOf("x-apple-at" to "32"), ByteArray(40))) as RtspMessage.Response
            assertEquals(400, response.status)
            assertEquals(listOf("32/40/400"), seen)
        } finally { session.close() }
    }
}
