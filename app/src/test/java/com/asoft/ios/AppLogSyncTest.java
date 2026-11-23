package com.asoft.ios;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class AppLogSyncTest {
    private File dir;

    @Before
    public void setUp() throws IOException {
        dir = Files.createTempDirectory("applogsync").toFile();
    }

    @After
    public void tearDown() {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    @Test
    public void writeLineSyncAppendsLinesPersistently() throws IOException {
        File f = new File(dir, "app.txt");
        AppLog.writeLineSync(f, "first-boot-step", true);
        AppLog.writeLineSync(f, "second-critical-gpu-init", true);
        AppLog.writeLineSync(f, "third-nonsync-line", false);

        String content = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertEquals("first-boot-step\nsecond-critical-gpu-init\nthird-nonsync-line\n", content);
    }
}
