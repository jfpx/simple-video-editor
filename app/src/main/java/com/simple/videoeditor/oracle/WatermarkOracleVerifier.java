package com.simple.videoeditor.oracle;

import java.util.Arrays;

/** Numeric straight-alpha blending against original analytic frames, not a PNG/production renderer. */
final class WatermarkOracleVerifier {
    private WatermarkOracleVerifier() {}

    static void verify(OracleCoreVerifier.RgbImage actual, OracleCoreVerifier.RgbImage background,
                       OracleCoreVerifier.Report report, int probe) {
        String key = "watermark.probe_" + probe + ".";
        region(actual, background, report, key + "opaque_red", 6, 6, 18, 26, 240, 30, 40, 255);
        region(actual, background, report, key + "half_blue", 26, 6, 42, 26, 25, 45, 240, 128);
        region(actual, background, report, key + "opaque_green", 50, 6, 58, 14, 25, 230, 55, 255);
        region(actual, background, report, key + "half_green", 50, 22, 58, 26, 25, 230, 55, 128);
        // Sample inside the unpainted gap, away from chroma-subsampled edges.
        region(actual, background, report, key + "transparent", 50, 18, 58, 19, 25, 230, 55, 0);
    }

    private static void region(OracleCoreVerifier.RgbImage actual, OracleCoreVerifier.RgbImage background,
                               OracleCoreVerifier.Report report, String key,
                               int left, int top, int right, int bottom, int r, int g, int b, int alpha) {
        int[] foreground = {r, g, b};
        double[] error = new double[3];
        for (int y = top + 156; y < bottom + 156; y++) {
            for (int x = left + 192; x < right + 192; x++) {
                int p = (y * 320 + x) * 3;
                for (int c = 0; c < 3; c++) {
                    double ideal = (foreground[c] * alpha + (background.rgb[p + c] & 255) * (255 - alpha)) / 255d;
                    error[c] += Math.abs((actual.rgb[p + c] & 255) - ideal);
                }
            }
        }
        double maximum = 0;
        for (int c = 0; c < 3; c++) {
            error[c] /= (right - left) * (bottom - top);
            maximum = Math.max(maximum, error[c]);
        }
        report.check(key, maximum <= 18, Arrays.asList(error[0], error[1], error[2]),
                "per-channel MAE <=18 vs analytic straight-alpha " + alpha + "/255; fixed inset ROI");
    }
}
