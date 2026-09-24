package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import androidx.media3.common.Effect;
import androidx.media3.common.util.Size;
import androidx.media3.effect.GlMatrixTransformation;

/** Canvas equivalent of the production GL geometry; input is already display oriented. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
final class FrameGeometry {
    static Size bounded(int width, int height, int maxSide) {
        float scale = Math.min(1f, (float) maxSide / Math.max(width, height));
        return new Size(Math.max(1, Math.round(width * scale)), Math.max(1, Math.round(height * scale)));
    }

    /** Consumes input, including on failure. No color, text, border or watermark effects. */
    static Bitmap project(Bitmap input, EditConfig config, int maxSide) {
        Bitmap bitmap = input;
        try {
            Size size = new Size(config.sourceWidth, config.sourceHeight);
            Size initial = bounded(size.getWidth(), size.getHeight(), maxSide);
            Bitmap corrected = Bitmap.createScaledBitmap(bitmap, initial.getWidth(), initial.getHeight(), true);
            if (corrected != bitmap) bitmap.recycle();
            bitmap = corrected;
            for (Effect effect : Media3ExportEngine.createGeometryEffects(config)) {
                GlMatrixTransformation transform = (GlMatrixTransformation) effect;
                size = transform.configure(size.getWidth(), size.getHeight());
                Size target = bounded(size.getWidth(), size.getHeight(), maxSide);
                float[] gl = transform.getGlMatrixArray(0);
                Matrix matrix = new Matrix();
                matrix.setValues(new float[]{gl[0], gl[4], gl[12], gl[1], gl[5], gl[13], gl[3], gl[7], gl[15]});
                Matrix pixelsToNdc = new Matrix();
                pixelsToNdc.setValues(new float[]{2f / bitmap.getWidth(), 0, -1,
                        0, -2f / bitmap.getHeight(), 1, 0, 0, 1});
                matrix.preConcat(pixelsToNdc);
                Matrix ndcToPixels = new Matrix();
                ndcToPixels.setValues(new float[]{target.getWidth() / 2f, 0, target.getWidth() / 2f,
                        0, -target.getHeight() / 2f, target.getHeight() / 2f, 0, 0, 1});
                matrix.postConcat(ndcToPixels);
                Bitmap next = Bitmap.createBitmap(target.getWidth(), target.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(next);
                canvas.drawColor(Color.BLACK);
                canvas.drawBitmap(bitmap, matrix, new Paint(Paint.FILTER_BITMAP_FLAG));
                bitmap.recycle();
                bitmap = next;
            }
            Bitmap result = bitmap;
            bitmap = null;
            return result;
        } finally {
            if (bitmap != null) bitmap.recycle();
        }
    }
}
