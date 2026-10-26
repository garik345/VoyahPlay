package com.shilapi.xcertplay.voyah.root;

import android.system.Os;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;

/** Installs one fixed boot entry. Runtime toggles only change private configuration. */
final class RootHelperBoot {
    private static final File HOOK = new File("/system/etc/init.logcat.sh");
    private static File own(String name) { return new File(RootHelperProtocol.DIRECTORY, name); }
    private RootHelperBoot() {}
    private static byte[] read(File path, int limit) throws Exception {
        if (path.length() > limit) throw new IOException("Файл слишком велик: " + path);
        try (InputStream input = new FileInputStream(path); ByteArrayOutputStream data = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int count;
            while ((count = input.read(buffer)) != -1) {
                if (data.size() + count > limit) throw new IOException("Файл слишком велик: " + path);
                data.write(buffer, 0, count);
            }
            return data.toByteArray();
        }
    }
    static void writePrivate(String name, byte[] data) throws Exception {
        RootHelperOperations.verifyHome();
        File target = own(name);
        if (Files.exists(target.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            RootHelperOperations.privatePath(target, false);
        File temporary = own(name + ".new-" + UUID.randomUUID());
        try {
            if (!temporary.createNewFile()) throw new IOException("Не создан временный файл помощника");
            Os.chmod(temporary.getPath(), 0600);
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(data); out.getFD().sync(); }
            Os.rename(temporary.getPath(), target.getPath());
        } finally { temporary.delete(); }
    }
    private static byte[] original() throws Exception {
        File backup = own("boot-hook.original");
        RootHelperOperations.privatePath(backup, false);
        byte[] bytes = read(backup, 65536);
        RootBootHook.patched(bytes); // Verify the captured original before any restoration.
        return bytes;
    }
    private static boolean installed() throws Exception {
        if (!own("boot-hook.original").exists()) return false;
        return RootBootHook.installed(read(HOOK, 65536), original());
    }
    private static boolean configured() throws Exception {
        File configuration = own("autostart.conf");
        if (!configuration.exists()) return false;
        RootHelperOperations.privatePath(configuration, false);
        String value = new String(read(configuration, 8), StandardCharsets.UTF_8).trim();
        if (!(value.equals("0") || value.equals("1"))) throw new IOException("Некорректная конфигурация автозапуска");
        return value.equals("1");
    }
    private static String mountTable() throws Exception {
        return new String(read(new File("/proc/mounts"), 262144), StandardCharsets.UTF_8);
    }
    private static String mountStatus() {
        try {
            String table = mountTable();
            String point = RootBootHook.mountPoint(table, HOOK.getCanonicalPath());
            return "BOOT_MOUNT_POINT=" + point + "\nBOOT_MOUNT_READ_ONLY="
                    + (RootBootHook.readOnly(table, point) ? 1 : 0) + "\n";
        } catch (Exception error) {
            return "BOOT_MOUNT_READ_ONLY=unknown\nBOOT_MOUNT_ERROR=" + error.getMessage() + "\n";
        }
    }
    static String status() {
        try {
            RootHelperOperations.verifyHome();
            boolean hook = installed(), enabled = configured();
            String result = "AUTOSTART_INSTALLED=" + (hook ? 1 : 0) + "\nAUTOSTART_ENABLED=" + (enabled ? 1 : 0)
                    + "\nBOOT_HOOK=" + HOOK + "\n" + mountStatus();
            File report = own("boot-report.txt");
            if (report.exists()) {
                RootHelperOperations.privatePath(report, false);
                result += new String(read(report, 4096), StandardCharsets.UTF_8);
            }
            if (own("boot-hook.original").exists() && !hook && !Arrays.equals(read(HOOK, 65536), original()))
                result += "AUTOSTART_ERROR=BOOT_HOOK_CHANGED\n";
            return result;
        } catch (Exception error) {
            return "AUTOSTART_INSTALLED=0\nAUTOSTART_ENABLED=0\nAUTOSTART_ERROR=" + error.getMessage() + "\n";
        }
    }
    static RootProcess.Result policy(boolean enabled) throws Exception {
        RootHelperOperations.verifyHome();
        if (enabled) {
            if (!installed()) throw new IOException("Автозапуск не установлен или системный сценарий изменён. Выполните root-helper.ps1 -Mode Install");
            RootHelperOperations.privatePath(own("runtime.sh"), false);
        }
        writePrivate("autostart.conf", (enabled ? "1\n" : "0\n").getBytes(StandardCharsets.UTF_8));
        return new RootProcess.Result(0, false, status());
    }
    static RootProcess.Result install() throws Exception {
        RootHelperOperations.verifyHome();
        RootHelperOperations.privatePath(own("app.apk"), false);
        byte[] runtime;
        try (ZipFile apk = new ZipFile(own("app.apk"))) {
            ZipEntry entry = apk.getEntry("assets/voyah-helper/runtime.sh");
            if (entry == null || entry.isDirectory()) throw new IOException("APK не содержит runtime.sh; пересоберите automotive");
            try (InputStream input = apk.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 65536) throw new IOException("runtime.sh слишком велик");
                    output.write(buffer, 0, count);
                }
                runtime = output.toByteArray();
            }
        }
        writePrivate("runtime.sh", runtime);
        installAgent("top-bounds.js", "top.sha256");
        installAgent("status-bar.js", "status.sha256");
        RootProcess.Result statTool = RootProcess.run(3, "/system/bin/stat", "-c", "%u:%a", RootHelperProtocol.DIRECTORY);
        if (!statTool.successful() || !statTool.output.trim().equals("0:700"))
            throw new IOException("BOOT_STAT_UNAVAILABLE: не подтверждена проверка владельца для boot entry\n" + statTool.output);
        if (!own("autostart.conf").exists()) writePrivate("autostart.conf", "1\n".getBytes(StandardCharsets.UTF_8));
        else configured();
        byte[] current = read(HOOK, 65536), source;
        if (own("boot-hook.original").exists()) {
            source = original();
            if (!Arrays.equals(current, source) && !RootBootHook.installed(current, source))
                throw new IOException("BOOT_HOOK_CHANGED: системный сценарий изменён после установки; автоматическая замена отменена");
        } else {
            RootBootHook.patched(current);
            source = current;
            writePrivate("boot-hook.original", source);
        }
        byte[] patched = RootBootHook.patched(source);
        String warning = Arrays.equals(current, patched) ? "" : replaceHook(patched);
        return new RootProcess.Result(0, false, warning + status());
    }
    private static void installAgent(String name, String hashName) throws Exception {
        try (ZipFile apk = new ZipFile(own("app.apk"))) {
            ZipEntry entry = apk.getEntry("assets/voyah-helper/" + name);
            if (entry == null || entry.isDirectory() || entry.getSize() > 16384) throw new IOException("Missing or oversized " + name);
            byte[] script;
            try (InputStream input = apk.getInputStream(entry); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > 16384) throw new IOException("Oversized " + name);
                    output.write(buffer, 0, count);
                }
                script = output.toByteArray();
            }
            writePrivate(name, script);
            writePrivate(hashName, (RootBootHook.hash(script) + "  " + own(name).getPath() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }
    static RootProcess.Result remove() throws Exception {
        policy(false);
        byte[] source = original(), current = read(HOOK, 65536);
        if (Arrays.equals(current, source)) return new RootProcess.Result(0, false, "AUTOSTART_REMOVED=1\n" + status());
        if (!RootBootHook.installed(current, source)) throw new IOException("BOOT_HOOK_CHANGED: чужие изменения не перезаписываются");
        String warning = replaceHook(source);
        return new RootProcess.Result(0, false, "AUTOSTART_REMOVED=1\n" + warning + status());
    }
    private static void command(String... arguments) throws Exception {
        RootProcess.Result result = RootProcess.run(8, arguments);
        if (!result.successful()) throw new IOException("Команда не выполнена: " + String.join(" ", arguments) + "\n" + result.output);
    }
    private static String replaceHook(byte[] replacement) throws Exception {
        StructStat metadata = Os.lstat(HOOK.getPath());
        if (metadata.st_uid != 0 || (metadata.st_mode & 0170000) != 0100000 || (metadata.st_mode & 0022) != 0)
            throw new IOException("Неожиданный владелец/тип/права системного сценария");
        String table = mountTable();
        String point = RootBootHook.mountPoint(table, HOOK.getCanonicalPath());
        boolean readOnly = RootBootHook.readOnly(table, point);
        if (readOnly && !(point.equals("/") || point.equals("/system") || point.equals("/system_root") || point.equals("/system_root/system")))
            throw new IOException("Не поддерживается remount для " + point);
        RootProcess.Result label = RootProcess.run(3, "/system/bin/ls", "-Zd", HOOK.getPath());
        String context = label.output.trim().split("\\s+")[0];
        if (!label.successful() || !context.matches("u:object_r:[a-zA-Z0-9_]+:s0"))
            throw new IOException("Не прочитан SELinux-контекст системного сценария: " + label.output);
        File temporary = new File(HOOK.getPath() + ".voyahplay-" + UUID.randomUUID());
        boolean mounted = false;
        Exception failure = null;
        String warning = "";
        try {
            if (readOnly) { command("/system/bin/mount", "-o", "remount,rw", point); mounted = true; }
            if (!temporary.createNewFile()) throw new IOException("Не создан временный системный сценарий");
            try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(replacement); out.getFD().sync(); }
            Os.chown(temporary.getPath(), metadata.st_uid, metadata.st_gid);
            Os.chmod(temporary.getPath(), metadata.st_mode & 07777);
            command("/system/bin/chcon", context, temporary.getPath());
            command("/system/bin/sh", "-n", temporary.getPath());
            if (!Arrays.equals(read(temporary, 65536), replacement)) throw new IOException("Проверка нового сценария не прошла");
            Os.rename(temporary.getPath(), HOOK.getPath());
            if (!Arrays.equals(read(HOOK, 65536), replacement)) throw new IOException("BOOT_HOOK_WRITE_FAILED");
        } catch (Exception error) {
            failure = error;
        } finally {
            temporary.delete();
            if (mounted) {
                try {
                    RootProcess.Result restore = RootProcess.run(8, "/system/bin/mount", "-o", "remount,ro", point);
                    // Check the actual mount flags, rather than infer them from the command's exit code.
                    if (!RootBootHook.readOnly(mountTable(), point)) {
                        if (failure == null && !restore.timedOut && restore.exitCode != 0
                                && restore.output.contains("Device or resource busy")) {
                            // The verified hook is installed. An open old inode/writer can defer RO until reboot.
                            warning = "AUTOSTART_WARNING=REMOUNT_RO_BUSY\nREBOOT_REQUIRED=1\n";
                        } else {
                            throw new IOException("BOOT_MOUNT_RESTORE_FAILED: " + point
                                    + " остаётся read-write; exit=" + restore.exitCode
                                    + (restore.timedOut ? " timeout" : "") + "\n" + restore.output);
                        }
                    }
                } catch (Exception restoreError) {
                    if (failure == null) failure = restoreError;
                    else failure.addSuppressed(restoreError);
                }
            }
        }
        if (failure != null) throw failure;
        return warning;
    }
}
