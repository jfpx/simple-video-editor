package com.simple.videoeditor;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import android.util.Log;

import com.simple.videoeditor.oracle.TitleOracleContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class PhoneCodecReportingTest extends InstrumentationTestCase {
    private static final String TAG = "PhoneCodecReportingTest";

    public void testFrozenComboControlsPassWithNativeDecoderSelection() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getInstrumentation().getTargetContext());
        JSONArray controls = verifier.loadControls().getJSONArray("controls");
        JSONObject reference = null;
        JSONObject stress = null;
        int referenceCount = 0;
        int stressCount = 0;
        for (int index = 0; index < controls.length(); index++) {
            JSONObject control = controls.getJSONObject(index);
            String id = control.getString("id");
            if ("positive_reference_combo".equals(id)) {
                reference = control;
                referenceCount++;
            } else if ("positive_stress_combo_crf28".equals(id)) {
                stress = control;
                stressCount++;
            }
        }
        assertNotNull(reference);
        assertNotNull(stress);
        assertEquals(1, referenceCount);
        assertEquals(1, stressCount);
        assertFrozenComboControl(verifier, reference);
        assertFrozenComboControl(verifier, stress);
    }

    private void assertFrozenComboControl(OracleVerifier verifier, JSONObject control)
            throws Exception {
        assertEquals("PASS", control.getString("expected_status"));
        assertEquals("combo", control.getString("case"));
        File candidate = verifier.prepareControl(control);
        JSONObject report = verifier.verify("combo", candidate);
        assertEquals(control.getString("id") + ": " + report, "PASS", report.getString("status"));
        assertEquals("combo", report.getString("case"));
        assertEquals(control.getString("sha256"), report.getString("candidate_sha256"));

        JSONObject decoder = report.getJSONObject(OracleVerifier.DECODER_KEY);
        JSONObject video = decoder.getJSONObject("video");
        JSONObject audio = decoder.getJSONObject("audio");
        assertDecoderEvidence("video", video);
        assertDecoderEvidence("audio", audio);

        JSONObject selection = video.getJSONObject("selection");
        assertEquals("configured", selection.getString("stage"));
        String selectedName = selection.getString("decoder_name");
        assertNotNull(selectedName);
        assertTrue(selectedName.length() > 0);
        assertEquals(selectedName, video.getString("decoder_name"));
        Object fallback = selection.get("software_fallback");
        assertTrue(fallback instanceof Boolean);

        JSONArray candidates = selection.getJSONArray("candidates");
        int selectedCount = 0;
        for (int index = 0; index < candidates.length(); index++) {
            JSONObject candidateEvidence = candidates.getJSONObject(index);
            assertTrue(candidateEvidence.has("name"));
            assertTrue(candidateEvidence.has("software"));
            assertTrue(candidateEvidence.has("eligible_attempt"));
            assertTrue(candidateEvidence.has("capability"));
            assertNotNull(candidateEvidence.getJSONObject("capability"));
            if (selectedName.equals(candidateEvidence.getString("name"))) {
                selectedCount++;
                assertEquals(fallback, candidateEvidence.get("software"));
                assertTrue(candidateEvidence.getBoolean("eligible_attempt"));
            }
        }
        assertEquals(1, selectedCount);

        if (Build.VERSION.SDK_INT >= 29) {
            boolean matched = false;
            boolean expectedSoftware = false;
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
                if (!info.isEncoder() && selectedName.equals(info.getName())) {
                    matched = true;
                    expectedSoftware = info.isSoftwareOnly();
                    break;
                }
            }
            assertTrue("Missing codec info for " + selectedName, matched);
            assertEquals(Boolean.valueOf(expectedSoftware), fallback);
        }

        Log.i(TAG, "control=" + control.getString("id") + " api=" + Build.VERSION.SDK_INT
                + " selected=" + selectedName + " software_fallback=" + fallback);
    }

    private static void assertDecoderEvidence(String track, JSONObject diagnostics) throws Exception {
        assertTrue(track + " track not present", diagnostics.getBoolean("present"));
        assertFalse(track + " decoder name missing", diagnostics.isNull("decoder_name"));
        assertTrue(diagnostics.getString("decoder_name").length() > 0);
        assertFalse(track + " input format missing", diagnostics.isNull("input_format"));
        assertTrue(diagnostics.getJSONObject("input_format").getString("mime").length() > 0);
        assertFalse(track + " output format missing", diagnostics.isNull("output_format"));
        assertTrue(diagnostics.getJSONObject("output_format").getString("mime").length() > 0);
    }

    public void testFrozenTitlePassesWithNativeDecoderSelection() throws Exception {
        assertFrozenTitleControl("title_reference", true);
    }

    public void testSevenFrozenTitleNegativesFailWithoutDecoderErrors() throws Exception {
        assertEquals(8, TitleOracleContract.CONTROLS.size());
        for (String id : new String[]{"title_missing", "title_wrong_text", "title_wrong_duration",
                "title_wrong_order", "title_audible", "title_muted_main", "title_wrong_background"}) {
            assertFrozenTitleControl(id, false);
        }
    }

    private void assertFrozenTitleControl(String id, boolean expectedPass) throws Exception {
        TitleOracleContract.Control control = null;
        int matches = 0;
        for (TitleOracleContract.Control entry : TitleOracleContract.CONTROLS) {
            if (id.equals(entry.id)) {
                control = entry;
                matches++;
            }
        }
        assertEquals(id, 1, matches);
        assertNotNull(control);
        assertEquals(id, expectedPass, control.expectedPass);
        OracleVerifier verifier = new OracleVerifier(getInstrumentation().getTargetContext(),
                TitleOracleContract.create());
        File candidate = verifier.prepareTitleAsset(control.asset);
        MediaFormat declared = frozenTitleVideoFormat(candidate);
        assertEquals("video/avc", declared.getString(MediaFormat.KEY_MIME));
        assertEquals(320, declared.getInteger(MediaFormat.KEY_WIDTH));
        assertEquals(240, declared.getInteger(MediaFormat.KEY_HEIGHT));
        if (expectedPass && declared.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            assertEquals(25, declared.getInteger(MediaFormat.KEY_FRAME_RATE));
        }
        if (declared.containsKey(MediaFormat.KEY_PROFILE)) {
            assertEquals(8, declared.getInteger(MediaFormat.KEY_PROFILE));
        }
        if (declared.containsKey(MediaFormat.KEY_LEVEL)) {
            assertEquals(524288, declared.getInteger(MediaFormat.KEY_LEVEL));
        }
        Log.i(TAG, "control=" + id + " declared_format=" + declared);
        // Mirror only the existing buffer-mode settings, retaining the asset's profile and level.
        declared.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
        declared.setInteger("rotation-degrees", 0);
        String expectedConfigureFormat = declared.toString();
        JSONArray checkpoints = new JSONArray();
        verifier.setObserver(diagnostics -> checkpoints.put(new JSONObject(diagnostics)));
        JSONObject report;
        try {
            report = verifier.verify(TitleOracleContract.CASE_ID, candidate);
        } finally {
            Log.i(TAG, "control=" + id + " api=" + Build.VERSION.SDK_INT + " last_checkpoint="
                    + (checkpoints.length() == 0 ? "none" : checkpoints.opt(checkpoints.length() - 1)));
            verifier.setObserver(null);
        }
        assertEquals(id + ": " + report, expectedPass ? "PASS" : "FAIL", report.getString("status"));
        assertEquals(TitleOracleContract.CASE_ID, report.getString("case"));
        assertEquals(id, control.asset.sha256, report.getString("candidate_sha256"));
        for (String requiredFailure : control.requiredFailures) {
            boolean found = false;
            JSONArray checks = report.getJSONArray("checks");
            for (int index = 0; index < checks.length(); index++) {
                JSONObject check = checks.getJSONObject(index);
                if (requiredFailure.equals(check.getString("assertion"))) {
                    found = true;
                    assertFalse(id + ": " + check, check.getBoolean("passed"));
                }
            }
            assertTrue(id + ": missing " + requiredFailure, found);
        }
        JSONObject decoder = report.getJSONObject(OracleVerifier.DECODER_KEY);
        if ("title_wrong_duration".equals(id)) {
            // This frozen negative is rejected by metadata before either decoder is configured.
            assertEquals(id + ": unexpected native selection", 0, checkpoints.length());
            assertFalse(decoder.getJSONObject("video").has("selection"));
            JSONArray checks = report.getJSONArray("checks");
            for (int index = 0; index < checks.length(); index++) {
                assertFalse("Metadata gate decoded unexpected frames",
                        "video.frame_count".equals(checks.getJSONObject(index).getString("assertion")));
            }
            return;
        }
        JSONObject video = decoder.getJSONObject("video");
        assertDecoderEvidence("video", video);
        assertDecoderEvidence("audio", decoder.getJSONObject("audio"));
        JSONObject selection = video.getJSONObject("selection");
        assertEquals("configured", selection.getString("stage"));
        assertEquals(expectedConfigureFormat, selection.getString("configure_format"));
        String selectedName = video.getString("decoder_name");
        assertEquals(selectedName, selection.getString("decoder_name"));
        boolean beforeConfigure = false;
        for (int index = 0; index < checkpoints.length(); index++) {
            JSONObject checkpoint = checkpoints.getJSONObject(index)
                    .getJSONObject("video").getJSONObject("selection");
            assertEquals(id + ": original configure format changed", expectedConfigureFormat,
                    checkpoint.getString("configure_format"));
            if ("before_configure".equals(checkpoint.getString("stage"))
                    && selectedName.equals(checkpoint.getString("decoder_name"))) {
                beforeConfigure = true;
            }
        }
        assertTrue(id + ": missing pre-configure evidence", beforeConfigure);
        JSONArray candidates = selection.getJSONArray("candidates");
        int selectedCount = 0;
        for (int index = 0; index < candidates.length(); index++) {
            JSONObject evidence = candidates.getJSONObject(index);
            if (!selectedName.equals(evidence.getString("name"))) continue;
            selectedCount++;
            assertTrue(evidence.toString(), evidence.getBoolean("eligible_attempt"));
            boolean software = evidence.getBoolean("software");
            assertEquals(software, selection.getBoolean("software_fallback"));
            JSONObject capability = evidence.getJSONObject("capability");
            assertTrue(capability.getBoolean("flexible_yuv420"));
            assertTrue(capability.getBoolean("size_supported"));
            assertFalse(capability.getBoolean("surface_only"));
            assertFalse(capability.getBoolean("alias"));
            boolean fullSupport = capability.getBoolean("format_supported");
            boolean levelFallback = capability.getBoolean("level_advisory_fallback");
            assertTrue(capability.get("level_query_performed") instanceof Boolean);
            if (fullSupport) {
                assertFalse(capability.toString(), levelFallback);
            } else {
                assertTrue(capability.toString(), levelFallback);
                assertTrue("Level advisory fallback must be software-only", software);
                assertTrue(declared.containsKey(MediaFormat.KEY_LEVEL));
                assertTrue(capability.getBoolean("level_query_performed"));
                assertTrue(capability.getBoolean("format_without_level_supported"));
                assertExplicitSoftwareDecoder(selectedName);
            }
        }
        assertEquals(id + ": selected candidate evidence", 1, selectedCount);
    }

    private static MediaFormat frozenTitleVideoFormat(File candidate) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(candidate.getAbsolutePath());
            for (int index = 0; index < extractor.getTrackCount(); index++) {
                MediaFormat format = extractor.getTrackFormat(index);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) return format;
            }
            throw new IOException("Missing frozen title video track: " + candidate);
        } finally {
            extractor.release();
        }
    }

    private static void assertExplicitSoftwareDecoder(String name) {
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.isEncoder() || !name.equals(info.getName())) continue;
            if (Build.VERSION.SDK_INT >= 29) {
                assertTrue(name, info.isSoftwareOnly());
            } else {
                String lower = name.toLowerCase(java.util.Locale.US);
                assertTrue(name, lower.startsWith("omx.google.") || lower.startsWith("c2.android.")
                        || lower.startsWith("c2.google."));
            }
            return;
        }
        fail("Missing software codec info for " + name);
    }

    public void testMusicCheckedDecoderFailurePersistsAndContinues() throws Exception {
        try (ReportingRun test = new ReportingRun()) {
            JSONObject rejected = musicResult("FAIL", false);
            int[] calls = {0};
            test.runner.runMusicControls(test.run, index -> {
                calls[0]++;
                Map<String, Object> diagnostics = new LinkedHashMap<>();
                diagnostics.put("stage", "before_configure_" + index);
                diagnostics.put("codec", "injected.decoder");
                test.runner.decoderObserver(test.run).checkpoint(diagnostics);
                // The risky operation must not begin until both TXT paths have been closed/synced.
                String marker = "before_configure_" + index;
                assertTrue(reportText(test.run.report).contains(marker));
                assertTrue(reportText(SavedReport.snapshot(test.context)).contains(marker));
                String external = reportText(test.external);
                assertTrue(external.contains(marker));
                assertTrue(external.endsWith("END CHECKPOINT\n"));
                if (index < 2) throw new IOException("injected decoder configure failure " + index);
                assertEquals("ERROR", test.summary(1).optString("status"));
                assertTrue(reportText(new File(test.run.directory, "control-music_unchanged.json"))
                        .contains("injected decoder configure failure 1"));
                return rejected;
            });
            assertEquals(3, calls[0]);
            JSONArray persisted = new JSONObject(reportText(new File(test.run.directory, "suite.json")))
                    .getJSONArray("checker_controls");
            for (int index = 0; index < 2; index++) {
                JSONObject summary = persisted.getJSONObject(36 + index);
                assertEquals(index == 0 ? "PASS" : "FAIL", summary.getString("expected_status"));
                assertEquals("ERROR", summary.getString("status"));
                assertEquals("ERROR", summary.getString("actual_status"));
                JSONObject detail = new JSONObject(reportText(new File(test.run.directory,
                        "control-" + summary.getString("id") + ".json")));
                assertEquals("ERROR", detail.getString("status"));
                assertEquals("ERROR", detail.getString("actual_status"));
            }
            assertEquals("PASS", persisted.getJSONObject(38).getString("status"));
            assertEquals("FAIL", persisted.getJSONObject(38).getString("actual_status"));
            assertTrue(reportText(test.external).contains("ERROR CONTROL music_unchanged"));
            assertTrue(reportText(test.external).contains("PASS CONTROL music_no_loop"));
        }
    }

    public void testMusicReturnedDecoderErrorCannotPassNegativeControl() throws Exception {
        try (ReportingRun test = new ReportingRun()) {
            JSONObject[] results = {musicResult("PASS", true), musicResult("ERROR", false),
                    musicResult("FAIL", false)};
            test.runner.runMusicControls(test.run, index -> results[index]);
            assertEquals("PASS", test.summary(0).getString("status"));
            assertEquals("FAIL", test.summary(1).getString("expected_status"));
            assertEquals("ERROR", test.summary(1).getString("actual_status"));
            assertEquals("ERROR", test.summary(1).getString("status"));
            assertEquals("PASS", test.summary(2).getString("status"));
        }
    }

    public void testMusicUnexpectedRuntimeExceptionStillSurfaces() throws Exception {
        try (ReportingRun test = new ReportingRun()) {
            IllegalStateException failure = new IllegalStateException("unrelated programming failure");
            int[] calls = {0};
            try {
                test.runner.runMusicControls(test.run, index -> {
                    calls[0]++;
                    throw failure;
                });
                fail("RuntimeException must not be converted into a control result");
            } catch (IllegalStateException actual) {
                assertSame(failure, actual);
            }
            assertEquals(1, calls[0]);
            assertEquals("NOT RUN", test.summary(1).getString("actual_status"));
            assertTrue(new File(test.run.directory, "suite.json").isFile());
        }
    }

    public void testDecoderCheckpointSaveFailurePreventsRiskyWork() throws Exception {
        try (ReportingRun test = new ReportingRun()) {
            test.run.savedReport = new SavedReport(test.context, Uri.fromFile(test.root),
                    "directory is not a writable TXT");
            Map<String, Object> diagnostics = new LinkedHashMap<>();
            diagnostics.put("stage", "before_configure");
            try {
                test.runner.decoderObserver(test.run).checkpoint(diagnostics);
                fail("Failed durable checkpoint must throw before configure can start");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("EXTERNAL TXT SAVE FAILED"));
            }
            assertTrue(reportText(test.run.report).contains("before_configure"));
            String snapshot = reportText(SavedReport.snapshot(test.context));
            assertTrue(snapshot.contains("before_configure"));
            assertTrue(snapshot.contains("EXTERNAL TXT SAVE FAILED"));
        }
    }

    private static JSONObject musicResult(String status, boolean passed) throws JSONException {
        return new JSONObject().put("status", status).put("candidate_sha256", "injected")
                .put("checks", new JSONArray().put(new JSONObject()
                        .put("assertion", "audio.window_0.pitch").put("passed", passed)));
    }

    public void testDiagnosticIOExceptionWinsConcurrentEngineCompletionWithoutMainDeadlock()
            throws Exception {
        SelfTestRunner.Completion<File> completion = new SelfTestRunner.Completion<>();
        IOException original = new IOException("durable checkpoint");
        AtomicReference<Throwable> workerResult = new AtomicReference<>();
        CountDownLatch workerDone = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try {
                completion.await(5, TimeUnit.SECONDS);
            } catch (Exception error) {
                workerResult.set(error);
            } finally {
                workerDone.countDown();
            }
        });
        worker.start();
        CountDownLatch mainDone = new CountDownLatch(1);
        AtomicReference<RuntimeException> abort = new AtomicReference<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                completion.checkpoint("identity", "before-configure", message -> { throw original; });
            } catch (RuntimeException error) {
                abort.set(error);
                completion.completeExceptionally(new RuntimeException("engine wrapper", error));
                completion.complete(new File("must-not-win"));
            } finally {
                mainDone.countDown();
            }
        });
        assertTrue("Main callback blocked", mainDone.await(5, TimeUnit.SECONDS));
        assertTrue(workerDone.await(5, TimeUnit.SECONDS));
        worker.join(5000);
        assertSame(original, abort.get().getCause());
        assertSame(original, workerResult.get());
        try {
            completion.await(1, TimeUnit.SECONDS);
            fail("Late success replaced diagnostic failure");
        } catch (IOException actual) {
            assertSame(original, actual);
        }
        try (ReportingRun test = new ReportingRun()) {
            test.runner.logExportProgress(test.run, new SelfTestRunner.Completion<>(), "pending progress");
            assertTrue(reportText(test.run.report).contains("pending progress"));
            assertTrue(test.external.delete());
            assertTrue(test.external.mkdir());
            // Force completion before the first progress write, independent of thread scheduling.
            test.runner.logExportProgress(test.run, completion, "must not overwrite original failure");
            assertFalse(reportText(test.run.report).contains("must not overwrite original failure"));
            try {
                completion.await(1, TimeUnit.SECONDS);
                fail("Progress replaced diagnostic failure");
            } catch (IOException actual) {
                assertSame(original, actual);
            }
        }
    }

    public void testDiagnosticRuntimeRemainsOriginalRuntime() throws Exception {
        SelfTestRunner.Completion<File> completion = new SelfTestRunner.Completion<>();
        IllegalStateException original = new IllegalStateException("callback bug");
        try {
            completion.checkpoint("identity", "before-configure", message -> { throw original; });
            fail("Callback must abort");
        } catch (IllegalStateException actual) {
            assertSame(original, actual);
        }
        completion.completeExceptionally(new IOException("later storage error"));
        try {
            completion.await(1, TimeUnit.SECONDS);
            fail("Unexpected runtime was swallowed");
        } catch (IllegalStateException actual) {
            assertSame(original, actual);
        }
    }

    public void testCompletionSuccessAndErrorAreFirstWins() throws Exception {
        SelfTestRunner.Completion<File> completion = new SelfTestRunner.Completion<>();
        File first = new File("first");
        completion.complete(first);
        completion.complete(new File("second"));
        completion.completeExceptionally(new IOException("late"));
        assertSame(first, completion.await(1, TimeUnit.SECONDS));
    }

    public void testNativeEncoderDiagnosticStorageFailurePersistsCaseError() throws Exception {
        assertNativeDiagnosticFailure("before-configure", false);
    }

    public void testMainDiagnosticStorageFailureAbortsWithoutDeadlock() throws Exception {
        assertNativeDiagnosticFailure("timeline trimMs=", false);
    }

    public void testNativeEncoderDiagnosticRuntimeRemainsRuntime() throws Exception {
        assertNativeDiagnosticFailure("before-configure", true);
    }

    private void assertNativeDiagnosticFailure(String marker, boolean runtime) throws Exception {
        try (ReportingRun test = new ReportingRun()) {
            File fixture = new OracleVerifier(test.context).prepareFixture();
            File output = new File(test.root, "injected-export.mp4");
            EditConfig config = new EditConfig.Builder(Uri.fromFile(fixture), 4000)
                    .sourceSize(320, 240).build();
            AtomicReference<Exception> injected = new AtomicReference<>();
            StringBuilder diagnostics = new StringBuilder();
            test.run.caseResults.put(new JSONObject().put("id", "identity"));
            Future<Exception> result = test.run.worker.submit(() -> {
                try {
                    test.runner.export(test.run, config, output, "identity", message -> {
                        diagnostics.append(message);
                        synchronized (test.run) {
                            if (message.contains(marker) && injected.get() == null) {
                                if (runtime) {
                                    IllegalStateException error = new IllegalStateException("callback bug");
                                    injected.set(error);
                                    throw error;
                                }
                                assertTrue(test.external.delete());
                                assertTrue(test.external.mkdir());
                            }
                            try {
                                test.runner.log(test.run, message);
                            } catch (IOException error) {
                                injected.compareAndSet(null, error);
                                throw error;
                            }
                        }
                    });
                    return null;
                } catch (IOException error) {
                    try {
                        test.runner.recordExportFailure(test.run, 0, output, error);
                    } catch (IOException stillUnavailable) {
                        assertTrue(stillUnavailable.getMessage().contains("previously failed"));
                    }
                    return error;
                } catch (RuntimeException error) {
                    return error;
                }
            });
            Exception actual = result.get(45, TimeUnit.SECONDS);
            assertNotNull("Injection never reached", injected.get());
            assertSame("Worker must receive original, not engine wrapper", injected.get(), actual);
            assertFalse("Partial output survived mandatory abort", output.exists());
            assertFalse("Encoder secretly continued", diagnostics.toString().contains("configured attempt="));
            CountDownLatch responsive = new CountDownLatch(1);
            new Handler(Looper.getMainLooper()).post(responsive::countDown);
            assertTrue("Native cleanup deadlocked main", responsive.await(5, TimeUnit.SECONDS));
            if (runtime) {
                assertTrue(actual instanceof IllegalStateException);
                assertNull(test.run.status[0]);
            } else {
                assertTrue(actual instanceof IOException);
                assertTrue(actual.getMessage().contains("EXTERNAL TXT SAVE FAILED"));
                JSONObject detail = new JSONObject(reportText(new File(test.root, "identity.json")));
                assertEquals("ERROR", detail.getString("status"));
                assertEquals("ERROR", detail.getString("actual_status"));
                assertEquals("PASS", detail.getString("expected_status"));
                JSONObject suite = new JSONObject(reportText(new File(test.root, "suite.json")));
                assertEquals("UNVERIFIED", suite.getString("status"));
                assertEquals("ERROR", suite.getJSONArray("exports").getJSONObject(0).getString("status"));
                assertTrue(reportText(SavedReport.snapshot(test.context)).contains("EXTERNAL TXT SAVE FAILED"));
                assertTrue(SavedReport.preferences(test.context).getString("error", "")
                        .contains("EXTERNAL TXT SAVE FAILED"));
                assertTrue(reportText(test.run.report).contains("ERROR identity"));
            }
        }
    }

    public void testEncoderCheckpointFailureCannotPassNegativeControlOrContinue() throws Exception {
        try (ReportingRun test = new ReportingRun()) {
            File fixture = new OracleVerifier(test.context).prepareFixture();
            EditConfig config = new EditConfig.Builder(Uri.fromFile(fixture), 4000)
                    .sourceSize(320, 240).build();
            int[] calls = {0};
            JSONObject positive = musicResult("PASS", true);
            JSONObject rejected = musicResult("FAIL", false);
            Future<IOException> result = test.run.worker.submit(() -> {
                try {
                    test.runner.runMusicControls(test.run, index -> {
                        calls[0]++;
                        if (index == 0) return positive;
                        try {
                            test.runner.export(test.run, config, new File(test.root, "negative.mp4"),
                                    "negative", message -> {
                                        if (message.contains("before-configure")) {
                                            assertTrue(test.external.delete());
                                            assertTrue(test.external.mkdir());
                                        }
                                        test.runner.log(test.run, message);
                                    });
                        } catch (InterruptedException unexpected) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(unexpected);
                        } catch (java.util.concurrent.TimeoutException unexpected) {
                            throw new AssertionError(unexpected);
                        }
                        return rejected;
                    });
                    return null;
                } catch (IOException error) {
                    return error;
                }
            });
            assertNotNull("Storage failure must stop controls", result.get(45, TimeUnit.SECONDS));
            assertEquals(2, calls[0]);
            assertEquals("FAIL", test.summary(1).getString("expected_status"));
            assertEquals("ERROR", test.summary(1).getString("status"));
            assertEquals("ERROR", test.summary(1).getString("actual_status"));
            assertEquals("NOT RUN", test.summary(2).getString("actual_status"));
            JSONObject detail = new JSONObject(reportText(new File(test.root,
                    "control-music_unchanged.json")));
            assertEquals("ERROR", detail.getString("status"));
            assertTrue(reportText(SavedReport.snapshot(test.context)).contains("EXTERNAL TXT SAVE FAILED"));
        }
    }

    private static String reportText(File file) throws IOException {
        return SavedReportTest.read(ReportJournal.open(file));
    }

    private final class ReportingRun implements AutoCloseable {
        final Context context;
        final File root;
        final File external;
        final SelfTestRunner runner;
        final SelfTestRunner.Run run;

        ReportingRun() throws Exception {
            Context target = getInstrumentation().getTargetContext();
            root = new File(target.getFilesDir(), "reporting-injection-" + UUID.randomUUID());
            assertTrue(root.mkdir());
            context = new ContextWrapper(target) {
                @Override public Context getApplicationContext() { return this; }
                @Override public File getFilesDir() { return root; }
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return super.getSharedPreferences(root.getName() + name, mode);
                }
            };
            external = new File(root, "external.txt");
            runner = new SelfTestRunner(context, (text, running) -> {});
            run = runner.new Run();
            run.directory = root;
            run.report = new File(root, "report.txt");
            // Local disk URI tests persistence plumbing, not SAF picker permissions.
            run.savedReport = new SavedReport(context, Uri.fromFile(external), "injected local TXT");
            for (int index = 0; index < 36; index++) run.controlResults.put(new JSONObject());
            String[] ids = {"music_reference", "music_unchanged", "music_no_loop"};
            for (int index = 0; index < ids.length; index++) {
                run.controlResults.put(new JSONObject().put("id", ids[index])
                        .put("expected_status", index == 0 ? "PASS" : "FAIL")
                        .put("actual_status", "NOT RUN").put("status", "UNVERIFIED"));
            }
        }

        JSONObject summary(int index) { return run.controlResults.optJSONObject(36 + index); }

        @Override public void close() {
            run.savedReport.release();
            run.worker.shutdownNow();
            SavedReport.preferences(context).edit().clear().commit();
            deleteOwned(root);
        }

        private void deleteOwned(File file) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteOwned(child);
            assertTrue("Cannot remove reporting test artifact " + file, file.delete());
        }
    }
}
