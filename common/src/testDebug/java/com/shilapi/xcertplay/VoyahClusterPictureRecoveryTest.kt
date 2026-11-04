package com.shilapi.xcertplay

import org.junit.Assert.*
import org.junit.Test
import com.shilapi.xcertplay.VoyahClusterPictureRecovery.Action.*

class VoyahClusterPictureRecoveryTest {
    @Test fun repeatedHostTransitionsCannotPostponeRecoveryOfAnAlreadyEmptyMap() {
        var now = 0L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start(); recovery.hostVisibility(0)
        val requests = mutableListOf<Pair<Long, VoyahClusterPictureRecovery.Action>>()
        repeat(30) { second ->
            now = (second + 1) * 1000L
            recovery.hostVisibility(second + 1L)
            recovery.due()?.takeIf { it != REFRESH }?.let { requests += now to it }
        }
        assertEquals("the original 2/15/30-second retries must finish despite host changes", 3, recovery.attempts)
        assertEquals(listOf(2000L to REFRESH_VIEW_AREA, 15000L to REQUEST_MAP, 30000L to REOPEN_MAP), requests)
    }

    @Test fun onlyTheLastEmptyPictureRetryMayReopenTheSceneAndARealMapCancelsIt() {
        var now = 0L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start(); assertNull(recovery.due())
        now = 2000; assertEquals(REFRESH_VIEW_AREA, recovery.due())
        now = 15000; assertEquals(REQUEST_MAP, recovery.due())
        now = 30000; assertEquals(REOPEN_MAP, recovery.due())
        assertNull(recovery.due())
        recovery.picture(true); assertNull(recovery.due())
        recovery.picture(false); assertNull(recovery.due())
        recovery.stop(); now += 100000; assertNull(recovery.due())
    }

    @Test fun absentOrUniformFramesGetOnlyThreeRetriesAndNeverAnUnboundedReconnectLoop() {
        var now = 1000L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start()
        for (delay in listOf(2000L, 15000L, 30000L)) {
            now = 1000 + delay - 1; recovery.picture(false); assertNull(recovery.due())
            now++; assertEquals(when (delay) { 2000L -> REFRESH_VIEW_AREA; 30000L -> REOPEN_MAP; else -> REQUEST_MAP }, recovery.due()); assertNull(recovery.due())
        }
        repeat(200) { now += 1000; recovery.picture(false); assertNull(recovery.due()) }
        assertEquals(3, recovery.attempts)
    }

    @Test fun aDetailedStationaryMapDoesNotNeedContinuingFramesToStayVisible() {
        var now = 1000L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start(); recovery.picture(true)
        repeat(200) { now += 1000; assertNull(recovery.due()) }
        assertEquals(0, recovery.attempts)
    }

    @Test fun aNewBlackEpisodeAfterARealMapHasItsOwnBoundedRecoveryBudget() {
        var now = 1000L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start(); now += 2000; assertEquals(REFRESH_VIEW_AREA, recovery.due())
        recovery.picture(true); now += 100000; assertNull(recovery.due())
        recovery.picture(false)
        assertEquals(0, recovery.attempts)
        now += 1999; assertNull(recovery.due()); now++; assertEquals(REFRESH_VIEW_AREA, recovery.due())
        recovery.picture(true); now += 100000; assertNull(recovery.due())
    }

    @Test fun retiringTheSurfaceCancelsRecoveryAndANewSurfaceStartsFresh() {
        var now = 1000L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start(); recovery.stop(); now += 50000
        assertNull(recovery.due())
        recovery.start(); assertEquals(0, recovery.attempts); assertNull(recovery.due())
        now += 2000; assertEquals(REFRESH_VIEW_AREA, recovery.due())
    }

    @Test fun hostTransitionsRefreshADetailedMapWithoutReopeningItsScene() {
        var now = 1000L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start(); recovery.picture(true); recovery.hostVisibility(10)
        assertNull(recovery.due())
        recovery.hostVisibility(11)
        assertEquals(REFRESH, recovery.due()); assertNull(recovery.due())
        repeat(100) { now += 1000; recovery.hostVisibility(11); assertNull(recovery.due()) }
        recovery.hostVisibility(12)
        assertEquals(REFRESH, recovery.due()); assertNull(recovery.due())
        assertEquals(0, recovery.attempts)
        recovery.stop(); recovery.hostVisibility(13); assertNull(recovery.due())
    }

    @Test fun theObservedTwoToneIdleFrameCannotSuppressTheStartupRecovery() {
        var now = 1000L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start()
        assertFalse(VoyahClusterPictureRecovery.hasDetail(256, 256, 2))
        recovery.picture(VoyahClusterPictureRecovery.hasDetail(256, 256, 2))
        now += 2000; assertEquals(REFRESH_VIEW_AREA, recovery.due())
        assertTrue(VoyahClusterPictureRecovery.hasDetail(256, 256, 158))
        recovery.picture(VoyahClusterPictureRecovery.hasDetail(256, 256, 158))
        now += 100000; assertNull(recovery.due())
        assertFalse(VoyahClusterPictureRecovery.hasDetail(0, 0, 158))
        assertFalse(VoyahClusterPictureRecovery.hasDetail(256, 0, 158))
    }

    @Test fun hostTransitionsCannotAuthorizeMoreReopensAfterTheEmptyPictureBudgetIsExhausted() {
        var now = 0L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start(); recovery.hostVisibility(0)
        for (delay in listOf(2000L, 15000L, 30000L)) { now = delay; assertNotNull(recovery.due()) }
        repeat(100) { version ->
            now += 1000; recovery.hostVisibility(version + 1L)
            assertEquals(REFRESH, recovery.due()); assertNull(recovery.due())
        }
        assertEquals(3, recovery.attempts)
    }

    @Test fun aHostRefreshAndTheLastRetryInOnePollCannotDelayTheReopen() {
        var now = 0L; val recovery = VoyahClusterPictureRecovery { now }
        recovery.start(); recovery.hostVisibility(0)
        now = 2000; assertEquals(REFRESH_VIEW_AREA, recovery.due())
        now = 15000; assertEquals(REQUEST_MAP, recovery.due())
        now = 30000; recovery.hostVisibility(2)
        assertEquals(REOPEN_MAP, recovery.due()); assertNull(recovery.due())
    }
}
