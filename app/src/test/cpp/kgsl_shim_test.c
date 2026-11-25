#define _GNU_SOURCE
#include "../../main/cpp/kgsl_shim.h"

#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static void test_proc_maps_single_and_multi_vma(void)
{
    const char *sample =
            "7000000000-7000010000 rw-s 00000000 00:01 123 /memfd:guest-ram\n"
            "7000010000-7000020000 rw-s 00010000 00:01 124 /memfd:iosurface\n"
            "7000020000-7000030000 r--p 00000000 00:00 0 [anon:ro]\n";

    FILE *fp = fmemopen((void *)sample, strlen(sample), "r");
    assert(fp != NULL);

    struct asoft_vma_stats st;
    assert(asoft_parse_proc_maps(fp, 0x7000000000ULL, 0x10000, &st) == 0);
    fclose(fp);
    assert(st.vma_count == 1);
    assert(st.total_mapped == 0x10000);
    assert(st.all_readable == 1);
    assert(st.all_writable == 1);
    assert(st.any_shared == 1);
    assert(asoft_should_bounce_host_ptr(&st, 0x10000, 1, 0) == 0);
    assert(asoft_should_bounce_host_ptr(&st, 0x10000, 1, 1) == 1);

    /* Range straddling 2 VMAs */
    fp = fmemopen((void *)sample, strlen(sample), "r");
    assert(fp != NULL);
    assert(asoft_parse_proc_maps(fp, 0x7000008000ULL, 0x10000, &st) == 0);
    fclose(fp);
    assert(st.vma_count == 2);
    assert(st.total_mapped == 0x10000);
    assert(st.all_readable == 1);
    assert(st.all_writable == 1);
    assert(asoft_should_bounce_host_ptr(&st, 0x10000, 1, 0) == 1);
    assert(asoft_should_bounce_host_ptr(&st, 0x10000, 0, 0) == 0);

    /* Range with a read-only VMA */
    fp = fmemopen((void *)sample, strlen(sample), "r");
    assert(fp != NULL);
    assert(asoft_parse_proc_maps(fp, 0x7000018000ULL, 0x10000, &st) == 0);
    fclose(fp);
    assert(st.vma_count == 2);
    assert(st.all_readable == 1);
    assert(st.all_writable == 0);

    /* Unmapped hole -> must not attempt bounce memcpy */
    fp = fmemopen((void *)sample, strlen(sample), "r");
    assert(fp != NULL);
    assert(asoft_parse_proc_maps(fp, 0x7000028000ULL, 0x10000, &st) == 0);
    fclose(fp);
    assert(st.total_mapped == 0x8000);
    assert(st.all_readable == 0);
    assert(asoft_should_bounce_host_ptr(&st, 0x10000, 1, 1) == 0);
}

static void test_three_way_page_diff_bounce(void)
{
    const size_t sz = 2 * ASOFT_PAGE_SIZE + 512; /* includes trailing partial page */
    uint8_t *host = calloc(1, sz);
    uint8_t *shadow = calloc(1, sz);
    uint8_t *prev = calloc(1, sz);
    assert(host && shadow && prev);

    for (size_t i = 0; i < sz; i++) {
        host[i] = (uint8_t)(i & 0xff);
    }
    memcpy(shadow, host, sz);
    memcpy(prev, host, sz);

    struct asoft_bounce_region reg = {
        .host_ptr = host,
        .shadow_ptr = shadow,
        .prev_ptr = prev,
        .size = sz,
        .host_writable = 1
    };

    /* 1. CPU modifies page 0 and trailing partial page -> only those sync to shadow */
    host[10] = 0xAA;
    host[2 * ASOFT_PAGE_SIZE + 100] = 0xBB;
    size_t up = asoft_bounce_sync_to_shadow(&reg);
    assert(up == ASOFT_PAGE_SIZE + 512);
    assert(shadow[10] == 0xAA);
    assert(shadow[2 * ASOFT_PAGE_SIZE + 100] == 0xBB);

    /* Second call with no changes syncs 0 bytes */
    assert(asoft_bounce_sync_to_shadow(&reg) == 0);

    /* 2. GPU modifies page 1 while CPU concurrently modifies a DIFFERENT offset in page 1 */
    shadow[ASOFT_PAGE_SIZE + 32] = 0xCC;
    host[ASOFT_PAGE_SIZE + 64] = 0xDD;
    size_t down = asoft_bounce_sync_to_host(&reg);
    assert(down == 1);
    assert(host[ASOFT_PAGE_SIZE + 32] == 0xCC);
    assert(host[ASOFT_PAGE_SIZE + 64] == 0xDD); /* CPU write preserved! */

    /* Read-only host protection avoids touching host */
    reg.host_writable = 0;
    shadow[5] = 0xEE;
    assert(asoft_bounce_sync_to_host(&reg) == 0);
    assert(host[5] != 0xEE);

    free(host);
    free(shadow);
    free(prev);
}

static void test_guarded_extensions(void)
{
    assert(asoft_is_guarded_extension("VK_EXT_host_image_copy") == 1);
    assert(asoft_is_guarded_extension("VK_EXT_device_generated_commands") == 1);
    assert(asoft_is_guarded_extension("VK_EXT_external_memory_host") == 0);
    assert(asoft_is_guarded_extension(NULL) == 0);
}

