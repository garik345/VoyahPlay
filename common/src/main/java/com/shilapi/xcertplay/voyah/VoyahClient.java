package com.shilapi.xcertplay.voyah;

import android.content.*;
import android.content.pm.PackageInfo;
import android.os.*;
import org.json.JSONObject;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Exact OEM build, three reviewed cache getters; never sends vehicle commands. */
public final class VoyahClient {
    static final String BUSY_MESSAGE = "Предыдущий запрос ещё выполняется";
    private static final String CAN = "com.qinggan.canbus.service";
    private static final String SIGNAL = "com.qinggan.carsignal.service";
    private static final String CAN_DESCRIPTOR = "com.qinggan.canbus.ICanBusService";
    private static final String SIGNAL_DESCRIPTOR = "com.qinggan.carsignal.ICarSignalService";
    private static final String CAN_HASH = "96ac5182e795ad70c43c78f26b9cf29e76b59db67c2d5c09216ba1d8425c427c";
    private static final String SIGNAL_HASH = "2bb3e7df1cd672578deeb0e106da8a864adff7d9431784a9a27cbcb8e189f887";
    // Shared across both activities: a stuck synchronous Binder cannot accumulate threads/tasks.
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Request current;

    public interface Listener { void complete(VoyahValues values, String error); }
    private static final class Request {
        volatile boolean cancelled;
        final ArrayList<ServiceConnection> connections = new ArrayList<>(); // main thread only
    }
    public VoyahClient(Context context) { this.context = context.getApplicationContext(); }

    /** Called only on the main thread. Results also arrive on the main thread. */
    public void read(Listener listener) {
        if (current != null) return;
        if (!BUSY.compareAndSet(false, true)) {
            listener.complete(null, BUSY_MESSAGE);
            return;
        }
        Request request = new Request();
        current = request;
        Runnable timeout = () -> {
            if (current != request) return;
            cancel();
            listener.complete(null, "Сервис не ответил. Повторите включение данных");
        };
        main.postDelayed(timeout, 6000);
        WORKER.execute(() -> {
            VoyahValues result = null;
            String error = null;
            try {
                verify(CAN, CAN_HASH, request);
                verify(SIGNAL, SIGNAL_HASH, request);
                IBinder signal = connect(request, SIGNAL, SIGNAL + ".CarSignalService", SIGNAL_DESCRIPTOR);
                check(request);
                int type = vehicleType(signal);
                if (type != 134 && type != 13401 && type != 13402)
                    throw new IOException("Неподдерживаемый тип Voyah: " + type);
                IBinder can = connect(request, CAN, CAN + ".CanBusService", CAN_DESCRIPTOR);
                check(request);
                JSONObject battery = readCache(can, 71);
                check(request);
                JSONObject fuel = readCache(can, 9);
                check(request);
                JSONObject climate = readCache(can, 30);
                check(request);
                JSONObject doors = readCache(can, 2);
                check(request);
                JSONObject windows = readCache(can, 44);
                check(request);
                result = new VoyahValues(value(battery, "value"), value(fuel, "mPercentage"),
                    value(climate, "airLeftTemperature"), value(climate, "airRightTemperature"),
                    climate == null ? -1 : climate.optInt("airWindSpeed", -1), SystemClock.elapsedRealtime(),
                    states(doors, "fLDoor", "fRDoor", "rLDoor", "rRDoor"),
                    states(windows, "fLWindow", "fRWindow", "rLWindow", "rRWindow"));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                BUSY.set(false);
            }
            final VoyahValues values = result;
            final String failure = error;
            main.post(() -> {
                main.removeCallbacks(timeout);
                release(request);
                if (current != request || request.cancelled) return;
                current = null;
                listener.complete(values, failure);
            });
        });
    }

    private static int[] states(JSONObject data, String... keys) {
        int[] values = new int[keys.length];
        for (int i = 0; i < keys.length; i++) values[i] = data == null ? -1 : data.optInt(keys[i], -1);
        return values;
    }

    public void cancel() {
        if (current != null) {
            current.cancelled = true;
            release(current);
            current = null;
        }
        main.removeCallbacksAndMessages(null);
    }

