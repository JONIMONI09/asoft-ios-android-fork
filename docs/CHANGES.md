# Changelog — iOS 26 SM8650 / KGSL Freeze Fix (Upstream Issue #92)

## Timeline & Integration

- **Feature Commit (`fix/ios26-kgsl-issue92`):** `fb595c1` (`fb595c14bccb756c1b1f28ff20b0519419e908d3`) at `2026-11-23 18:17:50 UTC` (`19:17:50 CET` / `10:17:50 -08:00`)
- **Merged into `main`:** `c31633a` (`c31633a82f3f330be277a4ae4af7120ae0b18c5e`) at `2026-11-23 18:17:50 UTC` (`19:17:50 CET` / `10:17:50 -08:00`)
- **Code Freeze:** All C and Java source/test files were frozen prior to merging into `main`; only Markdown documentation (`CHANGES.md`) follows.

---

## Changed & Added Files (13 files, `2858` insertions, `7` deletions)

### Native C / NDK (`app/src/main/cpp/`)
- **`CMakeLists.txt`** (`L18-L21`): Added `kgslshim` shared library target (`kgsl_shim.c`, `-O2 -Wall -Wextra`, linked with `log` and `dl`).
- **`render.c`** (`L162-L173`): Opens `qemu-stderr.txt` with `O_SYNC` when `ASOFT_SYNC_LOG=1` is present in `envp` (preserving JNI signature and non-`O_SYNC` fallback).
- **`kgsl_shim.h`** (`L1-L83`): Pure shared structs & declarations for VMA parsing, 3-way page-diff bounce sync, extension guard, and `O_SYNC` trace rotation.
- **`kgsl_shim.c`** (`L1-L1956`):
  - Vulkan ICD wrapper (`vk_icdNegotiateLoaderICDInterfaceVersion`, `vk_icdGetPhysicalDeviceProcAddr`, `vk_icdGetInstanceProcAddr` delegating to real Turnip via `ASOFT_REAL_TURNIP_PATH`).
  - `LD_PRELOAD` interposers for `open`/`open64`/`openat`/`openat64`/`close`/`ioctl` on `/dev/kgsl-3d0` and `serial.txt`.
  - Multi-VMA (and opt-in all-readable) host-import bounce onto `HOST_VISIBLE | HOST_COHERENT` `VkDeviceMemory` with 4 KiB 3-way page-diff sync & byte-level collision merge.
  - `IOCTL_KGSL_GPUMEM_SYNC_CACHE` / `IOCTL_KGSL_GPUOBJ_SYNC` cache maintenance around command submissions & waits.
  - Device submit serialization & unfenced drain (`ASOFT_SHIM_SUBMIT_THROTTLE`), sparse/feature guard (`ASOFT_SHIM_FEATURE_GUARD`), and `O_SYNC` trace logging (`kgsl-trace.txt` with 2 MiB rotation to `kgsl-trace.txt.prev`).

### Android App / Java (`app/src/main/java/com/asoft/ios/`)
- **`CompatConfig.java`** (`L1-L262`): Safe-Default toggles, Opt-in toggles, Vanilla 0.1.7 fallback, `SharedPreferences` persistence, `ios26.txt` override priority, and launch env assembly.
- **`AppLog.java`** (`L36-L84`): Conditional `FileOutputStream.getFD().sync()` in `writeLineSync()` and cross-boot retention of `kgsl-trace.txt` -> `last-kgsl-trace.txt`.
- **`RunLogs.java`** (`L64-L73`): Exposes `kgsl-trace` and `last-kgsl-trace` in `LogsActivity`.
- **`Guest.java`** (`L268-L280`, `L2444-L2467`): Adds `writeShimmedVulkanIcd()` (`turnip_icd_shim.json`) and wires `CompatConfig` strictly when `!watch && compat26` (`os == IOS26`).
- **`MainActivity.java`** (`L72`, `L427`, `L1439`, `L1448-L1502`): Adds the `"iOS 26 GPU compatibility"` Settings row and multi-choice configuration dialog (`showCompat26Dialog()`) with Safe-Default reset and Vanilla Fallback.

### Tests & Documentation
- **`app/src/test/cpp/kgsl_shim_test.c`** (`L1-L158`): Host C unit tests for `/proc/<pid>/maps` VMA parsing, read/write checks, 3-way page-diff & partial-page concurrent write preservation, extension guard, and `O_SYNC` trace rotation.
- **`app/src/test/java/com/asoft/ios/CompatConfigTest.java`** (`L1-L102`): JUnit tests for Safe Defaults, Vanilla Fallback, non-compat gating, and `ios26.txt` precedence.
- **`app/src/test/java/com/asoft/ios/AppLogSyncTest.java`** (`L1-L43`): JUnit test for persistent synced/non-synced line appends.
- **`FIX_KGSL_ISSUE92_README.md`** (`L1-L120`): Full technical architecture, line-by-line reference, toggle matrix, and build/verification guide.

