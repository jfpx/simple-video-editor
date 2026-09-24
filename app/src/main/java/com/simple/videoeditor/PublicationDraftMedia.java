package com.simple.videoeditor;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.OpenableColumns;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** No URI from an imported manifest is ever passed to this class. */
final class PublicationDraftMedia {
    static final class Reads {
        private final android.os.CancellationSignal signal = new android.os.CancellationSignal();
        private InputStream active;
        volatile Progress progress;
        void check() throws java.io.InterruptedIOException {
            if (signal.isCanceled() || Thread.currentThread().isInterrupted())
                throw new java.io.InterruptedIOException("Cancelled");
        }
        synchronized InputStream track(InputStream stream) throws IOException {
            if (signal.isCanceled()) {
                stream.close();
                throw new java.io.InterruptedIOException("Cancelled");
            }
            active = stream;
            return stream;
        }
        InputStream open(Context context, Uri uri) throws IOException {
            requireContent(uri);
            android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r", signal);
            if (fd == null) throw new IOException("Media unavailable");
            return track(new android.os.ParcelFileDescriptor.AutoCloseInputStream(fd));
        }
        void cancel() {
            signal.cancel();
            synchronized (this) {
                if (active != null) try { active.close(); } catch (IOException ignored) {}
                active = null;
            }
        }
    }

    static final class Fingerprint {
        final String sha;
        final long bytes;
        Fingerprint(String sha, long bytes) { this.sha = sha; this.bytes = bytes; }
    }

    interface Progress { void update(long bytes); }

    static Fingerprint hash(InputStream input) throws Exception {
        return hash(input, null);
    }

