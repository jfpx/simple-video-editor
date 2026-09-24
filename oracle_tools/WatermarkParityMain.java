package com.simple.videoeditor.oracle;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** Real pure-Java checker, independent FFmpeg full/sparse decoding; never a native export claim. */
public final class WatermarkParityMain {
    public static void main(String[] args) throws Exception {
        ImageIO.setUseCache(false);
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
        OracleContract contract = WatermarkOracleContract.create();
        OracleContract.OracleCase vector = contract.requireCase(WatermarkOracleContract.CASE_ID);
        File assets = new File("app\\src\\main\\assets\\watermark-oracle");
        File output = new File("app\\build\\watermark-parity");
        Files.createDirectories(output.toPath());
        for (OracleContract.Asset asset : WatermarkOracleContract.ASSETS) {
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
        if (args.length == 2) {
            File candidateFile = new File(args[0]);
            Map<String, Object> full = null;
            for (boolean sparse : new boolean[]{false, true}) {
                OracleCoreVerifier.Candidate candidate = (OracleCoreVerifier.Candidate) decode.invoke(
                        loader, candidateFile, vector, sparse);
                Map<String, Object> report = new OracleCoreVerifier().verify(contract, vector.id, candidate, provider).toMap();
                Files.write(new File(args[1] + (sparse ? "-sparse.json" : "-full.json")).toPath(),
                        ((String) json.invoke(null, report)).getBytes(StandardCharsets.UTF_8));
                require("PASS".equals(report.get("status")), "User export: " + report.get("failed_assertions"));
                if (full != null) require(full.equals(report), "User export full/sparse mismatch");
                full = report;
            }
            System.out.println("PASS actual UI export: independent full/sparse alpha/geometry/time/audio");
            return;
        }
        require(args.length == 0, "Expected no args or candidate and report prefix");
        for (WatermarkOracleContract.Control control : WatermarkOracleContract.CONTROLS) {
            Map<String, Object> full = null;
            for (boolean sparse : new boolean[]{false, true}) {
                OracleCoreVerifier.Candidate candidate = (OracleCoreVerifier.Candidate) decode.invoke(
                        loader, new File(assets, control.asset.path), vector, sparse);
                Map<String, Object> report = new OracleCoreVerifier().verify(contract, vector.id, candidate, provider).toMap();
                Files.write(new File(output, control.id + (sparse ? "-sparse" : "-full") + ".json").toPath(),
                        ((String) json.invoke(null, report)).getBytes(StandardCharsets.UTF_8));
                require((control.expectedPass ? "PASS" : "FAIL").equals(report.get("status")),
                        control.id + ": " + report.get("failed_assertions"));
                int watermarkChecks = 0;
                for (Map<?, ?> check : checks(report)) {
                    if (check.get("assertion").toString().startsWith("watermark.")) watermarkChecks++;
                    else require(Boolean.TRUE.equals(check.get("passed")), control.id + " changed " + check);
                }
                require(watermarkChecks == 45, "Nine probes of all five alpha/color regions required");
                for (String failure : control.requiredFailures) {
                    for (int probe = 0; probe < 9; probe++) require(
                            failed(report, failure.replace("probe_0", "probe_" + probe)), control.id + " missing " + failure);
                }
                if (full != null) require(full.equals(report), "Full/sparse mismatch " + control.id);
                full = report;
                if (sparse && control.expectedPass) mutations(contract, candidate, provider);
            }
            System.out.println("PASS " + control.id + " full/sparse agree, expected=" + (control.expectedPass ? "PASS" : "FAIL"));
        }
        System.out.println("PASS watermark pins, 4 encoded controls, outside/motion/time/audio/error mutations; native UNVERIFIED");
    }

    private static void mutations(OracleContract contract, OracleCoreVerifier.Candidate c,
                                  OracleCoreVerifier.ExpectedImageProvider provider) throws Exception {
        for (String mutation : new String[]{"background", "motion", "time", "audio", "checker_error"}) {
            List<OracleCoreVerifier.Frame> frames = new ArrayList<>();
            OracleCoreVerifier.RgbImage first = c.video.frames.get(0).image;
            for (OracleCoreVerifier.Frame frame : c.video.frames) {
                OracleCoreVerifier.RgbImage changed = frame.image;
                if (changed != null) {
                    changed = new OracleCoreVerifier.RgbImage(changed.width, changed.height, changed.rgb.clone());
                    for (int y = 0; y < 240; y++) {
                        for (int x = 0; x < 320; x++) {
                            int p = (y * 320 + x) * 3;
                            if ("background".equals(mutation) && x < 100 && y < 70) {
                                changed.rgb[p] = changed.rgb[p + 1] = changed.rgb[p + 2] = 0;
                            } else if ("motion".equals(mutation) && y >= 174 && y < 194
                                    && !WatermarkOracleContract.excluded(x, y)) {
                                System.arraycopy(first.rgb, p, changed.rgb, p, 3);
                            }
                        }
                    }
                }
                frames.add(new OracleCoreVerifier.Frame("time".equals(mutation) && frames.size() == 1 ? 0 : frame.ptsSeconds,
                        changed, frame.barcodeLumaPairs));
            }
            OracleCoreVerifier.VideoTrack v = c.video;
            OracleCoreVerifier.VideoTrack video = new OracleCoreVerifier.VideoTrack(v.codec, v.sampleAspectRatio,
                    v.width, v.height, v.rotationDegrees, v.durationSeconds, frames);
            OracleCoreVerifier.AudioTrack a = c.audio;
            OracleCoreVerifier.AudioTrack audio = "audio".equals(mutation)
                    ? new OracleCoreVerifier.AudioTrack(a.codec, a.sampleRate, a.channels, a.startSeconds,
                            a.durationSeconds, a.packets, new float[a.samples.length]) : a;
            OracleCoreVerifier.ExpectedImageProvider expected = "checker_error".equals(mutation)
                    ? (vector, probe, frame) -> { throw new IllegalStateException("Injected image failure"); } : provider;
            Map<String, Object> report = new OracleCoreVerifier().verify(contract, WatermarkOracleContract.CASE_ID,
                    new OracleCoreVerifier.Candidate(c.path, c.sha256, c.fileBytes, c.videoTrackCount,
                            c.audioTrackCount, video, audio), expected).toMap();
            String assertion = "background".equals(mutation) ? "video.pixel_regions"
                    : "motion".equals(mutation) ? "video.moving_markers"
                    : "audio".equals(mutation) ? "audio.window_0.rms"
                    : "time".equals(mutation) ? "video.pts_monotonic" : "checker.completed_without_error";
            require(("checker_error".equals(mutation) ? "ERROR" : "FAIL").equals(report.get("status"))
                    && failed(report, assertion), mutation + " escaped: " + report.get("failed_assertions"));
            System.out.println("PASS mutation " + mutation + " rejected by " + assertion);
        }
        try {
            new OracleCoreVerifier().verify(contract, WatermarkOracleContract.CASE_ID, c,
                    (vector, probe, frame) -> { throw new java.io.IOException("Injected I/O failure"); });
            throw new AssertionError("I/O failure returned a negative success");
        } catch (java.io.IOException expected) {
            require("Injected I/O failure".equals(expected.getMessage()), "Unexpected I/O failure");
        }
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
