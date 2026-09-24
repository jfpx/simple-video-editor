package com.simple.videoeditor;

import android.test.InstrumentationTestCase;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Metadata-only capacity fixtures; never packaged or advertised as real recordings. */
public final class MusicCatalogScaleTest extends InstrumentationTestCase {
    private JSONObject original() throws Exception {
        try (InputStream input = getInstrumentation().getTargetContext().getAssets().open("music/catalog.json");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
            return new JSONObject(bytes.toString("UTF-8")).getJSONArray("tracks").getJSONObject(0);
        }
    }

    private byte[] metadata(int count, long size) throws Exception {
        JSONObject original = original();
        JSONArray rows = new JSONArray();
        for (int i = 0; i < count; i++) {
            rows.put(new JSONObject(original.toString()).put("id", "metadata-" + i)
                    .put("title", "Metadata fixture" + i).put("titleZh", "元数据 fixture" + i)
                    .put("path", "music/test/metadata-" + i + ".ogg").put("bytes", size));
        }
        return new JSONObject().put("version", 1).put("tracks", rows).toString().getBytes(StandardCharsets.UTF_8);
    }

    public void testHundredMetadataRowsOpenOnlySelectedVerifiedAsset() throws Exception {
        JSONObject original = original();
        byte[] metadata = metadata(100, original.getLong("bytes"));
        List<String> opened = new ArrayList<>();
        OfflineMusicCatalog catalog = OfflineMusicCatalog.load(path -> {
            opened.add(path);
            return path.equals("music/catalog.json") ? new ByteArrayInputStream(metadata)
                    : getInstrumentation().getTargetContext().getAssets().open(original.optString("path"));
        });
        assertEquals(100, catalog.tracks.size());
        assertEquals(1, opened.size());
        File selected = new File(getInstrumentation().getTargetContext().getCacheDir(),
                "scale-selected-" + java.util.UUID.randomUUID() + ".ogg");
        try {
            catalog.copyVerified("metadata-99", selected);
            assertEquals(original.getLong("bytes"), selected.length());
            assertEquals(2, opened.size());
            assertEquals("music/test/metadata-99.ogg", opened.get(1));
        } finally { selected.delete(); }
    }

    public void testFiniteCountMetadataAndAggregateBudgets() throws Exception {
        byte[] maximum = metadata(256, 1024 * 1024);
        assertTrue(maximum.length < OfflineMusicCatalog.MAX_CATALOG_BYTES);
        assertEquals(256, OfflineMusicCatalog.load(path -> new ByteArrayInputStream(maximum)).tracks.size());
        reject(metadata(257, 100000));
        reject(metadata(256, 1024 * 1024 + 1));
        reject(new byte[OfflineMusicCatalog.MAX_CATALOG_BYTES + 1]);
    }

    public void testBilingualSearchUsesOnlyMetadata() throws Exception {
        OfflineMusicCatalog.Track track = OfflineMusicCatalog.load(
                getInstrumentation().getTargetContext()).tracks.get(0);
        assertTrue(OfflineMusicControls.matches(track, track.title.toUpperCase(java.util.Locale.ROOT)));
        assertTrue(OfflineMusicControls.matches(track, track.titleZh));
        assertTrue(OfflineMusicControls.matches(track, track.mood + " " + track.style));
        assertTrue(OfflineMusicControls.matches(track, track.author));
        assertTrue(OfflineMusicControls.matches(track, "  "));
        assertFalse(OfflineMusicControls.matches(track, "not-a-real-search-hit-999"));
    }

