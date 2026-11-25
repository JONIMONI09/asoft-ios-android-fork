package com.asoft.ios;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * Verified in-app GitHub Release APK updater: fetches the latest release APK for the chosen channel
 * (Upstream or Fork CI), downloads it into cache/share/update.apk while computing SHA-256, verifies
 * ZIP/APK structure, package name, signing certificate compatibility, and SHA-256 digest, and then
 * invokes Android's native package installer UI.
 */
final class ApkUpdater {
    static final String UPSTREAM_API = "https://api.github.com/repos/alpersamur3/asoft-ios-android/releases?per_page=5";
    static final String FORK_API = "https://api.github.com/repos/JONIMONI09/asoft-ios-android-fork/releases?per_page=5";
    static final String APK_FILE_NAME = "update.apk";

    static final class ReleaseAsset {
        final String tag;
        final String assetName;
        final String downloadUrl;
        final String expectedSha256; // lowercase hex, or null if GitHub did not provide a digest
        final long sizeBytes;

        ReleaseAsset(String tag, String assetName, String downloadUrl, String expectedSha256, long sizeBytes) {
            this.tag = tag;
            this.assetName = assetName;
            this.downloadUrl = downloadUrl;
            this.expectedSha256 = expectedSha256;
            this.sizeBytes = sizeBytes;
        }
    }

    static final class VerifyResult {
        final File apkFile;
        final boolean zipApkValid;
        final boolean packageMatch;
        final boolean signed;
        final boolean signatureMatch;
        final boolean hashChecked;
        final boolean hashMatch;
        final String actualSha256;
        final String apkVersion;
        final String error;

        VerifyResult(File apkFile, boolean zipApkValid, boolean packageMatch, boolean signed,
                     boolean signatureMatch, boolean hashChecked, boolean hashMatch,
                     String actualSha256, String apkVersion, String error) {
            this.apkFile = apkFile;
            this.zipApkValid = zipApkValid;
            this.packageMatch = packageMatch;
            this.signed = signed;
            this.signatureMatch = signatureMatch;
            this.hashChecked = hashChecked;
            this.hashMatch = hashMatch;
            this.actualSha256 = actualSha256;
            this.apkVersion = apkVersion;
            this.error = error;
        }

        boolean allGreen() {
            return zipApkValid && packageMatch && signed && signatureMatch && (!hashChecked || hashMatch) && error == null;
        }

        String summaryLines(String tag) {
            StringBuilder sb = new StringBuilder();
            sb.append("Release: ").append(tag);
            if (apkVersion != null && !apkVersion.isEmpty()) {
                sb.append(" (v").append(apkVersion).append(")");
            }
            sb.append("\n\n");
            sb.append(zipApkValid ? "[OK] " : "[FAIL] ").append("Valid APK archive (AndroidManifest + classes.dex)\n");
            sb.append(packageMatch ? "[OK] " : "[FAIL] ").append("Package name matches com.asoft.ios\n");
            sb.append(signed ? "[OK] " : "[FAIL] ").append("APK is cryptographically signed\n");
            sb.append(signatureMatch ? "[OK] " : "[WARN] ").append(signatureMatch
                    ? "Signing key matches installed app (in-place update)\n"
                    : "Different signing key (uninstall required before installing!)\n");
            if (hashChecked) {
                sb.append(hashMatch ? "[OK] " : "[FAIL] ").append("SHA-256 matches GitHub release digest\n");
            } else {
                sb.append("[OK] SHA-256 computed: ").append(shortHash(actualSha256)).append("\n");
            }
            if (error != null) {
                sb.append("\nError: ").append(error);
            }
            return sb.toString();
        }
    }

    interface ProgressCb {
        void onProgress(long doneBytes, long totalBytes);
    }

    private ApkUpdater() {}

