package com.shilapi.xcertplay.voyah;

import android.app.Activity;
import android.content.*;
import android.os.*;
import android.util.Log;
import android.view.View;
import android.view.ViewTreeObserver;

/** Main-screen dock requests shared by the home and CarPlay activities.
 * QGBus is bound through the application context and survives an internal transition.
 * This class does not resize windows or install hooks. Calls are on the main thread.
 */
public final class VoyahDockVisibility {
    private static final String TAG = "VoyahDockVisibility";
    private static final long RENEW_MS = 250;
    private static final long RECONNECT_MS = 5000;
    private static final long RESTORE_GRACE_MS = 750;
    private static final Coordinator SHARED = new Coordinator();

    private final Activity activity;
    private boolean active, paused, hidden, statusHidden;
    private ViewTreeObserver focusObserver;
    private final ViewTreeObserver.OnWindowFocusChangeListener focusListener = hasFocus -> {
        if (active) SHARED.refresh(this, hasFocus ? "window-focus" : "window-focus-lost");
    };

    public VoyahDockVisibility(Activity activity) { this.activity = activity; }
    public void resume() {
        if (!isMainDisplay() || activity.isFinishing() || activity.isDestroyed()) return;
        active = true;
        paused = false;
        watchFocus();
        SHARED.resume(this);
    }
    public void setHidden(boolean value) {
        hidden = value;
        SHARED.settingChanged(this);
    }
    public void setStatusBarHidden(boolean value) {
        statusHidden = value;
        SHARED.settingChanged(this);
    }
    public void pause() {
        // Climate/other translucent OEM activities may pause us while our window stays visible.
        paused = true;
        if (SHARED.owner == this)
            com.shilapi.xcertplay.voyah.root.VoyahTweaksFullscreen.hostPaused(activity);
        if (!windowVisible()) stop();
    }
    public void stop() {
        active = false;
        paused = false;
        unwatchFocus();
        SHARED.pause(this);
    }
    private boolean isMainDisplay() {
        return activity.getWindowManager().getDefaultDisplay().getDisplayId() == 0;
    }
    private boolean shouldHide() {
        return active && (hidden || statusHidden) && isMainDisplay() && !activity.isFinishing()
                && !activity.isDestroyed() && !activity.isInMultiWindowMode()
                && (!paused || windowVisible());
    }
    private boolean windowVisible() {
        return activity.getWindow().getDecorView().getWindowVisibility() == View.VISIBLE;
    }
    private void watchFocus() {
        ViewTreeObserver observer = activity.getWindow().getDecorView().getViewTreeObserver();
        if (observer == focusObserver && observer.isAlive()) return;
        unwatchFocus();
        if (observer.isAlive()) {
            focusObserver = observer;
            observer.addOnWindowFocusChangeListener(focusListener);
        }
    }
    private void unwatchFocus() {
        if (focusObserver != null && focusObserver.isAlive())
            focusObserver.removeOnWindowFocusChangeListener(focusListener);
        focusObserver = null;
    }

    private static final class Coordinator {
        final Handler main = new Handler(Looper.getMainLooper());
        final Messenger client = new Messenger(new Handler(Looper.getMainLooper()));
        VoyahDockVisibility owner;
        Connection connection;
        long nextConnectAt;
        String activityName = "";
        final Runnable settled = () -> ensureHidden("transition-settled");
        final Runnable renew = new Runnable() {
            @Override public void run() {
                if (!shouldHide()) {
                    if (owner != null && owner.paused && !owner.windowVisible()) owner.stop();
                    else release(true);
                    return;
                }
                ensureHidden("renew");
                main.postDelayed(this, RENEW_MS);
            }
        };
        final Runnable restore = new Runnable() {
            @Override public void run() {
                if (owner != null) return;
                if (com.shilapi.xcertplay.voyah.root.VoyahTweaksFullscreen.isRefreshingWindow()) {
                    main.postDelayed(this, 250); return;
                }
                release(true);
            }
        };

