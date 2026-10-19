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

/** Debug source set only: no permissions, certificates, root helper or OEM writes are changed. */
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
        record(app, "RECEIVER CONFIG pilot=1 version=ultra-climate-0.1.23 READ_ONLY=1")
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
                appendLine("VoyahPlay Ultra climate pilot 0.1.23")
                appendLine("DEBUG_ONLY=1 READ_ONLY=1 enabled=${isEnabled(context)} dropped=${dropped.get()}")
                appendLine("Driver value is the OEM climate set point in Celsius; cabin sensor is not advertised.")
                appendLine("Protocol/schema from xcertplay iAP2-rewrite e021581f1cdcebc495e5a81aae1bef5154848657.")
                appendLine("iPhone acceptance and climate UI are not yet confirmed. No vehicle commands are sent.")
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
            record(activity, result)
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
        @Volatile private var stopped = false
        private var latest: UltraClimateSample? = null
        private var callback: ((UltraClimateSample?) -> Unit)? = null
        private val poll = object : Runnable {
            override fun run() {
                if (stopped) return
                emit()
                // Reuse a foreground panel/probe read before asking the shared worker for another.
                val cached = VoyahClient.latestSnapshot()
                if (cached != null && SystemClock.elapsedRealtime() - cached.receivedAt in 0..2_500) {
                    latest = cached.left?.let { UltraClimateSample(it.toDouble(), cached.receivedAt) }
                    emit()
                } else {
                    client.read { values, error ->
                        if (stopped) return@read
                        if (error == null) {
                            latest = values?.left?.let { UltraClimateSample(it.toDouble(), values.receivedAt) }
                            emit()
                        } else record(app, "CAN ERROR $error")
                    }
                }
                handler.postDelayed(this, 2_000)
            }
        }
        override fun start(onSample: (UltraClimateSample?) -> Unit) {
            handler.post {
                if (!stopped && callback == null) { callback = onSample; handler.post(poll) }
            }
        }
        private fun emit() {
            val value = latest?.takeIf { SystemClock.elapsedRealtime() - it.sampledAtMillis in 0..6_000 }
            callback?.invoke(value)
        }
        override fun close() {
            stopped = true
            handler.post { handler.removeCallbacksAndMessages(null); client.cancel(); callback = null; latest = null }
        }
    }
}
