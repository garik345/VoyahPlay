package com.shilapi.xcertplay.voyah.root;

import java.io.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class RootHelperProtocolTest {
    @Test public void moduleActionsRoundTripWithoutShellText() throws Exception {
        for (RootHelperProtocol.Action action : RootHelperProtocol.Action.values()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            RootHelperProtocol.writeRequest(bytes, new RootHelperProtocol.Request(action));
            assertEquals(7, bytes.size());
            RootHelperProtocol.Request decoded = RootHelperProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
            assertEquals(action, decoded.action); assertFalse(decoded.active); assertFalse(decoded.hidden);
        }
    }
    @Test public void foregroundLeaseContainsOnlyBooleans() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RootHelperProtocol.writeRequest(bytes, new RootHelperProtocol.Request(RootHelperProtocol.Action.POLICY, true, true));
        RootHelperProtocol.Request request = RootHelperProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
        assertTrue(request.active); assertTrue(request.hidden); assertEquals(7, bytes.size());
    }
    @Test public void tweaksLeaseUsesItsOwnBooleanOnlyAction() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RootHelperProtocol.writeRequest(bytes, new RootHelperProtocol.Request(RootHelperProtocol.Action.TWEAKS_POLICY, true, true));
        RootHelperProtocol.Request request = RootHelperProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(RootHelperProtocol.Action.TWEAKS_POLICY, request.action);
        assertTrue(request.active); assertTrue(request.hidden); assertEquals(7, bytes.size());
    }
    @Test public void originalActionOrdinalsRemainCompatibleWithInstalledHelpers() {
        assertEquals(6, RootHelperProtocol.Action.POLICY.ordinal());
        assertEquals(7, RootHelperProtocol.Action.INFO.ordinal());
        assertEquals(8, RootHelperProtocol.Action.STOP.ordinal());
        assertEquals(9, RootHelperProtocol.Action.TWEAKS_STATUS.ordinal());
        assertEquals(10, RootHelperProtocol.Action.TWEAKS_POLICY.ordinal());
        assertEquals(11, RootHelperProtocol.Action.BOOT_STATUS.ordinal());
        assertEquals(12, RootHelperProtocol.Action.BOOT_POLICY.ordinal());
    }
    @Test public void bootPolicyAcceptsOneBooleanAndCannotCarryDockFlags() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RootHelperProtocol.writeRequest(bytes, new RootHelperProtocol.Request(RootHelperProtocol.Action.BOOT_POLICY, true, false));
        RootHelperProtocol.Request request = RootHelperProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
        assertTrue(request.active); assertFalse(request.hidden);
        try { new RootHelperProtocol.Request(RootHelperProtocol.Action.BOOT_POLICY, true, true); fail("Unexpected dock flag"); }
        catch (IllegalArgumentException expected) {}
    }
    @Test public void refusesUnknownActionBadBooleanAndWrongProtocol() throws Exception {
        for (byte[] input : new byte[][]{{0,0,0,0,0,0,0},{0x56,0x52,0x50,0x31,(byte)255,0,0},
                {0x56,0x52,0x50,0x31,0,2,0},{0x56,0x52,0x50,0x31,0,1,0}}) {
            try { RootHelperProtocol.readRequest(new ByteArrayInputStream(input)); fail("Invalid request accepted"); }
            catch (IOException expected) {}
        }
    }
    @Test public void allowsCurrentAppUidAndRestrictsRootAdminToHelperLifecycle() {
        for (RootHelperProtocol.Action action : RootHelperProtocol.Action.values()) {
            boolean allowed = action == RootHelperProtocol.Action.ROOT || action == RootHelperProtocol.Action.INFO
                    || action == RootHelperProtocol.Action.TWEAKS_STATUS || action == RootHelperProtocol.Action.TWEAKS_POLICY
                    || action == RootHelperProtocol.Action.BOOT_STATUS || action == RootHelperProtocol.Action.BOOT_POLICY;
            assertEquals(allowed, RootHelperProtocol.authorized(10042,10042,10042,action));
            assertFalse(RootHelperProtocol.authorized(10043,10042,10042,action));
            assertFalse(RootHelperProtocol.authorized(10042,10042,10043,action));
            assertEquals(action == RootHelperProtocol.Action.INFO || action == RootHelperProtocol.Action.STOP,
                    RootHelperProtocol.authorized(0,10042,10043,action));
        }
    }
    @Test public void responseCarriesExitStatusAndActualHelperVersion() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RootHelperProtocol.writeResult(bytes,0,new RootProcess.Result(7,true,"Пример отказа"));
        RootProcess.Result decoded = RootHelperProtocol.readResult(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(7,decoded.exitCode); assertTrue(decoded.timedOut);
        assertTrue(decoded.output.contains("TRANSPORT=root-helper\nHELPER_VERSION=" + RootHelperProtocol.VERSION));
        assertTrue(decoded.output.contains("Пример отказа"));
    }
    @Test public void refusesNonRootResponseAndUnboundedLengths() throws Exception {
        for (int uid : new int[]{0,10042}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(bytes);
            out.writeInt(RootHelperProtocol.MAGIC); out.writeInt(uid); out.writeInt(0); out.writeBoolean(false); out.writeInt(1000000);
            try { RootHelperProtocol.readResult(new ByteArrayInputStream(bytes.toByteArray())); fail("Bad response accepted"); }
            catch (IOException expected) {}
        }
    }
    @Test public void refusesTruncatedResponses() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RootHelperProtocol.writeResult(bytes,0,new RootProcess.Result(0,false,"ok"));
        byte[] full = bytes.toByteArray();
        for (int size = 0; size < full.length; size++) {
            try { RootHelperProtocol.readResult(new ByteArrayInputStream(java.util.Arrays.copyOf(full,size))); fail("Truncated response accepted"); }
            catch (IOException expected) {}
        }
    }
    @Test public void resolvesOnlyExactPrimaryPackageRows() throws Exception {
        assertEquals(10042,RootHelperOperations.parseUid("package:com.shilapi.xcertplay uid:10042\n"));
        for (String output : new String[]{"", "package:com.shilapi.xcertplay.other uid:10042\n",
                "package:com.shilapi.xcertplay uid:10042\npackage:com.shilapi.xcertplay uid:10043\n"}) {
            try { RootHelperOperations.parseUid(output); fail("Ambiguous package accepted"); } catch (IOException expected) {}
        }
    }
    @Test public void socketIdentityIsLimitedToPrimaryUserApplicationUids() {
        assertEquals("voyahplay.root.v1.10042",RootHelperProtocol.socketName(10042));
        for (int uid : new int[]{0,2000,9999,20000,110042}) {
            try { RootHelperProtocol.socketName(uid); fail("Invalid UID accepted"); } catch (IllegalArgumentException expected) {}
        }
    }
}
