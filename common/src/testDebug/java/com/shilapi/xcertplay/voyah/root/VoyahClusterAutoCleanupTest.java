package com.shilapi.xcertplay.voyah.root;

import java.lang.reflect.Field;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class VoyahClusterAutoCleanupTest {
    @Test public void aLateAutomaticStopCannotCloseAManualProbe() throws Exception {
        Field field = ClusterProbeRoot.class.getDeclaredField("lease");
        field.setAccessible(true);
        Object old = field.get(null);
        try {
            for (String mode : new String[]{"grid", "map"}) {
                JSONObject manual = new JSONObject().put("mode", mode).put("token", "manual");
                field.set(null, manual);
                assertTrue(ClusterProbeRoot.stopAuto().successful());
                assertSame(manual, field.get(null));
            }
            field.set(null, null);
            assertTrue(ClusterProbeRoot.stopAuto().successful());
        } finally { field.set(null, old); }
    }
}
