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

    static final String TRACE_FILE = "kgsl-trace.txt";
    static final String SHIM_LIB = "libkgslshim.so";
    static final String SAFE_TU_DEBUG = "noubwc,flushall,sysmem";

    private CompatConfig() {}

    static final class Config {
        boolean vanillaFallback;
        // Safe-defaults (ON out of the box)
        boolean syncLogging = true;
        boolean multiVmaBounce = true;
        boolean kgslCacheSync = true;
        boolean featureGuard = true;
        boolean submitThrottle = true;
        boolean lazySurfacesOff = true;
        // Opt-in toggles (OFF by default)
        boolean allHostBounce = false;
        boolean importThreadAlways = false;
        boolean tuDebugSafe = false;
        boolean verboseEngineLogs = false;

        static Config safeDefaults() {
            return new Config();
        }

        Config copy() {
            Config c = new Config();
            c.vanillaFallback = vanillaFallback;
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
            return !vanillaFallback && (multiVmaBounce || allHostBounce || featureGuard || submitThrottle || syncLogging);
        }

        boolean needsLdPreload() {
            return !vanillaFallback && (syncLogging || kgslCacheSync || multiVmaBounce || allHostBounce || featureGuard || submitThrottle);
        }
    }

    static Config load(Context c) {
        if (c == null) {
            return Config.safeDefaults();
        }
        SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        Config cfg = new Config();
        cfg.vanillaFallback = p.getBoolean(KEY_VANILLA_FALLBACK, false);
        cfg.syncLogging = p.getBoolean(KEY_SYNC_LOGGING, true);
        cfg.multiVmaBounce = p.getBoolean(KEY_MULTI_VMA_BOUNCE, true);
        cfg.kgslCacheSync = p.getBoolean(KEY_KGSL_CACHE_SYNC, true);
        cfg.featureGuard = p.getBoolean(KEY_FEATURE_GUARD, true);
        cfg.submitThrottle = p.getBoolean(KEY_SUBMIT_THROTTLE, true);
        cfg.lazySurfacesOff = p.getBoolean(KEY_LAZY_SURFACES_OFF, true);
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
                .apply();
    }

    static void resetDefaults(Context c) {
        save(c, Config.safeDefaults());
    }

    static boolean syncLogging(Context c) {
        Config cfg = load(c);
        return !cfg.vanillaFallback && cfg.syncLogging;
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
            if (runDir != null) {
                putIfNotOverridden(add, ov, "ASOFT_KGSL_TRACE_FILE", new File(runDir, TRACE_FILE).getPath());
            }
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
            return "Safe defaults";
        }
        if (cfg.vanillaFallback) {
            return "Off (Vanilla 0.1.7)";
        }
        boolean defaultsExact = cfg.syncLogging && cfg.multiVmaBounce && cfg.kgslCacheSync
                && cfg.featureGuard && cfg.submitThrottle && cfg.lazySurfacesOff
                && !cfg.allHostBounce && !cfg.importThreadAlways
                && !cfg.tuDebugSafe && !cfg.verboseEngineLogs;
        if (defaultsExact) {
            return "Safe defaults";
        }
        int on = countActive(cfg);
        return on + " active (custom)";
    }

    static int countActive(Config cfg) {
        if (cfg == null || cfg.vanillaFallback) {
            return 0;
        }
        int n = 0;
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
