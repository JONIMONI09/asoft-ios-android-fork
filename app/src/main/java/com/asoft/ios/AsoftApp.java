package com.asoft.ios;

import android.app.Application;

/**
 * The app's process, before any of its screens or services: where the app notices how its process before ended
 * and catches its own crashes (AppExit), whichever part of it Android starts first (a screen, the setup's
 * service, adb's intents in a debug build).
 */
public class AsoftApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // the GPU check runs in a process of its own (GpuCheckService): a driver that crashes takes only that one,
        // and its check says so itself
        String proc = Application.getProcessName();
        if (getPackageName().equals(proc)) {
            CompatConfig.load(this);
            PerfConfig.load(this);
            AppExit.onStart(this);
            FreezeWatchdog.install(this);
            CrashHandler.install(this);
            registerComponentCallbacks(new android.content.ComponentCallbacks2() {
                @Override
                public void onTrimMemory(int level) {
                    if (level >= TRIM_MEMORY_RUNNING_LOW) {
                        AppLog.note(AsoftApp.this, "memory: onTrimMemory " + GameEnvGuard.trimLevelName(level));
                    }
                }

                @Override
                public void onConfigurationChanged(android.content.res.Configuration newConfig) {}

                @Override
                public void onLowMemory() {
                    AppLog.note(AsoftApp.this, "memory: onLowMemory");
                }
            });
            // off the main thread: a phone whose counter register reads 0 measures it, which sleeps 0.3 s
            new Thread(() -> CounterFreq.logOnce(this), "counter").start();
        } else {
            Thread.UncaughtExceptionHandler before = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
                try {
                    AppLog.note(AsoftApp.this, "subprocess crash (" + proc + ", " + t.getName() + "): " + e);
                } catch (Throwable ignored) {
                    // never block default crash handling
                }
                if (before != null) {
                    before.uncaughtException(t, e);
                }
            });
        }
    }
}
