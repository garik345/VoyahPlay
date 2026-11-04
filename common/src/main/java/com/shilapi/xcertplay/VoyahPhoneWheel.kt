package com.shilapi.xcertplay

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.CarPlayController
import dalvik.system.PathClassLoader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val PHONE_WHEEL_TAG = "VoyahPhoneWheel"

private fun phoneWheelNote(message: String) {
    WheelKeyLog.note("Voyah phone button: $message")
    Log.i(PHONE_WHEEL_TAG, message)
}

/** Process-lifetime adapter. The native binding follows the controller, never an Activity's visibility. */
object VoyahPhoneWheel {
    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { task ->
        Thread(task, "voyah-phone-wheel").apply { isDaemon = true }
    }
    private val lock = Any()
    @Volatile private var runtime: VoyahPhoneWheelRuntime? = null
    @Volatile private var hostOpeningAllowed = false
    private var app: Context? = null
    private var controller: CarPlayController? = null
    private val permissionWatchStarted = AtomicBoolean()
    private val permissionWatcher = object : AppOpsManager.OnOpChangedListener {
        override fun onOpChanged(op: String?, packageName: String?) {
            if (op != null && op != AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW) return
            val application = synchronized(lock) { app } ?: return
            if (packageName != null && packageName != application.packageName) return
            main.post { refreshHostPermission(application) }
        }
    }

