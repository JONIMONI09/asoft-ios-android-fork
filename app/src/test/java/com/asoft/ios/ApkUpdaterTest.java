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
}
