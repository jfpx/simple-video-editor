package com.simple.videoeditor.oracle;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Same pure-Java production core, independent FFmpeg decoding, full/sparse agreement. */
public final class TextParityMain {
    public static void main(String[] args) throws Exception {
        ImageIO.setUseCache(false);
        verifyBackingAtEveryAllowedGap();
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
        OracleContract contract = TextOracleContract.create();
        OracleContract.OracleCase text = contract.requireCase(TextOracleContract.CASE_ID);
        File assets = new File("app\\src\\main\\assets\\text-oracle");
        File output = new File("app\\build\\text-parity");
        Files.createDirectories(output.toPath());
        for (OracleContract.Asset asset : TextOracleContract.ASSETS) {
            byte[] bytes = Files.readAllBytes(new File(assets, asset.path).toPath());
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hash.append(String.format("%02x", b & 255));
            require(bytes.length == asset.bytes && hash.toString().equals(asset.sha256), "Pin mismatch " + asset.path);
        }
        OracleCoreVerifier.ExpectedImageProvider provider = (oracleCase, probe, frame) -> {
            try {
                return (OracleCoreVerifier.RgbImage) image.invoke(loader, oracleCase.frameAssets.get(frame).path);
            } catch (ReflectiveOperationException error) {
                throw new java.io.IOException(error);
            }
        };
        List<Map<String, Object>> results = new ArrayList<>();
        for (TextOracleContract.Control control : TextOracleContract.CONTROLS) {
            Map<String, Object> full = null;
            for (boolean sparse : new boolean[]{false, true}) {
                OracleCoreVerifier.Candidate candidate = (OracleCoreVerifier.Candidate) decode.invoke(
                        loader, new File(assets, control.asset.path), text, sparse);
                Map<String, Object> report = new OracleCoreVerifier().verify(contract, text.id, candidate, provider).toMap();
                Files.write(new File(output, control.id + (sparse ? "-sparse" : "-full") + ".json").toPath(),
                        ((String) json.invoke(null, report)).getBytes(StandardCharsets.UTF_8));
                require((control.expectedPass ? "PASS" : "FAIL").equals(report.get("status")),
                        control.id + ": " + report.get("failed_assertions"));
                for (String assertion : control.requiredFailures) require(failed(report, assertion), "Missing " + assertion);
                // Every non-text assertion must pass, including background, motion and audio.
                for (Map<?, ?> check : checks(report)) {
                    String assertion = check.get("assertion").toString();
                    if (!assertion.startsWith("text.")) {
                        require(Boolean.TRUE.equals(check.get("passed")), control.id + " changed " + assertion);
                    }
                }
                for (String failure : control.requiredFailures) {
                    for (int probe = 0; probe < 9; probe++) require(
                            failed(report, failure.replace("probe_0", "probe_" + probe)),
                            control.id + ": missing semantic failure at probe " + probe);
                }
                if (full != null) require(full.equals(report), "Full/sparse mismatch " + control.id);
                full = report;
                if (control.expectedPass && sparse) {
                    metadataGate(contract, candidate, provider);
                    mutations(contract, candidate, provider);
                }
            }
            results.add(full);
            System.out.println("PASS " + control.id + " expected=" + (control.expectedPass ? "PASS" : "FAIL")
                    + " full/sparse equal");
        }
        Files.write(new File(output, "results.json").toPath(),
                ((String) json.invoke(null, results)).getBytes(StandardCharsets.UTF_8));
        System.out.println("PASS text pins, 4 controls, metadata-only gate and content/audio/motion mutations; native UNVERIFIED");
    }

    private static void verifyBackingAtEveryAllowedGap() {
        for (int gap = 4; gap <= 13; gap++) {
            for (boolean correctBacking : new boolean[]{true, false}) {
                byte[] background = new byte[320 * 240 * 3];
                byte[] pixels = new byte[background.length];
                Arrays.fill(background, (byte) 100);
                Arrays.fill(pixels, (byte) (correctBacking ? 40 : 100));
                int left = (320 - (29 + gap - 1 + 5)) / 2;
                int stem = left + 28 + gap;
                for (int y = 102; y < 137; y++) {
                    for (int x = left; x < stem + 5; x++) {
                        double outer = Math.pow((x - left - 14) / 14.5, 2)
                                + Math.pow((y - 119) / 17.5, 2);
                        double inner = Math.pow((x - left - 14) / 10.5, 2)
                                + Math.pow((y - 119) / 13.5, 2);
                        if ((x < left + 29 && outer <= 1 && inner >= 1) || x >= stem) {
                            int p = (y * 320 + x) * 3;
                            Arrays.fill(pixels, p, p + 3, (byte) 255);
                        }
                    }
                }
                OracleCoreVerifier.Report result = new OracleCoreVerifier.Report("text_overlay", "analytic", null);
                TextOracleVerifier.verify(new OracleCoreVerifier.RgbImage(320, 240, pixels),
                        new OracleCoreVerifier.RgbImage(320, 240, background), result, 0);
                Map<String, Object> report = result.toMap();
                for (Map<?, ?> check : checks(report)) {
                    boolean backing = "text.probe_0.backing".equals(check.get("assertion"));
                    require(Boolean.valueOf(!backing || correctBacking).equals(check.get("passed")),
                            "Gap " + gap + ", backing=" + correctBacking + ": " + report);
                }
            }
        }
        System.out.println("PASS all 10 allowed glyph gaps: correct backing accepted, missing backing rejected");
    }

