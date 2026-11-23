package com.asoft.ios;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class CrashPersistenceTest {
    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    @Test
    public void writeSyncedCreatesAtomicTargetWithoutLeftoverTmp() throws IOException {
        File dir = tmp.newFolder("run");
        File out = new File(dir, FreezeWatchdog.FILE_NAME);
        FreezeWatchdog.writeSynced(out, Arrays.asList("stallMs: 4200", "main frame 1"));

        assertTrue(out.isFile());
        assertFalse(new File(out.getPath() + ".tmp").exists());
        String content = new String(Files.readAllBytes(out.toPath()), StandardCharsets.UTF_8);
        assertTrue(content.contains("stallMs: 4200"));
        assertTrue(content.contains("main frame 1"));
    }

    @Test
    public void formatFreezeReportIncludesMainAndOtherThreadsWithBound() {
        StackTraceElement[] mainStack = new StackTraceElement[] {
                new StackTraceElement("com.asoft.ios.ScreenActivity", "doFrame", "ScreenActivity.java", 1165)
        };
        Map<Thread, StackTraceElement[]> all = new LinkedHashMap<>();
        Thread worker = new Thread("setup-worker");
        all.put(worker, new StackTraceElement[] {
                new StackTraceElement("com.asoft.ios.PhoneSetup26", "run", "PhoneSetup26.java", 284)
        });

        List<String> lines = FreezeWatchdog.formatFreezeReport(5500L, mainStack, all, "free 2400 MB", "42 C");
        String joined = String.join("\n", lines);
        assertTrue(joined.contains("stallMs: 5500"));
        assertTrue(joined.contains("memory: free 2400 MB"));
        assertTrue(joined.contains("thermal: 42 C"));
        assertTrue(joined.contains("ScreenActivity.doFrame"));
        assertTrue(joined.contains("setup-worker"));
        assertTrue(joined.contains("PhoneSetup26.run"));
    }

    @Test
    public void isolatesZteGameActivityStubBackgroundExceptionOnly() {
        Thread main = new Thread("main");
        Thread gameStub = new Thread("GameActivityStub");
        Thread worker = new Thread("setup-worker");

        NullPointerException zteNpe = new NullPointerException("KeyMapObserver.stop on null");
        zteNpe.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.zte.gameassist.app.GameActivityStub", "unregisterObserver", "GameActivityStub.java", 137),
                new StackTraceElement("android.os.HandlerThread", "run", "HandlerThread.java", 85)
        });
        assertTrue(AppExit.shouldIsolateOemHookCrash(gameStub, zteNpe, main));
        // Never swallow if it ran on the main thread
        assertFalse(AppExit.shouldIsolateOemHookCrash(main, zteNpe, main));
        // Never swallow JVM Errors even on GameActivityStub thread
        assertFalse(AppExit.shouldIsolateOemHookCrash(gameStub, new OutOfMemoryError("oom"), main));

        // Never swallow if any com.asoft.ios frame is present in the stack
        NullPointerException mixed = new NullPointerException("npe");
        mixed.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.zte.gameassist.app.GameActivityStub", "unregisterObserver", "GameActivityStub.java", 137),
                new StackTraceElement("com.asoft.ios.SetupActivity", "onDestroy", "SetupActivity.java", 99)
        });
        assertFalse(AppExit.shouldIsolateOemHookCrash(worker, mixed, main));

        // Normal worker exceptions are never isolated
        IllegalStateException appBug = new IllegalStateException("bug");
        appBug.setStackTrace(new StackTraceElement[] {
                new StackTraceElement("com.asoft.ios.PhoneSetup26", "run", "PhoneSetup26.java", 100)
        });
        assertFalse(AppExit.shouldIsolateOemHookCrash(worker, appBug, main));
    }
}
