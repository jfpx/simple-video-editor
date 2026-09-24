package com.simple.videoeditor.oracle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Frozen FFmpeg import: standard frames 48..71, then standard frames 0..95. */
public final class IntroOracleContract {
    public static final String VERSION = "intro-concat-1";
    public static final String CASE_ID = "imported_intro";
    public static final String ASSET_DIRECTORY = "intro-oracle";
    public static final long INTRO_DURATION_MS = 1000L;
    public static final long ORIGINAL_DURATION_MS = 4000L;
    public static final String CONTRACT_SHA256 =
            "f3193df2dbd0db791c5247e7fd5cfabbccc5b2d7c87c79274369f7c4cc69065e";
    public static final OracleContract.Asset MANIFEST = new OracleContract.Asset(
            "manifest.json", 59200, CONTRACT_SHA256);
    public static final OracleContract.Asset INTRO = new OracleContract.Asset(
            "intro.mp4", 29341, "d12302e96bcb911e1d0bcb126b63a989fad08c5cc12ad733a650fa0c96de8dcf");
    public static final OracleContract.Asset REFERENCE = new OracleContract.Asset(
            "reference.mp4", 139526, "acf616f88fce5b0ac4ca1bfe704196d5686ead04c63aa229eebadc35d1294b80");
    public static final OracleContract.Asset MISSING_INTRO = new OracleContract.Asset(
            "missing-intro.mp4", 95475, "f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2");
    public static final OracleContract.Asset REVERSED_ORDER = new OracleContract.Asset(
            "reversed-order.mp4", 130494, "b8afc888ea46c7cee2a74df1647dbb6a8361828c00b8b5f6b5eaa9ab1bae737f");
    public static final OracleContract.Asset WRONG_DURATION = new OracleContract.Asset(
            "wrong-duration.mp4", 167573, "bbc3f530c224f1e60d33f270b491650683a9204be2b7c66d5ed0ceb19deedf15");
    public static final OracleContract.Asset WRONG_ORIGINAL_AUDIO = new OracleContract.Asset(
            "wrong-original-audio.mp4", 66608, "bb9d35c6fbdc660a3e740dd391bfc71eb04e436cb86d80076e3015cc8f2cb3fa");
    public static final List<OracleContract.Asset> ASSETS = Collections.unmodifiableList(Arrays.asList(
            INTRO, REFERENCE, MISSING_INTRO, REVERSED_ORDER, WRONG_DURATION, WRONG_ORIGINAL_AUDIO, MANIFEST));
    public static final Map<String, String> ASSET_HASHES = assetHashes();
    public static final List<OracleContract.OracleCase> CASES =
            Collections.singletonList(buildCase());
    public static final List<Control> CONTROLS = Collections.unmodifiableList(Arrays.asList(
            new Control("reference_imported_intro", REFERENCE, true),
            // Native decoding stops at a duration mismatch; decoded frame counts are unavailable.
            new Control("missing_intro", MISSING_INTRO, false, "video.duration"),
            new Control("reversed_order", REVERSED_ORDER, false,
                    "video.temporal_barcode", "audio.window_0.frequency"),
            new Control("wrong_duration", WRONG_DURATION, false, "video.duration"),
            new Control("wrong_original_audio", WRONG_ORIGINAL_AUDIO, false, "audio.window_3.rms")));

    private IntroOracleContract() {}

    /**
     * The operation describes spatial transforms only. The core's temporal matcher must use
     * frameMapping for this version/case; its existing linear trim mapping cannot verify a concat.
     * Asset paths are relative to ASSET_DIRECTORY except inherited identity frameAssets (video-oracle).
     */
    public static OracleContract create() {
        OracleContract base = OracleGeneratedContract.create();
        return new OracleContract(base.schema, VERSION, base.pins, base.source,
                base.tolerances, base.fixture, CASES);
    }

    /** Search bounds are source-frame indices, with an exclusive stop; never cross the join. */
    public static FrameMapping frameMapping(double outputSeconds) {
        if (Double.isNaN(outputSeconds) || Double.isInfinite(outputSeconds) || outputSeconds < 0d) {
            throw new IllegalArgumentException("Nonnegative finite output timestamp required");
        }
        boolean intro = outputSeconds < 1d;
        double sourceSeconds = intro ? outputSeconds + 2d : outputSeconds - 1d;
        int nominal = (int) Math.floor(sourceSeconds * 24d + 0.001d);
        return new FrameMapping(nominal, intro ? 48 : 0, intro ? 72 : 96);
    }

    public static final class FrameMapping {
        public final int nominalSourceFrame;
        public final int firstSourceFrame;
        public final int stopSourceFrame;

        private FrameMapping(int nominalSourceFrame, int firstSourceFrame, int stopSourceFrame) {
            this.nominalSourceFrame = nominalSourceFrame;
            this.firstSourceFrame = firstSourceFrame;
            this.stopSourceFrame = stopSourceFrame;
        }
    }

    public static final class Control {
        public final String id;
        public final String caseId = CASE_ID;
        public final OracleContract.Asset asset;
        public final boolean expectedPass;
        public final List<String> requiredFailures;

        private Control(String id, OracleContract.Asset asset, boolean expectedPass, String... failures) {
            this.id = id;
            this.asset = asset;
            this.expectedPass = expectedPass;
            this.requiredFailures = Collections.unmodifiableList(Arrays.asList(failures));
        }
    }

    private static Map<String, String> assetHashes() {
        Map<String, String> result = new LinkedHashMap<>();
        for (OracleContract.Asset asset : ASSETS) result.put(asset.path, asset.sha256);
        return Collections.unmodifiableMap(result);
    }

    private static OracleContract.OracleCase buildCase() {
        OracleContract base = OracleGeneratedContract.create();
        OracleContract.OracleCase identity = base.requireCase("identity");
        List<OracleContract.Probe> probes = new ArrayList<>();
        for (int outputFrame : new int[]{0, 1, 12, 22, 23, 24, 25, 36, 48, 60, 72, 84, 96, 108, 119}) {
            int sourceFrame = outputFrame < 24 ? outputFrame + 48 : outputFrame - 24;
            probes.add(new OracleContract.Probe(sourceFrame, outputFrame / 24d,
                    identity.frameAssets.get(sourceFrame)));
        }
        List<OracleContract.AudioProbe> audio = new ArrayList<>();
        for (OracleContract.AudioProbe probe : identity.audioProbes) {
            if (probe.centerSeconds >= 2d && probe.centerSeconds < 3d) {
                audio.add(new OracleContract.AudioProbe(probe.centerSeconds - 2d, probe.windowSeconds,
                        probe.sourceSeconds, probe.frequencyHz, probe.rms));
            }
        }
        for (OracleContract.AudioProbe probe : identity.audioProbes) {
            audio.add(new OracleContract.AudioProbe(probe.centerSeconds + 1d, probe.windowSeconds,
                    probe.sourceSeconds, probe.frequencyHz, probe.rms));
        }
        return new OracleContract.OracleCase(CASE_ID, identity.operation, identity.androidEditConfig,
                identity.width, identity.height, 5d, identity.nativeFps, identity.acceptedCfrFps, 120,
                identity.videoTrackCount, identity.audioTrackCount, true,
                identity.audioSampleRate, identity.audioChannels, 240000,
                identity.barcodeRegions, probes, identity.frameAssets, audio, REFERENCE);
    }
}
