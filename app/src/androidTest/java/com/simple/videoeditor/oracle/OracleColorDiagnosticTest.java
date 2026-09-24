package com.simple.videoeditor.oracle;

import android.test.InstrumentationTestCase;

import com.simple.videoeditor.OracleVerifier;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class OracleColorDiagnosticTest extends InstrumentationTestCase {
    public void testIndependentKrKbMathGoldenVectors() throws Exception {
        assertRgb(new double[] {255, 1, 0}, OracleColorMath.rgb(63, 102, 240, 1, 2));
        assertRgb(new double[] {254, 0, 0}, OracleColorMath.rgb(81, 90, 240, 2, 2));
        assertRgb(new double[] {254, 0, 0}, OracleColorMath.rgb(81, 90, 240, 85, 2));
        assertRgb(new double[] {0, 0, 0}, OracleColorMath.rgb(16, 128, 128, 1, 2));
        assertRgb(new double[] {255, 255, 255}, OracleColorMath.rgb(235, 128, 128, 1, 2));
        assertRgb(new double[] {128, 128, 128}, OracleColorMath.rgb(128, 128, 128, 1, 1));
    }

    @SuppressWarnings("unchecked")
    public void testRealEncodedBt709AndPinnedSourceAgainstFrozenRgb() throws Exception {
        File source = new OracleVerifier(getInstrumentation().getTargetContext()).prepareFixture();
        File encoded = new File(getInstrumentation().getTargetContext().getFilesDir(),
                "color-regression-" + System.nanoTime() + ".mp4");
        try {
            try (InputStream input = getInstrumentation().getContext().getAssets()
                    .open("oracle-color/bt709-first-frame.mp4");
                 FileOutputStream output = new FileOutputStream(encoded)) {
                byte[] bytes = new byte[4096];
                int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            }
            List<String> checkpoints = new ArrayList<>();
            Map<String, Object> report = OracleColorDiagnostic.collect(encoded, source,
                    getInstrumentation().getTargetContext().getAssets(),
                    value -> checkpoints.add(new JSONObject(value).toString()));
            try (FileOutputStream evidence = new FileOutputStream(new File(
                    getInstrumentation().getTargetContext().getFilesDir(), "color-regression.json"))) {
                evidence.write(new JSONObject(report).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            List<Map<String, Object>> passes = (List<Map<String, Object>>) report.get("passes");
            assertEquals(report.toString(), 4, passes.size());
            int measured = 0;
            for (Map<String, Object> pass : passes) {
                if (!"MEASURED_NOT_A_VERDICT".equals(pass.get("status"))) {
                    // A second implementation need not exist (e.g. software-only devices).
                    assertEquals(pass.toString(), "software_only", pass.get("mode"));
                    assertEquals("java.io.IOException: Independent software decoder unavailable", pass.get("error"));
                    continue;
                }
                measured++;
                assertEquals(0L, ((Number) pass.get("pts_us")).longValue());
                int[] color = (int[]) pass.get("effective_standard_range_transfer");
                boolean bt709 = "actual_identity_export".equals(pass.get("role"));
                if (bt709) assertEquals(1, color[0]);
                else assertTrue("Source must remain BT601: " + java.util.Arrays.toString(color),
                        color[0] == 2 || color[0] == 4
                                // AOSP packs unspecified primaries + BT601 matrix as 64 + 3*7.
                                || color[0] == 85);
                assertEquals(2, color[1]);
                if (bt709) assertEquals(3, color[2]);
                List<Map<String, Object>> regions = (List<Map<String, Object>>) pass.get("regions");
                assertTrue(regions.size() >= 40);
                boolean saturated = false;
                for (Map<String, Object> region : regions) {
                    double[] expected = (double[]) region.get("frozen_expected_rgb_mean");
                    double[] independent = (double[]) region.get("independent_rgb_mean");
                    double[] oracle = (double[]) region.get("oracle_rgb_mean");
                    double[] raw = (double[]) region.get("ycbcr_mean");
                    for (int c = 0; c < 3; c++) {
                        // Frozen PNG, not one decoder used as truth for the other.
                        assertEquals(region.toString(), expected[c], independent[c], 18d);
                        assertEquals(region.toString(), independent[c], oracle[c], 1d);
                        assertTrue(raw[c] >= 0 && raw[c] <= 255);
                    }
                    saturated |= Math.abs(expected[0] - expected[2]) > 100;
                }
                assertTrue("Regression must exercise chroma, not just grayscale", saturated);
            }
            assertTrue(measured >= 2);
            JSONObject serialized = new JSONObject(report);
            assertTrue(serialized.getJSONArray("passes").getJSONObject(0)
                    .getJSONArray("regions").getJSONObject(0).getJSONArray("ycbcr_mean").length() == 3);
            assertTrue(checkpoints.toString().contains("before_configure"));
            assertTrue(checkpoints.toString().contains("before_image_and_buffer_release"));
            assertTrue(checkpoints.toString().contains("ycbcr_mean"));
        } finally {
            assertTrue(!encoded.exists() || encoded.delete());
        }
    }

    public void testMissingDecoderOrDifferentPtsNeverCountsAsAgreement() {
        Map<String, Object> normal = measured("vendor.decoder", 0L);
        normal.put("decoder_software", false);
        Map<String, Object> software = measured("software.decoder", 0L);
        assertEquals(true, OracleColorDiagnostic.compare(normal, software).get("independent_same_pts"));
        software.put("pts_us", 41667L);
        assertEquals(false, OracleColorDiagnostic.compare(normal, software).get("independent_same_pts"));
        software.put("pts_us", 0L);
        software.put("decoder_name", "vendor.decoder");
        assertEquals(false, OracleColorDiagnostic.compare(normal, software).get("independent_same_pts"));
        software.put("decoder_name", "software.decoder");
        software.put("status", "UNAVAILABLE_OR_FAILED");
        assertEquals(false, OracleColorDiagnostic.compare(normal, software).get("independent_same_pts"));
    }

    public void testIndependentComparisonRetainsPerChannelDifferences() {
        Map<String, Object> normal = measured("vendor.decoder", 0L);
        normal.put("decoder_software", false);
        Map<String, Object> software = measured("software.decoder", 0L);
        Map<String, Object> left = new LinkedHashMap<>();
        Map<String, Object> right = new LinkedHashMap<>();
        for (String key : new String[] {"ycbcr_mean", "oracle_rgb_mean", "independent_rgb_mean"}) {
            left.put(key, new double[] {1, 2, 3});
            right.put(key, new double[] {4, 6, 8});
        }
        normal.put("regions", Collections.singletonList(left));
        software.put("regions", Collections.singletonList(right));
        Map<String, Object> comparison = OracleColorDiagnostic.compare(normal, software);
        assertEquals(true, comparison.get("independent_same_pts"));
        assertRgb(new double[] {3, 4, 5},
                (double[]) comparison.get("ycbcr_mean_max_abs_decoder_delta"));
        normal.put("decoder_software", true);
        assertEquals(false, OracleColorDiagnostic.compare(normal, software).get("independent_same_pts"));
        normal.put("decoder_software", false);
        software.put("sha256", "different-file");
        assertEquals(false, OracleColorDiagnostic.compare(normal, software).get("independent_same_pts"));
    }

    private static Map<String, Object> measured(String name, long pts) {
        Map<String, Object> pass = new LinkedHashMap<>();
        pass.put("status", "MEASURED_NOT_A_VERDICT");
        pass.put("sha256", "same-file");
        pass.put("pts_us", pts);
        pass.put("decoder_name", name);
        pass.put("decoder_software", true);
        Map<String, Object> region = new LinkedHashMap<>();
        for (String key : new String[] {"ycbcr_mean", "oracle_rgb_mean", "independent_rgb_mean"}) {
            region.put(key, new double[] {0, 0, 0});
        }
        pass.put("regions", Collections.singletonList(region));
        return pass;
    }

    private static void assertRgb(double[] expected, double[] actual) {
        for (int c = 0; c < 3; c++) assertEquals(expected[c], actual[c], 0d);
    }
}
