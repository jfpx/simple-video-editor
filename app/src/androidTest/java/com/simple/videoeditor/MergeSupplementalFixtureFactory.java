package com.simple.videoeditor;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

final class MergeSupplementalFixtureFactory {
    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final long CODEC_TIMEOUT_US = 10_000L;
    private static final long TOTAL_TIMEOUT_NS = 120_000_000_000L;
    private static final long STALL_TIMEOUT_NS = 15_000_000_000L;
    private static final double AUDIO_AMPLITUDE = 0.18;

    interface Guard {
        void check() throws IOException;
    }

    static final class GeneratedClip {
        final ClipSpec spec;
        final File file;

        GeneratedClip(ClipSpec spec, File file) {
            this.spec = spec;
            this.file = file;
        }

        EditConfig.ImportedVideo imported() {
            return new EditConfig.ImportedVideo(android.net.Uri.fromFile(file), spec.durationMs,
                    spec.displayWidth(), spec.displayHeight(), spec.hasAudio(), file.length());
        }
    }

    static final class FixtureSet {
        final int fps;
        final int sampleRate;
        final Map<String, GeneratedClip> clips;

        FixtureSet(int fps, int sampleRate, Map<String, GeneratedClip> clips) {
            this.fps = fps;
            this.sampleRate = sampleRate;
            this.clips = clips;
        }

        GeneratedClip get(String id) {
            GeneratedClip clip = clips.get(id);
            if (clip == null) throw new IllegalArgumentException("Unknown clip: " + id);
            return clip;
        }
    }

