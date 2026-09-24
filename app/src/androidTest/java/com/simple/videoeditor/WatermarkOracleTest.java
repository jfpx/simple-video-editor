package com.simple.videoeditor;

import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;
import androidx.media3.common.util.UnstableApi;
import com.simple.videoeditor.oracle.OracleContract;
import com.simple.videoeditor.oracle.WatermarkOracleContract;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Android decoder witnesses plus a real export through the shared production engine. */
@UnstableApi
public final class WatermarkOracleTest extends AndroidTestCase {
    public void testPngSnapshotBoundsAndValidation() throws Exception {
        byte[] bytes;
        try (java.io.InputStream input = getContext().getAssets().open("watermark-oracle/watermark.png");
             java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            bytes = output.toByteArray();
        }
        PngWatermark image = PngWatermark.fromBytes(bytes);
        java.util.Arrays.fill(bytes, (byte) 0);
        android.graphics.Bitmap texture = image.copyBitmap();
        assertEquals(64, image.width);
        assertEquals(32, image.height);
        assertFalse("GL needs straight alpha", texture.isPremultiplied());
        assertEquals(128, android.graphics.Color.alpha(texture.getPixel(30, 10)));
        assertTrue(android.graphics.Color.blue(texture.getPixel(30, 10)) >= 238);
        texture.recycle();
        for (byte[] invalid : new byte[][]{null, bytes, new byte[PngWatermark.MAX_BYTES + 1]}) {
            try { PngWatermark.fromBytes(invalid); fail("Invalid PNG accepted"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
        try (java.io.InputStream input = getContext().getAssets().open("watermark-oracle/watermark.png")) {
            java.io.DataInputStream data = new java.io.DataInputStream(input);
            data.readFully(bytes);
        }
        byte[] badCrc = bytes.clone();
        badCrc[50] ^= 1;
        for (byte[] invalid : new byte[][]{badCrc, java.util.Arrays.copyOf(bytes, bytes.length - 1)}) {
            try { PngWatermark.fromBytes(invalid); fail("Corrupt PNG accepted"); }
            catch (IOException expected) { assertNotNull(expected.getMessage()); }
        }
        // A valid CRC must not allow hostile declared dimensions to reach BitmapFactory.
        bytes[16] = 0x7f;
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes, 12, 17);
        long value = crc.getValue();
        for (int i = 0; i < 4; i++) bytes[29 + i] = (byte) (value >> (24 - 8 * i));
        try { PngWatermark.fromBytes(bytes); fail("Oversized PNG accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("2048")); }
    }

    public void testDefaultOffSnapshotPlacementAndClear() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getContext(), WatermarkOracleContract.create());
        EditConfig enabled = SelfTestRunner.watermarkConfig(verifier.prepareFixture(),
                verifier.prepareWatermarkAsset(WatermarkOracleContract.PNG));
        EditConfig.Builder builder = new EditConfig.Builder(enabled.input, 4000);
        assertNull(builder.build().watermark);
        builder.watermark(enabled.watermark.image, .2f, .75f, .75f);
        EditConfig snapshot = builder.build();
        builder.watermark(null, .2f, .75f, .75f);
        assertNull(builder.build().watermark);
        android.graphics.RectF rect = PngWatermark.rectangle(320, 240, snapshot.watermark);
        assertEquals(new android.graphics.RectF(192, 156, 256, 188), rect);
        EditConfig.Watermark corner = builder.watermark(enabled.watermark.image, .5f, 1, 0).build().watermark;
        assertEquals(new android.graphics.RectF(160, 0, 320, 80), PngWatermark.rectangle(320, 240, corner));
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0, .51f}) {
            try { builder.watermark(enabled.watermark.image, invalid, .5f, .5f); fail("Invalid width"); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
        for (float invalid : new float[]{Float.NaN, -1, 1.01f}) {
            try { builder.watermark(enabled.watermark.image, .2f, invalid, .5f); fail("Invalid X"); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
            try { builder.watermark(enabled.watermark.image, .2f, .5f, invalid); fail("Invalid Y"); }
            catch (IllegalArgumentException expected) { assertNotNull(expected.getMessage()); }
        }
        try { PngWatermark.rectangle(320, 10, snapshot.watermark); fail("Clipped PNG silently accepted"); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("too tall")); }
        assertNotNull(snapshot.watermark);
    }

    public void testCacheCopyIsIndependentOfProviderAndFile() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getContext(), WatermarkOracleContract.create());
        File original = verifier.prepareWatermarkAsset(WatermarkOracleContract.PNG);
        File target = new File(getContext().getCacheDir(), "png-copy-test-" + UUID.randomUUID() + ".png");
        try {
            PngWatermark image = PngWatermark.copyDocument(getContext(), android.net.Uri.fromFile(original), target);
            assertEquals(original.length(), target.length());
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(target)) { output.write(0); }
            android.graphics.Bitmap bitmap = image.copyBitmap();
            try { assertEquals(android.graphics.Color.rgb(240, 30, 40), bitmap.getPixel(10, 10)); }
            finally { bitmap.recycle(); }
        } finally { assertTrue(target.delete()); }
    }

    public void testPinnedControlsThroughAndroidDecoderAndFormatter() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getContext(), WatermarkOracleContract.create());
        for (WatermarkOracleContract.Control control : WatermarkOracleContract.CONTROLS) {
            JSONObject report = verifier.verify(WatermarkOracleContract.CASE_ID,
                    verifier.prepareWatermarkAsset(control.asset));
            assertEquals(report.toString(), control.expectedPass ? "PASS" : "FAIL", report.getString("status"));
            assertTrue(report.toString(), SelfTestRunner.watermarkControlMatches(report, control));
            assertEquals(control.asset.sha256, report.getString("candidate_sha256"));
            for (String key : new String[]{"video.duration", "video.frame_count", "video.temporal_barcode",
                    "video.spatial_probe_count", "video.moving_markers", "audio.pts_continuity"}) {
                assertCheck(report, key, true);
            }
            assertEquals(WatermarkOracleContract.MANIFEST.sha256,
                    report.getJSONObject("pins").getJSONObject("supplement").getString("contract_sha256"));
            format(report);
            if (!control.expectedPass) {
                JSONObject fault = new JSONObject(report.toString()).put("status", "ERROR");
                assertFalse("Decoder/checker error cannot count as a negative", SelfTestRunner.watermarkControlMatches(fault, control));
                fault.put("status", "FAIL").getJSONArray("checks").put(new JSONObject()
                        .put("assertion", "checker.completed_without_error").put("passed", false));
                assertFalse("FAIL with checker fault cannot count as negative", SelfTestRunner.watermarkControlMatches(fault, control));
            }
        }
        for (OracleContract.Asset asset : new OracleContract.Asset[]{null,
                new OracleContract.Asset("..\\escape.png", 0, ""),
                new OracleContract.Asset(WatermarkOracleContract.PNG.path,
                        WatermarkOracleContract.PNG.bytes, WatermarkOracleContract.PNG.sha256)}) {
            try {
                verifier.prepareWatermarkAsset(asset);
                fail("Untrusted asset accepted");
            } catch (IOException expected) {
                assertEquals("Unknown watermark asset", expected.getMessage());
            }
        }
    }

    public void testActualPngWatermarkExportThroughProductionPath() throws Exception {
        assertTrue(Looper.myLooper() != Looper.getMainLooper());
        OracleVerifier verifier = new OracleVerifier(getContext(), WatermarkOracleContract.create());
        EditConfig edit = SelfTestRunner.watermarkConfig(verifier.prepareFixture(),
                verifier.prepareWatermarkAsset(WatermarkOracleContract.PNG));
        File output = new File(getContext().getFilesDir(), "watermark-test-" + UUID.randomUUID() + ".mp4");
        Handler main = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> codecs = new AtomicReference<>();
        AtomicReference<File> completed = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(main.post(() -> {
            try {
                engine.set(new Media3ExportEngine(getContext()));
                engine.get().export(edit, output, new Media3ExportEngine.Listener() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onCodecs(String video, String audio) { codecs.set(video + "; " + audio); }
                    @Override public void onCompleted(File file) { completed.set(file); done.countDown(); }
                    @Override public void onError(Exception failure) { error.set(failure); done.countDown(); }
                });
            } catch (Exception failure) { error.set(failure); done.countDown(); }
        }));
        try {
            assertTrue("Export exceeded deadline", done.await(200, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            assertNotNull("Missing real encoder diagnostics", codecs.get());
            assertNotNull(completed.get());
            assertEquals(output.getCanonicalFile(), completed.get().getCanonicalFile());
            JSONObject report = verifier.verify(WatermarkOracleContract.CASE_ID, output);
            assertEquals(report.toString(), "PASS", report.getString("status"));
            assertEquals(WatermarkOracleContract.VERSION, report.getString("contract_version"));
            for (int probe = 0; probe < 9; probe++) {
                for (String region : new String[]{"opaque_red", "half_blue", "opaque_green", "half_green", "transparent"}) {
                    assertCheck(report, "watermark.probe_" + probe + "." + region, true);
                }
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
            assertTrue("Native cleanup not confirmed; output retained", stopped);
        }
    }

    private static void format(JSONObject report) throws Exception {
        report.put("reference", new JSONObject().put("version", WatermarkOracleContract.VERSION)
                .put("sha256", WatermarkOracleContract.REFERENCE.sha256));
        if (!report.has("export_codecs")) report.put("export_codecs", "Independent control; no export");
        assertTrue(SelfTestRunner.formatCaseResult(report).startsWith(report.getString("status") + " png_watermark\n"));
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
