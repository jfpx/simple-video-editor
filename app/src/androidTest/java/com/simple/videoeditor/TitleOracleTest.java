package com.simple.videoeditor;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;
import androidx.media3.common.util.UnstableApi;
import com.simple.videoeditor.oracle.TitleOracleContract;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Native decode/export evidence is only obtained by executing these tests on a device. */
@UnstableApi
public final class TitleOracleTest extends AndroidTestCase {
    public void testPinnedTitleControlsAndMetadataGate() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getContext(), TitleOracleContract.create());
        for (TitleOracleContract.Control control : TitleOracleContract.CONTROLS) {
            JSONObject report = verifier.verify(TitleOracleContract.CASE_ID, verifier.prepareTitleAsset(control.asset));
            assertEquals(report.toString(), control.expectedPass ? "PASS" : "FAIL", report.getString("status"));
            assertEquals(control.asset.sha256, report.getString("candidate_sha256"));
            for (String key : control.requiredFailures) assertCheck(report, key, false);
            if (control.requiredFailures.contains("video.duration")) {
                JSONArray checks = report.getJSONArray("checks");
                for (int i = 0; i < checks.length(); i++) {
                    assertFalse("Metadata gate decoded unexpected frames",
                            "video.frame_count".equals(checks.getJSONObject(i).getString("assertion")));
                }
            }
            format(report);
        }
    }

    public void testActualGeneratedTitleExportThroughProductionPath() throws Exception {
        assertTrue(Looper.myLooper() != Looper.getMainLooper());
        double density = getContext().getResources().getDisplayMetrics().scaledDensity;
        OracleVerifier verifier = new OracleVerifier(getContext(), TitleOracleContract.create(density));
        File fixture = verifier.prepareFixture();
        EditConfig edit = SelfTestRunner.titleConfig(fixture);
        assertEquals("OI", edit.introTitle.text);
        assertEquals(48, edit.introTitle.textSizeSp);
        assertEquals("normal", edit.introTitle.fontStyle);
        assertEquals(1000, edit.introTitle.durationMs);
        assertEquals(0xFF000000, edit.introTitle.backgroundColor);
        assertNull(edit.intro);
        assertNull(edit.replacementMusic);
        assertEquals("", edit.overlayText);
        File output = new File(getContext().getFilesDir(), "title-test-" + UUID.randomUUID() + ".mp4");
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
                    @Override public void onCompleted(File file) {
                        if (!output.equals(file)) error.set(new Exception("Unexpected candidate path"));
                        done.countDown();
                    }
                    @Override public void onError(Exception failure) { error.set(failure); done.countDown(); }
                });
            } catch (Exception failure) { error.set(failure); done.countDown(); }
        }));
        try {
            assertTrue("Export deadline", done.await(200, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            assertNotNull("Missing actual export codecs", codecs.get());
            assertSourceAudioFormat(fixture, 48000, 1);
            assertMatchingAudioFormat(fixture, output);
            JSONObject report = verifier.verify(TitleOracleContract.CASE_ID, output);
            assertEquals(report.toString(), "PASS", report.getString("status"));
            assertFalse(TitleOracleContract.REFERENCE.sha256.equals(report.getString("candidate_sha256")));
            for (int probe = 0; probe < 30; probe++) assertCheck(report, "text.probe_" + probe + ".content", true);
            for (String key : new String[]{"title.join", "title.static", "video.temporal_barcode",
                    "video.moving_markers", "audio.window_0.rms", "audio.window_3.frequency"}) {
                assertCheck(report, key, true);
            }
            report.put("export_codecs", codecs.get());
            format(report);
        } finally {
            CountDownLatch cleanup = new CountDownLatch(1);
            assertTrue(main.post(() -> {
                try { if (engine.get() != null) engine.get().cancel(); }
                finally { cleanup.countDown(); }
            }));
            boolean stopped = cleanup.await(10, TimeUnit.SECONDS);
            if (stopped && output.exists()) assertTrue(output.delete());
            assertTrue("Cleanup unconfirmed; candidate retained", stopped);
        }
    }

    private static void assertSourceAudioFormat(File file, int sampleRate, int channels) throws Exception {
        MediaFormat format = audioFormat(file);
        assertEquals(sampleRate, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
        assertEquals(channels, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
    }

    private static void assertMatchingAudioFormat(File expected, File actual) throws Exception {
        MediaFormat source = audioFormat(expected);
        MediaFormat candidate = audioFormat(actual);
        assertEquals("Title export changed the source sample rate",
                source.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                candidate.getInteger(MediaFormat.KEY_SAMPLE_RATE));
        assertEquals("Title export changed the source channel count",
                source.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                candidate.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
    }

    private static MediaFormat audioFormat(File file) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return format;
            }
        } finally {
            extractor.release();
        }
        fail("Missing audio track: " + file);
        throw new AssertionError(file);
    }

    private static void format(JSONObject report) throws Exception {
        report.put("reference", new JSONObject().put("version", TitleOracleContract.VERSION)
                .put("sha256", TitleOracleContract.REFERENCE.sha256));
        if (!report.has("export_codecs")) report.put("export_codecs", "Independent control; no export");
        assertNotNull(report.getJSONObject(OracleVerifier.DECODER_KEY));
        assertTrue(SelfTestRunner.formatCaseResult(report).startsWith(
                report.getString("status") + " " + TitleOracleContract.CASE_ID + "\n"));
    }

    private static void assertCheck(JSONObject report, String key, boolean passed) throws Exception {
        JSONArray checks = report.getJSONArray("checks");
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            if (key.equals(check.getString("assertion"))) {
                assertEquals(check.toString(), passed, check.getBoolean("passed"));
                return;
            }
        }
        fail("Missing " + key);
    }
}
