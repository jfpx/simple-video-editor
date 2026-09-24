package com.simple.videoeditor.oracle;

import javax.imageio.ImageIO;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Independently decodes frozen intro controls using FFmpeg and the existing local helper.
 * Requires whole-file production-core checks and independently rebased segment checks to agree.
 */
public final class IntroParityMain {
    private static final File BASE = new File("app\\src\\main\\assets\\video-oracle");
    private static final File ASSETS = new File("app\\src\\main\\assets\\intro-oracle");
    private static final File OUTPUT = new File("app\\build\\intro-parity");
    private static final List<String> SPATIAL = Arrays.asList(
            "video.temporal_barcode", "video.spatial_probe_count", "video.spatial_mae",
            "video.spatial_p95", "video.pixel_regions", "video.moving_markers");
    private final OracleContract contract = IntroOracleContract.create();
    private final OracleContract.OracleCase intro = contract.requireCase(IntroOracleContract.CASE_ID);
    private final OracleCoreVerifier verifier = new OracleCoreVerifier();
    private final Object loader;
    private final Method decode;
    private final Method image;
    private final Method json;

    private IntroParityMain() throws Exception {
        Constructor<LocalParityMain> constructor = LocalParityMain.class.getDeclaredConstructor(File.class);
        constructor.setAccessible(true);
        loader = constructor.newInstance(BASE);
        decode = LocalParityMain.class.getDeclaredMethod("decode", File.class,
                OracleContract.OracleCase.class, boolean.class);
        decode.setAccessible(true);
        image = LocalParityMain.class.getDeclaredMethod("loadImage", String.class);
        image.setAccessible(true);
        json = LocalParityMain.class.getDeclaredMethod("json", Object.class);
        json.setAccessible(true);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1 || (args.length == 1 && !"--require-core".equals(args[0]))) {
            throw new IllegalArgumentException("Usage: IntroParityMain [--require-core] (from repository root)");
        }
        ImageIO.setUseCache(false);
        new IntroParityMain().run();
    }

    private void run() throws Exception {
        Files.createDirectories(OUTPUT.toPath());
        validatePinsAndMapping();
        List<Map<String, Object>> results = new ArrayList<>();
        boolean coreReady = true;
        for (IntroOracleContract.Control control : IntroOracleContract.CONTROLS) {
            Map<String, Object> full = evaluate(control, false);
            Map<String, Object> sparse = evaluate(control, true);
            require(full.equals(sparse), "Full/sparse report mismatch: " + control.id);
            coreReady &= Boolean.TRUE.equals(full.get("unadapted_core_expected_outcome"));
            full.put("full_sparse_equal", true);
            results.add(full);
            System.out.println("PASS expected=" + (control.expectedPass ? "PASS" : "FAIL")
                    + " control=" + control.id + " full/sparse=equal");
        }
        Map<String, Object> report = object(
                "version", IntroOracleContract.VERSION,
                "contract_sha256", IntroOracleContract.CONTRACT_SHA256,
                "fixture_checks_passed", true,
                "unadapted_core_ready", coreReady,
                "validation_mode", "whole-file core checks plus independently rebased segment video checks",
                "supplement_exports", IntroOracleContract.CASES.size(),
                "supplement_controls", results.size(),
                "default_counts", Arrays.asList(17, 60),
                "frozen_analytic_frames_checked", intro.frameAssets.size(),
                "controls", results);
        Files.write(new File(OUTPUT, "results.json").toPath(),
                (((String) json.invoke(null, report)) + "\n").getBytes(StandardCharsets.UTF_8));
        require(coreReady, "Whole-file production core disagrees with frozen controls; see results.json.");
        System.out.println("PASS fixtures=5/5; whole-file production core=PASS");
    }

    private Map<String, Object> evaluate(IntroOracleContract.Control control, boolean sparse) throws Exception {
        File input = new File(ASSETS, control.asset.path);
        OracleCoreVerifier.Candidate candidate = (OracleCoreVerifier.Candidate)
                decode.invoke(loader, input, intro, sparse);
        Map<String, Object> whole = verify(contract, candidate);
        if (control.asset == IntroOracleContract.MISSING_INTRO
                || control.asset == IntroOracleContract.WRONG_DURATION) {
            OracleCoreVerifier.VideoTrack video = candidate.video;
            OracleCoreVerifier.VideoTrack metadataOnly = new OracleCoreVerifier.VideoTrack(
                    video.codec, video.sampleAspectRatio, video.width, video.height,
                    video.rotationDegrees, video.durationSeconds, Collections.emptyList());
            Map<String, Object> gated = verify(contract, new OracleCoreVerifier.Candidate(
                    candidate.path, candidate.sha256, candidate.fileBytes, candidate.videoTrackCount,
                    candidate.audioTrackCount, metadataOnly, candidate.audio));
            require("FAIL".equals(gated.get("status")), "Native metadata gate must reject " + control.id);
            for (String assertion : control.requiredFailures) {
                boolean presentFailure = checks(gated).stream().anyMatch(check ->
                        assertion.equals(check.get("assertion"))
                                && Boolean.FALSE.equals(check.get("passed")));
                require(presentFailure, "Native gate omitted required failure: " + assertion);
            }
            require(checks(gated).stream().noneMatch(check ->
                    "video.frame_count".equals(check.get("assertion"))),
                    "Metadata-only rejection must not measure frame count");
        }
        List<Map<String, Object>> alignment = Collections.emptyList();
        if (control.expectedPass && "PASS".equals(whole.get("status"))) {
            alignment = validateSegmentAlignment(candidate);
        }
        List<Map<String, Object>> segments = new ArrayList<>();
        for (int segment = 0; segment < 2; segment++) {
            double start = segment == 0 ? 0d : 1d;
            double stop = segment == 0 ? 1d : 5d;
            OracleContract segmentContract = segmentContract(segment);
            segments.add(verify(segmentContract, sliceVideo(candidate, start, stop)));
        }
        List<Map<String, Object>> effectiveChecks = new ArrayList<>();
        for (Map<String, Object> check : checks(whole)) {
            if (!SPATIAL.contains(check.get("assertion"))) effectiveChecks.add(check);
        }
        for (String assertion : SPATIAL) {
            boolean passed = true;
            for (Map<String, Object> segment : segments) passed &= checkPassed(segment, assertion);
            effectiveChecks.add(object("assertion", assertion, "passed", passed));
        }
        for (int i = 0; i < segments.size(); i++) {
            for (Map<String, Object> check : checks(segments.get(i))) {
                effectiveChecks.add(object("assertion", "segment_" + i + "." + check.get("assertion"),
                        "passed", check.get("passed")));
            }
        }
        boolean passed = effectiveChecks.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")));
        Map<String, Object> effective = object("checks", effectiveChecks, "status", passed ? "PASS" : "FAIL");
        require(passed == control.expectedPass, "Unexpected fixture result " + control.id + ": " + effective);
        for (String assertion : control.requiredFailures) {
            require(!checkPassed(effective, assertion), "Missing meaningful failure " + control.id + ": " + assertion);
        }
        if (control.asset == IntroOracleContract.REVERSED_ORDER
                || control.asset == IntroOracleContract.WRONG_ORIGINAL_AUDIO) {
            for (String assertion : Arrays.asList("video.duration", "video.frame_count", "audio.track_duration")) {
                require(checkPassed(effective, assertion), "Negative must preserve " + assertion);
            }
        }
        if (control.asset == IntroOracleContract.WRONG_ORIGINAL_AUDIO) {
            for (String assertion : SPATIAL) require(checkPassed(effective, assertion), "Audio-only video changed");
            for (int i = 0; i < 15; i++) {
                require(checkPassed(effective, "audio.window_" + i + ".rms") == (i < 3),
                        "Wrong-original-audio must preserve intro and reject every original window");
                if (i < 3) require(checkPassed(effective, "audio.window_" + i + ".frequency"),
                        "Intro tone must be retained");
            }
        }
        boolean coreOutcome = (control.expectedPass ? "PASS" : "FAIL").equals(whole.get("status"));
        for (String assertion : control.requiredFailures) coreOutcome &= !checkPassed(whole, assertion);
        if (control.asset == IntroOracleContract.REVERSED_ORDER
                || control.asset == IntroOracleContract.WRONG_ORIGINAL_AUDIO) {
            for (String assertion : Arrays.asList("video.duration", "video.frame_count", "audio.track_duration")) {
                coreOutcome &= checkPassed(whole, assertion);
            }
        }
        if (control.asset == IntroOracleContract.WRONG_ORIGINAL_AUDIO) {
            for (Map<String, Object> check : checks(whole)) {
                String assertion = (String) check.get("assertion");
                if (assertion.startsWith("video.") || assertion.startsWith("audio.window_0.")
                        || assertion.startsWith("audio.window_1.") || assertion.startsWith("audio.window_2.")) {
                    coreOutcome &= Boolean.TRUE.equals(check.get("passed"));
                }
            }
            for (int i = 3; i < 15; i++) {
                coreOutcome &= !checkPassed(whole, "audio.window_" + i + ".rms");
            }
        }
        return object("id", control.id, "case_id", control.caseId, "input", control.asset.path,
                "expected", control.expectedPass ? "PASS" : "FAIL", "effective", effective,
                "whole_file_core", whole, "segment_video_core", segments,
                "segment_alignment", alignment,
                "unadapted_core_expected_outcome", coreOutcome);
    }

    private List<Map<String, Object>> validateSegmentAlignment(OracleCoreVerifier.Candidate candidate)
            throws Exception {
        List<Map<String, Object>> results = new ArrayList<>();
        // Exercise both sides of each segment boundary without changing the frozen media.
        for (int[] mutation : new int[][]{
                {0, 47, 0}, {23, 72, 0}, {24, -1, 0}, {119, 96, 0},
                {0, 49, 1}, {23, 70, 1}, {24, 1, 1}, {119, 94, 1}}) {
            OracleCoreVerifier.VideoTrack original = candidate.video;
            List<OracleCoreVerifier.Frame> frames = new ArrayList<>(original.frames);
            OracleCoreVerifier.Frame frame = frames.get(mutation[0]);
            double[][] barcode = new double[intro.barcodeRegions.size()][2];
            for (int bit = 0; bit < barcode.length; bit++) {
                boolean high = ((mutation[1] >> intro.barcodeRegions.get(bit).bit) & 1) == 1;
                barcode[bit][0] = high ? contract.source.barcodeHighRgb : contract.source.barcodeLowRgb;
                barcode[bit][1] = high ? contract.source.barcodeLowRgb : contract.source.barcodeHighRgb;
            }
            frames.set(mutation[0], new OracleCoreVerifier.Frame(frame.ptsSeconds, frame.image, barcode));
            OracleCoreVerifier.VideoTrack video = new OracleCoreVerifier.VideoTrack(original.codec,
                    original.sampleAspectRatio, original.width, original.height, original.rotationDegrees,
                    original.durationSeconds, frames);
            Map<String, Object> report = verify(contract, new OracleCoreVerifier.Candidate(candidate.path,
                    candidate.sha256, candidate.fileBytes, candidate.videoTrackCount, candidate.audioTrackCount,
                    video, candidate.audio));
            boolean passed = checkPassed(report, "video.temporal_barcode");
            require(passed == (mutation[2] == 1), "Segment alignment escaped bounds or lost tolerance: "
                    + Arrays.toString(mutation));
            results.add(object("output_frame", mutation[0], "observed_source_frame", mutation[1],
                    "barcode_passed", passed));
        }
        return results;
    }

    private Map<String, Object> verify(OracleContract expected, OracleCoreVerifier.Candidate candidate)
            throws Exception {
        Map<String, Object> report = verifier.verify(expected, intro.id, candidate,
                (oracleCase, probe, frame) -> {
                    try {
                        return (OracleCoreVerifier.RgbImage) image.invoke(loader, oracleCase.frameAssets.get(frame).path);
                    } catch (ReflectiveOperationException error) {
                        throw new java.io.IOException("Cannot load frozen expected pixels", error);
                    }
                }).toMap();
        for (Map<String, Object> check : checks(report)) {
            require(!"checker.completed_without_error".equals(check.get("assertion")),
                    "Checker error is not a negative control: " + report);
        }
        return report;
    }

    private OracleContract segmentContract(int segment) {
        double start = segment == 0 ? 0d : 1d;
        double stop = segment == 0 ? 1d : 5d;
        OracleContract.Operation op = intro.operation;
        OracleContract.Operation operation = new OracleContract.Operation(segment == 0 ? 2d : 0d,
                segment == 0 ? 3d : 4d, op.cropLeft, op.cropTop, op.cropRight, op.cropBottom,
                op.rotationDegrees, op.outputHeight, op.speed, op.volume);
        List<OracleContract.Probe> probes = new ArrayList<>();
        for (OracleContract.Probe probe : intro.probes) {
            if (probe.outputSeconds >= start && probe.outputSeconds < stop) {
                probes.add(new OracleContract.Probe(probe.sourceFrame, probe.outputSeconds - start, probe.image));
            }
        }
        OracleContract.OracleCase oracleCase = new OracleContract.OracleCase(intro.id, operation,
                intro.androidEditConfig, intro.width, intro.height, stop - start,
                intro.nativeFps, intro.acceptedCfrFps, (int) ((stop - start) * 24d),
                intro.videoTrackCount, intro.audioTrackCount, false, intro.audioSampleRate, intro.audioChannels,
                0, intro.barcodeRegions, probes, intro.frameAssets, Collections.emptyList(), intro.reference);
        // Deliberately not the supplement version: this is an ordinary linear core check.
        return new OracleContract(contract.schema, "intro-segment-validation", contract.pins,
                contract.source, contract.tolerances, contract.fixture, Collections.singletonList(oracleCase));
    }

    private OracleCoreVerifier.Candidate sliceVideo(OracleCoreVerifier.Candidate candidate, double start, double stop) {
        List<OracleCoreVerifier.Frame> frames = new ArrayList<>();
        OracleCoreVerifier.VideoTrack original = candidate.video;
        for (OracleCoreVerifier.Frame frame : original.frames) {
            if (frame.ptsSeconds >= start && frame.ptsSeconds < stop) {
                frames.add(new OracleCoreVerifier.Frame(frame.ptsSeconds - start, frame.image, frame.barcodeLumaPairs));
            }
        }
        OracleCoreVerifier.VideoTrack video = new OracleCoreVerifier.VideoTrack(original.codec,
                original.sampleAspectRatio, original.width, original.height, original.rotationDegrees,
                Math.max(0d, Math.min(stop, original.durationSeconds) - start), frames);
        return new OracleCoreVerifier.Candidate(candidate.path, candidate.sha256, candidate.fileBytes,
                candidate.videoTrackCount, candidate.audioTrackCount, video, candidate.audio);
    }

    private void validatePinsAndMapping() throws Exception {
        require(IntroOracleContract.CASES.size() == 1 && IntroOracleContract.CONTROLS.size() == 5,
                "Bounded supplement counts");
        for (OracleContract.Asset asset : IntroOracleContract.ASSETS) validateAsset(ASSETS, asset);
        validateAsset(BASE, contract.fixture);
        for (Map.Entry<Integer, OracleContract.Asset> entry : intro.frameAssets.entrySet()) {
            validateAsset(BASE, entry.getValue());
            OracleCoreVerifier.RgbImage frozen = (OracleCoreVerifier.RgbImage) image.invoke(loader, entry.getValue().path);
            OracleCoreVerifier.RgbImage analytic = OracleCoreVerifier.sourceFrame(contract, entry.getKey());
            require(frozen.width == analytic.width && frozen.height == analytic.height
                    && Arrays.equals(frozen.rgb, analytic.rgb), "Frozen analytic pixel mismatch: " + entry.getKey());
        }
        for (int outputFrame = 0; outputFrame < 120; outputFrame++) {
            IntroOracleContract.FrameMapping mapping = IntroOracleContract.frameMapping(outputFrame / 24d);
            require(mapping.nominalSourceFrame == (outputFrame < 24 ? outputFrame + 48 : outputFrame - 24)
                    && mapping.firstSourceFrame == (outputFrame < 24 ? 48 : 0)
                    && mapping.stopSourceFrame == (outputFrame < 24 ? 72 : 96), "Wrong piecewise mapping");
        }
        for (OracleContract.Probe probe : intro.probes) {
            require(IntroOracleContract.frameMapping(probe.outputSeconds).nominalSourceFrame == probe.sourceFrame,
                    "Probe mapping mismatch");
        }
        require(intro.audioProbes.size() == 15 && intro.durationSeconds == 5d
                && intro.expectedDecodedAudioSamples == 240000, "Wrong audio contract");
        for (int i = 0; i < intro.audioProbes.size(); i++) {
            OracleContract.AudioProbe probe = intro.audioProbes.get(i);
            double source = probe.centerSeconds < 1d ? probe.centerSeconds + 2d : probe.centerSeconds - 1d;
            require(probe.sourceSeconds == source && probe.frequencyHz == new int[]{440, 660, 880, 1100}[(int) source]
                    && probe.rms == contract.source.toneRms, "Wrong analytic audio probe");
        }
    }

    private static void validateAsset(File root, OracleContract.Asset asset) throws Exception {
        File file = new File(root, asset.path);
        require(file.isFile() && file.length() == asset.bytes, "Asset size mismatch: " + file);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));
        StringBuilder hex = new StringBuilder();
        for (byte value : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        require(asset.sha256.equals(hex.toString()), "Asset hash mismatch: " + file);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> checks(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.get("checks");
    }

    private static boolean checkPassed(Map<String, Object> report, String assertion) {
        for (Map<String, Object> check : checks(report)) {
            if (assertion.equals(check.get("assertion"))) return Boolean.TRUE.equals(check.get("passed"));
        }
        throw new AssertionError("Missing assertion " + assertion);
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put((String) pairs[i], pairs[i + 1]);
        return map;
    }

    private static void require(boolean passed, String message) {
        if (!passed) throw new AssertionError(message);
    }
}
