package com.shilapi.xcertplay

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Parcelable
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.shilapi.xcertplay.hud.BydNavigationOutputs
import com.shilapi.xcertplay.orchestration.CarPlayController
import dalvik.system.PathClassLoader
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors

/** ADB-only background command: never creates, resumes or changes an Android task. */
class VoyahPhoneKeyProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("request_id").orEmpty()
        val mode = intent.getStringExtra("mode").orEmpty()
        if (!VoyahPhoneProbeJournal.valid(id, mode)) {
            resultCode = 400; resultData = "VP_PHONE_PROBE:invalid:rejected"; return
        }
        try {
            val journal = VoyahPhoneProbeJournal.create(context, id, mode)
            val accepted = when (mode) {
                "open-recents" -> VoyahPhoneKeyProbe.open(journal)
                "phone-key-30s" -> VoyahPhoneKeyProbe.start(context.applicationContext, journal)
                else -> { VoyahPhoneKeyProbe.stop(); journal.event("STOPPED", "gate-disabled-unregister-queued"); true }
            }
            resultCode = if (accepted) 200 else 409
            resultData = "VP_PHONE_PROBE:$id:${if (accepted) "accepted" else "rejected"}"
        } catch (error: Exception) {
            Log.e("VoyahPhoneKeyProbe", "receiver failed ${error.javaClass.simpleName}")
            resultCode = 500; resultData = "VP_PHONE_PROBE:$id:failed"
        }
    }
}

