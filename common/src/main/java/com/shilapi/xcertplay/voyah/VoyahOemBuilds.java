package com.shilapi.xcertplay.voyah;

/** Exact inspected H97C builds, sharing the reviewed Binder/Parcelable/enum layouts.
 * PI comparisons: docs/voyah-pi-can-20261026.md and docs/voyah-pi-ota72-20261028.md.
 * Unknown APKs remain blocked.
 */
public final class VoyahOemBuilds {
    private static final String CAN_RU = "96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c";
    private static final String CAN_PI = "74d7de6e3d23a05e6519bce57a693dc3517177b885ad56c9be85a73ac54ea952";
    private static final String CAN_PI_OTA72 = "6fb0829a3f6479974256d2f55fcf26277ba0801cf662f6e1bb0539d078281267";
    private static final String SIGNAL_RU = "2bb3e7df1cd672578deeb0e106da8a864adff7d9431784a9a27cbcb8e189f887";
    private static final String SIGNAL_PI = "82a58e74171fc7b2afacdcabe6b9a821a8eb989cf774fe027438dc3697b874f3";
    private static final String SIGNAL_PI_OTA72 = "5558340a5784093d02f7620915bed2048ded299e1a28dadd57c80370932ecd10";

    private VoyahOemBuilds() {}

    public static boolean supportsCanApk(String sha256) {
        return CAN_RU.equals(sha256) || CAN_PI.equals(sha256) || CAN_PI_OTA72.equals(sha256);
    }

    /** Hardware confirmed by the owner: this PI configuration has no heated steering wheel. */
    public static boolean hasHeatedWheel(String canSha256) {
        return CAN_RU.equals(canSha256);
    }

    public static boolean supportsApk(String packageName, String sha256) {
        if ("com.qinggan.canbus.service".equals(packageName)) return supportsCanApk(sha256);
        return "com.qinggan.carsignal.service".equals(packageName)
                && (SIGNAL_RU.equals(sha256) || SIGNAL_PI.equals(sha256) || SIGNAL_PI_OTA72.equals(sha256));
    }
}
