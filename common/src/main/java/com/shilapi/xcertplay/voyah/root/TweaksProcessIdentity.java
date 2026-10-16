package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Process credentials come from status, not ownership of a protected proc directory. */
final class TweaksProcessIdentity {
    private TweaksProcessIdentity() {}

    static void verify(int expectedPid, String status, byte[] cmdline) throws IOException {
        String name = "missing", pid = "missing", tgid = "missing", uids = "missing";
        boolean duplicate = false;
        if (status == null || status.length() > 16384 || cmdline == null || cmdline.length > 65536)
            throw new IOException("TWEAKS_BRIDGE_PID: недоступны данные процесса; BRIDGE_PID=" + expectedPid);
        for (String line : status.split("\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon), value = line.substring(colon + 1).trim();
            switch (key) {
                case "Name": name = value; break;
                case "Pid": duplicate |= !pid.equals("missing"); pid = value; break;
                case "Tgid": duplicate |= !tgid.equals("missing"); tgid = value; break;
                case "Uid": duplicate |= !uids.equals("missing"); uids = value; break;
                default: break;
            }
        }
        int end = 0;
        while (end < cmdline.length && cmdline[end] != 0) end++;
        String command = new String(cmdline, 0, end, StandardCharsets.UTF_8);
        String[] credentials = uids.split("\\s+");
        boolean systemUid = credentials.length == 4;
        for (String uid : credentials) systemUid &= uid.equals("1000");
        String expected = Integer.toString(expectedPid);
        if (expectedPid <= 0 || duplicate || !expected.equals(pid) || !expected.equals(tgid)
                || !systemUid || !command.equals("system_server")) {
            throw new IOException("TWEAKS_BRIDGE_PID: данные процесса не подтверждают system_server\n"
                    + "BRIDGE_PID=" + expectedPid + "\nPROCESS_PID=" + pid + "\nPROCESS_TGID=" + tgid
                    + "\nPROCESS_UIDS=" + uids + "\nPROCESS_NAME=" + name
                    + "\nPROCESS_ARGV0=" + command + "\nDUPLICATE_FIELDS=" + duplicate);
        }
    }
}
