# iOS 26 Compatibility Mode Hard-Freeze / Auto-Reboot Fix (Upstream Issue #92)

This document details all changes implemented in `JONIMONI09/asoft-ios-android-fork` (v0.1.7) to diagnose and mitigate the full-device hard freeze and kernel/KGSL watchdog reboot occurring during iOS 26 setup and boot (`AppleParavirtGPU` / `IOSurfaceRootParavirtMapperInterface` initialization) on Snapdragon 8 Gen 3 (`SM8650`, Adreno 750) devices running in Vulkan compatibility mode (`REIMS_VGPU_DEFERRED_VIEWS=0`).

---

## 1. Root Cause Summary & Architecture Overview

On Snapdragon `SM8650` / Android 16 (REDMAGIC Nova `NP03J`), Qualcomm KGSL (`/dev/kgsl-3d0`) refuses sparse bindings from host memory, forcing iOS 26 into `compat26` mode. Under `compat26`, `reims-vgpu` and `metal2vulkan` map iOS graphics resources and `IOSurface` slices via `VK_EXT_external_memory_host` directly over QEMU guest RAM / `memfd` mappings (`IOCTL_KGSL_GPUOBJ_IMPORT` with `KGSL_USER_MEM_TYPE_ADDR`) while lazily changing virtual memory protections (`mprotect` / `PROT_NONE` reparking).

When a host-pointer import spans multiple VMAs, lacks kernel cache coherency sync before GPU command submission, exercises high-risk Vulkan 1.4 / sparse / `host_image_copy` paths, or floods KGSL with unserialized queue submissions, the kernel KGSL driver / Adreno MMU can hang and trigger a watchdog reboot—wiping buffered RAM logs (`app.txt`, `qemu-stderr.txt`, `serial.txt`) before they hit flash.

Because the prebuilt QEMU/Rust engine (`libqemu26.so`) uses `dlopen("libvulkan.so")` + `dlsym(handle, "vkGetInstanceProcAddr")` (which bypasses plain `LD_PRELOAD` on Android Bionic), the fix pairs a **Vulkan ICD wrapper** (`turnip_icd_shim.json` -> `libkgslshim.so` -> real `libvulkan_freedreno.so`) with **`LD_PRELOAD` interposition** on `/dev/kgsl-3d0` and `serial.txt` inside the same shared library.

All runtime changes are strictly gated to `ios26 && compat26` (`!watch && compat26`). iOS 16, watchOS, and non-compat runs are untouched, and a **Vanilla 0.1.7 fallback** switch restores stock 0.1.7 behavior completely.

---

## 2. File-by-File Changelog

### Native Layer (`app/src/main/cpp/`)

1. **`app/src/main/cpp/CMakeLists.txt`** (`L18-L21`)
   - Added the `kgslshim` shared library target compiling `kgsl_shim.c` with `-O2 -Wall -Wextra` and linking against `log` and `dl`.

2. **`app/src/main/cpp/render.c`** (`L162-L173`)
   - Updated `spawn()` to scan `envp` for `ASOFT_SYNC_LOG=1` without altering the JNI signature.
   - Opens `qemu-stderr.txt` with `O_SYNC` when `ASOFT_SYNC_LOG=1` (falling back to non-`O_SYNC` `open()` if refused by the underlying filesystem).

3. **`app/src/main/cpp/kgsl_shim.h`**
   - Declares shared structures (`asoft_vma_stats`, `asoft_bounce_region`, `asoft_shim_config`) and host-testable core helpers (`asoft_load_config_from_env`, `asoft_parse_proc_maps`, `asoft_should_bounce_host_ptr`, `asoft_bounce_sync_to_shadow`, `asoft_bounce_sync_to_host`, `asoft_is_guarded_extension`, `asoft_select_bounce_mem_type`, `asoft_submit_needs_throttle_drain`, `asoft_trace_write`).

