package com.simple.videoeditor;

import android.content.Context;
import android.content.ContextWrapper;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.system.Os;
import android.test.AndroidTestCase;

import androidx.media3.common.util.UnstableApi;

import com.simple.videoeditor.oracle.IntroOracleContract;
import com.simple.videoeditor.oracle.OracleContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Frozen expectations are independent of the production Media3 export. */
@UnstableApi
public final class IntroOracleTest extends AndroidTestCase {
    private File directory;

    public void testFrozenReferencePasses() throws Exception {
        verifyControl("reference_imported_intro", IntroOracleContract.REFERENCE, true);
    }

    public void testMissingIntroFailsBeforeFrameDecoding() throws Exception {
        JSONObject report = verifyControl("missing_intro", IntroOracleContract.MISSING_INTRO, false);
        assertCheck(report, "video.duration", false);
        assertCheck(report, "video.frames_present", false);
        assertFrameCountNotMeasured(report);
    }

    public void testWrongDurationFailsBeforeFrameDecoding() throws Exception {
        JSONObject report = verifyControl("wrong_duration", IntroOracleContract.WRONG_DURATION, false);
        assertCheck(report, "video.duration", false);
        assertCheck(report, "video.frames_present", false);
        assertFrameCountNotMeasured(report);
    }

    private void assertFrameCountNotMeasured(JSONObject report) throws JSONException {
        JSONArray checks = report.getJSONArray("checks");
        for (int i = 0; i < checks.length(); i++) {
            assertFalse("Metadata rejection must not claim a decoded frame count",
                    "video.frame_count".equals(checks.getJSONObject(i).getString("assertion")));
        }
    }

    public void testReversedOrderFailsContentNotDurationOrFrameCount() throws Exception {
        JSONObject report = verifyControl("reversed_order", IntroOracleContract.REVERSED_ORDER, false);
        assertCheck(report, "video.duration", true);
        assertCheck(report, "video.frame_count", true);
        assertCheck(report, "video.temporal_barcode", false);
        assertCheck(report, "audio.window_0.frequency", false);
    }

    public void testWrongOriginalAudioPreservesVideoAndIntroAudio() throws Exception {
        JSONObject report = verifyControl("wrong_original_audio",
                IntroOracleContract.WRONG_ORIGINAL_AUDIO, false);
        assertChecksWithPrefixPass(report, "video.");
        assertCheck(report, "video.duration", true);
        assertCheck(report, "video.frame_count", true);
        assertCheck(report, "video.temporal_barcode", true);
        assertEquals(15, IntroOracleContract.CASES.get(0).audioProbes.size());
        for (int window = 0; window < 15; window++) {
            assertCheck(report, "audio.window_" + window + ".samples", true);
            assertCheck(report, "audio.window_" + window + ".rms", window < 3);
            if (window < 3) {
                assertCheck(report, "audio.window_" + window + ".frequency", true);
                assertChecksWithPrefixPass(report, "audio.window_" + window + ".");
            }
        }
    }

    public void testFormatterRequiresCanonicalDecoderObject() throws Exception {
        JSONObject report = verifyControl("reference_imported_intro", IntroOracleContract.REFERENCE, true);
        Object decoder = report.remove(OracleVerifier.DECODER_KEY);
        report.put("decode", decoder);
        assertFormatterRejectsDecoder(report);
        report.put(OracleVerifier.DECODER_KEY, "not an object");
        assertFormatterRejectsDecoder(report);
        report.put(OracleVerifier.DECODER_KEY, JSONObject.NULL);
        assertFormatterRejectsDecoder(report);
        report.remove("decode");
        report.put(OracleVerifier.DECODER_KEY, decoder);
        assertFormatted(report, true);
    }

