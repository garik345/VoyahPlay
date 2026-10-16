package com.shilapi.xcertplay.voyah.root;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Bounded process I/O. No command interpolation is performed here. */
public final class RootProcess {
    private static final int MAX_OUTPUT = 32768;

    public static final class Result {
        public final int exitCode;
        public final boolean timedOut;
        public final String output;

        Result(int exitCode, boolean timedOut, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.output = output;
        }

        public boolean successful() { return !timedOut && exitCode == 0; }
    }

    private RootProcess() {}

    public static Result run(int timeoutSeconds, String... arguments) throws Exception {
        Process process = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    synchronized (output) {
                        int keep = Math.min(count, MAX_OUTPUT - output.size());
                        if (keep > 0) output.write(buffer, 0, keep);
                    }
                }
            } catch (IOException ignored) { /* A timeout can close the pipe. */ }
        }, "VoyahRootOutput");
        reader.setDaemon(true);
        reader.start();
        boolean finished;
        try {
            finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroy();
                if (!process.waitFor(500, TimeUnit.MILLISECONDS)) process.destroyForcibly();
            }
            reader.join(1000);
            synchronized (output) {
                return new Result(finished ? process.exitValue() : -1, !finished,
                        new String(output.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            if (process.isAlive()) process.destroy();
        }
    }

    static String quote(String value) {
        if (value.indexOf('\0') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Invalid shell argument");
        }
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
