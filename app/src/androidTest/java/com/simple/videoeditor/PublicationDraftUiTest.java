package com.simple.videoeditor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.*;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.lang.reflect.Field;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

@androidx.media3.common.util.UnstableApi
public final class PublicationDraftUiTest extends InstrumentationTestCase {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity editor;
    private PublicationDraftActivity activity;
    private File evidence;

    public void testRealPickerExportDraftSaveRestartManifestRebindCopyShare() throws Exception {
        instrumentation = getInstrumentation();
        context = instrumentation.getTargetContext();
        android.accessibilityservice.AccessibilityServiceInfo info = instrumentation.getUiAutomation().getServiceInfo();
        info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        instrumentation.getUiAutomation().setServiceInfo(info);
        evidence = new File(context.getFilesDir(), "publication-ui-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        String sourceName = "draft-source-" + System.currentTimeMillis() + ".mp4";
        String manifestName = sourceName + ".json";
        File fixture = new OracleVerifier(context).prepareFixture();
        PublicationDraftMedia.Fingerprint original;
        try (InputStream input = new FileInputStream(fixture)) { original = PublicationDraftMedia.hash(input); }
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, sourceName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        Uri source = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(source);
        Uri renamed = null, altered = null, cover = null;
        try (InputStream input = new FileInputStream(fixture); OutputStream output = context.getContentResolver().openOutputStream(source)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
        try {
            editor = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            await(() -> editor.findViewById(R.id.btnSelectVideo).isEnabled());
            ui(() -> editor.findViewById(R.id.btnSelectVideo).performClick());
            screenshot("00-picker");
            downloads();
            screenshot("00-downloads");
            clickNode(sourceName);
            screenshot("00-source-selected");
            await(() -> field(editor, "selectedMainVideo") != null && !(boolean) field(editor, "loading"));
            ui(() -> editor.findViewById(R.id.btnProcess).performClick());
            awaitLong(() -> field(editor, "lastVideo") != null && ((PublishedVideo) field(editor, "lastVideo")).uri != null
                    && !(boolean) field(editor, "exporting"));
            PublishedVideo exported = read(() -> (PublishedVideo) field(editor, "lastVideo"));
            screenshot("01-public-movies-export");
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(PublicationDraftActivity.class.getName(), null, false);
            ui(() -> editor.findViewById(R.id.btnPublicationDraft).performClick());
            activity = (PublicationDraftActivity) instrumentation.waitForMonitorWithTimeout(monitor, 10000);
            instrumentation.removeMonitor(monitor);
            assertNotNull(activity);
            ready();
            if (read(() -> field(activity, "draft")) == null) {
                PublicationDraftMedia.Fingerprint fingerprint;
                try (InputStream input = context.getContentResolver().openInputStream(exported.uri)) {
                    fingerprint = PublicationDraftMedia.hash(input);
                }
                for (JSONObject candidate : new PublicationDraftStore(context).list()) {
                    if (fingerprint.sha.equals(candidate.getString("mediaSha256"))) {
                        chooseDraft(candidate); break;
                    }
                }
                ready();
            }
            JSONObject draft = current();
            assertEquals(exported.uri.toString(), draft.getString("videoUri"));
            assertEquals(PublicationExportSnapshot.load(context, exported.uri.toString()).getString("musicCredits"), draft.getString("musicCredits"));
            PublicationDraftMedia.Fingerprint actual;
            try (InputStream input = context.getContentResolver().openInputStream(exported.uri)) { actual = PublicationDraftMedia.hash(input); }
            assertEquals(actual.sha, draft.getString("mediaSha256"));
            assertEquals(actual.bytes, draft.getLong("mediaBytes"));
            String id = draft.getString("id");
            ui(() -> ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .setPrimaryClip(ClipData.newPlainText("review", "unchanged clipboard")));
            ui(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText(""));
            press(R.string.draft_copy_all);
            assertEquals("unchanged clipboard", read(() -> ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .getPrimaryClip().getItemAt(0).getText().toString()));
            press(R.string.draft_share_video); ready();
            assertTrue(read(this::readMessage).contains("Title is required"));
            assertEquals(draft.getString("title"), new PublicationDraftStore(context).load(id).getString("title"));
            screenshot("01-invalid-title-no-share");
            ui(() -> {
                ((EditText) activity.findViewById(R.id.draftTitle)).setText("旅行🎬 native draft");
                ((EditText) activity.findViewById(R.id.draftDescription)).setText("说明第一行\nSecond line");
                ((EditText) activity.findViewById(R.id.draftTags)).setText("中文\n🎵\nlocal only");
                ((EditText) activity.findViewById(R.id.draftLanguage)).setText("zh-CN");
                ((Spinner) activity.findViewById(R.id.draftPlatform)).setSelection(1);
                ((Spinner) activity.findViewById(R.id.draftPrivacy)).setSelection(1);
                ((Spinner) activity.findViewById(R.id.draftAudience)).setSelection(2);
                ((Spinner) activity.findViewById(R.id.draftLicense)).setSelection(2);
            });
            for (int action : new int[]{R.string.draft_copy_title, R.string.draft_copy_description, R.string.draft_copy_tags}) {
                press(action);
                String expected = action == R.string.draft_copy_title ? "旅行🎬 native draft"
                        : action == R.string.draft_copy_description ? "说明第一行\nSecond line" : "中文\n🎵\nlocal only";
                assertEquals(expected, read(() -> ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                        .getPrimaryClip().getItemAt(0).getText().toString()));
            }
            assertEquals(draft.getString("title"), new PublicationDraftStore(context).load(id).getString("title"));
            screenshot("02-edited-cn-form");
            press(R.string.draft_save); ready();
            assertEquals("旅行🎬 native draft", new PublicationDraftStore(context).load(id).getString("title"));
            assertEquals("unlisted", new PublicationDraftStore(context).load(id).getString("privacy"));
            assertEquals("not-made-for-kids", new PublicationDraftStore(context).load(id).getString("audience"));
            assertEquals("creative-commons-attribution", new PublicationDraftStore(context).load(id).getString("publicationLicense"));
            assertTrue((boolean) read(() -> field(activity, "videoReadable")));
            String coverName = "cover-" + sourceName + ".png";
            ContentValues image = new ContentValues();
            image.put(MediaStore.MediaColumns.DISPLAY_NAME, coverName);
            image.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
            image.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            image.put(MediaStore.MediaColumns.IS_PENDING, 1);
            cover = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, image);
            Bitmap coverBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
            try (OutputStream output = context.getContentResolver().openOutputStream(cover)) {
                assertTrue(coverBitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { coverBitmap.recycle(); }
            ContentValues finishedCover = new ContentValues(); finishedCover.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(cover, finishedCover, null, null);
            press(R.string.draft_cover); downloads(); clickNode(coverName); ready();
            String savedCover = current().getString("coverUri");
            assertFalse(savedCover.isEmpty());
            ui(() -> activity.findViewById(R.id.draftLicense).requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, activity.findViewById(R.id.draftLicense).getWidth(),
                            activity.findViewById(R.id.draftLicense).getHeight()), true));
            screenshot("02-license-intention-separate-from-music");
            ui(() -> activity.finish());
            await(() -> activity.isDestroyed());
            activity = (PublicationDraftActivity) instrumentation.startActivitySync(new Intent(context, PublicationDraftActivity.class)
                    .putExtra(PublicationDraftActivity.EXPORT_URI, exported.uri.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ready();
            assertEquals(id, current().getString("id"));
            assertEquals("zh-CN", read(() -> ((EditText) activity.findViewById(R.id.draftLanguage)).getText().toString()));
            String copyName = "renamed-" + sourceName;
            renamed = copyVideo(exported.uri, copyName, false);
            altered = copyVideo(exported.uri, "altered-" + sourceName, true);
            press(R.string.draft_video); downloads(); clickNode(copyName); ready();
            chooseIfShown(new PublicationDraftStore(context).load(id)); ready();
            assertEquals(id, current().getString("id"));
            assertEquals(copyName, current().getString("mediaName"));
            assertEquals("旅行🎬 native draft", current().getString("title"));
            assertEquals("creative-commons-attribution", current().getString("publicationLicense"));
            assertEquals(draft.getString("musicCredits"), current().getString("musicCredits"));
            assertEquals(savedCover, current().getString("coverUri"));
            assertEquals(Uri.parse(savedCover), PublicationDraftMedia.share(context, current(), true)
                    .getParcelableExtra(Intent.EXTRA_STREAM));
            String copyUri = current().getString("videoUri");
            assertFalse(exported.uri.toString().equals(copyUri));
            assertTrue(read(this::readMessage).contains(activity.getString(R.string.draft_reused)));
            assertEquals(Uri.parse(copyUri), PublicationDraftMedia.share(context, current(), false)
                    .getParcelableExtra(Intent.EXTRA_STREAM));
            screenshot("02-byte-identical-renamed-reuses-id");
            Instrumentation.ActivityMonitor recreated = instrumentation.addMonitor(PublicationDraftActivity.class.getName(), null, false);
            ui(() -> activity.recreate());
            activity = (PublicationDraftActivity) instrumentation.waitForMonitorWithTimeout(recreated, 15000);
            instrumentation.removeMonitor(recreated); assertNotNull(activity); ready();
            assertEquals(id, current().getString("id"));
            press(R.string.draft_save); ready();
            assertEquals(copyUri, new PublicationDraftStore(context).load(id).getString("videoUri"));
            String unchanged = new PublicationDraftStore(context).load(id).getString("description");
            press(R.string.draft_video); downloads(); clickNode("altered-" + sourceName); ready();
            screenshot("02-different-bytes-explicit-confirmation");
            clickNode(activity.getString(R.string.draft_rebind)); ready();
            if (read(() -> field(activity, "draft")) != null
                    && id.equals(current().getString("id"))) {
                PublicationDraftMedia.Fingerprint changed;
                try (InputStream input = context.getContentResolver().openInputStream(altered)) {
                    changed = PublicationDraftMedia.hash(input);
                }
                for (JSONObject candidate : new PublicationDraftStore(context).list())
                    if (changed.sha.equals(candidate.getString("mediaSha256"))) { chooseIfShown(candidate); break; }
                ready();
            }
            String differentId = current().getString("id");
            assertFalse(id.equals(differentId));
            assertEquals("", current().getString("description"));
            assertEquals(unchanged, new PublicationDraftStore(context).load(id).getString("description"));
            assertEquals(copyUri, new PublicationDraftStore(context).load(id).getString("videoUri"));
            screenshot("02-different-bytes-fresh-id");
            press(R.string.draft_list); ready();
            press(R.string.draft_open_video); downloads(); clickNode(copyName); ready();
            chooseIfShown(new PublicationDraftStore(context).load(id)); ready();
            assertEquals(id, current().getString("id"));
            press(R.string.draft_list); ready();
            ui(() -> findButton(activity.getWindow().getDecorView(), "旅行🎬 native draft\n" + copyName).performClick());
            ready();
            press(R.string.draft_copy_all);
            assertEquals(PublicationDraftMedia.combined(current()), read(() -> ((ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE))
                    .getPrimaryClip().getItemAt(0).getText().toString()));
            press(R.string.draft_export);
            awaitNode("Save");
            for (AccessibilityNodeInfo field : instrumentation.getUiAutomation().getRootInActiveWindow()
                    .findAccessibilityNodeInfosByViewId("android:id/title")) {
                if ("android.widget.EditText".contentEquals(field.getClassName())) {
                    Bundle arguments = new Bundle();
                    arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, manifestName);
                    assertTrue(field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments));
                }
            }
            screenshot("03-saf-create-manifest");
            clickNode("Save");
            ready();
            await(() -> readMessage().contains(activity.getString(R.string.draft_exported)));
            press(R.string.draft_list); ready();
            press(R.string.draft_import);
            downloads();
            clickNode(manifestName);
            ready();
            JSONObject imported = current();
            assertFalse(id.equals(imported.getString("id")));
            assertEquals("", imported.getString("videoUri"));
            assertEquals("", imported.getString("coverUri"));
            assertEquals("creative-commons-attribution", imported.getString("publicationLicense"));
            assertEquals(draft.getString("musicCredits"), imported.getString("musicCredits"));
            press(R.string.draft_video);
            // The exact Movies export is selected through the real system picker.
            roots();
            clickNode("Videos");
            try { clickNode("Movies"); } catch (AssertionError ignored) { /* Some providers flatten Videos. */ }
            clickNode(exported.name);
            ready();
            screenshot("03-multiple-manifests-explicit-selection");
            chooseDraft(imported); ready();
            assertEquals(actual.sha, current().getString("mediaSha256"));
            assertFalse(current().getString("videoUri").isEmpty());
            Intent share = PublicationDraftMedia.share(context, current(), false);
            assertEquals("video/mp4", share.getType());
            assertEquals(Uri.parse(current().getString("videoUri")), share.getParcelableExtra(Intent.EXTRA_STREAM));
            assertEquals(share.getParcelableExtra(Intent.EXTRA_STREAM), share.getClipData().getItemAt(0).getUri());
            assertTrue((share.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            assertTrue(share.getStringExtra(Intent.EXTRA_TEXT).contains(current().getString("musicCredits")));
            assertTrue(share.getStringExtra(Intent.EXTRA_TEXT).contains("Publication license intention"));
            ui(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText(""));
            press(R.string.draft_save);
            assertTrue(read(this::readMessage).contains("Title is required"));
            ui(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText("旅行🎬 native draft"));
            press(R.string.draft_save); ready();
            press(R.string.draft_share_video);
            SystemClock.sleep(1500);
            screenshot("04-real-share-chooser");
            instrumentation.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);
            ready();
            assertEquals("shared", new PublicationDraftStore(context).load(imported.getString("id")).getString("status"));
            try (InputStream input = new FileInputStream(fixture)) { assertEquals(original.sha, PublicationDraftMedia.hash(input).sha); }
            try (InputStream input = context.getContentResolver().openInputStream(exported.uri)) { assertEquals(actual.sha, PublicationDraftMedia.hash(input).sha); }
            try (FileOutputStream output = new FileOutputStream(new File(evidence, "receipt.json"))) {
                output.write(new JSONObject().put("sourceRevision", BuildConfig.SOURCE_REVISION)
                        .put("videoUri", exported.uri).put("mediaName", exported.name)
                        .put("videoSha256", actual.sha).put("bytes", actual.bytes)
                         .put("originalSha256", original.sha).put("draftId", id)
                          .put("renamedVideoUri", copyUri).put("renamedDraftId", id)
                          .put("differentBytesDraftId", differentId)
                          .put("retainedCoverUri", savedCover)
                         .put("publicationLicense", current().getString("publicationLicense"))
                        .put("importedId", imported.getString("id")).put("result", "PASS").toString().getBytes("UTF-8"));
            }
        } catch (Throwable error) {
            screenshot("failure");
            throw error;
        } finally {
            if (activity != null) ui(() -> activity.finish());
            if (editor != null) ui(() -> editor.finish());
            context.getContentResolver().delete(source, null, null);
            if (renamed != null) context.getContentResolver().delete(renamed, null, null);
            if (altered != null) context.getContentResolver().delete(altered, null, null);
            if (cover != null) context.getContentResolver().delete(cover, null, null);
        }
    }

    private void chooseDraft(JSONObject value) throws Exception {
        clickNode(value.getString("title") + "\n" + value.getString("id") + "\n" + value.getString("musicCredits"));
    }

    private void chooseIfShown(JSONObject value) throws Exception {
        String label = value.getString("title") + "\n" + value.getString("id") + "\n" + value.getString("musicCredits");
        if (node(instrumentation.getUiAutomation().getRootInActiveWindow(), label) != null) clickNode(label);
    }

    private Uri copyVideo(Uri source, String name, boolean changed) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri copy = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        try (InputStream input = context.getContentResolver().openInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(copy)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            if (changed) output.write(new byte[]{0, 0, 0, 8, 'f', 'r', 'e', 'e'});
        }
        ContentValues finalized = new ContentValues(); finalized.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(copy, finalized, null, null);
        return copy;
    }

    private void ready() throws Exception {
        await(() -> activity != null && !(boolean) field(activity, "busy"));
        SystemClock.sleep(100);
    }
    private JSONObject current() throws Exception { return read(() -> new JSONObject(((JSONObject) field(activity, "draft")).toString())); }
    private String readMessage() throws Exception { return ((TextView) field(activity, "message")).getText().toString(); }
    private void press(int string) throws Exception {
        ui(() -> {
            Button button = findButton(activity.getWindow().getDecorView(), activity.getString(string));
            assertNotNull("button " + activity.getString(string), button); assertTrue(button.isEnabled()); button.performClick();
        });
    }
    private Button findButton(View view, String text) {
        if (view instanceof Button && text.equals(((Button) view).getText().toString())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = findButton(((ViewGroup) view).getChildAt(i), text); if (found != null) return found;
        }
        return null;
    }
    private Object field(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private void ui(Runnable action) { instrumentation.runOnMainSync(action); SystemClock.sleep(100); }
    private <T> T read(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>(); AtomicReference<Exception> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> { try { value.set(action.call()); } catch (Exception e) { error.set(e); } });
        if (error.get() != null) throw error.get(); return value.get();
    }
    private void await(Callable<Boolean> condition) throws Exception { waitFor(condition, 30000); }
    private void awaitLong(Callable<Boolean> condition) throws Exception { waitFor(condition, 240000); }
    private void waitFor(Callable<Boolean> condition, long millis) throws Exception {
        long end = SystemClock.uptimeMillis() + millis;
        while (SystemClock.uptimeMillis() < end) { if (read(condition)) return; SystemClock.sleep(100); }
        fail("Timed out waiting for UI; " + (activity == null ? "" : read(this::readMessage)));
    }
    private AccessibilityNodeInfo node(AccessibilityNodeInfo root, String text) {
        if (root == null) return null;
        if (text.equalsIgnoreCase(String.valueOf(root.getText())) || text.equalsIgnoreCase(String.valueOf(root.getContentDescription()))) return root;
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            AccessibilityNodeInfo found = node(root.getChild(i), text); if (found != null) return found;
        }
        return null;
    }
    private void awaitNode(String text) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            if (node(instrumentation.getUiAutomation().getRootInActiveWindow(), text) != null) return;
            SystemClock.sleep(200);
        }
        fail("Missing picker node: " + text);
    }
    private void clickNode(String text) throws Exception {
        awaitNode(text);
        AccessibilityNodeInfo found = node(instrumentation.getUiAutomation().getRootInActiveWindow(), text);
        assertNotNull(found);
        found.refresh();
        {
            AccessibilityNodeInfo target = found;
            for (int i = 0; target != null && i < 8; i++, target = target.getParent()) {
                android.util.Log.i("PublicationDraftUi", "file node " + target.getClassName() + " " + target.getActions());
                if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    SystemClock.sleep(1500);
                    return;
                }
            }
        }
        android.graphics.Rect bounds = new android.graphics.Rect();
        found.getBoundsInScreen(bounds);
        android.util.Log.i("PublicationDraftUi", "Tap " + text + " at " + bounds);
        long now = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN,
                bounds.centerX(), bounds.centerY(), 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP,
                bounds.centerX(), bounds.centerY(), 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(down, true));
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(up, true));
        } finally { down.recycle(); up.recycle(); }
        SystemClock.sleep(1500);
    }
    private void roots() throws Exception { clickNode("Show roots"); }
    private void downloads() throws Exception { roots(); clickNode("Downloads"); }
    private void screenshot(String name) throws Exception {
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name + ".png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        } finally { bitmap.recycle(); }
        StringBuilder nodes = new StringBuilder();
        dump(instrumentation.getUiAutomation().getRootInActiveWindow(), nodes, 0);
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name + ".hierarchy.txt"))) {
            output.write(nodes.toString().getBytes("UTF-8"));
        }
    }
    private void dump(AccessibilityNodeInfo node, StringBuilder text, int depth) {
        if (node == null || depth > 30) return;
        text.append(depth).append(' ').append(node.getClassName()).append(' ').append(node.getViewIdResourceName())
                .append(' ').append(node.getText()).append(' ').append(node.getContentDescription()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) dump(node.getChild(i), text, depth + 1);
    }
}
