package com.simple.videoeditor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/** Test-only controls; grants are issued by the real document provider's owning UID. */
public final class DirectDocumentTestControl extends ContentProvider {
    private java.io.File ranged;
    private long length;
    @Override public boolean onCreate() {
        try {
            ranged = new java.io.File(getContext().getFilesDir(), "ranged-video.bin");
            try (java.io.InputStream input = getContext().createPackageContext("com.simple.videoeditor", 0)
                    .getAssets().open("video-oracle/standard.mp4");
                 java.io.FileOutputStream output = new java.io.FileOutputStream(ranged)) {
                output.write(new byte[65536]);
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); length += count; }
                output.write(new byte[32768]);
            }
            return true;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("direct.reset") || method.equals("direct.cleanup")) {
            for (String id : new String[]{"seekable", "pipe", "slowpipe", "unknown", "large"}) {
                Uri uri = DirectVideoSourceTest.uri(id);
                if (method.equals("direct.reset"))
                    getContext().grantUriPermission("com.simple.videoeditor", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                else getContext().revokeUriPermission("com.simple.videoeditor", uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            }
        }
        return getContext().getContentResolver().call(Uri.parse("content://" + DirectVideoDocuments.AUTHORITY),
                method.equals("direct.cleanup") ? "direct.reset" : method, arg, extras);
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        android.database.MatrixCursor cursor = new android.database.MatrixCursor(projection == null
                ? new String[]{"_display_name", "_size"} : projection);
        cursor.newRow().add("_display_name", "ranged.mp4").add("_size", length);
        return cursor;
    }
    @Override public String getType(Uri uri) { return "video/mp4"; }
    @Override public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode)
            throws java.io.FileNotFoundException {
        if (!mode.equals("r")) throw new java.io.FileNotFoundException("Read only");
        return new android.content.res.AssetFileDescriptor(android.os.ParcelFileDescriptor.open(
                ranged, android.os.ParcelFileDescriptor.MODE_READ_ONLY), 65536, length);
    }
    @Override public android.content.res.AssetFileDescriptor openAssetFile(Uri uri, String mode,
            android.os.CancellationSignal signal) throws java.io.FileNotFoundException {
        if (signal != null) signal.throwIfCanceled();
        return openAssetFile(uri, mode);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
