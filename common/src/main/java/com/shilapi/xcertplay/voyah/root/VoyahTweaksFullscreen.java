package com.shilapi.xcertplay.voyah.root;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Foreground leases for the existing Tweaks bridge, independent of the legacy injector. */
public final class VoyahTweaksFullscreen {
    private static final String TAG = "VoyahTweaksFullscreen";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static Executor work = Executors.newSingleThreadExecutor();
    private static WeakReference<Activity> host = new WeakReference<>(null);
    private static Context app;
    private static boolean hidden, policyQueued, changing, compatible, windowResumed;
    private static volatile long generation;
    private static long userChange;
    private static Runnable windowUpdate;
    private static long refreshUntil;
    private static final Map<String, Long> windowRequests = new HashMap<>();
    private static String report = "Полная ширина через Tweaks ещё не проверена.";
    private static final Runnable LEAVE = new Runnable() {
        @Override public void run() {
            if (host.get() != null) return;
            if (isRefreshingWindow()) { MAIN.postDelayed(this, 250); return; }
            if (policyQueued || changing) { MAIN.postDelayed(this, 250); return; }
            windowRequests.clear();
            publish(false);
        }
    };
    private static final Runnable HEARTBEAT = new Runnable() {
        @Override public void run() {
            MAIN.removeCallbacks(this);
            if (app == null) return;
            Activity current = host.get();
            if (current == null || current.isFinishing() || current.isDestroyed()) return;
            boolean full = enabled(app) && hidden && !current.isInMultiWindowMode();
            if (enabled(app) || preferences(app).getBoolean("restore_pending", false)) publish(full);
            if (full) MAIN.postDelayed(this, 3000);
        }
    };
    private VoyahTweaksFullscreen() {}
    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("voyah_tweaks_fullscreen", Context.MODE_PRIVATE);
    }
    public static boolean enabled(Context context) { return preferences(context).getBoolean("enabled", false); }
    public static boolean isRefreshingWindow() { return SystemClock.elapsedRealtime() < refreshUntil; }
    public static String lastReport(Context context) { return preferences(context).getString("last_report", report); }
    private static void remember(String text) {
        report = text;
        if (app != null) preferences(app).edit().putString("last_report", text).apply();
        Log.i(TAG, text);
    }
    private static boolean supported(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()
                || activity.getWindowManager().getDefaultDisplay().getDisplayId() != 0) return false;
        String name = activity.getComponentName().getClassName();
        return RootHelperProtocol.PACKAGE.equals(activity.getPackageName()) &&
                (name.equals(RootHelperProtocol.PACKAGE + ".DiPlayActivity") ||
                 name.equals(RootHelperProtocol.PACKAGE + ".CarPlayHostActivity"));
    }

    /** Called by the shared dock owner. Late events from the outgoing activity are ignored. */
    public static void hostState(Activity activity, boolean active, boolean hideDock) {
        if (active ? !supported(activity) : host.get() != activity) return;
        app = activity.getApplicationContext();
        Activity current = host.get();
        if (active) {
            windowResumed = true;
            if (current != activity) refreshUntil = 0;
            if (current != activity || hidden != hideDock) generation++;
            if (hidden != hideDock) windowRequests.clear();
            host = new WeakReference<>(activity); hidden = hideDock;
            MAIN.removeCallbacks(LEAVE);
            MAIN.removeCallbacks(HEARTBEAT); MAIN.post(HEARTBEAT);
        } else if (current == activity) {
            windowResumed = false;
            host.clear(); generation++;
            MAIN.removeCallbacks(HEARTBEAT); MAIN.removeCallbacks(LEAVE);
            if (enabled(app)) MAIN.postDelayed(LEAVE, 750);
        }
    }

    /** Keep a visible host's lease under OEM overlays without relaunching its paused Activity. */
    public static void hostPaused(Activity activity) {
        if (host.get() == activity) windowResumed = false;
    }

    private static RootProcess.Result call(RootHelperProtocol.Request request) throws Exception {
        if (!compatible) {
            RootProcess.Result info = RootHelperClient.call(RootHelperProtocol.Action.INFO, 8);
            if (!info.successful() || !info.output.contains("CAPABILITIES=" + TweaksBoundsRoot.CAPABILITY)
                    || !info.output.contains("HELPER_VERSION=" + RootHelperProtocol.VERSION + "\n"))
                throw new IOException("Обновите root-помощник: root-helper.ps1 -Mode Install.\n" + info.output);
            compatible = true;
        }
        RootProcess.Result result = RootHelperClient.call(android.os.Process.myUid(), request, 18);
        if (!result.successful()) compatible = false;
        return result;
    }
    private static String failure(Exception error) {
        compatible = false;
        return "Нет подтверждения от root-помощника.\n" + error.getMessage();
    }
    public static void status(Context context, VoyahRootModule.Callback callback) {
        app = context.getApplicationContext();
        work.execute(() -> {
            boolean success = false; String text;
            try {
                RootProcess.Result result = call(new RootHelperProtocol.Request(RootHelperProtocol.Action.TWEAKS_STATUS));
                success = result.successful(); text = result.output;
            } catch (Exception error) { text = failure(error); }
            boolean ok = success; String answer = text;
            MAIN.post(() -> {
                remember((ok ? "Добавка Tweaks готова.\n" : "Полная ширина пока недоступна.\n") + answer + windowDescription());
                callback.complete(ok, report);
            });
        });
    }
    public static void setEnabled(Context context, boolean value, VoyahRootModule.Callback callback) {
        app = context.getApplicationContext();
        if (changing) { callback.complete(false, "Изменение режима ещё выполняется."); return; }
        Activity current = host.get();
        if (value && (!supported(current) || current.isInMultiWindowMode() || !hidden)) {
            callback.complete(false, "Откройте основное окно VoyahPlay и включите «Скрыть панель навигации»."); return;
        }
        changing = true;
        windowRequests.clear();
        long change = ++userChange; generation++;
        if (!value) {
            preferences(app).edit().putBoolean("enabled", false).putBoolean("restore_pending", true).apply();
            MAIN.removeCallbacks(HEARTBEAT); MAIN.removeCallbacks(LEAVE);
        }
        work.execute(() -> {
            boolean success = false, observed = false; String text;
            try {
                RootProcess.Result result = call(new RootHelperProtocol.Request(RootHelperProtocol.Action.TWEAKS_POLICY, value, value));
                success = result.successful(); observed = result.output.contains("BRIDGE_ACK=1"); text = result.output;
            } catch (Exception error) { text = failure(error); }
            boolean ok = success, ack = observed; String answer = text;
            MAIN.post(() -> {
                changing = false;
                if (change != userChange) return;
                if (value && ok) preferences(app).edit().putBoolean("enabled", true).putBoolean("restore_pending", false).apply();
                remember((value ? ok ? "Режим включён. Применяем ширину окна.\n" : "Режим не включён.\n"
                        : "Автоматическое расширение выключено.\n") + answer);
                callback.complete(ok, report);
                Activity target = host.get();
                if (target == null) {
                    if (ok && value) publish(false);
                    return;
                }
                if (value && ok) {
                    MAIN.removeCallbacks(HEARTBEAT); MAIN.post(HEARTBEAT);
                    if (ack) scheduleWindow(true, generation, 700);
                } else if (!value) {
                    scheduleWindow(false, generation, ok && ack ? 700 : 31000);
                }
            });
        });
    }
    private static void publish(boolean full) {
        if (app == null || policyQueued || changing) return;
        policyQueued = true;
        long token = generation;
        work.execute(() -> {
            boolean success = false, observed = false; String text;
            try {
                if (token != generation) {
                    MAIN.post(() -> { policyQueued = false; MAIN.post(HEARTBEAT); });
                    return;
                }
                RootProcess.Result result = call(new RootHelperProtocol.Request(RootHelperProtocol.Action.TWEAKS_POLICY, full, full));
                success = result.successful(); observed = result.output.contains("BRIDGE_ACK=1"); text = result.output;
            } catch (Exception error) { text = failure(error); }
            boolean ok = success, ack = observed; String answer = text;
            MAIN.post(() -> {
                policyQueued = false;
                if (token != generation) { MAIN.post(HEARTBEAT); return; }
                if (!ok) {
                    remember("Не удалось обновить разрешение на ширину окна.\n" + answer);
                    if (!full && host.get() != null) scheduleWindow(false, token, 31000);
                    return;
                }
                if (ack && host.get() != null) scheduleWindow(full, token, 700);
                else if (!full && host.get() != null) scheduleWindow(false, token, 31000);
            });
        });
    }
    private static void scheduleWindow(boolean full, long token, long delay) {
        if (windowUpdate != null) MAIN.removeCallbacks(windowUpdate);
        windowUpdate = () -> {
            windowUpdate = null;
            if (token != generation) return;
            Activity current = host.get();
            if (!supported(current) || current.isInMultiWindowMode() || !windowResumed) return;
            if (full != (enabled(app) && hidden)) return;
            View decor = current.getWindow().getDecorView();
            int[] origin = new int[2]; decor.getLocationOnScreen(origin);
            int width = decor.getWidth(), height = decor.getHeight();
            if (width <= 0 || height <= 0) return;
            if (!knownGeometry(origin[0], origin[1], width, height)) {
                remember("Получены другие границы окна; автоматическое пересоздание пропущено." + windowDescription()); return;
            }
            int desiredLeft = full ? 0 : 145;
            if (origin[0] == desiredLeft && width == 1920 - desiredLeft) {
                if (!full) preferences(app).edit().putBoolean("restore_pending", false).apply();
                remember((full ? "Полная ширина применена." : "Исходная ширина восстановлена.") + windowDescription());
                return;
            }
            String component = current.getComponentName().getClassName() + "|" + full;
            long now = SystemClock.elapsedRealtime();
            Long previous = windowRequests.get(component);
            if (previous != null) {
                remember("Изменение границ пока не подтверждено. Повторный запрос пропущен; сохраните отчёт." + windowDescription());
                if (now - previous < 5000) scheduleWindow(full, token, 750);
                return;
            }
            windowRequests.put(component, now);
            refreshWindow(current);
        };
        MAIN.postDelayed(windowUpdate, delay);
    }
    private static void refreshWindow(Activity current) {
        // recreate() reuses client-side configuration; a fresh ActivityRecord makes
        // the existing server-side Tweaks handler choose the requested bounds again.
        Intent previous = current.getIntent();
        Intent replacement = previous == null ? new Intent() : new Intent(previous);
        replacement.setComponent(current.getComponentName());
        replacement.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        refreshUntil = SystemClock.elapsedRealtime() + 10000;
        remember("Переоткрываем окно VoyahPlay для применения ширины." + windowDescription());
        try { current.startActivity(replacement); }
        catch (RuntimeException error) {
            refreshUntil = 0;
            remember("Не удалось обновить окно VoyahPlay: " + error.getMessage() + windowDescription());
        }
    }
    static boolean knownGeometry(int x, int y, int width, int height) {
        return y == 45 && (height == 675 || height == 485) &&
                ((x == 0 && width == 1920) || (x == 145 && width == 1775));
    }
    private static String windowDescription() {
        Activity current = host.get();
        if (current == null) return "\nОкно VoyahPlay сейчас не активно.";
        View decor = current.getWindow().getDecorView(); int[] origin = new int[2]; decor.getLocationOnScreen(origin);
        return "\nWINDOW=" + origin[0] + "," + origin[1] + " " + decor.getWidth() + "x" + decor.getHeight();
    }
}