    public void testActualHundredRowPickerSearchSelectsStableIdWithoutOpeningAudio() throws Exception {
        android.content.Context context = getInstrumentation().getTargetContext();
        android.content.SharedPreferences preferences = context.getSharedPreferences("offline_music", 0);
        String previous = preferences.getString("selection", null);
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                new android.content.Intent(context, MainActivity.class).addFlags(
                        android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        try {
            long deadline = android.os.SystemClock.elapsedRealtime() + 30000;
            while ((Boolean) field(activity, "restoringUi") || (Boolean) field(activity, "restoringAssets")) {
                assertTrue(android.os.SystemClock.elapsedRealtime() < deadline);
                Thread.sleep(50);
            }
            ((java.util.concurrent.ExecutorService) field(activity, "publicationWorker"))
                    .submit(() -> {}).get(20, java.util.concurrent.TimeUnit.SECONDS);
            OfflineMusicControls controls = (OfflineMusicControls) field(activity, "offlineMusic");
            byte[] metadata = metadata(100, 100000);
            java.util.concurrent.atomic.AtomicInteger opens = new java.util.concurrent.atomic.AtomicInteger();
            OfflineMusicCatalog catalog = OfflineMusicCatalog.load(path -> {
                assertEquals("music/catalog.json", path);
                opens.incrementAndGet();
                return new ByteArrayInputStream(metadata);
            });
            java.lang.reflect.Field catalogField = OfflineMusicControls.class.getDeclaredField("catalog");
            catalogField.setAccessible(true);
            getInstrumentation().runOnMainSync(() -> {
                try { catalogField.set(controls, catalog); }
                catch (Exception error) { throw new AssertionError(error); }
                ((com.google.android.material.tabs.TabLayout) activity.findViewById(R.id.editorTabs)).getTabAt(1).select();
                activity.findViewById(R.id.btnLibraryTrack).performClick();
            });
            click(context.getString(R.string.bgm_all_tracks));
            android.view.accessibility.AccessibilityNodeInfo search = null;
            deadline = android.os.SystemClock.elapsedRealtime() + 10000;
            while (search == null && android.os.SystemClock.elapsedRealtime() < deadline) {
                search = edit(getInstrumentation().getUiAutomation().getRootInActiveWindow());
                if (search == null) Thread.sleep(50);
            }
            assertNotNull("search field visible", search);
            assertTrue("search must be physically visible above the scrolling list", search.isVisibleToUser());
            android.graphics.Rect searchBounds = new android.graphics.Rect();
            search.getBoundsInScreen(searchBounds);
            assertFalse(searchBounds.isEmpty());
            assertTrue(searchBounds.bottom <= context.getResources().getDisplayMetrics().heightPixels);
            android.os.Bundle arguments = new android.os.Bundle();
            arguments.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    "fixture99");
            assertTrue(search.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
            getInstrumentation().waitForIdleSync();
            click("fixture99");
            getInstrumentation().runOnMainSync(() -> {
                assertEquals("metadata-99", controls.snapshot().trackId);
            });
            assertEquals("picker never opens any audio", 1, opens.get());
        } finally {
            getInstrumentation().runOnMainSync(activity::finish);
            getInstrumentation().waitForIdleSync();
            preferences.edit().putString("selection", previous).commit();
        }
    }

    private Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private android.view.accessibility.AccessibilityNodeInfo edit(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = edit(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private void click(String text) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + 10000;
        while (android.os.SystemClock.elapsedRealtime() < deadline) {
            android.view.accessibility.AccessibilityNodeInfo root =
                    getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root != null) for (android.view.accessibility.AccessibilityNodeInfo node :
                    root.findAccessibilityNodeInfosByText(text)) {
                // The search box contains the query too, but is never a track choice.
                if (node.isEditable()) continue;
                while (node != null && !node.isClickable()) node = node.getParent();
                if (node != null && node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                    getInstrumentation().waitForIdleSync();
                    return;
                }
            }
            Thread.sleep(50);
        }
        fail("Missing visible picker target: " + text);
    }

    private void reject(byte[] metadata) throws Exception {
        try {
            OfflineMusicCatalog.load(path -> new ByteArrayInputStream(metadata));
            fail("invalid catalog accepted");
        } catch (IOException expected) {}
    }
}
