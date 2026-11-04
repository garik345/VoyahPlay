package com.shilapi.xcertplay

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** The diagnostic entry point must be observable without bringing an Android task to the front. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahPhoneProbeReceiverTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun nonce(): String = UUID.randomUUID().toString().replace("-", "")
    private fun file(id: String) = File(app.filesDir, "phone-probe/$id.jsonl")
    private fun rows(id: String): List<JSONObject> = file(id).readLines(Charsets.UTF_8)
        .filter { it.isNotBlank() }.map(::JSONObject)
    private fun writer(): ExecutorService = VoyahPhoneProbeJournal::class.java
        .getDeclaredField("writer").apply { isAccessible = true }.get(null) as ExecutorService
    private fun flush() { writer().submit {}.get(5, TimeUnit.SECONDS) }

    @Before fun noExistingProjectionOrProbe() {
        VoyahPhoneKeyProbe.stop()
        CarPlayBackgroundSession.clear()
        shadowOf(Looper.getMainLooper()).idle()
        while (shadowOf(app).nextStartedActivity != null) { /* Drain any setup starts. */ }
    }

    @After fun finishPendingJournalWrites() { flush() }

    private data class Result(val code: Int, val data: String?)

    /** A real ordered delivery gives the receiver a PendingResult; direct onReceive() does not. */
    @Suppress("DEPRECATION")
    private fun broadcast(id: String, mode: String): Result {
        val action = "com.shilapi.xcertplay.TEST_PHONE_PROBE.${nonce()}"
        val receiver = VoyahPhoneKeyProbeReceiver()
        var completed = false
        var code = -1
        var data: String? = null
        app.registerReceiver(receiver, IntentFilter(action))
        try {
            app.sendOrderedBroadcast(
                Intent(action).setPackage(app.packageName).putExtra("request_id", id).putExtra("mode", mode),
                null,
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        code = resultCode; data = resultData; completed = true
                    }
                },
                Handler(Looper.getMainLooper()), 0, null, null,
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue("The ordered broadcast returned a result", completed)
            flush()
            return Result(code, data)
        } finally { app.unregisterReceiver(receiver) }
    }

    @Test fun entryIsDurableAndAddressedBeforeAnyAsynchronousEvent() {
        val id = nonce()
        VoyahPhoneProbeJournal.create(app, id, "open-recents")
        assertTrue(file(id).isFile)
        val entry = rows(id).single()
        assertEquals(id, entry.getString("requestId"))
        assertEquals("open-recents", entry.getString("mode"))
        assertEquals("ENTRY", entry.getString("event"))
        assertTrue("ENTRY is a complete JSONL record", file(id).readText().endsWith("\n"))
    }

    @Test fun invalidIdsAndModesCannotCreateAnEscapingOrAmbiguousJournal() {
        val id = nonce()
        for (invalid in listOf("../$id", "../../$id", "/$id", "A".repeat(32), id.dropLast(1), id + "0", id + "\n")) {
            assertFalse(VoyahPhoneProbeJournal.valid(invalid, "open-recents"))
            try { VoyahPhoneProbeJournal.create(app, invalid, "open-recents"); fail("Invalid id accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(File(app.filesDir, "$id.jsonl").exists())
        for (mode in listOf("", "open-recents/../../outside", "OPEN_RECENTS", "status")) {
            assertFalse(VoyahPhoneProbeJournal.valid(id, mode))
            try { VoyahPhoneProbeJournal.create(app, id, mode); fail("Invalid mode accepted") }
            catch (_: IllegalArgumentException) { }
        }
        assertFalse(file(id).exists())
    }

    @Test fun duplicateNonceIsRejectedWithoutOverwritingPriorEvidence() {
        val id = nonce()
        VoyahPhoneProbeJournal.create(app, id, "stop")
        val original = file(id).readBytes()
        try { VoyahPhoneProbeJournal.create(app, id, "open-recents"); fail("Duplicate id accepted") }
        catch (_: IllegalStateException) { }
        assertArrayEquals(original, file(id).readBytes())
    }

    @Test fun readyIsAppendedInTheBackgroundToTheSameRequestWithoutBlockingTheCaller() {
        val id = nonce()
        val journal = VoyahPhoneProbeJournal.create(app, id, "phone-key-30s")
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        writer().execute { entered.countDown(); release.await(5, TimeUnit.SECONDS) }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            journal.event("READY", "duration=30s")
            assertEquals(listOf("ENTRY"), rows(id).map { it.getString("event") })
        } finally { release.countDown(); flush() }
        assertEquals(listOf("ENTRY", "READY"), rows(id).map { it.getString("event") })
        assertTrue(rows(id).all { it.getString("requestId") == id })
    }

    @Test fun aKeyFloodIsBoundedButCannotDiscardTheStoppedRecord() {
        val id = nonce()
        val journal = VoyahPhoneProbeJournal.create(app, id, "phone-key-30s")
        repeat(400) { journal.event("KEY", "code=128 action=8192 sample=$it") }
        journal.event("STOPPED", "native-phone-key-restored")
        flush()
        val records = rows(id)
        assertEquals(256, records.count { it.getString("event") == "KEY" })
        assertEquals(258, records.size)
        assertEquals("ENTRY", records.first().getString("event"))
        assertEquals("STOPPED", records.last().getString("event"))
        assertTrue(records.all { it.getString("requestId") == id })
    }

    @Test fun stopAcknowledgesItsNonceAndLeavesEveryAndroidTaskUntouched() {
        val id = nonce()
        val result = broadcast(id, "stop")
        assertEquals(200, result.code)
        assertEquals("VP_PHONE_PROBE:$id:accepted", result.data)
        assertEquals(listOf("ENTRY", "STOPPED"), rows(id).map { it.getString("event") })
        assertNull("A background diagnostic must not open VoyahPlay's menu", shadowOf(app).nextStartedActivity)
    }

    @Test fun openRecentsWithoutACarPlaySessionRejectsWithEvidenceAndNeverOpensAnActivity() {
        val id = nonce()
        val result = broadcast(id, "open-recents")
        assertEquals(409, result.code)
        assertEquals("VP_PHONE_PROBE:$id:rejected", result.data)
        assertEquals(listOf("ENTRY", "ERROR"), rows(id).map { it.getString("event") })
        assertEquals("no-idle-carplay-session", rows(id).last().getString("detail"))
        assertNull("No fallback task or launcher may be opened", shadowOf(app).nextStartedActivity)
    }

    @Test fun receiverRejectsPathTraversalBeforeCreatingAnyJournal() {
        val id = nonce()
        val result = broadcast("../$id", "stop")
        assertEquals(400, result.code)
        assertEquals("VP_PHONE_PROBE:invalid:rejected", result.data)
        assertFalse(File(app.filesDir, "$id.jsonl").exists())
        assertFalse(file(id).exists())
        assertNull(shadowOf(app).nextStartedActivity)
    }

    @Test fun aRepeatedBroadcastCannotOverwriteTheOriginalRequest() {
        val id = nonce()
        VoyahPhoneProbeJournal.create(app, id, "stop")
        val original = file(id).readBytes()
        val result = broadcast(id, "stop")
        assertEquals(500, result.code)
        assertEquals("VP_PHONE_PROBE:$id:failed", result.data)
        assertArrayEquals(original, file(id).readBytes())
        assertNull(shadowOf(app).nextStartedActivity)
    }
}
