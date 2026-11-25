package com.asoft.ios;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Process;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;

/**
 * Global uncaught exception & severe UI-freeze handler.
 * Persists a crash-safe stacktrace to {@code files/run/last-app-crash.txt} and launches
 * {@link CrashOverlayActivity} in an isolated {@code :crash} process so the user always sees
 * a readable stacktrace overlay with Copy and Restart actions.
 */
final class CrashHandler {
    static final String CRASH_FILE = "last-app-crash.txt";
    private static volatile boolean installed;
    private static volatile boolean handling;

    private CrashHandler() {}

    static synchronized void install(Context context) {
        if (installed) {
            return;
        }
        installed = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            if (handling) {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                }
                return;
            }
            handling = true;
            String report = formatCrash(app, thread, throwable);
            try {
                File out = new File(Guest.runDir(app), CRASH_FILE);
                FreezeWatchdog.writeSynced(out, Collections.singletonList(report));
                AppLog.note(app, "app crash (" + thread.getName() + "): " + throwable);
            } catch (Throwable ignored) {
                // never fail inside crash handler
            }
            try {
                launchOverlay(app, "App Crash Caught", report);
            } catch (Throwable ignored) {
                if (previous != null) {
                    previous.uncaughtException(thread, throwable);
                    return;
                }
            }
            try {
                Thread.sleep(250L);
            } catch (InterruptedException ignored) {
                // proceed to exit
            }
            Process.killProcess(Process.myPid());
            System.exit(10);
        });
    }

    static void launchOverlay(Context app, String title, String report) {
        Intent i = new Intent(app, CrashOverlayActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        i.putExtra(CrashOverlayActivity.EXTRA_TITLE, title);
        i.putExtra(CrashOverlayActivity.EXTRA_REPORT, report);
        app.startActivity(i);
    }

    static String formatCrash(Context app, Thread thread, Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(new Date());
        pw.println("Time:    " + stamp);
        pw.println("App:     " + UpdateCheck.version(app));
        pw.println("Device:  " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")");
        pw.println("Thread:  " + (thread != null ? thread.getName() : "unknown"));
        pw.println("Error:   " + (throwable != null ? throwable.toString() : "unknown"));
        pw.println();
        pw.println("=== Stacktrace ===");
        if (throwable != null) {
            throwable.printStackTrace(pw);
        }
        pw.flush();
        return sw.toString();
    }
}
