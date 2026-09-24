package com.simple.videoeditor;

import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.util.Objects;

public final class VideoSource {
    private static final int SAVE_VERSION = 1;
    private static final String SAVE_KEY_VERSION = "videoSource.version";
    private static final String SAVE_KEY_URI = "videoSource.uri";
    private static final String SAVE_KEY_OWNED_PATH = "videoSource.ownedPath";
    private static final String SAVE_KEY_NAME = "videoSource.name";
    private static final String SAVE_KEY_SIZE = "videoSource.sizeBytes";
    private static final String SAVE_KEY_PERSISTENT = "videoSource.persistentRead";
    private static final String SAVE_KEY_LAST_MODIFIED = "videoSource.lastModifiedMs";
    private static final String SAVE_KEY_PROVENANCE = "videoSource.provenance";
    private static final String SAVE_KEY_SELECTION = "videoSource.selection";
    private static final String PROVENANCE_DOCUMENT = "document";
    private static final String PROVENANCE_OWNED = "owned";

    public final Uri uri;
    public final File ownedFile;
    public final String name;
    public final long sizeBytes;
    public final boolean persistentRead;

    private final long lastModifiedMs;
    private final String provenance;
    private final String selection;

    private VideoSource(Uri uri, File ownedFile, String name, long sizeBytes,
                        boolean persistentRead, long lastModifiedMs, String provenance) {
        this(uri, ownedFile, name, sizeBytes, persistentRead, lastModifiedMs, provenance,
                java.util.UUID.randomUUID().toString());
    }

