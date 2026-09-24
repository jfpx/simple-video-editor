package com.simple.videoeditor;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.test.AndroidTestCase;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** API 29+ wrapped fake provider tests I/O, not picker grants; the host kill test uses real DocumentsUI. */
public final class SavedReportTest extends AndroidTestCase {
    private Context isolated;
    private DiskProvider provider;
    private Uri uri;
    private SavedReport owned;

    private SavedReport report(String name) {
        owned = new SavedReport(isolated, uri, name);
        return owned;
    }

    @Override protected void tearDown() throws Exception {
        ReportJournal.faultInjector = null;
        if (owned != null) owned.release();
        super.tearDown();
    }

    @Override protected void setUp() throws Exception {
        super.setUp();
        File root = new File(getContext().getFilesDir(), "report-tests-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        provider = new DiskProvider(new File(root, "external.txt"));
        ProviderInfo info = new ProviderInfo();
        info.authority = "report-test";
        provider.attachInfo(getContext(), info);
        ContentResolver resolver = ContentResolver.wrap(provider);
        String prefs = root.getName();
        isolated = new ContextWrapper(getContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
            @Override public ContentResolver getContentResolver() { return resolver; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(prefs + name, mode);
            }
        };
        uri = Uri.parse("content://report-test/report.txt");
    }

    public void testClosedIncrementalAppendAndPrivateSnapshot() throws Exception {
        SavedReport report = report("fake disk provider");
        report.checkpoint("RUN first\n", false);
        String first = read(new FileInputStream(provider.file));
        report.checkpoint("RUN first\nPASS first\nSTAGE decode second\n", false);
        String second = read(new FileInputStream(provider.file));
        assertTrue(second.startsWith(first));
        assertEquals(1, second.split("RUN first", -1).length - 1);
        assertTrue(second.endsWith("END CHECKPOINT\n"));
        assertTrue(second.contains("STAGE decode second"));
        assertTrue(read(ReportJournal.open(SavedReport.snapshot(isolated))).contains("PASS first"));
        assertTrue(provider.opens >= 2);
    }

    public void testExternalFailureKeepsPrivateCheckpointAndStopsFurtherWrites() throws Exception {
        SavedReport report = report("fake failing provider");
        report.checkpoint("PASS previous\n", false);
        String before = read(new FileInputStream(provider.file));
        provider.fail = true;
        try {
            report.checkpoint("PASS previous\nSTAGE risky\n", false);
            fail("must not silently fall back to private-only reporting");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("EXTERNAL TXT SAVE FAILED"));
        }
        String privateText = read(ReportJournal.open(SavedReport.snapshot(isolated)));
        assertTrue(privateText.contains("STAGE risky"));
        assertTrue(privateText.contains("EXTERNAL TXT SAVE FAILED"));
        assertTrue(SavedReport.location(isolated).contains("EXTERNAL TXT SAVE FAILED"));
        assertEquals(before, read(new FileInputStream(provider.file)));
        provider.fail = false;
        try {
            report.checkpoint("must not continue", true);
            fail("saving failure is sticky for this run");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("previously failed"));
        }
    }

    public void testNonSeekableProviderRejectedBeforeRiskyWork() throws Exception {
        provider.pipe = true;
        SavedReport report = report("fake pipe provider");
        try {
            report.checkpoint("STAGE prepare\n", false);
            fail("pipe cannot provide durable disk checkpoint");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("EXTERNAL TXT SAVE FAILED"));
        }
        assertTrue(read(ReportJournal.open(SavedReport.snapshot(isolated))).contains("STAGE prepare"));
    }

    public void testBestEffortUncaughtPreservesEarlierCheckpoints() throws Exception {
        SavedReport report = report("fake disk provider");
        report.checkpoint("STAGE decode\n", false);
        String before = read(new FileInputStream(provider.file));
        SavedReport.recordUncaught(new IllegalStateException("synthetic error"));
        String after = read(new FileInputStream(provider.file));
        assertTrue(after.startsWith(before));
        assertTrue(after.contains("BEST-EFFORT UNCAUGHT JAVA ERROR"));
        assertTrue(after.contains("IllegalStateException"));
        report.checkpoint("TERMINAL\n", true);
        assertTrue(SavedReport.location(isolated).contains("TERMINAL"));
    }

    public void testNewDestinationCannotStealPendingReportOwnership() throws Exception {
        SavedReport report = report("first selection");
        report.checkpoint("STAGE prepare\n", false);
        try {
            SavedReport.create(isolated, uri, 3);
            fail("another activity cannot replace the pending or running report");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("still owns cleanup"));
        }
        report.checkpoint("STAGE prepare\nTERMINAL\n", true);
    }

    public void testProviderRuntimeBecomesCheckedStickyFailureWithCompletePrivateEvent() throws Exception {
        SavedReport report = report("revoked provider");
        report.appendCheckpoint("first\n", false);
        provider.revoked = true;
        try {
            report.appendCheckpoint("second complete event\n", false);
            fail("revoked access must stop the suite");
        } catch (IOException expected) {
            assertTrue(expected.getCause() instanceof SecurityException);
        }
        assertTrue(read(ReportJournal.open(SavedReport.snapshot(isolated))).contains("second complete event"));
        provider.revoked = false;
        try {
            report.appendCheckpoint("must not continue\n", false);
            fail("failure must be sticky");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("previously failed"));
        }
    }

    public void testPrivateStorageFailureDoesNotWriteExternalOrBecomeRuntimeAbort() throws Exception {
        assertTrue(new File(isolated.getFilesDir(), "selftest").createNewFile());
        SavedReport report = report("private storage unavailable");
        try {
            report.appendCheckpoint("must be private-durable first\n", false);
            fail("private storage failure must stop the suite");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("PRIVATE TXT SAVE FAILED"));
        }
        assertFalse(provider.file.exists());
        assertEquals(0, provider.opens);
    }

    public void testFirstCheckpointMarkerFailurePreservesPreviousReportAndExternal() throws Exception {
        ReportJournal.replace(SavedReport.snapshot(isolated), "previous complete report\n");
        ReportJournal.faultInjector = stage -> {
            if ("marker-write".equals(stage)) throw new IOException("injected marker failure");
        };
        SavedReport saved = report("new report");
        try {
            saved.appendCheckpoint("new first checkpoint\n", false);
            fail("private publication failure must stop");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("PRIVATE TXT SAVE FAILED"));
        } finally { ReportJournal.faultInjector = null; }
        assertEquals("previous complete report\n", read(ReportJournal.open(SavedReport.snapshot(isolated))));
        assertEquals(0, provider.opens);
        assertFalse(provider.file.exists());
        try {
            saved.appendCheckpoint("retry forbidden\n", false);
            fail("failure is sticky");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("previously failed"));
        }
    }

    public void testFirstCheckpointExternalFailureKeepsFullyPublishedPrivateGeneration() throws Exception {
        ReportJournal.replace(SavedReport.snapshot(isolated), "previous report\n");
        provider.fail = true;
        try {
            report("new unavailable provider").appendCheckpoint("new first checkpoint\n", false);
            fail("must explicitly report the second phase failure");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("EXTERNAL TXT SAVE FAILED"));
        }
        String text = read(ReportJournal.open(SavedReport.snapshot(isolated)));
        assertTrue(text.contains("new first checkpoint\n\nEND CHECKPOINT\n"));
        assertTrue(text.contains("EXTERNAL TXT SAVE FAILED"));
        assertFalse(text.contains("previous report"));
        assertFalse(provider.file.exists());
    }

    static String read(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > 512 * 1024) throw new IOException("Report exceeds test bound");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static final class DiskProvider extends ContentProvider {
        final File file;
        boolean fail;
        boolean pipe;
        boolean revoked;
        int opens;
        DiskProvider(File file) { this.file = file; }
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            opens++;
            if (revoked) throw new SecurityException("synthetic revoked grant");
            if (fail) throw new FileNotFoundException("synthetic unavailable destination");
            if (pipe) {
                try {
                    ParcelFileDescriptor[] ends = ParcelFileDescriptor.createPipe();
                    ends[1].close();
                    return ends[0];
                } catch (IOException error) { throw new FileNotFoundException(error.toString()); }
            }
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
        }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
            throw new UnsupportedOperationException();
        }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
