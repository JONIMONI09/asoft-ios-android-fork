package com.asoft.ios;

/**
 * Pure button-state rules shared by SetupScreen, Setup26Screen and PauseStateTest.
 * Keeps pause transitions ("Pause" / "Pause after step" -> greyed "Pausing…" -> "Resume")
 * deterministic and unit-testable without an Android UI runner.
 */
final class PauseUi {
    static final class ButtonState {
        final String label;
        final boolean visible;
        final boolean enabled;
        final float alpha;

        ButtonState(String label, boolean visible, boolean enabled, float alpha) {
            this.label = label;
            this.visible = visible;
            this.enabled = enabled;
            this.alpha = alpha;
        }
    }

    private PauseUi() {}

    static ButtonState setup26Button(boolean installed, boolean inRun, boolean paused, boolean pausing,
            boolean pauseAfterStep, boolean stepPausable, boolean otherRunning, boolean gpuCheckRunning,
            boolean failedNotInRun, boolean pass, boolean pending) {
        boolean busyPausing = inRun && !paused && (pausing || pauseAfterStep);
        String label;
        if (installed) {
            label = "";
        } else if (inRun) {
            if (paused) {
                label = "Resume";
            } else if (pauseAfterStep) {
                label = "Pausing after step\u2026";
            } else if (pausing) {
                label = "Pausing\u2026";
            } else if (stepPausable) {
                label = "Pause";
            } else {
                label = "Pause after step";
            }
        } else if (!pass) {
            label = "Check the GPU";
        } else if (pending) {
            label = "Resume";
        } else {
            label = "Set up";
        }
        boolean visible = !(installed || (!inRun && failedNotInRun));
        boolean enabled = !gpuCheckRunning && !otherRunning && !busyPausing;
        float alpha = enabled ? 1f : 0.4f;
        return new ButtonState(label, visible, enabled, alpha);
    }

    static ButtonState setup16Button(boolean complete, boolean running, boolean paused, boolean pausing,
            boolean pauseAfterStep, boolean stepPausable, boolean started, boolean otherRunning) {
        boolean busyPausing = running && !paused && (pausing || pauseAfterStep);
        String label;
        if (running) {
            if (paused) {
                label = "Resume";
            } else if (pauseAfterStep) {
                label = "Pausing after step\u2026";
            } else if (pausing) {
                label = "Pausing\u2026";
            } else if (stepPausable) {
                label = "Pause";
            } else {
                label = "Pause after step";
            }
        } else if (started && !complete) {
            label = "Resume";
        } else {
            label = "Set up";
        }
        boolean visible = !complete;
        boolean enabled = !otherRunning && !busyPausing;
        float alpha = enabled ? 1f : 0.4f;
        return new ButtonState(label, visible, enabled, alpha);
    }
}
