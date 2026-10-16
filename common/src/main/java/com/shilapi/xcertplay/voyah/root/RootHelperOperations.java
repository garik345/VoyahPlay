package com.shilapi.xcertplay.voyah.root;

import android.system.Os;
import android.system.StructStat;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/** Package identity and private helper storage shared by fixed root operations. */
final class RootHelperOperations {
    static int installedUid() throws Exception {
        RootProcess.Result result = RootProcess.run(4, "/system/bin/cmd", "package", "list", "packages", "--user", "0", "-U", RootHelperProtocol.PACKAGE);
        if (!result.successful()) throw new IOException("Cannot resolve current VoyahPlay UID: " + result.output);
        return parseUid(result.output);
    }

    static int parseUid(String output) throws IOException {
        Matcher match = Pattern.compile("(?m)^package:com\\.shilapi\\.xcertplay\\s+uid:([0-9]+)\\s*$").matcher(output);
        if (!match.find()) throw new IOException("VoyahPlay is absent or its UID could not be resolved");
        int uid;
        try { uid = Integer.parseInt(match.group(1)); }
        catch (NumberFormatException error) { throw new IOException("Invalid package UID", error); }
        if (match.find()) throw new IOException("Ambiguous package UID");
        return uid;
    }

    static void privatePath(File file, boolean directory) throws Exception {
        StructStat stat = Os.lstat(file.getPath());
        int type = stat.st_mode & 0170000;
        if (stat.st_uid != 0 || (stat.st_mode & 077) != 0 || type != (directory ? 0040000 : 0100000)) {
            throw new IOException("Root-owned private " + (directory ? "directory" : "file") + " required: " + file);
        }
    }

    static void verifyHome() throws Exception {
        privatePath(new File(RootHelperProtocol.DIRECTORY), true);
        File marker = new File(RootHelperProtocol.DIRECTORY, "owner");
        privatePath(marker, false);
        byte[] bytes = new byte[128];
        try (InputStream input = new FileInputStream(marker)) {
            int size = input.read(bytes);
            if (size < 1 || input.read() != -1 || !new String(bytes, 0, size, StandardCharsets.UTF_8).trim().equals(RootHelperProtocol.OWNER)) {
                throw new IOException("Unknown helper directory owner");
            }
        }
    }

}
