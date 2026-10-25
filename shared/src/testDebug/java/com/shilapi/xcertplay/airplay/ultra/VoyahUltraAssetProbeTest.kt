package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.rcs.RcsChannel
import com.shilapi.xcertplay.airplay.rcs.RcsMessage
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import com.shilapi.xcertplay.airplay.rcs.opack.OpackCodec
import com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec as AP
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections

class VoyahUltraAssetProbeTest {
    private val offered = listOf("uiSync", "vehicleStateProtocol", "fileTransfer", "logTransfer", "iAPChannel")
    private val log = Collections.synchronizedList(arrayListOf<String>())
    private val dumps = Collections.synchronizedList(arrayListOf<Pair<String, ByteArray>>())
    private fun protocol(probe: Boolean) = VoyahUltraProtocol({ Source() }, { 0L }, { log.add(it) },
        assetProbe = probe, dump = { name, bytes -> dumps.add(name to bytes) })

    @Test fun probeIsOffByDefaultButUiSyncHasItsOwnActiveHandler() {
        val p = VoyahUltraProtocol({ Source() }, { 0L }, {})
        assertEquals(listOf("vehicleStateProtocol", "uiSync"), p.enabledFeatures(offered))
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol"))
        try {
            p.openProbe(RcsClientTypes.CAR_PLAY_UPDATE_DATA, ByteArray(32), ByteArray(32), InetAddress.getByName("127.0.0.1"), emptyMap())
            fail("probe stream must not open when the probe is off")
        } catch (_: IllegalStateException) {
        } finally { p.close() }
    }

    @Test fun probeAddsOnlyFileTransferNeverLogTransferOrDuplicateUiSync() {
        val p = protocol(true)
        assertEquals(listOf("vehicleStateProtocol", "uiSync", "fileTransfer"), p.enabledFeatures(offered))
        assertEquals(emptyList<String>(), p.enabledFeatures(listOf("iAPChannel")))
        p.close()
    }

    @Test fun probeStreamNeedsItsFeatureToBeNegotiated() {
        val p = protocol(true)
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol", "uiSync"))
        try {
            p.openProbe(RcsClientTypes.CAR_PLAY_UPDATE_DATA, ByteArray(32), ByteArray(32), InetAddress.getByName("127.0.0.1"), emptyMap())
            fail("fileTransfer stream must not open when only uiSync was negotiated")
        } catch (_: IllegalStateException) {
        } finally { p.close() }
    }

    @Test fun probeRecordsBodiesAndOnlyAcknowledgesSyncFrames() {
        val p = protocol(true)
        p.onFeaturesNegotiated(listOf("vehicleStateProtocol", "fileTransfer", "uiSync"))
        val rx = ByteArray(32) { it.toByte() }; val tx = ByteArray(32) { (it + 7).toByte() }
        try {
            val stream = p.openProbe(RcsClientTypes.CAR_PLAY_UPDATE_DATA, rx, tx, InetAddress.getByName("127.0.0.1"),
                mapOf("streamConnectionID" to 42L)); p.attachPending()
            assertEquals(130, stream["type"])
            RcsChannel.connect(InetSocketAddress("127.0.0.1", stream["dataPort"] as Int), tx, rx).use { phone ->
                val body = OpackCodec.encode(mapOf("fileName" to "assets.pkg", "size" to 3L))
                phone.send(RcsMessage(AP.MESSAGE_TYPE_COMM, body, AP.KIND_SYNC, 99))
                val ack = phone.receiveNext(3000)!!
                assertEquals(AP.KIND_RPLY, ack.kind); assertEquals(99L, ack.token); assertTrue(ack.body.isEmpty())
                phone.send(RcsMessage(AP.MESSAGE_TYPE_COMM, byteArrayOf(1, 2, 3), AP.KIND_ASYN, 0))
                assertNull("an asyn frame gets no reply", phone.receiveNext(500))
                val deadline = System.currentTimeMillis() + 3000
                while (dumps.size < 2 && System.currentTimeMillis() < deadline) Thread.sleep(20)
            }
            assertEquals(listOf("CarPlayUpdateData-1", "CarPlayUpdateData-2"), dumps.map { it.first })
            assertArrayEquals(byteArrayOf(1, 2, 3), dumps[1].second)
            val lines = synchronized(log) { log.toList() }
            assertTrue(lines.toString(), lines.any { it.startsWith("ASSET PROBE RX client=CarPlayUpdateData n=1") && "assets.pkg" in it })
            // A probe stream is not a CAF channel and a teardown of it never closes the media streams.
            assertEquals(emptyList<Int>(), p.mediaTeardownTypes(mapOf("streams" to listOf(mapOf("type" to 130,
                "clientTypeUUID" to RcsClientTypes.CAR_PLAY_UPDATE_DATA.uuid.toString()))), listOf(130)))
        } finally { p.close() }
    }

    private class Source : UltraClimateSource {
        override fun start(onSample: (UltraClimateSample?) -> Unit) {}
        override fun close() {}
    }
}