    /** Parses GitHub releases JSON and returns the newest release containing an .apk asset, or null. */
    @SuppressWarnings("unchecked")
    static ReleaseAsset parseLatestApk(String json, boolean allowPrerelease) {
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof List)) {
            return null;
        }
        for (Object item : (List<?>) parsed) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> rel = (Map<String, Object>) item;
            if (MiniJson.bool(rel, "draft")) {
                continue;
            }
            if (!allowPrerelease && MiniJson.bool(rel, "prerelease")) {
                continue;
            }
            String tag = MiniJson.str(rel, "tag_name");
            Object assetsObj = rel.get("assets");
            if (!(assetsObj instanceof List)) {
                continue;
            }
            for (Object ao : (List<?>) assetsObj) {
                if (!(ao instanceof Map)) {
                    continue;
                }
                Map<String, Object> a = (Map<String, Object>) ao;
                String name = MiniJson.str(a, "name");
                if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) {
                    continue;
                }
                String url = MiniJson.str(a, "browser_download_url");
                if (url == null || url.isEmpty()) {
                    continue;
                }
                String digest = extractSha256(MiniJson.str(a, "digest"));
                long size = (long) MiniJson.num(a, "size", -1);
                return new ReleaseAsset(tag == null ? "" : tag, name, url, digest, size);
            }
        }
        return null;
    }

    static String extractSha256(String digestField) {
        if (digestField == null) {
            return null;
        }
        String d = digestField.trim().toLowerCase(Locale.ROOT);
        if (d.startsWith("sha256:")) {
            d = d.substring(7).trim();
        }
        return d.matches("[0-9a-f]{64}") ? d : null;
    }

    /** Checks that @f is a valid ZIP archive containing AndroidManifest.xml and classes.dex. */
    static boolean isValidApkZip(File f) {
        if (f == null || !f.isFile() || f.length() < 64) {
            return false;
        }
        try (ZipFile z = new ZipFile(f)) {
            return z.getEntry("AndroidManifest.xml") != null && z.getEntry("classes.dex") != null;
        } catch (IOException e) {
            return false;
        }
    }

    static boolean sha256Matches(String actual, String expected) {
        return actual != null && expected != null && actual.equalsIgnoreCase(expected.trim());
    }

    static boolean isTransientNetworkError(Throwable t) {
        while (t != null) {
            if (t instanceof java.net.UnknownHostException
                    || t instanceof java.net.SocketTimeoutException
                    || t instanceof java.net.ConnectException) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }

    static ReleaseAsset fetchLatest(Context c, boolean forkChannel) throws Exception {
        String api = forkChannel ? FORK_API : UPSTREAM_API;
        String ver = UpdateCheck.version(c);
        String json = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                json = UpdateCheck.get(api, "application/vnd.github+json", ver, 1 << 20);
                break;
            } catch (IOException e) {
                if (attempt == 1 || !isTransientNetworkError(e)) {
                    throw e;
                }
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        ReleaseAsset asset = parseLatestApk(json, forkChannel);
        if (asset == null) {
            throw new IOException("No APK asset found in latest release.");
        }
        return asset;
    }

    static void clearCache(Context c) {
        File dir = ShareProvider.dir(c);
        long freed = 0;
        int removed = 0;
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && (n.endsWith(".apk") || n.endsWith(".apk.part"))) {
                    long len = f.length();
                    if (f.delete()) {
                        freed += len;
                        removed++;
                    }
                }
            }
        }
        if (removed > 0) {
            AppLog.note(c, String.format(java.util.Locale.ROOT,
                    "updater: cleared %d leftover APK cache file(s) (%.1f MB)", removed, freed / 1e6));
        }
    }

    static VerifyResult downloadAndVerify(Context c, ReleaseAsset asset, ProgressCb cb) throws Exception {
        File dir = ShareProvider.dir(c);
        dir.mkdirs();
        File out = new File(dir, APK_FILE_NAME);
        File tmp = new File(dir, APK_FILE_NAME + ".part");
        MessageDigest sha = MessageDigest.getInstance("SHA-256");

        HttpURLConnection conn = (HttpURLConnection) new URL(asset.downloadUrl).openConnection();
        conn.setInstanceFollowRedirects(true);
        conn.setConnectTimeout(15_000);
        conn.setReadTimeout(30_000);
        conn.setRequestProperty("User-Agent", "ASoftware-Android/" + UpdateCheck.version(c));
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " downloading APK");
            }
            long total = conn.getContentLengthLong();
            if (total <= 0) {
                total = asset.sizeBytes;
            }
            long done = 0;
            try (InputStream in = conn.getInputStream(); FileOutputStream fos = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                int n;
                long lastCb = 0;
                while ((n = in.read(buf)) != -1) {
                    fos.write(buf, 0, n);
                    sha.update(buf, 0, n);
                    done += n;
                    long now = System.currentTimeMillis();
                    if (cb != null && (now - lastCb >= 200 || done == total)) {
                        lastCb = now;
                        cb.onProgress(done, total);
                    }
                }
                fos.flush();
            }
            if (!tmp.renameTo(out)) {
                throw new IOException("Could not finalize downloaded APK");
            }
        } catch (Exception e) {
            tmp.delete();
            throw e;
        } finally {
            conn.disconnect();
        }

        String actualSha = hex(sha.digest());
        boolean hashChecked = asset.expectedSha256 != null;
        boolean hashMatch = !hashChecked || sha256Matches(actualSha, asset.expectedSha256);
        boolean zipValid = isValidApkZip(out);

        PackageManager pm = c.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(out.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        boolean pkgMatch = archive != null && c.getPackageName().equals(archive.packageName);
        String apkVer = archive != null ? archive.versionName : "";

        String apkCert = signingCertSha256(archive);
        boolean signed = apkCert != null;
        PackageInfo installed = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        String installedCert = signingCertSha256(installed);
        boolean sigMatch = signed && installedCert != null && apkCert.equalsIgnoreCase(installedCert);

        String err = null;
        if (!zipValid) {
            err = "Downloaded file is not a valid APK archive.";
        } else if (!pkgMatch) {
            err = "APK package name does not match " + c.getPackageName() + ".";
        } else if (!signed) {
            err = "APK is not signed.";
        } else if (hashChecked && !hashMatch) {
            err = "SHA-256 checksum mismatch.";
        }
        return new VerifyResult(out, zipValid, pkgMatch, signed, sigMatch, hashChecked, hashMatch, actualSha, apkVer, err);
    }

    static void launchInstaller(Activity a, File apk) {
        if (!a.getPackageManager().canRequestPackageInstalls()) {
            Intent perm = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + a.getPackageName()));
            a.startActivity(perm);
            return;
        }
        Uri uri = ShareProvider.uri(a, apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(install);
    }

    private static String signingCertSha256(PackageInfo pi) {
        if (pi == null) {
            return null;
        }
        SigningInfo si = pi.signingInfo;
        if (si == null) {
            return null;
        }
        Signature[] sigs = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        if (sigs == null || sigs.length == 0) {
            return null;
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(sigs[0].toByteArray()));
        } catch (Exception e) {
            return null;
        }
    }

    static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        }
        return sb.toString();
    }

    private static String shortHash(String h) {
        return h != null && h.length() > 16 ? h.substring(0, 16) + "…" : String.valueOf(h);
    }
}
