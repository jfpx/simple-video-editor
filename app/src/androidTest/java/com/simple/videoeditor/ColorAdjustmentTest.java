package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.net.Uri;
import android.test.AndroidTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

@androidx.media3.common.util.UnstableApi
public final class ColorAdjustmentTest extends AndroidTestCase {
    public void testNeutralAndDisabledAreExactIdentityAndAbsentFromEffects() {
        for (ColorAdjustment value : new ColorAdjustment[]{ColorAdjustment.OFF,
                new ColorAdjustment(true, 0, 100, 100), new ColorAdjustment(false, 99, 0, 200)}) {
            Bitmap image = palette();
            Bitmap original = image.copy(Bitmap.Config.ARGB_8888, false);
            value.applyPreview(image);
            assertTrue(image.sameAs(original));
            EditConfig config = new EditConfig.Builder(Uri.EMPTY, 1).colorAdjustment(value).build();
            for (androidx.media3.common.Effect effect : Media3ExportEngine.createVideoEffects(config))
                assertFalse(effect instanceof ColorAdjustmentEffect);
            image.recycle(); original.recycle();
        }
    }

    public void testIndependentPaletteAndAllRgbGradients() {
        for (int[] settings : new int[][]{{0,100,0},{15,100,100},{0,160,100},
                {-20,65,180},{35,170,40},{-100,200,200},{100,200,0}}) {
            Bitmap image = palette(), original = image.copy(Bitmap.Config.ARGB_8888, false);
            new ColorAdjustment(true, settings[0], settings[1], settings[2]).applyPreview(image);
            for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                assertColor(expected(original.getPixel(x,y), settings[0], settings[1], settings[2]),
                        image.getPixel(x,y), 1);
            image.recycle(); original.recycle();
        }
    }

    public void testPivotLuminanceBrightnessAndClipAnalyticExpectations() {
        assertEquals(0xff808080, expected(0xff808080, 0, 100, 100));
        assertEquals(0xff808080, expected(0xffff0000, 0, 0, 200));
        // Linear Rec.709 red luminance .2126 becomes about 114 electrical, not gamma-space 54.
        assertColor(0xff727272, expected(0xffff0000, 0, 100, 0), 1);
        assertEquals(0xffffffff, expected(0xff000000, 100, 100, 100));
        assertEquals(0xff000000, expected(0xffffffff, -100, 100, 100));
        assertEquals(0xff000000, expected(0xff202020, 0, 200, 100));
        assertEquals(0xffffffff, expected(0xffeeeeee, 30, 200, 100));
    }

    public void testImmutableValidationStrictJsonAndBoundedPreview() throws Exception {
        for (int[] invalid : new int[][]{{101,100,100},{-101,100,100},{0,-1,100},
                {0,201,100},{0,100,-1},{0,100,201}}) {
            try { new ColorAdjustment(false, invalid[0],invalid[1],invalid[2]); fail(); }
            catch (IllegalArgumentException expected) {}
        }
        JSONObject recipe = new JSONObject().put("colorAdjustment", ColorAdjustment.OFF.toJson());
        for (Object invalid : new Object[]{1.5, "100", JSONObject.NULL, 2147483648L}) {
            recipe.getJSONObject("colorAdjustment").put("contrast", invalid);
            try { ColorAdjustment.fromRecipe(recipe); fail(); }
            catch (org.json.JSONException expected) {}
        }
        Bitmap large = Bitmap.createBitmap(641, 1, Bitmap.Config.ARGB_8888);
        try {
            new ColorAdjustment(true, 1, 100, 100).applyPreview(large); fail();
        } catch (IllegalArgumentException expected) { } finally { large.recycle(); }
    }

    public void testPresetMissingNeutralDisabledRoundTripAndAtomicFailure() throws Exception {
        WholeEditPresets store = new WholeEditPresets(getContext());
        String name = "color-" + UUID.randomUUID();
        JSONObject recipe = new JSONObject().put("crop", new JSONArray(new int[]{0,0,0,0}))
                .put("headMs", 100).put("tailMs", 200);
        assertSame(ColorAdjustment.OFF, ColorAdjustment.fromRecipe(recipe));
        try {
            recipe.put("colorAdjustment", new ColorAdjustment(false, 25, 175, 0).toJson());
            store.save(name, recipe, null, null, null);
            JSONObject saved = new WholeEditPresets(getContext()).load(name);
            ColorAdjustment restored = ColorAdjustment.fromRecipe(saved);
            assertFalse(restored.enabled); assertEquals(25, restored.brightness);
            assertEquals(175, restored.contrast); assertEquals(0, restored.saturation);
            assertEquals(7800L, WholeEditPresets.trim(saved, 8000)[1]);
            recipe.getJSONObject("colorAdjustment").put("contrast", 201);
            try { store.save(name, recipe, null, null, null); fail(); }
            catch (IllegalArgumentException expected) {}
            assertEquals(175, ColorAdjustment.fromRecipe(store.load(name)).contrast);
        } finally { store.delete(name); }
    }

    public void testExpectedCheckerRejectsOmittedAndWrongSettings() {
        int input = 0xffc06030, wanted = expected(input, 15, 160, 0);
        for (int wrong : new int[]{input, expected(input, 0, 100, 0), expected(input, 15, 160, 100)}) {
            boolean rejected = false;
            try { assertColor(wanted, wrong, 12); }
            catch (junit.framework.AssertionFailedError error) { rejected = true; }
            assertTrue("negative color control accepted", rejected);
        }
    }

    // Independent scalar oracle: no production matrix or transfer helper calls.
    static int expected(int pixel, int brightness, int contrast, int saturation) {
        double[] rgb = new double[3];
        for (int i = 0; i < 3; i++) {
            double encoded = ((pixel >> (16 - 8*i)) & 255) / 255d;
            rgb[i] = encoded < .0812 ? encoded / 4.5 : Math.pow((encoded + .099)/1.099, 20d/9);
        }
        double luminance = .2126*rgb[0] + .7152*rgb[1] + .0722*rgb[2];
        double pivot = Math.pow((.5 + .099)/1.099, 20d/9);
        int result = pixel & 0xff000000;
        for (int i = 0; i < 3; i++) {
            double value = luminance + saturation / 100d * (rgb[i] - luminance);
            value = Math.max(0, Math.min(1, (value-pivot)*contrast/100d + pivot + brightness/100d));
            value = value < .018 ? 4.5*value : 1.099*Math.pow(value, .45)-.099;
            result |= ((int) Math.round(255*value)) << (16-8*i);
        }
        return result;
    }

    static void assertColor(int expected, int actual, int tolerance) {
        for (int shift : new int[]{0,8,16})
            assertTrue("color expected=" + Integer.toHexString(expected) + " actual=" + Integer.toHexString(actual),
                    Math.abs(((expected >> shift)&255) - ((actual >> shift)&255)) <= tolerance);
    }

    private static Bitmap palette() {
        Bitmap image = Bitmap.createBitmap(256, 5, Bitmap.Config.ARGB_8888);
        for (int x = 0; x < 256; x++) {
            image.setPixel(x,0,0xff000000 | x*0x010101);
            image.setPixel(x,1,0xff003050 | x << 16);
            image.setPixel(x,2,0xff300050 | x << 8);
            image.setPixel(x,3,0xff305000 | x);
            image.setPixel(x,4,new int[]{0xffff0000,0xff00ff00,0xff0000ff,0xffffff00,
                    0xff00ffff,0xffff00ff,0xff000000,0xffffffff}[x % 8]);
        }
        return image;
    }
}