    private static void check(Request request) throws IOException {
        if (request.cancelled) throw new IOException("Запрос отменён");
    }

    private void verify(String pkg, String expected, Request request) throws Exception {
        check(request);
        PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
        if (info.applicationInfo.splitSourceDirs != null && info.applicationInfo.splitSourceDirs.length > 0)
            throw new IOException("Неподдерживаемая сборка " + pkg);
        File file = new File(info.applicationInfo.sourceDir);
        if (file.length() < 1 || file.length() > 128L * 1024 * 1024) throw new IOException("Неверный размер APK");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536]; int size;
            while ((size = input.read(buffer)) != -1) { check(request); digest.update(buffer, 0, size); }
        }
        StringBuilder hash = new StringBuilder();
        for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
        if (!expected.equals(hash.toString())) throw new IOException("Прошивка не проверена: " + pkg);
    }

    private IBinder connect(Request request, String pkg, String cls, String descriptor) throws Exception {
        check(request);
        CountDownLatch ready = new CountDownLatch(1);
        IBinder[] result = new IBinder[1];
        main.post(() -> {
            if (request.cancelled) { ready.countDown(); return; }
            ServiceConnection connection = new ServiceConnection() {
                public void onServiceConnected(ComponentName name, IBinder binder) { result[0] = binder; ready.countDown(); }
                public void onServiceDisconnected(ComponentName name) { result[0] = null; ready.countDown(); }
                public void onNullBinding(ComponentName name) { ready.countDown(); }
                public void onBindingDied(ComponentName name) { ready.countDown(); }
            };
            request.connections.add(connection);
            try {
                if (!context.bindService(new Intent().setComponent(new ComponentName(pkg, cls)), connection, Context.BIND_AUTO_CREATE))
                    ready.countDown();
            } catch (RuntimeException e) { ready.countDown(); }
        });
        if (!ready.await(4, TimeUnit.SECONDS)) throw new IOException("Нет соединения с " + pkg);
        check(request);
        IBinder binder = result[0];
        if (binder == null || !descriptor.equals(binder.getInterfaceDescriptor())) throw new IOException("Сервис недоступен: " + pkg);
        return binder;
    }

    private void release(Request request) {
        for (ServiceConnection connection : request.connections)
            try { context.unbindService(connection); } catch (RuntimeException ignored) { }
        request.connections.clear();
    }

    private static float value(JSONObject object, String field) {
        return object == null ? Float.NaN : (float) object.optDouble(field, Double.NaN);
    }

    private static int vehicleType(IBinder binder) throws Exception {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SIGNAL_DESCRIPTOR);
            if (!binder.transact(15, data, reply, 0)) throw new IOException("Тип машины недоступен");
            reply.readException();
            if (reply.dataAvail() != 4) throw new IOException("Неверный ответ типа машины");
            return reply.readInt();
        } finally { data.recycle(); reply.recycle(); }
    }

    private static JSONObject readCache(IBinder binder, int code) throws Exception {
        CanSchema.Spec spec = CanSchema.byCode(code);
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(CAN_DESCRIPTOR);
            if (!binder.transact(spec.code, data, reply, 0)) throw new IOException("Данные недоступны");
            reply.readException();
            if (spec.nullable) {
                if (reply.dataAvail() < 4) throw new IOException("Нет маркера объекта");
                int present = reply.readInt();
                if (present == 0 && reply.dataAvail() == 0) return null;
                if (present != 1) throw new IOException("Неверный маркер объекта");
            }
            if (reply.dataAvail() != spec.fields.length * 4) throw new IOException("Формат данных не совпал");
            JSONObject object = new JSONObject();
            for (int i = 0; i < spec.fields.length; i++) {
                if (spec.types.charAt(i) == 'f') {
                    float value = reply.readFloat();
                    object.put(spec.fields[i], Float.isFinite(value) ? (Object) value : JSONObject.NULL);
                } else object.put(spec.fields[i], reply.readInt());
            }
            return object;
        } finally { data.recycle(); reply.recycle(); }
    }
}
