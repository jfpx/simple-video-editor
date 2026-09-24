package com.simple.videoeditor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exercises the final composition PCM boundary, not item-level trimming.
 * AAC priming and padding are measured against a native exact-PCM control;
 * no encoder-delay constant or raw-PCM-equals-decoded-length oracle is used.
 */
@UnstableApi
@SuppressWarnings("deprecation")
public final class TimelineAudioAacTest extends InstrumentationTestCase {
    private static final String TAG = "TimelineAudioAacTest";
    private static final String AAC = MediaFormat.MIMETYPE_AUDIO_AAC;
    private static final int RATE = 48000;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PACKETS = 1024;
    private static final long POLL_US = 1000;
    private static final int[] IRREGULAR_CHUNKS = {127, 1025, 17, 4093, 256};

    public void testOneSecondComboAndUnboundedNegativeControl() throws Exception {
        long deadline = deadline(60000);
        byte[] supplied = pcm(48256, 1, 128, 0, false);
        byte[] exact = pcm(48000, 1, 128, 0, false);
        assertEquals(1005333L, framesToUs(48256));
        byte[] limited = limit(1000000, 1, deadline, supplied);
        assertLeadingMarker(limited, 1);
        Pair pair = compare("combo-48000-from-48256", exact, limited, 1, deadline);

        // This negative control must fail the submitted-PCM budget oracle.
        // Native padding may absorb 256 frames, so AAC growth is diagnostic,
        // not a universal assertion about an encoder's unknown delay.
        Encoded unbounded = encode(supplied, 1, pair.control.codec, deadline);
        Decoded decoded = decode(unbounded, 1, pair.decoded.codec, deadline);
        log("combo-unbounded", unbounded, decoded);
        assertEquals(48256L, unbounded.inputFrames);
        assertEquals(pair.control.inputFrames + 256, unbounded.inputFrames);
        assertTrue(Arrays.equals(supplied, unbounded.inputPcm));
        assertFalse("Unbounded PCM must not pass the exact control",
                Arrays.equals(exact, unbounded.inputPcm));
    }

    public void testAacFrameBoundariesWithSilenceAndStereoTone() throws Exception {
        long deadline = deadline(90000);
        // Test a frame boundary near one second: some native encoders emit no
        // access unit for a one-frame stream shorter than their startup buffering.
        for (int target : new int[]{47 * 1024 - 1, 47 * 1024, 47 * 1024 + 1}) {
            for (boolean silence : new boolean[]{true, false}) {
                checkDeadline(deadline, "boundary matrix");
                int channels = silence ? 1 : 2;
                // Just below the next frame: rounding instead of flooring
                // would admit one extra frame.
                long durationUs = ((target + 1L) * 1000000L + RATE - 1) / RATE - 1;
                assertEquals((long) target, durationUs * RATE / 1000000L);
                byte[] exact = pcm(target, channels, 0, 3, silence);
                byte[] supplied = pcm(target + 257, channels, 0, 3, silence);
                byte[] limited = limit(durationUs, channels, deadline, supplied);
                compare("boundary-" + target + (silence ? "-silence" : "-stereo-tone"),
                        exact, limited, channels, deadline);
            }
        }
    }

    public void testHalfSpeedFinalMixedPcm() throws Exception {
        checkSpeed(.5f, 24064);
    }

    public void testDoubleSpeedFinalMixedPcm() throws Exception {
        // 94 AAC-sized source blocks, as in the observed combo regression.
        checkSpeed(2f, 94 * 1024);
    }

    public void testIntroAndMergedClipsShareOneCompositionBudget() throws Exception {
        long deadline = deadline(45000);
        byte[] intro = pcm(6144, 2, 128, 1, false);
        byte[] firstClip = pcm(17000, 2, 0, 2, false);
        byte[] lastClip = pcm(25112, 2, 0, 4, false);
        // Independent generation of the exact timeline; never use limiter
        // output to construct the control, and never flush between items.
        byte[] exact = join(pcm(6144, 2, 128, 1, false),
                pcm(17000, 2, 0, 2, false), pcm(24856, 2, 0, 4, false));
        byte[] limited = limit(1000000, 2, deadline, intro, firstClip, lastClip);
        assertEquals(48256, (intro.length + firstClip.length + lastClip.length) / 4);
        assertLeadingMarker(limited, 2);
        compare("intro-plus-two-merged-clips", exact, limited, 2, deadline);
    }

