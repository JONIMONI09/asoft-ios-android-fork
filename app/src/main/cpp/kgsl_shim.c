#define _GNU_SOURCE
#include "kgsl_shim.h"

#include <errno.h>
#include <fcntl.h>
#include <inttypes.h>
#include <pthread.h>
#include <stdarg.h>
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <time.h>
#include <unistd.h>

/* -- Pure shared helpers (compiled on both Android and host unit tests) ---------------------------- */

static int env_flag(const char *name, int def_val)
{
    const char *v = getenv(name);
    if (!v || !*v) {
        return def_val;
    }
    if (strcmp(v, "1") == 0 || strcmp(v, "true") == 0 || strcmp(v, "on") == 0) {
        return 1;
    }
    if (strcmp(v, "0") == 0 || strcmp(v, "false") == 0 || strcmp(v, "off") == 0) {
        return 0;
    }
    return def_val;
}

void asoft_load_config_from_env(struct asoft_shim_config *cfg)
{
    if (!cfg) {
        return;
    }
    memset(cfg, 0, sizeof(*cfg));
    cfg->multi_vma_bounce = env_flag("ASOFT_SHIM_MULTI_VMA_BOUNCE", 0);
    cfg->all_host_bounce = env_flag("ASOFT_SHIM_ALL_HOST_BOUNCE", 0);
    cfg->cache_sync = env_flag("ASOFT_SHIM_CACHE_SYNC", 0);
    cfg->feature_guard = env_flag("ASOFT_SHIM_FEATURE_GUARD", 0);
    cfg->submit_throttle = env_flag("ASOFT_SHIM_SUBMIT_THROTTLE", 0);
    cfg->sync_serial = env_flag("ASOFT_SYNC_SERIAL", 0);
    cfg->sync_log = env_flag("ASOFT_SYNC_LOG", 0);
    const char *rlog = getenv("REIMS_VGPU_LOG");
    const char *mlog = getenv("METAL2VULKAN_LOG_MEMORY");
    cfg->verbose = (rlog && strcmp(rlog, "debug") == 0) || (mlog && strcmp(mlog, "1") == 0);
}

int asoft_parse_proc_maps(FILE *fp, uintptr_t start, size_t size, struct asoft_vma_stats *out)
{
    if (!fp || !out) {
        return -1;
    }
    memset(out, 0, sizeof(*out));
    if (size == 0) {
        return 0;
    }
    uintptr_t end = start + size;
    if (end < start) {
        return -1;
    }
    out->all_readable = 1;
    out->all_writable = 1;

    char line[1024];
    while (fgets(line, sizeof(line), fp) != NULL) {
        unsigned long long lo = 0, hi = 0;
        char perms[8] = {0};
        if (sscanf(line, "%llx-%llx %7s", &lo, &hi, perms) != 3) {
            continue;
        }
        if ((uintptr_t)lo >= end) {
            break; /* /proc/self/maps is sorted by ascending virtual address */
        }
        if ((uintptr_t)hi <= start) {
            continue;
        }
        uintptr_t ov_lo = (uintptr_t)lo > start ? (uintptr_t)lo : start;
        uintptr_t ov_hi = (uintptr_t)hi < end ? (uintptr_t)hi : end;
        if (ov_hi <= ov_lo) {
            continue;
        }
        out->vma_count++;
        out->total_mapped += (size_t)(ov_hi - ov_lo);
        if (perms[0] != 'r') {
            out->all_readable = 0;
        }
        if (perms[1] != 'w') {
            out->all_writable = 0;
        }
        if (perms[3] == 's') {
            out->any_shared = 1;
        }
    }
    if (out->vma_count == 0 || out->total_mapped < size) {
        out->all_readable = 0;
        out->all_writable = 0;
    }
    return 0;
}

int asoft_should_bounce_host_ptr(
        const struct asoft_vma_stats *st,
        size_t size,
        int multi_vma_bounce,
        int all_host_bounce)
{
    if (!st || size == 0 || !st->all_readable || st->total_mapped < size) {
        return 0;
    }
    if (all_host_bounce) {
        return 1;
    }
    if (multi_vma_bounce && st->vma_count > 1) {
        return 1;
    }
    return 0;
}

size_t asoft_bounce_sync_to_shadow(struct asoft_bounce_region *reg)
{
    if (!reg || !reg->host_ptr || !reg->shadow_ptr || !reg->prev_ptr || reg->size == 0) {
        return 0;
    }
    size_t synced = 0;
    for (size_t off = 0; off < reg->size; off += ASOFT_PAGE_SIZE) {
        size_t chunk = (reg->size - off >= ASOFT_PAGE_SIZE) ? (size_t)ASOFT_PAGE_SIZE : (reg->size - off);
        const uint8_t *h = reg->host_ptr + off;
        uint8_t *s = reg->shadow_ptr + off;
        uint8_t *p = reg->prev_ptr + off;
        if (memcmp(h, p, chunk) == 0) {
            continue;
        }
        if (memcmp(s, p, chunk) == 0) {
            /* Pure CPU modification on this page: fast whole-page copy */
            memcpy(s, h, chunk);
            memcpy(p, h, chunk);
            synced += chunk;
        } else {
            /* Concurrent CPU + GPU writes in same 4 KiB page: byte-level 3-way merge so GPU writes are preserved */
            for (size_t i = 0; i < chunk; i++) {
                if (h[i] != p[i]) {
                    s[i] = h[i];
                    p[i] = h[i];
                    synced++;
                }
            }
        }
    }
    return synced;
}

size_t asoft_bounce_sync_to_host(struct asoft_bounce_region *reg)
{
    if (!reg || !reg->host_ptr || !reg->shadow_ptr || !reg->prev_ptr || reg->size == 0 || !reg->host_writable) {
        return 0;
    }
    size_t written = 0;
    for (size_t off = 0; off < reg->size; off += ASOFT_PAGE_SIZE) {
        size_t chunk = (reg->size - off >= ASOFT_PAGE_SIZE) ? (size_t)ASOFT_PAGE_SIZE : (reg->size - off);
        uint8_t *h = reg->host_ptr + off;
        const uint8_t *s = reg->shadow_ptr + off;
        uint8_t *p = reg->prev_ptr + off;
        if (memcmp(s, p, chunk) == 0) {
            continue;
        }
        if (memcmp(h, p, chunk) == 0) {
            /* Pure GPU modification on this page */
            memcpy(h, s, chunk);
            memcpy(p, s, chunk);
            written += chunk;
        } else {
            /* Concurrent CPU + GPU writes in same 4 KiB page: byte-level 3-way merge */
            for (size_t i = 0; i < chunk; i++) {
                if (s[i] != p[i] && h[i] == p[i]) {
                    h[i] = s[i];
                    p[i] = s[i];
                    written++;
                }
            }
        }
    }
    return written;
}

int asoft_is_guarded_extension(const char *ext_name)
{
    if (!ext_name) {
        return 0;
    }
    return strcmp(ext_name, "VK_EXT_host_image_copy") == 0
            || strcmp(ext_name, "VK_EXT_device_generated_commands") == 0
            || strcmp(ext_name, "VK_NV_device_generated_commands") == 0;
}

int asoft_select_bounce_mem_type(
        const uint32_t *prop_flags,
        uint32_t count,
        uint32_t preferred_idx,
        uint32_t host_visible_bit,
        uint32_t host_coherent_bit,
        uint32_t host_cached_bit)
{
    if (!prop_flags || count == 0) {
        return (int)preferred_idx;
    }
    uint32_t need_cached = host_visible_bit | host_coherent_bit | host_cached_bit;
    uint32_t need_coherent = host_visible_bit | host_coherent_bit;

    if (preferred_idx < count && (prop_flags[preferred_idx] & need_cached) == need_cached) {
        return (int)preferred_idx;
    }
    for (uint32_t i = 0; i < count; i++) {
        if ((prop_flags[i] & need_cached) == need_cached) {
            return (int)i;
        }
    }
    if (preferred_idx < count && (prop_flags[preferred_idx] & need_coherent) == need_coherent) {
        return (int)preferred_idx;
    }
    for (uint32_t i = 0; i < count; i++) {
        if ((prop_flags[i] & need_coherent) == need_coherent) {
            return (int)i;
        }
    }
    return (int)preferred_idx;
}

int asoft_submit_needs_throttle_drain(int has_fence, uint32_t total_signal_semaphores)
{
    return (!has_fence && total_signal_semaphores == 0) ? 1 : 0;
}

#ifndef ASOFT_SHIM_HOST_TEST
static int shim_raw_open(const char *path, int flags, mode_t mode);
static int shim_raw_close(int fd);
#endif

static int open_trace_fd(const char *path, int use_osync)
{
    int flags = O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC | (use_osync ? O_SYNC : 0);
#ifndef ASOFT_SHIM_HOST_TEST
    int fd = shim_raw_open(path, flags, 0600);
    if (fd < 0 && use_osync) {
        fd = shim_raw_open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    }
#else
    int fd = open(path, flags, 0600);
    if (fd < 0 && use_osync) {
        fd = open(path, O_WRONLY | O_CREAT | O_APPEND | O_CLOEXEC, 0600);
    }
#endif
    return fd;
}

static void close_trace_fd(int fd)
{
#ifndef ASOFT_SHIM_HOST_TEST
    (void)shim_raw_close(fd);
#else
    (void)close(fd);
#endif
}

int asoft_trace_write(const char *path, const char *line, size_t max_bytes, int use_osync)
{
    if (!path || !*path || !line) {
        return -1;
    }
    struct stat st;
    if (max_bytes > 0 && stat(path, &st) == 0 && (size_t)st.st_size >= max_bytes) {
        size_t plen = strlen(path);
        char *prev_path = malloc(plen + 6);
        if (prev_path) {
            snprintf(prev_path, plen + 6, "%s.prev", path);
            rename(path, prev_path);
            free(prev_path);
        }
    }
    int fd = open_trace_fd(path, use_osync);
    if (fd < 0) {
        return -1;
    }
    size_t len = strlen(line);
    size_t off = 0;
    while (off < len) {
        ssize_t w = write(fd, line + off, len - off);
        if (w < 0) {
            if (errno == EINTR) {
                continue;
            }
            close_trace_fd(fd);
            return -1;
        }
        off += (size_t)w;
    }
    if (len == 0 || line[len - 1] != '\n') {
        const char nl = '\n';
        ssize_t nw = write(fd, &nl, 1);
        (void)nw;
    }
    close_trace_fd(fd);
    return 0;
}

#ifndef ASOFT_SHIM_HOST_TEST

/* -- Android Runtime Implementation (LD_PRELOAD + Vulkan ICD Wrapper) ------------------------------ */

#include <android/log.h>
#include <dlfcn.h>
#include <sys/ioctl.h>
#include <vulkan/vulkan.h>
#include <vulkan/vk_icd.h>

#define SHIM_TAG "asoft-kgsl-shim"
#define SHIM_LOGI(...) __android_log_print(ANDROID_LOG_INFO, SHIM_TAG, __VA_ARGS__)
#define SHIM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, SHIM_TAG, __VA_ARGS__)

/* KGSL UAPI subset from msm_kgsl.h / freedreno */
#define KGSL_IOC_TYPE 0x09

struct asoft_kgsl_gpuobj_free {
    uint64_t flags;
    uint64_t priv;
    unsigned int id;
    unsigned int type;
    unsigned int len;
};

struct asoft_kgsl_gpuobj_info {
    uint64_t gpuaddr;
    uint64_t flags;
    uint64_t size;
    uint64_t va_len;
    uint64_t va_addr;
    unsigned int id;
};

