package com.simple.videoeditor;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.test.InstrumentationTestCase;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import com.google.android.material.tabs.TabLayout;
import com.simple.videoeditor.oracle.OracleGeneratedContract;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

@androidx.media3.common.util.UnstableApi
public final class SelectedFramePreviewTest extends InstrumentationTestCase {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private File evidence, source;
    private Uri pickerInput;
    private final java.util.List<Uri> extraPickerInputs = new java.util.ArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        instrumentation = getInstrumentation();
        context = instrumentation.getTargetContext();
        evidence = new File(context.getFilesDir(), "public-output-preview-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        source = new OracleVerifier(context, OracleGeneratedContract.create()).prepareFixture();
    }

    @Override protected void tearDown() throws Exception {
        if (activity != null) instrumentation.runOnMainSync(activity::finish);
        if (pickerInput != null) context.getContentResolver().delete(pickerInput, null, null);
        for (Uri uri : extraPickerInputs) context.getContentResolver().delete(uri, null, null);
        super.tearDown();
    }

    public void testIndependentTitleAppearanceNativeControlsAndExports() throws Exception {
        launch();
        choose(source, "video/mp4", R.id.btnSelectVideo);
        tap(R.id.cbEnableTrim);
        edit(R.id.etTrimStart, "2");
        tap(R.id.cbEnableIntro);
        borderOption(R.id.spinnerTitleLayout, 0);
        titleEdit(R.id.etIntroText, "OI\nOI");
        titleEdit(R.id.etTitleSize, "48");
        titleEdit(R.id.etTitleDurationMs, "2400");
        titleColor(R.id.btnTitleTextColor, 0);
        titleColor(R.id.btnTitleBackgroundColor, 5);
        if (((android.widget.CheckBox) activity.findViewById(R.id.cbTitleGradient)).isChecked())
            tap(R.id.cbTitleGradient);
        for (int animation : new int[]{1, 2, 3, 4, 5, 0, 6}) {
            borderOption(R.id.spinnerTitleStyle, animation);
            EditConfig.IntroTitle title = snapshot().introTitle;
            assertEquals(0xFFFF0000, title.textColor);
            assertEquals(0xFF000000, title.backgroundColor);
            assertEquals(48, title.textSizeSp);
            assertEquals(2400, title.durationMs);
            assertEquals("canvas", title.layout);
        }
        // A background shortcut must no longer overwrite a separately selected text color.
        borderOption(R.id.spinnerTitlePalette, 3);
        assertEquals(0xFFFF0000, snapshot().introTitle.textColor);
        titleColor(R.id.btnTitleBackgroundColor, 5);
        reveal(R.id.titleCanvasPreview);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        Rect previewBounds = new Rect();
        activity.findViewById(R.id.titleCanvasPreview).getGlobalVisibleRect(previewBounds);
        Bitmap visible = Bitmap.createBitmap(screenshot, previewBounds.left, previewBounds.top,
                previewBounds.width(), previewBounds.height());
        assertTrue("actual on-screen red title, not just state", TitleAppearanceTest.red(visible) > 200);
        visible.recycle(); screenshot.recycle();
        capture("independent-title-small-preview");
        PublishedVideo previous = currentOutput();
        tap(R.id.btnProcess);
        PublishedVideo smallVideo = awaitNewOutput(previous);
        Bitmap small = frame(smallVideo.privateFile, 1200000);
        assertTrue("real exported red glyphs", TitleAppearanceTest.red(small) > 100);
        titleEdit(R.id.etTitleSize, "96");
        tap(R.id.btnProcess);
        PublishedVideo largeVideo = awaitNewOutput(smallVideo);
        Bitmap large = frame(largeVideo.privateFile, 1200000);
        assertTrue("real exported size increase", TitleAppearanceTest.red(large) > TitleAppearanceTest.red(small) * 2);
        Bitmap early = frame(largeVideo.privateFile, 100000);
        Bitmap late = frame(largeVideo.privateFile, 2300000);
        assertTrue("dissolve entrance", TitleAppearanceTest.red(early) < TitleAppearanceTest.red(large) / 3);
        assertTrue("dissolve exit", TitleAppearanceTest.red(late) < TitleAppearanceTest.red(large) / 3);
        Bitmap blank = Bitmap.createBitmap(large.getWidth(), large.getHeight(), Bitmap.Config.ARGB_8888);
        assertEquals("same content check rejects omitted/white glyphs", 0, TitleAppearanceTest.red(blank));
        blank.eraseColor(Color.WHITE);
        assertEquals(0, TitleAppearanceTest.red(blank));
        small.recycle(); large.recycle(); early.recycle(); late.recycle(); blank.recycle();

        tap(R.id.cbTitleSourceFrame);
        titleEdit(R.id.etTitleSourceSeconds, "1");
        waitTitleBackground(1000);
        assertEquals(2000L, snapshot().startMs);
        assertEquals(1000L, snapshot().titleBackground.timeMs);
        borderOption(R.id.spinnerTitleStyle, 2);
        borderOption(R.id.spinnerTitleStyle, 6);
        assertTrue(snapshot().introTitle.sourceFrameBackground);
        assertEquals(96, snapshot().introTitle.textSizeSp);
        String name = "independent-title-" + UUID.randomUUID();
        WholeEditPresets store = new WholeEditPresets(context);
        try {
            titleEdit(R.id.etWholePresetName, name);
            tap(R.id.btnSaveWholePreset);
            waitLoading();
            JSONObject saved = store.load(name).getJSONObject("title");
            assertEquals(2, saved.getInt("schemaVersion"));
            titleEdit(R.id.etTitleSize, "24");
            titleColor(R.id.btnTitleTextColor, 2);
            tap(R.id.cbTitleSourceFrame);
            selectName(R.id.spinnerWholePreset, name);
            tap(R.id.btnApplyWholePreset);
            waitLoading(); waitTitleBackground(1000);
            EditConfig config = snapshot();
            assertEquals(96, config.introTitle.textSizeSp);
            assertEquals(0xFFFF0000, config.introTitle.textColor);
            assertEquals("dissolve", config.introTitle.animation);
            assertEquals(2400, config.introTitle.durationMs);
            Bitmap expected = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
            Bitmap background = config.titleBackground.decode(4096);
            TitleRenderer.draw(new android.graphics.Canvas(expected), 320, 240, config.introTitle, 1200000, background);
            tap(R.id.btnProcess);
            PublishedVideo frameVideo = awaitNewOutput(largeVideo);
            Bitmap actual = frame(frameVideo.privateFile, 1200000);
            assertTrue("styled original frame export matches title preview", mae(actual, expected) < 7);
            assertEquals("duration includes custom title and trimmed main", 4400L, duration(frameVideo.privateFile), 120L);
            saveBitmap("independent-title-frame-export.png", actual);
            saveBitmap("independent-title-frame-preview.png", expected);
            reveal(R.id.titleCanvasPreview); capture("independent-title-frame-screen");
            actual.recycle(); expected.recycle(); background.recycle();
            // Use the real title preset dialog as well as whole-edit storage.
            tap(R.id.btnSaveTemplate);
            AccessibilityNodeInfo input = findEditable(activeRoot());
            assertNotNull(input);
            setNodeText(input, name);
            clickNode(activeRoot().findAccessibilityNodeInfosByViewId("android:id/button1").get(0));
            instrumentation.waitForIdleSync();
            titleEdit(R.id.etTitleSize, "32");
            titleTemplateOption(0);
            android.widget.Spinner templates = activity.findViewById(R.id.spinnerIntroTemplate);
            for (int i = 0; i < templates.getCount(); i++) if (name.equals(templates.getItemAtPosition(i)))
                titleTemplateOption(i);
            waitTitleBackground(1000);
            assertEquals(96, snapshot().introTitle.textSizeSp);
            assertEquals(0xFFFF0000, snapshot().introTitle.textColor);
            titleEdit(R.id.etTitleSize, "0");
            assertNotNull(((EditText) activity.findViewById(R.id.etTitleSize)).getError());
            try { snapshot(); fail("Invalid size must block export"); }
            catch (java.lang.reflect.InvocationTargetException expectedError) {
                assertTrue(expectedError.getCause() instanceof IllegalArgumentException);
            }
            titleEdit(R.id.etTitleSize, "96");
            JSONObject result = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                    .put("status", "PASS").put("exports", 3).put("title", saved)
                    .put("realColorSizeAnimationControls", true).put("renderedScreenAndExportChecked", true)
                    .put("omittedAndWrongColorRejected", true).put("absoluteFrameOutsideTrim", true);
            try (FileOutputStream output = new FileOutputStream(new File(evidence, "independent-title-result.json"))) {
                output.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            store.delete(name);
            new IntroTemplateManager(context).deleteTemplate(name);
        }
    }

    void directSafNoCopyConsentCancelRetryAndRestoredGrant() throws Exception {
        launch();
        long before = DirectVideoSourceTest.cacheBytes(context.getCacheDir());
        chooseDirectDocument("seekable");
        waitLoading();
        waitFrame(0, 0, 320, 240, false, 15000);
        EditConfig direct = snapshot();
        assertEquals(DirectVideoSourceTest.uri("seekable"), direct.input);
        assertNull(direct.inputSource.ownedFile);
        assertTrue("Real SAF result must persist its actual grant", direct.inputSource.persistentRead);
        assertEquals("Selection and preview must not stage an input", before, DirectVideoSourceTest.cacheBytes(context.getCacheDir()));
        instrumentation.runOnMainSync(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        SystemClock.sleep(700);
        instrumentation.runOnMainSync(() -> activity.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT));
        SystemClock.sleep(700);
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(MainActivity.class.getName(), null, false);
        instrumentation.runOnMainSync(activity::recreate);
        activity = (MainActivity) instrumentation.waitForMonitorWithTimeout(monitor, 15000);
        instrumentation.removeMonitor(monitor);
        assertNotNull("Activity recreated", activity);
        waitLoading();
        assertEquals(direct.input, snapshot().input);
        assertTrue(snapshot().inputSource.persistentRead);
        snapshot().inputSource.checkCurrent(context);
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            parcel.writeBundle(snapshot().inputSource.save());
            try (FileOutputStream stream = new FileOutputStream(new File(evidence, "direct-source-state.bin"))) {
                stream.write(parcel.marshall());
            }
        } finally { parcel.recycle(); }
        chooseDirectDocument("pipe");
        waitLoading();
        assertEquals("Pending copy must preserve previous direct selection", direct.input, snapshot().input);
        assertEquals(before, DirectVideoSourceTest.cacheBytes(context.getCacheDir()));
        assertTrue("Reason visible", activeRoot().findAccessibilityNodeInfosByText(
                context.getString(R.string.editor_source_not_seekable)).size() > 0);
        capture("direct-copy-consent");
        clickNode(activeRoot().findAccessibilityNodeInfosByViewId("android:id/button2").get(0));
        assertEquals(direct.input, snapshot().input);
        chooseDirectDocument("slowpipe");
        waitLoading();
        clickNode(activeRoot().findAccessibilityNodeInfosByViewId("android:id/button1").get(0));
        SystemClock.sleep(300);
        tap(R.id.btnCancelExport);
        waitLoading();
        long cleanup = SystemClock.uptimeMillis() + 10000;
        while (DirectVideoSourceTest.cacheBytes(context.getCacheDir()) != before && SystemClock.uptimeMillis() < cleanup)
            SystemClock.sleep(100);
        assertEquals("Cancelled copy partial removed", before, DirectVideoSourceTest.cacheBytes(context.getCacheDir()));
        assertEquals(direct.input, snapshot().input);
        chooseDirectDocument("pipe");
        waitLoading();
        clickNode(activeRoot().findAccessibilityNodeInfosByViewId("android:id/button1").get(0));
        waitLoading();
        VideoSource staged = snapshot().inputSource;
        assertNotNull("Only explicit consent creates a copy", staged.ownedFile);
        assertTrue(staged.ownedFile.isFile());
        PublishedVideo previous = currentOutput();
        tap(R.id.btnProcess);
        PublishedVideo result = awaitNewOutput(previous);
        Bitmap exported = frame(result.privateFile, 1000000), expected = frame(source, 1000000);
        assertTrue("Staged retry exports actual source pixels", mae(exported, expected) < 12);
        exported.recycle(); expected.recycle();
        chooseDirectDocument("seekable");
        waitLoading();
        assertFalse("Replacing a staged source cleans only its owned copy", staged.ownedFile.exists());
        context.getContentResolver().releasePersistableUriPermission(direct.input, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        tap(R.id.btnProcess);
        long failed = SystemClock.uptimeMillis() + 15000;
        while ((Boolean) field("exporting") && SystemClock.uptimeMillis() < failed) SystemClock.sleep(100);
        assertFalse((Boolean) field("exporting"));
        assertFalse(((TextView) activity.findViewById(R.id.tvErrorDetails)).getText().toString().isEmpty());
        assertNull("Permission loss never stages", snapshot().inputSource.ownedFile);
        chooseDirectDocument("seekable");
        waitLoading();
        assertTrue(snapshot().inputSource.persistentRead);
        snapshot().inputSource.checkCurrent(context);
        assertEquals(before, DirectVideoSourceTest.cacheBytes(context.getCacheDir()));
    }

    void directSafGrantSurvivesFreshInstrumentationProcess() throws Exception {
        // Run after the stock SAF test, in a separate instrumentation invocation.
        File state = new File(evidence, "direct-source-state.bin");
        assertTrue("Stock SAF prerequisite must be run, not mocked", state.isFile());
        byte[] bytes = new byte[(int) state.length()];
        try (java.io.DataInputStream input = new java.io.DataInputStream(new FileInputStream(state))) { input.readFully(bytes); }
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0);
            VideoSource restored = VideoSource.restore(context, parcel.readBundle(VideoSource.class.getClassLoader()));
            assertTrue(restored.persistentRead);
            restored.checkCurrent(context);
            Bitmap preview = SelectedFramePreview.render(context,
                    new EditConfig.Builder(restored.uri, 4000).inputSource(restored).sourceSize(320, 240).build());
            assertNotNull(preview); preview.recycle();
            assertNull(restored.ownedFile);
        } finally { parcel.recycle(); }
    }

    private void chooseDirectDocument(String id) throws Exception {
        String name = "Direct URI " + id + ".mp4";
        tap(R.id.btnSelectVideo);
        waitPicker(name, false);
        AccessibilityNodeInfo document = findDocument(activeRoot(), name);
        if (document == null) {
            AccessibilityNodeInfo navigation = navigationButton(activeRoot());
            assertNotNull("DocumentsUI navigation drawer", navigation);
            clickNode(navigation);
            SystemClock.sleep(400);
            java.util.List<AccessibilityNodeInfo> roots = activeRoot().findAccessibilityNodeInfosByText("Direct URI fixtures");
            assertFalse("Deterministic DocumentsProvider root visible in stock SAF", roots.isEmpty());
            clickNode(roots.get(0));
        }
        AccessibilityNodeInfo list = findPickerControl("option_menu_list");
        if (list != null) { clickNode(list); SystemClock.sleep(300); }
        document = waitPicker(name, true);
        clickNode(document);
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (!context.getPackageName().contentEquals(activeRoot().getPackageName())
                && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100);
        assertEquals(context.getPackageName(), activeRoot().getPackageName().toString());
    }

    private AccessibilityNodeInfo navigationButton(AccessibilityNodeInfo node) {
        CharSequence description = node.getContentDescription();
        if (description != null && (description.toString().equals("Show roots")
                || description.toString().contains("根目录") || description.toString().contains("导航")))
            return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = navigationButton(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    private long duration(File file) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            return Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
        } finally { retriever.release(); }
    }

    private void titleColor(int button, int palette) throws Exception {
        tap(button);
        String name = activity.getResources().getStringArray(R.array.editor_border_colors)[palette];
        AccessibilityNodeInfo option = null;
        for (AccessibilityNodeInfo node : activeRoot().findAccessibilityNodeInfosByText(name))
            if (node.getText() != null && name.contentEquals(node.getText()) && node.isClickable()) option = node;
        assertNotNull("real color palette button", option);
        clickNode(option);
        clickNode(activeRoot().findAccessibilityNodeInfosByViewId("android:id/button1").get(0));
        instrumentation.waitForIdleSync();
    }

    private void titleTemplateOption(int position) {
        android.widget.Spinner spinner = activity.findViewById(R.id.spinnerIntroTemplate);
        boolean touchMode = spinner.isInTouchMode();
        try {
            tap(R.id.spinnerIntroTemplate);
            for (int i = 0; i < spinner.getCount(); i++) key(KeyEvent.KEYCODE_DPAD_UP);
            for (int i = 0; i < position; i++) key(KeyEvent.KEYCODE_DPAD_DOWN);
            key(KeyEvent.KEYCODE_ENTER);
        } finally {
            // DPAD changes device-wide input mode; root focus highlighting otherwise tints later screenshots.
            instrumentation.setInTouchMode(touchMode);
        }
        instrumentation.waitForIdleSync();
        assertEquals("Preset navigation restores the original input mode", touchMode, spinner.isInTouchMode());
        assertEquals("native selection of off-screen title preset", position, spinner.getSelectedItemPosition());
    }

    private void titleEdit(int id, String value) {
        reveal(id);
        AccessibilityNodeInfo node = activeRoot().findAccessibilityNodeInfosByViewId(
                context.getResources().getResourceName(id)).get(0);
        setNodeText(node, value);
        instrumentation.waitForIdleSync();
    }

    private void setNodeText(AccessibilityNodeInfo node, String value) {
        android.os.Bundle args = new android.os.Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        assertTrue("native text edit action", node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args));
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isEditable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findEditable(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }

    public void testSelectedTimeCropClockwiseAndBoundedPixels() throws Exception {
        Bitmap selected = frame(source, 1000000);
        Bitmap zero = frame(source, 0);
        EditConfig config = new EditConfig.Builder(Uri.fromFile(source), 4000)
                .sourceSize(320, 240).trim(1000, 3000).build();
        Bitmap preview = SelectedFramePreview.render(config);
        saveBitmap("identity-reference.png", selected);
        saveBitmap("identity-preview.png", preview);
        assertTrue("must decode selected frame, not zero", mae(preview, zero) > 1);
        assertTrue("identity selected frame MAE=" + mae(preview, selected), mae(preview, selected) < 1);
        Bitmap cropped = SelectedFramePreview.render(new EditConfig.Builder(Uri.fromFile(source), 4000)
                .sourceSize(320, 240).trim(1000, 3000)
                .crop(.125f, .125f, .875f, .875f).rotation(90).build());
        Bitmap expected = clockwiseCrop(selected);
        assertEquals(180, cropped.getWidth());
        assertEquals(240, cropped.getHeight());
        assertTrue("independent integer crop/clockwise mapping", mae(cropped, expected) < 2);
        Bitmap large = SelectedFramePreview.render(new EditConfig.Builder(Uri.fromFile(source), 4000)
                .sourceSize(320, 240).trim(1000, 3000).rotation(37).outputHeight(1080).build());
        assertTrue(large.getWidth() <= 640 && large.getHeight() <= 640);
        assertTrue(large.getAllocationByteCount() <= 640 * 640 * 4);
        selected.recycle(); zero.recycle(); preview.recycle(); cropped.recycle();
        expected.recycle(); large.recycle();
    }

    public void testTitleOriginalFrameRealControlsSavePresetAndStaleRequests() throws Exception {
        launch();
        choose(source, "video/mp4", R.id.btnSelectVideo);
        waitLoading();
        assertFalse(activity.findViewById(R.id.etTitleSourceSeconds).isEnabled());
        tap(R.id.cbEnableIntro);
        tap(R.id.cbTitleSourceFrame);
        edit(R.id.etTitleSourceSeconds, "1");
        waitTitleBackground(1000);
        instrumentation.runOnMainSync(() -> {
            activity.findViewById(R.id.btnExtractTitleFrame).performClick();
            assertFalse("same-time refresh must not expose/delete a snapshot already handed to export",
                    activity.findViewById(R.id.btnSaveTitleFrame).isEnabled());
        });
        waitTitleBackground(1000);
        tap(R.id.cbEnableTrim);
        edit(R.id.etTrimStart, "2");
        for (int id : new int[]{R.id.etCropLeft, R.id.etCropTop, R.id.etCropRight, R.id.etCropBottom}) edit(id, "10");
        waitTitleBackground(1000);
        Bitmap original = frame(source, 1000000);
        Bitmap expected = Bitmap.createBitmap(original, 32, 24, 256, 192);
        Bitmap background = titleBackgroundBitmap();
        assertTrue("title uses excluded original time, not trim-relative", mae(background, expected) < 2);
        reveal(R.id.ivTitleSourceFrame);
        capture("title-original-1s-trim-2s-four-ten");
        for (String invalid : new String[]{"", ".", "-1", "999", "1.0001"}) {
            edit(R.id.etTitleSourceSeconds, invalid);
            assertFalse(activity.findViewById(R.id.btnSaveTitleFrame).isEnabled());
            String validation = ((TextView) activity.findViewById(R.id.tvTitleSourceStatus)).getText().toString();
            assertTrue(invalid + ": " + validation, validation.contains("0 ≤ t"));
        }
        edit(R.id.etTitleSourceSeconds, "1");
        waitTitleBackground(1000);
        // An actual completed decode is queued to main; change selection before it can publish.
        instrumentation.runOnMainSync(() -> {
            try {
                EditText time = activity.findViewById(R.id.etTitleSourceSeconds);
                time.setText("2");
                Object controls = field("titleBackgroundControls");
                Field pending = controls.getClass().getDeclaredField("pending"); pending.setAccessible(true);
                ((Runnable) pending.get(controls)).run();
                Field task = controls.getClass().getDeclaredField("task"); task.setAccessible(true);
                ((Future<?>) task.get(controls)).get(10, java.util.concurrent.TimeUnit.SECONDS);
                time.setText("1");
            } catch (Exception e) { throw new AssertionError(e); }
        });
        waitTitleBackground(1000);
        assertTrue("late frame cannot overwrite newer source-time selection", mae(titleBackgroundBitmap(), expected) < 2);
        instrumentation.runOnMainSync(() -> {
            try {
                EditText left = activity.findViewById(R.id.etCropLeft);
                left.setText("20");
                Object controls = field("titleBackgroundControls");
                Field pending = controls.getClass().getDeclaredField("pending"); pending.setAccessible(true);
                ((Runnable) pending.get(controls)).run();
                Field task = controls.getClass().getDeclaredField("task"); task.setAccessible(true);
                ((Future<?>) task.get(controls)).get(10, java.util.concurrent.TimeUnit.SECONDS);
                left.setText("10");
                assertFalse(activity.findViewById(R.id.btnSaveTitleFrame).isEnabled());
            } catch (Exception e) { throw new AssertionError(e); }
        });
        waitTitleBackground(1000);
        assertTrue("late crop cannot replace current geometry", mae(titleBackgroundBitmap(), expected) < 2);
        tap(R.id.btnSaveTitleFrame);
        long deadline = SystemClock.uptimeMillis() + 15000;
        String saved;
        do {
            SystemClock.sleep(100); instrumentation.waitForIdleSync();
            saved = ((TextView) activity.findViewById(R.id.tvTitleSourceStatus)).getText().toString();
        } while (!saved.contains("content://") && SystemClock.uptimeMillis() < deadline);
        assertTrue("must report actual image URI", saved.contains("content://"));
        Uri imageUri = null;
        for (String line : saved.split("\n")) if (line.startsWith("content://")) imageUri = Uri.parse(line);
        assertNotNull(imageUri);
        try {
            assertEquals("image/png", context.getContentResolver().getType(imageUri));
            try (InputStream in = context.getContentResolver().openInputStream(imageUri)) {
                Bitmap savedImage = android.graphics.BitmapFactory.decodeStream(in);
                assertTrue(savedImage.sameAs(titleBackgroundBitmap())); savedImage.recycle();
            }
        } finally { context.getContentResolver().delete(imageUri, null, null); }
        String presetName = "title-background-" + UUID.randomUUID();
        File secondSource = repeatVideo(2, 4000000), shortSource = repeatVideo(1, 500000);
        try {
            edit(R.id.etWholePresetName, presetName); tap(R.id.btnSaveWholePreset); waitLoading();
            JSONObject recipe = new WholeEditPresets(context).load(presetName);
            assertEquals(6, recipe.getInt("version"));
            assertEquals("1", recipe.getJSONObject("title").getString("sourceFrameSeconds"));
            assertFalse(recipe.getJSONObject("title").toString().contains(".png"));
            EditConfig firstSource = snapshot();
            choose(secondSource, "video/mp4", R.id.btnSelectVideo); waitLoading();
            tap(R.id.btnApplyWholePreset); waitLoading();
            waitTitleBackground(1000);
            EditConfig second = snapshot();
            assertFalse(firstSource.input.equals(second.input));
            assertTrue(second.sourceDurationMs > firstSource.sourceDurationMs);
            assertFalse("second source must not reuse previous PNG", firstSource.titleBackground.file.equals(
                    second.titleBackground.file));
            assertTrue("new input re-extracts same absolute seconds", mae(titleBackgroundBitmap(), expected) < 2);
            PublishedVideo previous = currentOutput();
            tap(R.id.btnProcess);
            PublishedVideo video = awaitNewOutput(previous);
            assertNotNull(video.uri);
            reveal(R.id.btnOutputLocation); capture("title-background-public-movies");
            assertTrue("diagnostics expose unlimited policy",
                    ((TextView) activity.findViewById(R.id.tvDebugInfo)).getText().toString().contains("No elapsed/stall"));
            tap(R.id.cbEnableTrim);
            tap(R.id.btnSaveWholePreset); waitLoading();
            choose(shortSource, "video/mp4", R.id.btnSelectVideo); waitLoading();
            edit(R.id.etTitleSourceSeconds, "0.2"); waitTitleBackground(200);
            tap(R.id.btnApplyWholePreset); waitLoading();
            assertEquals("rejected absolute time must not mutate controls", "0.2",
                    ((EditText) activity.findViewById(R.id.etTitleSourceSeconds)).getText().toString());
            reveal(R.id.tvErrorDetails);
            assertTrue(((TextView) activity.findViewById(R.id.tvErrorDetails)).getText().toString()
                    .startsWith(activity.getString(R.string.editor_preset_apply_failed)));
            reveal(R.id.tvRawError);
            assertTrue(((TextView) activity.findViewById(R.id.tvRawError)).getText().toString()
                    .contains("Original seconds"));
        } finally {
            new WholeEditPresets(context).delete(presetName); secondSource.delete(); shortSource.delete();
            original.recycle(); expected.recycle();
        }
    }

    public void testTitlePresetTimestampOnlyPublishesAndExports3Seconds() throws Exception {
        titlePresetTimestampOnly(3000);
    }

    public void testTitlePresetTimestampOnlyPublishesAndExports5Seconds() throws Exception {
        titlePresetTimestampOnly(5000);
    }

    private void titlePresetTimestampOnly(int duration) throws Exception {
        launch();
        choose(source, "video/mp4", R.id.btnSelectVideo); waitLoading();
        tap(R.id.cbEnableIntro);
        select(R.id.spinnerTitleStyle, 4);
        select(R.id.spinnerTitleDuration, duration == 3000 ? 1 : 2);
        edit(R.id.etIntroText, "");
        tap(R.id.cbTitleSourceFrame);
        tap(R.id.cbEnableTrim); edit(R.id.etTrimStart, "3");
        for (int id : new int[]{R.id.etCropLeft, R.id.etCropTop, R.id.etCropRight, R.id.etCropBottom}) edit(id, "10");
        edit(R.id.etTitleSourceSeconds, "2"); waitTitleBackground(2000);
        String name = "timestamp-only-" + UUID.randomUUID();
        try {
            edit(R.id.etWholePresetName, name); tap(R.id.btnSaveWholePreset); waitLoading();
            edit(R.id.etTitleSourceSeconds, "1"); waitTitleBackground(1000);
            tap(R.id.btnApplyWholePreset); waitLoading();
            waitTitleBackground(2000);
            EditConfig config = snapshot();
            assertEquals("background-only test must not render title text", "", config.introTitle.text);
            assertEquals("scale", config.introTitle.animation);
            assertEquals(2000L, config.titleBackground.timeMs);
            assertEquals(3000L, config.startMs);
            Bitmap original = frame(source, 2000000), old = frame(source, 1000000);
            Bitmap expected = Bitmap.createBitmap(original, 32, 24, 256, 192);
            Bitmap oldCrop = Bitmap.createBitmap(old, 32, 24, 256, 192);
            try {
                assertTrue("new absolute original frame outside main trim", mae(titleBackgroundBitmap(), expected) < 2);
                assertTrue("fixture distinguishes the old timestamp", mae(expected, oldCrop) > 1);
                tap(R.id.btnSaveTitleFrame);
                Uri saved = awaitTitleImage();
                try (InputStream input = context.getContentResolver().openInputStream(saved)) {
                    Bitmap png = android.graphics.BitmapFactory.decodeStream(input);
                    assertTrue("public PNG is the current preview", png.sameAs(titleBackgroundBitmap()));
                    assertTrue(mae(png, expected) < 2);
                    png.recycle();
                } finally { context.getContentResolver().delete(saved, null, null); }
                PublishedVideo previous = currentOutput();
                tap(R.id.btnProcess);
                PublishedVideo output = awaitNewOutput(previous);
                for (long us : new long[]{0, 1200000, duration * 1000L - 100000}) {
                    Bitmap actual = frame(output.privateFile, us);
                    try {
                        saveBitmap("title-preset-expected.png", expected);
                        saveBitmap("title-preset-actual-" + us + ".png", actual);
                        double currentError = mae(actual, expected), oldError = mae(actual, oldCrop);
                        assertTrue("Media3 must export the NEW preset background, not old pixels: "
                                + currentError + " vs " + oldError, currentError < 12 && currentError < oldError);
                    } finally { actual.recycle(); }
                }
                JSONObject record = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                        .put("policy", VideoEncodingSettings.compatibilityEnabled(context) ? "software" : "default")
                        .put("status", "PASS").put("requestedOriginalMs", 2000).put("trimStartMs", 3000)
                        .put("titleMs", duration).put("publicVideo", output.uri).put("publicPng", saved)
                        .put("videoSha256", hash(new FileInputStream(output.privateFile)));
                try (FileOutputStream out = new FileOutputStream(new File(evidence,
                        "title-preset-" + duration + "-" + record.getString("policy") + ".json"))) {
                    out.write(record.toString(2).getBytes("UTF-8"));
                }
                reveal(R.id.ivTitleSourceFrame); capture("title-preset-" + duration + "-new-absolute-frame");
            } finally { original.recycle(); old.recycle(); expected.recycle(); oldCrop.recycle(); }
        } finally { new WholeEditPresets(context).delete(name); }
    }

    public void testTitlePresetBackgroundEnableOnlyRestartsCapture() throws Exception {
        launch();
        choose(source, "video/mp4", R.id.btnSelectVideo); waitLoading();
        tap(R.id.cbEnableIntro); tap(R.id.cbTitleSourceFrame);
        edit(R.id.etTitleSourceSeconds, "1"); waitTitleBackground(1000);
        String name = "enable-only-" + UUID.randomUUID();
        try {
            edit(R.id.etWholePresetName, name); tap(R.id.btnSaveWholePreset); waitLoading();
            tap(R.id.cbTitleSourceFrame);
            assertFalse(activity.findViewById(R.id.btnSaveTitleFrame).isEnabled());
            tap(R.id.btnApplyWholePreset); waitLoading();
            waitTitleBackground(1000);
            assertEquals(1000L, snapshot().titleBackground.timeMs);
            tap(R.id.cbEnableIntro);
            assertFalse(activity.findViewById(R.id.btnSaveTitleFrame).isEnabled());
            tap(R.id.btnApplyWholePreset); waitLoading();
            waitTitleBackground(1000);
            WholeEditPresets store = new WholeEditPresets(context);
            JSONObject invalid = store.load(name);
            invalid.getJSONObject("title").put("sourceFrameSeconds", "999");
            invalid.put("overlay", "must not apply");
            store.save(name, invalid, null, null, null);
            EditConfig before = snapshot();
            tap(R.id.btnApplyWholePreset); waitLoading();
            assertEditUnchanged(before, snapshot());
            assertEquals("1", ((EditText) activity.findViewById(R.id.etTitleSourceSeconds)).getText().toString());
            reveal(R.id.tvErrorDetails);
            assertTrue(((TextView) activity.findViewById(R.id.tvErrorDetails)).getText().toString()
                    .startsWith(activity.getString(R.string.editor_preset_apply_failed)));
            reveal(R.id.tvRawError);
            assertTrue(((TextView) activity.findViewById(R.id.tvRawError)).getText().toString()
                    .contains("Original seconds"));
            edit(R.id.etTitleSourceSeconds, "999");
            assertFalse(activity.findViewById(R.id.btnSaveTitleFrame).isEnabled());
            assertTrue(((TextView) activity.findViewById(R.id.tvTitleSourceStatus)).getText().toString().contains("0 ≤ t"));
            tap(R.id.cbTitleSourceFrame);
            tap(R.id.btnSaveWholePreset); waitLoading();
            tap(R.id.cbTitleSourceFrame);
            edit(R.id.etTitleSourceSeconds, "1");
            tap(R.id.cbTitleSourceFrame);
            tap(R.id.btnApplyWholePreset); waitLoading();
            assertEquals("disabled recipe retains its inactive time", "999",
                    ((EditText) activity.findViewById(R.id.etTitleSourceSeconds)).getText().toString());
            assertNull(snapshot().titleBackground);
            tap(R.id.cbTitleSourceFrame);
            assertFalse(activity.findViewById(R.id.btnSaveTitleFrame).isEnabled());
            assertTrue(((TextView) activity.findViewById(R.id.tvTitleSourceStatus)).getText().toString().contains("0 ≤ t"));
        } finally { new WholeEditPresets(context).delete(name); }
    }

    public void testTitleBusyPresetInvalidatesQueuedCaptureAndSaveChecksLiveSettings() throws Exception {
        launch();
        choose(source, "video/mp4", R.id.btnSelectVideo); waitLoading();
        tap(R.id.cbEnableIntro); tap(R.id.cbTitleSourceFrame);
        edit(R.id.etTitleSourceSeconds, "1"); waitTitleBackground(1000);
        checkedMain(() -> {
            try {
                TitleBackgroundControls controls = (TitleBackgroundControls) field("titleBackgroundControls");
                Field restoring = MainActivity.class.getDeclaredField("restoringUi");
                restoring.setAccessible(true);
                Field selected = MainActivity.class.getDeclaredField("selectedMainVideo");
                selected.setAccessible(true);
                Object originalSource = selected.get(activity);
                Class<?> loaded = originalSource.getClass();
                java.lang.reflect.Constructor<?> constructor = loaded.getDeclaredConstructor(
                        VideoSource.class, long.class, Bitmap.class,
                        int.class, int.class, boolean.class);
                constructor.setAccessible(true);
                Object otherSource = constructor.newInstance(
                        VideoSource.document(context, Uri.fromFile(source)), 4000L,
                        null, 320, 240, true);
                EditText seconds = activity.findViewById(R.id.etTitleSourceSeconds);
                EditText crop = activity.findViewById(R.id.etCropLeft);
                String originalCrop = crop.getText().toString();
                restoring.setBoolean(activity, true);
                try {
                    assertLiveTitleSaveRejected(controls,
                            () -> seconds.setText("2"), () -> seconds.setText("1"));
                    assertLiveTitleSaveRejected(controls, () -> {
                        try { selected.set(activity, otherSource); }
                        catch (Exception error) { throw new AssertionError(error); }
                    }, () -> {
                        try { selected.set(activity, originalSource); }
                        catch (Exception error) { throw new AssertionError(error); }
                    });
                    assertLiveTitleSaveRejected(controls,
                            () -> crop.setText("10"), () -> crop.setText(originalCrop));
                } finally {
                    selected.set(activity, originalSource);
                    seconds.setText("1");
                    crop.setText(originalCrop);
                    restoring.setBoolean(activity, false);
                }
            } catch (Exception error) { throw new AssertionError(error); }
        });
        checkedMain(() -> {
            try {
                TitleBackgroundControls controls = (TitleBackgroundControls) field("titleBackgroundControls");
                EditConfig geometry = snapshot();
                java.lang.reflect.Method recipeMethod = MainActivity.class.getDeclaredMethod("wholePresetSnapshot");
                recipeMethod.setAccessible(true);
                JSONObject recipe = (JSONObject) recipeMethod.invoke(activity);
                IntroTemplate title = ((IntroTemplate) field("currentTemplate")).copy();
                title.setSourceFrameSeconds("2");
                controls.apply(title);
                assertNull("Save/preview must check live fields even before refresh", controls.preview());
                controls.save(null);
                assertFalse("direct Save cannot publish stale PNG", controls.isSaving());
                controls.refresh(geometry);
                Field pending = TitleBackgroundControls.class.getDeclaredField("pending"); pending.setAccessible(true);
                ((Runnable) pending.get(controls)).run();
                Field task = TitleBackgroundControls.class.getDeclaredField("task"); task.setAccessible(true);
                ((Future<?>) task.get(controls)).get(10, java.util.concurrent.TimeUnit.SECONDS);
                Field loading = MainActivity.class.getDeclaredField("loading"); loading.setAccessible(true);
                loading.setBoolean(activity, true);
                controls.setBusy(true, true);
                Class<?> loaded = Class.forName("com.simple.videoeditor.MainActivity$LoadedVideo");
                java.lang.reflect.Method apply = MainActivity.class.getDeclaredMethod("applyWholePresetSnapshot",
                        JSONObject.class, loaded, loaded, File.class, PngWatermark.class);
                apply.setAccessible(true);
                apply.invoke(activity, recipe, null, null, null, null);
                assertNull(controls.preview());
                controls.save(null);
                assertFalse("preset loading cannot publish queued old capture", controls.isSaving());
                java.lang.reflect.Method finish = MainActivity.class.getDeclaredMethod("finishLoading");
                finish.setAccessible(true); finish.invoke(activity);
            } catch (Exception error) { throw new AssertionError(error); }
        });
        waitTitleBackground(1000);
        assertEquals(1000L, snapshot().titleBackground.timeMs);
        checkedMain(() -> {
            try {
                TitleBackgroundControls controls = (TitleBackgroundControls) field("titleBackgroundControls");
                EditConfig before = snapshot();
                controls.setBusy(true, true);
                controls.refresh(new EditConfig.Builder(Uri.fromFile(new File(source.getParentFile(), "other.mp4")), 4000)
                        .sourceSize(320, 240).build());
                assertNull("source switch invalidates even while busy", controls.preview());
                controls.setBusy(false, true);
                controls.save(null);
                assertFalse(controls.isSaving());
                controls.setBusy(true, true);
                controls.refresh(new EditConfig.Builder(before.input, 4000).sourceSize(320, 240)
                        .crop(.1f, .1f, .9f, .9f).build());
                controls.setBusy(false, true);
                controls.save(null);
                assertFalse("crop switch cannot save old geometry", controls.isSaving());
                controls.setBusy(false, false);
                assertNull("disabled title must not expose ready background", controls.preview());
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    private void assertLiveTitleSaveRejected(TitleBackgroundControls controls,
                                            Runnable change, Runnable restore) throws Exception {
        Field generation = TitleBackgroundControls.class.getDeclaredField("generation");
        Field requested = TitleBackgroundControls.class.getDeclaredField("requested");
        generation.setAccessible(true); requested.setAccessible(true);
        Object ready = controls.preview(), key = requested.get(controls);
        int token = generation.getInt(controls);
        assertNotNull("each live-state case starts with a ready capture", ready);
        try {
            change.run();
            assertEquals("no refresh or generation invalidation", token, generation.getInt(controls));
            assertEquals(key, requested.get(controls));
            assertNull("live fields alone must reject stale pixels", controls.preview());
            controls.save(null);
            assertFalse("live fields alone must reject stale PNG publication", controls.isSaving());
        } finally { restore.run(); }
        assertSame("restoring live fields reuses the unchanged capture", ready, controls.preview());
    }

    private void checkedMain(Runnable action) {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { action.run(); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private Uri awaitTitleImage() {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            String text = ((TextView) activity.findViewById(R.id.tvTitleSourceStatus)).getText().toString();
            for (String line : text.split("\n")) if (line.startsWith("content://")) return Uri.parse(line);
            SystemClock.sleep(100);
        }
        fail("Save must publish an actual PNG URI");
        return null;
    }

    private Bitmap titleBackgroundBitmap() {
        return ((BitmapDrawable) ((ImageView) activity.findViewById(R.id.ivTitleSourceFrame)).getDrawable()).getBitmap();
    }

    private void waitTitleBackground(long timeMs) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 20000;
        while (SystemClock.uptimeMillis() < deadline) {
            boolean[] ready = {false};
            instrumentation.runOnMainSync(() -> {
                String text = ((TextView) activity.findViewById(R.id.tvTitleSourceStatus)).getText().toString();
                ready[0] = activity.findViewById(R.id.btnSaveTitleFrame).isEnabled()
                        && text.contains(String.format(java.util.Locale.getDefault(), "%.3f", timeMs / 1000d));
            });
            if (ready[0]) return;
            SystemClock.sleep(100);
        }
        fail("Background did not become ready: " + ((TextView) activity.findViewById(R.id.tvTitleSourceStatus)).getText());
    }

    public void testSourceMetadataRotationPrecedesUserCropAndRotation() throws Exception {
        Bitmap original = frame(source, 1000000);
        File rotatedFile = new File(evidence, "derived-rotation.mp4");
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                if (extractor.getTrackFormat(i).getString("mime").startsWith("video/")) track = i;
            }
            assertTrue(track >= 0);
            extractor.selectTrack(track);
            muxer = new android.media.MediaMuxer(rotatedFile.getAbsolutePath(), 0);
            muxer.setOrientationHint(90);
            int outputTrack = muxer.addTrack(extractor.getTrackFormat(track));
            muxer.start();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            int size;
            while ((size = extractor.readSampleData(buffer, 0)) >= 0) {
                info.set(0, size, extractor.getSampleTime(), extractor.getSampleFlags());
                muxer.writeSampleData(outputTrack, buffer, info);
                extractor.advance();
            }
            muxer.stop();
        } finally {
            extractor.release();
            if (muxer != null) muxer.release();
        }
        Bitmap preview = SelectedFramePreview.render(new EditConfig.Builder(Uri.fromFile(rotatedFile), 4000)
                .sourceSize(240, 320).trim(1000, 3000).crop(.25f, 0, .75f, 1).rotation(90).build());
        Bitmap expected = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 120; y++) for (int x = 0; x < 320; x++) {
            expected.setPixel(x, y, original.getPixel(319 - x, 179 - y));
        }
        saveBitmap("metadata-reference.png", expected);
        saveBitmap("metadata-preview.png", preview);
        assertTrue("metadata rotation exactly once, crop in display coordinates, then user rotation MAE="
                        + mae(preview, expected),
                mae(preview, expected) < 2);
        original.recycle(); preview.recycle(); expected.recycle();
        assertTrue(rotatedFile.delete());
    }

    public void testDisplayPixelAspectCorrectionBeforeCropAndResize() throws Exception {
        Bitmap original = frame(source, 1000000);
        Bitmap display = Bitmap.createScaledBitmap(original, 640, 240, true);
        Bitmap preview = SelectedFramePreview.render(new EditConfig.Builder(Uri.fromFile(source), 4000)
                .sourceSize(640, 240).trim(1000, 3000).crop(.25f, 0, .75f, 1).build());
        Bitmap expected = Bitmap.createBitmap(display, 160, 0, 320, 240);
        assertTrue("display dimensions supplied by import include source SAR", mae(preview, expected) < 2);
        original.recycle(); display.recycle(); preview.recycle(); expected.recycle();
    }

    public void testFourTenRemovalRealSarAndClockwisePreviewExport() throws Exception {
        File sar = new File(evidence, "sar2-source.mp4");
        try (InputStream input = instrumentation.getContext().getAssets().open("editor-workflow/sar2.mp4");
             FileOutputStream output = new FileOutputStream(sar)) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
        }
        float[] crop = CropRemoval.retained("10", "10", "10", "10");
        EditConfig config = new EditConfig.Builder(Uri.fromFile(sar), 4000).sourceSize(640, 240)
                .trim(1000, 2000).crop(crop[0], crop[1], crop[2], crop[3]).rotation(90).volume(0).build();
        Bitmap square = frame(source, 1000000);
        Bitmap display = Bitmap.createScaledBitmap(square, 640, 240, true);
        Bitmap expected = Bitmap.createBitmap(192, 512, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 512; y++) for (int x = 0; x < 192; x++) {
            expected.setPixel(x, y, display.getPixel(64 + y, 24 + 191 - x));
        }
        Bitmap preview = SelectedFramePreview.render(config);
        assertEquals(192, preview.getWidth()); assertEquals(512, preview.getHeight());
        saveBitmap("sar-four10-expected.png", expected); saveBitmap("sar-four10-preview.png", preview);
        assertTrue("real SAR then four10 crop then clockwise preview MAE=" + mae(preview, expected),
                mae(preview, expected) < 2);
        File output = new File(evidence, "sar-four10-clockwise-export.mp4");
        if (output.exists()) assertTrue(output.delete());
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Media3ExportEngine[] engine = new Media3ExportEngine[1];
        instrumentation.runOnMainSync(() -> {
            engine[0] = new Media3ExportEngine(context);
            engine[0].export(config, output, new Media3ExportEngine.Listener() {
                @Override public void onProgress(int progress) {}
                @Override public void onCompleted(File file) { done.countDown(); }
                @Override public void onError(Exception error) { failure.set(error); done.countDown(); }
            });
        });
        try {
            assertTrue(done.await(200, java.util.concurrent.TimeUnit.SECONDS));
            if (failure.get() != null) throw failure.get();
            Bitmap exported = frame(output, 0);
            assertEquals(192, exported.getWidth()); assertEquals(512, exported.getHeight());
            assertTrue("real SAR export content, not scaled image on black canvas: " + mae(exported, expected),
                    mae(exported, expected) <= OracleGeneratedContract.create().tolerances.rgbFrameMae);
            saveBitmap("sar-four10-expected.png", expected); saveBitmap("sar-four10-preview.png", preview);
            exported.recycle();
        } finally {
            instrumentation.runOnMainSync(() -> engine[0].cancel());
            square.recycle(); display.recycle(); expected.recycle(); preview.recycle(); sar.delete();
        }
    }

    public void testRealPickerValidationStaleRaceExportMoviesOpenShareAndRelaunch() throws Exception {
        assertFalse("Keep software AVC opt-in off, never force it to pass",
                VideoEncodingSettings.compatibilityEnabled(context));
        launch();
        String name = "public-preview-input-" + UUID.randomUUID() + ".mp4";
        ContentValues values = new ContentValues();
        values.put("_display_name", name);
        values.put("mime_type", "video/mp4");
        values.put("relative_path", "Download");
        values.put("is_pending", 1);
        pickerInput = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        assertNotNull(pickerInput);
        try (InputStream input = new FileInputStream(source);
             OutputStream output = context.getContentResolver().openOutputStream(pickerInput)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
        values.clear();
        values.put("is_pending", 0);
        assertEquals(1, context.getContentResolver().update(pickerInput, values, null, null));
        tap(R.id.btnSelectVideo);
        waitPicker(name, false);
        capture("01-system-picker");
        AccessibilityNodeInfo document = findDocument(activeRoot(), name);
        if (document == null) {
            AccessibilityNodeInfo search = findPickerControl("option_menu_search");
            assertNotNull("System picker search unavailable", search);
            clickNode(search);
            for (KeyEvent event : android.view.KeyCharacterMap.load(
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(name.toCharArray())) {
                assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true));
            }
            key(KeyEvent.KEYCODE_ENTER);
            document = waitPicker(name, true);
        }
        assertNotNull("Actual DocumentsUI fixture must be selected", document);
        clickNode(document);
        SystemClock.sleep(1000);
        capture("01b-after-document-selection");
        waitFrame(0, 0, 320, 240, false, 15000);
        tap(R.id.cbEnableTrim);
        edit(R.id.etTrimEnd, "3");
        edit(R.id.etTrimStart, "1");
        waitFrame(1, 0, 320, 240, false, 10000);
        Bitmap selected = frame(source, 1000000);
        Bitmap zero = frame(source, 0);
        Bitmap shown = shown();
        assertTrue(mae(shown, selected) < 1);
        assertTrue("selected UI frame must not be time zero", mae(shown, zero) > 1);
        for (String invalid : new String[]{"", "-1", "NaN", "3", "4.0001"}) {
            edit(R.id.etTrimStart, invalid);
            assertInvalidEdit(R.string.editor_validation_trim);
            assertFalse(activity.findViewById(R.id.btnProcess).isEnabled());
            assertSame("retain last valid image", shown, shown());
        }
        edit(R.id.etTrimStart, "1");
        waitFrame(1, 0, 320, 240, false, 10000);

        // Hold dispatch until a real old decode has posted its callback, then invalidate it.
        instrumentation.runOnMainSync(() -> {
            try {
                ((EditText) activity.findViewById(R.id.etTrimStart)).setText("2");
                ((Runnable) field("previewRequest")).run();
                Future<?> old = (Future<?>) field("previewTask");
                old.get(5, java.util.concurrent.TimeUnit.SECONDS);
                ((EditText) activity.findViewById(R.id.etTrimStart)).setText("1");
            } catch (Exception error) { throw new AssertionError(error); }
        });
        waitFrame(1, 0, 320, 240, false, 10000);
        assertTrue("stale selected-time completion must not replace newest generation", mae(shown(), selected) < 1);

        edit(R.id.etCropLeft, "50");
        waitFrame(1, 0, 160, 240, false, 10000);
        edit(R.id.etCropRight, "50");
        assertInvalidEdit(R.string.editor_validation_crop);
        assertFalse(activity.findViewById(R.id.btnProcess).isEnabled());
        for (int id : new int[]{R.id.etCropLeft, R.id.etCropTop, R.id.etCropRight, R.id.etCropBottom}) {
            edit(id, "10");
        }
        waitFrame(1, 0, 256, 192, false, 10000);
        Bitmap expected = Bitmap.createBitmap(selected, 32, 24, 256, 192);
        Bitmap cropPreview = shown();
        assertEquals(256, cropPreview.getWidth());
        assertEquals(192, cropPreview.getHeight());
        assertTrue("four 10% removals retain real center pixels, not a black mask", mae(cropPreview, expected) < 2);
        reveal(R.id.ivVideoThumbnail);
        capture("02-selected-trim-crop-rotate");
        assertScreenshotPixels(cropPreview);
        saveBitmap("selected-preview.png", cropPreview);
        PublishedVideo previousOutput = currentOutput();
        tap(R.id.btnProcess);
        PublishedVideo video = awaitNewOutput(previousOutput);
        assertNotNull(video);
        assertNotNull("Actual export must publish, not merely retain private file: " + video.info(), video.uri);
        assertEquals("Movies/", video.location);
        Bitmap exported = frame(video.privateFile, 0);
        double exportMae = mae(exported, expected);
        assertTrue("export vs independent selected-frame mapping MAE=" + exportMae,
                exportMae <= OracleGeneratedContract.create().tolerances.rgbFrameMae);
        assertEquals(256, exported.getWidth());
        assertEquals(192, exported.getHeight());
        assertEquals(hash(new FileInputStream(video.privateFile)),
                hash(context.getContentResolver().openInputStream(video.uri)));
        try (android.database.Cursor cursor = context.getContentResolver().query(video.uri,
                new String[]{"relative_path", "is_pending", "_display_name", "_size"}, null, null, null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals("Movies/", cursor.getString(0));
            assertEquals(0, cursor.getInt(1));
            assertEquals(video.name, cursor.getString(2));
            assertEquals(video.privateFile.length(), cursor.getLong(3));
        }
        reveal(R.id.btnOpenOutputFolder);
        capture("03-public-movies-result");
        for (boolean share : new boolean[]{false, true}) {
            Intent intent = OutputSharing.prepareVideo(activity, video, share);
            assertEquals("video/mp4", intent.getType());
            assertEquals(video.uri, share ? intent.getParcelableExtra(Intent.EXTRA_STREAM) : intent.getData());
            assertEquals(video.uri, intent.getClipData().getItemAt(0).getUri());
            assertTrue((intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
            tap(share ? R.id.btnShareVideo : R.id.btnOpenOutputFolder);
            long externalDeadline = SystemClock.uptimeMillis() + 10000;
            AccessibilityNodeInfo root;
            do {
                SystemClock.sleep(200);
                root = activeRoot();
            } while (context.getPackageName().contentEquals(root.getPackageName())
                    && SystemClock.uptimeMillis() < externalDeadline);
            if (context.getPackageName().contentEquals(root.getPackageName())) capture("external-open-missing-" + share);
            assertFalse("real external viewer/chooser must appear; share=" + share,
                    context.getPackageName().contentEquals(root.getPackageName()));
            SystemClock.sleep(1000);
            capture(share ? "05-share-original" : "04-open-original");
            key(KeyEvent.KEYCODE_BACK);
            externalDeadline = SystemClock.uptimeMillis() + 10000;
            while (!context.getPackageName().contentEquals(activeRoot().getPackageName())
                    && SystemClock.uptimeMillis() < externalDeadline) SystemClock.sleep(100);
            assertTrue(context.getPackageName().contentEquals(activeRoot().getPackageName()));
        }
        tap(R.id.btnCopyVideoInfo);
        String[] copiedLocation = new String[1];
        instrumentation.runOnMainSync(() -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    context.getSystemService(Context.CLIPBOARD_SERVICE);
            copiedLocation[0] = clipboard.getPrimaryClip() == null ? "" :
                    clipboard.getPrimaryClip().getItemAt(0).getText().toString();
        });
        assertTrue("Copy must contain the current public URI", copiedLocation[0].contains(video.uri.toString()));
        instrumentation.runOnMainSync(activity::finish);
        SystemClock.sleep(500);
        launch();
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (field("lastVideo") == null && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(50);
        assertEquals(video.uri, ((PublishedVideo) field("lastVideo")).uri);
        reveal(R.id.btnOpenOutputFolder);
        capture("06-relaunch-durable-result");
        assertEquals(hash(new FileInputStream(video.privateFile)),
                hash(context.getContentResolver().openInputStream(video.uri)));
        JSONObject result = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                .put("uri", video.uri).put("location", video.location).put("name", video.name)
                .put("private", video.privateFile).put("sha256", hash(new FileInputStream(video.privateFile)))
                .put("export_frame_mae", exportMae).put("source_selected_ms", 1000)
                .put("width", exported.getWidth()).put("height", exported.getHeight())
                .put("real_picker", true).put("default_software_mode", false)
                .put("status", "PASS");
        try (FileOutputStream output = new FileOutputStream(new File(evidence, "result.json"))) {
            output.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        selected.recycle(); zero.recycle(); expected.recycle(); exported.recycle();
    }

    public void testWholePresetRealPickerSecondDurationAtomicValidationAndTitleControls() throws Exception {
        String name = "workflow-" + UUID.randomUUID();
        WholeEditPresets store = new WholeEditPresets(context);
        File longer = repeatVideo(2, 4000000), shorter = repeatVideo(1, 1500000);
        File png = new File(evidence, name + ".png");
        try {
            try (InputStream input = context.getAssets().open("watermark-oracle/watermark.png");
                 FileOutputStream output = new FileOutputStream(png)) {
                byte[] bytes = new byte[8192]; int n;
                while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
            }
            launch();
            choose(source, "video/mp4", R.id.btnSelectVideo);
            waitFrame(0, 0, 320, 240, false, 15000);
            tap(R.id.cbEnableTrim); edit(R.id.etTrimStart, "1"); edit(R.id.etTrimEnd, "3");
            for (int id : new int[]{R.id.etCropLeft, R.id.etCropTop, R.id.etCropRight, R.id.etCropBottom}) edit(id, "10");
            edit(R.id.etCustomAngle, "90");
            select(R.id.spinnerResolution, 3); select(R.id.spinnerSpeed, 4);
            tap(R.id.cbEnableVolume); select(R.id.spinnerVolume, 1);
            edit(R.id.etOverlayText, "Whole edit");
            tap(R.id.cbEnableIntro);
            select(R.id.spinnerTitleStyle, 3); select(R.id.spinnerTitleDuration, 2);
            select(R.id.spinnerTitleFont, 2); select(R.id.spinnerTitleWeight, 3); select(R.id.spinnerTitleAlign, 0);
            select(R.id.spinnerTitlePalette, 1); edit(R.id.etTitleSize, "40");
            edit(R.id.etIntroText, "FIRST LINE\nA wrapping second line for the story");
            reveal(R.id.titleCanvasPreview); capture("preset-title-preview");
            choose(png, "image/png", R.id.btnSelectWatermark);
            waitLoading();
            if (!((android.widget.CheckBox) activity.findViewById(R.id.cbEnableWatermark)).isChecked()) tap(R.id.cbEnableWatermark);
            choose(source, "video/mp4", R.id.btnSelectIntro);
            waitLoading();
            edit(R.id.etWholePresetName, name); tap(R.id.btnSaveWholePreset);
            clickNode(activeRoot().findAccessibilityNodeInfosByViewId("android:id/button1").get(0)); waitLoading();
            JSONObject saved = store.load(name);
            assertEquals("typewriter", saved.getJSONObject("title").getString("animation"));
            assertEquals("monospace", saved.getJSONObject("title").getString("fontFamily"));
            assertEquals(5000, saved.getJSONObject("title").getInt("durationMs"));
            assertNotNull(store.asset(saved, "pngAsset")); assertNotNull(store.asset(saved, "introAsset"));
            boolean software = VideoEncodingSettings.compatibilityEnabled(context);
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            launch();
            choose(longer, "video/mp4", R.id.btnSelectVideo); waitLoading();
            edit(R.id.etOverlayText, "Before apply");
            selectName(R.id.spinnerWholePreset, name);
            tap(R.id.btnApplyWholePreset); waitLoading();
            EditConfig restored = snapshot();
            assertEquals(1000L, restored.startMs);
            assertEquals(restored.sourceDurationMs - saved.getLong("tailMs"), restored.endMs);
            assertEquals(90, restored.rotationDegrees); assertEquals(480, restored.outputHeight);
            assertEquals(1.5f, restored.speed); assertEquals(.5f, restored.volume);
            assertEquals("Whole edit", restored.overlayText); assertNotNull(restored.watermark);
            assertNotNull(restored.introVideo); assertEquals("typewriter", restored.introTitle.animation);
            assertEquals(software, VideoEncodingSettings.compatibilityEnabled(context));
            assertTrue(restored.appendedVideos.isEmpty());
            capture("preset-second-duration-restored");
            PublishedVideo previousOutput = currentOutput();
            tap(R.id.btnProcess);
            PublishedVideo presetOutput = awaitNewOutput(previousOutput);
            assertNotNull(presetOutput);
            assertNotSame("Restored preset must produce a new output", previousOutput, presetOutput);
            assertNotNull(presetOutput.uri); assertEquals("Movies/", presetOutput.location);
            Bitmap titleFrame = frame(presetOutput.privateFile, 1200000);
            assertEquals(360, titleFrame.getWidth()); assertEquals(480, titleFrame.getHeight());
            Bitmap titleExpected = Bitmap.createBitmap(360, 480, Bitmap.Config.ARGB_8888);
            TitleRenderer.draw(new android.graphics.Canvas(titleExpected), 360, 480, restored.introTitle, 1200000);
            assertTrue("whole preset exported title config", mae(titleFrame, titleExpected) < 6);
            titleFrame.recycle(); titleExpected.recycle();
            assertEquals(hash(new FileInputStream(presetOutput.privateFile)),
                    hash(context.getContentResolver().openInputStream(presetOutput.uri)));
            edit(R.id.etOverlayText, "Updated whole edit");
            tap(R.id.btnSaveWholePreset);
            clickNode(activeRoot().findAccessibilityNodeInfosByViewId("android:id/button1").get(0)); waitLoading();
            saved = store.load(name); assertEquals("Updated whole edit", saved.getString("overlay"));
            choose(shorter, "video/mp4", R.id.btnSelectVideo); waitLoading();
            EditConfig before = snapshot();
            tap(R.id.btnApplyWholePreset); waitLoading();
            assertEditUnchanged(before, snapshot());
            assertPresetRejected("Source too short");
            assertTrue(store.asset(saved, "pngAsset").delete());
            choose(longer, "video/mp4", R.id.btnSelectVideo); waitLoading();
            before = snapshot();
            tap(R.id.btnApplyWholePreset); waitLoading();
            assertEditUnchanged(before, snapshot());
            assertPresetRejected("Missing or changed");
            capture("preset-missing-asset-atomic-rejection");
            tap(R.id.btnDeleteWholePreset);
            java.util.List<AccessibilityNodeInfo> confirm = activeRoot().findAccessibilityNodeInfosByViewId("android:id/button1");
            assertEquals("Delete confirmation positive button", 1, confirm.size());
            clickNode(confirm.get(0));
            instrumentation.waitForIdleSync();
            for (String item : store.names()) assertFalse("Confirmed deletion must persist", name.equals(item));
            JSONObject result = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                    .put("status", "PASS").put("realPicker", true).put("secondDurationMs", restored.sourceDurationMs)
                    .put("headMs", restored.startMs).put("endMs", restored.endMs)
                    .put("publicOutput", presetOutput.uri).put("sha256", hash(new FileInputStream(presetOutput.privateFile)))
                    .put("missingAssetAtomic", true).put("shortSourceAtomic", true).put("durableAssets", true);
            try (FileOutputStream output = new FileOutputStream(new File(evidence, "preset-result.json"))) {
                output.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally { store.delete(name); longer.delete(); shorter.delete(); png.delete(); }
    }

    public void testDisabledPortraitPngPresetOnLandscapePreservesAssetAndReenableValidation() throws Exception {
        String name = "disabled-png-" + UUID.randomUUID();
        WholeEditPresets store = new WholeEditPresets(context);
        File png = new File(evidence, name + ".png");
        Bitmap portrait = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888);
        portrait.eraseColor(Color.RED);
        boolean encodingBeforeLaunch = VideoEncodingSettings.compatibilityEnabled(context);
        try {
            try (FileOutputStream output = new FileOutputStream(png)) {
                assertTrue(portrait.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
            launch();
            assertEquals("Restored checkbox state must not overwrite the selected encoding policy",
                    encodingBeforeLaunch, VideoEncodingSettings.compatibilityEnabled(context));
            choose(source, "video/mp4", R.id.btnSelectVideo);
            edit(R.id.etCustomAngle, "90");
            choose(png, "image/png", R.id.btnSelectWatermark);
            if (!((android.widget.CheckBox) activity.findViewById(R.id.cbEnableWatermark)).isChecked())
                tap(R.id.cbEnableWatermark);
            edit(R.id.etWatermarkWidth, "50");
            waitFrame(0, 90, 240, 320, true, 15000);
            assertNotNull(snapshot().watermark);
            edit(R.id.etWholePresetName, name);
            tap(R.id.btnSaveWholePreset); waitLoading();
            JSONObject enabled = store.load(name).put("rotation", 0).put("overlay", "must not apply");
            File durable = store.asset(enabled, "pngAsset");
            store.save(name + "-enabled", enabled, null, null, durable);
            tap(R.id.cbEnableWatermark);
            tap(R.id.btnSaveWholePreset); waitLoading();
            JSONObject disabled = store.load(name).put("rotation", 0);
            store.save(name, disabled, null, null, store.asset(disabled, "pngAsset"));
            instrumentation.runOnMainSync(activity::finish);
            instrumentation.waitForIdleSync();
            launch();
            assertEquals(encodingBeforeLaunch, VideoEncodingSettings.compatibilityEnabled(context));
            choose(source, "video/mp4", R.id.btnSelectVideo);
            selectName(R.id.spinnerWholePreset, name);
            tap(R.id.btnApplyWholePreset); waitLoading();
            assertEquals(activity.getString(R.string.editor_preset_applied, name),
                    ((TextView) activity.findViewById(R.id.tvProgress)).getText().toString());
            assertEquals(0, snapshot().rotationDegrees);
            assertNull(snapshot().watermark);
            assertFalse(((android.widget.CheckBox) activity.findViewById(R.id.cbEnableWatermark)).isChecked());
            assertEquals("50.0", ((EditText) activity.findViewById(R.id.etWatermarkWidth)).getText().toString());
            Object retained = field("watermarkImage");
            String retainedPath = (String) field("watermarkFilePath");
            assertNotNull(retained);
            assertTrue(new File(retainedPath).isFile());
            String retainedHash = hash(new FileInputStream(new File(retainedPath)));
            assertEquals(hash(new FileInputStream(png)), retainedHash);
            edit(R.id.etOverlayText, "unchanged");
            selectName(R.id.spinnerWholePreset, name + "-enabled");
            tap(R.id.btnApplyWholePreset); waitLoading();
            assertEquals("unchanged", snapshot().overlayText);
            assertEquals(0, snapshot().rotationDegrees);
            assertNull(snapshot().watermark);
            assertSame(retained, field("watermarkImage"));
            assertEquals(retainedPath, field("watermarkFilePath"));
            assertEquals(retainedHash, hash(new FileInputStream(new File(retainedPath))));
            assertTrue(((TextView) activity.findViewById(R.id.tvErrorDetails)).getText().length() > 0);
            tap(R.id.cbEnableWatermark);
            assertInvalidEdit(R.string.editor_validation_png);
            assertFalse(activity.findViewById(R.id.btnProcess).isEnabled());
            edit(R.id.etCustomAngle, "90");
            waitFrame(0, 90, 240, 320, true, 15000);
            assertNotNull(snapshot().watermark);
            assertSame(retained, snapshot().watermark.image);
            assertTrue(activity.findViewById(R.id.btnProcess).isEnabled());
        } finally {
            portrait.recycle(); png.delete(); store.delete(name); store.delete(name + "-enabled");
        }
    }

    public void testBorderRealPickerPaletteWidthScopePreviewPresetAndMovies() throws Exception {
        String name = "border-ui-" + UUID.randomUUID();
        WholeEditPresets store = new WholeEditPresets(context);
        Bitmap original = null;
        try {
            launch();
            choose(source, "video/mp4", R.id.btnSelectVideo);
            waitFrame(0, 0, 320, 240, false, 15000);
            assertFalse(snapshot().border.enabled);
            assertFalse(activity.findViewById(R.id.spinnerBorderColor).isEnabled());
            original = shown().copy(Bitmap.Config.ARGB_8888, false);
            tap(R.id.cbEnableBorder);
            assertInvalidEdit(R.string.editor_validation_border);
            assertFalse(activity.findViewById(R.id.btnProcess).isEnabled());
            borderOption(R.id.spinnerBorderScope, 1);
            borderOption(R.id.spinnerBorderColor, 2);
            borderOption(R.id.spinnerBorderWidth, 3);
            waitBorderFrame(0xFF0066FF);
            BorderPixelChecks.check(shown(), original, 4, 0xFF0066FF, true, 0, 0);
            borderOption(R.id.spinnerBorderColor, 0);
            waitBorderFrame(0xFFFF0000);
            BorderPixelChecks.check(shown(), original, 4, 0xFFFF0000, true, 0, 0);
            capture("border-red-whole-preview");
            assertBorderScreenshotPixels(shown(), 4, 0xFFFF0000);
            tap(R.id.cbEnableIntro);
            select(R.id.spinnerTitleStyle, 1);
            select(R.id.spinnerTitleDuration, 1);
            edit(R.id.etIntroText, "BORDER TITLE");
            borderOption(R.id.spinnerBorderScope, 0);
            waitFrame(0, 0, 320, 240, false, 15000);
            BorderPixelChecks.check(shown(), original, 4, 0xFFFF0000, false, 0, 0);
            reveal(R.id.titleCanvasPreview);
            capture("border-title-only-preview");
            assertEquals(3000, snapshot().introTitle.durationMs);
            edit(R.id.etWholePresetName, name); tap(R.id.btnSaveWholePreset); waitLoading();
            VideoBorder saved = VideoBorder.fromRecipe(store.load(name));
            assertTrue(saved.enabled); assertEquals(4, saved.percent);
            assertEquals(VideoBorder.Scope.TITLE, saved.scope);
            tap(R.id.cbEnableBorder);
            assertEquals(4, snapshot().border.percent);
            selectName(R.id.spinnerWholePreset, name); tap(R.id.btnApplyWholePreset); waitLoading();
            assertTrue(snapshot().border.enabled);
            PublishedVideo previous = currentOutput();
            tap(R.id.btnProcess);
            for (int id : new int[]{R.id.cbEnableBorder, R.id.spinnerBorderColor,
                    R.id.spinnerBorderWidth, R.id.spinnerBorderScope, R.id.btnLanguage}) {
                assertFalse("busy border controls locked", activity.findViewById(id).isEnabled());
            }
            PublishedVideo video = awaitNewOutput(previous);
            assertNotNull(video.uri); assertEquals("Movies/", video.location);
            assertEquals(hash(new FileInputStream(video.privateFile)),
                    hash(context.getContentResolver().openInputStream(video.uri)));
            Bitmap title = frame(video.privateFile, 1200000);
            Bitmap referenceTitle = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
            TitleRenderer.draw(new android.graphics.Canvas(referenceTitle), 320, 240, snapshot().introTitle, 1200000);
            Bitmap main = frame(video.privateFile, 3000000);
            try {
                BorderPixelChecks.check(title, referenceTitle, 4, 0xFFFF0000, true, 32, 6);
                BorderPixelChecks.check(main, original, 4, 0xFFFF0000, false, 32, 6);
                saveBitmap("border-public-title.png", title); saveBitmap("border-public-main.png", main);
            } finally { title.recycle(); referenceTitle.recycle(); main.recycle(); }
            capture("border-public-movies");
            JSONObject result = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                    .put("status", "PASS").put("uri", video.uri).put("location", video.location)
                    .put("titleBoundaryMs", 3000).put("presetRoundTrip", true);
            try (FileOutputStream output = new FileOutputStream(new File(evidence, "border-result.json"))) {
                output.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally {
            if (original != null) original.recycle();
            store.delete(name);
        }
    }

    public void testColorSlidersResetPresetNewSourcePreviewMoviesOpenShare() throws Exception {
        String name = "color-ui-" + UUID.randomUUID();
        WholeEditPresets store = new WholeEditPresets(context);
        String earlierName = "aaa-" + name;
        store.save(earlierName, new JSONObject().put("crop",new org.json.JSONArray(new int[]{0,0,0,0}))
                .put("headMs",0).put("tailMs",0),null,null,null);
        source = new File(evidence, "color-ui-palette.mp4");
        try (InputStream input = instrumentation.getContext().getAssets().open("color-adjust/palette.mp4");
             FileOutputStream output = new FileOutputStream(source)) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
        }
        Bitmap original = null;
        try {
            launch(); choose(source, "video/mp4", R.id.btnSelectVideo);
            waitFrame(0,0,320,240,false,15000);
            original = shown().copy(Bitmap.Config.ARGB_8888,false);
            assertTrue(snapshot().colorAdjustment.isIdentity());
            assertFalse(activity.findViewById(R.id.colorBrightness).isEnabled());
            Rect dock = new Rect();
            activity.findViewById(R.id.editorPreviewHeader).getGlobalVisibleRect(dock);
            tap(R.id.cbEnableColor);
            colorSlider(R.id.colorSaturation, 0);
            assertEquals(0,snapshot().colorAdjustment.saturation);
            colorSlider(R.id.colorBrightness, .6f);
            colorSlider(R.id.colorContrast, .7f);
            ColorAdjustment value = snapshot().colorAdjustment;
            assertTrue(value.brightness>0); assertTrue(value.contrast>100);
            waitColorFrame(original,value);
            Rect after = new Rect();
            activity.findViewById(R.id.editorPreviewHeader).getGlobalVisibleRect(after);
            assertEquals("fixed preview dock",dock,after);
            if (Build.VERSION.SDK_INT >= 29) {
                // Drawable pixels and main-thread idle do not fence RenderThread's previous palette frame.
                CountDownLatch committed = new CountDownLatch(1);
                instrumentation.runOnMainSync(() -> {
                    ImageView preview = activity.findViewById(R.id.ivVideoThumbnail);
                    assertTrue("Frame commit requires hardware rendering", preview.isHardwareAccelerated());
                    preview.getViewTreeObserver().registerFrameCommitCallback(committed::countDown);
                    preview.invalidate();
                });
                assertTrue("Color preview frame must commit before screenshot",
                        committed.await(5, TimeUnit.SECONDS));
            }
            assertScreenshotPixels(shown()); capture("color-adjust-sliders");
            tap(R.id.btnResetColor);
            waitColorFrame(original,new ColorAdjustment(true,0,100,100));
            assertTrue("reset exact pixels",original.sameAs(shown()));
            colorSlider(R.id.colorSaturation,0);
            tap(R.id.cbEnableColor);
            edit(R.id.etWholePresetName,name); tap(R.id.btnSaveWholePreset); waitLoading();
            assertEquals("save selects this recipe, not the alphabetically first recipe",name,
                    ((android.widget.Spinner)activity.findViewById(R.id.spinnerWholePreset)).getSelectedItem());
            assertEquals(0,ColorAdjustment.fromRecipe(store.load(name)).saturation);
            assertFalse(ColorAdjustment.fromRecipe(store.load(name)).enabled);
            File second = repeatVideo(2,1000000);
            choose(second,"video/mp4",R.id.btnSelectVideo);
            tap(R.id.btnResetColor);
            selectName(R.id.spinnerWholePreset,name); tap(R.id.btnApplyWholePreset); waitLoading();
            assertEquals(2000,snapshot().sourceDurationMs);
            assertFalse(snapshot().colorAdjustment.enabled); assertEquals(0,snapshot().colorAdjustment.saturation);
            tap(R.id.cbEnableColor);
            value = snapshot().colorAdjustment;
            waitColorFrame(original,value);
            PublishedVideo previous = currentOutput();
            tap(R.id.btnProcess);
            for (int id:new int[]{R.id.cbEnableColor,R.id.colorBrightness,R.id.colorContrast,
                    R.id.colorSaturation,R.id.btnResetColor})
                assertFalse("busy color controls",activity.findViewById(id).isEnabled());
            PublishedVideo video = awaitNewOutput(previous);
            assertNotNull(video.uri); assertEquals("Movies/",video.location);
            assertEquals(hash(new FileInputStream(video.privateFile)),
                    hash(context.getContentResolver().openInputStream(video.uri)));
            Bitmap actual = frame(video.privateFile,0);
            try {
                for(int y:new int[]{20,60,100,140,170,190,210,230})
                    for(int x:new int[]{40,120,200,280})
                        ColorAdjustmentTest.assertColor(ColorAdjustmentTest.expected(original.getPixel(x,y),
                                value.brightness,value.contrast,value.saturation),actual.getPixel(x,y),24);
            } finally { actual.recycle(); }
            capture("color-adjust-public-movies");
            for(boolean share:new boolean[]{false,true}) {
                Intent intent=OutputSharing.prepareVideo(activity,video,share);
                assertEquals(video.uri,share?intent.getParcelableExtra(Intent.EXTRA_STREAM):intent.getData());
                int button = share?R.id.btnShareVideo:R.id.btnOpenOutputFolder;
                long readyDeadline=SystemClock.uptimeMillis()+10000;
                while(activeRoot().findAccessibilityNodeInfosByViewId(context.getResources().getResourceName(button)).size()!=1
                        && SystemClock.uptimeMillis()<readyDeadline) SystemClock.sleep(100);
                tap(button);
                long deadline=SystemClock.uptimeMillis()+10000;
                while(context.getPackageName().contentEquals(activeRoot().getPackageName())
                        && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100);
                assertFalse(context.getPackageName().contentEquals(activeRoot().getPackageName()));
                SystemClock.sleep(1000);
                capture(share?"color-adjust-share":"color-adjust-open");
                key(KeyEvent.KEYCODE_BACK);
                deadline=SystemClock.uptimeMillis()+10000;
                while(!context.getPackageName().contentEquals(activeRoot().getPackageName())
                        && SystemClock.uptimeMillis()<deadline) SystemClock.sleep(100);
                assertTrue("external viewer returns to editor",
                        context.getPackageName().contentEquals(activeRoot().getPackageName()));
            }
            JSONObject result = new JSONObject().put("revision",BuildConfig.SOURCE_REVISION)
                    .put("status","PASS").put("uri",video.uri).put("settings",value.toJson())
                    .put("newSourceDurationMs",2000).put("realSliders",true).put("exactReset",true);
            try(FileOutputStream output=new FileOutputStream(new File(evidence,"color-result.json"))) {
                output.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally { if(original!=null) original.recycle(); store.delete(name); store.delete(earlierName); }
    }

    private void colorSlider(int id,float fraction) {
        reveal(id);
        Rect rect = new Rect();
        activity.findViewById(id).getGlobalVisibleRect(rect);
        int x = rect.left + Math.round(fraction * rect.width());
        touch(new Rect(x,rect.centerY(),x+1,rect.centerY()+1));
        instrumentation.waitForIdleSync();
    }

    private void waitColorFrame(Bitmap original,ColorAdjustment value) {
        long deadline=SystemClock.uptimeMillis()+15000;
        while(SystemClock.uptimeMillis()<deadline) {
            instrumentation.waitForIdleSync();
            if(!label().equals(activity.getString(R.string.editor_preview_updating))) {
                boolean match=true;
                for(int y:new int[]{20,60,100,140}) for(int x:new int[]{40,120,200,280}) {
                    int expected=value.isIdentity()?original.getPixel(x,y):ColorAdjustmentTest.expected(
                            original.getPixel(x,y),value.brightness,value.contrast,value.saturation);
                    if(colorError(expected,shown().getPixel(x,y))>1) match=false;
                }
                if(match)return;
            }
            SystemClock.sleep(50);
        }
        fail("independent color preview timeout");
    }

    private void borderOption(int id, int position) throws Exception {
        String option = ((android.widget.Spinner) activity.findViewById(id)).getItemAtPosition(position).toString();
        tap(id);
        java.util.List<AccessibilityNodeInfo> nodes = activeRoot().findAccessibilityNodeInfosByText(option);
        AccessibilityNodeInfo selected = null;
        for (AccessibilityNodeInfo node : nodes) if (option.contentEquals(node.getText())) selected = node;
        assertNotNull("real border option " + option, selected);
        clickNode(selected); instrumentation.waitForIdleSync();
    }

    private void waitBorderFrame(int color) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            if (label().contains(activity.getString(R.string.editor_preview_border))
                    && shown().getPixel(0, 0) == color) return;
            SystemClock.sleep(50);
        }
        fail("border preview timeout: " + label());
    }

    private void assertBorderScreenshotPixels(Bitmap preview, int percent, int rgb) {
        ImageView image = activity.findViewById(R.id.ivVideoThumbnail);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        int[] origin = new int[2];
        image.getLocationOnScreen(origin);
        int w = preview.getWidth(), h = preview.getHeight();
        float half = Math.max(1, (Math.min(w, h) * percent + 50) / 100) / 2f;
        try {
            for (float[] point : new float[][]{{half, half}, {w - half, half},
                    {half, h - half}, {w - half, h - half}, {w / 2f, half},
                    {w / 2f, h - half}, {half, h / 2f}, {w - half, h / 2f}}) {
                point[0] *= image.getDrawable().getIntrinsicWidth() / (float) w;
                point[1] *= image.getDrawable().getIntrinsicHeight() / (float) h;
                image.getImageMatrix().mapPoints(point);
                int actual = screenshot.getPixel(origin[0] + image.getPaddingLeft() + Math.round(point[0]),
                        origin[1] + image.getPaddingTop() + Math.round(point[1]));
                assertTrue("screenshot four edges and corners: " + Integer.toHexString(actual),
                        colorError(actual, rgb) <= 18);
            }
        } finally { screenshot.recycle(); }
    }

    private static void assertEditUnchanged(EditConfig before, EditConfig after) {
        assertEquals(before.input, after.input); assertEquals(before.startMs, after.startMs); assertEquals(before.endMs, after.endMs);
        assertEquals(before.cropLeft, after.cropLeft); assertEquals(before.cropTop, after.cropTop);
        assertEquals(before.cropRight, after.cropRight); assertEquals(before.cropBottom, after.cropBottom);
        assertEquals(before.rotationDegrees, after.rotationDegrees); assertEquals(before.outputHeight, after.outputHeight);
        assertEquals(before.speed, after.speed); assertEquals(before.volume, after.volume);
        assertEquals(before.overlayText, after.overlayText); assertEquals(before.replacementMusic, after.replacementMusic);
        assertEquals(before.intro, after.intro); assertEquals(before.mergeEnabled, after.mergeEnabled);
        assertEquals(before.appendedVideos.size(), after.appendedVideos.size());
        if (before.watermark == null) assertNull(after.watermark);
        else {
            assertNotNull(after.watermark);
            assertSame(before.watermark.image, after.watermark.image);
            assertEquals(before.watermark.widthFraction, after.watermark.widthFraction);
            assertEquals(before.watermark.x, after.watermark.x); assertEquals(before.watermark.y, after.watermark.y);
        }
        assertEquals(before.introTitle.text, after.introTitle.text);
        assertEquals(before.introTitle.animation, after.introTitle.animation);
        assertEquals(before.introTitle.fontFamily, after.introTitle.fontFamily);
        assertEquals(before.introTitle.durationMs, after.introTitle.durationMs);
    }

    private void select(int id, int position) {
        reveal(id);
        instrumentation.runOnMainSync(() -> {
            android.widget.Spinner spinner = activity.findViewById(id);
            assertTrue("Spinner must be enabled", spinner.isEnabled());
            spinner.setSelection(position);
        });
        instrumentation.waitForIdleSync();
    }

    private void selectName(int id, String name) {
        android.widget.Spinner spinner = activity.findViewById(id);
        for (int i = 0; i < spinner.getCount(); i++) if (name.equals(spinner.getItemAtPosition(i))) { select(id, i); return; }
        fail("Preset missing from spinner");
    }

    private EditConfig snapshot() throws Exception {
        java.lang.reflect.Method method = MainActivity.class.getDeclaredMethod("snapshotConfig");
        method.setAccessible(true);
        return (EditConfig) method.invoke(activity);
    }

    private void waitLoading() throws Exception {
        long deadline = SystemClock.uptimeMillis() + 65000;
        do { SystemClock.sleep(100); instrumentation.waitForIdleSync(); }
        while ((Boolean) field("loading") && SystemClock.uptimeMillis() < deadline);
        assertFalse("UI load/save/apply timeout", (Boolean) field("loading"));
        SystemClock.sleep(300);
    }

    private void choose(File file, String mime, int button) throws Exception {
        String name = "workflow-picker-" + UUID.randomUUID() + (mime.equals("image/png") ? ".png" : ".mp4");
        ContentValues values = new ContentValues();
        values.put("_display_name", name); values.put("mime_type", mime);
        values.put("relative_path", "Download"); values.put("is_pending", 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        extraPickerInputs.add(uri);
        try (InputStream input = new FileInputStream(file); OutputStream output = context.getContentResolver().openOutputStream(uri)) {
            byte[] bytes = new byte[8192]; int n;
            while ((n = input.read(bytes)) != -1) output.write(bytes, 0, n);
        }
        values.clear(); values.put("is_pending", 0);
        context.getContentResolver().update(uri, values, null, null);
        tap(button); waitPicker(name, false);
        AccessibilityNodeInfo listView = findPickerControl("option_menu_list");
        if (listView != null) { clickNode(listView); SystemClock.sleep(500); }
        AccessibilityNodeInfo document = findDocument(activeRoot(), name);
        if (document == null) {
            AccessibilityNodeInfo search = findPickerControl("option_menu_search");
            assertNotNull(search); clickNode(search);
            for (KeyEvent event : android.view.KeyCharacterMap.load(android.view.KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(name.toCharArray()))
                assertTrue(instrumentation.getUiAutomation().injectInputEvent(event, true));
            key(KeyEvent.KEYCODE_ENTER);
            document = waitPicker(name, true);
        }
        if (document == null) capture("preset-picker-missing-" + button);
        assertNotNull("Real picker document missing", document);
        Rect documentBounds = new Rect();
        document.getBoundsInScreen(documentBounds);
        assertFalse("Document must have visible bounds", documentBounds.isEmpty());
        clickNode(document);
        long returned = SystemClock.uptimeMillis() + 15000;
        while (!context.getPackageName().contentEquals(activeRoot().getPackageName())
                && SystemClock.uptimeMillis() < returned) SystemClock.sleep(100);
        if (!context.getPackageName().contentEquals(activeRoot().getPackageName())) capture("preset-picker-did-not-return");
        assertTrue("Document selection must return to the real editor",
                context.getPackageName().contentEquals(activeRoot().getPackageName()));
        waitLoading();
    }

    private AccessibilityNodeInfo waitPicker(String name, boolean requireDocument) {
        long deadline = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && root.getPackageName() != null
                    && (root.getPackageName().toString().equals("com.android.documentsui")
                    || root.getPackageName().toString().equals("com.google.android.documentsui"))) {
                AccessibilityNodeInfo document = findDocument(root, name);
                if (document != null && document.isVisibleToUser()) return document;
                if (!requireDocument) {
                    for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByViewId(
                            root.getPackageName() + ":id/option_menu_search")) {
                        if (node.isVisibleToUser() && node.isEnabled()) return null;
                    }
                    // A preceding SAF test/provider may leave DocumentsUI in a root without search.
                    AccessibilityNodeInfo navigation = navigationButton(root);
                    if (navigation != null) {
                        clickNode(navigation);
                        SystemClock.sleep(300);
                        AccessibilityNodeInfo drawer = activeRoot();
                        for (String label : new String[]{"Downloads", "下载"}) {
                            java.util.List<AccessibilityNodeInfo> downloads =
                                    drawer.findAccessibilityNodeInfosByText(label);
                            if (!downloads.isEmpty()) {
                                clickNode(downloads.get(0));
                                SystemClock.sleep(300);
                                break;
                            }
                        }
                    }
                }
            }
            SystemClock.sleep(100);
        }
        fail("Real DocumentsUI did not become ready: " + name + ", requireDocument=" + requireDocument);
        return null;
    }

    private AccessibilityNodeInfo findDocument(AccessibilityNodeInfo node, String name) {
        if (node == null) return null;
        if (("android:id/title".equals(node.getViewIdResourceName()) && node.getText() != null
                && node.getText().toString().equals(name))
                || (node.getContentDescription() != null && node.getContentDescription().toString().startsWith(name + ","))) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo found = findDocument(node.getChild(i), name);
            if (found != null) return found;
        }
        return null;
    }

    private File repeatVideo(int repeats, long limitUs) throws Exception {
        File output = new File(evidence, "derived-duration-" + UUID.randomUUID() + ".mp4");
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = new android.media.MediaMuxer(output.getAbsolutePath(), 0);
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++)
                if (extractor.getTrackFormat(i).getString("mime").startsWith("video/")) track = i;
            assertTrue(track >= 0); extractor.selectTrack(track);
            int target = muxer.addTrack(extractor.getTrackFormat(track)); muxer.start();
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(1024 * 1024);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            for (int repeat = 0; repeat < repeats; repeat++) {
                extractor.seekTo(0, android.media.MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                int size;
                while (extractor.getSampleTime() < limitUs && (size = extractor.readSampleData(buffer, 0)) >= 0) {
                    info.set(0, size, extractor.getSampleTime() + repeat * limitUs, extractor.getSampleFlags());
                    muxer.writeSampleData(target, buffer, info); extractor.advance();
                }
            }
            muxer.stop();
        } finally { extractor.release(); muxer.release(); }
        return output;
    }

    private void launch() {
        // Screenshot checks exercise touch UI, not the root's device-wide keyboard focus highlight.
        instrumentation.setInTouchMode(true);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        instrumentation.waitForIdleSync();
    }

    private Object field(String name) throws Exception {
        Field field = MainActivity.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(activity);
    }

    private PublishedVideo currentOutput() {
        PublishedVideo[] result = new PublishedVideo[1];
        instrumentation.runOnMainSync(() -> {
            try { result[0] = (PublishedVideo) field("lastVideo"); }
            catch (Exception error) { throw new AssertionError(error); }
        });
        return result[0];
    }

    private PublishedVideo awaitNewOutput(PublishedVideo previous) {
        long deadline = SystemClock.uptimeMillis() + 210000;
        while (SystemClock.uptimeMillis() < deadline) {
            PublishedVideo[] result = new PublishedVideo[1];
            // Read after the entire publication callback, never between clearing busy
            // state and updating the dock. An old recovered result is not completion.
            instrumentation.runOnMainSync(() -> {
                try {
                    String error = ((TextView) activity.findViewById(R.id.tvErrorDetails)).getText().toString();
                    assertEquals("Actual production export must not fail", "", error);
                    PublishedVideo current = (PublishedVideo) field("lastVideo");
                    if (!(Boolean) field("exporting") && !(Boolean) field("publishing")
                            && current != null && current != previous
                            && activity.findViewById(R.id.layoutSuccessContainer).isShown()) {
                        if (previous != null) assertFalse("Export must have a new private file",
                                current.privateFile.equals(previous.privateFile));
                        result[0] = current;
                    }
                } catch (Exception error) { throw new AssertionError(error); }
            });
            if (result[0] != null) return result[0];
            SystemClock.sleep(200);
        }
        fail("Actual export did not publish a new result within 210 seconds");
        return null;
    }

    private void edit(int id, String value) {
        reveal(id);
        instrumentation.runOnMainSync(() -> {
            EditText input = activity.findViewById(id);
            assertTrue("Edit field must be enabled", input.isEnabled());
            input.setText(value);
        });
        instrumentation.waitForIdleSync();
    }

    private String label() {
        return ((TextView) activity.findViewById(R.id.tvGeometryStatus)).getText().toString();
    }

    private void assertInvalidEdit(int reason) {
        assertEquals(activity.getString(R.string.editor_invalid_edit, activity.getString(reason)), label());
    }

    private void assertPresetRejected(String rawReason) {
        reveal(R.id.tvErrorDetails);
        assertEquals(activity.getString(R.string.editor_preset_apply_failed),
                ((TextView) activity.findViewById(R.id.tvErrorDetails)).getText().toString());
        reveal(R.id.tvRawError);
        assertTrue("Original rejection reason must remain available",
                ((TextView) activity.findViewById(R.id.tvRawError)).getText().toString().contains(rawReason));
    }

    private void waitFrame(double seconds, int rotation, int width, int height, boolean png, long timeout) {
        String expected = activity.getString(R.string.editor_preview_frame, seconds, rotation, width, height,
                png ? activity.getString(R.string.editor_preview_png) : "");
        long deadline = SystemClock.uptimeMillis() + timeout;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            if (label().equals(expected)) return;
            SystemClock.sleep(50);
        }
        fail("Preview timeout: " + label() + "; "
                + ((TextView) activity.findViewById(R.id.tvErrorDetails)).getText());
    }

    private Bitmap shown() {
        return ((BitmapDrawable) ((ImageView) activity.findViewById(R.id.ivVideoThumbnail)).getDrawable()).getBitmap();
    }

    private void reveal(int id) {
        int[] panels = {R.id.panelPicture, R.id.panelSound, R.id.panelTitle,
                R.id.panelAssets, R.id.panelMore};
        ScrollView[] owner = new ScrollView[1];
        instrumentation.runOnMainSync(() -> {
            View view = activity.findViewById(id);
            assertNotNull("Editor control must exist", view);
            View focused = activity.getCurrentFocus();
            if (focused != null && focused != view) focused.clearFocus();
            TabLayout tabs = activity.findViewById(R.id.editorTabs);
            assertNotNull(tabs);
            assertEquals("Five peer editor tabs", panels.length, tabs.getTabCount());
            for (int i = 0; i < panels.length; i++) {
                ScrollView panel = activity.findViewById(panels[i]);
                assertNotNull(panel);
                if (panel.findViewById(id) == view) {
                    assertNull("Control belongs to only one peer panel", owner[0]);
                    owner[0] = panel;
                    TabLayout.Tab tab = tabs.getTabAt(i);
                    assertNotNull(tab);
                    if (!tab.isSelected()) tab.select();
                    assertEquals(i, tabs.getSelectedTabPosition());
                }
            }
            if (owner[0] == null) {
                View header = activity.findViewById(R.id.editorPreviewHeader);
                View dock = activity.findViewById(R.id.editorActionDock);
                assertNotNull(header);
                assertNotNull(dock);
                assertTrue("Control must belong to the fixed preview header or action dock",
                        header.findViewById(id) == view || dock.findViewById(id) == view);
            }
        });
        instrumentation.waitForIdleSync();
        instrumentation.runOnMainSync(() -> {
            View view = activity.findViewById(id);
            if (owner[0] != null) {
                ScrollView panel = owner[0];
                assertTrue("Selected peer panel must be shown", panel.isShown());
                Rect rect = new Rect();
                view.getDrawingRect(rect);
                panel.offsetDescendantRectToMyCoords(view, rect);
                // A direct scroll leaves an earlier focus/gesture animation running.
                int position = Math.max(0, rect.centerY() - panel.getHeight() / 2);
                panel.smoothScrollTo(0, position);
                panel.smoothScrollTo(0, position);
            }
        });
        instrumentation.waitForIdleSync();
        long deadline = SystemClock.uptimeMillis() + 3000;
        Rect previous = new Rect();
        int stable = 0;
        while (stable < 3 && SystemClock.uptimeMillis() < deadline) {
            Rect current = new Rect();
            instrumentation.runOnMainSync(() -> activity.findViewById(id).getGlobalVisibleRect(current));
            stable = !current.isEmpty() && current.equals(previous) ? stable + 1 : 0;
            previous.set(current);
            SystemClock.sleep(50);
            instrumentation.waitForIdleSync();
        }
        assertEquals("Control scroll must settle before interaction", 3, stable);
        instrumentation.runOnMainSync(() -> {
            View view = activity.findViewById(id);
            assertTrue("Editor control must be shown", view.isShown());
            assertTrue("Editor control must have visible bounds: "
                    + activity.getResources().getResourceEntryName(id),
                    view.getGlobalVisibleRect(new Rect()));
            assertTrue("Preview header must remain fixed and visible",
                    activity.findViewById(R.id.editorPreviewHeader).getGlobalVisibleRect(new Rect()));
            assertTrue("Action dock must remain fixed and visible",
                    activity.findViewById(R.id.editorActionDock).getGlobalVisibleRect(new Rect()));
        });
    }

    private void tap(int id) {
        reveal(id);
        View target = activity.findViewById(id);
        Rect rect = new Rect();
        assertTrue("UI target must be visible: " + context.getResources().getResourceEntryName(id),
                target.getLocalVisibleRect(rect));
        int[] screen = new int[2];
        target.getLocationOnScreen(screen);
        rect.offset(screen[0], screen[1]);
        java.util.List<AccessibilityNodeInfo> nodes = activeRoot().findAccessibilityNodeInfosByViewId(
                context.getResources().getResourceName(id));
        assertEquals("Visible editor accessibility control", 1, nodes.size());
        assertTrue("Enabled editor control", nodes.get(0).isEnabled());
        assertTrue("Real accessibility click", nodes.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK));
        instrumentation.waitForIdleSync();
        SystemClock.sleep(200);
    }

    private void touch(Rect rect) {
        long downTime = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY(), 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(down, true));
        down.recycle();
        SystemClock.sleep(50);
        MotionEvent up = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        assertTrue(instrumentation.getUiAutomation().injectInputEvent(up, true));
        up.recycle();
        instrumentation.waitForIdleSync();
        SystemClock.sleep(150);
    }

    private void key(int code) {
        if (code == KeyEvent.KEYCODE_BACK) {
            assertTrue(instrumentation.getUiAutomation().performGlobalAction(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
            return;
        }
        long now = SystemClock.uptimeMillis();
        for (int action : new int[]{KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(new KeyEvent(now,
                    SystemClock.uptimeMillis(), action, code, 0, 0,
                    android.view.KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                    android.view.InputDevice.SOURCE_KEYBOARD), true));
        }
    }

    private AccessibilityNodeInfo findPickerControl(String id) {
        AccessibilityNodeInfo root = activeRoot();
        java.util.List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(
                root.getPackageName() + ":id/" + id);
        AccessibilityNodeInfo visible = null;
        for (AccessibilityNodeInfo node : nodes) {
            if (node.isVisibleToUser()) {
                assertNull("Picker action must be unique: " + id, visible);
                visible = node;
            }
        }
        return visible;
    }

    private AccessibilityNodeInfo activeRoot() {
        long deadline = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null && root.getChildCount() > 0) return root;
            SystemClock.sleep(100);
        }
        fail("No active accessibility hierarchy");
        return null;
    }

    private void clickNode(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo clickable = node;
        // DocumentsUI image tiles expose a delegated ACTION_CLICK even with isClickable=false.
        while (clickable != null && !clickable.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)) clickable = clickable.getParent();
        if (clickable != null) {
            assertTrue("System picker item disabled", clickable.isEnabled());
            assertTrue("System picker click rejected", clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK));
            instrumentation.waitForIdleSync();
            return;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        touch(rect);
    }

    private void capture(String name) throws Exception {
        AccessibilityNodeInfo root = activeRoot();
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name + ".tree.txt"))) {
            output.write(tree(root, new StringBuilder()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull(screenshot);
        saveBitmap(name + ".png", screenshot);
        screenshot.recycle();
    }

    private StringBuilder tree(AccessibilityNodeInfo node, StringBuilder out) {
        if (node == null) return out;
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        out.append(node.getPackageName()).append(' ').append(node.getViewIdResourceName()).append(' ')
                .append(node.getText()).append(' ').append(node.getContentDescription()).append(' ').append(rect)
                .append(" clickable=").append(node.isClickable()).append(" actions=").append(node.getActionList()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) tree(node.getChild(i), out);
        return out;
    }

    private void saveBitmap(String name, Bitmap bitmap) throws Exception {
        try (FileOutputStream output = new FileOutputStream(new File(evidence, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        }
    }

    private void assertScreenshotPixels(Bitmap preview) {
        ImageView view = activity.findViewById(R.id.ivVideoThumbnail);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        int[] origin = new int[2];
        view.getLocationOnScreen(origin);
        for (float fraction : new float[]{.25f, .5f, .75f}) {
            int x = (int) (preview.getWidth() * fraction), y = preview.getHeight() * 2 / 5;
            float[] point = {(x + .5f) * view.getDrawable().getIntrinsicWidth() / preview.getWidth(),
                    (y + .5f) * view.getDrawable().getIntrinsicHeight() / preview.getHeight()};
            view.getImageMatrix().mapPoints(point);
            int actual = screenshot.getPixel(origin[0] + view.getPaddingLeft() + Math.round(point[0]),
                    origin[1] + view.getPaddingTop() + Math.round(point[1]));
            int expected = preview.getPixel(x, y);
            assertTrue("rendered screenshot must contain selected preview pixels: "
                    + Integer.toHexString(actual) + " vs " + Integer.toHexString(expected),
                    colorError(actual, expected) <= 18);
        }
        screenshot.recycle();
    }

    private static Bitmap frame(File file, long timeUs) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            Bitmap result = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST);
            assertNotNull(result);
            return result;
        } finally { retriever.release(); }
    }

    private static Bitmap clockwiseCrop(Bitmap input) {
        Bitmap result = Bitmap.createBitmap(180, 240, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 240; y++) for (int x = 0; x < 180; x++) {
            result.setPixel(x, y, input.getPixel(40 + y, 30 + 179 - x));
        }
        return result;
    }

    private static int colorError(int a, int b) {
        return Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b))
                + Math.abs(Color.blue(a) - Color.blue(b));
    }

    private static double mae(Bitmap a, Bitmap b) {
        assertEquals(b.getWidth(), a.getWidth());
        assertEquals(b.getHeight(), a.getHeight());
        long error = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++) {
            error += colorError(a.getPixel(x, y), b.getPixel(x, y));
        }
        return (double) error / (a.getWidth() * a.getHeight() * 3);
    }

    private static String hash(InputStream input) throws Exception {
        try (InputStream stream = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n);
            StringBuilder result = new StringBuilder();
            for (byte value : digest.digest()) result.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return result.toString();
        }
    }
}
