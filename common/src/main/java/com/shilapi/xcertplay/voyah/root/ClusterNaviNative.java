package com.shilapi.xcertplay.voyah.root;

import android.content.Context;
import android.os.*;
import com.shilapi.xcertplay.hud.VoyahClusterCompatibility;
import com.shilapi.xcertplay.voyah.VoyahClusterProbeSpec;
import dalvik.system.PathClassLoader;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Separate, bounded root process so a dead application cannot prevent NAVI rollback. */
public final class ClusterNaviNative {
    private static final String PACKAGE = "com.qinggan.cluster", API = PACKAGE + ".IInstrumentClusterManagerService";
    private static final String SERVICE = "qg.cluster.service";
    private static final String CALLBACK = PACKAGE + ".IInstrumentClusterManagerCallback";
    private static final String VISIBLE = "com.qg:activityState.visible";
    private static final File FILE = new File(RootHelperProtocol.DIRECTORY, "cluster-probe.json");
    private ClusterNaviNative() {}

    public static void main(String[] args) {
        try {
            if (android.os.Process.myUid() != 0 || Build.VERSION.SDK_INT != 30 || args.length != 1 ||
                    !("prepare".equals(args[0]) || "restore".equals(args[0]) || "ready".equals(args[0]))) throw new SecurityException("Fixed root NAVI command required");
            RootHelperOperations.verifyHome();
            if (Looper.myLooper() == null) Looper.prepare();
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Object system = thread.getMethod("systemMain").invoke(null);
            Context context = (Context) thread.getMethod("getSystemContext").invoke(system);
            new Thread(() -> {
                int code = 1;
                try { execute(context, args[0]); code = 0; }
                catch (Throwable error) { error.printStackTrace(System.err); }
                System.exit(code);
            }, "VoyahNaviNative").start();
            Looper.loop();
        } catch (Throwable error) { error.printStackTrace(System.err); System.exit(1); }
    }

    private static void execute(Context context, String action) throws Exception {
        RootHelperOperations.privatePath(FILE, false);
        if (FILE.length() > 4096) throw new IOException("Invalid NAVI journal");
        JSONObject lease = new JSONObject(new String(Files.readAllBytes(FILE.toPath()), StandardCharsets.UTF_8));
        String mode = lease.optString("mode");
        if (!VoyahClusterProbeSpec.navi(mode) || !lease.optString("token").matches("[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}"))
            throw new IOException("Not a NAVI test lease");
        int requestedMode = VoyahClusterProbeSpec.nativeMode(mode);
        String requestedCategory = ClusterNaviTestState.category(requestedMode);
        if (!"restore".equals(action) && !VoyahClusterProbeSpec.validLease(lease.getString("token"), mode,
                lease.getInt("display"), lease.getLong("deadline"), SystemClock.elapsedRealtime())) throw new IOException("Expired NAVI test");
        if ("restore".equals(action) && (!lease.has("naviPreviousMode") || lease.optBoolean("naviRestored"))) return;
        String profile = VoyahClusterCompatibility.INSTANCE.verify(context);
        try (Link link = new Link(context, "restore".equals(action) ? SystemClock.elapsedRealtime() + 6000 : lease.getLong("deadline"))) {
            link.connect();
            if ("prepare".equals(action)) {
                if (lease.has("naviPreviousMode")) throw new IOException("NAVI snapshot already exists");
                int before = link.mode();
                String category = lease.getString("naviReturnCategory");
                if ("qg.car.cluster.NAVI".equals(category))
                    category = ClusterNaviTestState.NAVI + (before == 1 ? "_SMALL" : before == 3 ? "_AR" : "_FULL");
                ClusterNaviTestState.validate(before, category);
                lease.put("naviPreviousMode", before).put("naviPreviousReady", link.visible())
                        .put("naviReturnCategory", category).put("naviProfile", profile)
                        .put("naviRequestedMode", requestedMode).put("naviRequestedCategory", requestedCategory);
                System.out.println("CLUSTER_NAVI_STAGE=request_area\nCLUSTER_NAVI_REQUESTED_MODE=" + requestedMode +
                        "\nCLUSTER_NAVI_REQUESTED_CATEGORY=" + requestedCategory);
                ClusterNaviTestState.begin(link, () -> save(lease), requestedMode);
                lease.put("naviAreaGranted", true); save(lease);
                System.out.println("CLUSTER_NAVI_AREA_GRANTED=1\nCLUSTER_NAVI_FRAME_CONFIRMED=0");
            } else if ("ready".equals(action)) {
                if (!lease.has("naviPreviousMode") || lease.optBoolean("naviRestored") || lease.getLong("deadline") <= SystemClock.elapsedRealtime())
                    throw new IOException("NAVI test retired");
                if (!lease.optBoolean("naviAreaGranted")) throw new IOException("NAVI area was not granted");
                System.out.println("CLUSTER_NAVI_STAGE=confirm_first_frame");
                ClusterNaviTestState.firstFrame(link, requestedMode);
                lease.put("naviFrameConfirmed", true); save(lease);
                System.out.println("CLUSTER_NAVI_FRAME_CONFIRMED=1");
            } else {
                ClusterNaviTestState.restore(link, lease.getInt("naviPreviousMode"),
                        lease.getBoolean("naviPreviousReady"), lease.getString("naviReturnCategory"), requestedMode);
                lease.put("naviRestored", true); save(lease);
                System.out.println("CLUSTER_NAVI_MODE_AFTER=" + link.mode());
            }
            System.out.println("CLUSTER_NAVI_" + action.toUpperCase(java.util.Locale.ROOT) + "=1\nCLUSTER_NAVI_PROFILE=" + profile);
        }
    }
    private static void save(JSONObject lease) throws Exception {
        RootHelperBoot.writePrivate(FILE.getName(), lease.toString().getBytes(StandardCharsets.UTF_8));
    }

