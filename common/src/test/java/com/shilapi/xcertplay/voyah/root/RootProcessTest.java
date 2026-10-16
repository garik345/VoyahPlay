package com.shilapi.xcertplay.voyah.root;

import org.junit.Test;
import java.io.File;
import static org.junit.Assert.*;

public class RootProcessTest {
    private String[] child(String mode) throws Exception {
        String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return new String[]{new File(System.getProperty("java.home"), "bin/" + executable).getPath(),
                "-cp", new File(Fixture.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath(), Fixture.class.getName(), mode};
    }

    @Test public void capturesResultWithoutBlockingOnOutput() throws Exception {
        RootProcess.Result result = RootProcess.run(5, child("output"));
        assertTrue(result.successful());
        assertEquals("hello", result.output);
    }

    @Test public void boundsLargeOutputAndStillDrainsPipe() throws Exception {
        RootProcess.Result result = RootProcess.run(5, child("large"));
        assertTrue(result.successful());
        assertEquals(32768, result.output.length());
    }

    @Test public void reportsNonzeroExit() throws Exception {
        RootProcess.Result result = RootProcess.run(5, child("failure"));
        assertFalse(result.successful());
        assertEquals(7, result.exitCode);
    }

    @Test public void timesOutItsOwnChild() throws Exception {
        RootProcess.Result result = RootProcess.run(1, child("wait"));
        assertTrue(result.timedOut);
        assertFalse(result.successful());
    }

    @Test public void quotesLiteralShellArguments() {
        assertEquals("'a'\\''b$`c'", RootProcess.quote("a'b$`c"));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsMultilineArgument() {
        RootProcess.quote("first\nsecond");
    }

    public static final class Fixture {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "output": System.out.print("hello"); break;
                case "large": for (int i = 0; i < 200000; i++) System.out.print('x'); break;
                case "failure": System.exit(7); break;
                case "wait": Thread.sleep(20000); break;
                default: throw new IllegalArgumentException(args[0]);
            }
        }
    }
}
