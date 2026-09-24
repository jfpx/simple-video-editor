package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import androidx.media3.common.VideoFrameProcessingException;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.BitmapOverlay;

@UnstableApi
final class AnimatedTitleOverlay extends BitmapOverlay {
    private final EditConfig.IntroTitle title;
    private Bitmap bitmap;
    private final TitleBackground sourceFrame;
    private Bitmap background;

    AnimatedTitleOverlay(EditConfig.IntroTitle title) { this(title, null); }

    AnimatedTitleOverlay(EditConfig.IntroTitle title, TitleBackground sourceFrame) {
        this.title = title;
        this.sourceFrame = sourceFrame;
    }

    @Override public void configure(Size size) {
        super.configure(size);
        bitmap = Bitmap.createBitmap(size.getWidth(), size.getHeight(), Bitmap.Config.ARGB_8888);
        bitmap.setDensity(Bitmap.DENSITY_NONE);
        if (sourceFrame != null) {
            try { background = sourceFrame.decode(4096); }
            catch (java.io.IOException error) { throw new IllegalStateException(error); }
        }
    }

    @Override public Bitmap getBitmap(long timeUs) {
        TitleRenderer.draw(new Canvas(bitmap), bitmap.getWidth(), bitmap.getHeight(), title, timeUs, background);
        return bitmap;
    }

    @Override public void release() throws VideoFrameProcessingException {
        try { super.release(); }
        finally {
            if (bitmap != null) bitmap.recycle(); bitmap = null;
            if (background != null) background.recycle(); background = null;
        }
    }
}
