package com.simple.videoeditor;

import android.graphics.Bitmap;
import junit.framework.Assert;

/** Independent expected arithmetic; never calls the production border renderer. */
final class BorderPixelChecks {
    private BorderPixelChecks() {}

    static void check(Bitmap actual, Bitmap unbordered, int percent, int rgb, boolean enabled,
                      int colorTolerance, double interiorTolerance) {
        int width = unbordered.getWidth(), height = unbordered.getHeight();
        Assert.assertEquals("unchanged width", width, actual.getWidth());
        Assert.assertEquals("unchanged height", height, actual.getHeight());
        int thickness = Math.max(1, (Math.min(width, height) * percent + 50) / 100);
        long error = 0, samples = 0;
        int[] sides = new int[4];
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int distance = Math.min(Math.min(x, width - 1 - x), Math.min(y, height - 1 - y));
            boolean edge = enabled && distance < thickness;
            int pixel = actual.getPixel(x, y), expected = unbordered.getPixel(x, y);
            if (enabled && distance == thickness + 1 && maxError(expected, rgb) > 96
                    && maxError(pixel, rgb) < maxError(pixel, expected))
                Assert.fail("border too thick at " + x + "," + y);
            // AVC 4:2:0 blends the boundary's chroma; lossless checks include every pixel.
            if (enabled && colorTolerance > 0 && Math.abs(distance - thickness) <= 1) continue;
            Assert.assertEquals("opaque output", 255, pixel >>> 24);
            if (edge) {
                if (maxError(pixel, rgb) > colorTolerance)
                    Assert.fail("border RGB at " + x + "," + y + ": " + Integer.toHexString(pixel));
                if (y < thickness) sides[0]++;
                if (y >= height - thickness) sides[1]++;
                if (x < thickness) sides[2]++;
                if (x >= width - thickness) sides[3]++;
            } else {
                // Inspect the inner edge locally for excess width. Elsewhere AVC's
                // quantized detail is checked by frame MAE, not a lossless maximum.
                if ((colorTolerance == 0 || (enabled && distance == thickness + 2))
                        && maxError(pixel, expected) > colorTolerance)
                    Assert.fail("interior changed at " + x + "," + y);
                for (int shift = 0; shift <= 16; shift += 8) error += Math.abs((pixel >> shift & 255) - (expected >> shift & 255));
                samples += 3;
            }
        }
        Assert.assertTrue("interior samples", samples > 0);
        Assert.assertTrue("unaltered interior MAE=" + (double) error / samples,
                (double) error / samples <= interiorTolerance);
        if (enabled) for (int count : sides) Assert.assertTrue("each of four edges checked", count > 0);
    }

    static int maxError(int a, int b) {
        return Math.max(Math.abs((a & 255) - (b & 255)),
                Math.max(Math.abs((a >> 8 & 255) - (b >> 8 & 255)),
                        Math.abs((a >> 16 & 255) - (b >> 16 & 255))));
    }
}