    static Fingerprint hash(InputStream input, Reads reads) throws Exception {
        if (input == null) throw new IOException("Media unavailable");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        long bytes = 0;
        long lastProgress = 0;
        int count;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelled");
            if (reads != null) reads.check();
            count = input.read(buffer);
            if (count == -1) break;
            digest.update(buffer, 0, count);
            bytes = Math.addExact(bytes, count);
            long now = android.os.SystemClock.uptimeMillis();
            if (reads != null && now - lastProgress >= 250) {
                lastProgress = now;
                Progress progress = reads.progress;
                if (progress != null) progress.update(bytes);
            }
        }
        if (bytes == 0) throw new IOException("Empty media");
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return new Fingerprint(hex.toString(), bytes);
    }

    static JSONObject inspect(Context context, JSONObject draft, Uri uri) throws Exception {
        return inspect(context, draft, uri, new Reads());
    }

    static JSONObject inspect(Context context, JSONObject draft, Uri uri, Reads reads) throws Exception {
        return verify(context, uri, reads).bind(new JSONObject(draft.toString()));
    }

    /** Constructible only by reading media, never by decoding a manifest's hash claim. */
    static final class VerifiedVideo {
        final Uri uri;
        final String name;
        final Fingerprint fingerprint;
        final long duration;
        private final String provider;
        private final android.system.StructStat stat;
        private VerifiedVideo(Uri uri, String name, Fingerprint fingerprint, long duration,
                String provider, android.system.StructStat stat) {
            this.uri = uri; this.name = name; this.fingerprint = fingerprint; this.duration = duration;
            this.provider = provider; this.stat = stat;
        }
        boolean matches(JSONObject draft) {
            return fingerprint.sha.equals(draft.optString("mediaSha256"))
                    && fingerprint.bytes == draft.optLong("mediaBytes", -1);
        }
        JSONObject bind(JSONObject draft) throws Exception {
            return draft.put("videoUri", uri.toString()).put("mediaName", name)
                    .put("mediaSha256", fingerprint.sha).put("mediaBytes", fingerprint.bytes)
                    .put("durationMs", duration).put("mediaVerified", true);
        }
        void checkCurrent(Context context, Reads reads) throws Exception {
            reads.check();
            try (InputStream input = reads.open(context, uri)) {
                android.system.StructStat now = android.system.Os.fstat(((java.io.FileInputStream) input).getFD());
                if (!provider.equals(providerState(context, uri)) || !name.equals(name(context, uri))
                        || stat.st_size != now.st_size || stat.st_mtime != now.st_mtime
                        || stat.st_ctime != now.st_ctime || stat.st_ino != now.st_ino || stat.st_dev != now.st_dev)
                    throw new IOException("Media changed after verification; select it again");
            }
        }
    }

    static VerifiedVideo verify(Context context, Uri uri, Reads reads) throws Exception {
        requireContent(uri);
        reads.check();
        String before = providerState(context, uri);
        String name = name(context, uri);
        try (InputStream input = reads.open(context, uri)) {
            java.io.FileDescriptor fd = ((java.io.FileInputStream) input).getFD();
            android.system.StructStat start = android.system.Os.fstat(fd);
            Fingerprint fingerprint = hash(input, reads);
            long duration;
            try (android.media.MediaMetadataRetriever retriever = new android.media.MediaMetadataRetriever()) {
                retriever.setDataSource(fd);
                String value = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION);
                if (value == null) throw new IOException("No video duration");
                duration = Long.parseLong(value);
                if (retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH) == null)
                    throw new IOException("Not a video");
            }
            android.system.StructStat end = android.system.Os.fstat(fd);
            reads.check();
            if (start.st_size != end.st_size || start.st_mtime != end.st_mtime
                    || start.st_ctime != end.st_ctime
                    || (android.system.OsConstants.S_ISREG(start.st_mode) && start.st_size != fingerprint.bytes)
                    || !before.equals(providerState(context, uri)) || !name.equals(name(context, uri)))
                throw new IOException("Media changed during verification; select a finalized video again");
            return new VerifiedVideo(uri, name, fingerprint, duration, before, end);
        }
    }

    private static String providerState(Context context, Uri uri) throws IOException {
        try (android.database.Cursor cursor = context.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new java.io.FileNotFoundException("Media unavailable");
            StringBuilder state = new StringBuilder();
            for (String column : new String[]{OpenableColumns.SIZE, "last_modified", "date_modified", "is_pending"}) {
                int index = cursor.getColumnIndex(column);
                if (index >= 0) {
                    if ("is_pending".equals(column) && cursor.getInt(index) != 0)
                        throw new IOException("Video is still being published");
                    state.append(column).append('=').append(cursor.getString(index)).append(';');
                }
            }
            return state.toString();
        }
    }

    static String name(Context context, Uri uri) throws IOException {
        requireContent(uri);
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new java.io.FileNotFoundException("Media unavailable");
            String name = cursor.getString(0);
            if (name == null || name.length() > 512) throw new IOException("Invalid media name");
            return name;
        }
    }

    static void requireContent(Uri uri) throws IOException {
        if (uri == null || !"content".equals(uri.getScheme())) throw new IOException("Select a document explicitly");
    }

    static String combined(JSONObject draft) throws Exception {
        return draft.getString("title") + "\n\n" + draft.getString("description") + "\n\n"
                + tags(draft) + "\n\nPublication license intention / 发布许可意向: "
                + draft.optString("publicationLicense", "unspecified")
                + "\nLocal intention only; no rights granted or platform setting applied."
                + "\n仅本地意向；不授予权利，不改变平台设置。\n\n" + draft.getString("musicCredits");
    }

    static String tags(JSONObject draft) throws Exception {
        StringBuilder text = new StringBuilder();
        org.json.JSONArray tags = draft.getJSONArray("tags");
        for (int i = 0; i < tags.length(); i++) {
            if (i > 0) text.append('\n');
            text.append(tags.getString(i));
        }
        return text.toString();
    }

    static Intent share(Context context, JSONObject draft, boolean cover) throws Exception {
        Uri uri = Uri.parse(draft.getString(cover ? "coverUri" : "videoUri"));
        requireContent(uri);
        try (android.os.ParcelFileDescriptor fd = context.getContentResolver().openFileDescriptor(uri, "r")) {
            if (fd == null || fd.getStatSize() == 0) throw new IOException("Media unavailable; metadata retained");
            if (!cover && draft.optLong("mediaBytes") > 0 && fd.getStatSize() >= 0
                    && fd.getStatSize() != draft.getLong("mediaBytes"))
                throw new IOException("Video size changed; verify the video again. Metadata retained.");
        }
        String mime = cover ? context.getContentResolver().getType(uri) : "video/mp4";
        if (cover && (mime == null || !mime.startsWith("image/"))) throw new IOException("Image unavailable");
        Intent intent = new Intent(Intent.ACTION_SEND).setType(mime)
                .putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TEXT, combined(draft))
                .putExtra(Intent.EXTRA_SUBJECT, draft.getString("title"))
                .putExtra(Intent.EXTRA_TITLE, draft.getString("title"))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setClipData(ClipData.newRawUri(draft.getString("mediaName"), uri));
        return intent;
    }

    static void validateCover(Context context, Uri uri) throws Exception {
        validateCover(context, uri, new Reads());
    }

    static void validateCover(Context context, Uri uri, Reads reads) throws Exception {
        requireContent(uri);
        String mime = context.getContentResolver().getType(uri);
        if (mime == null || !mime.startsWith("image/")) throw new IOException("Select an image");
        // A bounds-only decode follows a capped read; no full bitmap or unbounded provider read.
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (InputStream input = reads.open(context, uri)) {
            if (input == null) throw new IOException("Image unavailable");
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelled");
                if (count > 8 * 1024 * 1024 - bytes.size()) throw new IOException("Cover exceeds 8 MiB");
                bytes.write(buffer, 0, count);
            }
        }
        android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        byte[] data = bytes.toByteArray();
        android.graphics.BitmapFactory.decodeByteArray(data, 0, data.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || options.outWidth > 4096 || options.outHeight > 4096)
            throw new IOException("Cover must be a readable image at most 4096 × 4096");
    }
}
