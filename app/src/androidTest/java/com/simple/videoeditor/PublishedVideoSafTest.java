package com.simple.videoeditor;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsProvider;
import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.UUID;

/** Wrapped SAF provider fault tests on API34; not API23 device/picker coverage. */
public final class PublishedVideoSafTest extends AndroidTestCase {
    private static final String AUTHORITY = "com.simple.videoeditor.publication-test";
    private static final Uri TREE = DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root");
    private Context isolated;
    private FaultDocuments provider;
    private File source;
    private Uri document;

    @Override protected void setUp() throws Exception {
        super.setUp();
        String namespace = "publication-saf-test-" + UUID.randomUUID();
        source = new File(getContext().getFilesDir(), namespace + ".mp4");
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(new byte[]{1, 2, 3, 4});
        }
        provider = new FaultDocuments(new File(getContext().getFilesDir(), namespace + "-public.mp4"));
        ProviderInfo info = new ProviderInfo();
        info.authority = AUTHORITY;
        info.exported = true;
        info.grantUriPermissions = true;
        info.readPermission = "android.permission.MANAGE_DOCUMENTS";
        info.writePermission = "android.permission.MANAGE_DOCUMENTS";
        provider.attachInfo(getContext(), info);
        ContentResolver resolver = ContentResolver.wrap(provider);
        isolated = new ContextWrapper(getContext()) {
            @Override public ContentResolver getContentResolver() { return resolver; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(namespace + name, mode);
            }
        };
        PublishedVideo.saveTree(isolated, TREE);
        document = DocumentsContract.createDocument(resolver,
                DocumentsContract.buildDocumentUriUsingTree(TREE, "root"), "video/mp4", "edited_test.mp4");
        assertNotNull(document);
        try (java.io.OutputStream output = resolver.openOutputStream(document)) {
            output.write(new byte[]{1, 2, 3, 4});
        }
        assertTrue(PublishedVideo.prefs(isolated).edit()
                .putString("private", source.getAbsolutePath())
                .putString("pending", document.toString())
                .putString("pendingLocation", "Selected directory")
                .putString("reserved", "edited_test.mp4")
                .putString("reservedTree", TREE.toString()).commit());
    }

    @Override protected void tearDown() throws Exception {
        provider.file.delete();
        source.delete();
        PublishedVideo.prefs(isolated).edit().clear().commit();
        super.tearDown();
    }

    public void testDeathAfterDocumentDeleteBeforeJournalClearIsIdempotent() throws Exception {
        DocumentsContract.deleteDocument(isolated.getContentResolver(), document);
        assertPrivateAndCleared();
        assertEquals(1, provider.deleteCalls);
        assertPrivateAndCleared();
    }

    public void testCompleteInterruptedSafCopyIsDeletedNotAdopted() throws Exception {
        assertPrivateAndCleared();
        assertEquals(1, provider.deleteCalls);
    }

    public void testRollbackDeleteDenialKeepsIntentUntilRetry() throws Exception {
        assertTrue(PublishedVideo.prefs(isolated).edit().putBoolean("rollback", true).commit());
        provider.denyDelete = true;
        assertBlocked(SecurityException.class);
        assertTrue(PublishedVideo.prefs(isolated).getBoolean("rollback", false));
        provider.denyDelete = false;
        assertPrivateAndCleared();
    }

    public void testAbsentDocumentWithPermissionDeniedIsNotConfirmedGone() throws Exception {
        assertTrue(provider.file.delete());
        provider.denyQuery = true;
        assertBlocked(SecurityException.class);
        provider.denyQuery = false;
        assertPrivateAndCleared();
    }

    public void testAbsentDocumentWithProviderFailureIsNotConfirmedGone() throws Exception {
        assertTrue(provider.file.delete());
        provider.failQuery = true;
        assertBlocked(IllegalStateException.class);
        provider.failQuery = false;
        assertPrivateAndCleared();
    }

    public void testNullListingKeepsJournalUntilProviderRecovers() throws Exception {
        assertTrue(provider.file.delete());
        provider.nullQuery = true;
        assertBlocked(IOException.class);
        provider.nullQuery = false;
        assertPrivateAndCleared();
    }

    public void testSuccessfulDeleteThatLeavesDocumentKeepsJournal() throws Exception {
        provider.noOpDelete = true;
        assertBlocked(IOException.class);
        assertTrue(provider.file.exists());
        provider.noOpDelete = false;
        assertPrivateAndCleared();
    }

    public void testDisappearanceDuringDeleteIsConfirmedByListing() throws Exception {
        provider.disappearAtDelete = true;
        assertPrivateAndCleared();
        assertEquals(1, provider.deleteCalls);
    }

    public void testFileNotFoundWhileDocumentStillListedIsNotSwallowed() throws Exception {
        provider.failDelete = true;
        assertBlocked(FileNotFoundException.class);
        assertTrue(provider.file.exists());
        provider.failDelete = false;
        assertPrivateAndCleared();
    }

    public void testFailureConfirmingDeletionKeepsJournalForRetry() throws Exception {
        provider.failQueryAfterDelete = true;
        assertBlocked(IllegalStateException.class);
        assertFalse(provider.file.exists());
        provider.failQuery = false;
        assertPrivateAndCleared();
        assertEquals(1, provider.deleteCalls);
    }

    public void testReservedOrphanRecoveryClearsRollbackIntent() throws Exception {
        assertTrue(PublishedVideo.prefs(isolated).edit().remove("pending")
                .remove("pendingLocation").putBoolean("rollback", true).commit());
        assertPrivateAndCleared();
        assertEquals(1, provider.deleteCalls);
    }

    public void testPipeBackedDocumentCopiesAllBytesAndClosesWithoutFsync() throws Exception {
        byte[] expected = new byte[196613];
        new java.util.Random(711).nextBytes(expected);
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write(expected);
        }
        provider.pipeWrite = true;
        PublishedVideo.copyAndVerify(isolated, source, document, new java.util.concurrent.atomic.AtomicBoolean());
        assertEquals("Reopening the provider must expose the completed copy", 0L, provider.pipeDone.getCount());
        if (provider.pipeError != null) throw new AssertionError(provider.pipeError);
        assertEquals(expected.length, provider.file.length());
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream input = isolated.getContentResolver().openInputStream(document)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        }
        assertTrue(java.util.Arrays.equals(expected, bytes.toByteArray()));
    }

    public void testRegularFileDescriptorRetainsFilesystemSync() throws Exception {
        try (FileOutputStream output = new FileOutputStream(provider.file)) {
            assertTrue(android.system.OsConstants.S_ISREG(
                    android.system.Os.fstat(output.getFD()).st_mode));
            output.write(new byte[]{9, 8, 7});
            PublishedVideo.syncOutput(output);
            assertTrue("Sync must not take descriptor ownership", output.getFD().valid());
        }
        assertEquals(3L, provider.file.length());
    }

    public void testRealRegularDescriptorSyncFailureRemainsError() throws Exception {
        // procfs reports S_IFREG but rejects fsync. Open only: do not alter the thread name.
        try (FileOutputStream output = new FileOutputStream("/proc/self/comm", true)) {
            assertTrue(android.system.OsConstants.S_ISREG(
                    android.system.Os.fstat(output.getFD()).st_mode));
            try {
                PublishedVideo.syncOutput(output);
                fail("Genuine regular-descriptor sync failure must propagate");
            } catch (java.io.SyncFailedException expected) {
                assertTrue(output.getFD().valid());
            }
        }
    }

    public void testCloseFailureAfterCompleteCopyRemainsError() throws Exception {
        java.io.OutputStream output = new java.io.FilterOutputStream(new FileOutputStream(provider.file)) {
            @Override public void close() throws IOException {
                super.close();
                throw new IOException("injected provider close failure");
            }
        };
        try {
            PublishedVideo.copy(source, output, new java.util.concurrent.atomic.AtomicBoolean());
            fail("Provider close failure must fail the copy");
        } catch (IOException expected) {
            assertEquals("injected provider close failure", expected.getMessage());
            assertEquals(source.length(), provider.file.length());
        }
    }

    private void assertBlocked(Class<? extends Exception> type) throws Exception {
        try {
            PublishedVideo.recover(isolated);
            fail("Unconfirmed SAF cleanup must keep the journal");
        } catch (Exception expected) {
            assertTrue("Expected " + type + ", got " + expected, type.isInstance(expected));
            assertTrue(PublishedVideo.prefs(isolated).contains("pending"));
            assertTrue(PublishedVideo.prefs(isolated).contains("reserved"));
            assertFalse(PublishedVideo.prefs(isolated).contains("uri"));
            assertTrue(source.isFile());
        }
    }

    private void assertPrivateAndCleared() throws Exception {
        PublishedVideo result = PublishedVideo.recover(isolated);
        assertNotNull(result);
        assertNull(result.uri);
        assertEquals(source, result.privateFile);
        assertTrue(source.length() > 0);
        assertFalse(provider.file.exists());
        SharedPreferences prefs = PublishedVideo.prefs(isolated);
        for (String key : new String[]{"pending", "pendingLocation", "reserved", "reservedTree", "rollback"}) {
            assertFalse("Journal key remains: " + key, prefs.contains(key));
        }
        assertEquals(TREE.toString(), prefs.getString("tree", null));
    }

    private static final class FaultDocuments extends DocumentsProvider {
        private static final String[] COLUMNS = {Document.COLUMN_DOCUMENT_ID,
                Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS};
        final File file;
        boolean denyDelete, denyQuery, failQuery, nullQuery, noOpDelete, disappearAtDelete;
        boolean failDelete, failQueryAfterDelete;
        boolean pipeWrite;
        final java.util.concurrent.CountDownLatch pipeDone = new java.util.concurrent.CountDownLatch(1);
        volatile Throwable pipeError;
        int deleteCalls;

        FaultDocuments(File file) { this.file = file; }
        @Override public boolean onCreate() { return true; }
        @Override public Cursor queryRoots(String[] projection) {
            return new MatrixCursor(projection == null ? new String[]{"root_id"} : projection);
        }
        @Override public boolean isChildDocument(String parent, String child) {
            return "root".equals(parent) && "video".equals(child);
        }
        private MatrixCursor cursor(String[] projection) {
            if (denyQuery) throw new SecurityException("injected listing permission denial");
            if (failQuery) throw new IllegalStateException("injected provider listing failure");
            return nullQuery ? null : new MatrixCursor(projection == null ? COLUMNS : projection);
        }
        private void add(MatrixCursor cursor, String id) {
            MatrixCursor.RowBuilder row = cursor.newRow();
            boolean root = "root".equals(id);
            for (String column : cursor.getColumnNames()) {
                switch (column) {
                    case Document.COLUMN_DOCUMENT_ID: row.add(id); break;
                    case Document.COLUMN_DISPLAY_NAME: row.add(root ? "Output" : "edited_test.mp4"); break;
                    case Document.COLUMN_MIME_TYPE: row.add(root ? Document.MIME_TYPE_DIR : "video/mp4"); break;
                    case Document.COLUMN_FLAGS:
                        row.add(root ? Document.FLAG_DIR_SUPPORTS_CREATE : Document.FLAG_SUPPORTS_DELETE); break;
                    default: row.add(null);
                }
            }
        }
        @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
            MatrixCursor result = cursor(projection);
            if (!"root".equals(id) && !file.exists()) throw new FileNotFoundException(id);
            if (result != null) add(result, id);
            return result;
        }
        @Override public Cursor queryChildDocuments(String parent, String[] projection, String sort) {
            MatrixCursor result = cursor(projection);
            if (result != null && file.exists()) add(result, "video");
            return result;
        }
        @Override public String createDocument(String parent, String mime, String name) throws FileNotFoundException {
            try {
                if (!file.createNewFile()) throw new IOException("Document already exists");
            } catch (IOException error) {
                throw new FileNotFoundException(error.toString());
            }
            return "video";
        }
        @Override public ParcelFileDescriptor openDocument(String id, String mode,
                android.os.CancellationSignal signal) throws FileNotFoundException {
            if (pipeWrite && mode.contains("w")) {
                try {
                    ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                    new Thread(() -> {
                        try (java.io.InputStream input = new ParcelFileDescriptor.AutoCloseInputStream(pipe[0]);
                             FileOutputStream output = new FileOutputStream(file)) {
                            Thread.sleep(100);
                            byte[] buffer = new byte[8192];
                            int count;
                            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                        } catch (Throwable error) {
                            pipeError = error;
                        } finally {
                            pipeDone.countDown();
                        }
                    }, "publication-test-pipe").start();
                    return pipe[1];
                } catch (IOException error) {
                    throw new FileNotFoundException(error.toString());
                }
            }
            if (pipeWrite) {
                try {
                    if (!pipeDone.await(10, java.util.concurrent.TimeUnit.SECONDS) || pipeError != null) {
                        throw new FileNotFoundException("Pipe consumer did not complete: " + pipeError);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new FileNotFoundException(error.toString());
                }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public void deleteDocument(String id) throws FileNotFoundException {
            deleteCalls++;
            if (denyDelete) throw new SecurityException("injected document deletion denial");
            if (disappearAtDelete) file.delete();
            if (failDelete || !file.exists()) throw new FileNotFoundException("injected missing document");
            if (!noOpDelete && !file.delete()) throw new FileNotFoundException("Cannot delete document");
            if (failQueryAfterDelete) failQuery = true;
        }
    }
}
