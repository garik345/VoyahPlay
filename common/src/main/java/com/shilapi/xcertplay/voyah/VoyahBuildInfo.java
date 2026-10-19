package com.shilapi.xcertplay.voyah;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Process;

/** Identifies the installed APK rather than a source folder or helper snapshot. */
public final class VoyahBuildInfo {
    public static final String SETTINGS_REVISION = "voyah-settings-20261017-r2";
    private VoyahBuildInfo() {}
    public static String summary(Context context) {
        String version = "unknown";
        long code = -1;
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            version = info.versionName;
            code = info.getLongVersionCode();
        } catch (Exception ignored) { }
        return "APP_VERSION=" + version + "\nAPP_VERSION_CODE=" + code +
                "\nAPP_PACKAGE=" + context.getPackageName() + "\nAPP_UID=" + Process.myUid() +
                "\nSETTINGS_REVISION=" + SETTINGS_REVISION;
    }
}
