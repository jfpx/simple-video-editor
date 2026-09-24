package com.simple.videoeditor;

import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;

import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.util.Size;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.GlMatrixTransformation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;

import com.simple.videoeditor.oracle.OracleAndroidDecoder;
import com.simple.videoeditor.oracle.OracleContract;
import com.simple.videoeditor.oracle.OracleCoreVerifier;
import com.simple.videoeditor.oracle.OracleCoreVerifier.AudioTrack;
import com.simple.videoeditor.oracle.OracleCoreVerifier.Candidate;
import com.simple.videoeditor.oracle.OracleCoreVerifier.Frame;
import com.simple.videoeditor.oracle.OracleCoreVerifier.RgbImage;
import com.simple.videoeditor.oracle.OracleGeneratedContract;

import java.io.File;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Supplemental title regressions, not additions to or replacements for the frozen oracle cases. */
@UnstableApi
public final class IntroCompositionTest extends AndroidTestCase {
    private static final Uri MAIN = Uri.parse("file:///main.mp4");
    private static final Uri INTRO = Uri.parse("file:///intro.mp4");
    private static final Uri MUSIC = Uri.parse("file:///music.m4a");
    private static final Uri TITLE = Uri.parse("file:///title.png");
    private static final Uri TITLE_DELAY = Uri.parse("file:///title-delay.wav");
    private static final int BACKGROUND = 0xFF2458B4;
    private File directory;

    public void testTitleSnapshotSurvivesTemplateAndBuilderMutation() {
        IntroTemplate template = template();
        template.setTextSize(37);
        template.setTextColor(0xFFAABBCC);
        template.setTextX(.2f);
        template.setTextY(.7f);
        template.setFontStyle("bold_italic");
        EditConfig.Builder builder = builder().intro(INTRO).replacementMusic(MUSIC)
                .introTemplate(template, "first\nsecond");
        template.setDurationMs(2000);
        template.setBackgroundColor(0xFFFF0000);
        template.setTextSize(80);
        template.setTextColor(0xFF000000);
        template.setTextX(.9f);
        template.setTextY(.1f);
        template.setFontStyle("normal");
        template.setText("changed");
        EditConfig saved = builder.build();
        builder.introTemplate(template, "replacement").intro(null).replacementMusic(null);
        EditConfig.IntroTitle title = saved.introTitle;
        assertEquals("first\nsecond", title.text);
        assertEquals(1000, title.durationMs);
        assertEquals(37, title.textSizeSp);
        assertEquals(0xFFAABBCC, title.textColor);
        assertEquals(BACKGROUND, title.backgroundColor);
        assertEquals(.2f, title.textX, 0f);
        assertEquals(.7f, title.textY, 0f);
        assertEquals("bold_italic", title.fontStyle);
        assertEquals(INTRO, saved.intro);
        assertEquals(MUSIC, saved.replacementMusic);
        assertEquals("replacement", builder.build().introTitle.text);
    }

    public void testClearingTitleKeepsImportedIntroAndMusic() {
        EditConfig.Builder builder = builder().intro(INTRO).replacementMusic(MUSIC)
                .introTemplate(template(), "");
        EditConfig saved = builder.build();
        EditConfig cleared = builder.introTemplate(null, null).build();
        assertNotNull(saved.introTitle);
        assertNull(cleared.introTitle);
        assertEquals(INTRO, cleared.intro);
        assertEquals(MUSIC, cleared.replacementMusic);
        assertEquals(2, Media3ExportEngine.createComposition(cleared)
                .sequences.get(0).editedMediaItems.size());
    }

