package com.shilapi.xcertplay.voyah.root;

import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Root-side fixed operations for the already installed Tweaks bridge. No injection. */
final class TweaksBoundsRoot {
    static final String KEY = "voyahplay_tweaks_bounds_test";
    static final String PROBE = "voyahplay_tweaks_bounds_probe";
    static final String CAPABILITY = "TWEAKS_BOUNDS_1_1";
    private static final File LEGACY_ENABLED = new File("/data/local/voyahplay-fullscreen/enabled");
    private static final String[] FILES = {"/system/framework/services.jar", "/data/local/tmp/ss11.bin", "/data/local/tmp/frida-inject",
            "/system/priv-app/SystemUI-release-signed/SystemUI-release-signed.apk"};
    private static long[] verifiedLengths, verifiedTimes;
    private static String verifiedProfile;

    private static String setting(String key) throws Exception {
        RootProcess.Result result = RootProcess.run(3, "/system/bin/settings", "get", "global", key);
        if (!result.successful()) throw new IOException("Не удалось прочитать " + key + ": " + result.output);
        return result.output.trim();
    }
    static boolean leaseActive() throws Exception {
        return TweaksBoundsSnapshot.activeLease(setting(KEY), SystemClock.elapsedRealtime());
    }
    private static synchronized void verifyFiles() throws Exception {
        if (Build.VERSION.SDK_INT != 30) throw new IOException("Поддерживается проверенная прошивка Android 11");
        boolean cached = verifiedLengths != null;
        long[] lengths = new long[FILES.length], times = new long[FILES.length];
        String[] hashes = new String[FILES.length];
        for (int i = 0; i < FILES.length; i++) {
            File file = new File(FILES[i]);
            if (!file.isFile()) throw new IOException("Компонент не найден: " + FILES[i]);
            lengths[i] = file.length(); times[i] = file.lastModified();
            if (cached && (lengths[i] != verifiedLengths[i] || times[i] != verifiedTimes[i])) cached = false;
        }
        if (cached) return;
        for (int i = 0; i < FILES.length; i++) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new FileInputStream(FILES[i])) {
                byte[] buffer = new byte[65536]; int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            StringBuilder actual = new StringBuilder();
            for (byte b : digest.digest()) actual.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            hashes[i] = actual.toString();
        }
        verifiedProfile = TweaksFirmwareProfile.verify(hashes[0], hashes[1], hashes[2], hashes[3]);
        verifiedLengths = lengths; verifiedTimes = times;
    }
    static TweaksBoundsSnapshot snapshot() throws Exception {
        verifyFiles();
        if (LEGACY_ENABLED.exists()) throw new IOException("Обнаружен включённый старый модуль. Выполните root-helper.ps1 -Mode Install для миграции");
        TweaksBoundsSnapshot data = TweaksBoundsSnapshot.parse(setting(PROBE), SystemClock.elapsedRealtime());
        File process = new File("/proc/" + data.pid);
        String status; byte[] cmdline;
        try {
            status = new String(java.nio.file.Files.readAllBytes(new File(process, "status").toPath()), StandardCharsets.UTF_8);
            cmdline = java.nio.file.Files.readAllBytes(new File(process, "cmdline").toPath());
        } catch (IOException error) {
            throw new IOException("TWEAKS_BRIDGE_PID: не удалось прочитать процесс; BRIDGE_PID=" + data.pid
                    + "\n" + error.getMessage(), error);
        }
        TweaksProcessIdentity.verify(data.pid, status, cmdline);
        return data;
    }
    static RootProcess.Result status() throws Exception {
        TweaksBoundsSnapshot data = snapshot();
        boolean leased = leaseActive();
        return new RootProcess.Result(0, false, "TWEAKS_BOUNDS_READY=1\nBRIDGE_VERSION=1.1\n" +
                "FIRMWARE_PROFILE=" + verifiedProfile + "\nTWEAKS_LEASE=" + (leased ? 1 : 0) + "\nPROBE=" + data.raw +
                "\nDOCK_GUARD_PROBE=" + setting("voyahplay_dock_probe") + "\n");
    }
    static RootProcess.Result policy(boolean active, boolean hidden) throws Exception {
        if (!active || !hidden) {
            RootProcess.Result clear = RootProcess.run(3, "/system/bin/settings", "delete", "global", KEY);
            boolean observed = clear.successful() && observeLease(false);
            return new RootProcess.Result(clear.exitCode, clear.timedOut, "TWEAKS_LEASE=" + (clear.successful() ? "0" : "unknown") + "\nBRIDGE_ACK=" +
                    (observed ? 1 : 0) + "\n" + clear.output);
        }
        TweaksBoundsSnapshot data = snapshot();
        long deadline = SystemClock.elapsedRealtime() + 15000;
        RootProcess.Result write = RootProcess.run(3, "/system/bin/settings", "put", "global", KEY, "1|" + deadline);
        boolean observed = write.successful() && (data.leaseActive || observeLease(true));
        return new RootProcess.Result(write.exitCode, write.timedOut, "TWEAKS_BOUNDS_READY=1\nBRIDGE_VERSION=1.1\n" +
                "FIRMWARE_PROFILE=" + verifiedProfile + "\nTWEAKS_LEASE=" + (write.successful() ? "1" : "unknown") + "\nBRIDGE_ACK=" + (observed ? 1 : 0) +
                "\nLEASE_UNTIL_MS=" + deadline + "\nPROBE=" + data.raw + "\n" + write.output);
    }
    private static boolean observeLease(boolean active) {
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                Thread.sleep(250);
                TweaksBoundsSnapshot seen = TweaksBoundsSnapshot.parse(setting(PROBE), SystemClock.elapsedRealtime());
                if (seen.leaseActive == active) return true;
            } catch (Exception ignored) { return false; }
        }
        return false;
    }
}
