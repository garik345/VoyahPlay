package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import org.junit.Test;
import static org.junit.Assert.*;

public class ClusterNaviPresentationTest {
    private static final String FULL = ClusterNaviTestState.FULL;
    private static final String CONCERN = ClusterNaviPresentation.CONCERN;
    private void rejected(ClusterNaviPresentation p, String category, long now, String reason) throws Exception {
        try { p.requireGrant(category, now); fail("Unexpected permission"); }
        catch (IOException expected) { assertTrue(expected.getMessage(), expected.getMessage().contains("CLUSTER_NAVI_PRESENTATION=" + reason)); }
    }
    @Test public void onlyTheRequestedPresentationCallbackGrantsTheArea() throws Exception {
        ClusterNaviPresentation p = new ClusterNaviPresentation();
        p.receive(CONCERN, FULL, 1); p.begin(FULL, 1000, 61000);
        p.receive(CONCERN, "qg.car.cluster.MEDIA_SMALL", 1);
        p.receive("CONCERN_ACTIVITY_STATE", FULL, 1);
        p.receive(CONCERN, FULL, 0); p.receive(CONCERN, FULL, 255);
        rejected(p, FULL, 1001, "pending");
        p.receive(CONCERN, FULL, 1); p.requireGrant(FULL, 1002);
        rejected(p, "qg.car.cluster.MEDIA_SMALL", 1002, "not_requested");
    }
    @Test public void aCloseIsTerminalEvenIfAnOpenArrivesLater() throws Exception {
        ClusterNaviPresentation p = new ClusterNaviPresentation(); p.begin(FULL, 1000, 61000);
        p.receive(CONCERN, FULL, 2); p.receive(CONCERN, FULL, 1);
        rejected(p, FULL, 1001, "closed");
    }
    @Test public void aGrantCanBeRevokedBeforeFrameConfirmation() throws Exception {
        ClusterNaviPresentation p = new ClusterNaviPresentation(); p.begin(FULL, 1000, 61000);
        p.receive(CONCERN, FULL, 1); p.requireGrant(FULL, 1001);
        p.receive(CONCERN, FULL, 2); rejected(p, FULL, 1002, "closed");
    }
    @Test public void requestTimeoutAndLeaseExpiryBothBoundConfirmation() throws Exception {
        ClusterNaviPresentation p = new ClusterNaviPresentation(); p.begin(FULL, 1000, 61000);
        rejected(p, FULL, 2500, "timeout");
        p.begin(FULL, 1000, 1100); p.receive(CONCERN, FULL, 1);
        rejected(p, FULL, 1100, "timeout");
        try { p.begin(FULL, 1100, 1100); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("expired")); }
    }
    @Test public void cancellingAndStartingANewRequestCannotReuseAnOldGrant() throws Exception {
        ClusterNaviPresentation p = new ClusterNaviPresentation(); p.begin(FULL, 1000, 61000);
        p.receive(CONCERN, FULL, 1); p.cancel(); p.receive(CONCERN, FULL, 1);
        rejected(p, FULL, 1002, "not_requested");
        p.begin(FULL, 1003, 61000); rejected(p, FULL, 1004, "pending");
    }
    @Test public void returningToMediaNeedsItsOwnMatchingGrant() throws Exception {
        String media = "qg.car.cluster.MEDIA_SMALL";
        ClusterNaviPresentation p = new ClusterNaviPresentation(); p.begin(media, 1000, 2000);
        p.receive(CONCERN, FULL, 1); rejected(p, media, 1001, "pending");
        p.receive(CONCERN, media, 1); p.awaitGrant(() -> 1002);
    }
}
