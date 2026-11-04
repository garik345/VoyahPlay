package com.shilapi.xcertplay.voyah.root;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Process-local evidence from the UID-bound root socket. Preferences never grant a capability. */
public final class VoyahFeatureAccess {
    public enum Feature { ULTRA, FULLSCREEN, MAP }
    static final long REFRESH_MS = 15000, EXPIRES_MS = 45000;
    static final class Evidence {
        final boolean helper, bounds, map;
        final long sampled;
        final String report;
        Evidence(boolean helper, boolean bounds, boolean map, long sampled, String report) {
            this.helper = helper; this.bounds = helper && bounds; this.map = helper && bounds && map;
            this.sampled = sampled; this.report = report;
        }
        boolean allows(Feature feature, long now) {
            if (sampled < 0 || now < sampled || now - sampled > EXPIRES_MS) return false;
            return feature == Feature.ULTRA ? helper : feature == Feature.FULLSCREEN ? bounds : map;
        }
    }
    interface Probe { Evidence read(); }
    static Probe probe = VoyahFeatureAccess::read;
    static Executor work = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VoyahFeatureAccess"); t.setDaemon(true); return t;
    });
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile Evidence evidence = new Evidence(false, false, false, -1, "Not checked");
    private static final List<Runnable> waiting = new ArrayList<>(); // main thread
    private static boolean checking;
    private static android.content.Context app;
    private VoyahFeatureAccess() {}
    public static void attach(android.content.Context context) { app = context.getApplicationContext(); refresh(null); }
    public static boolean allows(Feature feature) { return evidence.allows(feature, SystemClock.elapsedRealtime()); }
    public static boolean fresh() {
        long age = SystemClock.elapsedRealtime() - evidence.sampled;
        return evidence.sampled >= 0 && age >= 0 && age < REFRESH_MS;
    }
    public static String report() { return evidence.report; }
    /** Checks are coalesced, bounded and off the UI thread. Never reconnects a running session. */
    public static void refresh(Runnable complete) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> refresh(complete)); return; }
        if (fresh()) { if (complete != null) complete.run(); return; }
        if (complete != null) waiting.add(complete);
        if (checking) return;
        checking = true;
        work.execute(() -> {
            Evidence next;
            try { next = probe.read(); }
            catch (Exception error) { next = new Evidence(false, false, false, SystemClock.elapsedRealtime(), error.toString()); }
            Evidence result = next;
            MAIN.post(() -> {
                evidence = result; checking = false;
                List<Runnable> callbacks = new ArrayList<>(waiting); waiting.clear();
                for (Runnable callback : callbacks) callback.run();
            });
        });
    }
    private static Evidence read() {
        boolean helper = false, bounds = false, map = false;
        StringBuilder report = new StringBuilder();
        try {
            RootProcess.Result info = RootHelperClient.call(RootHelperProtocol.Action.INFO, 5);
            RootHelperStatus status = new RootHelperStatus(info, android.os.Process.myUid());
            report.append(status.report()); status.requireCompatible(); helper = true;
            RootProcess.Result full = RootHelperClient.call(RootHelperProtocol.Action.TWEAKS_STATUS, 8);
            report.append('\n').append(full.output);
            bounds = full.successful() && "1".equals(RootHelperStatus.field(full.output, "TWEAKS_BOUNDS_READY"))
                    && "1.1".equals(RootHelperStatus.field(full.output, "BRIDGE_VERSION"))
                    && "1".equals(RootHelperStatus.field(full.output, "FULLSCREEN_PREREQUISITES_READY"));
            if (bounds) {
                status.requireClusterAuto();
                RootProcess.Result cluster = RootHelperClient.call(RootHelperProtocol.Action.CLUSTER_STATUS, 5);
                report.append('\n').append(cluster.output);
                String target = RootHelperStatus.field(cluster.output, "CLUSTER_PROBE_TARGET");
                if (app == null) throw new java.io.IOException("Application not attached");
                com.shilapi.xcertplay.hud.VoyahClusterCompatibility.INSTANCE.verify(app);
                map = cluster.successful() && target != null && target.matches("[0-9]+");
            }
        } catch (Exception error) { report.append('\n').append(error); }
        return new Evidence(helper, bounds, map, SystemClock.elapsedRealtime(), report.toString());
    }
}