struct asoft_kgsl_gpuobj_import {
    uint64_t priv;
    uint64_t priv_len;
    uint64_t flags;
    unsigned int type;
    unsigned int id;
};

struct asoft_kgsl_gpuobj_import_useraddr {
    uint64_t virtaddr;
};

struct asoft_kgsl_gpuobj_sync_obj {
    uint64_t offset;
    uint64_t length;
    unsigned int id;
    unsigned int op;
};

struct asoft_kgsl_gpuobj_sync {
    uint64_t objs;
    unsigned int obj_len;
    unsigned int count;
};

struct asoft_kgsl_gpumem_free_id {
    unsigned int id;
    unsigned int __pad;
};

struct asoft_kgsl_gpumem_sync_cache {
    unsigned long gpuaddr;
    unsigned int id;
    unsigned int op;
    size_t offset;
    size_t length;
};

struct asoft_kgsl_gpu_command {
    uint64_t flags;
    uint64_t cmdlist;
    unsigned int cmdsize;
    unsigned int numcmds;
    uint64_t objlist;
    unsigned int objsize;
    unsigned int numobjs;
    uint64_t synclist;
    unsigned int syncsize;
    unsigned int numsyncs;
    unsigned int context_id;
    unsigned int timestamp;
};

#define KGSL_USER_MEM_TYPE_ADDR    0x2U
#define KGSL_GPUMEM_CACHE_CLEAN    (1U << 0)
#define KGSL_GPUMEM_CACHE_TO_GPU   KGSL_GPUMEM_CACHE_CLEAN
#define KGSL_GPUMEM_CACHE_INV      (1U << 1)
#define KGSL_GPUMEM_CACHE_FROM_GPU KGSL_GPUMEM_CACHE_INV
#define KGSL_GPUMEM_CACHE_FLUSH    (KGSL_GPUMEM_CACHE_CLEAN | KGSL_GPUMEM_CACHE_INV)

#define IOCTL_KGSL_GPUMEM_FREE_ID    _IOWR(KGSL_IOC_TYPE, 0x35, struct asoft_kgsl_gpumem_free_id)
#define IOCTL_KGSL_GPUMEM_SYNC_CACHE _IOW(KGSL_IOC_TYPE,  0x37, struct asoft_kgsl_gpumem_sync_cache)
#define IOCTL_KGSL_GPUOBJ_FREE       _IOW(KGSL_IOC_TYPE,  0x46, struct asoft_kgsl_gpuobj_free)
#define IOCTL_KGSL_GPUOBJ_INFO       _IOWR(KGSL_IOC_TYPE, 0x47, struct asoft_kgsl_gpuobj_info)
#define IOCTL_KGSL_GPUOBJ_IMPORT     _IOWR(KGSL_IOC_TYPE, 0x48, struct asoft_kgsl_gpuobj_import)
#define IOCTL_KGSL_GPUOBJ_SYNC       _IOW(KGSL_IOC_TYPE,  0x49, struct asoft_kgsl_gpuobj_sync)
#define IOCTL_KGSL_GPU_COMMAND       _IOWR(KGSL_IOC_TYPE, 0x4A, struct asoft_kgsl_gpu_command)

/* -- Globals & Locking ---------------------------------------------------------------------------- */

static pthread_once_t g_init_once = PTHREAD_ONCE_INIT;
static pthread_mutex_t g_state_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_trace_lock = PTHREAD_MUTEX_INITIALIZER;
static pthread_mutex_t g_turnip_load_lock = PTHREAD_MUTEX_INITIALIZER;

static struct asoft_shim_config g_cfg;
static char g_trace_path[512];
static uint64_t g_seq = 0;

typedef int (*fn_open_t)(const char *, int, ...);
typedef int (*fn_openat_t)(int, const char *, int, ...);
typedef int (*fn_close_t)(int);
typedef int (*fn_ioctl_t)(int, int, ...);

static fn_open_t g_real_open = NULL;
static fn_open_t g_real_open64 = NULL;
static fn_openat_t g_real_openat = NULL;
static fn_openat_t g_real_openat64 = NULL;
static fn_close_t g_real_close = NULL;
static fn_ioctl_t g_real_ioctl = NULL;

static void *g_turnip_handle = NULL;
static PFN_vk_icdNegotiateLoaderICDInterfaceVersion g_turnip_negotiate = NULL;
static PFN_vk_icdGetInstanceProcAddr g_turnip_gipa = NULL;
static PFN_vk_icdGetPhysicalDeviceProcAddr g_turnip_gpdpa = NULL;

static void init_shim_globals(void)
{
    asoft_load_config_from_env(&g_cfg);
    const char *tf = getenv("ASOFT_KGSL_TRACE_FILE");
    if (tf && *tf) {
        snprintf(g_trace_path, sizeof(g_trace_path), "%s", tf);
    } else {
        g_trace_path[0] = '\0';
    }
    g_real_open = (fn_open_t)dlsym(RTLD_NEXT, "open");
    g_real_open64 = (fn_open_t)dlsym(RTLD_NEXT, "open64");
    g_real_openat = (fn_openat_t)dlsym(RTLD_NEXT, "openat");
    g_real_openat64 = (fn_openat_t)dlsym(RTLD_NEXT, "openat64");
    g_real_close = (fn_close_t)dlsym(RTLD_NEXT, "close");
    g_real_ioctl = (fn_ioctl_t)dlsym(RTLD_NEXT, "ioctl");
}

static void ensure_init(void)
{
    pthread_once(&g_init_once, init_shim_globals);
}

static int shim_raw_open(const char *path, int flags, mode_t mode)
{
    ensure_init();
    if (!g_real_open) {
        errno = ENOSYS;
        return -1;
    }
    return g_real_open(path, flags, mode);
}

static int shim_raw_close(int fd)
{
    ensure_init();
    if (!g_real_close) {
        errno = ENOSYS;
        return -1;
    }
    return g_real_close(fd);
}

static void trace_logf(const char *fmt, ...)
{
    ensure_init();
    if (!g_trace_path[0]) {
        return;
    }
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    char msg[512];
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(msg, sizeof(msg), fmt, ap);
    va_end(ap);
    if (n < 0) {
        return;
    }
    char line[640];
    uint64_t seq = __atomic_add_fetch(&g_seq, 1, __ATOMIC_RELAXED);
    snprintf(line, sizeof(line), "[%llu.%03ld #%llu] %s",
            (unsigned long long)ts.tv_sec, ts.tv_nsec / 1000000L, (unsigned long long)seq, msg);

    pthread_mutex_lock(&g_trace_lock);
    asoft_trace_write(g_trace_path, line, ASOFT_TRACE_MAX_BYTES, g_cfg.sync_log);
    pthread_mutex_unlock(&g_trace_lock);
}

/* -- KGSL FD & Imported Userptr BO Tracking ------------------------------------------------------- */

#define MAX_KGSL_FDS 32
#define MAX_KGSL_BOS 2048
#define KGSL_FD_BITSET_MAX 1024

static uint64_t g_kgsl_fd_bits[KGSL_FD_BITSET_MAX / 64];
static int g_kgsl_fds[MAX_KGSL_FDS];
static int g_kgsl_fd_count = 0;
static int g_kgsl_high_fd_count = 0;

struct kgsl_tracked_bo {
    int in_use;
    int fd;
    unsigned int id;
    uint64_t virtaddr;
    uint64_t gpuaddr;
    uint64_t size;
};

static struct kgsl_tracked_bo g_kgsl_bos[MAX_KGSL_BOS];
static int g_kgsl_bo_count = 0;
static int g_kgsl_sync_mode = 0; /* 0 = try batched GPUOBJ_SYNC, 1 = batched works, 2 = legacy per-BO GPUMEM_SYNC_CACHE */
static uint64_t g_kgsl_cmd_count = 0;
static uint64_t g_gpu_work_gen = 0;
static __thread int t_in_vk_submit = 0;

static void track_kgsl_fd(int fd)
{
    if (fd < 0) return;
    if (fd < KGSL_FD_BITSET_MAX) {
        __atomic_fetch_or(&g_kgsl_fd_bits[fd >> 6], (1ULL << (fd & 63)), __ATOMIC_RELEASE);
    } else {
        pthread_mutex_lock(&g_state_lock);
        int exists = 0;
        for (int i = 0; i < g_kgsl_fd_count; i++) {
            if (g_kgsl_fds[i] == fd) {
                exists = 1;
                break;
            }
        }
        if (!exists && g_kgsl_fd_count < MAX_KGSL_FDS) {
            g_kgsl_fds[g_kgsl_fd_count++] = fd;
            __atomic_add_fetch(&g_kgsl_high_fd_count, 1, __ATOMIC_RELEASE);
        }
        pthread_mutex_unlock(&g_state_lock);
    }
    trace_logf("kgsl_open fd=%d", fd);
}

static int is_kgsl_fd(int fd)
{
    if (fd < 0) return 0;
    if (fd < KGSL_FD_BITSET_MAX) {
        uint64_t word = __atomic_load_n(&g_kgsl_fd_bits[fd >> 6], __ATOMIC_ACQUIRE);
        return (int)((word >> (fd & 63)) & 1ULL);
    }
    if (__atomic_load_n(&g_kgsl_high_fd_count, __ATOMIC_ACQUIRE) <= 0) {
        return 0;
    }
    pthread_mutex_lock(&g_state_lock);
    for (int i = 0; i < g_kgsl_fd_count; i++) {
        if (g_kgsl_fds[i] == fd) {
            pthread_mutex_unlock(&g_state_lock);
            return 1;
        }
    }
    pthread_mutex_unlock(&g_state_lock);
    return 0;
}

static void untrack_kgsl_fd(int fd)
{
    if (!is_kgsl_fd(fd)) {
        return; /* Lock-free fast path for 99.99% of close() calls in QEMU */
    }
    int found = 0;
    if (fd < KGSL_FD_BITSET_MAX) {
        uint64_t mask = (1ULL << (fd & 63));
        uint64_t prev = __atomic_fetch_and(&g_kgsl_fd_bits[fd >> 6], ~mask, __ATOMIC_ACQ_REL);
        found = (prev & mask) != 0;
    }
    pthread_mutex_lock(&g_state_lock);
    if (fd >= KGSL_FD_BITSET_MAX) {
        for (int i = 0; i < g_kgsl_fd_count; i++) {
            if (g_kgsl_fds[i] == fd) {
                g_kgsl_fds[i] = g_kgsl_fds[--g_kgsl_fd_count];
                __atomic_sub_fetch(&g_kgsl_high_fd_count, 1, __ATOMIC_RELEASE);
                found = 1;
                break;
            }
        }
    }
    if (found) {
        int removed = 0;
        for (int i = 0; i < MAX_KGSL_BOS; i++) {
            if (g_kgsl_bos[i].in_use && g_kgsl_bos[i].fd == fd) {
                g_kgsl_bos[i].in_use = 0;
                removed++;
            }
        }
        if (removed > 0) {
            __atomic_sub_fetch(&g_kgsl_bo_count, removed, __ATOMIC_RELEASE);
        }
    }
    pthread_mutex_unlock(&g_state_lock);
    if (found) {
        trace_logf("kgsl_close fd=%d", fd);
    }
}

