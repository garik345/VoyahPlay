package com.shilapi.xcertplay.voyah;

import java.util.*;
import java.util.regex.*;

/** Measured OEM media card from the local VoyahTune source and this car's display report. */
public final class VoyahClusterProbeSpec {
    public static final String DISPLAY = "Cluster-Media-Display";
    public static final String ACTIVITY = "com.shilapi.xcertplay.VoyahClusterProbeActivity";
    public static final String TOKEN = "voyah_probe_token", MODE = "voyah_probe_mode", DEADLINE = "voyah_probe_deadline";
    public static final int LEFT = 66, TOP = 200, WIDTH = 574, HEIGHT = 464;
    public static final long DURATION_MS = 60000;
    public static final long AUTO_LEASE_MS = 45000;
    private VoyahClusterProbeSpec() {}

    public static Integer targetDisplay(String dump) {
        Set<Integer> matches = new HashSet<>();
        Pattern record = Pattern.compile("^\\s*m(?:Base)?DisplayInfo=DisplayInfo\\{\"Cluster-Media-Display\", displayId (\\d+),.*");
        for (String line : dump.split("\\r?\\n")) {
            Matcher m = record.matcher(line);
            if (!m.matches() || !line.contains("FLAG_PRIVATE,") || !line.contains("real 1920 x 720,")
                    || !line.contains("state ON,") || !line.contains("type VIRTUAL,")
                    || !line.contains("owner com.qinggan.cluster (uid 1000),")) continue;
            int id;
            try { id = Integer.parseInt(m.group(1)); } catch (NumberFormatException error) { continue; }
            if (id > 0) matches.add(id);
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    public static boolean validLease(String token, String mode, int display, long deadline, long now) {
        return token != null && token.matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
                && ("grid".equals(mode) || "map".equals(mode) || "auto".equals(mode)) && display > 0
                && deadline > now && deadline - now <= ("auto".equals(mode) ? AUTO_LEASE_MS : DURATION_MS);
    }

    public static boolean ownsTask(String base, String top, int user, int display, int target) {
        return ACTIVITY.equals(base) && ACTIVITY.equals(top) && user == 0 && target > 0 && display == target;
    }
}
