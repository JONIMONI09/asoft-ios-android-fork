package com.asoft.ios;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * User-configurable QEMU, CPU, disk, display, and touch performance tuning for iOS/watchOS.
 * All defaults match stock behavior verbatim; explicit test files (cpu.txt, ios26.txt) still
 * take precedence when present. Persisted with synchronous {@code commit()} for reboot safety.
 */
final class PerfConfig {
    private static final String PREFS = "perf_config";

    static final int TOUCH_BALANCED = 0;      // 16 ms / 66 ms (stock)
    static final int TOUCH_RESPONSIVE = 1;    // 8 ms / 33 ms (snappier swipes)
    static final int TOUCH_LOW_OVERHEAD = 2;  // 33 ms / 100 ms (minimum QMP traffic)

    /** Live-updated touch pacing read by {@link Qmp#fingerLoop()} without SharedPreferences I/O. */
    static volatile long liveTouchMinGapMs = 16;
    static volatile long liveTouchMaxGapMs = 66;
    /** Live-updated host render frame cap (0 = native display Hz, else 60 or 30). */
    static volatile int liveFrameCapFps = 0;

    /** 0 = Auto (4 vCPUs); else 2, 4, 6, or 8. Takes effect on next cold boot. */
    int vcpus;
    /** Pin vCPU threads and Native-EL0 stubs to fast cores (cpu_capacity >= 60% of best). Next start. */
    boolean pinFastCores;
    /** QEMU TCG translation block cache in MiB (512, 768, 1024, 1536). Next start. */
    int tbSizeMiB;
    /** Run virtio-blk-pci on QEMU's dedicated disk iothread off the BQL. Next start. */
    boolean diskIoThread;
    /** Use cache=writeback instead of cache=none (O_DIRECT) for faster burst disk I/O (more RAM). Next start. */
    boolean diskWriteback;
    /** Native-EL0 stub pool size for iOS 26 (24, 32, 48). Next start. */
    int el0Stubs;
    /** Host display frame poll cap in ScreenActivity: 0 = Auto (native Hz), 60, or 30. Live. */
    int frameCapFps;
    /** Touch move pacing in Qmp.fingerLoop: TOUCH_BALANCED, TOUCH_RESPONSIVE, TOUCH_LOW_OVERHEAD. Live. */
    int touchPacing;

    PerfConfig() {
        this.vcpus = 0;
        this.pinFastCores = false;
        this.tbSizeMiB = 768;
        this.diskIoThread = false;
        this.diskWriteback = false;
        this.el0Stubs = 24;
        this.frameCapFps = 0;
        this.touchPacing = TOUCH_BALANCED;
    }

    static PerfConfig stockDefaults() {
        return new PerfConfig();
    }

    /** Balanced fast preset: fast-core pinning, 1024 MiB JIT cache, disk iothread, 32 stubs, 60 FPS cap, responsive swipe. */
    static PerfConfig balancedFast() {
        PerfConfig p = new PerfConfig();
        p.vcpus = 0;
        p.pinFastCores = true;
        p.tbSizeMiB = 1024;
        p.diskIoThread = true;
        p.diskWriteback = false;
        p.el0Stubs = 32;
        p.frameCapFps = 60;
        p.touchPacing = TOUCH_RESPONSIVE;
        return p;
    }

    /** Maximum speed preset for 12–16 GB RAM devices (adds writeback disk cache & 1536 MiB JIT cache). */
    static PerfConfig maxGaming() {
        PerfConfig p = new PerfConfig();
        p.vcpus = 0;
        p.pinFastCores = true;
        p.tbSizeMiB = 1536;
        p.diskIoThread = true;
        p.diskWriteback = true;
        p.el0Stubs = 48;
        p.frameCapFps = 60;
        p.touchPacing = TOUCH_RESPONSIVE;
        return p;
    }

