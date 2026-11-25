package com.asoft.ios;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PerfConfigTest {
    @Test
    public void stockDefaultsMatchUpstreamBehavior() {
        PerfConfig p = PerfConfig.stockDefaults();
        assertEquals(0, p.vcpus);
        assertFalse(p.pinFastCores);
        assertEquals(768, p.tbSizeMiB);
        assertFalse(p.diskIoThread);
        assertFalse(p.diskWriteback);
        assertEquals(24, p.el0Stubs);
        assertEquals(20000, p.el0DelayMs);
        assertFalse(p.disableQemuLog);
        assertEquals(0, p.frameCapFps);
        assertEquals(PerfConfig.TOUCH_BALANCED, p.touchPacing);
        assertFalse(p.logInteractionTiming);
    }

    @Test
    public void presetsAndSanitizersWork() {
        PerfConfig balanced = PerfConfig.balancedFast();
        assertTrue(balanced.pinFastCores);
        assertEquals(1024, balanced.tbSizeMiB);
        assertTrue(balanced.diskIoThread);
        assertFalse(balanced.diskWriteback);
        assertEquals(32, balanced.el0Stubs);
        assertEquals(10000, balanced.el0DelayMs);
        assertTrue(balanced.disableQemuLog);
        assertEquals(60, balanced.frameCapFps);
        assertEquals(PerfConfig.TOUCH_RESPONSIVE, balanced.touchPacing);

        PerfConfig gaming = PerfConfig.maxGaming();
        assertTrue(gaming.diskWriteback);
        assertEquals(1536, gaming.tbSizeMiB);
        assertEquals(48, gaming.el0Stubs);
        assertEquals(10000, gaming.el0DelayMs);
        assertTrue(gaming.disableQemuLog);

        assertEquals(0, PerfConfig.sanitizeVcpus(99));
        assertEquals(6, PerfConfig.sanitizeVcpus(6));
        assertEquals(768, PerfConfig.sanitizeTbSize(123));
        assertEquals(1536, PerfConfig.sanitizeTbSize(1536));
        assertEquals(24, PerfConfig.sanitizeEl0Stubs(10));
        assertEquals(20000, PerfConfig.sanitizeEl0DelayMs(100));
        assertEquals(5000, PerfConfig.sanitizeEl0DelayMs(5000));
        assertEquals(0, PerfConfig.sanitizeFrameCap(144));
        assertEquals(30, PerfConfig.sanitizeFrameCap(30));
    }

    @Test
    public void changeScopeClassificationAndLiveValues() {
        PerfConfig a = PerfConfig.stockDefaults();
        PerfConfig b = PerfConfig.stockDefaults();
        b.frameCapFps = 60;
        b.touchPacing = PerfConfig.TOUCH_RESPONSIVE;
        b.logInteractionTiming = true;
        assertTrue(b.changedLiveComparedTo(a));
        assertFalse(b.needsRestartComparedTo(a));
        assertFalse(b.needsColdBootComparedTo(a));

        PerfConfig.applyLive(b);
        assertEquals(60, PerfConfig.liveFrameCapFps);
        assertEquals(8, PerfConfig.liveTouchMinGapMs);
        assertEquals(33, PerfConfig.liveTouchMaxGapMs);
        assertTrue(PerfConfig.liveLogInteractionTiming);

        b.disableQemuLog = true;
        assertTrue(b.needsRestartComparedTo(a));
        assertFalse(b.needsColdBootComparedTo(a));

        b.vcpus = 6;
        assertTrue(b.needsColdBootComparedTo(a));

        PerfConfig.applyLive(PerfConfig.stockDefaults());
        assertFalse(PerfConfig.liveLogInteractionTiming);
    }
}
