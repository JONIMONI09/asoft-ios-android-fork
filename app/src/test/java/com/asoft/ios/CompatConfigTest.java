package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public class CompatConfigTest {
    private static final File NATIVE_DIR = new File("/app/lib/arm64");
    private static final File LINKS_DIR = new File("/app/files/lib");
    private static final File RUN_DIR = new File("/app/files/run");
    private static final Map<String, String> NO_OVERRIDES = Collections.emptyMap();

    @Test
    public void defaultsAreAllOffUntilOptedIn() {
        CompatConfig.Config cfg = CompatConfig.Config.safeDefaults();
        assertFalse(cfg.vanillaFallback);
        assertFalse(cfg.kgslTrace);
        assertFalse(cfg.syncLogging);
        assertFalse(cfg.multiVmaBounce);
        assertFalse(cfg.kgslCacheSync);
        assertFalse(cfg.featureGuard);
        assertFalse(cfg.submitThrottle);
        assertFalse(cfg.lazySurfacesOff);
        assertFalse(cfg.allHostBounce);
        assertFalse(cfg.importThreadAlways);
        assertFalse(cfg.tuDebugSafe);
        assertFalse(cfg.verboseEngineLogs);
        assertEquals("Off (defaults)", CompatConfig.summary(cfg));
        assertFalse(CompatConfig.shouldUseIcdShim(true, false, cfg));
        assertTrue(CompatConfig.envAdditions(true, false, cfg, NATIVE_DIR, LINKS_DIR, RUN_DIR, NO_OVERRIDES).isEmpty());
    }

    @Test
    public void kgslTraceIsIndependentFromSyncLogging() {
        CompatConfig.Config cfg = CompatConfig.Config.safeDefaults();
        cfg.kgslTrace = true;
        Map<String, String> env = CompatConfig.envAdditions(
                true, false, cfg, NATIVE_DIR, LINKS_DIR, RUN_DIR, NO_OVERRIDES);
        assertEquals(new File(RUN_DIR, CompatConfig.TRACE_FILE).getPath(), env.get("ASOFT_KGSL_TRACE_FILE"));
        assertFalse(env.containsKey("ASOFT_SYNC_LOG"));
        assertFalse(env.containsKey("ASOFT_SYNC_SERIAL"));
        assertTrue(CompatConfig.shouldUseIcdShim(true, false, cfg));

        CompatConfig.Config traceOnly = CompatConfig.Config.traceOnly();
        assertEquals("KGSL trace + sync logs", CompatConfig.summary(traceOnly));
    }

    @Test
    public void vanillaFallbackDisablesAllOverridesAndIcdShim() {
        CompatConfig.Config cfg = CompatConfig.Config.recommendedForKnownKgslBug();
        cfg.vanillaFallback = true;
        cfg.allHostBounce = true;
        assertEquals("Off (Vanilla 0.1.7)", CompatConfig.summary(cfg));
        assertFalse(CompatConfig.shouldUseIcdShim(true, false, cfg));
        assertTrue(CompatConfig.envAdditions(true, false, cfg, NATIVE_DIR, LINKS_DIR, RUN_DIR, NO_OVERRIDES).isEmpty());
    }

    @Test
    public void nonCompatOrWatchRunsReceiveNoAdditions() {
        CompatConfig.Config cfg = CompatConfig.Config.recommendedForKnownKgslBug();
        assertTrue(CompatConfig.envAdditions(false, false, cfg, NATIVE_DIR, LINKS_DIR, RUN_DIR, NO_OVERRIDES).isEmpty());
    }

    @Test
    public void recommendedPresetPopulatesIcdAndPreloadAndSyncVars() {
        CompatConfig.Config cfg = CompatConfig.Config.recommendedForKnownKgslBug();
        Map<String, String> env = CompatConfig.envAdditions(
                true, false, cfg, NATIVE_DIR, LINKS_DIR, RUN_DIR, NO_OVERRIDES);

        assertEquals("1", env.get("ASOFT_SYNC_LOG"));
        assertEquals("1", env.get("ASOFT_SYNC_SERIAL"));
        assertEquals(new File(RUN_DIR, CompatConfig.TRACE_FILE).getPath(), env.get("ASOFT_KGSL_TRACE_FILE"));
        assertEquals("1", env.get("ASOFT_SHIM_MULTI_VMA_BOUNCE"));
        assertEquals("1", env.get("ASOFT_SHIM_CACHE_SYNC"));
        assertEquals("1", env.get("ASOFT_SHIM_FEATURE_GUARD"));
        assertEquals("1", env.get("ASOFT_SHIM_SUBMIT_THROTTLE"));
        assertEquals(ImportMode.LISTS, env.get(ImportMode.LAZY_MAPPINGS));
        assertEquals(new File(LINKS_DIR, "libvulkan_freedreno.so").getPath(), env.get("ASOFT_REAL_TURNIP_PATH"));
        assertEquals(new File(NATIVE_DIR, CompatConfig.SHIM_LIB).getPath(), env.get("LD_PRELOAD"));
        assertFalse(env.containsKey("ASOFT_SHIM_ALL_HOST_BOUNCE"));
        assertFalse(env.containsKey("TU_KGSL_IMPORT_THREAD"));
        assertFalse(env.containsKey("TU_DEBUG"));
        assertFalse(env.containsKey("METAL2VULKAN_LOG_MEMORY"));
    }

    @Test
    public void optInTogglesAndIos26TxtOverridesRespected() {
        CompatConfig.Config cfg = CompatConfig.Config.recommendedForKnownKgslBug();
        cfg.allHostBounce = true;
        cfg.importThreadAlways = true;
        cfg.tuDebugSafe = true;
        cfg.verboseEngineLogs = true;

        Map<String, String> ov = new HashMap<>();
        ov.put("env.REIMS_VGPU_LAZY_MAPPINGS", "on");
        ov.put("env.TU_DEBUG", "noubwc");

        Map<String, String> env = CompatConfig.envAdditions(
                true, false, cfg, NATIVE_DIR, LINKS_DIR, RUN_DIR, ov);

        assertEquals("1", env.get("ASOFT_SHIM_ALL_HOST_BOUNCE"));
        assertEquals("always", env.get("TU_KGSL_IMPORT_THREAD"));
        assertEquals("debug", env.get("REIMS_VGPU_LOG"));
        assertEquals("1", env.get("METAL2VULKAN_LOG_MEMORY"));
        // Explicit ios26.txt env.* wins
        assertFalse(env.containsKey("REIMS_VGPU_LAZY_MAPPINGS"));
        assertFalse(env.containsKey("TU_DEBUG"));
        assertTrue(CompatConfig.describe(cfg, true).contains("allHostBounce=on"));
        assertTrue(CompatConfig.describe(cfg, true).contains("kgslTrace=on"));
    }

    @Test
    public void appLogSyncIsInactiveOutsideCompatRun() {
        CompatConfig.setCompatRunActive(false);
        assertFalse(CompatConfig.isSyncLogActive(null));
    }
}
