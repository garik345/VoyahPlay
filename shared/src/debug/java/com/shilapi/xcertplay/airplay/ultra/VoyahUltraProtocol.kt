package com.shilapi.xcertplay.airplay.ultra

import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.airplay.rcs.*
import com.shilapi.xcertplay.airplay.rcs.caf.*
import com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientTypes
import java.io.Closeable
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface UltraClimateSource : Closeable {
    fun start(onSample: (UltraClimateSample?) -> Unit)
}

/** Debug-only VSP/CAF pilot. The existing iAP2, video, audio and pairing paths stay in charge. */
class VoyahUltraProtocol(
    private val sourceFactory: () -> UltraClimateSource,
    private val clock: () -> Long,
    private val trace: (String) -> Unit,
) : AirPlayProtocolExtension, Closeable {
    @Volatile private var sample: UltraClimateSample? = null
    @Volatile private var negotiated = false
    private val closed = AtomicBoolean(false)
    private val sourceStarted = AtomicBoolean(false)
    private val channels = ConcurrentHashMap<Long, Channel>()
    // Keep closed CAF identities until this control session ends: TEARDOWN may arrive after EOF.
    private val cafConnections = ConcurrentHashMap.newKeySet<String>()
    private val nextId = AtomicLong(10_000)
    private val source by lazy(sourceFactory)

    override fun forSession(): AirPlayProtocolExtension = VoyahUltraProtocol(sourceFactory, clock, trace)

    override fun decorateInfo(info: MutableMap<String, Any?>) {
        info["vehicleStateProtocolInfo"] = linkedMapOf(
            "protocolVersion" to "1.0", "pluginCount" to 1,
            "pluginConfigs" to listOf(mapOf("pluginID" to VoyahClimateAccessory.PLUGIN_ID)),
            "pluginMapping" to mapOf("org.voyahplay.climate.test" to VoyahClimateAccessory.PLUGIN_ID),
        )
        log("INFO VSP=1 plugin=1 climate=driver-target READ_ONLY=1")
    }

    override fun enabledFeatures(proposed: List<*>?): List<String> {
        log("FEATURES proposed=" + proposed.orEmpty().filterIsInstance<String>().joinToString(",").take(1500))
        return if (proposed.orEmpty().contains(FEATURE)) listOf(FEATURE) else emptyList()
    }

    override fun onInfoRequest(body: ByteArray) {
        log("INFO REQUEST " + if (body.isEmpty()) "empty" else runCatching {
            ProtocolTraceFormatter.pretty(BplistCodec.decode(body))
        }.getOrElse { "decode error: ${it.message}" })
    }

    override fun onFeaturesNegotiated(features: List<String>) {
        negotiated = FEATURE in features
        log("NEGOTIATED VSP=${if (negotiated) 1 else 0}; enabled=${features.joinToString(",")}")
    }

    override fun openDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = stream["clientTypeUUID"] as? String ?: return null
        val client = RcsClientTypes.findByUuid(uuid) ?: return null
        if (client != RcsClientTypes.CAR_PLAY_PROTOCOL_DATA && client != RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2) return null
        if (!negotiated || closed.get()) { log("RCS rejected: VSP not negotiated or session closed"); return null }
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        fun key(label: String) = AirPlayCrypto.hkdfSha512(shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII), label.toByteArray(Charsets.US_ASCII), 32)
        return openChannel(client, key("DataStream-Output-Encryption-Key"),
            key("DataStream-Input-Encryption-Key"), session.localAddress ?: InetAddress.getByName("::"), stream)
    }

    internal fun openChannel(client: com.shilapi.xcertplay.airplay.rcs.catalog.RcsClientType,
        readKey: ByteArray, writeKey: ByteArray, address: InetAddress, stream: Map<String, Any?>): Map<String, Any?> {
        check(negotiated && !closed.get()) { "Ultra VSP is not active" }
        require(client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA || client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2)
        channels.entries.toList().filter { it.value.data.clientType == client }.forEach { (id, previous) ->
            channels.remove(id, previous)
            previous.close()
        }
        val data = RcsDataStream.open(client, readKey, writeKey, address, ::log)
        val channel = Channel(data, unsignedPlistDecimal(stream["streamConnectionID"]))
        channel.connectionId?.let { cafConnections.add(it) }
        val id = nextId.getAndIncrement()
        val port = try { data.listen() } catch (error: Throwable) { channel.close(); throw error }
        channels[id] = channel
        log("RCS LISTEN client=${client.name} streamID=$id port=$port")
        return linkedMapOf<String, Any?>("type" to 130, "streamID" to id, "dataPort" to port).apply {
            stream["streamConnectionID"]?.let { put("streamConnectionID", unsignedPlistInteger(it)) }
        }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        attachPending()
    }

    internal fun attachPending() {
        channels.values.forEach { channel ->
            if (channel.attached.compareAndSet(false, true)) channel.data.attach(channel)
        }
    }

    override fun mediaTeardownTypes(body: Map<String, Any?>?, original: List<Int>?): List<Int>? {
        val entries = body?.get("streams") as? List<*> ?: return original
        if (entries.isEmpty()) return original
        val media = entries.filterNot { value ->
            val entry = value as? Map<*, *> ?: return@filterNot false
            if ((entry["type"] as? Number)?.toInt() != 130) return@filterNot false
            val client = (entry["clientTypeUUID"] as? String)?.let(RcsClientTypes::findByUuid)
            val connection = unsignedPlistDecimal(entry["streamConnectionID"])
            client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA || client == RcsClientTypes.CAR_PLAY_PROTOCOL_DATA_2 ||
                (connection != null && connection in cafConnections)
        }
        if (media.size == entries.size) return original
        return if (media.isEmpty()) emptyList() else teardownStreamTypes(mapOf("streams" to media)).orEmpty()
    }

    override fun onTeardown(session: AirPlaySession, body: Map<String, Any?>?) {
        teardownChannels(body)
    }

    internal fun teardownChannels(body: Map<String, Any?>?) {
        val entries = body?.get("streams") as? List<*>
        if (entries.isNullOrEmpty()) { closeChannels(); return }
        entries.filterIsInstance<Map<*, *>>().forEach { entry ->
            if ((entry["type"] as? Number)?.toInt() != 130) return@forEach
            val streamId = (entry["streamID"] as? Number)?.toLong()
            val connectionId = unsignedPlistDecimal(entry["streamConnectionID"])
            val uuid = entry["clientTypeUUID"] as? String
            channels.entries.toList().filter { (id, channel) ->
                when {
                    streamId != null -> streamId == id
                    connectionId != null -> connectionId == channel.connectionId
                    uuid != null -> RcsClientTypes.findByUuid(uuid) == channel.data.clientType
                    else -> true
                }
            }.forEach { (id, channel) -> channels.remove(id, channel); channel.close() }
        }
    }

    override fun onSessionClosed(session: AirPlaySession) {
        close()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        closeChannels()
        if (sourceStarted.get()) source.close()
        cafConnections.clear()
        log("SESSION CLOSED")
    }

    private fun closeChannels() {
        channels.entries.toList().forEach { (id, channel) -> channels.remove(id, channel); channel.close() }
    }

    private fun beginSource() {
        if (closed.get() || !sourceStarted.compareAndSet(false, true)) return
        source.start { value ->
            if (closed.get()) return@start
            val changed = sample?.driverTargetCelsius != value?.driverTargetCelsius
            sample = value
            if (changed) log("VEHICLE driverTargetC=${value?.driverTargetCelsius ?: "unavailable"}")
            channels.values.forEach { it.publish() }
        }
    }

    private fun log(text: String) { runCatching { trace(text.take(3000)) } }

    private inner class Channel(val data: RcsDataStream, val connectionId: String?) : RcsDataStreamHandler, Closeable {
        val attached = AtomicBoolean(false)
        private val stopped = AtomicBoolean(false)
        private val accessory = VoyahClimateAccessory({ sample }, clock)
        private val worker = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(64),
            { runnable -> Thread(runnable, "voyah-ultra-caf").apply { isDaemon = true } })

        override fun onStreamOpened(stream: RcsDataStream) {
            log("RCS CONNECTED client=${stream.clientType.name}")
            beginSource()
        }

        override fun onMessage(stream: RcsDataStream, message: RcsMessage) {
            if (message.body.size > 262_144) { log("RCS body exceeds pilot limit"); close(); return }
            enqueue {
                if (message.messageType != com.shilapi.xcertplay.airplay.rcs.transport.ApTransportPackageCodec.MESSAGE_TYPE_COMM)
                    throw CafProtocolException("Expected CAF comm package")
                val request = CarAccessoryMessages.reader(message.body)
                log("CAF RX plugin=${request.pluginId} command=${request.command.wireName} values=${ProtocolTraceFormatter.pretty(request.values())}")
                accessory.handle(request)?.let(::send)
            }
        }

        fun publish() = enqueue { accessory.changed()?.let(::send) }
        private fun send(frame: CafRcsFrame) {
            log("CAF TX command=${frame.envelope.message.command.wireName} values=${ProtocolTraceFormatter.pretty(frame.envelope.message.values)}")
            data.send(frame.asRcsMessage())
        }
        private fun enqueue(action: () -> Unit) {
            if (stopped.get()) return
            try { worker.execute { runCatching(action).onFailure { log("CAF ERROR ${it.javaClass.simpleName}: ${it.message}"); close() } } }
            catch (_: java.util.concurrent.RejectedExecutionException) { if (!stopped.get()) { log("CAF queue full"); close() } }
        }
        override fun onStreamClosed(stream: RcsDataStream, cause: Throwable?) {
            log("RCS CLOSED client=${stream.clientType.name} reason=${cause?.message ?: "EOF"}")
            close()
        }
        override fun close() {
            if (!stopped.compareAndSet(false, true)) return
            channels.entries.removeIf { it.value === this }
            worker.shutdownNow()
            data.close()
        }
    }

    companion object { const val FEATURE = "vehicleStateProtocol" }
}