    public void testMedia3ImportedIntroExportPassesFrozenOracle() throws Exception {
        OracleContract contract = IntroOracleContract.create();
        OracleVerifier verifier = new OracleVerifier(getContext(), contract);
        File fixture = verifier.prepareFixture();
        File intro = verifier.prepareIntroAsset(IntroOracleContract.INTRO);
        assertAsset(fixture, contract.fixture);
        assertAsset(intro, IntroOracleContract.INTRO);
        JSONObject config = new JSONObject(contract.requireCase(IntroOracleContract.CASE_ID).androidEditConfig);
        assertEquals(IntroOracleContract.ORIGINAL_DURATION_MS, config.getLong("sourceDurationMs"));
        assertEquals(0L, config.getLong("startMs"));
        assertEquals(IntroOracleContract.ORIGINAL_DURATION_MS, config.getLong("endMs"));
        assertEquals(1d, config.getDouble("volume"), 0d);
        EditConfig edit = new EditConfig.Builder(Uri.fromFile(fixture), config.getLong("sourceDurationMs"))
                .sourceSize(contract.source.width, contract.source.height)
                .trim(config.getLong("startMs"), config.getLong("endMs"))
                .crop((float) config.getDouble("cropLeft"), (float) config.getDouble("cropTop"),
                        (float) config.getDouble("cropRight"), (float) config.getDouble("cropBottom"))
                .rotation(config.getInt("rotationDegrees")).outputHeight(config.getInt("outputHeight"))
                .speed((float) config.getDouble("speed")).volume((float) config.getDouble("volume"))
                .overlayText(config.getString("overlayText")).intro(Uri.fromFile(intro)).build();
        AtomicReference<String> codecs = new AtomicReference<>();
        try {
            File output = export(edit, codecs);
            JSONObject report = verifier.verify(IntroOracleContract.CASE_ID, output);
            assertReport(report, true, sha256(output), false);
            assertNotNull("Missing actual encoder diagnostics", codecs.get());
            addFormatterFields(report, codecs.get());
            assertFormatted(report, true);
        } finally {
            assertAsset(fixture, contract.fixture);
            assertAsset(intro, IntroOracleContract.INTRO);
        }
    }

    public void testIntroAssetRejectsCanonicalFileSymlink() throws Exception {
        assertIntroSymlinkRejected(false);
    }

    public void testIntroAssetRejectsCanonicalDirectorySymlink() throws Exception {
        assertIntroSymlinkRejected(true);
    }

    private JSONObject verifyControl(String id, OracleContract.Asset asset, boolean expectedPass)
            throws Exception {
        assertEquals(5, IntroOracleContract.CONTROLS.size());
        IntroOracleContract.Control selected = null;
        Set<String> ids = new HashSet<>();
        for (IntroOracleContract.Control control : IntroOracleContract.CONTROLS) {
            assertTrue("Duplicate control " + control.id, ids.add(control.id));
            if (id.equals(control.id)) selected = control;
        }
        assertNotNull("Missing frozen control " + id, selected);
        assertEquals(IntroOracleContract.CASE_ID, selected.caseId);
        assertSame(asset, selected.asset);
        assertEquals(expectedPass, selected.expectedPass);
        OracleVerifier verifier = new OracleVerifier(getContext(), IntroOracleContract.create());
        File candidate = verifier.prepareIntroAsset(asset);
        assertAsset(candidate, asset);
        JSONObject report;
        try {
            // IOException/decode failures propagate; they are never successful negative controls.
            report = verifier.verify(IntroOracleContract.CASE_ID, candidate);
        } finally {
            assertAsset(candidate, asset);
        }
        assertReport(report, expectedPass, asset.sha256,
                selected.requiredFailures.contains("video.duration"));
        for (String assertion : selected.requiredFailures) assertCheck(report, assertion, false);
        addFormatterFields(report, "ENCODER not exercised: frozen intro control " + id);
        assertFormatted(report, expectedPass);
        return report;
    }

