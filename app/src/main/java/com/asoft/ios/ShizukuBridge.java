package com.asoft.ios;

import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import rikka.shizuku.Shizuku;

/**
 * Optional Shizuku Binder bridge (Android 11-16, no root): lets ASoftware iOS run UID 2000 shell
 * commands (am kill-all, dumpsys meminfo) directly via Shizuku when the user grants permission,
 * bypassing OEM notification/split-screen pairing quirks. Never modifies WadbPairService.
 */
final class ShizukuBridge {
    static final int REQ_PERMISSION = 4102;

    private ShizukuBridge() {}

    static boolean available() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean granted() {
        try {
            return available() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    static void requestPermission() {
        try {
            if (available() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                Shizuku.requestPermission(REQ_PERMISSION);
            }
        } catch (Throwable ignored) {
            // Shizuku manager not installed or binder unavailable
        }
    }

    static String shell(String cmd, int timeoutMs) throws IOException {
        if (!granted()) {
            throw new IOException("Shizuku permission not granted");
        }
        Process proc = null;
        try {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            proc = (Process) m.invoke(null, new String[] {"sh", "-c", cmd}, null, null);
            if (proc == null) {
                throw new IOException("Shizuku.newProcess returned null");
            }
            InputStream in = proc.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + Math.max(1000, timeoutMs);
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (System.currentTimeMillis() > deadline) {
                    proc.destroy();
                    throw new IOException("Shizuku command timed out");
                }
            }
            proc.waitFor(Math.max(500, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) {
            throw e;
        } catch (Throwable t) {
            throw new IOException("Shizuku shell failed: " + t.getMessage(), t);
        } finally {
            if (proc != null) {
                try {
                    proc.destroy();
                } catch (Throwable ignored) {
                    // ignore
                }
            }
        }
    }
}
