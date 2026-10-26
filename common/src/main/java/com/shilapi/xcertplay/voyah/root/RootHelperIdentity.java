package com.shilapi.xcertplay.voyah.root;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/** UID migration is accepted only for the same package signed like the private root snapshot. */
final class RootHelperIdentity {
    private RootHelperIdentity() {}
    @SuppressWarnings("deprecation")
    static boolean sameSigner(PackageInfo trusted, PackageInfo installed) {
        if (trusted == null || installed == null || !RootHelperProtocol.PACKAGE.equals(trusted.packageName) ||
                !RootHelperProtocol.PACKAGE.equals(installed.packageName)) return false;
        Signature[] a = trusted.signatures, b = installed.signatures;
        if (a == null || b == null || a.length == 0 || a.length != b.length) return false;
        for (Signature value : a) if (value == null) return false;
        for (Signature value : b) if (value == null) return false;
        // Android's Signature equality compares the certificate bytes, independent of array order.
        return new java.util.HashSet<>(Arrays.asList(a)).equals(new java.util.HashSet<>(Arrays.asList(b))) &&
                new java.util.HashSet<>(Arrays.asList(a)).size() == a.length;
    }

    @SuppressWarnings("deprecation")
    static void verifyInstalled(int uid) throws Exception {
        RootHelperOperations.verifyHome();
        if (RootHelperOperations.installedUid() != uid) throw new IOException("Package UID mismatch");
        File snapshot = new File(RootHelperProtocol.APK);
        if (!snapshot.exists()) return; // First installation is performed explicitly by root.
        RootHelperOperations.privatePath(snapshot, false);
        if (android.os.Looper.myLooper() == null) android.os.Looper.prepare();
        Class<?> thread = Class.forName("android.app.ActivityThread");
        Object system = thread.getMethod("systemMain").invoke(null);
        Context context = (Context) thread.getMethod("getSystemContext").invoke(system);
        PackageManager pm = context.getPackageManager();
        PackageInfo trusted = pm.getPackageArchiveInfo(snapshot.getPath(), PackageManager.GET_SIGNATURES);
        PackageInfo current = pm.getPackageInfo(RootHelperProtocol.PACKAGE, PackageManager.GET_SIGNATURES);
        if (!sameSigner(trusted, current)) throw new IOException("HELPER_SIGNER_MISMATCH: installed app differs from trusted root snapshot");
    }
}
