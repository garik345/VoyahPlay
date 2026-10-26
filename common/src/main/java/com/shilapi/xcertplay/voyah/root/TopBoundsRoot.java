package com.shilapi.xcertplay.voyah.root;

import android.os.SystemClock;
import org.json.JSONObject;
import java.io.IOException;

/** A separate expiring top-edge policy; the existing Tweaks readiness checks remain mandatory. */
final class TopBoundsRoot {
    static final String KEY = "voyahplay_top_bounds_lease";
    static final String PROBE = "voyahplay_top_bounds_probe";
    static final String STATUS_PROBE = "voyahplay_status_bar_probe";
    private TopBoundsRoot() {}

    static boolean ready(String raw, long now, int systemPid, boolean active) {
        try {
            if (raw == null || raw.length() > 4096) return false;
            JSONObject value = new JSONObject(raw);
            long sampled = value.getLong("now_ms");
            return "1.0".equals(value.getString("version")) && value.getInt("pid") == systemPid &&
                    sampled >= 0 && sampled <= now && now - sampled <= 2500 && value.getString("error").isEmpty() &&
                    (!active || value.getBoolean("active"));
        } catch (Exception error) { return false; }
    }

    static RootProcess.Result policy(boolean active) throws Exception {
        if (!active) {
            RootProcess.Result result = RootProcess.run(3, "/system/bin/settings", "delete", "global", KEY);
            return new RootProcess.Result(result.exitCode, result.timedOut, "TOP_LEASE=0\n" + result.output);
        }
        TweaksBoundsSnapshot base = TweaksBoundsRoot.snapshot();
        if (!base.leaseActive) throw new IOException("TOP_BOUNDS: first obtain the verified width lease");
        RootProcess.Result ui = RootProcess.run(2, "/system/bin/pidof", "com.qinggan.systemui");
        if (!ui.successful() || !ui.output.trim().matches("[1-9][0-9]{0,8}"))
            throw new IOException("STATUS_BAR: cannot identify SystemUI");
        int uiPid = Integer.parseInt(ui.output.trim());
        long deadline = SystemClock.elapsedRealtime() + 15000;
        RootProcess.Result result = RootProcess.run(3, "/system/bin/settings", "put", "global", KEY, "1|" + deadline);
        if (!result.successful()) return result;
        boolean observed = false, statusReady = false;
        for (int attempt = 0; attempt < 4; attempt++) {
            RootProcess.Result probe = RootProcess.run(1, "/system/bin/settings", "get", "global", PROBE);
            RootProcess.Result status = RootProcess.run(1, "/system/bin/settings", "get", "global", STATUS_PROBE);
            long now = SystemClock.elapsedRealtime();
            statusReady = status.successful() && ready(status.output, now, uiPid, true);
            if (probe.successful() && ready(probe.output, now, base.pid, true) && statusReady) { observed = true; break; }
            Thread.sleep(250);
        }
        return new RootProcess.Result(observed ? 0 : 1, false,
                "TOP_BOUNDS_READY=" + (observed ? 1 : 0) + "\nSTATUS_BAR_READY=" + (statusReady ? 1 : 0) + "\nTOP_LEASE=1\nLEASE_UNTIL_MS=" + deadline + "\n" +
                (observed ? "" : "Top-edge agent has not acknowledged the lease. Check the helper supervisor.\n"));
    }
}
