package com.shilapi.xcertplay

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.security.MessageDigest
import java.util.ArrayDeque

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class VoyahPhoneWheelTest {
    private class Jobs {
        val main = ArrayDeque<() -> Unit>()
        val worker = ArrayDeque<() -> Unit>()
        val delayed = ArrayDeque<() -> Unit>()
        fun main() { while (main.isNotEmpty()) main.removeFirst().invoke() }
        fun worker() { while (worker.isNotEmpty()) worker.removeFirst().invoke() }
    }

    private class Session(override val identity: Any = Any()) : VoyahPhoneWheelSession {
        var primary: Any? = Any()
        val sent = mutableListOf<Any>()
        val permits = mutableListOf<() -> Boolean>()
        val completions = mutableListOf<(Boolean) -> Unit>()
        var accepted = true
        override fun token(): Any? = primary
        override fun open(token: Any, permitted: () -> Boolean, complete: (Boolean) -> Unit): Boolean {
            if (accepted) { sent.add(token); permits.add(permitted); completions.add(complete) }
            return accepted
        }
    }

    private class Backend : VoyahPhoneWheelBackend {
        var verifies = 0
        var reviewed = true
        val links = mutableListOf<Link>()
        override fun verify() { verifies++; check(reviewed) { "Unknown OEM files" } }
        override fun bind(onKey: (Int, Int) -> Boolean, lost: () -> Unit): VoyahPhoneWheelBackend.Connection =
            Link(onKey, lost).also { links.add(it) }
        class Link(val key: (Int, Int) -> Boolean, val lost: () -> Unit) : VoyahPhoneWheelBackend.Connection {
            var closed = false
            override fun close() { closed = true }
        }
    }

    private class Fixture {
        val jobs = Jobs()
        val backend = Backend()
        var calling = false
        var visible = false
        var canOpenHost = true
        var opened = 0
        val runtime = VoyahPhoneWheelRuntime(jobs.main::add, { _, task -> jobs.delayed.add(task) },
            jobs.worker::add, backend, { calling }, { visible }, { opened++ }, { canOpenHost })
        fun start(session: Session = Session()): Session {
            runtime.setEnabled(true)
            runtime.setSession(session)
            runtime.refresh()
            jobs.main()
            jobs.worker()
            jobs.main()
            return session
        }
        fun short(): Boolean = backend.links.last().key(128, 8192)
    }

    @Test fun readinessRequiresCompletedReviewedVerificationAndNotJustAnEnabledPreference() {
        val f = Fixture()
        f.runtime.setEnabled(true)
        f.runtime.setSession(Session())
        var notified = 0
        f.runtime.refresh { notified++ }
        f.runtime.refresh { notified++ }
        f.jobs.main()
        assertFalse(f.runtime.supported())
        assertEquals(0, notified)
        assertTrue(f.backend.links.isEmpty())
        f.jobs.worker()
        f.jobs.main()
        assertTrue(f.runtime.supported())
        assertEquals(1, f.backend.verifies)
        assertEquals(2, notified)
        assertEquals(1, f.backend.links.size)
        f.runtime.refresh { notified++ }
        f.jobs.main()
        assertEquals(3, notified)
        assertEquals(1, f.backend.verifies)
    }

    @Test fun unknownFirmwareNeverRegistersOrSuppressesTheNativePhoneButton() {
        val f = Fixture()
        f.backend.reviewed = false
        f.start()
        assertFalse(f.runtime.supported())
        assertTrue(f.backend.links.isEmpty())
        f.runtime.setEnabled(false)
        f.runtime.setEnabled(true)
        f.jobs.main()
        assertTrue(f.backend.links.isEmpty())
    }

    @Test fun knownFirmwareStillRequiresTheOptInAndAController() {
        val f = Fixture()
        f.runtime.refresh()
        f.jobs.worker(); f.jobs.main()
        assertTrue(f.runtime.supported())
        assertTrue(f.backend.links.isEmpty())
        f.runtime.setSession(Session())
        f.jobs.main()
        assertTrue(f.backend.links.isEmpty())
        f.runtime.setEnabled(true)
        f.jobs.main()
        assertEquals(1, f.backend.links.size)
    }

    @Test fun onlyCompletedQGPhoneShortPressOutsideAnyCallIsConsumed() {
        val f = Fixture()
        val session = f.start()
        val link = f.backend.links.single()
        for (action in listOf(4096, 65536, 16384, 32768, 262144, 524288, 0, 1)) {
            assertFalse("QG action $action must keep its native behavior", link.key(128, action))
        }
        for (key in listOf(1, 2, 3, 127, 129, 130, 176, 313)) assertFalse(link.key(key, 8192))
        f.calling = true
        assertFalse(f.short())
        f.calling = false
        assertTrue(f.short())
        assertEquals(listOf(session.primary), session.sent)
    }

    @Test fun oneControllerCanReconnectWithANewPrimaryTokenWithoutRebinding() {
        val f = Fixture()
        val session = f.start()
        val first = session.primary
        assertTrue(f.short())
        session.primary = null
        assertFalse(f.short())
        session.primary = Any()
        assertTrue(f.short())
        assertEquals(listOf(first, session.primary), session.sent)
        assertEquals(1, f.backend.links.size)
        session.completions.first()(true)
        f.jobs.main()
        assertEquals("Old primary delivery cannot open the current task", 0, f.opened)
        session.completions.last()(true)
        f.jobs.main()
        assertEquals(1, f.opened)
    }

    @Test fun storingTheSameControllerAgainDoesNotRegisterADuplicate() {
        val f = Fixture()
        val session = f.start()
        f.runtime.setSession(Session(session.identity))
        f.runtime.setEnabled(true)
        f.jobs.main()
        assertEquals(1, f.backend.links.size)
        assertFalse(f.backend.links.single().closed)
    }

    @Test fun disableAndClearReleaseTheKeyBeforeMainThreadCleanupRuns() {
        for (clear in listOf(false, true)) {
            val f = Fixture()
            val session = f.start()
            val old = f.backend.links.single()
            assertTrue(f.short())
            if (clear) f.runtime.setSession(null) else f.runtime.setEnabled(false)
            assertFalse("Gate closes synchronously", old.key(128, 8192))
            assertFalse("Native cleanup has not run yet", old.closed)
            session.completions.single()(true)
            f.jobs.main()
            assertTrue(old.closed)
            assertEquals(0, f.opened)
        }
    }

    @Test fun rapidOffOnAndControllerReplacementCannotResurrectAnOldListener() {
        val f = Fixture()
        val original = f.start()
        val old = f.backend.links.single()
        f.runtime.setEnabled(false)
        f.runtime.setEnabled(true)
        val replacement = Session()
        f.runtime.setSession(replacement)
        assertFalse(old.key(128, 8192))
        f.jobs.main()
        assertTrue(old.closed)
        assertEquals(2, f.backend.links.size)
        assertTrue(f.short())
        assertTrue(original.sent.isEmpty())
        assertEquals(listOf(replacement.primary), replacement.sent)
        old.lost() // A delayed death callback from the previous native connection.
        f.jobs.main()
        while (f.jobs.delayed.isNotEmpty()) f.jobs.delayed.removeFirst().invoke()
        f.jobs.main()
        assertEquals(2, f.backend.links.size)
        assertTrue(f.short())
    }

    @Test fun failedQueueAndFailedDeliveryNeverBringAnAndroidActivityForward() {
        val f = Fixture()
        val session = f.start()
        session.accepted = false
        assertFalse(f.short())
        assertTrue(session.sent.isEmpty())
        session.accepted = true
        assertTrue(f.short())
        session.completions.single()(false)
        f.jobs.main()
        assertEquals(0, f.opened)
    }

    @Test fun queuedRequestIsCancelledIfDisabledClearedReplacedOrACallStartsBeforeDispatch() {
        for (change in listOf("disabled", "cleared", "replacement", "call", "new-primary")) {
            val f = Fixture()
            val session = f.start()
            assertTrue(f.short())
            val permitted = session.permits.single()
            assertTrue("Initially eligible request: $change", permitted())
            when (change) {
                "disabled" -> f.runtime.setEnabled(false)
                "cleared" -> f.runtime.setSession(null)
                "replacement" -> f.runtime.setSession(Session())
                "call" -> f.calling = true
                "new-primary" -> session.primary = Any()
            }
            // The controller evaluates this predicate on its command worker, immediately before requestUI.
            val delivered = permitted()
            assertFalse("Queued request must be cancelled after $change", delivered)
            session.completions.single()(delivered)
            f.jobs.main()
            assertEquals(0, f.opened)
        }
    }

    @Test fun backgroundWithoutOverlayPermissionPassesToNativeButForegroundRecentsStillWorks() {
        WheelKeyLog.reset()
        val f = Fixture()
        f.canOpenHost = false
        val session = f.start()
        assertFalse("Keep native phone handling when VoyahPlay cannot return from the background", f.short())
        assertTrue(session.sent.isEmpty())
        assertTrue("A report must explain the native fallback even when OEM logcat is disabled",
            WheelKeyLog.report(false).any { it.contains("background screen permission unavailable") })
        f.visible = true
        assertTrue("The visible CarPlay task needs no background-launch permission", f.short())
        assertTrue(session.permits.single()())
        session.completions.single()(true)
        f.jobs.main()
        assertEquals(0, f.opened)
        f.visible = false
        assertFalse(f.short())
        f.canOpenHost = true
        assertTrue("A newly granted permission takes effect without reconnecting", f.short())
        assertTrue(session.permits.last()())
        session.completions.last()(true)
        f.jobs.main()
        assertEquals(1, f.opened)
        assertTrue(WheelKeyLog.report(false).any { it.contains("Recents request delivered=true") })
        assertTrue(WheelKeyLog.report(false).any { it.contains("returning to CarPlay") })
    }

    @Test fun permissionRevokedWhileQueuedCancelsDispatchAndSuppressesACompletedHostReturn() {
        val f = Fixture()
        val session = f.start()
        assertTrue(f.short())
        assertTrue(session.permits.single()())
        f.canOpenHost = false
        assertFalse("Dispatch must recheck the permission after queuing", session.permits.single()())
        session.completions.single()(false)
        f.jobs.main()
        assertEquals(0, f.opened)
        f.canOpenHost = true
        assertTrue(f.short())
        assertTrue(session.permits.last()())
        session.completions.last()(true) // Sent successfully, then permission revoked before the main callback.
        f.canOpenHost = false
        f.jobs.main()
        assertEquals(0, f.opened)
    }

    @Test fun foregroundAndNewlyRingingCallsSuppressOnlyTheReturnToHost() {
        val f = Fixture()
        val session = f.start()
        f.visible = true
        assertTrue(f.short())
        session.completions.last()(true)
        f.jobs.main()
        assertEquals(0, f.opened)
        f.visible = false
        assertTrue(f.short())
        session.completions.last()(true)
        f.calling = true
        f.jobs.main()
        assertEquals(0, f.opened)
    }

    @Test fun nativeLossFailsOpenAndRetryCannotOutliveDisabledOrReplacedSession() {
        val f = Fixture()
        f.start()
        val old = f.backend.links.single()
        old.lost()
        assertFalse(old.key(128, 8192))
        f.jobs.main()
        assertTrue(old.closed)
        f.runtime.setSession(null)
        f.jobs.main()
        f.jobs.delayed.removeFirst().invoke()
        f.jobs.main()
        assertEquals(1, f.backend.links.size)
    }

    @Test fun optInDefaultsOffAndPersistsWithoutEnablingTheGenericWheelService() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("voyah_phone_wheel", 0).edit().clear().commit()
        app.getSharedPreferences("diplay_wheel_map_zoom", 0).edit().clear().commit()
        assertFalse(VoyahPhoneWheelSettings.enabled(app))
        VoyahPhoneWheelSettings.setEnabled(app, true)
        assertTrue(VoyahPhoneWheelSettings.enabled(app))
        assertFalse(WheelZoomSettings.anyEnabled(app))
        VoyahPhoneWheelSettings.setEnabled(app, false)
        assertFalse(VoyahPhoneWheelSettings.enabled(app))
    }

    @Test fun reviewedFileRequiresExactHashAndBoundedNonemptyInput() {
        val file = File.createTempFile("wheel-api", ".bin", RuntimeEnvironment.getApplication().cacheDir)
        try {
            val content = byteArrayOf(4, 2, 8, 1)
            file.writeBytes(content)
            val digest = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it.toInt() and 255) }
            VoyahPhoneWheelOem.verifyFile(file, 4, digest)
            for ((limit, hash) in listOf(3L to digest, 4L to "0".repeat(64))) {
                try { VoyahPhoneWheelOem.verifyFile(file, limit, hash); fail("Unreviewed file accepted") }
                catch (_: IllegalStateException) { }
            }
            file.writeBytes(byteArrayOf())
            try { VoyahPhoneWheelOem.verifyFile(file, 4, digest); fail("Empty file accepted") }
            catch (_: IllegalStateException) { }
        } finally { file.delete() }
    }

    @Test fun splitKeyManagerIsRejectedBeforeLoadingOrReadingAnySdk() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).installPackage(PackageInfo().apply {
            packageName = VoyahPhoneWheelOem.PACKAGE
            applicationInfo = ApplicationInfo().apply {
                packageName = VoyahPhoneWheelOem.PACKAGE
                sourceDir = "/does-not-exist/known.apk"
                splitSourceDirs = arrayOf("/does-not-exist/split.apk")
            }
        })
        try { VoyahPhoneWheelOem.decoder(app); fail("Split KeyManager accepted") }
        catch (error: IllegalStateException) { assertEquals("Unreviewed split KeyManager", error.message) }
    }
}
