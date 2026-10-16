package com.shilapi.xcertplay.voyah;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;


/** Session-owned Qinggan focus adapter, based on VoyahTune CarPlayAudioController. */
public final class VoyahAudioFocus implements AutoCloseable {
    private static final String TAG = "CarPlayAudio";
    private final String ownerPackage;
    private final String client = "VoyahPlay_CarPlay_" + java.util.UUID.randomUUID();
    private final java.util.function.Consumer<String> report;
    private final Context context;
    private final HandlerThread thread = new HandlerThread(TAG);
    private final Handler worker;
    private volatile int wanted = -1;
    private volatile boolean closing;
    private OemAudio api;
    private int applied = -1;
    private boolean ownsRequest;
    private final Runnable retry = this::applyFocus;

    public VoyahAudioFocus(Context context, java.util.function.Consumer<String> report) {
        this.context = context.getApplicationContext();
        this.ownerPackage = context.getPackageName();
        this.report = report;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public void setFocus(int stream) {
        if (closing || (stream != -1 && !validStream(stream))) return;
        wanted = stream;
        worker.post(this::applyFocus);
    }

    private static boolean validStream(int stream) { return stream==1 || stream==3 || stream==6 || stream==22; }

    private OemAudio api() throws Exception {
        if (api == null || !api.alive()) {
            api = new OemAudio(context, ownerPackage, client);
            applied = -1;
        }
        return api;
    }

    private void applyFocus() {
        worker.removeCallbacks(retry);
        int next = wanted;
        if (next == applied && (api == null || api.alive()) &&
                (next != -1 || !ownsRequest)) return;
        try {
            if (ownsRequest) {
                api().abandon();
                ownsRequest = false;
            }
            applied = -1;
            if (next == -1 || closing) return;
            // Android media focus already resolves to OEM stream 3. Keep its MediaSession
            // owner, including play/pause while the iPhone is paused.
            if (next == 3) { applied = next; return; }
            OemAudio target = api();
            ownsRequest = true;
            int result = target.request(VoyahAudioPolicy.forStream(next));
            if (result == 1) applied = next;
            String line = "Audio: Voyah OEM focus stream=" + next + " granted=" + result;
            Log.i(TAG, line);
            try { report.accept(line); } catch (RuntimeException ignored) {}
        } catch (Exception | LinkageError error) {
            Log.w(TAG, "OEM audio focus unavailable", error);
            try { report.accept("Audio: Voyah OEM focus unavailable: " + error.getClass().getSimpleName()); }
            catch (RuntimeException ignored) {}
            if (api != null && !api.alive()) { api = null; ownsRequest = false; }
            applied = -1;
            if (!closing && (wanted != -1 || ownsRequest)) worker.postDelayed(retry, 5000);
        }
        if (wanted != next && !closing) worker.post(this::applyFocus);
    }

    @Override public synchronized void close() {
        if (closing) return;
        closing = true;
        wanted = -1;
        worker.removeCallbacks(retry);
        worker.post(() -> { applyFocus(); thread.quitSafely(); });
    }

    /** Load the installed OEM SDK and its Binder proxy; never copy transaction numbers. */
    private static final class OemAudio {
        final IBinder binder;
        final Object service, manager, listener;
        final Method request, abandon;

        final String ownerPackage, client;
        OemAudio(Context context, String ownerPackage, String client) throws Exception {
            this.ownerPackage = ownerPackage;
            this.client = client;
            Class<?> managerClass = sdkClass(context);
            ClassLoader loader = managerClass.getClassLoader();
            binder = (IBinder) loader.loadClass("com.qinggan.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "qg.audio_policy");
            if (binder == null || !binder.isBinderAlive() ||
                    !"com.qinggan.audiopolicy.IAudioPolicyService".equals(binder.getInterfaceDescriptor()))
                throw new IllegalStateException("OEM audio service unavailable");
            Class<?> serviceClass = loader.loadClass("com.qinggan.audiopolicy.IAudioPolicyService");
            service = loader.loadClass("com.qinggan.audiopolicy.IAudioPolicyService$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Class<?> init = loader.loadClass("com.qinggan.audiopolicy.AudioPolicyManager$OnInitListener");
            manager = managerClass.getMethod("getInstance", Context.class, init).invoke(null, context, null);
            if (manager == null) throw new IllegalStateException("OEM audio manager unavailable");
            Class<?> policy = loader.loadClass("com.qinggan.audiopolicy.AudioPolicyManager$OnAudioPolicyListener");
            listener = Proxy.newProxyInstance(loader, new Class<?>[] {policy}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    return client;
                }
                Log.d(TAG, "OEM focus callback=" + method.getName());
                return false; // Playback is controlled by the sink's Android focus listener.
            });
            request = managerClass.getMethod("requestAudioFocus", String.class, policy,
                    int.class, int.class, String.class, int.class, int.class);
            abandon = managerClass.getMethod("abandonAudioFocus", policy, String.class, String.class, boolean.class);
        }
        boolean alive() { return binder.isBinderAlive(); }
        int request(VoyahAudioPolicy attributes) throws Exception {
            return (Integer) request.invoke(manager, client, listener, attributes.usage, attributes.content,
                    ownerPackage, attributes.gain, 0);
        }
        void abandon() throws Exception { abandon.invoke(manager, listener, client, ownerPackage, true); }
        private static Class<?> sdkClass(Context context) throws Exception {
            String name = "com.qinggan.audiopolicy.AudioPolicyManager";
            try { return Class.forName(name); } catch (ClassNotFoundException absent) {
                for (String pkg : new String[] {"com.qinggan.audiopolicy.service", "com.qinggan.app.setting"}) {
                    ApplicationInfo app;
                    try { app = context.getPackageManager().getApplicationInfo(pkg, 0); }
                    catch (android.content.pm.PackageManager.NameNotFoundException absentPackage) { continue; }
                    if ((app.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 0)
                        throw new IllegalStateException("OEM SDK must be a system package");
                    try {
                        return context.createPackageContext(pkg, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY)
                                .getClassLoader().loadClass(name);
                    } catch (ClassNotFoundException ignored) {}
                }
                throw absent;
            }
        }
    }
}
