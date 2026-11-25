# Diagnostics, Root Causes & Fixes Log

## 1. 95% / 60-Minute Hang During `First boot of iOS` (`ios26:firstboot`)
- **Date Fixed:** 2026-11-24
- **Observed Symptom:** `iOS wrote no persona files in 60 min ()`, `serial.txt` remained **0 bytes**, `kgsl-trace.txt` remained **0 bytes**, and QEMU stderr stopped permanently right after `GPU memory map: 'vresearch.fb'`. Android did not reboot or crash.
- **Root Cause (`app/src/main/cpp/kgsl_shim.c`):**
  1. `ensure_turnip_loaded()` acquired the non-recursive mutex `g_state_lock` and called `dlopen(path, RTLD_NOW | RTLD_LOCAL)` while holding it. Inside `dlopen()`, the Android dynamic linker called `close(fd)`, which entered our `LD_PRELOAD` `close()` interposer → `untrack_kgsl_fd(fd)` → `pthread_mutex_lock(&g_state_lock)` → **instant self-deadlock**.
  2. `ensure_turnip_loaded()` also called `trace_logf("icd_init ...")` → `asoft_trace_write()` → `open()` / `close()`, which re-entered the same `LD_PRELOAD` interposers while `g_state_lock` was held.
- **Fix:**
  - Replaced `g_state_lock` in `ensure_turnip_loaded()` with a dedicated `g_turnip_load_lock` and moved `trace_logf("icd_init ...")` outside all locks.
  - Updated `open_trace_fd()` / `close_trace_fd()` on Android to call `shim_raw_open()` / `shim_raw_close()` (`g_real_open` / `g_real_close`), completely bypassing `LD_PRELOAD` interposers during trace writes.
  - Locked permanently by `BootDiagnosticsTest.kgslShimNeverHoldsStateLockAcrossDlopenOrTraceWrite()`.

---

