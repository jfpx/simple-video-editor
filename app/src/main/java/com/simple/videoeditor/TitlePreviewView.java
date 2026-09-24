package com.simple.videoeditor;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

public final class TitlePreviewView extends View {
    private EditConfig.IntroTitle title;
    private int canvasWidth = 640, canvasHeight = 360;
    private long timeUs;
    private VideoBorder border = VideoBorder.OFF;
    private android.graphics.Bitmap background;

    void setBackgroundFrame(android.graphics.Bitmap bitmap) {
        background = bitmap;
        invalidate();
    }

    void setBorder(VideoBorder border) {
        this.border = border;
        invalidate();
    }

    public TitlePreviewView(Context context, AttributeSet attrs) { super(context, attrs); }

    void setTitle(EditConfig.IntroTitle title, int width, int height, long timeUs) {
        this.title = title; canvasWidth = width; canvasHeight = height; this.timeUs = timeUs;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawColor(0xFFE0E0E0);
        if (title == null) return;
        float scale = Math.min(getWidth() / (float) canvasWidth, getHeight() / (float) canvasHeight);
        int save = canvas.save();
        canvas.translate((getWidth() - canvasWidth * scale) / 2, (getHeight() - canvasHeight * scale) / 2);
        canvas.scale(scale, scale);
        canvas.clipRect(0, 0, canvasWidth, canvasHeight);
        TitleRenderer.draw(canvas, canvasWidth, canvasHeight, title, timeUs, background,
                getResources().getDisplayMetrics().scaledDensity);
        if (border.appliesTo(true)) BorderRenderer.draw(canvas, canvasWidth, canvasHeight, border);
        canvas.restoreToCount(save);
    }

    static void drawLegacy(Canvas canvas, int width, int height, EditConfig.IntroTitle title, float density) {
        drawLegacy(canvas, width, height, title, density, null);
    }

    static void drawLegacy(Canvas canvas, int width, int height, EditConfig.IntroTitle title, float density,
                            android.graphics.Bitmap frame) {
        drawLegacy(canvas, width, height, title, density, frame, 0);
    }

    static void drawLegacy(Canvas canvas, int width, int height, EditConfig.IntroTitle title, float density,
                           android.graphics.Bitmap frame, long timeUs) {
        if (frame != null) TitleRenderer.drawBackground(canvas, width, height, title, frame);
        else {
        canvas.drawColor(Color.BLACK);
        canvas.drawColor(title.backgroundColor);
        if (title.gradientColor != title.backgroundColor) {
            Paint gradient = new Paint();
            gradient.setShader(new android.graphics.LinearGradient(0, 0, width, height,
                    title.backgroundColor, title.gradientColor, android.graphics.Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, width, height, gradient);
        }
        }
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(title.textColor);
        paint.setTextSize(title.textSizeSp * density);
        paint.setTextAlign(title.alignment.equals("left") ? Paint.Align.LEFT
                : title.alignment.equals("right") ? Paint.Align.RIGHT : Paint.Align.CENTER);
        int style = title.fontStyle.equals("bold") ? Typeface.BOLD
                : title.fontStyle.equals("italic") ? Typeface.ITALIC
                : title.fontStyle.equals("bold_italic") ? Typeface.BOLD_ITALIC : Typeface.NORMAL;
        paint.setTypeface(Typeface.create(title.fontFamily, style));
        String[] lines = title.text.split("\\r?\\n", -1);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = height * title.textY - (metrics.ascent + metrics.descent) / 2f
                - (lines.length - 1) * paint.getFontSpacing() / 2f;
        float progress = Math.max(0, Math.min(1, timeUs / 800000f));
        float eased = 1 - (float) Math.pow(1 - progress, 3);
        int save = canvas.save();
        if (title.animation.equals("fade")) paint.setAlpha(Math.round(Color.alpha(title.textColor) * progress));
        if (title.animation.equals("dissolve"))
            paint.setAlpha(Math.round(Color.alpha(title.textColor) * TitleRenderer.dissolve(title, timeUs)));
        if (title.animation.equals("slide") || title.animation.equals("lower-third"))
            canvas.translate(-(1 - eased) * width, 0);
        if (title.animation.equals("scale")) {
            float scale = .3f + .7f * eased;
            canvas.scale(scale, scale, width * title.textX, height * title.textY);
        }
        if (title.animation.equals("lower-third")) {
            baseline = height * .9f - metrics.descent - (lines.length - 1) * paint.getFontSpacing();
            Paint panel = new Paint();
            panel.setColor(0xAA000000);
            canvas.drawRect(width * .05f, baseline + metrics.ascent - height * .02f,
                    width * .95f, height * .93f, panel);
        }
        int remaining = (int) (title.text.codePointCount(0, title.text.length()) * progress);
        for (String line : lines) {
            if (title.animation.equals("typewriter")) {
                int count = line.codePointCount(0, line.length());
                int end = line.offsetByCodePoints(0, Math.max(0, Math.min(remaining, count)));
                float left = width * title.textX;
                if (paint.getTextAlign() == Paint.Align.CENTER) left -= paint.measureText(line) / 2;
                else if (paint.getTextAlign() == Paint.Align.RIGHT) left -= paint.measureText(line);
                Paint.Align align = paint.getTextAlign();
                paint.setTextAlign(Paint.Align.LEFT);
                canvas.drawText(line.substring(0, end), left, baseline, paint);
                paint.setTextAlign(align);
                remaining -= count + 1;
            } else canvas.drawText(line, width * title.textX, baseline, paint);
            baseline += paint.getFontSpacing();
        }
        canvas.restoreToCount(save);
    }
}
