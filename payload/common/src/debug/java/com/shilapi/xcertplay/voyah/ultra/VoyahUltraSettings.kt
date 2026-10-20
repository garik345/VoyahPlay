package com.shilapi.xcertplay.voyah.ultra

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.shilapi.xcertplay.DiagnosticExportStore
import com.shilapi.xcertplay.airplay.AirPlayProtocolExtension
import com.shilapi.xcertplay.airplay.ultra.*
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.voyah.VoyahClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Debug source set only: OEM writes require the existing WRITE_CANBUS grant; no root helper changes. */
object VoyahUltraSettings {
    const val available = true
    private const val PREFS = "voyah_ultra_pilot"
    private val reportLock = Any()
    private val io = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, ArrayBlockingQueue<Runnable>(256),
        { task -> Thread(task, "voyah-ultra-report").apply { isDaemon = true } })
    private var dropped = java.util.concurrent.atomic.AtomicLong()

    fun isEnabled(context: Context): Boolean = context.getSharedPreferences(PREFS, 0).getBoolean("enabled", false)
    fun saveEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, 0).edit().putBoolean("enabled", enabled).apply()
        record(context, "SETTING enabled=$enabled; applies on next receiver start")
    }

    fun createExtension(context: Context): AirPlayProtocolExtension? {
        if (!isEnabled(context)) return null
        val app = context.applicationContext
        record(app, "RECEIVER CONFIG pilot=1 version=ultra-climate-0.1.24 WIRE_REV=opack-5-hvac")
        return VoyahUltraProtocol({ ClimateSource(app) }, SystemClock::elapsedRealtime) { record(app, it) }
    }

    private fun record(context: Context, text: String) {
        val line = "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS Z", Locale.ROOT).format(Date())} ${text.take(3000)}\n"
        Log.i("VoyahUltra", text.take(3000))
        try {
            io.execute {
                synchronized(reportLock) {
                    runCatching {
                        val directory = File(context.filesDir, "ultra-pilot").apply { mkdirs() }
                        val file = File(directory, "capture.txt")
                        if (file.length() > 2 * 1024 * 1024) {
                            val previous = File(directory, "capture.previous.txt")
                            previous.delete()
                            check(file.renameTo(previous)) { "Cannot rotate Ultra journal" }
                        }
                        file.appendText(line, Charsets.UTF_8)
                    }.onFailure { Log.w("VoyahUltra", "Journal write failed", it) }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) { dropped.incrementAndGet() }
    }

    private fun report(context: Context): String {
        // Called from a worker, so waiting for the bounded journal queue never blocks the UI.
        runCatching { io.submit {}.get(5, TimeUnit.SECONDS) }
        return synchronized(reportLock) {
            val dir = File(context.filesDir, "ultra-pilot")
            buildString {
                appendLine("VoyahPlay Ultra climate pilot 0.1.24")
                appendLine("DEBUG_ONLY=1 WIRE_REV=opack-5-hvac enabled=${isEnabled(context)} dropped=${dropped.get()}")
                appendLine("H97C HVAC: driver/passenger target, power, AC, fan, AUTO, recirculation, front/rear defrost; comfort controls.")
                appendLine("CAF transport and climate metadata checked against f-io/LIVI 3fb5210f; OEM commands checked against the supplied CAN APK SHA256 96ac5182.")
                appendLine("Climate UI is not confirmed. Writes are accepted only from explicit iPhone requests and require OEM readback.")
                for (name in listOf("capture.previous.txt", "capture.txt")) {
                    val file = File(dir, name)
                    if (file.exists()) appendLine(file.readText(Charsets.UTF_8))
                }
            }
        }
    }

    fun showStatus(activity: Activity) {
        background(activity) { report(activity.applicationContext).takeLast(45_000) }
    }

    fun exportReport(activity: Activity) {
        val context = activity.applicationContext
        background(activity) {
            val text = report(context)
            val name = "voyahplay-ultra-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())}.txt"
            val saved = DiagnosticExportStore.saveWithoutPicker(context, name, text)
            saved.savedPath ?: saved.uri.toString()
        }
    }

    fun probeClimate(activity: Activity) {
        val client = VoyahClient(activity.applicationContext)
        record(activity, "CAN PROBE requested")
        client.read { values, error ->
            val target = values?.left
            val result = if (error == null && target != null) "driverTargetC=$target sampledAt=${values.receivedAt}"
                else "CAN ERROR ${error ?: "airLeftTemperature unavailable"}"
            record(activity, result + " hvac=" + values?.climateValues())
            if (!activity.isFinishing && !activity.isDestroyed) dialog(activity, result)
        }
    }

    private fun background(activity: Activity, action: () -> String) {
        android.widget.Toast.makeText(activity, R.string.voyah_ultra_working, android.widget.Toast.LENGTH_SHORT).show()
        Thread({
            val result = runCatching(action).getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
            activity.runOnUiThread { if (!activity.isFinishing && !activity.isDestroyed) dialog(activity, result) }
        }, "voyah-ultra-export").apply { isDaemon = true; start() }
    }

    private fun dialog(activity: Activity, text: String) {
        val view = android.widget.TextView(activity).apply {
            this.text = text; setTextIsSelectable(true)
            val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        }
        AlertDialog.Builder(activity).setTitle(R.string.voyah_ultra_title)
            .setView(android.widget.ScrollView(activity).apply { addView(view) })
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private class ClimateSource(context: Context) : UltraClimateSource {
        private val handler = Handler(Looper.getMainLooper())
        private val client = VoyahClient(context)
        private val app = context.applicationContext
        private val lock = Any()
        private val writeBusy = java.util.concurrent.atomic.AtomicBoolean()
        @Volatile private var stopped = false
        @Volatile private var latest: UltraClimateSample? = null
        @Volatile private var pending: ComfortRequest? = null
        private var callback: ((UltraClimateSample?) -> Unit)? = null
        private fun permitted() = app.checkSelfPermission("com.qinggan.permission.WRITE_CANBUS") == android.content.pm.PackageManager.PERMISSION_GRANTED
        override val supportedControls = if (permitted()) UltraClimateControl.entries.toSet() else emptySet()
        override val supportedHvac = if (permitted()) UltraHvacControl.entries.toSet() else emptySet()
        private val poll = object : Runnable {
            override fun run() {
                if (stopped) return
                emit()
                val cached = VoyahClient.latestSnapshot()
                if (cached != null && SystemClock.elapsedRealtime() - cached.receivedAt in 0..2_500) update(cached)
                else client.read { values, error ->
                    if (!stopped) {
                        if (error == null && values != null) update(values)
                        else record(app, "CAN ERROR $error")
                    }
                }
                handler.postDelayed(this, 2_000)
            }
        }
        private fun update(values: com.shilapi.xcertplay.voyah.VoyahValues) {
            val comfort = values.comfortValues()
            val levels = linkedMapOf<UltraClimateControl, Int>()
            for (control in supportedControls) {
                val level = if (control.isWheel) comfort?.wheelHeatingLevel()
                    else comfort?.seatLevel(if (control.isPassenger) 1 else 0, control.isVent)
                if (level != null) levels[control] = level
            }
            val hvac = com.shilapi.xcertplay.voyah.VoyahHvacSchema.decode(values.climateValues())
            synchronized(lock) {
                val old = latest
                val useOldHvac = old != null && old.hvacSampledAtMillis > values.receivedAt
                val target = if (useOldHvac) old!!.hvacValues else hvac
                val stamp = if (useOldHvac) old!!.hvacSampledAtMillis else values.receivedAt
                latest = UltraClimateSample((target[UltraHvacControl.DRIVER_TARGET] as? Number)?.toDouble() ?: Double.NaN, stamp,
                    if (old != null && old.comfortSampledAtMillis > values.receivedAt) old.comfortLevels else levels,
                    maxOf(old?.comfortSampledAtMillis ?: 0, values.receivedAt), target, stamp)
            }
            emit()
        }
        override fun start(onSample: (UltraClimateSample?) -> Unit) {
            handler.post { if (!stopped && callback == null) { callback = onSample; handler.post(poll) } }
        }
        private fun emit() { if (!stopped) callback?.invoke(latest) }
        override fun writeHvac(control: UltraHvacControl, value: Any): UltraClimateWriteResult {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (stopped || !permitted() || control !in supportedHvac || !writeBusy.compareAndSet(false, true))
                return UltraClimateWriteResult(false, "Climate closed, busy or permission denied")
            try {
                val result = com.shilapi.xcertplay.voyah.VoyahHvac.write(app, control, value) { !stopped }
                if (!stopped) synchronized(lock) {
                    val old = latest ?: UltraClimateSample(Double.NaN, 0)
                    val hvac = result.values ?: (old.hvacValues + (control to null))
                    val stamp = if (result.values != null) result.sampledAt else old.hvacSampledAtMillis
                    latest = old.copy(driverTargetCelsius = (hvac[UltraHvacControl.DRIVER_TARGET] as? Number)?.toDouble() ?: Double.NaN,
                        sampledAtMillis = stamp, hvacValues = hvac, hvacSampledAtMillis = stamp)
                }
                handler.post { emit() }
                return result.status
            } finally { writeBusy.set(false) }
        }
        private class ComfortRequest {
            val ready = java.util.concurrent.CountDownLatch(1)
            @Volatile var ticket: com.shilapi.xcertplay.voyah.VoyahControls.Ticket? = null
            @Volatile var result = UltraClimateWriteResult(false, "Comfort request unavailable")
        }
        override fun write(control: UltraClimateControl, level: Int): UltraClimateWriteResult {
            check(Looper.myLooper() != Looper.getMainLooper())
            if (stopped || !permitted() || control !in supportedControls || level !in 0..control.maxLevel || !writeBusy.compareAndSet(false, true))
                return UltraClimateWriteResult(false, "Comfort closed, busy, invalid or permission denied")
            val request = ComfortRequest(); pending = request
            try {
                handler.post {
                    if (stopped || pending !== request) { request.ready.countDown(); return@post }
                    request.ticket = com.shilapi.xcertplay.voyah.VoyahControls.request(app,
                        if (control.isWheel) "wheel-set" else "seat-level", if (control.isPassenger) 1 else 0,
                        if (control.isVent) 1 else 0, if (control.isWheel) level + 1 else level) { states, message ->
                        if (!stopped && pending === request) {
                            val levels = linkedMapOf<UltraClimateControl, Int>()
                            for (c in supportedControls) {
                                val state = if (c.isWheel) states["WHEEL"] else states[(if (c.isVent) "VENT" else "HEAT") + if (c.isPassenger) "_R" else "_L"]
                                val actual = if (c.isWheel) when (state) { 1 -> 0; 2 -> 1; else -> null }
                                    else com.shilapi.xcertplay.voyah.VoyahComfortValues.effectiveLevel(state ?: -1,
                                        states[(if (c.isVent) "VENT_LEVEL" else "HEAT_LEVEL") + if (c.isPassenger) "_R" else "_L"] ?: -1)
                                if (actual != null) levels[c] = actual
                            }
                            synchronized(lock) {
                                latest = (latest ?: UltraClimateSample(Double.NaN, 0)).copy(comfortLevels = levels,
                                    comfortSampledAtMillis = SystemClock.elapsedRealtime())
                            }
                            emit()
                            request.result = UltraClimateWriteResult(levels[control] == level, "$message readback=${levels[control]}")
                        }
                        request.ready.countDown()
                    }
                }
                return if (request.ready.await(7, TimeUnit.SECONDS)) request.result
                    else UltraClimateWriteResult(false, "Comfort timed out; state unknown")
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return UltraClimateWriteResult(false, "Comfort interrupted; state unknown")
            } finally {
                if (pending === request) pending = null
                handler.post { request.ticket?.cancel() }
                writeBusy.set(false)
            }
        }
        override fun close() {
            stopped = true
            val request = pending; pending = null; request?.ready?.countDown()
            handler.post { handler.removeCallbacksAndMessages(null); request?.ticket?.cancel(); client.cancel(); callback = null; latest = null }
        }
    }
}
