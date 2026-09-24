package com.simple.videoeditor.oracle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Analytic topology/geometry/color assertions, independent of Android text rasterization. */
final class TextOracleVerifier {
    private TextOracleVerifier() {}

    static void verify(OracleCoreVerifier.RgbImage image, OracleCoreVerifier.RgbImage background,
                       OracleCoreVerifier.Report report, int probe) {
        String key = "text.probe_" + probe + ".";
        int width = 60, height = 74;
        boolean[] ink = new boolean[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int p = ((y + 84) * image.width + x + 130) * 3;
                int r = image.rgb[p] & 255, g = image.rgb[p + 1] & 255, b = image.rgb[p + 2] & 255;
                ink[y * width + x] = Math.min(r, Math.min(g, b)) >= 185
                        && Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b)) <= 45;
            }
        }
        boolean[] visited = new boolean[ink.length];
        List<int[]> glyphs = new ArrayList<>();
        for (int i = 0; i < ink.length; i++) {
            if (!ink[i] || visited[i]) continue;
            int[] box = component(ink, visited, width, height, i, true);
            // Ignore codec speckles and the exposed source barcode's <=24px-high components.
            if (box[3] - box[1] + 1 >= 26 && box[4] >= 60) glyphs.add(box);
        }
        java.util.Collections.sort(glyphs, (a, b) -> Integer.compare(a[0], b[0]));
        List<Object> measured = new ArrayList<>();
        for (int[] box : glyphs) measured.add(Arrays.asList(
                box[0] + 130, box[1] + 84, box[2] + 131, box[3] + 85, box[4]));
        report.check(key + "glyph_count", glyphs.size() == 2, glyphs.size(), 2);
        boolean content = false, scale = false, position = false;
        double backingError = 255;
        if (glyphs.size() == 2) {
            int[] o = glyphs.get(0), stem = glyphs.get(1);
            int ow = o[2] - o[0] + 1, oh = o[3] - o[1] + 1;
            int iw = stem[2] - stem[0] + 1, ih = stem[3] - stem[1] + 1;
            int holesO = holes(ink, width, o), holesI = holes(ink, width, stem);
            double stemFill = stem[4] / (double) (iw * ih);
            double ringFill = o[4] / (double) (ow * oh);
            content = holesO == 1 && holesI == 0 && ow >= 3 * iw
                    && ringFill >= 0.22 && ringFill <= 0.60 && stemFill >= 0.80
                    && ringProfile(ink, width, o) && Math.abs(o[1] - stem[1]) <= 3
                    && Math.abs(o[3] - stem[3]) <= 3;
            scale = ow >= 25 && ow <= 34 && oh >= 31 && oh <= 39
                    && iw >= 3 && iw <= 8 && ih >= 31 && ih <= 39
                    && stem[0] - o[2] >= 4 && stem[0] - o[2] <= 13;
            double cx = (o[0] + stem[2]) / 2d + 130;
            double cy = (Math.min(o[1], stem[1]) + Math.max(o[3], stem[3])) / 2d + 84;
            position = Math.abs(cx - 159.5) <= 4 && Math.abs(cy - 119.5) <= 8;
            measured.add(Arrays.asList("holes", holesO, holesI, "fill", ringFill, stemFill,
                    "center", cx, cy));
            // Probe the inter-glyph gap, excluding antialiased edges, against 60%-black backing.
            double sum = 0;
            int count = 0;
            int edgeMargin = Math.min(3, (stem[0] - o[2]) / 2);
            for (int y = Math.max(o[1], stem[1]) + 5; y <= Math.min(o[3], stem[3]) - 5; y++) {
                for (int x = o[2] + edgeMargin; x <= stem[0] - edgeMargin; x++) {
                    int p = ((y + 84) * image.width + x + 130) * 3;
                    for (int c = 0; c < 3; c++) {
                        sum += Math.abs((image.rgb[p + c] & 255) - 0.4 * (background.rgb[p + c] & 255));
                        count++;
                    }
                }
            }
            if (count > 0) backingError = sum / count;
        }
        report.check(key + "content", content, measured, "O: one closed ring, then I: solid narrow stem");
        report.check(key + "scale", scale, measured, "48px em: O 25..34 x 31..39, I 3..8 x 31..39; gap 4..13");
        report.check(key + "position", position, measured, "ink center (159.5 +/-4,119.5 +/-8)");
        report.check(key + "backing", backingError <= 18, backingError, "MAE <=18 vs 0.4 * frozen background");
    }

    private static boolean ringProfile(boolean[] ink, int width, int[] b) {
        int w = b[2] - b[0] + 1, h = b[3] - b[1] + 1;
        // A ring has two separated side strokes on three interior scan lines and no center ink.
        for (double fraction : new double[]{0.35, 0.5, 0.65}) {
            int y = b[1] + (int) (fraction * (h - 1));
            int runs = 0;
            boolean previous = false;
            for (int x = b[0]; x <= b[2]; x++) {
                boolean next = ink[y * width + x];
                if (next && !previous) runs++;
                previous = next;
                if (x >= b[0] + w * 0.35 && x <= b[0] + w * 0.65 && next) return false;
            }
            if (runs != 2) return false;
        }
        return true;
    }

    private static int holes(boolean[] ink, int stride, int[] box) {
        int width = box[2] - box[0] + 3, height = box[3] - box[1] + 3;
        boolean[] empty = new boolean[width * height], visited = new boolean[empty.length];
        Arrays.fill(empty, true);
        for (int y = 1; y < height - 1; y++) {
            for (int x = 1; x < width - 1; x++) {
                empty[y * width + x] = !ink[(box[1] + y - 1) * stride + box[0] + x - 1];
            }
        }
        int holes = 0;
        for (int i = 0; i < empty.length; i++) {
            if (!empty[i] || visited[i]) continue;
            int[] part = component(empty, visited, width, height, i, false);
            if (part[0] > 0 && part[1] > 0 && part[2] < width - 1 && part[3] < height - 1
                    && part[4] >= 12) holes++;
        }
        return holes;
    }

    private static int[] component(boolean[] pixels, boolean[] visited, int width, int height,
                                   int start, boolean diagonal) {
        int[] queue = new int[pixels.length];
        int head = 0, tail = 1;
        queue[0] = start;
        visited[start] = true;
        int[] box = {width, height, -1, -1, 0};
        while (head < tail) {
            int p = queue[head++], x = p % width, y = p / width;
            box[0] = Math.min(box[0], x); box[1] = Math.min(box[1], y);
            box[2] = Math.max(box[2], x); box[3] = Math.max(box[3], y); box[4]++;
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (!diagonal && dx != 0 && dy != 0) continue;
                    int nx = x + dx, ny = y + dy;
                    if (nx < 0 || nx >= width || ny < 0 || ny >= height) continue;
                    int n = ny * width + nx;
                    if (pixels[n] && !visited[n]) { visited[n] = true; queue[tail++] = n; }
                }
            }
        }
        return box;
    }
}
