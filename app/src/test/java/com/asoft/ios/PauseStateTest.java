package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PauseStateTest {
    @Test
    public void runningPausableShowsPauseAndTransitionsToGreyedPausingThenResume() {
        PauseUi.ButtonState initial = PauseUi.setup26Button(false, true, false, false, false, true, false, false, false, true, false);
        assertEquals("Pause", initial.label);
        assertTrue(initial.visible);
        assertTrue(initial.enabled);
        assertEquals(1f, initial.alpha, 0.001f);

        PauseUi.ButtonState pausing = PauseUi.setup26Button(false, true, false, true, false, true, false, false, false, true, false);
        assertEquals("Pausing\u2026", pausing.label);
        assertTrue(pausing.visible);
        assertFalse(pausing.enabled);
        assertEquals(0.4f, pausing.alpha, 0.001f);

        PauseUi.ButtonState paused = PauseUi.setup26Button(false, true, true, false, false, true, false, false, false, true, false);
        assertEquals("Resume", paused.label);
        assertTrue(paused.visible);
        assertTrue(paused.enabled);
        assertEquals(1f, paused.alpha, 0.001f);
    }

    @Test
    public void nonInterruptibleStepShowsPauseAfterStepAndGreyedPendingState() {
        assertFalse(SetupService.pausable("ios26:firstboot"));
        assertFalse(SetupService.pausable("ios26:wallpaper"));
        assertFalse(SetupService.pausable("ios26:pack"));
        assertFalse(SetupService.pausable("shared:unpack"));

        PauseUi.ButtonState beforeTap = PauseUi.setup26Button(false, true, false, false, false, false, false, false, false, true, false);
        assertEquals("Pause after step", beforeTap.label);
        assertTrue(beforeTap.visible);
        assertTrue(beforeTap.enabled);

        PauseUi.ButtonState afterTap = PauseUi.setup26Button(false, true, false, false, true, false, false, false, false, true, false);
        assertEquals("Pausing after step\u2026", afterTap.label);
        assertTrue(afterTap.visible);
        assertFalse(afterTap.enabled);
        assertEquals(0.4f, afterTap.alpha, 0.001f);

        PauseUi.ButtonState settledPaused = PauseUi.setup26Button(false, true, true, false, false, false, false, false, false, true, false);
        assertEquals("Resume", settledPaused.label);
        assertTrue(settledPaused.enabled);
    }

    @Test
    public void helperVmStepsAreClassifiedPausable() {
        assertTrue(SetupService.pausable("ios26:rootfs"));
        assertTrue(SetupService.pausable("ios26:partition"));
        assertTrue(SetupService.pausable("shared:build"));
        assertTrue(SetupService.pausable("ios26:fetch"));
        assertTrue(SetupService.pausable("ios26:decrypt"));
    }

    @Test
    public void setup16FollowsSamePauseAndStepPauseContract() {
        PauseUi.ButtonState ipswPause = PauseUi.setup16Button(false, true, false, false, false, true, true, false);
        assertEquals("Pause", ipswPause.label);
        assertTrue(ipswPause.enabled);

        PauseUi.ButtonState diskPauseAfter = PauseUi.setup16Button(false, true, false, false, false, false, true, false);
        assertEquals("Pause after step", diskPauseAfter.label);
        assertTrue(diskPauseAfter.visible);
        assertTrue(diskPauseAfter.enabled);

        PauseUi.ButtonState pausing = PauseUi.setup16Button(false, true, false, true, false, true, true, false);
        assertEquals("Pausing\u2026", pausing.label);
        assertFalse(pausing.enabled);
        assertEquals(0.4f, pausing.alpha, 0.001f);
    }
}
