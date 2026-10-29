package com.shilapi.xcertplay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Our overlay pauses ScreenActivity, which otherwise advertises MEDIA as unavailable.
 * Restore availability after a decoded frame; the OEM app still answers the driver's
 * presentation requests. No card selection or unsolicited notifyReadyToShow is sent here.
 * State and completion callbacks are owned by the main thread.
 */
internal class VoyahClusterMediaReady(
    private val clock: () -> Long,
    private val current: () -> Boolean,
    private val send: (AtomicBoolean, (Boolean, String) -> Unit) -> Unit,
    private val report: (String) -> Unit,
) {
    private var pending: AtomicBoolean? = null
    private var closed = false
    private var nextAttempt = 0L
    private var failures = 0
    var ready = false
        private set

    fun ensure() {
        if (closed || ready || pending != null || !current() || clock() < nextAttempt) return
        val permit = AtomicBoolean(true)
        pending = permit
        send(permit) completion@{ ok, text ->
            if (pending !== permit) return@completion
            pending = null
            permit.set(false)
            if (closed || !current()) return@completion
            ready = ok
            if (!ok) nextAttempt = clock() + when (++failures) { 1 -> 2000; 2 -> 5000; else -> 15000 }
            report(text)
        }
    }

    fun close() {
        closed = true
        pending?.set(false)
        pending = null
        ready = false
        // Removing our task resumes ScreenActivity. A late MEDIA=0 could overwrite
        // the returning OEM activity's own MEDIA=1, so no clear is sent here.
    }

    companion object {
        fun create(context: Context, current: () -> Boolean, report: (String) -> Unit) =
            VoyahClusterMediaReady(SystemClock::elapsedRealtime, current,
                { permit, done -> VoyahClusterMediaReadyBinder.send(context.applicationContext, permit, done) }, report)
    }
}

/** One bounded connection, using the same reviewed OEM contract as VoyahNavigationOutput. */
private object VoyahClusterMediaReadyBinder {
    private const val PACKAGE = "com.qinggan.cluster"
    private const val API = "$PACKAGE.IInstrumentClusterManagerService"
    private const val MEDIA = "qg.car.cluster.MEDIA"
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "voyah-media-ready").apply { isDaemon = true }
    }

    fun send(context: Context, permit: AtomicBoolean, complete: (Boolean, String) -> Unit) {
        worker.execute {
            var link: Connection? = null
            var ok = false
            var result = "cancelled"
            try {
                if (permit.get()) {
                    verify(context)
                    if (permit.get()) {
                        link = Connection(context, permit)
                        link.connect()
                        if (permit.get()) {
                            link.announce()
                            ok = true
                            result = "MEDIA=1 sent"
                        }
                    }
                }
            } catch (error: Exception) {
                result = (error.cause ?: error).toString()
            } finally {
                link?.close()
            }
            main.post { complete(ok, result) }
        }
    }

    private fun verify(context: Context) = com.shilapi.xcertplay.hud.VoyahClusterCompatibility.verify(context)

    private class Connection(private val context: Context, private val permit: AtomicBoolean) : ServiceConnection {
        private val connected = CountDownLatch(1)
        @Volatile private var binder: IBinder? = null
        @Volatile private var closed = false
        private var bound = false // main thread only

        fun connect() {
            main.post {
                if (closed || !permit.get()) { connected.countDown(); return@post }
                try {
                    bound = context.bindService(Intent("$PACKAGE.InstrumentClusterService")
                        .setComponent(ComponentName(PACKAGE, "$PACKAGE.service.InstrumentClusterService")),
                        this, Context.BIND_AUTO_CREATE)
                    if (!bound) connected.countDown()
                } catch (_: Exception) { connected.countDown() }
            }
            check(connected.await(5, TimeUnit.SECONDS)) { "Cluster bind timeout" }
            check(permit.get() && !closed) { "Map window retired" }
        }

        fun announce() {
            val service = checkNotNull(binder) { "Cluster binder unavailable" }
            check(permit.get() && !closed && service.isBinderAlive) { "Map window retired or binder lost" }
            check(service.interfaceDescriptor == API) { "Wrong cluster descriptor" }
            val loader = context.classLoader
            val contract = Class.forName(API, true, loader)
            val proxy = Class.forName("$API\$Stub", true, loader)
                .getMethod("asInterface", IBinder::class.java).invoke(null, service)
            if (permit.get() && !closed) {
                contract.getMethod("syncReadyState", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(proxy, MEDIA, 1)
            } else throw IllegalStateException("Map window retired")
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            if (!closed && permit.get()) binder = service
            connected.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName) { binder = null; connected.countDown() }
        override fun onBindingDied(name: ComponentName) { binder = null; connected.countDown() }
        override fun onNullBinding(name: ComponentName) { connected.countDown() }

        fun close() {
            closed = true
            main.post { if (bound) { bound = false; runCatching { context.unbindService(this) } } }
        }
    }
}
