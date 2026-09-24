package com.simple.videoeditor;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Build;
import android.test.AndroidTestCase;

import com.simple.videoeditor.oracle.IntroOracleContract;
import com.simple.videoeditor.oracle.MusicOracleContract;
import com.simple.videoeditor.oracle.OracleContract;
import com.simple.videoeditor.oracle.TextOracleContract;
import com.simple.videoeditor.oracle.TitleOracleContract;
import com.simple.videoeditor.oracle.WatermarkOracleContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

@androidx.media3.common.util.UnstableApi
public final class CompactSuiteIndexTest extends AndroidTestCase {
    private static final int LIMIT = 256 * 1024;
    private static final Set<String> RESULT_KEYS = new HashSet<>(Arrays.asList(
            "id", "status", "expected_status", "actual_status",
            "candidate_sha256", "input_sha256", "report"));
    private Context isolated;
    private File root;
    private SelfTestRunner runner;
    private SelfTestRunner.Run run;
    private Uri selected;
    private String destination;

    @Override protected void setUp() throws Exception {
        super.setUp();
        root = new File(getContext().getFilesDir(), "compact-suite-" + System.nanoTime());
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
        try {
            if (run != null) {
                run.worker.shutdownNow();
                if (run.savedReport != null) run.savedReport.release();
            }
            SavedReport.preferences(isolated).edit().clear().commit();
            delete(root);
        } finally {
            super.tearDown();
        }
    }

