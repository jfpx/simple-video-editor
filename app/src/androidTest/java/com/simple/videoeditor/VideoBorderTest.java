package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.net.Uri;
import android.test.AndroidTestCase;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import junit.framework.AssertionFailedError;

public final class VideoBorderTest extends AndroidTestCase {
    @androidx.media3.common.util.UnstableApi
    public void testFourCachedOpaquePixelsAndMusicPassHasNoVideoEffects() throws Exception {
        java.lang.reflect.Constructor<BorderOverlay> constructor =
                BorderOverlay.class.getDeclaredConstructor(VideoBorder.class, int.class);
        constructor.setAccessible(true);
        VideoBorder border = new VideoBorder(true, 0, 4, VideoBorder.Scope.WHOLE);
        for (int edge = 0; edge < 4; edge++) {
            BorderOverlay overlay = constructor.newInstance(border, edge);
            overlay.configure(new androidx.media3.common.util.Size(320, 240));
            Bitmap pixel = overlay.getBitmap(0);
            assertEquals(1, pixel.getWidth()); assertEquals(1, pixel.getHeight());
            assertEquals(0xFFFF0000, pixel.getPixel(0, 0));
            for (long time : new long[]{33333, 3000000, 5000000, 120000000}) {
                assertSame("no per-frame allocation", pixel, overlay.getBitmap(time));
                assertSame(overlay.getOverlaySettings(0), overlay.getOverlaySettings(time));
            }
            pixel.recycle();
        }
        androidx.media3.transformer.Composition music = Media3ExportEngine.createMusicComposition(
                Uri.parse("content://test/rendered-video"), Uri.parse("content://test/music"));
        assertTrue(music.transmuxVideo);
        assertTrue(music.effects.videoEffects.isEmpty());
        assertTrue(music.sequences.get(0).editedMediaItems.get(0).effects.videoEffects.isEmpty());
    }

    public void testExactFourSidesCornersAlphaRoundingAndUnchangedInterior() {
        int[] colors = {0xFFFF0000, 0xFFFFD600, 0xFF0066FF, 0xFF00CC66, 0xFFFFFFFF, 0xFF000000};
        for (int[] size : new int[][]{{320, 240}, {180, 320}, {193, 257}, {2, 2}}) {
            for (int percent = 1; percent <= 8; percent++) for (int color = 0; color < colors.length; color++) {
                Bitmap original = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
                original.eraseColor(0xFF304050);
                Bitmap actual = original.copy(Bitmap.Config.ARGB_8888, true);
                BorderRenderer.draw(new Canvas(actual), size[0], size[1],
                        new VideoBorder(true, color, percent, VideoBorder.Scope.WHOLE));
                if (size[0] == 2) {
                    for (int y = 0; y < 2; y++) for (int x = 0; x < 2; x++) assertEquals(colors[color], actual.getPixel(x, y));
                } else BorderPixelChecks.check(actual, original, percent, colors[color], true, 0, 0);
                actual.recycle(); original.recycle();
            }
        }
    }

    public void testSameCheckerRejectsOmittedWrongColorWidthScopeAndPadding() {
        Bitmap original = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
        original.eraseColor(0xFF304050);
        for (int fault = 0; fault < 6; fault++) {
            Bitmap actual = original.copy(Bitmap.Config.ARGB_8888, true);
            if (fault != 0) BorderRenderer.draw(new Canvas(actual), 320, 240,
                    new VideoBorder(true, fault == 1 ? 2 : 0, fault == 2 ? 1 : fault == 5 ? 5 : 4, VideoBorder.Scope.WHOLE));
            if (fault == 4) { actual.recycle(); actual = Bitmap.createBitmap(340, 260, Bitmap.Config.ARGB_8888); }
            for (int tolerance : new int[]{0, 32}) {
                boolean rejected = false;
                try { BorderPixelChecks.check(actual, original, 4, 0xFFFF0000, fault != 3, tolerance, tolerance == 0 ? 0 : 6); }
                catch (AssertionFailedError expected) { rejected = true; }
                assertTrue("negative control " + fault + " tolerance=" + tolerance, rejected);
            }
            actual.recycle();
        }
        original.recycle();
    }

