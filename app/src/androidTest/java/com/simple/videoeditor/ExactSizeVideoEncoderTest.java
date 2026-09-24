package com.simple.videoeditor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCodecInfo.EncoderCapabilities;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Build;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.util.Log;
import android.view.Surface;

import androidx.media3.common.Format;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.transformer.AppEncoderQualityPolicy;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.ExportException;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.FutureTask;

@UnstableApi
public final class ExactSizeVideoEncoderTest extends InstrumentationTestCase {
    private static final String TAG = "ExactSizeEncoderTest";
    private static final int FRAME_COUNT = 13;
    private static final long TIMEOUT_MS = 30_000;
    private static final ExactSizeVideoEncoder.Diagnostics QUIET = message -> {};

    public void testSoftwareOnlyNativeCandidatesAreExactAndDeterministic() {
        Format requested = request(320, 240, 24);
        MediaCodecInfo[] inventory = nativeCodecs();
        List<ExactSizeVideoEncoder.Attempt> attempts =
                ExactSizeVideoEncoder.candidates(requested, inventory, true, QUIET);
        assertFalse("Device must advertise native software surface AVC at 320x240@24",
                attempts.isEmpty());
        List<ExactSizeVideoEncoder.Attempt> expected = new ArrayList<>();
        for (ExactSizeVideoEncoder.Attempt attempt :
                ExactSizeVideoEncoder.candidates(requested, inventory, false, QUIET)) {
            if (attempt.software) expected.add(attempt);
        }
        assertEquals(signatures(expected), signatures(attempts));
        List<MediaCodecInfo> reordered = new ArrayList<>(Arrays.asList(inventory));
        Collections.reverse(reordered);
        assertEquals(signatures(attempts), signatures(ExactSizeVideoEncoder.candidates(
                requested, reordered.toArray(new MediaCodecInfo[0]), true, QUIET)));
        for (ExactSizeVideoEncoder.Attempt attempt : attempts) {
            MediaCodecInfo info = findCodec(inventory, attempt.name);
            assertTrue(info.isEncoder());
            assertTrue("Hardware escaped software-only selection", attempt.software);
            assertTrue(ExactSizeVideoEncoder.isSoftware(info));
            assertEquals(320, attempt.format.width);
            assertEquals(240, attempt.format.height);
            assertEquals(24f, attempt.format.frameRate, 0f);
            assertEquals(320, attempt.mediaFormat.getInteger(MediaFormat.KEY_WIDTH));
            assertEquals(240, attempt.mediaFormat.getInteger(MediaFormat.KEY_HEIGHT));
            assertEquals(24f, attempt.mediaFormat.getFloat(MediaFormat.KEY_FRAME_RATE), 0f);
            assertEquals(CodecCapabilities.COLOR_FormatSurface,
                    attempt.mediaFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT));
            CodecCapabilities caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H264);
            assertTrue(caps.getVideoCapabilities().areSizeAndRateSupported(320, 240, 24));
            assertTrue("Candidate exceeds native capabilities", caps.isFormatSupported(attempt.mediaFormat));
        }
    }

    public void testExplicitDefaultCandidatesMatchOriginalOverload() {
        Format requested = request(320, 240, 24);
        MediaCodecInfo[] inventory = nativeCodecs();
        assertEquals(signatures(ExactSizeVideoEncoder.candidates(requested, inventory, QUIET)),
                signatures(ExactSizeVideoEncoder.candidates(requested, inventory, false, QUIET)));
    }

    public void testSoftwareOnlyHardwareInventoryAndAbsentInventoryFailWithoutFallback()
            throws Exception {
        List<MediaCodecInfo> hardware = new ArrayList<>();
        for (MediaCodecInfo info : nativeCodecs()) {
            if (info.isEncoder() && !ExactSizeVideoEncoder.isSoftware(info)) hardware.add(info);
        }
        for (MediaCodecInfo[] inventory : new MediaCodecInfo[][]{
                hardware.toArray(new MediaCodecInfo[0]), new MediaCodecInfo[0]}) {
            List<ExactSizeVideoEncoder.Attempt> attempts = ExactSizeVideoEncoder.candidates(
                    request(320, 240, 24), inventory, true, QUIET);
            assertTrue("Software-only mode must not substitute hardware", attempts.isEmpty());
            assertSoftwareOnlyUnavailable(attempts);
        }
        // This also exercises a nonempty hardware inventory on software-only emulators.
        assertSoftwareOnlyUnavailable(syntheticAttempts().subList(0, 2));
    }

    public void testSoftwareOnlyUnsupportedSizeAndRateNeverGenerateResizedAlternatives()
            throws Exception {
        for (Format requested : Arrays.asList(
                request(1_000_000, 1_000_000, 24), request(320, 240, 1_000_000))) {
            List<ExactSizeVideoEncoder.Attempt> attempts = ExactSizeVideoEncoder.candidates(
                    requested, nativeCodecs(), true, QUIET);
            assertTrue("Unsupported exact size/rate must fail, not resize or lower rate",
                    attempts.isEmpty());
            assertSoftwareOnlyUnavailable(attempts);
            try {
                Codec unexpected = ExactSizeVideoEncoder.create(
                        getInstrumentation().getTargetContext(), requested, true, QUIET);
                unexpected.release();
                fail("Native create accepted an unsupported exact size/rate");
            } catch (ExportException expected) {
                assertEquals(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                        expected.errorCode);
                assertTrue(expected.getCause().getMessage(),
                        expected.getCause().getMessage().contains("software"));
            }
        }
    }

    public void testSoftwareOnlyConfigureFiltersInjectedHardwareBeforeNativeCreation()
            throws Exception {
        List<ExactSizeVideoEncoder.Attempt> attempts = syntheticAttempts();
        List<ExactSizeVideoEncoder.Attempt> visited = new ArrayList<>();
        List<String> logs = new ArrayList<>();
        StubCodec software = new StubCodec(attempts.get(2).format);
        Codec result = ExactSizeVideoEncoder.configure(attempts, attempt -> {
            assertTrue("Hardware creator must never be called", attempt.software);
            visited.add(attempt);
            return software;
        }, true, logs::add);
        try {
            assertSame(software, result);
            assertEquals(Collections.singletonList(attempts.get(2)), visited);
            String configured = logs.get(logs.size() - 1);
            assertTrue(configured, configured.startsWith("configured attempt="));
            assertTrue(configured, configured.contains("software=true"));
        } finally {
            result.release();
        }
        assertEquals(1, software.releases);
    }

    public void testSoftwareOnlyNativeCandidateRejectionsNeverFallBackToHardware()
            throws Exception {
        List<ExactSizeVideoEncoder.Attempt> software = ExactSizeVideoEncoder.candidates(
                request(320, 240, 24), nativeCodecs(), true, QUIET);
        assertFalse("No native software AVC capability to exercise", software.isEmpty());
        for (int code : new int[]{ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED}) {
            List<ExactSizeVideoEncoder.Attempt> injected = new ArrayList<>();
            injected.add(syntheticAttempts().get(0));
            injected.addAll(software);
            injected.add(syntheticAttempts().get(1));
            List<ExportException> rejected = new ArrayList<>();
            List<ExactSizeVideoEncoder.Attempt> visited = new ArrayList<>();
            try {
                // Native inventory, injected configure failures: not a claim of an OEM fix.
                ExactSizeVideoEncoder.configure(injected, attempt -> {
                    assertTrue("Native rejection must never enable hardware fallback", attempt.software);
                    visited.add(attempt);
                    ExportException error = rejection(code, attempt.name);
                    rejected.add(error);
                    throw error;
                }, true, QUIET);
                fail("Rejected native software candidates must fail closed");
            } catch (ExportException expected) {
                assertEquals(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, expected.errorCode);
                assertEquals(software, visited);
                assertEquals(rejected.size(), expected.getSuppressed().length);
                for (int i = 0; i < rejected.size(); i++) {
                    assertSame(rejected.get(i), expected.getSuppressed()[i]);
                }
                assertTrue(expected.getCause().getMessage(),
                        expected.getCause().getMessage().contains("software"));
            }
        }
    }

    public void testSoftwareOnlyGuardReleasesChangedWidthHeightAndFrameRate() throws Exception {
        ExactSizeVideoEncoder.Attempt software = syntheticAttempts().get(2);
        for (Format mismatch : Arrays.asList(request(176, 120, 24), request(160, 128, 24),
                request(160, 120, 30), request(160, 120, Format.NO_VALUE))) {
            StubCodec codec = new StubCodec(mismatch);
            int[] calls = {0};
            try {
                ExactSizeVideoEncoder.configure(Arrays.asList(software, software), attempt -> {
                    calls[0]++;
                    return codec;
                }, true, QUIET);
                fail("Changed size/rate must not be accepted or retried");
            } catch (IllegalStateException expected) {
                assertNotNull(expected.getMessage());
                assertEquals(1, calls[0]);
                assertEquals(1, codec.releases);
            } finally {
                if (codec.releases == 0) codec.release();
            }
        }
    }

    public void testDefaultGuardAlsoReleasesChangedFrameRate() throws Exception {
        StubCodec codec = new StubCodec(request(160, 120, 30));
        int[] calls = {0};
        try {
            ExactSizeVideoEncoder.configure(syntheticAttempts(), attempt -> {
                calls[0]++;
                return codec;
            }, QUIET);
            fail("Default overload must preserve requested frame rate too");
        } catch (IllegalStateException expected) {
            assertEquals(1, calls[0]);
            assertEquals(1, codec.releases);
        } finally {
            if (codec.releases == 0) codec.release();
        }
    }

    public void testSoftwareOnlyCreateUsesNativeSoftwareSurfaceAtExactSizeAndRate()
            throws Exception {
        List<String> logs = new ArrayList<>();
        Codec codec = ExactSizeVideoEncoder.create(getInstrumentation().getTargetContext(),
                request(320, 240, 24), true, logs::add);
        try {
            assertTrue("Actual native backend must be software",
                    ExactSizeVideoEncoder.isSoftware(findCodec(nativeCodecs(), codec.getName())));
            assertEquals(320, codec.getConfigurationFormat().width);
            assertEquals(240, codec.getConfigurationFormat().height);
            assertEquals(24f, codec.getConfigurationFormat().frameRate, 0f);
            assertTrue(codec.getInputSurface().isValid());
            String configured = logs.get(logs.size() - 1);
            assertTrue(configured, configured.contains("configured attempt="));
            assertTrue(configured, configured.contains("software=true"));
            assertTrue(configured, configured.contains("nativeName=" + codec.getName()));
        } finally {
            codec.release();
        }
    }

    public void testSoftwareOnlyRejectsPqAndHlgInsteadOfSilentlyConvertingToSdr()
            throws Exception {
        for (int transfer : new int[]{C.COLOR_TRANSFER_ST2084, C.COLOR_TRANSFER_HLG}) {
            Format hdr = request(320, 240, 24).buildUpon().setColorInfo(new ColorInfo.Builder()
                    .setColorSpace(C.COLOR_SPACE_BT2020).setColorRange(C.COLOR_RANGE_LIMITED)
                    .setColorTransfer(transfer).build()).build();
            try {
                ExactSizeVideoEncoder.candidates(hdr, nativeCodecs(), true, QUIET);
                fail("HDR request must be explicitly rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("HDR"));
            }
            try {
                Codec unexpected = ExactSizeVideoEncoder.create(
                        getInstrumentation().getTargetContext(), hdr, true, QUIET);
                unexpected.release();
                fail("HDR native create must not silently produce SDR");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage(), expected.getMessage().contains("HDR"));
            }
        }
    }

    private static void assertSoftwareOnlyUnavailable(List<ExactSizeVideoEncoder.Attempt> attempts)
            throws Exception {
        try {
            ExactSizeVideoEncoder.configure(attempts, attempt -> {
                throw new AssertionError("No permitted software candidate; creator must not run");
            }, true, QUIET);
            fail("Software-only mode must fail clearly without hardware fallback");
        } catch (ExportException expected) {
            assertEquals(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, expected.errorCode);
            assertEquals(0, expected.getSuppressed().length);
            assertNotNull(expected.getCause());
            assertTrue(expected.getCause().getMessage(),
                    expected.getCause().getMessage().contains("software"));
        }
    }

    public void testProfilePolicyBySdkAndDevice() {
        int high = CodecProfileLevel.AVCProfileHigh;
        int main = CodecProfileLevel.AVCProfileMain;
        int baseline = CodecProfileLevel.AVCProfileBaseline;
        // Parameterized inputs, not mutations of Build/SDK or native codec capabilities.
        for (int sdk : new int[]{23, 24, 25, 26, 27, 28, 29, 35}) {
            for (String device : new String[]{"generic", "ASUS_X00T_3", "TC77"}) {
                boolean baselineOnly = sdk == 24 || sdk == 25
                        || (sdk == 27 && !"generic".equals(device));
                int[] expected = sdk == 23 ? new int[0]
                        : baselineOnly ? new int[]{baseline}
                        : sdk < 29 ? new int[]{high, baseline} : new int[]{high, main, baseline};
                assertTrue("sdk=" + sdk + " device=" + device,
                        Arrays.equals(expected, ExactSizeVideoEncoder.profilesFor(sdk, device)));
                for (int mode : new int[]{EncoderCapabilities.BITRATE_MODE_VBR,
                        EncoderCapabilities.BITRATE_MODE_CBR}) {
                    for (int profile : new int[]{high, main, baseline, Format.NO_VALUE}) {
                        boolean allowed = sdk == 23 || profile == Format.NO_VALUE;
                        for (int candidate : expected) allowed |= candidate == profile;
                        for (int level : new int[]{CodecProfileLevel.AVCLevel31, Format.NO_VALUE}) {
                            if (!allowed) {
                                try {
                                    ExactSizeVideoEncoder.profileFormat(request(160, 120, 24),
                                            mode, profile, level, sdk, device);
                                    fail("Unsafe explicit/negotiated-level profile: " + sdk + "/" + device);
                                } catch (IllegalArgumentException correct) {
                                    // The fallback cannot bypass the same device policy.
                                }
                                continue;
                            }
                            MediaFormat media = ExactSizeVideoEncoder.profileFormat(
                                    request(160, 120, 24), mode, profile, level, sdk, device);
                            int effective = sdk == 23 ? Format.NO_VALUE
                                    : profile == Format.NO_VALUE && sdk < 29 ? baseline : profile;
                            assertEquals(effective != Format.NO_VALUE,
                                    media.containsKey(MediaFormat.KEY_PROFILE));
                            assertEquals(effective != Format.NO_VALUE && level != Format.NO_VALUE,
                                    media.containsKey(MediaFormat.KEY_LEVEL));
                            if (effective != Format.NO_VALUE) {
                                assertEquals(effective, media.getInteger(MediaFormat.KEY_PROFILE));
                            }
                            boolean latency = sdk >= 26 && sdk <= 28 && effective == high;
                            assertEquals(latency, media.containsKey(MediaFormat.KEY_LATENCY));
                            if (latency) assertEquals(1, media.getInteger(MediaFormat.KEY_LATENCY));
                            assertEquals(mode, media.getInteger(MediaFormat.KEY_BITRATE_MODE));
                            assertFalse(media.containsKey(MediaFormat.KEY_OPERATING_RATE));
                            assertFalse(media.containsKey(MediaFormat.KEY_PRIORITY));
                        }
                    }
                }
            }
        }
    }

    public void testDiagnosticFailureBeforeConfigurePreventsNativeCreation() throws Exception {
        RuntimeException saving = new RuntimeException("SAF checkpoint failed");
        try {
            ExactSizeVideoEncoder.configure(syntheticAttempts(), attempt -> {
                throw new AssertionError("Native configure must wait for a durable checkpoint");
            }, message -> { throw saving; });
            fail("Checkpoint failure must propagate");
        } catch (RuntimeException actual) {
            assertSame(saving, actual);
        }
    }

    public void testDiagnosticFailureAfterConfigureReleasesCodec() throws Exception {
        StubCodec codec = new StubCodec(syntheticAttempts().get(0).format);
        RuntimeException saving = new RuntimeException("SAF configured checkpoint failed");
        try {
            ExactSizeVideoEncoder.configure(syntheticAttempts(), attempt -> codec, message -> {
                if (message.startsWith("configured attempt=")) throw saving;
            });
            fail("Checkpoint failure must propagate");
        } catch (RuntimeException actual) {
            assertSame(saving, actual);
            assertEquals(1, codec.releases);
        }
    }

    public void testCandidates160x120At24AreDeterministicAndExact() {
        assertCandidates(160, 120, 24);
    }

    public void testCandidates120x80At48AreDeterministicAndExact() {
        assertCandidates(120, 80, 48);
    }

    private void assertCandidates(int width, int height, int rate) {
        Format requested = request(width, height, rate).buildUpon()
                .setCodecs("avc1.640033")
                .setInitializationData(Arrays.asList(new byte[]{1, 2}, new byte[]{3, 4}))
                .build();
        MediaCodecInfo[] infos = nativeCodecs();
        List<ExactSizeVideoEncoder.Attempt> expected =
                ExactSizeVideoEncoder.candidates(requested, infos, QUIET);
        assertFalse("No advertised exact-size surface AVC encoder for " + requested,
                expected.isEmpty());
        List<MediaCodecInfo> reordered = new ArrayList<>(Arrays.asList(infos));
        Collections.reverse(reordered);
        assertEquals(signatures(expected), signatures(ExactSizeVideoEncoder.candidates(
                requested, reordered.toArray(new MediaCodecInfo[0]), QUIET)));
        for (int seed = 0; seed < 4; seed++) {
            Collections.shuffle(reordered, new Random(seed));
            assertEquals(signatures(expected), signatures(ExactSizeVideoEncoder.candidates(
                    requested, reordered.toArray(new MediaCodecInfo[0]), QUIET)));
        }

        boolean sawSoftware = false;
        ExactSizeVideoEncoder.Attempt previous = null;
        for (ExactSizeVideoEncoder.Attempt attempt : expected) {
            MediaCodecInfo info = findCodec(infos, attempt.name);
            assertTrue(info.isEncoder());
            assertEquals(ExactSizeVideoEncoder.isSoftware(info), attempt.software);
            if (sawSoftware) assertTrue("Hardware after software", attempt.software);
            sawSoftware |= attempt.software;
            if (previous != null && previous.software == attempt.software) {
                assertTrue(previous.name.compareTo(attempt.name) <= 0);
            }
            previous = attempt;
            assertEquals(width, attempt.format.width);
            assertEquals(height, attempt.format.height);
            assertEquals((float) rate, attempt.format.frameRate, 0f);
            assertNull(attempt.format.codecs);
            assertTrue(attempt.format.initializationData.isEmpty());

            MediaFormat media = attempt.mediaFormat;
            assertEquals(MimeTypes.VIDEO_H264, media.getString(MediaFormat.KEY_MIME));
            assertEquals(width, media.getInteger(MediaFormat.KEY_WIDTH));
            assertEquals(height, media.getInteger(MediaFormat.KEY_HEIGHT));
            assertEquals((float) rate, media.getFloat(MediaFormat.KEY_FRAME_RATE), 0f);
            assertEquals(CodecCapabilities.COLOR_FormatSurface,
                    media.getInteger(MediaFormat.KEY_COLOR_FORMAT));
            for (String key : new String[]{MediaFormat.KEY_OPERATING_RATE,
                    MediaFormat.KEY_PRIORITY, "codecs", "codecs-string",
                    "csd-0", "csd-1", "csd-2"}) {
                assertFalse("Leaked input/optional key " + key + ": " + media,
                        media.containsKey(key));
            }
            CodecCapabilities caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H264);
            assertTrue(caps.getVideoCapabilities().areSizeAndRateSupported(width, height, rate));
            assertTrue("Unadvertised format: " + attempt.describe(), caps.isFormatSupported(media));
            int mode = media.getInteger(MediaFormat.KEY_BITRATE_MODE);
            assertTrue(mode == EncoderCapabilities.BITRATE_MODE_VBR
                    || mode == EncoderCapabilities.BITRATE_MODE_CBR);
            assertTrue(caps.getEncoderCapabilities().isBitrateModeSupported(mode));
            int bitrate = caps.getVideoCapabilities().getBitrateRange().clamp(
                    AppEncoderQualityPolicy.bitrate(attempt.name, width, height, rate));
            assertEquals(bitrate, attempt.format.averageBitrate);
            assertEquals(bitrate, media.getInteger(MediaFormat.KEY_BIT_RATE));
            assertProfileLevel(caps, attempt);
        }
        assertEquals("avc1.640033", requested.codecs);
        assertEquals(2, requested.initializationData.size());
    }

    private static void assertProfileLevel(CodecCapabilities caps,
                                          ExactSizeVideoEncoder.Attempt attempt) {
        MediaFormat media = attempt.mediaFormat;
        if (!media.containsKey(MediaFormat.KEY_PROFILE)) {
            assertEquals("encoder-profile-level", attempt.policy);
            assertTrue(Build.VERSION.SDK_INT < 24 || Build.VERSION.SDK_INT >= 29);
            assertFalse(media.containsKey(MediaFormat.KEY_LEVEL));
            return;
        }
        int profile = media.getInteger(MediaFormat.KEY_PROFILE);
        boolean allowed = false;
        for (int candidate : ExactSizeVideoEncoder.profilesFor(Build.VERSION.SDK_INT, Build.DEVICE)) {
            allowed |= candidate == profile;
        }
        assertTrue("Unsafe device profile", allowed);
        boolean latency = Build.VERSION.SDK_INT >= 26 && Build.VERSION.SDK_INT <= 28
                && profile == CodecProfileLevel.AVCProfileHigh;
        assertEquals(latency, media.containsKey(MediaFormat.KEY_LATENCY));
        if (latency) assertEquals(1, media.getInteger(MediaFormat.KEY_LATENCY));
        if (!media.containsKey(MediaFormat.KEY_LEVEL)) {
            assertEquals("baseline-encoder-level", attempt.policy);
            assertEquals(CodecProfileLevel.AVCProfileBaseline, profile);
            assertTrue(Build.VERSION.SDK_INT >= 24 && Build.VERSION.SDK_INT < 29);
            return;
        }
        int level = media.getInteger(MediaFormat.KEY_LEVEL);
        assertTrue(profile == CodecProfileLevel.AVCProfileHigh
                || profile == CodecProfileLevel.AVCProfileMain
                || profile == CodecProfileLevel.AVCProfileBaseline);
        boolean advertised = false;
        for (CodecProfileLevel pair : caps.profileLevels) {
            advertised |= pair.profile == profile && pair.level >= level;
        }
        assertTrue("Profile/level exceeds advertised capabilities", advertised);
        CodecCapabilities limits = CodecCapabilities.createFromProfileLevel(
                MimeTypes.VIDEO_H264, profile, level);
        assertNotNull(limits);
        assertTrue(limits.getVideoCapabilities().areSizeAndRateSupported(
                attempt.format.width, attempt.format.height, attempt.format.frameRate));
        assertTrue(limits.getVideoCapabilities().getBitrateRange()
                .contains(attempt.format.averageBitrate));
        assertEquals(level, ExactSizeVideoEncoder.lowestLevel(caps, profile, attempt.format));
    }

    public void testAbsurdUnsupportedDimensionsAreRejectedRatherThanResized() throws Exception {
        Format requested = request(1_000_000, 1_000_000, 48);
        List<ExactSizeVideoEncoder.Attempt> attempts =
                ExactSizeVideoEncoder.candidates(requested, nativeCodecs(), QUIET);
        assertTrue("Unsupported geometry must not generate resized alternatives", attempts.isEmpty());
        assertNoCandidates(attempts);
        assertEquals(1_000_000, requested.width);
        assertEquals(1_000_000, requested.height);
    }

    public void testFormatUnsupportedTakesNextAttempt() throws Exception {
        assertRetry(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED);
    }

    public void testEncoderInitFailedTakesNextAttempt() throws Exception {
        assertRetry(ExportException.ERROR_CODE_ENCODER_INIT_FAILED);
    }

    private void assertRetry(int code) throws Exception {
        List<ExactSizeVideoEncoder.Attempt> attempts = syntheticAttempts();
        List<String> logs = new ArrayList<>();
        List<ExactSizeVideoEncoder.Attempt> visited = new ArrayList<>();
        StubCodec success = new StubCodec(attempts.get(1).format);
        ExportException rejection = rejection(code, "first");
        Codec result = ExactSizeVideoEncoder.configure(attempts, attempt -> {
            assertBeforeConfigure(logs, attempt, visited.size() + 1, attempts.size());
            visited.add(attempt);
            if (attempt == attempts.get(0)) throw rejection;
            assertSame(attempts.get(1), attempt);
            return success;
        }, logs::add);
        try {
            assertSame(success, result);
            assertEquals(attempts.subList(0, 2), visited);
            assertEquals(0, success.releases);
            assertTrue(logs.toString().contains("configure-rejected"));
            assertTrue(logs.toString().contains("code=" + code));
            assertTrue(logs.get(logs.size() - 1).contains("configured attempt=2"));
            for (String message : logs) {
                if (!message.startsWith("configure-rejected")
                        && !message.startsWith("configured attempt=")) continue;
                assertNonnegativeTiming(message, "configureMs=");
                assertNonnegativeTiming(message, "selectionTotalMs=");
            }
        } finally {
            result.release();
        }
    }

    private static void assertNonnegativeTiming(String message, String field) {
        int start = message.indexOf(field);
        assertTrue("Missing monotonic timing: " + message, start >= 0);
        start += field.length();
        int end = message.indexOf(' ', start);
        assertTrue(Long.parseLong(message.substring(start, end == -1 ? message.length() : end)) >= 0);
    }

    public void testSoftwareFallbackAfterBothHardwarePoliciesReject() throws Exception {
        // Synthetic success verifies sequencing only; it is not evidence of an OEM/Samsung fix.
        List<ExactSizeVideoEncoder.Attempt> attempts = syntheticAttempts();
        List<String> logs = new ArrayList<>();
        List<ExactSizeVideoEncoder.Attempt> visited = new ArrayList<>();
        StubCodec software = new StubCodec(attempts.get(2).format);
        Codec result = ExactSizeVideoEncoder.configure(attempts, attempt -> {
            assertBeforeConfigure(logs, attempt, visited.size() + 1, attempts.size());
            visited.add(attempt);
            if (!attempt.software) {
                throw rejection(visited.size() == 1
                        ? ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED
                        : ExportException.ERROR_CODE_ENCODER_INIT_FAILED, attempt.policy);
            }
            return software;
        }, logs::add);
        try {
            assertSame(software, result);
            assertEquals(attempts, visited);
            assertTrue(logs.get(logs.size() - 1).contains("configured attempt=3"));
            assertTrue(logs.get(logs.size() - 1).contains("software=true"));
        } finally {
            result.release();
        }
    }

    public void testAllExpectedFailuresAreAggregatedInAttemptOrder() throws Exception {
        List<ExactSizeVideoEncoder.Attempt> attempts = syntheticAttempts();
        List<String> logs = new ArrayList<>();
        List<ExportException> failures = new ArrayList<>();
        try {
            ExactSizeVideoEncoder.configure(attempts, attempt -> {
                assertBeforeConfigure(logs, attempt, failures.size() + 1, attempts.size());
                ExportException failure = rejection(failures.size() % 2 == 0
                        ? ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED
                        : ExportException.ERROR_CODE_ENCODER_INIT_FAILED, attempt.name);
                failures.add(failure);
                throw failure;
            }, logs::add);
            fail("All rejected attempts must fail");
        } catch (ExportException aggregate) {
            assertEquals(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, aggregate.errorCode);
            assertEquals(attempts.size(), failures.size());
            assertEquals(failures.size(), aggregate.getSuppressed().length);
            for (int i = 0; i < failures.size(); i++) {
                assertSame(failures.get(i), aggregate.getSuppressed()[i]);
            }
            assertNotNull(aggregate.getCause());
            assertTrue(aggregate.getCause().getMessage().contains("attempts=3"));
        }
    }

    public void testFatalRuntimeFailureIsNotRetried() throws Exception {
        List<ExactSizeVideoEncoder.Attempt> attempts = syntheticAttempts();
        IllegalStateException fatal = new IllegalStateException("unexpected creator bug");
        List<String> logs = new ArrayList<>();
        int[] calls = {0};
        try {
            ExactSizeVideoEncoder.configure(attempts, attempt -> {
                assertBeforeConfigure(logs, attempt, ++calls[0], attempts.size());
                throw fatal;
            }, logs::add);
            fail("Runtime failure must propagate");
        } catch (IllegalStateException actual) {
            assertSame(fatal, actual);
            assertEquals(1, calls[0]);
        }
    }

    public void testNonRetryableExportFailurePreservesEarlierRejection() throws Exception {
        List<ExactSizeVideoEncoder.Attempt> attempts = syntheticAttempts();
        ExportException first = rejection(ExportException.ERROR_CODE_ENCODER_INIT_FAILED, "first");
        ExportException fatal = rejection(ExportException.ERROR_CODE_ENCODING_FAILED, "fatal");
        int[] calls = {0};
        try {
            ExactSizeVideoEncoder.configure(attempts, attempt -> {
                if (++calls[0] == 1) throw first;
                throw fatal;
            }, QUIET);
            fail("Non-configuration failure must propagate");
        } catch (ExportException actual) {
            assertSame(fatal, actual);
            assertEquals(2, calls[0]);
            assertEquals(1, actual.getSuppressed().length);
            assertSame(first, actual.getSuppressed()[0]);
        }
    }

    public void testEmptyCandidatesNeverInvokeCreator() throws Exception {
        assertNoCandidates(Collections.emptyList());
    }

    private static void assertNoCandidates(List<ExactSizeVideoEncoder.Attempt> attempts)
            throws Exception {
        try {
            ExactSizeVideoEncoder.configure(attempts, attempt -> {
                throw new AssertionError("Creator called without a candidate");
            }, QUIET);
            fail("Empty candidates must fail");
        } catch (ExportException error) {
            assertEquals(ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED, error.errorCode);
            assertEquals(0, error.getSuppressed().length);
            assertNotNull(error.getCause());
            assertTrue(error.getCause().getMessage().contains("attempts=0"));
        }
    }

    public void testExactSizeGuardReleasesWidthAndHeightMismatches() throws Exception {
        for (Format mismatch : Arrays.asList(request(176, 120, 24), request(160, 128, 24))) {
            StubCodec codec = new StubCodec(mismatch);
            int[] calls = {0};
            try {
                ExactSizeVideoEncoder.configure(syntheticAttempts(), attempt -> {
                    calls[0]++;
                    return codec;
                }, QUIET);
                fail("Changed dimensions must be rejected");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains("dimensions"));
                assertEquals(1, calls[0]);
                assertEquals(1, codec.releases);
            } finally {
                if (codec.releases == 0) codec.release();
            }
        }
    }

    public void testNativeSurfaceOutput160x120At24() throws Exception {
        assertNativeOutput(160, 120, 24);
    }

    public void testNativeSurfaceOutput120x80At48() throws Exception {
        assertNativeOutput(120, 80, 48);
    }

    private void assertNativeOutput(int width, int height, int rate) throws Exception {
        File output = new File(getInstrumentation().getTargetContext().getFilesDir(),
                "exact-size-" + UUID.randomUUID() + ".mp4");
        Codec codec = null;
        MediaMuxer muxer = null;
        Thread producer = null;
        FutureTask<Void> drawing = null;
        try {
            codec = ExactSizeVideoEncoder.create(getInstrumentation().getTargetContext(),
                    request(width, height, rate));
            assertEquals(width, codec.getConfigurationFormat().width);
            assertEquals(height, codec.getConfigurationFormat().height);
            Surface input = codec.getInputSurface();
            assertNotNull(input);
            assertTrue(input.isValid());
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            drawing = new FutureTask<>(() -> {
                drawFrames(input, width, height, rate, deadline);
                return null;
            });
            producer = new Thread(drawing, "exact-size-egl");
            producer.setDaemon(true);
            producer.start();
            boolean eosSent = false;
            int track = -1;
            int samples = 0;
            while (!codec.isEnded()) {
                checkDeadline(deadline);
                if (!eosSent && drawing.isDone()) {
                    drawing.get();
                    codec.signalEndOfInputStream();
                    eosSent = true;
                }
                // In DefaultCodec 1.5.1, getOutputFormat() can dequeue without fetching bytes.
                // Fetch the buffer first or that pending buffer can remain permanently unreadable.
                ByteBuffer buffer = codec.getOutputBuffer();
                if (buffer == null) {
                    Thread.sleep(2);
                    continue;
                }
                try {
                    MediaCodec.BufferInfo info = codec.getOutputBufferInfo();
                    assertNotNull(info);
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        if (track == -1) {
                            Format actual = codec.getOutputFormat();
                            assertNotNull(actual);
                            assertEquals(width, actual.width);
                            assertEquals(height, actual.height);
                            assertEquals(MimeTypes.VIDEO_H264, actual.sampleMimeType);
                            track = muxer.addTrack(
                                    MediaFormatUtil.createMediaFormatFromFormat(actual));
                            muxer.start();
                        }
                        muxer.writeSampleData(track, buffer, info);
                        samples++;
                    }
                } finally {
                    codec.releaseOutputBuffer(false);
                }
            }
            assertTrue("Encoder ended before input EOS", eosSent);
            drawing.get();
            assertEquals(FRAME_COUNT, samples);
            assertTrue("No output track", track >= 0);
            muxer.stop();
            muxer.release();
            muxer = null;
            assertExtractedOutput(output, width, height, rate);
            Log.i(TAG, "native encoder=" + codec.getName() + " exact=" + width + "x" + height
                    + " rate=" + rate + " samples=" + samples
                    + " endPtsUs=" + ((FRAME_COUNT - 1L) * 1_000_000 / rate));
        } finally {
            if (drawing != null && !drawing.isDone()) drawing.cancel(true);
            try {
                // Release the owned input Surface/codec before waiting for a blocked EGL producer.
                if (codec != null) codec.release();
            } finally {
                try {
                    if (producer != null) {
                        producer.join(5_000);
                        assertFalse("EGL producer did not terminate", producer.isAlive());
                    }
                } finally {
                    try {
                        if (muxer != null) muxer.release();
                    } finally {
                        assertTrue("Cannot remove test output", !output.exists() || output.delete());
                    }
                }
            }
        }
    }

    private static void assertExtractedOutput(File file, int width, int height, int rate)
            throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            assertEquals(1, extractor.getTrackCount());
            MediaFormat format = extractor.getTrackFormat(0);
            assertEquals(MimeTypes.VIDEO_H264, format.getString(MediaFormat.KEY_MIME));
            assertEquals(width, format.getInteger(MediaFormat.KEY_WIDTH));
            assertEquals(height, format.getInteger(MediaFormat.KEY_HEIGHT));
            extractor.selectTrack(0);
            TreeSet<Long> timestamps = new TreeSet<>();
            ByteBuffer sample = ByteBuffer.allocateDirect(1024 * 1024);
            int count = 0;
            long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
            while (extractor.getSampleTime() >= 0) {
                checkDeadline(deadline);
                sample.clear();
                int size = extractor.readSampleData(sample, 0);
                assertTrue("Empty/truncated muxed sample", size > 0 && size <= sample.capacity());
                assertEquals(0, extractor.getSampleTrackIndex());
                assertTrue("Duplicate PTS", timestamps.add(extractor.getSampleTime()));
                assertTrue("Too many frames", ++count <= FRAME_COUNT);
                if (!extractor.advance()) break;
            }
            assertEquals(FRAME_COUNT, count);
            assertEquals(0L, timestamps.first().longValue());
            // Thirteen frames put the last PTS at exactly 0.5s/0.25s, avoiding mux timebase rounding.
            assertEquals((FRAME_COUNT - 1L) * 1_000_000 / rate,
                    timestamps.last().longValue());
            assertEquals(-1L, extractor.getSampleTime());
        } finally {
            extractor.release();
        }
    }

    private static void drawFrames(Surface input, int width, int height, int rate, long deadline)
            throws Exception {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface surface = EGL14.EGL_NO_SURFACE;
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            assertFalse(EGL14.EGL_NO_DISPLAY.equals(display));
            int[] version = new int[2];
            assertTrue(EGL14.eglInitialize(display, version, 0, version, 1));
            assertTrue(EGL14.eglBindAPI(EGL14.EGL_OPENGL_ES_API));
            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];
            int[] attributes = {
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8, EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    0x3142, 1, // EGL_RECORDABLE_ANDROID
                    EGL14.EGL_NONE
            };
            assertTrue(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0));
            assertTrue("No recordable EGL configuration", count[0] > 0);
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                    new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
            assertFalse(EGL14.EGL_NO_CONTEXT.equals(context));
            surface = EGL14.eglCreateWindowSurface(display, configs[0], input,
                    new int[]{EGL14.EGL_NONE}, 0);
            assertFalse(EGL14.EGL_NO_SURFACE.equals(surface));
            assertTrue(EGL14.eglMakeCurrent(display, surface, surface, context));
            GLES20.glViewport(0, 0, width, height);
            for (int frame = 0; frame < FRAME_COUNT; frame++) {
                checkDeadline(deadline);
                GLES20.glClearColor(frame / (float) FRAME_COUNT, 0.25f, 0.75f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                assertEquals(GLES20.GL_NO_ERROR, GLES20.glGetError());
                assertTrue(EGLExt.eglPresentationTimeANDROID(display, surface,
                        frame * 1_000_000_000L / rate));
                assertTrue(EGL14.eglSwapBuffers(display, surface));
            }
        } finally {
            if (!EGL14.EGL_NO_DISPLAY.equals(display)) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (!EGL14.EGL_NO_SURFACE.equals(surface)) {
                    EGL14.eglDestroySurface(display, surface);
                }
                if (!EGL14.EGL_NO_CONTEXT.equals(context)) {
                    EGL14.eglDestroyContext(display, context);
                }
                EGL14.eglTerminate(display);
            }
            EGL14.eglReleaseThread();
            // The Codec owns the Java Surface, not this EGL producer.
        }
    }

    private static void checkDeadline(long deadline) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        // Bounds polling/drawing; no Java deadline can preempt an unresponsive native driver call.
        assertTrue("Native encoding timed out", SystemClock.elapsedRealtime() < deadline);
    }

    private static Format request(int width, int height, int rate) {
        return new Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(width).setHeight(height).setFrameRate(rate).build();
    }

    private static MediaCodecInfo[] nativeCodecs() {
        return new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
    }

    private static MediaCodecInfo findCodec(MediaCodecInfo[] infos, String name) {
        for (MediaCodecInfo info : infos) {
            if (name.equals(info.getName())) return info;
        }
        throw new AssertionError("Missing native codec " + name);
    }

    private static List<String> signatures(List<ExactSizeVideoEncoder.Attempt> attempts) {
        List<String> signatures = new ArrayList<>();
        for (ExactSizeVideoEncoder.Attempt attempt : attempts) {
            signatures.add(attempt.describe() + " output=" + attempt.format);
        }
        return signatures;
    }

    private static List<ExactSizeVideoEncoder.Attempt> syntheticAttempts() {
        Format format = request(160, 120, 24);
        return Arrays.asList(synthetic("test.hardware.avc", false, "explicit", format),
                synthetic("test.hardware.avc", false, "encoder-profile-level", format),
                synthetic("test.software.avc", true, "encoder-profile-level", format));
    }

    private static ExactSizeVideoEncoder.Attempt synthetic(
            String name, boolean software, String policy, Format format) {
        MediaFormat media = MediaFormat.createVideoFormat(
                MimeTypes.VIDEO_H264, format.width, format.height);
        media.setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate);
        media.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface);
        return new ExactSizeVideoEncoder.Attempt(name, software, policy, format, media);
    }

    private static ExportException rejection(int code, String reason) {
        return ExportException.createForCodec(new IllegalArgumentException(reason), code,
                new ExportException.CodecInfo("injected AVC", true, false, "test.encoder"));
    }

    private static void assertBeforeConfigure(List<String> logs,
            ExactSizeVideoEncoder.Attempt attempt, int index, int count) {
        assertFalse("No diagnostics before creator invocation", logs.isEmpty());
        String before = logs.get(logs.size() - 1);
        assertTrue(before, before.contains("before-configure attempt=" + index + "/" + count));
        assertTrue(before, before.contains("encoder=" + attempt.name));
        assertTrue(before, before.contains("software=" + attempt.software));
        assertTrue(before, before.contains("policy=" + attempt.policy));
        assertTrue("Full MediaFormat must be logged before the failing call",
                before.contains("format=" + attempt.mediaFormat));
    }

    private static final class StubCodec implements Codec {
        private final Format format;
        int releases;

        StubCodec(Format format) {
            this.format = format;
        }

        @Override public Format getConfigurationFormat() { return format; }
        @Override public String getName() { return "injected-test-codec"; }
        @Override public Surface getInputSurface() { throw new UnsupportedOperationException(); }
        @Override public boolean maybeDequeueInputBuffer(DecoderInputBuffer input) {
            throw new UnsupportedOperationException();
        }
        @Override public void queueInputBuffer(DecoderInputBuffer input) {
            throw new UnsupportedOperationException();
        }
        @Override public void signalEndOfInputStream() { throw new UnsupportedOperationException(); }
        @Override public Format getOutputFormat() { return null; }
        @Override public ByteBuffer getOutputBuffer() { return null; }
        @Override public MediaCodec.BufferInfo getOutputBufferInfo() { return null; }
        @Override public void releaseOutputBuffer(boolean render) {
            throw new UnsupportedOperationException();
        }
        @Override public void releaseOutputBuffer(long presentationTimeUs) {
            throw new UnsupportedOperationException();
        }
        @Override public boolean isEnded() { return false; }
        @Override public void release() { releases++; }
    }
}
