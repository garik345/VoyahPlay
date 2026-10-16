package com.shilapi.xcertplay.voyah.root;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** One bounded, fixed-action request per authenticated Unix connection. No shell text. */
final class RootHelperProtocol {
    static final int MAGIC = 0x56525031; // VRP1
    static final int MAX_OUTPUT = 65536;
    static final String VERSION = "1.1.2";
    static final String PACKAGE = "com.shilapi.xcertplay";
    static final String DIRECTORY = "/data/local/voyahplay-root-helper";
    static final String APK = DIRECTORY + "/app.apk";
    static final String OWNER = "voyahplay-root-helper-v1";
    static final String MODULE = "/data/local/voyahplay-fullscreen/payload/module.sh";

    // Append only: preserve installed helper action ordinals.
    enum Action { ROOT, STATUS, INSTALL, ENABLE, DISABLE, REMOVE, POLICY, INFO, STOP, TWEAKS_STATUS, TWEAKS_POLICY, BOOT_STATUS, BOOT_POLICY }

    static boolean authorized(int peer, int owner, int currentPackageUid, Action action) {
        if (peer == 0) return action == Action.INFO || action == Action.STOP;
        return peer == owner && currentPackageUid == owner && (action == Action.ROOT || action == Action.INFO
                || action == Action.TWEAKS_STATUS || action == Action.TWEAKS_POLICY
                || action == Action.BOOT_STATUS || action == Action.BOOT_POLICY);
    }

    static String socketName(int uid) {
        if (uid < 10000 || uid >= 20000) throw new IllegalArgumentException("Primary-user application UID required");
        return "voyahplay.root.v1." + uid;
    }

    static final class Request {
        final Action action;
        final boolean active, hidden;
        Request(Action action, boolean active, boolean hidden) {
            if (action == null || ((action != Action.POLICY && action != Action.TWEAKS_POLICY && action != Action.BOOT_POLICY) && (active || hidden))
                    || (action == Action.BOOT_POLICY && hidden)) {
                throw new IllegalArgumentException("Invalid command fields");
            }
            this.action = action; this.active = active; this.hidden = hidden;
        }
        Request(Action action) { this(action, false, false); }
    }

    static Request readRequest(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        if (input.readInt() != MAGIC) throw new IOException("Unsupported helper protocol");
        int action = input.readUnsignedByte(), active = input.readUnsignedByte(), hidden = input.readUnsignedByte();
        if (action >= Action.values().length || active > 1 || hidden > 1) throw new IOException("Invalid helper command");
        try { return new Request(Action.values()[action], active == 1, hidden == 1); }
        catch (IllegalArgumentException error) { throw new IOException(error); }
    }

    static void writeRequest(OutputStream stream, Request request) throws IOException {
        DataOutputStream output = new DataOutputStream(stream);
        output.writeInt(MAGIC); output.writeByte(request.action.ordinal());
        output.writeByte(request.active ? 1 : 0); output.writeByte(request.hidden ? 1 : 0); output.flush();
    }

    static void writeResult(OutputStream stream, int uid, RootProcess.Result result) throws IOException {
        byte[] text = ("HELPER_VERSION=" + VERSION + "\n" + result.output).getBytes(StandardCharsets.UTF_8);
        if (text.length > MAX_OUTPUT) throw new IOException("Helper response exceeds limit");
        DataOutputStream output = new DataOutputStream(stream);
        output.writeInt(MAGIC); output.writeInt(uid); output.writeInt(result.exitCode);
        output.writeBoolean(result.timedOut); output.writeInt(text.length); output.write(text); output.flush();
    }

    static RootProcess.Result readResult(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream);
        if (input.readInt() != MAGIC || input.readInt() != 0) throw new IOException("Invalid root helper identity/protocol");
        int code = input.readInt(); boolean timeout = input.readBoolean(); int size = input.readInt();
        if (size < 0 || size > MAX_OUTPUT) throw new IOException("Invalid helper response length");
        byte[] bytes = new byte[size]; input.readFully(bytes);
        return new RootProcess.Result(code, timeout,
                "TRANSPORT=root-helper\n" + new String(bytes, StandardCharsets.UTF_8));
    }
}
