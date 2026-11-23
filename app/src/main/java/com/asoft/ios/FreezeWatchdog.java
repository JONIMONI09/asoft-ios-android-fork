package com.asoft.ios;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Detects main-thread stalls (>= 4 s) before Android kills or freezes the UI, and persists a synced
 * stack + thread snapshot to files/run/last-ui-freeze.txt (CrashPersistenceTest). Also provides
 * atomic fsync file writes so diagnostic files survive sudden reboots.
 */
final class FreezeWatchdog {
    static final String FILE_NAME = "last-ui-freeze.txt";
    private static final long TICK_MS = 2_000L;
    private static final long STALL_MS = 4_000L;
    private static final long COOLDOWN_MS = 30_000L;
    private static final int MAX_STACK_LINES = 200;

    private static volatile Thread thread;

    private FreezeWatchdog() {}

    static synchronized void install(Context context) {
        if (thread != null) {
            return;
        }
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        Thread mainThread = Looper.getMainLooper().getThread();
        AtomicLong seq = new AtomicLong(0);
        AtomicLong ack = new AtomicLong(0);

        Thread t = new Thread(() -> {
            long pendingSince = 0L;
            long lastDumpAt = -COOLDOWN_MS;
            while (!Thread.currentThread().isInterrupted()) {
                long next = seq.incrementAndGet();
                main.post(() -> ack.set(next));
                if (pendingSince == 0L) {
                    pendingSince = SystemClock.uptimeMillis();
                }
                try {
                    Thread.sleep(TICK_MS);
                } catch (InterruptedException e) {
                    break;
                }
                long acked = ack.get();
                long now = SystemClock.uptimeMillis();
                if (acked >= next) {
                    pendingSince = 0L;
                    continue;
                }
                long stalledMs = now - pendingSince;
                if (stalledMs >= STALL_MS && now - lastDumpAt >= COOLDOWN_MS) {
                    lastDumpAt = now;
                    try {
                        List<String> lines = formatFreezeReport(
                                stalledMs,
                                mainThread.getStackTrace(),
                                Thread.getAllStackTraces(),
                                CrashReport.memInfo(),
                                CrashReport.thermal(app));
                        File out = new File(Guest.runDir(app), FILE_NAME);
                        writeSynced(out, lines);
                        AppLog.note(app, "watchdog: main UI thread stalled for " + (stalledMs / 1000) + " s (wrote " + FILE_NAME + ")");
                    } catch (Throwable ignored) {
                        // watchdog must never crash the app
                    }
                }
            }
        }, "ui-watchdog");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    /** Formats a bounded human-readable freeze report (pure Java for CrashPersistenceTest). */
    static List<String> formatFreezeReport(long stallMs, StackTraceElement[] mainStack,
            Map<Thread, StackTraceElement[]> allStacks, String memLine, String thermalLine) {
        List<String> out = new ArrayList<>();
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date());
        out.add("time: " + stamp);
        out.add("stallMs: " + Math.max(0L, stallMs));
        if (memLine != null && !memLine.isEmpty()) {
            out.add("memory: " + memLine);
        }
        if (thermalLine != null && !thermalLine.isEmpty()) {
            out.add("thermal: " + thermalLine);
        }
        out.add("");
        out.add("=== main thread stack ===");
        if (mainStack == null || mainStack.length == 0) {
            out.add("  (no frames)");
        } else {
            for (int i = 0; i < mainStack.length && i < 40; i++) {
                out.add("  at " + mainStack[i]);
            }
        }
        if (allStacks != null && !allStacks.isEmpty()) {
            out.add("");
            out.add("=== other threads ===");
            int budget = MAX_STACK_LINES;
            for (Map.Entry<Thread, StackTraceElement[]> e : allStacks.entrySet()) {
                if (budget <= 0) {
                    out.add("  ...(remaining threads truncated)");
                    break;
                }
                Thread thr = e.getKey();
                if (thr != null && "main".equals(thr.getName())) {
                    continue;
                }
                String title = thr == null ? "thread" : (thr.getName() + " [" + thr.getState() + "]");
                out.add("* " + title);
                budget--;
                StackTraceElement[] frames = e.getValue();
                if (frames != null) {
                    for (int i = 0; i < frames.length && i < 12 && budget > 0; i++) {
                        out.add("    at " + frames[i]);
                        budget--;
                    }
                }
            }
        }
        return out;
    }

    /** Atomically writes @lines via .tmp, getFD().sync(), and renameTo() so data hits disk immediately. */
    static void writeSynced(File target, List<String> lines) throws IOException {
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            sb.append(line).append('\n');
        }
        File tmp = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (!tmp.renameTo(target)) {
            tmp.delete();
            throw new IOException("cannot rename " + tmp + " to " + target);
        }
    }
}
