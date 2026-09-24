package com.simple.videoeditor;

import android.net.Uri;
import android.test.InstrumentationTestCase;
import androidx.media3.common.C;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class UnlimitedProcessingTest extends InstrumentationTestCase {
    public void testStreamingBeyond4GiBUsesOne64KiBBufferAndPreservesLimits() throws Exception {
        final long total = 4L * 1024 * 1024 * 1024 + 123;
        long[] remaining = {total}, written = {0};
        byte[][] seen = {null};
        InputStream input = new InputStream() {
            public int read() { throw new AssertionError("must stream"); }
            public int read(byte[] buffer) {
                assertEquals(65536, buffer.length);
                if (seen[0] == null) seen[0] = buffer;
                assertSame(seen[0], buffer);
                if (remaining[0] == 0) return -1;
                int n = (int) Math.min(buffer.length, remaining[0]);
                remaining[0] -= n;
                return n;
            }
        };
        OutputStream sink = new OutputStream() {
            public void write(int b) { throw new AssertionError("must stream"); }
            public void write(byte[] b, int offset, int count) { written[0] += count; }
        };
        assertEquals(total, MediaCopy.copy(input, sink, 0, bytes -> {}));
        assertEquals(total, written[0]);
        remaining[0] = total;
        seen[0] = null;
        try { MediaCopy.copy(input, sink, 100, bytes -> {}); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("snapshot byte limit")); }
        Thread.currentThread().interrupt();
        try { MediaCopy.copy(input, sink, 0, bytes -> {}); fail(); }
        catch (java.io.InterruptedIOException expected) {}
        finally { Thread.interrupted(); }
    }

    public void testElapsed101HoursStillPollsAndExplicitCancelCleansOutput() throws Exception {
        File input = new OracleVerifier(getInstrumentation().getTargetContext(),
                com.simple.videoeditor.oracle.OracleGeneratedContract.create()).prepareFixture();
        File output = new File(getInstrumentation().getTargetContext().getCacheDir(), UUID.randomUUID() + ".mp4");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        getInstrumentation().runOnMainSync(() -> {
            Media3ExportEngine engine = new Media3ExportEngine(getInstrumentation().getTargetContext());
            try {
                long[] elapsed = {0};
                AtomicReference<Exception> error = new AtomicReference<>();
                engine.export(new EditConfig.Builder(Uri.fromFile(input), 4000).sourceSize(320, 240).build(),
                        output, new Media3ExportEngine.Listener() {
                            public void onProgress(int percent) {}
                            public void onElapsed(long ms, String pass) { elapsed[0] = ms; }
                            public void onCompleted(File file) {}
                            public void onError(Exception e) { error.set(e); }
                        });
                Object job = field(engine, "activeJob");
                Field start = job.getClass().getDeclaredField("startedNs");
                start.setAccessible(true);
                start.setLong(job, System.nanoTime() - TimeUnit.HOURS.toNanos(101));
                assertEquals(C.TIME_UNSET, ((Long) field(field(job, "transformer"), "maxDelayBetweenMuxerSamplesMs")).longValue());
                ((Runnable) field(job, "pollProgress")).run();
                assertTrue(elapsed[0] >= TimeUnit.HOURS.toMillis(101));
                assertNull(error.get());
                assertTrue(engine.isRunning());
                engine.cancel();
                assertTrue(error.get() instanceof java.util.concurrent.CancellationException);
                assertFalse(engine.isRunning());
                assertFalse(output.exists());
            } catch (Throwable e) { failure.set(e); }
            finally { engine.cancel(); done.countDown(); }
        });
        assertTrue(done.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    public void testRealInputErrorStillCleansReservedOutput() {
        File output = new File(getInstrumentation().getTargetContext().getCacheDir(), UUID.randomUUID() + ".mp4");
        getInstrumentation().runOnMainSync(() -> {
            AtomicReference<Exception> error = new AtomicReference<>();
            Media3ExportEngine engine = new Media3ExportEngine(getInstrumentation().getTargetContext());
            engine.export(new EditConfig.Builder(Uri.fromFile(new File("/missing-video")), 4000)
                    .sourceSize(320, 240).speed(2).build(), output, new Media3ExportEngine.Listener() {
                public void onProgress(int p) {}
                public void onCompleted(File f) { fail(); }
                public void onError(Exception e) { error.set(e); }
            });
            assertNotNull(error.get());
            assertFalse(engine.isRunning());
            assertFalse(output.exists());
        });
    }

    private static Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }
}
