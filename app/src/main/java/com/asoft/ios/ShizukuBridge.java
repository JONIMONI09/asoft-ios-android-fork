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
            final Process killerTarget = proc;
            final java.util.concurrent.atomic.AtomicBoolean timedOut = new java.util.concurrent.atomic.AtomicBoolean(false);
            Thread stderrDrainer = new Thread(() -> {
                try (InputStream err = killerTarget.getErrorStream()) {
                    if (err != null) {
                        byte[] drainBuf = new byte[1024];
                        while (err.read(drainBuf) != -1) {
                            // discard stderr so verbose output never deadlocks the child pipe
                        }
                    }
                } catch (Throwable ignored) {
                    // stream closed or process exited
                }
            }, "shizuku-stderr");
            stderrDrainer.setDaemon(true);
            stderrDrainer.start();
            Thread watchdog = new Thread(() -> {
                try {
                    Thread.sleep(Math.max(1000, timeoutMs));
                    timedOut.set(true);
                    killerTarget.destroy();
                } catch (InterruptedException ignored) {
                    // finished before timeout
                } catch (Throwable ignored) {
                    // ignore
                }
            }, "shizuku-timeout");
            watchdog.setDaemon(true);
            watchdog.start();
            try {
                InputStream in = proc.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                // Use 0-arg waitFor(): ShizukuRemoteProcess does not implement waitFor(long, TimeUnit)
                proc.waitFor();
                if (timedOut.get()) {
                    throw new IOException("Shizuku command timed out");
                }
                return out.toString(StandardCharsets.UTF_8.name());
            } catch (IOException e) {
                if (timedOut.get()) {
                    throw new IOException("Shizuku command timed out", e);
                }
                throw e;
            } catch (Throwable t) {
                if (timedOut.get()) {
                    throw new IOException("Shizuku command timed out", t);
                }
                throw new IOException("Shizuku shell failed: " + t.getMessage(), t);
            } finally {
                watchdog.interrupt();
            }
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
