package com.simple.videoeditor.oracle;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.media.AudioFormat;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OracleAndroidDecoder {
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long TOTAL_TIMEOUT_NS = 30_000_000_000L;
    private static final int MAX_VIDEO_FRAMES = 600;
    private static final int MAX_AUDIO_SAMPLES = 600_000;
    private static final long MAX_RETAINED_RGB_BYTES = 24L * 1024L * 1024L;
    private static final String KEY_COLOR_STANDARD = "color-standard";
    private static final String KEY_COLOR_RANGE = "color-range";
    private static final String KEY_COLOR_TRANSFER = "color-transfer";
    private static final int COLOR_STANDARD_BT709 = 1;
    private static final int COLOR_STANDARD_BT601_PAL = 2;
    private static final int COLOR_STANDARD_BT601_NTSC = 4;
    private static final int COLOR_STANDARD_EXTENDED_START = 64;
    private static final int EXTENDED_COLOR_STANDARD_PRIMARIES = 7;
    private static final int COLOR_RANGE_FULL = 1;
    private static final int COLOR_RANGE_LIMITED = 2;
    private static final int COLOR_TRANSFER_LINEAR = 1;
    private static final int COLOR_TRANSFER_SDR_VIDEO = 3;
    private static final int COLOR_TRANSFER_ST2084 = 6;
    private static final int COLOR_TRANSFER_HLG = 7;
    private static final int COLOR_MATRIX_BT709 = 1;
    private static final int COLOR_MATRIX_BT601 = 3;
    private Map<String, Object> videoDiagnostics = newTrackDiagnostics();
    private Map<String, Object> audioDiagnostics = newTrackDiagnostics();
    private Observer observer;

    public interface Observer {
        void checkpoint(Map<String, Object> diagnostics) throws IOException;
    }

    public void setObserver(Observer observer) {
        this.observer = observer;
    }

    public Map<String, Object> getDiagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<String, Object>();
        diagnostics.put("video", Collections.unmodifiableMap(new LinkedHashMap<String, Object>(videoDiagnostics)));
        diagnostics.put("audio", Collections.unmodifiableMap(new LinkedHashMap<String, Object>(audioDiagnostics)));
        return Collections.unmodifiableMap(diagnostics);
    }

    private static Map<String, Object> newTrackDiagnostics() {
        Map<String, Object> track = new LinkedHashMap<String, Object>();
        track.put("present", false);
        track.put("decoder_name", null);
        track.put("input_format", null);
        track.put("output_format", null);
        return track;
    }

    static Map<String, Object> formatDiagnostics(MediaFormat format) {
        Map<String, Object> values = new LinkedHashMap<String, Object>();
        values.put("raw", format.toString());
        values.put("mime", format.getString(MediaFormat.KEY_MIME));
        String[] integerKeys = {
                "width", "height", "crop-left", "crop-top", "crop-right", "crop-bottom",
                "stride", "slice-height", "color-format", KEY_COLOR_STANDARD, KEY_COLOR_RANGE,
                "color-transfer", "sample-rate", "channel-count", "channel-mask", "pcm-encoding",
                "rotation-degrees", "sar-width", "sar-height", "encoder-delay", "encoder-padding"
        };
        for (String key : integerKeys) {
            values.put(key, format.containsKey(key) ? format.getInteger(key) : null);
        }
        values.put("durationUs", format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION) : null);
        return Collections.unmodifiableMap(values);
    }

    public OracleCoreVerifier.Candidate decode(File file) throws IOException {
        return decode(file, null, null);
    }

    public OracleCoreVerifier.Candidate decode(File file, OracleContract contract,
                                               OracleContract.OracleCase oracleCase) throws IOException {
        videoDiagnostics = newTrackDiagnostics();
        audioDiagnostics = newTrackDiagnostics();
        if ((contract == null) != (oracleCase == null)) {
            throw new IOException("Contract and case must be supplied together");
        }
        long deadlineNs = System.nanoTime() + TOTAL_TIMEOUT_NS;
        checkDeadline(deadlineNs);
        if (file == null || !file.isFile()) {
            return new OracleCoreVerifier.Candidate(
                    file == null ? null : file.getAbsolutePath(), null, 0L, 0, 0, null, null);
        }
        long fileBytes = file.length();
        if (fileBytes <= 0L || fileBytes > OracleCoreVerifier.MAX_FILE_BYTES) {
            return new OracleCoreVerifier.Candidate(
                    file.getAbsolutePath(), null, fileBytes, 0, 0, null, null);
        }
        CandidateProbe probe = probe(file, deadlineNs);
        OracleCoreVerifier.VideoTrack video = metadataVideoTrack(probe.video);
        OracleCoreVerifier.AudioTrack audio = metadataAudioTrack(probe.audio);
        boolean decodeVideo = shouldDecodeVideo(contract, oracleCase, probe, video);
        if (decodeVideo) {
            video = decodeVideo(file, contract, oracleCase, probe.video, deadlineNs);
        }
        // Metadata-rejected video cannot reach audio checks; do not apply a shorter
        // case's PCM budget to an unchanged, longer source.
        if ((decodeVideo || oracleCase == null) && shouldDecodeAudio(oracleCase, probe)) {
            audio = decodeAudio(file, oracleCase, probe.audio, deadlineNs);
        }
        String hash = sha256(file, deadlineNs);
        checkDeadline(deadlineNs);
        return new OracleCoreVerifier.Candidate(
                file.getAbsolutePath(), hash, fileBytes,
                probe.videoTrackCount, probe.audioTrackCount, video, audio);
    }

    public OracleCoreVerifier.RgbImage loadAssetImage(InputStream inputStream) throws IOException {
        long deadlineNs = System.nanoTime() + TOTAL_TIMEOUT_NS;
        byte[] bytes = slurp(inputStream, deadlineNs);
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        enforceVideoBudget(bounds.outWidth, bounds.outHeight, 1);
        Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        if (bitmap == null) {
            throw new IOException("Unable to decode bitmap asset");
        }
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            int[] argb = new int[width * height];
            bitmap.getPixels(argb, 0, width, 0, 0, width, height);
            byte[] rgb = new byte[width * height * 3];
            for (int i = 0; i < argb.length; i++) {
                if ((i & 2047) == 0) {
                    checkDeadline(deadlineNs);
                }
                int color = argb[i];
                int base = i * 3;
                rgb[base] = (byte) ((color >> 16) & 0xFF);
                rgb[base + 1] = (byte) ((color >> 8) & 0xFF);
                rgb[base + 2] = (byte) (color & 0xFF);
            }
            return new OracleCoreVerifier.RgbImage(width, height, rgb);
        } finally {
            bitmap.recycle();
        }
    }

    private OracleCoreVerifier.VideoTrack decodeVideo(File file, OracleContract contract,
                                                      OracleContract.OracleCase oracleCase,
                                                      StreamSummary summary, long deadlineNs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            if (summary == null) {
                return null;
            }
            extractor.selectTrack(summary.trackIndex);
            MediaFormat format = extractor.getTrackFormat(summary.trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            // Buffer-mode pixels are rotated below, not by a rendering surface.
            format.setInteger("rotation-degrees", 0);
            videoDiagnostics.put("candidate_path", file.getAbsolutePath());
            videoDiagnostics.put("case_id", oracleCase == null ? null : oracleCase.id);
            codec = OracleVideoCodecSelector.configure(OracleVideoCodecSelector.enumerate(format),
                    format, OracleVideoCodecSelector.ANDROID, selection -> {
                        videoDiagnostics.put("selection", selection);
                        videoDiagnostics.put("decoder_name", selection.get("decoder_name"));
                        if (observer != null) observer.checkpoint(getDiagnostics());
                    }, deadlineNs);
            codec.start();
            ArrayList<OracleCoreVerifier.Frame> frames = new ArrayList<OracleCoreVerifier.Frame>();
            int[] probeFrameIndices = oracleCase == null ? null : new int[oracleCase.probes.size()];
            double[] probeFrameDeltas = oracleCase == null ? null : new double[oracleCase.probes.size()];
            if (probeFrameIndices != null) {
                Arrays.fill(probeFrameIndices, -1);
                Arrays.fill(probeFrameDeltas, Double.MAX_VALUE);
                enforceVideoBudget(summary.displayWidth, summary.displayHeight, oracleCase.probes.size());
            }
            ColorTransform colorTransform = ColorTransform.from(format);
            int maxFrames = maxVideoFrames(contract, oracleCase);
            boolean inputDone = false;
            boolean outputDone = false;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                checkDeadline(deadlineNs);
                if (!inputDone) {
                    inputDone = queueInput(extractor, codec);
                }
                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    videoDiagnostics.put("output_format", formatDiagnostics(outputFormat));
                    colorTransform = ColorTransform.from(outputFormat, format);
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    continue;
                }
                if (outputIndex < 0) {
                    continue;
                }
                if (info.size == 0 || (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                    codec.releaseOutputBuffer(outputIndex, false);
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    continue;
                }
                Image image = codec.getOutputImage(outputIndex);
                if (image == null) {
                    codec.releaseOutputBuffer(outputIndex, false);
                    throw new IOException("Decoder produced no output image");
                }
                try {
                    if (frames.size() >= maxFrames) {
                        throw new IOException("Decoded too many video frames");
                    }
                    OracleCoreVerifier.RgbImage rgb = imageToRgb(image, colorTransform, deadlineNs);
                    if (summary.rotationDegrees == 90 || summary.rotationDegrees == 180
                            || summary.rotationDegrees == 270) {
                        rgb = rotate(rgb, summary.rotationDegrees, deadlineNs);
                    }
                    if (rgb.width != summary.displayWidth || rgb.height != summary.displayHeight) {
                        throw new IOException("Decoded image dimensions differ from track metadata");
                    }
                    enforceVideoBudget(rgb.width, rgb.height,
                            oracleCase == null ? frames.size() + 1 : oracleCase.probes.size());
                    double ptsSeconds = info.presentationTimeUs / 1_000_000d;
                    double[][] barcode = oracleCase == null ? null : barcodeLuma(rgb, oracleCase, deadlineNs);
                    int frameIndex = frames.size();
                    OracleCoreVerifier.Frame frame = new OracleCoreVerifier.Frame(
                            ptsSeconds, oracleCase == null ? rgb : null, barcode);
                    frames.add(frame);
                    if (oracleCase != null) {
                        retainProbeFrame(frames, oracleCase, probeFrameIndices, probeFrameDeltas,
                                frameIndex, ptsSeconds, rgb, barcode);
                    }
                } finally {
                    image.close();
                    codec.releaseOutputBuffer(outputIndex, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
            String sar = sampleAspectRatio(format);
            return new OracleCoreVerifier.VideoTrack(
                    codecName(mime), sar, summary.displayWidth, summary.displayHeight,
                    0, summary.durationSeconds, frames);
        } finally {
            try {
                safeRelease(codec);
            } finally {
                extractor.release();
            }
        }
    }

    private OracleCoreVerifier.AudioTrack decodeAudio(File file, OracleContract.OracleCase oracleCase,
                                                      StreamSummary summary, long deadlineNs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            if (summary == null) {
                return null;
            }
            extractor.selectTrack(summary.trackIndex);
            MediaFormat format = extractor.getTrackFormat(summary.trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            audioDiagnostics.put("decoder_name", codec.getName());
            codec.configure(format, null, null, 0);
            codec.start();
            ArrayList<OracleCoreVerifier.AudioPacket> packets = new ArrayList<OracleCoreVerifier.AudioPacket>();
            FloatCollector samples = new FloatCollector();
            boolean inputDone = false;
            boolean outputDone = false;
            int sampleRate = getInt(format, MediaFormat.KEY_SAMPLE_RATE, summary.sampleRate);
            int channels = getInt(format, MediaFormat.KEY_CHANNEL_COUNT, summary.channels);
            int encoderDelaySamples = getInt(format, "encoder-delay", 0);
            int encoderPaddingSamples = getInt(format, "encoder-padding", 0);
            int aacFrameSamples = aacFrameSamples(format);
            ArrayList<Long> inputPtsUs = new ArrayList<Long>();
            double firstPts = 0d;
            boolean sawFirst = false;
            int maxSamples = maxAudioSamples(oracleCase, sampleRate);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                checkDeadline(deadlineNs);
                if (!inputDone) {
                    inputDone = queueInput(extractor, codec, inputPtsUs);
                }
                int outputIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat outputFormat = codec.getOutputFormat();
                    audioDiagnostics.put("output_format", formatDiagnostics(outputFormat));
                    validateAudioFormat(outputFormat, sampleRate, channels, sawFirst);
                    sampleRate = getInt(outputFormat, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                    channels = getInt(outputFormat, MediaFormat.KEY_CHANNEL_COUNT, channels);
                    continue;
                }
                if (outputIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED || outputIndex < 0) {
                    continue;
                }
                try {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(outputIndex);
                        MediaFormat outputFormat = codec.getOutputFormat(outputIndex);
                        audioDiagnostics.put("output_format", formatDiagnostics(outputFormat));
                        validateAudioFormat(outputFormat, sampleRate, channels, sawFirst);
                        sampleRate = getInt(outputFormat, MediaFormat.KEY_SAMPLE_RATE, sampleRate);
                        channels = getInt(outputFormat, MediaFormat.KEY_CHANNEL_COUNT, channels);
                        int encoding = getInt(outputFormat, MediaFormat.KEY_PCM_ENCODING,
                                AudioFormat.ENCODING_PCM_16BIT);
                        float[] decoded = decodePcmBuffer(buffer, info.offset, info.size, encoding,
                                channels, maxSamples - samples.size());
                        checkDeadline(deadlineNs);
                        if (!sawFirst) {
                            firstPts = info.presentationTimeUs / 1_000_000d;
                            sawFirst = true;
                        }
                        samples.append(decoded);
                        packets.add(new OracleCoreVerifier.AudioPacket(
                                info.presentationTimeUs / 1_000_000d, decoded.length));
                    }
                } finally {
                    codec.releaseOutputBuffer(outputIndex, false);
                }
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                    outputDone = true;
                }
            }
            audioDiagnostics.put("raw_packet_timeline", packetTimeline(packets));
            audioDiagnostics.put("queued_access_units", inputPtsUs.size());
            ArrayList<Long> inputEdges = new ArrayList<Long>(inputPtsUs.subList(0, Math.min(3, inputPtsUs.size())));
            if (inputPtsUs.size() > 3) {
                inputEdges.add(inputPtsUs.get(inputPtsUs.size() - 1));
            }
            audioDiagnostics.put("queued_input_edge_pts_us", Collections.unmodifiableList(inputEdges));
            audioDiagnostics.put("aac_frame_samples", aacFrameSamples);
            audioDiagnostics.put("gapless_expected_samples", aacFrameSamples == 0 ? null
                    : (long) inputPtsUs.size() * aacFrameSamples - encoderDelaySamples - encoderPaddingSamples);
            ArrayList<OracleCoreVerifier.AudioPacket> adjusted = normalizeGaplessPacketPts(
                    packets, sampleRate, encoderDelaySamples, encoderPaddingSamples,
                    sampleRate == getInt(format, MediaFormat.KEY_SAMPLE_RATE, 0) ? aacFrameSamples : 0,
                    inputPtsUs);
            audioDiagnostics.put("gapless_pts_shift_seconds", packets.size() < 2 ? 0d
                    : packets.get(1).ptsSeconds - adjusted.get(1).ptsSeconds);
            packets = adjusted;
            return new OracleCoreVerifier.AudioTrack(
                    codecName(mime), sampleRate, channels, firstPts,
                    summary.durationSeconds, packets, samples.toArray());
        } finally {
            try {
                safeRelease(codec);
            } finally {
                extractor.release();
            }
        }
    }

    private static boolean queueInput(MediaExtractor extractor, MediaCodec codec) throws IOException {
        return queueInput(extractor, codec, null);
    }

    private static boolean queueInput(MediaExtractor extractor, MediaCodec codec,
                                      List<Long> inputPtsUs) throws IOException {
        int index = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (index < 0) {
            return false;
        }
        ByteBuffer buffer = codec.getInputBuffer(index);
        if (buffer == null) {
            throw new IOException("Decoder produced no input buffer");
        }
        buffer.clear();
        int size = extractor.readSampleData(buffer, 0);
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            return true;
        }
        if (size > buffer.capacity()) {
            throw new IOException("Compressed sample exceeds codec input buffer");
        }
        // Extractor encryption/partial flags are NOT codec config/EOS flags.
        if ((extractor.getSampleFlags() & ~MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
            throw new IOException("Encrypted or partial compressed samples are unsupported");
        }
        codec.queueInputBuffer(index, 0, size, extractor.getSampleTime(), 0);
        if (inputPtsUs != null && size > 0) {
            inputPtsUs.add(extractor.getSampleTime());
        }
        extractor.advance();
        return false;
    }

    private static void validateAudioFormat(MediaFormat format, int previousRate,
                                            int previousChannels, boolean sawSamples) throws IOException {
        int rate = getInt(format, MediaFormat.KEY_SAMPLE_RATE, previousRate);
        int channels = getInt(format, MediaFormat.KEY_CHANNEL_COUNT, previousChannels);
        if (rate <= 0 || rate > 192000 || channels <= 0 || channels > 32) {
            throw new IOException("Invalid decoded audio format");
        }
        if (sawSamples && (rate != previousRate || channels != previousChannels)) {
            throw new IOException("Decoded audio format changed after PCM output");
        }
    }

    private static int aacFrameSamples(MediaFormat format) {
        if (!"audio/mp4a-latm".equals(format.getString(MediaFormat.KEY_MIME))
                || getInt(format, "aac-profile", 0) != 2 || !format.containsKey("csd-0")) {
            return 0;
        }
        ByteBuffer config = format.getByteBuffer("csd-0");
        if (config == null || config.remaining() < 2) {
            return 0;
        }
        // The optional 0x2b7 sync extension below explicitly declares SBR absent.
        if (config.remaining() != 2 && (config.remaining() != 5
                || (config.get(config.position() + 2) & 255) != 0x56
                || (config.get(config.position() + 3) & 255) != 0xe5
                || config.get(config.position() + 4) != 0)) {
            return 0;
        }
        int bits = ((config.get(config.position()) & 255) << 8)
                | (config.get(config.position() + 1) & 255);
        int frequencyIndex = (bits >> 7) & 15;
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000,
                22050, 16000, 12000, 11025, 8000, 7350};
        // Only ordinary AAC-LC with 1024-sample frames, not 960/SBR/explicit-rate configs.
        return bits >> 11 == 2 && (bits & 7) == 0 && frequencyIndex < rates.length
                && rates[frequencyIndex] == getInt(format, MediaFormat.KEY_SAMPLE_RATE, 0)
                ? 1024 : 0;
    }

    private static Map<String, Object> packetTimeline(List<OracleCoreVerifier.AudioPacket> packets) {
        Map<String, Object> timeline = new LinkedHashMap<String, Object>();
        ArrayList<Object> edges = new ArrayList<Object>();
        long samples = 0L;
        double maxGap = 0d;
        for (int i = 0; i < packets.size(); i++) {
            OracleCoreVerifier.AudioPacket packet = packets.get(i);
            samples += packet.sampleCount;
            if (i < 3 || i == packets.size() - 1) {
                edges.add(Arrays.asList(packet.ptsSeconds, packet.sampleCount));
            }
            if (i > 0) {
                maxGap = Math.max(maxGap, packet.ptsSeconds - packets.get(i - 1).ptsSeconds);
            }
        }
        timeline.put("edge_pts_seconds_and_samples", Collections.unmodifiableList(edges));
        timeline.put("samples", samples);
        timeline.put("packets", packets.size());
        timeline.put("max_pts_delta_seconds", maxGap);
        return Collections.unmodifiableMap(timeline);
    }

    private static ArrayList<OracleCoreVerifier.AudioPacket> normalizeGaplessPacketPts(
            List<OracleCoreVerifier.AudioPacket> packets, int sampleRate,
            int encoderDelaySamples, int encoderPaddingSamples,
            int aacFrameSamples, List<Long> inputPtsUs) {
        ArrayList<OracleCoreVerifier.AudioPacket> unchanged =
                new ArrayList<OracleCoreVerifier.AudioPacket>(packets);
        if (packets.size() < 2 || sampleRate <= 0
                || aacFrameSamples != 1024 || encoderDelaySamples != aacFrameSamples
                || encoderPaddingSamples <= 0 || encoderPaddingSamples >= aacFrameSamples
                || inputPtsUs.size() != packets.size() + 1) {
            return unchanged;
        }
        OracleCoreVerifier.AudioPacket first = packets.get(0);
        if (first.ptsSeconds != 0d || first.sampleCount != aacFrameSamples - encoderPaddingSamples) {
            return unchanged;
        }
        long totalSamples = 0L;
        for (int i = 0; i < packets.size(); i++) {
            OracleCoreVerifier.AudioPacket packet = packets.get(i);
            // Check every boundary, not just net span: gaps and overlaps can cancel.
            if (Double.isNaN(packet.ptsSeconds) || Double.isInfinite(packet.ptsSeconds)
                    || (i > 0 && packet.sampleCount != aacFrameSamples)
                    || Math.abs(packet.ptsSeconds - i * (double) aacFrameSamples / sampleRate) > 0.000001d) {
                return unchanged;
            }
            totalSamples += packet.sampleCount;
        }
        for (int i = 1; i < inputPtsUs.size(); i++) {
            double elapsedUs = (double) inputPtsUs.get(i) - inputPtsUs.get(0);
            if (Math.abs(elapsedUs - i * (double) aacFrameSamples * 1_000_000d / sampleRate) > 1d) {
                return unchanged;
            }
        }
        if (totalSamples != (long) inputPtsUs.size() * aacFrameSamples
                - encoderDelaySamples - encoderPaddingSamples) {
            return unchanged;
        }
        // Android SkipCutBuffer holds back tail padding starting with the first PCM
        // buffer, then prepends that carry to later buffers without changing their PTS.
        // Account independently for every compressed AAC frame before correcting PTS;
        // never fill missing PCM, flatten arbitrary timestamps, or use oracle expectations.
        double shiftSeconds = encoderPaddingSamples / (double) sampleRate;
        ArrayList<OracleCoreVerifier.AudioPacket> adjusted =
                new ArrayList<OracleCoreVerifier.AudioPacket>(packets.size());
        adjusted.add(first);
        for (int i = 1; i < packets.size(); i++) {
            OracleCoreVerifier.AudioPacket packet = packets.get(i);
            adjusted.add(new OracleCoreVerifier.AudioPacket(
                    packet.ptsSeconds - shiftSeconds, packet.sampleCount));
        }
        return adjusted;
    }

    private static float[] decodePcmBuffer(ByteBuffer buffer, int offset, int size,
                                           int encoding, int channels, int remainingSamples) throws IOException {
        int bytesPerSample;
        if (encoding == AudioFormat.ENCODING_PCM_16BIT) {
            bytesPerSample = 2;
        } else if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            bytesPerSample = 4;
        } else if (encoding == AudioFormat.ENCODING_PCM_8BIT) {
            bytesPerSample = 1;
        } else {
            throw new IOException("Unsupported PCM encoding: " + encoding);
        }
        if (buffer == null || offset < 0 || size < 0 || (long) offset + size > buffer.limit()
                || channels <= 0 || channels > 32 || size % (bytesPerSample * channels) != 0) {
            throw new IOException("Invalid or incomplete decoded PCM buffer");
        }
        if (size / (bytesPerSample * channels) > remainingSamples) {
            throw new IOException("Decoded too many audio samples");
        }
        ByteBuffer copy = buffer.duplicate();
        copy.position(offset);
        copy.limit(offset + size);
        float[] decoded = OraclePcmUtils.decodeToMono(copy, encoding, channels);
        for (float sample : decoded) {
            if (Float.isNaN(sample) || Float.isInfinite(sample)) {
                throw new IOException("Non-finite decoded PCM sample");
            }
        }
        return decoded;
    }

    static OracleCoreVerifier.RgbImage diagnosticRgb(Image image, MediaFormat output,
                                                     MediaFormat input, long deadlineNs) throws IOException {
        return imageToRgb(image, ColorTransform.from(output, input), deadlineNs);
    }

    private static OracleCoreVerifier.RgbImage imageToRgb(Image image, ColorTransform colorTransform,
                                                         long deadlineNs)
            throws IOException {
        Rect crop = image.getCropRect();
        Image.Plane[] planes = image.getPlanes();
        if (image.getFormat() != ImageFormat.YUV_420_888 || planes.length != 3
                || crop.right > image.getWidth() || crop.bottom > image.getHeight()) {
            throw new IOException("Unsupported decoded image format or crop");
        }
        return yuvToRgb(new ByteBuffer[] {planes[0].getBuffer(), planes[1].getBuffer(), planes[2].getBuffer()},
                new int[] {planes[0].getRowStride(), planes[1].getRowStride(), planes[2].getRowStride()},
                new int[] {planes[0].getPixelStride(), planes[1].getPixelStride(), planes[2].getPixelStride()},
                crop, colorTransform, deadlineNs);
    }

    private static OracleCoreVerifier.RgbImage yuvToRgb(ByteBuffer[] buffers, int[] rowStrides,
                                                       int[] pixelStrides, Rect crop,
                                                       ColorTransform colorTransform, long deadlineNs)
            throws IOException {
        checkDeadline(deadlineNs);
        int width = crop.width();
        int height = crop.height();
        enforceVideoBudget(width, height, 1);
        if (crop.left < 0 || crop.top < 0) {
            throw new IOException("Invalid decoded image crop");
        }
        ByteBuffer[] planes = new ByteBuffer[3];
        for (int i = 0; i < planes.length; i++) {
            int divisor = i == 0 ? 1 : 2;
            int lastX = (crop.right - 1) / divisor;
            int lastY = (crop.bottom - 1) / divisor;
            if (buffers[i] == null || rowStrides[i] <= 0 || pixelStrides[i] <= 0
                    || (long) lastX * pixelStrides[i] >= rowStrides[i]
                    || (long) lastY * rowStrides[i] + (long) lastX * pixelStrides[i]
                    >= buffers[i].remaining()) {
                throw new IOException("Invalid or truncated decoded YUV plane " + i);
            }
            planes[i] = buffers[i].slice();
        }
        byte[] rgb = new byte[width * height * 3];
        for (int y = 0; y < height; y++) {
            checkDeadline(deadlineNs);
            int sourceY = crop.top + y;
            int yBase = sourceY * rowStrides[0];
            int uvBase = (sourceY / 2) * rowStrides[1];
            int vvBase = (sourceY / 2) * rowStrides[2];
            for (int x = 0; x < width; x++) {
                int sourceX = crop.left + x;
                int yValue = planes[0].get(yBase + sourceX * pixelStrides[0]) & 0xFF;
                int uValue = planes[1].get(uvBase + (sourceX / 2) * pixelStrides[1]) & 0xFF;
                int vValue = planes[2].get(vvBase + (sourceX / 2) * pixelStrides[2]) & 0xFF;
                int index = (y * width + x) * 3;
                colorTransform.writeRgb(rgb, index, yValue, uValue, vValue);
            }
        }
        return new OracleCoreVerifier.RgbImage(width, height, rgb);
    }

    private static OracleCoreVerifier.RgbImage rotate(OracleCoreVerifier.RgbImage source,
                                                      int rotationDegrees, long deadlineNs) throws IOException {
        int rotation = ((rotationDegrees % 360) + 360) % 360;
        if (rotation == 0) {
            return source;
        }
        if (rotation == 180) {
            OracleCoreVerifier.RgbImage target = new OracleCoreVerifier.RgbImage(source.width, source.height);
            for (int y = 0; y < source.height; y++) {
                checkDeadline(deadlineNs);
                for (int x = 0; x < source.width; x++) {
                    copyPixel(source, x, y, target, source.width - 1 - x, source.height - 1 - y);
                }
            }
            return target;
        }
        OracleCoreVerifier.RgbImage target = new OracleCoreVerifier.RgbImage(source.height, source.width);
        for (int y = 0; y < source.height; y++) {
            checkDeadline(deadlineNs);
            for (int x = 0; x < source.width; x++) {
                if (rotation == 90) {
                    copyPixel(source, x, y, target, source.height - 1 - y, x);
                } else if (rotation == 270) {
                    copyPixel(source, x, y, target, y, source.width - 1 - x);
                }
            }
        }
        return target;
    }

    private static void copyPixel(OracleCoreVerifier.RgbImage source, int sourceX, int sourceY,
                                  OracleCoreVerifier.RgbImage target, int targetX, int targetY) {
        int sourceIndex = (sourceY * source.width + sourceX) * 3;
        int targetIndex = (targetY * target.width + targetX) * 3;
        target.rgb[targetIndex] = source.rgb[sourceIndex];
        target.rgb[targetIndex + 1] = source.rgb[sourceIndex + 1];
        target.rgb[targetIndex + 2] = source.rgb[sourceIndex + 2];
    }

    private CandidateProbe probe(File file, long deadlineNs) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int rotationDegrees = readRotation(file);
            int videoTrackCount = 0;
            int audioTrackCount = 0;
            StreamSummary video = null;
            StreamSummary audio = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                checkDeadline(deadlineNs);
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null) {
                    continue;
                }
                if (mime.startsWith("video/")) {
                    videoTrackCount++;
                    if (video == null) {
                        videoDiagnostics.put("present", true);
                        videoDiagnostics.put("input_format", formatDiagnostics(format));
                        int width = getInt(format, MediaFormat.KEY_WIDTH, 0);
                        int height = getInt(format, MediaFormat.KEY_HEIGHT, 0);
                        int displayWidth = rotationDegrees % 180 == 90 ? height : width;
                        int displayHeight = rotationDegrees % 180 == 90 ? width : height;
                        video = new StreamSummary(i, codecName(mime), sampleAspectRatio(format),
                                rotationDegrees, displayWidth, displayHeight,
                                durationSeconds(format), 0, 0);
                    }
                } else if (mime.startsWith("audio/")) {
                    audioTrackCount++;
                    if (audio == null) {
                        audioDiagnostics.put("present", true);
                        audioDiagnostics.put("input_format", formatDiagnostics(format));
                        audio = new StreamSummary(i, codecName(mime), "1:1", 0, 0, 0,
                                durationSeconds(format),
                                getInt(format, MediaFormat.KEY_SAMPLE_RATE, 0),
                                getInt(format, MediaFormat.KEY_CHANNEL_COUNT, 0));
                    }
                }
            }
            return new CandidateProbe(videoTrackCount, audioTrackCount, video, audio);
        } finally {
            extractor.release();
        }
    }

    private static int readRotation(File file) throws IOException {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getAbsolutePath());
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            int rotation;
            try {
                rotation = value == null ? 0 : Integer.parseInt(value);
            } catch (NumberFormatException error) {
                throw new IOException("Invalid video rotation: " + value, error);
            }
            rotation = ((rotation % 360) + 360) % 360;
            if (rotation % 90 != 0) {
                throw new IOException("Unsupported video rotation: " + rotation);
            }
            return rotation;
        } finally {
            retriever.release();
        }
    }

    private static String sampleAspectRatio(MediaFormat format) {
        int width = getInt(format, "sar-width", 1);
        int height = getInt(format, "sar-height", 1);
        if (width <= 0 || height <= 0) {
            return "1:1";
        }
        return width + ":" + height;
    }

    private static String codecName(String mime) {
        if ("video/avc".equals(mime)) {
            return "h264";
        }
        if ("audio/mp4a-latm".equals(mime)) {
            return "aac";
        }
        return mime == null ? "" : mime.toLowerCase(Locale.US);
    }

    private static OracleCoreVerifier.VideoTrack metadataVideoTrack(StreamSummary summary) {
        if (summary == null) {
            return null;
        }
        return new OracleCoreVerifier.VideoTrack(summary.codec, summary.sampleAspectRatio,
                summary.displayWidth, summary.displayHeight, 0, summary.durationSeconds,
                new ArrayList<OracleCoreVerifier.Frame>(0));
    }

    private static OracleCoreVerifier.AudioTrack metadataAudioTrack(StreamSummary summary) {
        if (summary == null) {
            return null;
        }
        return new OracleCoreVerifier.AudioTrack(summary.codec, summary.sampleRate, summary.channels,
                0d, summary.durationSeconds, new ArrayList<OracleCoreVerifier.AudioPacket>(0), new float[0]);
    }

    private static boolean shouldDecodeVideo(OracleContract contract, OracleContract.OracleCase oracleCase,
                                             CandidateProbe probe, OracleCoreVerifier.VideoTrack video)
            throws IOException {
        if (probe.video == null || video == null) {
            return false;
        }
        if (oracleCase == null || contract == null) {
            return true;
        }
        if (probe.videoTrackCount != oracleCase.videoTrackCount) {
            return false;
        }
        if (video.width != oracleCase.width || video.height != oracleCase.height) {
            return false;
        }
        if (!(video.durationSeconds > 0d && video.durationSeconds <= 12d)) {
            return false;
        }
        double durationTolerance = 1d / minAcceptedFps(oracleCase) + contract.tolerances.videoDurationExtraSeconds;
        if (Math.abs(video.durationSeconds - oracleCase.durationSeconds) > durationTolerance) {
            return false;
        }
        enforceVideoBudget(video.width, video.height, oracleCase.probes.size());
        return true;
    }

    private static boolean shouldDecodeAudio(OracleContract.OracleCase oracleCase, CandidateProbe probe) {
        return probe.audio != null && (oracleCase == null
                || (oracleCase.audioRequired && probe.audioTrackCount == oracleCase.audioTrackCount));
    }

    private static int maxVideoFrames(OracleContract contract, OracleContract.OracleCase oracleCase) {
        if (contract == null || oracleCase == null) {
            return MAX_VIDEO_FRAMES;
        }
        double maxFps = 0d;
        for (Double fps : oracleCase.acceptedCfrFps) {
            maxFps = Math.max(maxFps, fps.doubleValue());
        }
        return Math.max(2, (int) Math.ceil(oracleCase.durationSeconds * maxFps)
                + contract.tolerances.sourceFrameAlignment + 1);
    }

    private static int maxAudioSamples(OracleContract.OracleCase oracleCase, int sampleRate) {
        if (oracleCase == null) {
            return MAX_AUDIO_SAMPLES;
        }
        return Math.min(MAX_AUDIO_SAMPLES,
                oracleCase.expectedDecodedAudioSamples + Math.max(4096, sampleRate / 2));
    }

    private static void enforceVideoBudget(int width, int height, int retainedFrames) throws IOException {
        if (width <= 0 || height <= 0 || width * (long) height
                > MAX_RETAINED_RGB_BYTES / 3L / Math.max(1, retainedFrames)) {
            throw new IOException("Invalid image dimensions or RGB budget exceeded: "
                    + width + "x" + height + " frames=" + retainedFrames);
        }
    }

    private static double minAcceptedFps(OracleContract.OracleCase oracleCase) {
        double min = Double.MAX_VALUE;
        for (Double fps : oracleCase.acceptedCfrFps) {
            min = Math.min(min, fps.doubleValue());
        }
        return min;
    }

    private static double[][] barcodeLuma(OracleCoreVerifier.RgbImage image,
                                          OracleContract.OracleCase oracleCase, long deadlineNs) throws IOException {
        double[][] observed = new double[oracleCase.barcodeRegions.size()][2];
        for (int i = 0; i < oracleCase.barcodeRegions.size(); i++) {
            OracleContract.BarcodeRegion region = oracleCase.barcodeRegions.get(i);
            observed[i][0] = meanLuma(image, region.rect, deadlineNs);
            observed[i][1] = meanLuma(image, region.complementRect, deadlineNs);
        }
        return observed;
    }

    private static void retainProbeFrame(List<OracleCoreVerifier.Frame> frames,
                                         OracleContract.OracleCase oracleCase,
                                         int[] probeFrameIndices, double[] probeFrameDeltas,
                                         int frameIndex, double ptsSeconds,
                                         OracleCoreVerifier.RgbImage image,
                                         double[][] barcodeLumaPairs) {
        ArrayList<Integer> replaced = null;
        for (int i = 0; i < oracleCase.probes.size(); i++) {
            double delta = Math.abs(ptsSeconds - oracleCase.probes.get(i).outputSeconds);
            if (delta < probeFrameDeltas[i]) {
                if (probeFrameIndices[i] >= 0) {
                    if (replaced == null) {
                        replaced = new ArrayList<Integer>();
                    }
                    replaced.add(Integer.valueOf(probeFrameIndices[i]));
                }
                probeFrameDeltas[i] = delta;
                probeFrameIndices[i] = frameIndex;
            }
        }
        if (isRetainedFrame(probeFrameIndices, frameIndex)) {
            frames.set(frameIndex, new OracleCoreVerifier.Frame(ptsSeconds, image, barcodeLumaPairs));
        }
        if (replaced != null) {
            for (Integer replacedIndex : replaced) {
                int index = replacedIndex.intValue();
                if (!isRetainedFrame(probeFrameIndices, index)) {
                    OracleCoreVerifier.Frame old = frames.get(index);
                    if (old.image != null) {
                        frames.set(index, new OracleCoreVerifier.Frame(
                                old.ptsSeconds, null, old.barcodeLumaPairs));
                    }
                }
            }
        }
    }

    private static boolean isRetainedFrame(int[] probeFrameIndices, int frameIndex) {
        for (int index : probeFrameIndices) {
            if (index == frameIndex) {
                return true;
            }
        }
        return false;
    }

    private static double meanLuma(OracleCoreVerifier.RgbImage image, OracleContract.Rect rect,
                                   long deadlineNs) throws IOException {
        if (rect.left < 0 || rect.top < 0 || rect.right > image.width || rect.bottom > image.height
                || rect.left >= rect.right || rect.top >= rect.bottom) {
            throw new IOException("Barcode rectangle outside decoded image");
        }
        long total = 0L;
        int pixels = 0;
        for (int y = rect.top; y < rect.bottom; y++) {
            checkDeadline(deadlineNs);
            int row = y * image.width;
            for (int x = rect.left; x < rect.right; x++) {
                int index = (row + x) * 3;
                total += (image.rgb[index] & 0xFF)
                        + (image.rgb[index + 1] & 0xFF)
                        + (image.rgb[index + 2] & 0xFF);
                pixels++;
            }
        }
        return pixels == 0 ? 0d : total / (pixels * 3d);
    }

    private static int getInt(MediaFormat format, String key, int fallback) {
        return format != null && format.containsKey(key) ? format.getInteger(key) : fallback;
    }

    private static double durationSeconds(MediaFormat format) {
        return format != null && format.containsKey(MediaFormat.KEY_DURATION)
                ? format.getLong(MediaFormat.KEY_DURATION) / 1_000_000d
                : 0d;
    }

    private static byte[] slurp(InputStream inputStream, long deadlineNs) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        ByteArrayBuilder builder = new ByteArrayBuilder();
        while ((read = inputStream.read(buffer)) >= 0) {
            checkDeadline(deadlineNs);
            if (read > MAX_RETAINED_RGB_BYTES - builder.size) {
                throw new IOException("Bitmap asset exceeds byte budget");
            }
            builder.append(buffer, read);
        }
        return builder.toArray();
    }

    private static String sha256(File file, long deadlineNs) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            java.io.FileInputStream input = new java.io.FileInputStream(file);
            try {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkDeadline(deadlineNs);
                    digest.update(buffer, 0, read);
                }
            } finally {
                input.close();
            }
            byte[] hash = digest.digest();
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                builder.append(String.format(Locale.US, "%02x", value & 0xFF));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static void checkDeadline(long deadlineNs) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Decoding interrupted");
        }
        if (System.nanoTime() > deadlineNs) {
            throw new IOException("Decoding timed out");
        }
    }

    private static int clip(int value) {
        return value < 0 ? 0 : (value > 255 ? 255 : value);
    }

    private static void safeRelease(MediaCodec codec) {
        if (codec == null) {
            return;
        }
        codec.release();
    }

    private static final class CandidateProbe {
        private final int videoTrackCount;
        private final int audioTrackCount;
        private final StreamSummary video;
        private final StreamSummary audio;

        private CandidateProbe(int videoTrackCount, int audioTrackCount,
                               StreamSummary video, StreamSummary audio) {
            this.videoTrackCount = videoTrackCount;
            this.audioTrackCount = audioTrackCount;
            this.video = video;
            this.audio = audio;
        }
    }

    private static final class StreamSummary {
        private final int trackIndex;
        private final String codec;
        private final String sampleAspectRatio;
        private final int rotationDegrees;
        private final int displayWidth;
        private final int displayHeight;
        private final double durationSeconds;
        private final int sampleRate;
        private final int channels;

        private StreamSummary(int trackIndex, String codec, String sampleAspectRatio,
                              int rotationDegrees, int displayWidth, int displayHeight,
                              double durationSeconds, int sampleRate, int channels) {
            this.trackIndex = trackIndex;
            this.codec = codec;
            this.sampleAspectRatio = sampleAspectRatio;
            this.rotationDegrees = rotationDegrees;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.durationSeconds = durationSeconds;
            this.sampleRate = sampleRate;
            this.channels = channels;
        }
    }

    private static final class ColorTransform {
        private final double yOffset;
        private final double yScale;
        private final double rCr;
        private final double gCb;
        private final double gCr;
        private final double bCb;

        private ColorTransform(double yOffset, double yScale,
                               double rCr, double gCb, double gCr, double bCb) {
            this.yOffset = yOffset;
            this.yScale = yScale;
            this.rCr = rCr;
            this.gCb = gCb;
            this.gCr = gCr;
            this.bCb = bCb;
        }

        private static ColorTransform from(MediaFormat format) throws IOException {
            return from(format, null);
        }

        private static ColorTransform from(MediaFormat format, MediaFormat fallback) throws IOException {
            int standard = getInt(format, KEY_COLOR_STANDARD, getInt(fallback, KEY_COLOR_STANDARD, 0));
            int range = getInt(format, KEY_COLOR_RANGE, getInt(fallback, KEY_COLOR_RANGE, 0));
            int transfer = getInt(format, KEY_COLOR_TRANSFER, getInt(fallback, KEY_COLOR_TRANSFER, 0));
            int matrix = matrixFromStandard(standard);
            boolean limited = range == 0 || range == COLOR_RANGE_LIMITED;
            if (range != 0 && range != COLOR_RANGE_LIMITED && range != COLOR_RANGE_FULL) {
                throw new IOException("Unsupported color range: " + range);
            }
            if (transfer == COLOR_TRANSFER_ST2084 || transfer == COLOR_TRANSFER_HLG) {
                throw new IOException("Unsupported HDR color transfer: " + transfer);
            }
            if (transfer != 0 && transfer != COLOR_TRANSFER_LINEAR && transfer != COLOR_TRANSFER_SDR_VIDEO) {
                throw new IOException("Unsupported color transfer: " + transfer);
            }
            switch (matrix) {
                case COLOR_MATRIX_BT709:
                    return limited
                            ? new ColorTransform(16d, 255d / 219d, 1.7927411d, -0.2132486d, -0.5329093d, 2.1124018d)
                            : new ColorTransform(0d, 1d, 1.5748d, -0.187324d, -0.468124d, 1.8556d);
                case COLOR_MATRIX_BT601:
                    return limited
                            ? new ColorTransform(16d, 255d / 219d, 1.5960268d, -0.3917623d, -0.8129676d, 2.0172321d)
                            : new ColorTransform(0d, 1d, 1.402d, -0.344136d, -0.714136d, 1.772d);
                default:
                    throw new IOException("Unsupported color matrix: " + matrix);
            }
        }

        private static int matrixFromStandard(int standard) throws IOException {
            if (standard == 0 || standard == COLOR_STANDARD_BT601_PAL
                    || standard == COLOR_STANDARD_BT601_NTSC) {
                return COLOR_MATRIX_BT601;
            }
            if (standard == COLOR_STANDARD_BT709) {
                return COLOR_MATRIX_BT709;
            }
            if (standard >= COLOR_STANDARD_EXTENDED_START) {
                // AOSP ColorUtils packs defined-but-nonpublic standards as
                // extendedStart + primaries + matrix * 7.
                int packed = standard - COLOR_STANDARD_EXTENDED_START;
                return packed / EXTENDED_COLOR_STANDARD_PRIMARIES;
            }
            throw new IOException("Unsupported color standard: " + standard);
        }

        private void writeRgb(byte[] output, int index, int yValue, int cbValue, int crValue) {
            double y = (yValue - yOffset) * yScale;
            double cb = cbValue - 128d;
            double cr = crValue - 128d;
            output[index] = (byte) clip((int) Math.round(y + rCr * cr));
            output[index + 1] = (byte) clip((int) Math.round(y + gCb * cb + gCr * cr));
            output[index + 2] = (byte) clip((int) Math.round(y + bCb * cb));
        }
    }

    private static final class FloatCollector {
        private float[] values = new float[16384];
        private int size;

        void append(float[] block) {
            ensure(size + block.length);
            System.arraycopy(block, 0, values, size, block.length);
            size += block.length;
        }

        int size() {
            return size;
        }

        float[] toArray() {
            float[] copy = new float[size];
            System.arraycopy(values, 0, copy, 0, size);
            return copy;
        }

        private void ensure(int needed) {
            if (needed <= values.length) {
                return;
            }
            int next = values.length;
            while (next < needed) {
                next *= 2;
            }
            float[] grown = new float[next];
            System.arraycopy(values, 0, grown, 0, size);
            values = grown;
        }
    }

    private static final class ByteArrayBuilder {
        private byte[] values = new byte[8192];
        private int size;

        void append(byte[] block, int length) {
            ensure(size + length);
            System.arraycopy(block, 0, values, size, length);
            size += length;
        }

        byte[] toArray() {
            byte[] copy = new byte[size];
            System.arraycopy(values, 0, copy, 0, size);
            return copy;
        }

        private void ensure(int needed) {
            if (needed <= values.length) {
                return;
            }
            int next = values.length;
            while (next < needed) {
                next *= 2;
            }
            byte[] grown = new byte[next];
            System.arraycopy(values, 0, grown, 0, size);
            values = grown;
        }
    }
}
