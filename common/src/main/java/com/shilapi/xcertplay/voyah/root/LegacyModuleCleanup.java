package com.shilapi.xcertplay.voyah.root;

import android.system.Os;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** One-time retirement of our old payload; never attaches to or kills a system process. */
final class LegacyModuleCleanup {
    private static final File ROOT = new File("/data/local/voyahplay-fullscreen");
    private LegacyModuleCleanup() {}
    static RootProcess.Result retire() throws Exception {
        if (!Files.exists(ROOT.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
            return new RootProcess.Result(0, false, "LEGACY_PAYLOAD_REMOVED=1\n");
        RootHelperOperations.privatePath(ROOT, true);
        File marker = new File(ROOT, "owner");
        StructStat owner = Os.lstat(marker.getPath());
        if (owner.st_uid != 0 || (owner.st_mode & 0170000) != 0100000 || marker.length() > 128
                || !new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).trim().equals("voyahplay-fullscreen-v1"))
            throw new IOException("LEGACY_OWNER_UNKNOWN: каталог прежнего модуля не менялся");
        File enabled = new File(ROOT, "enabled");
        if (Files.exists(enabled.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS) && !enabled.delete())
            throw new IOException("Не удалось выключить прежний модуль");
        RootProcess.Result clear = RootProcess.run(3, "/system/bin/settings", "put", "global", "voyahplay_fullscreen_policy", "1|2|0|0|0");
        if (!clear.successful()) return clear;
        for (int attempt = 0; attempt < 20; attempt++) {
            boolean pending = false;
            File loader = new File(ROOT, "loader.pid");
            if (loader.exists()) {
                if (loader.length() > 128) throw new IOException("Некорректная запись старого loader");
                String[] fields = new String(Files.readAllBytes(loader.toPath()), StandardCharsets.UTF_8).trim().split("\\s+");
                if (fields.length != 2 || !fields[0].matches("[0-9]+") || !fields[1].matches("[0-9]+"))
                    throw new IOException("Не подтверждена личность старого loader");
                pending = processMatches(fields[0], fields[1]);
            }
            for (String role : new String[]{"system", "launcher"}) {
                RootProcess.Result state = RootProcess.run(3, "/system/bin/settings", "get", "global", "voyahplay_fullscreen_" + role);
                if (!state.successful()) return state;
                String value = state.output.trim();
                if (value.startsWith("active:") || value.startsWith("ready:") || value.startsWith("restoring:") || value.startsWith("error-restoration:")) {
                    String[] parts = value.split(":");
                    if (parts.length == 3 && parts[1].matches("[0-9]+") && parts[2].matches("[0-9]+")) {
                        pending |= processMatches(parts[1], parts[2]);
                    }
                }
            }
            if (!pending) {
                for (String name : new String[]{"payload", "payload.old", "payload.new", "version"}) remove(new File(ROOT, name));
                return new RootProcess.Result(0, false, "LEGACY_PAYLOAD_REMOVED=1\nLEGACY_LOGS_RETAINED=1\n");
            }
            Thread.sleep(250);
        }
        throw new IOException("LEGACY_RESTORATION_PENDING: выключение запрошено; перед удалением нужна перезагрузка мультимедиа");
    }
    private static boolean processMatches(String pid, String start) throws Exception {
        try {
            String text = new String(Files.readAllBytes(new File("/proc/" + pid + "/stat").toPath()), StandardCharsets.UTF_8);
            String[] fields = text.substring(text.lastIndexOf(')') + 2).trim().split("\\s+");
            if (fields.length <= 19) throw new IOException("Не прочитано время старта процесса " + pid);
            return fields[19].equals(start);
        } catch (java.nio.file.NoSuchFileException gone) { return false; }
    }
    private static void remove(File file) throws Exception {
        if (!Files.exists(file.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return;
        StructStat stat = Os.lstat(file.getPath());
        if (stat.st_uid != 0) throw new IOException("Чужой файл в payload: " + file);
        if ((stat.st_mode & 0170000) == 0040000) {
            File[] children = file.listFiles();
            if (children == null) throw new IOException("Не прочитан каталог " + file);
            for (File child : children) remove(child);
        }
        if (!file.delete()) throw new IOException("Не удалён прежний файл " + file);
    }
}
