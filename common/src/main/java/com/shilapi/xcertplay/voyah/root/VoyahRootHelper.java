package com.shilapi.xcertplay.voyah.root;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;

/** Root entry point launched by the fixed boot script or ADB, never by an exported service. */
public final class VoyahRootHelper {
    private final int allowedUid;
    private final LocalServerSocket server;
    private final ReentrantLock mutations = new ReentrantLock();
    private final AtomicBoolean listenerClosed = new AtomicBoolean();
    private volatile boolean stopping;

    private VoyahRootHelper(int uid) throws Exception {
        allowedUid = uid;
        RootHelperOperations.verifyHome();
        RootHelperOperations.privatePath(new File(RootHelperProtocol.APK), false);
        RootHelperIdentity.verifyInstalled(uid);
        server = new LocalServerSocket(RootHelperProtocol.socketName(uid));
    }

    public static void main(String[] arguments) {
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root launch through ADB required");
            if (arguments.length != 2) throw new IllegalArgumentException("Expected a fixed helper action and UID");
            int uid = Integer.parseInt(arguments[1]);
            RootHelperProtocol.socketName(uid); // Validate primary-user UID before any operation.
            switch (arguments[0]) {
                case "--check":
                    RootHelperIdentity.verifyInstalled(uid);
                    System.out.println("HELPER_CODE=1\nHELPER_VERSION=" + RootHelperProtocol.VERSION + "\nSERVER_UID=0");
                    return;
                case "--serve":
                    new VoyahRootHelper(uid).serve();
                    // This is a separate app_process helper, not the application's VM.
                    System.exit(0);
                    return;
                case "--install-autostart":
                case "--remove-autostart":
                case "--autostart-on":
                case "--autostart-off":
                case "--retire-legacy":
                    RootHelperOperations.verifyHome();
                    if (RootHelperOperations.installedUid() != uid) throw new IOException("Package UID mismatch");
                    RootProcess.Result setup;
                    switch (arguments[0]) {
                        case "--install-autostart": setup = RootHelperBoot.install(); break;
                        case "--remove-autostart": setup = RootHelperBoot.remove(); break;
                        case "--autostart-on": setup = RootHelperBoot.policy(true); break;
                        case "--autostart-off": setup = RootHelperBoot.policy(false); break;
                        default: setup = LegacyModuleCleanup.retire(); break;
                    }
                    System.out.print(setup.output);
                    if (!setup.successful()) System.exit(1);
                    return;
                case "--probe":
                case "--stop":
                    RootProcess.Result result = RootHelperClient.call(uid, new RootHelperProtocol.Request(
                            arguments[0].equals("--probe") ? RootHelperProtocol.Action.INFO : RootHelperProtocol.Action.STOP), 12);
                    System.out.print(result.output);
                    if (!result.successful()) System.exit(1);
                    return;
                default: throw new IllegalArgumentException("Unknown helper entry command");
            }
        } catch (RootHelperClient.Unavailable error) {
            System.err.println("HELPER_OFFLINE\n" + error.getMessage()); System.exit(2);
        } catch (Throwable error) {
            error.printStackTrace(System.err); System.exit(1);
        }
    }

    private void serve() throws Exception {
        ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(4), task -> {
                    Thread thread = new Thread(task, "VoyahRootRequest"); thread.setDaemon(true); return thread;
                });
        File pid = new File(RootHelperProtocol.DIRECTORY, "server.pid");
        String ownRecord = null;
        try {
            String stat;
            try (BufferedReader input = new BufferedReader(new FileReader("/proc/self/stat"))) { stat = input.readLine(); }
            String start = stat.substring(stat.lastIndexOf(')') + 2).split("\\s+")[19];
            String boot = new String(java.nio.file.Files.readAllBytes(new File("/proc/sys/kernel/random/boot_id").toPath()), StandardCharsets.UTF_8).trim();
            if (!boot.matches("[a-fA-F0-9-]{36}")) throw new IOException("Boot identity unavailable");
            ownRecord = boot + " " + Process.myPid() + " " + start + "\n";
            try (Writer output = new OutputStreamWriter(new FileOutputStream(pid), StandardCharsets.UTF_8)) {
                output.write(ownRecord);
            }
            android.system.Os.chmod(pid.getPath(), 0600);
            System.out.println("HELPER_READY=1 UID=0 CLIENT_UID=" + allowedUid);
            while (!stopping) {
                LocalSocket connection;
                try { connection = server.accept(); }
                catch (IOException error) { if (stopping) break; throw error; }
                try { workers.execute(() -> handle(connection)); }
                catch (RejectedExecutionException busy) { connection.close(); }
            }
        } finally {
            closeListener();
            workers.shutdown();
            try { workers.awaitTermination(6, TimeUnit.SECONDS); }
            finally {
                if (ownRecord != null && pid.isFile() && ownRecord.equals(new String(java.nio.file.Files.readAllBytes(pid.toPath()), StandardCharsets.UTF_8))) pid.delete();
            }
        }
    }

    private void closeListener() {
        if (!listenerClosed.compareAndSet(false, true)) return;
        try {
            FileDescriptor descriptor = server.getFileDescriptor();
            if (descriptor != null) Os.shutdown(descriptor, OsConstants.SHUT_RDWR);
        } catch (Exception error) {
            System.err.println("HELPER_LISTENER_SHUTDOWN " + error);
        } finally {
            try { server.close(); } catch (IOException error) {
                System.err.println("HELPER_LISTENER_CLOSE " + error);
            }
        }
    }

    private void handle(LocalSocket socket) {
        boolean stopAfterReply = false;
        try (LocalSocket connection = socket) {
            connection.setSoTimeout(6000);
            int peer = connection.getPeerCredentials().getUid();
            if (peer != 0 && peer != allowedUid) throw new SecurityException("Unexpected peer UID");
            RootHelperProtocol.Request request = RootHelperProtocol.readRequest(connection.getInputStream());
            int currentUid = peer == 0 ? allowedUid : RootHelperOperations.installedUid();
            if (!RootHelperProtocol.authorized(peer, allowedUid, currentUid, request.action)) {
                throw new SecurityException("Current package UID does not authorize this action");
            }
            RootProcess.Result result;
            boolean changes = request.action == RootHelperProtocol.Action.BOOT_POLICY ||
                    request.action == RootHelperProtocol.Action.STOP || request.action == RootHelperProtocol.Action.TWEAKS_POLICY ||
                    request.action == RootHelperProtocol.Action.TOP_POLICY;
            boolean locked = !changes || mutations.tryLock();
            if (!locked) {
                result = new RootProcess.Result(1, false, "HELPER_BUSY: another helper change is running\n");
            } else {
                try {
                    if (stopping) throw new IOException("Helper is stopping");
                    switch (request.action) {
                        case ROOT: result = new RootProcess.Result(0, false, "uid=0(root)\nCLIENT_UID=" + allowedUid + "\n"); break;
                        case INFO: result = new RootProcess.Result(0, false, "HELPER_READY=1\nSERVER_UID=0\nCLIENT_UID=" + allowedUid +
                                "\nCAPABILITIES=" + TweaksBoundsRoot.CAPABILITY + "\n" + RootHelperBoot.status()); break;
                        case BOOT_STATUS: result = new RootProcess.Result(0, false, RootHelperBoot.status()); break;
                        case BOOT_POLICY: result = RootHelperBoot.policy(request.active); break;
                        case TWEAKS_STATUS: result = TweaksBoundsRoot.status(); break;
                        case TWEAKS_POLICY: result = TweaksBoundsRoot.policy(request.active, request.hidden); break;
                        case TOP_POLICY: result = TopBoundsRoot.policy(request.active); break;
                        case STOP:
                            TopBoundsRoot.policy(false);
                            result = TweaksBoundsRoot.policy(false, false);
                            if (result.successful()) {
                                stopping = true; stopAfterReply = true;
                                result = new RootProcess.Result(0, false, "HELPER_STOPPED=1\nTWEAKS_LEASE=0\n");
                            }
                            break;
                        default: throw new IOException("Unsupported action");
                    }
                } catch (Exception error) {
                    result = new RootProcess.Result(1, false, error.getClass().getSimpleName() + ": " + error.getMessage() + "\n");
                } finally { if (changes) mutations.unlock(); }
            }
            RootHelperProtocol.writeResult(connection.getOutputStream(), Process.myUid(), result);
        } catch (Exception error) {
            System.err.println("REQUEST_REJECTED " + error.getClass().getSimpleName() + ": " + error.getMessage());
        } finally {
            if (stopAfterReply) closeListener();
        }
    }
}
