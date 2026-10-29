package com.shilapi.xcertplay.voyah.root;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.Executor;

/** Helper controls only. The former fullscreen module and direct-su fallback are retired. */
public final class VoyahRootModule {
    private static final Executor WORK = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    public interface Callback { void complete(boolean success, String report); }
    public enum ClusterProbeAction { STATUS, GRID, MAP, STOP }
    private VoyahRootModule() {}
    public static String lastReport(Context context) {
        return context.getSharedPreferences("voyah_root_helper", 0).getString("report", "Root-помощник ещё не проверен.");
    }
    private static void execute(Context context, RootHelperProtocol.Request request, Callback callback) {
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            long started = SystemClock.elapsedRealtime(); boolean ok = false; String text;
            try {
                RootProcess.Result info = RootHelperClient.call(RootHelperProtocol.Action.INFO, 10);
                RootHelperStatus state = new RootHelperStatus(info, android.os.Process.myUid());
                if (request.action == RootHelperProtocol.Action.INFO) {
                    ok = state.ready; text = state.report();
                } else if (request.action == RootHelperProtocol.Action.ROOT && state.ready) {
                    RootProcess.Result result = RootHelperClient.call(android.os.Process.myUid(), request, 15);
                    ok = result.successful(); text = state.report() + "\n" + result.output;
                } else {
                    if (request.action == RootHelperProtocol.Action.RADIO_STATUS || request.action == RootHelperProtocol.Action.RADIO_POLICY)
                        state.requireRadioLabels();
                    else if (request.action == RootHelperProtocol.Action.CLUSTER_AUTO_TICK || request.action == RootHelperProtocol.Action.CLUSTER_AUTO_STOP)
                        state.requireClusterAuto();
                    else if (request.action == RootHelperProtocol.Action.CLUSTER_STATUS || request.action == RootHelperProtocol.Action.CLUSTER_GRID
                            || request.action == RootHelperProtocol.Action.CLUSTER_MAP || request.action == RootHelperProtocol.Action.CLUSTER_STOP)
                        state.requireClusterProbe();
                    else state.requireCompatible();
                    RootProcess.Result result = RootHelperClient.call(android.os.Process.myUid(), request, 15);
                    ok = result.successful(); text = result.output;
                }
            } catch (RootHelperClient.Unavailable error) {
                text = "HELPER_CONNECTED=0\nEXPECTED_HELPER_VERSION=" + RootHelperProtocol.VERSION +
                        "\nHELPER_SOCKET=" + RootHelperProtocol.socketName(android.os.Process.myUid()) + "\n" + error.getMessage();
            } catch (Exception error) { text = error.getClass().getSimpleName() + ": " + error.getMessage(); }
            final boolean success = ok;
            final String report = com.shilapi.xcertplay.voyah.VoyahBuildInfo.summary(app) + "\nHELPER_ACTION=" + request.action + "\nДлительность: " + (SystemClock.elapsedRealtime() - started) + " мс\n"
                    + "Результат: " + (ok ? "успех" : "ошибка") + "\n" + text;
            app.getSharedPreferences("voyah_root_helper", 0).edit().putString("report", report).apply();
            // Old APK preferences cannot reactivate the removed module.
            app.getSharedPreferences("voyah_root_module", 0).edit().putBoolean("enabled", false).putBoolean("boot", false).apply();
            MAIN.post(() -> callback.complete(success, report));
        });
    }
    public static void checkRoot(Context context, Callback callback) {
        execute(context, new RootHelperProtocol.Request(RootHelperProtocol.Action.ROOT), callback);
    }
    public static void helperStatus(Context context, Callback callback) {
        execute(context, new RootHelperProtocol.Request(RootHelperProtocol.Action.INFO), callback);
    }
    public static void setAutoStart(Context context, boolean value, Callback callback) {
        execute(context, new RootHelperProtocol.Request(RootHelperProtocol.Action.BOOT_POLICY, value, false), callback);
    }
    public static void radioLabelsStatus(Context context, Callback callback) {
        execute(context, new RootHelperProtocol.Request(RootHelperProtocol.Action.RADIO_STATUS), callback);
    }
    public static void setRadioLabels(Context context, boolean value, Callback callback) {
        execute(context, new RootHelperProtocol.Request(RootHelperProtocol.Action.RADIO_POLICY, value, false), callback);
    }
    public static void clusterProbe(Context context, ClusterProbeAction action, Callback callback) {
        RootHelperProtocol.Action request;
        switch (action) {
            case GRID: request = RootHelperProtocol.Action.CLUSTER_GRID; break;
            case MAP: request = RootHelperProtocol.Action.CLUSTER_MAP; break;
            case STOP: request = RootHelperProtocol.Action.CLUSTER_STOP; break;
            default: request = RootHelperProtocol.Action.CLUSTER_STATUS;
        }
        execute(context, new RootHelperProtocol.Request(request), callback);
    }
    public static void clusterAuto(Context context, boolean keepAlive, Callback callback) {
        execute(context, new RootHelperProtocol.Request(keepAlive ? RootHelperProtocol.Action.CLUSTER_AUTO_TICK : RootHelperProtocol.Action.CLUSTER_AUTO_STOP), callback);
    }
}
