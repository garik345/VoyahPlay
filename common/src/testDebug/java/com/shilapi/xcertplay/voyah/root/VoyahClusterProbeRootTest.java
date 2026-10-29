package com.shilapi.xcertplay.voyah.root;

import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class VoyahClusterProbeRootTest {
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
        for (String version : new String[]{"1.2.3", "1.2.4", "1.2.5", "1.2.6"}) {
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
