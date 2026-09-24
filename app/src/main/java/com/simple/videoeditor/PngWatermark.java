package com.simple.videoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Build;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Arrays;
import java.util.zip.CRC32;

/** Bounded decoded snapshot: neither provider bytes nor a mutable bitmap escape. */
public final class PngWatermark {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SIDE = 2048;
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final Bitmap image;
    public final int width, height;

    private PngWatermark(Bitmap image) {
        this.image = image;
        width = image.getWidth();
        height = image.getHeight();
    }

    static PngWatermark copyDocument(Context context, Uri uri, File target) throws IOException {
        checkCancelled();
        try (InputStream input = context.getContentResolver().openInputStream(uri);
             FileOutputStream output = new FileOutputStream(target);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (input == null) throw new IOException("Document provider returned no PNG stream");
            byte[] buffer = new byte[16384];
            int count;
            while (true) {
                checkCancelled();
                count = input.read(buffer);
                checkCancelled();
                if (count == -1) break;
                if (count > MAX_BYTES - bytes.size()) throw new IOException("PNG exceeds 8 MiB");
                output.write(buffer, 0, count);
                bytes.write(buffer, 0, count);
            }
            output.getFD().sync();
            checkCancelled();
            PngWatermark snapshot = fromBytes(bytes.toByteArray());
            checkCancelled();
            return snapshot;
        }
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("PNG loading cancelled");
        }
    }

    public static PngWatermark fromBytes(byte[] source) throws IOException {
        if (source == null || source.length < 45 || source.length > MAX_BYTES) {
            throw new IOException("PNG must contain 45 bytes to 8 MiB");
        }
        byte[] bytes = source.clone();
        if (!Arrays.equals(SIGNATURE, Arrays.copyOf(bytes, 8))) throw new IOException("Not a PNG");
        boolean header = false, data = false, end = false;
        int offset = 8;
        while (offset < bytes.length) {
            if (bytes.length - offset < 12) throw new IOException("Truncated PNG chunk");
            long length = uint(bytes, offset);
            if (length > bytes.length - offset - 12) throw new IOException("Truncated PNG payload");
            int size = (int) length;
            String type = new String(bytes, offset + 4, 4, java.nio.charset.StandardCharsets.US_ASCII);
            CRC32 crc = new CRC32();
            crc.update(bytes, offset + 4, size + 4);
            if (crc.getValue() != uint(bytes, offset + 8 + size)) throw new IOException("PNG CRC mismatch");
            if (!header && !type.equals("IHDR")) throw new IOException("Missing PNG header");
            if (type.equals("IHDR")) {
                if (header || size != 13) throw new IOException("Invalid PNG header");
                long width = uint(bytes, offset + 8), height = uint(bytes, offset + 12);
                if (width < 1 || height < 1 || width > MAX_SIDE || height > MAX_SIDE) {
                    throw new IOException("PNG dimensions must be 1..2048 pixels per side");
                }
                header = true;
            }
            if (type.equals("acTL")) throw new IOException("Animated PNG is not supported");
            if (type.equals("IDAT")) data = true;
            offset += size + 12;
            if (type.equals("IEND")) {
                if (size != 0 || !data || offset != bytes.length) throw new IOException("Invalid PNG end");
                end = true;
            }
        }
        if (!end) throw new IOException("Missing PNG end");
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inScaled = false;
        options.inPreferredConfig = Bitmap.Config.ARGB_8888;
        if (Build.VERSION.SDK_INT >= 26) options.inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) throw new IOException("PNG decoder rejected image");
        decoded.setDensity(Bitmap.DENSITY_NONE);
        return new PngWatermark(decoded);
    }

    private static long uint(byte[] bytes, int offset) {
        return ((long) (bytes[offset] & 255) << 24) | ((long) (bytes[offset + 1] & 255) << 16)
                | ((long) (bytes[offset + 2] & 255) << 8) | (bytes[offset + 3] & 255);
    }

    Bitmap copyBitmap() {
        // Media3 1.5.1's overlay shader multiplies sampled RGB by alpha. GL must
        // receive straight RGB, unlike Canvas, which consumes premultiplied pixels.
        int[] pixels = new int[width * height];
        image.getPixels(pixels, 0, width, 0, 0, width, height);
        Bitmap texture = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        texture.setPremultiplied(false);
        texture.setPixels(pixels, 0, width, 0, 0, width, height);
        texture.setDensity(Bitmap.DENSITY_NONE);
        return texture;
    }

    void draw(Canvas canvas, RectF rect) {
        canvas.drawBitmap(image, null, rect, new Paint(Paint.FILTER_BITMAP_FLAG));
    }

    /** Width is a canvas fraction; x/y locate the image in the remaining free space. */
    static RectF rectangle(int canvasWidth, int canvasHeight, EditConfig.Watermark watermark) {
        float width = Math.max(1, Math.round(canvasWidth * watermark.widthFraction));
        float height = width * watermark.image.height / watermark.image.width;
        if (height > canvasHeight) {
            throw new IllegalArgumentException("PNG is too tall at this width; reduce watermark width");
        }
        float left = (canvasWidth - width) * watermark.x;
        float top = (canvasHeight - height) * watermark.y;
        return new RectF(left, top, left + width, top + height);
    }
}
