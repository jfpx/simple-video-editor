package com.simple.videoeditor;

import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;

import androidx.media3.common.util.UnstableApi;

import com.simple.videoeditor.oracle.OracleContract;
import com.simple.videoeditor.oracle.OracleGeneratedContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@UnstableApi
public final class EmulatorValidationTest extends AndroidTestCase {
    private static final long SELFTEST_WAIT_MS = TimeUnit.MINUTES.toMillis(17);
    private static final long EXPORT_WAIT_SECONDS = 200L;
    private static final long CLEANUP_WAIT_SECONDS = 10L;

    public void testCompatibilityProductionSelfTestRunnerTerminalSuiteJson() throws Exception {
        boolean original = VideoEncodingSettings.compatibilityEnabled(getContext());
        try {
            VideoEncodingSettings.setCompatibilityEnabled(getContext(), true);
            testProductionSelfTestRunnerTerminalSuiteJson();
        } finally {
            VideoEncodingSettings.setCompatibilityEnabled(getContext(), original);
        }
    }

    public void testProductionSelfTestRunnerTerminalSuiteJson() throws Exception {
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<String> terminalReport = new AtomicReference<>();
        final SelfTestRunner runner = new SelfTestRunner(getContext(), (report, running) -> {
            if (!running) {
                terminalReport.set(report);
                finished.countDown();
            }
        });

        runner.start();
        try {
            assertTrue("SelfTestRunner exceeded the 15-minute suite budget plus cleanup margin",
                    finished.await(SELFTEST_WAIT_MS, TimeUnit.MILLISECONDS));

            assertFalse("Runner still marked active after terminal report", runner.isRunning());
            File reportFile = runner.getReportFile();
            assertNotNull(reportFile);
            assertTrue("Missing terminal report file", reportFile.isFile());

            File runDir = reportFile.getParentFile();
            File suiteFile = new File(runDir, "suite.json");
            File outputsFile = new File(runDir, "outputs.txt");
            assertTrue("Missing persisted suite JSON", suiteFile.isFile());

            JSONObject suite = readJson(suiteFile);
            assertEquals("See " + suiteFile + ": " + suite.optString("fatal_error"),
                    "PASS", suite.getString("status"));
            assertTrue("Missing persisted output manifest", outputsFile.isFile());
            assertTrue(suite.getBoolean("complete"));
            assertEquals(17, suite.getInt("planned_export_count"));
            assertEquals(60, suite.getInt("planned_control_count"));

            JSONArray exports = suite.getJSONArray("exports");
            JSONArray controls = suite.getJSONArray("checker_controls");
            assertEquals(17, exports.length());
            assertEquals(60, controls.length());

            List<String> outputNames = readLines(outputsFile);
            assertEquals(17, outputNames.size());
            assertExportsPassAndPersisted(exports, outputNames, runDir);
            assertControlsPassAndRejected(controls);

            String finalReport = terminalReport.get();
            assertNotNull(finalReport);
            assertTrue(finalReport.contains("17/17 exports verified"));
            assertTrue(finalReport.contains("checker controls 60/60"));
        } finally {
            if (runner.isRunning()) {
                runner.cancel();
                assertTrue("SelfTestRunner cleanup exceeded 10 seconds",
                        finished.await(CLEANUP_WAIT_SECONDS, TimeUnit.SECONDS));
            }
        }
    }

    public void testSmokeRotate90ExportThroughProductionEngine() throws Exception {
        assertProductionExport("rotate90");
    }

    public void testSpeedAndComboExportsThroughProductionEngine() throws Exception {
        for (String caseId : new String[]{"speed2", "speed_half", "combo"}) {
            assertProductionExport(caseId);
        }
    }