    static PerfConfig load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        PerfConfig p = new PerfConfig();
        p.vcpus = sanitizeVcpus(sp.getInt("vcpus", 0));
        p.pinFastCores = sp.getBoolean("pinFastCores", false);
        p.tbSizeMiB = sanitizeTbSize(sp.getInt("tbSizeMiB", 768));
        p.diskIoThread = sp.getBoolean("diskIoThread", false);
        p.diskWriteback = sp.getBoolean("diskWriteback", false);
        p.el0Stubs = sanitizeEl0Stubs(sp.getInt("el0Stubs", 24));
        p.frameCapFps = sanitizeFrameCap(sp.getInt("frameCapFps", 0));
        p.touchPacing = sanitizeTouchPacing(sp.getInt("touchPacing", TOUCH_BALANCED));
        applyLive(p);
        return p;
    }

    static void save(Context c, PerfConfig p) {
        SharedPreferences sp = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        sp.edit()
                .putInt("vcpus", sanitizeVcpus(p.vcpus))
                .putBoolean("pinFastCores", p.pinFastCores)
                .putInt("tbSizeMiB", sanitizeTbSize(p.tbSizeMiB))
                .putBoolean("diskIoThread", p.diskIoThread)
                .putBoolean("diskWriteback", p.diskWriteback)
                .putInt("el0Stubs", sanitizeEl0Stubs(p.el0Stubs))
                .putInt("frameCapFps", sanitizeFrameCap(p.frameCapFps))
                .putInt("touchPacing", sanitizeTouchPacing(p.touchPacing))
                .commit();
        applyLive(p);
    }

    static void applyLive(PerfConfig p) {
        liveFrameCapFps = sanitizeFrameCap(p.frameCapFps);
        switch (sanitizeTouchPacing(p.touchPacing)) {
            case TOUCH_RESPONSIVE:
                liveTouchMinGapMs = 8;
                liveTouchMaxGapMs = 33;
                break;
            case TOUCH_LOW_OVERHEAD:
                liveTouchMinGapMs = 33;
                liveTouchMaxGapMs = 100;
                break;
            default:
                liveTouchMinGapMs = 16;
                liveTouchMaxGapMs = 66;
                break;
        }
    }

    static int sanitizeVcpus(int v) {
        return (v == 2 || v == 4 || v == 6 || v == 8) ? v : 0;
    }

    static int sanitizeTbSize(int v) {
        return (v == 512 || v == 768 || v == 1024 || v == 1536) ? v : 768;
    }

    static int sanitizeEl0Stubs(int v) {
        return (v == 24 || v == 32 || v == 48) ? v : 24;
    }

    static int sanitizeFrameCap(int v) {
        return (v == 30 || v == 60) ? v : 0;
    }

    static int sanitizeTouchPacing(int v) {
        return (v == TOUCH_RESPONSIVE || v == TOUCH_LOW_OVERHEAD) ? v : TOUCH_BALANCED;
    }

    boolean needsColdBootComparedTo(PerfConfig other) {
        return this.vcpus != other.vcpus;
    }

    boolean needsRestartComparedTo(PerfConfig other) {
        return this.pinFastCores != other.pinFastCores
                || this.tbSizeMiB != other.tbSizeMiB
                || this.diskIoThread != other.diskIoThread
                || this.diskWriteback != other.diskWriteback
                || this.el0Stubs != other.el0Stubs;
    }

    boolean changedLiveComparedTo(PerfConfig other) {
        return this.frameCapFps != other.frameCapFps
                || this.touchPacing != other.touchPacing;
    }

    String summaryLine() {
        return "vcpus=" + (vcpus == 0 ? "auto" : vcpus)
                + ", pinFast=" + (pinFastCores ? "on" : "off")
                + ", tbSize=" + tbSizeMiB + "M"
                + ", diskIoThread=" + (diskIoThread ? "on" : "off")
                + ", diskCache=" + (diskWriteback ? "writeback" : "none")
                + ", el0Stubs=" + el0Stubs
                + ", frameCap=" + (frameCapFps == 0 ? "auto" : frameCapFps + "fps")
                + ", touch=" + (touchPacing == TOUCH_RESPONSIVE ? "responsive"
                : touchPacing == TOUCH_LOW_OVERHEAD ? "low-overhead" : "balanced");
    }
}
