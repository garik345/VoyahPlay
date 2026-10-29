package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class TweaksFirmwareProfileTest {
    private String verify(String services, String bridge, String injector, String ui) throws IOException {
        return TweaksFirmwareProfile.verify(services, bridge, injector, ui);
    }
    @Test public void acceptsReviewedRuAndPiWithTheSameExtension() throws Exception {
        assertEquals("RU", verify(TweaksFirmwareProfile.RU_SERVICES, TweaksFirmwareProfile.BRIDGE,
                TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.RU_UI));
        assertEquals("PI", verify(TweaksFirmwareProfile.PI_SERVICES, TweaksFirmwareProfile.BRIDGE,
                TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.PI_UI));
        assertEquals("PI-OTA72", verify(TweaksFirmwareProfile.PI72_SERVICES, TweaksFirmwareProfile.BRIDGE,
                TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.PI72_UI));
    }
    @Test public void originalBridgeNeedsPreparationRatherThanBlindAcceptance() throws Exception {
        try {
            verify(TweaksFirmwareProfile.PI_SERVICES, TweaksFirmwareProfile.ORIGINAL_BRIDGE,
                    TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.PI_UI);
            fail("Original bridge has no 1.1 protocol");
        } catch (IOException expected) { assertTrue(expected.getMessage().startsWith("TWEAKS_BRIDGE_UPGRADE_REQUIRED:")); }
    }
    @Test public void rejectsUnknownFilesAndMixedFirmwarePairs() throws Exception {
        String[] valid = {TweaksFirmwareProfile.PI_SERVICES, TweaksFirmwareProfile.BRIDGE,
                TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.PI_UI};
        for (int i = 0; i < valid.length; i++) {
            String[] bad = valid.clone(); bad[i] = "unreviewed";
            assertRejected(bad);
        }
        assertRejected(new String[]{TweaksFirmwareProfile.PI_SERVICES, TweaksFirmwareProfile.BRIDGE,
                TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.RU_UI});
        assertRejected(new String[]{TweaksFirmwareProfile.RU_SERVICES, TweaksFirmwareProfile.BRIDGE,
                TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.PI_UI});
        assertRejected(new String[]{TweaksFirmwareProfile.PI72_SERVICES, TweaksFirmwareProfile.BRIDGE,
                TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.PI_UI});
        assertRejected(new String[]{TweaksFirmwareProfile.PI_SERVICES, TweaksFirmwareProfile.BRIDGE,
                TweaksFirmwareProfile.INJECTOR, TweaksFirmwareProfile.PI72_UI});
    }
    private void assertRejected(String[] hashes) throws Exception {
        try { verify(hashes[0], hashes[1], hashes[2], hashes[3]); fail("Unreviewed combination accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().startsWith("TWEAKS_FILES_CHANGED:")); }
    }
}