    public void testInvalidTitleSettingsAreExplicitlyRejected() {
        for (int invalid : new int[]{0, -1}) {
            IntroTemplate duration = template();
            duration.setDurationMs(invalid);
            rejects(IllegalArgumentException.class, "positive",
                    () -> builder().introTemplate(duration, ""));
            IntroTemplate size = template();
            size.setTextSize(invalid);
            rejects(IllegalArgumentException.class, "positive",
                    () -> builder().introTemplate(size, ""));
        }
        for (float invalid : new float[]{-.01f, 1.01f, Float.NaN,
                Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            IntroTemplate x = template();
            x.setTextX(invalid);
            rejects(IllegalArgumentException.class, "textX", () -> builder().introTemplate(x, ""));
            IntroTemplate y = template();
            y.setTextY(invalid);
            rejects(IllegalArgumentException.class, "textY", () -> builder().introTemplate(y, ""));
        }
        IntroTemplate style = template();
        style.setFontStyle("unsupported");
        rejects(IllegalArgumentException.class, "font style", () -> builder().introTemplate(style, ""));
        style.setFontStyle(null);
        rejects(NullPointerException.class, "font style", () -> builder().introTemplate(style, ""));
        rejects(NullPointerException.class, "intro text", () -> builder().introTemplate(template(), null));
        rejects(IllegalArgumentException.class, "sourceSize",
                () -> new EditConfig.Builder(MAIN, 4000).introTemplate(template(), "").build());
        rejects(IllegalArgumentException.class, "rendered title image",
                () -> Media3ExportEngine.createComposition(builder().introTemplate(template(), "").build()));
    }

    public void testTitleThenFullIntroThenMainWithMainOnlyEdits() {
        for (float speed : new float[]{.5f, 2f}) {
            Composition composition = Media3ExportEngine.createComposition(builder().intro(INTRO)
                    .introTemplate(template(), "").trim(500, 2500).speed(speed)
                    .crop(0f, 0f, .5f, 1f).rotation(90).outputHeight(480)
                    .overlayText("main only").build(), TITLE);
            assertEquals(1, composition.sequences.size());
            assertFalse(composition.sequences.get(0).isLooping);
            assertTrue(composition.forceAudioTrack);
            List<EditedMediaItem> items = composition.sequences.get(0).editedMediaItems;
            assertEquals(3, items.size());
            EditedMediaItem title = items.get(0), intro = items.get(1), main = items.get(2);
            assertEquals(TITLE, title.mediaItem.localConfiguration.uri);
            assertEquals(1000L, title.mediaItem.localConfiguration.imageDurationMs);
            assertEquals(30, title.frameRate);
            assertEquals(MediaItem.ClippingConfiguration.UNSET, title.mediaItem.clippingConfiguration);
            assertTrue(title.removeAudio);
            assertTrue(title.effects.videoEffects.isEmpty());
            assertTrue(title.effects.audioProcessors.isEmpty());
            assertEquals(INTRO, intro.mediaItem.localConfiguration.uri);
            assertEquals(MediaItem.ClippingConfiguration.UNSET, intro.mediaItem.clippingConfiguration);
            assertEquals(1, intro.effects.videoEffects.size());
            assertEquals(new Size(720, 480),
                    ((GlMatrixTransformation) intro.effects.videoEffects.get(0)).configure(320, 240));
            assertEquals(4_000_000L, effectedDuration(intro, 4_000_000));
            assertTrue(intro.effects.audioProcessors.isEmpty());
            assertFalse(intro.removeAudio);
            assertEquals(MAIN, main.mediaItem.localConfiguration.uri);
            assertEquals(500L, main.mediaItem.clippingConfiguration.startPositionMs);
            assertEquals(2500L, main.mediaItem.clippingConfiguration.endPositionMs);
            assertEquals(speed == 2f ? 1_000_000L : 4_000_000L,
                    effectedDuration(main, 2_000_000));
        }
    }

    public void testTitleMuteAndReplacementMusicStructures() {
        EditConfig.Builder builder = builder().intro(INTRO).introTemplate(template(), "").volume(0f);
        Composition muted = Media3ExportEngine.createComposition(builder.build(), TITLE);
        assertFalse(muted.forceAudioTrack);
        assertEquals(1, muted.sequences.size());
        for (EditedMediaItem item : muted.sequences.get(0).editedMediaItems) assertTrue(item.removeAudio);
        for (float speed : new float[]{1f, .5f, 2f}) {
            EditConfig config = builder.speed(speed).replacementMusic(MUSIC).build();
            Composition picture = Media3ExportEngine.createComposition(config, TITLE);
            assertFalse(picture.forceAudioTrack);
            assertEquals(3, picture.sequences.get(0).editedMediaItems.size());
            for (EditedMediaItem item : picture.sequences.get(0).editedMediaItems) {
                assertTrue(item.removeAudio);
                assertTrue(item.effects.audioProcessors.isEmpty());
            }
            assertEquals(speed != 1f, Media3ExportEngine.needsMusicPass(config));
            assertEquals(speed == 1f ? 2 : 1, picture.sequences.size());
            Composition withMusic = speed == 1f ? picture
                    : Media3ExportEngine.createMusicComposition(MAIN, MUSIC);
            EditedMediaItemSequence music = withMusic.sequences.get(1);
            assertTrue(music.isLooping);
            assertEquals(1, music.editedMediaItems.size());
            EditedMediaItem item = music.editedMediaItems.get(0);
            assertEquals(MUSIC, item.mediaItem.localConfiguration.uri);
            assertTrue(item.removeVideo);
            assertFalse(item.removeAudio);
            assertTrue(item.effects.audioProcessors.isEmpty());
        }
    }

    public void testTitleOnlyDelaysMainAudioWithoutForcedSilentTrack() {
        for (float speed : new float[]{1f, .5f, 2f}) {
            Composition composition = Media3ExportEngine.createComposition(new EditConfig.Builder(MAIN, 4000)
                    .sourceSize(320, 240).introTemplate(template(), "").trim(500, 2500)
                    .speed(speed).volume(.25f).build(), TITLE, TITLE_DELAY);
            assertFalse(composition.forceAudioTrack);
            assertFalse(composition.transmuxAudio);
            assertEquals(2, composition.sequences.size());
            List<EditedMediaItem> videoItems = composition.sequences.get(0).editedMediaItems;
            assertEquals(2, videoItems.size());
            assertTrue(videoItems.get(0).removeAudio);
            assertTrue(videoItems.get(1).removeAudio);
            assertFalse(videoItems.get(1).removeVideo);
            EditedMediaItemSequence audio = composition.sequences.get(1);
            assertFalse(audio.isLooping);
            assertEquals(2, audio.editedMediaItems.size());
            EditedMediaItem silence = audio.editedMediaItems.get(0);
            assertEquals(TITLE_DELAY, silence.mediaItem.localConfiguration.uri);
            assertTrue(silence.removeVideo);
            assertFalse(silence.removeAudio);
            assertEquals(MediaItem.ClippingConfiguration.UNSET, silence.mediaItem.clippingConfiguration);
            assertTrue(silence.effects.audioProcessors.isEmpty());
            EditedMediaItem mainAudio = audio.editedMediaItems.get(1);
            assertEquals(MAIN, mainAudio.mediaItem.localConfiguration.uri);
            assertFalse(mainAudio.removeAudio);
            assertTrue(mainAudio.removeVideo);
            assertEquals(speed == 1f ? 1 : 2, mainAudio.effects.audioProcessors.size());
        }
    }

    public void testExportTitleThenFastTrimmedMainPixelsAndPcm() throws Exception {
        verifyTitleAndMain(2f, 2d, new double[]{1.15, 1.5, 1.85});
    }

    public void testTitleAudioCannotFallBackToDefaultFormatGap() {
        EditConfig config = builder().introTemplate(template(), "").build();
        rejects(IllegalArgumentException.class, "probed source audio delay",
                () -> Media3ExportEngine.createComposition(config, TITLE));
    }

    public void testTitleWithNoSourceAudioDoesNotSynthesizeATrack() {
        Composition composition = Media3ExportEngine.createComposition(
                builder().introTemplate(template(), "").build(), TITLE, null);
        assertFalse(composition.forceAudioTrack);
        assertEquals(1, composition.sequences.size());
        for (EditedMediaItem item : composition.sequences.get(0).editedMediaItems) {
            assertTrue(item.removeAudio);
            assertFalse(item.removeVideo);
        }
    }

    public void testExportTitleThenSlowTrimmedMainPixelsAndPcm() throws Exception {
        verifyTitleAndMain(.5f, 5d, new double[]{1.4, 2.5, 4.6});
    }

    public void testExportTitleImportedIntroFastMainAndLoopedMusic() throws Exception {
        verifyTitleIntroMusic(2f, 6d, new double[]{5.15, 5.5, 5.85});
    }

    public void testExportTitleImportedIntroSlowMainAndLoopedMusic() throws Exception {
        verifyTitleIntroMusic(.5f, 9d, new double[]{5.4, 6.5, 8.6});
    }

    public void testExportMutedTitleAndMainHasNoAudioTrack() throws Exception {
        Uri fixture = fixture();
        Candidate candidate = decode(export(new EditConfig.Builder(fixture, 4000).sourceSize(320, 240)
                .introTemplate(template(), "").trim(500, 2500).speed(2f).outputHeight(120)
                .volume(0f).build()));
        assertVideo(candidate, 2d);
        assertTitle(candidate);
        assertSource(candidate, 1.5, 1d, .5d, 2d);
        assertEquals(0, candidate.audioTrackCount);
        assertNull(candidate.audio);
    }

    public void testOversizedTitleExportRejectsAndCleansReservedFiles() throws Exception {
        Uri fixture = fixture();
        EditConfig config = new EditConfig.Builder(fixture, 4000).sourceSize(5000, 240)
                .introTemplate(template(), "").speed(2f).replacementMusic(fixture).build();
        File output = output();
        Exception failure = runExport(config, output);
        assertTrue("Expected explicit canvas rejection, got " + failure,
                failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains("4096"));
        assertFalse("Failed output leaked", output.exists());
        assertNoWorkFiles();
    }

    private void verifyTitleAndMain(float speed, double duration, double[] mainTimes) throws Exception {
        Uri fixture = fixture();
        IntroTemplate template = template();
        EditConfig.Builder builder = new EditConfig.Builder(fixture, 4000).sourceSize(320, 240)
                .introTemplate(template, "").trim(500, 2500).speed(speed).volume(.25f).outputHeight(120);
        EditConfig snapshot = builder.build();
        template.setBackgroundColor(0xFFFF0000);
        template.setDurationMs(2000);
        builder.introTemplate(template, "must not appear").speed(1f);
        Candidate candidate = decode(export(snapshot));
        assertVideo(candidate, duration);
        assertTitle(candidate);
        for (double time : mainTimes) assertSource(candidate, time, 1d, .5d, speed);
        assertAudio(candidate, duration);
        assertEquals("Title must preserve source sample rate", 48000, candidate.audio.sampleRate);
        assertEquals("Title must preserve source channel count", 1, candidate.audio.channels);
        assertTrue("Title must be silent without music", rms(window(candidate.audio, .5)) < .003);
        assertTone(candidate.audio, mainTimes[0], 440, .25);
        assertTone(candidate.audio, mainTimes[1], 660, .25);
        assertTone(candidate.audio, mainTimes[2], 880, .25);
    }

    private void verifyTitleIntroMusic(float speed, double duration, double[] mainTimes) throws Exception {
        Uri fixture = fixture();
        Candidate candidate = decode(export(new EditConfig.Builder(fixture, 4000).sourceSize(320, 240)
                .introTemplate(template(), "").intro(fixture).trim(500, 2500).speed(speed)
                .volume(0f).replacementMusic(fixture).outputHeight(120).build()));
        // Standard fixture is 4s, not the separate 3s SelfTestFixture: 1 + 4 + 2 / speed.
        assertVideo(candidate, duration);
        assertTitle(candidate);
        for (double time : new double[]{1.2, 2.5, 4.75}) assertSource(candidate, time, 1d, 0d, 1d);
        for (double time : mainTimes) assertSource(candidate, time, 5d, .5d, speed);
        assertAudio(candidate, duration);
        for (double time = .5; time < duration; time += 1d) {
            assertTone(candidate.audio, time, 440 + 220 * ((int) time % 4), 1d);
        }
        assertTone(candidate.audio, duration - .2, 440 + 220 * ((int) (duration - .2) % 4), 1d);
    }

    private static void assertVideo(Candidate candidate, double duration) {
        assertEquals(1, candidate.videoTrackCount);
        assertNotNull(candidate.video);
        assertEquals(160, candidate.video.width);
        assertEquals(120, candidate.video.height);
        assertEquals("video duration", duration, candidate.video.durationSeconds, .12);
        List<Frame> frames = candidate.video.frames;
        assertTrue("No decoded video", frames.size() > 1);
        assertEquals("first decoded PTS", 0d, frames.get(0).ptsSeconds, .05);
        assertEquals("last decoded PTS", duration, frames.get(frames.size() - 1).ptsSeconds, .12);
        double previous = -1d;
        for (Frame frame : frames) {
            assertTrue("Non-monotonic decoded PTS", frame.ptsSeconds > previous);
            if (previous >= 0d) assertTrue("Gap in video", frame.ptsSeconds - previous <= .1);
            previous = frame.ptsSeconds;
        }
    }

    private static void assertTitle(Candidate candidate) {
        for (double time : new double[]{.1, .5, .9}) {
            RgbImage image = nearest(candidate, time).image;
            int[] expected = {0x24, 0x58, 0xB4};
            int bad = 0;
            for (int i = 0; i < image.rgb.length; i++) {
                if (Math.abs((image.rgb[i] & 255) - expected[i % 3]) > 18) bad++;
            }
            assertTrue("Decoded title is missing, blank black, wrong color, or overlaid at " + time,
                    bad <= image.rgb.length / 100);
        }
    }

    private void assertSource(Candidate candidate, double time, double segmentStart,
                              double sourceStart, double speed) throws Exception {
        Frame frame = nearest(candidate, time);
        RgbImage image = frame.image;
        int barcode = 0;
        // Frozen fixture's seven complementary bit cells, scaled exactly from 320x240 to 160x120.
        for (int bit = 0; bit < 7; bit++) {
            double top = gray(image, 50 + 9 * bit, 50);
            double bottom = gray(image, 50 + 9 * bit, 58);
            boolean high = top > bottom;
            assertEquals("barcode top", high ? 235d : 20d, top, 36d);
            assertEquals("barcode complement", high ? 20d : 235d, bottom, 36d);
            if (high) barcode |= 1 << bit;
        }
        int expected = (int) Math.floor((sourceStart + (frame.ptsSeconds - segmentStart) * speed) * 24 + 1e-6);
        assertTrue("At " + frame.ptsSeconds + "s expected source frame " + expected + ", got " + barcode,
                Math.abs(barcode - expected) <= 1);
        OracleContract.Asset asset = OracleGeneratedContract.create().requireCase("resize").frameAssets.get(barcode);
        assertNotNull("Barcode outside standard fixture", asset);
        try (InputStream input = getContext().getAssets().open("video-oracle/" + asset.path.replace('\\', '/'))) {
            RgbImage reference = new OracleAndroidDecoder().loadAssetImage(input);
            assertEquals(reference.width, image.width);
            assertEquals(reference.height, image.height);
            long absoluteError = 0;
            int largeErrors = 0;
            for (int i = 0; i < image.rgb.length; i++) {
                int error = Math.abs((image.rgb[i] & 255) - (reference.rgb[i] & 255));
                absoluteError += error;
                if (error > 45) largeErrors++;
            }
            assertTrue("Decoded frame differs from frozen resize image",
                    absoluteError / (double) image.rgb.length <= 12d);
            assertTrue("Decoded frame p95 error exceeds 45", largeErrors <= image.rgb.length * .05);
        }
    }

    private static double gray(RgbImage image, int left, int top) {
        int total = 0;
        for (int y = top; y < top + 4; y++) for (int x = left; x < left + 4; x++) {
            int offset = (y * image.width + x) * 3;
            for (int c = 0; c < 3; c++) total += image.rgb[offset + c] & 255;
        }
        return total / 48d;
    }

    private static Frame nearest(Candidate candidate, double time) {
        Frame nearest = null;
        for (Frame frame : candidate.video.frames) {
            if (nearest == null || Math.abs(frame.ptsSeconds - time) < Math.abs(nearest.ptsSeconds - time)) {
                nearest = frame;
            }
        }
        assertNotNull("Missing decoded frame at " + time, nearest);
        assertEquals("Decoded frame timing", time, nearest.ptsSeconds, .05);
        assertNotNull("RGB was not decoded", nearest.image);
        return nearest;
    }

    private static void assertAudio(Candidate candidate, double duration) {
        assertEquals(1, candidate.audioTrackCount);
        AudioTrack audio = candidate.audio;
        assertNotNull(audio);
        assertEquals("audio duration", duration, audio.durationSeconds, .12);
        assertEquals("PCM start", 0d, audio.startSeconds, .05);
        assertTrue(audio.sampleRate > 0);
        assertEquals("decoded PCM duration", duration, audio.samples.length / (double) audio.sampleRate, .12);
        assertFalse("No decoded audio packets", audio.packets.isEmpty());
        double next = audio.startSeconds;
        for (OracleCoreVerifier.AudioPacket packet : audio.packets) {
            assertEquals("PCM packet gap/overlap", next, packet.ptsSeconds, .003);
            next = packet.ptsSeconds + packet.sampleCount / (double) audio.sampleRate;
        }
        assertEquals("last decoded PCM end", duration, next, .12);
    }

    private static float[] window(AudioTrack audio, double center) {
        int start = (int) Math.round((center - .06 - audio.startSeconds) * audio.sampleRate);
        int end = start + (int) Math.round(.12 * audio.sampleRate);
        assertTrue("Missing PCM window at " + center, start >= 0 && end <= audio.samples.length);
        return Arrays.copyOfRange(audio.samples, start, end);
    }

    private static void assertTone(AudioTrack audio, double time, double hz, double gain) {
        float[] samples = window(audio, time);
        double expectedRms = .22627416997969518 * gain;
        assertEquals("PCM RMS at " + time, expectedRms, rms(samples), expectedRms * .12 + .002);
        assertEquals("PCM pitch at " + time, hz,
                OracleCoreVerifier.dominantFrequency(samples, audio.sampleRate), 12d);
    }

    private static double rms(float[] samples) {
        double sum = 0;
        for (float sample : samples) {
            assertFalse("Non-finite PCM", Float.isNaN(sample) || Float.isInfinite(sample));
            sum += sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }

    private Uri fixture() throws Exception {
        directory = new File(getContext().getFilesDir(), "intro-regression-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
        return Uri.fromFile(OracleVerifier.prepareFixture(getContext()));
    }

    private File output() {
        return new File(directory, "export-" + UUID.randomUUID() + ".mp4");
    }

    private File export(EditConfig config) throws Exception {
        File output = output();
        Exception failure = runExport(config, output);
        if (failure != null) throw failure;
        assertTrue("Export callback without output", output.isFile() && output.length() > 0);
        assertNoWorkFiles();
        return output;
    }

    private Exception runExport(EditConfig config, File output) throws Exception {
        assertTrue("Tests must not block main looper", Looper.myLooper() != Looper.getMainLooper());
        Handler main = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        assertTrue(main.post(() -> {
            try {
                engine.set(new Media3ExportEngine(getContext()));
                engine.get().export(config, output, new Media3ExportEngine.Listener() {
                    @Override public void onProgress(int percent) {}
                    @Override public void onCompleted(File file) { done.countDown(); }
                    @Override public void onError(Exception error) {
                        failure.set(error);
                        done.countDown();
                    }
                });
            } catch (Exception error) {
                failure.set(error);
                done.countDown();
            }
        }));
        try {
            assertTrue("Export exceeded 200s", done.await(200, TimeUnit.SECONDS));
            return failure.get();
        } finally {
            CountDownLatch stopped = new CountDownLatch(1);
            assertTrue(main.post(() -> {
                try {
                    if (engine.get() != null) engine.get().cancel();
                } finally {
                    stopped.countDown();
                }
            }));
            assertTrue("Main-thread cancellation exceeded 10s", stopped.await(10, TimeUnit.SECONDS));
        }
    }

    private static Candidate decode(File output) throws Exception {
        // Generic decoding has a 30s deadline, 600-frame/600k-mono-sample/24MiB RGB caps.
        // 160x120 keeps the longest 9s title export below those caps; no case-duration gate.
        return new OracleAndroidDecoder().decode(output);
    }

    private void assertNoWorkFiles() {
        File[] files = directory.listFiles();
        assertNotNull(files);
        for (File file : files) {
            assertFalse("Title image leaked: " + file, file.getName().startsWith(".intro-title-"));
            assertFalse("Title delay audio leaked: " + file, file.getName().startsWith(".intro-audio-delay-"));
            assertFalse("Music intermediate leaked: " + file, file.getName().startsWith(".music-video-"));
        }
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (directory != null) {
                File[] files = directory.listFiles();
                assertNotNull(files);
                for (File file : files) assertTrue("Cannot delete " + file, file.delete());
                assertTrue(directory.delete());
            }
        } finally {
            super.tearDown();
        }
    }

    private static IntroTemplate template() {
        IntroTemplate template = new IntroTemplate();
        template.setDurationMs(1000);
        template.setBackgroundColor(BACKGROUND);
        return template;
    }

    private static EditConfig.Builder builder() {
        return new EditConfig.Builder(MAIN, 4000).sourceSize(320, 240);
    }

    private static long effectedDuration(EditedMediaItem item, long durationUs) {
        for (Effect effect : item.effects.videoEffects) durationUs = effect.getDurationAfterEffectApplied(durationUs);
        return durationUs;
    }

    private static void rejects(Class<? extends RuntimeException> type, String message, Runnable action) {
        try {
            action.run();
            fail("Expected " + type.getSimpleName() + ": " + message);
        } catch (RuntimeException error) {
            assertTrue("Wrong rejection: " + error, type.isInstance(error));
            assertNotNull(error.getMessage());
            assertTrue("Missing explicit reason: " + error, error.getMessage().contains(message));
        }
    }
}