    private static void metadataGate(OracleContract contract, OracleCoreVerifier.Candidate c,
                                     OracleCoreVerifier.ExpectedImageProvider provider) throws Exception {
        OracleCoreVerifier.VideoTrack v = c.video;
        OracleCoreVerifier.VideoTrack metadata = new OracleCoreVerifier.VideoTrack(v.codec, v.sampleAspectRatio,
                v.width, v.height, v.rotationDegrees, 3d, Collections.emptyList());
        Map<String, Object> report = new OracleCoreVerifier().verify(contract, TextOracleContract.CASE_ID,
                candidate(c, metadata, c.audio), provider).toMap();
        require(failed(report, "video.duration"), "Duration gate must fail");
        for (Map<?, ?> check : checks(report)) require(!"video.frame_count".equals(check.get("assertion")),
                "No decoded frame count on metadata-only failure");
    }

    private static void mutations(OracleContract contract, OracleCoreVerifier.Candidate c,
                                  OracleCoreVerifier.ExpectedImageProvider provider) throws Exception {
        // Deliberate damage to candidates only; expected images always remain frozen independent assets.
        for (String mutation : Arrays.asList("closed_hole", "red_text", "background", "frozen_motion", "audio")) {
            List<OracleCoreVerifier.Frame> frames = new ArrayList<>();
            OracleCoreVerifier.RgbImage first = c.video.frames.get(0).image;
            for (OracleCoreVerifier.Frame frame : c.video.frames) {
                OracleCoreVerifier.RgbImage changed = frame.image;
                if (changed != null) {
                    changed = new OracleCoreVerifier.RgbImage(changed.width, changed.height, changed.rgb.clone());
                    for (int y = 0; y < changed.height; y++) {
                        for (int x = 0; x < changed.width; x++) {
                            int p = (y * changed.width + x) * 3;
                            if ("closed_hole".equals(mutation) && x >= 145 && x < 164 && y >= 109 && y < 132) {
                                Arrays.fill(changed.rgb, p, p + 3, (byte) 255);
                            } else if ("red_text".equals(mutation) && TextOracleContract.excluded(x, y)
                                    && (changed.rgb[p] & 255) > 185 && (changed.rgb[p + 1] & 255) > 185) {
                                changed.rgb[p + 1] = changed.rgb[p + 2] = 0;
                            } else if ("background".equals(mutation) && x < 100 && y < 70) {
                                Arrays.fill(changed.rgb, p, p + 3, (byte) 0);
                            } else if ("frozen_motion".equals(mutation) && y >= 174 && y < 194) {
                                System.arraycopy(first.rgb, p, changed.rgb, p, 3);
                            }
                        }
                    }
                }
                frames.add(new OracleCoreVerifier.Frame(frame.ptsSeconds, changed, frame.barcodeLumaPairs));
            }
            OracleCoreVerifier.VideoTrack v = c.video;
            OracleCoreVerifier.VideoTrack video = new OracleCoreVerifier.VideoTrack(v.codec, v.sampleAspectRatio,
                    v.width, v.height, v.rotationDegrees, v.durationSeconds, frames);
            OracleCoreVerifier.AudioTrack a = c.audio;
            OracleCoreVerifier.AudioTrack audio = "audio".equals(mutation)
                    ? new OracleCoreVerifier.AudioTrack(a.codec, a.sampleRate, a.channels, a.startSeconds,
                            a.durationSeconds, a.packets, new float[a.samples.length]) : a;
            Map<String, Object> report = new OracleCoreVerifier().verify(contract, TextOracleContract.CASE_ID,
                    candidate(c, video, audio), provider).toMap();
            String assertion = "audio".equals(mutation) ? "audio.window_0.rms"
                    : "frozen_motion".equals(mutation) ? "video.moving_markers"
                    : "background".equals(mutation) ? "video.pixel_regions" : "text.probe_0.content";
            require("FAIL".equals(report.get("status")) && failed(report, assertion),
                    "Mutation escaped: " + mutation + " " + report.get("failed_assertions"));
            System.out.println("PASS mutation " + mutation + " rejected by " + assertion);
        }
    }

    private static OracleCoreVerifier.Candidate candidate(OracleCoreVerifier.Candidate c,
            OracleCoreVerifier.VideoTrack video, OracleCoreVerifier.AudioTrack audio) {
        return new OracleCoreVerifier.Candidate(c.path, c.sha256, c.fileBytes, c.videoTrackCount,
                c.audioTrackCount, video, audio);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<?, ?>> checks(Map<String, Object> report) {
        return (List<Map<?, ?>>) report.get("checks");
    }

    private static boolean failed(Map<String, Object> report, String assertion) {
        for (Map<?, ?> check : checks(report)) {
            if (assertion.equals(check.get("assertion"))) return Boolean.FALSE.equals(check.get("passed"));
        }
        return false;
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