internal object VoyahPhoneKeyProbe {
    private const val TAG = "VoyahPhoneKeyProbe"
    private const val PACKAGE = "com.qinggan.keymanager.service"
    private const val SERVICE = "com.qinggan.system.IKeyManagerService"
    private const val CALLBACK = "com.qinggan.system.IKeyManagerCallback"
    private const val OWNER = "com.shilapi.xcertplay.wheelprobe"
    private val main = Handler(Looper.getMainLooper())
    // Keep unregister/register ordered when the owner restarts the test.
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, TAG).apply { isDaemon = true } }
    private var current: Link? = null // main thread

    fun open(journal: VoyahPhoneProbeJournal): Boolean {
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        val token = controller?.activeAirPlaySessionToken()
        if (controller == null || token == null || BydNavigationOutputs.carPlayCall() != null) {
            journal.event("ERROR", "no-idle-carplay-session"); return false
        }
        journal.event("QUEUED", "main-screen-recents-ui-only")
        return queueRecents(controller, token, journal)
    }

    private fun queueRecents(controller: CarPlayController, token: Any, journal: VoyahPhoneProbeJournal): Boolean {
        controller.observePhoneUiRequestsForSession(token) { journal.event("IPHONE_REQUEST_UI", it) }
        return controller.requestPhoneRecentsForSession(token) { journal.event("RECENTS_DELIVERED", it.toString()) }
    }

    fun start(app: Context, journal: VoyahPhoneProbeJournal): Boolean {
        stop()
        val controller = CarPlayBackgroundSession.snapshot()?.controller
        val token = controller?.activeAirPlaySessionToken()
        if (controller == null || token == null || BydNavigationOutputs.carPlayCall() != null) {
            journal.event("ERROR", "no-idle-carplay-session"); return false
        }
        val link = Link(app, controller, token, journal)
        current = link
        journal.event("QUEUED", "verify-keymanager-and-bind")
        work.execute {
            try {
                @Suppress("DEPRECATION") val info = app.packageManager.getApplicationInfo(PACKAGE, 0)
                check(info.splitSourceDirs.isNullOrEmpty()) { "Unreviewed split KeyManager" }
                verify(File(info.sourceDir), 16L * 1024 * 1024, "c3f7ef1ec78724ed31f70e2fc1204eff8d723d32be7b9b03b310c239c66e96fe")
                val sdk = File("/system/framework/QGAPI.jar")
                verify(sdk, 8L * 1024 * 1024, "e46d3e5db0cf932cd3a39654a2f95620f2668ada8fb32b904c1684f936eca510")
                val loader = PathClassLoader(sdk.path, app.classLoader)
                link.creator = Class.forName("com.qinggan.system.QGKeyEvent", true, loader)
                    .getField("CREATOR").get(null) as Parcelable.Creator<*>
                main.post {
                    if (current !== link || !link.active) return@post
                    try {
                        link.bound = app.bindService(Intent().setComponent(ComponentName(PACKAGE, "$PACKAGE.KeyManagerService")), link, Context.BIND_AUTO_CREATE)
                        check(link.bound) { "KeyManager bind rejected" }
                        main.postDelayed(link.timeout, 30_000)
                    } catch (e: Exception) { link.fail(e) }
                }
            } catch (e: Exception) { link.fail(e) }
        }
        return true
    }

    fun stop() { current?.close(); current = null }

    private fun verify(file: File, max: Long, expected: String) {
        check(file.isFile && file.length() in 1..max) { "Invalid OEM file: ${file.name}" }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65536)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected) { "Unreviewed OEM file: ${file.name}" }
    }

    private class Link(val app: Context, val controller: CarPlayController, val token: Any,
        val journal: VoyahPhoneProbeJournal) : ServiceConnection {
        @Volatile var active = true
        @Volatile var creator: Parcelable.Creator<*>? = null
        @Volatile private var gate: VoyahPhoneKeyProbeGate? = null
        var bound = false // main thread
        private var remote: IBinder? = null // worker thread
        private var registered = false
        val timeout = Runnable { close() }
        private val owner = Binder()
        private fun currentToken(): Any? = CarPlayBackgroundSession.snapshot()
            ?.takeIf { it.controller === controller }?.controller?.activeAirPlaySessionToken()
        private val callback = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (code == IBinder.INTERFACE_TRANSACTION) { reply?.writeString(CALLBACK); return true }
                if (code != 1 && code != 2) return super.onTransact(code, data, reply, flags)
                data.enforceInterface(CALLBACK)
                val event = if (data.readInt() == 0) null else creator?.createFromParcel(data) as? KeyEvent
                val addressed = code == 1 || (data.readString() == OWNER && data.readInt() == 1)
                var consumed = false
                if (event != null && addressed && active) {
                    journal.event("KEY", "code=${event.keyCode} action=${event.action}")
                    consumed = gate?.onKey(event.keyCode, event.action, SystemClock.elapsedRealtime(),
                        currentToken(), BydNavigationOutputs.carPlayCall() != null) {
                        controller.observePhoneUiRequestsForSession(token) { journal.event("IPHONE_REQUEST_UI", it) }
                        controller.requestPhoneRecentsForSession(token) { sent ->
                            journal.event("RECENTS_DELIVERED", sent.toString())
                            if (!sent) main.post { close() }
                        }
                    } == true
                }
                reply?.writeNoException(); reply?.writeInt(if (consumed) 1 else 0)
                return true
            }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            work.execute {
                if (!active || currentToken() !== token) { main.post { close() }; return@execute }
                try {
                    remote = binder
                    gate = VoyahPhoneKeyProbeGate(token, SystemClock.elapsedRealtime())
                    transaction(3) { parcel ->
                        parcel.writeStrongBinder(owner); parcel.writeStrongBinder(callback)
                        // Observe the translated code too if this wheel emits a different phone key.
                        // The gate still consumes only 128/SHORT; all other events return false.
                        parcel.writeIntArray(intArrayOf()); parcel.writeString(OWNER)
                        parcel.writeInt(2); parcel.writeInt(50)
                    }
                    registered = true
                    if (active) journal.event("READY", "duration=30s key=128 short=8192 other-events-pass-through")
                } catch (e: Exception) { fail(e) }
            }
        }
        override fun onServiceDisconnected(name: ComponentName) { close() }
        override fun onBindingDied(name: ComponentName) { close() }
        override fun onNullBinding(name: ComponentName) { close() }

        private fun transaction(code: Int, write: (Parcel) -> Unit) {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(SERVICE); write(data)
                check(remote?.transact(code, data, reply, 0) == true) { "KeyManager transaction rejected" }
                reply.readException(); check(reply.readInt() >= 0) { "KeyManager operation rejected" }
            } finally { reply.recycle(); data.recycle() }
        }

        fun fail(error: Exception) { journal.event("ERROR", "${error.javaClass.simpleName}: ${error.message}"); main.post { close() } }

        fun close() {
            if (!active) return
            active = false; gate?.enabled = false
            main.removeCallbacks(timeout)
            if (current === this) current = null
            if (bound) { runCatching { app.unbindService(this) }; bound = false }
            work.execute {
                if (registered) {
                    try { transaction(4) { it.writeStrongBinder(callback); it.writeString(OWNER) } }
                    catch (e: Exception) { journal.event("UNREGISTER_FAILED", e.javaClass.simpleName) }
                    registered = false
                }
                remote = null
                journal.event("STOPPED", "native-phone-key-restored")
            }
        }
    }
}
