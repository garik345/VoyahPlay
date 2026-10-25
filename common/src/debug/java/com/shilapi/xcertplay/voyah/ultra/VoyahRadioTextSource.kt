package com.shilapi.xcertplay.voyah.ultra

import android.content.Context
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import com.shilapi.xcertplay.airplay.ultra.VoyahRadioOem
import java.io.Closeable
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only RT getter from the installed OEM SDK. Never starts playback or registers a listener. */
internal class VoyahRadioTextSource(private val app: Context, private val handler: Handler,
    private val trace: (String) -> Unit) : Closeable {
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "VoyahRadioText").apply { isDaemon = true } }
    private val busy = AtomicBoolean()
    @Volatile private var closed = false
    private var compatible: Boolean? = null // Only accessed by the worker; one APK check per source.
    private var lastError: String? = null

    fun poll(onRead: (VoyahRadioOem.RadioText?) -> Unit) {
        if (closed || !busy.compareAndSet(false, true)) return
        worker.execute {
            val started = SystemClock.elapsedRealtime()
            val value = try {
                if (compatible == null) compatible = verifiedSdk()
                if (compatible != true) null else read()
            } catch (e: Exception) {
                val error = "${e.javaClass.simpleName}: ${e.message}"
                if (error != lastError) { lastError = error; trace("RADIO RT unavailable $error") }
                null
            }
            busy.set(false)
            handler.post { if (!closed) onRead(value.takeIf { SystemClock.elapsedRealtime() - started <= 1_500 }) }
        }
    }

    private fun verifiedSdk(): Boolean {
        val file = File(app.packageManager.getApplicationInfo(VoyahRadioOem.PACKAGE, 0).sourceDir)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return (hash == SDK_SHA256).also { trace("RADIO RT SDK compatible=$it sha256=$hash") }
    }

    private fun read(): VoyahRadioOem.RadioText? {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java)
            .invoke(null, "adayo.dab") as? IBinder ?: return null
        val request = Parcel.obtain(); val reply = Parcel.obtain()
        return try {
            request.writeInterfaceToken("com.adayo.proxy.dab.aidl.IDABServiceInterface")
            // SDK IDABServiceInterface.Stub.Proxy.getCurrentRadioStation, verified in the local APK.
            if (!binder.transact(8, request, reply, 0)) null else decode(reply)
        } finally { reply.recycle(); request.recycle() }
    }

    override fun close() { closed = true; worker.shutdownNow() }

    companion object {
        private const val SDK_SHA256 = "36b4d73a889aa78325f6a6ff0c8113c6b13605620a5a4b045209fbff36449cca"

        /** The exact RadioStation Parcelable layout of the hash-checked OEM SDK. */
        internal fun decode(reply: Parcel): VoyahRadioOem.RadioText? {
            require(reply.dataAvail() in 8..4096)
            fun integer(): Int { require(reply.dataAvail() >= 4); return reply.readInt() }
            fun flag(): Int { require(reply.dataAvail() >= 4); return reply.readByte().toInt().also { require(it in 0..1) } }
            fun string(): String? {
                require(reply.dataAvail() >= 4)
                return reply.readString().also { require(it == null || it.length <= 256) }
            }
            reply.readException()
            val present = integer(); require(present in 0..1)
            if (present == 0) { require(reply.dataAvail() == 0); return null }
            integer() // PI code
            val rds = flag()
            string() // PS name already comes through MediaSession.ARTIST.
            val frequency = integer(); require(frequency in 1..Int.MAX_VALUE)
            val fm = flag()
            repeat(3) { flag() } // favourite, PS decoded, TA PS decoded
            integer() // end-report type
            repeat(4) { flag() } // PTY warning, TA assert, TA, TP
            integer() // TA frequency
            string() // TA PS
            val rt = string()
            integer() // PTY code
            require(reply.dataAvail() == 0)
            return VoyahRadioOem.RadioText(if (fm == 1) VoyahRadioOem.Band.FM else VoyahRadioOem.Band.AM,
                frequency.toLong(), rt.takeIf { rds == 1 })
        }
    }
}