    public void testTitleOnlyRequiresGeneratedTitleAndDisabledKeepsSettings() throws Exception {
        assertFalse(new EditConfig.Builder(Uri.EMPTY, 1000).build().border.enabled);
        VideoBorder disabled = new VideoBorder(false, 2, 8, VideoBorder.Scope.TITLE);
        EditConfig config = new EditConfig.Builder(Uri.EMPTY, 1000).border(disabled).build();
        assertSame(disabled, config.border);
        Bitmap bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF304050);
        BorderRenderer.draw(new Canvas(bitmap), 1, 1, disabled);
        assertEquals(0xFF304050, bitmap.getPixel(0, 0));
        bitmap.recycle();
        for (Uri intro : new Uri[]{null, Uri.parse("content://test/imported-intro")}) {
            try {
                new EditConfig.Builder(Uri.EMPTY, 1000).intro(intro)
                        .border(new VideoBorder(true, 0, 2, VideoBorder.Scope.TITLE)).build();
                fail("imported intro is not a generated title");
            } catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Title-only")); }
        }
        for (int value : new int[]{0, 9}) {
            try { new VideoBorder(true, 0, value, VideoBorder.Scope.WHOLE); fail(); }
            catch (IllegalArgumentException expected) {}
        }
        assertFalse(disabled.appliesTo(true));
        assertTrue(new VideoBorder(true, 0, 2, VideoBorder.Scope.TITLE).appliesTo(true));
        assertFalse(new VideoBorder(true, 0, 2, VideoBorder.Scope.TITLE).appliesTo(false));
    }

    public void testPresetV3RoundTripDisabledSelectionsAndOldMissingFields() throws Exception {
        WholeEditPresets store = new WholeEditPresets(getContext());
        String name = "border-" + UUID.randomUUID();
        JSONObject recipe = new JSONObject().put("crop", new JSONArray(new int[]{0, 0, 0, 0}))
                .put("headMs", 0).put("tailMs", 0).put("titleEnabled", false);
        try {
            assertFalse(VideoBorder.fromRecipe(recipe).enabled);
            for (boolean enabled : new boolean[]{false, true}) {
                VideoBorder border = new VideoBorder(enabled, 3, 7, VideoBorder.Scope.WHOLE);
                store.save(name, recipe.put("border", border.toJson()), null, null, null);
                JSONObject saved = new WholeEditPresets(getContext()).load(name);
                assertEquals(6, saved.getInt("version"));
                assertFalse(BackgroundMusic.fromRecipe(saved).enabled);
                VideoBorder restored = VideoBorder.fromRecipe(saved);
                assertEquals(enabled, restored.enabled); assertEquals(3, restored.colorIndex);
                assertEquals(7, restored.percent); assertEquals(VideoBorder.Scope.WHOLE, restored.scope);
            }
            recipe.put("border", new VideoBorder(true, 0, 2, VideoBorder.Scope.TITLE).toJson());
            try { store.save(name, recipe, null, null, null); fail("invalid title scope saved"); }
            catch (IllegalArgumentException expected) {}
            assertEquals(VideoBorder.Scope.WHOLE, VideoBorder.fromRecipe(store.load(name)).scope);
            recipe.remove("border");
            store.save(name, recipe, null, null, null);
            assertFalse(VideoBorder.fromRecipe(store.load(name)).enabled);
            android.util.AtomicFile index = new android.util.AtomicFile(
                    new java.io.File(getContext().getFilesDir(), "whole-edit-presets/index.json"));
            JSONObject all = new JSONObject(new String(index.readFully(), java.nio.charset.StandardCharsets.UTF_8));
            for (int version : new int[]{1, 2, 3}) {
                all.getJSONObject(name).put("version", version).put("crop",
                        new JSONArray(version == 1 ? new int[]{0, 0, 1, 1} : new int[]{0, 0, 0, 0}));
                all.getJSONObject(name).remove("bgm");
                java.io.FileOutputStream output = index.startWrite();
                output.write(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                index.finishWrite(output);
                assertFalse("old preset v" + version, VideoBorder.fromRecipe(store.load(name)).enabled);
            }
        } finally { store.delete(name); }
    }
}
