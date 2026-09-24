package com.simple.videoeditor;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class PublishedImage {
    static String name(Context context, Uri uri) throws IOException {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0))
                throw new IOException("Saved image provider did not expose its name: " + uri);
            return cursor.getString(0);
        }
    }

    static Uri publish(Context context, TitleBackground frame, String name, Uri document,
                       AtomicBoolean cancelled) throws IOException {
        Uri uri = document;
        boolean committed = false;
        try {
            if (uri == null) {
                if (Build.VERSION.SDK_INT < 29) throw new IOException("Choose an image document destination");
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES);
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
                values.put(MediaStore.Images.Media.DESCRIPTION, "Original source requested " + frame.timeMs
                        + " ms; closest decoded frame; no title text; projection=" + frame.key);
                uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IOException("Cannot create Pictures image");
            }
            try (FileInputStream input = new FileInputStream(frame.file);
                 OutputStream output = context.getContentResolver().openOutputStream(uri, "w")) {
                if (output == null) throw new IOException("Image provider returned no stream");
                MediaCopy.copy(input, output, 0, bytes -> {
                    if (cancelled.get()) throw new java.util.concurrent.CancellationException("Image save cancelled");
                });
            }
            if (cancelled.get()) throw new java.util.concurrent.CancellationException("Image save cancelled");
            long actualBytes;
            try (java.io.InputStream check = context.getContentResolver().openInputStream(uri)) {
                if (check == null) throw new IOException("Cannot reopen saved image");
                actualBytes = MediaCopy.copy(check, new OutputStream() {
                    public void write(int b) {}
                    public void write(byte[] b, int offset, int count) {}
                }, frame.file.length(), bytes -> {});
            }
            if (actualBytes != frame.file.length()) throw new IOException("Saved image is truncated");
            if (document == null) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                if (context.getContentResolver().update(uri, ready, null, null) != 1)
                    throw new IOException("Cannot finalize Pictures image");
            }
            committed = true;
            return uri;
        } finally {
            if (!committed && uri != null) {
                if (document == null) context.getContentResolver().delete(uri, null, null);
                else android.provider.DocumentsContract.deleteDocument(context.getContentResolver(), uri);
            }
        }
    }
}
