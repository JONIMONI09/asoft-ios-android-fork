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

# Part 2 — UX, Stability, Split-Screen, Game Space & Wireless Debugging Fixes (`feat/ux-stability-pack`)

## Timeline & Branch Isolation
- **Branch:** `feat/ux-stability-pack` (branched off `main` `a33a8c5`, so Upstream PR `#371` and `main` remain completely clean and unaffected; contains zero CI workflows and zero APK binaries).
- **Implementation Commit:** `496b2c0` (`2026-11-23 20:09:41 UTC`) followed by documentation update in `CHANGES.md`.

---

## Bugs Fixed & Enhancements Added (27 files)

1. **Wireless Debugging Pairing Code Unavailable / Missing in Notification Bar (`WadbPairService.java`, `AdbMdns.java`, `MainActivity.java`, `AndroidManifest.xml`):**
   - **Root Cause:** `WadbPairService` omitted the `"Enter the code"` `RemoteInput` action whenever mDNS discovery hadn't finished yet (`SEARCHING`), disappeared (`onServiceLost` when opening the shade), or after a failed attempt (`show(..., false)`). Additionally, `AdbMdns.own()` compared `InetAddress.equals()` strictly, which rejects link-local IPv6 addresses on Android 14–16 when `scope_id` differs or `getHost()` is null (`getHostAddresses()`).
   - **Fix:** Always keep `"Enter the code"` (`RemoteInput`, free-form enabled, explicit `Icon`) available while ongoing; route inline replies via `PendingIntent.getBroadcast` -> `WadbPairService$ReplyReceiver`; parse `"123456"`, `"123 456"`, `"123456 37215"`, and `"123456:37215"` cleanly via `parsePairInput()`; compare raw IP bytes and `API >= 34` `getHostAddresses()` in `AdbMdns.ownAddress()`; and add an in-app **`Enter code here`** fallback dialog (`showPairCodeDialog()`) in `MainActivity.wirelessDebugging()` for Split-Screen use.
2. **Broken Setup Pause Button & Missing Helper-VM / Step-Boundary Pause (`PauseUi.java`, `SetupService.java`, `Setup26Screen.java`, `SetupScreen.java`, `PhoneSetup.java`, `PhoneSetup26.java`, `MainActivity.java`):**
   - **Root Cause:** Tapping `Pause` left the button clickable and unchanged during async stop; `Helper.hold` updated `state.held` (`SIGSTOP`) without setting `state.paused`; and non-interruptible steps (`:firstboot`, `:wallpaper`, `:pack`, `:unpack`, non-download iOS 16 steps) hid the Pause button completely instead of supporting deferred pause.
   - **Fix:** Synchronously set `state.pausing` / `state.pauseAfterStep` in `SetupService.pause(c)` so buttons immediately show greyed `"Pausing…"` or `"Pausing after step…"` (`enabled=false`, `alpha=0.4f`), synchronize `state.paused` inside `Helper.hold`, and check `Progress.stepPauseRequested()` between setup steps before starting the next step.
3. **Split-Screen & Multi-Window Support + Zero-Dimension Crashes (`AndroidManifest.xml`, `ScreenActivity.java`, `ScreenGeometry.java`, `WatchCase.java`, `render.c`):**
   - **Root Cause:** Portrait orientation locks in `AndroidManifest.xml` & `ScreenActivity.onCreate()`, incomplete `configChanges`, stopping `Choreographer` in `onPause()` instead of `onStop()`, immersive bars fighting split-screen, and unguarded zero/negative dimensions in `render.c`, `ScreenActivity.guestPoint()`/`keepThumb()`, and `WatchCase.onMeasure()`.
   - **Fix:** Set `android:resizeableActivity="true"`, remove portrait locks, handle `onMultiWindowModeChanged` / `onConfigurationChanged`, keep frame rendering live between `onStart()` and `onStop()`, and guard all layout/touch/GL math via `ScreenGeometry` & `render.c` bounds checks.
4. **REDMAGIC / OEM Game Space Mitigation & Upstream Issue #365 Attribution (`AndroidManifest.xml`, `GameEnvGuard.java`, `MainActivity.java`, `ScreenActivity.java`, `CrashText.java`, `AppExit.java`):**
   - Set `android:appCategory="productivity"`.
   - Added `GameEnvGuard`: `Window.setPreferMinimalPostProcessing(true)` (API 30+), `Surface.setFrameRate(60f, ...)` (API 30+), `GameManager` mode logging (API 31+), focus/`ACTION_CANCEL` burst/resize logging, and a dismissible Home warning banner when OEM Game Space (`com.zte.gameservice`, `com.nubia.gamelauncher`, etc.) is detected.
   - Added `CrashText.gameAssistantKill()` and `AppExit` stack scanning so crashes injected by OEM Game Assistants (`com.zte.gameassist`, etc., Issue `#365`) clearly identify Game Space as the external culprit.
5. **Freeze Watchdog, `fsync`ed Crash Persistence & UI Cleanups (`FreezeWatchdog.java`, `AsoftApp.java`, `AppExit.java`, `CrashReport.java`, `LogsActivity.java`, `SetupActivity.java`):**
   - Added `FreezeWatchdog` (main-thread stall detection `>= 4 s` writing bounded thread/stack snapshots via `FileOutputStream.getFD().sync()` to `files/run/last-ui-freeze.txt`), `onTrimMemory`/`onLowMemory` logging, subprocess (`:gpucheck`) uncaught exception logging, `fsync` on `AppExit.save()` and `CrashReport.write()`, automatic attachment of `kgsl-trace.txt` & `last-ui-freeze.txt` to crash/exit reports, horizontal scroll on `LogsActivity` actions, and destroyed-Activity guard in `SetupActivity.finishRun()`.

---

## Verification Report (`feat/ux-stability-pack`)

- **NDK C Checks (`aarch64-linux-android29-clang`):** `render.c` (`-Wall -Werror`) & `kgsl_shim.c` (`-Wall -Wextra -Werror`) — **0 errors, 0 warnings**.
- **C Host Unit Test (`gcc -DASOFT_SHIM_HOST_TEST -Wall -Wextra -Werror`):** `kgsl_shim_test` — **all assertions passed**.
- **Android JVM Unit Tests (`./gradlew testDebugUnitTest`):** **50 suites, 416 tests (415 passed, 0 failed, 0 errors, 1 skipped** via JUnit assumption in `CounterFreqTest.aRealTreeLikeTheScript` on non-ARM64 devbox) — includes new `PauseStateTest`, `GeometrySafetyTest`, `GameEnvGuardTest`, `CrashPersistenceTest`, and `WadbPairInputTest`.
- **Android Lint (`./gradlew lintReportDebug`):** **0 new errors / `NewApi` warnings** in any modified or added file (only pre-existing upstream BOM error at `MiniJson.java:93`).
- **Signed APK Verification:** `ASoftware-iOS-Android-0.1.7-ux-stability-debug.apk` (`114,170,310` bytes, v2 signature verified by `apksigner`).