---

## Syntax, Build & Test Verification Report (Pre-Merge State)

1. **NDK C Syntax & Warning Check (NDK `29.0.13846066`, `aarch64-linux-android29-clang`):**
   - `kgsl_shim.c` (`-O2 -Wall -Wextra -Werror`): **0 errors, 0 warnings**
   - `render.c` (`-O2 -Wall -Werror`): **0 errors, 0 warnings**
   - ELF inspection (`llvm-readelf -d libkgslshim.so`): depends only on `liblog.so`, `libdl.so`, `libm.so`, `libc.so`; `llvm-nm -D` confirms all 9 ICD & libc interposer exports.
2. **Host C Unit Tests (`gcc -Wall -Wextra -Werror -DASOFT_SHIM_HOST_TEST`):**
   - `kgsl_shim_test`: **all assertions passed** (0 failures, 0 warnings).
3. **Android JVM Unit Tests (`./gradlew clean testDebugUnitTest`):**
   - **45 test suites, 397 tests executed: 0 failures, 0 errors, 1 skipped** (`CounterFreqTest` ARM64 host-counter assumption skipped on x86_64 CI/devbox host).
4. **Android Lint (`./gradlew lintDebug`):**
   - **0 errors and 0 warnings** in all modified or newly added files.
   - 1 pre-existing upstream lint error in untouched `MiniJson.java:93` (`ByteOrderMark` character literal) left untouched out of scope.
5. **Clean Debug APK Build (`./gradlew clean assembleDebug`, NDK `29.0.13846066`, CMake `3.22.1`):**
   - **`BUILD SUCCESSFUL` in 47s** (`44 actionable tasks: 44 executed`).
   - Output: `app/build/outputs/apk/debug/app-debug.apk` (`109 MB`, `apksigner verify`: v2 scheme verified), containing freshly compiled `libkgslshim.so` (`224 KB`), `librender.so`, `libgpucheck.so`, `libunpack.so` alongside prebuilt 0.1.7 `libqemu26.so`, `libqemu.so`, Turnip (`lib_libvulkan_freedreno.so.so`), Vulkan loader, and 118 assets.
6. **Hardware Verification Status:**
   - Not yet run on physical Snapdragon `SM8650` / Adreno 750 hardware (REDMAGIC Nova `NP03J`) in the Linux build VM; requires on-device validation and maintainer code review.

---

# Part 2 — UX, Stability, Split-Screen, Game Space & Wireless Debugging Fixes (`work-ci-branch`)

## Timeline & Branch Structure
- **Working Branch:** `work-ci-branch` (unified working branch containing all UX/stability fixes and `.github/workflows/apk-release.yml`, which automatically builds and overwrites pre-release tag `v0.1.7-ux-stability-test`).
- **Upstream PR `#371` (`main`, `a33a8c5`):** Kept strictly clean with ONLY the Issue #92 KGSL fix (zero UX/stability or CI changes).
- **Key Commits:** `496b2c0` (initial UX/stability pack), `d89fa07` (ZTE `GameActivityStub` isolation), `4c258d2` (exact upstream `WadbPairService` `RemoteInput` restoration), `3953cce` (unified `work-ci-branch`).

---

## Bugs Fixed, Regressions Caught & Enhancements Added

