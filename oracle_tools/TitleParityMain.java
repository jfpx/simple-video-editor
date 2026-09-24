package com.simple.videoeditor.oracle;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Local FFmpeg witnesses exercise the actual pure-Java core, not the native exporter. */
public final class TitleParityMain {
    public static void main(String[] args) throws Exception {
        ImageIO.setUseCache(false);
        densityChecks();
        Constructor<LocalParityMain> constructor = LocalParityMain.class.getDeclaredConstructor(File.class);
        constructor.setAccessible(true);
        Object loader = constructor.newInstance(new File("app\\src\\main\\assets\\video-oracle"));
        Method decode = LocalParityMain.class.getDeclaredMethod("decode", File.class,
                OracleContract.OracleCase.class, boolean.class);
        decode.setAccessible(true);
        Method image = LocalParityMain.class.getDeclaredMethod("loadImage", String.class);
        image.setAccessible(true);
        Method json = LocalParityMain.class.getDeclaredMethod("json", Object.class);
        json.setAccessible(true);
        OracleContract contract = TitleOracleContract.create();
        OracleContract.OracleCase title = contract.requireCase(TitleOracleContract.CASE_ID);
        File assets = new File("app\\src\\main\\assets\\title-oracle");
        File output = new File("app\\build\\title-parity");
        Files.createDirectories(output.toPath());
        for (OracleContract.Asset asset : TitleOracleContract.ASSETS) {
            byte[] bytes = Files.readAllBytes(new File(assets, asset.path).toPath());
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", b & 255));
            require(bytes.length == asset.bytes && hash.toString().equals(asset.sha256), "Pin " + asset.path);
        }
        OracleCoreVerifier.ExpectedImageProvider provider = (oracleCase, probe, frame) -> {
            try {
                return (OracleCoreVerifier.RgbImage) image.invoke(loader, oracleCase.frameAssets.get(frame).path);
            } catch (ReflectiveOperationException error) {
                throw new java.io.IOException(error);
            }
        };
        for (TitleOracleContract.Control control : TitleOracleContract.CONTROLS) {
            Map<String, Object> full = null;
            for (boolean sparse : new boolean[]{false, true}) {
                OracleCoreVerifier.Candidate c = (OracleCoreVerifier.Candidate) decode.invoke(loader,
                        new File(assets, control.asset.path), title, sparse);
                Map<String, Object> report = new OracleCoreVerifier().verify(contract, title.id, c, provider).toMap();
                Files.write(new File(output, control.id + (sparse ? "-sparse" : "-full") + ".json").toPath(),
                        ((String) json.invoke(null, report)).getBytes(StandardCharsets.UTF_8));
                require((control.expectedPass ? "PASS" : "FAIL").equals(report.get("status")),
                        control.id + ": " + report.get("failed_assertions"));
                require(!failed(report, "checker.completed_without_error"), "Checker error " + control.id);
                for (String assertion : control.requiredFailures) require(failed(report, assertion), assertion);
                if (full != null) require(full.equals(report), "Full/sparse mismatch " + control.id);
                full = report;
                if (sparse && control.expectedPass) mutations(contract, c, provider);
                if (sparse && control.requiredFailures.contains("video.duration")) {
                    OracleCoreVerifier.VideoTrack v = c.video;
                    OracleCoreVerifier.VideoTrack metadata = new OracleCoreVerifier.VideoTrack(v.codec,
                            v.sampleAspectRatio, v.width, v.height, v.rotationDegrees,
                            v.durationSeconds, Collections.emptyList());
                    Map<String, Object> gate = new OracleCoreVerifier().verify(contract, title.id,
                            candidate(c, metadata, c.audio), provider).toMap();
                    require(failed(gate, "video.duration"), "Metadata gate duration");
                    for (Map<?, ?> check : checks(gate)) require(!"video.frame_count".equals(check.get("assertion")),
                            "Metadata gate must not require unavailable frame count");
                }
            }
            System.out.println("PASS " + control.id + " expected=" + (control.expectedPass ? "PASS" : "FAIL")
                    + " full/sparse equal");
        }
        System.out.println("PASS 8 title controls, metadata-only duration gate and motion/static/density mutations; native UNVERIFIED");
    }

