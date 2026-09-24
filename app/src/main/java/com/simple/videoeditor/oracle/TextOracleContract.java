package com.simple.videoeditor.oracle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bounded, font-independent O-ring/I-stem semantics; not general OCR. */
public final class TextOracleContract {
    public static final String VERSION = "text-topology-1";
    public static final String CASE_ID = "text_overlay";
    public static final String TEXT = "OI";
    public static final String ASSET_DIRECTORY = "text-oracle";
    public static final OracleContract.Asset MANIFEST = new OracleContract.Asset(
            "manifest.json", 5602, "49ce03f290749cffb70c6e18c9a81e98c6536701cb2cf9af42621e795b072136");
    public static final OracleContract.Asset REFERENCE = new OracleContract.Asset(
            "reference.mp4", 92263, "4444db739825600d839ca0c82838b47c05519dffc95de055a965b76e75c8fce2");
    public static final OracleContract.Asset WRONG_TEXT = new OracleContract.Asset(
            "wrong-text.mp4", 92028, "782ef75493b2de49601940a54c26a61d6b5d2c7233844245fabf7a29e2652e0b");
    public static final OracleContract.Asset NO_TEXT = new OracleContract.Asset(
            "no-text.mp4", 95475, "f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2");
    public static final OracleContract.Asset WRONG_POSITION = new OracleContract.Asset(
            "wrong-position.mp4", 92641, "95974fffc0217643695a2a1617fd760f3b9c897fa2ae1bdac8311d007b39aacb");
    public static final List<OracleContract.Asset> ASSETS = Collections.unmodifiableList(Arrays.asList(
            MANIFEST, REFERENCE, WRONG_TEXT, NO_TEXT, WRONG_POSITION));
    public static final Map<String, String> ASSET_HASHES = hashes();
    public static final List<OracleContract.OracleCase> CASES = Collections.singletonList(buildCase());
    public static final List<Control> CONTROLS = Collections.unmodifiableList(Arrays.asList(
            new Control("text_reference", REFERENCE, true),
            new Control("text_wrong_text", WRONG_TEXT, false, "text.probe_0.content"),
            new Control("text_omitted", NO_TEXT, false, "text.probe_0.content"),
            new Control("text_wrong_position", WRONG_POSITION, false, "text.probe_0.position")));

    private TextOracleContract() {}

    public static OracleContract create() {
        OracleContract base = OracleGeneratedContract.create();
        return new OracleContract(base.schema, VERSION, base.pins, base.source,
                base.tolerances, base.fixture, CASES);
    }

    public static boolean applies(OracleContract contract, OracleContract.OracleCase oracleCase) {
        return VERSION.equals(contract.version) && CASE_ID.equals(oracleCase.id);
    }

    // Fixed exclusion, never fitted to candidate pixels. All pixels outside it retain base tolerances.
    public static boolean excluded(int x, int y) {
        return x >= 130 && x < 190 && y >= 84 && y < 158;
    }

    public static boolean overlaps(OracleContract.Rect rect) {
        return rect.left < 190 && rect.right > 130 && rect.top < 158 && rect.bottom > 84;
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
        config.put("overlayText", TEXT);
        List<OracleContract.BarcodeRegion> barcode = new ArrayList<>();
        for (OracleContract.BarcodeRegion region : identity.barcodeRegions) {
            if (!overlaps(region.rect) && !overlaps(region.complementRect)) barcode.add(region);
        }
        // Visible low bits 0/1 uniquely identify the +/-1 alignment search; bits 5/6 retain epoch checks.
        if (barcode.size() != 4) throw new IllegalStateException("Unexpected text barcode geometry");
        return new OracleContract.OracleCase(CASE_ID, identity.operation, config,
                identity.width, identity.height, identity.durationSeconds, identity.nativeFps,
                identity.acceptedCfrFps, identity.nativeFrameCount, identity.videoTrackCount,
                identity.audioTrackCount, identity.audioRequired, identity.audioSampleRate,
                identity.audioChannels, identity.expectedDecodedAudioSamples, barcode, identity.probes,
                identity.frameAssets, identity.audioProbes, REFERENCE);
    }
}
