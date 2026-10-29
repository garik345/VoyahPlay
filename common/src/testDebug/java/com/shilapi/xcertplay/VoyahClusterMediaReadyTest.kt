package com.shilapi.xcertplay

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class VoyahClusterMediaReadyTest {
    private class Fixture {
        var now = 1000L
        var frameAndOwnerReady = false
        val permits = mutableListOf<AtomicBoolean>()
        var reply: ((Boolean, String) -> Unit)? = null
        val reports = mutableListOf<String>()
        val state = VoyahClusterMediaReady({ now }, { frameAndOwnerReady }, { permit, done ->
            assertNull("Only one write may be pending", reply)
            permits += permit
            reply = done
        }, { reports += it })
        fun complete(ok: Boolean = true) {
            val done = requireNotNull(reply)
            reply = null
            done(ok, if (ok) "MEDIA=1 sent" else "unavailable")
        }
    }

    @Test fun announcesAfterTheFirstFrameAndNotForEveryFrameOrCardSwitch() {
        val f = Fixture()
        f.state.ensure()
        assertTrue(f.permits.isEmpty())
        f.frameAndOwnerReady = true
        repeat(30) { f.state.ensure() }
        assertEquals(1, f.permits.size)
        f.complete()
        repeat(300) { f.now += 1000; f.state.ensure() }
        assertEquals(1, f.permits.size)
        assertTrue(f.state.ready)
    }

    @Test fun closeCancelsAnOutstandingWriteAndIgnoresItsLateReply() {
        val f = Fixture(); f.frameAndOwnerReady = true; f.state.ensure()
        assertTrue(f.permits.single().get())
        f.state.close()
        assertFalse(f.permits.single().get())
        f.complete()
        f.state.ensure()
        assertFalse(f.state.ready)
        assertTrue(f.reports.isEmpty())
        assertEquals(1, f.permits.size)
    }

    @Test fun changedOwnerCannotAcceptAnOldSuccess() {
        val f = Fixture(); f.frameAndOwnerReady = true; f.state.ensure()
        f.frameAndOwnerReady = false
        f.complete()
        assertFalse(f.state.ready)
        assertTrue(f.reports.isEmpty())
        f.state.ensure()
        assertEquals(1, f.permits.size)
    }

    @Test fun transientBinderFailureRetriesWithBackoffAndWithoutDuplicatingWrites() {
        val f = Fixture(); f.frameAndOwnerReady = true; f.state.ensure(); f.complete(false)
        assertFalse(f.state.ready)
        f.now += 1999; f.state.ensure(); assertEquals(1, f.permits.size)
        f.now++; f.state.ensure(); f.complete(false)
        f.now += 4999; f.state.ensure(); assertEquals(2, f.permits.size)
        f.now++; f.state.ensure(); f.complete()
        assertTrue(f.state.ready)
        assertEquals(3, f.permits.size)
    }

    @Test fun closingAnAnnouncedWindowDoesNotSendAClearOverTheReturningOemActivity() {
        val f = Fixture(); f.frameAndOwnerReady = true; f.state.ensure(); f.complete()
        f.state.close()
        repeat(20) { f.now += 1000; f.state.ensure() }
        assertEquals(1, f.permits.size)
        assertFalse(f.state.ready)
        val next = Fixture(); next.frameAndOwnerReady = true; next.state.ensure(); next.complete()
        assertTrue(next.state.ready)
    }
}
