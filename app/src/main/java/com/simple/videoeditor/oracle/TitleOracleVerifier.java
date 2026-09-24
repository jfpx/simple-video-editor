package com.simple.videoeditor.oracle;

/** Normalize by declared display density, not by detected glyph bounds or production pixels. */
final class TitleOracleVerifier {
    private TitleOracleVerifier() {}

    static OracleCoreVerifier.RgbImage verify(OracleCoreVerifier.RgbImage image,
                       OracleContract.OracleCase oracleCase, OracleCoreVerifier.Report report, int probe) {
        double density = ((Number) oracleCase.androidEditConfig.get("scaledDensity")).doubleValue();
        OracleCoreVerifier.RgbImage normalized = new OracleCoreVerifier.RgbImage(320, 240);
        OracleCoreVerifier.RgbImage black = new OracleCoreVerifier.RgbImage(320, 240);
        for (int y = 84; y < 158; y++) {
            for (int x = 130; x < 190; x++) {
                int sx = (int) Math.floor(160 + (x + .5 - 160) * density);
                int sy = (int) Math.floor(120 + (y + .5 - 120) * density);
                if (sx >= 0 && sx < image.width && sy >= 0 && sy < image.height) {
                    System.arraycopy(image.rgb, (sy * image.width + sx) * 3,
                            normalized.rgb, (y * 320 + x) * 3, 3);
                }
            }
        }
        TextOracleVerifier.verify(normalized, black, report, probe);
        double innerBackground = 0;
        int innerSamples = 0, chromatic = 0;
        for (int y = 84; y < 158; y++) {
            for (int x = 130; x < 190; x++) {
                int p = (y * 320 + x) * 3;
                int r = normalized.rgb[p] & 255, g = normalized.rgb[p + 1] & 255;
                int b = normalized.rgb[p + 2] & 255;
                if (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) > 45) chromatic++;
                // A <=39px cap centered at 119.5 +/-8 cannot occupy these fixed bands.
                if (y < 92 || y >= 148) {
                    innerBackground += r + g + b;
                    innerSamples += 3;
                }
            }
        }
        report.check("title.probe_" + probe + ".inner_background",
                innerBackground / innerSamples <= 18 && chromatic <= 60 * 74 * .01,
                java.util.Arrays.asList(innerBackground / innerSamples, chromatic),
                "fixed empty ROI bands black MAE<=18; <=1% ROI pixels chroma>45");
        int unpainted = 0, nonblack = 0;
        for (int y = 84; y < 158; y++) {
            for (int x = 130; x < 190; x++) {
                boolean nearWhite = false;
                // Only the fixed two-pixel antialias fringe may be gray; holes/background stay black.
                for (int dy = -2; dy <= 2 && !nearWhite; dy++) {
                    for (int dx = -2; dx <= 2; dx++) {
                        int p = ((y + dy) * 320 + x + dx) * 3;
                        if ((normalized.rgb[p] & 255) >= 185 && (normalized.rgb[p + 1] & 255) >= 185
                                && (normalized.rgb[p + 2] & 255) >= 185) { nearWhite = true; break; }
                    }
                }
                if (!nearWhite) {
                    int p = (y * 320 + x) * 3;
                    unpainted++;
                    if ((normalized.rgb[p] & 255) > 45 || (normalized.rgb[p + 1] & 255) > 45
                            || (normalized.rgb[p + 2] & 255) > 45) nonblack++;
                }
            }
        }
        report.check("title.probe_" + probe + ".unpainted_background",
                unpainted > 0 && nonblack <= unpainted * .01,
                java.util.Arrays.asList(nonblack, unpainted),
                "<=1% nonblack pixels outside white ink and fixed 2px antialias fringe");
        double sum = 0;
        int count = 0, bright = 0;
        int physicalBackground = 0, physicalNonblack = 0;
        for (int y = 0; y < image.height; y++) {
            for (int x = 0; x < image.width; x++) {
                double nx = 160 + (x + .5 - 160) / density;
                double ny = 120 + (y + .5 - 120) / density;
                if (nx >= 130 && nx < 190 && ny >= 84 && ny < 158) {
                    // Use the validated normalized ink mask, but inspect EVERY physical pixel.
                    // Candidate pixels skipped by normalization must not create their own exclusion.
                    boolean nearInk = false;
                    int ix = (int) Math.floor(nx), iy = (int) Math.floor(ny);
                    for (int dy = -2; dy <= 2 && !nearInk; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            int p = ((iy + dy) * normalized.width + ix + dx) * 3;
                            if ((normalized.rgb[p] & 255) >= 185
                                    && (normalized.rgb[p + 1] & 255) >= 185
                                    && (normalized.rgb[p + 2] & 255) >= 185) {
                                nearInk = true;
                                break;
                            }
                        }
                    }
                    if (!nearInk) {
                        physicalBackground++;
                        int p = (y * image.width + x) * 3;
                        if ((image.rgb[p] & 255) > 45 || (image.rgb[p + 1] & 255) > 45
                                || (image.rgb[p + 2] & 255) > 45) physicalNonblack++;
                    }
                    continue;
                }
                for (int c = 0; c < 3; c++) {
                    int value = image.rgb[(y * image.width + x) * 3 + c] & 255;
                    sum += value;
                    if (value > 45) bright++;
                    count++;
                }
            }
        }
        report.check("title.probe_" + probe + ".physical_background",
                physicalBackground > 0 && physicalNonblack <= physicalBackground * .01,
                java.util.Arrays.asList(physicalNonblack, physicalBackground),
                "<=1% nonblack physical ROI pixels outside normalized ink and density-scaled 2px fringe");
        report.check("title.probe_" + probe + ".background",
                count > 0 && sum / count <= 18 && bright <= count * .01,
                java.util.Arrays.asList(sum / Math.max(1, count), bright, count),
                "black outside fixed density-scaled ROI: MAE<=18, <=1% channels >45");
        return normalized;
    }
}
