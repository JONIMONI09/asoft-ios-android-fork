package com.asoft.ios;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.Test;

public class BootDiagnosticsTest {
    @Test
    public void appendFileStatusReportsExistingAndMissingFiles() throws Exception {
        File tmp = File.createTempFile("bootdiag", ".txt");
        try {
            Files.write(tmp.toPath(), "hello".getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            BootDiagnostics.appendFileStatus(sb, "serial.txt", tmp);
            BootDiagnostics.appendFileStatus(sb, "missing.txt", new File(tmp.getParentFile(), "does-not-exist-99999.txt"));
            String out = sb.toString();
            assertTrue(out.contains("serial.txt: exists, size=5 B, perms=r"));
            assertTrue(out.contains("missing.txt: MISSING"));
        } finally {
            tmp.delete();
        }
    }

    @Test
    public void kgslShimNeverHoldsStateLockAcrossDlopenOrTraceWrite() throws Exception {
        Path p = Paths.get("src/main/cpp/kgsl_shim.c");
        if (!Files.exists(p)) {
            p = Paths.get("app/src/main/cpp/kgsl_shim.c");
        }
        assertTrue("kgsl_shim.c must exist", Files.exists(p));
        String src = new String(Files.readAllBytes(p), StandardCharsets.UTF_8);

        assertTrue("kgsl_shim.c must use dedicated g_turnip_load_lock", src.contains("g_turnip_load_lock"));
        assertTrue("asoft_trace_write must use shim_raw_open on Android", src.contains("shim_raw_open("));
        assertTrue("asoft_trace_write must use shim_raw_close on Android", src.contains("shim_raw_close("));

        int start = src.indexOf("static int ensure_turnip_loaded(void)");
        int end = src.indexOf("static struct instance_entry *find_instance(", start);
        assertTrue(start > 0 && end > start);
        String fn = src.substring(start, end);
        assertFalse("ensure_turnip_loaded must NOT acquire g_state_lock",
                fn.contains("pthread_mutex_lock(&g_state_lock)"));
    }
}
