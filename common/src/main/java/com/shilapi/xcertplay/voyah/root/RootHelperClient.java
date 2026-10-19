package com.shilapi.xcertplay.voyah.root;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Process;
import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** App-side transport. Only an absent connection permits a direct-su fallback. */
final class RootHelperClient {
    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "VoyahRootDeadline"); thread.setDaemon(true); return thread;
    });

    static final class Unavailable extends IOException {
        Unavailable(IOException cause) { super("Root-помощник не подключён. Во время загрузки дождитесь запуска; первичная установка автозапуска — root-helper.ps1 -Mode Install. Ручное восстановление — -Mode Start. После переустановки APK с новым UID выполните -Mode Install.\nПричина подключения: " + cause + "", cause); }
    }

    static RootProcess.Result call(RootHelperProtocol.Action action, int seconds) throws IOException {
        return call(Process.myUid(), new RootHelperProtocol.Request(action), seconds);
    }

    static RootProcess.Result call(int uid, RootHelperProtocol.Request request, int seconds) throws IOException {
        LocalSocket socket = new LocalSocket();
        AtomicBoolean expired = new AtomicBoolean();
        ScheduledFuture<?> alarm = DEADLINES.schedule(() -> {
            expired.set(true);
            try { socket.close(); } catch (IOException ignored) {}
        }, seconds, TimeUnit.SECONDS);
        boolean connected = false;
        try {
            if (expired.get()) throw new IOException("Helper deadline expired before connect");
            socket.connect(new LocalSocketAddress(RootHelperProtocol.socketName(uid), LocalSocketAddress.Namespace.ABSTRACT));
            connected = true;
            if (expired.get()) throw new IOException("Helper deadline expired during connect");
            socket.setSoTimeout(seconds * 1000);
            if (socket.getPeerCredentials().getUid() != 0) throw new IOException("Сокет занят процессом без UID 0; запрос не отправлен");
            RootHelperProtocol.writeRequest(socket.getOutputStream(), request);
            return RootHelperProtocol.readResult(socket.getInputStream());
        } catch (IOException error) {
            if (!connected && !expired.get()) throw new Unavailable(error);
            // A request may already have executed. Never retry through another transport.
            return new RootProcess.Result(-1, expired.get(), "TRANSPORT=root-helper\n" +
                    (expired.get() ? "Тайм-аут ответа помощника" : error.toString()) +
                    "\nПовторная команда автоматически не отправлялась.");
        } finally {
            alarm.cancel(false);
            try { socket.close(); } catch (IOException ignored) {}
        }
    }
}
