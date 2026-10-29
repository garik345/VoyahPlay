package com.shilapi.xcertplay.voyah.root;

import android.os.Build;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Fixed opt-in UI patch for the supplied system-UID radio APK. No tuner or CAN commands. */
final class RadioLabelsRoot {
    static final String CAPABILITY = "OEM_RADIO_LABELS_1";
    static final String APK_HASH = "36b4d73a889aa78325f6a6ff0c8113c6b13605620a5a4b045209fbff36449cca";
    private static final String PACKAGE = "com.pateo.rdsapp";
    private static File own(String name) { return new File(RootHelperProtocol.DIRECTORY, name); }
    private RadioLabelsRoot() {}

    static String apkPath(String output) throws IOException {
        String path = null;
        for (String line : output.split("\\r?\\n")) {
            if (line.trim().isEmpty()) continue;
            if (!line.startsWith("package:") || path != null) throw new IOException("Radio APK path is ambiguous");
            path = line.substring(8).trim();
        }
        if (path == null || !path.matches("/(?:data/app|(?:system|product|vendor)/(?:priv-app|app))/[A-Za-z0-9_./+=~@-]+\\.apk")
                || path.contains("/../") || path.contains("/./")) throw new IOException("Unsupported radio APK path");
        return path;
    }

    private static void verifyRadio() throws Exception {
        if (Build.VERSION.SDK_INT != 30) throw new IOException("Radio labels require the reviewed Android 11 firmware");
        RootProcess.Result paths = RootProcess.run(3, "/system/bin/pm", "path", "--user", "0", PACKAGE);
        if (!paths.successful()) throw new IOException("Radio APK not found: " + paths.output);
        File file = new File(apkPath(paths.output));
        if (!file.isFile() || file.length() < 1 || file.length() > 128L * 1024 * 1024) throw new IOException("Invalid radio APK size");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hash = new StringBuilder();
        for (byte value : digest.digest()) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        if (!APK_HASH.contentEquals(hash)) throw new IOException("RADIO_APK_UNSUPPORTED expected=" + APK_HASH + " actual=" + hash);
        RootProcess.Result uid = RootProcess.run(3, "/system/bin/cmd", "package", "list", "packages", "--user", "0", "-U", PACKAGE);
        if (!uid.successful() || !uid.output.trim().matches("package:com\\.pateo\\.rdsapp\\s+uid:1000"))
            throw new IOException("RADIO_UID_UNSUPPORTED: expected the reviewed system-UID radio");
        RootProcess.Result injector = RootProcess.run(3, "/system/bin/sha256sum", "/data/local/tmp/frida-inject");
        if (!injector.successful() || !injector.output.trim().matches(
                "0d99be0e44d4ca3449056fa0a0b868fca1cf66c8ad704508949945afc07706aa\\s+/data/local/tmp/frida-inject"))
            throw new IOException("RADIO_INJECTOR_UNSUPPORTED: install the compatible helper infrastructure");
        for (String name : new String[]{"radio-runtime.sh", "radio-runtime.sha256", "radio-labels.js", "radio-labels.sha256"})
            RootHelperOperations.privatePath(own(name), false);
        for (String name : new String[]{"radio-runtime.sha256", "radio-labels.sha256"}) {
            RootProcess.Result integrity = RootProcess.run(2, "/system/bin/sha256sum", "-c", own(name).getPath());
            if (!integrity.successful()) throw new IOException("Radio agent checksum failed: " + name);
        }
    }

    static boolean configured() throws Exception {
        if (!own("radio-labels.conf").exists()) return false;
        RootHelperOperations.privatePath(own("radio-labels.conf"), false);
        if (own("radio-labels.conf").length() > 8) throw new IOException("Invalid radio labels configuration");
        String value = new String(java.nio.file.Files.readAllBytes(own("radio-labels.conf").toPath()), StandardCharsets.UTF_8).trim();
        if (!(value.equals("0") || value.equals("1"))) throw new IOException("Invalid radio labels configuration");
        return value.equals("1");
    }

    static String brief() throws Exception {
        String result = "RADIO_LABELS_CAPABILITY=" + CAPABILITY + "\n";
        try { return result + "RADIO_LABELS_ENABLED=" + (configured() ? 1 : 0) + "\n"; }
        catch (Exception error) { return result + "RADIO_LABELS_ENABLED=unknown\nRADIO_LABELS_REASON=" + error.getMessage() + "\n"; }
    }

    static RootProcess.Result status() throws Exception {
        RootHelperOperations.verifyHome();
        String summary = brief();
        try { verifyRadio(); summary += "RADIO_LABELS_SUPPORTED=1\n"; }
        catch (Exception error) { summary += "RADIO_LABELS_SUPPORTED=0\nRADIO_LABELS_REASON=" + error.getMessage() + "\n"; }
        File log = own("radio-agent.log");
        if (log.exists()) {
            RootHelperOperations.privatePath(log, false);
            RootProcess.Result tail = RootProcess.run(2, "/system/bin/tail", "-n", "8", log.getPath());
            if (tail.successful()) summary += tail.output;
        }
        return new RootProcess.Result(0, false, summary);
    }

    static RootProcess.Result policy(boolean enabled) throws Exception {
        RootHelperOperations.verifyHome();
        if (enabled) verifyRadio();
        RootHelperBoot.writePrivate("radio-labels.conf", (enabled ? "1\n" : "0\n").getBytes(StandardCharsets.UTF_8));
        if (!enabled) {
            RootProcess.Result clear = RootProcess.run(3, "/system/bin/settings", "put", "global", "voyahplay_radio_labels_enabled", "0");
            if (!clear.successful()) throw new IOException("Radio labels disable was not acknowledged: " + clear.output);
        }
        RootHelperOperations.privatePath(own("radio-runtime.sh"), false);
        RootHelperOperations.privatePath(own("radio-runtime.sha256"), false);
        RootProcess.Result integrity = RootProcess.run(2, "/system/bin/sha256sum", "-c", own("radio-runtime.sha256").getPath());
        if (!integrity.successful()) throw new IOException("Radio runtime checksum failed");
        RootProcess.Result change = RootProcess.run(12, "/system/bin/sh", own("radio-runtime.sh").getPath(), enabled ? "tick" : "stop");
        return new RootProcess.Result(change.exitCode, change.timedOut, status().output + change.output);
    }
}
