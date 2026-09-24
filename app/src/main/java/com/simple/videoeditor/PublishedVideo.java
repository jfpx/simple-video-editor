package com.simple.videoeditor;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Serialized durable publication journal, separate from diagnostic TXT destinations. */
final class PublishedVideo {
    static final String PREFS = "published-video";
    private static final String[] STATE_KEYS = {"tree", "treeName", "private", "savedPrivate",
            "uri", "name", "location", "pending", "pendingLocation", "reserved", "reservedTree", "rollback"};
    final Uri uri;
    final String name, location, status;
    final File privateFile;

    PublishedVideo(Uri uri, String name, String location, String status, File file) {
        this.uri = uri;
        this.name = name;
        this.location = location;
        this.status = status;
        privateFile = file;
    }

    String info() {
        return status + "\n" + location + "\n" + name + "\n"
                + (uri == null ? privateFile.getAbsolutePath() : uri.toString());
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static synchronized void saveTree(Context context, Uri tree) throws IOException {
        Uri document = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = context.getContentResolver().query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_FLAGS,
                        DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()
                    || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))
                    || (cursor.getLong(1) & DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE) == 0) {
                throw new IOException("Select a writable output video directory");
            }
            SharedPreferences prefs = prefs(context);
            commit(prefs, prefs.edit().putString("tree", tree.toString())
                    .putString("treeName", cursor.getString(0)));
        }
    }

    static synchronized PublishedVideo recover(Context context) throws IOException {
        SharedPreferences prefs = prefs(context);
        String pending = prefs.getString("pending", null);
        String reserved = prefs.getString("reserved", null);
        if (pending == null && reserved != null) {
            Uri collection;
            String treeValue = prefs.getString("reservedTree", null);
            boolean media = treeValue == null && Build.VERSION.SDK_INT >= 29;
            if (media) {
                collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
            } else {
                if (treeValue == null) throw new IOException("Missing pending video directory");
                Uri tree = Uri.parse(treeValue);
                collection = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                        DocumentsContract.getTreeDocumentId(tree));
            }
            // Collection queries hide pending rows by default, including our interrupted insert.
            Uri queryCollection = media && Build.VERSION.SDK_INT >= 29
                    ? MediaStore.setIncludePending(collection) : collection;
            try (Cursor cursor = context.getContentResolver().query(queryCollection,
                    media ? new String[]{"_id", OpenableColumns.DISPLAY_NAME}
                            : new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME},
                    media ? OpenableColumns.DISPLAY_NAME + "=?" : null,
                    media ? new String[]{reserved} : null, null)) {
                if (cursor == null) throw new IOException("Cannot recover reserved output");
                while (cursor.moveToNext()) {
                    if (!reserved.equals(cursor.getString(1))) continue;
                    Uri orphan = media ? android.content.ContentUris.withAppendedId(collection, cursor.getLong(0))
                            : DocumentsContract.buildDocumentUriUsingTree(Uri.parse(treeValue), cursor.getString(0));
                    delete(context, orphan);
                }
            }
            clearPending(prefs);
        }
        if (pending != null) {
            Uri uri = Uri.parse(pending);
            if (!prefs.getBoolean("rollback", false)
                    && Build.VERSION.SDK_INT >= 29 && "media".equals(uri.getAuthority())) {
                try (Cursor cursor = context.getContentResolver().query(uri,
                        new String[]{MediaStore.Video.Media.IS_PENDING}, null, null, null)) {
                    if (cursor == null) throw new IOException("Pending publication state unavailable");
                    if (cursor.moveToFirst() && cursor.getInt(0) == 0) {
                        PublishedVideo result = describe(context, uri, prefs.getString("pendingLocation", ""),
                                new File(prefs.getString("private", "")));
                        store(prefs, result);
                        return result;
                    }
                }
            }
            delete(context, uri);
            clearPending(prefs);
        }
        String file = prefs.getString("private", null);
        if (file == null) return null;
        File privateFile = new File(file);
        String saved = prefs.getString("uri", null);
        if (saved != null) {
            privateFile = new File(prefs.getString("savedPrivate", file));
            try {
                return describe(context, Uri.parse(saved), prefs.getString("location", ""), privateFile);
            } catch (IOException | RuntimeException error) {
                return new PublishedVideo(null, privateFile.getName(), "Private export (not public)",
                        "Saved public video unavailable: " + error.getMessage(), privateFile);
            }
        }
        return new PublishedVideo(null, privateFile.getName(), "Private export (not public)",
                "Publication incomplete, cancelled or failed; private MP4 retained", privateFile);
    }

    static synchronized PublishedVideo publish(Context context, File file, AtomicBoolean cancelled)
            throws IOException {
        recover(context);
        if (!file.isFile() || file.length() == 0) throw new IOException("Completed private MP4 missing");
        SharedPreferences prefs = prefs(context);
        SharedPreferences.Editor starting = prefs.edit().putString("private", file.getAbsolutePath());
        if (prefs.contains("uri") && !prefs.contains("savedPrivate")) {
            starting.putString("savedPrivate", prefs.getString("private", ""));
        }
        commit(prefs, starting);
        ContentResolver resolver = context.getContentResolver();
        Uri uri = null;
        String name = "edited_" + UUID.randomUUID() + ".mp4";
        String location;
        try {
            check(cancelled);
            String savedTree = Build.VERSION.SDK_INT >= 29 ? null : prefs.getString("tree", null);
            if (Build.VERSION.SDK_INT < 29 && savedTree == null) {
                throw new IOException("Select an output video directory first");
            }
            // Until result metadata commits, even a finalized row belongs to cleanup.
            // This survives a crash while persisting a later failure/rollback.
            commit(prefs, prefs.edit().putString("reserved", name)
                    .putString("reservedTree", savedTree).putBoolean("rollback", true));
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.DISPLAY_NAME, name);
                values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                values.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES);
                values.put(MediaStore.Video.Media.IS_PENDING, 1);
                uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                location = Environment.DIRECTORY_MOVIES;
            } else {
                Uri tree = Uri.parse(savedTree);
                uri = DocumentsContract.createDocument(resolver,
                        DocumentsContract.buildDocumentUriUsingTree(tree,
                                DocumentsContract.getTreeDocumentId(tree)), "video/mp4", name);
                location = "Selected directory: " + prefs.getString("treeName", "") + "\n" + tree;
            }
            if (uri == null) throw new IOException("Provider refused to create the output video");
            commit(prefs, prefs.edit().putString("pending", uri.toString()).putString("pendingLocation", location));
            copyAndVerify(context, file, uri, cancelled);
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                if (resolver.update(uri, values, null, null) != 1) {
                    throw new IOException("Provider did not finalize the public video");
                }
            }
            check(cancelled);
            PublishedVideo result = describe(context, uri, location, file);
            check(cancelled);
            store(prefs, result);
            return result;
        } catch (IOException | RuntimeException error) {
            try {
                // Persist rollback before any deletion: a finalized row is not success
                // once cancellation/failure has been observed, even if cleanup fails.
                commit(prefs, prefs.edit().putBoolean("rollback", true));
                if (uri != null) {
                    delete(context, uri);
                    clearPending(prefs);
                } else if (!prefs.contains("reserved")) {
                    clearPending(prefs);
                }
            } catch (IOException | RuntimeException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw new IOException("Public publication failed; good private MP4 retained: " + file, error);
        }
    }

    private static PublishedVideo describe(Context context, Uri uri, String location, File file)
            throws IOException {
        boolean media = Build.VERSION.SDK_INT >= 29 && "media".equals(uri.getAuthority());
        String[] columns = media
                ? new String[]{OpenableColumns.DISPLAY_NAME, MediaStore.Video.Media.RELATIVE_PATH,
                    MediaStore.Video.Media.IS_PENDING}
                : new String[]{OpenableColumns.DISPLAY_NAME};
        try (Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IOException("Output no longer exists");
            if (media && cursor.getInt(2) != 0) throw new IOException("Public output is still pending");
            if (media) location = cursor.getString(1);
            try (android.os.ParcelFileDescriptor descriptor =
                         context.getContentResolver().openFileDescriptor(uri, "r")) {
                if (descriptor == null || descriptor.getStatSize() == 0) {
                    throw new IOException("Output unreadable or empty");
                }
            }
            return new PublishedVideo(uri, cursor.getString(0), location, "Published video", file);
        }
    }

    static void copyAndVerify(Context context, File file, Uri uri, AtomicBoolean cancelled) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        copy(file, resolver.openOutputStream(uri, "w"), cancelled);
        check(cancelled);
        try (android.os.ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor == null || (descriptor.getStatSize() >= 0
                    && descriptor.getStatSize() != file.length())) {
                throw new IOException("Published byte count differs from completed private MP4");
            }
        }
    }

    static void copy(File file, OutputStream output, AtomicBoolean cancelled) throws IOException {
        try (OutputStream owned = output; FileInputStream input = new FileInputStream(file)) {
            if (owned == null) throw new IOException("Provider returned no output stream");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                check(cancelled);
                owned.write(buffer, 0, count);
            }
            owned.flush();
            syncOutput(owned);
        }
    }

    static void syncOutput(OutputStream output) throws IOException {
        if (output instanceof java.io.FileOutputStream) {
            FileDescriptor descriptor = ((java.io.FileOutputStream) output).getFD();
            try {
                if (OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) descriptor.sync();
            } catch (ErrnoException error) {
                throw new IOException("Cannot inspect output descriptor", error);
            }
        }
    }

    private static void store(SharedPreferences prefs, PublishedVideo result) throws IOException {
        commit(prefs, prefs.edit().putString("uri", result.uri.toString()).putString("name", result.name)
                .putString("savedPrivate", result.privateFile.getAbsolutePath())
                .putString("location", result.location).remove("pending").remove("pendingLocation")
                .remove("reserved").remove("reservedTree").remove("rollback"));
    }

    private static void clearPending(SharedPreferences prefs) throws IOException {
        commit(prefs, prefs.edit().remove("pending").remove("pendingLocation")
                .remove("reserved").remove("reservedTree").remove("rollback"));
    }

    private static void delete(Context context, Uri uri) throws IOException {
        if ("media".equals(uri.getAuthority())) {
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{"_id"}, null, null, null)) {
                if (cursor == null) throw new IOException("Pending cleanup state unavailable: " + uri);
                if (!cursor.moveToFirst()) return;
            }
            context.getContentResolver().delete(uri, null, null);
            try (Cursor cursor = context.getContentResolver().query(uri, new String[]{"_id"}, null, null, null)) {
                if (cursor == null || cursor.moveToFirst()) throw new IOException("Pending cleanup not confirmed: " + uri);
            }
        } else {
            if (!documentListed(context, uri)) return;
            try {
                if (!DocumentsContract.deleteDocument(context.getContentResolver(), uri)) {
                    throw new IOException("Provider refused pending output cleanup: " + uri);
                }
            } catch (java.io.FileNotFoundException missing) {
                if (documentListed(context, uri)) throw missing;
                return;
            }
            if (documentListed(context, uri)) {
                throw new IOException("Pending cleanup not confirmed: " + uri);
            }
        }
    }

    private static boolean documentListed(Context context, Uri uri) throws IOException {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(uri,
                DocumentsContract.getTreeDocumentId(uri));
        String id = DocumentsContract.getDocumentId(uri);
        // A missing document query may mask revoked permission. Only a successful
        // listing of the creation directory establishes absence for retry cleanup.
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID}, null, null, null)) {
            if (cursor == null) throw new IOException("Pending directory listing unavailable: " + uri);
            while (cursor.moveToNext()) {
                if (id.equals(cursor.getString(0))) return true;
            }
            return false;
        }
    }

    private static void commit(SharedPreferences prefs, SharedPreferences.Editor editor) throws IOException {
        Map<String, ?> before = prefs.getAll();
        IOException failure;
        try {
            if (editor.commit()) return;
            failure = new IOException("Cannot persist output publication state");
        } catch (RuntimeException error) {
            failure = new IOException("Cannot persist output publication state", error);
        }
        // Android mutates memory BEFORE disk I/O, even on false. Restore the whole
        // binding/journal before any subsequent rollback edit can persist that memory.
        try {
            SharedPreferences.Editor restore = prefs.edit();
            for (String key : STATE_KEYS) {
                Object value = before.get(key);
                if (value == null) restore.remove(key);
                else if (value instanceof Boolean) restore.putBoolean(key, (Boolean) value);
                else restore.putString(key, (String) value);
            }
            if (!restore.commit()) {
                failure.addSuppressed(new IOException("Cannot persist restored publication state"));
            }
        } catch (RuntimeException error) {
            failure.addSuppressed(error);
        }
        throw failure;
    }

    private static void check(AtomicBoolean cancelled) throws InterruptedIOException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Publication cancelled");
        }
    }
}
