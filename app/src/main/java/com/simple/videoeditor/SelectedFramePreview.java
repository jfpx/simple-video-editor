package com.simple.videoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import androidx.media3.common.Effect;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlMatrixTransformation;
import java.io.IOException;

@UnstableApi
final class SelectedFramePreview {
    private SelectedFramePreview() {}

    static Bitmap decode(MediaMetadataRetriever retriever, long timeUs) throws IOException {
        return decode(retriever, timeUs, null);
    }

    static Bitmap decode(MediaMetadataRetriever retriever, long timeUs, Size displaySize) throws IOException {
        int width = Integer.parseInt(retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
        int height = Integer.parseInt(retriever.extractMetadata(
                MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
        if (width <= 0 || height <= 0) throw new IOException("Invalid source dimensions");
        if (Build.VERSION.SDK_INT < 27) {
            if ((long) width * height > 4096L * 2160) {
                throw new IOException("Static preview on Android 6–8 requires at most 4096×2160 pixels");
            }
        }
        String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        if (rotation != null && Math.abs(Integer.parseInt(rotation) % 180) == 90) {
            int swap = width;
            width = height;
            height = swap;
        }
        // Retriever preserves display aspect within the requested bounds. Raw encoded bounds
        // undersample anamorphic frames (e.g. 320×240 SAR2 becomes 320×120).
        Size request = displaySize == null ? bounded(width, height)
                : bounded(displaySize.getWidth(), displaySize.getHeight());
        Bitmap frame = Build.VERSION.SDK_INT >= 27
                ? retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST,
                        request.getWidth(), request.getHeight())
                : retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
        if (frame == null) throw new IOException("Decoder returned no selected frame");
        Size bounded = bounded(frame.getWidth(), frame.getHeight());
        Bitmap scaled = Bitmap.createScaledBitmap(frame, bounded.getWidth(), bounded.getHeight(), true);
        if (scaled != frame) frame.recycle();
        return scaled;
    }

    static Bitmap render(EditConfig config) throws IOException {
        String scheme = config.input.getScheme();
        if (config.inputSource != null || (scheme != null && !"file".equals(scheme))) {
            throw new IOException("Selected frame preview for non-file inputs requires Context");
        }
        return renderInternal(null, config);
    }

    static Bitmap render(Context context, EditConfig config) throws IOException {
        return renderInternal(context, config);
    }

    private static Bitmap renderInternal(Context context, EditConfig config) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Bitmap bitmap = null;
        try {
            setRetrieverDataSource(context, config, retriever);
            // Retriever applies metadata rotation once. Source dimensions also include SAR.
            bitmap = decode(retriever, config.startMs * 1000, new Size(config.sourceWidth, config.sourceHeight));
            Size size = new Size(config.sourceWidth, config.sourceHeight);
            Bitmap decoded = bitmap;
            bitmap = null;
            bitmap = FrameGeometry.project(decoded, config, 640);
            size = Media3ExportEngine.editedCanvas(config);
            config.colorAdjustment.applyPreview(bitmap);
            if (config.watermark != null) {
                Canvas canvas = new Canvas(bitmap);
                canvas.scale((float) bitmap.getWidth() / size.getWidth(),
                        (float) bitmap.getHeight() / size.getHeight());
                config.watermark.image.draw(canvas,
                        PngWatermark.rectangle(size.getWidth(), size.getHeight(), config.watermark));
            }
            if (config.border.appliesTo(false)) {
                Canvas canvas = new Canvas(bitmap);
                canvas.scale((float) bitmap.getWidth() / size.getWidth(),
                        (float) bitmap.getHeight() / size.getHeight());
                BorderRenderer.draw(canvas, size.getWidth(), size.getHeight(), config.border);
            }
            Bitmap result = bitmap;
            bitmap = null;
            return result;
        } catch (OutOfMemoryError error) {
            throw new IOException("Insufficient memory for bounded static preview", error);
        } finally {
            if (bitmap != null) bitmap.recycle();
            retriever.release();
        }
    }

    private static void setRetrieverDataSource(Context context, EditConfig config,
                                               MediaMetadataRetriever retriever) throws IOException {
        if (context != null) {
            if (config.inputSource != null) {
                config.inputSource.checkCurrent(context);
            }
            VideoSource.setDataSource(context, retriever,
                    config.inputSource == null ? config.input : config.inputSource.uri);
            return;
        }
        String path = config.input.getPath();
        if (path == null || path.trim().isEmpty()) {
            throw new IOException("Input file path is missing");
        }
        retriever.setDataSource(path);
    }

    private static Size bounded(int width, int height) {
        float scale = Math.min(1f, 640f / Math.max(width, height));
        return new Size(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
    }
}