static void track_kgsl_bo(int fd, unsigned int id, uint64_t virtaddr, uint64_t size)
{
    uint64_t gpuaddr = 0;
    if (g_real_ioctl && id != 0) {
        struct asoft_kgsl_gpuobj_info info;
        memset(&info, 0, sizeof(info));
        info.id = id;
        if (g_real_ioctl(fd, (int)IOCTL_KGSL_GPUOBJ_INFO, &info) == 0) {
            gpuaddr = info.gpuaddr;
        }
    }
    pthread_mutex_lock(&g_state_lock);
    int free_slot = -1;
    for (int i = 0; i < MAX_KGSL_BOS; i++) {
        if (g_kgsl_bos[i].in_use && g_kgsl_bos[i].fd == fd && g_kgsl_bos[i].id == id) {
            g_kgsl_bos[i].virtaddr = virtaddr;
            g_kgsl_bos[i].gpuaddr = gpuaddr;
            g_kgsl_bos[i].size = size;
            pthread_mutex_unlock(&g_state_lock);
            return;
        }
        if (free_slot < 0 && !g_kgsl_bos[i].in_use) {
            free_slot = i;
        }
    }
    if (free_slot >= 0) {
        g_kgsl_bos[free_slot].in_use = 1;
        g_kgsl_bos[free_slot].fd = fd;
        g_kgsl_bos[free_slot].id = id;
        g_kgsl_bos[free_slot].virtaddr = virtaddr;
        g_kgsl_bos[free_slot].gpuaddr = gpuaddr;
        g_kgsl_bos[free_slot].size = size;
        __atomic_add_fetch(&g_kgsl_bo_count, 1, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&g_state_lock);
}

static void untrack_kgsl_bo(int fd, unsigned int id)
{
    if (__atomic_load_n(&g_kgsl_bo_count, __ATOMIC_ACQUIRE) <= 0) {
        return;
    }
    pthread_mutex_lock(&g_state_lock);
    int removed = 0;
    for (int i = 0; i < MAX_KGSL_BOS; i++) {
        if (g_kgsl_bos[i].in_use && g_kgsl_bos[i].fd == fd && g_kgsl_bos[i].id == id) {
            g_kgsl_bos[i].in_use = 0;
            removed++;
        }
    }
    if (removed > 0) {
        __atomic_sub_fetch(&g_kgsl_bo_count, removed, __ATOMIC_RELEASE);
    }
    pthread_mutex_unlock(&g_state_lock);
}

static void sync_kgsl_imported_bos(unsigned int op)
{
    if (!g_real_ioctl || !g_cfg.cache_sync) {
        return;
    }
    if (__atomic_load_n(&g_kgsl_bo_count, __ATOMIC_ACQUIRE) <= 0) {
        return;
    }
    struct kgsl_tracked_bo snap[MAX_KGSL_BOS];
    int n = 0;
    pthread_mutex_lock(&g_state_lock);
    for (int i = 0; i < MAX_KGSL_BOS && n < MAX_KGSL_BOS; i++) {
        if (g_kgsl_bos[i].in_use && g_kgsl_bos[i].id != 0 && g_kgsl_bos[i].size > 0) {
            snap[n++] = g_kgsl_bos[i];
        }
    }
    pthread_mutex_unlock(&g_state_lock);
    if (n == 0) {
        return;
    }

    int mode = __atomic_load_n(&g_kgsl_sync_mode, __ATOMIC_ACQUIRE);
    if (mode != 2) {
        /* Batch all BOs per KGSL fd into a single IOCTL_KGSL_GPUOBJ_SYNC syscall */
        struct asoft_kgsl_gpuobj_sync_obj objs[MAX_KGSL_BOS];
        int done[MAX_KGSL_BOS];
        memset(done, 0, (size_t)n * sizeof(int));
        int batch_failed_notty = 0;

        for (int i = 0; i < n; i++) {
            if (done[i]) continue;
            int fd = snap[i].fd;
            int count = 0;
            for (int j = i; j < n; j++) {
                if (!done[j] && snap[j].fd == fd) {
                    done[j] = 1;
                    objs[count].offset = 0;
                    objs[count].length = snap[j].size;
                    objs[count].id = snap[j].id;
                    objs[count].op = op;
                    count++;
                }
            }
            struct asoft_kgsl_gpuobj_sync req = {
                .objs = (uintptr_t)objs,
                .obj_len = sizeof(objs[0]),
                .count = (unsigned int)count
            };
            int rc = g_real_ioctl(fd, (int)IOCTL_KGSL_GPUOBJ_SYNC, &req);
            if (rc == 0) {
                if (mode == 0) {
                    __atomic_store_n(&g_kgsl_sync_mode, 1, __ATOMIC_RELEASE);
                }
            } else if (errno == ENOTTY) {
                __atomic_store_n(&g_kgsl_sync_mode, 2, __ATOMIC_RELEASE);
                batch_failed_notty = 1;
                break;
            }
        }
        if (!batch_failed_notty) {
            return;
        }
    }

    for (int i = 0; i < n; i++) {
        struct asoft_kgsl_gpumem_sync_cache sc;
        memset(&sc, 0, sizeof(sc));
        sc.gpuaddr = (unsigned long)snap[i].gpuaddr;
        sc.id = snap[i].id;
        sc.op = op;
        sc.offset = 0;
        sc.length = (size_t)snap[i].size;
        (void)g_real_ioctl(snap[i].fd, (int)IOCTL_KGSL_GPUMEM_SYNC_CACHE, &sc);
    }
}

static int path_is_kgsl(const char *path)
{
    return path && strcmp(path, "/dev/kgsl-3d0") == 0;
}

static int path_is_serial_log(const char *path)
{
    if (!path) return 0;
    size_t len = strlen(path);
    return len >= 10 && strcmp(path + len - 10, "serial.txt") == 0;
}

/* -- LD_PRELOAD libc interposers ------------------------------------------------------------------ */

int open(const char *pathname, int flags, ...)
{
    ensure_init();
    mode_t mode = 0;
    int has_mode = (flags & O_CREAT) != 0;
#ifdef O_TMPFILE
    if ((flags & O_TMPFILE) == O_TMPFILE) has_mode = 1;
#endif
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    int mod_flags = flags;
    if (g_cfg.sync_serial && path_is_serial_log(pathname)) {
        mod_flags |= O_SYNC;
    }
    if (!g_real_open) {
        errno = ENOSYS;
        return -1;
    }
    int fd = has_mode ? g_real_open(pathname, mod_flags, mode) : g_real_open(pathname, mod_flags);
    if (fd < 0 && mod_flags != flags) {
        fd = has_mode ? g_real_open(pathname, flags, mode) : g_real_open(pathname, flags);
    }
    if (fd >= 0 && path_is_kgsl(pathname)) {
        track_kgsl_fd(fd);
    }
    return fd;
}

int open64(const char *pathname, int flags, ...)
{
    ensure_init();
    mode_t mode = 0;
    int has_mode = (flags & O_CREAT) != 0;
#ifdef O_TMPFILE
    if ((flags & O_TMPFILE) == O_TMPFILE) has_mode = 1;
#endif
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    int mod_flags = flags;
    if (g_cfg.sync_serial && path_is_serial_log(pathname)) {
        mod_flags |= O_SYNC;
    }
    fn_open_t fn = g_real_open64 ? g_real_open64 : g_real_open;
    if (!fn) {
        errno = ENOSYS;
        return -1;
    }
    int fd = has_mode ? fn(pathname, mod_flags, mode) : fn(pathname, mod_flags);
    if (fd < 0 && mod_flags != flags) {
        fd = has_mode ? fn(pathname, flags, mode) : fn(pathname, flags);
    }
    if (fd >= 0 && path_is_kgsl(pathname)) {
        track_kgsl_fd(fd);
    }
    return fd;
}

int openat(int dirfd, const char *pathname, int flags, ...)
{
    ensure_init();
    mode_t mode = 0;
    int has_mode = (flags & O_CREAT) != 0;
#ifdef O_TMPFILE
    if ((flags & O_TMPFILE) == O_TMPFILE) has_mode = 1;
#endif
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    int mod_flags = flags;
    if (g_cfg.sync_serial && path_is_serial_log(pathname)) {
        mod_flags |= O_SYNC;
    }
    if (!g_real_openat) {
        errno = ENOSYS;
        return -1;
    }
    int fd = has_mode ? g_real_openat(dirfd, pathname, mod_flags, mode) : g_real_openat(dirfd, pathname, mod_flags);
    if (fd < 0 && mod_flags != flags) {
        fd = has_mode ? g_real_openat(dirfd, pathname, flags, mode) : g_real_openat(dirfd, pathname, flags);
    }
    if (fd >= 0 && path_is_kgsl(pathname)) {
        track_kgsl_fd(fd);
    }
    return fd;
}

int openat64(int dirfd, const char *pathname, int flags, ...)
{
    ensure_init();
    mode_t mode = 0;
    int has_mode = (flags & O_CREAT) != 0;
#ifdef O_TMPFILE
    if ((flags & O_TMPFILE) == O_TMPFILE) has_mode = 1;
#endif
    if (has_mode) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    int mod_flags = flags;
    if (g_cfg.sync_serial && path_is_serial_log(pathname)) {
        mod_flags |= O_SYNC;
    }
    fn_openat_t fn = g_real_openat64 ? g_real_openat64 : g_real_openat;
    if (!fn) {
        errno = ENOSYS;
        return -1;
    }
    int fd = has_mode ? fn(dirfd, pathname, mod_flags, mode) : fn(dirfd, pathname, mod_flags);
    if (fd < 0 && mod_flags != flags) {
        fd = has_mode ? fn(dirfd, pathname, flags, mode) : fn(dirfd, pathname, flags);
    }
    if (fd >= 0 && path_is_kgsl(pathname)) {
        track_kgsl_fd(fd);
    }
    return fd;
}

int close(int fd)
{
    ensure_init();
    untrack_kgsl_fd(fd);
    if (!g_real_close) {
        errno = ENOSYS;
        return -1;
    }
    return g_real_close(fd);
}

int ioctl(int fd, int request, ...)
{
    ensure_init();
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    if (!g_real_ioctl) {
        errno = ENOSYS;
        return -1;
    }

    int kgsl = is_kgsl_fd(fd);
    unsigned int req_u = (unsigned int)request;

    if (kgsl && req_u == (unsigned int)IOCTL_KGSL_GPUOBJ_IMPORT && arg) {
        struct asoft_kgsl_gpuobj_import *imp = (struct asoft_kgsl_gpuobj_import *)arg;
        uint64_t uaddr = 0;
        if (imp->type == KGSL_USER_MEM_TYPE_ADDR && imp->priv) {
            const struct asoft_kgsl_gpuobj_import_useraddr *ua =
                    (const struct asoft_kgsl_gpuobj_import_useraddr *)(uintptr_t)imp->priv;
            uaddr = ua->virtaddr;
        }
        trace_logf("kgsl_import_pre fd=%d type=%u ptr=0x%llx len=%llu flags=0x%llx",
                fd, imp->type, (unsigned long long)uaddr,
                (unsigned long long)imp->priv_len, (unsigned long long)imp->flags);
        int rc = g_real_ioctl(fd, request, arg);
        int saved_errno = errno;
        if (rc == 0 && imp->type == KGSL_USER_MEM_TYPE_ADDR) {
            track_kgsl_bo(fd, imp->id, uaddr, imp->priv_len);
        }
        trace_logf("kgsl_import_post rc=%d err=%d id=%u", rc, rc < 0 ? saved_errno : 0, imp->id);
        errno = saved_errno;
        return rc;
    }

    if (kgsl && req_u == (unsigned int)IOCTL_KGSL_GPUOBJ_FREE && arg) {
        const struct asoft_kgsl_gpuobj_free *fr = (const struct asoft_kgsl_gpuobj_free *)arg;
        untrack_kgsl_bo(fd, fr->id);
    } else if (kgsl && req_u == (unsigned int)IOCTL_KGSL_GPUMEM_FREE_ID && arg) {
        const struct asoft_kgsl_gpumem_free_id *fr = (const struct asoft_kgsl_gpumem_free_id *)arg;
        untrack_kgsl_bo(fd, fr->id);
    }

    if (kgsl && req_u == (unsigned int)IOCTL_KGSL_GPU_COMMAND && arg) {
        struct asoft_kgsl_gpu_command *cmd = (struct asoft_kgsl_gpu_command *)arg;
        uint64_t cseq = __atomic_add_fetch(&g_kgsl_cmd_count, 1, __ATOMIC_RELAXED);
        if (!t_in_vk_submit) {
            if (g_cfg.cache_sync) {
                sync_kgsl_imported_bos(KGSL_GPUMEM_CACHE_CLEAN);
            }
            __atomic_add_fetch(&g_gpu_work_gen, 1, __ATOMIC_RELEASE);
        }
        if (g_cfg.verbose || cseq <= 200 || (cseq % 32ULL) == 0) {
            trace_logf("kgsl_cmd_pre #%llu ctx=%u cmds=%u objs=%u syncs=%u flags=0x%llx",
                    (unsigned long long)cseq, cmd->context_id, cmd->numcmds,
                    cmd->numobjs, cmd->numsyncs, (unsigned long long)cmd->flags);
        }
        int rc = g_real_ioctl(fd, request, arg);
        int saved_errno = errno;
        if (rc < 0) {
            trace_logf("kgsl_cmd_err #%llu rc=%d errno=%d", (unsigned long long)cseq, rc, saved_errno);
        }
        errno = saved_errno;
        return rc;
    }

    return g_real_ioctl(fd, request, arg);
}

/* -- Vulkan ICD Wrapper & Bounce / Feature Guard / Submit Throttle -------------------------------- */

#define MAX_INSTANCES 8
#define MAX_PHYS_DEVS 16
#define MAX_DEVICES 16
#define MAX_QUEUES 64
#define MAX_BOUNCE_ALLOCS 4096

struct instance_entry {
    int in_use;
    VkInstance instance;
    PFN_vkDestroyInstance destroy_instance;
    PFN_vkEnumeratePhysicalDevices enum_phys_devs;
    PFN_vkGetPhysicalDeviceFeatures get_features;
    PFN_vkGetPhysicalDeviceFeatures2 get_features2;
    PFN_vkGetPhysicalDeviceMemoryProperties get_mem_props;
    PFN_vkEnumerateDeviceExtensionProperties enum_dev_exts;
    PFN_vkCreateDevice create_device;
};

struct phys_entry {
    int in_use;
    VkPhysicalDevice phys;
    VkInstance instance;
};

struct device_entry {
    int in_use;
    VkDevice device;
    VkPhysicalDevice phys;
    VkPhysicalDeviceMemoryProperties mem_props;
    pthread_mutex_t submit_lock;
    PFN_vkGetDeviceProcAddr get_device_proc_addr;
    PFN_vkDestroyDevice destroy_device;
    PFN_vkGetDeviceQueue get_device_queue;
    PFN_vkGetDeviceQueue2 get_device_queue2;
    PFN_vkAllocateMemory alloc_mem;
    PFN_vkFreeMemory free_mem;
    PFN_vkMapMemory map_mem;
    PFN_vkUnmapMemory unmap_mem;
    PFN_vkQueueSubmit queue_submit;
    PFN_vkQueueSubmit2 queue_submit2;
    PFN_vkQueueSubmit2KHR queue_submit2_khr;
    PFN_vkQueueBindSparse queue_bind_sparse;
    PFN_vkWaitForFences wait_fences;
    PFN_vkGetFenceStatus get_fence_status;
    PFN_vkQueueWaitIdle queue_wait_idle;
    PFN_vkDeviceWaitIdle device_wait_idle;
    PFN_vkWaitSemaphores wait_semaphores;
    PFN_vkWaitSemaphoresKHR wait_semaphores_khr;
};

struct queue_entry {
    int in_use;
    VkQueue queue;
    VkDevice device;
};

struct bounce_alloc {
    int in_use;
    VkDevice device;
    VkDeviceMemory memory;
    struct asoft_bounce_region region;
    int client_mapped;
};

static struct instance_entry g_instances[MAX_INSTANCES];
static struct phys_entry g_phys[MAX_PHYS_DEVS];
static struct device_entry g_devices[MAX_DEVICES];
static struct queue_entry g_queues[MAX_QUEUES];
static struct bounce_alloc g_bounces[MAX_BOUNCE_ALLOCS];
static uint64_t g_vk_submit_seq = 0;

static int ensure_turnip_loaded(void)
{
    ensure_init();
    int just_loaded = 0;
    const char *loaded_path = NULL;
    PFN_vk_icdGetInstanceProcAddr gipa = NULL;

    /* Use dedicated g_turnip_load_lock (never g_state_lock) because dlopen() invokes close()
     * which enters our LD_PRELOAD interposer -> untrack_kgsl_fd() -> g_state_lock. */
    pthread_mutex_lock(&g_turnip_load_lock);
    if (!g_turnip_handle) {
        const char *path = getenv("ASOFT_REAL_TURNIP_PATH");
        if (!path || !*path) {
            path = "libvulkan_freedreno.so";
        }
        g_turnip_handle = dlopen(path, RTLD_NOW | RTLD_LOCAL);
        if (!g_turnip_handle) {
            SHIM_LOGE("dlopen(%s) failed: %s", path, dlerror());
            pthread_mutex_unlock(&g_turnip_load_lock);
            return -1;
        }
        g_turnip_negotiate = (PFN_vk_icdNegotiateLoaderICDInterfaceVersion)
                dlsym(g_turnip_handle, "vk_icdNegotiateLoaderICDInterfaceVersion");
        g_turnip_gipa = (PFN_vk_icdGetInstanceProcAddr)
                dlsym(g_turnip_handle, "vk_icdGetInstanceProcAddr");
        g_turnip_gpdpa = (PFN_vk_icdGetPhysicalDeviceProcAddr)
                dlsym(g_turnip_handle, "vk_icdGetPhysicalDeviceProcAddr");
        just_loaded = 1;
        loaded_path = path;
    }
    gipa = g_turnip_gipa;
    pthread_mutex_unlock(&g_turnip_load_lock);

    if (just_loaded) {
        trace_logf("icd_init real_turnip=%s gipa=%p cfg(bounce=%d/%d cache=%d guard=%d throttle=%d sync=%d/%d)",
                loaded_path, (void *)gipa, g_cfg.multi_vma_bounce, g_cfg.all_host_bounce,
                g_cfg.cache_sync, g_cfg.feature_guard, g_cfg.submit_throttle, g_cfg.sync_log, g_cfg.sync_serial);
    }
    return (gipa != NULL) ? 0 : -1;
}

static struct instance_entry *find_instance(VkInstance inst)
{
    for (int i = 0; i < MAX_INSTANCES; i++) {
        if (g_instances[i].in_use && g_instances[i].instance == inst) {
            return &g_instances[i];
        }
    }
    return NULL;
}

static struct instance_entry *find_instance_for_phys(VkPhysicalDevice phys)
{
    for (int i = 0; i < MAX_PHYS_DEVS; i++) {
        if (g_phys[i].in_use && g_phys[i].phys == phys) {
            return find_instance(g_phys[i].instance);
        }
    }
    return NULL;
}

static void record_phys_dev(VkPhysicalDevice phys, VkInstance inst)
{
    for (int i = 0; i < MAX_PHYS_DEVS; i++) {
        if (g_phys[i].in_use && g_phys[i].phys == phys) {
            g_phys[i].instance = inst;
            return;
        }
    }
    for (int i = 0; i < MAX_PHYS_DEVS; i++) {
        if (!g_phys[i].in_use) {
            g_phys[i].in_use = 1;
            g_phys[i].phys = phys;
            g_phys[i].instance = inst;
            return;
        }
    }
}

static struct device_entry *find_device(VkDevice dev)
{
    for (int i = 0; i < MAX_DEVICES; i++) {
        if (g_devices[i].in_use && g_devices[i].device == dev) {
            return &g_devices[i];
        }
    }
    return NULL;
}

static struct device_entry *find_device_for_queue(VkQueue q)
{
    for (int i = 0; i < MAX_QUEUES; i++) {
        if (g_queues[i].in_use && g_queues[i].queue == q) {
            return find_device(g_queues[i].device);
        }
    }
    return NULL;
}

static void record_queue(VkDevice dev, VkQueue q)
{
    pthread_mutex_lock(&g_state_lock);
    for (int i = 0; i < MAX_QUEUES; i++) {
        if (g_queues[i].in_use && g_queues[i].queue == q) {
            g_queues[i].device = dev;
            pthread_mutex_unlock(&g_state_lock);
            return;
        }
    }
    for (int i = 0; i < MAX_QUEUES; i++) {
        if (!g_queues[i].in_use) {
            g_queues[i].in_use = 1;
            g_queues[i].queue = q;
            g_queues[i].device = dev;
            break;
        }
    }
    pthread_mutex_unlock(&g_state_lock);
}

static void mask_features_struct(VkPhysicalDeviceFeatures *f)
{
    if (!f || !g_cfg.feature_guard) {
        return;
    }
    f->sparseBinding = VK_FALSE;
    f->sparseResidencyBuffer = VK_FALSE;
    f->sparseResidencyImage2D = VK_FALSE;
    f->sparseResidencyImage3D = VK_FALSE;
    f->sparseResidency2Samples = VK_FALSE;
    f->sparseResidency4Samples = VK_FALSE;
    f->sparseResidency8Samples = VK_FALSE;
    f->sparseResidency16Samples = VK_FALSE;
    f->sparseResidencyAliased = VK_FALSE;
}

static void mask_features2_chain(VkPhysicalDeviceFeatures2 *f2)
{
    if (!f2 || !g_cfg.feature_guard) {
        return;
    }
    mask_features_struct(&f2->features);
    VkBaseOutStructure *node = (VkBaseOutStructure *)f2->pNext;
    while (node) {
        if (node->sType == VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_HOST_IMAGE_COPY_FEATURES_EXT) {
            VkPhysicalDeviceHostImageCopyFeaturesEXT *hic = (VkPhysicalDeviceHostImageCopyFeaturesEXT *)node;
            hic->hostImageCopy = VK_FALSE;
        }
        node = node->pNext;
    }
}

static pthread_mutex_t g_bounce_lock = PTHREAD_MUTEX_INITIALIZER;
static int g_bounce_active_count = 0;

static int find_host_coherent_mem_type(const VkPhysicalDeviceMemoryProperties *mp, uint32_t preferred_idx)
{
    if (!mp || mp->memoryTypeCount == 0) return (int)preferred_idx;
    uint32_t flags[VK_MAX_MEMORY_TYPES];
    uint32_t count = mp->memoryTypeCount < VK_MAX_MEMORY_TYPES ? mp->memoryTypeCount : VK_MAX_MEMORY_TYPES;
    for (uint32_t i = 0; i < count; i++) {
        flags[i] = (uint32_t)mp->memoryTypes[i].propertyFlags;
    }
    return asoft_select_bounce_mem_type(
            flags, count, preferred_idx,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
            VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            VK_MEMORY_PROPERTY_HOST_CACHED_BIT);
}

static uint32_t all_host_coherent_bits(const VkPhysicalDeviceMemoryProperties *mp)
{
    uint32_t bits = 0;
    if (!mp) return 0;
    for (uint32_t i = 0; i < mp->memoryTypeCount && i < 32; i++) {
        VkMemoryPropertyFlags pf = mp->memoryTypes[i].propertyFlags;
        if ((pf & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT))
                == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
            bits |= (1U << i);
        }
    }
    return bits;
}