    public void testProductionSpeedAndReplacementMusicExport() throws Exception {
        checkProductionMusicExport(false);
    }

    public void testProductionMusicExportIncludesTitleImportedIntroAndMerge() throws Exception {
        checkProductionMusicExport(true);
    }

    private void checkProductionMusicExport(boolean includeIntroAndMerge) throws Exception {
        File directory = new File(getInstrumentation().getTargetContext().getFilesDir(),
                "timeline-aac-" + UUID.randomUUID());
        long deadline = deadline(300000);
        try {
            File source = SelfTestFixture.create(directory,
                    () -> checkDeadline(deadline, "export fixture"));
            File music = new File(directory, "loop.wav");
            writeSilentMusic(music);
            Uri sourceUri = Uri.fromFile(source);
            EditConfig.Builder builder = new EditConfig.Builder(sourceUri, 3000)
                    .sourceSize(320, 240).mainSourceMetadata(true, source.length())
                    .trim(750, 2751).speed(2f).replacementMusic(Uri.fromFile(music));
            long expectedUs = 1_000_500L;
            if (includeIntroAndMerge) {
                IntroTemplate title = new IntroTemplate();
                title.setDurationMs(500);
                EditConfig.ImportedVideo clip = new EditConfig.ImportedVideo(
                        sourceUri, 3000, 320, 240, true, source.length());
                builder.introTemplate(title, "").introVideo(clip)
                        .appendVideos(Collections.singletonList(clip));
                expectedUs += 6_500_000L;
            }
            EditConfig config = builder.build();
            assertTrue(Media3ExportEngine.needsMusicPass(config));
            assertEquals(expectedUs, Media3ExportEngine.editedAudioDurationUs(config));
            File output = new File(directory, "export.mp4");
            String encoder = export(config, output);
            Encoded actual = extractAac(output, encoder, deadline);
            int expectedFrames = (int) (expectedUs * RATE / 1_000_000L);
            // Independently submit exactly the full timeline's silent PCM to the same
            // native encoder. Neither the production limiter nor AAC priming guesses
            // define this control. The short WAV must loop many times in the real export.
            Encoded control = encode(new byte[expectedFrames * 2], 1, encoder, deadline);
            // Inspect all decoded AAC, including native padding, on BOTH arms.
            // MP4 may omit gapless metadata; do not trim just the direct-codec arm.
            Decoded controlDecoded = decode(control, 1, null, deadline, false);
            Decoded actualDecoded = decode(actual, 1, controlDecoded.codec, deadline, false);
            log("production-exact-" + includeIntroAndMerge, control, controlDecoded);
            log("production-export-" + includeIntroAndMerge, actual, actualDecoded);
            assertEquals("Production AAC packet budget", control.packets.size(), actual.packets.size());
            assertEquals("Production full decoded AAC including native padding",
                    controlDecoded.frames, actualDecoded.frames);
        } finally {
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) assertTrue("Cannot delete " + file, file.delete());
            }
            if (directory.exists()) assertTrue(directory.delete());
        }
    }

    private String export(EditConfig config, File output) throws Exception {
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        AtomicReference<String> audioEncoder = new AtomicReference<>();
        List<String> completedPasses = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        try {
            getInstrumentation().runOnMainSync(() -> {
                engine.set(new Media3ExportEngine(getInstrumentation().getTargetContext()));
                engine.get().export(config, output, new Media3ExportEngine.Listener() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onCompleted(File file) { done.countDown(); }
                    @Override public void onError(Exception error) {
                        failure.set(error);
                        done.countDown();
                    }
                    @Override public void onCodecs(String video, String audio) {
                        if (audio != null) audioEncoder.set(audio);
                    }
                    @Override public void onDiagnostic(String message) {
                        if (message.startsWith("completed pass=")) completedPasses.add(message);
                    }
                });
            });
            assertTrue("Production export timed out", done.await(200, TimeUnit.SECONDS));
            if (failure.get() != null) throw failure.get();
            assertTrue("Missing export", output.isFile() && output.length() > 0);
            assertEquals("Both production passes must complete", 2, completedPasses.size());
            assertTrue(completedPasses.get(0).startsWith("completed pass=edit "));
            assertTrue(completedPasses.get(1).startsWith("completed pass=music-transmux "));
            assertNotNull("Missing production AAC encoder", audioEncoder.get());
            return audioEncoder.get();
        } finally {
            getInstrumentation().runOnMainSync(() -> {
                if (engine.get() != null) engine.get().cancel();
            });
        }
    }

    private static void writeSilentMusic(File file) throws Exception {
        int frames = 12000;
        ByteBuffer wav = ByteBuffer.allocate(44 + frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        wav.put(new byte[]{'R', 'I', 'F', 'F'}).putInt(36 + frames * 2)
                .put(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '}).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16)
                .put(new byte[]{'d', 'a', 't', 'a'}).putInt(frames * 2);
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(wav.array());
        }
    }

    private static Encoded extractAac(File file, String encoder, long deadline) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int audioTrack = -1;
            boolean hasVideo = false;
            Encoded result = new Encoded(encoder);
            // Export does not expose submitted PCM; do not infer it from AAC duration.
            result.inputFrames = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) hasVideo = true;
                if (!AAC.equals(mime)) continue;
                assertEquals("Multiple AAC tracks", -1, audioTrack);
                audioTrack = i;
                assertEquals(RATE, format.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                result.config = copy(format.getByteBuffer("csd-0"));
                result.delay = optionalInt(format, MediaFormat.KEY_ENCODER_DELAY);
                result.padding = optionalInt(format, MediaFormat.KEY_ENCODER_PADDING);
                result.format = format.toString();
            }
            assertTrue("Missing production video track", hasVideo);
            assertTrue("Missing production AAC track", audioTrack >= 0);
            extractor.selectTrack(audioTrack);
            ByteBuffer buffer = ByteBuffer.allocate(MAX_BYTES);
            int totalBytes = 0;
            // Negative priming PTS are valid samples, not the extractor's EOS signal.
            while (extractor.getSampleTrackIndex() >= 0) {
                checkDeadline(deadline, "extract production AAC");
                buffer.clear();
                int count = extractor.readSampleData(buffer, 0);
                assertTrue("Empty AAC packet", count > 0);
                totalBytes += count;
                assertTrue(totalBytes <= MAX_BYTES);
                assertTrue(result.packets.size() < MAX_PACKETS);
                buffer.position(0);
                buffer.limit(count);
                result.packets.add(new Packet(copy(buffer), extractor.getSampleTime()));
                if (!extractor.advance()) break;
            }
            assertFalse(result.packets.isEmpty());
            return result;
        } finally {
            extractor.release();
        }
    }

    private void checkSpeed(float speed, int sourceFrames) throws Exception {
        long deadline = deadline(45000);
        byte[] scaled = process(new PitchPreservingAudioProcessor(speed), 1,
                new int[]{4096, 251, 1024}, deadline,
                pcm(sourceFrames, 1, 0, 2, false));
        assertEquals(48128, scaled.length / 2);
        byte[] supplied = join(new byte[128 * 2], scaled);
        assertEquals(48256, supplied.length / 2);

        // Regenerate through a separate real speed processor, with a different
        // chunk schedule. Slice that reference, not the production limiter.
        byte[] reference = process(new PitchPreservingAudioProcessor(speed), 1,
                new int[]{317, 2048, 61}, deadline,
                pcm(sourceFrames, 1, 0, 2, false));
        assertEquals(48128, reference.length / 2);
        byte[] exact = join(new byte[128 * 2], Arrays.copyOf(reference, 47872 * 2));
        byte[] limited = limit(1000000, 1, deadline, supplied);
        assertLeadingMarker(limited, 1);
        compare("speed-" + speed + "-final-mixed-pcm", exact, limited, 1, deadline);
    }

    private static byte[] limit(long durationUs, int channels, long deadline,
                                byte[]... pieces) throws Exception {
        return process(new TimelineAudioProcessor(durationUs), channels,
                IRREGULAR_CHUNKS, deadline, pieces);
    }

    private static byte[] process(AudioProcessor processor, int channels, int[] chunkFrames,
                                  long deadline, byte[]... pieces) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int bytesPerFrame = channels * 2;
        try {
            AudioProcessor.AudioFormat format = new AudioProcessor.AudioFormat(
                    RATE, channels, C.ENCODING_PCM_16BIT);
            AudioProcessor.AudioFormat outputFormat = processor.configure(format);
            assertEquals(format.sampleRate, outputFormat.sampleRate);
            assertEquals(format.channelCount, outputFormat.channelCount);
            assertEquals(format.encoding, outputFormat.encoding);
            processor.flush();
            assertTrue("Processor unexpectedly bypassed", processor.isActive());
            int chunk = 0;
            for (byte[] piece : pieces) {
                assertEquals(0, piece.length % bytesPerFrame);
                int offset = 0;
                while (offset < piece.length) {
                    checkDeadline(deadline, "PCM input");
                    int count = Math.min(piece.length - offset,
                            chunkFrames[chunk++ % chunkFrames.length] * bytesPerFrame);
                    ByteBuffer input = ByteBuffer.allocateDirect(count)
                            .order(ByteOrder.nativeOrder());
                    input.put(piece, offset, count).flip();
                    while (input.hasRemaining()) {
                        checkDeadline(deadline, "PCM consumption");
                        int before = input.remaining();
                        processor.queueInput(input);
                        int emitted = append(result, processor.getOutput());
                        assertTrue("Processor neither consumed nor emitted PCM",
                                input.remaining() < before || emitted > 0);
                    }
                    assertEquals("Input, including discarded tail, must be consumed",
                            input.limit(), input.position());
                    offset += count;
                }
            }
            processor.queueEndOfStream();
            do {
                checkDeadline(deadline, "PCM EOS");
                append(result, processor.getOutput());
            } while (!processor.isEnded());
            assertEquals(0, result.size() % bytesPerFrame);
            return result.toByteArray();
        } finally {
            processor.reset();
        }
    }

    private static Pair compare(String label, byte[] exact, byte[] limited,
                                int channels, long deadline) throws Exception {
        assertEquals(label + " PCM byte count", exact.length, limited.length);
        assertTrue(label + " must preserve every byte before the final boundary",
                Arrays.equals(exact, limited));
        Encoded control = encode(exact, channels, null, deadline);
        Encoded actual = encode(limited, channels, control.codec, deadline);
        assertEquals(label + " encoder identity", control.codec, actual.codec);
        assertEquals(label + " input frames", (long) exact.length / (channels * 2),
                actual.inputFrames);
        assertTrue(label + " control submitted PCM", Arrays.equals(exact, control.inputPcm));
        assertTrue(label + " limited submitted PCM", Arrays.equals(exact, actual.inputPcm));
        assertEquals(label + " native AAC packet count",
                control.packets.size(), actual.packets.size());
        assertEquals(label + " native AAC PTS", packetPts(control), packetPts(actual));
        assertEquals(label + " encoder delay metadata", control.delay, actual.delay);
        assertEquals(label + " encoder padding metadata", control.padding, actual.padding);

        Decoded controlDecoded = decode(control, channels, null, deadline);
        Decoded actualDecoded = decode(actual, channels, controlDecoded.codec, deadline);
        log(label + "-exact", control, controlDecoded);
        log(label + "-limited", actual, actualDecoded);
        assertEquals(label + " decoder identity", controlDecoded.codec, actualDecoded.codec);
        assertEquals(label + " decoded frame count", controlDecoded.frames, actualDecoded.frames);
        assertEquals(label + " decoder delay metadata", controlDecoded.delay, actualDecoded.delay);
        assertEquals(label + " decoder padding metadata",
                controlDecoded.padding, actualDecoded.padding);
        return new Pair(control, controlDecoded);
    }

    private static Encoded encode(byte[] pcm, int channels, String codecName,
                                  long deadline) throws Exception {
        MediaCodec codec = codecName == null ? MediaCodec.createEncoderByType(AAC)
                : MediaCodec.createByCodecName(codecName);
        try {
            Encoded result = new Encoded(codec.getName());
            MediaFormat format = MediaFormat.createAudioFormat(AAC, RATE, channels);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096 * channels * 2);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            int bytesPerFrame = channels * 2;
            assertEquals(0, pcm.length % bytesPerFrame);
            ByteArrayOutputStream submitted = new ByteArrayOutputStream();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int offset = 0;
            boolean inputEnded = false;
            boolean outputEnded = false;
            int encodedBytes = 0;
            while (!outputEnded) {
                checkDeadline(deadline, "AAC encode " + result.codec);
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(POLL_US);
                    if (index >= 0) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        assertNotNull(input);
                        input.clear();
                        long pts = framesToUs(offset / bytesPerFrame);
                        if (offset == pcm.length) {
                            codec.queueInputBuffer(index, 0, 0, pts,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            int capacity = input.remaining() / bytesPerFrame * bytesPerFrame;
                            assertTrue("No complete PCM frame fits codec input", capacity > 0);
                            int count = Math.min(pcm.length - offset,
                                    Math.min(capacity, 1024 * bytesPerFrame));
                            input.put(pcm, offset, count);
                            codec.queueInputBuffer(index, 0, count, pts, 0);
                            submitted.write(pcm, offset, count);
                            offset += count;
                            result.inputFrames += count / bytesPerFrame;
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, POLL_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    assertEquals(AAC, output.getString(MediaFormat.KEY_MIME));
                    assertEquals(RATE, output.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                    assertEquals(channels, output.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    result.delay = optionalInt(output, MediaFormat.KEY_ENCODER_DELAY);
                    result.padding = optionalInt(output, MediaFormat.KEY_ENCODER_PADDING);
                    result.format = output.toString();
                    if (output.containsKey("csd-0")) {
                        result.config = copy(output.getByteBuffer("csd-0"));
                    }
                } else if (index >= 0) {
                    try {
                        if (info.size > 0) {
                            byte[] data = outputBytes(codec, index, info);
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                if (result.config == null) result.config = data;
                            } else {
                                // Each dequeued/released access unit is counted
                                // once. Neither equal payloads nor equal PTS
                                // imply duplicates on a native codec.
                                assertTrue("Excess AAC packets", result.packets.size() < MAX_PACKETS);
                                result.packets.add(new Packet(data, info.presentationTimeUs));
                                encodedBytes += data.length;
                                assertTrue("Excess encoded bytes", encodedBytes <= MAX_BYTES);
                            }
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(index, false);
                    }
                }
            }
            assertTrue("Encoder EOS before all input", inputEnded);
            assertEquals(pcm.length, offset);
            assertEquals((long) pcm.length / bytesPerFrame, result.inputFrames);
            assertFalse("Encoder emitted no AAC packets", result.packets.isEmpty());
            assertNotNull("Encoder did not expose AAC configuration", result.config);
            assertTrue(result.config.length > 0);
            result.inputPcm = submitted.toByteArray();
            return result;
        } finally {
            codec.release();
        }
    }

    private static Decoded decode(Encoded encoded, int channels, String codecName,
                                  long deadline) throws Exception {
        return decode(encoded, channels, codecName, deadline, true);
    }

    private static Decoded decode(Encoded encoded, int channels, String codecName,
                                  long deadline, boolean forwardGaplessMetadata) throws Exception {
        MediaCodec codec = codecName == null ? MediaCodec.createDecoderByType(AAC)
                : MediaCodec.createByCodecName(codecName);
        try {
            Decoded result = new Decoded(codec.getName());
            MediaFormat format = MediaFormat.createAudioFormat(AAC, RATE, channels);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(encoded.config));
            format.setInteger(MediaFormat.KEY_PCM_ENCODING, C.ENCODING_PCM_16BIT);
            int maxPacket = 0;
            for (Packet packet : encoded.packets) maxPacket = Math.max(maxPacket, packet.data.length);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, maxPacket);
            // Forward only metadata actually supplied by this encoder. A codec
            // may apply gapless trimming; both arms must use identical policy.
            if (forwardGaplessMetadata && encoded.delay != null) {
                format.setInteger(MediaFormat.KEY_ENCODER_DELAY, encoded.delay);
            }
            if (forwardGaplessMetadata && encoded.padding != null) {
                format.setInteger(MediaFormat.KEY_ENCODER_PADDING, encoded.padding);
            }
            codec.configure(format, null, null, 0);
            codec.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int next = 0;
            boolean inputEnded = false;
            boolean outputEnded = false;
            boolean sawFormat = false;
            int decodedBytes = 0;
            while (!outputEnded) {
                checkDeadline(deadline, "AAC decode " + result.codec);
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(POLL_US);
                    if (index >= 0) {
                        if (next == encoded.packets.size()) {
                            long lastPts = encoded.packets.get(next - 1).pts;
                            codec.queueInputBuffer(index, 0, 0,
                                    Math.max(lastPts, framesToUs(encoded.inputFrames)),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            Packet packet = encoded.packets.get(next++);
                            ByteBuffer input = codec.getInputBuffer(index);
                            assertNotNull(input);
                            input.clear();
                            assertTrue("AAC packet exceeds decoder input",
                                    input.remaining() >= packet.data.length);
                            input.put(packet.data);
                            codec.queueInputBuffer(index, 0, packet.data.length, packet.pts, 0);
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, POLL_US);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    assertEquals(RATE, output.getInteger(MediaFormat.KEY_SAMPLE_RATE));
                    assertEquals(channels, output.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    Integer encoding = optionalInt(output, MediaFormat.KEY_PCM_ENCODING);
                    assertTrue("Decoder must return PCM16: " + output,
                            encoding == null || encoding == C.ENCODING_PCM_16BIT);
                    result.delay = optionalInt(output, MediaFormat.KEY_ENCODER_DELAY);
                    result.padding = optionalInt(output, MediaFormat.KEY_ENCODER_PADDING);
                    result.format = output.toString();
                    sawFormat = true;
                } else if (index >= 0) {
                    try {
                        if (info.size > 0
                                && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            assertTrue("PCM arrived before decoder format", sawFormat);
                            assertEquals(0, info.size % (channels * 2));
                            // Read the real output buffers, not an AAC packet
                            // count multiplied by an assumed frame size.
                            byte[] data = outputBytes(codec, index, info);
                            decodedBytes += data.length;
                            assertTrue("Excess decoded bytes", decodedBytes <= MAX_BYTES);
                            result.frames += data.length / (channels * 2);
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } finally {
                        codec.releaseOutputBuffer(index, false);
                    }
                }
            }
            assertTrue("Decoder EOS before all input", inputEnded);
            assertEquals(encoded.packets.size(), next);
            assertTrue("Decoder emitted no PCM", result.frames > 0);
            return result;
        } finally {
            codec.release();
        }
    }

    private static byte[] pcm(int frames, int channels, int leadingSilence,
                              int voice, boolean silence) {
        ByteBuffer bytes = ByteBuffer.allocate(frames * channels * 2)
                .order(ByteOrder.nativeOrder());
        for (int frame = 0; frame < frames; frame++) {
            int position = frame - leadingSilence;
            for (int channel = 0; channel < channels; channel++) {
                short sample = 0;
                if (!silence && position >= 0) {
                    sample = position < 16 ? (short) (23000 - position * 431)
                            : (short) Math.round(11000 * Math.sin(
                                    2 * Math.PI * (330 + voice * 110) * position / RATE));
                    if (channel % 2 != 0) sample = (short) -sample;
                }
                bytes.putShort(sample);
            }
        }
        return bytes.array();
    }

    private static void assertLeadingMarker(byte[] pcm, int channels) {
        ByteBuffer samples = ByteBuffer.wrap(pcm).order(ByteOrder.nativeOrder());
        for (int i = 0; i < 128 * channels; i++) {
            assertEquals("Leading delay was removed or shifted at sample " + i,
                    (short) 0, samples.getShort());
        }
        for (int channel = 0; channel < channels; channel++) {
            assertEquals("Marker must remain at frame 128",
                    (short) (channel % 2 == 0 ? 23000 : -23000), samples.getShort());
        }
    }

    private static byte[] join(byte[]... pieces) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] piece : pieces) result.write(piece, 0, piece.length);
        return result.toByteArray();
    }

    private static int append(ByteArrayOutputStream output, ByteBuffer buffer) {
        int count = buffer.remaining();
        assertTrue("Excess processor output", output.size() + count <= MAX_BYTES);
        byte[] data = new byte[count];
        buffer.get(data);
        output.write(data, 0, count);
        return count;
    }

    private static byte[] outputBytes(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
        ByteBuffer output = codec.getOutputBuffer(index);
        assertNotNull(output);
        ByteBuffer slice = output.duplicate();
        slice.position(info.offset);
        slice.limit(info.offset + info.size);
        return copy(slice);
    }

    private static byte[] copy(ByteBuffer source) {
        assertNotNull(source);
        ByteBuffer view = source.duplicate();
        byte[] result = new byte[view.remaining()];
        view.get(result);
        return result;
    }

    private static Integer optionalInt(MediaFormat format, String key) {
        return format.containsKey(key) ? format.getInteger(key) : null;
    }

    private static List<Long> packetPts(Encoded encoded) {
        List<Long> result = new ArrayList<>();
        for (Packet packet : encoded.packets) result.add(packet.pts);
        return result;
    }

    private static long framesToUs(long frames) {
        return frames * 1000000L / RATE;
    }

    private static long deadline(long durationMs) {
        return SystemClock.elapsedRealtime() + durationMs;
    }

    private static void checkDeadline(long deadline, String operation) {
        assertTrue("Deadline exceeded: " + operation, SystemClock.elapsedRealtime() < deadline);
    }

    private static void log(String label, Encoded encoded, Decoded decoded) {
        Log.i(TAG, label + " encoder=" + encoded.codec + " inputFrames=" + encoded.inputFrames
                + " inputEosUs=" + framesToUs(encoded.inputFrames)
                + " uniqueAacPackets=" + encoded.packets.size() + " pts=" + packetPts(encoded)
                + " delay=" + encoded.delay + " padding=" + encoded.padding
                + " decoder=" + decoded.codec + " decodedFrames=" + decoded.frames
                + " decoderDelay=" + decoded.delay + " decoderPadding=" + decoded.padding
                + " encoderFormat=" + encoded.format + " decoderFormat=" + decoded.format);
    }

    private static final class Packet {
        final byte[] data;
        final long pts;

        Packet(byte[] data, long pts) {
            this.data = data;
            this.pts = pts;
        }
    }

    private static final class Encoded {
        final String codec;
        final List<Packet> packets = new ArrayList<>();
        byte[] config;
        byte[] inputPcm;
        long inputFrames;
        Integer delay;
        Integer padding;
        String format;

        Encoded(String codec) {
            this.codec = codec;
        }
    }

    private static final class Decoded {
        final String codec;
        long frames;
        Integer delay;
        Integer padding;
        String format;

        Decoded(String codec) {
            this.codec = codec;
        }
    }

    private static final class Pair {
        final Encoded control;
        final Decoded decoded;

        Pair(Encoded control, Decoded decoded) {
            this.control = control;
            this.decoded = decoded;
        }
    }
}
