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
                if (!info.successful() || !info.output.contains("HELPER_VERSION=" + RootHelperProtocol.VERSION + "\n")) {
                    text = "Обновите root-помощник: root-helper.ps1 -Mode Install.\n" + info.output;
                } else {
                    RootProcess.Result result = request.action == RootHelperProtocol.Action.INFO ? info
                            : RootHelperClient.call(android.os.Process.myUid(), request, 15);
                    ok = result.successful(); text = result.output;
                }
            } catch (Exception error) { text = error.getClass().getSimpleName() + ": " + error.getMessage(); }
            final boolean success = ok;
            final String report = "Длительность: " + (SystemClock.elapsedRealtime() - started) + " мс\n"
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
}
