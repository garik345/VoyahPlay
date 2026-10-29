package com.shilapi.xcertplay.voyah.ultra

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.IBinder
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.ultra.UltraAudioChannel
import com.shilapi.xcertplay.airplay.ultra.UltraClimateWriteResult

/** Vendor volume groups. Reading/setting a volume never requests focus or touches mute. */
internal class VoyahOemAudioChannels(
    private val connect: () -> Service,
    private val clock: () -> Long,
    private val report: (String) -> Unit,
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    interface Service {
        fun alive(): Boolean
        fun maximum(stream: Int): Int
        fun volume(stream: Int): Int
        fun set(stream: Int, value: Int)
        val linkedPhoneAndRingtone: Boolean get() = false
        val verifiesPlayback: Boolean get() = false
        fun playbackVolume(stream: Int): Int? = null
    }
    constructor(context: Context, report: (String) -> Unit) : this(
        { BinderService(context.applicationContext) }, SystemClock::elapsedRealtime, report)
    private var service: Service? = null
    private var retryAt = 0L
    private var advertised: Map<UltraAudioChannel, IntRange>? = null
    @Volatile var values: Map<UltraAudioChannel, Int> = emptyMap(); private set
    @Volatile var sampledAt = 0L; private set
    private fun api(): Service? {
        service?.takeIf { it.alive() }?.let { return it }
        service = null
        if (clock() < retryAt) return null
        retryAt = clock() + 5_000
        return connect().also { service = it }
    }
    private fun failed(error: Exception) {
        service = null; retryAt = clock() + 5_000
        report("AUDIO_CHANNELS unavailable ${error.javaClass.simpleName}: ${error.cause?.javaClass?.simpleName ?: error.message}")
    }
    @Synchronized fun read(): Map<UltraAudioChannel, Int> {
        val result = linkedMapOf<UltraAudioChannel, Int>()
        try {
            api()?.let { api ->
                val ranges = linkedMapOf<UltraAudioChannel, IntRange>()
                for (c in UltraAudioChannel.entries) {
                    // The verified OS engine routes both OEM 2 and 6 to VolumeGroupComm.
                    // A second slider would imply a separate level the car cannot store.
                    if (c == UltraAudioChannel.RINGTONE && api.linkedPhoneAndRingtone) continue
                    val max = api.maximum(c.oemStream)
                    val value = if (checksPlayback(api, c)) api.playbackVolume(c.oemStream) else api.volume(c.oemStream)
                    if (max in 1..255 && value != null && value in 0..max) {
                        ranges[c] = 0..max
                        // A changed vendor range needs a new CAF description, never scale silently.
                        if (advertised == null || advertised?.get(c) == ranges[c]) result[c] = value
                    }
                }
                if (advertised == null && ranges.isNotEmpty()) advertised = ranges.toMap()
            }
        } catch (error: Exception) { failed(error); result.clear() }
        values = result.toMap(); sampledAt = clock()
        return values
    }
    /** Frozen for the session; a missing service yields no extra controls. */
    @Synchronized fun ranges(): Map<UltraAudioChannel, IntRange> {
        if (advertised == null) read()
        return advertised.orEmpty()
    }
    private fun checksPlayback(api: Service, c: UltraAudioChannel) = api.verifiesPlayback &&
        (c == UltraAudioChannel.NAVIGATION || c == UltraAudioChannel.SIRI)
    @Synchronized fun write(channel: UltraAudioChannel, wanted: Int): UltraClimateWriteResult {
        val range = ranges()[channel]
        if (range == null || wanted !in range) return UltraClimateWriteResult(false, "Unsupported audio channel/value")
        return try {
            val target = api() ?: return UltraClimateWriteResult(false, "OEM audio unavailable")
            if (target.maximum(channel.oemStream) != range.last) return UltraClimateWriteResult(false, "OEM volume range changed")
            target.set(channel.oemStream, wanted)
            val checked = checksPlayback(target, channel)
            var actual: Int? = null
            if (checked) for (attempt in 0..8) {
                actual = target.playbackVolume(channel.oemStream)
                if (actual == wanted || actual == null || attempt == 8) break
                pause(10) // OEM applies the group asynchronously; leave ample time for CAF's echo.
            }
            val oem = target.volume(channel.oemStream)
            val after = read()[channel]
            UltraClimateWriteResult(after == wanted && oem == wanted && (!checked || actual == wanted),
                "$channel readback=$after wanted=$wanted oem=$oem android=${actual?.toString() ?: if (checked) "unavailable" else "unchecked"} checked=$checked")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            UltraClimateWriteResult(false, "OEM audio confirmation interrupted")
        } catch (error: Exception) {
            failed(error); values = emptyMap(); sampledAt = clock()
            UltraClimateWriteResult(false, "OEM audio write unavailable")
        }
    }

    private class BinderService(context: Context) : Service {
        private val binder: IBinder
        private val proxy: Any
        private val type: Class<*>
        private val owner = context.packageName
        override val linkedPhoneAndRingtone: Boolean
        override val verifiesPlayback get() = linkedPhoneAndRingtone
        private var nativeLevels: AndroidLevels? = null
        init {
            val name = "com.qinggan.audiopolicy.AudioPolicyManager"
            val sdk = runCatching { Class.forName(name) }.getOrNull() ?: sequenceOf(
                "com.qinggan.audiopolicy.service", "com.qinggan.app.setting").mapNotNull { pkg ->
                runCatching {
                    val info = context.packageManager.getApplicationInfo(pkg, 0)
                    check(info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    context.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                        .classLoader.loadClass(name)
                }.getOrNull()
            }.firstOrNull() ?: error("OEM audio SDK unavailable")
            val loader = sdk.classLoader!!
            linkedPhoneAndRingtone = runCatching {
                val info = context.packageManager.getApplicationInfo("com.qinggan.audiopolicy.service", 0)
                check(info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                check(info.splitSourceDirs.isNullOrEmpty())
                val file = java.io.File(info.sourceDir)
                check(file.length() == 1_754_811L)
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val bytes = ByteArray(65_536)
                    while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
                }
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } ==
                    "8acdf92ffcaf68a3cca8b7d6e4c97824ae486cbf2876ea58ef7f719534649ab6"
            }.getOrDefault(false)
            binder = loader.loadClass("com.qinggan.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "qg.audio_policy") as? IBinder ?: error("OEM audio service unavailable")
            check(binder.isBinderAlive && binder.interfaceDescriptor == "com.qinggan.audiopolicy.IAudioPolicyService")
            type = loader.loadClass("com.qinggan.audiopolicy.IAudioPolicyService")
            proxy = loader.loadClass("com.qinggan.audiopolicy.IAudioPolicyService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: error("OEM audio proxy unavailable")
        }
        override fun alive() = binder.isBinderAlive
        override fun maximum(stream: Int) = type.getMethod("getStreamMaxVolume", Int::class.javaPrimitiveType).invoke(proxy, stream) as Int
        override fun volume(stream: Int) = type.getMethod("getStreamVolume", Int::class.javaPrimitiveType, String::class.java).invoke(proxy, stream, owner) as Int
        override fun playbackVolume(stream: Int): Int? {
            val androidStream = when (stream) { 1 -> 1; 22 -> 10; else -> return null }
            return runCatching {
                val levels = nativeLevels?.takeIf { it.alive() } ?: AndroidLevels().also { nativeLevels = it }
                levels.volume(androidStream)
            }.getOrNull()
        }
        override fun set(stream: Int, value: Int) {
            type.getMethod("setStreamVolume", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java)
                .invoke(proxy, stream, value, 0, owner)
        }
    }

    /** Read Android directly: the ROM redirects AudioManager getters back to OEM IDs. No writes. */
    private class AndroidLevels {
        private val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "audio") as IBinder
        private val type = Class.forName("android.media.IAudioService")
        private val proxy = Class.forName("android.media.IAudioService\$Stub")
            .getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        init { check(binder.interfaceDescriptor == "android.media.IAudioService") }
        fun alive() = binder.isBinderAlive
        fun volume(stream: Int) = type.getMethod("getStreamVolume", Int::class.javaPrimitiveType).invoke(proxy, stream) as Int
    }
}
