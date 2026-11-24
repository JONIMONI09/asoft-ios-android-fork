package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class MemoryCleanerTest {
    @Test
    public void theTopOfDumpsysMeminfoIsReadable() {
        String d = "Total PSS by process:\n"
                + "  1,228,800K: com.foo (pid 12)\n"
                + "  204,800K: com.bar (pid 13 / activities)\n"
                + "Total PSS by category:\n"
                + "  400,000K: .so mmap\n";
        assertEquals("com.foo — 1200 MB\ncom.bar — 200 MB", MemoryCleaner.topLines(d, 6));
        assertEquals("com.foo — 1200 MB", MemoryCleaner.topLines(d, 1));
        assertEquals("", MemoryCleaner.topLines("nothing here", 6));
    }

    @Test
    public void theDumpsysSummarySaysWhatIsNotInTheTopList() {
        String d = "Total RAM: 11,700,000K (status normal)\n"
                + "Free RAM: 4,900,000K (cached 1,000,000K)\n"
                + "Used RAM: 5,500,000K (5,000,000K kernel)\n"
                + "Lost RAM: 1,200,000K\n"
                + "ZRAM: 2,000,000K physical used for 4,000,000K in swap (6,000,000K total swap)\n";
        assertEquals("Used 5371 MB · Free 4785 MB · Lost 1172 MB · ZRAM 1953 MB (swap 3906 MB)",
                MemoryCleaner.summaryLines(d));
        assertEquals("", MemoryCleaner.summaryLines("nothing here"));
    }

    @Test
    public void privilegedShellPrefersShizukuOverStaleWirelessAdb() throws Exception {
        AtomicInteger shizukuCalls = new AtomicInteger();
        AtomicInteger wadbCalls = new AtomicInteger();
        MemoryCleaner.ShellResult res = MemoryCleaner.runPrivilegedShell(
                true,
                true,
                (cmd, t) -> {
                    shizukuCalls.incrementAndGet();
                    return "ok-shizuku";
                },
                (cmd, t) -> {
                    wadbCalls.incrementAndGet();
                    throw new IOException("ECONNREFUSED");
                },
                "am kill-all",
                10_000);
        assertNotNull(res);
        assertEquals("Shizuku", res.backend);
        assertEquals("ok-shizuku", res.output);
        assertEquals(1, shizukuCalls.get());
        assertEquals(0, wadbCalls.get());
    }

    @Test
    public void privilegedShellFallsBackToWirelessAdbWhenShizukuFails() throws Exception {
        AtomicInteger shizukuCalls = new AtomicInteger();
        AtomicInteger wadbCalls = new AtomicInteger();
        MemoryCleaner.ShellResult res = MemoryCleaner.runPrivilegedShell(
                true,
                true,
                (cmd, t) -> {
                    shizukuCalls.incrementAndGet();
                    throw new IOException("Shizuku command timed out");
                },
                (cmd, t) -> {
                    wadbCalls.incrementAndGet();
                    return "ok-wadb";
                },
                "am kill-all",
                10_000);
        assertNotNull(res);
        assertEquals("wireless adb", res.backend);
        assertEquals("ok-wadb", res.output);
        assertEquals(1, shizukuCalls.get());
        assertEquals(1, wadbCalls.get());
    }

    @Test
    public void privilegedShellReportsCombinedErrorWhenBothFailAndNullWhenNoneAvailable() throws Exception {
        assertNull(MemoryCleaner.runPrivilegedShell(
                false, false,
                (cmd, t) -> "unused",
                (cmd, t) -> "unused",
                "am kill-all", 10_000));
        try {
            MemoryCleaner.runPrivilegedShell(
                    true, true,
                    (cmd, t) -> { throw new IOException("binder dead"); },
                    (cmd, t) -> { throw new IOException("ECONNREFUSED"); },
                    "am kill-all", 10_000);
            fail("Expected IOException when both backends fail");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("binder dead"));
            assertTrue(e.getMessage().contains("ECONNREFUSED"));
        }
    }

    @Test
    public void supportedMatrixCoversAndroid13AndAndroid14Plus() {
        // Android 13 (API 33): always supported even without Wireless ADB or Shizuku
        assertTrue(MemoryCleaner.supported(33, false, false));
        // Android 14+ (API 34+): requires either Wireless ADB pairing or Shizuku
        assertFalse(MemoryCleaner.supported(34, false, false));
        assertTrue(MemoryCleaner.supported(34, true, false));
        assertTrue(MemoryCleaner.supported(34, false, true));
        assertTrue(MemoryCleaner.supported(35, true, true));
    }
}