static void test_trace_rotation_and_sync_write(void)
{
    char dir_tmpl[] = "/tmp/asoft-trace-XXXXXX";
    char *dir = mkdtemp(dir_tmpl);
    assert(dir != NULL);

    char path[512], prev[520];
    snprintf(path, sizeof(path), "%s/kgsl-trace.txt", dir);
    snprintf(prev, sizeof(prev), "%s.prev", path);

    for (int i = 0; i < 6; i++) {
        char line[128];
        snprintf(line, sizeof(line), "line-%d-payload-0123456789abcdef", i);
        assert(asoft_trace_write(path, line, 100, 1) == 0);
    }

    struct stat st_cur, st_prev;
    assert(stat(path, &st_cur) == 0);
    assert(stat(prev, &st_prev) == 0);
    assert(st_cur.st_size > 0);
    assert(st_prev.st_size >= 100);

    unlink(path);
    unlink(prev);
    rmdir(dir);
}

static void test_host_to_shadow_preserves_concurrent_gpu_writes(void)
{
    const size_t sz = 4096;
    uint8_t *host = calloc(1, sz);
    uint8_t *shadow = calloc(1, sz);
    uint8_t *prev = calloc(1, sz);
    assert(host && shadow && prev);

    memset(host, 0x11, sz);
    memcpy(shadow, host, sz);
    memcpy(prev, host, sz);

    struct asoft_bounce_region reg = {
        .host_ptr = host,
        .shadow_ptr = shadow,
        .prev_ptr = prev,
        .size = sz,
        .host_writable = 1,
    };

    /* GPU writes at offset 64, CPU concurrently writes at offset 128 on the same 4 KiB page */
    shadow[64] = 0xAA;
    host[128] = 0xBB;

    /* Host -> Shadow must copy CPU's byte (128) without clobbering GPU's byte (64) */
    size_t synced = asoft_bounce_sync_to_shadow(&reg);
    assert(synced == 1);
    assert(shadow[128] == 0xBB);
    assert(shadow[64] == 0xAA);

    /* Shadow -> Host then merges GPU's byte (64) back without clobbering CPU's byte (128) */
    size_t written = asoft_bounce_sync_to_host(&reg);
    assert(written > 0);
    assert(host[64] == 0xAA);
    assert(host[128] == 0xBB);

    free(host);
    free(shadow);
    free(prev);
}

static void test_proc_maps_early_break(void)
{
    const char *sample =
            "7000000000-7000010000 rw-s 00000000 00:01 123 /memfd:guest-ram\n"
            "7000020000-7000030000 rw-s 00000000 00:01 124 /memfd:later\n"
            "7000040000-7000050000 rw-s 00000000 00:01 125 /memfd:never-read\n";
    FILE *fp = fmemopen((void *)sample, strlen(sample), "r");
    assert(fp != NULL);
    struct asoft_vma_stats st;
    assert(asoft_parse_proc_maps(fp, 0x7000000000ULL, 0x10000, &st) == 0);
    assert(st.vma_count == 1);
    assert(st.total_mapped == 0x10000);
    /* Must have stopped after seeing 7000020000 >= end without consuming the 3rd line */
    char rem[128] = {0};
    assert(fgets(rem, sizeof(rem), fp) != NULL);
    assert(strstr(rem, "never-read") != NULL);
    fclose(fp);
}

static void test_bounce_mem_type_prefers_cached(void)
{
    const uint32_t VIS = 1u << 0;
    const uint32_t COH = 1u << 1;
    const uint32_t CACHED = 1u << 2;
    /* Index 0 = Device-local, Index 1 = WC (VIS|COH, preferred), Index 2 = Cached (VIS|COH|CACHED) */
    uint32_t flags[] = { 0, VIS | COH, VIS | COH | CACHED };
    assert(asoft_select_bounce_mem_type(flags, 3, 1, VIS, COH, CACHED) == 2);

    /* When no cached heap exists, falls back to preferred WC coherent index */
    uint32_t wc_only[] = { 0, VIS | COH };
    assert(asoft_select_bounce_mem_type(wc_only, 2, 1, VIS, COH, CACHED) == 1);
}

static void test_submit_throttle_semaphore_check(void)
{
    /* Fence present -> never drain */
    assert(asoft_submit_needs_throttle_drain(1, 0) == 0);
    assert(asoft_submit_needs_throttle_drain(1, 2) == 0);
    /* Timeline/binary semaphore present (fence == NULL) -> do NOT stall with vkQueueWaitIdle */
    assert(asoft_submit_needs_throttle_drain(0, 1) == 0);
    /* Truly unsynchronized submit -> drain */
    assert(asoft_submit_needs_throttle_drain(0, 0) == 1);
}

int main(void)
{
    test_proc_maps_single_and_multi_vma();
    test_proc_maps_early_break();
    test_three_way_page_diff_bounce();
    test_host_to_shadow_preserves_concurrent_gpu_writes();
    test_bounce_mem_type_prefers_cached();
    test_submit_throttle_semaphore_check();
    test_guarded_extensions();
    test_trace_rotation_and_sync_write();
    printf("kgsl_shim_test: all assertions passed\n");
    return 0;
}
