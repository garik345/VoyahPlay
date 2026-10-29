package com.shilapi.xcertplay.voyah;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Read-only equipment lookup, without binding to CAN or depending on its current switch values. */
public final class VoyahEquipment {
    private static String stamp;
    private static boolean heatedWheel;
    private VoyahEquipment() {}

    public static synchronized boolean hasHeatedWheel(Context context) {
        try {
            ApplicationInfo info = context.getPackageManager().getApplicationInfo("com.qinggan.canbus.service", 0);
            if (info.splitSourceDirs != null && info.splitSourceDirs.length != 0) return false;
            File apk = new File(info.sourceDir);
            // Bound this UI metadata probe to the two inspected, ~2 MB APKs. Actual commands
            // retain their independent full verification; file size alone never enables hardware.
            if (!apk.isFile() || (apk.length() != 2229868 && apk.length() != 2233964)) return false;
            String current = apk.getAbsolutePath() + ":" + apk.length() + ":" + apk.lastModified();
            if (current.equals(stamp)) return heatedWheel;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(apk)) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder hash = new StringBuilder();
            for (byte value : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", value & 255));
            heatedWheel = VoyahOemBuilds.hasHeatedWheel(hash.toString());
            stamp = current;
            return heatedWheel;
        } catch (Exception error) { return false; }
    }
}
