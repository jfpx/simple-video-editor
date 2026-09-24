package com.simple.videoeditor;

import android.graphics.SurfaceTexture;
import android.test.AndroidTestCase;
import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.ExportException;

@UnstableApi
public final class CompatibilityDecoderFactoryTest extends AndroidTestCase {
    public void testPqRejectedBeforeDelegate() throws Exception {
        assertRejected(videoFormat(MimeTypes.VIDEO_H265,
                colorInfo(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_ST2084)), false);
    }

    public void testHlgRejectedBeforeDelegate() throws Exception {
        assertRejected(videoFormat(MimeTypes.VIDEO_H265,
                colorInfo(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_HLG)), false);
    }

    public void testDolbyVisionWithoutColorInfoRejectedBeforeDelegate() throws Exception {
        Format format = videoFormat(MimeTypes.VIDEO_DOLBY_VISION, null);
        assertNull(format.colorInfo);
        assertRejected(format, false);
    }

    public void testDolbyVisionWithSdrColorInfoRejectedBeforeDelegate() throws Exception {
        assertRejected(videoFormat(MimeTypes.VIDEO_DOLBY_VISION,
                colorInfo(C.COLOR_SPACE_BT709, C.COLOR_TRANSFER_SDR)), false);
    }

    public void testRequestedToneMappingForSdrRejectedBeforeDelegate() throws Exception {
        assertRejected(videoFormat(MimeTypes.VIDEO_H264,
                colorInfo(C.COLOR_SPACE_BT709, C.COLOR_TRANSFER_SDR)), true);
    }

    public void testRequestedToneMappingWithoutColorInfoRejectedBeforeDelegate() throws Exception {
        assertRejected(videoFormat(MimeTypes.VIDEO_H264, null), true);
    }

    public void testBt601SdrFormatAndSurfacePassedUnchanged() throws Exception {
        assertVideoDelegated(videoFormat(MimeTypes.VIDEO_H264,
                colorInfo(C.COLOR_SPACE_BT601, C.COLOR_TRANSFER_SDR)));
    }

    public void testBt709SdrFormatAndSurfacePassedUnchanged() throws Exception {
        assertVideoDelegated(videoFormat(MimeTypes.VIDEO_H264,
                colorInfo(C.COLOR_SPACE_BT709, C.COLOR_TRANSFER_SDR)));
    }

    public void testVideoWithoutColorInfoPassedUnchanged() throws Exception {
        assertVideoDelegated(videoFormat(MimeTypes.VIDEO_H264, null));
    }

    public void testAudioPassedUnchangedWithoutVideoFiltering() throws Exception {
        RecordingDecoderFactory delegate = new RecordingDecoderFactory();
        CompatibilityDecoderFactory factory = new CompatibilityDecoderFactory(delegate);
        Format audio = new Format.Builder()
                .setSampleMimeType(MimeTypes.AUDIO_AAC)
                .setSampleRate(48000)
                .setChannelCount(2)
                .build();
        assertNull(factory.createForAudioDecoding(audio));
        assertEquals(1, delegate.audioCalls);
        assertSame(audio, delegate.audioFormat);
        assertEquals(0, delegate.videoCalls);

        // Even unexpected HDR metadata must not make the video policy filter audio.
        Format audioWithHdr = audio.buildUpon()
                .setColorInfo(colorInfo(C.COLOR_SPACE_BT2020, C.COLOR_TRANSFER_ST2084))
                .build();
        assertNull(factory.createForAudioDecoding(audioWithHdr));
        assertEquals(2, delegate.audioCalls);
        assertSame(audioWithHdr, delegate.audioFormat);
        assertEquals(0, delegate.videoCalls);
    }

    private static void assertRejected(Format format, boolean requestToneMapping) throws Exception {
        RecordingDecoderFactory delegate = new RecordingDecoderFactory();
        delegate.rejectCalls = true;
        CompatibilityDecoderFactory factory = new CompatibilityDecoderFactory(delegate);
        SurfaceTexture texture = new SurfaceTexture(false);
        Surface surface = new Surface(texture);
        try {
            try {
                factory.createForVideoDecoding(format, surface, requestToneMapping);
                fail("Unsupported HDR/tone mapping must fail before decoder creation");
            } catch (ExportException expected) {
                assertEquals(ExportException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                        expected.errorCode);
                assertTrue("Rejection must explain the unsupported input",
                        expected.getCause() instanceof IllegalArgumentException);
                String message = expected.getCause().getMessage();
                assertNotNull("Missing HDR rejection cause message", message);
                assertTrue("Cause must clearly identify unsupported HDR: " + message,
                        message.contains("HDR input is unsupported"));
            }
            assertEquals("Rejected video must never reach delegate", 0, delegate.videoCalls);
            assertEquals("Video rejection must not call audio delegate", 0, delegate.audioCalls);
        } finally {
            surface.release();
            texture.release();
        }
    }

    private static void assertVideoDelegated(Format format) throws Exception {
        RecordingDecoderFactory delegate = new RecordingDecoderFactory();
        CompatibilityDecoderFactory factory = new CompatibilityDecoderFactory(delegate);
        SurfaceTexture texture = new SurfaceTexture(false);
        Surface surface = new Surface(texture);
        try {
            assertNull(factory.createForVideoDecoding(format, surface, false));
            assertEquals(1, delegate.videoCalls);
            assertSame("Source Format must not be rebuilt or relabeled", format, delegate.videoFormat);
            assertSame("Output Surface must be forwarded", surface, delegate.videoSurface);
            assertFalse("SDR must not request decoder tone mapping", delegate.requestToneMapping);
            assertEquals("Video must not call audio delegate", 0, delegate.audioCalls);
        } finally {
            surface.release();
            texture.release();
        }
    }

    private static Format videoFormat(String mimeType, ColorInfo colorInfo) {
        return new Format.Builder()
                .setSampleMimeType(mimeType)
                .setWidth(320)
                .setHeight(240)
                .setFrameRate(30)
                .setColorInfo(colorInfo)
                .build();
    }

    private static ColorInfo colorInfo(int colorSpace, int colorTransfer) {
        return new ColorInfo.Builder()
                .setColorSpace(colorSpace)
                .setColorRange(C.COLOR_RANGE_LIMITED)
                .setColorTransfer(colorTransfer)
                .build();
    }

    private static final class RecordingDecoderFactory implements Codec.DecoderFactory {
        int audioCalls;
        int videoCalls;
        Format audioFormat;
        Format videoFormat;
        Surface videoSurface;
        boolean requestToneMapping = true;
        boolean rejectCalls;

        @Override
        public Codec createForAudioDecoding(Format format) {
            audioCalls++;
            if (rejectCalls) throw new AssertionError("Rejected input reached audio delegate");
            audioFormat = format;
            return null;
        }

        @Override
        public Codec createForVideoDecoding(Format format, Surface surface,
                boolean requestSdrToneMapping) {
            videoCalls++;
            if (rejectCalls) throw new AssertionError("Rejected input reached video delegate");
            videoFormat = format;
            videoSurface = surface;
            requestToneMapping = requestSdrToneMapping;
            // No codec is needed: these tests inspect delegation, not native decoding.
            return null;
        }
    }
}
