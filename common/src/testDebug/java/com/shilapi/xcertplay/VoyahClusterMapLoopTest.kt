package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test

class VoyahClusterMapLoopTest {
    private class Fixture {
        var now = 1000L
        val requests = mutableListOf<Boolean>()
        var reply: ((Boolean, String) -> Unit)? = null
        val loop = VoyahClusterMapLoop({ now }, { tick, done ->
            assertNull("Only one root mutation may be in flight", reply)
            requests += tick; reply = done
        }, {})
        fun complete(ok: Boolean = true, body: String = lease()) {
            val done = requireNotNull(reply); reply = null; done(ok, body)
        }
        fun lease(token: String = "01234567-89ab-cdef-0123-456789abcdef", deadline: Long = now + 45000) =
            "CLUSTER_PROBE_ACTIVE=1\nCLUSTER_PROBE_MODE=auto\nCLUSTER_PROBE_TOKEN=$token\nCLUSTER_PROBE_DISPLAY=5\nCLUSTER_PROBE_DEADLINE=$deadline\n"
    }

    @Test fun aLongRouteRenewsTheSameWindowWithoutAOneMinuteLimit() {
        val f = Fixture(); val phone = Any()
        f.loop.update(phone); f.complete()
        val token = f.loop.lease!!.token
        repeat(10) {
            f.now += 14999; f.loop.update(phone)
            assertNull(f.reply)
            f.now++; f.loop.update(phone); f.complete()
            assertEquals(token, f.loop.lease!!.token)
            assertEquals(f.now + 45000, f.loop.lease!!.deadline)
        }
        assertTrue(f.requests.all { it })
    }

    @Test fun endingNavigationOrDisablingTheSettingClosesTheLease() {
        val f = Fixture(); f.loop.update(Any()); f.complete()
        f.loop.update(null)
        assertNull(f.loop.lease)
        assertEquals(listOf(true, false), f.requests)
        f.complete(body = "CLUSTER_AUTO_STOPPED=1\n")
        f.loop.update(null)
        assertEquals(2, f.requests.size)
    }

    @Test fun disconnectWhileLaunchIsInFlightCannotReopenTheMap() {
        val f = Fixture(); f.loop.update(Any()); f.loop.update(null)
        f.complete()
        assertNull(f.loop.lease)
        f.loop.update(null)
        assertEquals(listOf(true, false), f.requests)
        f.complete(body = "stopped")
        f.loop.update(null)
        assertNull(f.loop.lease)
    }

    @Test fun aNewPhoneOrSessionRetiresTheOldLeaseBeforeStarting() {
        val f = Fixture(); f.loop.update(Any()); val next = Any()
        f.loop.update(next); f.complete()
        f.loop.update(next)
        assertEquals(listOf(true, false), f.requests)
        f.complete(body = "stopped")
        f.loop.update(next); f.complete()
        assertEquals(listOf(true, false, true), f.requests)
        assertNotNull(f.loop.lease)
    }

    @Test fun aBusyRenewalKeepsTheLastValidLeaseAndBacksOff() {
        val f = Fixture(); val phone = Any()
        f.loop.update(phone); f.complete(); val lease = f.loop.lease
        f.now += 15000; f.loop.update(phone); f.complete(false, "HELPER_BUSY")
        assertEquals(lease, f.loop.lease)
        f.loop.update(phone); assertNull(f.reply)
        f.now += 2000; f.loop.update(phone); f.complete()
        assertNotNull(f.loop.lease)
    }

    @Test fun failedCleanupMustFinishBeforeAnotherLaunch() {
        val f = Fixture(); f.loop.update(Any()); f.complete()
        val next = Any(); f.loop.update(next); f.complete(false, "unavailable")
        f.now += 2000; f.loop.update(next)
        assertEquals(listOf(true, false, false), f.requests)
        f.complete(body = "stopped"); f.loop.update(next)
        assertEquals(listOf(true, false, false, true), f.requests)
    }

    @Test fun malformedDuplicatedExpiredAndOverlongRepliesCannotAuthorizeAnActivity() {
        val f = Fixture()
        for (text in listOf(f.lease(deadline = f.now), f.lease(deadline = f.now + 45001),
            f.lease() + "CLUSTER_PROBE_TOKEN=another\n", f.lease().replace("MODE=auto", "MODE=grid"),
            f.lease().replace("DISPLAY=5", "DISPLAY=0"), f.lease("bad-token"))) {
            assertNull(VoyahClusterMapLoop.Lease.parse(text, f.now))
        }
    }

    @Test fun aLostHelperCannotKeepAnExpiredWindowAuthorized() {
        val f = Fixture(); val phone = Any()
        f.loop.update(phone); f.complete()
        f.now += 45000; f.loop.update(phone)
        assertNull(f.loop.lease)
        f.complete(false, "helper unavailable")
        assertNull(f.loop.lease)
    }
}
