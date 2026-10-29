package com.shilapi.xcertplay.voyah;

import android.content.*;
import android.os.*;
import android.util.Log;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Read-only LightStatus subscription, as used by VoyahTune CanBusEventHub.
 * Wire layout additionally verified against this car's QGVehicle.jar and H97C CAN APK.
 * No vehicle-state commands, reflection or OEM class loading.
 */
public final class VoyahLightStatus {
    private static final String PKG = "com.qinggan.canbus.service";
    private static final String API = "com.qinggan.canbus.ICanBusService";
    private static final String CALLBACK = "com.qinggan.canbus.ICanBusServiceCallback";
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile String verifiedApk;
    public interface Listener { void changed(int mode); }
    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean active;
    private Session session;
    private int mode = -1;
    private final Runnable retry = this::connect;

    public VoyahLightStatus(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }
    /** Lifecycle calls and notifications are confined to the main thread. */
    public void start() { if (!active) { active = true; connect(); } }
    public void stop() {
        active = false;
        main.removeCallbacks(retry);
        if (session != null) { session.close(); session = null; }
        publish(-1);
    }
    private void publish(int next) {
        if (mode != next) { mode = next; listener.changed(next); }
    }
    private void connect() {
        if (!active || session != null) return;
        Session s = new Session(); session = s;
        main.postDelayed(s.timeout, 8000);
        IO.execute(() -> {
            try {
                verify();
                main.post(() -> {
                    if (!s.live()) return;
                    try {
                        s.bound = context.bindService(new Intent().setComponent(
                                new ComponentName(PKG, PKG + ".CanBusService")), s, Context.BIND_AUTO_CREATE);
                        if (!s.bound) s.fail("bindService=false");
                    } catch (RuntimeException e) { s.fail(e.toString()); }
                });
            } catch (Exception e) { main.post(() -> s.fail(e.toString())); }
        });
    }
    // Auto takes priority over actual lamp output (automatic mode can turn the lamps on/off).
    static int decodeMode(int auto, int dipped, int position) {
        if (auto == 1) return 3;
        if (auto != 0 || dipped < 0 || dipped > 1 || position < 0 || position > 1) return -1;
        if (dipped == 1) return 2;
        return position == 1 ? 1 : 0;
    }
    static int readMode(Parcel data) throws IOException {
        if (data.dataAvail() < 4) throw new IOException("Missing LightStatus marker");
        int present = data.readInt();
        if (present == 0 && data.dataAvail() == 0) return -1;
        if (present != 1 || data.dataAvail() != 17 * 4) throw new IOException("Unknown LightStatus layout");
        int[] fields = new int[17];
        for (int i = 0; i < fields.length; i++) fields[i] = data.readInt();
        // H97C setLampStatus sets both positionLamp[8] and headLight[13] from bit 30.
        return decodeMode(fields[16], fields[7], fields[13]);
    }
    private final class Session implements ServiceConnection {
        volatile boolean closed;
        boolean bound;
        IBinder remote;
        final AtomicLong revision = new AtomicLong();
        final Runnable timeout = () -> fail("LightStatus connection timeout");
        final Binder callback = new Binder() {
            { attachInterface(null, CALLBACK); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == 10) {
                    if (closed) return true;
                    try {
                        data.enforceInterface(CALLBACK);
                        int next = readMode(data);
                        long rev = revision.incrementAndGet();
                        main.post(() -> { if (live() && revision.get() == rev) publish(next); });
                    } catch (IOException | RuntimeException e) {
                        long rev = revision.incrementAndGet();
                        main.post(() -> { if (live() && revision.get() == rev) publish(-1); });
                        Log.w("VoyahLightStatus", "Invalid LightStatus", e);
                    }
                    return true;
                }
                // OEM callback interface contains other oneway events; they are not used here.
                if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION) return true;
                return super.onTransact(code, data, reply, flags);
            }
        };
        boolean live() { return !closed && active && session == this; }
        void fail(String reason) {
            if (!live()) return;
            Log.w("VoyahLightStatus", reason);
            close(); session = null; publish(-1);
            main.removeCallbacks(retry); main.postDelayed(retry, 5000);
        }
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!live()) return;
            remote = binder;
            IO.execute(() -> {
                try {
                    if (closed) return;
                    if (!API.equals(binder.getInterfaceDescriptor())) throw new IOException("Unknown CAN interface");
                    register(binder, 28);
                    // An event received during the seed read must win over that older snapshot.
                    long rev = revision.get();
                    Parcel data = Parcel.obtain(), reply = Parcel.obtain();
                    int initial;
                    try {
                        data.writeInterfaceToken(API);
                        if (!binder.transact(5, data, reply, 0)) throw new IOException("getLightStatus unavailable");
                        reply.readException(); initial = readMode(reply);
                    } finally { data.recycle(); reply.recycle(); }
                    main.post(() -> {
                        if (!live()) return;
                        main.removeCallbacks(timeout);
                        if (revision.get() == rev) publish(initial);
                    });
                } catch (Exception e) { main.post(() -> fail(e.toString())); }
            });
        }
        void register(IBinder binder, int code) throws Exception {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(API); data.writeStrongBinder(callback);
                if (!binder.transact(code, data, reply, 0)) throw new IOException("Callback TX" + code + " unavailable");
                reply.readException();
                if (reply.dataAvail() != 4 || reply.readInt() != 1) throw new IOException("Callback TX" + code + " rejected");
            } finally { data.recycle(); reply.recycle(); }
        }
        void close() {
            closed = true; main.removeCallbacks(timeout);
            IBinder binder = remote; remote = null;
            // Same queue as registration: even a late successful add is followed by removal.
            if (binder != null) IO.execute(() -> {
                try { register(binder, 29); }
                catch (Exception e) { Log.w("VoyahLightStatus", "Callback cleanup: " + e); }
            });
            if (bound) { bound = false; try { context.unbindService(this); } catch (RuntimeException ignored) {} }
        }
        @Override public void onServiceDisconnected(ComponentName name) { fail("CAN disconnected"); }
        @Override public void onBindingDied(ComponentName name) { fail("CAN binding died"); }
        @Override public void onNullBinding(ComponentName name) { fail("CAN null binding"); }
    }
    private void verify() throws Exception {
        android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(PKG, 0);
        if (info.splitSourceDirs != null && info.splitSourceDirs.length > 0) throw new IOException("Unknown split CAN APK");
        File file = new File(info.sourceDir);
        if (!file.isFile() || file.length() > 128L * 1024 * 1024) throw new IOException("Unknown CAN APK");
        String stamp = file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
        if (stamp.equals(verifiedApk)) return;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(file)) {
            byte[] b = new byte[65536]; int n;
            while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!VoyahOemBuilds.supportsCanApk(hash.toString())) throw new IOException("CAN APK differs from reviewed builds: " + hash);
        verifiedApk = stamp;
    }
}