1. **Wireless Debugging Pairing Code Input in Notification Bar & Dialog (`WadbPairService.java`, `AdbMdns.java`, `MainActivity.java`, `WadbPairInputTest.java`):**
   - **Regressions Caught & Root Causes:**
     - *Regression 1 (`496b2c0`):* Switching `RemoteInput` to `PendingIntent.getBroadcast` (`ReplyReceiver`), adding a non-null `Icon`, and calling `NotificationManager.notify()` on every mDNS callback broke inline code entry on Android 16 (REDMAGIC OS 11).
     - *Regression 2 (`d89fa07`):* Changing `PendingIntent.getService` to `PendingIntent.getForegroundService` (`selfForeground`) prevented tapping `"Enter the code"` from opening the inline `RemoteInput` box on Android 16 / REDMAGIC OS 11, and forcing `boolean ask = !pairing` caused `"Enter the code"` to appear immediately before Wireless Debugging / the pairing dialog was even open.
   - **Final Fix (`4c258d2` + regression test):**
     - Restored the **exact upstream & Shizuku native `RemoteInput` pattern**: `PendingIntent.getService(...)` (`self("code", ...)`), `null` action icons, `setOnlyAlertOnce(!ask)`, and `show(String text, boolean ask)` so `"Enter the code"` only appears when `AdbMdns` detects the open pairing dialog (`p > 0`) and hides again when it closes (`p <= 0`).
     - Kept only safe, non-intrusive improvements: `shouldNotifyPortChange()` + `lastShownText`/`lastShownAsk` deduplication (prevents duplicate mDNS callbacks from closing the open keyboard), `parsePairInput()` (`123456`, `123 456`, `123456 37215`, `123456:37215`), `AdbMdns.ownAddress()` (raw IPv6 byte matching & API >= 34 `getHostAddresses()`), and the in-app **`Enter code here`** dialog (`showPairCodeDialog()`) in `MainActivity` for Split-Screen use.
     - Added regression test `WadbPairInputTest.remoteInputUsesUpstreamPendingIntentGetServiceAndAskGating()` to enforce `PendingIntent.getService`, `null` icon, and `ask`-gated `show(String text, boolean ask)` permanently in CI.
2. **Broken Setup Pause Button & Missing Helper-VM / Step-Boundary Pause (`PauseUi.java`, `SetupService.java`, `Setup26Screen.java`, `SetupScreen.java`, `PhoneSetup.java`, `PhoneSetup26.java`, `MainActivity.java`):**
   - **Root Cause:** Tapping `Pause` left the button clickable and unchanged during async stop; `Helper.hold` updated `state.held` (`SIGSTOP`) without setting `state.paused`; and non-interruptible steps (`:firstboot`, `:wallpaper`, `:pack`, `:unpack`, non-download iOS 16 steps) hid the Pause button completely instead of supporting deferred pause.
   - **Fix:** Synchronously set `state.pausing` / `state.pauseAfterStep` in `SetupService.pause(c)` so buttons immediately show greyed `"Pausing…"` or `"Pausing after step…"` (`enabled=false`, `alpha=0.4f`), synchronize `state.paused` inside `Helper.hold`, and check `Progress.stepPauseRequested()` between setup steps before starting the next step.
3. **Split-Screen & Multi-Window Support + Zero-Dimension Crashes (`AndroidManifest.xml`, `ScreenActivity.java`, `ScreenGeometry.java`, `WatchCase.java`, `render.c`):**
   - **Root Cause:** Portrait orientation locks in `AndroidManifest.xml` & `ScreenActivity.onCreate()`, incomplete `configChanges`, stopping `Choreographer` in `onPause()` instead of `onStop()`, immersive bars fighting split-screen, and unguarded zero/negative dimensions in `render.c`, `ScreenActivity.guestPoint()`/`keepThumb()`, and `WatchCase.onMeasure()`.
   - **Fix:** Set `android:resizeableActivity="true"`, remove portrait locks, handle `onMultiWindowModeChanged` / `onConfigurationChanged`, keep frame rendering live between `onStart()` and `onStop()`, and guard all layout/touch/GL math via `ScreenGeometry` & `render.c` bounds checks.
4. **REDMAGIC / OEM Game Space Mitigation, Survival Isolation & Upstream Issue #365 (`AndroidManifest.xml`, `GameEnvGuard.java`, `MainActivity.java`, `ScreenActivity.java`, `CrashText.java`, `AppExit.java`, `CrashPersistenceTest.java`):**
   - **Real Device Crash Caught (`20261123-151237-crash-19565`):** On REDMAGIC Nova (`NP03J`, Android 16) during `ios26:rootfs`, ZTE's injected `GameActivityStub` background thread threw `NullPointerException: Attempt to invoke virtual method 'void android.view.KeyMapObserver.stop(android.app.Activity)' on a null object reference` at `com.zte.gameassist.app.GameActivityStub.unregisterObserver`, killing the app and ongoing 30-minute helper VM setup.
   - **Fix:** Added `AppExit.shouldIsolateOemHookCrash()` to catch and log non-fatal exceptions solely inside injected OEM Game Space/Game Assistant background threads (with zero `com.asoft.ios.*` frames on the stack) so the app and helper VM survive unharmed. Also set `android:appCategory="productivity"`, added `GameEnvGuard` (`Window.setPreferMinimalPostProcessing`, `Surface.setFrameRate`, `GameManager` mode logging, dismissible Home warning banner), and `CrashText.gameAssistantKill()` attribution.
