package com.simple.videoeditor;

/** UI percentages removed; EditConfig and legacy oracles remain retained coordinates. */
final class CropRemoval {
    private CropRemoval() {}

    static double[] removed(float left, float top, float right, float bottom) {
        double[] values = {left * 100d, top * 100d, (1d - right) * 100d, (1d - bottom) * 100d};
        for (int i = 0; i < values.length; i++) values[i] = Math.round(values[i] * 10000d) / 10000d;
        return values;
    }

    static float[] retained(String left, String top, String right, String bottom) {
        String[] values = {left, top, right, bottom};
        String[] names = {"左侧", "顶部", "右侧", "底部"};
        float[] removed = new float[4];
        for (int i = 0; i < values.length; i++) {
            try {
                float percent = Float.parseFloat(values[i].trim());
                if (Float.isNaN(percent) || Float.isInfinite(percent) || percent < 0 || percent > 50) {
                    throw new NumberFormatException("Out of range");
                }
                removed[i] = percent / 100f;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(names[i] + "切除须填写 0–50% 的数字", error);
            }
        }
        if (removed[0] + removed[2] >= 1 || removed[1] + removed[3] >= 1) {
            throw new IllegalArgumentException("左右 / 上下切除之和必须小于 100%");
        }
        return new float[]{removed[0], removed[1], 1f - removed[2], 1f - removed[3]};
    }
}
