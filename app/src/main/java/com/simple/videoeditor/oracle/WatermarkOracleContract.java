package com.simple.videoeditor.oracle;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent finite PNG alpha/placement witness; original v1 pins remain unchanged. */
public final class WatermarkOracleContract {
    public static final String VERSION = "png-watermark-1";
    public static final String CASE_ID = "png_watermark";
    public static final String ASSET_DIRECTORY = "watermark-oracle";
    public static final OracleContract.Asset MANIFEST = new OracleContract.Asset(
            "manifest.json", 5550, "033a684143b8046355096b855672db8a7f3a7176579e0d018bbb7cb53fd67f32");
    public static final OracleContract.Asset PNG = new OracleContract.Asset(
            "watermark.png", 157, "7ba8262bd5443cdf4f25305299dc8ff0ec3e7bd5d8dba95638d9608524c2e5ef");
    public static final OracleContract.Asset REFERENCE = new OracleContract.Asset(
            "reference.mp4", 88945, "c98dea889f6fb46e843bdadccbc23f4d2e9cfb18de3c2850eb02a9bb3a084543");
    public static final OracleContract.Asset OMITTED = new OracleContract.Asset(
            "omitted.mp4", 95475, "f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2");
    public static final OracleContract.Asset MISPLACED = new OracleContract.Asset(
            "misplaced.mp4", 88859, "fc5e5cdde33f30e526be07b4525461d0afa54923579a027865ab84e8f555dc56");
    public static final OracleContract.Asset OPAQUE = new OracleContract.Asset(
            "opaque.mp4", 88255, "ce07e7234d4229ec10255357a20cb82779e49c81fb9f07cb22ea9457098fe702");
    public static final List<OracleContract.Asset> ASSETS = Collections.unmodifiableList(Arrays.asList(
            MANIFEST, PNG, REFERENCE, OMITTED, MISPLACED, OPAQUE));
    public static final Map<String, String> ASSET_HASHES = hashes();
    public static final List<OracleContract.OracleCase> CASES = Collections.singletonList(buildCase());
    public static final List<Control> CONTROLS = Collections.unmodifiableList(Arrays.asList(
            new Control("watermark_reference", REFERENCE, true),
            new Control("watermark_omitted", OMITTED, false, "watermark.probe_0.opaque_red"),
            new Control("watermark_misplaced", MISPLACED, false, "watermark.probe_0.opaque_red"),
            new Control("watermark_opaque", OPAQUE, false,
                    "watermark.probe_0.half_blue", "watermark.probe_0.transparent")));

    private WatermarkOracleContract() {}

    public static OracleContract create() {
        OracleContract base = OracleGeneratedContract.create();
        return new OracleContract(base.schema, VERSION, base.pins, base.source,
                base.tolerances, base.fixture, CASES);
    }

    public static boolean applies(OracleContract contract, OracleContract.OracleCase oracleCase) {
        return VERSION.equals(contract.version) && CASE_ID.equals(oracleCase.id);
    }

    // Fixed codec halo includes the misplaced witness; never fitted to decoded candidates.
    public static boolean excluded(int x, int y) {
        return x >= 188 && x < 268 && y >= 152 && y < 192;
    }

    public static boolean overlaps(OracleContract.Rect rect) {
        return rect.left < 268 && rect.right > 188 && rect.top < 192 && rect.bottom > 152;
    }

    public static final class Control {
        public final String id;
        public final OracleContract.Asset asset;
        public final boolean expectedPass;
        public final List<String> requiredFailures;

        private Control(String id, OracleContract.Asset asset, boolean expectedPass, String... failures) {
            this.id = id;
            this.asset = asset;
            this.expectedPass = expectedPass;
            requiredFailures = Collections.unmodifiableList(Arrays.asList(failures));
        }
    }

    private static Map<String, String> hashes() {
        Map<String, String> result = new LinkedHashMap<>();
        for (OracleContract.Asset asset : ASSETS) result.put(asset.path, asset.sha256);
        return Collections.unmodifiableMap(result);
    }

    private static OracleContract.OracleCase buildCase() {
        OracleContract.OracleCase identity = OracleGeneratedContract.create().requireCase("identity");
        Map<String, Object> config = new LinkedHashMap<>(identity.androidEditConfig);
        config.put("watermarkAsset", PNG.path);
        config.put("watermarkSha256", PNG.sha256);
        config.put("watermarkWidthFraction", .2);
        config.put("watermarkX", .75);
        config.put("watermarkY", .75);
        for (OracleContract.BarcodeRegion region : identity.barcodeRegions) {
            if (overlaps(region.rect) || overlaps(region.complementRect)) {
                throw new IllegalStateException("Watermark must preserve every barcode bit");
            }
        }
        return new OracleContract.OracleCase(CASE_ID, identity.operation, config,
                identity.width, identity.height, identity.durationSeconds, identity.nativeFps,
                identity.acceptedCfrFps, identity.nativeFrameCount, identity.videoTrackCount,
                identity.audioTrackCount, identity.audioRequired, identity.audioSampleRate,
                identity.audioChannels, identity.expectedDecodedAudioSamples, identity.barcodeRegions,
                identity.probes, identity.frameAssets, identity.audioProbes, REFERENCE);
    }
}