static void sync_device_bounces(VkDevice dev, int to_shadow)
{
    if (__atomic_load_n(&g_bounce_active_count, __ATOMIC_ACQUIRE) <= 0) {
        return; /* Zero-lock fast path when no allocations are bounced */
    }
    pthread_mutex_lock(&g_bounce_lock);
    for (int i = 0; i < MAX_BOUNCE_ALLOCS; i++) {
        if (!g_bounces[i].in_use) continue;
        if (dev != VK_NULL_HANDLE && g_bounces[i].device != dev) continue;
        if (to_shadow) {
            (void)asoft_bounce_sync_to_shadow(&g_bounces[i].region);
        } else {
            (void)asoft_bounce_sync_to_host(&g_bounces[i].region);
        }
    }
    pthread_mutex_unlock(&g_bounce_lock);
}

static struct bounce_alloc *find_bounce_locked(VkDeviceMemory mem)
{
    for (int i = 0; i < MAX_BOUNCE_ALLOCS; i++) {
        if (g_bounces[i].in_use && g_bounces[i].memory == mem) {
            return &g_bounces[i];
        }
    }
    return NULL;
}

/* -- Wrapped Instance Functions ------------------------------------------------------------------- */

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkCreateInstance(
        const VkInstanceCreateInfo *pCreateInfo,
        const VkAllocationCallbacks *pAllocator,
        VkInstance *pInstance)
{
    if (ensure_turnip_loaded() != 0) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    PFN_vkCreateInstance real_create = (PFN_vkCreateInstance)g_turnip_gipa(VK_NULL_HANDLE, "vkCreateInstance");
    if (!real_create) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    VkResult res = real_create(pCreateInfo, pAllocator, pInstance);
    if (res == VK_SUCCESS && pInstance && *pInstance) {
        VkInstance inst = *pInstance;
        pthread_mutex_lock(&g_state_lock);
        for (int i = 0; i < MAX_INSTANCES; i++) {
            if (!g_instances[i].in_use) {
                memset(&g_instances[i], 0, sizeof(g_instances[i]));
                g_instances[i].in_use = 1;
                g_instances[i].instance = inst;
                g_instances[i].destroy_instance = (PFN_vkDestroyInstance)g_turnip_gipa(inst, "vkDestroyInstance");
                g_instances[i].enum_phys_devs = (PFN_vkEnumeratePhysicalDevices)g_turnip_gipa(inst, "vkEnumeratePhysicalDevices");
                g_instances[i].get_features = (PFN_vkGetPhysicalDeviceFeatures)g_turnip_gipa(inst, "vkGetPhysicalDeviceFeatures");
                g_instances[i].get_features2 = (PFN_vkGetPhysicalDeviceFeatures2)g_turnip_gipa(inst, "vkGetPhysicalDeviceFeatures2");
                if (!g_instances[i].get_features2) {
                    g_instances[i].get_features2 = (PFN_vkGetPhysicalDeviceFeatures2)g_turnip_gipa(inst, "vkGetPhysicalDeviceFeatures2KHR");
                }
                g_instances[i].get_mem_props = (PFN_vkGetPhysicalDeviceMemoryProperties)g_turnip_gipa(inst, "vkGetPhysicalDeviceMemoryProperties");
                g_instances[i].enum_dev_exts = (PFN_vkEnumerateDeviceExtensionProperties)g_turnip_gipa(inst, "vkEnumerateDeviceExtensionProperties");
                g_instances[i].create_device = (PFN_vkCreateDevice)g_turnip_gipa(inst, "vkCreateDevice");
                break;
            }
        }
        pthread_mutex_unlock(&g_state_lock);
        trace_logf("vkCreateInstance ok inst=%p", (void *)inst);
    }
    return res;
}

