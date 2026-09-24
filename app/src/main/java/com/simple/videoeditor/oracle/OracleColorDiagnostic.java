package com.simple.videoeditor.oracle;

import android.content.res.AssetManager;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Identity-only evidence, deliberately outside the verifier and its fallback/result path. */
public final class OracleColorDiagnostic {
    private static final long PASS_NS = 6_000_000_000L;
    private static final int MAX_INPUTS = 64;
    private static final int MAX_OUTPUTS = 16;

    private OracleColorDiagnostic() {}

    public static Map<String, Object> collect(File identity, File source, AssetManager assets,
                                              OracleAndroidDecoder.Observer observer) throws IOException {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", "identity-color-diagnostic-1");
        report.put("diagnostic_only", true);
        report.put("bounds", "two files; default then software-only; first frozen probe; "
                + "6s cooperative/pass, 30s total, 64 input/16 output buffers/pass; <=80 ROIs; no audio");
        report.put("native_timeout_limitation", "Deadlines bound polling, not blocked vendor native calls "
                + "or synchronous report I/O. Before-native checkpoints and existing suite watchdog remain; "
                + "a hung/crashed decoder is incomplete evidence, never success.");
        report.put("scope", "Separate buffer decoders, shared extractor and Image API. Independent RGB math, "
                + "not an independent demuxer or GL/surface observation. No alternate-matrix fitting.");
        report.put("interpretation", "Compare only matching PTS/geometry and independent decoder names. "
                + "Hardware-only raw difference implicates decoder/Image path. Equal raw but different RGB "
                + "implicates metadata/conversion. Both decoders disagree with frozen RGB: production "
                + "pixels/signaling, source surface/GL path or expectation remain hypotheses, not a verdict.");
        report.put("source_api_evidence", "Media3ExportEngine createEditedItem uses MediaItem.Builder.setUri; "
                + "Transformer and createVideoEffects own the surface/GL path. Oracle buffer Image reads "
                + "do not observe that surface path. Inspect source decoder output ColorInfo, external-texture "
                + "sampling/dataspace and encoder input/output signaling next; no boundary is proven wrong.");
        report.put("math", "Metadata output overrides input; absent defaults BT601 limited. "
                + "Y'=(Y-16)/219, Cb'=(Cb-128)/224, Cr'=(Cr-128)/224 for limited; "
                + "full uses Y/255 and chroma/255. R'=Y'+2(1-Kr)Cr'; B'=Y'+2(1-Kb)Cb'; "
                + "G'=(Y'-Kr*R'-Kb*B')/(1-Kr-Kb); round/clip each channel*255 BEFORE averaging. "
                + "BT709 Kr=.2126 Kb=.0722; BT601 Kr=.299 Kb=.114. "
                + "Raw chroma means are luma-pixel-weighted, not unique chroma-site means.");
        List<Map<String, Object>> passes = new ArrayList<>();
        report.put("passes", passes);
        long deadline = System.nanoTime() + 30_000_000_000L;
        try {
            OracleContract contract = OracleGeneratedContract.create();
            OracleContract.OracleCase oracleCase = contract.requireCase("identity");
            OracleContract.Probe probe = oracleCase.probes.get(0);
            report.put("source_frame", probe.sourceFrame);
            report.put("target_pts_us", Math.round(probe.outputSeconds * 1_000_000));
            report.put("expected_asset", probe.image.path);
            report.put("expected_sha256", probe.image.sha256);
            String sourceHash = hash(source, deadline);
            report.put("source_sha256", sourceHash);
            report.put("source_pin", contract.fixture.sha256);
            if (!contract.fixture.sha256.equals(sourceHash)) throw new IOException("Source pin mismatch");
            byte[] png;
            try (InputStream input = assets.open("video-oracle/" + probe.image.path.replace('\\', '/'))) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    check(deadline);
                    if (bytes.size() + count > 256 * 1024) throw new IOException("Expected image budget");
                    bytes.write(buffer, 0, count);
                }
                png = bytes.toByteArray();
            }
            if (!probe.image.sha256.equals(hex(digest().digest(png)))) {
                throw new IOException("Expected image pin mismatch");
            }
            OracleCoreVerifier.RgbImage expected = new OracleAndroidDecoder()
                    .loadAssetImage(new ByteArrayInputStream(png));
            List<OracleCoreVerifier.RegionExpectation> regions = OracleCoreVerifier.pixelRegions(expected);
            report.put("roi_count", regions.size());
            report.put("roi_definition", "Unchanged OracleCoreVerifier.pixelRegions on pinned first-probe PNG");
            File[] files = {identity, source};
            String[] roles = {"actual_identity_export", "pinned_source"};
            for (int i = 0; i < files.length; i++) {
                String fileHash = hash(files[i], deadline);
                Map<String, Object> normal = decode(files[i], roles[i], fileHash, false, null,
                        expected, regions, probe, observer, deadline);
                passes.add(normal);
                Map<String, Object> software = decode(files[i], roles[i], fileHash, true,
                        (String) normal.get("decoder_name"), expected, regions, probe, observer, deadline);
                passes.add(software);
                report.put(roles[i] + "_comparison", compare(normal, software));
            }
            boolean complete = true;
            for (Map<String, Object> pass : passes) {
                complete &= "MEASURED_NOT_A_VERDICT".equals(pass.get("status"));
            }
            report.put("status", complete ? "COLLECTED_NOT_A_VERDICT" : "DIAGNOSTIC_INCOMPLETE");
        } catch (IOException | RuntimeException error) {
            report.put("status", "DIAGNOSTIC_INCOMPLETE");
            report.put("error", error.toString());
        }
        return report;
    }

    private static Map<String, Object> decode(File file, String role, String hash, boolean software,
            String defaultName, OracleCoreVerifier.RgbImage expected,
            List<OracleCoreVerifier.RegionExpectation> regions, OracleContract.Probe probe,
            OracleAndroidDecoder.Observer observer, long totalDeadline) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("role", role);
        result.put("path", file.getAbsolutePath());
        result.put("sha256", hash);
        result.put("mode", software ? "software_only" : "oracle_default_selection");
        result.put("status", "IN_PROGRESS");
        emit(observer, result);
        long start = System.nanoTime();
        long deadline = Math.min(totalDeadline, start + PASS_NS);
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            check(deadline);
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) { track = i; break; }
            }
            if (track < 0) throw new IOException("No video track");
            extractor.selectTrack(track);
            MediaFormat input = extractor.getTrackFormat(track);
            result.put("input_format", OracleAndroidDecoder.formatDiagnostics(input));
            Map<String, Object> csd = new LinkedHashMap<>();
            for (String key : Arrays.asList("csd-0", "csd-1")) {
                ByteBuffer buffer = input.containsKey(key) ? input.getByteBuffer(key) : null;
                if (buffer != null) {
                    ByteBuffer copy = buffer.duplicate();
                    csd.put(key + "_bytes", copy.remaining());
                    byte[] bytes = new byte[Math.min(copy.remaining(), 4096)];
                    copy.get(bytes);
                    csd.put(key + "_hex", hex(bytes));
                    csd.put(key + "_truncated", copy.hasRemaining());
                }
            }
            result.put("codec_config_for_sps_vui_inspection", csd);
            if (input.getInteger(MediaFormat.KEY_WIDTH) != expected.width
                    || input.getInteger(MediaFormat.KEY_HEIGHT) != expected.height
                    || (input.containsKey("rotation-degrees") && input.getInteger("rotation-degrees") != 0)) {
                throw new IOException("Identity diagnostic requires frozen unrotated geometry");
            }
            input.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            input.setInteger("rotation-degrees", 0);
            List<OracleVideoCodecSelector.Candidate> candidates = OracleVideoCodecSelector.enumerate(input);
            if (software) {
                List<OracleVideoCodecSelector.Candidate> independent = new ArrayList<>();
                List<Map<String, Object>> softwareEvidence = new ArrayList<>();
                for (OracleVideoCodecSelector.Candidate candidate : candidates) {
                    if (candidate.software) softwareEvidence.add(candidate.evidence());
                    if (candidate.software && candidate.eligibleAttempt && !candidate.name.equals(defaultName)) {
                        independent.add(candidate);
                    }
                }
                result.put("software_candidates", softwareEvidence);
                candidates = independent;
                if (candidates.isEmpty()) throw new IOException("Independent software decoder unavailable");
            }
            codec = OracleVideoCodecSelector.configure(candidates, input,
                    OracleVideoCodecSelector.ANDROID, selection -> {
                        result.put("selection", selection);
                        result.put("decoder_name", selection.get("decoder_name"));
                        result.put("decoder_software", selection.get("software_fallback"));
                        emit(observer, result);
                    }, deadline);
            result.put("decoder_name", codec.getName());
            result.put("stage", "before_start");
            emit(observer, result);
            codec.start();
            MediaFormat output = input;
            boolean eos = false;
            int inputs = 0;
            int outputs = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long targetUs = Math.round(probe.outputSeconds * 1_000_000);
            while (true) {
                check(deadline);
                if (!eos) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        if (++inputs > MAX_INPUTS) throw new IOException("Diagnostic input budget");
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        if (buffer == null) throw new IOException("No input buffer");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        eos = size < 0;
                        codec.queueInputBuffer(index, 0, eos ? 0 : size,
                                eos ? 0 : extractor.getSampleTime(), eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (!eos) extractor.advance();
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    output = codec.getOutputFormat();
                    result.put("output_format", OracleAndroidDecoder.formatDiagnostics(output));
                } else if (index >= 0) {
                    try {
                        if (++outputs > MAX_OUTPUTS) throw new IOException("Diagnostic output budget");
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                                && info.presentationTimeUs >= targetUs) {
                            result.put("pts_us", info.presentationTimeUs);
                            result.put("target_delta_us", info.presentationTimeUs - targetUs);
                            result.put("frozen_probe_pts_match", info.presentationTimeUs == targetUs);
                            output = codec.getOutputFormat(index);
                            result.put("output_format", OracleAndroidDecoder.formatDiagnostics(output));
                            try (Image image = codec.getOutputImage(index)) {
                                if (image == null) throw new IOException("No decoded Image");
                                result.putAll(OracleColorMath.measure(image, output, input, expected, regions, deadline));
                                result.put("status", "MEASURED_NOT_A_VERDICT");
                                result.put("stage", "before_image_and_buffer_release");
                                emit(observer, result);
                            }
                            break;
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            throw new IOException("EOS before diagnostic probe");
                        }
                    } finally {
                        codec.releaseOutputBuffer(index, false);
                    }
                }
            }
        } catch (IOException | RuntimeException error) {
            result.put("status", "UNAVAILABLE_OR_FAILED");
            result.put("error", error.toString());
        } finally {
            result.put("stage", "before_release");
            try {
                Map<String, Object> release = new LinkedHashMap<>(result);
                release.remove("regions");
                emit(observer, release);
            } finally {
                try {
                    if (codec != null) codec.release();
                } catch (RuntimeException error) {
                    result.put("release_error", error.toString());
                    result.put("status", "UNAVAILABLE_OR_FAILED");
                } finally {
                    extractor.release();
                }
            }
            result.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> compare(Map<String, Object> normal, Map<String, Object> software) {
        Map<String, Object> result = new LinkedHashMap<>();
        boolean comparable = "MEASURED_NOT_A_VERDICT".equals(normal.get("status"))
                && "MEASURED_NOT_A_VERDICT".equals(software.get("status"))
                && normal.get("sha256").equals(software.get("sha256"))
                && normal.get("pts_us").equals(software.get("pts_us"))
                && Boolean.FALSE.equals(normal.get("decoder_software"))
                && Boolean.TRUE.equals(software.get("decoder_software"))
                && !normal.get("decoder_name").equals(software.get("decoder_name"));
        result.put("independent_same_pts", comparable);
        result.put("default_is_software", normal.get("decoder_software"));
        if (!comparable) {
            result.put("limitation", "Missing/failed hardware-vs-software pair, default already software, "
                    + "or unmatched PTS; distinct software names alone do not prove independent implementations");
            return result;
        }
        List<Map<String, Object>> left = (List<Map<String, Object>>) normal.get("regions");
        List<Map<String, Object>> right = (List<Map<String, Object>>) software.get("regions");
        for (String key : Arrays.asList("ycbcr_mean", "oracle_rgb_mean", "independent_rgb_mean")) {
            double[] max = new double[3];
            for (int i = 0; i < left.size(); i++) {
                double[] a = (double[]) left.get(i).get(key);
                double[] b = (double[]) right.get(i).get(key);
                for (int c = 0; c < 3; c++) max[c] = Math.max(max[c], Math.abs(a[c] - b[c]));
            }
            result.put(key + "_max_abs_decoder_delta", max);
        }
        return result;
    }

    private static void emit(OracleAndroidDecoder.Observer observer, Map<String, Object> value) throws IOException {
        if (observer != null) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("identity_color_diagnostic", new LinkedHashMap<>(value));
            observer.checkpoint(envelope);
        }
    }

    static void check(long deadline) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadline) {
            throw new IOException("Color diagnostic interrupted or deadline exceeded");
        }
    }

    private static String hash(File file, long deadline) throws IOException {
        if (!file.isFile() || file.length() > OracleCoreVerifier.MAX_FILE_BYTES) {
            throw new IOException("Diagnostic file missing or over budget");
        }
        MessageDigest digest = digest();
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[16384];
            int count;
            while ((count = input.read(bytes)) != -1) {
                check(deadline);
                digest.update(bytes, 0, count);
            }
        }
        return hex(digest.digest());
    }

    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte value : bytes) text.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return text.toString();
    }
}
