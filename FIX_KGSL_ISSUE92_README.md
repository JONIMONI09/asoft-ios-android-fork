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

3. **`app/src/main/cpp/kgsl_shim.h`** (`L1-L83`)
   - Declares shared structures (`asoft_vma_stats`, `asoft_bounce_region`, `asoft_shim_config`) and host-testable core helpers (`asoft_load_config_from_env`, `asoft_parse_proc_maps`, `asoft_should_bounce_host_ptr`, `asoft_bounce_sync_to_shadow`, `asoft_bounce_sync_to_host`, `asoft_is_guarded_extension`, `asoft_trace_write`).

4. **`app/src/main/cpp/kgsl_shim.c`** (`L1-L1956`)
   - **Pure Shared Helpers (`L1-L228`):**
     - `asoft_parse_proc_maps` (`L52-L100`): Inspects `/proc/self/maps` across `[start, start + size)` to count covering VMAs and verify read/write permissions.
     - `asoft_should_bounce_host_ptr` (`L102-L116`): Determines whether a host pointer import should be bounced (multi-VMA under `ASOFT_SHIM_MULTI_VMA_BOUNCE`, or all readable imports under `ASOFT_SHIM_ALL_HOST_BOUNCE`).
     - **3-Way 4 KiB Page-Diff Bounce (`L118-L168`):**
       - `asoft_bounce_sync_to_shadow` (`L118-L132`): Copies CPU-modified 4 KiB pages (`host != prev`) to coherent shadow `VkDeviceMemory` and updates `prev` before GPU submits.
       - `asoft_bounce_sync_to_host` (`L134-L168`): Copies GPU-modified pages (`shadow != prev`) back to writable host ranges after fence/queue/device waits, performing a byte-level 3-way merge when both CPU and GPU touched different offsets within the same 4 KiB page.
     - `asoft_is_guarded_extension` (`L170-L178`): Flags `VK_EXT_host_image_copy`, `VK_EXT_device_generated_commands`, and `VK_NV_device_generated_commands` for masking.
     - `asoft_trace_write` (`L190-L228`): Appends lines with `O_SYNC` and rotates `kgsl-trace.txt` -> `kgsl-trace.txt.prev` at 2 MiB (`ASOFT_TRACE_MAX_BYTES`).
   - **`LD_PRELOAD` Interposers (`L581-L775`):**
     - `open`, `open64`, `openat`, `openat64`, `close`: Tracks `/dev/kgsl-3d0` file descriptors and adds `O_SYNC` when opening `serial.txt` under `ASOFT_SYNC_SERIAL=1`.
     - `ioctl` (`L709-L775`): Logs `IOCTL_KGSL_GPUOBJ_IMPORT` (`pre`/`post` with `O_SYNC`), tracks imported userptr BO IDs/sizes (`kgsl_tracked_bo`), executes `IOCTL_KGSL_GPUMEM_SYNC_CACHE` (with `IOCTL_KGSL_GPUOBJ_SYNC` fallback) before `IOCTL_KGSL_GPU_COMMAND` when `ASOFT_SHIM_CACHE_SYNC=1`, and traces command submissions/errors.
   - **Vulkan ICD Wrapper (`L855-L1953`):**
     - Exports `vk_icdNegotiateLoaderICDInterfaceVersion` (`L1905`), `vk_icdGetPhysicalDeviceProcAddr` (`L1914`), and `vk_icdGetInstanceProcAddr` (`L1928`), lazily loading real Turnip via `ASOFT_REAL_TURNIP_PATH`.
     - `shim_vkGetPhysicalDeviceFeatures` / `shim_vkGetPhysicalDeviceFeatures2` (`L1145-L1171`) & `shim_vkEnumerateDeviceExtensionProperties` (`L1173-L1223`): Masks `sparseBinding`, `sparseResidency*`, `hostImageCopy`, and guarded device extensions when `ASOFT_SHIM_FEATURE_GUARD=1`.
     - `shim_vkAllocateMemory` (`L1487-L1557`), `perform_bounce_alloc` (`L1403-L1485`), `shim_vkFreeMemory` (`L1559-L1591`), `shim_vkMapMemory` / `shim_vkUnmapMemory` (`L1593-L1635`): Transparently bounces multi-VMA (or opt-in all readable) host imports onto normal `HOST_VISIBLE | HOST_COHERENT` `VkDeviceMemory` with `prev` snapshot tracking and automatic fallback bounce if native Turnip rejects an import.
     - `shim_vkQueueSubmit` (`L1637-L1683`), `shim_vkQueueSubmit2` / `KHR` (`L1685-L1741`), `shim_vkQueueBindSparse` (`L1743-L1759`), and completion waits (`L1761-L1857`): Synchronizes bounce buffers & KGSL caches around submissions/completions, serializes queue submits per device under `ASOFT_SHIM_SUBMIT_THROTTLE=1`, and logs `O_SYNC` traces prior to each submission.

### Android App / Java Layer (`app/src/main/java/com/asoft/ios/`)

5. **`app/src/main/java/com/asoft/ios/CompatConfig.java`** (`L1-L262`)
   - Manages `SharedPreferences` (`asoft_compat26`), Safe Defaults vs. Opt-in toggles vs. Vanilla Fallback, environment variable generation (`envAdditions`, respecting `ios26.txt` `env.*` overrides), Settings summary (`summary()`), and launch logging (`describe()`).

