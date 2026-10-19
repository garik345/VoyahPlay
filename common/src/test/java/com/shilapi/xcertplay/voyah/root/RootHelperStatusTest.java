package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class RootHelperStatusTest {
    private String reply(String version, int uid) {
        return "TRANSPORT=root-helper\nHELPER_VERSION=" + version +
                "\nHELPER_READY=1\nSERVER_UID=0\nCLIENT_UID=" + uid +
                "\nCAPABILITIES=TWEAKS_BOUNDS_1_1\nAUTOSTART_INSTALLED=1\nAUTOSTART_ENABLED=1\n";
    }
    @Test public void currentHelperIsReadyForChanges() throws Exception {
        RootHelperStatus state = new RootHelperStatus(new RootProcess.Result(0,false,reply(RootHelperProtocol.VERSION,10113)),10113);
        assertTrue(state.ready); assertTrue(state.compatible); state.requireCompatible();
        assertTrue(state.report().contains("HELPER_CONNECTED=1"));
    }
    @Test public void olderHelperIsFoundButCannotChangeBootOrBounds() throws Exception {
        RootHelperStatus state = new RootHelperStatus(new RootProcess.Result(0,false,reply("1.1.0",10113)),10113);
        assertTrue(state.ready); assertFalse(state.compatible);
        assertTrue(state.report().contains("HELPER_VERSION=1.1.0\n"));
        assertTrue(state.report().contains("HELPER_CONNECTED=1\n"));
        try { state.requireCompatible(); fail("Old snapshot accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("-Mode Install")); }
    }
    @Test public void uidChangeRequiresRebindingEvenAtSameVersion() throws Exception {
        RootHelperStatus state = new RootHelperStatus(new RootProcess.Result(0,false,reply(RootHelperProtocol.VERSION,10111)),10113);
        assertFalse(state.ready); assertFalse(state.compatible);
        try { state.requireCompatible(); fail("Different UID accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("UID")); }
    }
    @Test public void missingCapabilityIsNotAConnectionFailure() throws Exception {
        RootHelperStatus state = new RootHelperStatus(new RootProcess.Result(0,false,
                reply(RootHelperProtocol.VERSION,10113).replace("CAPABILITIES=TWEAKS_BOUNDS_1_1\n","")),10113);
        assertTrue(state.ready); assertFalse(state.compatible);
    }
    @Test public void timedOutOrFailedStatusNeverAuthorizesChanges() throws Exception {
        for (RootProcess.Result result : new RootProcess.Result[] {
                new RootProcess.Result(1,false,reply(RootHelperProtocol.VERSION,10113)),
                new RootProcess.Result(0,true,reply(RootHelperProtocol.VERSION,10113)) }) {
            RootHelperStatus state = new RootHelperStatus(result,10113);
            assertFalse(state.ready); assertFalse(state.compatible);
        }
    }
    @Test public void missingAuthenticatedReplyIsNotReportedAsAFoundHelper() throws Exception {
        RootHelperStatus state = new RootHelperStatus(new RootProcess.Result(-1,false,
                "TRANSPORT=root-helper\nIOException: Invalid root helper identity/protocol"),10113);
        assertFalse(state.connected); assertFalse(state.ready);
        assertTrue(state.report().contains("HELPER_CONNECTED=0\n"));
    }
    @Test public void duplicateIdentityIsRejectedAndCrLfIsAccepted() throws Exception {
        String reply = reply(RootHelperProtocol.VERSION,10113);
        assertTrue(new RootHelperStatus(new RootProcess.Result(0,false,reply.replace("\n","\r\n")),10113).compatible);
        try { new RootHelperStatus(new RootProcess.Result(0,false,reply+"CLIENT_UID=10113\n"),10113); fail("Duplicate identity accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Duplicate")); }
    }
}
