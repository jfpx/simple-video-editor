package com.simple.videoeditor.oracle;

import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.Image;
import android.media.MediaFormat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Diagnostic cross-check derived from Kr/Kb, not the oracle's coefficient table. */
final class OracleColorMath {
    private OracleColorMath() {}

    static double[] rgb(int y, int cb, int cr, int standard, int range) throws IOException {
        int matrix = standard >= 64 ? (standard - 64) / 7
                : standard == 1 ? 1 : standard == 0 || standard == 2 || standard == 4 ? 3 : -1;
        if (matrix != 1 && matrix != 3) throw new IOException("Unsupported diagnostic matrix " + standard);
        if (range != 0 && range != 1 && range != 2) throw new IOException("Unsupported diagnostic range " + range);
        double kr = matrix == 1 ? .2126 : .299;
        double kb = matrix == 1 ? .0722 : .114;
        double luma = range == 1 ? y / 255d : (y - 16d) / 219d;
        double u = (cb - 128d) / (range == 1 ? 255d : 224d);
        double v = (cr - 128d) / (range == 1 ? 255d : 224d);
        double red = luma + 2 * (1 - kr) * v;
        double blue = luma + 2 * (1 - kb) * u;
        double green = (luma - kr * red - kb * blue) / (1 - kr - kb);
        return new double[] {clip(red), clip(green), clip(blue)};
    }

    private static double clip(double normalized) {
        return Math.max(0, Math.min(255, Math.round(normalized * 255)));
    }

    static Map<String, Object> measure(Image image, MediaFormat output, MediaFormat input,
            OracleCoreVerifier.RgbImage expected, List<OracleCoreVerifier.RegionExpectation> regions,
            long deadline) throws IOException {
        Rect crop = image.getCropRect();
        Image.Plane[] imagePlanes = image.getPlanes();
        if (image.getFormat() != ImageFormat.YUV_420_888 || imagePlanes.length != 3
                || crop.width() != expected.width || crop.height() != expected.height) {
            throw new IOException("Unsupported diagnostic image/geometry");
        }
        OracleCoreVerifier.RgbImage oracle = OracleAndroidDecoder.diagnosticRgb(image, output, input, deadline);
        int standard = effective(output, input, "color-standard");
        int range = effective(output, input, "color-range");
        int transfer = effective(output, input, "color-transfer");
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("effective_standard_range_transfer", new int[] {standard, range, transfer});
        evidence.put("matrix_or_range_defaulted", standard == 0 || range == 0);
        evidence.put("crop_ltrb", new int[] {crop.left, crop.top, crop.right, crop.bottom});
        List<Map<String, Object>> layout = new ArrayList<>();
        ByteBuffer[] planes = new ByteBuffer[3];
        for (int i = 0; i < 3; i++) {
            ByteBuffer buffer = imagePlanes[i].getBuffer();
            Map<String, Object> plane = new LinkedHashMap<>();
            plane.put("position", buffer.position());
            plane.put("limit", buffer.limit());
            plane.put("row_stride", imagePlanes[i].getRowStride());
            plane.put("pixel_stride", imagePlanes[i].getPixelStride());
            layout.add(plane);
            planes[i] = buffer.slice();
        }
        evidence.put("planes_y_cb_cr", layout);
        List<Map<String, Object>> measurements = new ArrayList<>();
        for (OracleCoreVerifier.RegionExpectation region : regions) {
            OracleColorDiagnostic.check(deadline);
            OracleContract.Rect r = region.rect;
            double[] raw = new double[3];
            double[] converted = new double[3];
            double[] actual = new double[3];
            int count = (r.right - r.left) * (r.bottom - r.top);
            for (int y = r.top; y < r.bottom; y++) {
                for (int x = r.left; x < r.right; x++) {
                    int[] values = new int[3];
                    for (int p = 0; p < 3; p++) {
                        int divisor = p == 0 ? 1 : 2;
                        int offset = ((crop.top + y) / divisor) * imagePlanes[p].getRowStride()
                                + ((crop.left + x) / divisor) * imagePlanes[p].getPixelStride();
                        values[p] = planes[p].get(offset) & 255;
                        raw[p] += values[p];
                    }
                    double[] pixel = rgb(values[0], values[1], values[2], standard, range);
                    for (int c = 0; c < 3; c++) {
                        converted[c] += pixel[c];
                        actual[c] += oracle.rgb[(y * oracle.width + x) * 3 + c] & 255;
                    }
                }
            }
            double[] delta = new double[3];
            double[] mathDelta = new double[3];
            for (int c = 0; c < 3; c++) {
                raw[c] /= count;
                converted[c] /= count;
                actual[c] /= count;
                delta[c] = actual[c] - region.rgb[c];
                mathDelta[c] = actual[c] - converted[c];
            }
            Map<String, Object> measurement = new LinkedHashMap<>();
            measurement.put("rect_ltrb", new int[] {r.left, r.top, r.right, r.bottom});
            measurement.put("pixels", count);
            measurement.put("ycbcr_mean", raw);
            measurement.put("oracle_rgb_mean", actual);
            measurement.put("independent_rgb_mean", converted);
            measurement.put("frozen_expected_rgb_mean", Arrays.copyOf(region.rgb, 3));
            measurement.put("oracle_minus_expected_rgb", delta);
            measurement.put("oracle_minus_independent_rgb", mathDelta);
            measurements.add(measurement);
        }
        evidence.put("regions", measurements);
        return evidence;
    }

    private static int effective(MediaFormat output, MediaFormat input, String key) {
        return output.containsKey(key) ? output.getInteger(key) : input.containsKey(key) ? input.getInteger(key) : 0;
    }
}
