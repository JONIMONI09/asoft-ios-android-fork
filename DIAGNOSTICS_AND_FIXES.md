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
- **KGSL crash trace:** Synced `kgsl-trace.txt` before submits. Enable first to capture last GPU call before a hard reboot.
- **Crash-safe O_SYNC logging:** Immediate flush of serial/stderr/app logs during compat runs only.
- **Multi-VMA host-import bounce:** Byte-merged 3-way shadow memory for multi-VMA host imports.
- **KGSL cache sync:** Syncs imported buffers before submits (disable first if boot is slow).
- **Compat feature guard:** Strips sparse binding and `host_image_copy` from guest Vulkan features.
- **Submit throttle:** Serializes queue submits (disable if boot stalls).
- **Disable lazy IOSurface reparking:** Uses list mode (`REIMS_VGPU_LAZY_MAPPINGS=lists`).
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
- **Persistent CI Keystore:** `.github/workflows/apk-release.yml` on `work-ci-branch` explicitly signs every APK with cached `~/.android/debug.keystore` via `apksigner` so all builds from `#15` onward install as in-place updates without data loss.

---

## 7. Verified In-App GitHub Release APK Updater (`ApkUpdater.java`, `ShareProvider.java`, `MainActivity.java`, `ApkUpdaterTest.java`)
- **Date Added:** 2026-11-24
- **Features:**
  - Lets users pick between **Upstream (`alpersamur3`)** and **Fork CI (`JONIMONI09`)** update channels with an explicit warning that switching channels uses a different signing key (requiring backup + uninstall first).
  - **Download & Install** streams the latest `.apk` asset into `cache/share/update.apk` while computing SHA-256, checks valid APK/ZIP structure (`AndroidManifest.xml` + `classes.dex`), verifies `packageName == com.asoft.ios`, compares the APK's signing certificate SHA-256 against the installed app, checks GitHub's SHA-256 digest when provided, displays the `[OK]` verification summary, and launches Android's native update dialog directly via `ShareProvider` + `REQUEST_INSTALL_PACKAGES`.
  - Added `android:resizeableActivity="true"` across all activities (`MainActivity`, `SetupActivity`, `LogsActivity`, `ScreenActivity`) and `android.supports_size_changes="true"`.


