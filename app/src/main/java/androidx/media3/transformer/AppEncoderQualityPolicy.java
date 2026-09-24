package androidx.media3.transformer;

import androidx.media3.common.util.UnstableApi;

/**
 * Narrow bridge to the pinned Media3 1.5.1 quality policy. Keep its device-aware bitrate targeting
 * without DefaultEncoderFactory's AVC profile overrides or resolution fallback.
 */
@UnstableApi
public final class AppEncoderQualityPolicy {
    private AppEncoderQualityPolicy() {}

    public static int bitrate(String encoder, int width, int height, float frameRate) {
        return new DeviceMappedEncoderBitrateProvider().getBitrate(encoder, width, height, frameRate);
    }
}
