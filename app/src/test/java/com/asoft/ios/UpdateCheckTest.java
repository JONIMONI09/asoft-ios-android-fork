package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class UpdateCheckTest {
    @Test
    public void newerComparesSemverAndForkCiAdvancedBuildNumbers() {
        // Standard semver
        assertTrue(UpdateCheck.newer("v0.1.8", "0.1.7"));
        assertTrue(UpdateCheck.newer("v0.2.0", "0.1.9"));
        assertTrue(UpdateCheck.newer("v0.1.7.1", "0.1.7"));
        assertFalse(UpdateCheck.newer("v0.1.7", "0.1.7"));
        assertFalse(UpdateCheck.newer("v0.1.6", "0.1.7"));
        assertFalse(UpdateCheck.newer("", "0.1.7"));

        // Fork CI -advanced-<N> build numbers
        assertTrue(UpdateCheck.newer("v0.1.7-advanced-21", "0.1.7-advanced-20"));
        assertFalse(UpdateCheck.newer("v0.1.7-advanced-20", "0.1.7-advanced-20"));
        assertFalse(UpdateCheck.newer("v0.1.7-advanced-19", "0.1.7-advanced-20"));
        assertTrue(UpdateCheck.newer("v0.1.7-advanced-20", "0.1.7"));
        assertFalse(UpdateCheck.newer("v0.1.7", "0.1.7-advanced-20"));
        assertTrue(UpdateCheck.newer("v0.1.8", "0.1.7-advanced-20"));
        assertTrue(UpdateCheck.newer("v0.1.8-advanced-1", "0.1.7-advanced-99"));
    }

    @Test
    public void parsesAppNoticeBlockLevelAndText() {
        String body = "Release notes\r\n"
                + "<!-- app-notice\r\n"
                + "level: critical\r\n"
                + "text: First line of notice.\r\n"
                + "text: Second\tline.\r\n"
                + "-->\r\n"
                + "More notes";
        String[] b = UpdateCheck.block(body);
        assertEquals(UpdateCheck.CRITICAL, UpdateCheck.level(b));
        assertEquals("First line of notice.\nSecond line.", UpdateCheck.text(b));

        String[] important = UpdateCheck.block("<!-- app-notice\nLEVEL: Important\ntext: Check GPU settings.\n-->");
        assertEquals(UpdateCheck.IMPORTANT, UpdateCheck.level(important));
        assertEquals("Check GPU settings.", UpdateCheck.text(important));

        String[] unknown = UpdateCheck.block("<!-- app-notice\nlevel: future-level\n-->");
        assertEquals(UpdateCheck.NORMAL, UpdateCheck.level(unknown));
        assertNull(UpdateCheck.text(unknown));
        assertEquals(0, UpdateCheck.block("No notice comment here").length);
    }

    @Test
    public void selectsUpstreamVsForkCiReleasesCorrectly() {
        String json = "["
                + "{\"tag_name\":\"v0.1.9\",\"draft\":true,\"prerelease\":false,\"body\":\"\"},"
                + "{\"tag_name\":\"v0.1.7-advanced-22\",\"draft\":false,\"prerelease\":true,"
                + "\"body\":\"<!-- app-notice\\nlevel: important\\ntext: Fork CI build 22\\n-->\"},"
                + "{\"tag_name\":\"v0.1.7-advanced-21\",\"draft\":false,\"prerelease\":true,"
                + "\"body\":\"<!-- app-notice\\nlevel: critical\\ntext: Critical intermediate\\n-->\"},"
                + "{\"tag_name\":\"v0.1.8\",\"draft\":false,\"prerelease\":false,"
                + "\"body\":\"<!-- app-notice\\nlevel: normal\\ntext: Stable 0.1.8\\n-->\"}"
                + "]";

        // Upstream channel: skips drafts and prereleases, picks v0.1.8
        UpdateCheck.Notice upstream = UpdateCheck.selectNotice(json, "0.1.7", null, false);
        assertNotNull(upstream);
        assertEquals("v0.1.8", upstream.tag);
        assertEquals("Stable 0.1.8", upstream.text);
        assertEquals(UpdateCheck.NORMAL, upstream.level);

        // Fork CI channel on 0.1.7-advanced-20 (only fork prereleases): picks v0.1.7-advanced-22
        // and carries forward CRITICAL level from intermediate v0.1.7-advanced-21
        String forkJson = "["
                + "{\"tag_name\":\"v0.1.7-advanced-23\",\"draft\":true,\"prerelease\":true,\"body\":\"\"},"
                + "{\"tag_name\":\"v0.1.7-advanced-22\",\"draft\":false,\"prerelease\":true,"
                + "\"body\":\"<!-- app-notice\\nlevel: important\\ntext: Fork CI build 22\\n-->\"},"
                + "{\"tag_name\":\"v0.1.7-advanced-21\",\"draft\":false,\"prerelease\":true,"
                + "\"body\":\"<!-- app-notice\\nlevel: critical\\ntext: Critical intermediate\\n-->\"}"
                + "]";
        UpdateCheck.Notice fork = UpdateCheck.selectNotice(forkJson, "0.1.7-advanced-20", null, true);
        assertNotNull(fork);
        assertEquals("v0.1.7-advanced-22", fork.tag);
        assertEquals("Fork CI build 22", fork.text);
        assertEquals(UpdateCheck.CRITICAL, fork.level);

        // Already on latest Fork CI build -> null
        assertNull(UpdateCheck.selectNotice(forkJson, "0.1.7-advanced-22", null, true));
    }

    @Test
    public void apiUrlSwitchesBetweenUpstreamAndForkCi() {
        assertEquals(ApkUpdater.FORK_API, UpdateCheck.apiUrl(true));
        assertTrue(UpdateCheck.apiUrl(false).contains("alpersamur3/asoft-ios-android"));
    }
}
