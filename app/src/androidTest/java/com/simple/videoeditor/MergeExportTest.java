package com.simple.videoeditor;

import android.content.Context;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;

import androidx.media3.common.util.UnstableApi;

import com.simple.videoeditor.oracle.OracleAndroidDecoder;
import com.simple.videoeditor.oracle.OracleCoreVerifier;
import com.simple.videoeditor.oracle.OracleCoreVerifier.AudioTrack;
import com.simple.videoeditor.oracle.OracleCoreVerifier.Candidate;
import com.simple.videoeditor.oracle.OracleCoreVerifier.Frame;
import com.simple.videoeditor.oracle.OracleCoreVerifier.RgbImage;

import junit.framework.AssertionFailedError;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@UnstableApi
public final class MergeExportTest extends InstrumentationTestCase {
    private File directory;
    private boolean requireSoftwareCompatibility;

    public void testSoftwarePreferenceMergeRetainsOrderAndSilentAudioGaps() throws Exception {
        boolean original = VideoEncodingSettings.compatibilityEnabled(targetContext());
        try {
            VideoEncodingSettings.setCompatibilityEnabled(targetContext(), true);
            requireSoftwareCompatibility = true;
            testExportMergeRetainsOrderAndSilentAudioGaps();
        } finally {
            requireSoftwareCompatibility = false;
            VideoEncodingSettings.setCompatibilityEnabled(targetContext(), original);
            assertEquals(original, VideoEncodingSettings.compatibilityEnabled(targetContext()));
        }
    }

    public void testExportMergeRetainsOrderAndSilentAudioGaps() throws Exception {
        MergeSupplementalFixtureFactory.FixtureSet fixtures = fixtures();
        Candidate candidate = decode(export(sequenceConfig(fixtures, null), false));
        assertEquals(1, candidate.videoTrackCount);
        assertEquals(1, candidate.audioTrackCount);
        assertEquals(160, candidate.video.width);
        assertEquals(120, candidate.video.height);
        assertEquals(5.3, candidate.video.durationSeconds, .065);
        assertEquals(5.3, candidate.audio.durationSeconds, .085);
        assertSequenceVisuals(candidate, fixtures);
        assertSilent(candidate.audio, .5);
        assertTone(candidate.audio, 1.4, 330);
        assertTone(candidate.audio, 2.3, 660);
        assertTone(candidate.audio, 2.6, 880);
        assertSilent(candidate.audio, 3.2);
        assertTone(candidate.audio, 4.1, 550);
        assertSilent(candidate.audio, 5.0);
        assertOrderFails(candidate, new double[]{.5, 1.6, 2.35, 3.2, 4.2, 5.0},
                new int[]{0x663399, fixtures.get("intro_audio").spec.baseColor,
                        fixtures.get("extra_audio_rot270").spec.baseColor, fixtures.get("main_audio_rot90").spec.baseColor,
                        fixtures.get("extra_silent_square").spec.baseColor, fixtures.get("extra_silent_wide").spec.baseColor});
    }

    public void testReplacementMusicLoopsAcrossMergedSequence() throws Exception {
        MergeSupplementalFixtureFactory.FixtureSet fixtures = fixtures();
        Candidate candidate = decode(export(sequenceConfig(fixtures, Uri.fromFile(fixtures.get("intro_audio").file)), false));
        assertSequenceVisuals(candidate, fixtures);
        assertEquals(1, candidate.audioTrackCount);
        for (double time : new double[]{.5, 1.6, 2.4, 3.4, 4.4, 5.1}) {
            assertTone(candidate.audio, time, 330);
        }
    }

    public void testMutedMergeStillExportsAllVideoClips() throws Exception {
        MergeSupplementalFixtureFactory.FixtureSet fixtures = fixtures();
        Candidate candidate = decode(export(sequenceBuilder(fixtures).volume(0f).build(), false));
        assertSequenceVisuals(candidate, fixtures);
        assertEquals(0, candidate.audioTrackCount);
        assertNull(candidate.audio);
    }

