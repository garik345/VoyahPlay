package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class TweaksProcessIdentityTest {
    private static final String STATUS = "Name:\tsystem_server\nState:\tS (sleeping)\n"
            + "Tgid:\t987\nPid:\t987\nPPid:\t500\nUid:\t1000\t1000\t1000\t1000\n";
    private static byte[] command(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static String rejected(String status, byte[] cmdline) throws Exception {
        try { TweaksProcessIdentity.verify(987, status, cmdline); fail("Wrong process accepted"); }
        catch (IOException expected) { return expected.getMessage(); }
        throw new AssertionError();
    }
    @Test public void acceptsLiveSystemServerCredentials() throws Exception {
        TweaksProcessIdentity.verify(987, STATUS, command("system_server\0"));
    }
    @Test public void doesNotDependOnMainThreadNameOrProcDirectoryOwner() throws Exception {
        TweaksProcessIdentity.verify(987, STATUS.replace("Name:\tsystem_server", "Name:\tmain"),
                command("system_server\0\0\0"));
    }
    @Test public void onlyFirstCommandLineArgumentDefinesTheProcessName() throws Exception {
        TweaksProcessIdentity.verify(987, STATUS, command("system_server\0argument\0"));
        rejected(STATUS, command("other_process\0system_server\0"));
        rejected(STATUS, command("system_server_extra\0"));
    }
    @Test public void rejectsEachWrongProcessUid() throws Exception {
        for (int field = 0; field < 4; field++) {
            String[] uids = {"1000", "1000", "1000", "1000"}; uids[field] = "0";
            rejected(STATUS.replace("1000\t1000\t1000\t1000", String.join("\t", uids)), command("system_server\0"));
        }
        rejected(STATUS.replace("1000\t1000\t1000\t1000", "10113\t10113\t10113\t10113"), command("system_server\0"));
    }
    @Test public void rejectsDifferentPidOrThreadGroup() throws Exception {
        rejected(STATUS.replace("Pid:\t987", "Pid:\t988"), command("system_server\0"));
        rejected(STATUS.replace("Tgid:\t987", "Tgid:\t988"), command("system_server\0"));
    }
    @Test public void rejectsMissingAndDuplicateCredentials() throws Exception {
        rejected(STATUS.replace("Uid:\t1000\t1000\t1000\t1000\n", ""), command("system_server\0"));
        rejected(STATUS + "Uid:\t1000 1000 1000 1000\n", command("system_server\0"));
        rejected(STATUS + "Pid:\t987\n", command("system_server\0"));
        rejected(STATUS + "Tgid:\t987\n", command("system_server\0"));
    }
    @Test public void rejectsAbsentAndOversizedInput() throws Exception {
        rejected(null, command("system_server\0"));
        rejected(STATUS, null);
        rejected(STATUS, new byte[0]);
        rejected("x".repeat(16385), command("system_server\0"));
        rejected(STATUS, new byte[65537]);
    }
    @Test public void mismatchReportContainsActualCredentials() throws Exception {
        String message = rejected(STATUS.replace("1000\t1000\t1000\t1000", "0 0 0 0"), command("other\0"));
        assertTrue(message.contains("BRIDGE_PID=987"));
        assertTrue(message.contains("PROCESS_UIDS=0 0 0 0"));
        assertTrue(message.contains("PROCESS_ARGV0=other"));
    }
}
