package com.simple.videoeditor;

import android.content.Context;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.UUID;

public final class MergeImportTest extends InstrumentationTestCase {
    private File source;
    private File target;

    public void testNativeDirectDocumentDetectsProviderMutationWithoutCopy() throws Exception {
        prepare();
        Object snapshot = load(EditConfig.MAX_VIDEO_BYTES, 4096, deadline());
        try {
            assertFalse("Direct import must not stage", target.exists());
            java.lang.reflect.Field field = snapshot.getClass().getDeclaredField("source");
            field.setAccessible(true);
            VideoSource input = (VideoSource) field.get(snapshot);
            try (FileOutputStream output = new FileOutputStream(source)) {
                output.write(new byte[]{1, 2, 3});
            }
            try {
                input.checkCurrent(getInstrumentation().getTargetContext());
                fail("Changed original accepted");
            } catch (java.io.IOException expected) {}
        } finally {
            Method dispose = snapshot.getClass().getDeclaredMethod("dispose");
            dispose.setAccessible(true);
            dispose.invoke(snapshot);
        }
        assertFalse(target.exists());
        assertTrue("Disposing a selection must not delete the original", source.isFile());
    }

    public void testBoundedCopyDeletesPartialFile() throws Exception {
        prepare();
        reject(100, 4096, deadline(), "snapshot byte limit");
    }

    public void testMissingVideoMetadataDeletesCopy() throws Exception {
        prepare();
        try (FileOutputStream output = new FileOutputStream(source)) {
            output.write("not a media file".getBytes("UTF-8"));
        }
        reject(EditConfig.MAX_VIDEO_BYTES, 4096, deadline(), null);
    }

    public void testElapsedOver100HoursDoesNotRejectAndDimensionStillRejects() throws Exception {
        prepare();
        Object snapshot = load(EditConfig.MAX_VIDEO_BYTES, 4096,
                SystemClock.elapsedRealtime() - 101L * 60 * 60 * 1000);
        assertFalse(target.exists());
        Method dispose = snapshot.getClass().getDeclaredMethod("dispose");
        dispose.setAccessible(true);
        dispose.invoke(snapshot);
        reject(EditConfig.MAX_VIDEO_BYTES, 1, deadline(), "display-size limit");
    }

    private void prepare() throws Exception {
        Context context = getInstrumentation().getTargetContext();
        File directory = new File(context.getFilesDir(), "exports");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        source = new File(directory, "merge-import-" + UUID.randomUUID() + ".mp4");
        target = new File(context.getCacheDir(), "merge-import-" + UUID.randomUUID() + ".mp4");
        try (InputStream input = context.getAssets().open("video-oracle/standard.mp4");
             FileOutputStream output = new FileOutputStream(source)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        }
    }

    private Object load(long maxBytes, int dimension, long deadline) throws Exception {
        Context context = getInstrumentation().getTargetContext();
        Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".fileprovider", source);
        Method method = MainActivity.class.getDeclaredMethod("readMedia", Context.class, Uri.class,
                File.class, boolean.class, long.class, long.class, int.class);
        method.setAccessible(true);
        return method.invoke(null, context, uri, target, true, deadline, maxBytes, dimension);
    }

    private void reject(long maxBytes, int dimension, long deadline, String message) throws Exception {
        try {
            load(maxBytes, dimension, deadline);
        } catch (InvocationTargetException failure) {
            assertNotNull(failure.getCause());
            if (message != null) assertTrue(failure.getCause().toString(),
                    failure.getCause().toString().contains(message));
            assertFalse("Failed import leaked its copy", target.exists());
            return;
        }
        fail("Expected an explicit import rejection");
    }

    private static long deadline() {
        return SystemClock.elapsedRealtime() + 60000;
    }

    private static byte[] bytes(File file) throws Exception {
        byte[] result = new byte[(int) file.length()];
        try (FileInputStream input = new FileInputStream(file)) {
            int offset = 0, count;
            while (offset < result.length && (count = input.read(result, offset, result.length - offset)) > 0) {
                offset += count;
            }
            assertEquals(result.length, offset);
        }
        return result;
    }

    @Override protected void tearDown() throws Exception {
        if (source != null && source.exists()) assertTrue(source.delete());
        if (target != null && target.exists()) assertTrue(target.delete());
        super.tearDown();
    }
}
