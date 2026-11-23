package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GameEnvGuardTest {
    @Test
    public void knownGameSpacePackagesResolveFriendlyNames() {
        assertEquals("REDMAGIC Game Space", GameEnvGuard.friendlyName("com.zte.gameservice"));
        assertEquals("REDMAGIC Game Space", GameEnvGuard.friendlyName("com.nubia.gamelauncher"));
        assertEquals("Samsung Game Booster", GameEnvGuard.friendlyName("com.samsung.android.game.gametools"));
        assertNull(GameEnvGuard.friendlyName("com.android.settings"));
        assertNull(GameEnvGuard.friendlyName(null));
    }

    @Test
    public void warningMessageNamesDetectedPackage() {
        String msg = GameEnvGuard.warningMessage("com.zte.gameservice");
        assertTrue(msg.contains("REDMAGIC Game Space"));
        assertTrue(msg.contains("com.zte.gameservice"));
    }

    @Test
    public void cancelBurstDetectsThreeCancelsInsideWindowAndThrottles() {
        GameEnvGuard guard = new GameEnvGuard();
        assertFalse(guard.noteTouchCancel(1_000L));
        assertFalse(guard.noteTouchCancel(2_000L));
        // 3rd cancel within 5s triggers burst log once
        assertTrue(guard.noteTouchCancel(3_500L));
        // immediate 4th cancel inside 10s throttle window stays suppressed
        assertFalse(guard.noteTouchCancel(4_000L));
        // after cooldown (>= 10s later) with 3 recent events in 5s, fires again
        assertFalse(guard.noteTouchCancel(15_000L));
        assertFalse(guard.noteTouchCancel(15_500L));
        assertTrue(guard.noteTouchCancel(16_000L));
    }

    @Test
    public void spacedOutCancelsDoNotTriggerBurst() {
        GameEnvGuard guard = new GameEnvGuard();
        assertFalse(guard.noteTouchCancel(1_000L));
        assertFalse(guard.noteTouchCancel(7_000L));
        assertFalse(guard.noteTouchCancel(14_000L));
        assertFalse(guard.noteTouchCancel(21_000L));
    }
}
