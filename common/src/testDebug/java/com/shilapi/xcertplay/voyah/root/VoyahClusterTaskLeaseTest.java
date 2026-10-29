package com.shilapi.xcertplay.voyah.root;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Intent;
import com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30, manifest = Config.NONE)
public class VoyahClusterTaskLeaseTest {
    private ActivityManager.RunningTaskInfo task(int id) throws Exception {
        ActivityManager.RunningTaskInfo task = new ActivityManager.RunningTaskInfo();
        task.taskId = id;
        task.getClass().getField("userId").setInt(task, 0);
        task.getClass().getField("displayId").setInt(task, 5);
        task.baseActivity = new ComponentName(RootHelperProtocol.PACKAGE, VoyahClusterProbeSpec.ACTIVITY);
        task.topActivity = task.baseActivity;
        // Actual Android 11 RunningTaskInfo uses Intent.cloneFilter(), which drops extras.
        task.baseIntent = new Intent().setComponent(task.baseActivity);
        return task;
    }
    private JSONObject lease() throws Exception {
        return new JSONObject().put("mode", "auto").put("display", 5).put("taskId", 74);
    }
    @Test public void bindsTheUniqueRootLaunchedTaskAndRenewsWithoutIntentExtras() throws Exception {
        ActivityManager.RunningTaskInfo task = task(74);
        assertNull(task.baseIntent.getExtras());
        assertEquals(74, ClusterProbeRoot.uniqueOwnTaskId(Collections.singletonList(task), 5));
        assertTrue(ClusterProbeRoot.ownsLeaseTask(task, 5, lease()));
    }
    @Test public void refusesAnotherTaskDisplayUserComponentOrMode() throws Exception {
        assertFalse(ClusterProbeRoot.ownsLeaseTask(task(75), 5, lease()));
        assertFalse(ClusterProbeRoot.ownsLeaseTask(task(74), 6, lease()));
        assertFalse(ClusterProbeRoot.ownsLeaseTask(task(74), 5, lease().put("mode", "map")));
        JSONObject missingTask = lease(); missingTask.remove("taskId");
        assertFalse(ClusterProbeRoot.ownsLeaseTask(task(74), 5, missingTask));
        ActivityManager.RunningTaskInfo foreignUser = task(74);
        foreignUser.getClass().getField("userId").setInt(foreignUser, 10);
        assertFalse(ClusterProbeRoot.ownsLeaseTask(foreignUser, 5, lease()));
        ActivityManager.RunningTaskInfo host = task(74);
        host.topActivity = new ComponentName(RootHelperProtocol.PACKAGE, "com.shilapi.xcertplay.CarPlayHostActivity");
        assertFalse(ClusterProbeRoot.ownsLeaseTask(host, 5, lease()));
        ActivityManager.RunningTaskInfo foreignPackage = task(74);
        foreignPackage.baseActivity = new ComponentName("another.package", VoyahClusterProbeSpec.ACTIVITY);
        assertFalse(ClusterProbeRoot.ownsLeaseTask(foreignPackage, 5, lease()));
    }
    @Test public void launchBindingRequiresExactlyOneReviewedTask() throws Exception {
        try { ClusterProbeRoot.uniqueOwnTaskId(Collections.emptyList(), 5); fail("Missing task accepted"); }
        catch (IOException expected) { }
        try { ClusterProbeRoot.uniqueOwnTaskId(Arrays.asList(task(74), task(75)), 5); fail("Ambiguous tasks accepted"); }
        catch (IOException expected) { }
    }
}
