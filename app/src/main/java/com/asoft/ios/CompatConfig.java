package com.asoft.ios;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime mitigation and crash-persistent logging controls for iOS 26 compatibility mode
 * on Snapdragon / KGSL devices (Upstream Issue #92).
 *
 * All mitigations are gated exclusively to {@code ios26 && compat26} runs and can be bypassed
 * completely via {@link Config#vanillaFallback} to restore stock 0.1.7 behavior.
 */
final class CompatConfig {
    static final String PREFS = "asoft_compat26";

    static final String KEY_VANILLA_FALLBACK = "vanilla_fallback";
    static final String KEY_KGSL_TRACE = "kgsl_trace";
    static final String KEY_SYNC_LOGGING = "sync_logging";
    static final String KEY_MULTI_VMA_BOUNCE = "multi_vma_bounce";
    static final String KEY_ALL_HOST_BOUNCE = "all_host_bounce";
    static final String KEY_KGSL_CACHE_SYNC = "kgsl_cache_sync";
    static final String KEY_FEATURE_GUARD = "feature_guard";
    static final String KEY_SUBMIT_THROTTLE = "submit_throttle";
    static final String KEY_LAZY_SURFACES_OFF = "lazy_surfaces_off";
    static final String KEY_IMPORT_THREAD_ALWAYS = "import_thread_always";
    static final String KEY_TU_DEBUG_SAFE = "tu_debug_safe";
    static final String KEY_VERBOSE_ENGINE_LOGS = "verbose_engine_logs";
    static final String KEY_GPU_PROMPT_HANDLED = "gpu_prompt_handled";

    static final String TRACE_FILE = "kgsl-trace.txt";
    static final String SHIM_LIB = "libkgslshim.so";
    static final String SAFE_TU_DEBUG = "noubwc,flushall,sysmem";

    /** True only while an iOS 26 / watchOS compat run is active; scopes AppLog fsync strictly to compat runs. */
    private static volatile boolean compatRunActive;

    private CompatConfig() {}

    static final class Config {
        boolean vanillaFallback;
        // All mitigations and trace toggles default to OFF per maintainer review (#371)
        boolean kgslTrace = false;
        boolean syncLogging = false;
        boolean multiVmaBounce = false;
        boolean kgslCacheSync = false;
        boolean featureGuard = false;
        boolean submitThrottle = false;
        boolean lazySurfacesOff = false;
        boolean allHostBounce = false;
        boolean importThreadAlways = false;
        boolean tuDebugSafe = false;
        boolean verboseEngineLogs = false;

        /** Stock defaults: everything off until opted in. */
        static Config safeDefaults() {
            return new Config();
        }

        /** Diagnostic preset: synced KGSL trace + O_SYNC logs only, all mitigations OFF. */
        static Config traceOnly() {
            Config c = new Config();
            c.kgslTrace = true;
            c.syncLogging = true;
            return c;
        }

        /**
         * Setup-safe & fast daily preset for known Issue #92 Snapdragon/KGSL GPUs.
         * Keeps lazySurfacesOff = false (prevents 65,530 vm.max_map_count crashes) and keeps
         * kgslTrace / syncLogging = false (avoids per-GPU-submit flash fsync overhead).
         */
        static Config recommendedForKnownKgslBug() {
            Config c = new Config();
            c.kgslTrace = false;
            c.syncLogging = false;
            c.multiVmaBounce = true;
            c.kgslCacheSync = true;
            c.featureGuard = true;
            c.submitThrottle = true;
            c.lazySurfacesOff = false;
            return c;
        }

        /** Intelligently selects the setup-safe preset based on GPU check results. */
        static Config recommendedForGpu(boolean pass, boolean nosparse, boolean systemDriver) {
            return (pass && nosparse && !systemDriver) ? recommendedForKnownKgslBug() : safeDefaults();
        }

        Config copy() {
            Config c = new Config();
            c.vanillaFallback = vanillaFallback;
            c.kgslTrace = kgslTrace;
            c.syncLogging = syncLogging;
            c.multiVmaBounce = multiVmaBounce;
            c.kgslCacheSync = kgslCacheSync;
            c.featureGuard = featureGuard;
            c.submitThrottle = submitThrottle;
            c.lazySurfacesOff = lazySurfacesOff;
            c.allHostBounce = allHostBounce;
            c.importThreadAlways = importThreadAlways;
            c.tuDebugSafe = tuDebugSafe;
            c.verboseEngineLogs = verboseEngineLogs;
            return c;
        }

        boolean needsIcdWrapper() {
            return !vanillaFallback && (kgslTrace || multiVmaBounce || allHostBounce || featureGuard || submitThrottle);
        }

        boolean needsLdPreload() {
            return !vanillaFallback && (kgslTrace || syncLogging || kgslCacheSync);
        }
    }

    static Config load(Context c) {
        if (c == null) {
            return Config.safeDefaults();
        }
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config cfg = new Config();
        cfg.vanillaFallback = p.getBoolean(KEY_VANILLA_FALLBACK, false);
        cfg.kgslTrace = p.getBoolean(KEY_KGSL_TRACE, false);
        cfg.syncLogging = p.getBoolean(KEY_SYNC_LOGGING, false);
        cfg.multiVmaBounce = p.getBoolean(KEY_MULTI_VMA_BOUNCE, false);
        cfg.kgslCacheSync = p.getBoolean(KEY_KGSL_CACHE_SYNC, false);
        cfg.featureGuard = p.getBoolean(KEY_FEATURE_GUARD, false);
        cfg.submitThrottle = p.getBoolean(KEY_SUBMIT_THROTTLE, false);
        cfg.lazySurfacesOff = p.getBoolean(KEY_LAZY_SURFACES_OFF, false);
        cfg.allHostBounce = p.getBoolean(KEY_ALL_HOST_BOUNCE, false);
        cfg.importThreadAlways = p.getBoolean(KEY_IMPORT_THREAD_ALWAYS, false);
        cfg.tuDebugSafe = p.getBoolean(KEY_TU_DEBUG_SAFE, false);
        cfg.verboseEngineLogs = p.getBoolean(KEY_VERBOSE_ENGINE_LOGS, false);
        return cfg;
    }

    static void save(Context c, Config cfg) {
        if (c == null || cfg == null) {
            return;
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_VANILLA_FALLBACK, cfg.vanillaFallback)
                .putBoolean(KEY_KGSL_TRACE, cfg.kgslTrace)
                .putBoolean(KEY_SYNC_LOGGING, cfg.syncLogging)
                .putBoolean(KEY_MULTI_VMA_BOUNCE, cfg.multiVmaBounce)
                .putBoolean(KEY_KGSL_CACHE_SYNC, cfg.kgslCacheSync)
                .putBoolean(KEY_FEATURE_GUARD, cfg.featureGuard)
                .putBoolean(KEY_SUBMIT_THROTTLE, cfg.submitThrottle)
                .putBoolean(KEY_LAZY_SURFACES_OFF, cfg.lazySurfacesOff)
                .putBoolean(KEY_ALL_HOST_BOUNCE, cfg.allHostBounce)
                .putBoolean(KEY_IMPORT_THREAD_ALWAYS, cfg.importThreadAlways)
                .putBoolean(KEY_TU_DEBUG_SAFE, cfg.tuDebugSafe)
                .putBoolean(KEY_VERBOSE_ENGINE_LOGS, cfg.verboseEngineLogs)
                .commit();
    }

    static void resetDefaults(Context c) {
        save(c, Config.safeDefaults());
    }

    static boolean needsGpuPrompt(Context c) {
        if (c == null) {
            return false;
        }
        return !c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_GPU_PROMPT_HANDLED, false);
    }

    static void markGpuPromptHandled(Context c) {
        if (c == null) {
            return;
        }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_GPU_PROMPT_HANDLED, true)
                .commit();
    }

    static void setCompatRunActive(boolean active) {
        compatRunActive = active;
    }

    static boolean syncLogging(Context c) {
        Config cfg = load(c);
        return !cfg.vanillaFallback && cfg.syncLogging;
    }

    /** True only while a compat run is active AND syncLogging is enabled (prevents fsync on normal guests). */
    static boolean isSyncLogActive(Context c) {
        return compatRunActive && syncLogging(c);
    }

    static boolean shouldUseIcdShim(boolean compat26, boolean sysDriver, Config cfg) {
        return compat26 && !sysDriver && cfg != null && cfg.needsIcdWrapper();
    }

    /**
     * Computes environment additions for an iOS 26 compatibility-mode launch.
     * Explicit {@code env.<NAME>} entries from {@code overrides} (ios26.txt) always win.
     */
    static Map<String, String> envAdditions(
            boolean compat26,
            boolean sysDriver,
            Config cfg,
            File nativeLibDir,
            File linksDir,
            File runDir,
            Map<String, String> overrides) {
        Map<String, String> add = new LinkedHashMap<>();
        if (!compat26 || cfg == null || cfg.vanillaFallback) {
            return add;
        }
        Map<String, String> ov = overrides != null ? overrides : java.util.Collections.emptyMap();

        if (cfg.syncLogging) {
            putIfNotOverridden(add, ov, "ASOFT_SYNC_LOG", "1");
            putIfNotOverridden(add, ov, "ASOFT_SYNC_SERIAL", "1");
        }
        if (cfg.kgslTrace && runDir != null) {
            putIfNotOverridden(add, ov, "ASOFT_KGSL_TRACE_FILE", new File(runDir, TRACE_FILE).getPath());
        }
        if (cfg.multiVmaBounce) {
            putIfNotOverridden(add, ov, "ASOFT_SHIM_MULTI_VMA_BOUNCE", "1");
        }
        if (cfg.allHostBounce) {
            putIfNotOverridden(add, ov, "ASOFT_SHIM_ALL_HOST_BOUNCE", "1");
        }
        if (cfg.kgslCacheSync) {
            putIfNotOverridden(add, ov, "ASOFT_SHIM_CACHE_SYNC", "1");
        }
        if (cfg.featureGuard) {
            putIfNotOverridden(add, ov, "ASOFT_SHIM_FEATURE_GUARD", "1");
        }
        if (cfg.submitThrottle) {
            putIfNotOverridden(add, ov, "ASOFT_SHIM_SUBMIT_THROTTLE", "1");
        }
        if (cfg.lazySurfacesOff) {
            putIfNotOverridden(add, ov, ImportMode.LAZY_MAPPINGS, ImportMode.LISTS);
        }
        if (cfg.importThreadAlways) {
            putIfNotOverridden(add, ov, "TU_KGSL_IMPORT_THREAD", "always");
        }
        if (cfg.tuDebugSafe) {
            putIfNotOverridden(add, ov, "TU_DEBUG", SAFE_TU_DEBUG);
        }
        if (cfg.verboseEngineLogs) {
            putIfNotOverridden(add, ov, "REIMS_VGPU_LOG", "debug");
            putIfNotOverridden(add, ov, "METAL2VULKAN_LOG_MEMORY", "1");
        }
        if (!sysDriver && linksDir != null && cfg.needsIcdWrapper()) {
            putIfNotOverridden(add, ov, "ASOFT_REAL_TURNIP_PATH", new File(linksDir, "libvulkan_freedreno.so").getPath());
        }
        if (nativeLibDir != null && cfg.needsLdPreload()) {
            File shim = new File(nativeLibDir, SHIM_LIB);
            putIfNotOverridden(add, ov, "LD_PRELOAD", shim.getPath());
        }
        return add;
    }

    private static void putIfNotOverridden(Map<String, String> out, Map<String, String> ov, String key, String value) {
        if (!ov.containsKey("env." + key)) {
            out.put(key, value);
        }
    }

    /** Short one-line summary for the Settings row subtitle. */
    static String summary(Config cfg) {
        if (cfg == null) {
            return "Off (defaults)";
        }
        if (cfg.vanillaFallback) {
            return "Off (Vanilla 0.1.7)";
        }
        int on = countActive(cfg);
        if (on == 0) {
            return "Off (defaults)";
        }
        if (cfg.kgslTrace && cfg.syncLogging && on == 2) {
            return "KGSL trace + sync logs";
        }
        return on + " active";
    }

    static int countActive(Config cfg) {
        if (cfg == null || cfg.vanillaFallback) {
            return 0;
        }
        int n = 0;
        if (cfg.kgslTrace) n++;
        if (cfg.syncLogging) n++;
        if (cfg.multiVmaBounce) n++;
        if (cfg.kgslCacheSync) n++;
        if (cfg.featureGuard) n++;
        if (cfg.submitThrottle) n++;
        if (cfg.lazySurfacesOff) n++;
        if (cfg.allHostBounce) n++;
        if (cfg.importThreadAlways) n++;
        if (cfg.tuDebugSafe) n++;
        if (cfg.verboseEngineLogs) n++;
        return n;
    }

    /** Human-readable log line recorded in app.txt before each iOS 26 compat launch. */
    static String describe(Config cfg, boolean icdShimmed) {
        if (cfg == null || cfg.vanillaFallback) {
            return "gpu compat #92: vanilla fallback (all shims/overrides disabled)";
        }
        return "gpu compat #92: icdShim=" + (icdShimmed ? "on" : "off")
                + ", kgslTrace=" + b(cfg.kgslTrace)
                + ", syncLog=" + b(cfg.syncLogging)
                + ", multiVmaBounce=" + b(cfg.multiVmaBounce)
                + ", allHostBounce=" + b(cfg.allHostBounce)
                + ", kgslCacheSync=" + b(cfg.kgslCacheSync)
                + ", featureGuard=" + b(cfg.featureGuard)
                + ", submitThrottle=" + b(cfg.submitThrottle)
                + ", lazyViews=" + (cfg.lazySurfacesOff ? "lists" : "default")
                + ", tuThread=" + (cfg.importThreadAlways ? "always" : "default")
                + ", tuDebug=" + (cfg.tuDebugSafe ? SAFE_TU_DEBUG : "off")
                + ", verboseLog=" + b(cfg.verboseEngineLogs);
    }

    private static String b(boolean v) {
        return v ? "on" : "off";
    }
}