    fun attach(context: Context) {
        val application = context.applicationContext
        // AppOps may use system Binder. Do this before acquiring lifecycle locks, never on a key callback.
        refreshHostPermission(application)
        val attached = synchronized(lock) {
            runtime ?: run {
                app = application
                VoyahPhoneWheelRuntime(
                    post = { main.post(it) }, later = { delay, task -> main.postDelayed(task, delay) },
                    work = { work.execute(it) },
                    backend = VoyahPhoneWheelNativeBackend(application, main, work),
                    calling = { BydNavigationOutputs.carPlayCall() != null },
                    visible = { CarPlayBackgroundSession.projectionVisible },
                    openHost = {
                        // OwnScreenStart needs an Activity. This explicit component preserves the .carplay task.
                        runCatching {
                            application.startActivity(Intent(application, CarPlayHostActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                        }.onFailure { Log.w(PHONE_WHEEL_TAG, "Could not return to CarPlay", it) }
                    },
                    canOpenHost = { hostOpeningAllowed },
                ).also {
                    runtime = it
                    it.setEnabled(VoyahPhoneWheelSettings.enabled(application))
                    it.setSession(controller?.asPhoneWheelSession())
                }
            }
        }
        watchHostPermission(application)
        attached.refresh()
    }

    /** Cached only: settings rendering and native callbacks never hash files or wait for Binder. */
    fun supported(): Boolean = runtime?.supported() == true

    /** Coalesces the process's initial firmware verification; completion is always posted to the main thread. */
    fun refresh(onReady: (() -> Unit)? = null) {
        val current = runtime
        if (current == null) { if (onReady != null) main.post(onReady); return }
        current.refresh(onReady)
    }

    fun setController(controller: CarPlayController?) {
        synchronized(lock) {
            this.controller = controller
            runtime?.setSession(controller?.asPhoneWheelSession())
        }
    }

    /** Called after saving the opt-in, outside the native callback. */
    fun refreshEnabled() {
        val current = synchronized(lock) { app?.let { it to runtime } } ?: return
        refreshHostPermission(current.first)
        current.second?.setEnabled(VoyahPhoneWheelSettings.enabled(current.first))
    }

    private fun refreshHostPermission(application: Context) {
        hostOpeningAllowed = Build.VERSION.SDK_INT < 29 ||
            runCatching { Settings.canDrawOverlays(application) }.getOrDefault(false)
    }

    private fun watchHostPermission(application: Context) {
        if (Build.VERSION.SDK_INT < 29 || !permissionWatchStarted.compareAndSet(false, true)) return
        main.post {
            runCatching {
                val manager = application.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: error("AppOps unavailable")
                manager.startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, application.packageName, permissionWatcher)
                refreshHostPermission(application)
            }.onFailure {
                // Activity resume also refreshes the cache on firmware which refuses an AppOps watcher.
                permissionWatchStarted.set(false)
            }
        }
    }

    private fun CarPlayController.asPhoneWheelSession() = object : VoyahPhoneWheelSession {
        override val identity: Any = this@asPhoneWheelSession
        override fun token(): Any? = activeAirPlaySessionToken()
        override fun open(token: Any, permitted: () -> Boolean, complete: (Boolean) -> Unit): Boolean =
            requestPhoneRecentsForSession(token, permitted, complete)
    }
}

internal interface VoyahPhoneWheelSession {
    val identity: Any
    fun token(): Any?
    fun open(token: Any, permitted: () -> Boolean, complete: (Boolean) -> Unit): Boolean
}

internal interface VoyahPhoneWheelBackend {
    /** Worker thread, fails closed unless the OEM files and decoder are reviewed. */
    fun verify()
    /** Main thread. A connection must disable its callback synchronously when closed. */
    fun bind(onKey: (Int, Int) -> Boolean, lost: () -> Unit): Connection
    fun interface Connection { fun close() }
}

/** A small lease makes delayed bind callbacks, old phones and disabled settings fail open immediately. */
internal class VoyahPhoneWheelRuntime(
    private val post: (() -> Unit) -> Unit,
    private val later: (Long, () -> Unit) -> Unit,
    private val work: (() -> Unit) -> Unit,
    private val backend: VoyahPhoneWheelBackend,
    private val calling: () -> Boolean,
    private val visible: () -> Boolean,
    private val openHost: () -> Unit,
    private val canOpenHost: () -> Boolean = { true },
) {
    private val lock = Any()
    @Volatile private var verified = false
    private var verificationStarted = false
    private var verificationComplete = false
    private val waiters = mutableListOf<() -> Unit>()
    private var enabled = false
    private var session: VoyahPhoneWheelSession? = null
    private var version = 0L
    private var lease: Lease? = null

    private class Lease(val session: VoyahPhoneWheelSession, val version: Long) {
        @Volatile var active = true
        var connection: VoyahPhoneWheelBackend.Connection? = null // main thread
    }

    fun supported(): Boolean = verified

    fun refresh(onReady: (() -> Unit)? = null) {
        val start = synchronized(lock) {
            if (verificationComplete) {
                if (onReady != null) post(onReady)
                return
            }
            if (onReady != null) waiters.add(onReady)
            if (verificationStarted) false else { verificationStarted = true; true }
        }
        if (!start) return
        work {
            val valid = runCatching { backend.verify() }.onFailure {
                Log.w(PHONE_WHEEL_TAG, "Reviewed phone-wheel API unavailable: ${it.javaClass.simpleName}")
            }.isSuccess
            val callbacks = synchronized(lock) {
                verified = valid
                verificationComplete = true
                waiters.toList().also { waiters.clear() }
            }
            post { reconcile(); callbacks.forEach { it() } }
        }
    }

    fun setSession(next: VoyahPhoneWheelSession?) {
        synchronized(lock) {
            if (session?.identity === next?.identity) return
            session = next
            version++
            lease?.active = false
        }
        post(::reconcile)
    }

    fun setEnabled(value: Boolean) {
        synchronized(lock) {
            if (enabled == value) return
            enabled = value
            version++
            lease?.active = false
        }
        post(::reconcile)
    }

    private fun reconcile() {
        val old: Lease?
        val next: Lease?
        synchronized(lock) {
            val current = lease
            if (current != null && current.active && enabled && verified && current.version == version &&
                current.session.identity === session?.identity) return
            old = current
            old?.active = false
            next = session?.takeIf { enabled && verified }?.let { Lease(it, version) }
            lease = next
        }
        // OEM unregister removes by owner name, not callback identity. Close before issuing a new bind;
        // the backend's single worker also orders an in-flight old register before this unregister.
        old?.connection?.close()
        if (next != null) {
            try {
                next.connection = backend.bind({ key, action -> onKey(next, key, action) }, { lost(next) })
                if (!allowed(next)) next.connection?.close()
            } catch (_: RuntimeException) { lost(next) }
        }
    }

    private fun allowed(candidate: Lease): Boolean = synchronized(lock) {
        candidate.active && lease === candidate && enabled && verified && version == candidate.version &&
            session?.identity === candidate.session.identity
    }

    private fun onKey(candidate: Lease, key: Int, action: Int): Boolean {
        if (key != 128 || action != 8192 || !allowed(candidate) || calling()) return false
        if (!visible() && !canOpenHost()) {
            phoneWheelNote("left to the car: background screen permission unavailable")
            return false
        }
        // A controller can acquire a new primary stream without being replaced. Never freeze its token at bind.
        val token = candidate.session.token() ?: return false
        if (!allowed(candidate) || calling()) return false
        return try {
            val queued = candidate.session.open(token, {
                allowed(candidate) && !calling() && candidate.session.token() === token && (visible() || canOpenHost())
            }) { delivered ->
                post {
                    phoneWheelNote("Recents request delivered=$delivered")
                    if (delivered && allowed(candidate) && !calling() && candidate.session.token() === token &&
                        !visible() && canOpenHost()) {
                        phoneWheelNote("returning to CarPlay")
                        openHost()
                    }
                }
            }
            if (queued) phoneWheelNote("Recents request queued")
            queued
        } catch (_: RuntimeException) { false }
    }

    private fun lost(candidate: Lease) {
        candidate.active = false
        post {
            candidate.connection?.close()
            val retryVersion = synchronized(lock) {
                if (lease !== candidate) return@post
                lease = null
                version
            }
            later(5_000) {
                if (synchronized(lock) { version == retryVersion && enabled && verified }) reconcile()
            }
        }
    }
}

/** Only this reviewed QG event ABI is decoded. Android KEYCODE_128 has no relation to QG PHONE=128. */
internal class VoyahPhoneWheelNativeBackend(
    private val app: Context,
    private val main: Handler,
    private val work: Executor,
    private val loadReviewedDecoder: () -> Parcelable.Creator<*> = { VoyahPhoneWheelOem.decoder(app) },
) : VoyahPhoneWheelBackend {
    @Volatile private var decoder: Parcelable.Creator<*>? = null

    override fun verify() { decoder = loadReviewedDecoder() }

    override fun bind(onKey: (Int, Int) -> Boolean, lost: () -> Unit): VoyahPhoneWheelBackend.Connection {
        val creator = checkNotNull(decoder) { "Phone-wheel API has not been verified" }
        return Link(creator, onKey, lost).also { it.bind() }
    }

    private inner class Link(
        private val creator: Parcelable.Creator<*>,
        private val onKey: (Int, Int) -> Boolean,
        private val lost: () -> Unit,
    ) : ServiceConnection, VoyahPhoneWheelBackend.Connection {
        private val alive = AtomicBoolean(true)
        private val lossReported = AtomicBoolean()
        @Volatile private var ready = false
        private var bound = false // main thread
        private var remote: IBinder? = null // single worker
        private var registered = false // single worker
        private val owner = Binder()
        private val timeout = Runnable { if (alive.get() && !ready) fail() }
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(VoyahPhoneWheelOem.CALLBACK); return true }
                if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags)
                val consumed = try {
                    data.enforceInterface(VoyahPhoneWheelOem.CALLBACK)
                    val event = if (data.readInt() == 0) null else creator.createFromParcel(data) as? KeyEvent
                    val addressed = code == 1 || (data.readString() == VoyahPhoneWheelOem.OWNER && data.readInt() == 1)
                    event != null && addressed && alive.get() && ready && onKey(event.keyCode, event.action)
                } catch (_: RuntimeException) { false }
                reply?.writeNoException()
                reply?.writeInt(if (consumed) 1 else 0)
                return true
            }
        }

        fun bind() {
            try {
                bound = app.bindService(Intent().setComponent(ComponentName(VoyahPhoneWheelOem.PACKAGE,
                    "${VoyahPhoneWheelOem.PACKAGE}.KeyManagerService")), this, Context.BIND_AUTO_CREATE)
                if (!bound) { fail(); return }
                main.postDelayed(timeout, 5_000)
            } catch (_: RuntimeException) { fail() }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            work.execute {
                if (!alive.get()) return@execute
                try {
                    check(service.interfaceDescriptor == VoyahPhoneWheelOem.SERVICE) { "Unexpected KeyManager descriptor" }
                    remote = service
                    transaction(3) {
                        it.writeStrongBinder(owner)
                        it.writeStrongBinder(callback)
                        it.writeIntArray(intArrayOf(128))
                        it.writeString(VoyahPhoneWheelOem.OWNER)
                        it.writeInt(2)
                        it.writeInt(50)
                    }
                    registered = true
                    if (alive.get()) {
                        ready = true
                        phoneWheelNote("native key ready")
                    }
                    main.post { main.removeCallbacks(timeout) }
                } catch (_: Exception) { fail() }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = fail()
        override fun onBindingDied(name: ComponentName) = fail()
        override fun onNullBinding(name: ComponentName) = fail()

        private fun transaction(code: Int, write: (Parcel) -> Unit) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VoyahPhoneWheelOem.SERVICE)
                write(data)
                check(remote?.transact(code, data, reply, 0) == true) { "KeyManager transaction refused" }
                reply.readException()
                check(reply.readInt() >= 0) { "KeyManager operation refused" }
            } finally { data.recycle(); reply.recycle() }
        }

        private fun fail() {
            close()
            if (lossReported.compareAndSet(false, true)) main.post(lost)
        }

        override fun close() {
            if (!alive.getAndSet(false)) return
            ready = false
            val unbind = {
                main.removeCallbacks(timeout)
                if (bound) { runCatching { app.unbindService(this) }; bound = false }
            }
            if (Looper.myLooper() == main.looper) unbind() else main.post(unbind)
            work.execute {
                if (registered) {
                    runCatching { transaction(4) { it.writeStrongBinder(callback); it.writeString(VoyahPhoneWheelOem.OWNER) } }
                        .onFailure { Log.w(PHONE_WHEEL_TAG, "KeyManager unregister failed: ${it.javaClass.simpleName}") }
                    registered = false
                    phoneWheelNote("native key released")
                }
                remote = null
            }
        }
    }
}

