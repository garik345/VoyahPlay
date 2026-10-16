package com.shilapi.xcertplay.voyah.root;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class RootBootHookTest {
    @Test public void insertionPreservesAllOriginalCommandsAndShebang() throws Exception {
        String before = "#!/system/bin/sh\necho 'existing loader'\nwait $loader\n";
        String after = new String(RootBootHook.insert(before.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
        assertEquals(before, after.replace(RootBootHook.BLOCK, ""));
        assertTrue(after.startsWith("#!/system/bin/sh\n# BEGIN"));
        assertTrue(RootBootHook.BLOCK.contains("runtime.sh boot </dev/null >/dev/null 2>&1 &"));
    }
    @Test public void refusesUnreviewedBootScript() throws Exception {
        try { RootBootHook.patched("#!/system/bin/sh\necho altered\n".getBytes(StandardCharsets.UTF_8)); fail("Unknown firmware accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("BOOT_HOOK_UNREVIEWED")); }
    }
    @Test public void refusesDuplicateInsertionAndNonShellHeaders() throws Exception {
        for (String before : new String[]{"not a shell\n", "#!/system/bin/sh\n" + RootBootHook.BLOCK}) {
            try { RootBootHook.insert(before.getBytes(StandardCharsets.UTF_8)); fail("Bad hook input accepted"); }
            catch (IOException expected) {}
        }
    }
    @Test public void resolvesLongestMountAndLatestOverlayOptions() throws Exception {
        String mounts = "rootfs / rootfs ro 0 0\n/dev/system /system ext4 ro,relatime 0 0\noverlay /system overlay rw,relatime 0 0\n";
        assertEquals("/system", RootBootHook.mountPoint(mounts, "/system/etc/init.logcat.sh"));
        assertFalse(RootBootHook.readOnly(mounts, "/system"));
        assertTrue(RootBootHook.readOnly(mounts, "/"));
        assertEquals("/", RootBootHook.mountPoint(mounts, "/system_ext/file"));
    }
    @Test public void doesNotInventMountDetails() throws Exception {
        try { RootBootHook.mountPoint("invalid\n", "/system/etc/init.logcat.sh"); fail(); }
        catch (IOException expected) {}
        try { RootBootHook.readOnly("rootfs / rootfs rw 0 0", "/system"); fail(); }
        catch (IOException expected) {}
    }
}
