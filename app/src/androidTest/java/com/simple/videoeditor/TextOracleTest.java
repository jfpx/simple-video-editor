package com.simple.videoeditor;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;
import androidx.media3.common.util.UnstableApi;
import com.simple.videoeditor.oracle.TextOracleContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Real phone decoding and production export; never invoked by local parity. */
@UnstableApi
public final class TextOracleTest extends AndroidTestCase {
    public void testPinnedControlsThroughAndroidDecoderAndFormatter() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getContext(), TextOracleContract.create());
        for (TextOracleContract.Control control : TextOracleContract.CONTROLS) {
            JSONObject report = verifier.verify(TextOracleContract.CASE_ID, verifier.prepareTextAsset(control.asset));
            assertEquals(control.expectedPass ? "PASS" : "FAIL", report.getString("status"));
            assertEquals(control.asset.sha256, report.getString("candidate_sha256"));
            for (String failure : control.requiredFailures) assertCheck(report, failure, false);
            assertCheck(report, "video.duration", true);
            assertCheck(report, "video.frame_count", true);
            assertCheck(report, "video.temporal_barcode", true);
            JSONArray checks = report.getJSONArray("checks");
            for (int i = 0; i < checks.length(); i++) {
                JSONObject check = checks.getJSONObject(i);
                if (!check.getString("assertion").startsWith("text.")) {
                    assertTrue(check.toString(), check.getBoolean("passed"));
                }
            }
            format(report);
        }
    }

    public void testMedia3ActualTextExportMatchesIndependentOracle() throws Exception {
        assertTrue(Looper.myLooper() != Looper.getMainLooper());
        OracleVerifier verifier = new OracleVerifier(getContext(), TextOracleContract.create());
        File fixture = verifier.prepareFixture();
        JSONObject config = new JSONObject(TextOracleContract.CASES.get(0).androidEditConfig);
        EditConfig edit = new EditConfig.Builder(Uri.fromFile(fixture), config.getLong("sourceDurationMs"))
                .sourceSize(320, 240).trim(config.getLong("startMs"), config.getLong("endMs"))
                .crop((float) config.getDouble("cropLeft"), (float) config.getDouble("cropTop"),
                        (float) config.getDouble("cropRight"), (float) config.getDouble("cropBottom"))
                .rotation(config.getInt("rotationDegrees")).outputHeight(config.getInt("outputHeight"))
                .speed((float) config.getDouble("speed")).volume((float) config.getDouble("volume"))
                .overlayText(config.getString("overlayText")).build();
        assertEquals(TextOracleContract.TEXT, edit.overlayText);
        File output = new File(getContext().getFilesDir(), "text-test-" + UUID.randomUUID() + ".mp4");
        Handler main = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> codecs = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(main.post(() -> {
            try {
                engine.set(new Media3ExportEngine(getContext()));
                engine.get().export(edit, output, new Media3ExportEngine.Listener() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onCodecs(String video, String audio) { codecs.set(video + "; " + audio); }
                    @Override public void onCompleted(File file) { done.countDown(); }
                    @Override public void onError(Exception failure) { error.set(failure); done.countDown(); }
                });
            } catch (Exception failure) { error.set(failure); done.countDown(); }
        }));
        boolean stopped = false;
        try {
            assertTrue("Export exceeded deadline", done.await(200, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            assertNotNull("Missing real encoder diagnostics", codecs.get());
            JSONObject report = verifier.verify(TextOracleContract.CASE_ID, output);
            assertEquals(report.toString(), "PASS", report.getString("status"));
            assertEquals(TextOracleContract.VERSION, report.getString("contract_version"));
            for (int probe = 0; probe < 9; probe++) assertCheck(report, "text.probe_" + probe + ".content", true);
            report.put("export_codecs", codecs.get());
            format(report);
        } finally {
            CountDownLatch cleanup = new CountDownLatch(1);
            assertTrue(main.post(() -> {
                try { if (engine.get() != null) engine.get().cancel(); }
                finally { cleanup.countDown(); }
            }));
            stopped = cleanup.await(10, TimeUnit.SECONDS);
            if (stopped && output.exists()) assertTrue(output.delete());
            assertTrue("Native cleanup not confirmed; output retained", stopped);
        }
    }

    private static void format(JSONObject report) throws Exception {
        report.put("reference", new JSONObject().put("version", TextOracleContract.VERSION)
                .put("sha256", TextOracleContract.REFERENCE.sha256));
        if (!report.has("export_codecs")) report.put("export_codecs", "Independent control; no export");
        assertNotNull(report.getJSONObject(OracleVerifier.DECODER_KEY));
        assertTrue(SelfTestRunner.formatCaseResult(report).startsWith(report.getString("status") + " text_overlay\n"));
        Object decoder = report.remove(OracleVerifier.DECODER_KEY);
        try {
            SelfTestRunner.formatCaseResult(report);
            fail("Missing shared decoder key accepted");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage().contains(OracleVerifier.DECODER_KEY));
        } finally { report.put(OracleVerifier.DECODER_KEY, decoder); }
    }

    private static void assertCheck(JSONObject report, String assertion, boolean passed) throws Exception {
        JSONArray checks = report.getJSONArray("checks");
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (assertion.equals(check.getString("assertion"))) {
                assertEquals(check.toString(), passed, check.getBoolean("passed"));
                return;
            }
        }
        fail("Missing assertion " + assertion);
    }
}