static VKAPI_ATTR void VKAPI_CALL shim_vkDestroyInstance(
        VkInstance instance,
        const VkAllocationCallbacks *pAllocator)
{
    PFN_vkDestroyInstance real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct instance_entry *ie = find_instance(instance);
    if (ie) {
        real_fn = ie->destroy_instance;
        ie->in_use = 0;
    }
    for (int i = 0; i < MAX_PHYS_DEVS; i++) {
        if (g_phys[i].in_use && g_phys[i].instance == instance) {
            g_phys[i].in_use = 0;
        }
    }
    pthread_mutex_unlock(&g_state_lock);
    if (real_fn) {
        real_fn(instance, pAllocator);
    }
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkEnumeratePhysicalDevices(
        VkInstance instance,
        uint32_t *pPhysicalDeviceCount,
        VkPhysicalDevice *pPhysicalDevices)
{
    PFN_vkEnumeratePhysicalDevices real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct instance_entry *ie = find_instance(instance);
    if (ie) real_fn = ie->enum_phys_devs;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) {
        real_fn = (PFN_vkEnumeratePhysicalDevices)g_turnip_gipa(instance, "vkEnumeratePhysicalDevices");
    }
    if (!real_fn) return VK_ERROR_INITIALIZATION_FAILED;
    VkResult res = real_fn(instance, pPhysicalDeviceCount, pPhysicalDevices);
    if ((res == VK_SUCCESS || res == VK_INCOMPLETE) && pPhysicalDevices && pPhysicalDeviceCount) {
        pthread_mutex_lock(&g_state_lock);
        for (uint32_t i = 0; i < *pPhysicalDeviceCount; i++) {
            record_phys_dev(pPhysicalDevices[i], instance);
        }
        pthread_mutex_unlock(&g_state_lock);
    }
    return res;
}

static VKAPI_ATTR void VKAPI_CALL shim_vkGetPhysicalDeviceFeatures(
        VkPhysicalDevice physicalDevice,
        VkPhysicalDeviceFeatures *pFeatures)
{
    PFN_vkGetPhysicalDeviceFeatures real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct instance_entry *ie = find_instance_for_phys(physicalDevice);
    if (ie) real_fn = ie->get_features;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) return;
    real_fn(physicalDevice, pFeatures);
    mask_features_struct(pFeatures);
}

static VKAPI_ATTR void VKAPI_CALL shim_vkGetPhysicalDeviceFeatures2(
        VkPhysicalDevice physicalDevice,
        VkPhysicalDeviceFeatures2 *pFeatures)
{
    PFN_vkGetPhysicalDeviceFeatures2 real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct instance_entry *ie = find_instance_for_phys(physicalDevice);
    if (ie) real_fn = ie->get_features2;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) return;
    real_fn(physicalDevice, pFeatures);
    mask_features2_chain(pFeatures);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkEnumerateDeviceExtensionProperties(
        VkPhysicalDevice physicalDevice,
        const char *pLayerName,
        uint32_t *pPropertyCount,
        VkExtensionProperties *pProperties)
{
    PFN_vkEnumerateDeviceExtensionProperties real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct instance_entry *ie = find_instance_for_phys(physicalDevice);
    if (ie) real_fn = ie->enum_dev_exts;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) return VK_ERROR_INITIALIZATION_FAILED;
    if (!g_cfg.feature_guard || pLayerName != NULL || !pPropertyCount) {
        return real_fn(physicalDevice, pLayerName, pPropertyCount, pProperties);
    }
    uint32_t raw_count = 0;
    VkResult res = real_fn(physicalDevice, pLayerName, &raw_count, NULL);
    if (res != VK_SUCCESS) return res;
    if (raw_count == 0) {
        *pPropertyCount = 0;
        return VK_SUCCESS;
    }
    VkExtensionProperties *tmp = malloc((size_t)raw_count * sizeof(VkExtensionProperties));
    if (!tmp) return VK_ERROR_OUT_OF_HOST_MEMORY;
    res = real_fn(physicalDevice, pLayerName, &raw_count, tmp);
    if (res != VK_SUCCESS && res != VK_INCOMPLETE) {
        free(tmp);
        return res;
    }
    uint32_t filtered = 0;
    for (uint32_t i = 0; i < raw_count; i++) {
        if (!asoft_is_guarded_extension(tmp[i].extensionName)) {
            filtered++;
        }
    }
    if (!pProperties) {
        *pPropertyCount = filtered;
        free(tmp);
        return VK_SUCCESS;
    }
    uint32_t cap = *pPropertyCount;
    uint32_t out_n = 0;
    for (uint32_t i = 0; i < raw_count && out_n < cap; i++) {
        if (!asoft_is_guarded_extension(tmp[i].extensionName)) {
            pProperties[out_n++] = tmp[i];
        }
    }
    *pPropertyCount = out_n;
    free(tmp);
    return (out_n < filtered) ? VK_INCOMPLETE : VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkGetMemoryHostPointerPropertiesEXT(
        VkDevice device,
        VkExternalMemoryHandleTypeFlagBits handleType,
        const void *pHostPointer,
        VkMemoryHostPointerPropertiesEXT *pMemoryHostPointerProperties)
{
    PFN_vkGetMemoryHostPointerPropertiesEXT real_fn = NULL;
    VkPhysicalDeviceMemoryProperties mp;
    memset(&mp, 0, sizeof(mp));
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) {
        mp = de->mem_props;
        if (de->get_device_proc_addr) {
            real_fn = (PFN_vkGetMemoryHostPointerPropertiesEXT)
                    de->get_device_proc_addr(device, "vkGetMemoryHostPointerPropertiesEXT");
        }
    }
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) {
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }
    VkResult res = real_fn(device, handleType, pHostPointer, pMemoryHostPointerProperties);
    if (res != VK_SUCCESS && g_cfg.all_host_bounce && pMemoryHostPointerProperties) {
        uint32_t bits = all_host_coherent_bits(&mp);
        if (bits != 0) {
            trace_logf("host_ptr_props fallback ptr=%p orig_res=%d bits=0x%x", pHostPointer, res, bits);
            pMemoryHostPointerProperties->memoryTypeBits = bits;
            return VK_SUCCESS;
        }
    }
    return res;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkCreateDevice(
        VkPhysicalDevice physicalDevice,
        const VkDeviceCreateInfo *pCreateInfo,
        const VkAllocationCallbacks *pAllocator,
        VkDevice *pDevice)
{
    PFN_vkCreateDevice real_create = NULL;
    PFN_vkGetPhysicalDeviceMemoryProperties get_mp = NULL;
    VkInstance inst = VK_NULL_HANDLE;
    pthread_mutex_lock(&g_state_lock);
    struct instance_entry *ie = find_instance_for_phys(physicalDevice);
    if (ie) {
        real_create = ie->create_device;
        get_mp = ie->get_mem_props;
        inst = ie->instance;
    }
    pthread_mutex_unlock(&g_state_lock);
    if (!real_create) return VK_ERROR_INITIALIZATION_FAILED;

    VkResult res = real_create(physicalDevice, pCreateInfo, pAllocator, pDevice);
    if (res == VK_SUCCESS && pDevice && *pDevice) {
        VkDevice dev = *pDevice;
        PFN_vkGetDeviceProcAddr gdpa = (PFN_vkGetDeviceProcAddr)g_turnip_gipa(inst, "vkGetDeviceProcAddr");
        VkPhysicalDeviceMemoryProperties mp;
        memset(&mp, 0, sizeof(mp));
        if (get_mp) get_mp(physicalDevice, &mp);

        pthread_mutex_lock(&g_state_lock);
        for (int i = 0; i < MAX_DEVICES; i++) {
            if (!g_devices[i].in_use) {
                memset(&g_devices[i], 0, sizeof(g_devices[i]));
                g_devices[i].in_use = 1;
                g_devices[i].device = dev;
                g_devices[i].phys = physicalDevice;
                g_devices[i].mem_props = mp;
                pthread_mutex_init(&g_devices[i].submit_lock, NULL);
                g_devices[i].get_device_proc_addr = gdpa;
                if (gdpa) {
                    g_devices[i].destroy_device = (PFN_vkDestroyDevice)gdpa(dev, "vkDestroyDevice");
                    g_devices[i].get_device_queue = (PFN_vkGetDeviceQueue)gdpa(dev, "vkGetDeviceQueue");
                    g_devices[i].get_device_queue2 = (PFN_vkGetDeviceQueue2)gdpa(dev, "vkGetDeviceQueue2");
                    g_devices[i].alloc_mem = (PFN_vkAllocateMemory)gdpa(dev, "vkAllocateMemory");
                    g_devices[i].free_mem = (PFN_vkFreeMemory)gdpa(dev, "vkFreeMemory");
                    g_devices[i].map_mem = (PFN_vkMapMemory)gdpa(dev, "vkMapMemory");
                    g_devices[i].unmap_mem = (PFN_vkUnmapMemory)gdpa(dev, "vkUnmapMemory");
                    g_devices[i].queue_submit = (PFN_vkQueueSubmit)gdpa(dev, "vkQueueSubmit");
                    g_devices[i].queue_submit2 = (PFN_vkQueueSubmit2)gdpa(dev, "vkQueueSubmit2");
                    g_devices[i].queue_submit2_khr = (PFN_vkQueueSubmit2KHR)gdpa(dev, "vkQueueSubmit2KHR");
                    g_devices[i].queue_bind_sparse = (PFN_vkQueueBindSparse)gdpa(dev, "vkQueueBindSparse");
                    g_devices[i].wait_fences = (PFN_vkWaitForFences)gdpa(dev, "vkWaitForFences");
                    g_devices[i].get_fence_status = (PFN_vkGetFenceStatus)gdpa(dev, "vkGetFenceStatus");
                    g_devices[i].queue_wait_idle = (PFN_vkQueueWaitIdle)gdpa(dev, "vkQueueWaitIdle");
                    g_devices[i].device_wait_idle = (PFN_vkDeviceWaitIdle)gdpa(dev, "vkDeviceWaitIdle");
                    g_devices[i].wait_semaphores = (PFN_vkWaitSemaphores)gdpa(dev, "vkWaitSemaphores");
                    g_devices[i].wait_semaphores_khr = (PFN_vkWaitSemaphoresKHR)gdpa(dev, "vkWaitSemaphoresKHR");
                }
                break;
            }
        }
        pthread_mutex_unlock(&g_state_lock);
        trace_logf("vkCreateDevice ok dev=%p exts=%u", (void *)dev,
                pCreateInfo ? pCreateInfo->enabledExtensionCount : 0);
    }
    return res;
}