5. **Freeze Watchdog, `fsync`ed Crash Persistence & UI Cleanups (`FreezeWatchdog.java`, `AsoftApp.java`, `AppExit.java`, `CrashReport.java`, `LogsActivity.java`, `SetupActivity.java`):**
   - Added `FreezeWatchdog` (main-thread stall detection `>= 4 s` writing bounded thread/stack snapshots via `FileOutputStream.getFD().sync()` to `files/run/last-ui-freeze.txt`), `onTrimMemory`/`onLowMemory` logging, subprocess (`:gpucheck`) uncaught exception logging, `fsync` on `AppExit.save()` and `CrashReport.write()`, automatic attachment of `kgsl-trace.txt` & `last-ui-freeze.txt` to crash/exit reports, horizontal scroll on `LogsActivity` actions, and destroyed-Activity guard in `SetupActivity.finishRun()`.

---

## Verification Report

- **NDK C Checks (`aarch64-linux-android29-clang`):** `render.c` (`-Wall -Werror`) & `kgsl_shim.c` (`-Wall -Wextra -Werror`) — **0 errors, 0 warnings**.
- **C Host Unit Test (`gcc -DASOFT_SHIM_HOST_TEST -Wall -Wextra -Werror`):** `kgsl_shim_test` (including concurrent CPU+GPU same-page Host→Shadow 3-way merge test) — **all assertions passed**.
- **Android JVM Unit Tests (`./gradlew testDebugUnitTest`):** **50 suites, 421 tests (420 passed, 0 failed, 0 errors, 1 skipped** via JUnit assumption on non-ARM64 devbox) — includes `CompatConfigTest`, `AppLogSyncTest`, `PauseStateTest`, `GeometrySafetyTest`, `GameEnvGuardTest`, `CrashPersistenceTest`, and `WadbPairInputTest`.

---

# Part 3 — PR #371 Maintainer Review Fixes & Affected-GPU Prompt

1. **Byte-granular 3-way merge on Host → Shadow (`app/src/main/cpp/kgsl_shim.c`, `app/src/test/cpp/kgsl_shim_test.c`):**
   - `asoft_bounce_sync_to_shadow()` now uses byte-level 3-way merge when both CPU and GPU wrote to the same 4 KiB page (`if (h[i] != p[i]) { s[i] = h[i]; p[i] = h[i]; }`), preserving concurrent GPU writes instead of overwriting the whole page. Covered by `test_host_to_shadow_preserves_concurrent_gpu_writes()`.
2. **All Mitigations OFF by Default + Independent KGSL Trace Switch (`CompatConfig.java`, `MainActivity.java`, `CompatConfigTest.java`):**
   - All compat mitigations and trace toggles default to `false` until opted in.
   - `kgslTrace` (`KEY_KGSL_TRACE`) is now its own independent switch separate from `syncLogging` (`O_SYNC` stderr/serial/app log).
3. **Compat-Run-Scoped `AppLog` `fsync` (`AppLog.java`, `Guest.java`, `CompatConfig.java`):**
   - `AppLog.note0()` now gates `fsync` on `CompatConfig.isSyncLogActive(c)` (active only while an `ios26 && compat26` run is active and `syncLogging` is enabled — zero `fsync` overhead for normal guests).
4. **Affected-GPU Check Prompt (`Setup26Screen.java`, `CompatConfig.java`):**
   - When `GpuCheck` detects a Snapdragon/KGSL compatibility-mode GPU (`r.pass && r.nosparse && !systemDriver`), shows a native `AlertDialog` informing the user about Issue `#92` and auto-saving their choice (`Recommended (fast)`, `Trace only`, or `Keep defaults`).

---

# Part 4 — Shizuku Bridge, Verified In-App Updater, Sleep-Safe `DataMigration` & `kgsl_shim.c` Performance Pack (`2026-11-24` – `2026-11-25`)

1. **Direct Shizuku Bridge & Shizuku-First RAM Cleaner (`ShizukuBridge.java`, `MemoryCleaner.java`, `MainActivity.java`):**
   - Integrated official Shizuku API `13.1.5` (`dev.rikka.shizuku:api` + `provider`).
   - Added `MemoryCleaner.runPrivilegedShell()` preferring live Shizuku over stale Wireless ADB pairings (`ECONNREFUSED`), with automatic fallback, clean interrupt exit, stderr draining, and reliable timeout reporting.
   - Added clickable Home-screen `adbWarn` banner when Shizuku / Wireless debugging is disconnected on Android 14+, `"Grant Shizuku"` status, and one-tap **Use Shizuku** in all ADB dialogs.
