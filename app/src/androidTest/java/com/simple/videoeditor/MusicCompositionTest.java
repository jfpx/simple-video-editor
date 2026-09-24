package com.simple.videoeditor;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;

import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.audio.AudioProcessingPipeline;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlMatrixTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Platform instrumentation tests; no external fixtures or test-library dependency. */
@UnstableApi
public final class MusicCompositionTest extends AndroidTestCase {
    private final Uri mainUri = Uri.parse("file:///main.mp4");
    private final Uri introUri = Uri.parse("file:///intro.mp4");
    private final Uri musicUri = Uri.parse("file:///music.m4a");
    private File directory;

    @Override
    protected void tearDown() throws Exception {
        if (directory != null) {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) assertTrue(file.delete());
            assertTrue(directory.delete());
        }
        super.tearDown();
    }

    public void testDefaultStillRetainsOriginalAudio() {
        Composition composition = Media3ExportEngine.createComposition(builder().build());
        assertEquals(1, composition.sequences.size());
        assertFalse(composition.forceAudioTrack);
        assertFalse(first(composition).removeAudio);
        assertFalse(composition.sequences.get(0).isLooping);
    }

    public void testEncodingRateUsesRetimedMainAndUnchangedIntro() {
        assertEquals(48f, Media3ExportEngine.encodingFrameRate(
                builder().speed(2f).build(), 24f, Format.NO_VALUE), 0f);
        assertEquals(12f, Media3ExportEngine.encodingFrameRate(
                builder().speed(.5f).build(), 24f, Format.NO_VALUE), 0f);
        assertEquals(48f, Media3ExportEngine.encodingFrameRate(
                builder().intro(introUri).speed(2f).build(), 24f, 30f), 0f);
        assertEquals(60f, Media3ExportEngine.encodingFrameRate(
                builder().intro(introUri).speed(.5f).build(), 24f, 60f), 0f);
        assertEquals(60f, Media3ExportEngine.encodingFrameRate(
                builder().speed(2f).build(), Format.NO_VALUE, Format.NO_VALUE), 0f);
        assertTrue(Media3ExportEngine.videoEncoderSettings().enableHighQualityTargeting);
    }

    public void testReplacementLoopsWithoutMixingOrSourceGain() {
        Composition composition = Media3ExportEngine.createComposition(
                builder().intro(introUri).replacementMusic(musicUri).volume(0f).build());
        assertEquals(2, composition.sequences.size());
        for (EditedMediaItem item : composition.sequences.get(0).editedMediaItems) {
            assertTrue(item.removeAudio);
            assertTrue(item.effects.audioProcessors.isEmpty());
        }
        EditedMediaItemSequence music = composition.sequences.get(1);
        assertTrue(music.isLooping);
        assertTrue(music.editedMediaItems.get(0).removeVideo);
        assertFalse(music.editedMediaItems.get(0).removeAudio);
        assertTrue(music.editedMediaItems.get(0).effects.audioProcessors.isEmpty());
        assertEquals(musicUri, music.editedMediaItems.get(0).mediaItem.localConfiguration.uri);
    }

    public void testSilentIntroCanPrecedeAudibleMain() {
        Composition composition = Media3ExportEngine.createComposition(builder().intro(introUri).build());
        assertTrue(composition.forceAudioTrack);
        assertFalse(first(composition).removeAudio);
        assertEquals(introUri, first(composition).mediaItem.localConfiguration.uri);
        assertEquals(mainUri, composition.sequences.get(0).editedMediaItems.get(1).mediaItem.localConfiguration.uri);
    }

    public void testMuteDoesNotForceSilentAudioTrack() {
        Composition composition = Media3ExportEngine.createComposition(
                builder().intro(introUri).volume(0f).build());
        assertFalse(composition.forceAudioTrack);
        for (EditedMediaItem item : composition.sequences.get(0).editedMediaItems) assertTrue(item.removeAudio);
    }

    public void testIntroFitsEditedMainCanvasWithoutMainTrimOrSpeed() {
        Composition composition = Media3ExportEngine.createComposition(builder().intro(introUri)
                .trim(500, 2500).crop(0f, 0f, .5f, 1f).rotation(90).outputHeight(480).speed(2f).build());
        EditedMediaItem intro = first(composition);
        assertEquals(MediaItem.ClippingConfiguration.UNSET, intro.mediaItem.clippingConfiguration);
        assertEquals(1, intro.effects.videoEffects.size());
        assertEquals(new Size(720, 480),
                ((GlMatrixTransformation) intro.effects.videoEffects.get(0)).configure(1920, 1080));
        EditedMediaItem main = composition.sequences.get(0).editedMediaItems.get(1);
        assertEquals(500, main.mediaItem.clippingConfiguration.startPositionMs);
        long durationUs = 2_000_000;
        for (Effect effect : main.effects.videoEffects) {
            durationUs = effect.getDurationAfterEffectApplied(durationUs);
        }
        assertEquals(1_000_000L, durationUs);
    }

    public void testIntroRequiresMainDisplaySize() {
        try {
            new EditConfig.Builder(mainUri, 3000).intro(introUri).build();
            fail("Missing main display size must be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("sourceSize"));
        }
    }

    public void testAnamorphicSourceUsesMedia3DisplayCanvas() {
        Size size = Media3ExportEngine.displaySize(720, 576, 0, 16f / 15f);
        assertEquals(new Size(768, 576), size);
        Composition composition = Media3ExportEngine.createComposition(
                new EditConfig.Builder(mainUri, 3000).sourceSize(size.getWidth(), size.getHeight())
                        .intro(introUri).build());
        assertEquals(size, ((GlMatrixTransformation) first(composition).effects.videoEffects.get(0))
                .configure(1920, 1080));
        assertEquals(new Size(480, 320), Media3ExportEngine.displaySize(320, 240, 90, 2f));
        assertEquals(new Size(320, 480), Media3ExportEngine.displaySize(320, 240, 0, .5f));
    }

    public void testBuilderSnapshotAndClearingSelections() {
        EditConfig.Builder builder = builder().intro(introUri).replacementMusic(musicUri);
        EditConfig snapshot = builder.build();
        EditConfig cleared = builder.intro(null).replacementMusic(null).build();
        assertEquals(introUri, snapshot.intro);
        assertEquals(musicUri, snapshot.replacementMusic);
        assertNull(cleared.intro);
        assertNull(cleared.replacementMusic);
    }

    public void testSpeedMusicRendersBeforeLoopingToAvoidMedia3DurationBug() {
        for (float speed : new float[]{.5f, 2f}) {
            EditConfig config = builder().speed(speed).replacementMusic(musicUri).build();
            assertTrue(Media3ExportEngine.needsMusicPass(config));
            Composition picture = Media3ExportEngine.createComposition(config);
            assertEquals(1, picture.sequences.size());
            assertTrue(first(picture).removeAudio);
        }
        assertFalse(Media3ExportEngine.needsMusicPass(builder().replacementMusic(musicUri).build()));
        assertFalse(Media3ExportEngine.needsMusicPass(builder().speed(2f).build()));
        Composition finalPass = Media3ExportEngine.createMusicComposition(mainUri, musicUri);
        assertTrue(finalPass.transmuxVideo);
        assertTrue(first(finalPass).effects.videoEffects.isEmpty());
        assertTrue(finalPass.sequences.get(1).isLooping);
    }

    public void testExportShortMusicLoopsAcrossIntroAndSlowMain() throws Exception {
        verifyMusicExport(.5f, 7000);
    }

    public void testExportShortMusicLoopsAcrossIntroAndFastMain() throws Exception {
        verifyMusicExport(2f, 4000);
    }

    public void testExportLongMusicStopsAtTrimmedVideoEnd() throws Exception {
        File fixture = fixture();
        File output = export(new EditConfig.Builder(Uri.fromFile(fixture), 3000)
                .trim(500, 1500).replacementMusic(Uri.fromFile(fixture)).build());
        assertTrackEnds(output, "video/", 1000, 150);
        assertTrackEnds(output, "audio/", 1000, 150);
    }

    public void testExportSilentIntroThenAudibleSpeedEditedMain() throws Exception {
        File fixture = fixture();
        File silentIntro = export(new EditConfig.Builder(Uri.fromFile(fixture), 3000)
                .trim(0, 1000).volume(0f).build());
        File output = export(new EditConfig.Builder(Uri.fromFile(fixture), 3000)
                .sourceSize(320, 240).intro(Uri.fromFile(silentIntro)).trim(500, 2500).speed(2f).build());
        assertTrackEnds(output, "video/", 2000, 180);
        assertTrackEnds(output, "audio/", 2000, 180);
    }

    public void testSourceSpeedUsesOverflowSafePitchProcessor() {
        for (float speed : new float[]{.5f, 2f}) {
            Composition composition = Media3ExportEngine.createComposition(builder().speed(speed).build());
            assertEquals(1, first(composition).effects.audioProcessors.size());
            AudioProcessor processor = first(composition).effects.audioProcessors.get(0);
            assertTrue(processor instanceof PitchPreservingAudioProcessor);
            assertEquals(Math.round(3_000_000 / (double) speed),
                    processor.getDurationAfterProcessorApplied(3_000_000));
        }
    }

    public void testPitchSearchDoesNotOverflowAtOrdinaryOrLoudLevels() throws Exception {
        for (int rate : new int[]{44100, 48000}) {
            for (double amplitude : new double[]{.08, .32, .9}) {
                for (double hz : new double[]{440, 660, 880, 1100, 1733}) {
                    short[] input = tones(rate, 1, rate, amplitude, new double[]{hz});
                    for (float speed : new float[]{.5f, 2f}) {
                        short[] output = processPcm(new PitchPreservingAudioProcessor(speed),
                                input, rate, 1, speed, 1024);
                        assertTone(output, rate, 1, 0, .2, .2, hz, amplitude);
                    }
                }
            }
        }
    }

    public void testPitchProcessorPreservesToneChangesAndAntiPhaseStereo() throws Exception {
        int rate = 48000;
        double[] frequencies = {317, 660, 880, 1100, 1733};
        short[] input = tones(rate, 2, rate * frequencies.length, .32, frequencies);
        for (float speed : new float[]{.5f, .75f, 1.25f, 2f}) {
            short[] output = processPcm(new PitchPreservingAudioProcessor(speed),
                    input, rate, 2, speed, 997);
            for (int i = 0; i < frequencies.length; i++) {
                for (int channel = 0; channel < 2; channel++) {
                    assertTone(output, rate, 2, channel, (i + .4) / speed,
                            .15 / speed, frequencies[i], .32);
                }
            }
            for (int i = 0; i < output.length; i += 2) {
                assertTrue("Stereo phase relationship changed",
                        Math.abs(output[i] + output[i + 1]) <= 1);
            }
        }
    }

    public void testPitchProcessorChunkingDrainFlushAndExactDurations() throws Exception {
        for (float speed : new float[]{.5f, .73f, .999f, 1f, 1.001f, 1.37f, 2f}) {
            for (int count : new int[]{0, 1, 17, 1023, 8191}) {
                short[] input = tones(48000, 1, count, .9, new double[]{1100});
                PitchPreservingAudioProcessor processor = new PitchPreservingAudioProcessor(speed);
                short[] whole = processPcm(processor, input, 48000, 1, speed,
                        Math.max(1, count));
                short[] split = processPcm(processor, input, 48000, 1, speed, 7);
                assertTrue("Buffer boundaries or flush changed PCM", Arrays.equals(whole, split));
                processor.reset();
                assertTrue(Arrays.equals(whole,
                        processPcm(processor, input, 48000, 1, speed, 1)));
            }
        }
        for (float speed : new float[]{.99999994f, 1.0000001f}) {
            short[] input = tones(48000, 1, 48000, .32, new double[]{1100});
            short[] output = processPcm(new PitchPreservingAudioProcessor(speed),
                    input, 48000, 1, speed, 1009);
            assertTrue("Sub-frame rate change must not delete a period or pad silence",
                    Arrays.equals(input, output));
        }
    }

    public void testPitchProcessorSilenceAndFormatValidation() throws Exception {
        for (float speed : new float[]{.5f, 2f}) {
            short[] output = processPcm(new PitchPreservingAudioProcessor(speed),
                    new short[96000], 48000, 2, speed, 1024);
            for (short sample : output) assertEquals(0, sample);
        }
        for (float speed : new float[]{Float.NaN, Float.POSITIVE_INFINITY, 0f, 2.1f}) {
            try {
                new PitchPreservingAudioProcessor(speed);
                fail("Invalid speed accepted");
            } catch (IllegalArgumentException expected) {
                // Expected.
            }
        }
        PitchPreservingAudioProcessor processor = new PitchPreservingAudioProcessor(2f);
        try {
            processor.configure(new AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_FLOAT));
            fail("Non-PCM16 input accepted");
        } catch (AudioProcessor.UnhandledAudioFormatException expected) {
            // Expected.
        }
        processor.configure(new AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT));
        processor.flush();
        try {
            processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()));
            fail("Incomplete interleaved frame accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
        processor.reset();
    }

    public void testPitchProcessorDrainsThroughMedia3PipelineWithGain() throws Exception {
        for (float speed : new float[]{.5f, .73f, 1.37f, 2f}) {
            for (int count : new int[]{0, 1, 17, 8191}) {
                short[] source = tones(48000, 1, count, .32, new double[]{1100});
                short[] expected = processPcm(new PitchPreservingAudioProcessor(speed),
                        source, 48000, 1, speed, 997);
                PitchPreservingAudioProcessor processor = new PitchPreservingAudioProcessor(speed);
                AudioProcessingPipeline pipeline = new AudioProcessingPipeline(ImmutableList.of(
                        processor, new Media3ExportEngine.GainAudioProcessor(.25f)));
                pipeline.configure(new AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT));
                pipeline.flush();
                ByteBuffer input = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder());
                for (short sample : source) input.putShort(sample);
                input.flip();
                ByteBuffer result = ByteBuffer.allocate(expected.length * 2).order(ByteOrder.nativeOrder());
                while (input.hasRemaining()) {
                    int before = input.position() + result.position();
                    pipeline.queueInput(input);
                    result.put(pipeline.getOutput());
                    assertTrue("Pipeline stalled", input.position() + result.position() > before);
                }
                pipeline.queueEndOfStream();
                for (int tries = 0; !pipeline.isEnded() && tries < 10000; tries++) {
                    result.put(pipeline.getOutput());
                }
                assertTrue("Pipeline EOS did not drain", pipeline.isEnded());
                assertEquals(expected.length * 2, result.position());
                result.flip();
                for (short sample : expected) {
                    assertEquals((short) Math.round(sample * .25f), result.getShort());
                }
                processor.queueInput(AudioProcessor.EMPTY_BUFFER);
                try {
                    processor.queueInput(ByteBuffer.allocateDirect(2).order(ByteOrder.nativeOrder()));
                    fail("Nonempty input after EOS accepted");
                } catch (IllegalStateException expectedError) {
                    // Empty drain calls are legal, but new audio is not.
                }
                pipeline.reset();
            }
        }
    }

    private static short[] tones(int rate, int channels, int frames, double amplitude,
                                 double[] frequencies) {
        short[] samples = new short[frames * channels];
        for (int i = 0; i < frames; i++) {
            double hz = frequencies[Math.min(frequencies.length - 1, i / rate)];
            short value = (short) Math.round(32767 * amplitude * Math.sin(2 * Math.PI * hz * i / rate));
            for (int c = 0; c < channels; c++) {
                samples[i * channels + c] = c % 2 == 0 ? value : (short) -value;
            }
        }
        return samples;
    }

    private static short[] processPcm(PitchPreservingAudioProcessor processor, short[] samples,
                                      int rate, int channels, float speed, int chunkFrames)
            throws Exception {
        processor.configure(new AudioProcessor.AudioFormat(rate, channels, C.ENCODING_PCM_16BIT));
        processor.flush();
        int expected = (int) Math.round(samples.length / channels / (double) speed) * channels;
        ByteBuffer collected = ByteBuffer.allocate(expected * 2).order(ByteOrder.nativeOrder());
        int offset = 0;
        while (offset < samples.length) {
            int count = Math.min(chunkFrames * channels, samples.length - offset);
            ByteBuffer input = ByteBuffer.allocateDirect(count * 2).order(ByteOrder.nativeOrder());
            for (int i = 0; i < count; i++) input.putShort(samples[offset + i]);
            input.flip();
            while (input.hasRemaining()) {
                int beforeInput = input.position();
                int beforeOutput = collected.position();
                processor.queueInput(input);
                collectPcm(processor, collected);
                assertTrue("Audio processor stalled",
                        input.position() > beforeInput || collected.position() > beforeOutput);
            }
            offset += count;
        }
        processor.queueEndOfStream();
        collectPcm(processor, collected);
        assertTrue("EOS did not drain", processor.isEnded());
        assertEquals("Incorrect output frame count", expected * 2, collected.position());
        assertFalse(processor.getOutput().hasRemaining());
        collected.flip();
        short[] result = new short[expected];
        collected.asShortBuffer().get(result);
        return result;
    }

    private static void collectPcm(AudioProcessor processor, ByteBuffer collected) {
        for (int tries = 0; tries < 100000; tries++) {
            ByteBuffer output = processor.getOutput();
            if (!output.hasRemaining()) return;
            assertTrue("Output exceeds duration", output.remaining() <= collected.remaining());
            collected.put(output);
        }
        fail("Unbounded processor drain");
    }

    private static void assertTone(short[] samples, int rate, int channels, int channel,
                                   double start, double duration, double hz, double amplitude) {
        int first = (int) Math.round(start * rate);
        int last = first + (int) Math.round(duration * rate);
        assertTrue(last * channels <= samples.length);
        double energy = 0;
        double firstCrossing = 0;
        double lastCrossing = 0;
        int crossings = 0;
        for (int i = first; i < last; i++) {
            double value = samples[i * channels + channel] / 32768.0;
            energy += value * value;
            if (i > first) {
                double before = samples[(i - 1) * channels + channel] / 32768.0;
                if (before <= 0 && value > 0) {
                    double crossing = i - 1 - before / (value - before);
                    if (crossings == 0) firstCrossing = crossing;
                    lastCrossing = crossing;
                    crossings++;
                }
            }
        }
        assertTrue("Insufficient tone crossings", crossings > 2);
        assertEquals("Pitch at " + hz + "Hz, amplitude " + amplitude,
                hz, (crossings - 1) * rate / (lastCrossing - firstCrossing), hz * .01);
        assertEquals("RMS at " + hz + "Hz, amplitude " + amplitude,
                amplitude / Math.sqrt(2), Math.sqrt(energy / (last - first)), amplitude * .03);
    }

    private void verifyMusicExport(float speed, long expectedMs) throws Exception {
        File fixture = fixture();
        Uri uri = Uri.fromFile(fixture);
        File output = export(new EditConfig.Builder(uri, 3000).sourceSize(320, 240)
                .intro(uri).trim(0, 2000).speed(speed).volume(0f).replacementMusic(uri).build());
        assertTrackEnds(output, "video/", expectedMs, 180);
        assertTrackEnds(output, "audio/", expectedMs, 180);
        for (File file : directory.listFiles()) {
            assertFalse("Intermediate video leaked", file.getName().startsWith(".music-video-"));
        }
    }

    private File fixture() throws Exception {
        directory = new File(getContext().getCacheDir(), "music-test-" + UUID.randomUUID());
        return SelfTestFixture.create(directory, () -> {
            if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Test interrupted");
        });
    }

    private File export(EditConfig config) throws Exception {
        File output = new File(directory, "export-" + UUID.randomUUID() + ".mp4");
        Handler handler = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        handler.post(() -> {
            engine.set(new Media3ExportEngine(getContext()));
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                @Override public void onProgress(int percent) {}
                @Override public void onCompleted(File file) { completed.countDown(); }
                @Override public void onError(Exception failure) {
                    error.set(failure);
                    completed.countDown();
                }
            });
        });
        try {
            assertTrue("Export timed out", completed.await(200, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            assertTrue(output.length() > 0);
            return output;
        } finally {
            CountDownLatch cancelled = new CountDownLatch(1);
            handler.post(() -> {
                if (engine.get() != null) engine.get().cancel();
                cancelled.countDown();
            });
            assertTrue(cancelled.await(10, TimeUnit.SECONDS));
        }
    }

    private static void assertTrackEnds(File output, String mimePrefix, long expectedMs, long toleranceMs)
            throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(output.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                if (format.getString(MediaFormat.KEY_MIME).startsWith(mimePrefix)) track = i;
            }
            assertTrue("Missing " + mimePrefix + " track", track >= 0);
            extractor.selectTrack(track);
            long lastUs = -1;
            while (extractor.getSampleTime() >= 0) {
                lastUs = Math.max(lastUs, extractor.getSampleTime());
                if (!extractor.advance()) break;
            }
            assertTrue(mimePrefix + " ended at " + lastUs / 1000 + "ms, expected " + expectedMs,
                    Math.abs(lastUs / 1000 - expectedMs) <= toleranceMs);
        } finally {
            extractor.release();
        }
    }

    private EditConfig.Builder builder() {
        return new EditConfig.Builder(mainUri, 3000).sourceSize(320, 240);
    }

    private static EditedMediaItem first(Composition composition) {
        return composition.sequences.get(0).editedMediaItems.get(0);
    }
}