    public void testSilentMainBeforeAudibleExtraKeepsNativeAudioOrder() throws Exception {
        MergeSupplementalFixtureFactory.FixtureSet fixtures = fixtures();
        MergeSupplementalFixtureFactory.GeneratedClip main = fixtures.get("extra_silent_square");
        MergeSupplementalFixtureFactory.GeneratedClip extra = fixtures.get("extra_audio_rot270");
        EditConfig config = new EditConfig.Builder(Uri.fromFile(main.file), main.spec.durationMs)
                .mainSourceMetadata(false, main.file.length())
                .mergeMode(true)
                .sourceSize(main.spec.displayWidth(), main.spec.displayHeight())
                .appendVideos(Arrays.asList(extra.imported()))
                .outputHeight(120)
                .build();
        Candidate candidate = decode(export(config, false));
        assertEquals(120, candidate.video.width);
        assertEquals(120, candidate.video.height);
        assertEquals(1.8, candidate.video.durationSeconds, .2);
        assertClipColor(candidate, .4, main.spec.baseColor, 30);
        assertClipColor(candidate, 1.4, extra.spec.baseColor, 30);
        assertFrameCode(candidate, .4, frameIndex(fixtures, main.spec.durationMs, 400),
                fullRect(candidate.video.width, candidate.video.height));
        Rect extraFit = fitRect(candidate.video.width, candidate.video.height,
                extra.spec.displayWidth(), extra.spec.displayHeight());
        assertSourceFrameCode(candidate, 1.4, frameIndex(fixtures, extra.spec.durationMs, 400),
                extraFit, extra.spec, 0);
        assertTopBottomLetterbox(candidate, 1.4, extra.spec, 35);
        assertSilent(candidate.audio, .4);
        assertTone(candidate.audio, 1.4, 550);
    }

    public void testAllSilentMergeExportsNoAudioTrack() throws Exception {
        MergeSupplementalFixtureFactory.FixtureSet fixtures = fixtures();
        MergeSupplementalFixtureFactory.GeneratedClip main = fixtures.get("extra_silent_square");
        MergeSupplementalFixtureFactory.GeneratedClip extra = fixtures.get("extra_silent_wide");
        EditConfig config = new EditConfig.Builder(Uri.fromFile(main.file), main.spec.durationMs)
                .mainSourceMetadata(false, main.file.length())
                .mergeMode(true)
                .sourceSize(main.spec.displayWidth(), main.spec.displayHeight())
                .appendVideos(Arrays.asList(extra.imported()))
                .outputHeight(120)
                .build();
        Candidate candidate = decode(export(config, false));
        assertEquals(0, candidate.audioTrackCount);
        assertNull(candidate.audio);
        assertClipColor(candidate, .4, main.spec.baseColor, 30);
        assertClipColor(candidate, 1.4, extra.spec.baseColor, 30);
        assertFrameCode(candidate, .4, frameIndex(fixtures, main.spec.durationMs, 400),
                fullRect(candidate.video.width, candidate.video.height));
        assertCenterAccent(candidate, .4, main.spec.accentColor, 35);
        assertTopBottomLetterbox(candidate, 1.4, extra.spec, 35);
    }

    public void testSlowMainAndHalfGainLeaveAppendedClipsAtNativeSpeed() throws Exception {
        MergeSupplementalFixtureFactory.FixtureSet fixtures = fixtures();
        Candidate candidate = decode(export(sequenceBuilder(fixtures).speed(.5f).volume(.5f).build(), false));
        assertEquals(7.1, candidate.video.durationSeconds, .065);
        assertClipColor(candidate, 1.6, fixtures.get("intro_audio").spec.baseColor, 30);
        assertClipColor(candidate, 2.8, fixtures.get("main_audio_rot90").spec.baseColor, 30);
        assertClipColor(candidate, 4.2, fixtures.get("main_audio_rot90").spec.baseColor, 30);
        assertClipColor(candidate, 5.1, fixtures.get("extra_silent_square").spec.baseColor, 30);
        assertClipColor(candidate, 5.9, fixtures.get("extra_audio_rot270").spec.baseColor, 30);
        assertClipColor(candidate, 6.8, fixtures.get("extra_silent_wide").spec.baseColor, 30);
        for (double[] tone : new double[][]{{1.6, 330}, {2.8, 660}, {4.0, 880}, {5.9, 550}}) {
            assertTone(candidate.audio, tone[0], tone[1]);
            float[] samples = window(candidate.audio, tone[0]);
            double squareSum = 0;
            for (float sample : samples) squareSum += sample * sample;
            assertEquals(.18 / Math.sqrt(2) * .5, Math.sqrt(squareSum / samples.length), .02);
        }
        assertSilent(candidate.audio, .5);
        assertSilent(candidate.audio, 5.1);
        assertSilent(candidate.audio, 6.8);
    }

