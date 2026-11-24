package com.asoft.ios;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

public class ApkUpdaterTest {
    @Test
    public void parsesLatestApkAssetAndSha256Digest() throws Exception {
        String json = "["
                + "{\"tag_name\":\"v0.1.7-advanced-20\",\"draft\":false,\"prerelease\":true,\"assets\":["
                + "{\"name\":\"notes.txt\",\"browser_download_url\":\"https://example/notes.txt\",\"size\":12},"
                + "{\"name\":\"ASoftware-iOS-Android-0.1.7-ux-stability-debug.apk\","
                + "\"browser_download_url\":\"https://example/app.apk\","
                + "\"digest\":\"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\","
                + "\"size\":114000000}"
                + "]}"
                + "]";
        assertNull(ApkUpdater.parseLatestApk(json, false));
        ApkUpdater.ReleaseAsset a = ApkUpdater.parseLatestApk(json, true);
        assertNotNull(a);
        assertEquals("v0.1.7-advanced-20", a.tag);
        assertEquals("https://example/app.apk", a.downloadUrl);
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", a.expectedSha256);
        assertEquals(114000000L, a.sizeBytes);
    }

    @Test
    public void validatesApkZipStructureAndRejectsNonApks() throws Exception {
        File good = File.createTempFile("valid-apk", ".apk");
        File bad = File.createTempFile("bad-apk", ".apk");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(good))) {
                zos.putNextEntry(new ZipEntry("AndroidManifest.xml"));
                zos.write(new byte[1024]);
                zos.closeEntry();
                zos.putNextEntry(new ZipEntry("classes.dex"));
                zos.write(new byte[1024]);
                zos.closeEntry();
            }
            try (FileOutputStream fos = new FileOutputStream(bad)) {
                fos.write("not a zip".getBytes(StandardCharsets.UTF_8));
            }
            assertTrue(ApkUpdater.isValidApkZip(good));
            assertFalse(ApkUpdater.isValidApkZip(bad));
        } finally {
            good.delete();
            bad.delete();
        }
    }

    @Test
    public void sha256MatchingIsCaseInsensitiveAndRejectsMismatch() {
        String h = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        assertTrue(ApkUpdater.sha256Matches(h, h.toUpperCase()));
        assertFalse(ApkUpdater.sha256Matches(h, "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"));
        assertNull(ApkUpdater.extractSha256("md5:1234"));
    }

    @Test
    public void skipsDraftReleasesEvenWhenPrereleasesAllowed() {
        String json = "["
                + "{\"tag_name\":\"v0.1.7-advanced-99\",\"draft\":true,\"prerelease\":true,\"assets\":["
                + "{\"name\":\"app.apk\",\"browser_download_url\":\"https://example/draft.apk\",\"size\":1000}"
                + "]},"
                + "{\"tag_name\":\"v0.1.7-advanced-21\",\"draft\":false,\"prerelease\":true,\"assets\":["
                + "{\"name\":\"app.apk\",\"browser_download_url\":\"https://example/live.apk\",\"size\":2000}"
                + "]}"
                + "]";
        ApkUpdater.ReleaseAsset a = ApkUpdater.parseLatestApk(json, true);
        assertNotNull(a);
        assertEquals("v0.1.7-advanced-21", a.tag);
        assertEquals("https://example/live.apk", a.downloadUrl);
    }

    @Test
    public void verifyResultAllGreenAndSummaryLinesCoverAllOutcomes() {
        File dummy = new File("update.apk");
        String sha = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

        // All green with GitHub SHA-256 digest
        ApkUpdater.VerifyResult ok = new ApkUpdater.VerifyResult(
                dummy, true, true, true, true, true, true, sha, "0.1.7", null);
        assertTrue(ok.allGreen());
        String okSummary = ok.summaryLines("v0.1.7-advanced-21");
        assertTrue(okSummary.contains("[OK] Valid APK archive"));
        assertTrue(okSummary.contains("[OK] Signing key matches installed app"));
        assertTrue(okSummary.contains("[OK] SHA-256 matches GitHub release digest"));

        // Different signing key: warning only (error == null, allGreen == false)
        ApkUpdater.VerifyResult sigWarn = new ApkUpdater.VerifyResult(
                dummy, true, true, true, false, false, true, sha, "0.1.7", null);
        assertFalse(sigWarn.allGreen());
        String warnSummary = sigWarn.summaryLines("v0.1.8");
        assertTrue(warnSummary.contains("[WARN] Different signing key"));
        assertTrue(warnSummary.contains("[OK] SHA-256 computed: 0123456789abcdef"));

        // Hash mismatch failure
        ApkUpdater.VerifyResult hashFail = new ApkUpdater.VerifyResult(
                dummy, true, true, true, true, true, false, sha, "0.1.7", "SHA-256 checksum mismatch.");
        assertFalse(hashFail.allGreen());
        String hashSummary = hashFail.summaryLines("v0.1.7-advanced-21");
        assertTrue(hashSummary.contains("[FAIL] SHA-256 matches GitHub release digest"));
        assertTrue(hashSummary.contains("Error: SHA-256 checksum mismatch."));

        // Unsigned APK failure
        ApkUpdater.VerifyResult unsigned = new ApkUpdater.VerifyResult(
                dummy, true, true, false, false, false, true, sha, "0.1.7", "APK is not signed.");
        assertFalse(unsigned.allGreen());
        String unsignedSummary = unsigned.summaryLines("v0.1.7-advanced-21");
        assertTrue(unsignedSummary.contains("[FAIL] APK is cryptographically signed"));
        assertTrue(unsignedSummary.contains("Error: APK is not signed."));
    }
}
