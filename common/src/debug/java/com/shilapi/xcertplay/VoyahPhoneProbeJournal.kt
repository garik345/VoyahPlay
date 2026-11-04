package com.shilapi.xcertplay

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Private, request-scoped evidence. Binder callbacks enqueue records; they never wait for disk. */
internal class VoyahPhoneProbeJournal private constructor(
    private val file: File, private val id: String, private val mode: String,
) {
    private val keys = AtomicInteger()
    private fun row(event: String, detail: String): String = JSONObject()
        .put("requestId", id).put("mode", mode).put("event", event)
        .put("elapsedMs", SystemClock.elapsedRealtime()).put("detail", detail.take(512)).toString() + "\n"

    fun event(event: String, detail: String) {
        if (event == "KEY" && keys.getAndIncrement() >= 256) return
        val record = row(event, detail)
        writer.execute {
            try { file.appendText(record, Charsets.UTF_8) }
            catch (error: Exception) { Log.e("VoyahPhoneKeyProbe", "journal failed ${error.javaClass.simpleName}") }
        }
    }

    companion object {
        private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "VoyahPhoneProbeJournal").apply { isDaemon = true } }
        fun valid(id: String, mode: String): Boolean = id.matches(Regex("[a-f0-9]{32}")) &&
            mode in setOf("open-recents", "phone-key-30s", "stop")

        fun create(context: Context, id: String, mode: String): VoyahPhoneProbeJournal {
            require(valid(id, mode)) { "Invalid phone-probe request" }
            val directory = File(context.filesDir, "phone-probe").apply { mkdirs() }
            val file = File(directory, "$id.jsonl")
            check(file.createNewFile()) { "Duplicate phone-probe request" }
            return VoyahPhoneProbeJournal(file, id, mode).also {
                // Acknowledge the ordered broadcast only after ENTRY has been written and closed.
                file.writeText(it.row("ENTRY", com.shilapi.xcertplay.airplay.ultra.VoyahUltraProtocol.WIRE_REV), Charsets.UTF_8)
            }
        }
    }
}