    private static void assertReport(JSONObject report, boolean expectedPass, String hash,
                                     boolean metadataRejected) throws Exception {
        OracleContract contract = IntroOracleContract.create();
        String diagnostic = report.toString();
        assertEquals(diagnostic, IntroOracleContract.CASE_ID, report.getString("case"));
        assertEquals(diagnostic, expectedPass ? "PASS" : "FAIL", report.getString("status"));
        assertEquals(diagnostic, expectedPass, report.getBoolean("passed"));
        assertEquals(hash, report.getString("candidate_sha256"));
        assertEquals("independent-video-oracle-android-report", report.getString("schema"));
        assertEquals("1.0.0", report.getString("version"));
        assertEquals(contract.schema, report.getString("contract_schema"));
        assertEquals(IntroOracleContract.VERSION, report.getString("contract_version"));
        JSONObject pins = report.getJSONObject("pins");
        assertEquals(contract.fixture.sha256, pins.getJSONObject("fixture").getString("sha256"));
        assertEquals(contract.pins.assetContractSha256,
                pins.getJSONObject("asset_contract").getString("sha256"));
        assertEquals(contract.pins.manifestSha256, pins.getJSONObject("manifest").getString("sha256"));
        assertEquals("decoder", OracleVerifier.DECODER_KEY);
        for (String track : new String[]{"video", "audio"}) {
            JSONObject decoder = report.getJSONObject(OracleVerifier.DECODER_KEY).getJSONObject(track);
            assertTrue(diagnostic, decoder.getBoolean("present"));
            assertTrue(decoder.getJSONObject("input_format").getString("mime").length() > 0);
            assertTrue(diagnostic, decoder.has("decoder_name"));
            assertTrue(diagnostic, decoder.has("output_format"));
            if (metadataRejected) {
                // The duration gate probes tracks but must not instantiate either decoder.
                assertTrue(diagnostic, decoder.isNull("decoder_name"));
                assertTrue(diagnostic, decoder.isNull("output_format"));
                continue;
            }
            assertFalse(diagnostic, decoder.isNull("decoder_name"));
            assertTrue(diagnostic, decoder.getString("decoder_name").length() > 0);
            JSONObject output = decoder.getJSONObject("output_format");
            assertTrue(output.getString("mime").length() > 0);
            assertTrue(output.has("color-standard"));
            assertTrue(output.has("color-range"));
            assertTrue(output.has("color-transfer"));
        }
        JSONArray checks = report.getJSONArray("checks");
        assertTrue(diagnostic, checks.length() > 0);
        Set<String> assertions = new HashSet<>();
        boolean contentFailure = false;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            String assertion = check.getString("assertion");
            assertTrue("Duplicate assertion " + assertion, assertions.add(assertion));
            boolean passed = check.getBoolean("passed");
            if (expectedPass || assertion.startsWith("checker.") || assertion.startsWith("decode.")
                    || assertion.startsWith("decoder.")) assertTrue(check.toString(), passed);
            if (!passed && (assertion.startsWith("video.") || assertion.startsWith("audio."))) {
                contentFailure = true;
            }
        }
        if (!expectedPass) assertTrue("No content mismatch: " + diagnostic, contentFailure);
        for (String assertion : new String[]{"file.exists", "file.bounded",
                "video.stream_count", "audio.stream_count", "video.decode_bound"}) {
            assertCheck(report, assertion, true);
        }
        assertCheck(report, "video.duration", !metadataRejected);
        assertCheck(report, "video.frames_present", !metadataRejected);
    }

    private static void assertCheck(JSONObject report, String assertion, boolean expected) throws Exception {
        JSONArray checks = report.getJSONArray("checks");
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (assertion.equals(check.getString("assertion"))) {
                assertEquals(check.toString(), expected, check.getBoolean("passed"));
                return;
            }
        }
        fail("Missing assertion " + assertion + ": " + report);
    }

    private static void assertChecksWithPrefixPass(JSONObject report, String prefix) throws Exception {
        JSONArray checks = report.getJSONArray("checks");
        int matched = 0;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (check.getString("assertion").startsWith(prefix)) {
                matched++;
                assertTrue(check.toString(), check.getBoolean("passed"));
            }
        }
        assertTrue("No assertions for " + prefix, matched > 0);
    }

    private static void addFormatterFields(JSONObject report, String codecs) throws Exception {
        report.put("export_codecs", codecs).put("reference", new JSONObject()
                .put("path", IntroOracleContract.REFERENCE.path)
                .put("bytes", IntroOracleContract.REFERENCE.bytes)
                .put("sha256", IntroOracleContract.REFERENCE.sha256));
    }

    private static void assertFormatted(JSONObject report, boolean passed) throws Exception {
        String summary = SelfTestRunner.formatCaseResult(report);
        assertTrue(summary, summary.startsWith((passed ? "PASS " : "FAIL ")
                + IntroOracleContract.CASE_ID + "\n"));
        assertTrue(summary, summary.contains("\nOUTPUT SHA256 " + report.getString("candidate_sha256")));
        assertTrue(summary, summary.contains("\nDECODER " + report.getJSONObject(OracleVerifier.DECODER_KEY)));
        assertTrue(summary, summary.endsWith("Full measurements: " + IntroOracleContract.CASE_ID + ".json\n"));
    }

    private static void assertFormatterRejectsDecoder(JSONObject report) throws Exception {
        try {
            SelfTestRunner.formatCaseResult(report);
            fail("Missing/malformed canonical decoder evidence must be a schema error");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(OracleVerifier.DECODER_KEY));
        }
    }

    private File export(EditConfig config, AtomicReference<String> codecs) throws Exception {
        assertTrue("Do not block main looper", Looper.myLooper() != Looper.getMainLooper());
        File output = new File(testDirectory(), "export.mp4");
        Handler main = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(main.post(() -> {
            try {
                engine.set(new Media3ExportEngine(getContext()));
                engine.get().export(config, output, new Media3ExportEngine.Listener() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onCodecs(String video, String audio) {
                        codecs.set("ENCODER video=" + video + ", audio=" + audio);
                    }
                    @Override public void onCompleted(File file) { done.countDown(); }
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
            assertTrue("Export exceeded 200s", done.await(200, TimeUnit.SECONDS));
            if (failure.get() != null) throw failure.get();
            assertTrue("Export callback without output", output.isFile() && output.length() > 0);
            return output;
        } finally {
            CountDownLatch stopped = new CountDownLatch(1);
            assertTrue(main.post(() -> {
                try {
                    if (engine.get() != null) engine.get().cancel();
                } finally {
                    stopped.countDown();
                }
            }));
            assertTrue("Main-thread cancellation exceeded 10s", stopped.await(10, TimeUnit.SECONDS));
        }
    }

    private void assertIntroSymlinkRejected(boolean linkDirectory) throws Exception {
        final File files = testDirectory().getCanonicalFile();
        Context isolated = new ContextWrapper(getContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getFilesDir() { return files; }
        };
        OracleVerifier verifier = new OracleVerifier(isolated, IntroOracleContract.create());
        File prepared = verifier.prepareIntroAsset(IntroOracleContract.INTRO);
        assertAsset(prepared, IntroOracleContract.INTRO);
        File link = linkDirectory ? prepared.getParentFile() : prepared;
        File target = new File(files, linkDirectory ? "original-directory" : "original.mp4");
        assertTrue(link.renameTo(target));
        File preserved = linkDirectory ? new File(target, prepared.getName()) : target;
        Os.symlink(target.getAbsolutePath(), link.getAbsolutePath());
        try {
            try {
                verifier.prepareIntroAsset(IntroOracleContract.INTRO);
                fail("Canonical symlink must be rejected, even when its target has valid frozen bytes");
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
            assertAsset(preserved, IntroOracleContract.INTRO);
        } finally {
            Os.remove(link.getAbsolutePath());
        }
    }

    private File testDirectory() throws IOException {
        if (directory == null) {
            directory = new File(getContext().getFilesDir().getCanonicalFile(),
                    "intro-oracle-test-" + UUID.randomUUID());
            assertTrue(directory.mkdirs());
        }
        return directory;
    }

    private static void assertAsset(File file, OracleContract.Asset asset) throws Exception {
        assertTrue("Missing " + file, file.isFile());
        assertEquals("Changed size: " + file, asset.bytes, file.length());
        assertEquals("Changed bytes: " + file, asset.sha256, sha256(file));
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder hex = new StringBuilder(64);
        for (byte value : digest.digest()) {
            hex.append(Character.forDigit((value & 0xff) >>> 4, 16));
            hex.append(Character.forDigit(value & 0xf, 16));
        }
        return hex.toString();
    }

    private static void deleteOwned(File file) throws IOException {
        if (file.getCanonicalFile().equals(file.getAbsoluteFile()) && file.isDirectory()) {
            File[] children = file.listFiles();
            assertNotNull(children);
            for (File child : children) deleteOwned(child);
        }
        assertTrue("Cannot delete " + file, file.delete());
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (directory != null) deleteOwned(directory);
        } finally {
            super.tearDown();
        }
    }
}
