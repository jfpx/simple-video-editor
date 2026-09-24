package com.simple.videoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import androidx.media3.common.util.Size;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.UUID;

/** Immutable app-owned, already-projected PNG. Never a trimmed intermediate or public screenshot. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class TitleBackground {
    final File file;
    final String key;
    final long timeMs;
    final int width, height;

    private TitleBackground(File file, String key, long timeMs, int width, int height) {
        this.file = file; this.key = key; this.timeMs = timeMs; this.width = width; this.height = height;
    }

    static long parseTime(String seconds, long durationMs) {
        try {
            long ms = new BigDecimal(seconds.trim()).movePointRight(3).longValueExact();
            if (ms < 0 || ms >= durationMs) throw new ArithmeticException();
            return ms;
        } catch (NumberFormatException | ArithmeticException error) {
            throw new IllegalArgumentException("原视频秒数 / Original seconds: 0 ≤ t < "
                    + BigDecimal.valueOf(durationMs, 3).toPlainString() + "; ≤ 3 decimals");
        }
    }

    static String key(EditConfig config, long timeMs) {
        return config.input + (config.inputSource == null ? "" : "|" + config.inputSource.selectionKey())
                + "|" + config.sourceDurationMs + "|" + config.sourceWidth + "x" + config.sourceHeight
                + "|" + timeMs + "|" + config.cropLeft + "," + config.cropTop + "," + config.cropRight + ","
                + config.cropBottom + "|" + config.rotationDegrees + "|" + config.outputHeight;
    }

    static TitleBackground capture(Context context, EditConfig config, long timeMs) throws IOException {
        if (timeMs < 0 || timeMs >= config.sourceDurationMs) throw new IOException("Original frame time outside source");
        Size output = Media3ExportEngine.editedCanvas(config);
        requireSize(config.sourceWidth, config.sourceHeight);
        requireSize(output.getWidth(), output.getHeight());
        File directory = new File(context.getFilesDir(), "title-backgrounds");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create title snapshot directory");
        File file = new File(directory, UUID.randomUUID() + ".png");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap frame = null;
        boolean saved = false;
        try {
            MediaCopy.checkCancelled();
            if (config.inputSource != null) config.inputSource.checkCurrent(context);
            VideoSource.setDataSource(context, retriever, config.input);
            // No scaled preview is promoted to export quality. Retriever applies metadata rotation;
            // the shared geometry corrects SAR/display dimensions before cropping, exactly once.
            int rawWidth = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int rawHeight = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            requireSize(rawWidth, rawHeight);
            frame = Build.VERSION.SDK_INT >= 27
                    ? retriever.getScaledFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST,
                            config.sourceWidth, config.sourceHeight)
                    : retriever.getFrameAtTime(timeMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST);
            if (frame == null) throw new IOException("No frame returned at original source time");
            MediaCopy.checkCancelled();
            Bitmap decoded = frame;
            frame = null;
            frame = FrameGeometry.project(decoded, config, 4096);
            try (FileOutputStream stream = new FileOutputStream(file)) {
                if (!frame.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw new IOException("Cannot encode frame PNG");
                stream.getFD().sync();
            }
            MediaCopy.checkCancelled();
            saved = true;
            return new TitleBackground(file, key(config, timeMs), timeMs, frame.getWidth(), frame.getHeight());
        } catch (OutOfMemoryError error) {
            throw new IOException("Insufficient memory for full-resolution title background; no quality fallback", error);
        } finally {
            if (frame != null) frame.recycle();
            retriever.release();
            if (!saved) file.delete();
        }
    }

    Bitmap decode(int maxSide) throws IOException {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(width, height) / (options.inSampleSize * 2) >= maxSide) options.inSampleSize *= 2;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        if (bitmap == null) throw new IOException("Title background snapshot missing or unreadable");
        Size size = FrameGeometry.bounded(bitmap.getWidth(), bitmap.getHeight(), maxSide);
        Bitmap result = Bitmap.createScaledBitmap(bitmap, size.getWidth(), size.getHeight(), true);
        if (result != bitmap) bitmap.recycle();
        result.setDensity(Bitmap.DENSITY_NONE);
        return result;
    }

    private static void requireSize(int width, int height) throws IOException {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096)
            throw new IOException("Full-resolution title backgrounds require source/output ≤ 4096 pixels per side");
    }
}