    public void testAllSeventeenExportAndSixtyControlErrorsReopenWithExactCounts() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        assertEquals(17, run.caseResults.length());
        assertEquals(60, run.controlResults.length());
        assertEquals(36, run.metadata.getJSONObject("control_manifest").getJSONArray("controls").length());
        JSONObject provenance = CompactSuiteIndex.metadata(run.metadata);
        assertEquals(BuildConfig.SOURCE_REVISION, provenance.getString("source_revision"));
        assertEquals(run.metadata.getString("installed_apk_sha256"),
                provenance.getString("installed_apk_sha256"));
        assertEquals(4, provenance.length());
        IOException failure = largeFailure("decoder initialization failed", 256);
        String expectedStack = stack(failure);
        assertTrue(expectedStack.length() > 16 * 1024);
        long rawBytes = 0;
        for (int i = 0; i < run.caseResults.length(); i++) {
            JSONObject before = copy(run.caseResults.getJSONObject(i));
            String id = run.plan.cases.get(i);
            File output = new File(run.directory, String.format(Locale.US, "%02d-%s.mp4", i, id));
            if (i == 0) SavedReport.atomic(output, "partial encoded candidate\n");
            runner.recordExportFailure(run, i, output, failure);
            JSONObject raw = json(id + ".json");
            assertEquals("ERROR", raw.getString("status"));
            assertEquals("ERROR", run.status[i]);
            assertEquals(expectedStack, raw.getString("error"));
            assertEquals(run.codecInfo, raw.getString("export_codecs"));
            assertJson(before.get("export_config"), raw.get("export_config"));
            assertJson(before.get("reference"), raw.get("reference"));
            assertJson(run.metadata, raw.getJSONObject("run_metadata"));
            JSONObject compact = run.caseResults.getJSONObject(i);
            assertCompact(compact);
            assertEquals(id + ".json", compact.getString("report"));
            if (i == 0) {
                assertEquals(invoke("sha256", new Class<?>[]{File.class}, output),
                        raw.getString("candidate_sha256"));
                assertEquals(raw.getString("candidate_sha256"), compact.getString("candidate_sha256"));
            } else {
                assertFalse(raw.has("candidate_sha256"));
                assertFalse(compact.has("candidate_sha256"));
            }
            rawBytes += new File(run.directory, id + ".json").length();
            assertBoundedIndex();
        }
        int[] calls = {0};
        runner.runMusicControls(run, index -> {
            assertEquals(calls[0]++, index);
            throw failure;
        });
        assertEquals(3, calls[0]);
        for (int i = 0; i < run.controlResults.length(); i++) {
            String id = run.plan.controls.get(i);
            JSONObject summary = run.controlResults.getJSONObject(i);
            JSONObject before = copy(summary);
            if (!id.startsWith("music_")) {
                runner.recordControlFailure(run, summary, "control-" + id + ".json", failure);
            }
            JSONObject raw = json("control-" + id + ".json");
            assertEquals(id, raw.getString("id"));
            assertEquals("ERROR", raw.getString("status"));
            assertEquals("ERROR", raw.getString("actual_status"));
            assertEquals(before.getString("expected_status"), raw.getString("expected_status"));
            assertEquals(expectedStack, raw.getString("error"));
            assertJson(run.metadata, raw.getJSONObject("run_metadata"));
            for (String key : Arrays.asList("reference", "input_sha256", "required_failures")) {
                if (before.has(key)) assertJson(before.get(key), raw.get(key));
            }
            assertEquals("control-" + id + ".json",
                    run.controlResults.getJSONObject(i).getString("report"));
            rawBytes += new File(run.directory, "control-" + id + ".json").length();
        }
        assertTrue("Regression must exceed the old suite reader limit", rawBytes > LIMIT * 4L);
        run.finished = true;
        for (String requested : Arrays.asList("FAIL", "ERROR")) {
            SuiteOutcome terminal = runner.persistTerminalOutcome(run, requested);
            assertEquals(requested, terminal.state());
            assertErrorCounts(terminal);
            JSONObject evidence = assertBoundedIndex();
            android.util.Log.i("CompactSuiteIndexTest", requested + " 17 export errors/60 control errors: index="
                    + new File(run.directory, "suite.json").length() + " bytes; raw case reports=" + rawBytes);
            assertEquals(requested, evidence.getString("status"));
            assertReference(evidence, "metadata");
            assertNoFailedReportWrites(evidence);
            assertJson(run.metadata, json("run-metadata.json").getJSONObject("metadata"));
            SelfTestRunner reopened = restored();
            assertEquals(requested, reopened.outcome().state());
            assertErrorCounts(reopened.outcome());
            assertEquals(run.report, reopened.getReportFile());
            assertEquals(selected, reopened.boundSavedReportUri());
            assertTrue(read(run.report).contains("decoder initialization failed"));
        }
    }

    public void testHugeMetadataAndSelectedBindingRemainRawOnlyAndShareExactUri() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, true);
        run.metadata.put("driver_diagnostics", repeated('中', LIMIT));
        passRows();
        assertEquals("ALL_PASS", runner.persistTerminalOutcome(run, "PASS").state());
        JSONObject evidence = assertBoundedIndex();
        assertReference(evidence, "metadata");
        JSONObject raw = json("run-metadata.json");
        assertNoFailedReportWrites(evidence);
        android.util.Log.i("CompactSuiteIndexTest", "Huge metadata/binding: index="
                + new File(run.directory, "suite.json").length() + " bytes; raw manifest="
                + new File(run.directory, "run-metadata.json").length());
        assertJson(run.metadata, raw.getJSONObject("metadata"));
        assertTrue(new File(run.directory, "run-metadata.json").length() > LIMIT);
        JSONObject binding = evidence.getJSONObject("report_binding");
        assertEquals("selftest-report-binding-v2", binding.getString("schema"));
        assertFalse(binding.has("selected_report_uri"));
        assertFalse(binding.has("selected_report_destination"));
        assertEquals(sha256(selected.toString()), binding.getString("selected_report_uri_sha256"));
        assertEquals(sha256(destination), binding.getString("selected_report_destination_sha256"));
        assertEquals(64, binding.getString("selected_report_uri_sha256").length());
        assertEquals(64, binding.getString("selected_report_destination_sha256").length());
        assertEquals(run.plan.mode.name(), binding.getString("selected_report_mode"));
        assertEquals(selected.toString(), raw.getJSONObject("report_binding").getString("selected_report_uri"));
        assertEquals(destination, raw.getJSONObject("report_binding").getString("selected_report_destination"));
        assertEquals(run.plan.mode.name(), raw.getJSONObject("report_binding").getString("selected_report_mode"));
        SelfTestRunner reopened = restored();
        assertEquals("ALL_PASS", reopened.outcome().state());
        assertEquals(run.report, reopened.getReportFile());
        assertEquals(selected.toString(), reopened.boundSavedReportUri().toString());

        for (String field : Arrays.asList("selected_report_uri_sha256", "selected_report_destination_sha256")) {
            JSONObject changed = copy(evidence);
            String digest = binding.getString(field);
            changed.getJSONObject("report_binding").put(field,
                    (digest.charAt(0) == '0' ? "1" : "0") + digest.substring(1));
            writeSuite(changed);
            assertStaleWithoutShare();
            expectIOException(() -> reopened.boundSavedReportUri());
        }
        writeSuite(evidence);
        for (String key : Arrays.asList("uri", "destination", "mode")) {
            String original = SavedReport.preferences(isolated).getString(key, "");
            assertTrue(SavedReport.preferences(isolated).edit().putString(key, original + "-changed").commit());
            assertStaleWithoutShare();
            assertTrue(SavedReport.preferences(isolated).edit().putString(key, original).commit());
        }
        assertEquals(selected, restored().boundSavedReportUri());
        assertJson(raw, json("run-metadata.json"));
    }

    public void testHugeFatalAndStorageErrorsKeepSemanticsAndExactRawManifest() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        run.fatal = largeFailure("fatal codec failure", 4096);
        assertTrue(stack(run.fatal).length() > LIMIT);
        assertEquals("ERROR", runner.persistTerminalOutcome(run, "ERROR").state());
        JSONObject evidence = assertBoundedIndex();
        assertReference(evidence, "fatal_error");
        assertFalse(evidence.has("report_storage_error"));
        assertEquals(stack(run.fatal), json("run-metadata.json").getString("fatal_error"));
        assertEquals("ERROR", restored().outcome().state());

        run.reportFailure = largeFailure("private journal unavailable", 4096);
        assertEquals("STORAGE_ERROR", runner.persistTerminalOutcome(run, "ERROR").state());
        evidence = assertBoundedIndex();
        assertFalse(evidence.getBoolean("report_storage_ok"));
        assertReference(evidence, "fatal_error");
        assertReference(evidence, "report_storage_error");
        JSONObject raw = json("run-metadata.json");
        assertEquals(stack(run.fatal), raw.getString("fatal_error"));
        assertEquals(stack(run.reportFailure), raw.getString("report_storage_error"));
        assertJson(run.metadata, raw.getJSONObject("metadata"));
        assertEquals("STORAGE_ERROR", restored().outcome().state());
        assertFalse(restored().outcome().isSuccess());
        assertEquals(run.report, restored().getReportFile());

        // A later atomic snapshot must not inherit errors from the previous manifest.
        run.fatal = null;
        run.reportFailure = null;
        assertEquals("ALL_PASS", runner.persistTerminalOutcome(run, "PASS").state());
        evidence = assertBoundedIndex();
        assertFalse(evidence.has("fatal_error"));
        assertFalse(evidence.has("report_storage_error"));
        raw = json("run-metadata.json");
        assertTrue(!raw.has("fatal_error") || raw.isNull("fatal_error"));
        assertTrue(!raw.has("report_storage_error") || raw.isNull("report_storage_error"));
        assertEquals("ALL_PASS", restored().outcome().state());
    }

    public void testActualSelectedReportWriteFailurePersistsStorageErrorReference() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        assertTrue(new File(selected.getPath()).mkdir());
        assertEquals("STORAGE_ERROR", runner.persistTerminalOutcome(run, "PASS").state());
        JSONObject evidence = assertBoundedIndex();
        assertFalse(evidence.getBoolean("report_storage_ok"));
        assertReference(evidence, "report_storage_error");
        assertNotNull(run.reportFailure);
        assertEquals(stack(run.reportFailure), json("run-metadata.json").getString("report_storage_error"));
        assertEquals("STORAGE_ERROR", restored().outcome().state());
        assertFalse(restored().outcome().isSuccess());
    }

    public void testShortThreeExportsTenControlsRemainSubsetPass() throws Exception {
        prepare(SelfTestPlan.Mode.SHORT_DIAGNOSTIC, false);
        passRows();
        assertEquals("SUBSET_PASS", runner.persistTerminalOutcome(run, "SUBSET_PASS").state());
        JSONObject evidence = assertBoundedIndex();
        assertEquals("NOT_RUN", evidence.getString("full_suite_status"));
        assertNoFailedReportWrites(evidence);
        assertEquals(3, evidence.getJSONArray("exports").length());
        assertEquals(10, evidence.getJSONArray("checker_controls").length());
        assertEquals(14, evidence.getJSONArray("omitted_cases").length());
        assertEquals(50, evidence.getJSONArray("omitted_controls").length());
        SuiteOutcome outcome = restored().outcome();
        assertEquals("SUBSET_PASS", outcome.state());
        assertEquals("SUBSET PASS", outcome.headline());
        assertTrue(outcome.isSuccess());
        assertCounts(outcome.toJson().getJSONObject("exports"), 3, 0, 3);
        assertCounts(outcome.toJson().getJSONObject("controls"), 10, 0, 10);
        assertEquals(selected, restored().boundSavedReportUri());
    }

    public void testLegacyV1BindingStillReopensAndShares() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        runner.persistTerminalOutcome(run, "PASS");
        JSONObject legacy = legacySuite();
        writeSuite(legacy);
        assertEquals("ALL_PASS", restored().outcome().state());
        assertEquals(run.report, restored().getReportFile());
        assertEquals(selected, restored().boundSavedReportUri());
        legacy.getJSONObject("report_binding").put("selected_report_uri", selected + "-stale");
        writeSuite(legacy);
        assertStaleWithoutShare();
    }

    public void testOversizedLegacySuiteIsExplicitMalformedWithBoundedReadError() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        runner.persistTerminalOutcome(run, "PASS");
        SelfTestRunner previouslyReadable = restored();
        JSONObject legacy = legacySuite();
        legacy.getJSONObject("metadata").put("legacy_inline_stack", repeated('中', LIMIT));
        writeSuite(legacy);
        assertTrue(new File(run.directory, "suite.json").length() > LIMIT);
        SuiteOutcome outcome = restored().outcome();
        assertEquals("MALFORMED", outcome.state());
        assertFalse(outcome.isSuccess());
        IOException error = expectIOException(() -> previouslyReadable.boundSavedReportUri());
        assertNotNull(error.getMessage());
        assertTrue(error.getMessage(), error.getMessage().contains("256"));
        assertTrue("Bounded read must not echo the oversized payload", error.getMessage().length() < 1024);
    }

    public void testResultsProjectOnlyProvenanceWithoutMutatingRawRows() throws Exception {
        JSONObject row = result("identity", "PASS").put("status", "ERROR").put("actual_status", "ERROR")
                .put("candidate_sha256", sha256("candidate")).put("input_sha256", sha256("input"))
                .put("report", "identity.json").put("error", repeated('e', LIMIT + 1))
                .put("export_config", "unabridged config").put("reference", new JSONObject().put("path", "reference.mp4"))
                .put("run_metadata", new JSONObject().put("full", repeated('中', LIMIT)));
        JSONObject original = copy(row);
        JSONArray projected = CompactSuiteIndex.results(new JSONArray().put(row), 17);
        assertEquals(1, projected.length());
        JSONObject compact = projected.getJSONObject(0);
        assertCompact(compact);
        assertEquals(7, compact.length());
        for (String key : RESULT_KEYS) assertJson(original.get(key), compact.get(key));
        assertJson(original, row);
        assertNotSame(row, compact);
        compact.put("status", "FAIL");
        assertEquals("ERROR", row.getString("status"));
        JSONObject omitted = new JSONObject() {
            @Override public String toString() { throw new AssertionError("Raw metadata serialized by projection"); }
            @Override public String toString(int indent) { throw new AssertionError("Raw metadata serialized by projection"); }
        };
        row.put("run_metadata", omitted);
        assertCompact(CompactSuiteIndex.results(new JSONArray().put(row), 17).getJSONObject(0));
    }

    public void testResultsRejectBadCapsAndExcessCounts() throws Exception {
        expectIOException(() -> CompactSuiteIndex.results(new JSONArray(), -1));
        expectIOException(() -> CompactSuiteIndex.results(new JSONArray(), Integer.MAX_VALUE));
        for (int cap : new int[]{17, 60}) {
            JSONArray rows = new JSONArray();
            for (int i = 0; i <= cap; i++) rows.put(result("row-" + i, "PASS"));
            expectIOException(() -> CompactSuiteIndex.results(rows, cap));
        }
    }

    public void testResultsRejectOversizedRetainedFieldsRatherThanTruncate() throws Exception {
        for (String key : RESULT_KEYS) {
            JSONObject row = result("identity", "PASS").put(key, repeated('x', LIMIT + 1));
            expectIOException(() -> CompactSuiteIndex.results(new JSONArray().put(row), 17));
        }
    }

    public void testResultsRejectUnsafeReportReferences() throws Exception {
        for (String report : Arrays.asList("../identity.json", "nested/identity.json",
                "nested\\identity.json", "content://provider/report", "identity.txt")) {
            JSONObject row = result("identity", "PASS").put("report", report);
            expectIOException(() -> CompactSuiteIndex.results(new JSONArray().put(row), 17));
        }
    }

    public void testFinalEncodedByteGuardChecksAggregateAndEscaping() throws Exception {
        for (char value : new char[]{'中', '\u0000'}) {
            JSONObject index = new JSONObject();
            for (int i = 0; i < 24; i++) index.put("field" + i, repeated(value, 4096));
            expectIOException(() -> CompactSuiteIndex.serialize(index));
        }
        JSONObject index = new JSONObject().put("text", repeated('中', 8192));
        assertJson(index, new JSONObject(CompactSuiteIndex.serialize(index)));
    }

    public void testRawManifestWriteFailureCannotCommitPassingIndex() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        File manifest = new File(run.directory, "run-metadata.json");
        assertTrue(manifest.mkdir());
        SavedReport.atomic(new File(manifest, "blocker"), "not replaceable");
        assertEquals("STORAGE_ERROR", runner.persistTerminalOutcome(run, "PASS").state());
        assertFalse(new File(run.directory, "suite.json").exists());
        assertFalse(restored().outcome().isSuccess());
    }

    public void testAtomicIndexPromotionFailureCannotPublishPass() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        File suite = new File(run.directory, "suite.json");
        assertTrue(suite.mkdir());
        SavedReport.atomic(new File(suite, "blocker"), "not replaceable");
        assertEquals("STORAGE_ERROR", runner.persistTerminalOutcome(run, "PASS").state());
        assertNull(run.persistedOutcome);
        assertTrue(new File(run.directory, "run-metadata.json").isFile());
        assertFalse(restored().outcome().isSuccess());
    }

    public void testMusicThrownDiagnosticSurvivesRawDestinationCollision() throws Exception {
        assertMusicThrownWriteFailure(false);
    }

    public void testMusicThrownDiagnosticNewCollisionDoesNotReferenceOldGeneration() throws Exception {
        assertMusicThrownWriteFailure(true);
    }

    private void assertMusicThrownWriteFailure(boolean stagingCollision) throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        run.finished = false;
        int index = run.plan.controls.indexOf("music_reference");
        JSONObject summary = run.controlResults.getJSONObject(index)
                .put("input_sha256", MusicOracleContract.REFERENCE.sha256)
                .put("reference", copy(run.caseResults.getJSONObject(
                        run.plan.cases.indexOf(MusicOracleContract.CASE_ID)).getJSONObject("reference")));
        String filename = "control-music_reference.json";
        JSONObject old = new JSONObject().put("generation", "previous-success");
        if (stagingCollision) {
            CompactSuiteIndex.writeRaw(new File(run.directory, filename), old);
            summary.put("report", filename);
        }
        JSONObject before = copy(summary);
        IOException original = diagnosticFailure("music-original-" + stagingCollision);
        JSONObject expected = controlFailure(before, original);
        blockDestination(filename + (stagingCollision ? ".new" : ""));
        int[] calls = {0};
        IOException storage = expectIOException(() -> runner.runMusicControls(run, i -> {
            assertEquals(0, i);
            calls[0]++;
            throw original;
        }));
        assertEquals(1, calls[0]);
        assertNotSame(original, storage);
        assertSame(storage, run.reportFailure);
        assertFalse(summary.has("report"));
        assertJson(expected, failedDiagnostic(filename, 0, 1, storage));
        if (stagingCollision) assertJson(old, json(filename));
        else assertFalse(new File(run.directory, filename).isFile());

        // runMusicControls must have saved the fallback from its finally, before terminal handling.
        JSONObject fallback = assertFallbackIndex(filename, "checker_controls", index, 1);
        assertJson(expected, fallback.getJSONArray(filename).getJSONObject(0).getJSONObject("diagnostic"));
        assertStorageTerminal(filename, "checker_controls", index, 1, fallback, storage);
    }

    public void testMusicErrorResultAndFailureRecordBothSurviveRawWriteFailure() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        run.finished = false;
        int index = run.plan.controls.indexOf("music_reference");
        String filename = "control-music_reference.json";
        JSONObject summary = run.controlResults.getJSONObject(index);
        JSONObject expectedControl = copy(summary).put("status", "ERROR").put("actual_status", "ERROR")
                .put("candidate_sha256", sha256("music-result-candidate"));
        JSONObject result = verificationError("music-normal-result")
                .put("candidate_sha256", expectedControl.getString("candidate_sha256"));
        JSONObject expectedResult = copy(result).put("control", copy(expectedControl));
        blockDestination(filename);
        int[] calls = {0};
        IOException failureWrite = expectIOException(() -> runner.runMusicControls(run, i -> {
            assertEquals(0, i);
            calls[0]++;
            return result;
        }));
        assertEquals(1, calls[0]);
        IOException resultWrite = run.reportFailure;
        assertNotNull(resultWrite);
        assertNotSame(resultWrite, failureWrite);
        JSONObject retainedResult = failedDiagnostic(filename, 0, 2, resultWrite);
        assertSame(result, retainedResult);
        assertJson(expectedResult, retainedResult);
        assertNotSame(summary, retainedResult.getJSONObject("control"));
        assertJson(controlFailure(expectedControl, resultWrite),
                failedDiagnostic(filename, 1, 2, failureWrite));
        assertFalse(summary.has("report"));
        assertFalse(new File(run.directory, filename).isFile());
        JSONObject fallback = assertFallbackIndex(filename, "checker_controls", index, 2);
        assertJson(expectedResult, fallback.getJSONArray(filename).getJSONObject(0).getJSONObject("diagnostic"));
        assertStorageTerminal(filename, "checker_controls", index, 2, fallback, resultWrite);
    }

    public void testExportThrownDiagnosticKeepsCandidateAndMetadataWhenRawWriteFails() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        run.finished = false;
        int index = 0;
        String filename = run.plan.cases.get(index) + ".json";
        File candidate = new File(run.directory, "existing-candidate.mp4");
        SavedReport.atomic(candidate, "existing encoded candidate for provenance\n");
        String digest = (String) invoke("sha256", new Class<?>[]{File.class}, candidate);
        run.metadata.put("raw_export_provenance", repeated('中', LIMIT));
        JSONObject before = copy(run.caseResults.getJSONObject(index)).put("report", filename);
        run.caseResults.put(index, copy(before));
        IOException original = diagnosticFailure("export-thrown-original");
        blockDestination(filename);
        IOException storage = expectIOException(() -> runner.recordExportFailure(run, index, candidate, original));
        assertSame(storage, run.reportFailure);
        assertEquals("ERROR", run.status[index]);
        JSONObject expected = exportFailure(before, original, digest);
        assertJson(expected, failedDiagnostic(filename, 0, 1, storage));
        assertFalse(run.caseResults.getJSONObject(index).has("report"));
        assertFalse(new File(run.directory, filename).isFile());
        invoke("persistEvidence", new Class<?>[]{SelfTestRunner.Run.class, String.class}, run, "UNVERIFIED");
        JSONObject fallback = assertFallbackIndex(filename, "exports", index, 1);
        assertJson(expected, fallback.getJSONArray(filename).getJSONObject(0).getJSONObject("diagnostic"));
        assertStorageTerminal(filename, "exports", index, 1, fallback, storage);
    }

    public void testPostExportErrorResultAndFailureRecordBothSurviveRawWriteFailure() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        run.finished = false;
        int index = 0;
        String filename = run.plan.cases.get(index) + ".json";
        File candidate = new File(run.directory, "verified-candidate.mp4");
        SavedReport.atomic(candidate, "candidate whose verification returned ERROR\n");
        String digest = (String) invoke("sha256", new Class<?>[]{File.class}, candidate);
        JSONObject before = copy(run.caseResults.getJSONObject(index));
        JSONObject result = verificationError("post-export-normal-result")
                .put("id", run.plan.cases.get(index)).put("candidate_sha256", digest)
                .put("export_config", before.get("export_config")).put("reference", before.get("reference"))
                .put("run_metadata", run.metadata).put("export_codecs", run.codecInfo);
        JSONObject expectedResult = copy(result);
        blockDestination(filename + ".new");
        IOException resultWrite = expectIOException(() -> runner.recordExportResult(run, index, result));
        assertSame(resultWrite, run.reportFailure);
        assertJson(before, run.caseResults.getJSONObject(index));
        assertSame(result, failedDiagnostic(filename, 0, 1, resultWrite));
        assertJson(expectedResult, result);
        IOException failureWrite = expectIOException(() ->
                runner.recordExportFailure(run, index, candidate, resultWrite));
        assertNotSame(resultWrite, failureWrite);
        assertSame(resultWrite, run.reportFailure);
        assertJson(expectedResult, failedDiagnostic(filename, 0, 2, resultWrite));
        assertJson(exportFailure(before, resultWrite, digest),
                failedDiagnostic(filename, 1, 2, failureWrite));
        assertFalse(run.caseResults.getJSONObject(index).has("report"));
        assertFalse(new File(run.directory, filename).isFile());
        invoke("persistEvidence", new Class<?>[]{SelfTestRunner.Run.class, String.class}, run, "UNVERIFIED");
        JSONObject fallback = assertFallbackIndex(filename, "exports", index, 2);
        assertJson(expectedResult, fallback.getJSONArray(filename).getJSONObject(0).getJSONObject("diagnostic"));
        assertStorageTerminal(filename, "exports", index, 2, fallback, resultWrite);
    }

    public void testRawAndMetadataDestinationFailuresPublishUnavailableMarker() throws Exception {
        assertRawAndMetadataFailure(false);
    }

    public void testRawAndMetadataNewFailuresKeepPreviousIndexWithoutFallbackReference() throws Exception {
        assertRawAndMetadataFailure(true);
    }

    private void assertRawAndMetadataFailure(boolean stagingCollision) throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        run.finished = false;
        int index = run.plan.controls.indexOf("music_reference");
        String filename = "control-music_reference.json";
        JSONObject summary = run.controlResults.getJSONObject(index);
        IOException original = diagnosticFailure("dual-destination-original-" + stagingCollision);
        JSONObject expected = controlFailure(copy(summary), original);
        blockDestination(filename);
        int[] calls = {0};
        JSONObject[] preflight = {null};
        IOException metadataWrite = expectIOException(() -> runner.runMusicControls(run, i -> {
            assertEquals(0, i);
            calls[0]++;
            // The music preflight must succeed so the original verification error is reached.
            File manifest = new File(run.directory, "run-metadata.json");
            assertTrue(manifest.isFile());
            try {
                preflight[0] = assertBoundedIndex();
            } catch (Exception error) {
                throw new IOException("Cannot inspect music preflight", error);
            }
            if (!stagingCollision) assertTrue(manifest.delete());
            blockDestination("run-metadata.json" + (stagingCollision ? ".new" : ""));
            throw original;
        }));
        assertEquals(1, calls[0]);
        IOException storage = run.reportFailure;
        assertNotNull(storage);
        assertNotSame(original, storage);
        assertNotSame(storage, metadataWrite);
        assertJson(expected, failedDiagnostic(filename, 0, 1, storage));
        assertFalse(summary.has("report"));
        assertUnavailableMarker();
        JSONObject previous = assertBoundedIndex();
        assertJson(preflight[0], previous);
        assertFalse(previous.has("failed_report_writes"));
        assertFalse(previous.getJSONArray("checker_controls").getJSONObject(index).has("report"));
        assertFalse(previous.getBoolean("complete"));
        assertFalse(restored().outcome().isSuccess());
        assertEquals("STORAGE_ERROR", runner.outcome().state());
        if (stagingCollision) assertFalse(json("run-metadata.json").has("failed_report_writes"));
        else assertFalse(new File(run.directory, "run-metadata.json").isFile());

        run.finished = true;
        assertEquals("STORAGE_ERROR", runner.persistTerminalOutcome(run, "PASS").state());
        assertSame(storage, run.reportFailure);
        assertUnavailableMarker();
        assertJson(previous, assertBoundedIndex());
        assertFalse(restored().outcome().isSuccess());
        assertJson(expected, failedDiagnostic(filename, 0, 1, storage));
    }

    private void assertUnavailableMarker() throws Exception {
        String marker = "RAW_DIAGNOSTICS_UNAVAILABLE: per-case and suite metadata persistence failed";
        assertTrue("Missing live raw-diagnostic loss marker",
                (run.preview.text() + run.log).contains(marker));
        assertTrue("Writable private journal must retain raw-diagnostic loss marker",
                read(run.report).contains(marker));
    }

    private void blockDestination(String filename) throws IOException {
        File collision = new File(run.directory, filename);
        assertTrue("Cannot create collision " + filename, collision.mkdir());
        SavedReport.atomic(new File(collision, "blocker"), "nonempty destination collision\n");
    }

    private JSONObject failedDiagnostic(String filename, int entry, int count, IOException storage) throws Exception {
        assertEquals("Only failed filenames belong in fallback", 1, run.failedReportWrites.length());
        JSONArray failures = run.failedReportWrites.getJSONArray(filename);
        assertEquals(count, failures.length());
        JSONObject failure = failures.getJSONObject(entry);
        assertEquals(2, failure.length());
        assertEquals(stack(storage), failure.getString("storage_error"));
        assertTrue(failure.getString("storage_error").contains(filename));
        return failure.getJSONObject("diagnostic");
    }

    private JSONObject assertFallbackIndex(String filename, String group, int index, int count) throws Exception {
        JSONObject evidence = assertBoundedIndex();
        assertReference(evidence, "failed_report_writes");
        assertFalse(evidence.getBoolean("report_storage_ok"));
        JSONObject row = evidence.getJSONArray(group).getJSONObject(index);
        assertEquals("ERROR", row.getString("status"));
        assertEquals("ERROR", row.getString("actual_status"));
        assertFalse("A failed commit must not publish a per-case report reference", row.has("report"));
        JSONObject manifest = json("run-metadata.json");
        JSONObject failures = manifest.getJSONObject("failed_report_writes");
        assertEquals(1, failures.length());
        assertEquals(count, failures.getJSONArray(filename).length());
        assertJson(run.failedReportWrites, failures);
        assertJson(run.metadata, manifest.getJSONObject("metadata"));
        assertEquals(stack(run.reportFailure), manifest.getString("report_storage_error"));
        assertTrue(new File(run.directory, "run-metadata.json").length() > LIMIT);
        assertEquals("STORAGE_ERROR", runner.outcome().state());
        assertFalse(restored().outcome().isSuccess());
        assertFalse(read(new File(run.directory, "suite.json")).contains("diagnostic-original-cause"));
        return failures;
    }

    private void assertStorageTerminal(String filename, String group, int index, int count,
            JSONObject fallback, IOException storage) throws Exception {
        run.finished = true;
        assertEquals("STORAGE_ERROR", runner.persistTerminalOutcome(run, "PASS").state());
        assertSame("Raw report failure must remain sticky", storage, run.reportFailure);
        assertJson(fallback, assertFallbackIndex(filename, group, index, count));
        SelfTestRunner reopened = restored();
        assertEquals("STORAGE_ERROR", reopened.outcome().state());
        assertFalse(reopened.outcome().isSuccess());
        JSONObject counts = reopened.outcome().toJson().getJSONObject(
                "exports".equals(group) ? "exports" : "controls");
        assertCounts(counts, "exports".equals(group) ? 16 : 59, 1,
                "exports".equals(group) ? 17 : 60);
        assertEquals(run.report, reopened.getReportFile());
    }

    private JSONObject controlFailure(JSONObject before, IOException original) throws Exception {
        JSONObject expected = copy(before).put("status", "ERROR").put("actual_status", "ERROR")
                .put("error", stack(original)).put("run_metadata", run.metadata);
        expected.remove("report");
        return expected;
    }

    private JSONObject exportFailure(JSONObject before, IOException original, String digest) throws Exception {
        return controlFailure(before, original).put("export_codecs", run.codecInfo)
                .put("candidate_sha256", digest);
    }

    private static IOException diagnosticFailure(String label) {
        IOException original = largeFailure(label, 4096);
        original.initCause(new IOException("diagnostic-original-cause-" + label,
                new IllegalStateException("unique decoder provenance: " + label)));
        assertTrue(stack(original).length() > LIMIT);
        return original;
    }

    private static JSONObject verificationError(String label) throws Exception {
        String raw = stack(diagnosticFailure(label));
        return new JSONObject().put("status", "ERROR").put("error", raw)
                .put("checks", new JSONArray().put(new JSONObject().put("id", "audio.window_original")
                        .put("pass", false).put("diagnostic", raw)))
                .put("raw_provenance", new JSONObject().put("label", label)
                        .put("unicode", "原始诊断\n\u0000").put("tail", "exact-original-result"));
    }

    private void assertNoFailedReportWrites(JSONObject evidence) throws Exception {
        assertEquals(0, run.failedReportWrites.length());
        assertFalse(evidence.has("failed_report_writes"));
        JSONObject manifest = json("run-metadata.json");
        assertFalse(manifest.has("failed_report_writes"));
        assertFalse(manifest.has("exports"));
        assertFalse(manifest.has("checker_controls"));
    }

    public void testHugeExportMetadataAndStackStayExactInCaseReport() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        run.metadata.put("native_codec_detail", repeated('中', LIMIT));
        IOException failure = largeFailure("very large export diagnostic", 4096);
        String configuration = run.caseResults.getJSONObject(0).getString("export_config");
        runner.recordExportFailure(run, 0, new File(run.directory, "missing.mp4"), failure);
        assertEquals("ERROR", runner.persistTerminalOutcome(run, "ERROR").state());
        JSONObject raw = json("identity.json");
        assertJson(run.metadata, raw.getJSONObject("run_metadata"));
        assertEquals(stack(failure), raw.getString("error"));
        assertEquals(configuration, raw.getString("export_config"));
        assertTrue(new File(run.directory, "identity.json").length() > LIMIT);
        assertBoundedIndex();
        JSONObject counts = restored().outcome().toJson().getJSONObject("exports");
        assertEquals(16, counts.getInt("passed"));
        assertEquals(1, counts.getInt("errors"));
        assertEquals(selected, restored().boundSavedReportUri());
    }

    public void testInterruptedCancellationStillCommitsDiagnosticsAndIndex() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        // Exercise private index persistence, not the provider's interruptible file channel.
        run.savedReport.release();
        run.savedReport = null;
        passRows();
        run.cancelled = true;
        SuiteOutcome terminal;
        Thread.currentThread().interrupt();
        try {
            terminal = runner.persistTerminalOutcome(run, "UNVERIFIED");
        } finally {
            Thread.interrupted();
        }
        assertEquals("CANCELLED", terminal.state());
        assertJson(run.metadata, json("run-metadata.json").getJSONObject("metadata"));
        assertBoundedIndex();
        assertEquals("CANCELLED", restored().outcome().state());
        assertNull(restored().boundSavedReportUri());
    }

    public void testMixedFailureAndErrorCountsReopenUnchanged() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        for (JSONArray rows : Arrays.asList(run.caseResults, run.controlResults)) {
            for (int i = 0; i < rows.length(); i++) {
                rows.getJSONObject(i).put("status", i == 0 ? "FAIL" : "ERROR")
                        .put("actual_status", i == 0 ? "FAIL" : "ERROR");
            }
        }
        assertEquals("FAIL", runner.persistTerminalOutcome(run, "FAIL").state());
        JSONObject counts = restored().outcome().toJson();
        for (String field : Arrays.asList("exports", "controls")) {
            JSONObject group = counts.getJSONObject(field);
            assertEquals(0, group.getInt("passed"));
            assertEquals(1, group.getInt("failed"));
            assertEquals("exports".equals(field) ? 16 : 59, group.getInt("errors"));
            assertEquals(0, group.getInt("unrun"));
        }
        assertEquals(selected, restored().boundSavedReportUri());
    }

    public void testSerializePreflightsOversizeBeforeCallingJsonSerializer() throws Exception {
        for (String payload : Arrays.asList(repeated('x', LIMIT + 1),
                repeated('中', 100 * 1024), repeated('\u0000', 50 * 1024))) {
            JSONObject oversized = new JSONObject() {
                @Override public String toString() { throw new AssertionError("Serialized before bounded preflight"); }
                @Override public String toString(int indent) { throw new AssertionError("Serialized before bounded preflight"); }
            };
            oversized.put("nested", new JSONArray().put(new JSONObject().put("payload", payload)));
            expectIOException(() -> CompactSuiteIndex.serialize(oversized));
        }
        JSONObject small = new JSONObject().put("text", "中文\n\"\\\u0000")
                .put("nested", new JSONArray().put(true).put(17).put(JSONObject.NULL));
        String serialized = CompactSuiteIndex.serialize(small);
        assertTrue(serialized.getBytes(StandardCharsets.UTF_8).length <= LIMIT);
        assertJson(small, new JSONObject(serialized));
    }

    public void testExportProvenanceTamperingNeverReopensGreen() throws Exception {
        assertTamperedRowsNeverGreen("exports");
    }

    public void testControlProvenanceTamperingNeverReopensGreen() throws Exception {
        assertTamperedRowsNeverGreen("checker_controls");
    }

    public void testProjectionDoesNotRepairTamperedLiveExportOrControlProvenance() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        for (JSONArray rows : Arrays.asList(run.caseResults, run.controlResults)) {
            JSONObject original = copy(rows.getJSONObject(0));
            for (String field : Arrays.asList("id", "expected_status", "actual_status", "status")) {
                JSONObject changed = copy(original).put(field, "id".equals(field) ? "foreign-case" : "FAIL");
                rows.put(0, changed);
                SuiteOutcome terminal = runner.persistTerminalOutcome(run, "PASS");
                assertFalse("Compaction must not repair " + field, terminal.isSuccess());
                assertFalse(restored().outcome().isSuccess());
            }
            rows.put(0, original);
        }
        assertEquals("ALL_PASS", runner.persistTerminalOutcome(run, "PASS").state());
        assertEquals("ALL_PASS", restored().outcome().state());
    }

    public void testPlanAndStoredCountsTamperingNeverReopensGreen() throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        assertEquals("ALL_PASS", runner.persistTerminalOutcome(run, "PASS").state());
        JSONObject valid = assertBoundedIndex();
        for (String key : Arrays.asList("planned_cases", "planned_controls")) {
            JSONObject changed = copy(valid);
            JSONArray ids = changed.getJSONArray(key);
            Object first = ids.get(0);
            ids.put(0, ids.get(1)).put(1, first);
            assertNonGreen(changed);
        }
        for (String key : Arrays.asList("planned_export_count", "planned_control_count")) {
            JSONObject changed = copy(valid);
            changed.put(key, changed.getInt(key) - 1);
            assertNonGreen(changed);
        }
        JSONObject changed = copy(valid);
        changed.getJSONObject("outcome").getJSONObject("exports").put("passed", 16);
        assertNonGreen(changed);
        changed = copy(valid);
        changed.put("fixture_verified", false);
        assertNonGreen(changed);
    }

    private void assertTamperedRowsNeverGreen(String key) throws Exception {
        prepare(SelfTestPlan.Mode.FULL, false);
        passRows();
        assertEquals("ALL_PASS", runner.persistTerminalOutcome(run, "PASS").state());
        JSONObject valid = assertBoundedIndex();
        for (String field : Arrays.asList("id", "expected_status", "actual_status", "status")) {
            JSONObject changed = copy(valid);
            JSONObject row = changed.getJSONArray(key).getJSONObject(0);
            row.put(field, "id".equals(field) ? "foreign-case" : "FAIL");
            assertNonGreen(changed);
            changed = copy(valid);
            changed.getJSONArray(key).getJSONObject(0).remove(field);
            assertNonGreen(changed);
        }
        JSONObject changed = copy(valid);
        JSONArray rows = changed.getJSONArray(key);
        Object first = rows.get(0);
        rows.put(0, rows.get(1)).put(1, first);
        assertNonGreen(changed);
        changed = copy(valid);
        rows = changed.getJSONArray(key);
        rows.put(1, copy(rows.getJSONObject(0)));
        assertNonGreen(changed);
        changed = copy(valid);
        rows = changed.getJSONArray(key);
        rows.put(rows.length(), copy(rows.getJSONObject(0)));
        assertNonGreen(changed);
        changed = copy(valid);
        rows = changed.getJSONArray(key);
        JSONArray missing = new JSONArray();
        for (int i = 0; i < rows.length() - 1; i++) missing.put(rows.get(i));
        changed.put(key, missing);
        assertNonGreen(changed);
        writeSuite(valid);
        assertEquals("ALL_PASS", restored().outcome().state());
    }

    private void prepare(SelfTestPlan.Mode mode, boolean hugeBinding) throws Exception {
        selected = Uri.fromFile(new File(root, "selected.txt"));
        if (hugeBinding) {
            // File providers open getPath(); the large query tests lossless URI binding without long filenames.
            selected = selected.buildUpon().appendQueryParameter("document", repeated('u', LIMIT + 1)).build();
        }
        destination = hugeBinding ? repeated('中', LIMIT + 1) + "\n" + selected : "selected.txt\n" + selected;
        assertTrue(SavedReport.preferences(isolated).edit().putString("uri", selected.toString())
                .putString("mode", mode.name()).putString("destination", destination)
                .putString("state", "TERMINAL / 已结束，查看结果").putString("error", "").commit());
        runner = restored();
        run = runner.new Run(new SelfTestPlan(mode));
        run.directory = new File(new File(root, "selftest"), "run-0000000000001-aaaaaaaa");
        assertTrue(run.directory.mkdirs());
        run.report = new File(run.directory, "report.txt");
        ReportJournal.replace(run.report, "Compact suite regression report\n");
        run.savedReport = new SavedReport(isolated, selected, destination);
        run.codecInfo = "Encoder details not received";
        initializeProductionMetadata();
    }

    private void initializeProductionMetadata() throws Exception {
        OracleVerifier verifier = new OracleVerifier(isolated);
        JSONObject contract = verifier.loadContract();
        JSONObject manifest = verifier.loadControls();
        run.metadata.put("plan", run.plan.metadata()).put("app", invoke("appVersion", new Class<?>[0]))
                .put("source_revision", BuildConfig.SOURCE_REVISION)
                .put("installed_apk_sha256", invoke("sha256", new Class<?>[]{File.class},
                        new File(isolated.getApplicationInfo().sourceDir)))
                .put("sdk", Build.VERSION.SDK_INT).put("manufacturer", Build.MANUFACTURER)
                .put("model", Build.MODEL).put("os", Build.VERSION.RELEASE)
                .put("fingerprint", Build.FINGERPRINT)
                .put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)))
                .put("media3", androidx.media3.common.MediaLibraryInfo.VERSION)
                .put("started_utc_ms", System.currentTimeMillis())
                .put("fixture", contract.getJSONObject("fixture")).put("pins", contract.getJSONObject("pins"))
                .put("control_manifest", manifest);
        JSONObject music = new JSONObject().put("path", MusicOracleContract.REFERENCE.path)
                .put("sha256", MusicOracleContract.REFERENCE.sha256).put("version", "music-loop-1")
                .put("frequency_hz", 1320).put("rms", 0.16 / Math.sqrt(2))
                .put("music_sha256", MusicOracleContract.MUSIC.sha256);
        JSONArray vectors = contract.getJSONArray("cases");
        assertEquals(12, vectors.length());
        vectors.put(new JSONObject().put("id", MusicOracleContract.CASE_ID).put("reference", music));
        addSupplement(vectors, "intro", IntroOracleContract.CASE_ID);
        addSupplement(vectors, "text", TextOracleContract.CASE_ID);
        addSupplement(vectors, "title", TitleOracleContract.CASE_ID);
        addSupplement(vectors, "watermark", WatermarkOracleContract.CASE_ID);
        assertEquals(17, vectors.length());
        File fixture = new File(run.directory, "standard.mp4");
        for (String id : run.plan.cases) {
            JSONObject vector = vectors.getJSONObject(SelfTestPlan.FULL_CASES.indexOf(id));
            assertEquals(id, vector.getString("id"));
            JSONObject reference = vector.getJSONObject("reference");
            if (TitleOracleContract.CASE_ID.equals(id)) {
                reference.put("actual_scaled_density", isolated.getResources().getDisplayMetrics().scaledDensity);
            }
            run.caseResults.put(result(id, "PASS").put("reference", reference)
                    .put("export_config", exportConfig(id, vector, fixture).toString()));
        }
        for (String id : run.plan.controls) {
            run.controlResults.put(result(id, SelfTestPlan.positive(id) ? "PASS" : "FAIL"));
        }
        for (IntroOracleContract.Control c : IntroOracleContract.CONTROLS) {
            supplementControl(c.id, c.asset, c.requiredFailures, "intro");
        }
        for (TextOracleContract.Control c : TextOracleContract.CONTROLS) {
            supplementControl(c.id, c.asset, c.requiredFailures, "text");
        }
        for (TitleOracleContract.Control c : TitleOracleContract.CONTROLS) {
            supplementControl(c.id, c.asset, c.requiredFailures, "title");
        }
        for (WatermarkOracleContract.Control c : WatermarkOracleContract.CONTROLS) {
            supplementControl(c.id, c.asset, c.requiredFailures, "watermark");
        }
        run.fixturePassed = true;
    }

    private void addSupplement(JSONArray vectors, String name, String id) throws Exception {
        JSONObject reference = (JSONObject) invoke(name + "Reference", new Class<?>[0]);
        run.metadata.put(name + "_supplement", "intro".equals(name) ? reference
                : invoke(name + "Reference", new Class<?>[0]));
        JSONObject vector = new JSONObject().put("id", id).put("reference", reference);
        if ("text".equals(name)) {
            vector.put("android_edit_config", new JSONObject(TextOracleContract.CASES.get(0).androidEditConfig));
        }
        if ("watermark".equals(name)) {
            vector.put("android_edit_config", new JSONObject(WatermarkOracleContract.CASES.get(0).androidEditConfig));
        }
        vectors.put(vector);
    }

    private EditConfig exportConfig(String id, JSONObject vector, File fixture) throws Exception {
        if (MusicOracleContract.CASE_ID.equals(id)) {
            return new EditConfig.Builder(Uri.fromFile(fixture), 4000).sourceSize(320, 240)
                    .replacementMusic(Uri.fromFile(new File(run.directory, MusicOracleContract.MUSIC.path))).build();
        }
        if (IntroOracleContract.CASE_ID.equals(id)) {
            return new EditConfig.Builder(Uri.fromFile(fixture), IntroOracleContract.ORIGINAL_DURATION_MS)
                    .sourceSize(320, 240).intro(Uri.fromFile(new File(run.directory, IntroOracleContract.INTRO.path))).build();
        }
        if (TitleOracleContract.CASE_ID.equals(id)) return SelfTestRunner.titleConfig(fixture);
        if (WatermarkOracleContract.CASE_ID.equals(id)) {
            byte[] png = new byte[(int) WatermarkOracleContract.PNG.bytes];
            try (DataInputStream input = new DataInputStream(isolated.getAssets().open(
                    "watermark-oracle/" + WatermarkOracleContract.PNG.path))) {
                input.readFully(png);
                assertEquals(-1, input.read());
            }
            return new EditConfig.Builder(Uri.fromFile(fixture), 4000).sourceSize(320, 240)
                    .watermark(PngWatermark.fromBytes(png), .2f, .75f, .75f).build();
        }
        return (EditConfig) invoke("configFor", new Class<?>[]{JSONObject.class, File.class},
                vector.getJSONObject("android_edit_config"), fixture);
    }

    private void supplementControl(String id, OracleContract.Asset asset,
            java.util.List<String> failures, String name) throws Exception {
        int index = run.plan.controls.indexOf(id);
        if (index < 0) return;
        run.controlResults.getJSONObject(index)
                .put("reference", invoke(name + "Reference", new Class<?>[0]))
                .put("input_sha256", asset.sha256).put("required_failures", new JSONArray(failures));
    }

    private void passRows() throws Exception {
        for (JSONArray rows : Arrays.asList(run.caseResults, run.controlResults)) {
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                row.put("status", "PASS").put("actual_status", row.getString("expected_status"));
            }
        }
        Arrays.fill(run.status, "PASS");
        run.finished = true;
    }

    private JSONObject legacySuite() throws Exception {
        JSONObject evidence = json("suite.json");
        evidence.put("metadata", copy(run.metadata));
        evidence.put("report_binding", new JSONObject().put("schema", "selftest-report-binding-v1")
                .put("run_id", run.directory.getName()).put("mode", run.plan.mode.name())
                .put("selected_report_required", true).put("selected_report_uri", selected.toString())
                .put("selected_report_mode", run.plan.mode.name()).put("selected_report_destination", destination));
        return evidence;
    }

    private JSONObject assertBoundedIndex() throws Exception {
        File file = new File(run.directory, "suite.json");
        assertTrue(file.isFile());
        assertTrue("suite.json must keep its 256 KiB reader bound: " + file.length(), file.length() <= LIMIT);
        JSONObject evidence = json("suite.json");
        for (String key : Arrays.asList("exports", "checker_controls")) {
            JSONArray rows = evidence.getJSONArray(key);
            for (int i = 0; i < rows.length(); i++) assertCompact(rows.getJSONObject(i));
        }
        return evidence;
    }

    private static void assertCompact(JSONObject row) {
        Iterator<String> keys = row.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            assertTrue("Raw field leaked into compact index: " + key, RESULT_KEYS.contains(key));
        }
        for (String key : Arrays.asList("id", "status", "expected_status", "actual_status")) {
            assertTrue("Missing provenance " + key, row.opt(key) instanceof String);
        }
    }

    private void assertReference(JSONObject evidence, String field) throws Exception {
        JSONObject reference = evidence.getJSONObject(field);
        assertEquals("metadata".equals(field) ? 4 : 2, reference.length());
        assertEquals("run-metadata.json", reference.getString("report"));
        assertEquals(field, reference.getString("field"));
        if ("metadata".equals(field)) {
            assertEquals(run.metadata.getString("source_revision"), reference.getString("source_revision"));
            assertEquals(run.metadata.getString("installed_apk_sha256"),
                    reference.getString("installed_apk_sha256"));
        }
    }

    private static void assertErrorCounts(SuiteOutcome outcome) throws Exception {
        assertFalse(outcome.isSuccess());
        assertCounts(outcome.toJson().getJSONObject("exports"), 0, 17, 17);
        assertCounts(outcome.toJson().getJSONObject("controls"), 0, 60, 60);
    }

    private static void assertCounts(JSONObject counts, int passed, int errors, int total) throws Exception {
        assertEquals(passed, counts.getInt("passed"));
        assertEquals(0, counts.getInt("failed"));
        assertEquals(errors, counts.getInt("errors"));
        assertEquals(0, counts.getInt("unrun"));
        assertEquals(total, counts.getInt("total"));
    }

    private void assertStaleWithoutShare() throws Exception {
        SelfTestRunner reopened = restored();
        assertEquals("STALE", reopened.outcome().state());
        assertFalse(reopened.outcome().isSuccess());
        assertNull(reopened.getReportFile());
        expectIOException(() -> reopened.boundSavedReportUri());
    }

    private void assertNonGreen(JSONObject changed) throws Exception {
        writeSuite(changed);
        SuiteOutcome outcome = restored().outcome();
        assertFalse(outcome.toJson().toString(), outcome.isSuccess());
        assertTrue(outcome.state(), "INCONSISTENT".equals(outcome.state()) || "MALFORMED".equals(outcome.state()));
    }

    private SelfTestRunner restored() {
        return new SelfTestRunner(isolated, (text, running) -> {});
    }

    private Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = SelfTestRunner.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(runner, args);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof Exception) throw (Exception) error.getCause();
            throw error;
        }
    }

    private void writeSuite(JSONObject evidence) throws Exception {
        SavedReport.atomic(new File(run.directory, "suite.json"), evidence.toString(2));
    }

    private JSONObject json(String name) throws Exception {
        return new JSONObject(read(new File(run.directory, name)));
    }

    private static String read(File file) throws Exception {
        int limit = "suite.json".equals(file.getName()) ? LIMIT : 8 * 1024 * 1024;
        try (FileInputStream input = ReportJournal.open(file);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > limit - bytes.size()) throw new IOException("Test diagnostic exceeds bound");
                bytes.write(buffer, 0, count);
            }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static JSONObject result(String id, String expected) throws Exception {
        return new JSONObject().put("id", id).put("status", "UNVERIFIED")
                .put("expected_status", expected).put("actual_status", "NOT RUN");
    }

    private static JSONObject copy(JSONObject object) throws Exception {
        return new JSONObject(object.toString());
    }

    private static void assertJson(Object expected, Object actual) throws Exception {
        if (expected instanceof JSONObject) {
            assertTrue(actual instanceof JSONObject);
            JSONObject left = (JSONObject) expected;
            JSONObject right = (JSONObject) actual;
            assertEquals(left.length(), right.length());
            Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                assertTrue("Missing raw field " + key, right.has(key));
                assertJson(left.get(key), right.get(key));
            }
        } else if (expected instanceof JSONArray) {
            assertTrue(actual instanceof JSONArray);
            JSONArray left = (JSONArray) expected;
            JSONArray right = (JSONArray) actual;
            assertEquals(left.length(), right.length());
            for (int i = 0; i < left.length(); i++) assertJson(left.get(i), right.get(i));
        } else if (expected instanceof Number && actual instanceof Number) {
            assertEquals(0, new java.math.BigDecimal(expected.toString())
                    .compareTo(new java.math.BigDecimal(actual.toString())));
        } else {
            assertEquals(expected, actual);
        }
    }

    private static IOException largeFailure(String message, int frames) {
        IOException error = new IOException(message + ": " + repeated('x', 2048));
        StackTraceElement[] stack = new StackTraceElement[frames];
        Arrays.fill(stack, new StackTraceElement("android.media.MediaCodec$CodecException",
                "configureNativeDecoderWithRepeatedDriverDiagnostics", "MediaCodec.java", 1943));
        error.setStackTrace(stack);
        return error;
    }

    private static String stack(Exception error) {
        StringWriter text = new StringWriter();
        error.printStackTrace(new PrintWriter(text));
        return text.toString();
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(64);
        for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b & 255));
        return hex.toString();
    }

    private interface CheckedAction { void run() throws Exception; }

    private static IOException expectIOException(CheckedAction action) throws Exception {
        try {
            action.run();
            fail("Expected bounded IOException rejection");
            return null;
        } catch (IOException expected) {
            return expected;
        }
    }

    private static String repeated(char value, int count) {
        char[] data = new char[count];
        Arrays.fill(data, value);
        return new String(data);
    }

    private static void delete(File file) {
        if (file == null || !file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
