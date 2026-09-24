package com.simple.videoeditor.oracle;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class LocalParityMain {
    private final OracleContract contract = OracleGeneratedContract.create();
    private final OracleCoreVerifier verifier = new OracleCoreVerifier();
    private final File oracleRoot;
    private final File assetRoot = new File("app\\src\\main\\assets\\video-oracle");
    private final List<Map<String, Object>> controls = new ArrayList<Map<String, Object>>();
    private final Map<String, Object> checks = new LinkedHashMap<String, Object>();
    private final OracleCoreVerifier.ExpectedImageProvider imageProvider =
            new OracleCoreVerifier.ExpectedImageProvider() {
                @Override
                public OracleCoreVerifier.RgbImage load(OracleContract.OracleCase oracleCase,
                                                        OracleContract.Probe probe,
                                                        int matchedSourceFrame) throws IOException {
                    OracleContract.Asset frameAsset = oracleCase.frameAssets.get(Integer.valueOf(matchedSourceFrame));
                    if (frameAsset != null) {
                        return loadImage(frameAsset.path);
                    }
                    if (matchedSourceFrame == probe.sourceFrame) {
                        return loadImage(probe.image.path);
                    }
                    throw new IOException("Missing frozen expectation: " + oracleCase.id
                            + " source frame " + matchedSourceFrame);
                }
            };

    private LocalParityMain(File oracleRoot) {
        this.oracleRoot = oracleRoot;
    }

    public static void main(String[] args) throws Exception {
        ImageIO.setUseCache(false);
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected oracle root path");
        }
        new LocalParityMain(new File(args[0])).run();
    }

    private void run() throws Exception {
        check("probe_assets", () -> {
            validateProbeAssets();
            return Collections.singletonMap("authority", "hash-checked lossless assets; no rerender fallback");
        });
        Map<String, List<String>> noopAssertions = new LinkedHashMap<String, List<String>>();
        noopAssertions.put("crop", Arrays.asList("video.geometry"));
        noopAssertions.put("rotate90", Arrays.asList("video.geometry"));
        noopAssertions.put("rotate180", Arrays.asList("video.pixel_regions"));
        noopAssertions.put("rotate270", Arrays.asList("video.geometry"));
        noopAssertions.put("trim", Arrays.asList("video.duration", "video.temporal_barcode", "audio.window_0.frequency"));
        noopAssertions.put("resize", Arrays.asList("video.geometry"));
        noopAssertions.put("mute", Arrays.asList("audio.stream_count"));
        noopAssertions.put("volume25", Arrays.asList("audio.window_0.rms"));
        noopAssertions.put("speed2", Arrays.asList("video.duration"));
        noopAssertions.put("speed_half", Arrays.asList("video.duration"));
        noopAssertions.put("combo", Arrays.asList("video.geometry", "video.duration"));

        check("pcm_conversions", () -> {
            verifySharedPcmConversions();
            return Collections.singletonMap("encodings", Arrays.asList("pcm16_stereo", "float_stereo"));
        });

        int positivePasses = 0;
        for (OracleContract.OracleCase oracleCase : contract.cases) {
            File reference = new File(oracleRoot, oracleCase.reference.path);
            control("reference_" + oracleCase.id, reference, oracleCase.id, true, Collections.<String>emptyList());
            positivePasses++;
        }
        control("fixture_identity", new File(oracleRoot, contract.fixture.path), "identity",
                true, Collections.<String>emptyList());
        positivePasses++;
        positivePasses += requirePositive("stress\\rotate90_crf28.mp4", "rotate90");
        positivePasses += requirePositive("stress\\combo_crf28.mp4", "combo");
        positivePasses += requirePositive("stress\\speed2_cfr24.mp4", "speed2");
        positivePasses += requirePositive("stress\\speed_half_cfr24.mp4", "speed_half");

        int negativePasses = 0;
        File fixture = new File(oracleRoot, contract.fixture.path);
        for (Map.Entry<String, List<String>> entry : noopAssertions.entrySet()) {
            control("noop_" + entry.getKey(), fixture, entry.getKey(), false, entry.getValue());
            negativePasses++;
        }

        negativePasses += requireNegative("wrong_crop_same_dimensions.mp4", "crop",
                Arrays.asList("video.pixel_regions"));
        negativePasses += requireNegative("wrong_rotation_same_dimensions.mp4", "rotate90",
                Arrays.asList("video.temporal_barcode", "video.pixel_regions"));
        negativePasses += requireNegative("wrong_trim_same_duration.mp4", "trim",
                Arrays.asList("video.temporal_barcode"));
        negativePasses += requireNegative("wrong_volume_half_not_quarter.mp4", "volume25",
                Arrays.asList("audio.window_0.rms"));
        negativePasses += requireNegative("speed_changes_pitch.mp4", "speed2",
                Arrays.asList("audio.window_0.frequency"));
        negativePasses += requireNegative("frozen_video_correct_duration.mp4", "identity",
                Arrays.asList("video.temporal_barcode"));
        negativePasses += requireNegative("frozen_markers_advancing_barcode.mp4", "identity",
                Arrays.asList("video.moving_markers"));
        negativePasses += requireNegative("audio_timestamp_gap.mp4", "identity",
                Arrays.asList("audio.pts_continuity"));

        regressionChecks();
        check("encoded_control_counts", () -> {
            require(controls.size() == 36
                    && controls.stream().filter(c -> "positive".equals(c.get("kind"))).count() == 17
                    && controls.stream().filter(c -> "negative".equals(c.get("kind"))).count() == 19,
                    "Expected 17 positive and 19 negative controls");
            return Collections.singletonMap("total", controls.size());
        });
        check("encoded_full_sparse_parity", () -> {
            for (Map<String, Object> control : controls) {
                require(Boolean.TRUE.equals(control.get("equivalent"))
                        && Boolean.TRUE.equals(control.get("sparse_storage_verified")),
                        "Full/sparse report or storage mismatch: " + control.get("id"));
            }
            return object("equal_reports", controls.size(), "verified_sparse_storage", controls.size());
        });
        boolean passed = controls.stream().allMatch(c -> Boolean.TRUE.equals(c.get("passed")))
                && checks.values().stream().allMatch(c -> Boolean.TRUE.equals(((Map<?, ?>) c).get("passed")));
        Map<String, Object> report = object("schema_version", 1, "passed", passed,
                "controls", controls, "checks", checks,
                "summary", object("positive", positivePasses, "negative", negativePasses));
        System.out.println(json(report));
        if (!passed) {
            System.exit(1);
        }
    }

    private int requireNegative(String fileName, String caseId, List<String> prefixes) throws Exception {
        File file = new File(new File(oracleRoot, "negative"), fileName);
        control("negative_" + fileName, file, caseId, false, prefixes);
        return 1;
    }

    private int requirePositive(String relativePath, String caseId) throws Exception {
        File file = new File(oracleRoot, relativePath);
        control(relativePath, file, caseId, true, Collections.<String>emptyList());
        return 1;
    }

    private boolean hasFailures(OracleCoreVerifier.Report report, List<String> prefixes) {
        @SuppressWarnings("unchecked")
        List<String> failures = (List<String>) report.toMap().get("failed_assertions");
        for (String prefix : prefixes) {
            boolean matched = false;
            for (String failure : failures) {
                if (failure.startsWith(prefix)) {
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private void control(String id, File file, String caseId, boolean expected, List<String> prefixes) {
        Map<String, Object> result = object("id", id, "kind", expected ? "positive" : "negative",
                "case_id", caseId, "input", file.getAbsolutePath(),
                "expected", object("passed", expected, "failure_prefixes", prefixes));
        controls.add(result);
        OracleCoreVerifier.Candidate full = null;
        OracleCoreVerifier.Candidate sparse = null;
        for (boolean sparseMode : new boolean[]{false, true}) {
            Map<String, Object> outcome = object("storage", object("mode", sparseMode ? "sparse" : "full"),
                    "expected_outcome_matched", false);
            result.put(sparseMode ? "sparse" : "full", outcome);
            try {
                OracleCoreVerifier.Candidate candidate = decode(file, contract.requireCase(caseId), sparseMode);
                if (sparseMode) {
                    sparse = candidate;
                } else {
                    full = candidate;
                }
                outcome.put("storage", storage(candidate));
                OracleCoreVerifier.Report core = verifier.verify(contract, caseId, candidate, imageProvider);
                outcome.put("report", core.toMap());
                outcome.put("expected_outcome_matched", core.passed() == expected
                        && (expected ? "PASS" : "FAIL").equals(core.toMap().get("status"))
                        && hasFailures(core, prefixes));
            } catch (Exception error) {
                outcome.put("error", errorDetails(error));
            }
        }
        Map<?, ?> fullOutcome = (Map<?, ?>) result.get("full");
        Map<?, ?> sparseOutcome = (Map<?, ?>) result.get("sparse");
        boolean equivalent = fullOutcome.get("report") != null
                && fullOutcome.get("report").equals(sparseOutcome.get("report"));
        result.put("equivalent", equivalent);
        boolean storagePassed = false;
        try {
            assertSparseStorage(contract.requireCase(caseId), full, sparse);
            storagePassed = true;
        } catch (Exception error) {
            result.put("error", errorDetails(error));
        }
        result.put("sparse_storage_verified", storagePassed);
        result.put("passed", equivalent && storagePassed
                && Boolean.TRUE.equals(fullOutcome.get("expected_outcome_matched"))
                && Boolean.TRUE.equals(sparseOutcome.get("expected_outcome_matched"))
                && !fullOutcome.containsKey("error") && !sparseOutcome.containsKey("error"));
    }

    private static Map<String, Object> storage(OracleCoreVerifier.Candidate candidate) {
        int images = 0;
        int barcodes = 0;
        long rgbBytes = 0;
        List<OracleCoreVerifier.Frame> frames = candidate.video == null
                ? Collections.<OracleCoreVerifier.Frame>emptyList() : candidate.video.frames;
        for (OracleCoreVerifier.Frame frame : frames) {
            if (frame.image != null) {
                images++;
                rgbBytes += frame.image.rgb.length;
            }
            if (frame.barcodeLumaPairs != null) {
                barcodes++;
            }
        }
        return object("frames", frames.size(), "rgb_frames", images, "rgb_bytes", rgbBytes,
                "barcode_frames", barcodes, "omitted_rgb_frames", frames.size() - images);
    }

    private static void assertSparseStorage(OracleContract.OracleCase oracleCase,
                                            OracleCoreVerifier.Candidate full,
                                            OracleCoreVerifier.Candidate sparse) {
        require(full != null && sparse != null && full.video != null && sparse.video != null,
                "Both decoded tracks are required");
        List<OracleCoreVerifier.Frame> a = full.video.frames;
        List<OracleCoreVerifier.Frame> b = sparse.video.frames;
        require(a.size() == b.size(), "Sparse frame count differs");
        List<Double> pts = new ArrayList<Double>();
        for (OracleCoreVerifier.Frame frame : a) {
            pts.add(frame.ptsSeconds);
        }
        boolean[] retained = probeFrames(pts, oracleCase);
        int omitted = 0;
        for (int i = 0; i < a.size(); i++) {
            require(a.get(i).image != null && a.get(i).barcodeLumaPairs == null,
                    "Full mode must exercise RGB barcode extraction");
            require(a.get(i).ptsSeconds == b.get(i).ptsSeconds, "Sparse PTS differs");
            require((b.get(i).image != null) == retained[i], "Nonprobe RGB retained or probe RGB missing");
            require(Arrays.deepEquals(barcodeSamples(a.get(i).image, oracleCase), b.get(i).barcodeLumaPairs),
                    "Sparse barcode must come from every decoded frame");
            if (retained[i]) {
                require(Arrays.equals(a.get(i).image.rgb, b.get(i).image.rgb), "Sparse probe RGB differs");
            } else {
                omitted++;
            }
        }
        require(omitted > 0, "Sparse mode did not omit any RGB");
    }

    private void validateProbeAssets() throws IOException {
        require(assetRoot.isDirectory(), "Missing asset root " + assetRoot.getAbsolutePath());
        for (OracleContract.OracleCase oracleCase : contract.cases) {
            for (OracleContract.Probe probe : oracleCase.probes) {
                File image = new File(assetRoot, probe.image.path);
                require(image.isFile(), "Missing probe image " + probe.image.path);
                require(image.length() == probe.image.bytes, "Probe length mismatch " + probe.image.path);
                require(sha256(image).equals(probe.image.sha256), "Probe hash mismatch " + probe.image.path);
            }
            for (OracleContract.Asset asset : oracleCase.frameAssets.values()) {
                File image = new File(assetRoot, asset.path);
                require(image.isFile(), "Missing full frame " + asset.path);
                require(image.length() == asset.bytes, "Frame length mismatch " + asset.path);
                require(sha256(image).equals(asset.sha256), "Frame hash mismatch " + asset.path);
            }
        }
    }

    private OracleCoreVerifier.Candidate decode(File file, OracleContract.OracleCase oracleCase,
                                                boolean sparse) throws Exception {
        long bytes = file.isFile() ? file.length() : 0L;
        if (!file.isFile()) {
            return new OracleCoreVerifier.Candidate(file.getAbsolutePath(), null, bytes, 0, 0, null, null);
        }
        TrackCounts counts = probeTrackCounts(file);
        StreamInfo videoInfo = probeStream(file, "v:0");
        StreamInfo audioInfo = probeStream(file, "a:0");
        List<OracleCoreVerifier.Frame> frames = decodeVideo(file, videoInfo, oracleCase, sparse);
        OracleCoreVerifier.VideoTrack video = videoInfo == null ? null : new OracleCoreVerifier.VideoTrack(
                videoInfo.codec, videoInfo.sampleAspectRatio, videoInfo.width, videoInfo.height,
                0, videoInfo.durationSeconds, frames);
        OracleCoreVerifier.AudioTrack audio = audioInfo == null ? null : new OracleCoreVerifier.AudioTrack(
                audioInfo.codec, audioInfo.sampleRate, audioInfo.channels, audioInfo.startSeconds,
                audioInfo.durationSeconds, audioPackets(file), decodeAudio(file));
        return new OracleCoreVerifier.Candidate(file.getAbsolutePath(), sha256(file), bytes,
                counts.videoTracks, counts.audioTracks, video, audio);
    }

    private List<OracleCoreVerifier.Frame> decodeVideo(File file, StreamInfo info,
                                                      OracleContract.OracleCase oracleCase,
                                                      boolean sparse) throws Exception {
        if (info == null) {
            return new ArrayList<OracleCoreVerifier.Frame>();
        }
        List<Double> pts = framePts(file, "v:0");
        require(pts.size() <= 600 && info.width > 0 && info.height > 0
                && (long) info.width * info.height <= 1920 * 1080, "Unbounded decoded video");
        boolean[] retained = probeFrames(pts, oracleCase);
        return run(new String[]{
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", file.getAbsolutePath(), "-map", "0:v:0", "-an", "-fps_mode", "passthrough",
                "-frames:v", "600", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1"
        }, input -> {
            DataInputStream raw = new DataInputStream(input);
            ArrayList<OracleCoreVerifier.Frame> frames = new ArrayList<OracleCoreVerifier.Frame>(pts.size());
            for (int i = 0; i < pts.size(); i++) {
                byte[] rgb = new byte[info.width * info.height * 3];
                raw.readFully(rgb);
                OracleCoreVerifier.RgbImage image = new OracleCoreVerifier.RgbImage(info.width, info.height, rgb);
                double[][] pairs = sparse ? barcodeSamples(image, oracleCase) : null;
                frames.add(new OracleCoreVerifier.Frame(pts.get(i), !sparse || retained[i] ? image : null, pairs));
            }
            require(raw.read() == -1, "Unexpected extra RGB bytes: " + file.getName());
            return frames;
        });
    }

    private static boolean[] probeFrames(List<Double> pts, OracleContract.OracleCase oracleCase) {
        boolean[] retained = new boolean[pts.size()];
        for (OracleContract.Probe probe : oracleCase.probes) {
            int nearest = -1;
            double distance = Double.MAX_VALUE;
            for (int i = 0; i < pts.size(); i++) {
                double delta = Math.abs(pts.get(i) - probe.outputSeconds);
                if (delta < distance) {
                    nearest = i;
                    distance = delta;
                }
            }
            if (nearest >= 0) {
                retained[nearest] = true;
            }
        }
        return retained;
    }

    private static double[][] barcodeSamples(OracleCoreVerifier.RgbImage image,
                                             OracleContract.OracleCase oracleCase) {
        double[][] pairs = new double[oracleCase.barcodeRegions.size()][2];
        for (int i = 0; i < pairs.length; i++) {
            OracleContract.BarcodeRegion region = oracleCase.barcodeRegions.get(i);
            pairs[i][0] = meanLuma(image, region.rect);
            pairs[i][1] = meanLuma(image, region.complementRect);
        }
        return pairs;
    }

    private static double meanLuma(OracleCoreVerifier.RgbImage image, OracleContract.Rect rect) {
        // Geometry negatives are rejected before barcode evaluation; clamp only for their diagnostics.
        int left = Math.min(rect.left, image.width - 1);
        int top = Math.min(rect.top, image.height - 1);
        int right = Math.min(rect.right, image.width);
        int bottom = Math.min(rect.bottom, image.height);
        double[] sum = new double[3];
        int pixels = (right - left) * (bottom - top);
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                int offset = (y * image.width + x) * 3;
                for (int channel = 0; channel < 3; channel++) {
                    sum[channel] += image.rgb[offset + channel] & 0xff;
                }
            }
        }
        return (sum[0] / pixels + sum[1] / pixels + sum[2] / pixels) / 3d;
    }

    private float[] decodeAudio(File file) throws Exception {
        byte[] raw = run(new String[]{
                "ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin", "-y",
                "-i", file.getAbsolutePath(), "-map", "0:a:0", "-vn", "-ac", "1", "-ar", "48000",
                "-t", "12", "-f", "f32le", "pipe:1"
        });
        return OraclePcmUtils.decodeToMono(ByteBuffer.wrap(raw), OraclePcmUtils.ENCODING_PCM_FLOAT, 1);
    }

    private List<OracleCoreVerifier.AudioPacket> audioPackets(File file) throws Exception {
        ArrayList<OracleCoreVerifier.AudioPacket> packets = new ArrayList<OracleCoreVerifier.AudioPacket>();
        for (String line : runLines(new String[]{
                "ffprobe", "-v", "error", "-select_streams", "a:0",
                "-show_entries", "frame=best_effort_timestamp_time,nb_samples",
                "-of", "csv=p=0", file.getAbsolutePath()
        })) {
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] parts = line.split(",");
            if (parts.length < 2) {
                continue;
            }
            packets.add(new OracleCoreVerifier.AudioPacket(
                    Double.parseDouble(parts[0].trim()),
                    Integer.parseInt(parts[1].trim())));
        }
        return packets;
    }

    private List<Double> framePts(File file, String stream) throws Exception {
        ArrayList<Double> values = new ArrayList<Double>();
        for (String line : runLines(new String[]{
                "ffprobe", "-v", "error", "-select_streams", stream,
                "-show_entries", "frame=best_effort_timestamp_time",
                "-of", "default=nw=1:nk=1", file.getAbsolutePath()
        })) {
            if (!line.trim().isEmpty()) {
                values.add(Double.parseDouble(line.trim()));
            }
        }
        return values;
    }

    private StreamInfo probeStream(File file, String stream) throws Exception {
        Map<String, String> values = keyValues(runLines(new String[]{
                "ffprobe", "-v", "error", "-select_streams", stream,
                "-show_entries", "stream=codec_name,width,height,sample_aspect_ratio,duration,sample_rate,channels,start_time",
                "-of", "default=nw=1", file.getAbsolutePath()
        }));
        if (values.isEmpty()) {
            return null;
        }
        if ("v:0".equals(stream)) {
            return new StreamInfo(values.get("codec_name"), parseInt(values.get("width"), 0),
                    parseInt(values.get("height"), 0), value(values, "sample_aspect_ratio", "1:1"),
                    parseDouble(values.get("duration"), 0d), 0, 0,
                    parseDouble(values.get("start_time"), 0d));
        }
        return new StreamInfo(values.get("codec_name"), 0, 0, "1:1", parseDouble(values.get("duration"), 0d),
                parseInt(values.get("sample_rate"), 48000), parseInt(values.get("channels"), 1),
                parseDouble(values.get("start_time"), 0d));
    }

    private TrackCounts probeTrackCounts(File file) throws Exception {
        int videos = 0;
        int audios = 0;
        for (String line : runLines(new String[]{
                "ffprobe", "-v", "error", "-show_entries", "stream=codec_type",
                "-of", "default=nw=1:nk=1", file.getAbsolutePath()
        })) {
            if ("video".equals(line.trim())) {
                videos++;
            } else if ("audio".equals(line.trim())) {
                audios++;
            }
        }
        return new TrackCounts(videos, audios);
    }

    private static OracleCoreVerifier.RgbImage fromBufferedImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] rgb = new byte[width * height * 3];
        int[] argb = new int[width * height];
        image.getRGB(0, 0, width, height, argb, 0, width);
        for (int i = 0; i < argb.length; i++) {
            int color = argb[i];
            rgb[i * 3] = (byte) ((color >> 16) & 0xFF);
            rgb[i * 3 + 1] = (byte) ((color >> 8) & 0xFF);
            rgb[i * 3 + 2] = (byte) (color & 0xFF);
        }
        return new OracleCoreVerifier.RgbImage(width, height, rgb);
    }

    private OracleCoreVerifier.RgbImage loadImage(String relativePath) throws IOException {
        BufferedImage image = ImageIO.read(new File(assetRoot, relativePath));
        if (image == null) {
            throw new IOException("Unable to load " + relativePath);
        }
        return fromBufferedImage(image);
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static double parseDouble(String value, double fallback) {
        try {
            return value == null ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException error) {
            return fallback;
        }
    }

    private static Map<String, String> keyValues(String[] lines) {
        LinkedHashMap<String, String> map = new LinkedHashMap<String, String>();
        for (String line : lines) {
            int split = line.indexOf('=');
            if (split > 0) {
                map.put(line.substring(0, split), line.substring(split + 1));
            }
        }
        return map;
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    private static byte[] run(String[] command) throws Exception {
        return run(command, LocalParityMain::readFully);
    }

    private interface OutputReader<T> {
        T read(InputStream input) throws Exception;
    }

    private static <T> T run(String[] command, OutputReader<T> reader) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().remove("FFREPORT");
        Process process = builder.start();
        ExecutorService drains = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "oracle-process-output");
            thread.setDaemon(true);
            return thread;
        });
        Future<T> output = drains.submit(() -> reader.read(process.getInputStream()));
        Future<byte[]> errors = drains.submit(() -> readFully(process.getErrorStream()));
        try {
            process.getOutputStream().close();
            T result = output.get(60, TimeUnit.SECONDS);
            require(process.waitFor(5, TimeUnit.SECONDS), "Process did not exit: " + Arrays.toString(command));
            byte[] stderr = errors.get(5, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                throw new IOException("Process exit code " + process.exitValue() + "\n"
                        + new String(stderr, StandardCharsets.UTF_8));
            }
            return result;
        } catch (Exception error) {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
            String stderr = "";
            try {
                stderr = new String(errors.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8);
            } catch (Exception diagnosticError) {
                stderr = "Unable to collect stderr: " + errorDetails(diagnosticError);
            }
            throw new IOException("Command failed or timed out: " + Arrays.toString(command)
                    + "\n" + errorDetails(error) + "\nstderr:\n" + stderr, error);
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            output.cancel(true);
            errors.cancel(true);
            drains.shutdownNow();
            process.getInputStream().close();
            process.getErrorStream().close();
        }
    }

    private static String[] runLines(String[] command) throws Exception {
        String output = new String(run(command), StandardCharsets.UTF_8).replace("\r", "");
        output = output.trim();
        return output.isEmpty() ? new String[0] : output.split("\n");
    }

    private static byte[] readFully(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String sha256(File file) throws IOException {
        try {
            return bytesToHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(file.toPath())));
        } catch (java.security.NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private void check(String name, Callable<Map<String, Object>> body) {
        Map<String, Object> result = object("passed", false);
        checks.put(name, result);
        try {
            result.put("diagnostics", body.call());
            result.put("passed", true);
        } catch (Exception error) {
            result.put("error", errorDetails(error));
        }
    }

    private static String errorDetails(Throwable error) {
        StringBuilder details = new StringBuilder(error.toString());
        for (Throwable cause = error.getCause(); cause != null; cause = cause.getCause()) {
            details.append("\nCaused by: ").append(cause);
        }
        return details.toString();
    }

    private void regressionChecks() {
        check("frozen_transform_pixels", () -> {
            int compared = 0;
            for (OracleContract.OracleCase oracleCase : contract.cases) {
                for (OracleContract.Probe probe : oracleCase.probes) {
                    OracleCoreVerifier.RgbImage frozen = loadImage(probe.image.path);
                    OracleCoreVerifier.RgbImage rendered = OracleCoreVerifier.transformedFrame(
                            contract, oracleCase.operation, probe.sourceFrame);
                    require(frozen.width == rendered.width && frozen.height == rendered.height
                            && Arrays.equals(frozen.rgb, rendered.rgb),
                            "Analytic transform differs from frozen Pillow pixels: " + probe.image.path);
                    compared++;
                }
            }
            return object("images_compared", compared, "comparison", "exact bytes; separate from encoded expectations");
        });
        check("exact_length_fft", () -> {
            List<Map<String, Object>> vectors = new ArrayList<Map<String, Object>>();
            for (int length : new int[]{63, 64, 65, 257, 1000}) {
                float[] samples = new float[length];
                for (int i = 0; i < length; i++) {
                    samples[i] = (float) (0.17 + 0.3 * Math.sin(2 * Math.PI * 7.37 * i / length)
                            + 0.08 * Math.cos(2 * Math.PI * 11.13 * i / length));
                }
                double expected = directFrequency(samples, 48000);
                double actual = OracleCoreVerifier.dominantFrequency(samples, 48000);
                require(Math.abs(expected - actual) < 1e-7, "Unpadded DFT mismatch, length=" + length);
                vectors.add(object("length", length, "actual_hz", actual, "dft_hz", expected));
            }
            return object("vectors", vectors);
        });
        check("nyquist_frequency", () -> {
            List<Map<String, Object>> vectors = new ArrayList<Map<String, Object>>();
            for (int length : new int[]{64, 100}) {
                float[] samples = new float[length];
                for (int i = 0; i < length; i++) {
                    samples[i] = i % 2 == 0 ? 0.5f : -0.5f;
                }
                double hz = OracleCoreVerifier.dominantFrequency(samples, 48000);
                require(hz == 24000d, "Nyquist bin omitted");
                vectors.add(object("length", length, "actual_hz", hz, "expected_hz", 24000));
            }
            return object("vectors", vectors);
        });
        final OracleContract.OracleCase identity = contract.requireCase("identity");
        final OracleCoreVerifier.Candidate[] decoded = new OracleCoreVerifier.Candidate[1];
        check("regression_fixture", () -> {
            decoded[0] = decode(new File(oracleRoot, contract.fixture.path), identity, true);
            OracleCoreVerifier.Report report = verifier.verify(contract, identity.id, decoded[0], imageProvider);
            require(report.passed(), "Regression fixture must pass before mutation");
            return object("report", report.toMap());
        });
        check("null_tracks", () -> {
            OracleCoreVerifier.Candidate base = decoded[0];
            OracleCoreVerifier.Report noVideo = verifier.verify(contract, identity.id,
                    candidateWith(base, null, base.audio), imageProvider);
            OracleCoreVerifier.Report noAudio = verifier.verify(contract, identity.id,
                    candidateWith(base, base.video, null), imageProvider);
            requireCheckerError(noVideo);
            requireCheckerError(noAudio);
            return object("video", noVideo.toMap(), "audio", noAudio.toMap());
        });
        check("malformed_images", () -> {
            OracleCoreVerifier.Candidate base = decoded[0];
            int size = identity.width * identity.height * 3;
            List<Map<String, Object>> reports = new ArrayList<Map<String, Object>>();
            for (OracleCoreVerifier.RgbImage malformed : Arrays.asList(
                    new OracleCoreVerifier.RgbImage(identity.width, identity.height, null),
                    new OracleCoreVerifier.RgbImage(identity.width, identity.height, new byte[size - 1]),
                    new OracleCoreVerifier.RgbImage(identity.width, identity.height, new byte[size + 1]),
                    new OracleCoreVerifier.RgbImage(identity.height, identity.width, new byte[size]))) {
                OracleCoreVerifier.Frame first = base.video.frames.get(0);
                OracleCoreVerifier.Candidate changed = replaceFrame(base, 0,
                        new OracleCoreVerifier.Frame(first.ptsSeconds, malformed, first.barcodeLumaPairs));
                OracleCoreVerifier.Report actual = verifier.verify(contract, identity.id, changed, imageProvider);
                OracleCoreVerifier.Report expected = verifier.verify(contract, identity.id, base,
                        (oracleCase, probe, source) -> malformed);
                requireCheckerError(actual);
                requireCheckerError(expected);
                reports.add(object("actual_image", actual.toMap(), "expected_image", expected.toMap()));
            }
            OracleCoreVerifier.Report nullExpected = verifier.verify(contract, identity.id, base,
                    (oracleCase, probe, source) -> null);
            requireCheckerError(nullExpected);
            return object("vectors", reports, "null_expected", nullExpected.toMap());
        });
        check("missing_probe_rgb", () -> {
            OracleCoreVerifier.Candidate base = decoded[0];
            OracleCoreVerifier.Frame first = base.video.frames.get(0);
            try {
                verifier.verify(contract, identity.id, replaceFrame(base, 0,
                        new OracleCoreVerifier.Frame(first.ptsSeconds, null, first.barcodeLumaPairs)), imageProvider);
            } catch (IOException expected) {
                require(expected.getMessage().contains("Probe frame image was not retained"),
                        "Unexpected missing-probe failure: " + expected);
                return object("expected_exception", expected.toString());
            }
            throw new IllegalStateException("Missing probe RGB accepted");
        });
        check("all_frame_barcode_sensitivity", () -> {
            OracleCoreVerifier.Candidate base = decoded[0];
            int index = 0;
            while (index < base.video.frames.size() && base.video.frames.get(index).image != null) {
                index++;
            }
            require(index < base.video.frames.size(), "No omitted RGB frame to corrupt");
            OracleCoreVerifier.Frame frame = base.video.frames.get(index);
            double[][] corrupted = new double[identity.barcodeRegions.size()][2];
            for (double[] pair : corrupted) {
                Arrays.fill(pair, 127.5d);
            }
            OracleCoreVerifier.Report report = verifier.verify(contract, identity.id, replaceFrame(base, index,
                    new OracleCoreVerifier.Frame(frame.ptsSeconds, null, corrupted)), imageProvider);
            require(!report.passed() && hasFailures(report, Arrays.asList("video.temporal_barcode")),
                    "Nonprobe barcode corruption not detected");
            Map<?, ?> metrics = (Map<?, ?>) report.toMap().get("metrics");
            require(((List<?>) metrics.get("video_timecodes")).size() == base.video.frames.size(),
                    "Not every frame's barcode was checked");
            return object("corrupted_nonprobe_index", index, "report", report.toMap());
        });
        check("p95_interpolation_boundary", () -> {
            OracleCoreVerifier.Candidate base = decoded[0];
            OracleContract.Probe probe = identity.probes.get(0);
            OracleCoreVerifier.RgbImage expected = loadImage(probe.image.path);
            List<Map<String, Object>> reports = new ArrayList<Map<String, Object>>();
            for (int low : new int[]{44, 45}) {
                byte[] rgb = new byte[expected.rgb.length];
                for (int i = 0; i < rgb.length; i++) {
                    int delta = i < rgb.length * 19 / 20 ? low : low + 1;
                    int value = expected.rgb[i] & 0xff;
                    rgb[i] = (byte) (value + delta <= 255 ? value + delta : value - delta);
                }
                OracleCoreVerifier.Frame first = base.video.frames.get(0);
                OracleCoreVerifier.Candidate changed = replaceFrame(base, 0,
                        new OracleCoreVerifier.Frame(first.ptsSeconds,
                                new OracleCoreVerifier.RgbImage(expected.width, expected.height, rgb),
                                first.barcodeLumaPairs));
                OracleCoreVerifier.Report report = verifier.verify(contract, identity.id, changed, imageProvider);
                Map<?, ?> assertion = assertion(report, "video.spatial_p95");
                require(Math.abs(((Number) assertion.get("actual")).doubleValue() - (low + 0.05)) < 1e-8,
                        "p95 must interpolate across the 95% histogram boundary");
                require(Boolean.valueOf(low == 44).equals(assertion.get("passed")),
                        "p95 threshold outcome changed");
                reports.add(report.toMap());
            }
            return object("reports", reports);
        });
        check("half_sample_audio_windows", () -> {
            OracleCoreVerifier.Candidate base = decoded[0];
            require(base.audio.packets.get(0).ptsSeconds == 0d, "Half-sample regression needs zero decoded start");
            List<OracleContract.AudioProbe> probes = Arrays.asList(
                    new OracleContract.AudioProbe(0.015625, 1d / 48000, 0, 440, 0),
                    new OracleContract.AudioProbe(0.0078125, 1d / 48000, 0, 440, 0));
            OracleContract modified = withProbes(identity, identity.probes, probes);
            OracleCoreVerifier.Report report = verifier.verify(modified, identity.id, base, imageProvider);
            for (int i = 0; i < 2; i++) {
                Map<?, ?> samples = assertion(report, "audio.window_" + i + ".samples");
                require(Boolean.TRUE.equals(samples.get("passed"))
                        && ((Number) samples.get("actual")).intValue() == i * 2
                        && ((Number) samples.get("expected")).intValue() == i * 2,
                        "Half-sample windows must round ties to even: " + samples);
            }
            double rms = Math.sqrt((base.audio.samples[374] * (double) base.audio.samples[374]
                    + base.audio.samples[375] * (double) base.audio.samples[375]) / 2);
            require(Math.abs(((Number) assertion(report, "audio.window_1.rms").get("actual")).doubleValue()
                    - rms) < 1e-12, "Half-sample window selected wrong PCM indices");
            return object("rounded_ranges", Arrays.asList(Arrays.asList(750, 750), Arrays.asList(374, 376)),
                    "report", report.toMap());
        });
        check("probe_duplicates_and_order", () -> {
            OracleCoreVerifier.Candidate base = decoded[0];
            List<OracleContract.Probe> probes = new ArrayList<OracleContract.Probe>();
            for (int i = identity.probes.size() - 1; i >= 0; i--) {
                OracleContract.Probe probe = identity.probes.get(i);
                probes.add(probe);
                probes.add(new OracleContract.Probe(probe.sourceFrame + 1, probe.outputSeconds, probe.image));
            }
            OracleCoreVerifier.Report ordinary = verifier.verify(contract, identity.id, base, imageProvider);
            OracleCoreVerifier.Report duplicate = verifier.verify(
                    withProbes(identity, probes, identity.audioProbes), identity.id, base, imageProvider);
            require(ordinary.passed() && ordinary.toMap().equals(duplicate.toMap()),
                    "Duplicate probes must keep first metadata and sort unique decoded indices");
            return object("input_probes", probes.size(), "report", duplicate.toMap());
        });
        check("nearest_probe_tie", () -> {
            OracleCoreVerifier.Candidate base = decoded[0];
            double midpoint = (base.video.frames.get(0).ptsSeconds + base.video.frames.get(1).ptsSeconds) / 2;
            OracleContract.Probe first = identity.probes.get(0);
            List<OracleContract.Probe> probes = Arrays.asList(
                    new OracleContract.Probe(first.sourceFrame, midpoint, first.image));
            OracleCoreVerifier.Report report = verifier.verify(
                    withProbes(identity, probes, identity.audioProbes), identity.id, base, imageProvider);
            List<?> spatial = (List<?>) ((Map<?, ?>) report.toMap().get("metrics")).get("video_spatial");
            require(report.passed() && spatial.size() == 1
                    && ((Number) ((Map<?, ?>) spatial.get(0)).get("pts")).doubleValue()
                    == base.video.frames.get(0).ptsSeconds, "Nearest PTS ties must select first frame");
            return object("target_seconds", midpoint, "report", report.toMap());
        });
    }

    private static void requireCheckerError(OracleCoreVerifier.Report report) {
        require(!report.passed() && "ERROR".equals(report.toMap().get("status"))
                && ((List<?>) report.toMap().get("failed_assertions")).contains("checker.completed_without_error"),
                "Malformed candidate must report checker error: " + report.toMap());
    }

    private static Map<?, ?> assertion(OracleCoreVerifier.Report report, String name) {
        for (Object item : (List<?>) report.toMap().get("checks")) {
            Map<?, ?> check = (Map<?, ?>) item;
            if (name.equals(check.get("assertion"))) {
                return check;
            }
        }
        throw new IllegalStateException("Missing assertion: " + name);
    }

    private static OracleCoreVerifier.Candidate candidateWith(OracleCoreVerifier.Candidate base,
                                                              OracleCoreVerifier.VideoTrack video,
                                                              OracleCoreVerifier.AudioTrack audio) {
        return new OracleCoreVerifier.Candidate(base.path, base.sha256, base.fileBytes,
                base.videoTrackCount, base.audioTrackCount, video, audio);
    }

    private static OracleCoreVerifier.Candidate replaceFrame(OracleCoreVerifier.Candidate base, int index,
                                                             OracleCoreVerifier.Frame frame) {
        List<OracleCoreVerifier.Frame> frames = new ArrayList<OracleCoreVerifier.Frame>(base.video.frames);
        frames.set(index, frame);
        OracleCoreVerifier.VideoTrack video = base.video;
        return candidateWith(base, new OracleCoreVerifier.VideoTrack(video.codec, video.sampleAspectRatio,
                video.width, video.height, video.rotationDegrees, video.durationSeconds, frames), base.audio);
    }

    private OracleContract withProbes(OracleContract.OracleCase c, List<OracleContract.Probe> probes,
                                      List<OracleContract.AudioProbe> audioProbes) {
        OracleContract.OracleCase modified = new OracleContract.OracleCase(c.id, c.operation, c.androidEditConfig,
                c.width, c.height, c.durationSeconds, c.nativeFps, c.acceptedCfrFps, c.nativeFrameCount,
                c.videoTrackCount, c.audioTrackCount, c.audioRequired, c.audioSampleRate, c.audioChannels,
                c.expectedDecodedAudioSamples, c.barcodeRegions, probes, c.frameAssets, audioProbes, c.reference);
        return new OracleContract(contract.schema, contract.version, contract.pins, contract.source,
                contract.tolerances, contract.fixture, Arrays.asList(modified));
    }

    private static double directFrequency(float[] samples, int rate) {
        // Independent O(n^2) DFT, deliberately not the core's FFT or padded frequency grid.
        int n = samples.length;
        double mean = 0;
        for (float sample : samples) {
            mean += sample;
        }
        mean /= n;
        double[] magnitudes = new double[n / 2 + 1];
        for (int k = 0; k < magnitudes.length; k++) {
            double real = 0;
            double imaginary = 0;
            for (int i = 0; i < n; i++) {
                double value = (samples[i] - mean) * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (n - 1)));
                real += value * Math.cos(2 * Math.PI * k * i / n);
                imaginary -= value * Math.sin(2 * Math.PI * k * i / n);
            }
            magnitudes[k] = Math.hypot(real, imaginary);
        }
        int peak = 1;
        for (int k = 2; k < magnitudes.length; k++) {
            if (magnitudes[k] > magnitudes[peak]) {
                peak = k;
            }
        }
        double delta = 0;
        if (peak < magnitudes.length - 1) {
            double left = Math.log(Math.max(magnitudes[peak - 1], 1e-20));
            double middle = Math.log(Math.max(magnitudes[peak], 1e-20));
            double right = Math.log(Math.max(magnitudes[peak + 1], 1e-20));
            double denominator = left - 2 * middle + right;
            if (Math.abs(denominator) > 1e-12) {
                delta = 0.5 * (left - right) / denominator;
            }
        }
        return (peak + delta) * rate / n;
    }

    private static Map<String, Object> object(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int i = 0; i < pairs.length; i += 2) {
            result.put((String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    private static String json(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            if (value instanceof Number && !Double.isFinite(((Number) value).doubleValue())) {
                // Keep diagnostics valid JSON even when a broken checker returns non-finite metrics.
                return json(value.toString());
            }
            return value.toString();
        }
        if (value instanceof Map) {
            List<String> fields = new ArrayList<String>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                fields.add(json(entry.getKey().toString()) + ":" + json(entry.getValue()));
            }
            return "{" + String.join(",", fields) + "}";
        }
        if (value instanceof Iterable) {
            List<String> items = new ArrayList<String>();
            for (Object item : (Iterable<?>) value) {
                items.add(json(item));
            }
            return "[" + String.join(",", items) + "]";
        }
        if (value.getClass().isArray()) {
            List<Object> items = new ArrayList<Object>();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) {
                items.add(java.lang.reflect.Array.get(value, i));
            }
            return json(items);
        }
        StringBuilder quoted = new StringBuilder("\"");
        for (char c : value.toString().toCharArray()) {
            if (c == '"' || c == '\\') {
                quoted.append('\\').append(c);
            } else if (c < 0x20) {
                quoted.append(String.format(Locale.US, "\\u%04x", (int) c));
            } else {
                quoted.append(c);
            }
        }
        return quoted.append('"').toString();
    }

    private static void verifySharedPcmConversions() throws IOException {
        ByteBuffer pcm16 = ByteBuffer.allocate(8);
        pcm16.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        pcm16.putShort((short) 16384);
        pcm16.putShort((short) 16384);
        pcm16.putShort((short) -16384);
        pcm16.putShort((short) -16384);
        pcm16.flip();
        float[] pcm16Decoded = OraclePcmUtils.decodeToMono(pcm16, OraclePcmUtils.ENCODING_PCM_16BIT, 2);
        require(pcm16Decoded.length == 2
                        && Math.abs(pcm16Decoded[0] - 0.5f) < 0.0001f
                        && Math.abs(pcm16Decoded[1] + 0.5f) < 0.0001f,
                "PCM16 shared byte-order decode");

        ByteBuffer pcmFloat = ByteBuffer.allocate(16);
        pcmFloat.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        pcmFloat.putFloat(0.25f);
        pcmFloat.putFloat(0.75f);
        pcmFloat.putFloat(-0.25f);
        pcmFloat.putFloat(-0.75f);
        pcmFloat.flip();
        float[] floatDecoded = OraclePcmUtils.decodeToMono(pcmFloat, OraclePcmUtils.ENCODING_PCM_FLOAT, 2);
        require(floatDecoded.length == 2
                        && Math.abs(floatDecoded[0] - 0.5f) < 0.0001f
                        && Math.abs(floatDecoded[1] + 0.5f) < 0.0001f,
                "Float shared byte-order decode");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class StreamInfo {
        private final String codec;
        private final int width;
        private final int height;
        private final String sampleAspectRatio;
        private final double durationSeconds;
        private final int sampleRate;
        private final int channels;
        private final double startSeconds;

        private StreamInfo(String codec, int width, int height, String sampleAspectRatio,
                           double durationSeconds, int sampleRate, int channels,
                           double startSeconds) {
            this.codec = codec;
            this.width = width;
            this.height = height;
            this.sampleAspectRatio = sampleAspectRatio;
            this.durationSeconds = durationSeconds;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.startSeconds = startSeconds;
        }
    }

    private static final class TrackCounts {
        private final int videoTracks;
        private final int audioTracks;

        private TrackCounts(int videoTracks, int audioTracks) {
            this.videoTracks = videoTracks;
            this.audioTracks = audioTracks;
        }
    }
}
