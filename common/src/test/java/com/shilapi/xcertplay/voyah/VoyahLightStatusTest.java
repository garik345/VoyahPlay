package com.shilapi.xcertplay.voyah;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class VoyahLightStatusTest {
    @Test public void manualModesUseFeedbackNotCommandPulses() {
        assertEquals(0, VoyahLightStatus.decodeMode(0, 0, 0));
        assertEquals(1, VoyahLightStatus.decodeMode(0, 0, 1));
        assertEquals(2, VoyahLightStatus.decodeMode(0, 1, 1));
        assertEquals(2, VoyahLightStatus.decodeMode(0, 1, 0));
    }
    @Test public void automaticModeWinsInDaylightAndDarkness() {
        assertEquals(3, VoyahLightStatus.decodeMode(1, 0, 0));
        assertEquals(3, VoyahLightStatus.decodeMode(1, 1, 1));
    }
    @Test public void unknownIsNeverReportedAsOff() {
        assertEquals(-1, VoyahLightStatus.decodeMode(-1, 0, 0));
        assertEquals(-1, VoyahLightStatus.decodeMode(2, 0, 0));
        assertEquals(-1, VoyahLightStatus.decodeMode(0, -1, 0));
        assertEquals(-1, VoyahLightStatus.decodeMode(0, 0, -1));
        assertEquals(-1, VoyahLightStatus.decodeMode(0, 2, 0));
        assertEquals(-1, VoyahLightStatus.decodeMode(0, 0, 2));
    }
}
