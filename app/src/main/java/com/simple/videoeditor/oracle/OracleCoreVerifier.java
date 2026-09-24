package com.simple.videoeditor.oracle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class OracleCoreVerifier {
    public static final int MAX_FILE_BYTES = 32 * 1024 * 1024;

    public interface ExpectedImageProvider {
        RgbImage load(OracleContract.OracleCase oracleCase, OracleContract.Probe probe,
                      int matchedSourceFrame) throws IOException;
    }

    public Report verify(OracleContract contract, String caseId, Candidate candidate) throws IOException {
        return verify(contract, caseId, candidate, null);
    }

    public Report verify(OracleContract contract, String caseId, Candidate candidate,
                         ExpectedImageProvider imageProvider) throws IOException {
        if (contract == null || candidate == null) {
            throw new IllegalArgumentException("contract and candidate are required");
        }
        OracleContract.OracleCase oracleCase;
        try {
            oracleCase = contract.requireCase(caseId);
        } catch (IllegalArgumentException error) {
            throw new IOException("Unknown oracle case: " + caseId, error);
        }
        Report report = new Report(caseId, candidate.path, candidate.sha256);
        try {
            verifyCase(contract, oracleCase, candidate, imageProvider, report);
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted() || error instanceof CancellationException) {
                throw new IOException("Verification interrupted", error);
            }
            report.check("checker.completed_without_error", false, error.toString(), null);
        }
        return report;
    }

    private void verifyCase(OracleContract contract, OracleContract.OracleCase oracleCase,
                            Candidate candidate, ExpectedImageProvider imageProvider,
                            Report report) throws IOException {
        checkInterrupted();
        OracleContract.Tolerances tolerance = contract.tolerances;
        boolean generatedTitle = TitleOracleContract.applies(contract, oracleCase);
        report.check("file.exists", candidate.fileBytes > 0, candidate.fileBytes, ">0");
        if (!report.lastPassed()) {
            return;
        }
        report.check("file.bounded", candidate.fileBytes <= MAX_FILE_BYTES,
                candidate.fileBytes, "<=" + MAX_FILE_BYTES);
        if (!report.lastPassed()) {
            return;
        }
        report.check("video.stream_count",
                candidate.videoTrackCount == oracleCase.videoTrackCount,
                candidate.videoTrackCount, oracleCase.videoTrackCount);
        report.check("audio.stream_count",
                candidate.audioTrackCount == oracleCase.audioTrackCount,
                candidate.audioTrackCount, oracleCase.audioTrackCount);
        if (candidate.videoTrackCount != oracleCase.videoTrackCount) {
            return;
        }
        if (candidate.video == null) {
            throw new IllegalStateException("Video track data unavailable");
        }
        VideoTrack video = candidate.video;
        int displayWidth = video.width;
        int displayHeight = video.height;
        if (Math.abs(video.rotationDegrees) % 180 == 90) {
            displayWidth = video.height;
            displayHeight = video.width;
        }
        boolean geometryOk = displayWidth == oracleCase.width && displayHeight == oracleCase.height;
        report.check("video.geometry", geometryOk,
                list(displayWidth, displayHeight), list(oracleCase.width, oracleCase.height));
        report.check("video.square_pixels",
                "1:1".equals(video.sampleAspectRatio) || "N/A".equals(video.sampleAspectRatio),
                video.sampleAspectRatio, "1:1");
        report.check("video.codec", "h264".equals(video.codec), video.codec, "h264");
        report.check("video.duration",
                Math.abs(video.durationSeconds - oracleCase.durationSeconds)
                        <= 1d / min(oracleCase.acceptedCfrFps) + tolerance.videoDurationExtraSeconds,
                video.durationSeconds, oracleCase.durationSeconds);
        report.check("video.decode_bound",
                video.durationSeconds > 0d && video.durationSeconds <= 12d,
                video.durationSeconds, "(0,12]");
        if (!report.lastPassed()) {
            return;
        }

        List<Frame> frames = video.frames == null ? Collections.<Frame>emptyList() : video.frames;
        report.check("video.frames_present", frames.size() > 1, frames.size(), ">1");
        if (frames.size() < 2) {
            return;
        }
        double[] pts = new double[frames.size()];
        for (int i = 0; i < frames.size(); i++) {
            checkInterrupted();
            pts[i] = frames.get(i).ptsSeconds;
        }
        report.check("video.pts_start", Math.abs(pts[0]) <= tolerance.videoStartSeconds, pts[0], 0d);
        double[] steps = new double[pts.length - 1];
        boolean monotonic = true;
        for (int i = 1; i < pts.length; i++) {
            steps[i - 1] = pts[i] - pts[i - 1];
            if (!(steps[i - 1] > 0d)) {
                monotonic = false;
            }
        }
        report.check("video.pts_monotonic", monotonic, monotonic, true);
        double medianStep = median(steps);
        double rate = medianStep > 0d ? 1d / medianStep : 0d;
        double accepted = nearestAccepted(rate, oracleCase.acceptedCfrFps);
        report.check("video.fps", Math.abs(rate - accepted) < 0.03d, rate, oracleCase.acceptedCfrFps);
        double maxCadenceError = 0d;
        for (int i = 0; i < steps.length; i++) {
            double expectedStep = generatedTitle ? (pts[i] < 1d ? 1d / 30 : 1d / 24) : 1d / accepted;
            maxCadenceError = Math.max(maxCadenceError, Math.abs(steps[i] - expectedStep));
        }
        report.check("video.pts_cadence",
                maxCadenceError <= tolerance.ptsCadenceSeconds,
                maxCadenceError, tolerance.ptsCadenceSeconds);
        long expectedFrames = generatedTitle ? 126 : Math.round(oracleCase.durationSeconds * accepted);
        report.check("video.frame_count", Math.abs(frames.size() - expectedFrames) <= 1,
                frames.size(), expectedFrames);
        double ptsEnd = pts[pts.length - 1] + 1d / accepted;
        report.check("video.pts_end",
                Math.abs(ptsEnd - oracleCase.durationSeconds)
                        <= 1d / accepted + tolerance.videoDurationExtraSeconds,
                ptsEnd, oracleCase.durationSeconds);
        if (!geometryOk) {
            return;
        }

        List<MatchedFrame> matchedFrames = new ArrayList<MatchedFrame>(frames.size());
        double maxBarcodeError = 0d;
        OracleContract.Operation operation = oracleCase.operation;
        int firstSourceFrame = (int) Math.round(operation.trimStartSeconds * contract.source.fps);
        int stopSourceFrame = (int) Math.round(operation.trimEndSeconds * contract.source.fps);
        boolean importedIntro = IntroOracleContract.VERSION.equals(contract.version)
                && IntroOracleContract.CASE_ID.equals(oracleCase.id);
        int titleFrames = 0;
        for (int i = 0; i < frames.size(); i++) {
            checkInterrupted();
            Frame frame = frames.get(i);
            if (generatedTitle && frame.ptsSeconds < 1d) {
                matchedFrames.add(new MatchedFrame(frame.ptsSeconds, -1, -1, 0));
                titleFrames++;
                continue;
            }
            double mainSeconds = generatedTitle ? frame.ptsSeconds - 1d : frame.ptsSeconds;
            int nominal = (int) Math.floor((mainSeconds * operation.speed
                    + operation.trimStartSeconds) * contract.source.fps + 0.001d);
            int start = Math.max(firstSourceFrame, nominal - tolerance.sourceFrameAlignment);
            int end = Math.min(stopSourceFrame - 1, nominal + tolerance.sourceFrameAlignment);
            if (importedIntro) {
                IntroOracleContract.FrameMapping mapping = IntroOracleContract.frameMapping(frame.ptsSeconds);
                nominal = mapping.nominalSourceFrame;
                start = Math.max(mapping.firstSourceFrame, nominal - tolerance.sourceFrameAlignment);
                end = Math.min(mapping.stopSourceFrame - 1, nominal + tolerance.sourceFrameAlignment);
            }
            MatchedFrame match = matchBarcode(contract, oracleCase, frame, nominal, start, end);
            matchedFrames.add(match);
            maxBarcodeError = Math.max(maxBarcodeError, match.maxLumaError);
        }
        if (generatedTitle) {
            report.check("title.frame_count", titleFrames == 30, titleFrames, 30);
            report.check("title.join", titleFrames < frames.size()
                            && Math.abs(frames.get(titleFrames).ptsSeconds - 1d) <= tolerance.ptsCadenceSeconds,
                    titleFrames < frames.size() ? frames.get(titleFrames).ptsSeconds : -1, 1d);
        }
        report.check("video.temporal_barcode",
                matchedFrames.size() == frames.size() && maxBarcodeError <= tolerance.barcodeLumaError,
                maxBarcodeError, tolerance.barcodeLumaError);

        List<Map<String, Object>> timecodes = new ArrayList<Map<String, Object>>(matchedFrames.size());
        for (MatchedFrame match : matchedFrames) {
            checkInterrupted();
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("pts", match.ptsSeconds);
            item.put("expected_source_frame", match.nominalSourceFrame);
            item.put("matched_source_frame", match.matchedSourceFrame);
            item.put("max_luma_error", match.maxLumaError);
            timecodes.add(item);
        }
        report.metric("video_timecodes", timecodes);

        Map<Integer, OracleContract.Probe> selected = new java.util.TreeMap<Integer, OracleContract.Probe>();
        for (OracleContract.Probe probe : oracleCase.probes) {
            checkInterrupted();
            int frameIndex = nearestFrameIndex(frames, probe.outputSeconds);
            if (!selected.containsKey(frameIndex)) {
                selected.put(frameIndex, probe);
            }
        }
        List<SpatialProbeResult> spatialResults = new ArrayList<SpatialProbeResult>(selected.size());
        int titleProbes = 0;
        RgbImage firstTitle = null;
        double staticError = 0;
        for (Map.Entry<Integer, OracleContract.Probe> selection : selected.entrySet()) {
            checkInterrupted();
            int frameIndex = selection.getKey();
            OracleContract.Probe probe = selection.getValue();
            Frame frame = frames.get(frameIndex);
            if (generatedTitle && probe.sourceFrame == -1) {
                RgbImage image = TitleOracleVerifier.verify(requireImage(frame), oracleCase, report, titleProbes++);
                if (firstTitle == null) firstTitle = image;
                long difference = 0;
                for (int y = 84; y < 158; y++) {
                    for (int x = 130 * 3; x < 190 * 3; x++) {
                        int j = y * 320 * 3 + x;
                        difference += Math.abs((image.rgb[j] & 255) - (firstTitle.rgb[j] & 255));
                    }
                }
                staticError = Math.max(staticError, difference / (60d * 74 * 3));
                continue;
            }
            MatchedFrame match = matchedFrames.get(frameIndex);
            if (match.matchedSourceFrame < 0) {
                continue;
            }
            RgbImage expected = imageProvider != null
                    ? imageProvider.load(oracleCase, probe, match.matchedSourceFrame)
                    : transformedFrame(contract, operation, match.matchedSourceFrame);
            SpatialProbeResult spatial = measureSpatial(contract, oracleCase, probe, frame, match, expected);
            spatialResults.add(spatial);
            if (TextOracleContract.applies(contract, oracleCase)) {
                TextOracleVerifier.verify(requireImage(frame), expected, report, spatialResults.size() - 1);
            }
            if (WatermarkOracleContract.applies(contract, oracleCase)) {
                WatermarkOracleVerifier.verify(requireImage(frame), expected, report, spatialResults.size() - 1);
            }
        }
        if (generatedTitle) {
            report.check("title.probe_count", titleProbes == 30, titleProbes, 30);
            report.check("title.static", staticError <= 2, staticError, "normalized fixed-ROI inter-frame RGB MAE <=2");
        }
        report.check("video.spatial_probe_count", spatialResults.size() == selected.size() - titleProbes,
                spatialResults.size(), selected.size() - titleProbes);
        report.check("video.spatial_mae",
                !spatialResults.isEmpty() && maxMae(spatialResults) <= tolerance.rgbFrameMae,
                maxMae(spatialResults), tolerance.rgbFrameMae);
        report.check("video.spatial_p95",
                !spatialResults.isEmpty() && maxP95(spatialResults) <= tolerance.rgbFrameP95AbsoluteError,
                maxP95(spatialResults), tolerance.rgbFrameP95AbsoluteError);
        report.check("video.pixel_regions",
                !spatialResults.isEmpty() && maxRegionError(spatialResults) <= tolerance.rgbRegionMaxChannelError,
                maxRegionError(spatialResults), tolerance.rgbRegionMaxChannelError);
        report.check("video.moving_markers",
                !spatialResults.isEmpty() && maxMarkerError(spatialResults) <= tolerance.markerMaxChannelError,
                maxMarkerError(spatialResults), tolerance.markerMaxChannelError);
        List<Map<String, Object>> spatialMetrics = new ArrayList<Map<String, Object>>(spatialResults.size());
        for (SpatialProbeResult spatial : spatialResults) {
            checkInterrupted();
            spatialMetrics.add(spatial.toMap());
        }
        report.metric("video_spatial", spatialMetrics);

        if (!oracleCase.audioRequired || candidate.audioTrackCount != oracleCase.audioTrackCount) {
            return;
        }
        if (candidate.audio == null) {
            throw new IllegalStateException("Audio track data unavailable");
        }
        AudioTrack audio = candidate.audio;
        report.check("audio.codec", "aac".equals(audio.codec), audio.codec, "aac");
        report.check("audio.sample_rate", audio.sampleRate == contract.source.sampleRate,
                audio.sampleRate, contract.source.sampleRate);
        report.check("audio.channels", audio.channels == 1, audio.channels, 1);
        report.check("audio.pts_start", Math.abs(audio.startSeconds) <= tolerance.audioStartSeconds,
                audio.startSeconds, 0d);
        report.check("audio.track_duration",
                Math.abs(audio.durationSeconds - oracleCase.durationSeconds) <= tolerance.audioDurationSeconds,
                audio.durationSeconds, oracleCase.durationSeconds);
        List<AudioPacket> packets = audio.packets == null ? Collections.<AudioPacket>emptyList() : audio.packets;
        report.check("audio.frames_present", packets.size() > 1, packets.size(), ">1");
        if (packets.size() < 2) {
            return;
        }
        double maxGapError = 0d;
        for (int i = 1; i < packets.size(); i++) {
            checkInterrupted();
            double expectedGap = packets.get(i - 1).sampleCount / (double) audio.sampleRate;
            maxGapError = Math.max(maxGapError,
                    Math.abs((packets.get(i).ptsSeconds - packets.get(i - 1).ptsSeconds) - expectedGap));
        }
        report.check("audio.pts_continuity",
                maxGapError <= tolerance.audioPtsContinuitySeconds,
                maxGapError, tolerance.audioPtsContinuitySeconds);
        double decodedStart = packets.get(0).ptsSeconds;
        double decodedEnd = packets.get(packets.size() - 1).ptsSeconds
                + packets.get(packets.size() - 1).sampleCount / (double) audio.sampleRate;
        report.check("audio.decoded_pts_start",
                Math.abs(decodedStart) <= tolerance.audioStartSeconds,
                decodedStart, 0d);
        report.check("audio.decoded_pts_end",
                Math.abs(decodedEnd - oracleCase.durationSeconds) <= tolerance.audioDurationSeconds,
                decodedEnd, oracleCase.durationSeconds);
        boolean samplesFinite = finite(audio.samples);
        report.check("audio.finite", samplesFinite, samplesFinite, true);
        if (!samplesFinite) {
            return;
        }
        int sampleCountTolerance = (int) Math.ceil(tolerance.audioDurationSeconds * contract.source.sampleRate);
        report.check("audio.decoded_sample_count",
                Math.abs(audio.samples.length - oracleCase.expectedDecodedAudioSamples) <= sampleCountTolerance,
                audio.samples.length, oracleCase.expectedDecodedAudioSamples);
        long timestampedSamples = 0;
        for (AudioPacket packet : packets) {
            checkInterrupted();
            timestampedSamples += packet.sampleCount;
        }
        if (audio.sampleRate == contract.source.sampleRate) {
            report.check("audio.timestamped_sample_count",
                    timestampedSamples == audio.samples.length,
                    timestampedSamples, audio.samples.length);
        }

        List<Map<String, Object>> audioWindows = new ArrayList<Map<String, Object>>(oracleCase.audioProbes.size());
        for (int i = 0; i < oracleCase.audioProbes.size(); i++) {
            checkInterrupted();
            OracleContract.AudioProbe probe = oracleCase.audioProbes.get(i);
            int left = (int) Math.rint((probe.centerSeconds - decodedStart - probe.windowSeconds / 2d)
                    * contract.source.sampleRate);
            int right = (int) Math.rint((probe.centerSeconds - decodedStart + probe.windowSeconds / 2d)
                    * contract.source.sampleRate);
            int expectedLength = right - left;
            left = clamp(left, 0, audio.samples.length);
            right = clamp(right, 0, audio.samples.length);
            float[] segment = Arrays.copyOfRange(audio.samples, left, right);
            report.check("audio.window_" + i + ".samples",
                    segment.length == expectedLength,
                    segment.length, expectedLength);
            if (segment.length == 0) {
                continue;
            }
            double rms = rms(segment);
            double hz = dominantFrequency(segment, contract.source.sampleRate);
            report.check("audio.window_" + i + ".rms",
                    Math.abs(rms - probe.rms)
                            <= probe.rms * tolerance.audioRmsRelative + tolerance.audioRmsAbsolute,
                    rms, probe.rms);
            // Silence has no defined dominant frequency; its RMS and window length remain mandatory.
            if (!(generatedTitle && probe.rms == 0 && probe.frequencyHz == 0)) {
                report.check("audio.window_" + i + ".frequency",
                        Math.abs(hz - probe.frequencyHz) <= tolerance.audioFrequencyHz,
                        hz, probe.frequencyHz);
            }
            LinkedHashMap<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("center_seconds", probe.centerSeconds);
            item.put("rms", rms);
            item.put("frequency_hz", hz);
            audioWindows.add(item);
        }
        report.metric("audio_windows", audioWindows);
        report.metric("decoded_audio_samples", audio.samples.length);
        LinkedHashMap<String, Object> timeline = new LinkedHashMap<String, Object>();
        timeline.put("start_seconds", decodedStart);
        timeline.put("end_seconds", decodedEnd);
        timeline.put("max_continuity_error_seconds", maxGapError);
        timeline.put("frames", packets.size());
        timeline.put("timestamped_samples", timestampedSamples);
        report.metric("audio_timeline", timeline);
    }

    private MatchedFrame matchBarcode(OracleContract contract, OracleContract.OracleCase oracleCase,
                                      Frame frame, int nominal, int start, int end) {
        double[][] observed = barcodeObservations(frame, oracleCase);
        double bestError = Double.MAX_VALUE;
        int bestFrame = -1;
        for (int sourceFrame = start; sourceFrame <= end; sourceFrame++) {
            double error = 0d;
            for (int i = 0; i < oracleCase.barcodeRegions.size(); i++) {
                int high = ((sourceFrame >> oracleCase.barcodeRegions.get(i).bit) & 1) == 1
                        ? contract.source.barcodeHighRgb : contract.source.barcodeLowRgb;
                int low = high == contract.source.barcodeHighRgb
                        ? contract.source.barcodeLowRgb : contract.source.barcodeHighRgb;
                error = Math.max(error, Math.abs(observed[i][0] - high));
                error = Math.max(error, Math.abs(observed[i][1] - low));
            }
            if (error < bestError) {
                bestError = error;
                bestFrame = sourceFrame;
            }
        }
        return new MatchedFrame(frame.ptsSeconds, nominal, bestFrame, bestError);
    }

    private SpatialProbeResult measureSpatial(OracleContract contract, OracleContract.OracleCase oracleCase,
                                              OracleContract.Probe probe, Frame frame,
                                              MatchedFrame matchedFrame, RgbImage expected) throws IOException {
        RgbImage actual = requireImage(frame);
        validateImage(actual, oracleCase.width, oracleCase.height);
        validateImage(expected, oracleCase.width, oracleCase.height);
        double mae = 0d;
        int[] histogram = new int[256];
        boolean text = TextOracleContract.applies(contract, oracleCase);
        boolean watermark = WatermarkOracleContract.applies(contract, oracleCase);
        int values = 0;
        for (int i = 0; i < actual.rgb.length; i++) {
            checkInterrupted();
            if (text && TextOracleContract.excluded((i / 3) % actual.width, (i / 3) / actual.width)) continue;
            if (watermark && WatermarkOracleContract.excluded((i / 3) % actual.width, (i / 3) / actual.width)) continue;
            int delta = Math.abs((actual.rgb[i] & 0xFF) - (expected.rgb[i] & 0xFF));
            mae += delta;
            histogram[delta]++;
            values++;
        }
        mae /= values;
        double p95 = percentile(histogram, values, 0.95d);

        List<RegionExpectation> pixelRegions = pixelRegions(expected);
        double regionError = 0d;
        for (RegionExpectation region : pixelRegions) {
            checkInterrupted();
            if (text && TextOracleContract.overlaps(region.rect)) continue;
            if (watermark && WatermarkOracleContract.overlaps(region.rect)) continue;
            double[] observed = meanRgb(actual, region.rect);
            regionError = Math.max(regionError, maxChannelError(observed, region.rgb));
        }
        List<OracleContract.Rect> markerRects = markerRegions(contract, oracleCase.operation,
                matchedFrame.matchedSourceFrame);
        double markerError = 0d;
        for (OracleContract.Rect rect : markerRects) {
            checkInterrupted();
            if (text && TextOracleContract.overlaps(rect)) continue;
            if (watermark && WatermarkOracleContract.overlaps(rect)) continue;
            double[] observed = meanRgb(actual, rect);
            double[] ideal = meanRgb(expected, rect);
            markerError = Math.max(markerError, maxChannelError(observed, ideal));
        }
        return new SpatialProbeResult(probe.sourceFrame, matchedFrame.matchedSourceFrame, frame.ptsSeconds,
                Math.abs(frame.ptsSeconds - probe.outputSeconds), mae, p95, regionError, pixelRegions.size(),
                markerError);
    }

    private static double[][] barcodeObservations(Frame frame, OracleContract.OracleCase oracleCase) {
        if (frame.image != null) {
            validateImage(frame.image, oracleCase.width, oracleCase.height);
        }
        if (frame.barcodeLumaPairs != null) {
            return frame.barcodeLumaPairs;
        }
        RgbImage image = requireImageUnchecked(frame);
        double[][] observed = new double[oracleCase.barcodeRegions.size()][2];
        for (int i = 0; i < oracleCase.barcodeRegions.size(); i++) {
            OracleContract.BarcodeRegion region = oracleCase.barcodeRegions.get(i);
            observed[i][0] = meanLuma(image, region.rect);
            observed[i][1] = meanLuma(image, region.complementRect);
        }
        return observed;
    }

    public static RgbImage sourceFrame(OracleContract contract, int frameIndex) {
        int frame = clamp(frameIndex, 0, contract.source.frames - 1);
        RgbImage image = new RgbImage(contract.source.width, contract.source.height);
        fill(image, 0, 0, 160, 120, 210, 40, 40);
        fill(image, 160, 0, 320, 120, 35, 190, 55);
        fill(image, 0, 120, 160, 240, 40, 65, 215);
        fill(image, 160, 120, 320, 240, 215, 190, 35);
        for (int x = 40; x < contract.source.width; x += 40) {
            fill(image, x, 0, x + 2, contract.source.height, 28, 28, 28);
        }
        for (int y = 40; y < contract.source.height; y += 40) {
            fill(image, 0, y, contract.source.width, y + 2, 28, 28, 28);
        }
        fill(image, 8, 8, 32, 32, 225, 40, 210);
        fill(image, 288, 8, 312, 32, 30, 215, 215);
        fill(image, 8, 208, 32, 232, 225, 135, 25);
        fill(image, 288, 208, 312, 232, 120, 60, 215);
        fill(image, 56, 52, 64, 84, 230, 230, 230);
        fill(image, 56, 76, 88, 84, 230, 230, 230);
        fill(image, 224, 56, 248, 72, 15, 15, 15);
        fill(image, 240, 64, 256, 80, 15, 15, 15);
        for (int bit = 0; bit < contract.source.barcodeBits; bit++) {
            int x = 96 + bit * 18;
            boolean high = ((frame >> bit) & 1) == 1;
            int top = high ? contract.source.barcodeHighRgb : contract.source.barcodeLowRgb;
            int bottom = high ? contract.source.barcodeLowRgb : contract.source.barcodeHighRgb;
            fill(image, x, 96, x + 16, 112, top, top, top);
            fill(image, x, 112, x + 16, 128, bottom, bottom, bottom);
        }
        int movingX = 48 + (frame * 6) % 216;
        fill(image, movingX, 176, movingX + 16, 192, 240, 240, 240);
        fill(image, movingX + 6, 182, movingX + 10, 186, 10, 10, 10);
        int movingY = 136 + (frame * 2) % 24;
        fill(image, 144, movingY, 160, movingY + 16, 220, 30, 200);
        return image;
    }

    public static RgbImage transformedFrame(OracleContract contract, OracleContract.Operation operation,
                                            int sourceFrame) {
        RgbImage frame = sourceFrame(contract, sourceFrame);
        RgbImage cropped = crop(frame, operation.cropLeft, operation.cropTop,
                operation.cropRight, operation.cropBottom);
        RgbImage rotated = rotate(cropped, operation.rotationDegrees);
        int[] dims = outputDimensions(operation);
        if (rotated.width != dims[0] || rotated.height != dims[1]) {
            return resize(rotated, dims[0], dims[1]);
        }
        return rotated;
    }

    public static int[] outputDimensions(OracleContract.Operation operation) {
        int width = operation.cropRight - operation.cropLeft;
        int height = operation.cropBottom - operation.cropTop;
        if (operation.rotationDegrees == 90 || operation.rotationDegrees == 270) {
            int swap = width;
            width = height;
            height = swap;
        }
        if (operation.outputHeight > 0) {
            long scaledWidth = (long) width * operation.outputHeight;
            if (height <= 0 || scaledWidth % height != 0 || scaledWidth / height > Integer.MAX_VALUE
                    || scaledWidth / height <= 0 || (scaledWidth / height) % 2 != 0
                    || operation.outputHeight % 2 != 0) {
                throw new IllegalArgumentException("Only exact, even-sized resizes are supported");
            }
            width = (int) (scaledWidth / height);
            height = operation.outputHeight;
        }
        return new int[]{width, height};
    }

    public static List<OracleContract.BarcodeRegion> barcodeRegions(OracleContract.Operation operation) {
        ArrayList<OracleContract.BarcodeRegion> regions = new ArrayList<OracleContract.BarcodeRegion>(7);
        for (int bit = 0; bit < 7; bit++) {
            regions.add(new OracleContract.BarcodeRegion(
                    bit,
                    mapRect(operation, new OracleContract.Rect(100 + 18 * bit, 100, 108 + 18 * bit, 108)),
                    mapRect(operation, new OracleContract.Rect(100 + 18 * bit, 116, 108 + 18 * bit, 124))));
        }
        return regions;
    }

    public static List<OracleContract.Rect> markerRegions(OracleContract contract,
                                                          OracleContract.Operation operation,
                                                          int sourceFrame) {
        int x = 48 + (sourceFrame * 6) % 216;
        int y = 136 + (sourceFrame * 2) % 24;
        ArrayList<OracleContract.Rect> regions = new ArrayList<OracleContract.Rect>(2);
        regions.add(mapRect(operation, new OracleContract.Rect(x + 2, 178, x + 14, 190)));
        regions.add(mapRect(operation, new OracleContract.Rect(146, y + 2, 158, y + 14)));
        return regions;
    }

    public static List<RegionExpectation> pixelRegions(RgbImage expected) {
        ArrayList<RegionExpectation> regions = new ArrayList<RegionExpectation>();
        for (int iy = 0; iy < 8; iy++) {
            int y = interpolateIndex(6, expected.height - 7, iy, 8);
            for (int ix = 0; ix < 10; ix++) {
                int x = interpolateIndex(6, expected.width - 7, ix, 10);
                OracleContract.Rect rect = new OracleContract.Rect(x - 2, y - 2, x + 3, y + 3);
                double[] mean = meanRgb(expected, rect);
                double[] std = stddev(expected, rect, mean);
                if (max(std[0], std[1], std[2]) < 2d) {
                    regions.add(new RegionExpectation(rect, mean));
                }
            }
        }
        return regions;
    }

    public static OracleContract.Rect mapRect(OracleContract.Operation operation, OracleContract.Rect rect) {
        int x0 = rect.left - operation.cropLeft;
        int y0 = rect.top - operation.cropTop;
        int x1 = rect.right - operation.cropLeft;
        int y1 = rect.bottom - operation.cropTop;
        int width = operation.cropRight - operation.cropLeft;
        int height = operation.cropBottom - operation.cropTop;
        int rotation = ((operation.rotationDegrees % 360) + 360) % 360;
        if (rotation == 90) {
            int nx0 = height - y1;
            int ny0 = x0;
            int nx1 = height - y0;
            int ny1 = x1;
            x0 = nx0;
            y0 = ny0;
            x1 = nx1;
            y1 = ny1;
        } else if (rotation == 180) {
            int nx0 = width - x1;
            int ny0 = height - y1;
            int nx1 = width - x0;
            int ny1 = height - y0;
            x0 = nx0;
            y0 = ny0;
            x1 = nx1;
            y1 = ny1;
        } else if (rotation == 270) {
            int nx0 = y0;
            int ny0 = width - x1;
            int nx1 = y1;
            int ny1 = width - x0;
            x0 = nx0;
            y0 = ny0;
            x1 = nx1;
            y1 = ny1;
        }
        int[] dims = outputDimensions(operation);
        double scale = dims[1] / (double) ((rotation == 90 || rotation == 270) ? width : height);
        OracleContract.Rect mapped = new OracleContract.Rect(
                (int) Math.ceil(x0 * scale),
                (int) Math.ceil(y0 * scale),
                (int) Math.floor(x1 * scale),
                (int) Math.floor(y1 * scale));
        if (mapped.left < 0 || mapped.top < 0 || mapped.right > dims[0] || mapped.bottom > dims[1]) {
            throw new IllegalArgumentException("Probe lies outside output");
        }
        return mapped;
    }

    public static double dominantFrequency(float[] samples, int sampleRate) {
        int length = samples.length;
        if (length < 2) {
            return 0d;
        }
        double[] real = new double[length];
        double[] imag = new double[length];
        double mean = 0d;
        for (float sample : samples) {
            mean += sample;
        }
        mean /= length;
        for (int i = 0; i < length; i++) {
            double window = 0.5d - 0.5d * Math.cos(2d * Math.PI * i / Math.max(1, length - 1));
            real[i] = (samples[i] - mean) * window;
        }
        exactLengthFft(real, imag);
        int best = 1;
        double bestMagnitude = -1d;
        int limit = length / 2;
        for (int i = 1; i <= limit; i++) {
            double magnitude = Math.hypot(real[i], imag[i]);
            if (magnitude > bestMagnitude) {
                bestMagnitude = magnitude;
                best = i;
            }
        }
        double delta = 0d;
        if (best > 0 && best < limit) {
            double left = Math.log(Math.max(Math.hypot(real[best - 1], imag[best - 1]), 1e-20d));
            double middle = Math.log(Math.max(Math.hypot(real[best], imag[best]), 1e-20d));
            double right = Math.log(Math.max(Math.hypot(real[best + 1], imag[best + 1]), 1e-20d));
            double denom = left - 2d * middle + right;
            if (Math.abs(denom) > 1e-12d) {
                delta = 0.5d * (left - right) / denom;
            }
        }
        return (best + delta) * sampleRate / length;
    }

    private static void exactLengthFft(double[] real, double[] imag) {
        int length = real.length;
        if ((length & (length - 1)) == 0) {
            fft(real, imag);
            return;
        }
        // Bluestein preserves NumPy's unpadded frequency bins for arbitrary window lengths.
        int size = 1;
        while (size < 2L * length - 1) {
            size <<= 1;
        }
        double[] aReal = new double[size];
        double[] aImag = new double[size];
        double[] bReal = new double[size];
        double[] bImag = new double[size];
        for (int i = 0; i < length; i++) {
            double angle = Math.PI * ((long) i * i % (2L * length)) / length;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            aReal[i] = real[i] * cos + imag[i] * sin;
            aImag[i] = imag[i] * cos - real[i] * sin;
            bReal[i] = cos;
            bImag[i] = sin;
            if (i > 0) {
                bReal[size - i] = cos;
                bImag[size - i] = sin;
            }
        }
        fft(aReal, aImag);
        fft(bReal, bImag);
        for (int i = 0; i < size; i++) {
            double productReal = aReal[i] * bReal[i] - aImag[i] * bImag[i];
            aImag[i] = -(aReal[i] * bImag[i] + aImag[i] * bReal[i]);
            aReal[i] = productReal;
        }
        fft(aReal, aImag);
        for (int i = 0; i < length; i++) {
            double angle = Math.PI * ((long) i * i % (2L * length)) / length;
            double cos = Math.cos(angle);
            double sin = Math.sin(angle);
            real[i] = (aReal[i] * cos - aImag[i] * sin) / size;
            imag[i] = (-aImag[i] * cos - aReal[i] * sin) / size;
        }
    }

    private static void fft(double[] real, double[] imag) {
        int n = real.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >>> 1;
            for (; (j & bit) != 0; bit >>>= 1) {
                j ^= bit;
            }
            j ^= bit;
            if (i < j) {
                double tr = real[i];
                real[i] = real[j];
                real[j] = tr;
                double ti = imag[i];
                imag[i] = imag[j];
                imag[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2d * Math.PI / len;
            double wLenR = Math.cos(angle);
            double wLenI = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double wR = 1d;
                double wI = 0d;
                for (int j = 0; j < len / 2; j++) {
                    int u = i + j;
                    int v = u + len / 2;
                    double vR = real[v] * wR - imag[v] * wI;
                    double vI = real[v] * wI + imag[v] * wR;
                    real[v] = real[u] - vR;
                    imag[v] = imag[u] - vI;
                    real[u] += vR;
                    imag[u] += vI;
                    double nextWR = wR * wLenR - wI * wLenI;
                    double nextWI = wR * wLenI + wI * wLenR;
                    wR = nextWR;
                    wI = nextWI;
                }
            }
        }
    }

    private static boolean finite(float[] samples) {
        if (samples == null) {
            return false;
        }
        for (float sample : samples) {
            if (!Float.isFinite(sample)) {
                return false;
            }
        }
        return true;
    }

    private static double rms(float[] samples) {
        if (samples.length == 0) {
            return 0d;
        }
        double sum = 0d;
        for (float sample : samples) {
            sum += (double) sample * sample;
        }
        return Math.sqrt(sum / samples.length);
    }

    private static RgbImage crop(RgbImage source, int left, int top, int right, int bottom) {
        RgbImage cropped = new RgbImage(right - left, bottom - top);
        for (int y = top; y < bottom; y++) {
            System.arraycopy(source.rgb, ((y * source.width) + left) * 3,
                    cropped.rgb, ((y - top) * cropped.width) * 3,
                    cropped.width * 3);
        }
        return cropped;
    }

    private static RgbImage rotate(RgbImage source, int rotationDegrees) {
        int rotation = ((rotationDegrees % 360) + 360) % 360;
        if (rotation == 0) {
            return source;
        }
        if (rotation == 180) {
            RgbImage rotated = new RgbImage(source.width, source.height);
            for (int y = 0; y < source.height; y++) {
                for (int x = 0; x < source.width; x++) {
                    copyPixel(source, x, y, rotated, source.width - 1 - x, source.height - 1 - y);
                }
            }
            return rotated;
        }
        RgbImage rotated = new RgbImage(source.height, source.width);
        for (int y = 0; y < source.height; y++) {
            for (int x = 0; x < source.width; x++) {
                if (rotation == 90) {
                    copyPixel(source, x, y, rotated, source.height - 1 - y, x);
                } else if (rotation == 270) {
                    copyPixel(source, x, y, rotated, y, source.width - 1 - x);
                } else {
                    throw new IllegalArgumentException("Unsupported rotation: " + rotation);
                }
            }
        }
        return rotated;
    }

    private static RgbImage resize(RgbImage source, int width, int height) {
        return resizeAxis(resizeAxis(source, width, true), height, false);
    }

    private static RgbImage resizeAxis(RgbImage source, int length, boolean horizontal) {
        int sourceLength = horizontal ? source.width : source.height;
        if (length == sourceLength) {
            return source;
        }
        RgbImage resized = new RgbImage(horizontal ? length : source.width,
                horizontal ? source.height : length);
        double scale = sourceLength / (double) length;
        double support = Math.max(1d, scale);
        int otherLength = horizontal ? source.height : source.width;
        // Pillow widens the bilinear filter for reduction and rounds each 8-bit axis pass.
        for (int index = 0; index < length; index++) {
            checkInterruptedUnchecked();
            double center = (index + 0.5d) * scale;
            int first = Math.max(0, (int) (center - support + 0.5d));
            int stop = Math.min(sourceLength, (int) (center + support + 0.5d));
            double[] weights = new double[stop - first];
            double total = 0d;
            for (int i = 0; i < weights.length; i++) {
                weights[i] = Math.max(0d, 1d - Math.abs((first + i + 0.5d - center) / support));
                total += weights[i];
            }
            int[] coefficients = new int[weights.length];
            for (int i = 0; i < weights.length; i++) {
                coefficients[i] = (int) Math.round(weights[i] / total * (1 << 22));
            }
            for (int other = 0; other < otherLength; other++) {
                for (int channel = 0; channel < 3; channel++) {
                    int sum = 1 << 21;
                    for (int i = 0; i < coefficients.length; i++) {
                        sum += coefficients[i] * sample(source, horizontal ? first + i : other,
                                horizontal ? other : first + i, channel);
                    }
                    set(resized, horizontal ? index : other, horizontal ? other : index,
                            channel, clamp(sum >> 22, 0, 255));
                }
            }
        }
        return resized;
    }

    private static double percentile(int[] histogram, int total, double percentile) {
        double rank = (total - 1) * percentile;
        int lowerRank = (int) Math.floor(rank);
        int upperRank = (int) Math.ceil(rank);
        int lowerValue = -1;
        int seen = 0;
        for (int i = 0; i < histogram.length; i++) {
            seen += histogram[i];
            if (lowerValue < 0 && seen > lowerRank) {
                lowerValue = i;
            }
            if (seen > upperRank) {
                return lowerValue + (i - lowerValue) * (rank - lowerRank);
            }
        }
        return histogram.length - 1;
    }

    private static double[] meanRgb(RgbImage image, OracleContract.Rect rect) {
        double[] rgb = new double[3];
        int pixels = 0;
        for (int y = rect.top; y < rect.bottom; y++) {
            int row = y * image.width * 3;
            for (int x = rect.left; x < rect.right; x++) {
                int index = row + x * 3;
                rgb[0] += image.rgb[index] & 0xFF;
                rgb[1] += image.rgb[index + 1] & 0xFF;
                rgb[2] += image.rgb[index + 2] & 0xFF;
                pixels++;
            }
        }
        rgb[0] /= pixels;
        rgb[1] /= pixels;
        rgb[2] /= pixels;
        return rgb;
    }

    private static double meanLuma(RgbImage image, OracleContract.Rect rect) {
        double[] rgb = meanRgb(image, rect);
        return (rgb[0] + rgb[1] + rgb[2]) / 3d;
    }

    private static double[] stddev(RgbImage image, OracleContract.Rect rect, double[] mean) {
        double[] variance = new double[3];
        int pixels = 0;
        for (int y = rect.top; y < rect.bottom; y++) {
            int row = y * image.width * 3;
            for (int x = rect.left; x < rect.right; x++) {
                int index = row + x * 3;
                for (int channel = 0; channel < 3; channel++) {
                    double delta = (image.rgb[index + channel] & 0xFF) - mean[channel];
                    variance[channel] += delta * delta;
                }
                pixels++;
            }
        }
        variance[0] = Math.sqrt(variance[0] / pixels);
        variance[1] = Math.sqrt(variance[1] / pixels);
        variance[2] = Math.sqrt(variance[2] / pixels);
        return variance;
    }

    private static double maxChannelError(double[] a, double[] b) {
        return max(Math.abs(a[0] - b[0]), Math.abs(a[1] - b[1]), Math.abs(a[2] - b[2]));
    }

    private static int nearestFrameIndex(List<Frame> frames, double targetSeconds) {
        int bestIndex = 0;
        double bestDelta = Double.MAX_VALUE;
        for (int i = 0; i < frames.size(); i++) {
            checkInterruptedUnchecked();
            double delta = Math.abs(frames.get(i).ptsSeconds - targetSeconds);
            if (delta < bestDelta) {
                bestDelta = delta;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static double min(List<Double> values) {
        double best = Double.MAX_VALUE;
        for (Double value : values) {
            best = Math.min(best, value.doubleValue());
        }
        return best;
    }

    private static double nearestAccepted(double rate, List<Double> accepted) {
        double best = accepted.get(0);
        double delta = Math.abs(rate - best);
        for (int i = 1; i < accepted.size(); i++) {
            double value = accepted.get(i);
            double candidate = Math.abs(rate - value);
            if (candidate < delta) {
                delta = candidate;
                best = value;
            }
        }
        return best;
    }

    private static double maxMae(List<SpatialProbeResult> results) {
        double best = 0d;
        for (SpatialProbeResult result : results) {
            best = Math.max(best, result.mae);
        }
        return best;
    }

    private static double maxP95(List<SpatialProbeResult> results) {
        double best = 0d;
        for (SpatialProbeResult result : results) {
            best = Math.max(best, result.p95);
        }
        return best;
    }

    private static double maxRegionError(List<SpatialProbeResult> results) {
        double best = 0d;
        for (SpatialProbeResult result : results) {
            best = Math.max(best, result.regionError);
        }
        return best;
    }

    private static double maxMarkerError(List<SpatialProbeResult> results) {
        double best = 0d;
        for (SpatialProbeResult result : results) {
            best = Math.max(best, result.markerError);
        }
        return best;
    }

    private static double median(double[] values) {
        double[] copy = Arrays.copyOf(values, values.length);
        Arrays.sort(copy);
        int middle = copy.length / 2;
        return copy.length % 2 == 0 ? (copy[middle - 1] + copy[middle]) / 2d : copy[middle];
    }

    private static int interpolateIndex(int start, int end, int index, int count) {
        if (count == 1) {
            return start;
        }
        double value = start + index * (end - start) / (double) (count - 1);
        return (int) value;
    }

    private static void fill(RgbImage image, int left, int top, int right, int bottom,
                             int r, int g, int b) {
        for (int y = top; y < bottom; y++) {
            int row = y * image.width * 3;
            for (int x = left; x < right; x++) {
                int index = row + x * 3;
                image.rgb[index] = (byte) r;
                image.rgb[index + 1] = (byte) g;
                image.rgb[index + 2] = (byte) b;
            }
        }
    }

    private static void copyPixel(RgbImage source, int sourceX, int sourceY,
                                  RgbImage target, int targetX, int targetY) {
        int sourceIndex = (sourceY * source.width + sourceX) * 3;
        int targetIndex = (targetY * target.width + targetX) * 3;
        target.rgb[targetIndex] = source.rgb[sourceIndex];
        target.rgb[targetIndex + 1] = source.rgb[sourceIndex + 1];
        target.rgb[targetIndex + 2] = source.rgb[sourceIndex + 2];
    }

    private static int sample(RgbImage image, int x, int y, int channel) {
        return image.rgb[(y * image.width + x) * 3 + channel] & 0xFF;
    }

    private static void set(RgbImage image, int x, int y, int channel, int value) {
        image.rgb[(y * image.width + x) * 3 + channel] = (byte) value;
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    private static double max(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }

    private static List<Integer> list(int a, int b) {
        ArrayList<Integer> values = new ArrayList<Integer>(2);
        values.add(a);
        values.add(b);
        return values;
    }

    public static final class Candidate {
        public final String path;
        public final String sha256;
        public final long fileBytes;
        public final int videoTrackCount;
        public final int audioTrackCount;
        public final VideoTrack video;
        public final AudioTrack audio;

        public Candidate(String path, String sha256, long fileBytes, int videoTrackCount,
                         int audioTrackCount, VideoTrack video, AudioTrack audio) {
            this.path = path;
            this.sha256 = sha256;
            this.fileBytes = fileBytes;
            this.videoTrackCount = videoTrackCount;
            this.audioTrackCount = audioTrackCount;
            this.video = video;
            this.audio = audio;
        }
    }

    public static final class VideoTrack {
        public final String codec;
        public final String sampleAspectRatio;
        public final int width;
        public final int height;
        public final int rotationDegrees;
        public final double durationSeconds;
        public final List<Frame> frames;

        public VideoTrack(String codec, String sampleAspectRatio, int width, int height,
                          int rotationDegrees, double durationSeconds, List<Frame> frames) {
            this.codec = codec;
            this.sampleAspectRatio = sampleAspectRatio;
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
            this.durationSeconds = durationSeconds;
            this.frames = frames;
        }
    }

    public static final class Frame {
        public final double ptsSeconds;
        public final RgbImage image;
        public final double[][] barcodeLumaPairs;

        public Frame(double ptsSeconds, RgbImage image) {
            this(ptsSeconds, image, null);
        }

        public Frame(double ptsSeconds, RgbImage image, double[][] barcodeLumaPairs) {
            this.ptsSeconds = ptsSeconds;
            this.image = image;
            this.barcodeLumaPairs = barcodeLumaPairs;
        }
    }

    public static final class AudioTrack {
        public final String codec;
        public final int sampleRate;
        public final int channels;
        public final double startSeconds;
        public final double durationSeconds;
        public final List<AudioPacket> packets;
        public final float[] samples;

        public AudioTrack(String codec, int sampleRate, int channels, double startSeconds,
                          double durationSeconds, List<AudioPacket> packets, float[] samples) {
            this.codec = codec;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.startSeconds = startSeconds;
            this.durationSeconds = durationSeconds;
            this.packets = packets;
            this.samples = samples;
        }
    }

    public static final class AudioPacket {
        public final double ptsSeconds;
        public final int sampleCount;

        public AudioPacket(double ptsSeconds, int sampleCount) {
            this.ptsSeconds = ptsSeconds;
            this.sampleCount = sampleCount;
        }
    }

    public static final class RgbImage {
        public final int width;
        public final int height;
        public final byte[] rgb;

        public RgbImage(int width, int height) {
            this(width, height, new byte[width * height * 3]);
        }

        public RgbImage(int width, int height, byte[] rgb) {
            this.width = width;
            this.height = height;
            this.rgb = rgb;
        }
    }

    private static RgbImage requireImage(Frame frame) throws IOException {
        if (frame.image == null) {
            throw new IOException("Probe frame image was not retained");
        }
        return frame.image;
    }

    private static RgbImage requireImageUnchecked(Frame frame) {
        if (frame.image == null) {
            throw new IllegalStateException("Frame image unavailable");
        }
        return frame.image;
    }

    private static void validateImage(RgbImage image, int width, int height) {
        if (image == null || image.width != width || image.height != height || image.rgb == null
                || image.rgb.length != (long) width * height * 3) {
            throw new IllegalArgumentException("RGB image does not match expected geometry");
        }
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Verification interrupted");
        }
    }

    private static void checkInterruptedUnchecked() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Verification interrupted");
        }
    }

    public static final class Report {
        private final String caseId;
        private final String candidatePath;
        private final String candidateSha256;
        private final List<Check> checks = new ArrayList<Check>();
        private final Map<String, Object> metrics = new LinkedHashMap<String, Object>();

        Report(String caseId, String candidatePath, String candidateSha256) {
            this.caseId = caseId;
            this.candidatePath = candidatePath;
            this.candidateSha256 = candidateSha256;
        }

        void check(String assertion, boolean passed, Object actual, Object expected) {
            checks.add(new Check(assertion, passed, actual, expected));
        }

        boolean lastPassed() {
            return checks.get(checks.size() - 1).passed;
        }

        void metric(String key, Object value) {
            metrics.put(key, value);
        }

        public boolean passed() {
            for (Check check : checks) {
                if (!check.passed) {
                    return false;
                }
            }
            return !checks.isEmpty();
        }

        public Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("case", caseId);
            map.put("candidate", candidatePath);
            map.put("candidate_sha256", candidateSha256);
            map.put("passed", passed());
            ArrayList<String> failures = new ArrayList<String>();
            ArrayList<Object> serializedChecks = new ArrayList<Object>(checks.size());
            for (Check check : checks) {
                if (!check.passed) {
                    failures.add(check.assertion);
                }
                serializedChecks.add(check.toMap());
            }
            map.put("status", failures.contains("checker.completed_without_error")
                    ? "ERROR" : passed() ? "PASS" : "FAIL");
            map.put("failed_assertions", failures);
            map.put("checks", serializedChecks);
            map.put("metrics", metrics);
            return map;
        }
    }

    private static final class Check {
        private final String assertion;
        private final boolean passed;
        private final Object actual;
        private final Object expected;

        private Check(String assertion, boolean passed, Object actual, Object expected) {
            this.assertion = assertion;
            this.passed = passed;
            this.actual = actual;
            this.expected = expected;
        }

        private Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("assertion", assertion);
            map.put("passed", passed);
            map.put("actual", actual);
            map.put("expected", expected);
            return map;
        }
    }

    public static final class RegionExpectation {
        public final OracleContract.Rect rect;
        public final double[] rgb;

        public RegionExpectation(OracleContract.Rect rect, double[] rgb) {
            this.rect = rect;
            this.rgb = rgb;
        }
    }

    private static final class MatchedFrame {
        private final double ptsSeconds;
        private final int nominalSourceFrame;
        private final int matchedSourceFrame;
        private final double maxLumaError;

        private MatchedFrame(double ptsSeconds, int nominalSourceFrame,
                             int matchedSourceFrame, double maxLumaError) {
            this.ptsSeconds = ptsSeconds;
            this.nominalSourceFrame = nominalSourceFrame;
            this.matchedSourceFrame = matchedSourceFrame;
            this.maxLumaError = maxLumaError;
        }
    }

    private static final class SpatialProbeResult {
        private final int requestedSourceFrame;
        private final int matchedSourceFrame;
        private final double ptsSeconds;
        private final double probeDeltaSeconds;
        private final double mae;
        private final double p95;
        private final double regionError;
        private final int regionCount;
        private final double markerError;

        private SpatialProbeResult(int requestedSourceFrame, int matchedSourceFrame,
                                   double ptsSeconds, double probeDeltaSeconds, double mae,
                                   double p95, double regionError, int regionCount,
                                   double markerError) {
            this.requestedSourceFrame = requestedSourceFrame;
            this.matchedSourceFrame = matchedSourceFrame;
            this.ptsSeconds = ptsSeconds;
            this.probeDeltaSeconds = probeDeltaSeconds;
            this.mae = mae;
            this.p95 = p95;
            this.regionError = regionError;
            this.regionCount = regionCount;
            this.markerError = markerError;
        }

        private Map<String, Object> toMap() {
            LinkedHashMap<String, Object> map = new LinkedHashMap<String, Object>();
            map.put("requested_source_frame", requestedSourceFrame);
            map.put("matched_source_frame", matchedSourceFrame);
            map.put("pts", ptsSeconds);
            map.put("probe_delta_seconds", probeDeltaSeconds);
            map.put("mae", mae);
            map.put("p95", p95);
            map.put("region_error", regionError);
            map.put("region_count", regionCount);
            map.put("marker_error", markerError);
            return map;
        }
    }
}
