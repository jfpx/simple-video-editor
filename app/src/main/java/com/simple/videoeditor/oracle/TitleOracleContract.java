package com.simple.videoeditor.oracle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One immutable 48sp normal white OI title on black, then the unchanged standard. */
public final class TitleOracleContract {
    public static final String VERSION = "title-intro-1";
    public static final String CASE_ID = "generated_title";
    public static final String ASSET_DIRECTORY = "title-oracle";
    public static final String TEXT = "OI";
    public static final int SIZE_SP = 48, DURATION_MS = 1000;
    public static final OracleContract.Asset MANIFEST = new OracleContract.Asset(
            "manifest.json", 13039, "bae068d67a8722b7adfceaf340ff569a4c8b881c6bf413b1c60e8683f2579b24");
    public static final OracleContract.Asset REFERENCE = new OracleContract.Asset(
            "reference.mp4", 95313, "42db134614b332fc5bc56f2562e2553c121482f25fa5d66361ad1ff67562ab39");
    public static final List<Control> CONTROLS = Collections.unmodifiableList(Arrays.asList(
            new Control("title_reference", REFERENCE, true),
            new Control("title_missing", new OracleContract.Asset("missing-title.mp4", 94700,
                    "1ad754817a4aa16a942a865386f0b00c540b1d3549770c81294301442aa0c587"),
                    false, "text.probe_0.content"),
            new Control("title_wrong_text", new OracleContract.Asset("wrong-title.mp4", 95323,
                    "3656168e85a5d196e220f4e1538f84e4791f578c7210e2322243c5512c9df142"),
                    false, "text.probe_0.content"),
            // Native metadata rejection intentionally has no decoded frame-count assertion.
            new Control("title_wrong_duration", new OracleContract.Asset("wrong-duration.mp4", 98409,
                    "9d7e6c8108e27d60d2ba637d2fbdae5078e7d2e8512cd8b79c3c7d97f042b0ee"),
                    false, "video.duration"),
            new Control("title_wrong_order", new OracleContract.Asset("wrong-order.mp4", 89892,
                    "69b9423ee159be203f4f21ce92931783857db32c01927a7646a36bb00c4ee8ee"),
                    false, "text.probe_0.content", "video.temporal_barcode"),
            new Control("title_audible", new OracleContract.Asset("audible-title.mp4", 115946,
                    "e71bca724059ad329cc9e543c81c6cbffa94803075d4449db79cdd65739b3cd6"),
                    false, "audio.window_0.rms"),
            new Control("title_muted_main", new OracleContract.Asset("muted-main.mp4", 26360,
                    "916bfb91bcf6e4f23e1a1528493a0dbc840ef8eb5ccbfba8add86295072215cf"),
                    false, "audio.window_3.rms"),
            new Control("title_wrong_background", new OracleContract.Asset("wrong-background.mp4", 95783,
                    "839b89df6c24b000d802a167dd318be29553429ff55d700b18a95149dcbc9249"),
                    false, "title.probe_0.background")));
    public static final List<OracleContract.Asset> ASSETS = assets();
    public static final Map<String, String> ASSET_HASHES = hashes();

    private TitleOracleContract() {}

    public static OracleContract create() { return create(1d); }

    public static OracleContract create(double scaledDensity) {
        if (Double.isNaN(scaledDensity) || Double.isInfinite(scaledDensity)
                || scaledDensity < .75 || scaledDensity > 4) {
            throw new IllegalArgumentException("Title oracle supports scaledDensity 0.75..4 only");
        }
        OracleContract base = OracleGeneratedContract.create();
        OracleContract.OracleCase identity = base.requireCase("identity");
        Map<String, Object> config = new LinkedHashMap<>(identity.androidEditConfig);
        config.put("introTitleText", TEXT);
        config.put("introTitleSizeSp", SIZE_SP);
        config.put("introTitleStyle", "normal");
        config.put("introTitleBackground", "#FF000000");
        config.put("introTitleForeground", "#FFFFFFFF");
        config.put("introTitleX", .5);
        config.put("introTitleY", .5);
        config.put("introTitleDurationMs", DURATION_MS);
        config.put("scaledDensity", scaledDensity);
        List<OracleContract.Probe> probes = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            probes.add(new OracleContract.Probe(-1, i / 30d, identity.frameAssets.get(0)));
        }
        for (OracleContract.Probe probe : identity.probes) {
            probes.add(new OracleContract.Probe(probe.sourceFrame, probe.outputSeconds + 1,
                    probe.image));
        }
        List<OracleContract.AudioProbe> audio = new ArrayList<>();
        for (double center : new double[]{.2, .5, .8}) {
            audio.add(new OracleContract.AudioProbe(center, .12, -1, 0, 0));
        }
        for (OracleContract.AudioProbe probe : identity.audioProbes) {
            audio.add(new OracleContract.AudioProbe(probe.centerSeconds + 1, probe.windowSeconds,
                    probe.sourceSeconds, probe.frequencyHz, probe.rms));
        }
        OracleContract.OracleCase title = new OracleContract.OracleCase(CASE_ID, identity.operation, config,
                320, 240, 5, 30, Arrays.asList(24d, 30d), 126, 1, 1, true,
                48000, 1, 240000, identity.barcodeRegions, probes, identity.frameAssets, audio, REFERENCE);
        return new OracleContract(base.schema, VERSION, base.pins, base.source, base.tolerances,
                base.fixture, Collections.singletonList(title));
    }

    public static boolean applies(OracleContract contract, OracleContract.OracleCase oracleCase) {
        return VERSION.equals(contract.version) && CASE_ID.equals(oracleCase.id);
    }

    public static final class Control {
        public final String id;
        public final OracleContract.Asset asset;
        public final boolean expectedPass;
        public final List<String> requiredFailures;
        private Control(String id, OracleContract.Asset asset, boolean pass, String... failures) {
            this.id = id;
            this.asset = asset;
            expectedPass = pass;
            requiredFailures = Collections.unmodifiableList(Arrays.asList(failures));
        }
    }

    private static List<OracleContract.Asset> assets() {
        List<OracleContract.Asset> result = new ArrayList<>();
        result.add(MANIFEST);
        for (Control control : CONTROLS) result.add(control.asset);
        return Collections.unmodifiableList(result);
    }

    private static Map<String, String> hashes() {
        Map<String, String> result = new LinkedHashMap<>();
        for (OracleContract.Asset asset : ASSETS) result.put(asset.path, asset.sha256);
        return Collections.unmodifiableMap(result);
    }
}
