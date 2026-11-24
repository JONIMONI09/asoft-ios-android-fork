package com.asoft.ios;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Passive boot and stall diagnostic snapshot writer (writes {@code files/run/boot-diag.txt}).
 * Never alters QEMU, Vulkan, or Wireless ADB pairing behavior; only inspects files, permissions,
 * QEMU /proc state, and (optionally, on stall when already paired) read-only WirelessAdb logcat.
 */
final class BootDiagnostics {
    static final String FILE_NAME = "boot-diag.txt";

    private BootDiagnostics() {}

    static File file(Context c) {
        return new File(Guest.runDir(c), FILE_NAME);
    }

    /** Captures a passive diagnostic snapshot into files/run/boot-diag.txt. */
    static void capture(Context c, String reason, long elapsedSec, boolean allowWadb) {
        if (c == null) {
            return;
        }
        try {
            String report = buildReport(c, reason, elapsedSec, allowWadb);
            File out = file(c);
            File parent = out.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            try (FileOutputStream fos = new FileOutputStream(out, false)) {
                fos.write(report.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                try {
                    fos.getFD().sync();
                } catch (IOException ignored) {
                    // keep flushed content if fsync is unsupported
                }
            }
            AppLog.note(c, "boot-diag: captured (" + reason + ", " + report.length() + " bytes)");
        } catch (Throwable t) {
            // Diagnostics must never crash or interrupt a boot
        }
    }

    static String buildReport(Context c, String reason, long elapsedSec, boolean allowWadb) {
        StringBuilder sb = new StringBuilder(2048);
        String ts = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date());
        sb.append("== ASoftware Boot & Stall Diagnostics ==\n");
        sb.append("time: ").append(ts).append('\n');
        sb.append("reason: ").append(reason == null ? "manual" : reason).append('\n');
        sb.append("elapsed_sec: ").append(elapsedSec).append('\n');
        sb.append("app: ").append(appVersion(c)).append('\n');
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");

        CompatConfig.Config cfg = CompatConfig.load(c);
        sb.append("compat_cfg: ").append(CompatConfig.describe(cfg, cfg.needsIcdWrapper())).append('\n');
        sb.append('\n');

        File run = Guest.runDir(c);
        File vulkan = new File(c.getFilesDir(), "vulkan");
        File nativeLib = new File(c.getApplicationInfo().nativeLibraryDir);
        sb.append("== Files & Permissions ==\n");
        appendFileStatus(sb, "serial.txt", new File(run, "serial.txt"));
        appendFileStatus(sb, "qemu-stderr.txt", new File(run, "qemu-stderr.txt"));
        appendFileStatus(sb, "kgsl-trace.txt", new File(run, CompatConfig.TRACE_FILE));
        appendFileStatus(sb, "app.txt", new File(run, "app.txt"));
        appendFileStatus(sb, "turnip_icd_shim.json", new File(vulkan, "turnip_icd_shim.json"));
        appendFileStatus(sb, "libkgslshim.so", new File(nativeLib, CompatConfig.SHIM_LIB));
        appendFileStatus(sb, "/dev/kgsl-3d0", new File("/dev/kgsl-3d0"));
        sb.append('\n');

        sb.append("== QEMU Process State ==\n");
        int pid = Guest.pid(c);
        sb.append("qemu_running: ").append(Guest.running()).append(", pid: ").append(pid).append('\n');
        if (pid > 0) {
            appendProcSummary(sb, pid);
        }
        sb.append('\n');

        if (allowWadb) {
            sb.append("== Optional Wireless ADB Check (read-only) ==\n");
            if (WirelessAdb.paired(c)) {
                try {
                    String out = WirelessAdb.shell(c, "logcat -d -t 30 -s asoft-kgsl-shim:V", 4000);
                    sb.append(out == null || out.trim().isEmpty() ? "(no shim logcat lines)\n" : out.trim() + "\n");
                } catch (Exception e) {
                    sb.append("(wireless adb query skipped: ").append(e.getMessage()).append(")\n");
                }
            } else {
                sb.append("(wireless adb not paired; skipped)\n");
            }
        }
        return sb.toString();
    }

    private static String appVersion(Context c) {
        try {
            android.content.pm.PackageInfo pi = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            return pi.versionName + " (" + pi.getLongVersionCode() + ")";
        } catch (Exception e) {
            return "unknown";
        }
    }

    static void appendFileStatus(StringBuilder sb, String label, File f) {
        sb.append(label).append(": ");
        if (!f.exists()) {
            sb.append("MISSING\n");
            return;
        }
        String perms = (f.canRead() ? "r" : "-")
                + (f.canWrite() ? "w" : "-")
                + (f.canExecute() ? "x" : "-");
        sb.append("exists, size=").append(f.length()).append(" B, perms=").append(perms).append('\n');
    }

    private static void appendProcSummary(StringBuilder sb, int pid) {
        File status = new File("/proc/" + pid + "/status");
        if (status.isFile()) {
            try {
                for (String line : Files.readAllLines(status.toPath(), StandardCharsets.ISO_8859_1)) {
                    if (line.startsWith("State:") || line.startsWith("Threads:") || line.startsWith("VmRSS:")) {
                        sb.append("  ").append(line.trim()).append('\n');
                    }
                }
            } catch (IOException ignored) {
                sb.append("  (could not read /proc/").append(pid).append("/status)\n");
            }
        }
        File taskDir = new File("/proc/" + pid + "/task");
        File[] tasks = taskDir.listFiles();
        if (tasks != null) {
            int shown = 0;
            for (File t : tasks) {
                if (shown >= 12) {
                    break;
                }
                File wchan = new File(t, "wchan");
                File comm = new File(t, "comm");
                try {
                    String wc = wchan.isFile() ? new String(Files.readAllBytes(wchan.toPath()), StandardCharsets.ISO_8859_1).trim() : "?";
                    String cm = comm.isFile() ? new String(Files.readAllBytes(comm.toPath()), StandardCharsets.ISO_8859_1).trim() : "?";
                    sb.append("  tid ").append(t.getName()).append(" (").append(cm).append("): wchan=").append(wc).append('\n');
                    shown++;
                } catch (IOException ignored) {
                    // thread exited
                }
            }
        }
    }
}
