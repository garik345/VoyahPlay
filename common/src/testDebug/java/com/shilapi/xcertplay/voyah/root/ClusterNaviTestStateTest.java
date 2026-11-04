package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterNaviTestStateTest {
    @Test public void arUsesModeThreeAndRestoresThePreviousFullModeAfterDenial() throws Exception {
        Fake f = new Fake(); f.mode = 2; f.deny = true;
        try { ClusterNaviTestState.begin(f, () -> f.events.add("saved"), 3); fail(); } catch (IOException expected) { }
        assertEquals(Arrays.asList("saved", "mode=3", "ready=true", "request=qg.car.cluster.NAVI_AR"), f.events);
        f.deny = false; ClusterNaviTestState.restore(f, 2, false, "qg.car.cluster.MEDIA_SMALL", 3);
        assertEquals(2, f.mode); assertFalse(f.ready);
        assertTrue(f.events.contains("hide=qg.car.cluster.NAVI_AR"));
        assertFalse(f.events.stream().anyMatch(value -> value.startsWith("frame=")));
    }
    @Test public void arFirstFrameUsesItsOwnCategoryAndPreservesADriversLaterModeChange() throws Exception {
        Fake f = new Fake(); f.mode = 2;
        ClusterNaviTestState.begin(f, () -> f.events.add("saved"), 3); ClusterNaviTestState.firstFrame(f, 3);
        assertTrue(f.events.contains("frame=qg.car.cluster.NAVI_AR"));
        assertFalse(f.events.contains("request=qg.car.cluster.NAVI_FULL"));
        f.mode = 1; ClusterNaviTestState.restore(f, 2, false, "qg.car.cluster.MEDIA_SMALL", 3);
        assertEquals(1, f.mode);
    }
    @Test public void anUnsupportedTestModeCannotSaveOrChangeTheVehicle() throws Exception {
        for (int mode : new int[]{0,1,4,255}) {
            Fake f = new Fake();
            try { ClusterNaviTestState.begin(f, () -> f.events.add("saved"), mode); fail(); } catch (IOException expected) { }
            assertTrue(f.events.isEmpty());
        }
    }
    private static class Fake implements ClusterNaviTestState.Port {
        final List<String> events = new ArrayList<>();
        int mode = 1; boolean ready, failReady, failHide, deny;
        public int mode() { return mode; }
        public void mode(int value) { events.add("mode=" + value); mode = value; }
        public void ready(boolean value) throws Exception { events.add("ready=" + value); if (failReady) throw new IOException("ready unavailable"); ready = value; }
        public void show(String value) { events.add("show=" + value); }
        public void request(String value) throws Exception { events.add("request=" + value); if (deny) throw new IOException("MCU closed"); }
        public void frame(String value) { events.add("frame=" + value); }
        public void hide(String category) throws Exception { events.add("hide=" + category); if (failHide) throw new IOException("hide unavailable"); }
    }
    @Test public void durableSnapshotPrecedesEveryChangeAndRestoreReturnsTheOriginalState() throws Exception {
        Fake f = new Fake();
        ClusterNaviTestState.begin(f, () -> f.events.add("saved"), 2);
        assertEquals(Arrays.asList("saved", "mode=2", "ready=true", "request=qg.car.cluster.NAVI_FULL"), f.events);
        assertFalse(f.events.stream().anyMatch(value -> value.startsWith("frame=")));
        ClusterNaviTestState.firstFrame(f, 2);
        assertEquals(Arrays.asList("ready=true", "request=qg.car.cluster.NAVI_FULL", "frame=qg.car.cluster.NAVI_FULL"),
                f.events.subList(4, 7));
        ClusterNaviTestState.restore(f, 1, false, "qg.car.cluster.MEDIA_SMALL", 2);
        assertEquals(1, f.mode); assertFalse(f.ready);
        assertEquals("show=qg.car.cluster.MEDIA_SMALL", f.events.get(f.events.size()-1));
    }
    @Test public void closingTheAreaNeverAcknowledgesAFrameAndStillRestores() throws Exception {
        Fake f = new Fake(); f.deny = true;
        try { ClusterNaviTestState.begin(f, () -> f.events.add("saved"), 2); fail(); } catch (IOException expected) { }
        assertFalse(f.events.stream().anyMatch(value -> value.startsWith("frame=")));
        f.deny = false; ClusterNaviTestState.restore(f, 1, false, "qg.car.cluster.MEDIA_SMALL", 2);
        assertEquals(1, f.mode); assertFalse(f.ready);
    }
    @Test public void aRevokedGrantAtFirstFrameCannotBeConfirmed() throws Exception {
        Fake f = new Fake(); ClusterNaviTestState.begin(f, () -> f.events.add("saved"), 2); f.deny = true;
        try { ClusterNaviTestState.firstFrame(f, 2); fail(); } catch (IOException expected) { }
        assertFalse(f.events.stream().anyMatch(value -> value.startsWith("frame=")));
    }
    @Test public void failedSnapshotCannotChangeAnything() throws Exception {
        Fake f = new Fake();
        try { ClusterNaviTestState.begin(f, () -> { throw new IOException("journal unavailable"); }, 2); fail(); }
        catch (IOException expected) { assertTrue(f.events.isEmpty()); }
    }
    @Test public void aPartiallyAppliedStartCanStillBeRolledBack() throws Exception {
        Fake f = new Fake(); f.failReady = true;
        try { ClusterNaviTestState.begin(f, () -> f.events.add("saved"), 2); fail(); } catch (IOException expected) { }
        assertEquals("saved", f.events.get(0)); assertEquals(2, f.mode);
        f.failReady = false; ClusterNaviTestState.restore(f, 1, false, "qg.car.cluster.MEDIA_SMALL", 2);
        assertEquals(1, f.mode); assertFalse(f.ready);
    }
    @Test public void failedHideDoesNotPreventRestoringModeAndReadinessAndCanBeRetried() throws Exception {
        Fake f = new Fake(); f.mode = 2; f.ready = true; f.failHide = true;
        try { ClusterNaviTestState.restore(f, 1, false, "qg.car.cluster.MEDIA_SMALL", 2); fail(); } catch (IOException expected) { }
        assertEquals(1, f.mode); assertFalse(f.ready);
        f.failHide = false; ClusterNaviTestState.restore(f, 1, false, "qg.car.cluster.MEDIA_SMALL", 2);
    }
    @Test public void driverModeChangesDuringTheTestAreRetained() throws Exception {
        Fake f = new Fake(); f.mode = 3;
        ClusterNaviTestState.restore(f, 1, true, "qg.car.cluster.MEDIA_SMALL", 2);
        assertEquals(3, f.mode); assertTrue(f.ready);
    }
    @Test public void invalidSnapshotsNeverSendCommands() throws Exception {
        for (int mode : new int[]{0,4,255}) {
            Fake f = new Fake();
            try { ClusterNaviTestState.restore(f, mode, false, "qg.car.cluster.MEDIA_SMALL", 2); fail(); }
            catch (IOException expected) { assertTrue(f.events.isEmpty()); }
        }
        Fake f = new Fake();
        try { ClusterNaviTestState.restore(f, 1, false, "arbitrary.category", 2); fail(); }
        catch (IOException expected) { assertTrue(f.events.isEmpty()); }
    }
}
