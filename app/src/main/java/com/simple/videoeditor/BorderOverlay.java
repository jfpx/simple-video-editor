package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.graphics.Rect;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.OverlaySettings;
import androidx.media3.effect.TextureOverlay;
import java.util.ArrayList;
import java.util.List;

@UnstableApi
final class BorderOverlay extends BitmapOverlay {
    private final VideoBorder border;
    private final int edge;
    private Bitmap pixel;
    private OverlaySettings settings;

    static OverlayEffect effect(VideoBorder border) {
        List<TextureOverlay> edges = new ArrayList<>();
        for (int edge = 0; edge < 4; edge++) edges.add(new BorderOverlay(border, edge));
        return new OverlayEffect(edges);
    }

    private BorderOverlay(VideoBorder border, int edge) {
        this.border = border;
        this.edge = edge;
    }

    @Override public void configure(Size size) {
        super.configure(size);
        Rect rect = BorderRenderer.rectangles(size.getWidth(), size.getHeight(), border)[edge];
        settings = new OverlaySettings.Builder()
                .setOverlayFrameAnchor(-1f, 1f)
                .setBackgroundFrameAnchor(2f * rect.left / size.getWidth() - 1f,
                        1f - 2f * rect.top / size.getHeight())
                .setScale(rect.width(), rect.height()).build();
    }

    @Override public Bitmap getBitmap(long presentationTimeUs) {
        if (pixel == null) {
            pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
            pixel.eraseColor(border.color());
        }
        return pixel;
    }

    @Override public OverlaySettings getOverlaySettings(long presentationTimeUs) {
        if (settings == null) throw new IllegalStateException("Border not configured");
        return settings;
    }

    @Override public void release() throws VideoFrameProcessingException {
        try { super.release(); }
        finally {
            if (pixel != null) pixel.recycle();
            pixel = null;
        }
    }
}