## 2. Passive Boot & Stall Diagnostics (`BootDiagnostics.java`, `RunLogs.java`, `PhoneSetup26.java`)
- **Date Added:** 2026-11-24
- **Purpose:** Whenever `serial.txt` remains 0 bytes for `>= 4 min` during `ios26:firstboot` (or on 60-min timeout), writes a passive snapshot to `files/run/boot-diag.txt` (visible under **Logs → The system's logs → Boot & stall diagnostics**):
  - App version (`BuildConfig.VERSION_NAME (VERSION_CODE)`), device/SDK, and active `CompatConfig` flags.
  - Existence, size, and `rwx` permissions of `serial.txt`, `qemu-stderr.txt`, `kgsl-trace.txt`, `app.txt`, `turnip_icd_shim.json`, `libkgslshim.so`, and `/dev/kgsl-3d0`.
  - QEMU `/proc/<pid>/status` and thread `wchan` wait channels (immediately reveals futex deadlocks).
  - Optional read-only `WirelessAdb.shell` logcat query (only on stall when already paired; `WadbPairService.java` untouched).

---

## 3. Advanced Settings Guide (`?` Help in `MainActivity.showCompat26Dialog()`)
- **Auto for GPU:** Inspects `GpuCheck.last()` and selects either the fast setup-safe Snapdragon preset (`Multi-VMA bounce`, batched `KGSL cache sync`, `Compat feature guard`, `Submit throttle`; with `KGSL crash trace`, `O_SYNC logging`, and `Disable lazy IOSurface reparking` OFF) or stock defaults.
- **KGSL crash trace:** Writes GPU submits and KGSL calls to `kgsl-trace.txt` (sampled after 200 commands unless verbose logging is ON; uses `O_SYNC` only when `Crash-safe O_SYNC logging` is also ON). Keep OFF for daily speed; enable when diagnosing a hard reboot.
- **Crash-safe O_SYNC logging:** Immediate flush of serial/stderr/app/trace logs during compat runs only. Keep OFF for daily speed.
- **Multi-VMA host-import bounce:** `HOST_CACHED` 3-way byte-merged shadow memory for multi-VMA host imports (zero-lock fast path when 0 buffers are bounced).
- **KGSL cache sync:** Batched `IOCTL_KGSL_GPUOBJ_SYNC` on host-imported BOs before submit and after completion (disable if you want zero `LD_PRELOAD`).
- **Compat feature guard:** Strips sparse binding and `host_image_copy` from guest Vulkan features at init time (zero runtime cost).
- **Submit throttle:** Serializes concurrent Vulkan submits without stalling fence- or semaphore-synchronized frames.
- **Disable lazy IOSurface reparking:** Uses list mode (`REIMS_VGPU_LAZY_MAPPINGS=lists`). **WARNING:** Leave OFF during iOS 26 setup / `DataMigration` and normal use — turning it ON can exhaust Android's 65,530 `vm.max_map_count` limit.
- **Opt-in toggles:** Keep OFF unless diagnosing a specific issue.

---

## 4. Direct Shizuku Integration (`ShizukuBridge.java`, `MemoryCleaner.java`, `MainActivity.java`)
- **Date Added:** 2026-11-24 (`5da084d`)
- **Problem:** Some OEM ROMs (such as REDMAGIC OS 11 / Android 16) collapse or block inline `RemoteInput` notification actions and disallow Split-Screen on Android's Settings pairing dialog.
- **Solution:** Integrated official **Shizuku API 13.1.5** (`dev.rikka.shizuku:api:13.1.5` + `provider:13.1.5`). When Shizuku is running, tapping **Wireless debugging → Use Shizuku** opens Shizuku's native permission dialog and lets `MemoryCleaner` run `am kill-all` / `dumpsys meminfo` directly over Shizuku Binder (UID 2000) without needing notification pairing or Split-Screen.

---

## 5. `WadbPairService` Notification Verification vs. Upstream (`66d92fd`)
- **Analysis:** Compared `WirelessAdb.java`, `WadbPairService.java`, and `AndroidManifest.xml` line-by-line against upstream `e85e49b`:
  - `WirelessAdb.java` and `<service android:name=".WadbPairService">` are 100% identical to upstream.
  - `WadbPairService.show()` previously skipped `startForeground()` when `lastShownText`/`lastShownAsk` matched, preventing notification refreshes. Removed in `66d92fd` so `show()` and `notification()` match upstream 1:1. Enforced by `WadbPairInputTest.remoteInputUsesUpstreamPendingIntentGetServiceAndAskGating()`.

---

## 6. Floating Window / Freeform & Persistent CI Signing (`e37ecb4`, `8e87f1b`)
- **Floating Window:** Added `android.software.freeform_window_management`, `android.allow_multiple_resumed_activities`, and `<layout>` on `MainActivity` in `AndroidManifest.xml`.
- **Persistent CI Keystore:** CI on `work-ci-branch` explicitly signs every APK with cached `~/.android/debug.keystore` via `apksigner` so all builds from `#15` onward install as in-place updates without data loss.

---

## 7. Verified In-App GitHub Release APK Updater (`ApkUpdater.java`, `ShareProvider.java`, `MainActivity.java`, `ApkUpdaterTest.java`)
- **Date Added:** 2026-11-24
- **Features:**
  - Lets users pick between **Upstream (`alpersamur3`)** and **Fork CI (`JONIMONI09`)** update channels with an explicit warning that switching channels uses a different signing key (requiring backup + uninstall first).
  - **Download & Install** streams the latest `.apk` asset into `cache/share/update.apk` while computing SHA-256, checks valid APK/ZIP structure (`AndroidManifest.xml` + `classes.dex`), verifies `packageName == com.asoft.ios`, compares the APK's signing certificate SHA-256 against the installed app, checks GitHub's SHA-256 digest when provided, displays the `[OK]` verification summary, and launches Android's native update dialog directly via `ShareProvider` + `REQUEST_INSTALL_PACKAGES`.
  - Added `android:resizeableActivity="true"` across all activities (`MainActivity`, `SetupActivity`, `LogsActivity`, `ScreenActivity`) and `android.supports_size_changes="true"`.

---

## 8. Non-GPU Reliability Fixes: Shizuku-First RAM Cleaner, Reboot-Safe Settings, Startup Update Check & Per-Toggle Guidance (`34624cf`)
- **Date Added:** 2026-11-24
- **RAM Cleaning & Shizuku Reliability (`MemoryCleaner.java`, `ShizukuBridge.java`, `MainActivity.java`):**
  - Added `MemoryCleaner.runPrivilegedShell()` preferring live `ShizukuBridge.granted()` first and automatically falling back to `WirelessAdb.shell(...)` on `IOException`.
  - Checked `!Thread.currentThread().isInterrupted()` in `MemoryCleaner.start()` and added a daemon `shizuku-stderr` drainer + reliable timeout handling in `ShizukuBridge.shell()`.
- **Reboot-Safe Settings Persistence:** Switched `SharedPreferences` writes from `.apply()` to synchronous `.commit()` across `CompatConfig`, `MemoryCleaner`, `SetupService`, and `UpdateCheck`.
- **Startup Update Check & Per-Toggle Guidance:** Added `UpdateCheck.checkOnStart()`, Fork CI prerelease & `-advanced-<N>` build comparison, per-toggle plain-English explanations, and `UpdateCheckTest.java`.

---

## 9. Updater Hardening, Shizuku Banner & Sleep-Safe `DataMigration` (`09a16ee` – `c200dfa`)
- **Date Added:** 2026-11-24 – 2026-11-25
- **Detached Dialog Fix (`09a16ee`):** Added `MainActivity.safeDismiss()` so background updater callbacks never crash with `View not attached to window manager`.
- **Updater Hardening (`05500bd`, `c200dfa`):**
  - Blocked updates while Setup or iOS is running (`SetupService.busy() || Guest.running()`).
  - Added live installed-vs-latest version comparison (`"App is up to date"` + **Force reinstall** with `ApkUpdater.clearCache()`), download size vs. free storage confirmation, `canRequestPackageInstalls()` check with `onResume()` auto-resume, transient DNS/network retry, and optional `-PasoftVersionName=...` in `app/build.gradle`.
- **65,530 Mapping Safeguard & Shizuku Home Banner (`6fbf0b5`):**
  - Removed `lazySurfacesOff = true` from `recommendedForKnownKgslBug()`, added GPU-aware `recommendedForGpu()` + **Auto for GPU** button, and added clickable `adbWarn` banner on `MainActivity` when Shizuku / Wireless debugging is disconnected.
- **Sleep-Safe `DataMigration` (`37612db`):**
  - Subtracted Android Deep Sleep duration (`elapsedRealtime - uptimeMillis`) from `PhoneSetup26.iosWallpaper()` elapsed minutes and `MigrationWatch`, preventing false ceiling aborts after overnight sleep.

---

## 10. iOS 26 Performance Optimizations in `kgsl_shim.c` & `CompatConfig.java` (`2026-11-25`)
- **Root Causes Uncovered (`submitting 25–32 ms` in `reims` logs):**
  1. `submitThrottle` called synchronous `vkQueueWaitIdle()` on timeline-semaphore submits (`fence == VK_NULL_HANDLE`), stalling CPU/GPU pipelining every frame.
  2. `kgslCacheSync` fired twice per submit (`vkQueueSubmit*` + `IOCTL_KGSL_GPU_COMMAND`), issued unbatched per-BO ioctls across all tracked BOs, and repeatedly flushed on `vkGetFenceStatus` polls.
  3. `/proc/self/maps` was parsed to EOF (up to 60,000 VMAs) on every host-pointer import even when bouncing was disabled.
  4. `find_host_coherent_mem_type()` accepted uncached Write-Combining (WC) memory (~200 MB/s reads) over `HOST_CACHED` (~20 GB/s), and `sync_device_bounces()` locked global `g_state_lock` across 4,096 slots even with 0 bounced buffers.
  5. `LD_PRELOAD` locked global `g_state_lock` on every `close()`/`ioctl()` in QEMU and was injected even when only pure Vulkan ICD hooks were enabled.
- **Fixes Applied:**
  1. Semaphore-aware `asoft_submit_needs_throttle_drain()` skips `vkQueueWaitIdle()` when any semaphore is signaled.
  2. Batched `IOCTL_KGSL_GPUOBJ_SYNC` per KGSL fd, thread-local `t_in_vk_submit` prevents double sync, atomic `g_kgsl_bo_count` fast-path, and deduped `vkGetFenceStatus` syncs.
  3. Early `break` when `lo >= end` in `asoft_parse_proc_maps()` and gated `/proc/self/maps` open on active bounce/trace.
  4. `asoft_select_bounce_mem_type()` prefers `HOST_VISIBLE | HOST_COHERENT | HOST_CACHED`, dedicated `g_bounce_lock`, and atomic `g_bounce_active_count == 0` fast-path.
  5. Lock-free atomic FD bitset (`g_kgsl_fd_bits`) for fds 0–1023 and decoupled `CompatConfig.Config.needsLdPreload()` to `kgslTrace || syncLogging || kgslCacheSync`.