/* -- Wrapped Device Functions --------------------------------------------------------------------- */

static VKAPI_ATTR void VKAPI_CALL shim_vkDestroyDevice(
        VkDevice device,
        const VkAllocationCallbacks *pAllocator)
{
    PFN_vkDestroyDevice real_destroy = NULL;
    PFN_vkUnmapMemory real_unmap = NULL;
    PFN_vkFreeMemory real_free = NULL;
    sync_device_bounces(device, 0);

    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) {
        real_destroy = de->destroy_device;
        real_unmap = de->unmap_mem;
        real_free = de->free_mem;
    }
    pthread_mutex_unlock(&g_state_lock);

    if (__atomic_load_n(&g_bounce_active_count, __ATOMIC_ACQUIRE) > 0) {
        pthread_mutex_lock(&g_bounce_lock);
        int removed = 0;
        for (int i = 0; i < MAX_BOUNCE_ALLOCS; i++) {
            if (g_bounces[i].in_use && g_bounces[i].device == device) {
                if (real_unmap && g_bounces[i].region.shadow_ptr) {
                    real_unmap(device, g_bounces[i].memory);
                }
                if (real_free) {
                    real_free(device, g_bounces[i].memory, NULL);
                }
                free(g_bounces[i].region.prev_ptr);
                g_bounces[i].in_use = 0;
                removed++;
            }
        }
        if (removed > 0) {
            __atomic_sub_fetch(&g_bounce_active_count, removed, __ATOMIC_RELEASE);
        }
        pthread_mutex_unlock(&g_bounce_lock);
    }

    pthread_mutex_lock(&g_state_lock);
    de = find_device(device);
    for (int i = 0; i < MAX_QUEUES; i++) {
        if (g_queues[i].in_use && g_queues[i].device == device) {
            g_queues[i].in_use = 0;
        }
    }
    if (de) {
        pthread_mutex_destroy(&de->submit_lock);
        de->in_use = 0;
    }
    pthread_mutex_unlock(&g_state_lock);

    trace_logf("vkDestroyDevice dev=%p", (void *)device);
    if (real_destroy) {
        real_destroy(device, pAllocator);
    }
}

static VKAPI_ATTR void VKAPI_CALL shim_vkGetDeviceQueue(
        VkDevice device,
        uint32_t queueFamilyIndex,
        uint32_t queueIndex,
        VkQueue *pQueue)
{
    PFN_vkGetDeviceQueue real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) real_fn = de->get_device_queue;
    pthread_mutex_unlock(&g_state_lock);
    if (real_fn) {
        real_fn(device, queueFamilyIndex, queueIndex, pQueue);
        if (pQueue && *pQueue) record_queue(device, *pQueue);
    }
}

static VKAPI_ATTR void VKAPI_CALL shim_vkGetDeviceQueue2(
        VkDevice device,
        const VkDeviceQueueInfo2 *pQueueInfo,
        VkQueue *pQueue)
{
    PFN_vkGetDeviceQueue2 real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) real_fn = de->get_device_queue2;
    pthread_mutex_unlock(&g_state_lock);
    if (real_fn) {
        real_fn(device, pQueueInfo, pQueue);
        if (pQueue && *pQueue) record_queue(device, *pQueue);
    }
}

static VkResult perform_bounce_alloc(
        struct device_entry *de_snap,
        VkDevice device,
        const VkMemoryAllocateInfo *orig_info,
        const VkAllocationCallbacks *pAllocator,
        void *host_ptr,
        int host_writable,
        VkDeviceMemory *pMemory)
{
    size_t sz = (size_t)orig_info->allocationSize;
    uint8_t *prev = malloc(sz);
    if (!prev) {
        return VK_ERROR_OUT_OF_HOST_MEMORY;
    }
    memcpy(prev, host_ptr, sz);

    /* Rebuild pNext chain without VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT */
    enum { MAX_PNEXT = 16 };
    VkMemoryAllocateFlagsInfo flags_copy;
    const void *clean_pnext = NULL;
    const VkBaseInStructure *cur = (const VkBaseInStructure *)orig_info->pNext;
    int depth = 0;
    while (cur && depth++ < MAX_PNEXT) {
        if (cur->sType == VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_FLAGS_INFO) {
            flags_copy = *(const VkMemoryAllocateFlagsInfo *)cur;
            flags_copy.pNext = clean_pnext;
            clean_pnext = &flags_copy;
            break;
        }
        cur = cur->pNext;
    }

    VkMemoryAllocateInfo mod_info = *orig_info;
    mod_info.pNext = clean_pnext;
    int mtype = find_host_coherent_mem_type(&de_snap->mem_props, orig_info->memoryTypeIndex);
    mod_info.memoryTypeIndex = (uint32_t)mtype;

    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkResult res = de_snap->alloc_mem(device, &mod_info, pAllocator, &mem);
    if (res != VK_SUCCESS) {
        free(prev);
        return res;
    }

    void *mapped = NULL;
    res = de_snap->map_mem(device, mem, 0, orig_info->allocationSize, 0, &mapped);
    if (res != VK_SUCCESS || !mapped) {
        de_snap->free_mem(device, mem, pAllocator);
        free(prev);
        return (res != VK_SUCCESS) ? res : VK_ERROR_MEMORY_MAP_FAILED;
    }
    memcpy(mapped, host_ptr, sz);

    pthread_mutex_lock(&g_bounce_lock);
    int stored = 0;
    for (int i = 0; i < MAX_BOUNCE_ALLOCS; i++) {
        if (!g_bounces[i].in_use) {
            g_bounces[i].in_use = 1;
            g_bounces[i].device = device;
            g_bounces[i].memory = mem;
            g_bounces[i].region.host_ptr = (uint8_t *)host_ptr;
            g_bounces[i].region.shadow_ptr = (uint8_t *)mapped;
            g_bounces[i].region.prev_ptr = prev;
            g_bounces[i].region.size = sz;
            g_bounces[i].region.host_writable = host_writable;
            g_bounces[i].client_mapped = 0;
            __atomic_add_fetch(&g_bounce_active_count, 1, __ATOMIC_RELEASE);
            stored = 1;
            break;
        }
    }
    pthread_mutex_unlock(&g_bounce_lock);
    if (!stored) {
        de_snap->unmap_mem(device, mem);
        de_snap->free_mem(device, mem, pAllocator);
        free(prev);
        return VK_ERROR_TOO_MANY_OBJECTS;
    }

    *pMemory = mem;
    return VK_SUCCESS;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkAllocateMemory(
        VkDevice device,
        const VkMemoryAllocateInfo *pAllocateInfo,
        const VkAllocationCallbacks *pAllocator,
        VkDeviceMemory *pMemory)
{
    struct device_entry de_snap;
    memset(&de_snap, 0, sizeof(de_snap));
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) de_snap = *de;
    pthread_mutex_unlock(&g_state_lock);
    if (!de_snap.alloc_mem) return VK_ERROR_INITIALIZATION_FAILED;

    const VkImportMemoryHostPointerInfoEXT *host_info = NULL;
    if (pAllocateInfo) {
        const VkBaseInStructure *cur = (const VkBaseInStructure *)pAllocateInfo->pNext;
        while (cur) {
            if (cur->sType == VK_STRUCTURE_TYPE_IMPORT_MEMORY_HOST_POINTER_INFO_EXT) {
                host_info = (const VkImportMemoryHostPointerInfoEXT *)cur;
                break;
            }
            cur = cur->pNext;
        }
    }

    if (!host_info || !host_info->pHostPointer) {
        return de_snap.alloc_mem(device, pAllocateInfo, pAllocator, pMemory);
    }

    if (!g_cfg.multi_vma_bounce && !g_cfg.all_host_bounce && g_trace_path[0] == '\0') {
        return de_snap.alloc_mem(device, pAllocateInfo, pAllocator, pMemory);
    }

    struct asoft_vma_stats st;
    memset(&st, 0, sizeof(st));
    FILE *maps = fopen("/proc/self/maps", "re");
    if (maps) {
        (void)asoft_parse_proc_maps(maps, (uintptr_t)host_info->pHostPointer, (size_t)pAllocateInfo->allocationSize, &st);
        fclose(maps);
    }

    int want_bounce = asoft_should_bounce_host_ptr(
            &st, (size_t)pAllocateInfo->allocationSize, g_cfg.multi_vma_bounce, g_cfg.all_host_bounce);

    trace_logf("vkAllocHost ptr=%p size=%llu vmas=%zu mapped=%zu rw=%d/%d want_bounce=%d",
            host_info->pHostPointer, (unsigned long long)pAllocateInfo->allocationSize,
            st.vma_count, st.total_mapped, st.all_readable, st.all_writable, want_bounce);

    if (want_bounce && de_snap.map_mem && de_snap.unmap_mem && de_snap.free_mem) {
        VkResult bres = perform_bounce_alloc(
                &de_snap, device, pAllocateInfo, pAllocator,
                host_info->pHostPointer, st.all_writable, pMemory);
        trace_logf("vkAllocHost_bounce ptr=%p res=%d mem=%p",
                host_info->pHostPointer, bres, pMemory ? (void *)*pMemory : NULL);
        if (bres == VK_SUCCESS) {
            return VK_SUCCESS;
        }
    }

    VkResult res = de_snap.alloc_mem(device, pAllocateInfo, pAllocator, pMemory);
    if (res != VK_SUCCESS && (g_cfg.multi_vma_bounce || g_cfg.all_host_bounce)
            && st.all_readable && st.total_mapped >= (size_t)pAllocateInfo->allocationSize
            && de_snap.map_mem && de_snap.unmap_mem && de_snap.free_mem) {
        VkResult bres = perform_bounce_alloc(
                &de_snap, device, pAllocateInfo, pAllocator,
                host_info->pHostPointer, st.all_writable, pMemory);
        trace_logf("vkAllocHost_fallback_bounce orig_res=%d bounce_res=%d ptr=%p",
                res, bres, host_info->pHostPointer);
        if (bres == VK_SUCCESS) {
            return VK_SUCCESS;
        }
    }
    return res;
}

