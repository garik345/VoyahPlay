package com.shilapi.xcertplay.voyah.root;

import android.os.SystemClock;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class VoyahFeatureAccessTest {
    public static void allowAll() { state(true, true, true); }
    public static void state(boolean helper, boolean bounds, boolean map) {
        ReflectionHelpers.setStaticField(VoyahFeatureAccess.class, "evidence",
                new VoyahFeatureAccess.Evidence(helper, bounds, map, SystemClock.elapsedRealtime(), "test"));
    }
    @After public void reset() { state(false, false, false); }
    @Test public void preferencesDoNotGrantFeaturesWithoutVerifiedHelper() {
        VoyahFeatureAccess.Evidence value = new VoyahFeatureAccess.Evidence(false, true, true, 10, "wrong UID");
        for (VoyahFeatureAccess.Feature feature : VoyahFeatureAccess.Feature.values()) assertFalse(value.allows(feature, 11));
    }
    @Test public void ultraOnlyNeedsTheHelperWhileMapAndBarsNeedTheBridge() {
        VoyahFeatureAccess.Evidence value = new VoyahFeatureAccess.Evidence(true, false, true, 10, "missing bridge");
        assertTrue(value.allows(VoyahFeatureAccess.Feature.ULTRA, 11));
        assertFalse(value.allows(VoyahFeatureAccess.Feature.FULLSCREEN, 11));
        assertFalse(value.allows(VoyahFeatureAccess.Feature.MAP, 11));
    }
    @Test public void unknownClusterDoesNotDisableVerifiedFullscreen() {
        VoyahFeatureAccess.Evidence value = new VoyahFeatureAccess.Evidence(true, true, false, 10, "unknown cluster");
        assertTrue(value.allows(VoyahFeatureAccess.Feature.FULLSCREEN, 11));
        assertFalse(value.allows(VoyahFeatureAccess.Feature.MAP, 11));
    }
    @Test public void expiredOrFutureEvidenceCannotEnableAnyFeature() {
        VoyahFeatureAccess.Evidence value = new VoyahFeatureAccess.Evidence(true, true, true, 10, "ready");
        for (VoyahFeatureAccess.Feature feature : VoyahFeatureAccess.Feature.values()) {
            assertFalse(value.allows(feature, 9));
            assertFalse(value.allows(feature, 10 + VoyahFeatureAccess.EXPIRES_MS + 1));
            assertTrue(value.allows(feature, 11));
        }
    }
    @Test public void savedFlagsCannotBypassLossOfReadiness() {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        allowAll();
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveHideTopBar(app, true);
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveHideBottomBar(app, true);
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveClusterMapEnabled(app, true);
        com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings.INSTANCE.saveEnabled(app, true);
        assertTrue(com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.loadClusterMapEnabled(app));
        state(false, false, false);
        assertFalse(com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.loadHideTopBar(app));
        assertFalse(com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.loadHideBottomBar(app));
        assertFalse(com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.loadClusterMapEnabled(app));
        assertFalse(com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings.INSTANCE.isEnabled(app));
        // Readiness recovery retains the driver choices; it does not reset preferences or reconnect.
        allowAll();
        assertTrue(com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.loadClusterMapEnabled(app));
        assertTrue(com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings.INSTANCE.isEnabled(app));
    }
    @Test public void deadMissingOrStaleWindowAgentsBlockBothBars() {
        String top = "{\"version\":\"1.0\",\"pid\":101,\"now_ms\":1000,\"active\":false,\"error\":\"\"}";
        String ui = top.replace("101", "102");
        String dock = "{\"version\":\"1.1\",\"pid\":103,\"now_ms\":1000,\"installed\":true,\"error\":\"\"}";
        assertTrue(FullscreenReadiness.ready(top, ui, dock, 1100, 101, 102, 103));
        assertFalse(FullscreenReadiness.ready(top, "null", dock, 1100, 101, 102, 103));
        assertFalse(FullscreenReadiness.ready(top, ui, dock, 4000, 101, 102, 103));
        assertFalse(FullscreenReadiness.ready(top, ui, dock, 1100, 101, 999, 103));
        assertFalse(FullscreenReadiness.ready(top, ui, dock.replace("true", "false"), 1100, 101, 102, 103));
        assertFalse(FullscreenReadiness.ready(top, ui, dock.replace("\"error\":\"\"", "\"error\":\"hook failed\""), 1100, 101, 102, 103));
    }
    @Test public void blockedEnableDoesNotSaveAChoiceThatActivatesLater() {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveClusterMapEnabled(app, false);
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveHideTopBar(app, false);
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveHideBottomBar(app, false);
        com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings.INSTANCE.saveEnabled(app, false);
        state(false, false, false);
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveClusterMapEnabled(app, true);
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveHideTopBar(app, true);
        com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.saveHideBottomBar(app, true);
        com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings.INSTANCE.saveEnabled(app, true);
        allowAll();
        assertFalse(com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.loadClusterMapEnabled(app));
        assertFalse(com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.loadHideTopBar(app));
        assertFalse(com.shilapi.xcertplay.AirPlayPersistence.INSTANCE.loadHideBottomBar(app));
        assertFalse(com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings.INSTANCE.isEnabled(app));
    }
    @Test public void absentRadioEquipmentOverridesAnOldSavedRadioOptIn() {
        android.content.Context app = org.robolectric.RuntimeEnvironment.getApplication();
        app.getSharedPreferences("voyah_ultra_pilot", 0).edit().putBoolean("radioEnabled", true).commit();
        allowAll();
        assertFalse(com.shilapi.xcertplay.voyah.ultra.VoyahUltraSettings.INSTANCE.isRadioEnabled(app));
    }
}