2. **Verified In-App GitHub Release APK Updater (`ApkUpdater.java`, `UpdateCheck.java`, `ShareProvider.java`, `MainActivity.java`, `app/build.gradle`):**
   - Added Upstream (`alpersamur3`) vs. Fork CI (`JONIMONI09`) update channels with signing-key change warning, startup update check (`UpdateCheck.checkOnStart()`), and optional `-PasoftVersionName=...` in `app/build.gradle` for exact Fork CI version matching.
   - Added Setup/iOS running guard (`SetupService.busy() || Guest.running()`), live installed-vs-latest version check, `"App is up to date"` dialog with **Force reinstall** (clearing cached APK files via `ApkUpdater.clearCache()`), download-size vs. free-storage confirmation, transient DNS/network retry, `canRequestPackageInstalls()` check with `onResume()` auto-resume, and `safeDismiss()` dialog protection.
3. **Reboot-Safe Settings & Sleep-Safe `DataMigration` (`CompatConfig.java`, `SetupService.java`, `MemoryCleaner.java`, `UpdateCheck.java`, `PhoneSetup26.java`):**
   - Switched `SharedPreferences` writes to synchronous `.commit()` across settings classes.
   - Subtracted Android Deep Sleep duration (`elapsedRealtime - uptimeMillis`) from `PhoneSetup26.iosWallpaper()` active minutes and `MigrationWatch`, preventing false ceiling aborts after overnight device sleep.
4. **Setup-Safe GPU-Aware Preset & 5 High-Impact `kgsl_shim.c` Performance Optimizations (`kgsl_shim.c`, `kgsl_shim.h`, `CompatConfig.java`, `MainActivity.java`):**
   - Kept `lazySurfacesOff = false` (prevents 65,530 `vm.max_map_count` crashes during `DataMigration`) and `kgslTrace = false` / `syncLogging = false` (no per-submit flash `fsync`) in `recommendedForKnownKgslBug()`, plus **Auto for GPU** button and per-toggle plain-English explanations.
   - **Fix 1 (`submitThrottle`):** Skip `vkQueueWaitIdle()` drain when `pSubmits` signals any Vulkan semaphore (eliminates full-pipeline stalls on `reims` timeline-semaphore frames).
   - **Fix 2 (`kgslCacheSync`):** Batch BO syncs into a single `IOCTL_KGSL_GPUOBJ_SYNC` syscall per KGSL fd, eliminate duplicate sync in `IOCTL_KGSL_GPU_COMMAND` via thread-local `t_in_vk_submit`, skip when `g_kgsl_bo_count == 0`, and deduplicate `vkGetFenceStatus` polling syncs.
   - **Fix 3 (`/proc/self/maps`):** Gate parsing on active bounce/trace and `break` immediately once `lo >= end`.
   - **Fix 4 (`multiVmaBounce`):** Prefer `HOST_VISIBLE | HOST_COHERENT | HOST_CACHED` over uncached WC shadow memory, use a dedicated `g_bounce_lock`, and skip scans when `g_bounce_active_count == 0`.
   - **Fix 5 (`LD_PRELOAD`):** Use a lock-free atomic FD bitset (`g_kgsl_fd_bits`) for non-KGSL `close()`/`ioctl()` calls and only inject `LD_PRELOAD` when `kgslTrace || syncLogging || kgslCacheSync` is enabled.
5. **Save Recovery, Delete All Saves & Factory Reset UX (`Guest.java`, `ScreenActivity.java`, `MainActivity.java`):**
   - Added `Guest.deleteSnapshot(Context, int, String)` and `Guest.deleteAllSnapshots(Context, int)` to scan and delete every qcow2 snapshot (including both quick-save slots and named saves) plus sidecar metadata files without touching the installed OS.
   - Added a **`Recovery / Boot without save…`** button directly on `ScreenActivity`'s stopped/crash overlay offering a 3-step escalation: (1) Restart without save (cold boot), (2) Delete all saves & cold boot, and (3) Factory reset iPhone/watch (`Guest.erase`, wipes user settings/apps/saves while keeping iOS/watchOS installed with no reinstall).
   - Added **`Delete all saves (N)`** (plus cold boot & factory reset when stopped) to `ScreenActivity`'s sidebar `Saves` dialog, and **`Manage / delete all saves…`** + clearer **`Factory reset iPhone (keep …)…`** labeling to `MainActivity`'s OS menu.




