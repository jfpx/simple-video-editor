package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Independent inspection: no Transformer metadata or success callback is trusted. */
public final class OutputInspector {
    private static final long MAX_BYTES = 32L * 1024 * 1024;
    private static final long MAX_US = 10_000_000;
    private static final int MAX_PACKETS = 20000;

    private OutputInspector() { }

    public static final class Result {
        public final String report;
        public final double audioRms;
        public final double toneHz;

        Result(String report, double audioRms, double toneHz) {
            this.report = report;
            this.audioRms = audioRms;
            this.toneHz = toneHz;
        }
    }

    public static Result inspect(File file, EditConfig config, Result reference,
                                 SelfTestFixture.Guard guard) throws IOException, TimeoutException {
        StringBuilder report = new StringBuilder();
        try {
            require(file.isFile() && file.length() > 0 && file.length() <= MAX_BYTES,
                    "File exists and is bounded to 32 MiB", report);
            double expectedMs = (config.endMs - config.startMs) / (double) config.speed;
            double toleranceMs = Math.max(120, 2500.0 / SelfTestFixture.FPS / config.speed);
            boolean hasAudio = scan(file, expectedMs, toleranceMs, config.volume == 0, guard, report);
            inspectFrames(file, config, expectedMs, toleranceMs, guard, report);
            if (!hasAudio) {
                require(config.volume == 0, "No audio track: mute is structurally silent", report);
                return new Result(report.toString(), 0, 0);
            }
            AudioStats stats = decodeAudio(file, expectedMs, guard, report);
            double expectedRms = reference == null
                    ? SelfTestFixture.TONE_AMPLITUDE / Math.sqrt(2)
                    : reference.audioRms * config.volume;
            if (config.volume == 0) {
                require(stats.allSamples.rms() < .002 && stats.peak < .008,
                        "Entire mute PCM including endpoints: RMS=" + number(stats.allSamples.rms())
                                + ", peak=" + number(stats.peak), report);
                require(stats.rms() < 0.002, "Mute PCM RMS=" + number(stats.rms())
                        + " < 0.002", report);
                for (AudioStats window : stats.windows) {
                    require(window.rms() < 0.002, "Mute sustained in PCM window", report);
                }
            } else {
                require(expectedRms > 0.01, "Audible validated RMS reference", report);
                require(Math.abs(stats.rms() / expectedRms - 1) <= 0.22,
                        "PCM RMS=" + number(stats.rms()) + ", expected=" + number(expectedRms)
                                + " (22% AAC/gain tolerance)", report);
                for (AudioStats window : stats.windows) {
                    require(Math.abs(window.rms() / expectedRms - 1) <= 0.25,
                            "Sustained window RMS=" + number(window.rms()), report);
                    require(Math.abs(window.frequency() - SelfTestFixture.TONE_HZ) <= 35,
                            "Pitch-preserving window tone=" + number(window.frequency())
                                    + " Hz (1000 +/-35)", report);
                }
                require(Math.abs(stats.frequency() - SelfTestFixture.TONE_HZ) <= 35,
                        "Decoded tone=" + number(stats.frequency()) + " Hz", report);
            }
            return new Result(report.toString(), stats.rms(), stats.frequency());
        } catch (IOException error) {
            throw new IOException("Independent inspection failed:\n" + report, error);
        } catch (TimeoutException | CancellationException error) {
            error.addSuppressed(new IOException("Independent inspection interrupted:\n" + report));
            throw error;
        } catch (RuntimeException error) {
            error.addSuppressed(new IOException("Independent inspection aborted:\n" + report));
            throw error;
        }
    }

