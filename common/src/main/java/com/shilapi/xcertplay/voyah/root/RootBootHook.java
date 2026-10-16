package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Exact, reversible insertion into the boot script already captured from this vehicle. */
final class RootBootHook {
    static final String ORIGINAL_SHA = "a2153c1819c2eb15a0a3c7264ed90c81120b61214a1e891d798a0289551666bb";
    static final String BLOCK = "# BEGIN VOYAHPLAY ROOT HELPER V1\n"
            + "if [ ! -L /data/local/voyahplay-root-helper ] &&\n"
            + "   [ \"$(/system/bin/stat -c '%u:%a' /data/local/voyahplay-root-helper 2>/dev/null)\" = '0:700' ] &&\n"
            + "   [ ! -L /data/local/voyahplay-root-helper/runtime.sh ] &&\n"
            + "   [ \"$(/system/bin/stat -c '%u:%a' /data/local/voyahplay-root-helper/runtime.sh 2>/dev/null)\" = '0:600' ]; then\n"
            + "    /system/bin/sh /data/local/voyahplay-root-helper/runtime.sh boot </dev/null >/dev/null 2>&1 &\n"
            + "fi\n# END VOYAHPLAY ROOT HELPER V1\n";
    private RootBootHook() {}
    static String hash(byte[] value) throws Exception {
        StringBuilder text = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value))
            text.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }
    static byte[] patched(byte[] original) throws Exception {
        if (original.length > 65536 || !ORIGINAL_SHA.equals(hash(original)))
            throw new IOException("BOOT_HOOK_UNREVIEWED: init.logcat.sh отличается от присланного файла; замена отменена");
        return insert(original);
    }
    static byte[] insert(byte[] original) throws IOException {
        String text = new String(original, StandardCharsets.UTF_8);
        if (!text.startsWith("#!/system/bin/sh\n") || text.contains("# BEGIN VOYAHPLAY ROOT HELPER V1"))
            throw new IOException("Неожиданный заголовок или повторная вставка автозапуска");
        int afterHeader = text.indexOf('\n') + 1;
        return (text.substring(0, afterHeader) + BLOCK + text.substring(afterHeader)).getBytes(StandardCharsets.UTF_8);
    }
    static boolean installed(byte[] current, byte[] original) throws Exception {
        return Arrays.equals(current, patched(original));
    }
    static String mountPoint(String table, String path) throws IOException {
        String selected = null;
        for (String line : table.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length < 4) continue;
            String point = fields[1];
            if ((point.equals("/") || path.equals(point) || path.startsWith(point + "/"))
                    && (selected == null || point.length() > selected.length())) selected = point;
        }
        if (selected == null) throw new IOException("Не определено монтирование системного сценария");
        return selected;
    }
    static boolean readOnly(String table, String point) throws IOException {
        Boolean readOnly = null;
        for (String line : table.split("\n")) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length >= 4 && fields[1].equals(point))
                readOnly = Arrays.asList(fields[3].split(",")).contains("ro");
        }
        if (readOnly != null) return readOnly;
        throw new IOException("Не прочитаны параметры монтирования " + point);
    }
}
