package com.simple.videoeditor;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

final class BorderRenderer {
    private BorderRenderer() {}

    static Rect[] rectangles(int width, int height, VideoBorder border) {
        if (width < 2 || height < 2) throw new IllegalArgumentException("Border canvas must be at least 2×2");
        int pixels = Math.max(1, Math.round(Math.min(width, height) * border.percent / 100f));
        return new Rect[]{new Rect(0, 0, width, pixels),
                new Rect(0, height - pixels, width, height),
                new Rect(0, pixels, pixels, height - pixels),
                new Rect(width - pixels, pixels, width, height - pixels)};
    }

    static void draw(Canvas canvas, int width, int height, VideoBorder border) {
        if (!border.enabled) return;
        Paint paint = new Paint();
        paint.setColor(border.color());
        for (Rect rect : rectangles(width, height, border)) canvas.drawRect(rect, paint);
    }
}
