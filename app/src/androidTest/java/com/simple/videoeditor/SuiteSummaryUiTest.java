package com.simple.videoeditor;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@androidx.media3.common.util.UnstableApi
public final class SuiteSummaryUiTest extends InstrumentationTestCase {
    private static final int GREEN = Color.rgb(20, 92, 40);
    private static final int GREEN_BG = Color.rgb(229, 245, 233);
    private static final int RED = Color.rgb(153, 27, 27);
    private static final int RED_BG = Color.rgb(255, 239, 233);

    private Instrumentation instrumentation;
    private Context targetContext;
    private File filesDir;
    private File selftestRoot;
    private File evidenceRoot;
    private File backupRoot;
    private boolean hadSelftestRoot;
    private SharedPreferences prefs;
    private Map<String, ?> savedPrefs;
    private final List<Activity> launchedActivities = new ArrayList<>();

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        instrumentation = getInstrumentation();
        targetContext = instrumentation.getTargetContext();
        filesDir = targetContext.getFilesDir();
        selftestRoot = new File(filesDir, "selftest");
        evidenceRoot = new File(filesDir, "selftest-ui-evidence");
        if (!evidenceRoot.isDirectory()) assertTrue(evidenceRoot.mkdirs());
        backupRoot = new File(filesDir, "suite-summary-ui-backup-" + UUID.randomUUID());
        assertTrue(backupRoot.mkdirs());
        prefs = SavedReport.preferences(targetContext);
        savedPrefs = prefs.getAll();
        hadSelftestRoot = selftestRoot.exists();
        if (hadSelftestRoot) copyRecursively(selftestRoot, new File(backupRoot, "selftest"));
        deleteRecursively(selftestRoot);
        assertTrue(prefs.edit().clear().commit());
    }

    @Override
    protected void tearDown() throws Exception {
        for (int i = launchedActivities.size() - 1; i >= 0; i--) {
            Activity activity = launchedActivities.get(i);
            if (activity != null && !activity.isFinishing()) {
                finishActivity(activity);
            }
        }
        assertTrue(prefs.edit().clear().commit());
        restorePreferences(savedPrefs);
        deleteRecursively(selftestRoot);
        if (hadSelftestRoot) copyRecursively(new File(backupRoot, "selftest"), selftestRoot);
        deleteRecursively(backupRoot);
        super.tearDown();
    }

    public void testFullPassRestoreRelaunchesGreenSummaryAfterRealPreviewButtons() throws Exception {
        prepareWorkspace();
        Fixture fixture = createFixture("fullpass", SelfTestPlan.Mode.FULL, "PASS", true,
                true, false, false, false, true, 2);
        SelfTestActivity activity = launchActivity();
        assertAllPassUi(activity, "fullpass-launch", fixture.runId,
                activity.getString(R.string.secondary_all_pass),
                activity.getString(R.string.secondary_result_mode, "FULL"),
                activity.getString(R.string.secondary_export_counts, 17, 17, 0, 0, 0),
                activity.getString(R.string.secondary_control_counts, 60, 60, 0, 0, 0));
        tapLastPreviewAndReturn(activity, "fullpass-launch");
        finishActivity(activity);
        SelfTestActivity relaunched = launchActivity();
        assertAllPassUi(relaunched, "fullpass-relaunch", fixture.runId,
                relaunched.getString(R.string.secondary_all_pass),
                relaunched.getString(R.string.secondary_result_mode, "FULL"),
                relaunched.getString(R.string.secondary_export_counts, 17, 17, 0, 0, 0),
                relaunched.getString(R.string.secondary_control_counts, 60, 60, 0, 0, 0));
        tapLastPreviewAndReturn(relaunched, "fullpass-relaunch");
    }

    public void testShortPassRestoreRecreatesGreenSubsetSummary() throws Exception {
        prepareWorkspace();
        Fixture fixture = createFixture("shortpass", SelfTestPlan.Mode.SHORT_DIAGNOSTIC,
                "SUBSET_PASS", true, true, false, false, false, true, 1);
        SelfTestActivity activity = launchActivity();
        assertAllPassUi(activity, "shortpass-launch", fixture.runId,
                activity.getString(R.string.secondary_subset_pass),
                activity.getString(R.string.secondary_result_mode, "SHORT_DIAGNOSTIC"),
                activity.getString(R.string.secondary_export_counts, 3, 3, 0, 0, 0),
                activity.getString(R.string.secondary_omitted_counts, 14, 50));
        tapLastPreviewAndReturn(activity, "shortpass-launch");
        SelfTestActivity recreated = recreateActivity(activity);
        assertAllPassUi(recreated, "shortpass-recreate", fixture.runId,
                recreated.getString(R.string.secondary_subset_pass),
                recreated.getString(R.string.secondary_result_mode, "SHORT_DIAGNOSTIC"),
                recreated.getString(R.string.secondary_export_counts, 3, 3, 0, 0, 0),
                recreated.getString(R.string.secondary_omitted_counts, 14, 50));
        tapLastPreviewAndReturn(recreated, "shortpass-recreate");
    }

    public void testFailRestoreShowsPreviewFilesWithoutGreenVerdict() throws Exception {
        prepareWorkspace();
        Fixture fixture = createFixture("fail", SelfTestPlan.Mode.FULL, "FAIL", true,
                true, false, false, false, true, 1);
        for (int i = 0; i < fixture.exports.length(); i++) {
            if (!"resize".equals(fixture.exports.getJSONObject(i).getString("id"))) {
                fixture.exports.getJSONObject(i).put("status", "FAIL").put("actual_status", "FAIL");
            }
        }
        fixture.reportText += "Samsung terminal-count fixture: 1/17 exports PASS (resize), "
                + "16 FAIL, 0 ERROR; 60/60 controls PASS. Not a physical-phone rerun.\n";
        writeFixture(fixture);
        SelfTestActivity activity = launchActivity();
        assertFailureUi(activity, "fail-launch", fixture.runId,
                activity.getString(R.string.secondary_export_counts, 1, 17, 16, 0, 0),
                "存在明确失败项。");
        tapLastPreviewAndReturn(activity, "fail-launch");
        finishActivity(activity);
        SelfTestActivity relaunched = launchActivity();
        assertFailureUi(relaunched, "fail-relaunch", fixture.runId,
                relaunched.getString(R.string.secondary_export_counts, 1, 17, 16, 0, 0),
                relaunched.getString(R.string.secondary_control_counts, 60, 60, 0, 0, 0));
        ensureSummaryVisible(relaunched, "fail-relaunch");
        captureEvidence("fail-relaunch");
    }

    public void testStorageErrorRestoreShowsFailureSummary() throws Exception {
        prepareWorkspace();
        Fixture fixture = createFixture("storage", SelfTestPlan.Mode.FULL, "PASS", true,
                true, false, false, false, false, 1);
        SelfTestActivity activity = launchActivity();
        assertFailureUi(activity, "storage-launch", fixture.runId,
                "报告或元数据保存失败；结果不具权威性。",
                activity.getString(R.string.secondary_guidance_storage));
        tapLastPreviewAndReturn(activity, "storage-launch");
    }

    public void testMissingRestoreShowsNoArtifactsAndNoSuccessColor() throws Exception {
        prepareWorkspace();
        SelfTestActivity activity = launchActivity();
        waitForRestore(activity);
        assertEquals(activity.getString(R.string.secondary_not_passed),
                status(activity).getText().toString());
        assertEquals(0, previewButtons(activity, outputs(activity)).size());
        verifySummaryCardLayout(activity);
        ensureSummaryVisible(activity, "missing-launch");
        String text = summary(activity).getText().toString();
        assertTrue(text.contains(activity.getString(R.string.secondary_result_mode, "UNKNOWN")));
        assertTrue(text.contains("未找到任何自测运行目录"));
        assertEquals(RED, summary(activity).getCurrentTextColor());
        assertEquals(RED_BG, ((ColorDrawable) summary(activity).getBackground()).getColor());
        captureEvidence("missing-launch");
    }

    public void testCopyCannotResurrectPreviousPassForExternalOrCancelledReport() throws Exception {
        prepareWorkspace();
        Fixture fixture = createFixture("copy-pass", SelfTestPlan.Mode.FULL, "PASS", true,
                true, false, false, false, true, 1);
        SelfTestActivity activity = launchActivity();
        assertAllPassUi(activity, "copy-pass", fixture.runId,
                activity.getString(R.string.secondary_all_pass));
        Field external = SelfTestActivity.class.getDeclaredField("viewingExternalReport");
        Field problem = SelfTestActivity.class.getDeclaredField("summaryProblem");
        java.lang.reflect.Method render = SelfTestActivity.class.getDeclaredMethod("renderSummary");
        java.lang.reflect.Method copy = SelfTestActivity.class.getDeclaredMethod("copyPreview");
        external.setAccessible(true);
        problem.setAccessible(true);
        render.setAccessible(true);
        copy.setAccessible(true);
        for (boolean viewingExternal : new boolean[]{true, false}) {
            instrumentation.runOnMainSync(() -> {
                try {
                    external.setBoolean(activity, viewingExternal);
                    problem.set(activity, viewingExternal ? null
                            : activity.getString(R.string.secondary_select_cancelled));
                    render.invoke(activity);
                    String before = summary(activity).getText().toString();
                    assertFalse(before.contains(activity.getString(R.string.secondary_all_pass)));
                    copy.invoke(activity);
                    assertFalse(status(activity).getText().toString()
                            .contains(activity.getString(R.string.secondary_all_pass)));
                    assertEquals(activity.getString(R.string.secondary_copied),
                            status(activity).getText().toString());
                    assertEquals(before, summary(activity).getText().toString());
                } catch (Exception error) {
                    throw new AssertionError(error);
                }
            });
        }
    }

    private void assertAllPassUi(SelfTestActivity activity, String evidenceName, String runId,
            String headline, String... fragments) throws Exception {
        waitForRestore(activity);
        TextView status = status(activity);
        TextView summary = summary(activity);
        LinearLayout outputs = outputs(activity);
        verifySummaryCardLayout(activity);
        assertEquals(headline, status.getText().toString());
        assertEquals(GREEN, status.getCurrentTextColor());
        assertEquals(GREEN, summary.getCurrentTextColor());
        assertEquals(GREEN_BG, ((ColorDrawable) summary.getBackground()).getColor());
        String summaryText = summary.getText().toString();
        assertEquals(activity.getString(R.string.secondary_summary, headline,
                SecondaryOutcomeUi.details(activity, ((SelfTestRunner) field(activity, "runner")).outcome())),
                summaryText);
        for (String fragment : fragments) assertTrue(summaryText.contains(fragment));
        assertTrue(report(activity).getText().toString().contains(runId));
        assertPreviewButtons(activity, outputs);
    }

    private void assertFailureUi(SelfTestActivity activity, String evidenceName, String runId,
            String detailA, String detailB) throws Exception {
        waitForRestore(activity);
        TextView status = status(activity);
        TextView summary = summary(activity);
        LinearLayout outputs = outputs(activity);
        verifySummaryCardLayout(activity);
        assertEquals(activity.getString(R.string.secondary_not_passed), status.getText().toString());
        assertEquals(RED, status.getCurrentTextColor());
        assertEquals(RED, summary.getCurrentTextColor());
        assertEquals(RED_BG, ((ColorDrawable) summary.getBackground()).getColor());
        String summaryText = summary.getText().toString();
        assertTrue(summaryText.contains(detailA));
        assertTrue(summaryText.contains(detailB));
        assertTrue(report(activity).getText().toString().contains(runId));
        assertPreviewButtons(activity, outputs);
    }

    private void assertPreviewButtons(SelfTestActivity activity, LinearLayout outputs) throws Exception {
        List<Button> previews = previewButtons(activity, outputs);
        List<File> files = ((SelfTestRunner) field(activity, "runner")).getOutputs();
        assertTrue("expected at least one real preview button", !previews.isEmpty());
        assertEquals(files.size(), previews.size());
        assertEquals(files.size() + 1, outputs.getChildCount());
        TextView label = (TextView) outputs.getChildAt(0);
        assertEquals(activity.getString(R.string.secondary_outputs_notice, files.size()),
                label.getText().toString());
        for (int i = 0; i < previews.size(); i++) {
            Button button = previews.get(i);
            assertEquals(activity.getString(R.string.secondary_preview_output, files.get(i).getName()),
                    button.getText().toString());
            assertTrue(button.isEnabled());
        }
    }

    private void verifySummaryCardLayout(SelfTestActivity activity) throws Exception {
        TextView summary = summary(activity);
        LinearLayout outputs = outputs(activity);
        assertEquals("terminal-suite-summary", summary.getTag());
        ViewParent parent = summary.getParent();
        assertTrue(parent instanceof LinearLayout);
        LinearLayout content = (LinearLayout) parent;
        assertSame(summary, content.getChildAt(content.getChildCount() - 1));
        assertTrue(content.indexOfChild(summary) > content.indexOfChild(outputs));
    }

    private void tapLastPreviewAndReturn(SelfTestActivity activity, String evidencePrefix) throws Exception {
        List<Button> previews = previewButtons(activity, outputs(activity));
        assertFalse("expected a real preview button to tap", previews.isEmpty());
        Button last = previews.get(previews.size() - 1);
        for (int i = 0; i < 30 && !isMostlyVisible(last); i++) {
            swipeUp(scroll(activity));
        }
        assertTrue("last Preview must be visible before the actual tap", isMostlyVisible(last));
        SystemClock.sleep(1000);
        instrumentation.waitForIdleSync();
        String beforeStatus = status(activity).getText().toString();
        String beforeReport = report(activity).getText().toString();
        tap(last);
        long deadline = SystemClock.uptimeMillis() + 5000;
        String packageName;
        do {
            SystemClock.sleep(200);
            instrumentation.waitForIdleSync();
            packageName = activeWindowPackage();
        } while ((packageName == null || targetContext.getPackageName().equals(packageName))
                && !report(activity).getText().toString().contains("PREVIEW FAILED")
                && SystemClock.uptimeMillis() < deadline);
        if (packageName != null && !targetContext.getPackageName().equals(packageName)) {
            captureEvidence(evidencePrefix + "-preview");
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            waitForPackage(targetContext.getPackageName(), 5000);
            long returnDeadline = SystemClock.uptimeMillis() + 5000;
            while ((!activity.hasWindowFocus() || !isMostlyVisible(scroll(activity)))
                    && SystemClock.uptimeMillis() < returnDeadline) {
                instrumentation.waitForIdleSync();
                SystemClock.sleep(50);
            }
            assertTrue("preview return must restore the actual activity window",
                    activity.hasWindowFocus() && isMostlyVisible(scroll(activity)));
            ensureSummaryVisible(activity, evidencePrefix + "-return");
            captureEvidence(evidencePrefix + "-return");
            return;
        }
        String afterStatus = status(activity).getText().toString();
        String reportText = report(activity).getText().toString();
        captureEvidence(evidencePrefix + "-tap-diagnostic");
        assertTrue(reportText.contains("PREVIEW FAILED")
                || !afterStatus.equals(beforeStatus)
                || !reportText.equals(beforeReport));
        ensureSummaryVisible(activity, evidencePrefix + "-no-handler");
        captureEvidence(evidencePrefix + "-no-handler");
    }

    private SelfTestActivity recreateActivity(SelfTestActivity activity) throws Exception {
        Instrumentation.ActivityMonitor monitor =
                instrumentation.addMonitor(SelfTestActivity.class.getName(), null, false);
        instrumentation.runOnMainSync(activity::recreate);
        Activity candidate = monitor.waitForActivityWithTimeout(5000);
        instrumentation.removeMonitor(monitor);
        assertNotNull("recreate did not deliver a new activity", candidate);
        launchedActivities.add(candidate);
        waitForRestore((SelfTestActivity) candidate);
        return (SelfTestActivity) candidate;
    }

    private SelfTestActivity launchActivity() throws Exception {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClass(targetContext, SelfTestActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        Activity activity = instrumentation.startActivitySync(intent);
        instrumentation.waitForIdleSync();
        launchedActivities.add(activity);
        return (SelfTestActivity) activity;
    }

    private void finishActivity(Activity activity) throws Exception {
        instrumentation.runOnMainSync(activity::finish);
        instrumentation.waitForIdleSync();
        SystemClock.sleep(250);
    }

    private void waitForRestore(SelfTestActivity activity) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 5000L;
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync();
            if (!(Boolean) field(activity, "restoring")) {
                assertNotNull(field(activity, "runner"));
                return;
            }
            SystemClock.sleep(50);
        }
        fail("restore timed out");
    }

    private void waitForPackage(String expected, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        while (SystemClock.uptimeMillis() < deadline) {
            if (expected.equals(activeWindowPackage())) return;
            SystemClock.sleep(100);
        }
        fail("window never returned to " + expected);
    }

    private void ensureSummaryVisible(SelfTestActivity activity, String evidenceName) throws Exception {
        TextView summary = summary(activity);
        for (int i = 0; i < 30; i++) {
            if (!scroll(activity).canScrollVertically(1) && isMostlyVisible(summary)) return;
            swipeUp(scroll(activity));
        }
        captureEvidence(evidenceName + "-not-visible");
        fail("summary card never became visible after real swipes");
    }

    private boolean isMostlyVisible(View view) {
        Rect rect = new Rect();
        if (!view.getGlobalVisibleRect(rect)) return false;
        return rect.height() >= Math.max(1, view.getHeight() / 2);
    }

    private void tap(View view) {
        Rect rect = globalRect(view);
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN,
                rect.centerX(), rect.centerY(), 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(event);
        event.recycle();
        MotionEvent up = MotionEvent.obtain(down, SystemClock.uptimeMillis() + 60,
                MotionEvent.ACTION_UP, rect.centerX(), rect.centerY(), 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(up);
        up.recycle();
    }

    private void swipeUp(ScrollView scroll) {
        Rect rect = globalRect(scroll);
        float x = rect.centerX();
        float startY = rect.bottom - rect.height() * 0.20f;
        float endY = rect.top + rect.height() * 0.20f;
        long down = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, startY, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(event);
        event.recycle();
        for (int step = 1; step <= 8; step++) {
            float y = startY + ((endY - startY) * step / 8f);
            MotionEvent move = MotionEvent.obtain(down, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_MOVE, x, y, 0);
            move.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            instrumentation.sendPointerSync(move);
            move.recycle();
            SystemClock.sleep(16);
        }
        MotionEvent up = MotionEvent.obtain(down, SystemClock.uptimeMillis() + 16,
                MotionEvent.ACTION_UP, x, endY, 0);
        up.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(up);
        up.recycle();
        instrumentation.waitForIdleSync();
        SystemClock.sleep(700);
    }

    private Rect globalRect(View view) {
        Rect rect = new Rect();
        assertTrue("view not shown: " + view, view.getGlobalVisibleRect(rect));
        return rect;
    }

    private String activeWindowPackage() {
        AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
        if (root == null || root.getPackageName() == null) return null;
        return root.getPackageName().toString();
    }

    private void captureEvidence(String name) throws Exception {
        String safe = name.replaceAll("[^a-zA-Z0-9._-]+", "-");
        File prefix = new File(evidenceRoot, safe);
        AccessibilityNodeInfo root = waitForActiveRoot();
        writeString(new File(prefix.getPath() + ".tree.txt"), dump(root, 0, new StringBuilder()).toString());
        Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("screenshot capture failed", screenshot);
        FileOutputStream output = new FileOutputStream(new File(prefix.getPath() + ".png"));
        try {
            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, output));
            output.getFD().sync();
        } finally {
            output.close();
            screenshot.recycle();
        }
    }

    private AccessibilityNodeInfo waitForActiveRoot() {
        long deadline = SystemClock.uptimeMillis() + 3000L;
        while (SystemClock.uptimeMillis() < deadline) {
            AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
            if (root != null) return root;
            SystemClock.sleep(100);
        }
        fail("rootInActiveWindow unavailable");
        return null;
    }

    private StringBuilder dump(AccessibilityNodeInfo node, int depth, StringBuilder text) {
        if (node == null) return text;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        for (int i = 0; i < depth; i++) text.append("  ");
        text.append(node.getClassName()).append(" pkg=").append(node.getPackageName())
                .append(" id=").append(node.getViewIdResourceName())
                .append(" text=").append(quoted(node.getText()))
                .append(" desc=").append(quoted(node.getContentDescription()))
                .append(" bounds=").append(bounds)
                .append(" clickable=").append(node.isClickable())
                .append(" enabled=").append(node.isEnabled())
                .append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            dump(node.getChild(i), depth + 1, text);
        }
        return text;
    }

    private String quoted(CharSequence value) {
        if (value == null) return "\"\"";
        return "\"" + value.toString().replace("\n", "\\n") + "\"";
    }

    private void prepareWorkspace() throws Exception {
        deleteRecursively(selftestRoot);
        assertTrue(selftestRoot.mkdirs());
        assertTrue(prefs.edit().clear().commit());
    }

    private Fixture createFixture(String name, SelfTestPlan.Mode mode, String status, boolean complete,
            boolean fixtureVerified, boolean cancelled, boolean timedOut, boolean fatal,
            boolean reportStorageOk, int previewCount) throws Exception {
        SelfTestPlan plan = new SelfTestPlan(mode);
        Fixture fixture = new Fixture();
        fixture.name = name;
        fixture.plan = plan;
        fixture.runId = String.format(Locale.US, "run-%013d-%08x", previewCount, name.hashCode());
        fixture.selectedUri = Uri.fromFile(new File(filesDir, name + "-selected.txt")).toString();
        fixture.selectedDestination = name + "-selected.txt\n" + fixture.selectedUri;
        fixture.reportText = "fixture " + name + "\nrun=" + fixture.runId + "\n"
                + "Preview files are copied from bundled standard.mp4 for chooser wiring only.\n"
                + "They are UI fixtures, not proof of physical export success.\n";
        fixture.exports = exportResults(plan);
        fixture.controls = controlResults(plan);
        fixture.status = status;
        fixture.complete = complete;
        fixture.fixtureVerified = fixtureVerified;
        fixture.cancelled = cancelled;
        fixture.timedOut = timedOut;
        fixture.fatal = fatal;
        fixture.reportStorageOk = reportStorageOk;
        fixture.previewFiles = new ArrayList<>();
        for (int i = 0; i < previewCount; i++) {
            fixture.previewFiles.add(String.format(Locale.US, "%02d-%s.mp4", i, plan.cases.get(i)));
        }
        writeFixture(fixture);
        return fixture;
    }

    private void writeFixture(Fixture fixture) throws Exception {
        File selected = new File(Uri.parse(fixture.selectedUri).getPath());
        writeString(selected, "selected report binding fixture\n");
        assertTrue(prefs.edit()
                .putString("uri", fixture.selectedUri)
                .putString("mode", fixture.plan.mode.name())
                .putString("destination", fixture.selectedDestination)
                .putString("state", "TERMINAL / 已结束，查看结果")
                .putString("error", "")
                .commit());
        File run = new File(selftestRoot, fixture.runId);
        assertTrue(run.isDirectory() || run.mkdirs());
        ReportJournal.replace(new File(run, "report.txt"), fixture.reportText);
        SavedReport.atomic(new File(run, "suite.json"), suiteJson(fixture).toString(2));
        StringBuilder outputs = new StringBuilder();
        for (String name : fixture.previewFiles) {
            outputs.append(name).append('\n');
            copyAsset("video-oracle/standard.mp4", new File(run, name));
        }
        if (!fixture.previewFiles.isEmpty()) {
            SavedReport.atomic(new File(run, "outputs.txt"), outputs.toString());
        }
    }

    private JSONObject suiteJson(Fixture fixture) throws Exception {
        JSONObject suite = fixture.plan.metadata()
                .put("schema", SuiteOutcome.EVIDENCE_SCHEMA)
                .put("status", fixture.status)
                .put("complete", fixture.complete)
                .put("full_feature_coverage", fixture.plan.isShort() ? "UNVERIFIED" : "INCOMPLETE")
                .put("full_suite_status", fixture.plan.isShort() ? "NOT_RUN" : fixture.status)
                .put("metadata", new JSONObject().put("fixture_namespace", "suite-summary-ui")
                        .put("fixture_name", fixture.name))
                .put("fixture_verified", fixture.fixtureVerified)
                .put("exports", fixture.exports)
                .put("checker_controls", fixture.controls)
                .put("cancelled", fixture.cancelled)
                .put("timed_out", fixture.timedOut)
                .put("updated_utc_ms", System.currentTimeMillis())
                .put("report_storage_ok", fixture.reportStorageOk)
                .put("report_binding", new JSONObject()
                        .put("schema", "selftest-report-binding-v1")
                        .put("run_id", fixture.runId)
                        .put("mode", fixture.plan.mode.name())
                        .put("selected_report_required", true)
                        .put("selected_report_uri", fixture.selectedUri)
                        .put("selected_report_mode", fixture.plan.mode.name())
                        .put("selected_report_destination", fixture.selectedDestination));
        if (fixture.fatal) suite.put("fatal_error", "fixture fatal");
        if (!fixture.reportStorageOk) suite.put("report_storage_error", "fixture storage error");
        suite.put("outcome", SuiteOutcome.fromSnapshot(fixture.plan, fixture.status, fixture.complete,
                fixture.fixtureVerified, fixture.exports, fixture.controls, fixture.cancelled,
                fixture.timedOut, fixture.fatal, !fixture.reportStorageOk).toJson());
        return suite;
    }

    private JSONArray exportResults(SelfTestPlan plan) throws Exception {
        JSONArray results = new JSONArray();
        for (String id : plan.cases) {
            results.put(new JSONObject().put("id", id).put("status", "PASS")
                    .put("expected_status", "PASS").put("actual_status", "PASS")
                    .put("report", id + ".json"));
        }
        return results;
    }

    private JSONArray controlResults(SelfTestPlan plan) throws Exception {
        JSONArray results = new JSONArray();
        for (String id : plan.controls) {
            String expected = SelfTestPlan.positive(id) ? "PASS" : "FAIL";
            results.put(new JSONObject().put("id", id).put("status", "PASS")
                    .put("expected_status", expected).put("actual_status", expected)
                    .put("report", id + ".json"));
        }
        return results;
    }

    private void copyAsset(String asset, File destination) throws IOException {
        File parent = destination.getParentFile();
        if (!parent.isDirectory()) assertTrue(parent.mkdirs());
        try (InputStream input = targetContext.getAssets().open(asset);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
        assertTrue(destination.isFile());
        assertTrue(destination.length() > 0);
    }

    private void restorePreferences(Map<String, ?> values) {
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            String key = entry.getKey();
            if (value instanceof String) editor.putString(key, (String) value);
            else if (value instanceof Integer) editor.putInt(key, (Integer) value);
            else if (value instanceof Long) editor.putLong(key, (Long) value);
            else if (value instanceof Float) editor.putFloat(key, (Float) value);
            else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
            else if (value instanceof java.util.Set) {
                editor.putStringSet(key, new HashSet<>((java.util.Set<String>) value));
            }
        }
        assertTrue(editor.commit());
    }

    private List<Button> previewButtons(SelfTestActivity activity, LinearLayout outputs) throws Exception {
        List<Button> previews = new ArrayList<>();
        List<File> files = ((SelfTestRunner) field(activity, "runner")).getOutputs();
        for (int i = 0; i < outputs.getChildCount(); i++) {
            View child = outputs.getChildAt(i);
            if (child instanceof Button) {
                for (File file : files) {
                    if (activity.getString(R.string.secondary_preview_output, file.getName())
                            .equals(((Button) child).getText().toString())) {
                        previews.add((Button) child);
                        break;
                    }
                }
                assertTrue("unexpected preview button label", previews.contains(child));
            }
        }
        assertEquals("each output must have a preview button", files.size(), previews.size());
        return previews;
    }

    private TextView status(SelfTestActivity activity) throws Exception {
        return (TextView) field(activity, "statusView");
    }

    private TextView report(SelfTestActivity activity) throws Exception {
        return (TextView) field(activity, "reportView");
    }

    private LinearLayout outputs(SelfTestActivity activity) throws Exception {
        return (LinearLayout) field(activity, "outputsView");
    }

    private TextView summary(SelfTestActivity activity) throws Exception {
        return (TextView) field(activity, "summaryView");
    }

    private ScrollView scroll(SelfTestActivity activity) throws Exception {
        ViewParent parent = report(activity).getParent();
        assertTrue(parent instanceof LinearLayout);
        ViewParent maybeScroll = parent.getParent();
        assertTrue(maybeScroll instanceof ScrollView);
        return (ScrollView) maybeScroll;
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void writeString(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }

    private void copyRecursively(File source, File destination) throws IOException {
        if (!source.exists()) return;
        if (source.isDirectory()) {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("cannot create " + destination);
            }
            File[] children = source.listFiles();
            if (children == null) throw new IOException("cannot list " + source);
            for (File child : children) {
                copyRecursively(child, new File(destination, child.getName()));
            }
            return;
        }
        File parent = destination.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        try (FileInputStream input = new FileInputStream(source);
             FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            output.getFD().sync();
        }
    }

    private void deleteRecursively(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        file.delete();
    }

    private static final class Fixture {
        String name;
        String runId;
        String selectedUri;
        String selectedDestination;
        String reportText;
        String status;
        boolean complete;
        boolean fixtureVerified;
        boolean cancelled;
        boolean timedOut;
        boolean fatal;
        boolean reportStorageOk;
        SelfTestPlan plan;
        JSONArray exports;
        JSONArray controls;
        List<String> previewFiles;
    }
}
