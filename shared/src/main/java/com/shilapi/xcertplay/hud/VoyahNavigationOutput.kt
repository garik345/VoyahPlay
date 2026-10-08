package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import java.io.File
import java.lang.reflect.Method
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Own route state, independent of the dashboard overlay's disconnect-retention policy. */
object VoyahNavigationOutput {
    private const val TAG = "VoyahNavigation"
    private const val PKG = "com.qinggan.cluster"
    private const val API = "com.qinggan.cluster.IInstrumentClusterManagerService"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "voyah-navigation").apply { isDaemon = true } }
    private val busy = AtomicBoolean()
    private val lock = Any()
    private val route = BydHudRouteState(keepAcrossNoRoute = false)
    private var app: Context? = null
    private var active = false
    private var enabled = true
    private var epoch = 0L
    private var requested = Request(0, null, false)
    // Worker-owned state. A blocked Binder cannot create a backlog of stale instructions.
    private var connection: Connection? = null
    private var applied: Guidance? = null
    private var mayHaveOutput = false
    private var failedEpoch: Long? = null
    private data class Guidance(val icon: Int, val distance: Int, val road: String)
    private data class Request(val epoch: Long, val guidance: Guidance?, val active: Boolean)

    @JvmStatic fun enabled(context: Context): Boolean =
        context.getSharedPreferences("voyah_navigation", Context.MODE_PRIVATE).getBoolean("enabled", true)

    @JvmStatic fun isSessionActive(): Boolean = synchronized(lock) { active }

    @JvmStatic fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("voyah_navigation", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        synchronized(lock) { enabled = value; epoch++; if (!value) route.clear() }
        publish()
    }

    fun start(context: Context) {
        synchronized(lock) {
            app = context.applicationContext
            enabled = enabled(context)
            if (!active) { active = true; epoch++; route.clear() }
        }
        main.post { main.removeCallbacks(tick); tick.run() }
    }

    internal fun onFrame(frame: Iap2Frame) {
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        synchronized(lock) {
            if (active && enabled) route.accept(frame.messageId, frame.payload)
        }
        // Frames are decoded immediately, but vehicle writes are coalesced by the 1 s tick.
    }

    fun end() {
        synchronized(lock) { active = false; epoch++; route.clear() }
        main.post { main.removeCallbacks(tick); publish() }
    }

    private val tick = object : Runnable {
        override fun run() {
            publish()
            if (synchronized(lock) { active }) main.postDelayed(this, 1000)
        }
    }

    private fun publish() {
        synchronized(lock) {
            val next = if (active && enabled) route.currentApple() else null
            requested = Request(epoch, next?.let {
                Guidance(VoyahManeuverCodes.icon(it.type, it.drivingSide),
                    it.distanceMeters.coerceAtLeast(0), it.road.replace('\u0000', ' ').take(120))
            }, active && enabled)
        }
        pump()
    }

    private fun pump() {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            val request = synchronized(lock) { requested }
            try { apply(request) }
            catch (e: Exception) {
                Log.e(TAG, "Output failed; paused until reconnect or setting change", e)
                failedEpoch = request.epoch
                // Even a failed write may have reached the server. Try to clear before releasing.
                runCatching { clearOutput() }.onFailure { Log.e(TAG, "Clear not confirmed", it) }
                disconnect()
            } finally {
                busy.set(false)
                if (synchronized(lock) { requested != request }) pump()
            }
        }
    }

    private fun apply(request: Request) {
        if (failedEpoch == request.epoch) return
        if (request.guidance == null) {
            clearOutput()
            if (!request.active) disconnect()
            return
        }
        if (request.guidance == applied) return
        val context = synchronized(lock) { app } ?: return
        var link = connection
        if (link == null) {
            verify(context)
            if (synchronized(lock) { requested != request }) return
            link = Connection(context)
            connection = link
            link.connect()
        }
        if (synchronized(lock) { requested != request }) return
        mayHaveOutput = true
        link.send(request.guidance)
        applied = request.guidance
        Log.d(TAG, "Sent icon=${request.guidance.icon}, distance=${request.guidance.distance}")
    }

    private fun clearOutput() {
        if (mayHaveOutput) {
            checkNotNull(connection) { "No connection to confirm clearing" }.send(null)
            mayHaveOutput = false
            Log.i(TAG, "MAN_STOP sent; display clearing requires vehicle confirmation")
        }
        applied = null
    }

    private fun disconnect() {
        connection?.close()
        connection = null
        applied = null
    }

    private fun verify(context: Context) {
        val pkg = context.packageManager.getPackageInfo(PKG, 0)
        val info = requireNotNull(pkg.applicationInfo)
        check(info.splitSourceDirs.isNullOrEmpty()) { "Unsupported split cluster APK" }
        checkHash(File(info.sourceDir), "1a394bf793a467dd78b95ca1ed2004df62c280df4b58b043629257889d8e4f38")
        checkHash(File("/system/framework/QGAPI.jar"), "e46d3e5db0cf932cd3a39654a2f95620f2668ada8fb32b904c1684f936eca510")
    }

    private fun checkHash(file: File, expected: String) {
        check(file.isFile && file.length() in 1..(64L * 1024 * 1024)) { "Missing ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32768)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == expected) { "Unsupported ${file.name}" }
    }

    private class Connection(private val context: Context) : ServiceConnection {
        private val ready = CountDownLatch(1)
        @Volatile private var binder: IBinder? = null
        @Volatile private var closed = false
        private var bound = false // main thread only
        private var proxy: Any? = null
        private lateinit var info: Class<*>
        private lateinit var guide: Class<*>
        private lateinit var update: Method
        fun connect() {
            main.post {
                if (closed) { ready.countDown(); return@post }
                try {
                    bound = context.bindService(Intent("com.qinggan.cluster.InstrumentClusterService")
                        .setComponent(ComponentName(PKG, "$PKG.service.InstrumentClusterService")), this, Context.BIND_AUTO_CREATE)
                    if (!bound) ready.countDown()
                } catch (e: Exception) { Log.e(TAG, "Bind failed", e); ready.countDown() }
            }
            check(ready.await(5, TimeUnit.SECONDS)) { "Cluster bind timeout" }
            val service = checkNotNull(binder) { "Cluster binder unavailable" }
            check(service.interfaceDescriptor == API) { "Wrong cluster descriptor" }
            val loader = context.classLoader
            val contract = Class.forName(API, true, loader)
            proxy = Class.forName("$API\$Stub", true, loader).getMethod("asInterface", IBinder::class.java).invoke(null, service)
            info = Class.forName("com.qinggan.cluster.info.NaviInfo", true, loader)
            guide = Class.forName("com.qinggan.cluster.info.GuideState", true, loader)
            update = contract.getMethod("updateNaviInfo", info)
        }
        fun send(value: Guidance?) {
            check(!closed && binder?.isBinderAlive == true) { "Cluster connection lost" }
            val data = info.getConstructor().newInstance()
            info.getMethod("setGuideState", guide).invoke(data, guide.getField(if (value == null) "MAN_STOP" else "GUIDING").get(null))
            info.getMethod("setCurrentRoadName", String::class.java).invoke(data, "")
            info.getMethod("setTurnRoadName", String::class.java).invoke(data, value?.road.orEmpty())
            info.getMethod("setDestName", String::class.java).invoke(data, "")
            if (value != null) {
                info.getMethod("setTbtIconId", Int::class.javaPrimitiveType).invoke(data, value.icon)
                info.getMethod("setNextDistance", Int::class.javaPrimitiveType).invoke(data, value.distance)
                info.getMethod("setDisplay", Boolean::class.javaPrimitiveType).invoke(data, true)
            }
            update.invoke(proxy, data)
        }
        override fun onServiceConnected(name: ComponentName, service: IBinder) { if (!closed) binder = service; ready.countDown() }
        override fun onNullBinding(name: ComponentName) { ready.countDown() }
        override fun onServiceDisconnected(name: ComponentName) { binder = null }
        override fun onBindingDied(name: ComponentName) { binder = null; ready.countDown() }
        fun close() {
            closed = true
            main.post { if (bound) { bound = false; runCatching { context.unbindService(this) } } }
        }
    }
}
