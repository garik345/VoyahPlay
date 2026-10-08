package com.shilapi.xcertplay.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.SystemClock
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

/** Bounded song announcements through the verified PhoneInfo display path. Opt-in. */
object VoyahTrackOutput {
    private const val TAG = "VoyahTrack"
    private const val PKG = "com.qinggan.cluster"
    private const val API = "com.qinggan.cluster.IInstrumentClusterManagerService"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "voyah-track").apply { isDaemon = true } }
    private val busy = AtomicBoolean()
    private val lock = Any()
    private val songs = ClusterSongState()
    private val calls = CarPlayCallState()
    private var app: Context? = null
    private var active = false
    private var enabled = false
    private var epoch = 0L
    private var lastTrack: String? = null
    private var notice: String? = null
    private var since = 0L
    private var width = 10
    private var stepMs = 650L
    private var test = false
    private var requested = Request(0, null, false)
    private data class Request(val epoch: Long, val text: String?, val call: Boolean)
    // Only the worker owns Binder state. Pending frames are coalesced, never queued.
    private var connection: Connection? = null
    private var applied: String? = null
    private var owned = false
    private var failedEpoch: Long? = null

    @JvmStatic fun isBusy(): Boolean = busy.get() || synchronized(lock) { notice != null }

    @JvmStatic fun visibleCharacters(context: Context): Int =
        context.getSharedPreferences("voyah_track", Context.MODE_PRIVATE).getInt("width", 10).coerceIn(6, 30)
    @JvmStatic fun scrollInterval(context: Context): Int =
        context.getSharedPreferences("voyah_track", Context.MODE_PRIVATE).getInt("step_ms", 650).coerceIn(200, 1500)
    private fun loadScroll(context: Context) {
        width = visibleCharacters(context); stepMs = scrollInterval(context).toLong()
    }
    @JvmStatic fun setScroll(context: Context, characters: Int, interval: Int) {
        context.getSharedPreferences("voyah_track", Context.MODE_PRIVATE).edit()
            .putInt("width", characters.coerceIn(6, 30)).putInt("step_ms", interval.coerceIn(200, 1500)).apply()
        synchronized(lock) {
            loadScroll(context)
            if (notice != null) since = SystemClock.elapsedRealtime()
        }
        wake()
    }

    @JvmStatic fun enabled(context: Context): Boolean =
        context.getSharedPreferences("voyah_track", Context.MODE_PRIVATE).getBoolean("enabled", false)

    @JvmStatic fun setEnabled(context: Context, value: Boolean) {
        context.getSharedPreferences("voyah_track", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
        synchronized(lock) {
            loadScroll(context)
            app = context.applicationContext
            enabled = value; epoch++; notice = null; test = false; lastTrack = null
            if (value && active) updateSong()
        }
        wake()
    }

    fun start(context: Context) {
        synchronized(lock) {
            loadScroll(context)
            app = context.applicationContext; enabled = enabled(context)
            if (!active) { active = true; epoch++; songs.clear(); calls.clear(); lastTrack = null; notice = null; test = false }
        }
        wake()
    }

    internal fun onFrame(frame: Iap2Frame) {
        synchronized(lock) {
            if (!active) return
            if (frame.messageId == CarPlayCallState.CALL_STATE_UPDATE) {
                calls.accept(frame)
                if (calls.current() != null) { notice = null; test = false }
            } else if (frame.messageId == ClusterSongState.NOW_PLAYING_UPDATE) {
                songs.accept(frame)
                if (!test) updateSong()
            } else return
        }
        wake()
    }

    private fun updateSong() {
        val song = songs.current()
        if (song == null || !song.playing) { notice = null; return }
        if (enabled && calls.current() == null && song.text != lastTrack) {
            lastTrack = song.text
            notice = song.text
            since = SystemClock.elapsedRealtime()
        }
    }

    /** Manual test shares the same connection, timing, cancellation and scrolling as live music. */
    @JvmStatic fun test(context: Context): Boolean {
        synchronized(lock) {
            if (active || calls.current() != null || busy.get() || notice != null) return false
            loadScroll(context)
            app = context.applicationContext; epoch++; test = true
            notice = "ТЕСТ VOYAHPLAY — Название композиции — Исполнитель"
            since = SystemClock.elapsedRealtime()
        }
        wake()
        return true
    }

    @JvmStatic fun stopTest() {
        synchronized(lock) { if (test) { test = false; notice = null } }
        wake()
    }

    fun end() {
        synchronized(lock) {
            active = false; epoch++; notice = null; test = false; songs.clear(); lastTrack = null
            // Retain observed call priority until the next session; do not clear a real call on disconnect.
        }
        wake()
    }

    private fun wake() { main.post { main.removeCallbacks(tick); tick.run() } }
    private val tick = object : Runnable {
        override fun run() {
            synchronized(lock) {
                val now = SystemClock.elapsedRealtime()
                val line = notice
                val text = if (line != null && (test || active && enabled) && calls.current() == null)
                    VoyahTrackMarquee.frame(line, now - since, width, stepMs) else null
                if (text == null) { notice = null; test = false }
                requested = Request(epoch, text, calls.current() != null)
            }
            pump()
            if (synchronized(lock) { active || test }) main.postDelayed(this, synchronized(lock) { stepMs })
        }
    }

    private fun pump() {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            val request = synchronized(lock) { requested }
            try { apply(request) }
            catch (e: Exception) {
                Log.e(TAG, "Track output failed; retry after reconnect or toggling setting", e)
                failedEpoch = request.epoch
                runCatching { clear() }.onFailure { Log.e(TAG, "Track clear not confirmed", it) }
                disconnect()
            } finally {
                busy.set(false)
                if (synchronized(lock) { requested != request }) pump()
            }
        }
    }

    private fun callActive(): Boolean = synchronized(lock) { calls.current() != null }
    private fun apply(request: Request) {
        if (request.call || callActive()) {
            // PhoneInfo has no ownership token. Never send NOTHING over an observed real call.
            owned = false; applied = null; disconnect(); return
        }
        if (failedEpoch == request.epoch) return
        if (request.text == null) { clear(); disconnect(); return }
        if (request.text == applied) return
        val context = synchronized(lock) { app } ?: return
        var link = connection
        if (link == null) {
            verify(context)
            if (synchronized(lock) { requested != request } || callActive()) return
            link = Connection(context); connection = link; link.connect()
        }
        if (synchronized(lock) { requested != request } || callActive()) return
        owned = true
        link.send(request.text)
        applied = request.text
    }

    private fun clear() {
        if (owned && !callActive()) {
            checkNotNull(connection).send(null)
            Log.i(TAG, "Track clear sent")
        }
        owned = false; applied = null
    }
    private fun disconnect() { connection?.close(); connection = null; applied = null }

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
            info = Class.forName("com.qinggan.cluster.info.PhoneInfo", true, loader)
            guide = Class.forName("com.qinggan.cluster.info.PhoneState", true, loader)
            update = contract.getMethod("updatePhoneInfo", info)
        }
        fun send(value: String?) {
            check(!closed && binder?.isBinderAlive == true) { "Cluster connection lost" }
            val data = info.getConstructor().newInstance()
            info.getMethod("setPhoneState", guide).invoke(data, guide.getField(if (value == null) "NOTHING" else "CONNECTED").get(null))
            info.getMethod("setName", String::class.java).invoke(data, value.orEmpty())
            info.getMethod("setPhoneNum", String::class.java).invoke(data, "")
            info.getMethod("setDuration", Int::class.javaPrimitiveType).invoke(data, 0)
            info.getMethod("setVehicleCall", Int::class.javaPrimitiveType).invoke(data, 0)
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
