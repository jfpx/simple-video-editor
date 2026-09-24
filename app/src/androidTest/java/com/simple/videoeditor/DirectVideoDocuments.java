package com.simple.videoeditor;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.OsConstants;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

/** Test-only SAF authority, in the test APK, never packaged in the editor. */
public final class DirectVideoDocuments extends DocumentsProvider {
    static final String AUTHORITY = "com.simple.videoeditor.test.directdocs";
    private static final String[] DOCUMENT = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED};
    private byte[] fixture;
    private Handler handler;
    private volatile boolean denied;
    private final AtomicLong reads = new AtomicLong(), opens = new AtomicLong();

    @Override public boolean onCreate() {
        try {
            Context target = getContext().createPackageContext("com.simple.videoeditor", 0);
            try (InputStream input = target.getAssets().open("video-oracle/standard.mp4");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                fixture = bytes.toByteArray();
            }
            HandlerThread thread = new HandlerThread("direct-documents");
            thread.start();
            handler = new Handler(thread.getLooper());
            return true;
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? new String[]{
                DocumentsContract.Root.COLUMN_ROOT_ID, DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                DocumentsContract.Root.COLUMN_TITLE, DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPES} : projection);
        MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, "direct")
                .add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "root")
                .add(DocumentsContract.Root.COLUMN_TITLE, "Direct URI fixtures")
                .add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
                .add(DocumentsContract.Root.COLUMN_MIME_TYPES, "video/mp4");
        return cursor;
    }

    private void add(MatrixCursor cursor, String id) {
        boolean root = id.equals("root");
        cursor.newRow().add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
                .add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, root ? "Direct URI fixtures" : "Direct URI " + id + ".mp4")
                .add(DocumentsContract.Document.COLUMN_MIME_TYPE, root ? DocumentsContract.Document.MIME_TYPE_DIR : "video/mp4")
                .add(DocumentsContract.Document.COLUMN_FLAGS, 0)
                .add(DocumentsContract.Document.COLUMN_SIZE, id.equals("unknown") ? null : length(id))
                .add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 1234567890000L);
    }

    @Override public Cursor queryDocument(String id, String[] projection) {
        if (denied) throw new SecurityException("Test permission revoked");
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT : projection);
        add(cursor, id);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) {
        MatrixCursor cursor = new MatrixCursor(projection == null ? DOCUMENT : projection);
        for (String id : new String[]{"seekable", "pipe", "slowpipe", "unknown", "large"}) add(cursor, id);
        return cursor;
    }

    @Override public boolean isChildDocument(String parent, String child) { return parent.equals("root"); }

    private long length(String id) { return id.equals("large") ? 5L * 1024 * 1024 * 1024 + 17 : fixture.length; }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal cancellation)
            throws FileNotFoundException {
        if (denied) throw new SecurityException("Test permission revoked");
        if (!mode.equals("r")) throw new FileNotFoundException("Read only test document");
        if (cancellation != null) cancellation.throwIfCanceled();
        opens.incrementAndGet();
        try {
            if (id.endsWith("pipe")) {
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createReliablePipe();
                new Thread(() -> {
                    try (OutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                        for (int offset = 0; offset < fixture.length; offset += 1024) {
                            if (id.equals("slowpipe")) Thread.sleep(25);
                            int size = Math.min(1024, fixture.length - offset);
                            output.write(fixture, offset, size);
                            reads.addAndGet(size);
                        }
                    } catch (Exception closedByReader) { /* Probe/cancel closes the read end. */ }
                }, "direct-pipe").start();
                return pipe[0];
            }
            return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(
                    ParcelFileDescriptor.MODE_READ_ONLY, new ProxyFileDescriptorCallback() {
                        @Override public long onGetSize() { return length(id); }
                        @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                            if (denied) throw new ErrnoException("read", OsConstants.EACCES);
                            int count = (int) Math.min(size, Math.max(0, length(id) - offset));
                            java.util.Arrays.fill(data, 0, count, (byte) 0);
                            if (offset < fixture.length)
                                System.arraycopy(fixture, (int) offset, data, 0, Math.min(count, fixture.length - (int) offset));
                            reads.addAndGet(count);
                            return count;
                        }
                        @Override public void onRelease() {}
                    }, handler);
        } catch (java.io.IOException error) { throw new FileNotFoundException(error.toString()); }
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        if (method.equals("direct.reset")) { reads.set(0); opens.set(0); denied = false; }
        else if (method.equals("direct.deny")) denied = true;
        else if (!method.equals("direct.stats")) return super.call(method, arg, extras);
        Bundle result = new Bundle();
        result.putLong("readBytes", reads.get());
        result.putLong("opens", opens.get());
        result.putLong("fixtureBytes", fixture.length);
        return result;
    }
}
