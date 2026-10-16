package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import org.json.JSONObject;

/** Validation of the live 1.1 bridge. Times use elapsedRealtime, including suspend. */
final class TweaksBoundsSnapshot {
    final int pid;
    final long sampledAt, leaseUntil;
    final boolean leaseActive;
    final String raw;

    private TweaksBoundsSnapshot(int pid, long sampledAt, long leaseUntil, boolean active, String raw) {
        this.pid = pid; this.sampledAt = sampledAt; this.leaseUntil = leaseUntil;
        this.leaseActive = active; this.raw = raw;
    }

    static TweaksBoundsSnapshot parse(String raw, long now) throws Exception {
        if (raw == null || raw.length() > 8192 || !raw.trim().startsWith("{"))
            throw new IOException("TWEAKS_BRIDGE_MISSING: добавка Tweaks 1.1 ещё не загрузилась");
        JSONObject data = new JSONObject(raw.trim());
        if (!"1.1".equals(data.optString("version")))
            throw new IOException("TWEAKS_BRIDGE_VERSION: требуется проверенная добавка Tweaks 1.1");
        int pid = data.getInt("pid");
        if (pid <= 0) throw new IOException("TWEAKS_BRIDGE_PID: неверный PID");
        long sampledAt = data.getLong("now_ms");
        if (sampledAt < 0 || sampledAt > now || now - sampledAt > 5000)
            throw new IOException("TWEAKS_BRIDGE_STALE: нет свежего статуса добавки");
        if (!data.optBoolean("boot_ready", false) || !data.optBoolean("subscription_valid", false))
            throw new IOException("TWEAKS_NOT_READY: штатные условия готовности Tweaks ещё не выполнены");
        long deadline = data.optLong("lease_until_ms", 0);
        boolean active = data.optBoolean("lease_active", false) && deadline > now && deadline - now <= 30000;
        return new TweaksBoundsSnapshot(pid, sampledAt, deadline, active, data.toString());
    }

    static boolean activeLease(String raw, long now) {
        if (raw == null || !raw.matches("1\\|[0-9]{1,15}")) return false;
        try {
            long end = Long.parseLong(raw.substring(2));
            return end > now && end - now <= 30000;
        } catch (NumberFormatException ignored) { return false; }
    }
}
