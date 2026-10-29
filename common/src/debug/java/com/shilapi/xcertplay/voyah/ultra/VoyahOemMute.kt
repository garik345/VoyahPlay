package com.shilapi.xcertplay.voyah.ultra

import android.content.Context
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.IBinder
import android.os.SystemClock
import java.lang.reflect.Method

/** Called on the audio worker/CAF command worker, never from the UI polling loop. */
internal class VoyahOemMute(private val context: Context, private val report: (String) -> Unit) : VoyahNativeMute {
    internal interface Service {
        fun alive(): Boolean
        fun masterMuted(): Boolean
        fun mediaMuted(): Boolean
        fun clearMaster()
        fun clearMedia()
    }
    internal constructor(context: Context, report: (String) -> Unit, factory: () -> Service) : this(context, report) {
        connect = factory
    }
    private var connect: () -> Service = { BinderService(context) }
    private var service: Service? = null
    private var retryAt = 0L
    private var previous: String? = null

    private fun api(): Service? {
        service?.takeIf { it.alive() }?.let { return it }
        service = null
        if (SystemClock.elapsedRealtime() < retryAt) return null
        retryAt = SystemClock.elapsedRealtime() + 5_000
        return connect().also { service = it }
    }
    private fun failed(error: Exception) {
        service = null
        retryAt = SystemClock.elapsedRealtime() + 5_000
        record("unavailable ${error.javaClass.simpleName}: ${error.cause?.javaClass?.simpleName ?: error.message}")
    }
    private fun record(value: String) {
        if (previous != value) { previous = value; report("OEM_MUTE $value") }
    }
    @Synchronized override fun read(): Boolean? = try {
        api()?.let {
            val master = it.masterMuted()
            val media = it.mediaMuted()
            record("master=$master media=$media")
            master || media
        }
    } catch (error: Exception) { failed(error); null }

    @Synchronized override fun clear(): Boolean? = try {
        api()?.let {
            // Do not touch microphone, call, navigation, reverse or other policy mutes.
            if (it.masterMuted()) it.clearMaster()
            if (it.mediaMuted()) it.clearMedia()
            val master = it.masterMuted()
            val media = it.mediaMuted()
            record("master=$master media=$media")
            !master && !media
        }
    } catch (error: Exception) { failed(error); false }

    /** Use the installed SDK's proxy/signatures, without fixed Binder transaction numbers. */
    private class BinderService(context: Context) : Service {
        private val binder: IBinder
        private val proxy: Any
        private val master: Method
        private val media: Method
        private val setMaster: Method
        private val setMedia: Method
        init {
            val loader = sdk(context).classLoader!!
            binder = loader.loadClass("com.qinggan.os.ServiceManager").getMethod("getService", String::class.java)
                .invoke(null, "qg.audio_policy") as? IBinder ?: error("OEM audio service unavailable")
            check(binder.isBinderAlive && binder.interfaceDescriptor == "com.qinggan.audiopolicy.IAudioPolicyService")
            val type = loader.loadClass("com.qinggan.audiopolicy.IAudioPolicyService")
            proxy = loader.loadClass("com.qinggan.audiopolicy.IAudioPolicyService\$Stub")
                .getMethod("asInterface", IBinder::class.java).invoke(null, binder) ?: error("OEM audio proxy unavailable")
            master = type.getMethod("isMasterMute")
            media = type.getMethod("isStreamMute", Int::class.javaPrimitiveType)
            setMaster = type.getMethod("setMasterMute", Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            setMedia = type.getMethod("setStreamMute", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        }
        override fun alive() = binder.isBinderAlive
        override fun masterMuted() = master.invoke(proxy) as Boolean
        override fun mediaMuted() = media.invoke(proxy, AudioManager.STREAM_MUSIC) as Boolean
        override fun clearMaster() { setMaster.invoke(proxy, false, 0) }
        override fun clearMedia() { setMedia.invoke(proxy, AudioManager.STREAM_MUSIC, false) }
        private fun sdk(context: Context): Class<*> {
            val name = "com.qinggan.audiopolicy.AudioPolicyManager"
            try { return Class.forName(name) } catch (absent: ClassNotFoundException) {
                for (pkg in listOf("com.qinggan.audiopolicy.service", "com.qinggan.app.setting")) {
                    val info = try { context.packageManager.getApplicationInfo(pkg, 0) }
                        catch (_: android.content.pm.PackageManager.NameNotFoundException) { continue }
                    check(info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                    try {
                        return context.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE or Context.CONTEXT_IGNORE_SECURITY)
                            .classLoader.loadClass(name)
                    } catch (_: ClassNotFoundException) { }
                }
                throw absent
            }
        }
    }
}