    private static void mutations(OracleContract contract, OracleCoreVerifier.Candidate c,
                                  OracleCoreVerifier.ExpectedImageProvider provider) throws Exception {
        for (String mutation : new String[]{"motion", "static", "density", "omitted", "inner_background", "gray_hole"}) {
            List<OracleCoreVerifier.Frame> frames = new ArrayList<>();
            for (OracleCoreVerifier.Frame f : c.video.frames) {
                OracleCoreVerifier.RgbImage rgb = f.image;
                if ("motion".equals(mutation) && f.ptsSeconds > 1 && rgb != null) {
                    rgb = new OracleCoreVerifier.RgbImage(rgb.width, rgb.height, rgb.rgb.clone());
                    // Keep barcode/timecodes but remove moving markers outside their stripe.
                    OracleCoreVerifier.RgbImage frozen = c.video.frames.get(30).image;
                    for (int y = 0; y < 240; y++) if (y < 90 || y > 125) {
                        System.arraycopy(frozen.rgb, y * 960, rgb.rgb, y * 960, 960);
                    }
                }
                if ("static".equals(mutation) && f.ptsSeconds > .4 && f.ptsSeconds < .6 && rgb != null) {
                    rgb = new OracleCoreVerifier.RgbImage(rgb.width, rgb.height, rgb.rgb.clone());
                    for (int i = 0; i < rgb.rgb.length; i++) {
                        rgb.rgb[i] = (byte) Math.min(255, (rgb.rgb[i] & 255) + 10);
                    }
                }
                if ("inner_background".equals(mutation) && f.ptsSeconds < 1 && rgb != null) {
                    rgb = new OracleCoreVerifier.RgbImage(rgb.width, rgb.height, rgb.rgb.clone());
                    for (int y = 84; y < 102; y++) for (int x = 130; x < 190; x++) {
                        int p = (y * 320 + x) * 3;
                        rgb.rgb[p] = (byte) 255;
                        rgb.rgb[p + 1] = rgb.rgb[p + 2] = 0;
                    }
                }
                if ("gray_hole".equals(mutation) && f.ptsSeconds < 1 && rgb != null) {
                    rgb = new OracleCoreVerifier.RgbImage(rgb.width, rgb.height, rgb.rgb.clone());
                    for (int y = 113; y < 129; y++) for (int x = 149; x < 158; x++) {
                        int p = (y * 320 + x) * 3;
                        rgb.rgb[p] = rgb.rgb[p + 1] = rgb.rgb[p + 2] = 80;
                    }
                }
                if (!"omitted".equals(mutation) || f.ptsSeconds >= 1) {
                    frames.add(new OracleCoreVerifier.Frame("omitted".equals(mutation) ? f.ptsSeconds - 1 : f.ptsSeconds,
                            rgb, f.barcodeLumaPairs));
                }
            }
            OracleCoreVerifier.VideoTrack v = c.video;
            if ("omitted".equals(mutation)) frames.clear();
            OracleCoreVerifier.VideoTrack changed = new OracleCoreVerifier.VideoTrack(v.codec, v.sampleAspectRatio,
                    v.width, v.height, v.rotationDegrees, "omitted".equals(mutation) ? 4 : v.durationSeconds, frames);
            Map<String, Object> report = new OracleCoreVerifier().verify(
                    "density".equals(mutation) ? TitleOracleContract.create(2) : contract,
                    TitleOracleContract.CASE_ID, candidate(c, changed, c.audio), provider).toMap();
            String key = "motion".equals(mutation) ? "video.moving_markers" : "static".equals(mutation)
                    ? "title.static" : "density".equals(mutation) ? "text.probe_0.scale"
                    : "inner_background".equals(mutation) ? "title.probe_0.inner_background"
                    : "gray_hole".equals(mutation) ? "title.probe_0.unpainted_background" : "video.duration";
            require(failed(report, key), "Mutation " + mutation + ": " + report.get("failed_assertions"));
        }
    }