    static IBinder publishedService() throws Exception {
        // The reviewed OEM service publishes the same manager binder in onCreate().
        // app_process is not an ActivityManager application and cannot bindService.
        return requireService((IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("checkService", String.class).invoke(null, SERVICE));
    }
    static IBinder requireService(IBinder binder) throws Exception {
        if (binder == null || !binder.isBinderAlive() || !API.equals(binder.getInterfaceDescriptor()))
            throw new IOException("Published NAVI binder unavailable; no state changed");
        return binder;
    }

    private static final class Link implements ClusterNaviTestState.Port, AutoCloseable {
        private final Context context;
        private final long deadline;
        private final ClusterNaviPresentation presentation = new ClusterNaviPresentation();
        private final CountDownLatch snapshot = new CountDownLatch(1);
        private volatile Bundle navi;
        private Class<?> contract, state, callbackContract; private Object proxy, naviMode, callback;
        private final Binder listener = new Binder() {
            { attachInterface(null, CALLBACK); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == IBinder.INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(CALLBACK); return true; }
                if (code >= 1 && code <= 10) {
                    data.enforceInterface(CALLBACK);
                    if (code == 2) {
                        data.readString(); String category = data.readString();
                        Bundle value = data.readInt() == 0 ? null : Bundle.CREATOR.createFromParcel(data);
                        if (ClusterNaviTestState.NAVI.equals(category) && value != null && value.containsKey(VISIBLE)) {
                            navi = value; snapshot.countDown();
                        }
                    } else if (code == 3) {
                        presentation.receive(data.readString(), data.readString(), data.readInt());
                    }
                    return true;
                }
                return super.onTransact(code, data, reply, flags);
            }
        };
        Link(Context context, long deadline) { this.context = context; this.deadline = deadline; }
        void connect() throws Exception {
            IBinder binder = publishedService();
            ClassLoader loader = new PathClassLoader("/system/framework/QGAPI.jar", context.getClassLoader());
            contract = Class.forName(API, true, loader); state = Class.forName(PACKAGE + ".ClusterState", true, loader);
            proxy = Class.forName(API + "$Stub", true, loader).getMethod("asInterface", IBinder.class).invoke(null, binder);
            naviMode = state.getField("NAVI_SHOW_TYPE").get(null);
            Class<?> cb = Class.forName(CALLBACK, true, loader); callbackContract = cb;
            callback = Class.forName(CALLBACK + "$Stub", true, loader).getMethod("asInterface", IBinder.class).invoke(null, listener);
            contract.getMethod("registerCallback", cb).invoke(proxy, callback);
            if (!snapshot.await(2, TimeUnit.SECONDS)) throw new IOException("Original NAVI readiness unavailable; no state changed");
        }
        boolean visible() throws IOException { Bundle value = navi; if (value == null) throw new IOException("Missing NAVI state"); return value.getBoolean(VISIBLE); }
        public int mode() throws Exception { return (Integer) contract.getMethod("getClusterState", state).invoke(proxy, naviMode); }
        public void mode(int value) throws Exception { contract.getMethod("setClusterState", state, int.class).invoke(proxy, naviMode, value); }
        public void ready(boolean value) throws Exception { contract.getMethod("syncReadyState", String.class, int.class).invoke(proxy, ClusterNaviTestState.NAVI, value ? 1 : 0); }
        public void request(String category) throws Exception {
            presentation.begin(category, SystemClock.elapsedRealtime(), deadline);
            contract.getMethod("requestPresentation", String.class, int.class).invoke(proxy, category, 1);
            presentation.awaitGrant(SystemClock::elapsedRealtime);
        }
        public void frame(String category) throws Exception {
            presentation.requireGrant(category, SystemClock.elapsedRealtime());
            contract.getMethod("notifyReadyToShow", String.class).invoke(proxy, category);
            presentation.requireGrant(category, SystemClock.elapsedRealtime());
        }
        public void show(String category) throws Exception { request(category); frame(category); }
        public void hide(String category) throws Exception { contract.getMethod("requestPresentation", String.class, int.class).invoke(proxy, category, 2); }
        public void close() {
            presentation.cancel();
            try { if (callback != null && proxy != null) contract.getMethod("unregisterCallback", callbackContract).invoke(proxy, callback); }
            catch (Exception ignored) { }
        }
    }
}