static VKAPI_ATTR void VKAPI_CALL shim_vkFreeMemory(
        VkDevice device,
        VkDeviceMemory memory,
        const VkAllocationCallbacks *pAllocator)
{
    PFN_vkFreeMemory real_free = NULL;
    PFN_vkUnmapMemory real_unmap = NULL;
    uint8_t *prev_to_free = NULL;
    int was_bounced = 0;

    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) {
        real_free = de->free_mem;
        real_unmap = de->unmap_mem;
    }
    pthread_mutex_unlock(&g_state_lock);

    if (__atomic_load_n(&g_bounce_active_count, __ATOMIC_ACQUIRE) > 0) {
        pthread_mutex_lock(&g_bounce_lock);
        struct bounce_alloc *ba = find_bounce_locked(memory);
        if (ba) {
            (void)asoft_bounce_sync_to_host(&ba->region);
            prev_to_free = ba->region.prev_ptr;
            ba->in_use = 0;
            __atomic_sub_fetch(&g_bounce_active_count, 1, __ATOMIC_RELEASE);
            was_bounced = 1;
        }
        pthread_mutex_unlock(&g_bounce_lock);
    }

    if (was_bounced && real_unmap) {
        real_unmap(device, memory);
    }
    free(prev_to_free);
    if (real_free) {
        real_free(device, memory, pAllocator);
    }
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkMapMemory(
        VkDevice device,
        VkDeviceMemory memory,
        VkDeviceSize offset,
        VkDeviceSize size,
        VkMemoryMapFlags flags,
        void **ppData)
{
    (void)size;
    if (__atomic_load_n(&g_bounce_active_count, __ATOMIC_ACQUIRE) > 0) {
        pthread_mutex_lock(&g_bounce_lock);
        struct bounce_alloc *ba = find_bounce_locked(memory);
        if (ba) {
            ba->client_mapped = 1;
            if (ppData) {
                *ppData = ba->region.host_ptr + offset;
            }
            pthread_mutex_unlock(&g_bounce_lock);
            return VK_SUCCESS;
        }
        pthread_mutex_unlock(&g_bounce_lock);
    }
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    PFN_vkMapMemory real_map = de ? de->map_mem : NULL;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_map) return VK_ERROR_INITIALIZATION_FAILED;
    return real_map(device, memory, offset, size, flags, ppData);
}

static VKAPI_ATTR void VKAPI_CALL shim_vkUnmapMemory(
        VkDevice device,
        VkDeviceMemory memory)
{
    if (__atomic_load_n(&g_bounce_active_count, __ATOMIC_ACQUIRE) > 0) {
        pthread_mutex_lock(&g_bounce_lock);
        struct bounce_alloc *ba = find_bounce_locked(memory);
        if (ba) {
            ba->client_mapped = 0;
            (void)asoft_bounce_sync_to_shadow(&ba->region);
            pthread_mutex_unlock(&g_bounce_lock);
            return;
        }
        pthread_mutex_unlock(&g_bounce_lock);
    }
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    PFN_vkUnmapMemory real_unmap = de ? de->unmap_mem : NULL;
    pthread_mutex_unlock(&g_state_lock);
    if (real_unmap) real_unmap(device, memory);
}

static uint64_t g_last_poll_fence_val = 0;
static uint64_t g_last_poll_fence_gen = 0;

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkQueueSubmit(
        VkQueue queue,
        uint32_t submitCount,
        const VkSubmitInfo *pSubmits,
        VkFence fence)
{
    PFN_vkQueueSubmit real_fn = NULL;
    PFN_vkQueueWaitIdle real_wait_idle = NULL;
    VkDevice dev = VK_NULL_HANDLE;
    pthread_mutex_t *slock = NULL;

    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device_for_queue(queue);
    if (de) {
        dev = de->device;
        real_fn = de->queue_submit;
        real_wait_idle = de->queue_wait_idle;
        slock = &de->submit_lock;
    }
    pthread_mutex_unlock(&g_state_lock);

    if (!real_fn) return VK_ERROR_DEVICE_LOST;

    uint32_t total_signal_sems = 0;
    if (pSubmits) {
        for (uint32_t i = 0; i < submitCount; i++) {
            total_signal_sems += pSubmits[i].signalSemaphoreCount;
        }
    }

    if (slock && g_cfg.submit_throttle) {
        pthread_mutex_lock(slock);
    }
    sync_device_bounces(dev, 1);
    if (g_cfg.cache_sync) {
        sync_kgsl_imported_bos(KGSL_GPUMEM_CACHE_CLEAN);
    }

    uint64_t seq = __atomic_add_fetch(&g_vk_submit_seq, 1, __ATOMIC_RELAXED);
    __atomic_add_fetch(&g_gpu_work_gen, 1, __ATOMIC_RELEASE);
    trace_logf("vkQueueSubmit_pre seq=%llu count=%u fence=%p", (unsigned long long)seq, submitCount, (void *)fence);
    t_in_vk_submit = 1;
    VkResult res = real_fn(queue, submitCount, pSubmits, fence);
    t_in_vk_submit = 0;
    if (res == VK_SUCCESS && g_cfg.submit_throttle && real_wait_idle
            && asoft_submit_needs_throttle_drain(fence != VK_NULL_HANDLE, total_signal_sems)) {
        /* Only drain truly unsynchronized submits (no fence and no signal semaphores) */
        (void)real_wait_idle(queue);
        sync_device_bounces(dev, 0);
    }
    if (res != VK_SUCCESS) {
        trace_logf("vkQueueSubmit_err seq=%llu res=%d", (unsigned long long)seq, res);
    }
    if (slock && g_cfg.submit_throttle) {
        pthread_mutex_unlock(slock);
    }
    return res;
}

static VkResult submit2_common(
        VkQueue queue,
        uint32_t submitCount,
        const VkSubmitInfo2 *pSubmits,
        VkFence fence,
        int is_khr)
{
    PFN_vkQueueSubmit2 real_fn = NULL;
    PFN_vkQueueWaitIdle real_wait_idle = NULL;
    VkDevice dev = VK_NULL_HANDLE;
    pthread_mutex_t *slock = NULL;

    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device_for_queue(queue);
    if (de) {
        dev = de->device;
        real_fn = is_khr ? de->queue_submit2_khr : de->queue_submit2;
        real_wait_idle = de->queue_wait_idle;
        slock = &de->submit_lock;
    }
    pthread_mutex_unlock(&g_state_lock);

    if (!real_fn) return VK_ERROR_DEVICE_LOST;

    uint32_t total_signal_sems = 0;
    if (pSubmits) {
        for (uint32_t i = 0; i < submitCount; i++) {
            total_signal_sems += pSubmits[i].signalSemaphoreInfoCount;
        }
    }

    if (slock && g_cfg.submit_throttle) {
        pthread_mutex_lock(slock);
    }
    sync_device_bounces(dev, 1);
    if (g_cfg.cache_sync) {
        sync_kgsl_imported_bos(KGSL_GPUMEM_CACHE_CLEAN);
    }
    uint64_t seq = __atomic_add_fetch(&g_vk_submit_seq, 1, __ATOMIC_RELAXED);
    __atomic_add_fetch(&g_gpu_work_gen, 1, __ATOMIC_RELEASE);
    trace_logf("vkQueueSubmit2_pre seq=%llu count=%u fence=%p", (unsigned long long)seq, submitCount, (void *)fence);
    t_in_vk_submit = 1;
    VkResult res = real_fn(queue, submitCount, pSubmits, fence);
    t_in_vk_submit = 0;
    if (res == VK_SUCCESS && g_cfg.submit_throttle && real_wait_idle
            && asoft_submit_needs_throttle_drain(fence != VK_NULL_HANDLE, total_signal_sems)) {
        (void)real_wait_idle(queue);
        sync_device_bounces(dev, 0);
    }
    if (res != VK_SUCCESS) {
        trace_logf("vkQueueSubmit2_err seq=%llu res=%d", (unsigned long long)seq, res);
    }
    if (slock && g_cfg.submit_throttle) {
        pthread_mutex_unlock(slock);
    }
    return res;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkQueueSubmit2(
        VkQueue queue, uint32_t submitCount, const VkSubmitInfo2 *pSubmits, VkFence fence)
{
    return submit2_common(queue, submitCount, pSubmits, fence, 0);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkQueueSubmit2KHR(
        VkQueue queue, uint32_t submitCount, const VkSubmitInfo2 *pSubmits, VkFence fence)
{
    return submit2_common(queue, submitCount, pSubmits, fence, 1);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkQueueBindSparse(
        VkQueue queue,
        uint32_t bindInfoCount,
        const VkBindSparseInfo *pBindInfo,
        VkFence fence)
{
    trace_logf("vkQueueBindSparse count=%u guarded=%d", bindInfoCount, g_cfg.feature_guard);
    if (g_cfg.feature_guard) {
        return VK_ERROR_FEATURE_NOT_PRESENT;
    }
    PFN_vkQueueBindSparse real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device_for_queue(queue);
    if (de) real_fn = de->queue_bind_sparse;
    pthread_mutex_unlock(&g_state_lock);
    return real_fn ? real_fn(queue, bindInfoCount, pBindInfo, fence) : VK_ERROR_FEATURE_NOT_PRESENT;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkWaitForFences(
        VkDevice device, uint32_t fenceCount, const VkFence *pFences, VkBool32 waitAll, uint64_t timeout)
{
    PFN_vkWaitForFences real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) real_fn = de->wait_fences;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) return VK_ERROR_DEVICE_LOST;
    VkResult res = real_fn(device, fenceCount, pFences, waitAll, timeout);
    if (res == VK_SUCCESS) {
        if (g_cfg.cache_sync) sync_kgsl_imported_bos(KGSL_GPUMEM_CACHE_FROM_GPU);
        sync_device_bounces(device, 0);
    }
    return res;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkGetFenceStatus(VkDevice device, VkFence fence)
{
    PFN_vkGetFenceStatus real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) real_fn = de->get_fence_status;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) return VK_ERROR_DEVICE_LOST;
    VkResult res = real_fn(device, fence);
    if (res == VK_SUCCESS) {
        uint64_t fval = (uint64_t)(uintptr_t)fence;
        uint64_t gen = __atomic_load_n(&g_gpu_work_gen, __ATOMIC_ACQUIRE);
        if (fval != __atomic_load_n(&g_last_poll_fence_val, __ATOMIC_ACQUIRE)
                || gen != __atomic_load_n(&g_last_poll_fence_gen, __ATOMIC_ACQUIRE)) {
            if (g_cfg.cache_sync) sync_kgsl_imported_bos(KGSL_GPUMEM_CACHE_FROM_GPU);
            sync_device_bounces(device, 0);
            __atomic_store_n(&g_last_poll_fence_val, fval, __ATOMIC_RELEASE);
            __atomic_store_n(&g_last_poll_fence_gen, gen, __ATOMIC_RELEASE);
        }
    }
    return res;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkQueueWaitIdle(VkQueue queue)
{
    PFN_vkQueueWaitIdle real_fn = NULL;
    VkDevice dev = VK_NULL_HANDLE;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device_for_queue(queue);
    if (de) {
        dev = de->device;
        real_fn = de->queue_wait_idle;
    }
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) return VK_ERROR_DEVICE_LOST;
    VkResult res = real_fn(queue);
    if (res == VK_SUCCESS) {
        if (g_cfg.cache_sync) sync_kgsl_imported_bos(KGSL_GPUMEM_CACHE_FROM_GPU);
        sync_device_bounces(dev, 0);
    }
    return res;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkDeviceWaitIdle(VkDevice device)
{
    PFN_vkDeviceWaitIdle real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) real_fn = de->device_wait_idle;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) return VK_ERROR_DEVICE_LOST;
    VkResult res = real_fn(device);
    if (res == VK_SUCCESS) {
        if (g_cfg.cache_sync) sync_kgsl_imported_bos(KGSL_GPUMEM_CACHE_FROM_GPU);
        sync_device_bounces(device, 0);
    }
    return res;
}

