package com.simple.videoeditor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.tabs.TabLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

@androidx.media3.common.util.UnstableApi
public final class ProcessingStatusTest extends InstrumentationTestCase {
    private Instrumentation instrumentation;
    private Context context;
    private MainActivity activity;
    private File evidence, source;
    private boolean originalEnglish;

    @Override protected void setUp() throws Exception {
        super.setUp();
        instrumentation = getInstrumentation();
        context = instrumentation.getTargetContext();
        originalEnglish = UiLocales.isEnglish(context);
    }

    @Override protected void runTest() throws Throwable {
        try {
            super.runTest();
        } catch (Throwable failure) {
            if (activity != null && evidence != null) {
                try { capture("failed"); }
                catch (Throwable captureFailure) { failure.addSuppressed(captureFailure); }
            }
            throw failure;
        }
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (activity != null && !activity.isDestroyed()) {
                ui(() -> {
                    if (flag("exporting") || flag("loading")) {
                        view(R.id.btnCancelExport).performClick();
                    }
                });
                await("cleanup idle", 30000, () -> !flag("exporting")
                        && !flag("publishing") && !flag("loading"));
                setLanguage(originalEnglish);
            }
        } finally {
            try {
                if (activity != null) ui(() -> activity.finish());
                if (source != null && source.exists()) assertTrue(source.delete());
            } finally {
                super.tearDown();
            }
        }
    }

    public void testChineseProcessingResources() {
        assertResources(false);
    }

    public void testEnglishProcessingResources() {
        assertResources(true);
    }

    public void testChineseLiveExportScreenshotCancelAndRetry() throws Exception {
        liveExport(false);
    }

    public void testEnglishLiveExportScreenshotCancelAndRetry() throws Exception {
        liveExport(true);
    }

    private void assertResources(boolean english) {
        Configuration configuration = new Configuration(context.getResources().getConfiguration());
        configuration.setLocale(english ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE);
        Context localized = context.createConfigurationContext(configuration);
        assertEquals(starting(english), localized.getString(R.string.editor_export_starting));
        for (int percent : new int[]{0, 1, 42, 100}) {
            assertEquals(progress(english, percent),
                    localized.getString(R.string.editor_export_progress, percent));
        }
        assertEquals(loading(english, false), localized.getString(R.string.editor_loading_media));
        assertEquals(loading(english, true), localized.getString(R.string.editor_loading_clips));
    }

    private void liveExport(boolean english) throws Exception {
        assertTrue("Public export workflow requires API 29+ (MediaStore)", Build.VERSION.SDK_INT >= 29);
        evidence = new File(context.getFilesDir(), "processing-status-evidence/"
                + getName() + "-" + UUID.randomUUID());
        assertTrue(evidence.mkdirs());
        Bundle location = new Bundle();
        location.putString("processing_status_evidence", evidence.getAbsolutePath());
        instrumentation.sendStatus(2, location);
        instrumentation.setInTouchMode(true);
        activity = (MainActivity) instrumentation.startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        awaitReady();
        setLanguage(english);
        assertEquals(english ? "en" : "zh", read(() ->
                activity.getResources().getConfiguration().locale.getLanguage()));
        source = new File(evidence, "input.mp4");
        try (InputStream in = instrumentation.getContext().getAssets().open("real-music/source160.mp4");
             FileOutputStream out = new FileOutputStream(source)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
        }
        // Reuse FixedEditorUiTest's real import callback/worker route, not a selected-video fixture.
        ui(() -> {
            activity.onActivityResult((Integer) field(activity, "VIDEO_PICK_CODE"), Activity.RESULT_OK,
                    new Intent().setData(Uri.fromFile(source)));
            assertTrue(flag("loading"));
            assertEquals(loading(english, false), text(R.id.tvProgress));
        });
        await("real media import", 60000, () -> !flag("loading") && !flag("restoringAssets"));
        ui(() -> {
            assertNoError();
            assertNotNull(field(activity, "selectedMainVideo"));
            ((TabLayout) view(R.id.editorTabs)).getTabAt(0).select();
            // A long, genuinely transformed clip leaves time to observe and capture live progress.
            ((Spinner) view(R.id.spinnerSpeed)).setSelection(0);
            assertTrue(view(R.id.btnRotateRight).performClick());
        });
        PublishedVideo previous = read(() -> (PublishedVideo) field(activity, "lastVideo"));
        File cancelledOutput = read(() -> {
            click(R.id.btnProcess);
            assertTrue(flag("exporting"));
            assertTrue(engine().isRunning());
            assertEquals(starting(english), text(R.id.tvProgress));
            return (File) field(field(engine(), "activeJob"), "output");
        });
        await("nonzero real export progress", 90000, () -> {
            assertNoError();
            assertTrue("export must remain active until manual cancel", flag("exporting"));
            assertFalse("must capture transformation, not publication", flag("publishing"));
            return ((ProgressBar) view(R.id.progressBar)).getProgress() > 0;
        });
        ui(() -> assertLiveProgress(english));
        capture("processing");
        ui(() -> {
            assertLiveProgress(english);
            click(R.id.btnCancelExport);
        });
        await("manual export cancellation", 30000, () -> !flag("exporting") && !flag("publishing"));
        ui(() -> {
            assertFalse(engine().isRunning());
            assertSame("cancellation retains previous result", previous, field(activity, "lastVideo"));
            assertTrue(view(R.id.btnProcess).isEnabled());
            assertEquals(View.GONE, view(R.id.btnCancelExport).getVisibility());
            assertEquals(View.GONE, view(R.id.tvProgress).getVisibility());
            assertEquals(View.VISIBLE, view(R.id.svErrorContainer).getVisibility());
            assertTrue("must be an explicit cancellation, not another export failure",
                    text(R.id.tvRawError).contains("CancellationException"));
        });
        assertFalse("cancel removes the actual reserved output", cancelledOutput.exists());
        capture("cancelled");

        ui(() -> {
            ((CheckBox) view(R.id.cbEnableTrim)).setChecked(true);
            ((EditText) view(R.id.etTrimStart)).setText("0");
            ((EditText) view(R.id.etTrimEnd)).setText("2");
            ((Spinner) view(R.id.spinnerSpeed)).setSelection(2);
            click(R.id.btnProcess);
        });
        await("new export after cancel", 210000, () -> {
            assertNoError();
            PublishedVideo current = (PublishedVideo) field(activity, "lastVideo");
            return !flag("exporting") && !flag("publishing") && current != null && current != previous
                    && view(R.id.layoutSuccessContainer).isShown();
        });
        PublishedVideo result = read(() -> (PublishedVideo) field(activity, "lastVideo"));
        assertNotNull("retry must publish, not merely retain a private file", result.uri);
        assertTrue(result.privateFile.isFile() && result.privateFile.length() > 0);
        assertFalse(cancelledOutput.equals(result.privateFile));
        try (InputStream in = context.getContentResolver().openInputStream(result.uri)) {
            assertNotNull(in);
            assertTrue("published retry is readable", in.read() != -1);
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(result.privateFile.getAbsolutePath());
            Bitmap frame = retriever.getFrameAtTime(500000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            assertNotNull("retry contains a decodable video frame", frame);
            frame.recycle();
        } finally {
            retriever.release();
        }
        capture("retry-published");
    }

    private void assertLiveProgress(boolean english) throws Exception {
        assertTrue(flag("exporting"));
        assertFalse(flag("publishing"));
        assertTrue(engine().isRunning());
        int percent = ((ProgressBar) view(R.id.progressBar)).getProgress();
        assertTrue("actual nonterminal percentage", percent > 0 && percent < 100);
        String expected = progress(english, percent);
        String actual = text(R.id.tvProgress);
        assertTrue("localized live status: " + actual,
                actual.equals(expected) || actual.startsWith(expected + " · "));
        assertTrue(view(R.id.tvProgress).isShown());
        assertTrue(view(R.id.tvProgress).getGlobalVisibleRect(new Rect()));
        assertTrue(view(R.id.btnCancelExport).isShown());
        assertTrue(view(R.id.btnCancelExport).isEnabled());
        assertTrue(view(R.id.btnCancelExport).getGlobalVisibleRect(new Rect()));
    }

    private void setLanguage(boolean english) throws Exception {
        if (UiLocales.isEnglish(context) == english) return;
        Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(
                MainActivity.class.getName(), null, false);
        try {
            ui(() -> {
                ((TabLayout) view(R.id.editorTabs)).getTabAt(4).select();
                click(R.id.btnLanguage);
            });
            Activity recreated = monitor.waitForActivityWithTimeout(15000);
            assertNotNull("language switch recreates the real editor", recreated);
            activity = (MainActivity) recreated;
            awaitReady();
            assertEquals(english, UiLocales.isEnglish(context));
        } finally {
            instrumentation.removeMonitor(monitor);
        }
    }

    private void awaitReady() throws Exception {
        await("editor ready", 60000, () -> !flag("restoringUi") && !flag("restoringAssets")
                && !flag("loading") && activity.hasWindowFocus() && view(R.id.editorRoot).getHeight() > 0);
        read(() -> (ExecutorService) field(activity, "publicationWorker"))
                .submit(() -> {}).get(20, TimeUnit.SECONDS);
        instrumentation.waitForIdleSync();
    }

    private void capture(String name) throws Exception {
        JSONObject before = read(this::observedState);
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("real screen capture", screenshot);
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name + ".png"))) {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, out));
        } finally {
            screenshot.recycle();
        }
        JSONObject after = read(this::observedState);
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name + ".json"))) {
            out.write(new JSONObject().put("beforeScreenshot", before).put("afterScreenshot", after)
                    .toString(2).getBytes(StandardCharsets.UTF_8));
        }
        if ("processing".equals(name)) {
            assertTrue("processing before screenshot", before.getBoolean("engineRunning"));
            assertTrue("processing after screenshot", after.getBoolean("engineRunning"));
            assertFalse(after.getBoolean("publishing"));
            assertTrue("processing explanation is not ellipsized", after.getBoolean("statusCoreVisible"));
        }
    }

    private JSONObject observedState() throws Exception {
        PublishedVideo result = (PublishedVideo) field(activity, "lastVideo");
        return new JSONObject().put("test", getName())
                .put("locale", activity.getResources().getConfiguration().locale.toLanguageTag())
                .put("progressText", text(R.id.tvProgress))
                .put("progress", ((ProgressBar) view(R.id.progressBar)).getProgress())
                .put("exporting", flag("exporting")).put("publishing", flag("publishing"))
                .put("engineRunning", engine().isRunning())
                .put("cancelVisible", view(R.id.btnCancelExport).isShown())
                .put("cancelEnabled", view(R.id.btnCancelExport).isEnabled())
                .put("statusCoreVisible", statusCoreVisible())
                .put("error", text(R.id.tvRawError))
                .put("publishedUri", result == null ? JSONObject.NULL : String.valueOf(result.uri));
    }

    private boolean statusCoreVisible() {
        TextView status = (TextView) view(R.id.tvProgress);
        android.text.Layout layout = status.getLayout();
        if (layout == null || layout.getLineCount() == 0) return false;
        String text = status.getText().toString();
        int elapsed = text.indexOf(" · ");
        int required = elapsed < 0 ? text.length() : elapsed;
        int last = Math.min(status.getMaxLines(), layout.getLineCount()) - 1;
        int visibleEnd = layout.getEllipsisCount(last) == 0 ? layout.getLineEnd(last)
                : layout.getLineStart(last) + layout.getEllipsisStart(last);
        return visibleEnd >= required;
    }

    private static String starting(boolean english) {
        return english ? "Starting export — no in-app processing time limit; cancel manually"
                : "正在开始导出 — 无应用内处理时限，可手动取消";
    }

    private static String progress(boolean english, int percent) {
        return english ? "Export " + percent + "% — no in-app processing time limit; cancel manually"
                : "导出 " + percent + "% — 无应用内处理时限，可手动取消";
    }

    private static String loading(boolean english, boolean appended) {
        return english ? "Copying and checking " + (appended ? "appended clips" : "media")
                + " (no in-app processing time limit; cancel manually)…"
                : "正在复制并检查" + (appended ? "追加片段" : "素材") + "（无应用内处理时限，可手动取消）…";
    }

    private void assertNoError() {
        assertEquals("real workflow must not fail", "", text(R.id.tvErrorDetails));
    }

    private void click(int id) {
        assertTrue("button enabled: " + id, view(id).isEnabled());
        assertTrue("button shown: " + id, view(id).isShown());
        assertTrue("button handled: " + id, view(id).performClick());
    }

    private View view(int id) { return activity.findViewById(id); }
    private String text(int id) { return ((TextView) view(id)).getText().toString(); }
    private boolean flag(String name) throws Exception { return (Boolean) field(activity, name); }
    private Media3ExportEngine engine() throws Exception {
        return (Media3ExportEngine) field(activity, "exportEngine");
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private interface Action { void run() throws Exception; }
    private void ui(Action action) throws Exception {
        read(() -> { action.run(); return null; });
    }

    private <T> T read(Callable<T> action) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> {
            try { value.set(action.call()); }
            catch (Throwable failure) { error.set(failure); }
        });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw (Exception) error.get();
        return value.get();
    }

    private void await(String message, long timeoutMs, Callable<Boolean> condition) throws Exception {
        // Test deadlines only: never change production timers, job clocks or progress callbacks.
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        do {
            if (read(condition)) return;
            SystemClock.sleep(50);
        } while (SystemClock.uptimeMillis() < deadline);
        fail(message + ": " + read(() -> text(R.id.tvProgress) + "; " + text(R.id.tvRawError)));
    }
}
