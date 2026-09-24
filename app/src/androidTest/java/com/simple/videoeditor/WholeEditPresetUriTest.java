package com.simple.videoeditor;

import android.content.Context;
import android.net.Uri;
import android.test.InstrumentationTestCase;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.UUID;

public final class WholeEditPresetUriTest extends InstrumentationTestCase {
    public void testSaveWithVideoSourceCopiesContentUriIntoDurablePresetAsset() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        WholeEditPresets store = new WholeEditPresets(context);
        String name = "test-uri-preset-" + UUID.randomUUID();
        File source = sourceFile(context, name + ".mp4");
        byte[] expected = new byte[]{7, 6, 5, 4, 3, 2, 1};
        try {
            write(source, expected);
            VideoSource intro = VideoSource.document(context, contentUri(context, source));
            store.saveWithVideoSource(name, recipe(), intro, null, null, bytes -> {});
            assertTrue(source.delete());
            JSONObject saved = store.load(name);
            File durable = store.asset(saved, "introAsset");
            assertTrue(saved.getBoolean("introAssetRequired"));
            assertFalse(saved.has("input"));
            assertFalse(saved.has("appendedVideos"));
            assertTrue(Arrays.equals(expected, bytes(durable)));
        } finally {
            if (source.exists()) source.delete();
            store.delete(name);
        }
    }

    public void testSaveWithVideoSourceRejectsChangedSourceAndPreservesStoredPreset() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        WholeEditPresets store = new WholeEditPresets(context);
        String name = "test-uri-changed-" + UUID.randomUUID();
        File source = sourceFile(context, name + ".mp4");
        int beforeAssets = assetCount(context);
        try {
            store.save(name, recipe(), null, null, null);
            write(source, new byte[]{1, 2, 3, 4});
            VideoSource intro = VideoSource.document(context, contentUri(context, source));
            write(source, new byte[]{1, 2, 3, 4, 5});
            try {
                store.saveWithVideoSource(name, recipe().put("headMs", 500), intro, null, null, bytes -> {});
                fail("changed source accepted");
            } catch (java.io.IOException expected) {}
            JSONObject saved = store.load(name);
            assertEquals(1000L, saved.getLong("headMs"));
            assertTrue(saved.isNull("introAsset"));
            assertEquals(beforeAssets, assetCount(context));
        } finally {
            if (source.exists()) source.delete();
            store.delete(name);
        }
    }

    public void testSaveWithVideoSourceCancellationDeletesPartialAsset() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        WholeEditPresets store = new WholeEditPresets(context);
        String name = "test-uri-cancel-" + UUID.randomUUID();
        File source = sourceFile(context, name + ".mp4");
        byte[] payload = new byte[200_000];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 31);
        int beforeAssets = assetCount(context);
        try {
            write(source, payload);
            VideoSource intro = VideoSource.document(context, contentUri(context, source));
            try {
                store.saveWithVideoSource(name, recipe(), intro, null, null, new MediaCopy.Progress() {
                    private boolean cancelled;

                    @Override public void copied(long bytes) {
                        if (!cancelled && bytes > 0) {
                            cancelled = true;
                            Thread.currentThread().interrupt();
                        }
                    }
                });
                fail("cancelled save succeeded");
            } catch (InterruptedIOException expected) {
            } finally {
                Thread.interrupted();
            }
            assertEquals(beforeAssets, assetCount(context));
            for (String item : store.names()) assertFalse(item.equals(name));
            assertTrue(source.isFile());
            assertEquals(payload.length, source.length());
        } finally {
            if (source.exists()) source.delete();
            store.delete(name);
            Thread.interrupted();
        }
    }

    private static JSONObject recipe() throws Exception {
        return new JSONObject().put("headMs", 1000).put("tailMs", 1000)
                .put("crop", new JSONArray(new int[]{10, 10, 10, 10}));
    }

    public void testProviderCopyDoesNotBlockIndexOrCollectInFlightAsset() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        WholeEditPresets store = new WholeEditPresets(context);
        String name = "concurrent-uri-" + UUID.randomUUID(), other = name + "-other";
        File source = sourceFile(context, name + ".mp4");
        write(source, new byte[200000]);
        VideoSource input = VideoSource.document(context, contentUri(context, source));
        java.util.concurrent.CountDownLatch copying = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch resume = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                store.saveWithVideoSource(name, recipe(), input, null, null, bytes -> {
                    copying.countDown();
                    try { resume.await(); }
                    catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
                });
            } catch (Throwable error) { failure.set(error); }
        });
        try {
            store.save(other, recipe(), null, null, null);
            int before = assetCount(context);
            worker.start();
            assertTrue(copying.await(10, java.util.concurrent.TimeUnit.SECONDS));
            java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                executor.submit(() -> {
                    try { store.delete(other); store.names(); }
                    catch (Exception error) { throw new RuntimeException(error); }
                }).get(3, java.util.concurrent.TimeUnit.SECONDS);
                assertEquals("Collection must retain the active copy", before + 1, assetCount(context));
            } finally { executor.shutdownNow(); }
            worker.interrupt(); resume.countDown(); worker.join(10000);
            assertFalse(worker.isAlive());
            assertTrue(failure.get() instanceof InterruptedIOException);
            assertEquals(before, assetCount(context));
            store.saveWithVideoSource(name, recipe(), input, null, null, bytes -> {});
            assertNotNull(store.asset(store.load(name), "introAsset"));
        } finally {
            worker.interrupt(); resume.countDown(); worker.join(10000);
            store.delete(name); store.delete(other); source.delete();
        }
    }

    private static Uri contentUri(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", file);
    }

    private static File sourceFile(Context context, String name) throws Exception {
        File directory = new File(context.getFilesDir(), "selftest");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        return new File(directory, name);
    }

    private static int assetCount(Context context) {
        File[] files = new File(context.getFilesDir(), "whole-edit-presets").listFiles();
        int count = 0;
        if (files != null) for (File file : files) if (file.getName().endsWith(".asset")) count++;
        return count;
    }

    private static void write(File file, byte[] bytes) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(bytes);
        }
    }

    private static byte[] bytes(File file) throws Exception {
        byte[] result = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0;
            int count;
            while (offset < result.length && (count = input.read(result, offset, result.length - offset)) > 0) {
                offset += count;
            }
            assertEquals(result.length, offset);
        }
        return result;
    }
}
