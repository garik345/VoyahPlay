package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;

/** Availability and compatibility are separate facts; mutations still require an exact match. */
final class RootHelperStatus {
    private final RootProcess.Result result;
    final String version;
    final boolean connected;
    final boolean ready;
    final boolean compatible;
    RootHelperStatus(RootProcess.Result result, int appUid) throws IOException {
        this.result = result;
        version = field(result.output, "HELPER_VERSION");
        connected = version != null;
        String owner = field(result.output, "CLIENT_UID");
        ready = result.successful() && "1".equals(field(result.output, "HELPER_READY")) &&
                "0".equals(field(result.output, "SERVER_UID")) && Integer.toString(appUid).equals(owner);
        compatible = ready && RootHelperProtocol.VERSION.equals(version) &&
                TweaksBoundsRoot.CAPABILITY.equals(field(result.output, "CAPABILITIES"));
    }
    static String field(String output, String key) throws IOException {
        String value = null;
        for (String line : output.split("\\r?\\n")) {
            if (!line.startsWith(key + "=")) continue;
            if (value != null) throw new IOException("Duplicate helper field: " + key);
            value = line.substring(key.length() + 1);
        }
        return value;
    }
    void requireCompatible() throws IOException {
        if (!compatible) throw new IOException("Root-помощник отвечает, но его версия или UID не соответствует APK. " +
                "Установите помощник из этого проекта: root-helper.ps1 -Mode Install.\n" + report());
    }
    String report() {
        return "HELPER_CONNECTED=" + (connected ? 1 : 0) + "\nEXPECTED_HELPER_VERSION=" + RootHelperProtocol.VERSION +
                "\nHELPER_COMPATIBLE=" + (compatible ? 1 : 0) + "\n" + result.output +
                (!connected ? "\nНе получен проверенный ответ root-помощника.\n" : compatible ? "" :
                        "\nПомощник найден, но требует обновления или повторной привязки к установленному APK. " +
                        "После установки APK выполните root-helper.ps1 -Mode Install без удаления приложения.\n");
    }
}
