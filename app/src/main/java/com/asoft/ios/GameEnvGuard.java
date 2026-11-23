package com.asoft.ios;

import android.app.Activity;
import android.app.GameManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Surface;

/**
 * Protects QEMU/GLES presentation from OEM gaming overlays and frame-interpolation engines
 * (e.g. nubia/REDMAGIC Game Space), and records environment signals (focus theft, touch
 * cancel bursts, multi-window churn) into app.txt for root-cause triage (GameEnvGuardTest).
 */
final class GameEnvGuard {
    static final String PREFS = "setup";
    static final String PREF_DISMISSED = "game-space-warn-dismissed";

    /** Known OEM Game Space / Game Booster packages whose hooks inject overlays or frame smoothing. */
    static final String[] GAME_SPACE_PACKAGES = {
            "com.zte.gameservice",              // nubia / REDMAGIC Game Space
            "com.nubia.gamelauncher",           // nubia Game Launcher
            "com.oplus.games",                  // OnePlus / OPPO Games
            "com.samsung.android.game.gametools", // Samsung Game Booster
            "com.xiaomi.joyose"                 // Xiaomi / POCO Game Turbo telemetry/booster
    };

    private static final long CANCEL_WINDOW_MS = 5_000L;
    private static final int CANCEL_BURST_THRESHOLD = 3;
    private static final long LOG_THROTTLE_MS = 10_000L;

    private final long[] cancelTimes = new long[CANCEL_BURST_THRESHOLD];
    private int cancelIndex;
    private int cancelCount;
    private long lastCancelBurstLogMs = -LOG_THROTTLE_MS;
    private int lastSurfW = -1, lastSurfH = -1;

    GameEnvGuard() {}

    /** Human-readable OEM name for a matched package, or null if not in the known list. */
    static String friendlyName(String pkg) {
        if (pkg == null) {
            return null;
        }
        switch (pkg) {
            case "com.zte.gameservice":
            case "com.nubia.gamelauncher":
                return "REDMAGIC Game Space";
            case "com.oplus.games":
                return "OPPO / OnePlus Games";
            case "com.samsung.android.game.gametools":
                return "Samsung Game Booster";
            case "com.xiaomi.joyose":
                return "Xiaomi Game Turbo";
            default:
                return null;
        }
    }

    /** Returns matching OEM Game Space package present on the device, or null if none is installed. */
    static String detectGameSpacePackage(Context c) {
        PackageManager pm = c.getPackageManager();
        for (String pkg : GAME_SPACE_PACKAGES) {
            if (isPackageInstalled(pm, pkg)) {
                return pkg;
            }
        }
        return null;
    }

    private static boolean isPackageInstalled(PackageManager pm, String pkg) {
        try {
            pm.getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return false;
        }
    }

    static boolean isBannerDismissed(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(PREF_DISMISSED, false);
    }

    static void dismissBanner(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(PREF_DISMISSED, true).apply();
    }

    static String warningMessage(String pkg) {
        String name = friendlyName(pkg);
        String label = name == null ? "OEM Game Space" : name;
        return label + " (" + pkg + ") is installed on this device. Remove ASoftware iOS from Game Space / "
                + "frame interpolation / bypass charging hooks if you see black screens, touch drops, or freezes.";
    }

    /** Opens app-info for the OEM Game Space package (or app settings fallback). */
    static void openGameSpaceSettings(Context c, String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + (pkg != null ? pkg : c.getPackageName())));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (RuntimeException ignored) {
            // system settings unavailable
        }
    }

    /**
     * Tells the Android Window composer to disable display post-processing / frame interpolation
     * (API 30+), and logs unexpected OEM GameManager modes (API 31+).
     */
    static void protectWindow(Activity a) {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                a.getWindow().setPreferMinimalPostProcessing(true);
            } catch (RuntimeException e) {
                AppLog.note(a, "env: setPreferMinimalPostProcessing failed: " + e.getMessage());
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                GameManager gm = a.getSystemService(GameManager.class);
                if (gm != null) {
                    int mode = gm.getGameMode();
                    if (mode != GameManager.GAME_MODE_STANDARD && mode != GameManager.GAME_MODE_UNSUPPORTED) {
                        AppLog.note(a, "env: GameManager active mode=" + mode);
                    }
                }
            } catch (RuntimeException ignored) {
                // optional system service
            }
        }
    }

    /** Pins swap cadence hint to normal 60 Hz default to avoid OEM 120/144 Hz frame-generation glitches. */
    static void protectSurface(Context c, Surface s) {
        if (Build.VERSION.SDK_INT >= 30 && s != null && s.isValid()) {
            try {
                s.setFrameRate(60f, Surface.FRAME_RATE_COMPATIBILITY_DEFAULT);
            } catch (RuntimeException e) {
                AppLog.note(c, "env: surface setFrameRate failed: " + e.getMessage());
            }
        }
    }

    /** Records touch cancellation bursts; returns true when a new burst was detected and should be logged. */
    boolean noteTouchCancel(long nowMs) {
        cancelTimes[cancelIndex] = nowMs;
        cancelIndex = (cancelIndex + 1) % cancelTimes.length;
        if (cancelCount < cancelTimes.length) {
            cancelCount++;
        }
        boolean burst = isBurst(cancelTimes, cancelCount, nowMs, CANCEL_WINDOW_MS);
        if (burst && nowMs - lastCancelBurstLogMs >= LOG_THROTTLE_MS) {
            lastCancelBurstLogMs = nowMs;
            return true;
        }
        return false;
    }

    /** Pure ring-buffer burst detector (GameEnvGuardTest). */
    static boolean isBurst(long[] ring, int filled, long nowMs, long windowMs) {
        if (filled < ring.length || ring.length == 0) {
            return false;
        }
        int recent = 0;
        for (long t : ring) {
            if (nowMs - t >= 0 && nowMs - t <= windowMs) {
                recent++;
            }
        }
        return recent >= ring.length;
    }

    void logTouchCancelIfBurst(Context c, long nowMs) {
        if (noteTouchCancel(nowMs)) {
            AppLog.note(c, "env: repeated MotionEvent.ACTION_CANCEL burst (" + CANCEL_BURST_THRESHOLD
                    + "+ in " + (CANCEL_WINDOW_MS / 1000) + "s) — possible overlay / Game Space edge swipe interception");
        }
    }

    void logWindowFocus(Context c, boolean hasFocus) {
        AppLog.note(c, "env: screen focus " + (hasFocus ? "gained" : "lost"));
    }

    void logMultiWindow(Context c, boolean inMultiWindow, int w, int h) {
        AppLog.note(c, "env: multi-window=" + inMultiWindow + " size=" + w + "x" + h);
    }

    void logSurfaceResize(Context c, int w, int h) {
        if (w != lastSurfW || h != lastSurfH) {
            lastSurfW = w;
            lastSurfH = h;
            AppLog.note(c, "env: surface size=" + w + "x" + h);
        }
    }

    /** Human-readable description of trim memory levels for FreezeWatchdog / ComponentCallbacks2. */
    static String trimLevelName(int level) {
        switch (level) {
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE:
                return "RUNNING_MODERATE";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW:
                return "RUNNING_LOW";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL:
                return "RUNNING_CRITICAL";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN:
                return "UI_HIDDEN";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND:
                return "BACKGROUND";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE:
                return "MODERATE";
            case android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE:
                return "COMPLETE";
            default:
                return "LEVEL_" + level;
        }
    }
}