    static FixtureSet prepare(Context context, File directory, Guard guard) throws Exception {
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Supplemental fixture generation requires a worker thread");
        }
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create supplemental fixture directory");
        }
        SuiteSpec suite = loadSpec(context);
        LinkedHashMap<String, GeneratedClip> clips = new LinkedHashMap<>();
        for (ClipSpec spec : suite.clips) {
            guard.check();
            File output = new File(directory, spec.fileName);
            createClip(spec, suite.fps, suite.sampleRate, output, guard);
            clips.put(spec.id, new GeneratedClip(spec, output));
        }
        return new FixtureSet(suite.fps, suite.sampleRate, clips);
    }

    private static SuiteSpec loadSpec(Context context) throws Exception {
        try (InputStream input = context.getAssets().open("merge-fixtures/spec.json")) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                bytes.write(buffer, 0, count);
            }
            JSONObject root = new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
            int fps = root.getInt("fps");
            int sampleRate = root.getInt("sampleRate");
            JSONArray array = root.getJSONArray("clips");
            ArrayList<ClipSpec> clips = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject clip = array.getJSONObject(i);
                JSONArray audioArray = clip.getJSONArray("audio");
                ArrayList<AudioSegment> segments = new ArrayList<>();
                for (int j = 0; j < audioArray.length(); j++) {
                    JSONObject audio = audioArray.getJSONObject(j);
                    segments.add(new AudioSegment(audio.getInt("startMs"), audio.getInt("endMs"),
                            audio.getDouble("hz")));
                }
                clips.add(new ClipSpec(clip.getString("id"), clip.getString("file"),
                        clip.getInt("width"), clip.getInt("height"), clip.getInt("rotation"),
                        clip.getInt("durationMs"), parseColor(clip.getString("baseColor")),
                        parseColor(clip.getString("accentColor")), segments));
            }
            return new SuiteSpec(fps, sampleRate, clips);
        }
    }

    private static int parseColor(String hex) {
        if (hex == null || !hex.matches("#[0-9A-Fa-f]{6}")) {
            throw new IllegalArgumentException("Invalid RGB color: " + hex);
        }
        return Integer.parseInt(hex.substring(1), 16);
    }

    private static void createClip(ClipSpec spec, int fps, int sampleRate, File output, Guard guard)
            throws Exception {
        if (output.exists() && !output.delete()) throw new IOException("Cannot replace " + output.getName());
        Budget budget = new Budget(guard);
        Track video = null;
        Track audio = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean outputOwned = false;
        Throwable failure = null;
        int frameCount = Math.max(1, Math.round(spec.durationMs * fps / 1000f));
        int audioSampleCount = Math.max(0, Math.round(spec.durationMs * sampleRate / 1000f));
        int[] pixels = new int[spec.width * spec.height];
        byte[] yuv = new byte[spec.width * spec.height * 3 / 2];
        try {
            video = startVideo(spec.width, spec.height, fps, yuv.length, budget);
            if (spec.hasAudio()) {
                audio = startAudio(sampleRate, budget);
            }
            outputOwned = true;
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(((spec.rotationDegrees % 360) + 360) % 360);
            while (!video.outputEnded || (audio != null && !audio.outputEnded)) {
                budget.check();
                boolean progress = feedVideo(spec, video, fps, frameCount, pixels, yuv, budget);
                if (audio != null) {
                    progress |= feedAudio(spec, audio, sampleRate, audioSampleCount, budget);
                }
                progress |= drain(video, muxer, muxerStarted, budget);
                if (audio != null) {
                    progress |= drain(audio, muxer, muxerStarted, budget);
                }
                if (!muxerStarted && video.trackIndex >= 0 && (audio == null || audio.trackIndex >= 0)) {
                    muxer.start();
                    muxerStarted = true;
                    progress = true;
                }
                if (progress) budget.progress();
            }
            muxer.stop();
            muxerStarted = false;
            if (!output.isFile() || output.length() == 0) {
                throw new IOException("Empty supplemental fixture output");
            }
        } catch (IOException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            RuntimeException cleanup = closeTrack(video, null);
            cleanup = closeTrack(audio, cleanup);
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (RuntimeException error) {
                        cleanup = combine(cleanup, error);
                    }
                }
                try {
                    muxer.release();
                } catch (RuntimeException error) {
                    cleanup = combine(cleanup, error);
                }
            }
            if ((failure != null || cleanup != null) && outputOwned && output.exists() && !output.delete()) {
                cleanup = combine(cleanup, new RuntimeException("Cannot delete partial supplemental fixture"));
            }
            if (cleanup != null) {
                if (failure != null) {
                    failure.addSuppressed(cleanup);
                } else {
                    throw cleanup;
                }
            }
        }
    }

    private static Track startVideo(int width, int height, int fps, int yuvSize, Budget budget) throws Exception {
        Exception attempts = null;
        int[] allowed = {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar};
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (!VIDEO_MIME.equalsIgnoreCase(type)) continue;
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                for (int color : allowed) {
                    boolean supported = false;
                    for (int candidate : caps.colorFormats) supported |= candidate == color;
                    if (!supported) continue;
                    Track track = null;
                    boolean accepted = false;
                    try {
                        track = new Track(MediaCodec.createByCodecName(info.getName()), color);
                        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, width, height);
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
                        format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(600_000, width * height * fps / 2));
                        format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, yuvSize);
                        track.codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        track.codec.start();
                        track.started = true;
                        MediaFormat input = track.codec.getInputFormat();
                        if (input.containsKey(MediaFormat.KEY_COLOR_FORMAT)
                                && input.getInteger(MediaFormat.KEY_COLOR_FORMAT) != color) {
                            throw new IOException("AVC encoder changed the requested CPU color format");
                        }
                        if (hasDifferentPositiveValue(input, MediaFormat.KEY_STRIDE, width)
                                || hasDifferentPositiveValue(input, MediaFormat.KEY_SLICE_HEIGHT, height)) {
                            throw new IOException("AVC encoder requires unsupported padded YUV input");
                        }
                        accepted = true;
                        return track;
                    } catch (Exception error) {
                        attempts = combine(attempts, error);
                    } finally {
                        if (!accepted) {
                            RuntimeException cleanup = closeTrack(track, null);
                            if (cleanup != null) throw cleanup;
                        }
                    }
                }
            }
        }
        throw new IOException("No explicit-buffer AVC encoder available", attempts);
    }

    private static boolean hasDifferentPositiveValue(MediaFormat format, String key, int expected) {
        return format.containsKey(key) && format.getInteger(key) > 0 && format.getInteger(key) != expected;
    }

    private static Track startAudio(int sampleRate, Budget budget) throws Exception {
        budget.check();
        Track track = new Track(MediaCodec.createEncoderByType(AUDIO_MIME), 0);
        MediaFormat format = MediaFormat.createAudioFormat(AUDIO_MIME, sampleRate, 1);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        track.codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        track.codec.start();
        track.started = true;
        return track;
    }

    private static boolean feedVideo(ClipSpec spec, Track track, int fps, int frameCount,
                                     int[] pixels, byte[] yuv, Budget budget) throws Exception {
        if (track.inputEnded) return false;
        int index = track.codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (index < 0) return false;
        long ptsUs = track.inputCount * 1_000_000L / fps;
        if (track.inputCount == frameCount) {
            track.codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            track.inputEnded = true;
            return true;
        }
        ByteBuffer buffer = track.codec.getInputBuffer(index);
        if (buffer == null || buffer.capacity() < yuv.length) {
            throw new IOException("Supplemental video input buffer too small");
        }
        render(spec, track.inputCount, frameCount, pixels, yuv, track.colorFormat, budget);
        buffer.clear();
        buffer.put(yuv);
        track.codec.queueInputBuffer(index, 0, yuv.length, ptsUs, 0);
        track.inputCount++;
        return true;
    }

    private static boolean feedAudio(ClipSpec spec, Track track, int sampleRate, int sampleCount, Budget budget)
            throws Exception {
        if (track.inputEnded) return false;
        int index = track.codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
        if (index < 0) return false;
        long ptsUs = track.inputCount * 1_000_000L / sampleRate;
        if (track.inputCount == sampleCount) {
            track.codec.queueInputBuffer(index, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            track.inputEnded = true;
            return true;
        }
        ByteBuffer buffer = track.codec.getInputBuffer(index);
        if (buffer == null || buffer.capacity() < 2) throw new IOException("No AAC PCM input buffer");
        buffer.clear();
        buffer.order(ByteOrder.nativeOrder());
        int count = Math.min(1024, Math.min(buffer.remaining() / 2, sampleCount - track.inputCount));
        for (int i = 0; i < count; i++) {
            double hz = spec.frequencyAt((track.inputCount + i) * 1000d / sampleRate);
            short sample = hz <= 0d ? 0 : (short) Math.round(32767d * AUDIO_AMPLITUDE
                    * Math.sin(2d * Math.PI * hz * (track.inputCount + i) / sampleRate));
            buffer.putShort(sample);
        }
        track.codec.queueInputBuffer(index, 0, count * 2, ptsUs, 0);
        track.inputCount += count;
        return true;
    }

    private static boolean drain(Track track, MediaMuxer muxer, boolean muxerStarted, Budget budget)
            throws Exception {
        if (track == null || track.outputEnded || (!muxerStarted && track.trackIndex >= 0)) return false;
        int index = track.codec.dequeueOutputBuffer(track.bufferInfo, CODEC_TIMEOUT_US);
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER || index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            return false;
        }
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            track.trackIndex = muxer.addTrack(track.codec.getOutputFormat());
            return true;
        }
        if (index < 0) throw new IOException("Unexpected encoder dequeue result");
        Throwable failure = null;
        try {
            MediaCodec.BufferInfo info = track.bufferInfo;
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                if (!muxerStarted || track.trackIndex < 0) throw new IOException("Encoded data before muxer start");
                ByteBuffer buffer = track.codec.getOutputBuffer(index);
                if (buffer == null) throw new IOException("Missing encoded output buffer");
                if (track.colorFormat == 0 && track.sampleCount == 0 && info.presentationTimeUs < 0) {
                    track.timestampOffset = -info.presentationTimeUs;
                }
                info.presentationTimeUs += track.timestampOffset;
                if (info.presentationTimeUs < 0 || info.presentationTimeUs <= track.lastPts) {
                    throw new IOException("Non-monotonic encoded timestamps");
                }
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                muxer.writeSampleData(track.trackIndex, buffer, info);
                track.lastPts = info.presentationTimeUs;
                track.sampleCount++;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) track.outputEnded = true;
            return true;
        } catch (Exception error) {
            failure = error;
            throw error;
        } finally {
            try {
                track.codec.releaseOutputBuffer(index, false);
            } catch (RuntimeException error) {
                if (failure == null) throw error;
                failure.addSuppressed(error);
            }
        }
    }

    private static void render(ClipSpec spec, int frameIndex, int frameCount, int[] pixels, byte[] yuv,
                               int colorFormat, Budget budget) throws Exception {
        int width = spec.width;
        int height = spec.height;
        int stripeHeight = Math.max(8, height / 8);
        int accentWidth = Math.max(12, width / 6);
        for (int y = 0; y < height; y++) {
            if ((y & 15) == 0) budget.check();
            for (int x = 0; x < width; x++) {
                int rgb = spec.baseColor;
                if (y < stripeHeight) {
                    int bit = Math.min(7, x * 8 / Math.max(1, width));
                    rgb = ((frameIndex >> bit) & 1) != 0 ? 0xFFFFFF : 0x000000;
                } else if (x > width / 2 - accentWidth / 2 && x < width / 2 + accentWidth / 2
                        && y > height / 2 - accentWidth / 2 && y < height / 2 + accentWidth / 2) {
                    rgb = spec.accentColor;
                } else if (frameIndex == frameCount - 1 && y > height * 3 / 4) {
                    rgb = 0x202020;
                }
                pixels[y * width + x] = rgb;
                int r = (rgb >> 16) & 255;
                int g = (rgb >> 8) & 255;
                int b = rgb & 255;
                yuv[y * width + x] = (byte) (16 + ((66 * r + 129 * g + 25 * b + 128) >> 8));
            }
        }
        int planeSize = width * height;
        for (int y = 0; y < height; y += 2) {
            if ((y & 15) == 0) budget.check();
            for (int x = 0; x < width; x += 2) {
                int r = 0, g = 0, b = 0;
                for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) {
                    int rgb = pixels[(y + dy) * width + x + dx];
                    r += (rgb >> 16) & 255;
                    g += (rgb >> 8) & 255;
                    b += rgb & 255;
                }
                r = (r + 2) / 4;
                g = (g + 2) / 4;
                b = (b + 2) / 4;
                byte u = (byte) (128 + ((-38 * r - 74 * g + 112 * b + 128) >> 8));
                byte v = (byte) (128 + ((112 * r - 94 * g - 18 * b + 128) >> 8));
                int chroma = (y / 2) * (width / 2) + x / 2;
                if (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    yuv[planeSize + chroma] = u;
                    yuv[planeSize + planeSize / 4 + chroma] = v;
                } else {
                    yuv[planeSize + 2 * chroma] = u;
                    yuv[planeSize + 2 * chroma + 1] = v;
                }
            }
        }
    }

    private static RuntimeException closeTrack(Track track, RuntimeException error) {
        if (track == null) return error;
        if (track.started) {
            try {
                track.codec.stop();
            } catch (RuntimeException e) {
                error = combine(error, e);
            }
        }
        try {
            track.codec.release();
        } catch (RuntimeException e) {
            error = combine(error, e);
        }
        return error;
    }

    private static <T extends Exception> T combine(T first, T next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }

    private static final class Track {
        final MediaCodec codec;
        final int colorFormat;
        final MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
        boolean started;
        boolean inputEnded;
        boolean outputEnded;
        int inputCount;
        int sampleCount;
        int trackIndex = -1;
        long lastPts = Long.MIN_VALUE;
        long timestampOffset;

        Track(MediaCodec codec, int colorFormat) {
            this.codec = codec;
            this.colorFormat = colorFormat;
        }
    }

    private static final class Budget {
        private final Guard guard;
        private final long startedNs = System.nanoTime();
        private long lastProgressNs = startedNs;

        Budget(Guard guard) {
            this.guard = guard;
        }

        void check() throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Supplemental fixture generation interrupted");
            }
            guard.check();
            long now = System.nanoTime();
            if (now - startedNs > TOTAL_TIMEOUT_NS || now - lastProgressNs > STALL_TIMEOUT_NS) {
                throw new IOException("Supplemental fixture generation timed out");
            }
        }

        void progress() {
            lastProgressNs = System.nanoTime();
        }
    }

    private static final class SuiteSpec {
        final int fps;
        final int sampleRate;
        final List<ClipSpec> clips;

        SuiteSpec(int fps, int sampleRate, List<ClipSpec> clips) {
            this.fps = fps;
            this.sampleRate = sampleRate;
            this.clips = clips;
        }
    }

    static final class ClipSpec {
        final String id;
        final String fileName;
        final int width;
        final int height;
        final int rotationDegrees;
        final int durationMs;
        final int baseColor;
        final int accentColor;
        final List<AudioSegment> audioSegments;

        ClipSpec(String id, String fileName, int width, int height, int rotationDegrees, int durationMs,
                 int baseColor, int accentColor, List<AudioSegment> audioSegments) {
            this.id = id;
            this.fileName = fileName;
            this.width = width;
            this.height = height;
            this.rotationDegrees = rotationDegrees;
            this.durationMs = durationMs;
            this.baseColor = baseColor;
            this.accentColor = accentColor;
            this.audioSegments = audioSegments;
        }

        boolean hasAudio() {
            return !audioSegments.isEmpty();
        }

        int displayWidth() {
            return Math.abs(rotationDegrees % 180) == 90 ? height : width;
        }

        int displayHeight() {
            return Math.abs(rotationDegrees % 180) == 90 ? width : height;
        }

        double frequencyAt(double timeMs) {
            for (AudioSegment segment : audioSegments) {
                if (timeMs >= segment.startMs && timeMs < segment.endMs) return segment.hz;
            }
            return 0d;
        }
    }

    private static final class AudioSegment {
        final int startMs;
        final int endMs;
        final double hz;

        AudioSegment(int startMs, int endMs, double hz) {
            this.startMs = startMs;
            this.endMs = endMs;
            this.hz = hz;
        }
    }
}
