package com.shilapi.xcertplay.voyah;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoyahClusterProbeSpecTest {
    private String record(int id) {
        return "    mDisplayInfo=DisplayInfo{\"Cluster-Media-Display\", displayId " + id +
            ", FLAG_PRIVATE, real 1920 x 720, state ON, type VIRTUAL, owner com.qinggan.cluster (uid 1000), removeMode 1}";
    }
    @Test public void discoversTheReviewedLogicalDisplayWithoutAssumingFive() {
        assertEquals(Integer.valueOf(5), VoyahClusterProbeSpec.targetDisplay(record(5)));
        assertEquals(Integer.valueOf(12), VoyahClusterProbeSpec.targetDisplay(record(12)));
        assertEquals(Integer.valueOf(5), VoyahClusterProbeSpec.targetDisplay(record(5) + "\n" + record(5)));
        assertNull(VoyahClusterProbeSpec.targetDisplay(record(5) + "\n" + record(12)));
    }
    @Test public void rejectsPassengerNavigationWrongOwnerAndUnreviewedGeometry() {
        for (String bad : new String[]{record(0), record(5).replace("Cluster-Media", "Cluster-Navi"),
            record(5).replace("Cluster-Media", "Passenger"), record(5).replace("1920 x 720", "1280 x 720"),
            record(5).replace("uid 1000", "uid 10001"), record(5).replace("com.qinggan.cluster", "other.app"),
            record(5).replace("FLAG_PRIVATE", "FLAG_PRESENTATION"), record(5).replace("state ON", "state OFF"),
            record(5).replace("type VIRTUAL", "type EXTERNAL"), record(5).replace("mDisplayInfo", "mOverrideDisplayInfo")})
            assertNull(bad, VoyahClusterProbeSpec.targetDisplay(bad));
    }
    @Test public void aProbeCannotOutliveOneMinuteOrUseArbitraryModesAndTokens() {
        String token = "01234567-89ab-cdef-0123-456789abcdef";
        assertTrue(VoyahClusterProbeSpec.validLease(token, "grid", 5, 61000, 1000));
        assertTrue(VoyahClusterProbeSpec.validLease(token, "map", 5, 61000, 60000));
        assertFalse(VoyahClusterProbeSpec.validLease(token, "map", 5, 61000, 61000));
        assertFalse(VoyahClusterProbeSpec.validLease(token, "map", 5, 61001, 1000));
        assertFalse(VoyahClusterProbeSpec.validLease(token, "grid; other", 5, 61000, 1000));
        assertFalse(VoyahClusterProbeSpec.validLease("untrusted", "grid", 5, 61000, 1000));
        assertFalse(VoyahClusterProbeSpec.validLease(token, "grid", 0, 61000, 1000));
    }
    @Test public void stopCanOnlyRetireTheOwnProbeTaskOnItsOriginalDisplay() {
        String own = VoyahClusterProbeSpec.ACTIVITY;
        assertTrue(VoyahClusterProbeSpec.ownsTask(own, own, 0, 5, 5));
        assertFalse(VoyahClusterProbeSpec.ownsTask(own, own, 0, 0, 5));
        assertFalse(VoyahClusterProbeSpec.ownsTask(own, own, 10, 5, 5));
        assertFalse(VoyahClusterProbeSpec.ownsTask(own, own, 0, 6, 5));
        assertFalse(VoyahClusterProbeSpec.ownsTask("com.shilapi.xcertplay.CarPlayHostActivity", own, 0, 5, 5));
        assertFalse(VoyahClusterProbeSpec.ownsTask(own, "com.qinggan.instrumentcard.ScreenActivity", 0, 5, 5));
        assertFalse(VoyahClusterProbeSpec.ownsTask(null, null, 0, 5, 5));
    }
}
