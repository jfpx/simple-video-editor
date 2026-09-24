package com.simple.videoeditor.oracle;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class OraclePcmUtils {
    public static final int ENCODING_PCM_16BIT = 2;
    public static final int ENCODING_PCM_8BIT = 3;
    public static final int ENCODING_PCM_FLOAT = 4;

    private OraclePcmUtils() {
    }

    public static float[] decodeToMono(ByteBuffer buffer, int encoding, int channels) throws IOException {
        if (channels <= 0) {
            return new float[0];
        }
        ByteBuffer copy = buffer.slice().order(ByteOrder.nativeOrder());
        if (encoding == ENCODING_PCM_FLOAT) {
            int frames = copy.remaining() / (4 * channels);
            float[] output = new float[frames];
            for (int frame = 0; frame < frames; frame++) {
                float sum = 0f;
                for (int channel = 0; channel < channels; channel++) {
                    sum += copy.getFloat();
                }
                output[frame] = sum / channels;
            }
            return output;
        }
        if (encoding == ENCODING_PCM_8BIT) {
            int frames = copy.remaining() / channels;
            float[] output = new float[frames];
            for (int frame = 0; frame < frames; frame++) {
                float sum = 0f;
                for (int channel = 0; channel < channels; channel++) {
                    sum += ((copy.get() & 0xFF) - 128) / 128f;
                }
                output[frame] = sum / channels;
            }
            return output;
        }
        if (encoding != ENCODING_PCM_16BIT) {
            throw new IOException("Unsupported PCM encoding: " + encoding);
        }
        int frames = copy.remaining() / (2 * channels);
        float[] output = new float[frames];
        for (int frame = 0; frame < frames; frame++) {
            float sum = 0f;
            for (int channel = 0; channel < channels; channel++) {
                sum += copy.getShort() / 32768f;
            }
            output[frame] = sum / channels;
        }
        return output;
    }
}