    private static void densityChecks() {
        for (double density : new double[]{.75, 1, 1.5, 2, 3, 4}) {
            OracleCoreVerifier.RgbImage image = new OracleCoreVerifier.RgbImage(320, 240);
            for (int y = 0; y < 240; y++) {
                for (int x = 0; x < 320; x++) {
                    double nx = 160 + (x + .5 - 160) / density;
                    double ny = 120 + (y + .5 - 120) / density;
                    double outer = Math.pow((nx - 154) / 15, 2) + Math.pow((ny - 120.5) / 17.5, 2);
                    double inner = Math.pow((nx - 154) / 10.5, 2) + Math.pow((ny - 120.5) / 13, 2);
                    if ((outer <= 1 && inner >= 1) || (nx >= 175 && nx < 180 && ny >= 103 && ny < 138)) {
                        for (int c = 0; c < 3; c++) image.rgb[(y * 320 + x) * 3 + c] = (byte) 255;
                    }
                }
            }
            OracleCoreVerifier.Report result = new OracleCoreVerifier.Report(TitleOracleContract.CASE_ID, "analytic", null);
            TitleOracleVerifier.verify(image, TitleOracleContract.create(density).requireCase(TitleOracleContract.CASE_ID),
                    result, 0);
            require("PASS".equals(result.toMap().get("status")), "Independent density geometry " + density + ": " + result.toMap());
            if (density >= 2) {
                OracleCoreVerifier.RgbImage stripes =
                        new OracleCoreVerifier.RgbImage(image.width, image.height, image.rgb.clone());
                boolean[] sampledRows = new boolean[image.height];
                for (int y = 84; y < 158; y++) {
                    int sy = (int) Math.floor(120 + (y + .5 - 120) * density);
                    if (sy >= 0 && sy < sampledRows.length) sampledRows[sy] = true;
                }
                for (int y = 0; y < image.height; y++) {
                    if (sampledRows[y]) continue;
                    for (int x = 0; x < image.width; x++) {
                        double nx = 160 + (x + .5 - 160) / density;
                        double ny = 120 + (y + .5 - 120) / density;
                        if (nx >= 130 && nx < 190 && ny >= 84 && ny < 158) {
                            java.util.Arrays.fill(stripes.rgb, (y * image.width + x) * 3,
                                    (y * image.width + x) * 3 + 3, (byte) 255);
                        }
                    }
                }
                OracleCoreVerifier.Report corrupted =
                        new OracleCoreVerifier.Report(TitleOracleContract.CASE_ID, "unsampled-stripes", null);
                TitleOracleVerifier.verify(stripes,
                        TitleOracleContract.create(density).requireCase(TitleOracleContract.CASE_ID), corrupted, 0);
                require(failed(corrupted.toMap(), "title.probe_0.physical_background"),
                        "Unsampled stripes escaped at density " + density);
            }
        }
        for (double invalid : new double[]{0, .5, 4.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            try {
                TitleOracleContract.create(invalid);
                throw new AssertionError("Out-of-coverage density accepted");
            } catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS independent analytic density geometry at .75/1/1.5/2/3/4 and out-of-range rejection");
    }

    private static OracleCoreVerifier.Candidate candidate(OracleCoreVerifier.Candidate c,
            OracleCoreVerifier.VideoTrack v, OracleCoreVerifier.AudioTrack a) {
        return new OracleCoreVerifier.Candidate(c.path, c.sha256, c.fileBytes,
                c.videoTrackCount, c.audioTrackCount, v, a);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> checks(Map<String, Object> report) {
        return (List<Map<?, ?>>) report.get("checks");
    }

    private static boolean failed(Map<String, Object> report, String key) {
        for (Map<?, ?> check : checks(report)) {
            if (key.equals(check.get("assertion"))) return Boolean.FALSE.equals(check.get("passed"));
        }
        return false;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
