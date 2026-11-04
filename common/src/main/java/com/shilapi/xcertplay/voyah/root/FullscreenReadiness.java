package com.shilapi.xcertplay.voyah.root;

import org.json.JSONObject;

/** Read-only readiness; an inactive but installed hook is sufficient before the user opts in. */
final class FullscreenReadiness {
    static boolean ready(String top, String status, String dock, long now, int systemPid, int uiPid, int launcherPid) {
        if (!TopBoundsRoot.ready(top, now, systemPid, false) || !TopBoundsRoot.ready(status, now, uiPid, false)) return false;
        try {
            JSONObject value = new JSONObject(dock);
            long sampled = value.getLong("now_ms");
            return launcherPid > 0 && value.getInt("pid") == launcherPid &&
                    "1.1".equals(value.getString("version")) && value.getBoolean("installed") &&
                    value.getString("error").isEmpty() && sampled >= 0 && sampled <= now && now - sampled <= 2500;
        } catch (Exception error) { return false; }
    }
}
