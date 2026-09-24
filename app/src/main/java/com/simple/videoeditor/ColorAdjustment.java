package com.simple.videoeditor;

import android.graphics.Bitmap;
import org.json.JSONException;
import org.json.JSONObject;

/** Main-video-only, linear-light SDR adjustment; neutral values never insert a GL pass. */
public final class ColorAdjustment {
    public static final ColorAdjustment OFF = new ColorAdjustment(false, 0, 100, 100);
    public final boolean enabled;
    public final int brightness, contrast, saturation;

    public ColorAdjustment(boolean enabled, int brightness, int contrast, int saturation) {
        if (brightness < -100 || brightness > 100 || contrast < 0 || contrast > 200
                || saturation < 0 || saturation > 200) {
            throw new IllegalArgumentException("Color adjustment requires brightness -100..100, contrast/saturation 0..200");
        }
        this.enabled = enabled;
        this.brightness = brightness;
        this.contrast = contrast;
        this.saturation = saturation;
    }

    public boolean isIdentity() {
        return !enabled || (brightness == 0 && contrast == 100 && saturation == 100);
    }

    // Media3 1.5.1 DEFAULT's electrical SDR uses SMPTE170M, not sRGB.
    static double toLinear(double value) {
        return value < .0812 ? value / 4.5 : Math.pow((value + .099) / 1.099, 1 / .45);
    }

    static double toElectrical(double value) {
        return value < .018 ? value * 4.5 : 1.099 * Math.pow(value, .45) - .099;
    }

    float[] matrix() {
        float[] m = new float[16];
        m[15] = 1;
        if (isIdentity()) { m[0] = m[5] = m[10] = 1; return m; }
        float c = contrast / 100f, s = saturation / 100f;
        float[] luma = {.2126f, .7152f, .0722f};
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                m[col * 4 + row] = c * ((1 - s) * luma[col] + (row == col ? s : 0));
            }
            m[12 + row] = (float) ((1 - c) * toLinear(.5) + brightness / 100d);
        }
        return m;
    }

    void applyPreview(Bitmap bitmap) {
        if (isIdentity()) return;
        if (bitmap.getWidth() > 640 || bitmap.getHeight() > 640) {
            throw new IllegalArgumentException("Color preview must be bounded to 640 pixels");
        }
        float[] m = matrix();
        double[] decode = new double[256];
        for (int i = 0; i < decode.length; i++) decode[i] = toLinear(i / 255d);
        int[] row = new int[bitmap.getWidth()];
        for (int y = 0; y < bitmap.getHeight(); y++) {
            if (Thread.currentThread().isInterrupted()) return;
            bitmap.getPixels(row, 0, row.length, 0, y, row.length, 1);
            for (int x = 0; x < row.length; x++) {
                int p = row[x];
                double r = decode[(p >> 16) & 255], g = decode[(p >> 8) & 255], b = decode[p & 255];
                row[x] = (p & 0xff000000)
                        | channel(m[0] * r + m[4] * g + m[8] * b + m[12]) << 16
                        | channel(m[1] * r + m[5] * g + m[9] * b + m[13]) << 8
                        | channel(m[2] * r + m[6] * g + m[10] * b + m[14]);
            }
            bitmap.setPixels(row, 0, row.length, 0, y, row.length, 1);
        }
    }

    private static int channel(double linear) {
        return (int) Math.round(255 * toElectrical(Math.max(0, Math.min(1, linear))));
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("enabled", enabled).put("brightness", brightness)
                .put("contrast", contrast).put("saturation", saturation);
    }

    static ColorAdjustment fromRecipe(JSONObject recipe) throws JSONException {
        if (!recipe.has("colorAdjustment")) return OFF;
        JSONObject value = recipe.getJSONObject("colorAdjustment");
        Object enabled = value.get("enabled");
        if (!(enabled instanceof Boolean)) throw new JSONException("Color enabled must be boolean");
        return new ColorAdjustment((Boolean) enabled, integer(value, "brightness"),
                integer(value, "contrast"), integer(value, "saturation"));
    }

    private static int integer(JSONObject value, String key) throws JSONException {
        Object number = value.get(key);
        if (!(number instanceof Number)) throw new JSONException("Color " + key + " must be an integer");
        double d = ((Number) number).doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)
                || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) {
            throw new JSONException("Invalid color " + key);
        }
        return (int) d;
    }

    @Override public String toString() {
        return "enabled=" + enabled + ", brightness=" + brightness
                + ", contrast=" + contrast + ", saturation=" + saturation + ", linearSDR";
    }
}
