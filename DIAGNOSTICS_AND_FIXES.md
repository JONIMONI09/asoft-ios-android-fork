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
