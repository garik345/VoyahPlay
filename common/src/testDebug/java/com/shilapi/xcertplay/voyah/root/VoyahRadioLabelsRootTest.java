package com.shilapi.xcertplay.voyah.root;

import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class VoyahRadioLabelsRootTest {
    private String reply(String version, boolean radio) {
        return "HELPER_VERSION=" + version + "\nHELPER_READY=1\nSERVER_UID=0\nCLIENT_UID=10114\n" +
                "CAPABILITIES=TWEAKS_BOUNDS_1_1\n" + (radio ? "RADIO_LABELS_CAPABILITY=OEM_RADIO_LABELS_1\n" : "");
    }
    @Test public void previousHelperRetainsFullscreenButCannotReceiveRadioCommands() throws Exception {
        RootHelperStatus old = new RootHelperStatus(new RootProcess.Result(0, false, reply("1.2.1", false)), 10114);
        old.requireCompatible();
        try { old.requireRadioLabels(); fail("Old helper received new command"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("-Mode Install")); }
        RootHelperStatus current = new RootHelperStatus(new RootProcess.Result(0, false, reply(RootHelperProtocol.VERSION, true)), 10114);
        current.requireRadioLabels();
    }
    @Test public void radioRequiresTheCapabilityAndCorrectAuthenticatedUid() throws Exception {
        for (RootHelperStatus state : new RootHelperStatus[]{
                new RootHelperStatus(new RootProcess.Result(0, false, reply(RootHelperProtocol.VERSION, false)), 10114),
                new RootHelperStatus(new RootProcess.Result(0, false, reply(RootHelperProtocol.VERSION, true)), 10115)}) {
            try { state.requireRadioLabels(); fail("Invalid capability or owner accepted"); }
            catch (IOException expected) { }
        }
    }
    @Test public void radioCommandsAreAppendedAndOnlyTheOwningAppCanChangeTheFlag() throws Exception {
        assertEquals(13, RootHelperProtocol.Action.TOP_POLICY.ordinal());
        assertEquals(14, RootHelperProtocol.Action.RADIO_STATUS.ordinal());
        assertEquals(15, RootHelperProtocol.Action.RADIO_POLICY.ordinal());
        assertTrue(RootHelperProtocol.authorized(10114, 10114, 10114, RootHelperProtocol.Action.RADIO_POLICY));
        for (int peer : new int[]{0, 1000, 10115})
            assertFalse(RootHelperProtocol.authorized(peer, 10114, 10114, RootHelperProtocol.Action.RADIO_POLICY));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        RootHelperProtocol.writeRequest(bytes, new RootHelperProtocol.Request(RootHelperProtocol.Action.RADIO_POLICY, true, false));
        RootHelperProtocol.Request read = RootHelperProtocol.readRequest(new ByteArrayInputStream(bytes.toByteArray()));
        assertEquals(RootHelperProtocol.Action.RADIO_POLICY, read.action); assertTrue(read.active); assertFalse(read.hidden);
        try { new RootHelperProtocol.Request(RootHelperProtocol.Action.RADIO_POLICY, true, true); fail("Unexpected field accepted"); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void exactlyOneAbsoluteApkPathIsRequired() throws Exception {
        for (String path : new String[]{"/system/priv-app/RdsApp/RdsApp.apk", "/product/app/RdsApp/base.apk",
                "/vendor/app/rdsapp-release-signed/rdsapp-release-signed.apk", "/vendor/priv-app/RdsApp/base.apk",
                "/data/app/~~abc==/com.pateo.rdsapp-abc==/base.apk"})
            assertEquals(path, RadioLabelsRoot.apkPath("package:" + path + "\r\n"));
        for (String bad : new String[]{"", "package:relative.apk", "package:/data/local/tmp/radio.apk",
                "package:/system/app/../radio.apk", "package:/vendor/app/../../radio.apk", "package:/vendor/app/./radio.apk", "package:/system/app/radio.apk\nwarning",
                "package:/system/app/base.apk\npackage:/system/app/split.apk"}) {
            try { RadioLabelsRoot.apkPath(bad); fail("Invalid path accepted: " + bad); }
            catch (IOException expected) { }
        }
    }
}