    private void assertProductionExport(String caseId) throws Exception {
        File smokeDir = new File(getContext().getFilesDir(), "emulator-validation");
        assertTrue(smokeDir.isDirectory() || smokeDir.mkdirs());

        OracleContract contract = OracleGeneratedContract.create();
        OracleContract.OracleCase oracleCase = contract.requireCase(caseId);
        OracleVerifier verifier = new OracleVerifier(getContext(), contract);
        File fixture = verifier.prepareFixture();
        EditConfig config = editConfig(fixture, contract.source.width, contract.source.height, oracleCase);
        assertEquals(oracleCase.operation.rotationDegrees, config.rotationDegrees);

        String runId = caseId + "-" + UUID.randomUUID().toString();
        File output = new File(smokeDir, runId + ".mp4");
        File reportFile = new File(smokeDir, runId + ".json");
        JSONObject seed = new JSONObject()
                .put("case", oracleCase.id)
                .put("status", "RUNNING")
                .put("output", output.getAbsolutePath())
                .put("report", reportFile.getAbsolutePath());
        writeJson(reportFile, seed);

        Handler main = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<String> codecs = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<File> completedOutput = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(main.post(() -> {
            try {
                engine.set(new Media3ExportEngine(getContext()));
                engine.get().export(config, output, new Media3ExportEngine.Listener() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onCodecs(String video, String audio) {
                        codecs.set("ENCODER video=" + video + ", audio=" + audio);
                    }
                    @Override public void onCompleted(File file) {
                        completedOutput.set(file);
                        done.countDown();
                    }
                    @Override public void onError(Exception error) {
                        failure.set(error);
                        done.countDown();
                    }
                });
            } catch (Exception error) {
                failure.set(error);
                done.countDown();
            }
        }));

