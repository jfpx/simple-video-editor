package com.simple.videoeditor;

import android.view.Surface;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.ExportException;

/** Reject HDR at each source, including later merged clips, before any decoder tone mapping. */
@UnstableApi
final class CompatibilityDecoderFactory implements Codec.DecoderFactory {
    private final Codec.DecoderFactory delegate;

    CompatibilityDecoderFactory(Codec.DecoderFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Codec createForAudioDecoding(Format format) throws ExportException {
        return delegate.createForAudioDecoding(format);
    }

    @Override
    public Codec createForVideoDecoding(Format format, Surface surface, boolean requestSdrToneMapping)
            throws ExportException {
        if (requestSdrToneMapping || ColorInfo.isTransferHdr(format.colorInfo)
                || MimeTypes.VIDEO_DOLBY_VISION.equals(format.sampleMimeType)) {
            throw ExportException.createForCodec(
                    new IllegalArgumentException("兼容编码不支持 HDR 输入 / HDR input is unsupported; "
                            + "disable compatibility encoding to use the default HDR path"),
                    ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    new ExportException.CodecInfo(format.toString(), true, true, null));
        }
        return delegate.createForVideoDecoding(format, surface, false);
    }
}
