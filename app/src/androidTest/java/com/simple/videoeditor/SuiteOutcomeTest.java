package com.simple.videoeditor;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.test.AndroidTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;

public final class SuiteOutcomeTest extends AndroidTestCase {
    private Context isolated;
    private File root;

    @Override protected void setUp() throws Exception {
        super.setUp();
        root = new File(getContext().getFilesDir(), "suite-outcome-" + System.nanoTime());
        assertTrue(root.mkdirs());
        isolated = new ContextWrapper(getContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(root.getName() + "-" + name, mode);
            }
        };
    }

    @Override protected void tearDown() throws Exception {
        SavedReport.preferences(isolated).edit().clear().commit();
        delete(root);
        super.tearDown();
    }

    public void testFullAuthoritativePassUsesAllPassHeadline() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(runId, suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true));
        SuiteOutcome outcome = restored().outcome();
        assertEquals("ALL PASS", outcome.headline());
        assertTrue(outcome.isSuccess());
        assertEquals("ALL_PASS", outcome.state());
        assertTrue(outcome.details().contains("模式：FULL"));
        assertTrue(outcome.details().contains("导出：17/17 通过，0 失败，0 错误，0 未运行"));
        assertTrue(outcome.details().contains("对照：60/60 通过，0 失败，0 错误，0 未运行"));
        assertTrue(outcome.details().contains("原因：本套件已覆盖项目全部通过。"));
    }

    public void testShortPassIsSubsetPassWithOmittedCounts() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        selectReport(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        writeRun(runId, suite(plan, runId, "SUBSET_PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true));
        SuiteOutcome outcome = restored().outcome();
        assertEquals("SUBSET PASS", outcome.headline());
        assertTrue(outcome.isSuccess());
        assertEquals("SUBSET_PASS", outcome.state());
        assertFalse("ALL PASS".equals(outcome.headline()));
        assertTrue(outcome.details().contains("模式：SHORT_DIAGNOSTIC"));
        assertTrue(outcome.details().contains("未覆盖：14 导出，50 对照"));
        assertTrue(outcome.details().contains("原因：短诊断所选项目通过。"));
    }

    public void testIncompleteFullCountsNeverRestoreGreenPhoneFixture() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        JSONArray exports = exportResults(plan);
        exports.getJSONObject(16).put("status", "UNVERIFIED").put("actual_status", "NOT RUN");
        writeRun(runId, suite(plan, runId, "UNVERIFIED", true, true,
                exports, controlResults(plan), false, false, false, true, true));
        SelfTestRunner runner = restored();
        SuiteOutcome outcome = runner.outcome();
        assertEquals("未通过", outcome.headline());
        assertFalse(outcome.isSuccess());
        assertEquals("FAIL", outcome.state());
        assertEquals(1, runner.getOutputs().size());
        assertTrue(outcome.details().contains("导出：16/17 通过，0 失败，0 错误，1 未运行"));
    }

    public void testCancelledRunRestoresCancelledState() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(runId, suite(plan, runId, "UNVERIFIED", true, true,
                exportResults(plan), controlResults(plan), true, false, false, true, true));
        SuiteOutcome outcome = restored().outcome();
        assertFalse(outcome.isSuccess());
        assertEquals("CANCELLED", outcome.state());
        assertTrue(outcome.details().contains("原因：套件已取消；当前结果不完整。"));
    }

    public void testInterruptedRunRestoresInterruptedState() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(runId, suite(plan, runId, "UNVERIFIED", false, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true));
        SuiteOutcome outcome = restored().outcome();
        assertFalse(outcome.isSuccess());
        assertEquals("INTERRUPTED", outcome.state());
        assertTrue(outcome.details().contains("原因：套件在完成前中断；RUNNING 快照不能代表通过。"));
    }

    public void testLiveIncompleteSnapshotStaysRunning() throws Exception {
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        SuiteOutcome outcome = SuiteOutcome.fromSnapshot(plan, "UNVERIFIED", false, true,
                exportResults(plan), controlResults(plan), false, false, false, false);
        assertEquals("RUNNING", outcome.state());
        assertEquals("运行中", outcome.headline());
        assertTrue(outcome.details().contains("原因：套件仍在运行；完成前不能判定通过。"));
    }

    public void testSparseSnapshotNeverThrowsOrTurnsGreen() throws Exception {
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        JSONArray exports = new JSONArray()
                .put(new JSONObject())
                .put(new JSONObject().put("id", plan.cases.get(1)).put("status", "PASS")
                        .put("expected_status", "PASS").put("actual_status", "PASS"));
        String controlId = plan.controls.get(1);
        String expected = SelfTestPlan.positive(controlId) ? "PASS" : "FAIL";
        JSONArray controls = new JSONArray()
                .put(new JSONObject())
                .put(new JSONObject().put("id", controlId).put("status", "PASS")
                        .put("expected_status", expected).put("actual_status", expected));
        SuiteOutcome outcome = SuiteOutcome.fromSnapshot(plan, "PASS", true, true,
                exports, controls, false, false, false, false);
        assertEquals("INCONSISTENT", outcome.state());
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.details().contains("导出：1/17 通过，0 失败，0 错误，16 未运行"));
        assertTrue(outcome.details().contains("对照：1/60 通过，0 失败，0 错误，59 未运行"));
    }

    public void testMissingRunReturnsMissingState() {
        SuiteOutcome outcome = restored().outcome();
        assertFalse(outcome.isSuccess());
        assertEquals("MISSING", outcome.state());
        assertTrue(outcome.details().contains("模式：UNKNOWN"));
    }

    public void testLatestMissingSuiteDoesNotReuseOlderPass() throws Exception {
        String olderRun = "run-0000000000001-aaaaaaaa";
        String latestRun = "run-0000000000002-bbbbbbbb";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(olderRun, suite(plan, olderRun, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true));
        File latest = runDir(latestRun);
        ReportJournal.replace(new File(latest, "report.txt"), "newer interrupted report\n");
        SelfTestRunner runner = restored();
        assertEquals(new File(latest, "report.txt"), runner.getReportFile());
        assertEquals("STALE", runner.outcome().state());
        assertFalse(runner.outcome().isSuccess());
    }

    public void testLegacySuiteWithoutBindingDoesNotRestoreGreen() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(runId, suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, false));
        SuiteOutcome outcome = restored().outcome();
        assertEquals("STALE", outcome.state());
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.details().contains("旧结果不再自动恢复"));
    }

    public void testWrongModeInventoryIsInconsistent() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        selectReport(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        JSONObject suite = suite(plan, runId, "SUBSET_PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true);
        suite.put("planned_export_count", 17);
        writeRun(runId, suite);
        SuiteOutcome outcome = restored().outcome();
        assertFalse(outcome.isSuccess());
        assertEquals("INCONSISTENT", outcome.state());
        assertTrue(outcome.details().contains("固定库存不一致"));
    }

    public void testMalformedSuiteJsonRestoresMalformedState() throws Exception {
        File run = runDir("run-0000000000001-aaaaaaaa");
        ReportJournal.replace(new File(run, "report.txt"), "report\n");
        SavedReport.atomic(new File(run, "suite.json"), "{");
        SuiteOutcome outcome = restored().outcome();
        assertFalse(outcome.isSuccess());
        assertEquals("MALFORMED", outcome.state());
    }

    public void testStorageAndExecutionErrorsNeverRestoreSuccess() throws Exception {
        String errorRun = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(errorRun, suite(plan, errorRun, "ERROR", true, true,
                exportResults(plan), controlResults(plan), false, false, true, true, true));
        SuiteOutcome error = restored().outcome();
        assertFalse(error.isSuccess());
        assertEquals("ERROR", error.state());
        delete(root);
        assertTrue(root.mkdirs());
        setUpSelectionIsolation();
        String storageRun = "run-0000000000002-bbbbbbbb";
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(storageRun, suite(plan, storageRun, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, false, true));
        SuiteOutcome storage = restored().outcome();
        assertFalse(storage.isSuccess());
        assertEquals("STORAGE_ERROR", storage.state());
        assertEquals("未通过", storage.headline());
    }

    public void testMissingReportNeverRestoresPass() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRunWithoutReport(runId, suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true));
        SelfTestRunner runner = restored();
        assertNull(runner.getReportFile());
        assertEquals("MISSING", runner.outcome().state());
        assertFalse(runner.outcome().isSuccess());
    }

    public void testCopiedSuiteRunIdentityIsRejectedAsStale() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        String copiedTo = "run-0000000000002-bbbbbbbb";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(copiedTo, suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true));
        SuiteOutcome outcome = restored().outcome();
        assertEquals("STALE", outcome.state());
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.details().contains("运行标识与目录不一致"));
    }

    public void testSelectedDifferentModeRejectsRestoredPass() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        writeRun(runId, suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true));
        selectReport(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        SelfTestRunner runner = restored();
        SuiteOutcome outcome = runner.outcome();
        assertEquals("STALE", outcome.state());
        assertFalse(outcome.isSuccess());
        assertNull(runner.getReportFile());
        assertEquals(0, runner.getOutputs().size());
        assertTrue(outcome.details().contains("当前所选 TXT 或模式与该运行不一致"));
    }

    public void testSelectedReportModeMustMatchEvidenceMode() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        JSONObject suite = suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true);
        suite.getJSONObject("report_binding").put("selected_report_mode",
                SelfTestPlan.Mode.SHORT_DIAGNOSTIC.name());
        writeRun(runId, suite);
        SelfTestRunner runner = restored();
        assertEquals("STALE", runner.outcome().state());
        assertNull(runner.getReportFile());
        assertEquals(0, runner.getOutputs().size());
        assertTrue(runner.outcome().details().contains("所选报告模式与结果模式不一致"));
    }

    public void testExportExpectedStatusMustMatchPlan() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        JSONArray exports = exportResults(plan);
        exports.getJSONObject(0).put("expected_status", "FAIL");
        writeRun(runId, suite(plan, runId, "PASS", true, true,
                exports, controlResults(plan), false, false, false, true, true));
        SuiteOutcome outcome = restored().outcome();
        assertEquals("INCONSISTENT", outcome.state());
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.details().contains("expected_status 与固定计划不一致"));
    }

    public void testControlPassRowActualStatusMustMatchExpected() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        selectReport(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        JSONArray controls = controlResults(plan);
        controls.getJSONObject(2).put("actual_status", "PASS");
        writeRun(runId, suite(plan, runId, "SUBSET_PASS", true, true,
                exportResults(plan), controls, false, false, false, true, true));
        SuiteOutcome outcome = restored().outcome();
        assertEquals("INCONSISTENT", outcome.state());
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.details().contains("PASS 行 actual_status 必须等于 expected_status"));
    }

    public void testLivePassSnapshotRequiresInventoryValidation() throws Exception {
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        JSONArray exports = exportResults(plan);
        exports.getJSONObject(0).put("expected_status", "FAIL");
        SuiteOutcome outcome = SuiteOutcome.fromSnapshot(plan, "PASS", true, true,
                exports, controlResults(plan), false, false, false, false);
        assertEquals("INCONSISTENT", outcome.state());
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.details().contains("固定库存不一致"));
    }

    public void testOversizedSuiteJsonIsRejected() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        JSONObject suite = suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true);
        suite.put("padding", repeated('中', 300 * 1024));
        writeRun(runId, suite);
        SuiteOutcome outcome = restored().outcome();
        assertEquals("MALFORMED", outcome.state());
        assertFalse(outcome.isSuccess());
    }

    public void testInvalidBooleanFieldIsMalformed() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.FULL);
        JSONObject suite = suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true);
        suite.put("cancelled", "false");
        writeRun(runId, suite);
        SuiteOutcome outcome = restored().outcome();
        assertEquals("MALFORMED", outcome.state());
        assertFalse(outcome.isSuccess());
        assertTrue(outcome.details().contains("cancelled"));
    }

    public void testFinalStorageErrorPersistsStorageFailureInsteadOfPass() throws Exception {
        File external = new File(root, "selected-report.txt");
        selectReport(Uri.fromFile(external).toString(), SelfTestPlan.Mode.FULL);
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        SelfTestRunner runner = restored();
        SelfTestRunner.Run run = runner.new Run(plan);
        run.directory = runDir("run-0000000000001-aaaaaaaa");
        run.report = new File(run.directory, "report.txt");
        ReportJournal.replace(run.report, "initial report\n");
        run.savedReport = new SavedReport(isolated, Uri.fromFile(external), "selected");
        run.finished = true;
        run.fixturePassed = true;
        run.log.append("final checkpoint\n");
        JSONArray exports = exportResults(plan);
        JSONArray controls = controlResults(plan);
        for (int i = 0; i < exports.length(); i++) run.caseResults.put(exports.getJSONObject(i));
        for (int i = 0; i < controls.length(); i++) run.controlResults.put(controls.getJSONObject(i));
        assertTrue(external.mkdir());
        SuiteOutcome outcome = runner.persistTerminalOutcome(run, "PASS");
        assertEquals("STORAGE_ERROR", outcome.state());
        assertFalse(outcome.isSuccess());
        JSONObject suite = new JSONObject(read(new File(run.directory, "suite.json")));
        assertFalse(suite.getBoolean("report_storage_ok"));
        assertEquals("STORAGE_ERROR", suite.getJSONObject("outcome").getString("state"));
    }

    public void testCancellationAcceptedBeforeCommitForcesUnverifiedTerminalStatus() {
        SelfTestRunner runner = restored();
        SelfTestRunner.Run run = runner.new Run(new SelfTestPlan(SelfTestPlan.Mode.FULL));
        assertTrue(runner.requestCancel(run));
        assertEquals("UNVERIFIED", runner.beginTerminalCommit(run, "PASS"));
        assertTrue(run.finalizing);
        runner.finishTerminalCommit(run);
        assertTrue(run.terminalCommitted);
        assertFalse(run.finalizing);
    }

    public void testCancellationRejectedAfterFinalizingStarts() {
        SelfTestRunner runner = restored();
        SelfTestRunner.Run run = runner.new Run(new SelfTestPlan(SelfTestPlan.Mode.FULL));
        assertEquals("PASS", runner.beginTerminalCommit(run, "PASS"));
        assertFalse(runner.requestCancel(run));
        assertFalse(run.cancelled);
        runner.finishTerminalCommit(run);
        assertTrue(run.terminalCommitted);
    }

    public void testIsFinalizingUsesCachedTerminalState() throws Exception {
        SelfTestRunner runner = restored();
        assertFalse(runner.isFinalizing());
        SelfTestRunner.Run run = runner.new Run(new SelfTestPlan(SelfTestPlan.Mode.FULL));
        setActive(runner, run);
        assertFalse(runner.isFinalizing());
        assertEquals("PASS", runner.beginTerminalCommit(run, "PASS"));
        assertTrue(runner.isFinalizing());
        runner.finishTerminalCommit(run);
        assertTrue(runner.isFinalizing());
        setActive(runner, null);
        assertFalse(runner.isFinalizing());
    }

    public void testPrivateRunSharingDoesNotUseOlderSelectedTxt() throws Exception {
        String runId = "run-0000000000001-aaaaaaaa";
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        selectReport(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        JSONObject evidence = suite(plan, runId, "PASS", true, true,
                exportResults(plan), controlResults(plan), false, false, false, true, true);
        evidence.getJSONObject("report_binding").put("selected_report_required", false)
                .put("selected_report_uri", "").put("selected_report_mode", "");
        writeRun(runId, evidence);
        assertNull(restored().boundSavedReportUri());
    }

    public void testInterruptedBeforePreparationRestoresSelectedUnrunCounts() throws Exception {
        SelfTestRunner runner = restored();
        SelfTestRunner.Run run = runner.new Run(new SelfTestPlan(SelfTestPlan.Mode.FULL));
        run.directory = runDir("run-0000000000001-aaaaaaaa");
        run.report = new File(run.directory, "report.txt");
        ReportJournal.replace(run.report, "interrupted before fixture preparation\n");
        runner.persistTerminalOutcome(run, "UNVERIFIED");
        SuiteOutcome outcome = restored().outcome();
        assertEquals("INTERRUPTED", outcome.state());
        assertTrue(outcome.details().contains("导出：0/17 通过，0 失败，0 错误，17 未运行"));
        assertTrue(outcome.details().contains("对照：0/60 通过，0 失败，0 错误，60 未运行"));
        assertFalse(outcome.isSuccess());
    }

    private SelfTestRunner restored() {
        return new SelfTestRunner(isolated, (text, running) -> {});
    }

    private JSONObject suite(SelfTestPlan plan, String runId, String status, boolean complete,
            boolean fixtureVerified, JSONArray exports, JSONArray controls, boolean cancelled,
            boolean timedOut, boolean fatal, boolean reportStorageOk, boolean includeBinding) throws Exception {
        JSONObject suite = plan.metadata().put("schema", SuiteOutcome.EVIDENCE_SCHEMA)
                .put("status", status).put("complete", complete)
                .put("full_feature_coverage", "INCOMPLETE")
                .put("full_suite_status", plan.isShort() ? "NOT_RUN" : status)
                .put("metadata", new JSONObject()).put("fixture_verified", fixtureVerified)
                .put("exports", exports).put("checker_controls", controls)
                .put("cancelled", cancelled).put("timed_out", timedOut)
                .put("updated_utc_ms", 1L).put("report_storage_ok", reportStorageOk);
        if (fatal) suite.put("fatal_error", "fatal");
        if (!reportStorageOk) suite.put("report_storage_error", "disk");
        if (includeBinding) suite.put("report_binding", binding(runId, plan.mode));
        suite.put("outcome", SuiteOutcome.fromSnapshot(plan, status, complete, fixtureVerified,
                exports, controls, cancelled, timedOut, fatal, !reportStorageOk).toJson());
        return suite;
    }

    private JSONObject binding(String runId, SelfTestPlan.Mode mode) throws Exception {
        return new JSONObject().put("schema", "selftest-report-binding-v1")
                .put("run_id", runId).put("mode", mode.name())
                .put("selected_report_required", true)
                .put("selected_report_uri", selectedUri(mode))
                .put("selected_report_mode", mode.name())
                .put("selected_report_destination", "selected.txt");
    }

    private JSONArray exportResults(SelfTestPlan plan) throws Exception {
        JSONArray results = new JSONArray();
        for (String id : plan.cases) {
            results.put(new JSONObject().put("id", id).put("status", "PASS")
                    .put("expected_status", "PASS").put("actual_status", "PASS"));
        }
        return results;
    }

    private JSONArray controlResults(SelfTestPlan plan) throws Exception {
        JSONArray results = new JSONArray();
        for (String id : plan.controls) {
            String expected = SelfTestPlan.positive(id) ? "PASS" : "FAIL";
            results.put(new JSONObject().put("id", id).put("status", "PASS")
                    .put("expected_status", expected).put("actual_status", expected));
        }
        return results;
    }

    private void writeRun(String name, JSONObject suite) throws Exception {
        File run = runDir(name);
        ReportJournal.replace(new File(run, "report.txt"), "report for " + name + "\n");
        SavedReport.atomic(new File(run, "suite.json"), suite.toString(2));
        try (FileOutputStream output = new FileOutputStream(new File(run, "outputs.txt"))) {
            output.write("00-identity.mp4\n".getBytes(StandardCharsets.UTF_8));
        }
        try (FileOutputStream output = new FileOutputStream(new File(run, "00-identity.mp4"))) {
            output.write(1);
        }
    }

    private void writeRunWithoutReport(String name, JSONObject suite) throws Exception {
        File run = runDir(name);
        SavedReport.atomic(new File(run, "suite.json"), suite.toString(2));
    }

    private File runDir(String name) {
        File directory = new File(new File(root, "selftest"), name);
        assertTrue(directory.isDirectory() || directory.mkdirs());
        return directory;
    }

    private void selectReport(SelfTestPlan.Mode mode) {
        selectReport(selectedUri(mode), mode);
    }

    private void selectReport(String uri, SelfTestPlan.Mode mode) {
        assertTrue(SavedReport.preferences(isolated).edit()
                .putString("uri", uri)
                .putString("mode", mode.name())
                .putString("destination", "selected.txt")
                .putString("state", "TERMINAL / 已结束，查看结果")
                .putString("error", "")
                .commit());
    }

    private String selectedUri(SelfTestPlan.Mode mode) {
        return Uri.fromFile(new File(root, mode.name().toLowerCase() + "-selected.txt")).toString();
    }

    private void setActive(SelfTestRunner runner, SelfTestRunner.Run run) throws Exception {
        Field active = SelfTestRunner.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(runner, run);
    }

    private static String read(File file) throws Exception {
        return SavedReportTest.read(ReportJournal.open(file));
    }

    private void setUpSelectionIsolation() {
        isolated = new ContextWrapper(getContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return root; }
            @Override public android.content.SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(root.getName() + "-" + name, mode);
            }
        };
    }

    private static String repeated(char value, int count) {
        char[] data = new char[count];
        java.util.Arrays.fill(data, value);
        return new String(data);
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