    private VideoSource(Uri uri, File ownedFile, String name, long sizeBytes,
                        boolean persistentRead, long lastModifiedMs, String provenance, String selection) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.ownedFile = ownedFile;
        this.name = Objects.requireNonNull(name, "name");
        this.sizeBytes = sizeBytes;
        this.persistentRead = persistentRead;
        this.lastModifiedMs = lastModifiedMs;
        this.provenance = Objects.requireNonNull(provenance, "provenance");
        this.selection = Objects.requireNonNull(selection, "selection");
    }

    String selectionKey() { return selection; }

    public static VideoSource document(Context context, Uri uri) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(uri, "uri");
        MediaCopy.checkCancelled();
        if (isFileUri(uri)) {
            File file = requireExistingFile(requireUriFile(uri));
            return new VideoSource(Uri.fromFile(file), null, file.getName(), file.length(), false,
                    normalizeTimestamp(file.lastModified()), PROVENANCE_DOCUMENT);
        }
        QueriedMetadata metadata = queryMetadata(context, uri);
        if (metadata.virtualDocument) {
            requireVideoStreamType(context, uri);
            throw new NeedsLocalCopyException(uri, metadata.name, metadata.sizeBytes, Reason.VIRTUAL_DOCUMENT);
        }
        inspectSeekableDescriptor(context, uri, metadata);
        return new VideoSource(uri, null, metadata.name, metadata.sizeBytes,
                hasPersistedReadGrant(context, uri), metadata.lastModifiedMs, PROVENANCE_DOCUMENT);
    }

    public static VideoSource owned(Context context, File file, String name) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(file, "file");
        MediaCopy.checkCancelled();
        File canonicalCache = context.getCacheDir().getCanonicalFile();
        File canonicalFile = file.getCanonicalFile();
        if (!isUnderDirectory(canonicalFile, canonicalCache)) {
            throw new IOException("Owned video must stay inside the app cache directory");
        }
        if (!canonicalFile.isFile()) {
            throw new FileNotFoundException("Owned video is missing: " + canonicalFile);
        }
        String resolvedName = name == null || name.trim().isEmpty() ? canonicalFile.getName() : name.trim();
        return new VideoSource(Uri.fromFile(canonicalFile), canonicalFile, resolvedName, canonicalFile.length(),
                false, normalizeTimestamp(canonicalFile.lastModified()), PROVENANCE_OWNED);
    }

    /** Attempts to persist read access when the actual result flags include both read and persistable grants; never releases shared grants. */
    public static boolean retainReadGrant(Context context, Uri uri, int actualIntentFlags) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(uri, "uri");
        if (!"content".equals(uri.getScheme())) return false;
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION;
        if ((actualIntentFlags & required) != required) return false;
        try {
            context.getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {}
        return hasPersistedReadGrant(context, uri);
    }

    public void checkCurrent(Context context) throws IOException {
        try { checkCurrentSource(context); }
        catch (NeedsLocalCopyException | InterruptedIOException error) { throw error; }
        catch (IOException | SecurityException error) { throw new SourceUnavailableException(error); }
    }

    private void checkCurrentSource(Context context) throws IOException {
        Objects.requireNonNull(context, "context");
        MediaCopy.checkCancelled();
        if (ownedFile != null) {
            File canonicalCache = context.getCacheDir().getCanonicalFile();
            File canonicalFile = ownedFile.getCanonicalFile();
            if (!isUnderDirectory(canonicalFile, canonicalCache)) {
                throw new IOException("Owned video left the app cache directory");
            }
            if (!canonicalFile.isFile()) {
                throw new FileNotFoundException("Owned video no longer exists: " + canonicalFile);
            }
            verifyCurrentSize(canonicalFile.length());
            verifyCurrentModified(normalizeTimestamp(canonicalFile.lastModified()));
            return;
        }
        if (persistentRead && !hasPersistedReadGrant(context, uri)) {
            throw new IOException("Persisted read access is no longer available");
        }
        if (isFileUri(uri)) {
            File file = requireExistingFile(requireUriFile(uri));
            verifyCurrentSize(file.length());
            verifyCurrentModified(normalizeTimestamp(file.lastModified()));
            return;
        }
        QueriedMetadata current = queryMetadata(context, uri);
        if (current.virtualDocument) {
            throw new NeedsLocalCopyException(uri, current.name, current.sizeBytes, Reason.VIRTUAL_DOCUMENT);
        }
        verifyCurrentSize(current.sizeBytes);
        verifyCurrentModified(current.lastModifiedMs);
        inspectSeekableDescriptor(context, uri, current);
    }

    public static void setDataSource(Context context, MediaMetadataRetriever retriever, Uri uri)
            throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(retriever, "retriever");
        Objects.requireNonNull(uri, "uri");
        MediaCopy.checkCancelled();
        try {
            if (isFileUri(uri)) {
                retriever.setDataSource(requireExistingFile(requireUriFile(uri)).getAbsolutePath());
                return;
            }
            QueriedMetadata metadata = queryMetadata(context, uri);
            if (metadata.virtualDocument) {
                throw new NeedsLocalCopyException(uri, metadata.name, metadata.sizeBytes, Reason.VIRTUAL_DOCUMENT);
            }
            try (AssetFileDescriptor descriptor = openAssetDescriptor(context, uri)) {
                Range range = descriptorRange(uri, metadata, descriptor);
                retriever.setDataSource(descriptor.getFileDescriptor(), range.startOffset, range.length);
            }
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Video source opening cancelled");
            }
            throw new IOException("Cannot open source for metadata retrieval", error);
        }
    }

    public static void setDataSource(Context context, MediaExtractor extractor, Uri uri)
            throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(extractor, "extractor");
        Objects.requireNonNull(uri, "uri");
        MediaCopy.checkCancelled();
        try {
            if (isFileUri(uri)) {
                extractor.setDataSource(requireExistingFile(requireUriFile(uri)).getAbsolutePath());
                return;
            }
            QueriedMetadata metadata = queryMetadata(context, uri);
            if (metadata.virtualDocument) {
                throw new NeedsLocalCopyException(uri, metadata.name, metadata.sizeBytes, Reason.VIRTUAL_DOCUMENT);
            }
            try (AssetFileDescriptor descriptor = openAssetDescriptor(context, uri)) {
                Range range = descriptorRange(uri, metadata, descriptor);
                extractor.setDataSource(descriptor.getFileDescriptor(), range.startOffset, range.length);
            }
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Video source opening cancelled");
            }
            throw new IOException("Cannot open source for media extraction", error);
        }
    }

    /** Deletes only app-cache files created through owned(...); external content/grants remain untouched. */
    public void dispose() {
        if (ownedFile != null && PROVENANCE_OWNED.equals(provenance)) {
            ownedFile.delete();
        }
    }

    public Bundle save() {
        Bundle bundle = new Bundle();
        bundle.putInt(SAVE_KEY_VERSION, SAVE_VERSION);
        bundle.putString(SAVE_KEY_URI, uri.toString());
        bundle.putString(SAVE_KEY_OWNED_PATH, ownedFile == null ? null : ownedFile.getAbsolutePath());
        bundle.putString(SAVE_KEY_NAME, name);
        bundle.putLong(SAVE_KEY_SIZE, sizeBytes);
        bundle.putBoolean(SAVE_KEY_PERSISTENT, persistentRead);
        bundle.putLong(SAVE_KEY_LAST_MODIFIED, lastModifiedMs);
        bundle.putString(SAVE_KEY_PROVENANCE, provenance);
        bundle.putString(SAVE_KEY_SELECTION, selection);
        return bundle;
    }

    public static VideoSource restore(Context context, Bundle bundle) throws IOException {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(bundle, "bundle");
        if (bundle.getInt(SAVE_KEY_VERSION, -1) != SAVE_VERSION) {
            throw new IOException("Unsupported saved video source version");
        }
        String uriValue = bundle.getString(SAVE_KEY_URI);
        String provenance = bundle.getString(SAVE_KEY_PROVENANCE);
        String name = bundle.getString(SAVE_KEY_NAME);
        if (uriValue == null || provenance == null || name == null) {
            throw new IOException("Saved video source is incomplete");
        }
        Uri uri = Uri.parse(uriValue);
        String ownedPath = bundle.getString(SAVE_KEY_OWNED_PATH);
        File ownedFile = ownedPath == null ? null : new File(ownedPath);
        VideoSource source = new VideoSource(uri, ownedFile, name,
                bundle.getLong(SAVE_KEY_SIZE, -1L),
                bundle.getBoolean(SAVE_KEY_PERSISTENT, false),
                bundle.getLong(SAVE_KEY_LAST_MODIFIED, -1L),
                provenance, bundle.getString(SAVE_KEY_SELECTION, java.util.UUID.randomUUID().toString()));
        if (!PROVENANCE_DOCUMENT.equals(provenance) && !PROVENANCE_OWNED.equals(provenance)) {
            throw new IOException("Saved video source provenance is unknown");
        }
        if (PROVENANCE_OWNED.equals(provenance) != (ownedFile != null)) {
            throw new IOException("Saved video source provenance is inconsistent");
        }
        if (ownedFile != null && (!Uri.fromFile(ownedFile).equals(uri)
                || !isUnderDirectory(ownedFile.getCanonicalFile(), context.getCacheDir().getCanonicalFile()))) {
            throw new IOException("Saved owned source is outside the app cache or has a different Uri");
        }
        return source;
    }

    public enum Reason {
        NON_SEEKABLE,
        VIRTUAL_DOCUMENT
    }

    public static final class SourceUnavailableException extends IOException {
        SourceUnavailableException(Exception cause) { super("Original source unavailable or changed; reselect the document", cause); }
    }

    public static final class NeedsLocalCopyException extends IOException {
        public final Uri uri;
        public final String name;
        public final long sizeBytes;
        public final Reason reason;

        NeedsLocalCopyException(Uri uri, String name, long sizeBytes, Reason reason) {
            super(reason == Reason.VIRTUAL_DOCUMENT
                    ? "Video source is a virtual document and needs a local copy"
                    : "Video source is not seekable and needs a local copy");
            this.uri = Objects.requireNonNull(uri, "uri");
            this.name = Objects.requireNonNull(name, "name");
            this.sizeBytes = sizeBytes;
            this.reason = Objects.requireNonNull(reason, "reason");
        }
    }

    private void verifyCurrentSize(long currentSize) throws IOException {
        if (sizeBytes >= 0 && currentSize >= 0 && currentSize != sizeBytes) {
            throw new IOException("Video source size changed");
        }
    }

    private void verifyCurrentModified(long currentLastModifiedMs) throws IOException {
        if (lastModifiedMs >= 0 && currentLastModifiedMs >= 0 && currentLastModifiedMs != lastModifiedMs) {
            throw new IOException("Video source was modified");
        }
    }

    private static boolean hasPersistedReadGrant(Context context, Uri uri) {
        for (UriPermission permission : context.getContentResolver().getPersistedUriPermissions()) {
            if (permission.isReadPermission() && uri.equals(permission.getUri())) return true;
        }
        return false;
    }

    private static QueriedMetadata queryMetadata(Context context, Uri uri) throws IOException {
        if (isFileUri(uri)) {
            File file = requireExistingFile(requireUriFile(uri));
            return new QueriedMetadata(file.getName(), file.length(),
                    normalizeTimestamp(file.lastModified()), false);
        }
        if (!"content".equals(uri.getScheme())) {
            throw new FileNotFoundException("Unsupported video Uri scheme: " + uri);
        }
        String[] projection = DocumentsContract.isDocumentUri(context, uri)
                ? new String[] {
                OpenableColumns.DISPLAY_NAME,
                OpenableColumns.SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS
        }
                : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        try (AccessCancellation cancellation = new AccessCancellation();
             Cursor cursor = context.getContentResolver().query(uri, projection, null, null, null, cancellation.signal)) {
            MediaCopy.checkCancelled();
            String name = fallbackName(uri);
            long sizeBytes = -1L;
            long lastModifiedMs = -1L;
            boolean virtual = false;
            if (cursor != null && cursor.moveToFirst()) {
                int displayNameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (displayNameColumn >= 0 && !cursor.isNull(displayNameColumn)) {
                    name = cursor.getString(displayNameColumn);
                }
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    sizeBytes = cursor.getLong(sizeColumn);
                }
                int modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED);
                if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) {
                    lastModifiedMs = normalizeTimestamp(cursor.getLong(modifiedColumn));
                }
                int flagsColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_FLAGS);
                if (flagsColumn >= 0 && !cursor.isNull(flagsColumn)) {
                    int flags = cursor.getInt(flagsColumn);
                    virtual = android.os.Build.VERSION.SDK_INT >= 24
                            && (flags & DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT) != 0;
                }
            }
            return new QueriedMetadata(name == null || name.trim().isEmpty() ? fallbackName(uri) : name,
                    sizeBytes, lastModifiedMs, virtual);
        } catch (InterruptedIOException error) {
            throw error;
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Video source inspection cancelled");
            }
            throw new IOException("Cannot inspect video source metadata", error);
        }
    }

    private static void inspectSeekableDescriptor(Context context, Uri uri, QueriedMetadata metadata)
            throws IOException {
        if (isFileUri(uri)) return;
        try (AssetFileDescriptor descriptor = openAssetDescriptor(context, uri)) {
            descriptorRange(uri, metadata, descriptor);
        }
    }

    private static AssetFileDescriptor openAssetDescriptor(Context context, Uri uri) throws IOException {
        MediaCopy.checkCancelled();
        try (AccessCancellation cancellation = new AccessCancellation()) {
            AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r", cancellation.signal);
            if (descriptor == null) {
                throw new FileNotFoundException("Content resolver returned no descriptor for " + uri);
            }
            return descriptor;
        } catch (SecurityException error) {
            throw new IOException("Read access to video source was denied", error);
        }
    }

    private static Range descriptorRange(Uri uri, QueriedMetadata metadata, AssetFileDescriptor descriptor)
            throws IOException {
        ParcelFileDescriptor parcelFileDescriptor = descriptor.getParcelFileDescriptor();
        if (parcelFileDescriptor == null) {
            throw new FileNotFoundException("Content resolver returned no file descriptor for " + uri);
        }
        long startOffset = descriptor.getStartOffset();
        if (startOffset < 0) {
            throw new IOException("Content descriptor returned a negative start offset");
        }
        long declaredLength = descriptor.getLength();
        FileDescriptorPosition position = inspectFileDescriptor(uri, metadata, parcelFileDescriptor, startOffset);
        if (declaredLength >= 0) {
            if (declaredLength > position.length) throw new IOException("Content descriptor range exceeds file length");
            return new Range(startOffset, declaredLength);
        }
        return new Range(startOffset, position.length);
    }

    static VideoSource stage(Context context, Uri uri, File target, long maxBytes,
                             MediaCopy.Progress progress) throws IOException {
        QueriedMetadata metadata = queryMetadata(context, uri);
        if (maxBytes > 0 && metadata.sizeBytes > maxBytes) throw new IOException("Source exceeds snapshot byte limit");
        if (metadata.sizeBytes >= 0 && metadata.sizeBytes > context.getCacheDir().getUsableSpace())
            throw new IOException("Insufficient free space for the source copy");
        File cache = context.getCacheDir().getCanonicalFile();
        if (!isUnderDirectory(target.getCanonicalFile(), cache) || !target.createNewFile())
            throw new IOException("Copy destination must be a new app-cache file");
        boolean complete = false;
        try {
            try (InputStream input = openReadStream(context, uri, metadata.virtualDocument);
                 FileOutputStream output = new FileOutputStream(target)) {
                long copied = MediaCopy.copy(input, output, maxBytes, bytes -> {
                    if (context.getCacheDir().getUsableSpace() < 1024 * 1024)
                        throw new IllegalStateException("Insufficient free space while copying");
                    progress.copied(bytes);
                });
                if (!metadata.virtualDocument && metadata.sizeBytes >= 0 && copied != metadata.sizeBytes)
                    throw new IOException("Source size changed or copy was incomplete");
                output.getFD().sync();
            }
            MediaCopy.checkCancelled();
            VideoSource source = owned(context, target, metadata.name);
            complete = true;
            return source;
        } finally {
            if (!complete) target.delete();
        }
    }

    static InputStream openReadStream(Context context, Uri uri) throws IOException {
        return openReadStream(context, uri, false);
    }

    private static InputStream openReadStream(Context context, Uri uri, boolean virtual) throws IOException {
        AccessCancellation cancellation = new AccessCancellation();
        AssetFileDescriptor descriptor = null;
        try {
            MediaCopy.checkCancelled();
            descriptor = virtual ? context.getContentResolver().openTypedAssetFileDescriptor(
                    uri, requireVideoStreamType(context, uri), null, cancellation.signal)
                    : context.getContentResolver().openAssetFileDescriptor(uri, "r", cancellation.signal);
            if (descriptor == null) throw new IOException("Document provider returned no stream");
            AssetFileDescriptor opened = descriptor;
            return new java.io.FilterInputStream(opened.createInputStream()) {
                @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                    StructPollfd poll = new StructPollfd();
                    poll.fd = opened.getFileDescriptor();
                    poll.events = (short) OsConstants.POLLIN;
                    try {
                        do {
                            MediaCopy.checkCancelled();
                        } while (Os.poll(new StructPollfd[]{poll}, 250) == 0);
                    } catch (ErrnoException error) { throw new IOException("Cannot read source stream", error); }
                    MediaCopy.checkCancelled();
                    return in.read(buffer, offset, length);
                }
                @Override public void close() throws IOException {
                    try { super.close(); }
                    finally {
                        cancellation.close();
                        opened.close();
                    }
                }
            };
        } catch (IOException | RuntimeException error) {
            cancellation.close();
            if (descriptor != null) try { descriptor.close(); } catch (IOException close) { error.addSuppressed(close); }
            throw error;
        }
    }

    private static String requireVideoStreamType(Context context, Uri uri) throws IOException {
        String[] types = context.getContentResolver().getStreamTypes(uri, "video/*");
        if (types == null || types.length == 0) throw new IOException("Virtual document has no video representation; reselect");
        return types[0];
    }

    private static final java.util.concurrent.ScheduledExecutorService CANCELLATION =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(action -> {
                Thread thread = new Thread(action, "source-access-cancellation");
                thread.setDaemon(true);
                return thread;
            });

    private static final class AccessCancellation implements AutoCloseable {
        final CancellationSignal signal = new CancellationSignal();
        final Thread owner = Thread.currentThread();
        final java.util.concurrent.ScheduledFuture<?> check = CANCELLATION.scheduleWithFixedDelay(() -> {
            if (owner.isInterrupted()) signal.cancel();
        }, 0, 100, java.util.concurrent.TimeUnit.MILLISECONDS);
        @Override public void close() { check.cancel(false); }
    }

    private static FileDescriptorPosition inspectFileDescriptor(Uri uri, QueriedMetadata metadata,
                                                                ParcelFileDescriptor descriptor, long startOffset)
            throws IOException {
        MediaCopy.checkCancelled();
        try {
            long current = Os.lseek(descriptor.getFileDescriptor(), 0L, OsConstants.SEEK_CUR);
            long end = Os.lseek(descriptor.getFileDescriptor(), 0L, OsConstants.SEEK_END);
            Os.lseek(descriptor.getFileDescriptor(), current, OsConstants.SEEK_SET);
            if (end < startOffset) {
                throw new IOException("Content descriptor size is smaller than its declared start offset");
            }
            return new FileDescriptorPosition(end - startOffset);
        } catch (ErrnoException error) {
            if (error.errno == OsConstants.ESPIPE) {
                throw new NeedsLocalCopyException(uri, metadata.name, metadata.sizeBytes, Reason.NON_SEEKABLE);
            }
            throw new IOException("Cannot inspect content descriptor size", error);
        }
    }

    private static File requireUriFile(Uri uri) throws IOException {
        String path = uri.getPath();
        if (path == null || path.trim().isEmpty()) {
            throw new FileNotFoundException("File Uri has no path: " + uri);
        }
        return new File(path);
    }

    private static File requireExistingFile(File file) throws IOException {
        File canonical = file.getCanonicalFile();
        if (!canonical.isFile()) {
            throw new FileNotFoundException("Video file is missing: " + canonical);
        }
        return canonical;
    }

    private static boolean isFileUri(Uri uri) {
        String scheme = uri.getScheme();
        return scheme == null || "file".equals(scheme);
    }

    private static boolean isUnderDirectory(File file, File directory) throws IOException {
        File current = file;
        while (current != null) {
            if (directory.equals(current)) return true;
            current = current.getParentFile();
        }
        return false;
    }

    private static long normalizeTimestamp(long timestampMs) {
        return timestampMs > 0 ? timestampMs : -1L;
    }

    private static String fallbackName(Uri uri) {
        String last = uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? uri.toString() : last;
    }

    private static final class QueriedMetadata {
        final String name;
        final long sizeBytes;
        final long lastModifiedMs;
        final boolean virtualDocument;

        QueriedMetadata(String name, long sizeBytes, long lastModifiedMs, boolean virtualDocument) {
            this.name = Objects.requireNonNull(name, "name");
            this.sizeBytes = sizeBytes;
            this.lastModifiedMs = lastModifiedMs;
            this.virtualDocument = virtualDocument;
        }
    }

    private static final class Range {
        final long startOffset;
        final long length;

        Range(long startOffset, long length) {
            this.startOffset = startOffset;
            this.length = length;
        }
    }

    private static final class FileDescriptorPosition {
        final long length;

        FileDescriptorPosition(long length) {
            this.length = length;
        }
    }
}
