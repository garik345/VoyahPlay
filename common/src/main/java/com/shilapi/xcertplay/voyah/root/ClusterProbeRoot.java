package com.shilapi.xcertplay.voyah.root;

import android.app.ActivityManager;
import android.os.Build;
import android.os.SystemClock;
import com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Fixed media-card task: one-minute tests or an automatic map with a short renewable lease. */
@android.annotation.TargetApi(30)
final class ClusterProbeRoot {
    static final String CAPABILITY = "VOYAH_CLUSTER_PROBE_1";
    static final String AUTO_CAPABILITY = "VOYAH_CLUSTER_AUTO_1";
    private static final File FILE = new File(RootHelperProtocol.DIRECTORY, "cluster-probe.json");
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "VoyahClusterProbeExpiry"); t.setDaemon(true); return t;
    });
    private static JSONObject lease;
    private static String last = "idle";
    private ClusterProbeRoot() {}

    static synchronized void initialize() {
        // A helper restart closes an interrupted probe; it never reopens a diagnostic screen.
        try {
            if (FILE.exists()) {
                RootHelperOperations.privatePath(FILE, false);
                if (FILE.length() > 4096) throw new IOException("Invalid probe lease");
                lease = new JSONObject(new String(java.nio.file.Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8));
                stop();
            }
        } catch (Exception error) { last = "recovery: " + error; }
        try { publishAutoBounds(false); } catch (Exception error) { last = "auto bounds recovery: " + error; }
        TIMER.scheduleWithFixedDelay(() -> {
            synchronized (ClusterProbeRoot.class) {
                if (lease != null && SystemClock.elapsedRealtime() >= lease.optLong("deadline")) {
                    try { stop(); } catch (Exception error) { last = "expiry: " + error; }
                }
            }
        }, 1, 1, TimeUnit.SECONDS);
    }

    static String brief() { return "CLUSTER_PROBE_CAPABILITY=" + CAPABILITY + "\nCLUSTER_AUTO_CAPABILITY=" + AUTO_CAPABILITY + "\n"; }

    private static void publishAutoBounds(boolean active) throws Exception {
        String value = active ? "1|" + lease.getString("token") + "|" + lease.getInt("display") + "|" + lease.getLong("deadline") : "0";
        RootProcess.Result result = RootProcess.run(3, "/system/bin/settings", "put", "global", "voyahplay_cluster_auto_lease", value);
        if (!result.successful()) throw new IOException("Automatic map bounds lease failed: " + result.output);
    }

    private static String displays() throws Exception {
        if (Build.VERSION.SDK_INT != 30) throw new IOException("Cluster probe requires the reviewed Android 11 display contract");
        // Fixed filter bounds the otherwise large dumpsys report before RootProcess captures it.
        RootProcess.Result result = RootProcess.run(4, "/system/bin/sh", "-c",
            "/system/bin/dumpsys display | /system/bin/grep -E 'm(Base)?DisplayInfo=DisplayInfo'");
        if (!result.successful()) throw new IOException("Display discovery failed: " + result.output);
        return result.output;
    }

    private static Object service() throws Exception {
        return Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null);
    }

    @SuppressWarnings("unchecked")
    private static List<ActivityManager.RunningTaskInfo> tasks(Object service) throws Exception {
        if (Build.VERSION.SDK_INT != 30) throw new IOException("Unreviewed task inspection API");
        for (Method method : service.getClass().getMethods()) {
            Class<?>[] types = method.getParameterTypes();
            if (!method.getName().equals("getTasks") || types.length < 1 || types[0] != int.class
                    || !List.class.isAssignableFrom(method.getReturnType())) continue;
            Object[] args = new Object[types.length]; args[0] = 100;
            boolean supported = true;
            for (int i = 1; i < types.length; i++) { if (types[i] != boolean.class) supported = false; args[i] = false; }
            if (supported) {
                method.setAccessible(true);
                List<ActivityManager.RunningTaskInfo> result = (List<ActivityManager.RunningTaskInfo>) method.invoke(service, args);
                if (result == null) throw new IOException("Task inspection returned no list");
                return result;
            }
        }
        throw new IOException("Reviewed task inspection API unavailable");
    }

    private static int field(ActivityManager.RunningTaskInfo task, String name) throws Exception {
        return task.getClass().getField(name).getInt(task);
    }
    private static boolean own(ActivityManager.RunningTaskInfo task, int display) throws Exception {
        return VoyahClusterProbeSpec.ownsTask(task.baseActivity == null ? null : task.baseActivity.getClassName(),
            task.topActivity == null ? null : task.topActivity.getClassName(), field(task, "userId"), field(task, "displayId"), display)
            && RootHelperProtocol.PACKAGE.equals(task.baseActivity.getPackageName())
            && RootHelperProtocol.PACKAGE.equals(task.topActivity.getPackageName());
    }
    static int uniqueOwnTaskId(List<ActivityManager.RunningTaskInfo> tasks, int display) throws Exception {
        Integer found = null;
        for (ActivityManager.RunningTaskInfo task : tasks) {
            if (!own(task, display)) continue;
            if (found != null) throw new IOException("Multiple probe tasks after root launch");
            found = task.taskId;
        }
        if (found == null) throw new IOException("Probe task missing after root launch");
        return found;
    }
    static boolean ownsLeaseTask(ActivityManager.RunningTaskInfo task, int display, JSONObject active) throws Exception {
        // Android 11 getTasks() strips Intent extras. Bind the task ID discovered immediately
        // after our root launch, retaining the component, user and display checks on every tick.
        return "auto".equals(active.optString("mode")) && active.optInt("display", -1) == display &&
            active.optInt("taskId", -1) >= 0 && active.optInt("taskId", -1) == task.taskId && own(task, display);
    }
    private static void removeOurTasks(int display) throws Exception {
        Object service = service();
        Method remove = service.getClass().getMethod("removeTask", int.class);
        remove.setAccessible(true);
        for (ActivityManager.RunningTaskInfo task : tasks(service)) {
            if (!own(task, display)) continue;
            // Recheck identity immediately before removal. The main CarPlay task cannot match.
            boolean stillOwn = false;
            for (ActivityManager.RunningTaskInfo current : tasks(service))
                if (current.taskId == task.taskId && own(current, display)) stillOwn = true;
            if (stillOwn && Boolean.FALSE.equals(remove.invoke(service, task.taskId)))
                throw new IOException("Probe task removal rejected: " + task.taskId);
        }
    }

    static synchronized RootProcess.Result status() throws Exception {
        RootHelperOperations.verifyHome();
        String dump = displays();
        Integer target = VoyahClusterProbeSpec.targetDisplay(dump);
        return new RootProcess.Result(0, false, summary(target) + dump);
    }
    private static String summary(Integer target) throws Exception {
        return brief() + "CLUSTER_PROBE_TARGET=" + (target == null ? "none" : target)
            + "\nCLUSTER_PROBE_ACTIVE=" + (lease == null ? 0 : 1) + "\nCLUSTER_PROBE_NOTE=" + last + "\n"
            + (lease == null ? "" : "CLUSTER_PROBE_TOKEN=" + lease.getString("token") + "\nCLUSTER_PROBE_MODE=" + lease.getString("mode")
                + "\nCLUSTER_PROBE_DISPLAY=" + lease.getInt("display") + "\nCLUSTER_PROBE_DEADLINE=" + lease.getLong("deadline") + "\n")
            ;
    }

    static synchronized RootProcess.Result start(String mode) throws Exception {
        RootHelperOperations.verifyHome();
        String dump = displays();
        Integer target = VoyahClusterProbeSpec.targetDisplay(dump);
        if (target == null) throw new IOException("No unique reviewed Cluster-Media-Display; probe not launched");
        if (lease != null) stop();
        // Prove that our exact task can be retired before creating a new diagnostic window.
        removeOurTasks(target);
        String token = UUID.randomUUID().toString();
        long deadline = SystemClock.elapsedRealtime() + ("auto".equals(mode) ? VoyahClusterProbeSpec.AUTO_LEASE_MS : VoyahClusterProbeSpec.DURATION_MS);
        if (!VoyahClusterProbeSpec.validLease(token, mode, target, deadline, SystemClock.elapsedRealtime()))
            throw new IOException("Invalid probe lease");
        lease = new JSONObject().put("token", token).put("mode", mode).put("display", target).put("deadline", deadline);
        RootHelperBoot.writePrivate(FILE.getName(), lease.toString().getBytes(StandardCharsets.UTF_8));
        if ("auto".equals(mode)) publishAutoBounds(true);
        RootProcess.Result launched = RootProcess.run(5, "/system/bin/am", "start-activity", "--user", "0", "--display", target.toString(),
            "-f", "0x18000000", "-n", RootHelperProtocol.PACKAGE + "/" + VoyahClusterProbeSpec.ACTIVITY,
            "--es", VoyahClusterProbeSpec.TOKEN, token, "--es", VoyahClusterProbeSpec.MODE, mode,
            "--el", VoyahClusterProbeSpec.DEADLINE, Long.toString(deadline));
        last = "launch requested; actual Activity display and pixels require confirmation";
        if (!launched.successful() || launched.output.matches("(?si).*\\b(error|exception|denied)\\b.*")) {
            stop(); return new RootProcess.Result(1, launched.timedOut, launched.output);
        }
        if ("auto".equals(mode)) {
            try {
                lease.put("taskId", uniqueOwnTaskId(tasks(service()), target));
                RootHelperBoot.writePrivate(FILE.getName(), lease.toString().getBytes(StandardCharsets.UTF_8));
            } catch (Exception error) { stop(); throw error; }
        }
        return new RootProcess.Result(0, false, summary(target) + launched.output + dump);
    }

    static synchronized RootProcess.Result autoTick() throws Exception {
        RootHelperOperations.verifyHome();
        if (lease != null && !"auto".equals(lease.optString("mode")))
            return new RootProcess.Result(1, false, "CLUSTER_AUTO_MANUAL_ACTIVE=1\n");
        // Do not create a 0x0 window while the boot-time bounds agent is still coming up.
        TweaksBoundsSnapshot bridge = TweaksBoundsRoot.snapshot();
        RootProcess.Result agent = RootProcess.run(2, "/system/bin/settings", "get", "global", TopBoundsRoot.PROBE);
        if (!agent.successful() || !TopBoundsRoot.ready(agent.output, SystemClock.elapsedRealtime(), bridge.pid, false))
            throw new IOException("CLUSTER_AUTO_BOUNDS_NOT_READY: waiting for the reviewed bounds agent");
        Integer target = VoyahClusterProbeSpec.targetDisplay(displays());
        if (target == null) { stopAuto(); throw new IOException("No unique reviewed Cluster-Media-Display"); }
        long now = SystemClock.elapsedRealtime();
        if (lease != null && lease.optInt("display") == target && lease.optLong("deadline") > now) {
            int matches = 0;
            for (ActivityManager.RunningTaskInfo task : tasks(service())) {
                if (ownsLeaseTask(task, target, lease)) matches++;
            }
            if (matches == 1) {
                lease.put("deadline", now + VoyahClusterProbeSpec.AUTO_LEASE_MS);
                RootHelperBoot.writePrivate(FILE.getName(), lease.toString().getBytes(StandardCharsets.UTF_8));
                publishAutoBounds(true);
                last = "automatic map lease renewed";
                return new RootProcess.Result(0, false, summary(target));
            }
        }
        return start("auto");
    }

    static synchronized RootProcess.Result stopAuto() throws Exception {
        // A late disconnect/renew callback must never close a newer manual calibration test.
        if (lease != null && "auto".equals(lease.optString("mode"))) return stop();
        return new RootProcess.Result(0, false, "CLUSTER_AUTO_STOPPED=1\n");
    }

    static synchronized RootProcess.Result stop() throws Exception {
        RootHelperOperations.verifyHome();
        publishAutoBounds(false);
        if (lease != null) {
            int target = lease.optInt("display", -1);
            if (target <= 0) throw new IOException("Invalid stored probe target");
            removeOurTasks(target);
        }
        if (FILE.exists()) { RootHelperOperations.privatePath(FILE, false); if (!FILE.delete()) throw new IOException("Probe lease cleanup failed"); }
        lease = null; last = "stopped";
        return new RootProcess.Result(0, false, "CLUSTER_PROBE_ACTIVE=0\nCLUSTER_PROBE_STOPPED=1\n");
    }
}
