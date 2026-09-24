package com.simple.videoeditor;

import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

public final class WholeEditPresetsTest extends AndroidTestCase {
    public void testRemovedEdgesAndSourceSafeTrimRejectInvalidValues() throws Exception {
        float[] crop = CropRemoval.retained("10", "10", "10", "10");
        assertEquals(.1f, crop[0]); assertEquals(.9f, crop[2]);
        for (String bad : new String[]{"", "NaN", "Infinity", "-1", "50.01"}) {
            try { CropRemoval.retained(bad, "0", "0", "0"); fail(bad); }
            catch (IllegalArgumentException expected) {}
        }
        try { CropRemoval.retained("50", "0", "50", "0"); fail(); }
        catch (IllegalArgumentException expected) {}
        JSONObject recipe = recipe();
        long[] trim = WholeEditPresets.trim(recipe, 8000);
        assertEquals(1000L, trim[0]); assertEquals(7000L, trim[1]);
        for (long duration : new long[]{500, 1000, 2000}) {
            try { WholeEditPresets.trim(recipe, duration); fail(); }
            catch (java.io.IOException expected) {}
        }
    }

    public void testNamedCreateUpdateDeleteAndDurableAssetIntegrity() throws Exception {
        WholeEditPresets store = new WholeEditPresets(getContext());
        String name = "test-durable-" + UUID.randomUUID();
        File source = new File(getContext().getCacheDir(), name);
        try {
            try (FileOutputStream out = new FileOutputStream(source)) { out.write(new byte[]{1, 2, 3, 4}); }
            store.save(name, recipe(), null, source, null);
            assertTrue(source.delete());
            JSONObject saved = new WholeEditPresets(getContext()).load(name);
            File durable = store.asset(saved, "musicAsset");
            assertEquals(4L, durable.length());
            assertFalse(saved.has("input")); assertFalse(saved.has("appendedVideos"));
            JSONObject update = recipe().put("headMs", 500);
            store.save(name, update, null, null, null);
            assertFalse("obsolete asset collected after atomic update", durable.exists());
            assertEquals(500L, store.load(name).getLong("headMs"));
            store.delete(name);
            for (String item : store.names()) assertFalse(item.equals(name));
        } finally { source.delete(); store.delete(name); }
    }

    public void testMissingOrChangedAssetFailsWithoutChangingStoredRecipe() throws Exception {
        WholeEditPresets store = new WholeEditPresets(getContext());
        String name = "test-missing-" + UUID.randomUUID();
        File source = new File(getContext().getCacheDir(), name);
        try {
            try (FileOutputStream out = new FileOutputStream(source)) { out.write(new byte[]{1, 2, 3}); }
            store.save(name, recipe(), source, null, null);
            JSONObject saved = store.load(name);
            File asset = store.asset(saved, "introAsset");
            try (FileOutputStream out = new FileOutputStream(asset)) { out.write(new byte[]{9, 8, 7}); }
            try { store.load(name); fail("changed asset accepted"); } catch (java.io.IOException expected) {}
            assertTrue(asset.delete());
            try { store.load(name); fail("missing asset accepted"); } catch (java.io.IOException expected) {}
            try { store.save(name, recipe().put("headMs", 10), source, new File(source + "-missing"), null); fail(); }
            catch (java.io.IOException expected) {}
            try { store.load(name); fail("failed update must not erase previous required missing asset"); }
            catch (java.io.IOException expected) {}
        } finally { source.delete(); store.delete(name); }
    }

    public void testLegacyTitleRoundTripAndBoundedNewTitleValidation() throws Exception {
        IntroTemplate old = new IntroTemplate("old", "Legacy", 48, -1, 0xFF000000, .5f, .5f, 1000, "normal");
        JSONObject json = new JSONObject(old.toJson());
        json.remove("schemaVersion"); json.remove("layout");
        json.remove("animation"); json.remove("fontFamily"); json.remove("alignment");
        IntroTemplate migrated = IntroTemplate.fromJsonStrict(json.toString());
        assertEquals("legacy", migrated.getAnimation()); assertEquals(1000, migrated.getDurationMs());
        for (String family : new String[]{"sans-serif", "serif", "monospace"}) {
            old.setAnimation("typewriter"); old.setDurationMs(5000); old.setFontFamily(family);
            old.setAlignment("right"); old.setGradientColor(0xFF203040);
            IntroTemplate restored = IntroTemplate.fromJsonStrict(old.toJson());
            assertEquals(family, restored.getFontFamily()); assertEquals("right", restored.getAlignment());
            assertEquals(0xFF203040, restored.getGradientColor());
            assertEquals("typewriter", restored.copy().getAnimation());
        }
        old.setTextSize(161);
        try { new EditConfig.Builder(android.net.Uri.EMPTY, 1000).sourceSize(320, 240).introTemplate(old, "hello").build(); fail(); }
        catch (IllegalArgumentException expected) {}
    }

    public void testVersionOneRetainedCropMigratesWithoutReinterpretation() throws Exception {
        WholeEditPresets store = new WholeEditPresets(getContext());
        String name = "test-migrate-" + UUID.randomUUID();
        try {
            store.save(name, recipe(), null, null, null);
            android.util.AtomicFile index = new android.util.AtomicFile(
                    new File(getContext().getFilesDir(), "whole-edit-presets/index.json"));
            JSONObject all = new JSONObject(new String(index.readFully(), java.nio.charset.StandardCharsets.UTF_8));
            all.getJSONObject(name).put("version", 1).put("crop", new JSONArray(new double[]{.1, .1, .9, .9}));
            FileOutputStream output = index.startWrite();
            output.write(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); index.finishWrite(output);
            JSONObject migrated = store.load(name);
            assertEquals(2, migrated.getInt("version")); assertEquals(10d, migrated.getJSONArray("crop").getDouble(2));
            assertEquals(.9f, WholeEditPresets.crop(migrated)[2]);
            all.getJSONObject(name).put("crop", new JSONArray(new double[]{.6, 0, .9, 1}));
            output = index.startWrite();
            output.write(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); index.finishWrite(output);
            try { store.load(name); fail("nonrepresentable legacy crop must require correction"); }
            catch (IllegalArgumentException expected) {}
        } finally { store.delete(name); }
    }

    private static JSONObject recipe() throws Exception {
        return new JSONObject().put("headMs", 1000).put("tailMs", 1000)
                .put("crop", new JSONArray(new int[]{10, 10, 10, 10}));
    }
}
