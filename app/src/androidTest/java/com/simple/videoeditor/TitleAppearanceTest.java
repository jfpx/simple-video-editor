package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.test.AndroidTestCase;
import org.json.JSONException;
import org.json.JSONObject;

public final class TitleAppearanceTest extends AndroidTestCase {
    public void testLegacyMappingAndRoundTripPreserveAppearance() throws Exception {
        for (String animation : new String[]{"legacy", "fade", "slide", "typewriter", "scale", "lower-third"}) {
            IntroTemplate old = new IntroTemplate();
            old.setAnimation(animation);
            old.setText("旧版\nSecond line");
            old.setTextSize(52);
            old.setTextY(.4f);
            old.setTextColor(0xCCFE8123);
            old.setBackgroundColor(0x801976D2);
            old.setGradientColor(0xA0356B83);
            JSONObject unversioned = new JSONObject(old.toJson());
            unversioned.remove("schemaVersion");
            unversioned.remove("layout");
            IntroTemplate migrated = IntroTemplate.fromJsonStrict(unversioned.toString());
            assertEquals(animation.equals("legacy") ? "legacy-sp" : "canvas", migrated.getLayout());
            assertEquals(2, new JSONObject(migrated.toJson()).getInt("schemaVersion"));
            assertEquals(old.toJson(), migrated.toJson());
            Bitmap before = render(old, 640, 360, 1200000);
            Bitmap after = render(IntroTemplate.fromJsonStrict(migrated.toJson()), 640, 360, 1200000);
            assertTrue("old appearance is byte-identical after migration", before.sameAs(after));
            before.recycle(); after.recycle();
        }
        JSONObject oldest = new JSONObject(new IntroTemplate().toJson());
        for (String key : new String[]{"schemaVersion", "layout", "animation", "alignment", "fontFamily",
                "sourceFrameBackground", "sourceFrameSeconds"}) oldest.remove(key);
        IntroTemplate classic = IntroTemplate.fromJsonStrict(oldest.toString());
        assertEquals("legacy", classic.getAnimation());
        assertEquals("legacy-sp", classic.getLayout());
        assertEquals(48, classic.getTextSize());
        assertEquals(0xFFFFFFFF, classic.getTextColor());
    }