        try {
            if (!done.await(EXPORT_WAIT_SECONDS, TimeUnit.SECONDS)) {
                writeSmokeFailureReport(reportFile, oracleCase.id, output, codecs.get(),
                        completedOutput.get(), "Export exceeded the bounded main-thread wait");
                fail("Export exceeded the bounded main-thread wait");
            }
            if (failure.get() != null) {
                throw failure.get();
            }
            assertNotNull("Missing completed output callback", completedOutput.get());
            assertEquals("Export completed with the wrong file",
                    output.getAbsoluteFile(), completedOutput.get().getAbsoluteFile());
            assertNotNull("Missing actual codec diagnostics", codecs.get());
            assertTrue("Missing exported output", output.isFile() && output.length() > 0);

            JSONObject report = verifier.verify(oracleCase.id, output);
            report.put("export_codecs", codecs.get());
            report.put("smoke_output", output.getName());
            writeJson(reportFile, report);

            assertEquals("PASS", report.getString("status"));
            assertEquals(sha256(output), report.getString("candidate_sha256"));
            assertDecoderDiagnostics(report);
            assertAllOracleAssertionsPass(report);
        } catch (Exception | AssertionError error) {
            writeSmokeFailureReport(reportFile, oracleCase.id, output, codecs.get(),
                    completedOutput.get(), error.toString());
            throw error;
        } finally {
            CountDownLatch cleanup = new CountDownLatch(1);
            assertTrue(main.post(() -> {
                try {
                    if (engine.get() != null) {
                        engine.get().cancel();
                    }
                } finally {
                    cleanup.countDown();
                }
            }));
            if (!cleanup.await(CLEANUP_WAIT_SECONDS, TimeUnit.SECONDS)) {
                writeSmokeFailureReport(reportFile, oracleCase.id, output, codecs.get(),
                        completedOutput.get(), "Main-thread cleanup exceeded 10 seconds");
                fail("Main-thread cleanup exceeded 10 seconds");
            }
        }
    }

    private static void assertExportsPassAndPersisted(JSONArray exports, List<String> outputNames,
                                                      File runDir) throws Exception {
        for (int i = 0; i < exports.length(); i++) {
            JSONObject export = exports.getJSONObject(i);
            assertEquals("PASS", export.getString("status"));
            assertEquals("PASS", export.getString("expected_status"));
            assertEquals("PASS", export.getString("actual_status"));
            String reportName = export.getString("report");
            File reportFile = new File(runDir, reportName);
            assertTrue("Missing per-export report " + reportName, reportFile.isFile());
            JSONObject caseReport = readJson(reportFile);
            assertEquals("PASS", caseReport.getString("status"));
            assertEquals(export.getString("candidate_sha256"), caseReport.getString("candidate_sha256"));
            assertEquals(sha256(new File(runDir, outputNames.get(i))),
                    export.getString("candidate_sha256"));
            assertAllOracleAssertionsPass(caseReport);
            assertDecoderDiagnostics(caseReport);
        }
    }

    private static void assertControlsPassAndRejected(JSONArray controls) throws Exception {
        int positive = 0;
        int negative = 0;
        for (int i = 0; i < controls.length(); i++) {
            JSONObject control = controls.getJSONObject(i);
            assertEquals("PASS", control.getString("status"));
            String expected = control.getString("expected_status");
            String actual = control.getString("actual_status");
            assertEquals("Control status must match the frozen expectation", expected, actual);
            if ("PASS".equals(expected)) {
                positive++;
            } else if ("FAIL".equals(expected)) {
                negative++;
                assertEquals("Negative controls must be rejected, not accepted", "FAIL", actual);
            }
        }
        assertEquals(22, positive);
        assertEquals(38, negative);
    }

    private static void assertAllOracleAssertionsPass(JSONObject report) throws Exception {
        JSONArray checks = report.getJSONArray("checks");
        assertTrue("Missing oracle checks", checks.length() > 0);
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            assertTrue(check.toString(), check.getBoolean("passed"));
        }
    }

    private static void assertDecoderDiagnostics(JSONObject report) throws Exception {
        JSONObject decoder = report.getJSONObject(OracleVerifier.DECODER_KEY);
        assertTrackDiagnostics("video", decoder.getJSONObject("video"),
                actualStreamCount(report, "video.stream_count") > 0);
        assertTrackDiagnostics("audio", decoder.getJSONObject("audio"),
                actualStreamCount(report, "audio.stream_count") > 0);
    }

    private static int actualStreamCount(JSONObject report, String assertion) throws Exception {
        JSONArray checks = report.getJSONArray("checks");
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (assertion.equals(check.getString("assertion"))) {
                return check.getInt("actual");
            }
        }
        throw new AssertionError("Missing oracle assertion: " + assertion);
    }

    private static void assertTrackDiagnostics(String track, JSONObject diagnostics,
                                               boolean present) throws Exception {
        assertEquals(track + " track present mismatch", present, diagnostics.getBoolean("present"));
        if (!present) {
            assertTrue(track + " decoder name should be null", diagnostics.isNull("decoder_name"));
            assertTrue(track + " input format should be null", diagnostics.isNull("input_format"));
            assertTrue(track + " output format should be null", diagnostics.isNull("output_format"));
            return;
        }
        assertFalse(track + " decoder name missing", diagnostics.isNull("decoder_name"));
        assertTrue(track + " decoder name missing",
                diagnostics.getString("decoder_name").length() > 0);
        assertFalse(track + " input format missing", diagnostics.isNull("input_format"));
        assertTrue(track + " input mime missing",
                diagnostics.getJSONObject("input_format").getString("mime").length() > 0);
        JSONObject output = diagnostics.getJSONObject("output_format");
        assertTrue(track + " output mime missing", output.getString("mime").length() > 0);
    }

    private static void writeSmokeFailureReport(File reportFile, String caseId, File output,
                                                String codecs, File completedOutput,
                                                String error) throws Exception {
        JSONObject failure = reportFile.isFile() ? readJson(reportFile) : new JSONObject();
        failure.put("case", caseId)
                .put("status", "FAIL")
                .put("output", output.getAbsolutePath())
                .put("report", reportFile.getAbsolutePath())
                .put("error", error)
                .put("export_codecs", codecs == null ? JSONObject.NULL : codecs)
                .put("completed_output", completedOutput == null
                        ? JSONObject.NULL : completedOutput.getAbsolutePath())
                .put("output_exists", output.exists())
                .put("output_sha256", output.exists() ? sha256(output) : JSONObject.NULL);
        writeJson(reportFile, failure);
    }

    private static EditConfig editConfig(File fixture, int sourceWidth, int sourceHeight,
                                         OracleContract.OracleCase oracleCase)
            throws Exception {
        JSONObject config = new JSONObject(oracleCase.androidEditConfig);
        EditConfig.Builder builder = new EditConfig.Builder(
                android.net.Uri.fromFile(fixture), config.getLong("sourceDurationMs"))
                .sourceSize(sourceWidth, sourceHeight)
                .trim(config.getLong("startMs"), config.getLong("endMs"))
                .crop((float) config.getDouble("cropLeft"), (float) config.getDouble("cropTop"),
                        (float) config.getDouble("cropRight"), (float) config.getDouble("cropBottom"))
                .rotation(config.getInt("rotationDegrees"))
                .outputHeight(config.getInt("outputHeight"))
                .speed((float) config.getDouble("speed"))
                .volume((float) config.getDouble("volume"));
        if (config.has("overlayText")) {
            builder.overlayText(config.getString("overlayText"));
        }
        return builder.build();
    }

    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(readText(file));
    }

    private static List<String> readLines(File file) throws Exception {
        String text = readText(file);
        List<String> lines = new ArrayList<>();
        for (String line : text.split("\\r?\\n")) {
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }
        return lines;
    }

    private static String readText(File file) throws Exception {
        try (FileInputStream input = new FileInputStream(file);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static void writeJson(File file, JSONObject json) throws Exception {
        writeText(file, json.toString(2));
    }

    private static void writeText(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest()) {
            hex.append(String.format("%02x", value & 0xff));
        }
        return hex.toString();
    }
}
