package com.simple.videoeditor;

import android.test.AndroidTestCase;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@androidx.media3.common.util.UnstableApi
public final class ShortDiagnosticTest extends AndroidTestCase {
    public void testFullPlanPreservesPinnedInventoryAndDefaultRun() throws Exception {
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.FULL);
        assertEquals(17, plan.cases.size());
        assertEquals(60, plan.controls.size());
        assertEquals(22, plan.metadata().getInt("planned_positive_control_count"));
        assertEquals(38, plan.metadata().getInt("planned_negative_control_count"));
        OracleVerifier verifier = new OracleVerifier(getContext());
        JSONArray controls = verifier.loadControls().getJSONArray("controls");
        for (int i = 0; i < controls.length(); i++) {
            assertEquals(controls.getJSONObject(i).getString("id"), plan.controls.get(i));
        }
        JSONArray cases = verifier.loadContract().getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            assertEquals(cases.getJSONObject(i).getString("id"), plan.cases.get(i));
        }
        SelfTestRunner runner = new SelfTestRunner(getContext(), (text, running) -> {});
        SelfTestRunner.Run run = runner.new Run();
        try {
            assertEquals(SelfTestPlan.Mode.FULL, run.plan.mode);
            assertEquals(17, run.status.length);
        } finally {
            run.worker.shutdownNow();
        }
    }

    public void testShortInventoryIsExactlyRelevantPinnedControls() throws Exception {
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        assertEquals(Arrays.asList("identity", "speed2", "music_loop"), plan.cases);
        JSONArray controls = new OracleVerifier(getContext()).loadControls().getJSONArray("controls");
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < controls.length(); i++) {
            JSONObject control = controls.getJSONObject(i);
            if (!plan.controls.contains(control.getString("id"))) continue;
            selected.add(control.getString("id"));
            assertTrue(plan.cases.contains(control.getString("case")));
            if ("FAIL".equals(control.getString("expected_status"))) {
                assertFalse(control.getString("expected_failure_prefix").isEmpty());
            }
        }
        assertEquals(plan.controls.subList(0, 7), selected);
        assertEquals(Arrays.asList("music_reference", "music_unchanged", "music_no_loop"),
                plan.controls.subList(7, 10));
        assertEquals(10, new HashSet<>(plan.controls).size());
    }

    public void testShortCoverageEnumeratesOmissionsAndCounts() throws Exception {
        JSONObject metadata = new SelfTestPlan(SelfTestPlan.Mode.SHORT_DIAGNOSTIC).metadata();
        assertEquals("SHORT_DIAGNOSTIC", metadata.getString("mode"));
        assertEquals("LIMITED_SUBSET", metadata.getString("coverage"));
        assertEquals(3, metadata.getInt("planned_export_count"));
        assertEquals(10, metadata.getInt("planned_control_count"));
        assertEquals(3, metadata.getInt("planned_positive_control_count"));
        assertEquals(7, metadata.getInt("planned_negative_control_count"));
        assertEquals(14, metadata.getJSONArray("omitted_cases").length());
        assertEquals(50, metadata.getJSONArray("omitted_controls").length());
    }

    public void testPlanCannotMutateOrAcceptMissingMode() {
        SelfTestPlan plan = new SelfTestPlan(SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
        for (List<String> ids : Arrays.asList(plan.cases, plan.controls, SelfTestPlan.FULL_CASES)) {
            try { ids.add("extra"); fail("immutable inventory required"); }
            catch (UnsupportedOperationException expected) {}
        }
        try { new SelfTestPlan(null); fail("missing mode must not silently select full"); }
        catch (IllegalArgumentException expected) {}
    }

    public void testSubsetSuccessIsNeverFullSuitePass() {
        assertEquals("SUBSET_PASS", new SelfTestPlan(SelfTestPlan.Mode.SHORT_DIAGNOSTIC).successStatus());
        assertEquals("PASS", new SelfTestPlan(SelfTestPlan.Mode.FULL).successStatus());
        assertTrue(SelfTestPlan.SHORT_NOTICE.contains("LIMITED COVERAGE"));
        assertTrue(SelfTestPlan.SHORT_NOTICE.contains("139"));
    }

    public void testShortFailurePersistsSelectedIndexAndCoverage() throws Exception {
        SelfTestRunner runner = new SelfTestRunner(getContext(), (text, running) -> {});
        SelfTestRunner.Run run = prepared(runner);
        try {
            runner.recordExportFailure(run, 1, new File(run.directory, "missing.mp4"),
                    new IOException("checked diagnostic disk failure"));
            JSONObject suite = json(new File(run.directory, "suite.json"));
            assertEquals("SHORT_DIAGNOSTIC", suite.getString("mode"));
            assertEquals("NOT_RUN", suite.getString("full_suite_status"));
            assertEquals(3, suite.getInt("planned_export_count"));
            assertEquals(10, suite.getInt("planned_control_count"));
            assertFalse(suite.getBoolean("complete"));
            JSONObject failure = suite.getJSONArray("exports").getJSONObject(1);
            assertEquals("speed2", failure.getString("id"));
            assertEquals("ERROR", failure.getString("status"));
            assertFalse(failure.has("error"));
            assertTrue(json(new File(run.directory, failure.getString("report")))
                    .getString("error").contains("java.io.IOException"));
            assertTrue(new File(run.directory, "speed2.json").isFile());
            assertFalse(new File(run.directory, "crop.json").exists());
        } finally { run.worker.shutdownNow(); }
    }

    public void testShortNegativeControlIOExceptionIsErrorNotRejectionPass() throws Exception {
        SelfTestRunner runner = new SelfTestRunner(getContext(), (text, running) -> {});
        SelfTestRunner.Run run = prepared(runner);
        try {
            runner.runMusicControls(run, index -> { throw new IOException("diagnostic write failed"); });
            for (int i = 7; i < 10; i++) {
                JSONObject result = run.controlResults.getJSONObject(i);
                assertEquals("ERROR", result.getString("status"));
                assertEquals("ERROR", result.getString("actual_status"));
                assertTrue(result.getString("error").contains("java.io.IOException"));
            }
            assertEquals("UNVERIFIED", json(new File(run.directory, "suite.json")).getString("status"));
        } finally { run.worker.shutdownNow(); }
    }

    public void testShortCancellationKeepsSubsetUnverified() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        SelfTestRunner[] owner = new SelfTestRunner[1];
        String[] terminal = new String[1];
        owner[0] = new SelfTestRunner(getContext(), (text, running) -> {
            if (running && text.contains("IN PROGRESS / INCOMPLETE — initializing")) owner[0].cancel();
            if (!running) { terminal[0] = text; finished.countDown(); }
        });
        try {
            owner[0].start(null, SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
            assertTrue(finished.await(90, TimeUnit.SECONDS));
            assertFalse(owner[0].isRunning());
            assertTrue(terminal[0].contains("CANCELLED"));
            assertFalse(terminal[0].contains("SUBSET_PASS"));
            JSONObject suite = json(new File(owner[0].getReportFile().getParentFile(), "suite.json"));
            assertEquals("SHORT_DIAGNOSTIC", suite.getString("mode"));
            assertEquals("NOT_RUN", suite.getString("full_suite_status"));
            assertTrue(suite.getBoolean("cancelled"));
        } finally { if (owner[0].isRunning()) owner[0].cancel(); }
    }

    private SelfTestRunner.Run prepared(SelfTestRunner runner) throws Exception {
        SelfTestRunner.Run run = runner.new Run(new SelfTestPlan(SelfTestPlan.Mode.SHORT_DIAGNOSTIC));
        run.directory = new File(getContext().getFilesDir(), "short-test-" + UUID.randomUUID());
        assertTrue(run.directory.mkdir());
        run.report = new File(run.directory, "report.txt");
        for (String id : run.plan.cases) run.caseResults.put(new JSONObject().put("id", id)
                .put("status", "UNVERIFIED"));
        for (String id : run.plan.controls) run.controlResults.put(new JSONObject().put("id", id)
                .put("expected_status", SelfTestPlan.positive(id) ? "PASS" : "FAIL")
                .put("status", "UNVERIFIED"));
        return run;
    }

    private JSONObject json(File file) throws Exception {
        return new JSONObject(SavedReportTest.read(new FileInputStream(file)));
    }
}