    public void testStrictTypesAndRangesRejectInsteadOfDefault() throws Exception {
        for (String key : new String[]{"textSize", "textColor", "backgroundColor", "gradientColor", "durationMs"}) {
            for (Object invalid : new Object[]{"48", 48.5, true, JSONObject.NULL, 4294967295L}) {
                JSONObject json = new JSONObject(new IntroTemplate().toJson()).put(key, invalid);
                rejected(json);
            }
        }
        for (int size : new int[]{0, -1, 161}) rejected(new JSONObject(new IntroTemplate().toJson()).put("textSize", size));
        for (String key : new String[]{"animation", "fontFamily", "alignment", "layout", "fontStyle"}) {
            rejected(new JSONObject(new IntroTemplate().toJson()).put(key, "unsupported"));
            rejected(new JSONObject(new IntroTemplate().toJson()).put(key, 4));
        }
        rejected(new JSONObject(new IntroTemplate().toJson()).put("schemaVersion", 3));
        rejected(new JSONObject(new IntroTemplate().toJson()).put("sourceFrameBackground", "false"));
        rejected(new JSONObject(new IntroTemplate().toJson()).put("textX", "0.5"));
        rejected(new JSONObject(new IntroTemplate().toJson()).put("durationMs", 0));
        for (String key : new String[]{"layout", "animation", "sourceFrameBackground", "sourceFrameSeconds"}) {
            JSONObject versionTwo = new JSONObject(new IntroTemplate().toJson());
            versionTwo.remove(key);
            rejected(versionTwo);
        }
        try { IntroTemplate.fromJson("bad JSON"); fail("No silent default"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Invalid title")); }
    }

    public void testCustomDurationStyleSnapshotsAndSizeScaling() throws Exception {
        IntroTemplate template = new IntroTemplate();
        template.setLayout("canvas");
        template.setText("OI\nOI");
        template.setTextColor(0xFFFF0000);
        template.setBackgroundColor(0xFF000000);
        template.setTextSize(48);
        template.setDurationMs(2400);
        String[] animations = {"legacy", "fade", "slide", "typewriter", "scale", "lower-third", "dissolve"};
        for (String animation : animations) {
            template.setAnimation(animation);
            EditConfig.IntroTitle title = snapshot(template);
            assertEquals("canvas", title.layout);
            assertEquals(2400, title.durationMs);
            assertEquals(48, title.textSizeSp);
            assertEquals(0xFFFF0000, title.textColor);
            assertEquals(0xFF000000, title.backgroundColor);
            assertEquals(template.toJson(), IntroTemplate.fromJsonStrict(template.toJson()).toJson());
        }
        EditConfig.IntroTitle immutable = snapshot(template);
        template.setTextSize(96);
        template.setTextColor(0xFF00FF00);
        assertEquals(48, immutable.textSizeSp);
        assertEquals(0xFFFF0000, immutable.textColor);
        template.setTextColor(0xFFFF0000);
        Bitmap large = render(template, 640, 360, 1200000);
        template.setTextSize(48);
        Bitmap small = render(template, 640, 360, 1200000);
        Bitmap doubleCanvas = render(template, 1280, 720, 1200000);
        assertTrue(red(large) > red(small) * 2);
        assertTrue(red(doubleCanvas) > red(small) * 3);
        for (long time : new long[]{0, 120000, 2280000, 2400000}) {
            Bitmap fading = render(template, 640, 360, time);
            assertTrue("dissolve fades at both ends", redEnergy(fading) < redEnergy(small) * .3);
            fading.recycle();
        }
        Bitmap omitted = Bitmap.createBitmap(640, 360, Bitmap.Config.ARGB_8888);
        assertEquals(0, red(omitted));
        assertTrue("negative blank cannot satisfy visible title assertion", red(small) > 100);
        large.recycle(); small.recycle(); doubleCanvas.recycle(); omitted.recycle();
        for (int size : new int[]{1, 160}) { template.setTextSize(size); snapshot(template); }
    }

    public void testCorruptStoreIsNotOverwrittenWithDefaults() throws Exception {
        android.content.SharedPreferences prefs = getContext().getSharedPreferences("intro_templates", 0);
        java.util.Map<String, ?> original = prefs.getAll();
        String bad = "[\"{broken}\"]";
        try {
            assertTrue(prefs.edit().clear().putString("templates", bad).commit());
            try { new IntroTemplateManager(getContext()).getAllTemplates(); fail("Corrupt store accepted"); }
            catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Cannot load")); }
            assertEquals(bad, prefs.getString("templates", ""));
        } finally {
            android.content.SharedPreferences.Editor editor = prefs.edit().clear();
            for (java.util.Map.Entry<String, ?> entry : original.entrySet()) {
                if (entry.getValue() instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) entry.getValue());
                else editor.putString(entry.getKey(), (String) entry.getValue());
            }
            assertTrue(editor.commit());
        }
    }

    public void testDraftCanBeCorrectedButPresetRejectsInvalidAndIntegralFloatTypes() throws Exception {
        IntroTemplate draft = new IntroTemplate();
        draft.setLayout("canvas");
        draft.setTextColor(0x80123456);
        draft.setTextSize(0);
        draft.setDurationMs(0);
        IntroTemplate restored = IntroTemplate.fromDraftJson(draft.toJson());
        assertEquals(0x80123456, restored.getTextColor());
        assertEquals(0, restored.getTextSize());
        rejected(new JSONObject(draft.toJson()));
        restored.setTextSize(48); restored.setDurationMs(2400); restored.validate();
        JSONObject floatingColor = new JSONObject(new IntroTemplate().toJson()).put("textColor", -1.0);
        try { IntroTemplate.fromJsonStrict(floatingColor); fail("Double is not an integer color"); }
        catch (JSONException expected) { assertTrue(expected.getMessage().contains("integer")); }
        JSONObject recipe = new JSONObject().put("title", floatingColor);
        try {
            new WholeEditPresets(getContext()).save("invalid-floating-color", recipe, null, null, null);
            fail("Whole preset must validate before JSON serialization normalizes floating numbers");
        } catch (JSONException expected) { assertTrue(expected.getMessage().contains("integer")); }
    }

    private static EditConfig.IntroTitle snapshot(IntroTemplate template) {
        return new EditConfig.Builder(Uri.EMPTY, 1).sourceSize(640, 360)
                .introTemplate(template, template.getText()).build().introTitle;
    }

    private static Bitmap render(IntroTemplate template, int width, int height, long time) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        TitleRenderer.draw(new Canvas(bitmap), width, height, snapshot(template), time);
        return bitmap;
    }

    private static void rejected(JSONObject json) throws Exception {
        try { IntroTemplate.fromJsonStrict(json.toString()); fail("Accepted malformed title: " + json); }
        catch (JSONException expected) { assertNotNull(expected.getMessage()); }
    }

    static int red(Bitmap bitmap) {
        int count = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) {
            int pixel = bitmap.getPixel(x, y);
            if (android.graphics.Color.red(pixel) > 120 && android.graphics.Color.green(pixel) < 70
                    && android.graphics.Color.blue(pixel) < 70) count++;
        }
        return count;
    }

    private static long redEnergy(Bitmap bitmap) {
        long sum = 0;
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++)
            sum += android.graphics.Color.red(bitmap.getPixel(x, y));
        return sum;
    }
}