static VkResult wait_semaphores_common(VkDevice device, const VkSemaphoreWaitInfo *pWaitInfo, uint64_t timeout, int is_khr)
{
    PFN_vkWaitSemaphores real_fn = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) real_fn = is_khr ? de->wait_semaphores_khr : de->wait_semaphores;
    pthread_mutex_unlock(&g_state_lock);
    if (!real_fn) return VK_ERROR_DEVICE_LOST;
    VkResult res = real_fn(device, pWaitInfo, timeout);
    if (res == VK_SUCCESS) {
        if (g_cfg.cache_sync) sync_kgsl_imported_bos(KGSL_GPUMEM_CACHE_FROM_GPU);
        sync_device_bounces(device, 0);
    }
    return res;
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkWaitSemaphores(
        VkDevice device, const VkSemaphoreWaitInfo *pWaitInfo, uint64_t timeout)
{
    return wait_semaphores_common(device, pWaitInfo, timeout, 0);
}

static VKAPI_ATTR VkResult VKAPI_CALL shim_vkWaitSemaphoresKHR(
        VkDevice device, const VkSemaphoreWaitInfo *pWaitInfo, uint64_t timeout)
{
    return wait_semaphores_common(device, pWaitInfo, timeout, 1);
}

static PFN_vkVoidFunction lookup_device_intercept(const char *pName)
{
    if (!pName) return NULL;
    if (strcmp(pName, "vkDestroyDevice") == 0) return (PFN_vkVoidFunction)shim_vkDestroyDevice;
    if (strcmp(pName, "vkGetDeviceQueue") == 0) return (PFN_vkVoidFunction)shim_vkGetDeviceQueue;
    if (strcmp(pName, "vkGetDeviceQueue2") == 0) return (PFN_vkVoidFunction)shim_vkGetDeviceQueue2;
    if (strcmp(pName, "vkGetMemoryHostPointerPropertiesEXT") == 0) return (PFN_vkVoidFunction)shim_vkGetMemoryHostPointerPropertiesEXT;
    if (strcmp(pName, "vkAllocateMemory") == 0) return (PFN_vkVoidFunction)shim_vkAllocateMemory;
    if (strcmp(pName, "vkFreeMemory") == 0) return (PFN_vkVoidFunction)shim_vkFreeMemory;
    if (strcmp(pName, "vkMapMemory") == 0) return (PFN_vkVoidFunction)shim_vkMapMemory;
    if (strcmp(pName, "vkUnmapMemory") == 0) return (PFN_vkVoidFunction)shim_vkUnmapMemory;
    if (strcmp(pName, "vkQueueSubmit") == 0) return (PFN_vkVoidFunction)shim_vkQueueSubmit;
    if (strcmp(pName, "vkQueueSubmit2") == 0) return (PFN_vkVoidFunction)shim_vkQueueSubmit2;
    if (strcmp(pName, "vkQueueSubmit2KHR") == 0) return (PFN_vkVoidFunction)shim_vkQueueSubmit2KHR;
    if (strcmp(pName, "vkQueueBindSparse") == 0) return (PFN_vkVoidFunction)shim_vkQueueBindSparse;
    if (strcmp(pName, "vkWaitForFences") == 0) return (PFN_vkVoidFunction)shim_vkWaitForFences;
    if (strcmp(pName, "vkGetFenceStatus") == 0) return (PFN_vkVoidFunction)shim_vkGetFenceStatus;
    if (strcmp(pName, "vkQueueWaitIdle") == 0) return (PFN_vkVoidFunction)shim_vkQueueWaitIdle;
    if (strcmp(pName, "vkDeviceWaitIdle") == 0) return (PFN_vkVoidFunction)shim_vkDeviceWaitIdle;
    if (strcmp(pName, "vkWaitSemaphores") == 0) return (PFN_vkVoidFunction)shim_vkWaitSemaphores;
    if (strcmp(pName, "vkWaitSemaphoresKHR") == 0) return (PFN_vkVoidFunction)shim_vkWaitSemaphoresKHR;
    return NULL;
}

static VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL shim_vkGetDeviceProcAddr(VkDevice device, const char *pName)
{
    if (!pName) return NULL;
    if (strcmp(pName, "vkGetDeviceProcAddr") == 0) {
        return (PFN_vkVoidFunction)shim_vkGetDeviceProcAddr;
    }
    PFN_vkGetDeviceProcAddr real_gdpa = NULL;
    pthread_mutex_lock(&g_state_lock);
    struct device_entry *de = find_device(device);
    if (de) real_gdpa = de->get_device_proc_addr;
    pthread_mutex_unlock(&g_state_lock);

    PFN_vkVoidFunction real_fn = real_gdpa ? real_gdpa(device, pName) : NULL;
    if (!real_fn) {
        return NULL;
    }
    PFN_vkVoidFunction hook = lookup_device_intercept(pName);
    return hook ? hook : real_fn;
}

/* -- Exported Vulkan ICD Entrypoints -------------------------------------------------------------- */

__attribute__((visibility("default")))
VKAPI_ATTR VkResult VKAPI_CALL vk_icdNegotiateLoaderICDInterfaceVersion(uint32_t *pVersion)
{
    if (ensure_turnip_loaded() != 0 || !g_turnip_negotiate) {
        return VK_ERROR_INCOMPATIBLE_DRIVER;
    }
    return g_turnip_negotiate(pVersion);
}

__attribute__((visibility("default")))
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetPhysicalDeviceProcAddr(VkInstance instance, const char *pName)
{
    if (ensure_turnip_loaded() != 0 || !pName) {
        return NULL;
    }
    if (strcmp(pName, "vkGetPhysicalDeviceFeatures") == 0) return (PFN_vkVoidFunction)shim_vkGetPhysicalDeviceFeatures;
    if (strcmp(pName, "vkGetPhysicalDeviceFeatures2") == 0) return (PFN_vkVoidFunction)shim_vkGetPhysicalDeviceFeatures2;
    if (strcmp(pName, "vkGetPhysicalDeviceFeatures2KHR") == 0) return (PFN_vkVoidFunction)shim_vkGetPhysicalDeviceFeatures2;
    if (strcmp(pName, "vkEnumerateDeviceExtensionProperties") == 0) return (PFN_vkVoidFunction)shim_vkEnumerateDeviceExtensionProperties;
    if (strcmp(pName, "vkCreateDevice") == 0) return (PFN_vkVoidFunction)shim_vkCreateDevice;
    return g_turnip_gpdpa ? g_turnip_gpdpa(instance, pName) : NULL;
}

__attribute__((visibility("default")))
VKAPI_ATTR PFN_vkVoidFunction VKAPI_CALL vk_icdGetInstanceProcAddr(VkInstance instance, const char *pName)
{
    if (ensure_turnip_loaded() != 0 || !pName) {
        return NULL;
    }
    if (strcmp(pName, "vk_icdGetInstanceProcAddr") == 0) return (PFN_vkVoidFunction)vk_icdGetInstanceProcAddr;
    if (strcmp(pName, "vk_icdNegotiateLoaderICDInterfaceVersion") == 0) return (PFN_vkVoidFunction)vk_icdNegotiateLoaderICDInterfaceVersion;
    if (strcmp(pName, "vk_icdGetPhysicalDeviceProcAddr") == 0) return (PFN_vkVoidFunction)vk_icdGetPhysicalDeviceProcAddr;
    if (strcmp(pName, "vkGetInstanceProcAddr") == 0) return (PFN_vkVoidFunction)vk_icdGetInstanceProcAddr;
    if (strcmp(pName, "vkCreateInstance") == 0) return (PFN_vkVoidFunction)shim_vkCreateInstance;
    if (strcmp(pName, "vkDestroyInstance") == 0) return (PFN_vkVoidFunction)shim_vkDestroyInstance;
    if (strcmp(pName, "vkEnumeratePhysicalDevices") == 0) return (PFN_vkVoidFunction)shim_vkEnumeratePhysicalDevices;
    if (strcmp(pName, "vkGetPhysicalDeviceFeatures") == 0) return (PFN_vkVoidFunction)shim_vkGetPhysicalDeviceFeatures;
    if (strcmp(pName, "vkGetPhysicalDeviceFeatures2") == 0) return (PFN_vkVoidFunction)shim_vkGetPhysicalDeviceFeatures2;
    if (strcmp(pName, "vkGetPhysicalDeviceFeatures2KHR") == 0) return (PFN_vkVoidFunction)shim_vkGetPhysicalDeviceFeatures2;
    if (strcmp(pName, "vkEnumerateDeviceExtensionProperties") == 0) return (PFN_vkVoidFunction)shim_vkEnumerateDeviceExtensionProperties;
    if (strcmp(pName, "vkCreateDevice") == 0) return (PFN_vkVoidFunction)shim_vkCreateDevice;
    if (strcmp(pName, "vkGetDeviceProcAddr") == 0) return (PFN_vkVoidFunction)shim_vkGetDeviceProcAddr;

    PFN_vkVoidFunction real_fn = g_turnip_gipa(instance, pName);
    if (!real_fn) {
        return NULL;
    }
    PFN_vkVoidFunction dev_hook = lookup_device_intercept(pName);
    return dev_hook ? dev_hook : real_fn;
}

#endif /* !ASOFT_SHIM_HOST_TEST */