    private static boolean scan(File file, double expectedMs, double toleranceMs, boolean mute,
                             SelfTestFixture.Guard guard, StringBuilder report)
            throws IOException, TimeoutException {
        try (Resources resources = new Resources()) {
            MediaExtractor extractor = resources.extractor;
            extractor.setDataSource(file.getAbsolutePath());
            require(extractor.getTrackCount() <= 4, "Bounded track count", report);
            int videos = 0;
            int audios = 0;
            for (int track = 0; track < extractor.getTrackCount(); track++) {
                guard.check();
                MediaFormat format = extractor.getTrackFormat(track);
                String mime = format.getString(MediaFormat.KEY_MIME);
                boolean video = mime != null && mime.startsWith("video/");
                boolean audio = mime != null && mime.startsWith("audio/");
                if (!video && !audio) {
                    continue;
                }
                videos += video ? 1 : 0;
                audios += audio ? 1 : 0;
                extractor.selectTrack(track);
                extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC);
                List<Long> times = new ArrayList<>();
                int syncFrames = 0;
                while (extractor.getSampleTime() >= 0) {
                    guard.check();
                    long pts = extractor.getSampleTime();
                    requireBound(pts <= MAX_US && times.size() < MAX_PACKETS,
                            "Timestamp/packet scan limit exceeded");
                    times.add(pts);
                    if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                        syncFrames++;
                    }
                    if (!extractor.advance()) {
                        break;
                    }
                }
                extractor.unselectTrack(track);
                require(times.size() >= (video ? 12 : 20), mime + " real packet count="
                        + times.size(), report);
                Collections.sort(times); // Presentation order can differ from B-frame decode order.
                long first = times.get(0);
                long last = times.get(times.size() - 1);
                require(first <= 100_000, mime + " starts near zero: " + first + " us", report);
                long largestGap = 0;
                for (int i = 1; i < times.size(); i++) {
                    long gap = times.get(i) - times.get(i - 1);
                    requireBound(gap > 0, "Duplicate " + mime + " presentation timestamp");
                    largestGap = Math.max(largestGap, gap);
                }
                require(largestGap <= (video ? 180_000 : 100_000),
                        mime + " largest timestamp gap=" + largestGap + " us", report);
                require(Math.abs(last / 1000.0 - expectedMs) <= toleranceMs,
                        mime + " last PTS=" + number(last / 1000.0)
                                + " ms; expected end=" + number(expectedMs), report);
                require(format.containsKey(MediaFormat.KEY_DURATION),
                        mime + " supplies track duration", report);
                double duration = format.getLong(MediaFormat.KEY_DURATION) / 1000.0;
                require(Math.abs(duration - expectedMs) <= toleranceMs,
                        mime + " duration=" + number(duration) + " ms (+/-"
                                + number(toleranceMs) + ")", report);
                if (video) {
                    require(syncFrames > 0, "Video contains sync samples", report);
                }
            }
            require(videos == 1 && (audios == 1 || (mute && audios == 0)),
                    "Track counts: video=" + videos + ", audio=" + audios
                            + (mute ? " (mute permits absent audio)" : ""), report);
            return audios == 1;
        }
    }

    public static void verifyNonKeyframeTrim(File fixture, long startMs,
                                             SelfTestFixture.Guard guard)
            throws IOException, TimeoutException {
        try (Resources resources = new Resources()) {
            MediaExtractor extractor = resources.extractor;
            extractor.setDataSource(fixture.getAbsolutePath());
            boolean found = false;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/")) {
                    extractor.selectTrack(i);
                    found = true;
                    break;
                }
            }
            requireBound(found, "Fixture video missing for non-keyframe trim assertion");
            extractor.seekTo(startMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            requireBound(extractor.getSampleTime() >= 0 && extractor.getSampleTime() < startMs * 1000,
                    "Trim point must follow an earlier keyframe");
            int count = 0;
            while (extractor.getSampleTime() >= 0 && extractor.getSampleTime() <= startMs * 1000) {
                guard.check();
                requireBound(++count <= MAX_PACKETS, "Non-keyframe verification scan bound");
                requireBound(extractor.getSampleTime() != startMs * 1000
                                || (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) == 0,
                        "Fixture trim point unexpectedly is a keyframe; non-keyframe assertion unverified");
                if (!extractor.advance()) {
                    break;
                }
            }
        }
    }

    private static void inspectFrames(File file, EditConfig config, double durationMs,
                                      double toleranceMs, SelfTestFixture.Guard guard,
                                      StringBuilder report) throws IOException, TimeoutException {
        try (Resources resources = new Resources()) {
            MediaMetadataRetriever retriever = resources.retriever = new MediaMetadataRetriever();
            retriever.setDataSource(file.getAbsolutePath());
            String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            require(duration != null && Math.abs(Long.parseLong(duration) - durationMs) <= toleranceMs,
                    "Retriever independently confirms duration", report);
            double width = SelfTestFixture.WIDTH * (config.cropRight - config.cropLeft);
            double height = SelfTestFixture.HEIGHT * (config.cropBottom - config.cropTop);
            if (config.rotationDegrees == 90 || config.rotationDegrees == 270) {
                double swap = width;
                width = height;
                height = swap;
            }
            if (config.outputHeight != 0) {
                width *= config.outputHeight / height;
                height = config.outputHeight;
            }
            int previousId = -1;
            double previousMovement = -1;
            double[] fractions = {0.01, 0.13, 0.33, 0.57, 0.79, 0.90, 0.98};
            for (double fraction : fractions) {
                guard.check();
                long outputUs = Math.round(durationMs * fraction * 1000);
                int expectedId = SelfTestFixture.frameIndexAt(
                        config.startMs * 1000 + Math.round(outputUs * config.speed));
                Bitmap frame = retriever.getFrameAtTime(outputUs, MediaMetadataRetriever.OPTION_CLOSEST);
                require(frame != null, "Decoded non-sync frame at " + outputUs + " us", report);
                try {
                    require(Math.abs(frame.getWidth() - width) <= 3
                                    && Math.abs(frame.getHeight() - height) <= 3,
                            "Display frame geometry=" + frame.getWidth() + "x" + frame.getHeight()
                                    + ", expected=" + number(width) + "x" + number(height), report);
                    require(frame.getWidth() * (long) frame.getHeight() <= 1024 * 1024,
                            "Decoded frame allocation bounded", report);
                    int id = decodeId(frame, config);
                    require(Math.abs(id - expectedId) <= 2,
                            "Decoded binary source frame=" + id + ", expected=" + expectedId
                                    + " (+/-2 frames, includes non-keyframe trim)", report);
                    require(id > previousId, "Binary source identifiers advance", report);
                    previousId = id;
                    checkColors(frame, config, id, report);
                    double movement = movement(frame, config);
                    double expectedMovement = 0.2 + 0.6 * id / (SelfTestFixture.FRAME_COUNT - 1.0);
                    require(Math.abs(movement - expectedMovement) <= 0.025,
                            "Decoded moving marker source x=" + number(movement)
                                    + ", expected=" + number(expectedMovement), report);
                    require(movement > previousMovement, "Marker movement advances with source time", report);
                    previousMovement = movement;
                    if (!config.overlayText.isEmpty()) {
                        inspectOverlay(frame, config, id, report);
                    }
                } finally {
                    frame.recycle();
                }
            }
        }
    }

    private static int decodeId(Bitmap frame, EditConfig config) throws IOException {
        int id = 0;
        for (int bit = 0; bit < 8; bit++) {
            double x = 0.2 + (bit + 0.5) * 0.075;
            int rgb = sample(frame, map(config, x, 0.13));
            double light = (((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255)) / 3.0;
            requireBound(light < 65 || light > 190, "Binary frame cell is not black or white");
            if (light > 190) {
                id |= 1 << bit;
            }
        }
        requireBound(id < SelfTestFixture.FRAME_COUNT, "Invalid decoded binary frame " + id);
        return id;
    }

    private static void checkColors(Bitmap frame, EditConfig config, int id,
                                    StringBuilder report) throws IOException {
        double[] xs = {0.17, 0.32, 0.43, 0.58, 0.68, 0.83};
        double[] ys = {0.29, 0.39, 0.61, 0.86};
        int checked = 0;
        int matched = 0;
        double worst = 0;
        for (double x : xs) {
            for (double y : ys) {
                double[] point = map(config, x, y);
                if (!config.overlayText.isEmpty() && point[0] > 0.08 && point[0] < 0.92
                        && point[1] > 0.34 && point[1] < 0.66) {
                    continue;
                }
                int expected = SelfTestFixture.colorAt(id, x, y);
                double distance = colorDistance(sample(frame, point), expected);
                worst = Math.max(worst, distance);
                checked++;
                matched += distance <= 65 ? 1 : 0;
            }
        }
        require(checked >= 12 && matched == checked,
                "Mapped quadrant RGB probes=" + matched + "/" + checked
                        + ", worst channel error=" + number(worst) + " <=65", report);
        // Full-source cases additionally establish corner identity, not just dimensions.
        if (config.cropLeft == 0 && config.cropTop == 0
                && config.cropRight == 1 && config.cropBottom == 1) {
            for (double x : new double[]{0.04, 0.96}) {
                for (double y : new double[]{0.04, 0.96}) {
                    require(colorDistance(sample(frame, map(config, x, y)),
                                    SelfTestFixture.colorAt(id, x, y)) <= 80,
                            "Distinct transformed corner marker", report);
                }
            }
        }
    }

    private static double movement(Bitmap frame, EditConfig config) throws IOException {
        double sum = 0;
        int count = 0;
        for (double x = 0.165; x <= 0.835; x += 0.005) {
            int rgb = sample(frame, map(config, x, 0.72));
            if (white(rgb)) {
                sum += x;
                count++;
            }
        }
        requireBound(count >= 6 && count <= 22, "Moving white marker missing or wrong width");
        return sum / count;
    }

    private static void inspectOverlay(Bitmap frame, EditConfig config, int id,
                                       StringBuilder report) throws IOException {
        int addedWhite = 0;
        double sumX = 0;
        double sumY = 0;
        for (int y = (int) (frame.getHeight() * .35); y < frame.getHeight() * .65; y++) {
            for (int x = (int) (frame.getWidth() * .08); x < frame.getWidth() * .92; x++) {
                double u = (x + .5) / frame.getWidth();
                double v = (y + .5) / frame.getHeight();
                double[] source = inverse(config, u, v);
                if (white(frame.getPixel(x, y))
                        && !white(SelfTestFixture.colorAt(id, source[0], source[1]))) {
                    addedWhite++;
                    sumX += u;
                    sumY += v;
                }
            }
        }
        require(addedWhite >= 30 && addedWhite < frame.getWidth() * frame.getHeight() * .12,
                "New white overlay pixels=" + addedWhite + " (not source content)", report);
        require(Math.abs(sumX / addedWhite - .5) < .17
                        && Math.abs(sumY / addedWhite - .5) < .09,
                "White overlay is centered; exact glyph/OCR not asserted", report);
    }

    static double[] map(EditConfig config, double x, double y) throws IOException {
        double u = (x - config.cropLeft) / (config.cropRight - config.cropLeft);
        double v = (y - config.cropTop) / (config.cropBottom - config.cropTop);
        requireBound(u > 0 && u < 1 && v > 0 && v < 1, "Probe cropped out; assertion unverified");
        switch (config.rotationDegrees) {
            case 0: return new double[]{u, v};
            case 90: return new double[]{1 - v, u};
            case 180: return new double[]{1 - u, 1 - v};
            case 270: return new double[]{v, 1 - u};
            default: throw new IOException("Only quarter-turn geometry is verified");
        }
    }

    private static double[] inverse(EditConfig config, double u, double v) {
        double x;
        double y;
        switch (config.rotationDegrees) {
            case 90: x = v; y = 1 - u; break;
            case 180: x = 1 - u; y = 1 - v; break;
            case 270: x = 1 - v; y = u; break;
            default: x = u; y = v;
        }
        return new double[]{config.cropLeft + x * (config.cropRight - config.cropLeft),
                config.cropTop + y * (config.cropBottom - config.cropTop)};
    }

    private static int sample(Bitmap frame, double[] point) throws IOException {
        int x = (int) (point[0] * frame.getWidth());
        int y = (int) (point[1] * frame.getHeight());
        requireBound(x >= 1 && x < frame.getWidth() - 1 && y >= 1
                && y < frame.getHeight() - 1, "Probe outside decoded frame");
        int r = 0;
        int g = 0;
        int b = 0;
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int rgb = frame.getPixel(x + dx, y + dy);
                r += (rgb >> 16) & 255;
                g += (rgb >> 8) & 255;
                b += rgb & 255;
            }
        }
        return ((r / 9) << 16) | ((g / 9) << 8) | (b / 9);
    }

    private static boolean white(int rgb) {
        return ((rgb >> 16) & 255) > 195 && ((rgb >> 8) & 255) > 195 && (rgb & 255) > 195;
    }

    private static double colorDistance(int a, int b) {
        return Math.max(Math.abs((a & 255) - (b & 255)),
                Math.max(Math.abs(((a >> 8) & 255) - ((b >> 8) & 255)),
                        Math.abs(((a >> 16) & 255) - ((b >> 16) & 255))));
    }

    private static AudioStats decodeAudio(File file, double durationMs,
                                          SelfTestFixture.Guard guard,
                                          StringBuilder report) throws IOException, TimeoutException {
        long deadline = System.nanoTime() + 45_000_000_000L;
        try (Resources resources = new Resources()) {
            MediaExtractor extractor = resources.extractor;
            extractor.setDataSource(file.getAbsolutePath());
            MediaFormat input = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    extractor.selectTrack(i);
                    input = candidate;
                    break;
                }
            }
            requireBound(input != null, "No audio track to decode");
            MediaCodec codec = resources.codec = MediaCodec.createDecoderByType(
                    input.getString(MediaFormat.KEY_MIME));
            codec.configure(input, null, null, 0);
            codec.start();
            resources.started = true;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputEnded = false;
            boolean outputEnded = false;
            int sampleRate = 0;
            int channels = 0;
            int encoding = AudioFormat.ENCODING_PCM_16BIT;
            int packets = 0;
            long decodedFrames = 0;
            long lastPts = -1;
            double endSeconds = 0;
            AudioStats stats = new AudioStats(true);
            while (!outputEnded) {
                guard.check();
                if (System.nanoTime() >= deadline) {
                    throw new TimeoutException("PCM decode exceeded 45 seconds");
                }
                if (!inputEnded) {
                    int index = codec.dequeueInputBuffer(1000);
                    if (index >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(index);
                        requireBound(buffer != null, "Missing audio input buffer");
                        buffer.clear();
                        int size = extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputEnded = true;
                        } else {
                            requireBound(++packets <= MAX_PACKETS && size <= buffer.capacity(),
                                    "Compressed audio input bound exceeded");
                            long pts = extractor.getSampleTime();
                            requireBound(pts >= 0 && pts <= MAX_US, "Invalid audio input PTS");
                            codec.queueInputBuffer(index, 0, size, pts, 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 1000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    int newRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    int newChannels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    requireBound(newRate >= 8000 && newRate <= 96000
                                    && newChannels >= 1 && newChannels <= 2,
                            "Unsupported decoded audio rate/channels");
                    requireBound(sampleRate == 0 || (sampleRate == newRate && channels == newChannels),
                            "Decoded audio format changed mid-stream");
                    sampleRate = newRate;
                    channels = newChannels;
                    if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    }
                    requireBound(encoding == AudioFormat.ENCODING_PCM_16BIT
                                    || encoding == AudioFormat.ENCODING_PCM_FLOAT,
                            "Unsupported PCM encoding " + encoding);
                } else if (index >= 0) {
                    Throwable failure = null;
                    try {
                        if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                            requireBound(sampleRate > 0 && info.presentationTimeUs >= lastPts,
                                    "Audio PCM format or timestamp invalid");
                            lastPts = info.presentationTimeUs;
                            ByteBuffer pcm = codec.getOutputBuffer(index);
                            requireBound(pcm != null, "Missing decoded PCM buffer");
                            pcm.order(ByteOrder.nativeOrder());
                            pcm.position(info.offset);
                            pcm.limit(info.offset + info.size);
                            int bytes = encoding == AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2;
                            requireBound(info.size % (bytes * channels) == 0, "Truncated PCM frame");
                            int frames = info.size / (bytes * channels);
                            decodedFrames += frames;
                            requireBound(decodedFrames <= 1_000_000, "Decoded PCM sample bound exceeded");
                            for (int f = 0; f < frames; f++) {
                                double value = 0;
                                for (int channel = 0; channel < channels; channel++) {
                                    double next = bytes == 4 ? pcm.getFloat() : pcm.getShort() / 32768.0;
                                    requireBound(!Double.isNaN(next) && !Double.isInfinite(next),
                                            "Non-finite PCM");
                                    value += next / channels;
                                }
                                double time = info.presentationTimeUs / 1_000_000.0 + f / (double) sampleRate;
                                stats.allSamples.add(value, time);
                                stats.peak = Math.max(stats.peak, Math.abs(value));
                                if (time >= .20 && time < durationMs / 1000 - .20) {
                                    stats.add(value, time);
                                    int window = Math.min(2, (int) ((time - .20)
                                            / (durationMs / 1000 - .40) * 3));
                                    stats.windows[window].add(value, time);
                                }
                            }
                            endSeconds = (info.presentationTimeUs / 1_000_000.0)
                                    + frames / (double) sampleRate;
                        }
                        outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    } catch (IOException | RuntimeException | Error error) {
                        failure = error;
                        throw error;
                    } finally {
                        try {
                            codec.releaseOutputBuffer(index, false);
                        } catch (RuntimeException error) {
                            if (failure == null) {
                                throw error;
                            }
                            if (failure != error) {
                                failure.addSuppressed(error);
                            }
                        }
                    }
                }
            }
            require(Math.abs(endSeconds * 1000 - durationMs) <= 160,
                    "Actual decoded PCM end=" + number(endSeconds * 1000) + " ms", report);
            require(decodedFrames / (double) sampleRate >= durationMs / 1000 - .18,
                    "Actual PCM sample count covers edited duration", report);
            require(stats.count >= sampleRate * (durationMs / 1000 - .50),
                    "Measured PCM covers interior timeline", report);
            for (AudioStats window : stats.windows) {
                require(window.count >= sampleRate * .15, "PCM window has sufficient samples", report);
            }
            return stats;
        }
    }

    private static final class AudioStats {
        long count;
        double sumSquares;
        double previous;
        double previousTime;
        double firstCrossing;
        double lastCrossing;
        int crossings;
        final AudioStats[] windows;
        final AudioStats allSamples;
        double peak;

        AudioStats(boolean withWindows) {
            windows = withWindows ? new AudioStats[]{new AudioStats(false),
                    new AudioStats(false), new AudioStats(false)} : null;
            allSamples = withWindows ? new AudioStats(false) : null;
        }

        void add(double value, double time) {
            sumSquares += value * value;
            if (count > 0 && previous <= 0 && value > 0) {
                double crossing = previousTime + (time - previousTime) * -previous / (value - previous);
                if (crossings++ == 0) {
                    firstCrossing = crossing;
                }
                lastCrossing = crossing;
            }
            previous = value;
            previousTime = time;
            count++;
        }

        double rms() { return count == 0 ? 0 : Math.sqrt(sumSquares / count); }
        double frequency() {
            return crossings < 2 || lastCrossing <= firstCrossing
                    ? 0 : (crossings - 1) / (lastCrossing - firstCrossing);
        }
    }

    private static final class Resources implements AutoCloseable {
        final MediaExtractor extractor = new MediaExtractor();
        MediaMetadataRetriever retriever;
        MediaCodec codec;
        boolean started;

        @Override public void close() throws IOException {
            Exception failure = null;
            if (codec != null) {
                if (started) {
                    try { codec.stop(); } catch (RuntimeException e) { failure = e; }
                }
                try { codec.release(); } catch (RuntimeException e) { failure = append(failure, e); }
            }
            if (retriever != null) {
                try { retriever.release(); }
                catch (IOException | RuntimeException e) { failure = append(failure, e); }
            }
            try { extractor.release(); } catch (RuntimeException e) { failure = append(failure, e); }
            if (failure != null) {
                if (failure instanceof IOException) {
                    throw (IOException) failure;
                }
                throw (RuntimeException) failure;
            }
        }
    }

    private static Exception append(Exception first, Exception next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
        return first;
    }

    private static void require(boolean condition, String message, StringBuilder report) throws IOException {
        report.append(condition ? "  PASS " : "  FAIL ").append(message).append('\n');
        requireBound(condition, message);
    }

    private static void requireBound(boolean condition, String message) throws IOException {
        if (!condition) {
            throw new IOException(message);
        }
    }

    private static String number(double value) {
        return String.format(Locale.US, "%.3f", value);
    }
}