    public void testCancelCleansMergeWorkFiles() throws Exception {
        MergeSupplementalFixtureFactory.FixtureSet fixtures = fixtures();
        File output = new File(directory, "cancel-" + UUID.randomUUID() + ".mp4");
        EditConfig config = sequenceConfig(fixtures, null);
        Handler main = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        assertTrue(main.post(() -> {
            engine.set(new Media3ExportEngine(targetContext()));
            main.postDelayed(() -> {
                if (cancelled.compareAndSet(false, true) && engine.get() != null) {
                    engine.get().cancel();
                }
            }, 100);
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                @Override public void onProgress(int percent) {
                    if (cancelled.compareAndSet(false, true)) {
                        engine.get().cancel();
                    }
                }

                @Override public void onCompleted(File file) {
                    error.set(new AssertionFailedError("Export completed before cancellation"));
                    done.countDown();
                }

                @Override public void onError(Exception failure) {
                    error.set(failure);
                    done.countDown();
                }
            });
        }));
        assertTrue(done.await(120, TimeUnit.SECONDS));
        assertNotNull(error.get());
        assertTrue(error.get().toString(), error.get() instanceof java.util.concurrent.CancellationException
                || error.get().getCause() instanceof java.util.concurrent.CancellationException
                || error.get().toString().contains("cancel"));
        assertFalse(output.exists());
        assertNoWorkFiles();
    }

    private MergeSupplementalFixtureFactory.FixtureSet fixtures() throws Exception {
        directory = new File(targetContext().getCacheDir(), "merge-test-" + UUID.randomUUID());
        return MergeSupplementalFixtureFactory.prepare(assetContext(), directory, () -> {
            if (Thread.currentThread().isInterrupted()) throw new java.io.IOException("Interrupted");
        });
    }

    private EditConfig.Builder sequenceBuilder(MergeSupplementalFixtureFactory.FixtureSet fixtures) {
        MergeSupplementalFixtureFactory.GeneratedClip main = fixtures.get("main_audio_rot90");
        return new EditConfig.Builder(Uri.fromFile(main.file), main.spec.durationMs)
                .mainSourceMetadata(true, main.file.length())
                .mergeMode(true)
                .sourceSize(main.spec.displayWidth(), main.spec.displayHeight())
                .introVideo(fixtures.get("intro_audio").imported())
                .introTemplate(template(), "")
                .appendVideos(Arrays.asList(
                        fixtures.get("extra_silent_square").imported(),
                        fixtures.get("extra_audio_rot270").imported(),
                        fixtures.get("extra_silent_wide").imported()))
                .trim(600, 1800)
                .crop(.25f, 0f, 1f, 1f)
                .outputHeight(120)
                .speed(2f);
    }

    private EditConfig sequenceConfig(MergeSupplementalFixtureFactory.FixtureSet fixtures, Uri replacementMusic) {
        EditConfig.Builder builder = sequenceBuilder(fixtures);
        if (replacementMusic != null) builder.replacementMusic(replacementMusic).volume(0f);
        return builder.build();
    }

    private File export(EditConfig config, boolean expectFailure) throws Exception {
        File output = new File(directory, "export-" + UUID.randomUUID() + ".mp4");
        Handler main = new Handler(Looper.getMainLooper());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> videoEncoder = new AtomicReference<>();
        List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch completed = new CountDownLatch(1);
        assertTrue(main.post(() -> {
            engine.set(new Media3ExportEngine(targetContext()));
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                @Override public void onProgress(int percent) {}
                @Override public void onCodecs(String video, String audio) { videoEncoder.set(video); }
                @Override public void onDiagnostic(String message) {
                    if (requireSoftwareCompatibility) diagnostics.add(message);
                }
                @Override public void onCompleted(File file) { completed.countDown(); }
                @Override public void onError(Exception failure) {
                    error.set(failure);
                    completed.countDown();
                }
            });
        }));
        try {
            assertTrue("Export timed out", completed.await(180, TimeUnit.SECONDS));
            if (!expectFailure && error.get() != null) throw error.get();
            if (requireSoftwareCompatibility) {
                SoftwareAvcExportTest.assertSoftwareDiagnostics(diagnostics, videoEncoder.get());
            }
            return output;
        } finally {
            CountDownLatch cancelled = new CountDownLatch(1);
            assertTrue(main.post(() -> {
                if (engine.get() != null) engine.get().cancel();
                cancelled.countDown();
            }));
            assertTrue(cancelled.await(10, TimeUnit.SECONDS));
        }
    }

    private Candidate decode(File output) throws Exception {
        assertTrue(output.isFile() && output.length() > 0);
        assertNoWorkFiles();
        return new OracleAndroidDecoder().decode(output);
    }

    private Context assetContext() {
        return getInstrumentation().getContext();
    }

    private Context targetContext() {
        return getInstrumentation().getTargetContext();
    }

    private void assertNoWorkFiles() {
        File[] files = directory.listFiles();
        assertNotNull(files);
        for (File file : files) {
            String name = file.getName();
            assertFalse("Leaked title image " + name, name.startsWith(".intro-title-"));
            assertFalse("Leaked delayed audio " + name, name.startsWith(".intro-audio-delay-"));
            assertFalse("Leaked merge gap audio " + name, name.startsWith(".merge-audio-gap-"));
            assertFalse("Leaked music intermediate " + name, name.startsWith(".music-video-"));
        }
    }

    private static IntroTemplate template() {
        IntroTemplate template = new IntroTemplate();
        template.setDurationMs(1000);
        template.setBackgroundColor(0xFF663399);
        template.setTextColor(0xFFFFFFFF);
        template.setTextSize(36);
        template.setTextX(.5f);
        template.setTextY(.5f);
        return template;
    }

    private static void assertSequenceVisuals(Candidate candidate,
                                              MergeSupplementalFixtureFactory.FixtureSet fixtures) {
        assertEquals(5.3, candidate.video.durationSeconds, .065);
        assertClipColor(candidate, .5, 0x663399, 30);
        MergeSupplementalFixtureFactory.GeneratedClip intro = fixtures.get("intro_audio");
        MergeSupplementalFixtureFactory.GeneratedClip main = fixtures.get("main_audio_rot90");
        MergeSupplementalFixtureFactory.GeneratedClip square = fixtures.get("extra_silent_square");
        MergeSupplementalFixtureFactory.GeneratedClip audio = fixtures.get("extra_audio_rot270");
        MergeSupplementalFixtureFactory.GeneratedClip wide = fixtures.get("extra_silent_wide");
        assertClipColor(candidate, 1.6, intro.spec.baseColor, 30);
        assertClipColor(candidate, 2.35, main.spec.baseColor, 30);
        assertClipColor(candidate, 2.65, main.spec.baseColor, 30);
        assertClipColor(candidate, 3.2, square.spec.baseColor, 30);
        assertClipColor(candidate, 4.2, audio.spec.baseColor, 30);
        assertClipColor(candidate, 5.0, wide.spec.baseColor, 30);
        Rect full = fullRect(candidate.video.width, candidate.video.height);
        Rect landscape = fitRect(candidate.video.width, candidate.video.height,
                intro.spec.displayWidth(), intro.spec.displayHeight());
        Rect squareFit = fitRect(candidate.video.width, candidate.video.height,
                square.spec.displayWidth(), square.spec.displayHeight());
        Rect rotatedLandscape = fitRect(candidate.video.width, candidate.video.height,
                audio.spec.displayWidth(), audio.spec.displayHeight());
        Rect wideFit = fitRect(candidate.video.width, candidate.video.height,
                wide.spec.displayWidth(), wide.spec.displayHeight());
        assertFrameCode(candidate, 1.6, frameIndex(fixtures, intro.spec.durationMs, 600), landscape);
        assertSourceFrameCode(candidate, 2.35, frameIndex(fixtures, main.spec.durationMs, 900),
                full, main.spec, .25);
        assertSourceFrameCode(candidate, 2.65, frameIndex(fixtures, main.spec.durationMs, 1500),
                full, main.spec, .25);
        assertFrameCode(candidate, 3.2, frameIndex(fixtures, square.spec.durationMs, 400), squareFit);
        assertSourceFrameCode(candidate, 4.2, frameIndex(fixtures, audio.spec.durationMs, 400),
                rotatedLandscape, audio.spec, 0);
        assertFrameCode(candidate, 5.0, frameIndex(fixtures, wide.spec.durationMs, 400), wideFit);
        assertTopBottomLetterbox(candidate, 1.6, intro.spec, 35);
        assertCenterAccent(candidate, 1.6, intro.spec.accentColor, 35);
        assertBottomBand(candidate, 2.16, landscape);
        assertColorAt(candidate, 2.35, candidate.video.width / 3, candidate.video.height / 2, main.spec.accentColor, 35);
        assertColorAt(candidate, 2.35, 10, candidate.video.height / 2, main.spec.baseColor, 35);
        // The main is trimmed before its source's final-frame band; check its advancing code instead.
        assertSourceFrameCode(candidate, 2.76, frameIndex(fixtures, main.spec.durationMs, 1720),
                full, main.spec, .25);
        assertLeftRightLetterbox(candidate, 3.2, square.spec, 35);
        assertCenterAccent(candidate, 3.2, square.spec.accentColor, 35);
        assertBottomBand(candidate, 3.76, squareFit);
        assertTopBottomLetterbox(candidate, 4.2, audio.spec, 35);
        assertCenterAccent(candidate, 4.2, audio.spec.accentColor, 35);
        assertSourceColor(candidate, 4.2, .25, .5, rotatedLandscape, audio.spec, 0, audio.spec.baseColor, 35);
        assertSourceColor(candidate, 4.56, .5, .875, rotatedLandscape, audio.spec, 0, 0x202020, 45);
        assertTopBottomLetterbox(candidate, 5.0, wide.spec, 35);
        assertCenterAccent(candidate, 5.0, wide.spec.accentColor, 35);
        assertBottomBand(candidate, 5.24, wideFit);
    }

    private static void assertOrderFails(Candidate candidate, double[] times, int[] rgb) {
        try {
            for (int i = 0; i < times.length; i++) {
                assertClipColor(candidate, times[i], rgb[i], 18);
            }
        } catch (AssertionFailedError expected) {
            return;
        }
        fail("Expected independently specified order mismatch to be detected");
    }

    private static void assertClipColor(Candidate candidate, double time, int rgb, int tolerance) {
        RgbImage image = nearest(candidate, time).image;
        assertColorAt(candidate, time, image.width / 4, image.height / 2, rgb, tolerance);
    }

    private static Frame nearest(Candidate candidate, double time) {
        Frame nearest = null;
        for (Frame frame : candidate.video.frames) {
            if (nearest == null || Math.abs(frame.ptsSeconds - time) < Math.abs(nearest.ptsSeconds - time)) {
                nearest = frame;
            }
        }
        assertNotNull("Missing frame near " + time, nearest);
        assertEquals(time, nearest.ptsSeconds, .08);
        return nearest;
    }

    private static void assertFrameCode(Candidate candidate, double time, int expected, Rect activeRect) {
        RgbImage image = nearest(candidate, time).image;
        int y = Math.min(activeRect.bottom - 1, activeRect.top + Math.max(1, activeRect.height() / 16));
        for (int bit = 0; bit < 8; bit++) {
            int x = Math.min(activeRect.right - 1,
                    activeRect.left + Math.max(1, (bit * 2 + 1) * activeRect.width() / 16));
            int rgb = colorAt(image, x, y);
            int brightness = ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255);
            boolean on = brightness > 3 * 160;
            assertEquals("Unexpected framecode bit " + bit + " at " + time,
                    ((expected >> bit) & 1) != 0, on);
        }
    }

    private static void assertSourceFrameCode(Candidate candidate, double time, int expected,
                                               Rect fit, MergeSupplementalFixtureFactory.ClipSpec spec,
                                               double cropLeft) {
        int actual = 0;
        for (int bit = 0; bit < 8; bit++) {
            int color = sourceColor(candidate, time, (bit + .5) / 8, 1d / 16, fit, spec, cropLeft);
            int brightness = ((color >> 16) & 255) + ((color >> 8) & 255) + (color & 255);
            assertTrue("Framecode must be black or white", brightness < 3 * 45 || brightness > 3 * 210);
            if (brightness > 3 * 210) actual |= 1 << bit;
        }
        // Non-frame-aligned main trim (600 ms at 24 fps) and nearest output sampling
        // each use the containing source frame; allow only one source-frame quantization.
        if (cropLeft > 0) assertTrue("Retimed source frame at " + time + ": " + actual + " vs " + expected,
                Math.abs(actual - expected) <= 1);
        else assertEquals("Imported source frame at " + time, expected, actual);
    }

    private static void assertSourceColor(Candidate candidate, double time, double x, double y,
                                           Rect fit, MergeSupplementalFixtureFactory.ClipSpec spec,
                                           double cropLeft, int rgb, int tolerance) {
        int actual = sourceColor(candidate, time, x, y, fit, spec, cropLeft);
        for (int shift : new int[]{16, 8, 0}) {
            assertTrue("Rotated source point mismatch at " + time,
                    Math.abs(((actual >> shift) & 255) - ((rgb >> shift) & 255)) <= tolerance);
        }
    }

    private static int sourceColor(Candidate candidate, double time, double x, double y,
                                   Rect fit, MergeSupplementalFixtureFactory.ClipSpec spec, double cropLeft) {
        // Analytic source-pixel mapping, independent of the production effect/canvas helpers.
        double displayX = x, displayY = y;
        if (spec.rotationDegrees == 90) {
            displayX = 1 - y;
            displayY = x;
        } else if (spec.rotationDegrees == 270) {
            displayX = y;
            displayY = 1 - x;
        }
        assertTrue(displayX >= cropLeft);
        int px = fit.left + (int) ((displayX - cropLeft) / (1 - cropLeft) * fit.width());
        int py = fit.top + (int) (displayY * fit.height());
        return colorAt(nearest(candidate, time).image, px, py);
    }

    private static void assertTopBottomLetterbox(Candidate candidate, double time,
                                                 MergeSupplementalFixtureFactory.ClipSpec spec, int tolerance) {
        assertColorAt(candidate, time, candidate.video.width / 2, 5, 0x000000, tolerance);
        assertColorAt(candidate, time, candidate.video.width / 2, candidate.video.height - 6, 0x000000, tolerance);
        assertColorAt(candidate, time, candidate.video.width / 4, candidate.video.height / 2, spec.baseColor, tolerance);
    }

    private static void assertLeftRightLetterbox(Candidate candidate, double time,
                                                 MergeSupplementalFixtureFactory.ClipSpec spec, int tolerance) {
        assertColorAt(candidate, time, 5, candidate.video.height / 2, 0x000000, tolerance);
        assertColorAt(candidate, time, candidate.video.width - 6, candidate.video.height / 2, 0x000000, tolerance);
        assertColorAt(candidate, time, candidate.video.width / 2, candidate.video.height * 5 / 8, spec.baseColor, tolerance);
    }

    private static void assertCenterAccent(Candidate candidate, double time, int rgb, int tolerance) {
        assertColorAt(candidate, time, candidate.video.width / 2, candidate.video.height / 2, rgb, tolerance);
    }

    private static void assertBottomBand(Candidate candidate, double time, Rect activeRect) {
        assertColorAt(candidate, time, (activeRect.left + activeRect.right) / 2,
                Math.min(activeRect.bottom - 1, activeRect.top + activeRect.height() * 7 / 8), 0x202020, 45);
    }

    private static void assertColorAt(Candidate candidate, double time, int x, int y, int rgb, int tolerance) {
        int actual = colorAt(nearest(candidate, time).image, x, y);
        int[] expected = {(rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255};
        int[] observed = {(actual >> 16) & 255, (actual >> 8) & 255, actual & 255};
        for (int i = 0; i < 3; i++) {
            assertTrue(String.format(Locale.ROOT,
                            "Color mismatch at %.2fs (%d,%d): %06X vs %06X", time, x, y, actual, rgb),
                    Math.abs(observed[i] - expected[i]) <= tolerance);
        }
    }

    private static int colorAt(RgbImage image, int x, int y) {
        int clampedX = Math.max(0, Math.min(image.width - 1, x));
        int clampedY = Math.max(0, Math.min(image.height - 1, y));
        int offset = (clampedY * image.width + clampedX) * 3;
        return ((image.rgb[offset] & 255) << 16)
                | ((image.rgb[offset + 1] & 255) << 8)
                | (image.rgb[offset + 2] & 255);
    }

    private static Rect fitRect(int canvasWidth, int canvasHeight, int contentWidth, int contentHeight) {
        float scale = Math.min(canvasWidth / (float) contentWidth, canvasHeight / (float) contentHeight);
        int width = Math.max(1, Math.round(contentWidth * scale));
        int height = Math.max(1, Math.round(contentHeight * scale));
        int left = (canvasWidth - width) / 2;
        int top = (canvasHeight - height) / 2;
        return new Rect(left, top, left + width, top + height);
    }

    private static Rect fullRect(int width, int height) {
        return new Rect(0, 0, width, height);
    }

    private static int frameIndex(MergeSupplementalFixtureFactory.FixtureSet fixtures, int durationMs, int clipTimeMs) {
        int frameCount = Math.max(1, Math.round(durationMs * fixtures.fps / 1000f));
        return Math.min(frameCount - 1, Math.max(0, Math.round(clipTimeMs * fixtures.fps / 1000f)));
    }

    private static void assertSilent(AudioTrack audio, double time) {
        float[] samples = window(audio, time);
        double rms = 0;
        for (float sample : samples) rms += sample * sample;
        assertTrue("Expected silence at " + time, Math.sqrt(rms / samples.length) < .01);
    }

    private static void assertTone(AudioTrack audio, double time, double hz) {
        float[] samples = window(audio, time);
        double rms = 0;
        for (float sample : samples) rms += sample * sample;
        assertTrue("Unexpectedly quiet tone at " + time, Math.sqrt(rms / samples.length) > .05);
        assertEquals(hz, OracleCoreVerifier.dominantFrequency(samples, audio.sampleRate), 18d);
    }

    private static float[] window(AudioTrack audio, double center) {
        int start = (int) Math.round((center - .06 - audio.startSeconds) * audio.sampleRate);
        int end = start + (int) Math.round(.12 * audio.sampleRate);
        assertTrue("Missing PCM window at " + center, start >= 0 && end <= audio.samples.length);
        return Arrays.copyOfRange(audio.samples, start, end);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            if (directory != null) {
                File[] files = directory.listFiles();
                File evidence = new File(targetContext().getFilesDir(), "merge-validation/" + getName());
                assertTrue(evidence.isDirectory() || evidence.mkdirs());
                if (files != null) for (File file : files) {
                    try (FileInputStream input = new FileInputStream(file);
                         FileOutputStream output = new FileOutputStream(new File(evidence, file.getName()))) {
                        byte[] buffer = new byte[65536];
                        int count;
                        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                    }
                }
                if (files != null) for (File file : files) assertTrue("Cannot delete " + file, file.delete());
                assertTrue(directory.delete());
            }
        } finally {
            super.tearDown();
        }
    }
}
