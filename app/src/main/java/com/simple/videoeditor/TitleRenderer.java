package com.simple.videoeditor;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;

/** Original finite title patterns; the same time-based canvas is used by preview and export. */
final class TitleRenderer {
    private TitleRenderer() {}

    static void validate(int width, int height, EditConfig.IntroTitle title) {
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            throw new IllegalArgumentException("Title canvas must be 1–4096 pixels per side");
        }
        if (title.layout.equals("legacy-sp")) return;
        android.graphics.Bitmap pixel = android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888);
        try { draw(new Canvas(pixel), width, height, title, 1200000); }
        finally { pixel.recycle(); }
    }

    static void draw(Canvas canvas, int width, int height, EditConfig.IntroTitle title, long timeUs) {
        draw(canvas, width, height, title, timeUs, null);
    }

    static void draw(Canvas canvas, int width, int height, EditConfig.IntroTitle title, long timeUs,
                     android.graphics.Bitmap frame) {
        draw(canvas, width, height, title, timeUs, frame,
                android.content.res.Resources.getSystem().getDisplayMetrics().scaledDensity);
    }

    static void draw(Canvas canvas, int width, int height, EditConfig.IntroTitle title, long timeUs,
                     android.graphics.Bitmap frame, float density) {
        if (title.layout.equals("legacy-sp")) {
            TitlePreviewView.drawLegacy(canvas, width, height, title, density, frame, timeUs);
            return;
        }
        drawBackground(canvas, width, height, title, frame);
        drawText(canvas, width, height, title, timeUs);
    }

    static void drawBackground(Canvas canvas, int width, int height, EditConfig.IntroTitle title,
                               android.graphics.Bitmap frame) {
        if (frame != null) {
            canvas.drawBitmap(frame, null, new android.graphics.Rect(0, 0, width, height),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
            return;
        }
        Paint background = new Paint();
        background.setShader(new LinearGradient(0, 0, width, height, title.backgroundColor,
                title.gradientColor, Shader.TileMode.CLAMP));
        canvas.drawColor(Color.BLACK);
        canvas.drawRect(0, 0, width, height, background);
    }

    private static void drawText(Canvas canvas, int width, int height, EditConfig.IntroTitle title, long timeUs) {
        TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(title.textColor);
        int style = title.fontStyle.equals("bold") ? Typeface.BOLD
                : title.fontStyle.equals("italic") ? Typeface.ITALIC
                : title.fontStyle.equals("bold_italic") ? Typeface.BOLD_ITALIC : Typeface.NORMAL;
        paint.setTypeface(Typeface.create(title.fontFamily, style));
        paint.setTextSize(title.textSizeSp * Math.min(width / 640f, height / 360f));
        float progress = Math.max(0, Math.min(1, timeUs / 800000f));
        Layout.Alignment align = title.alignment.equals("left") ? Layout.Alignment.ALIGN_NORMAL
                : title.alignment.equals("right") ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_CENTER;
        int textWidth = Math.max(1, Math.round(width * .84f));
        float maxHeight = height * (title.animation.equals("lower-third") ? .28f : .8f);
        StaticLayout layout = layout(title.text, paint, textWidth, align);
        for (int i = 0; layout.getHeight() > maxHeight && i < 40; i++) {
            paint.setTextSize(paint.getTextSize() * .9f);
            layout = layout(title.text, paint, textWidth, align);
        }
        if (layout.getHeight() > maxHeight) throw new IllegalArgumentException("Title cannot fit safe canvas");
        if (title.animation.equals("typewriter")) {
            int count = title.text.codePointCount(0, title.text.length());
            int visible = title.text.offsetByCodePoints(0, Math.min(count, (int) (count * progress)));
            SpannableString revealed = new SpannableString(title.text);
            revealed.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), visible, title.text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            layout = layout(revealed, paint, textWidth, align);
        }
        float left = (width - textWidth) / 2f;
        float top = (height - layout.getHeight()) / 2f;
        float eased = 1 - (1 - progress) * (1 - progress) * (1 - progress);
        int save = canvas.save();
        canvas.clipRect(width * .05f, height * .05f, width * .95f, height * .95f);
        if (title.animation.equals("fade")) paint.setAlpha(Math.round(Color.alpha(title.textColor) * progress));
        if (title.animation.equals("dissolve"))
            paint.setAlpha(Math.round(Color.alpha(title.textColor) * dissolve(title, timeUs)));
        if (title.animation.equals("slide")) left -= (1 - eased) * width;
        if (title.animation.equals("scale")) {
            float scale = .3f + .7f * eased;
            canvas.scale(scale, scale, width / 2f, height / 2f);
        }
        if (title.animation.equals("lower-third")) {
            top = height * .9f - layout.getHeight();
            left -= (1 - eased) * width;
            Paint panel = new Paint();
            panel.setColor(0xAA000000);
            canvas.drawRect(left - width * .02f, top - height * .02f,
                    left + textWidth + width * .02f, height * .93f, panel);
        }
        canvas.translate(left, top);
        layout.draw(canvas);
        canvas.restoreToCount(save);
    }

    static float dissolve(EditConfig.IntroTitle title, long timeUs) {
        long durationUs = title.durationMs * 1000L;
        float ramp = Math.min(800000L, durationUs / 2L);
        return Math.max(0, Math.min(1, Math.min(timeUs, durationUs - timeUs) / ramp));
    }

    private static StaticLayout layout(CharSequence text, TextPaint paint, int width, Layout.Alignment alignment) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(alignment).setIncludePad(true).setLineSpacing(0, 1.05f)
                .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE).build();
    }
}
