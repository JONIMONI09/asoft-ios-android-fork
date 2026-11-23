#ifndef ASOFT_KGSL_SHIM_H
#define ASOFT_KGSL_SHIM_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

#define ASOFT_PAGE_SIZE 4096ULL
#define ASOFT_TRACE_MAX_BYTES (2ULL * 1024ULL * 1024ULL)

struct asoft_vma_stats {
    size_t vma_count;
    size_t total_mapped;
    int all_readable;
    int all_writable;
    int any_shared;
};

struct asoft_bounce_region {
    uint8_t *host_ptr;
    uint8_t *shadow_ptr;
    uint8_t *prev_ptr;
    size_t size;
    int host_writable;
};

struct asoft_shim_config {
    int multi_vma_bounce;
    int all_host_bounce;
    int cache_sync;
    int feature_guard;
    int submit_throttle;
    int sync_serial;
    int sync_log;
    int verbose;
};

void asoft_load_config_from_env(struct asoft_shim_config *cfg);

/* Parses a Linux /proc/<pid>/maps stream for [start, start + size). Returns 0 on success, -1 on error. */
int asoft_parse_proc_maps(FILE *fp, uintptr_t start, size_t size, struct asoft_vma_stats *out);

/* Decides whether a VkImportMemoryHostPointerInfoEXT allocation should be bounced to normal coherent memory. */
int asoft_should_bounce_host_ptr(
        const struct asoft_vma_stats *st,
        size_t size,
        int multi_vma_bounce,
        int all_host_bounce);

/*
 * 3-way 4 KiB page-diff sync: copies pages where host differs from prev snapshot into shadow and updates prev.
 * Returns number of bytes copied, or 0 if pointers/sizes are invalid.
 */
size_t asoft_bounce_sync_to_shadow(struct asoft_bounce_region *reg);

/*
 * 3-way 4 KiB page-diff sync back to host (only when host_writable != 0).
 * Pages modified by both GPU (shadow != prev) and CPU (host != prev) undergo byte-level 3-way merge
 * so concurrent CPU writes inside the same 4 KiB page are never clobbered.
 * Returns number of GPU-modified bytes written back to host.
 */
size_t asoft_bounce_sync_to_host(struct asoft_bounce_region *reg);

/* Checks whether a device extension name should be masked under feature_guard. */
int asoft_is_guarded_extension(const char *ext_name);

/*
 * Appends a single trace line to path, rotating path -> "<path>.prev" once max_bytes is reached.
 * When use_osync != 0, opens with O_SYNC (falling back without O_SYNC if the filesystem rejects it).
 * Returns 0 on success, -1 on error.
 */
int asoft_trace_write(const char *path, const char *line, size_t max_bytes, int use_osync);

#ifdef __cplusplus
}
#endif

#endif /* ASOFT_KGSL_SHIM_H */