        void resume(VoyahDockVisibility host) {
            main.removeCallbacks(restore);
            if (owner != null && owner != host) {
                owner.active = false;
                owner.unwatchFocus();
            }
            owner = host;
            com.shilapi.xcertplay.voyah.root.VoyahTweaksFullscreen.hostState(host.activity, true, host.hidden);
            activityName = host.activity.getComponentName().getClassName();
            nextConnectAt = 0;
            Log.i(TAG, "Dock owner: " + activityName);
            // Retain the same Messenger: no visible=true and no reconnect between our windows.
            refresh(host, "resume");
        }
        void settingChanged(VoyahDockVisibility host) {
            if (owner != host) return;
            com.shilapi.xcertplay.voyah.root.VoyahTweaksFullscreen.hostState(host.activity, true, host.hidden);
            if (host.hidden) nextConnectAt = 0;
            refresh(host, "setting");
        }
        void pause(VoyahDockVisibility host) {
            if (owner != host) return;
            com.shilapi.xcertplay.voyah.root.VoyahTweaksFullscreen.hostState(host.activity, false, host.hidden);
            owner = null;
            cancelScheduled();
            main.removeCallbacks(restore);
            // The next resumed VoyahPlay window cancels this before the old window
            // can request a visible dock. No hide requests are sent during the gap.
            if (connection != null) {
                Log.i(TAG, "Dock restore deferred: " + RESTORE_GRACE_MS + " ms");
                main.postDelayed(restore, RESTORE_GRACE_MS);
            }
        }
        boolean shouldHide() { return owner != null && owner.shouldHide(); }
        void cancelScheduled() {
            main.removeCallbacks(settled);
            main.removeCallbacks(renew);
        }
        void refresh(VoyahDockVisibility host, String reason) {
            if (owner != host) return;
            cancelScheduled();
            if (!shouldHide()) { release(true); return; }
            host.watchFocus();
            ensureHidden(reason);
            // The launcher may process its own activity-change event after onResume.
            main.postDelayed(settled, 300);
            main.postDelayed(settled, 1200);
            main.postDelayed(renew, RENEW_MS);
        }
        void ensureHidden(String reason) {
            if (!shouldHide()) return;
            Connection current = connection;
            if (current != null) {
                if (current.service != null) {
                    try { current.apply(reason); }
                    catch (Exception | LinkageError e) { current.failed(e.toString()); }
                }
                return;
            }
            if (SystemClock.elapsedRealtime() < nextConnectAt) return;
            Connection next = new Connection(owner.activity.getApplicationContext());
            connection = next;
            try {
                next.bound = next.context.bindService(new Intent("com.qinggan.QGBus.QGBusService")
                        .setPackage("com.qinggan.QGBus"), next, Context.BIND_AUTO_CREATE);
                if (!next.bound) { next.failed("QGBus unavailable"); return; }
                main.postDelayed(next.timeout, 5000);
            } catch (RuntimeException e) { next.failed(e.toString()); }
        }
        void release(boolean show) {
            main.removeCallbacks(restore);
            Connection old = connection; connection = null;
            if (old != null) old.close(show);
        }

