package com.shilapi.xcertplay.voyah

import android.content.*
import android.os.*
import android.util.Log
import com.shilapi.xcertplay.transport.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Read-only CAN snapshots; iAP2 never waits for OEM Binder or APK verification. */
class VoyahCanSpeedSource(context: Context, private val report: (String) -> Unit = {}) : VehicleSpeedSource {
    companion object {
        private const val PKG = "com.qinggan.canbus.service"
        private const val API = "com.qinggan.canbus.ICanBusService"
        private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "voyah-can-speed").apply { isDaemon = true } }
        private val busy = AtomicBoolean()
        @Volatile private var verifiedStamp: String? = null
    }
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val buffer = VoyahVehicleSpeedSource(SystemClock::elapsedRealtime)
    private var generation = 0
    private var active = false
    private var connecting = false
    private var retryAt = 0L
    private var lastReport = -5000L
    private var connection: Link? = null
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            if (connection == null) connect()
            val link = connection
            val binder = link?.binder
            val token = generation
            if (binder != null && busy.compareAndSet(false, true)) io.execute {
                val sampled = SystemClock.elapsedRealtime()
                val result = runCatching {
                    check(binder.interfaceDescriptor == API)
                    val speed = read(binder, 26) { reply ->
                        check(reply.dataAvail() == 4); reply.readInt()
                    }
                    val gear = read(binder, 6) { reply ->
                        check(reply.dataAvail() == 12 && reply.readInt() == 1)
                        reply.readInt() // GearState stable ID; the next field is the ordinal.
                        reply.readInt()
                    }
                    speed to gear
                }
                main.post {
                    if (active && token == generation && connection === link) {
                        result.onSuccess { (speed, gear) ->
                            buffer.update(speed, gear, sampled)
                            if (SystemClock.elapsedRealtime() - lastReport >= 5000) {
                                lastReport = SystemClock.elapsedRealtime()
                                runCatching { report("Voyah CAN: speedKmh=$speed gear=$gear sampleElapsed=$sampled") }
                            }
                        }
                            .onFailure {
                                buffer.reset()
                                Log.w("VoyahCanSpeed", "CAN speed/gear unavailable", it)
                                runCatching { report("Voyah CAN: speed/gear unavailable: ${it.javaClass.simpleName}") }
                                reconnect()
                            }
                    }
                }
                busy.set(false)
            }
            main.postDelayed(this, 1000)
        }
    }
    override fun start() { main.post {
        if (!active) { active = true; generation++; retryAt = 0L; buffer.start(); connect(); poll.run() }
    } }
    override fun stop() { main.post {
        active = false; connecting = false; generation++; main.removeCallbacksAndMessages(null)
        disconnect(); buffer.stop()
    } }
    override fun drain(): VehicleSpeedReading? = buffer.drain()

    private fun connect() {
        if (!active || connecting || connection != null || SystemClock.elapsedRealtime() < retryAt || !busy.compareAndSet(false, true)) return
        val token = generation
        connecting = true
        io.execute {
            val verified = runCatching {
                val info = app.packageManager.getApplicationInfo(PKG, 0)
                check(info.splitSourceDirs.isNullOrEmpty())
                val file = File(info.sourceDir)
                check(file.isFile && file.length() in 1..(128L * 1024 * 1024))
                val stamp = "${file.absolutePath}:${file.length()}:${file.lastModified()}"
                if (verifiedStamp != stamp) {
                    val digest = MessageDigest.getInstance("SHA-256")
                    file.inputStream().use { input ->
                        val bytes = ByteArray(65536)
                        while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
                    }
                    check(VoyahOemBuilds.supportsCanApk(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }))
                    verifiedStamp = stamp
                }
            }
            main.post {
                if (active && generation == token) {
                    connecting = false
                    if (verified.isSuccess) {
                        val link = Link(); connection = link
                        link.bound = runCatching { app.bindService(Intent().setComponent(
                            ComponentName(PKG, "$PKG.CanBusService")), link, Context.BIND_AUTO_CREATE) }.getOrDefault(false)
                        if (!link.bound) reconnect()
                        else main.postDelayed({ if (connection === link && link.binder == null) reconnect() }, 5000)
                    } else {
                        Log.w("VoyahCanSpeed", "CAN APK verification failed", verified.exceptionOrNull())
                        runCatching { report("Voyah CAN: APK verification failed; speed/gear disabled") }
                        retryAt = SystemClock.elapsedRealtime() + 5000
                        main.postDelayed(::connect, 5000)
                    }
                }
            }
            busy.set(false)
        }
    }
    private fun reconnect() {
        disconnect(); buffer.reset(); retryAt = SystemClock.elapsedRealtime() + 5000
        if (active) main.postDelayed(::connect, 5000)
    }
    private fun disconnect() {
        val link = connection; connection = null
        if (link?.bound == true) runCatching { app.unbindService(link) }
    }
    private inner class Link : ServiceConnection {
        var bound = false
        var binder: IBinder? = null
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (connection === this) binder = service
        }
        override fun onServiceDisconnected(name: ComponentName) { if (connection === this) reconnect() }
        override fun onBindingDied(name: ComponentName) { if (connection === this) reconnect() }
        override fun onNullBinding(name: ComponentName) { if (connection === this) reconnect() }
    }
    private fun <T> read(binder: IBinder, code: Int, decode: (Parcel) -> T): T {
        val data = Parcel.obtain(); val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(API)
            check(binder.transact(code, data, reply, 0))
            reply.readException()
            return decode(reply)
        } finally { data.recycle(); reply.recycle() }
    }
}
