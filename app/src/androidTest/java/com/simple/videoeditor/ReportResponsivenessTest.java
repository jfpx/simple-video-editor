package com.simple.videoeditor;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.ProxyFileDescriptorCallback;
import android.os.storage.StorageManager;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.test.InstrumentationTestCase;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@androidx.media3.common.util.UnstableApi
public final class ReportResponsivenessTest extends InstrumentationTestCase {
    public void testLifecycleDiagnosticQueueIsBoundedAndNeverWaitsOnMain() throws Exception {
        SelfTestRunner.Completion<Void> completion = new SelfTestRunner.Completion<>();
        SelfTestRunner.DiagnosticQueue queue = new SelfTestRunner.DiagnosticQueue(completion);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch mainReturned = new CountDownLatch(1);
        try {
            queue.submit(() -> { entered.countDown(); await(release); });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            new Handler(Looper.getMainLooper()).post(() -> {
                for (int i = 0; i < 17; i++) queue.submit(() -> { });
                mainReturned.countDown();
            });
            assertTrue(mainReturned.await(2, TimeUnit.SECONDS));
            try {
                completion.await(2, TimeUnit.SECONDS);
                fail("overflow must be explicit, not drop diagnostics or block main");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("Diagnostic storage queue unavailable"));
            }
        } finally { release.countDown(); queue.close(); }
    }

    public void testLifecycleCompletionWaitsForDurableDiagnosticsAndKeepsFirstError() throws Exception {
        SelfTestRunner.Completion<Void> completion = new SelfTestRunner.Completion<>();
        SelfTestRunner.DiagnosticQueue queue = new SelfTestRunner.DiagnosticQueue(completion);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IOException original = new IOException("durability failed");
        try {
            queue.submit(() -> {
                entered.countDown();
                await(release);
                completion.checkpoint("test", "message", text -> { throw original; });
            });
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            queue.submit(() -> completion.complete(null));
            assertFalse(completion.isDone());
            release.countDown();
            try {
                completion.await(2, TimeUnit.SECONDS);
                fail("late success must not replace the checked storage failure");
            } catch (IOException expected) { assertSame(original, expected); }
        } finally { release.countDown(); queue.close(); }
    }

    public void testNativeBlockedProviderWriteDoesNotBlockPreviewCancelOrWatchdog() throws Exception {
        exerciseBlockedStorage(false, false);
    }

    public void testBlockedPrivateSyncDoesNotBlockPreviewCancelOrWatchdog() throws Exception {
        exerciseBlockedStorage(true, false);
    }

    public void testCancelInterruptDuringPrivateWriteStaysResponsiveAndExplicit() throws Exception {
        exerciseBlockedStorage(true, true);
    }

    private void exerciseBlockedStorage(boolean privateSync, boolean interruptWriter) throws Exception {
        Context target = getInstrumentation().getTargetContext();
        File root = new File(target.getFilesDir(), "responsive-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        SlowDiskProvider provider = new SlowDiskProvider(new File(root, "external.txt"));
        ProviderInfo info = new ProviderInfo();
        info.authority = "slow-report-test";
        provider.attachInfo(target, info);
        ContentResolver resolver = ContentResolver.wrap(provider);
        Context context = new ContextWrapper(target) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
            @Override public ContentResolver getContentResolver() { return resolver; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(root.getName() + name, mode);
            }
        };
        Handler main = new Handler(Looper.getMainLooper());
        List<String> updates = new ArrayList<>();
        CountDownLatch firstUpdate = new CountDownLatch(1);
        CountDownLatch lastUpdate = new CountDownLatch(1);
        SelfTestRunner runner = new SelfTestRunner(context, (text, running) -> {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            updates.add(text);
            if (text.contains("FIRST")) firstUpdate.countDown();
            if (text.contains("LAST")) lastUpdate.countDown();
        });
        SelfTestRunner.Run run = runner.new Run();
        run.report = new File(root, "run.txt");
        run.savedReport = new SavedReport(context, Uri.parse("content://slow-report-test/report"), "slow disk");
        Field active = SelfTestRunner.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(runner, run);
        CountDownLatch mainBlocked = new CountDownLatch(1);
        CountDownLatch mainRelease = new CountDownLatch(1);
        main.post(() -> { mainBlocked.countDown(); await(mainRelease); });
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            // Codec/diagnostic emitters are not the suite worker interrupted by cancel().
            if (interruptWriter) run.workerThread = Thread.currentThread();
            try { runner.log(run, "LAST\n"); }
            catch (Throwable error) { failure.set(error); }
        }, "blocked-report-writer");
        try {
            assertTrue(mainBlocked.await(5, TimeUnit.SECONDS));
            runner.log(run, "FIRST\n");
            synchronized (run.updateLock) { assertTrue(run.updatePending); }
            if (privateSync) {
                ReportJournal.faultInjector = stage -> {
                    if ("append-sync".equals(stage)) {
                        provider.entered.countDown();
                        await(provider.release);
                    }
                };
            } else provider.block = true;
            writer.start();
            assertTrue("native storage write not reached", provider.entered.await(5, TimeUnit.SECONDS));
            assertTrue(writer.isAlive());
            Thread.sleep(150); // Make the coalesced callback due before the heartbeat/cancel tasks.
            mainRelease.countDown();
            assertTrue("preview waited for persistence", firstUpdate.await(2, TimeUnit.SECONDS));
            CountDownLatch responsive = new CountDownLatch(1);
            main.post(() -> {
                runner.cancel();
                run.watchdog.run();
                responsive.countDown();
            });
            assertTrue("main cancel/watchdog waited for storage", responsive.await(2, TimeUnit.SECONDS));
            assertTrue(run.cancelled);
            assertTrue(run.timedOut);
            assertTrue("storage unexpectedly unblocked", writer.isAlive());
            CountDownLatch cancelRendered = new CountDownLatch(1);
            main.post(cancelRendered::countDown);
            assertTrue(cancelRendered.await(2, TimeUnit.SECONDS));
            provider.release.countDown();
            writer.join(5000);
            assertFalse(writer.isAlive());
            if (interruptWriter) {
                assertTrue(failure.get() instanceof IOException);
                assertTrue(failure.get().getMessage().contains("EXTERNAL TXT SAVE FAILED"));
            } else {
                assertNull(failure.get());
                assertTrue("last publish lost after callback cleared pending", lastUpdate.await(2, TimeUnit.SECONDS));
            }
            getInstrumentation().waitForIdleSync();
            synchronized (run.updateLock) { assertFalse(run.updatePending); }
            assertEquals(interruptWriter ? 0 : 1,
                    updates.stream().filter(text -> text.contains("LAST")).count());
            assertTrue(updates.size() <= 3); // First, cancel status, last; no event snapshots queued.
            assertTrue(ReportJournalTest.read(SavedReport.snapshot(context)).contains("LAST"));
            android.util.Log.i("ReportResponsiveness", "blocked=" + (privateSync ? "private-sync" : "native-proxy-write")
                    + " interruptWriter=" + interruptWriter + " main/cancel/watchdog=PASS callbacks=" + updates.size()
                    + " revision=" + BuildConfig.SOURCE_REVISION);
        } finally {
            mainRelease.countDown();
            provider.release.countDown();
            writer.join(5000);
            ReportJournal.faultInjector = null;
            active.set(runner, null);
            run.savedReport.release();
            run.worker.shutdownNow();
            provider.thread.quitSafely();
            provider.thread.join(5000);
            getInstrumentation().waitForIdleSync();
            ReportJournalTest.delete(root);
        }
    }

    private static void await(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        try {
            while (true) {
                try {
                    if (!latch.await(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        throw new AssertionError("test storage gate expired");
                    }
                    return;
                } catch (InterruptedException error) { interrupted = true; }
            }
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    private static final class SlowDiskProvider extends ContentProvider {
        final File file;
        final HandlerThread thread = new HandlerThread("slow-disk-provider");
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        volatile boolean block;

        SlowDiskProvider(File file) { this.file = file; thread.start(); }
        @Override public boolean onCreate() { return true; }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            try {
                RandomAccessFile disk = new RandomAccessFile(file, "rw");
                return getContext().getSystemService(StorageManager.class).openProxyFileDescriptor(
                        ParcelFileDescriptor.MODE_READ_WRITE, new ProxyFileDescriptorCallback() {
                            private ErrnoException io(IOException error) {
                                return new ErrnoException("test disk", OsConstants.EIO, error);
                            }
                            @Override public long onGetSize() throws ErrnoException {
                                try { return disk.length(); } catch (IOException error) { throw io(error); }
                            }
                            @Override public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                                try { disk.seek(offset); return Math.max(0, disk.read(data, 0, size)); }
                                catch (IOException error) { throw io(error); }
                            }
                            @Override public int onWrite(long offset, int size, byte[] data) throws ErrnoException {
                                if (block) { entered.countDown(); await(release); }
                                try { disk.seek(offset); disk.write(data, 0, size); return size; }
                                catch (IOException error) { throw io(error); }
                            }
                            @Override public void onFsync() throws ErrnoException {
                                try { disk.getFD().sync(); } catch (IOException error) { throw io(error); }
                            }
                            @Override public void onRelease() {
                                try { disk.close(); } catch (IOException error) { throw new AssertionError(error); }
                            }
                        }, new Handler(thread.getLooper()));
            } catch (IOException error) {
                throw new FileNotFoundException(error.toString());
            }
        }
        @Override public String getType(Uri uri) { return "text/plain"; }
        @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    }
}
