package com.shilapi.xcertplay.voyah.root;

import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class VoyahClusterProbeRootTest {
    @Test public void arActionsAppendAfterNaviAndAreRestrictedToTheCurrentAppUid() throws Exception {
        RootHelperProtocol.Action[] commands = {RootHelperProtocol.Action.CLUSTER_AR_GRID, RootHelperProtocol.Action.CLUSTER_AR_MAP};
        for (int i = 0; i < commands.length; i++) {
            assertEquals(25 + i, commands[i].ordinal());
            assertTrue(RootHelperProtocol.authorized(10114,10114,10114,commands[i]));
            for (int uid : new int[]{0,1000,10115}) assertFalse(RootHelperProtocol.authorized(uid,10114,10114,commands[i]));
            assertFalse(RootHelperProtocol.authorized(10114,10114,10115,commands[i]));
            assertTrue(VoyahRootHelper.changesState(commands[i]));
            try { new RootHelperProtocol.Request(commands[i], true, false); fail(); } catch (IllegalArgumentException expected) { }
        }
        org.json.JSONObject active = new org.json.JSONObject().put("mode", "ar-map").put("deadline",61000).put("taskId",123).put("naviPreviousMode",2);
        assertTrue(ClusterProbeRoot.readyLease(active,1000,123));
        assertFalse(ClusterProbeRoot.readyLease(active,61000,123));
    }
    @Test public void naviCommandsAppendAndRequireTheirOwnCurrentHelperCapability() throws Exception {
        assertEquals(22, RootHelperProtocol.Action.CLUSTER_NAVI_GRID.ordinal());
        assertEquals(23, RootHelperProtocol.Action.CLUSTER_NAVI_MAP.ordinal());
        assertEquals(24, RootHelperProtocol.Action.CLUSTER_NAVI_READY.ordinal());
        for (RootHelperProtocol.Action action : new RootHelperProtocol.Action[]{RootHelperProtocol.Action.CLUSTER_NAVI_GRID, RootHelperProtocol.Action.CLUSTER_NAVI_MAP, RootHelperProtocol.Action.CLUSTER_NAVI_READY}) {
            assertTrue(RootHelperProtocol.authorized(10114, 10114, 10114, action));
            for (int peer : new int[]{0, 1000, 10115}) assertFalse(RootHelperProtocol.authorized(peer, 10114, 10114, action));
        }
        RootHelperStatus old = new RootHelperStatus(new RootProcess.Result(0, false,
            reply("1.2.13") + "CLUSTER_PROBE_CAPABILITY=VOYAH_CLUSTER_PROBE_1\nCLUSTER_NAVI_CAPABILITY=VOYAH_CLUSTER_NAVI_TEST_1\n"), 10114);
        old.requireClusterProbe();
        try { old.requireClusterNavi(); fail("Old helper accepted NAVI"); } catch (IOException expected) { }
        RootHelperStatus current = new RootHelperStatus(new RootProcess.Result(0, false,
            reply(RootHelperProtocol.VERSION) + "CLUSTER_NAVI_CAPABILITY=VOYAH_CLUSTER_NAVI_TEST_1\n"), 10114);
        current.requireClusterNavi();
    }
    @Test public void delayedPixelReadinessCannotOverrideAStoppedExpiredOrReplacedNaviTest() throws Exception {
        org.json.JSONObject active = new org.json.JSONObject().put("mode", "navi-map").put("deadline", 61000).put("taskId", 123).put("naviPreviousMode", 1);
        assertTrue(ClusterProbeRoot.readyLease(active, 1000, 123));
        assertFalse(ClusterProbeRoot.readyLease(null, 1000, 123));
        assertFalse(ClusterProbeRoot.readyLease(active, 61000, 123));
        assertFalse(ClusterProbeRoot.readyLease(active, 1000, 124));
        assertFalse(ClusterProbeRoot.readyLease(new org.json.JSONObject(active.toString()).put("naviRestored", true), 1000, 123));
        assertFalse(ClusterProbeRoot.readyLease(new org.json.JSONObject(active.toString()).put("mode", "auto"), 1000, 123));
    }
    @Test public void rollbackRetriesAfterBootEvenIfThePreviousBootDeadlineIsStillFarAhead() throws Exception {
        org.json.JSONObject previous = new org.json.JSONObject().put("deadline", 3L * 24 * 60 * 60 * 1000);
        assertFalse(ClusterProbeRoot.cleanupDue(previous, false, 1000));
        assertTrue(ClusterProbeRoot.cleanupDue(previous, true, 1000));
        assertTrue(ClusterProbeRoot.cleanupDue(new org.json.JSONObject().put("deadline", 1000), false, 1000));
        assertFalse(ClusterProbeRoot.cleanupDue(null, true, 1000));
    }
    private String reply(String version) {
        return "HELPER_VERSION=" + version + "\nHELPER_READY=1\nSERVER_UID=0\nCLIENT_UID=10114\n" +
            "CAPABILITIES=TWEAKS_BOUNDS_1_1\nRADIO_LABELS_CAPABILITY=OEM_RADIO_LABELS_1\n";
    }
    @Test public void olderBoundsAndRadioKeepWorkingButCannotLaunchAProbe() throws Exception {
        for (String version : new String[]{"1.2.1", "1.2.2"}) {
            RootHelperStatus state = new RootHelperStatus(new RootProcess.Result(0, false, reply(version)), 10114);
            state.requireCompatible();
            if (version.equals("1.2.2")) state.requireRadioLabels();
            try { state.requireClusterProbe(); fail("Old helper accepted probe"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("1.2.3")); }
        }
        RootHelperStatus current = new RootHelperStatus(new RootProcess.Result(0, false,
            reply(RootHelperProtocol.VERSION) + "CLUSTER_PROBE_CAPABILITY=VOYAH_CLUSTER_PROBE_1\n"), 10114);
        current.requireClusterProbe(); current.requireRadioLabels();
    }
    @Test public void fixedActionsAreAppendedAndAuthorizeOnlyTheCurrentOwningUid() throws Exception {
        assertEquals(15, RootHelperProtocol.Action.RADIO_POLICY.ordinal());
        RootHelperProtocol.Action[] actions = {RootHelperProtocol.Action.CLUSTER_STATUS, RootHelperProtocol.Action.CLUSTER_GRID,
            RootHelperProtocol.Action.CLUSTER_MAP, RootHelperProtocol.Action.CLUSTER_STOP};
        for (int i = 0; i < actions.length; i++) {
            assertEquals(16 + i, actions[i].ordinal());
            assertTrue(RootHelperProtocol.authorized(10114, 10114, 10114, actions[i]));
            for (int uid : new int[]{0, 1000, 10115})
                assertFalse(RootHelperProtocol.authorized(uid, 10114, 10114, actions[i]));
            try { new RootHelperProtocol.Request(actions[i], true, false); fail("Probe accepted caller fields"); }
            catch (IllegalArgumentException expected) { }
        }
    }
    @Test public void previousHelpersRetainTheirRadioAndClusterCapabilities() throws Exception {
        for (String version : new String[]{"1.2.3", "1.2.4", "1.2.5", "1.2.6", "1.2.7", "1.2.8", "1.2.9", "1.2.10", "1.2.11", "1.2.12"}) {
            RootHelperStatus state = new RootHelperStatus(new RootProcess.Result(0, false,
                reply(version) + "CLUSTER_PROBE_CAPABILITY=VOYAH_CLUSTER_PROBE_1\n"), 10114);
            state.requireCompatible(); state.requireRadioLabels(); state.requireClusterProbe();
        }
    }
    @Test public void automaticCommandsRequireTheirNewCapabilityAndKeepTheOldOrdinals() throws Exception {
        assertEquals(19, RootHelperProtocol.Action.CLUSTER_STOP.ordinal());
        RootHelperProtocol.Action[] actions = {RootHelperProtocol.Action.CLUSTER_AUTO_TICK, RootHelperProtocol.Action.CLUSTER_AUTO_STOP};
        for (int i = 0; i < actions.length; i++) {
            assertEquals(20 + i, actions[i].ordinal());
            assertTrue(RootHelperProtocol.authorized(10114, 10114, 10114, actions[i]));
            for (int peer : new int[]{0, 1000, 10115}) assertFalse(RootHelperProtocol.authorized(peer, 10114, 10114, actions[i]));
            try { new RootHelperProtocol.Request(actions[i], true, false); fail("Caller fields accepted"); }
            catch (IllegalArgumentException expected) { }
        }
        for (String version : new String[]{"1.2.5", "1.2.6", RootHelperProtocol.VERSION}) {
            RootHelperStatus old = new RootHelperStatus(new RootProcess.Result(0, false,
                reply(version) + "CLUSTER_PROBE_CAPABILITY=VOYAH_CLUSTER_PROBE_1\n"), 10114);
            old.requireClusterProbe();
            try { old.requireClusterAuto(); fail("Automatic map accepted without capability"); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains(RootHelperProtocol.VERSION)); }
        }
        new RootHelperStatus(new RootProcess.Result(0, false,
            reply(RootHelperProtocol.VERSION) + "CLUSTER_AUTO_CAPABILITY=VOYAH_CLUSTER_AUTO_1\n"), 10114).requireClusterAuto();
    }
}