4. **`app/src/main/cpp/kgsl_shim.c`**
   - **Pure Shared Helpers:**
     - `asoft_parse_proc_maps`: Inspects `/proc/self/maps` across `[start, start + size)` and breaks immediately once `lo >= end` (avoiding full-file scans across 5,000–60,000 VMAs).
     - `asoft_should_bounce_host_ptr`: Determines whether a host pointer import should be bounced (multi-VMA under `ASOFT_SHIM_MULTI_VMA_BOUNCE`, or all readable imports under `ASOFT_SHIM_ALL_HOST_BOUNCE`).
     - **3-Way 4 KiB Page-Diff Bounce:**
       - `asoft_bounce_sync_to_shadow`: Copies CPU-modified pages (`host != prev`) to `HOST_CACHED` coherent shadow `VkDeviceMemory` with byte-level 3-way merge when CPU and GPU touched the same 4 KiB page.
       - `asoft_bounce_sync_to_host`: Copies GPU-modified pages (`shadow != prev`) back to writable host ranges with byte-level 3-way merge preserving concurrent CPU writes.
     - `asoft_select_bounce_mem_type`: Always prefers `HOST_VISIBLE | HOST_COHERENT | HOST_CACHED` over uncached write-combining (WC) memory to avoid ~50× slower shadow reads on ARM64.
     - `asoft_submit_needs_throttle_drain`: Returns `1` only for truly unsynchronized submits (`fence == VK_NULL_HANDLE` AND 0 signaled semaphores), preventing false `vkQueueWaitIdle()` stalls on Vulkan timeline-semaphore frames.
     - `asoft_is_guarded_extension`: Flags `VK_EXT_host_image_copy`, `VK_EXT_device_generated_commands`, and `VK_NV_device_generated_commands` for masking.
     - `asoft_trace_write`: Appends lines to `kgsl-trace.txt` (using `O_SYNC` only when `ASOFT_SYNC_LOG=1` is also enabled, via `shim_raw_open`/`shim_raw_close` bypassing `LD_PRELOAD`) and rotates to `kgsl-trace.txt.prev` at 2 MiB (`ASOFT_TRACE_MAX_BYTES`).
   - **`LD_PRELOAD` Interposers (injected only when `kgslTrace`, `syncLogging`, or `kgslCacheSync` is enabled):**
     - `open`, `open64`, `openat`, `openat64`, `close`: Lock-free atomic bitset (`g_kgsl_fd_bits` for fds 0–1023) tracks `/dev/kgsl-3d0` file descriptors with zero mutex contention on non-KGSL `close()`/`ioctl()` calls; adds `O_SYNC` on `serial.txt` under `ASOFT_SYNC_SERIAL=1`.
     - `ioctl`: Logs `IOCTL_KGSL_GPUOBJ_IMPORT`, tracks active userptr BOs with an atomic counter (`g_kgsl_bo_count`), skips duplicate cache sync when already inside `shim_vkQueueSubmit*` (`t_in_vk_submit`), and batches BO cache syncs into a single `IOCTL_KGSL_GPUOBJ_SYNC` syscall per KGSL fd (with cached `ENOTTY` fallback to `IOCTL_KGSL_GPUMEM_SYNC_CACHE`).
   - **Vulkan ICD Wrapper:**
     - Exports `vk_icdNegotiateLoaderICDInterfaceVersion`, `vk_icdGetPhysicalDeviceProcAddr`, and `vk_icdGetInstanceProcAddr`, lazily loading real Turnip under a dedicated `g_turnip_load_lock` (deadlock-free).
     - Masks `sparseBinding`, `sparseResidency*`, `hostImageCopy`, and guarded device extensions at init time (zero runtime overhead) when `ASOFT_SHIM_FEATURE_GUARD=1`.
     - Transparently bounces multi-VMA (or opt-in all readable) host imports onto `HOST_CACHED` shadow memory using a dedicated `g_bounce_lock` and zero-lock `g_bounce_active_count == 0` fast path, gating `/proc/self/maps` parsing on bounce/trace activation.
     - Serializes concurrent Vulkan submits per device under `ASOFT_SHIM_SUBMIT_THROTTLE=1` without stalling fence- or semaphore-synchronized submits, and deduplicates `vkGetFenceStatus` polling cache syncs.

