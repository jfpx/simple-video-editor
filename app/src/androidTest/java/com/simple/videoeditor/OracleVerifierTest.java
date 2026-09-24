package com.simple.videoeditor;

import android.test.InstrumentationTestCase;
import android.graphics.Rect;
import android.media.AudioFormat;
import android.media.MediaFormat;

import com.simple.videoeditor.oracle.OracleAndroidDecoder;
import com.simple.videoeditor.oracle.OracleContract;
import com.simple.videoeditor.oracle.OracleCoreVerifier;
import com.simple.videoeditor.oracle.OracleGeneratedContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Selftest using only the shipped oracle assets, not exported candidates. */
public final class OracleVerifierTest extends InstrumentationTestCase {
    public void testShippedSourcePassesOnlyIdentity() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getInstrumentation().getTargetContext());
        JSONObject contract = verifier.loadContract();
        JSONArray cases = contract.getJSONArray("cases");
        assertEquals("Shipped contract must contain exactly 12 cases", 12, cases.length());

        Set<String> caseIds = new HashSet<String>();
        int identityCount = 0;
        for (int i = 0; i < cases.length(); i++) {
            String caseId = cases.getJSONObject(i).getString("id");
            assertTrue("Empty case id", caseId.length() > 0);
            assertTrue("Duplicate case: " + caseId, caseIds.add(caseId));
            if ("identity".equals(caseId)) {
                identityCount++;
            }
        }
        assertEquals("Identity must occur exactly once", 1, identityCount);

        JSONObject fixtureContract = contract.getJSONObject("fixture");
        assertEquals("standard.mp4", fixtureContract.getString("path"));
        File fixture = verifier.prepareFixture();
        long expectedBytes = fixtureContract.getLong("bytes");
        String expectedSha256 = fixtureContract.getString("sha256");
        assertFixtureUnchanged(fixture, expectedBytes, expectedSha256, "prepared fixture");

        assertReport(verifier, "identity", fixture, expectedBytes, expectedSha256, true);
        int rejectedCases = 0;
        for (int i = 0; i < cases.length(); i++) {
            String caseId = cases.getJSONObject(i).getString("id");
            if (!"identity".equals(caseId)) {
                assertReport(verifier, caseId, fixture, expectedBytes, expectedSha256, false);
                rejectedCases++;
            }
        }
        assertEquals("Unchanged source must fail every non-identity case", 11, rejectedCases);
    }

    public void testUnknownCaseIsSetupError() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getInstrumentation().getTargetContext());
        try {
            verifier.verify("unknown-case", null);
            fail("Unknown case must throw IOException, not return a mismatch report");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unknown oracle case"));
        }
        try {
            new OracleCoreVerifier().verify(OracleGeneratedContract.create(), "unknown-case",
                    new OracleCoreVerifier.Candidate(null, null, 0, 0, 0, null, null));
            fail("Core must also reject unknown cases as setup errors");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unknown oracle case"));
        }
    }

    public void testCaseSummaryRequiresDecoderEvidence() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getInstrumentation().getTargetContext());
        JSONObject report = verifier.verify("identity", verifier.prepareFixture());
        report.put("export_codecs", "ENCODER not exercised: frozen fixture");
        report.put("reference", new JSONObject());
        Object decoder = report.remove(OracleVerifier.DECODER_KEY);
        report.put("decode", decoder);
        assertSummaryRejectsDecoder(report);
        report.put(OracleVerifier.DECODER_KEY, "not an object");
        assertSummaryRejectsDecoder(report);
        report.put(OracleVerifier.DECODER_KEY, JSONObject.NULL);
        assertSummaryRejectsDecoder(report);
    }

    public void testCaseSummaryPreservesMissingCandidateFailure() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getInstrumentation().getTargetContext());
        JSONObject report = verifier.verify("identity", null);
        assertEquals("FAIL", report.getString("status"));
        report.put("export_codecs", "ENCODER not exercised: missing candidate");
        report.put("reference", new JSONObject());
        String summary = SelfTestRunner.formatCaseResult(report);
        assertTrue(summary, summary.startsWith("FAIL identity\n"));
        assertTrue(summary, summary.contains("FAIL "));
        assertTrue(summary, summary.contains("\nDECODER "
                + report.getJSONObject(OracleVerifier.DECODER_KEY)));
    }

    private void assertSummaryRejectsDecoder(JSONObject report) throws Exception {
        try {
            SelfTestRunner.formatCaseResult(report);
            fail("Missing or malformed decoder evidence must remain a schema error");
        } catch (JSONException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains(OracleVerifier.DECODER_KEY));
        }
    }

    public void testCheckerRuntimeErrorIsNotMismatch() throws Exception {
        OracleContract contract = OracleGeneratedContract.create();
        OracleContract.OracleCase identity = contract.requireCase("identity");
        OracleCoreVerifier.VideoTrack brokenVideo = new OracleCoreVerifier.VideoTrack(
                "h264", "1:1", identity.width, identity.height, 0, identity.durationSeconds,
                Arrays.asList((OracleCoreVerifier.Frame) null, (OracleCoreVerifier.Frame) null));
        OracleCoreVerifier.Report report = new OracleCoreVerifier().verify(contract, "identity",
                new OracleCoreVerifier.Candidate("broken", null, 1,
                        identity.videoTrackCount, identity.audioTrackCount, brokenVideo, null));
        assertFalse(report.passed());
        assertEquals("ERROR", report.toMap().get("status"));
        assertTrue(report.toMap().toString().contains("checker.completed_without_error"));
    }

    public void testDecoderDiagnosticsResetAndSnapshot() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getInstrumentation().getTargetContext());
        OracleContract contract = OracleGeneratedContract.create();
        OracleAndroidDecoder decoder = new OracleAndroidDecoder();
        decoder.decode(verifier.prepareFixture(), contract, contract.requireCase("identity"));
        Map<String, Object> decoded = decoder.getDiagnostics();
        Map<?, ?> video = (Map<?, ?>) decoded.get("video");
        Map<?, ?> audio = (Map<?, ?>) decoded.get("audio");
        assertNotNull(video.get("decoder_name"));
        assertNotNull(audio.get("decoder_name"));
        decoder.decode(null, contract, contract.requireCase("identity"));
        Map<String, Object> reset = decoder.getDiagnostics();
        for (String track : new String[] {"video", "audio"}) {
            Map<?, ?> diagnostics = (Map<?, ?>) reset.get(track);
            assertEquals(Boolean.FALSE, diagnostics.get("present"));
            assertNull(diagnostics.get("decoder_name"));
            assertNull(diagnostics.get("input_format"));
            assertNull(diagnostics.get("output_format"));
        }
        assertNotNull("Previous snapshot was reset", video.get("decoder_name"));
        assertNotNull("Previous snapshot was reset", audio.get("decoder_name"));
    }

    public void testRealFullAndSparseDecodeWithPngExpectations() throws Exception {
        OracleVerifier verifier = new OracleVerifier(getInstrumentation().getTargetContext());
        final OracleContract contract = OracleGeneratedContract.create();
        final OracleContract.OracleCase identity = contract.requireCase("identity");
        final OracleAndroidDecoder decoder = new OracleAndroidDecoder();
        File fixture = verifier.prepareFixture();
        OracleCoreVerifier.Candidate full = decoder.decode(fixture);
        OracleCoreVerifier.Candidate sparse = decoder.decode(fixture, contract, identity);
        assertNotNull(full.video);
        assertNotNull(full.audio);
        assertEquals("h264", full.video.codec);
        assertEquals("aac", full.audio.codec);
        assertEquals(48000, full.audio.sampleRate);
        assertEquals(1, full.audio.channels);
        assertEquals(96, full.video.frames.size());
        assertEquals(full.video.frames.size(), sparse.video.frames.size());
        int retained = 0;
        for (int i = 0; i < full.video.frames.size(); i++) {
            OracleCoreVerifier.Frame frame = full.video.frames.get(i);
            OracleCoreVerifier.Frame compact = sparse.video.frames.get(i);
            assertNotNull("Missing real pixels at " + i, frame.image);
            assertEquals(frame.ptsSeconds, compact.ptsSeconds, 0d);
            assertEquals(identity.barcodeRegions.size(), compact.barcodeLumaPairs.length);
            if (compact.image != null) {
                retained++;
                assertTrue("Sparse probe pixels differ at " + i,
                        Arrays.equals(frame.image.rgb, compact.image.rgb));
            }
        }
        assertEquals(identity.probes.size(), retained);
        assertTrue(full.audio.samples.length > 0);
        assertTrue(Arrays.equals(full.audio.samples, sparse.audio.samples));
        int sampleCount = 0;
        double nextPts = full.audio.startSeconds;
        for (OracleCoreVerifier.AudioPacket packet : full.audio.packets) {
            assertEquals("PCM PTS must not be fabricated from array offsets", nextPts,
                    packet.ptsSeconds, contract.tolerances.audioPtsContinuitySeconds);
            assertTrue(packet.sampleCount > 0);
            sampleCount += packet.sampleCount;
            nextPts = packet.ptsSeconds + packet.sampleCount / (double) full.audio.sampleRate;
        }
        assertEquals(full.audio.samples.length, sampleCount);
        final int[] pngLoads = {0};
        OracleCoreVerifier.ExpectedImageProvider pngs = new OracleCoreVerifier.ExpectedImageProvider() {
            @Override
            public OracleCoreVerifier.RgbImage load(OracleContract.OracleCase oracleCase,
                                                    OracleContract.Probe probe,
                                                    int matchedSourceFrame) throws IOException {
                OracleContract.Asset asset = oracleCase.frameAssets.get(Integer.valueOf(matchedSourceFrame));
                if (asset == null && matchedSourceFrame == probe.sourceFrame) {
                    asset = probe.image;
                }
                if (asset == null) {
                    throw new IOException("Missing analytic PNG for source " + matchedSourceFrame);
                }
                InputStream input = getInstrumentation().getTargetContext().getAssets()
                        .open("video-oracle/" + asset.path.replace('\\', '/'));
                try {
                    pngLoads[0]++;
                    return decoder.loadAssetImage(input);
                } finally {
                    input.close();
                }
            }
        };
        OracleCoreVerifier core = new OracleCoreVerifier();
        OracleCoreVerifier.Report fullReport = core.verify(contract, "identity", full, pngs);
        assertTrue(fullReport.toMap().toString(), fullReport.passed());
        OracleCoreVerifier.Report sparseReport = core.verify(contract, "identity", sparse, pngs);
        assertTrue(sparseReport.toMap().toString(), sparseReport.passed());
        assertEquals(2 * identity.probes.size(), pngLoads[0]);
        assertEquals(fullReport.toMap(), sparseReport.toMap());
    }

    public void testYuvPlaneOffsetsStridesAndOddCrop() throws Exception {
        ByteBuffer y = ByteBuffer.allocate(24);
        ByteBuffer u = ByteBuffer.allocate(12);
        ByteBuffer v = ByteBuffer.allocate(9);
        y.position(2);
        u.position(3);
        v.position(1);
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                y.put(2 + row * 6 + col, (byte) (16 + row * 20 + col * 10));
            }
        }
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                u.put(3 + row * 6 + col * 2, (byte) 128);
                v.put(1 + row * 5 + col * 2, (byte) 128);
            }
        }
        OracleCoreVerifier.RgbImage image = convertYuv(new ByteBuffer[] {y, u, v},
                new int[] {6, 6, 5}, new int[] {1, 2, 2}, new Rect(1, 1, 4, 4),
                new MediaFormat(), new MediaFormat(), Long.MAX_VALUE);
        assertEquals(3, image.width);
        assertEquals(3, image.height);
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                int expected = (int) Math.round(((row + 1) * 20 + (col + 1) * 10) * 255d / 219d);
                for (int channel = 0; channel < 3; channel++) {
                    assertEquals(expected, image.rgb[(row * 3 + col) * 3 + channel] & 255);
                }
            }
        }
        assertEquals(2, y.position());
        assertEquals(3, u.position());
        assertEquals(1, v.position());
        u.limit(u.limit() - 1);
        try {
            convertYuv(new ByteBuffer[] {y, u, v}, new int[] {6, 6, 5},
                    new int[] {1, 2, 2}, new Rect(1, 1, 4, 4),
                    new MediaFormat(), new MediaFormat(), Long.MAX_VALUE);
            fail("Truncated final chroma row must be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("YUV plane"));
        }
    }

    public void testColorMetadataFallbackAndDeadline() throws Exception {
        MediaFormat input = new MediaFormat();
        input.setInteger("color-standard", 1);
        input.setInteger("color-range", 1);
        ByteBuffer[] planes = {ByteBuffer.wrap(new byte[] {100}),
                ByteBuffer.wrap(new byte[] {(byte) 128}), ByteBuffer.wrap(new byte[] {(byte) 180})};
        OracleCoreVerifier.RgbImage image = convertYuv(planes, new int[] {1, 1, 1},
                new int[] {1, 1, 1}, new Rect(0, 0, 1, 1), new MediaFormat(), input, Long.MAX_VALUE);
        assertEquals(182, image.rgb[0] & 255);
        assertEquals(76, image.rgb[1] & 255);
        assertEquals(100, image.rgb[2] & 255);
        try {
            convertYuv(planes, new int[] {1, 1, 1}, new int[] {1, 1, 1}, new Rect(0, 0, 1, 1),
                    new MediaFormat(), input, System.nanoTime() - 1);
            fail("Expired pixel-conversion deadline must fail");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("timed out"));
        }
    }

    public void testExtendedColorStandardUsesDocumentedBt601Matrix() throws Exception {
        MediaFormat output = new MediaFormat();
        output.setInteger("color-standard", 85);
        output.setInteger("color-range", 2);
        output.setInteger("color-transfer", 0);
        ByteBuffer[] planes = {ByteBuffer.wrap(new byte[] {100}),
                ByteBuffer.wrap(new byte[] {(byte) 128}), ByteBuffer.wrap(new byte[] {(byte) 180})};
        OracleCoreVerifier.RgbImage image = convertYuv(planes, new int[] {1, 1, 1},
                new int[] {1, 1, 1}, new Rect(0, 0, 1, 1), output, new MediaFormat(), Long.MAX_VALUE);
        assertEquals(181, image.rgb[0] & 255);
        assertEquals(56, image.rgb[1] & 255);
        assertEquals(98, image.rgb[2] & 255);
    }

    public void testUnsupportedColorMetadataStillRejected() throws Exception {
        ByteBuffer[] planes = {ByteBuffer.wrap(new byte[] {100}),
                ByteBuffer.wrap(new byte[] {(byte) 128}), ByteBuffer.wrap(new byte[] {(byte) 180})};
        MediaFormat unknown = new MediaFormat();
        unknown.setInteger("color-standard", 64);
        unknown.setInteger("color-range", 2);
        unknown.setInteger("color-transfer", 0);
        try {
            convertYuv(planes, new int[] {1, 1, 1}, new int[] {1, 1, 1},
                    new Rect(0, 0, 1, 1), unknown, new MediaFormat(), Long.MAX_VALUE);
            fail("Unspecified extended matrix must still be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("Unsupported color matrix"));
        }
        MediaFormat hdr = new MediaFormat();
        hdr.setInteger("color-standard", 1);
        hdr.setInteger("color-range", 2);
        hdr.setInteger("color-transfer", 7);
        try {
            convertYuv(planes, new int[] {1, 1, 1}, new int[] {1, 1, 1},
                    new Rect(0, 0, 1, 1), hdr, new MediaFormat(), Long.MAX_VALUE);
            fail("HDR transfer must still be rejected");
        } catch (IOException expected) {
            assertTrue(expected.getMessage(), expected.getMessage().contains("HDR"));
        }
    }

    public void testPcmOffsetsAlignmentBudgetAndFormatChanges() throws Exception {
        ByteBuffer pcm = ByteBuffer.allocate(10).order(ByteOrder.nativeOrder());
        pcm.putShort(2, (short) 16384);
        pcm.putShort(4, (short) -16384);
        pcm.position(8);
        float[] samples = decodePcm(pcm, 2, 4, AudioFormat.ENCODING_PCM_16BIT, 1, 2);
        assertEquals(0.5f, samples[0], 0f);
        assertEquals(-0.5f, samples[1], 0f);
        assertEquals(8, pcm.position());
        int[][] invalid = {{2, 3, 1, 2}, {9, 4, 1, 2}, {2, 4, 0, 2}, {2, 4, 1, 1}};
        for (int[] args : invalid) {
            try {
                decodePcm(pcm, args[0], args[1], AudioFormat.ENCODING_PCM_16BIT, args[2], args[3]);
                fail("Malformed/budget-exceeding PCM must be rejected: " + Arrays.toString(args));
            } catch (IOException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        pcm.putFloat(2, Float.NaN);
        try {
            decodePcm(pcm, 2, 4, AudioFormat.ENCODING_PCM_FLOAT, 1, 1);
            fail("Non-finite PCM must not reach tone checks");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Non-finite"));
        }
        Method validate = OracleAndroidDecoder.class.getDeclaredMethod("validateAudioFormat",
                MediaFormat.class, int.class, int.class, boolean.class);
        MediaFormat changed = MediaFormat.createAudioFormat("audio/raw", 44100, 1);
        try {
            invokeDecoder(validate, changed, 48000, 1, true);
            fail("Rate change must not reinterpret previous packet sample counts");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("format changed"));
        }
    }

    public void testDecoderHonorsInterruptionBeforeOpeningMedia() throws Exception {
        Thread.currentThread().interrupt();
        try {
            new OracleAndroidDecoder().decode(null);
            fail("Interrupted decode must not return a candidate");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    public void testGaplessPaddingRebasesAndroidAudioPacketPts() throws Exception {
        ArrayList<OracleCoreVerifier.AudioPacket> speed2 = normalizeGaplessPackets(Arrays.asList(
                new OracleCoreVerifier.AudioPacket(0d, 992),
                new OracleCoreVerifier.AudioPacket(1024d / 48000d, 1024),
                new OracleCoreVerifier.AudioPacket(2048d / 48000d, 1024)), 48000, 1024, 32);
        assertEquals(0d, speed2.get(0).ptsSeconds, 0d);
        assertEquals(992d / 48000d, speed2.get(1).ptsSeconds, 1e-9);
        assertEquals((992d + 1024d) / 48000d, speed2.get(2).ptsSeconds, 1e-9);
        assertEquals(0d, maxGapError(speed2, 48000), 1e-9);

        ArrayList<OracleCoreVerifier.AudioPacket> combo = normalizeGaplessPackets(Arrays.asList(
                new OracleCoreVerifier.AudioPacket(0d, 1012),
                new OracleCoreVerifier.AudioPacket(1024d / 48000d, 1024),
                new OracleCoreVerifier.AudioPacket(2048d / 48000d, 1024)), 48000, 1024, 12);
        assertEquals(1012d / 48000d, combo.get(1).ptsSeconds, 1e-9);
        assertEquals(0d, maxGapError(combo, 48000), 1e-9);
    }

    public void testGaplessPaddingDoesNotHideRealTimestampGap() throws Exception {
        ArrayList<OracleCoreVerifier.AudioPacket> raw = new ArrayList<OracleCoreVerifier.AudioPacket>(
                Arrays.asList(
                        new OracleCoreVerifier.AudioPacket(0d, 992),
                        new OracleCoreVerifier.AudioPacket(1024d / 48000d, 1024),
                        new OracleCoreVerifier.AudioPacket(5824d / 48000d, 1024)));
        ArrayList<OracleCoreVerifier.AudioPacket> adjusted = normalizeGaplessPackets(raw, 48000, 1024, 32);
        assertEquals(raw.get(1).ptsSeconds, adjusted.get(1).ptsSeconds, 0d);
        assertEquals(raw.get(2).ptsSeconds, adjusted.get(2).ptsSeconds, 0d);
        assertTrue(maxGapError(adjusted, 48000) > .05d);
    }

    public void testGaplessPaddingPreservesQuantizedPtsAndSampleCounts() throws Exception {
        List<OracleCoreVerifier.AudioPacket> raw = Arrays.asList(
                new OracleCoreVerifier.AudioPacket(0d, 992),
                new OracleCoreVerifier.AudioPacket(.021333d, 1024),
                new OracleCoreVerifier.AudioPacket(.042666d, 1024));
        List<OracleCoreVerifier.AudioPacket> adjusted = normalizeGaplessPackets(raw, 48000, 1024, 32);
        assertSame(raw.get(0), adjusted.get(0));
        for (int i = 1; i < raw.size(); i++) {
            assertEquals(raw.get(i).sampleCount, adjusted.get(i).sampleCount);
            assertEquals(raw.get(i).ptsSeconds - 32d / 48000d, adjusted.get(i).ptsSeconds, 0d);
        }
        assertTrue(maxGapError(adjusted, 48000) <= .000001d);
        assertEquals(.021333d, raw.get(1).ptsSeconds, 0d);
    }

    public void testGaplessAccountingAcrossCompleteAacTimelines() throws Exception {
        int[][] cases = {{95, 32, 96224}, {48, 12, 48116}};
        for (int[] values : cases) {
            int accessUnits = values[0];
            int padding = values[1];
            ArrayList<Long> input = new ArrayList<Long>();
            ArrayList<OracleCoreVerifier.AudioPacket> output =
                    new ArrayList<OracleCoreVerifier.AudioPacket>();
            for (int i = 0; i < accessUnits; i++) {
                input.add(i * 1024L * 1_000_000L / 48000L);
                if (i < accessUnits - 1) {
                    output.add(new OracleCoreVerifier.AudioPacket(
                            (i * 1024L * 1_000_000L / 48000L) / 1_000_000d,
                            i == 0 ? 1024 - padding : 1024));
                }
            }
            List<OracleCoreVerifier.AudioPacket> adjusted =
                    normalizeGaplessPackets(output, 48000, 1024, padding, 1024, input);
            int samples = 0;
            for (OracleCoreVerifier.AudioPacket packet : adjusted) {
                samples += packet.sampleCount;
            }
            assertEquals(values[2], samples);
            assertEquals(padding / 48000d,
                    output.get(1).ptsSeconds - adjusted.get(1).ptsSeconds, 1e-12);
            assertTrue(maxGapError(adjusted, 48000) <= .000001d);
        }
    }

    public void testGaplessPaddingDoesNotHideDroppedPcmOrAccessUnit() throws Exception {
        List<OracleCoreVerifier.AudioPacket> raw = Arrays.asList(
                new OracleCoreVerifier.AudioPacket(0d, 992),
                new OracleCoreVerifier.AudioPacket(.021333d, 1024),
                new OracleCoreVerifier.AudioPacket(.042666d, 1024));
        // Five submitted frames require four outputs after the one-frame encoder delay.
        assertPacketsUnchanged(raw, normalizeGaplessPackets(raw, 48000, 1024, 32, 1024,
                Arrays.asList(0L, 21333L, 42666L, 64000L, 85333L)));
        // A first-buffer loss equal to padding is not proof of gapless carry.
        List<OracleCoreVerifier.AudioPacket> trimmedTail = Arrays.asList(raw.get(0), raw.get(1),
                new OracleCoreVerifier.AudioPacket(.042666d, 992));
        assertPacketsUnchanged(trimmedTail, normalizeGaplessPackets(trimmedTail, 48000, 1024, 32));
        List<OracleCoreVerifier.AudioPacket> oneSampleLost = Arrays.asList(
                new OracleCoreVerifier.AudioPacket(0d, 991), raw.get(1), raw.get(2));
        assertPacketsUnchanged(oneSampleLost, normalizeGaplessPackets(oneSampleLost, 48000, 1024, 32));
        // Even perfect-looking PCM output cannot justify repairing a compressed-input gap.
        assertPacketsUnchanged(raw, normalizeGaplessPackets(raw, 48000, 1024, 32, 1024,
                Arrays.asList(0L, 22000L, 43333L, 64666L)));
        assertPacketsUnchanged(raw, normalizeGaplessPackets(raw, 48000, 1024, 32, 1024,
                Arrays.asList(0L, 21333L, 64000L, 85333L)));
    }

    public void testGaplessPaddingDoesNotAcceptCancellingGapAndOverlap() throws Exception {
        List<OracleCoreVerifier.AudioPacket> raw = Arrays.asList(
                new OracleCoreVerifier.AudioPacket(0d, 992),
                new OracleCoreVerifier.AudioPacket(1024d / 48000d, 1024),
                new OracleCoreVerifier.AudioPacket(2200d / 48000d, 1024),
                new OracleCoreVerifier.AudioPacket(3072d / 48000d, 1024));
        assertPacketsUnchanged(raw, normalizeGaplessPackets(raw, 48000, 1024, 32, 1024,
                Arrays.asList(0L, 21333L, 42666L, 64000L, 85333L)));
    }

    public void testGaplessPaddingRequiresExactMetadataAndZeroStart() throws Exception {
        List<OracleCoreVerifier.AudioPacket> raw = Arrays.asList(
                new OracleCoreVerifier.AudioPacket(0d, 992),
                new OracleCoreVerifier.AudioPacket(.021333d, 1024),
                new OracleCoreVerifier.AudioPacket(.042666d, 1024));
        int[][] metadata = {{0, 32}, {1024, 0}, {1024, 31}, {1024, 33}, {512, 32}, {1024, 1024}};
        for (int[] values : metadata) {
            assertPacketsUnchanged(raw, normalizeGaplessPackets(raw, 48000, values[0], values[1]));
        }
        assertPacketsUnchanged(raw, normalizeGaplessPackets(raw, 48000, 1024, 32, 0,
                Arrays.asList(0L, 21333L, 42666L, 64000L)));
        List<OracleCoreVerifier.AudioPacket> offset = Arrays.asList(
                new OracleCoreVerifier.AudioPacket(1d / 48000d, 992),
                new OracleCoreVerifier.AudioPacket(1025d / 48000d, 1024),
                new OracleCoreVerifier.AudioPacket(2049d / 48000d, 1024));
        assertPacketsUnchanged(offset, normalizeGaplessPackets(offset, 48000, 1024, 32));
        List<OracleCoreVerifier.AudioPacket> alreadyTrimmed = Arrays.asList(
                new OracleCoreVerifier.AudioPacket(0d, 1024),
                new OracleCoreVerifier.AudioPacket(.021333d, 1024),
                new OracleCoreVerifier.AudioPacket(.042666d, 992));
        assertPacketsUnchanged(alreadyTrimmed, normalizeGaplessPackets(alreadyTrimmed, 48000, 1024, 32));
    }

    public void testGaplessAccountingRequires1024SampleAacLcConfig() throws Exception {
        Method method = OracleAndroidDecoder.class.getDeclaredMethod("aacFrameSamples", MediaFormat.class);
        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", 48000, 1);
        format.setInteger("aac-profile", 2);
        ByteBuffer config = ByteBuffer.wrap(new byte[] {0x11, (byte) 0x88});
        format.setByteBuffer("csd-0", config);
        assertEquals(1024, ((Integer) invokeDecoder(method, format)).intValue());
        assertEquals(0, config.position());
        format.setByteBuffer("csd-0", ByteBuffer.wrap(
                new byte[] {0x11, (byte) 0x88, 0x56, (byte) 0xe5, 0}));
        assertEquals(1024, ((Integer) invokeDecoder(method, format)).intValue());
        format.setByteBuffer("csd-0", ByteBuffer.wrap(
                new byte[] {0x11, (byte) 0x88, 0x56, (byte) 0xe5, (byte) 0x80}));
        assertEquals(0, ((Integer) invokeDecoder(method, format)).intValue());
        format.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[] {0x11, (byte) 0x8c}));
        assertEquals(0, ((Integer) invokeDecoder(method, format)).intValue());
        format.setByteBuffer("csd-0", ByteBuffer.wrap(new byte[] {0x11}));
        assertEquals(0, ((Integer) invokeDecoder(method, format)).intValue());
        format.setByteBuffer("csd-0", config);
        format.setInteger("sample-rate", 44100);
        assertEquals(0, ((Integer) invokeDecoder(method, format)).intValue());
        format.setInteger("sample-rate", 48000);
        format.setInteger("aac-profile", 5);
        assertEquals(0, ((Integer) invokeDecoder(method, format)).intValue());
    }

    private static void assertPacketsUnchanged(List<OracleCoreVerifier.AudioPacket> expected,
                                               List<OracleCoreVerifier.AudioPacket> actual) {
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), actual.get(i));
        }
    }

    private static OracleCoreVerifier.RgbImage convertYuv(ByteBuffer[] buffers, int[] rows, int[] pixels,
                                                          Rect crop, MediaFormat output, MediaFormat input,
                                                          long deadline) throws Exception {
        Class<?> transformClass = Class.forName(OracleAndroidDecoder.class.getName() + "$ColorTransform");
        Method from = transformClass.getDeclaredMethod("from", MediaFormat.class, MediaFormat.class);
        Object transform = invokeDecoder(from, output, input);
        Method convert = OracleAndroidDecoder.class.getDeclaredMethod("yuvToRgb", ByteBuffer[].class,
                int[].class, int[].class, Rect.class, transformClass, long.class);
        return (OracleCoreVerifier.RgbImage) invokeDecoder(convert, buffers, rows, pixels, crop, transform, deadline);
    }

    private static float[] decodePcm(ByteBuffer buffer, int offset, int size, int encoding,
                                       int channels, int remaining) throws Exception {
        Method method = OracleAndroidDecoder.class.getDeclaredMethod("decodePcmBuffer", ByteBuffer.class,
                int.class, int.class, int.class, int.class, int.class);
        return (float[]) invokeDecoder(method, buffer, offset, size, encoding, channels, remaining);
    }

    private static ArrayList<OracleCoreVerifier.AudioPacket> normalizeGaplessPackets(
            List<OracleCoreVerifier.AudioPacket> packets, int sampleRate,
            int encoderDelay, int encoderPadding) throws Exception {
        return normalizeGaplessPackets(packets, sampleRate, encoderDelay, encoderPadding, 1024,
                Arrays.asList(0L, 21333L, 42666L, 64000L));
    }

    @SuppressWarnings("unchecked")
    private static ArrayList<OracleCoreVerifier.AudioPacket> normalizeGaplessPackets(
            List<OracleCoreVerifier.AudioPacket> packets, int sampleRate,
            int encoderDelay, int encoderPadding, int frameSamples, List<Long> inputPtsUs) throws Exception {
        Method method = OracleAndroidDecoder.class.getDeclaredMethod("normalizeGaplessPacketPts",
                List.class, int.class, int.class, int.class, int.class, List.class);
        return (ArrayList<OracleCoreVerifier.AudioPacket>) invokeDecoder(
                method, packets, sampleRate, encoderDelay, encoderPadding, frameSamples, inputPtsUs);
    }

    private static double maxGapError(List<OracleCoreVerifier.AudioPacket> packets, int sampleRate) {
        double maxGapError = 0d;
        for (int i = 1; i < packets.size(); i++) {
            OracleCoreVerifier.AudioPacket previous = packets.get(i - 1);
            OracleCoreVerifier.AudioPacket current = packets.get(i);
            double expectedGap = previous.sampleCount / (double) sampleRate;
            maxGapError = Math.max(maxGapError,
                    Math.abs((current.ptsSeconds - previous.ptsSeconds) - expectedGap));
        }
        return maxGapError;
    }

    private static Object invokeDecoder(Method method, Object... args) throws Exception {
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw new AssertionError(cause);
        }
    }

    private void assertReport(OracleVerifier verifier, String caseId, File fixture,
                              long expectedBytes, String expectedSha256,
                              boolean expectedPass) throws Exception {
        assertFixtureUnchanged(fixture, expectedBytes, expectedSha256, "before " + caseId);
        JSONObject report;
        try {
            report = verifier.verify(caseId, fixture);
        } finally {
            // Check before the next verify call can repair the prepared fixture.
            assertFixtureUnchanged(fixture, expectedBytes, expectedSha256, "after " + caseId);
        }
        assertNotNull("Missing report for " + caseId, report);
        String diagnostic = caseId + ": " + report.toString();
        assertEquals(diagnostic, caseId, report.getString("case"));
        assertEquals(diagnostic, expectedSha256, report.getString("candidate_sha256"));
        assertEquals(diagnostic, expectedPass ? "PASS" : "FAIL", report.getString("status"));
        assertEquals(diagnostic, expectedPass, report.getBoolean("passed"));
        report.put("export_codecs", "ENCODER not exercised: frozen fixture");
        report.put("reference", verifier.loadContract().getJSONArray("cases")
                .getJSONObject(caseIndex(verifier, caseId)).getJSONObject("reference"));
        String summary = SelfTestRunner.formatCaseResult(report);
        assertTrue(summary, summary.startsWith((expectedPass ? "PASS " : "FAIL ") + caseId + "\n"));
        assertTrue(summary, summary.contains("\nOUTPUT SHA256 " + expectedSha256));
        assertTrue(summary, summary.contains("\nDECODER "
                + report.getJSONObject(OracleVerifier.DECODER_KEY)));
        assertTrue(summary, summary.endsWith("Full measurements: " + caseId + ".json\n"));
        OracleContract contract = OracleGeneratedContract.create();
        assertEquals("independent-video-oracle-android-report", report.getString("schema"));
        assertEquals("1.0.0", report.getString("version"));
        assertEquals(contract.schema, report.getString("contract_schema"));
        assertEquals(contract.version, report.getString("contract_version"));
        JSONObject pins = report.getJSONObject("pins");
        assertEquals(expectedSha256, pins.getJSONObject("fixture").getString("sha256"));
        assertEquals(contract.pins.assetContractSha256,
                pins.getJSONObject("asset_contract").getString("sha256"));
        assertEquals(contract.pins.manifestSha256,
                pins.getJSONObject("manifest").getString("sha256"));
        if (expectedPass) {
            for (String track : new String[] {"video", "audio"}) {
                JSONObject decoder = report.getJSONObject(OracleVerifier.DECODER_KEY).getJSONObject(track);
                assertTrue(decoder.getBoolean("present"));
                assertTrue(decoder.getString("decoder_name").length() > 0);
                assertTrue(decoder.getJSONObject("input_format").getString("mime").length() > 0);
                JSONObject output = decoder.getJSONObject("output_format");
                assertTrue(output.getString("mime").length() > 0);
                assertTrue(output.has("color-standard"));
                assertTrue(output.has("color-range"));
                assertTrue(output.has("color-transfer"));
            }
        }
    }

    private int caseIndex(OracleVerifier verifier, String caseId) throws Exception {
        JSONArray cases = verifier.loadContract().getJSONArray("cases");
        for (int i = 0; i < cases.length(); i++) {
            if (caseId.equals(cases.getJSONObject(i).getString("id"))) return i;
        }
        throw new AssertionError("Missing case: " + caseId);
    }

    private void assertFixtureUnchanged(File fixture, long expectedBytes,
                                        String expectedSha256, String stage) throws Exception {
        assertTrue(stage + ": fixture missing", fixture.isFile());
        assertEquals(stage + ": fixture length changed", expectedBytes, fixture.length());
        assertEquals(stage + ": fixture contents changed", expectedSha256, sha256(fixture));
    }

    private String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        } finally {
            input.close();
        }
        char[] hex = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            int unsigned = value & 0xff;
            result.append(hex[unsigned >>> 4]);
            result.append(hex[unsigned & 0x0f]);
        }
        return result.toString();
    }
}
