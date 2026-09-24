package com.simple.videoeditor;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@androidx.media3.common.util.UnstableApi
public final class ReportMemoryTest extends InstrumentationTestCase {
    private File root;
    private Context context;

    @Override protected void setUp() throws Exception {
        super.setUp();
        Context target = getInstrumentation().getTargetContext();
        root = new File(target.getFilesDir(), "memory-test-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(root.getName() + name, mode);
            }
        };
    }

    @Override protected void tearDown() throws Exception {
        delete(root);
        super.tearDown();
    }

    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static String payload() {
        char[] data = new char[64 * 1024];
        Arrays.fill(data, 'x');
        return new String(data) + "中文😀";
    }

    private static byte[] digest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = ReportJournal.open(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) hash.update(buffer, 0, count);
        }
        return hash.digest();
    }

    public void testThirtyTwoMiBOrderedDiskCopiesAndBlockedUiStayBounded() throws Exception {
        final String data = payload();
        File external = new File(root, "external.txt");
        SavedReport saved = new SavedReport(context, Uri.fromFile(external), "test disk");
        List<String> updates = new ArrayList<>();
        SelfTestRunner runner = new SelfTestRunner(context, (text, running) -> updates.add(text));
        SelfTestRunner.Run run = runner.new Run();
        run.report = new File(root, "report.txt");
        run.savedReport = saved;
        java.lang.reflect.Field active = SelfTestRunner.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(runner, run);
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            blocked.countDown();
            try { unblock.await(120, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        long peak = 0;
        MessageDigest expected = MessageDigest.getInstance("SHA-256");
        try {
            for (int i = 0; i < 512; i++) {
                String event = "{\"event\":" + i + ",\"diagnostics\":\"" + data + "\"}\n";
                expected.update(event.getBytes(StandardCharsets.UTF_8));
                runner.log(run, event);
                assertEquals(0, run.log.length());
                assertTrue(run.preview.length() <= ReportPreview.LIMIT);
                assertTrue(run.snapshot.length() <= ReportPreview.LIMIT + ReportPreview.NOTICE.length());
                assertTrue(run.updatePending);
                peak = Math.max(peak, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            }
            assertTrue(external.length() > 32L * 1024 * 1024);
            assertTrue(Arrays.equals(digest(external), digest(SavedReport.snapshot(context))));
            // Stream every event in order; timestamp/checkpoint framing is not part of the event hash.
            for (File file : new File[]{external, SavedReport.snapshot(context), run.report}) {
                MessageDigest actual = MessageDigest.getInstance("SHA-256");
                int index = 0;
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                         new java.io.InputStreamReader(ReportJournal.open(file), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("{\"event\":")) continue;
                        assertTrue(line.startsWith("{\"event\":" + index++ + ","));
                        actual.update((line + "\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                assertEquals(512, index);
                if (file.equals(external)) {
                    assertTrue(Arrays.equals(expected.digest(), actual.digest()));
                } else {
                    assertTrue(Arrays.equals(eventDigest(external), actual.digest()));
                }
            }
            assertTrue("peak heap=" + peak, peak < Runtime.getRuntime().maxMemory() * 3 / 4);
            Log.i("ReportMemoryTest", "events=512 bytes=" + external.length() + " peakJavaBytes="
                    + peak + " maxHeap=" + Runtime.getRuntime().maxMemory()
                    + " previewChars=" + run.preview.length() + " queuedPreviewCallbacks=1"
                    + " revision=" + BuildConfig.SOURCE_REVISION);
        } finally {
            unblock.countDown();
            saved.release();
            run.worker.shutdownNow();
        }
        getInstrumentation().waitForIdleSync();
        assertEquals(1, updates.size());
        assertTrue(updates.get(0).contains(data.substring(data.length() - 20)));
        active.set(runner, null);
    }

    private static byte[] eventDigest(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(ReportJournal.open(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("{\"event\":")) hash.update((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        return hash.digest();
    }

    public void testOldSnapshotQueueMutantExhaustsControlledAppHeap() {
        // Test-only mutant. Never installed in production; no host heap pressure or heap setting changes.
        assertTrue(Runtime.getRuntime().maxMemory() <= 256L * 1024 * 1024);
        List<String> queued = new ArrayList<>();
        StringBuilder cumulative = new StringBuilder();
        String event = payload();
        int completed = 0;
        boolean exhausted = false;
        try {
            for (; completed < 512; completed++) {
                cumulative.append(event);
                queued.add(cumulative.toString());
            }
        } catch (OutOfMemoryError expected) {
            exhausted = true;
        } finally {
            queued.clear();
            cumulative = null;
        }
        assertTrue("Old whole-snapshot queue must fail the same 512-event workload", exhausted);
        Log.i("ReportMemoryTest", "oldSnapshotQueueMutant=OOM events=" + completed
                + " maxHeap=" + Runtime.getRuntime().maxMemory());
    }

    public void testPrivateRecoveryDropsOnlyUncommittedSuffixAndReadsLegacyAtomicBackup() throws Exception {
        File file = new File(root, "journal.txt");
        ReportJournal.append(file, "first 中文😀\n");
        byte[] committed = digest(file);
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write("uncommitted half event".getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
        try (InputStream ignored = ReportJournal.open(file)) { }
        assertTrue(Arrays.equals(committed, digest(file)));
        ReportJournal.append(file, "second\n");
        assertEquals("first 中文😀\nsecond\n", SavedReportTest.read(ReportJournal.open(file)));
        File legacy = new File(root, "legacy.txt");
        SavedReport.atomic(new File(legacy + ".bak"), "old complete report\n");
        assertEquals("old complete report\n", SavedReportTest.read(ReportJournal.open(legacy)));
        ReportJournal.append(legacy, "new delta\n");
        assertEquals("old complete report\nnew delta\n", SavedReportTest.read(ReportJournal.open(legacy)));
    }

    public void testUtf8PreviewKeepsTailNotHeadWithoutSplittingSurrogates() throws Exception {
        String source = payload() + "LAST 中文😀";
        String preview = ReportPreview.read(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)));
        assertTrue(preview.startsWith(ReportPreview.NOTICE));
        assertTrue(preview.endsWith("LAST 中文😀"));
        assertFalse(preview.contains("\ufffd"));
        assertTrue(preview.length() <= ReportPreview.LIMIT + ReportPreview.NOTICE.length());
        ReportPreview tail = new ReportPreview();
        tail.append("😀" + source);
        assertTrue(tail.length() <= ReportPreview.LIMIT);
    }
}
