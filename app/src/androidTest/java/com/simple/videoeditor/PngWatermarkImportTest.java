package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.util.UUID;

public final class PngWatermarkImportTest extends InstrumentationTestCase {
    public void testNativeCopyAndCancellationRetainInterrupt() throws Exception {
        File input = new File(getInstrumentation().getTargetContext().getCacheDir(), UUID.randomUUID() + ".png");
        File output = new File(input.getParentFile(), UUID.randomUUID() + ".png");
        Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xffa03268);
        try {
            try (FileOutputStream stream = new FileOutputStream(input)) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
            }
            PngWatermark png = PngWatermark.copyDocument(getInstrumentation().getTargetContext(), Uri.fromFile(input), output);
            assertEquals(32, png.width); assertEquals(24, png.height);
            assertTrue(java.util.Arrays.equals(Files.readAllBytes(input.toPath()), Files.readAllBytes(output.toPath())));
            Thread.currentThread().interrupt();
            try {
                PngWatermark.copyDocument(getInstrumentation().getTargetContext(), Uri.fromFile(input), output);
                fail("cancelled stream must raise InterruptedIOException");
            } catch (InterruptedIOException expected) { assertTrue(Thread.currentThread().isInterrupted()); }
            finally { Thread.interrupted(); }
        } finally { bitmap.recycle(); input.delete(); output.delete(); }
    }

    public void testNativeCopyStillRejectsBytesDimensionsCrcAndMalformedPng() throws Exception {
        File input = new File(getInstrumentation().getTargetContext().getCacheDir(), UUID.randomUUID() + ".png");
        File output = new File(input.getParentFile(), UUID.randomUUID() + ".png");
        try {
            try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(input, "rw")) {
                file.setLength(PngWatermark.MAX_BYTES + 1L);
            }
            reject(input, output, "8 MiB");
            Bitmap wide = Bitmap.createBitmap(2049, 1, Bitmap.Config.ARGB_8888);
            try (FileOutputStream stream = new FileOutputStream(input)) {
                assertTrue(wide.compress(Bitmap.CompressFormat.PNG, 100, stream));
            } finally { wide.recycle(); }
            reject(input, output, "2048");
            byte[] bytes = Files.readAllBytes(input.toPath());
            bytes[29] ^= 1;
            Files.write(input.toPath(), bytes);
            reject(input, output, "CRC");
            Files.write(input.toPath(), new byte[50]);
            reject(input, output, "Not a PNG");
        } finally { input.delete(); output.delete(); }
    }

    private void reject(File input, File output, String message) throws Exception {
        try {
            PngWatermark.copyDocument(getInstrumentation().getTargetContext(), Uri.fromFile(input), output);
            fail("Invalid PNG accepted: " + message);
        } catch (IOException expected) { assertTrue(expected.toString(), expected.getMessage().contains(message)); }
    }
}
