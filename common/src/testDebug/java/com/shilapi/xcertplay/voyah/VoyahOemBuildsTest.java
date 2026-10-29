package com.shilapi.xcertplay.voyah;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoyahOemBuildsTest {
    private static final String CAN = "com.qinggan.canbus.service";
    private static final String SIGNAL = "com.qinggan.carsignal.service";
    private static final String PI_CAN = "74d7de6e3d23a05e6519bce57a693dc3517177b885ad56c9be85a73ac54ea952";
    private static final String PI_SIGNAL = "82a58e74171fc7b2afacdcabe6b9a821a8eb989cf774fe027438dc3697b874f3";
    @Test public void ota72UsesItsReviewedCanAndSignalWithoutAuthorizingOtherPackages() {
        String can = "6fb0829a3f6479974256d2f55fcf26277ba0801cf662f6e1bb0539d078281267";
        String signal = "5558340a5784093d02f7620915bed2048ded299e1a28dadd57c80370932ecd10";
        assertTrue(VoyahOemBuilds.supportsApk(CAN, can));
        assertTrue(VoyahOemBuilds.supportsApk(SIGNAL, signal));
        assertFalse(VoyahOemBuilds.supportsApk(CAN, signal));
        assertFalse(VoyahOemBuilds.supportsApk(SIGNAL, can));
        assertFalse(VoyahOemBuilds.supportsCanApk(can.substring(0, 63)));
        assertFalse(VoyahOemBuilds.hasHeatedWheel(can));
    }

    @Test public void reviewedPiFilesEnableBothServicesWithoutExchangingPackageIdentities() {
        assertTrue(VoyahOemBuilds.supportsApk(CAN, PI_CAN));
        assertTrue(VoyahOemBuilds.supportsCanApk(PI_CAN));
        assertTrue(VoyahOemBuilds.supportsApk(SIGNAL, PI_SIGNAL));
        assertFalse(VoyahOemBuilds.supportsCanApk(PI_SIGNAL));
        assertFalse(VoyahOemBuilds.supportsApk(SIGNAL, PI_CAN));
        assertFalse(VoyahOemBuilds.supportsApk(CAN, PI_SIGNAL));
    }

    @Test public void thePreviouslyReviewedRuCarRemainsSupported() {
        String can = "96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c";
        assertTrue(VoyahOemBuilds.supportsCanApk(can));
        assertTrue(VoyahOemBuilds.supportsApk(CAN, can));
        assertTrue(VoyahOemBuilds.supportsApk(SIGNAL,
                "2bb3e7df1cd672578deeb0e106da8a864adff7d9431784a9a27cbcb8e189f887"));
    }

    @Test public void unknownTruncatedOrChangedBuildsStillFailClosed() {
        for (String hash : new String[]{null, "", "74d7de6e", PI_CAN.substring(1),
                "0" + PI_CAN.substring(1), PI_CAN + "\n", "c245e689da80b8842c07f367362109ad4b12fe74642bcfe364d151bef9262040"}) {
            assertFalse(VoyahOemBuilds.supportsCanApk(hash));
            assertFalse(VoyahOemBuilds.supportsApk(CAN, hash));
            assertFalse(VoyahOemBuilds.supportsApk(SIGNAL, hash));
        }
        for (String pkg : new String[]{null, "", "com.qinggan.canbus.service.extra", "com.other.app"})
            assertFalse(VoyahOemBuilds.supportsApk(pkg, PI_CAN));
    }
}