internal object VoyahPhoneWheelOem {
    const val PACKAGE = "com.qinggan.keymanager.service"
    const val SERVICE = "com.qinggan.system.IKeyManagerService"
    const val CALLBACK = "com.qinggan.system.IKeyManagerCallback"
    const val OWNER = "com.shilapi.xcertplay.phonewheel"
    const val KEY_APK_SHA256 = "c3f7ef1ec78724ed31f70e2fc1204eff8d723d32be7b9b03b310c239c66e96fe"
    const val QGAPI_SHA256 = "e46d3e5db0cf932cd3a39654a2f95620f2668ada8fb32b904c1684f936eca510"

    fun decoder(app: Context): Parcelable.Creator<*> {
        @Suppress("DEPRECATION") val info = app.packageManager.getApplicationInfo(PACKAGE, 0)
        check(info.splitSourceDirs.isNullOrEmpty()) { "Unreviewed split KeyManager" }
        verifyFile(File(info.sourceDir), 16L * 1024 * 1024, KEY_APK_SHA256)
        val sdk = File("/system/framework/QGAPI.jar")
        verifyFile(sdk, 8L * 1024 * 1024, QGAPI_SHA256)
        val loader = PathClassLoader(sdk.path, app.classLoader)
        return Class.forName("com.qinggan.system.QGKeyEvent", true, loader)
            .getField("CREATOR").get(null) as Parcelable.Creator<*>
    }

    internal fun verifyFile(file: File, maxBytes: Long, expectedSha256: String) {
        check(file.isFile && file.length() in 1..maxBytes) { "Invalid OEM file: ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            var total = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                check(total <= maxBytes) { "OEM file changed while reading" }
                digest.update(buffer, 0, count)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(actual == expectedSha256) { "Unreviewed OEM file: ${file.name}" }
    }
}
