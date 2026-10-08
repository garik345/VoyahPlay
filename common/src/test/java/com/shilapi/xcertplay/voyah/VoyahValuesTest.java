package com.shilapi.xcertplay.voyah;

import org.junit.Test;
import static org.junit.Assert.*;

public class VoyahValuesTest {
    @Test public void unknownValuesAreNotDisplayedAsMeasurements() {
        VoyahValues values = new VoyahValues(-1, Float.NaN, -9999, Float.POSITIVE_INFINITY, -1, 1000);
        assertNull(values.battery); assertNull(values.fuel);
        assertNull(values.left); assertNull(values.right); assertNull(values.fan);
        assertFalse(values.display(1001).contains("-9999"));
    }
    @Test public void observedReportValuesAreAcceptedWithoutInventingRangeOrLitres() {
        VoyahValues values = new VoyahValues(49.6f, 28, 23, 24, 3, 1000);
        assertEquals(49.6f, values.battery, 0.01f);
        assertEquals(28f, values.fuel, 0.01f);
        assertEquals(24f, values.right, 0.01f);
        assertEquals(Integer.valueOf(3), values.fan);
    }
    @Test public void oldReadTimeIsNotPresentedAsCurrent() {
        VoyahValues values = new VoyahValues(50, 28, 23, 23, 3, 1000);
        assertEquals("Данные не обновляются", values.display(7001));
        assertEquals("Данные не обновляются", values.display(999));
    }
    @Test public void openingStatesKeepUnknownAndPartialDistinct() {
        assertEquals("нет данных", VoyahValues.opening(-1, false));
        assertEquals("нет данных", VoyahValues.opening(2, false));
        assertEquals("частично открыто", VoyahValues.opening(0, true));
        assertEquals("открыто", VoyahValues.opening(1, true));
        assertEquals("закрыто", VoyahValues.opening(2, true));
        assertEquals("закрыто", VoyahValues.opening(0, false));
        assertEquals("открыто", VoyahValues.opening(1, false));
        assertEquals("нет данных", VoyahValues.opening(Integer.MIN_VALUE, true));
        int[] doors={0,1,-1,0}, windows={0,2,1,-1};
        VoyahValues values=new VoyahValues(50,28,23,23,3,1000,doors,windows);
        doors[0]=1;
        assertTrue(values.display(1001).contains("Передняя левая: закрыто"));
        assertTrue(values.display(1001).contains("Передняя левая: частично открыто"));
        assertEquals("Данные не обновляются",values.display(7001));
    }
    @Test public void unrelatedCommandsAreRejected() {
        for (int code : new int[]{1, 6, 26, 31, 32, 45, 70, 999}) {
            try { CanSchema.byCode(code); fail("Allowed unsupported command " + code); }
            catch (IllegalArgumentException expected) { }
        }
    }
}