6. **`app/src/main/java/com/asoft/ios/AppLog.java`** (`L36-L84`)
   - Added `writeLineSync()` (`L36-L49`) calling `FileOutputStream.getFD().sync()` when `CompatConfig.syncLogging(c)` is enabled (`L51-L66`).
   - Added `CompatConfig.TRACE_FILE` (`kgsl-trace.txt`) to `keepRunLogs()` (`L69-L84`) so `last-kgsl-trace.txt` is preserved after hard reboots.

7. **`app/src/main/java/com/asoft/ios/RunLogs.java`** (`L64-L73`)
   - Registered `kgsl-trace` (`kgsl-trace.txt`) and `last-kgsl-trace` (`last-kgsl-trace.txt`) in `RunLogs.all()` for viewing/sharing in `LogsActivity`.

8. **`app/src/main/java/com/asoft/ios/Guest.java`** (`L269-L280`, `L2444-L2467`)
   - Added `writeShimmedVulkanIcd(Context)` (`L269-L280`) writing `turnip_icd_shim.json` pointing to `nativeLibraryDir/libkgslshim.so` while leaving `turnip_icd.json` intact.
   - Wired `CompatConfig` in `start26()` (`L2444-L2467`) strictly gated on `ios26Compat = !watch && compat26`, applying `VK_DRIVER_FILES`, `LD_PRELOAD`, `ASOFT_*`, and `REIMS_VGPU_LAZY_MAPPINGS=lists` unless `vanillaFallback` or `ios26.txt` `env.*` overrides it.

9. **`app/src/main/java/com/asoft/ios/MainActivity.java`** (`L72`, `L427`, `L1439`, `L1448-L1502`)
   - Added the `"iOS 26 GPU compatibility"` settings row (`L427`), status summary update (`L1439`), and multi-choice configuration dialog `showCompat26Dialog()` (`L1448-L1502`) with live `"Safe defaults"` reset and `"Vanilla 0.1.7 fallback"`.

---

## 3. Configuration Switches & Defaults

Accessible via **Main Screen -> Settings -> iOS 26 GPU compatibility**:

| Toggle | Default | Effect (`ios26 && compat26` only) |
| :--- | :--- | :--- |
| **Vanilla 0.1.7 fallback** | **OFF** | Disables all shims, `O_SYNC` logging, `LD_PRELOAD`, and env overrides (stock 0.1.7 behavior). |
| **Crash-safe `O_SYNC` logging** | **ON** | Sets `ASOFT_SYNC_LOG=1`, `ASOFT_SYNC_SERIAL=1`, `ASOFT_KGSL_TRACE_FILE=.../kgsl-trace.txt`, and `fd.sync()` in `AppLog`. |
| **Multi-VMA host-import bounce** | **ON** | Sets `ASOFT_SHIM_MULTI_VMA_BOUNCE=1`; bounces multi-VMA `VkImportMemoryHostPointerInfoEXT` allocations to coherent shadow memory with 3-way page-diff sync. |
| **KGSL cache sync before submits** | **ON** | Sets `ASOFT_SHIM_CACHE_SYNC=1`; issues `IOCTL_KGSL_GPUMEM_SYNC_CACHE` (`CLEAN` pre-submit, `INV` post-wait) on active userptr BOs. |
| **Compat feature guard** | **ON** | Sets `ASOFT_SHIM_FEATURE_GUARD=1`; masks `sparseBinding` / `sparseResidency*`, `hostImageCopy`, and `device_generated_commands`. |
| **Submit throttle** | **ON** | Sets `ASOFT_SHIM_SUBMIT_THROTTLE=1`; serializes `vkQueueSubmit*` per `VkDevice` and drains unfenced submits. |
| **Disable lazy IOSurface reparking** | **ON** | Sets `REIMS_VGPU_LAZY_MAPPINGS=lists` (prevents lazy `PROT_NONE` mapping churn). |
| **Opt-in: Bounce ALL host imports** | **OFF** | Sets `ASOFT_SHIM_ALL_HOST_BOUNCE=1`; bounces even 1-VMA readable host imports (useful if single-VMA userptr faults persist). |
| **Opt-in: Turnip KGSL import thread** | **OFF** | Sets `TU_KGSL_IMPORT_THREAD=always`. |
| **Opt-in: Turnip safe debug flags** | **OFF** | Sets `TU_DEBUG=noubwc,flushall,sysmem`. |
| **Opt-in: Verbose engine logging** | **OFF** | Sets `REIMS_VGPU_LOG=debug` and `METAL2VULKAN_LOG_MEMORY=1`. |

---

## 4. Verification & Build Commands

Prebuilt `.so` libraries (`jniLibs`, `jniLibs26`) and helper `assets/` from the 0.1.7 release APK must be present in the `.gitignore`d app directories (via `pull-libs.sh` / `pull-libs26.sh` / `pull-setup.sh` or APK extraction).

1. **Host C Unit Tests (`app/src/test/cpp/kgsl_shim_test.c`):**
   ```bash
   gcc -Wall -Wextra -Werror -DASOFT_SHIM_HOST_TEST -Iapp/src/main/cpp \
     app/src/main/cpp/kgsl_shim.c app/src/test/cpp/kgsl_shim_test.c \
     -o /tmp/kgsl_shim_test && /tmp/kgsl_shim_test
   ```
   Verifies `/proc/self/maps` VMA parsing, read/write boundary safety, 3-way 4 KiB page-diff + partial-page collision preservation, extension guard filtering, and `O_SYNC` trace rotation.

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
