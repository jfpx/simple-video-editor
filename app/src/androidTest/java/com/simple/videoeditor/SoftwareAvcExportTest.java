package com.simple.videoeditor;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;

import androidx.media3.common.util.UnstableApi;

import com.simple.videoeditor.oracle.OracleContract;
import com.simple.videoeditor.oracle.OracleGeneratedContract;
import com.simple.videoeditor.oracle.WatermarkOracleContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@UnstableApi
public final class SoftwareAvcExportTest extends InstrumentationTestCase {
    public void testSoftwarePreferenceIdentity320x240PassesFullOracle() throws Exception {
        assertProductionExport(false, false);
    }

    public void testSoftwarePreferenceWatermark320x240PassesFullOracle() throws Exception {
        assertProductionExport(true, false);
    }

    public void testExportKeepsSoftwarePreferenceSnapshotWhenPreferenceChanges() throws Exception {
        assertProductionExport(false, true);
    }

    private void assertProductionExport(boolean watermark, boolean changePreference) throws Exception {
        assertTrue("Export waits must not block the main looper",
                Looper.myLooper() != Looper.getMainLooper());
        Context context = getInstrumentation().getTargetContext();
        boolean original = VideoEncodingSettings.compatibilityEnabled(context);
        OracleContract contract = watermark
                ? WatermarkOracleContract.create() : OracleGeneratedContract.create();
        String caseId = watermark ? WatermarkOracleContract.CASE_ID : "identity";
        OracleVerifier verifier = new OracleVerifier(context, contract);
        File fixture = verifier.prepareFixture();
        EditConfig config = watermark
                ? SelfTestRunner.watermarkConfig(fixture,
                        verifier.prepareWatermarkAsset(WatermarkOracleContract.PNG))
                : new EditConfig.Builder(Uri.fromFile(fixture), 4000).sourceSize(320, 240).build();
        assertEquals(320, contract.source.width);
        assertEquals(240, contract.source.height);
        assertEquals(320, contract.requireCase(caseId).width);
        assertEquals(240, contract.requireCase(caseId).height);
        assertEquals("Same-size test must not request resizing", 0, config.outputHeight);
        File output = new File(context.getFilesDir(),
                "software-avc-" + caseId + "-" + UUID.randomUUID() + ".mp4");
        Handler main = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<File> completed = new AtomicReference<>();
        AtomicReference<String> videoEncoder = new AtomicReference<>();
        AtomicBoolean preferenceChanged = new AtomicBoolean();
        AtomicBoolean configureStarted = new AtomicBoolean();
        AtomicBoolean preferenceChangedBeforeConfigure = new AtomicBoolean();
        List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch done = new CountDownLatch(1);
        try {
            assertTrue(main.post(() -> {
                try {
                    // Construction must not freeze preferences; each export takes its own snapshot.
                    VideoEncodingSettings.setCompatibilityEnabled(context, false);
                    engine.set(new Media3ExportEngine(context));
                    VideoEncodingSettings.setCompatibilityEnabled(context, true);
                    engine.get().export(config, output, new Media3ExportEngine.Listener() {
                        @Override public void onProgress(int percent) {}
                        @Override public void onCodecs(String video, String audio) {
                            videoEncoder.set(video);
                        }
                        @Override public void onDiagnostic(String message) {
                            diagnostics.add(message);
                            if (message.contains("before-configure attempt=")) configureStarted.set(true);
                            if (changePreference && message.contains("encodingMode=software-avc")
                                    && preferenceChanged.compareAndSet(false, true)) {
                                preferenceChangedBeforeConfigure.set(!configureStarted.get());
                                VideoEncodingSettings.setCompatibilityEnabled(context, false);
                            }
                        }
                        @Override public void onCompleted(File file) {
                            completed.set(file);
                            done.countDown();
                        }
                        @Override public void onError(Exception failure) {
                            error.set(failure);
                            done.countDown();
                        }
                    });
                } catch (Exception failure) {
                    error.set(failure);
                    done.countDown();
                }
            }));
            assertTrue("Production export exceeded deadline", done.await(200, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            assertNotNull("Missing completion callback", completed.get());
            assertEquals(output.getCanonicalFile(), completed.get().getCanonicalFile());
            assertTrue("Missing real MP4 output", output.isFile() && output.length() > 0);
            assertSoftwareDiagnostics(diagnostics, videoEncoder.get());
            if (changePreference) {
                assertTrue("Preference must change during this export", preferenceChanged.get());
                assertTrue("Snapshot must be tested before native encoder selection",
                        preferenceChangedBeforeConfigure.get());
                assertFalse(VideoEncodingSettings.compatibilityEnabled(context));
            } else {
                assertTrue(VideoEncodingSettings.compatibilityEnabled(context));
            }

            JSONObject report = verifier.verify(caseId, output);
            assertEquals("Full unchanged oracle must accept the actual engine output",
                    "PASS", report.getString("status"));
            assertEquals(contract.version, report.getString("contract_version"));
            assertAllOracleChecks(report, watermark);
            JSONObject decoder = report.getJSONObject(OracleVerifier.DECODER_KEY);
            for (String track : new String[]{"video", "audio"}) {
                JSONObject decoded = decoder.getJSONObject(track);
                assertTrue(track + " must be decoded, not just probed", decoded.getBoolean("present"));
                assertTrue(decoded.getString("decoder_name").length() > 0);
                assertTrue(decoded.getJSONObject("output_format").getString("mime").length() > 0);
            }
        } finally {
            try {
                CountDownLatch cleanup = new CountDownLatch(1);
                assertTrue(main.post(() -> {
                    try {
                        if (engine.get() != null) engine.get().cancel();
                    } finally {
                        cleanup.countDown();
                    }
                }));
                boolean stopped = cleanup.await(10, TimeUnit.SECONDS);
                if (stopped && output.exists()) assertTrue(output.delete());
                assertTrue("Native cleanup not confirmed; output retained", stopped);
            } finally {
                VideoEncodingSettings.setCompatibilityEnabled(context, original);
                assertEquals(original, VideoEncodingSettings.compatibilityEnabled(context));
            }
        }
    }

    static void assertSoftwareDiagnostics(List<String> messages, String actualEncoder) {
        assertNotNull("Missing completion backend diagnostics", actualEncoder);
        assertNativeSoftwareEncoder(actualEncoder);
        boolean modeLogged = false;
        boolean configured = false;
        boolean actualNameLogged = false;
        synchronized (messages) {
            for (String message : messages) {
                modeLogged |= message.contains("encodingMode=software-avc");
                assertFalse("Snapshot unexpectedly switched to default",
                        message.contains("encodingMode=default"));
                for (String line : message.split("\n")) {
                    if (line.contains("before-configure attempt=")
                            || line.contains("configured attempt=")) {
                        assertTrue("Hardware must never be configured: " + line,
                                line.contains("software=true"));
                    }
                    if (!line.contains("configured attempt=")) continue;
                    configured = true;
                    assertTrue("Configured record must include actual native backend",
                            line.contains("nativeName="));
                    actualNameLogged |= line.contains("nativeName=" + actualEncoder);
                }
            }
        }
        assertTrue("Missing persisted mode diagnostic", modeLogged);
        assertTrue("No successful native configure checkpoint", configured);
        assertTrue("Completed backend differs from configured native backend", actualNameLogged);
    }

    private static void assertNativeSoftwareEncoder(String name) {
        for (MediaCodecInfo info :
                new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!name.equals(info.getName())) continue;
            assertTrue("Selected backend must be an encoder", info.isEncoder());
            assertTrue("Selected backend must be native software, not hardware: " + name,
                    ExactSizeVideoEncoder.isSoftware(info));
            return;
        }
        fail("Selected backend absent from native inventory: " + name);
    }

    private static void assertAllOracleChecks(JSONObject report, boolean watermark) throws Exception {
        JSONArray checks = report.getJSONArray("checks");
        assertTrue("Missing full oracle checks", checks.length() > 0);
        Set<String> assertions = new HashSet<>();
        for (int i = 0; i < checks.length(); i++) {
            JSONObject check = checks.getJSONObject(i);
            String assertion = check.getString("assertion");
            assertTrue("Oracle rejected " + assertion, check.getBoolean("passed"));
            assertions.add(assertion);
        }
        for (String assertion : new String[]{"video.geometry", "video.codec", "video.duration",
                "video.fps", "video.frame_count", "video.pts_cadence", "video.temporal_barcode",
                "video.spatial_probe_count", "video.spatial_mae", "video.pixel_regions",
                "video.moving_markers", "audio.codec", "audio.sample_rate", "audio.channels",
                "audio.pts_continuity", "audio.decoded_sample_count", "audio.window_0.rms",
                "audio.window_0.frequency"}) {
            assertTrue("Required full oracle assertion missing: " + assertion,
                    assertions.contains(assertion));
        }
        if (watermark) {
            for (int probe = 0; probe < 9; probe++) {
                for (String region : new String[]{
                        "opaque_red", "half_blue", "opaque_green", "half_green", "transparent"}) {
                    String assertion = "watermark.probe_" + probe + "." + region;
                    assertTrue("Missing PNG alpha/placement check: " + assertion,
                            assertions.contains(assertion));
                }
            }
        }
    }
}
