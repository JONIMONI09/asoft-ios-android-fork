package com.asoft.ios;

/**
 * The GPU check's recorded pass as a start of iOS 26 / watchOS and a begun setup read it (GpuCheck.forStart): the
 * keys GpuCheck.run wrote (the "versionCode/Turnip size" of the build that checked; "" for no pass) against this
 * build's key. GpuCheck.passed and its modes count only a pass of this build, so that Set up checks again after an
 * app update; but nothing checks at a start, and the mode a check finds belongs to the phone (its GPU and driver).
 * Before 0.1.7 an update made a start forget it: a Mali that had passed on its own Vulkan driver started on Turnip
 * and died at device creation (A#296, Vulkan -3), and an Adreno 740/750/660 started without the compatibility mode.
 * A check of this build replaces the recorded pass, a failed one too.
 */
final class GpuPass {
    /** The key of the build whose check passed; "" when the last check failed or none ran. */
    final String by;
    /** A pass by this build: Set up may download iOS 26 and watchOS (GpuCheck.passed). */
    final boolean thisBuild;
    /** A pass by this build or by the build before an update: a start uses its mode, a begun setup goes on. */
    final boolean any;
    /** That pass was in the compatibility mode (REIMS_VGPU_DEFERRED_VIEWS=0). */
    final boolean noSparse;
    /** That pass was on the phone's own Vulkan driver (the engine opens Android's loader; always compatibility mode). */
    final boolean system;

    /**
     * @param current this build's key; @param pass, @param nosparse, @param system the keys the last check wrote;
     * @param forceSystem the debug flag files/gpucheck-force-system (with a pass).
     */
    GpuPass(String current, String pass, String nosparse, String system, boolean forceSystem) {
        by = pass == null ? "" : pass;
        any = !by.isEmpty();
        thisBuild = any && by.equals(current);
        this.system = any && (by.equals(system) || forceSystem);
        this.noSparse = any && by.equals(nosparse);
    }

    /** The mode in the app's log's words. */
    String mode() {
        return !any ? "no pass" : system ? "the phone's own driver" : noSparse ? "the compatibility mode" : "the full mode";
    }
}