        private final class Connection implements ServiceConnection {
            final Context context;
            boolean bound, hideSent, statusHideSent;
            Messenger service;
            final Runnable timeout = () -> failed("QGBus connection timeout");
            Connection(Context context) { this.context = context; }
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                if (connection != this) return;
                if (!shouldHide()) { release(true); return; }
                main.removeCallbacks(timeout);
                try {
                    if (!"android.os.IMessenger".equals(binder.getInterfaceDescriptor()))
                        throw new IllegalStateException("Unknown QGBus interface");
                    service = new Messenger(binder);
                    Message register = Message.obtain(null, 5);
                    Bundle identity = new Bundle(); identity.putString("name", context.getPackageName());
                    register.setData(identity); register.replyTo = client;
                    service.send(register);
                    Log.i(TAG, "QGBus connected: " + activityName);
                    apply("connected");
                } catch (Exception | LinkageError e) { failed(e.toString()); }
            }
            void send(boolean visible, String reason) throws Exception {
                Bundle data = new Bundle();
                data.putString("package", context.getPackageName());
                data.putString("activity", activityName);
                data.putBoolean("visible", visible);
                data.putInt("extra_navigation_bar_visible_type", 2);
                // QGAPI supplies the real no-arg constructor; the local stub differs.
                Class<?> type = Class.forName("com.qinggan.bus.QGBusEvent", true, context.getClassLoader());
                Object event = type.getConstructor().newInstance();
                type.getMethod("setEventType", String.class).invoke(event, "navigation_bar_visibility");
                type.getMethod("setDestination", String.class).invoke(event, "com.qinggan.app.launcher");
                type.getMethod("setData", Bundle.class).invoke(event, data);
                type.getField("mSource").set(event, context.getPackageName());
                if (!context.getPackageName().equals(type.getMethod("getSource").invoke(event))
                        || !(event instanceof Parcelable)) throw new IllegalStateException("Invalid QGBus event");
                Bundle payload = new Bundle(); payload.putParcelable("event", (Parcelable) event);
                Message message = Message.obtain(null, 3); message.obj = payload;
                if (!visible) hideSent = true;
                service.send(message);
                if (visible) hideSent = false;
                // A sent request is not an acknowledgement of the launcher's state.
                String report = "Dock request visible=" + visible + " reason=" + reason
                        + " activity=" + activityName;
                if ("renew".equals(reason) || "transition-settled".equals(reason)) Log.d(TAG, report);
                else Log.i(TAG, report);
            }
            void apply(String reason) throws Exception {
                if (owner == null) return;
                if (owner.hidden || hideSent) send(!owner.hidden, reason);
                if (owner.statusHidden || statusHideSent) sendStatus(!owner.statusHidden, reason);
            }
            void sendStatus(boolean visible, String reason) throws Exception {
                // QGToolCommon.showStatusBar and PhoneStatusBar$12 on this firmware.
                Bundle data = new Bundle();
                data.putBoolean("isVisible", visible);
                data.putInt("displayId", 0);
                Class<?> type = Class.forName("com.qinggan.bus.QGBusEvent", true, context.getClassLoader());
                Object event = type.getConstructor().newInstance();
                type.getMethod("setEventType", String.class).invoke(event, "SYSTEMUI_STATUS_BAR_MODE_VISIBLE");
                type.getMethod("setDestination", String.class).invoke(event, "com.qinggan.systemui");
                type.getMethod("setData", Bundle.class).invoke(event, data);
                // This firmware accepts the visual toggle only on its gallery compatibility route.
                // The real authenticated QGBus client remains VoyahPlay; no package is replaced.
                type.getField("mSource").set(event, "com.qinggan.app.gallery");
                if (!(event instanceof Parcelable)) throw new IllegalStateException("Invalid QGBus status event");
                Bundle payload = new Bundle(); payload.putParcelable("event", (Parcelable) event);
                Message message = Message.obtain(null, 3); message.obj = payload;
                if (!visible) statusHideSent = true;
                service.send(message);
                if (visible) statusHideSent = false;
                String report = "Status request visible=" + visible + " display=0 reason=" + reason;
                if ("renew".equals(reason) || "transition-settled".equals(reason)) Log.d(TAG, report);
                else Log.i(TAG, report);
            }
            void failed(String reason) {
                if (connection != this) return;
                Log.w(TAG, reason);
                nextConnectAt = SystemClock.elapsedRealtime() + RECONNECT_MS;
                release(true);
            }
            void close(boolean show) {
                main.removeCallbacks(timeout);
                if (show && hideSent && service != null) {
                    try { send(true, "restore"); }
                    catch (Exception | LinkageError e) { Log.w(TAG, "Dock restore request failed", e); }
                }
                if (show && statusHideSent && service != null) {
                    try { sendStatus(true, "restore"); }
                    catch (Exception | LinkageError e) { Log.w(TAG, "Status restore request failed", e); }
                }
                service = null;
                if (bound) {
                    bound = false;
                    try { context.unbindService(this); } catch (RuntimeException ignored) {}
                }
            }
            @Override public void onServiceDisconnected(ComponentName name) { failed("QGBus disconnected"); }
            @Override public void onBindingDied(ComponentName name) { failed("QGBus binding died"); }
            @Override public void onNullBinding(ComponentName name) { failed("QGBus null binding"); }
        }
    }
}
