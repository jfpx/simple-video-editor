package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.graphics.RectF;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlaySettings;

@UnstableApi
final class PngWatermarkOverlay extends BitmapOverlay {
    private final EditConfig.Watermark watermark;
    private Bitmap bitmap;
    private OverlaySettings settings;

    PngWatermarkOverlay(EditConfig.Watermark watermark) {
        this.watermark = watermark;
    }

    @Override public void configure(Size size) {
        super.configure(size);
        RectF rect = PngWatermark.rectangle(size.getWidth(), size.getHeight(), watermark);
        settings = new OverlaySettings.Builder()
                .setOverlayFrameAnchor(-1f, 1f)
                .setBackgroundFrameAnchor(2f * rect.left / size.getWidth() - 1f,
                        1f - 2f * rect.top / size.getHeight())
                .setScale(rect.width() / watermark.image.width, rect.height() / watermark.image.height)
                .build();
    }

    @Override public Bitmap getBitmap(long presentationTimeUs) {
        if (bitmap == null) bitmap = watermark.image.copyBitmap();
        return bitmap;
    }

    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
        if (settings == null) throw new IllegalStateException("Watermark not configured");
        return settings;
    }

    @Override public void release() throws VideoFrameProcessingException {
        try { super.release(); }
        finally {
            if (bitmap != null) bitmap.recycle();
            bitmap = null;
        }
    }
}
