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
}