### Android App / Java Layer (`app/src/main/java/com/asoft/ios/`)

5. **`app/src/main/java/com/asoft/ios/CompatConfig.java`**
   - Manages `SharedPreferences` (`asoft_compat26`) with synchronous `.commit()` persistence, 12 OFF-by-default switches, `recommendedForKnownKgslBug()` / `recommendedForGpu()` fast Snapdragon preset, `needsIcdWrapper()` vs. decoupled `needsLdPreload()`, `ios26.txt` `env.*` override priority, and `isSyncLogActive()` scoping.

6. **`app/src/main/java/com/asoft/ios/AppLog.java`**
   - Conditional `FileOutputStream.getFD().sync()` in `writeLineSync()` strictly scoped to active `ios26 && compat26` runs when `syncLogging` is ON; preserves `last-kgsl-trace.txt` across reboots.

7. **`app/src/main/java/com/asoft/ios/RunLogs.java`**
   - Exposes `kgsl-trace`, `last-kgsl-trace`, and `boot-diag` in `LogsActivity`.

8. **`app/src/main/java/com/asoft/ios/Guest.java`**
   - Writes `turnip_icd_shim.json` pointing to `nativeLibraryDir/libkgslshim.so` and wires `CompatConfig` strictly when `!watch && compat26`.

9. **`app/src/main/java/com/asoft/ios/MainActivity.java` & `Setup26Screen.java`**
   - **Settings → iOS 26 GPU compatibility:** 12 multi-choice toggles (all OFF by default), **`Auto for GPU`** button, **`?`** help dialog, per-toggle plain-English explanations (with *"If you are not sure what this setting does, leave it OFF."* + **Turn OFF** button), and **`Reset (all off)`**.
   - `Setup26Screen.maybePromptKnownKgslGpu()`: Prompts on Snapdragon compat-mode GPUs with **`Recommended (fast)`**, **`Trace only`**, or **`Keep defaults`**.

---

## 3. Configuration Switches & Defaults

Accessible via **Main Screen -> Settings -> iOS 26 GPU compatibility** (all 12 switches default to **OFF** to match stock 0.1.7; tapping **Auto for GPU** on a Snapdragon/KGSL compat GPU enables the 4 marked **★** below):

