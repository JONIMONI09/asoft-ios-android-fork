package com.asoft.ios;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Gives the app a log to share as a file: a text shared as text is cut by what receives it (2026-11-12: a user's
 * 60 KB log arrived as its first 20 KB, twice, so its end, the part that mattered, never came). It serves only the
 * files of cache/share, read only, to the app the share sheet grants the one URI to.
 */
public class ShareProvider extends ContentProvider {
    static File dir(Context c) {
        return new File(c.getCacheDir(), "share");
    }

    static Uri uri(Context c, File f) {
        return new Uri.Builder().scheme("content").authority(c.getPackageName() + ".share").appendPath(f.getName()).build();
    }

    private File file(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        // one name, no path in it
        if (name == null || name.contains("/") || name.contains("..") || uri.getPathSegments().size() != 1) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        File f = new File(dir(getContext()), name);
        if (!f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return f;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("read only");
        }
        return ParcelFileDescriptor.open(file(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        String[] cols = projection != null ? projection : new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor c = new MatrixCursor(cols, 1);
        try {
            File f = file(uri);
            Object[] row = new Object[cols.length];
            for (int i = 0; i < cols.length; i++) {
                row[i] = OpenableColumns.DISPLAY_NAME.equals(cols[i]) ? f.getName()
                        : OpenableColumns.SIZE.equals(cols[i]) ? (Object) f.length() : null;
            }
            c.addRow(row);
        } catch (FileNotFoundException e) {
            // no row
        }
        return c;
    }

    @Override
    public String getType(Uri uri) {
        String name = uri != null ? uri.getLastPathSegment() : null;
        if (name != null && name.endsWith(".apk")) {
            return "application/vnd.android.package-archive";
        }
        return "text/plain";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
