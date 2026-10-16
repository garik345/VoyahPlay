package com.shilapi.xcertplay.voyah.root;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TweaksBoundsSnapshotTest {
    private JSONObject sample() throws Exception {
        return new JSONObject().put("version", "1.1").put("pid", 987).put("now_ms", 10000)
                .put("boot_ready", true).put("subscription_valid", true)
                .put("lease_active", false).put("lease_until_ms", 0);
    }
    private void rejected(JSONObject data, long now) throws Exception {
        try { TweaksBoundsSnapshot.parse(data.toString(), now); fail("Unusable bridge accepted"); }
        catch (java.io.IOException expected) {}
    }
    @Test public void acceptsFreshLiveBridgeBeforeItsFirstLease() throws Exception {
        TweaksBoundsSnapshot state = TweaksBoundsSnapshot.parse(sample().toString(), 10400);
        assertEquals(987, state.pid); assertFalse(state.leaseActive);
    }
    @Test public void acceptsObservedShortLease() throws Exception {
        JSONObject data = sample().put("lease_active", true).put("lease_until_ms", 24000);
        assertTrue(TweaksBoundsSnapshot.parse(data.toString(), 10400).leaseActive);
    }
    @Test public void rejectsStaleProbeAndPreviousBootClock() throws Exception {
        rejected(sample(), 16000);
        rejected(sample(), 9999);
    }
    @Test public void rejectsOtherBridgeVersion() throws Exception { rejected(sample().put("version", "1.0"), 10400); }
    @Test public void rejectsInvalidPid() throws Exception { rejected(sample().put("pid", 0), 10400); }
    @Test public void preservesExistingTweaksReadinessAndSubscriptionGates() throws Exception {
        rejected(sample().put("boot_ready", false), 10400);
        rejected(sample().put("subscription_valid", false), 10400);
        rejected(sample().put("subscription_valid", JSONObject.NULL), 10400);
    }
    @Test public void historicalErrorDoesNotOverrideFreshRecoveredState() throws Exception {
        assertNotNull(TweaksBoundsSnapshot.parse(sample().put("last_error", "old initialization error").toString(), 10400));
    }
    @Test public void staleLeaseFlagCannotExtendExpiredOrUnboundedLease() throws Exception {
        assertFalse(TweaksBoundsSnapshot.parse(sample().put("lease_active", true).put("lease_until_ms", 10000).toString(), 10400).leaseActive);
        assertFalse(TweaksBoundsSnapshot.parse(sample().put("lease_active", true).put("lease_until_ms", 100000).toString(), 10400).leaseActive);
    }
    @Test public void refusesAbsentAndUnboundedProbe() throws Exception {
        for (String raw : new String[]{null, "null", "", "x".repeat(8193)}) {
            try { TweaksBoundsSnapshot.parse(raw, 10400); fail("Bad probe accepted"); }
            catch (java.io.IOException expected) {}
        }
    }
    @Test public void leaseParserAcceptsOnlyBoundedFutureDeadline() {
        assertTrue(TweaksBoundsSnapshot.activeLease("1|15000", 10000));
        for (String raw : new String[]{null, "", "1|10000", "1|9999", "1|40001", "2|15000", "1|x", "1|15000\n", "1|15000|1"})
            assertFalse(raw, TweaksBoundsSnapshot.activeLease(raw, 10000));
    }
}