| Toggle | Default | Auto for GPU (Snapdragon) | Effect (`ios26 && compat26` only) |
| :--- | :--- | :--- | :--- |
| **Vanilla 0.1.7 fallback** | **OFF** | OFF | Disables all shims, `O_SYNC` logging, `LD_PRELOAD`, and env overrides (stock 0.1.7 behavior). |
| **KGSL crash trace** | **OFF** | OFF | Sets `ASOFT_KGSL_TRACE_FILE=.../kgsl-trace.txt`; logs GPU submits & KGSL calls (uses `O_SYNC` only if `Crash-safe O_SYNC logging` is also ON). |
| **Crash-safe `O_SYNC` logging** | **OFF** | OFF | Sets `ASOFT_SYNC_LOG=1`, `ASOFT_SYNC_SERIAL=1`, and `fd.sync()` in `AppLog` during active compat runs only. |
| **Multi-VMA host-import bounce** | **OFF** | **★ ON** | Sets `ASOFT_SHIM_MULTI_VMA_BOUNCE=1`; bounces multi-VMA host imports to `HOST_CACHED` shadow memory with 3-way byte-diff sync (zero-lock fast path when unused). |
| **KGSL cache sync** | **OFF** | **★ ON** | Sets `ASOFT_SHIM_CACHE_SYNC=1`; issues batched `IOCTL_KGSL_GPUOBJ_SYNC` (`CLEAN` pre-submit, `FROM_GPU` post-wait) on active userptr BOs. |
| **Compat feature guard** | **OFF** | **★ ON** | Sets `ASOFT_SHIM_FEATURE_GUARD=1`; masks `sparseBinding` / `sparseResidency*`, `hostImageCopy`, and `device_generated_commands` at init time (zero runtime cost). |
| **Submit throttle** | **OFF** | **★ ON** | Sets `ASOFT_SHIM_SUBMIT_THROTTLE=1`; serializes `vkQueueSubmit*` per `VkDevice` without stalling fence- or semaphore-synchronized submits. |
| **Disable lazy IOSurface reparking** | **OFF** | OFF | Sets `REIMS_VGPU_LAZY_MAPPINGS=lists`. **WARNING:** Keep OFF during iOS 26 setup / `DataMigration` and normal use — turning it ON can exhaust Android's 65,530 `vm.max_map_count` limit. |
| **Opt-in: Bounce ALL host imports** | **OFF** | OFF | Sets `ASOFT_SHIM_ALL_HOST_BOUNCE=1`; bounces even 1-VMA readable host imports. |
| **Opt-in: Turnip KGSL import thread** | **OFF** | OFF | Sets `TU_KGSL_IMPORT_THREAD=always`. |
| **Opt-in: Turnip safe debug flags** | **OFF** | OFF | Sets `TU_DEBUG=noubwc,flushall,sysmem`. |
| **Opt-in: Verbose engine logging** | **OFF** | OFF | Sets `REIMS_VGPU_LOG=debug` and `METAL2VULKAN_LOG_MEMORY=1`. |

---

## 4. Verification & Build Commands

Prebuilt `.so` libraries (`jniLibs`, `jniLibs26`) and helper `assets/` from the 0.1.7 release APK must be present in the `.gitignore`d app directories (via `pull-libs.sh` / `pull-libs26.sh` / `pull-setup.sh` or APK extraction).

1. **Host C Unit Tests (`app/src/test/cpp/kgsl_shim_test.c`):**
   ```bash
   gcc -Wall -Wextra -DASOFT_SHIM_HOST_TEST -Iapp/src/main/cpp \
     app/src/main/cpp/kgsl_shim.c app/src/test/cpp/kgsl_shim_test.c \
     -o /tmp/kgsl_shim_test && /tmp/kgsl_shim_test
   ```
   Verifies `/proc/self/maps` VMA parsing + sorted early-break, read/write boundary safety, 3-way 4 KiB page-diff + concurrent CPU/GPU merge in both directions, `HOST_CACHED` bounce memory preference, semaphore-aware submit throttle check, extension guard filtering, and trace rotation.

2. **Android JVM Unit Tests (`CompatConfigTest.java`, `AppLogSyncTest.java`, + upstream suite):**
   ```bash
   bash ./gradlew testDebugUnitTest -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
   ```

3. **Full APK Build (NDK `29.0.13846066`, CMake `3.22.1`):**
   ```bash
   bash ./gradlew assembleDebug -Dorg.gradle.java.home=/usr/lib/jvm/java-21-openjdk-amd64
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk` (includes compiled `libkgslshim.so`, `librender.so`, `libgpucheck.so`, `libunpack.so` alongside prebuilt `libqemu26.so`, Turnip, and assets).

4. **Post-Reboot Forensics on Device:**
   If a freeze occurs on hardware, immediately pull or open **Settings -> Logs**:
   - `Previous run: KGSL/Vulkan trace` (`last-kgsl-trace.txt` or `files/run/kgsl-trace.txt`)
   - `Previous run: QEMU stderr` (`last-qemu-stderr.txt`)
   - `Previous run: console` (`last-serial.txt`)
   - App log (`Android/data/com.asoft.ios/files/log/app.txt`)
