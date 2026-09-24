package com.simple.videoeditor;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/**
 * Generates an offline AVC/AAC fixture; call create on a worker thread.
 * Colors are source RGB, before lossy encoding and chroma subsampling. Standard
 * byte-buffer YUV420 planar/NV12 encoders are required; surface/flexible-only
 * encoders are deliberately unsupported. No network or backend is involved.
 */
public final class SelfTestFixture {
    public static final int WIDTH = 320;
    public static final int HEIGHT = 240;
    public static final int FPS = 24;
    public static final int FRAME_COUNT = 72;
    public static final int SAMPLE_RATE = 48000;
    public static final long DURATION_MS = 3000;
    public static final double TONE_HZ = 1000.0;
    public static final double TONE_AMPLITUDE = 0.12;

    public static final double BAND_TOP = 0.08;
    public static final double BAND_BOTTOM = 0.18;
    public static final double BIT_LEFT = 0.2;
    public static final double BIT_RIGHT = 0.8;
    public static final double BIT_WIDTH = 0.075;
    public static final int BIT_COUNT = 8;
    public static final double MOVEMENT_LEFT = 0.2;
    public static final double MOVEMENT_RIGHT = 0.8;
    public static final double MOVEMENT_Y = 0.72;
    public static final double MOVEMENT_HALF_WIDTH = 0.035;
    public static final double MOVEMENT_HALF_HEIGHT = 0.035;
    public static final double MARKER_INSET = 0.02;
    public static final double MARKER_SIZE = 0.04;
    public static final double GRID_SPACING = 0.25;
    public static final double GRID_HALF_WIDTH = 0.5 / WIDTH;
    public static final double GRID_HALF_HEIGHT = 0.5 / HEIGHT;

    private static final String VIDEO_MIME = "video/avc";
    private static final String AUDIO_MIME = "audio/mp4a-latm";
    private static final int AUDIO_SAMPLE_COUNT = 144000;
    private static final int YUV_SIZE = WIDTH * HEIGHT * 3 / 2;
    private static final long DEQUEUE_US = 1000;
    private static final long TOTAL_TIMEOUT_NS = 120_000_000_000L;
    private static final long STALL_TIMEOUT_NS = 15_000_000_000L;

    private SelfTestFixture() {
    }

    public interface Guard {
        void check() throws IOException, TimeoutException;
    }

    /** RGB without alpha. Rectangles use inclusive left/top, exclusive right/bottom. */
    public static int colorAt(int frameIndex, double x, double y) {
        int frame = Math.max(0, Math.min(FRAME_COUNT - 1, frameIndex));
        int color = y < 0.5
                ? (x < 0.5 ? 0xDC2323 : 0x23C82D)
                : (x < 0.5 ? 0x2337DC : 0xE1CD23);
        for (int line = 1; line <= 3; line++) {
            double position = line * GRID_SPACING;
            if ((x >= position - GRID_HALF_WIDTH && x < position + GRID_HALF_WIDTH)
                    || (y >= position - GRID_HALF_HEIGHT
                    && y < position + GRID_HALF_HEIGHT)) {
                color = 0x606060;
            }
        }
        double far = 1.0 - MARKER_INSET - MARKER_SIZE;
        if (inside(x, y, MARKER_INSET, MARKER_INSET, MARKER_SIZE, MARKER_SIZE)) {
            color = 0xFF00FF;
        } else if (inside(x, y, far, MARKER_INSET, MARKER_SIZE, MARKER_SIZE)) {
            color = 0x00FFFF;
        } else if (inside(x, y, MARKER_INSET, far, MARKER_SIZE, MARKER_SIZE)) {
            color = 0xFF8000;
        } else if (inside(x, y, far, far, MARKER_SIZE, MARKER_SIZE)) {
            color = 0x8000FF;
        }
        if (y >= BAND_TOP && y < BAND_BOTTOM) {
            color = 0x181818;
            if (x >= BIT_LEFT && x < BIT_RIGHT) {
                int bit = Math.min(BIT_COUNT - 1, (int) ((x - BIT_LEFT) / BIT_WIDTH));
                color = (frame & (1 << bit)) != 0 ? 0xFFFFFF : 0x000000;
            }
        }
        double center = MOVEMENT_LEFT + 0.6 * (frame / (FRAME_COUNT - 1.0));
        if (inside(x, y, center - MOVEMENT_HALF_WIDTH, MOVEMENT_Y - MOVEMENT_HALF_HEIGHT,
                2.0 * MOVEMENT_HALF_WIDTH, 2.0 * MOVEMENT_HALF_HEIGHT)) {
            color = 0xFFFFFF;
        }
        return color;
    }

    public static int frameIndexAt(long timeUs) {
        if (timeUs <= 0) {
            return 0;
        }
        // Clamp before multiplying, including Long.MAX_VALUE.
        if (timeUs >= DURATION_MS * 1000) {
            return FRAME_COUNT - 1;
        }
        return (int) Math.min(FRAME_COUNT - 1, timeUs * FPS / 1_000_000L);
    }

    private static boolean inside(double x, double y, double left, double top,
                                  double width, double height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }

    /**
     * Replaces directory/fixture.mp4. The caller must exclusively own that path.
     * Cancellation, timeouts, unavailable encoders, and finalization errors throw;
     * a partial output is deleted. AAC may contain codec priming/padding despite
     * exactly 144000 input PCM samples. Negative AAC priming timestamps are shifted
     * to zero, retaining priming packets and their small initial audio delay.
     * Codec calls themselves are platform native
     * operations; deadlines bound polling, not an unresponsive native driver.
     */
    public static File create(File directory, Guard guard) throws IOException, TimeoutException {
        if (directory == null || guard == null) {
            throw new IllegalArgumentException("directory and guard are required");
        }
        if (Looper.getMainLooper() != null && Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("Fixture generation requires a worker thread");
        }
        Budget budget = new Budget(guard);
        budget.check();
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Cannot create fixture directory");
        }
        File output = new File(directory, "fixture.mp4");
        Track video = null;
        Track audio = null;
        MediaMuxer muxer = null;
        boolean muxerStarted = false;
        boolean outputOwned = false;
        Throwable failure = null;
        try {
            video = startVideo(budget);
            budget.check();
            audio = new Track(MediaCodec.createEncoderByType(AUDIO_MIME), 0);
            MediaFormat audioFormat = MediaFormat.createAudioFormat(AUDIO_MIME, SAMPLE_RATE, 1);
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 128000);
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 2048);
            audio.codec.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            audio.codec.start();
            audio.started = true;
            budget.check();
            outputOwned = true;
            muxer = new MediaMuxer(output.getAbsolutePath(),
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxer.setOrientationHint(0);
            int[] pixels = new int[WIDTH * HEIGHT];
            byte[] yuv = new byte[YUV_SIZE];
            while (!video.outputEnded || !audio.outputEnded) {
                budget.check();
                boolean progress = feedVideo(video, pixels, yuv, budget);
                progress |= feedAudio(audio, budget);
                progress |= drain(video, muxer, muxerStarted, budget);
                progress |= drain(audio, muxer, muxerStarted, budget);
                if (!muxerStarted && video.trackIndex >= 0 && audio.trackIndex >= 0) {
                    budget.check();
                    muxer.start();
                    muxerStarted = true;
                    progress = true;
                }
                if (progress) {
                    budget.progress();
                }
            }
            if (!muxerStarted || video.sampleCount != FRAME_COUNT || audio.sampleCount == 0
                    || video.inputCount != FRAME_COUNT || audio.inputCount != AUDIO_SAMPLE_COUNT) {
                throw new IOException("Incomplete encoded fixture");
            }
            budget.check();
            // Clear first: a failing native stop must not be retried in finally.
            muxerStarted = false;
            muxer.stop();
            budget.check();
            if (!output.isFile() || output.length() == 0) {
                throw new IOException("Empty fixture output");
            }
        } catch (IOException | TimeoutException | RuntimeException | Error e) {
            failure = e;
            throw e;
        } finally {
            RuntimeException codecCleanup = closeTrack(video, null);
            Exception cleanup = closeTrack(audio, codecCleanup);
            if (muxer != null) {
                if (muxerStarted) {
                    try {
                        muxer.stop();
                    } catch (RuntimeException e) {
                        cleanup = combine(cleanup, e);
                    }
                }
                try {
                    muxer.release();
                } catch (RuntimeException e) {
                    cleanup = combine(cleanup, e);
                }
            }
            if (failure == null && cleanup == null) {
                try {
                    budget.check();
                } catch (IOException | TimeoutException | RuntimeException e) {
                    cleanup = e;
                }
            }
            if ((failure != null || cleanup != null) && outputOwned
                    && output.exists() && !output.delete()) {
                cleanup = combine(cleanup, new IOException("Cannot delete partial fixture"));
            }
            if (cleanup != null) {
                if (failure != null) {
                    if (failure != cleanup) {
                        failure.addSuppressed(cleanup);
                    }
                } else {
                    if (cleanup instanceof IOException) {
                        throw (IOException) cleanup;
                    }
                    if (cleanup instanceof TimeoutException) {
                        throw (TimeoutException) cleanup;
                    }
                    throw (RuntimeException) cleanup;
                }
            }
        }
        return output;
    }

    private static Track startVideo(Budget budget) throws IOException, TimeoutException {
        Exception attempts = null;
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
        int[] allowed = {MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar};
        for (MediaCodecInfo info : infos) {
            budget.check();
            if (!info.isEncoder()) {
                continue;
            }
            for (String type : info.getSupportedTypes()) {
                if (!VIDEO_MIME.equalsIgnoreCase(type)) {
                    continue;
                }
                MediaCodecInfo.CodecCapabilities caps;
                try {
                    caps = info.getCapabilitiesForType(type);
                } catch (RuntimeException e) {
                    attempts = combine(attempts, e);
                    continue;
                }
                for (int color : allowed) {
                    boolean supported = false;
                    for (int candidate : caps.colorFormats) {
                        supported |= candidate == color;
                    }
                    if (!supported) {
                        continue;
                    }
                    budget.check();
                    Track track = null;
                    boolean accepted = false;
                    try {
                        track = new Track(MediaCodec.createByCodecName(info.getName()), color);
                        MediaFormat format = MediaFormat.createVideoFormat(VIDEO_MIME, WIDTH, HEIGHT);
                        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, color);
                        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000);
                        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
                        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
                        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, YUV_SIZE);
                        format.setInteger(MediaFormat.KEY_PROFILE,
                                MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                        if (Build.VERSION.SDK_INT >= 24) {
                            format.setInteger(MediaFormat.KEY_COLOR_STANDARD,
                                    MediaFormat.COLOR_STANDARD_BT601_NTSC);
                            format.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED);
                            format.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO);
                        }
                        track.codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                        track.codec.start();
                        track.started = true;
                        MediaFormat input = track.codec.getInputFormat();
                        if (input.containsKey(MediaFormat.KEY_COLOR_FORMAT)
                                && input.getInteger(MediaFormat.KEY_COLOR_FORMAT) != color) {
                            throw new IOException("AVC encoder changed the requested CPU color format");
                        }
                        if (hasDifferentPositiveValue(input, MediaFormat.KEY_STRIDE, WIDTH)
                                || hasDifferentPositiveValue(input, MediaFormat.KEY_SLICE_HEIGHT, HEIGHT)) {
                            throw new IOException("AVC encoder requires unsupported padded YUV input");
                        }
                        accepted = true;
                        return track;
                    } catch (IOException | RuntimeException e) {
                        attempts = combine(attempts, e);
                    } finally {
                        if (!accepted) {
                            RuntimeException cleanup = closeTrack(track, null);
                            if (cleanup != null) {
                                if (attempts != null) {
                                    cleanup.addSuppressed(attempts);
                                }
                                throw cleanup;
                            }
                        }
                    }
                }
            }
        }
        throw new IOException("No usable AVC encoder with explicit CPU YUV420 planar or semiplanar input",
                attempts);
    }

    private static boolean hasDifferentPositiveValue(MediaFormat format, String key, int expected) {
        return format.containsKey(key) && format.getInteger(key) > 0
                && format.getInteger(key) != expected;
    }

    private static boolean feedVideo(Track track, int[] pixels, byte[] yuv, Budget budget)
            throws IOException, TimeoutException {
        if (track.inputEnded) {
            return false;
        }
        budget.check();
        int index = track.codec.dequeueInputBuffer(DEQUEUE_US);
        if (index < 0) {
            return false;
        }
        long pts = track.inputCount * 1_000_000L / FPS;
        if (track.inputCount == FRAME_COUNT) {
            track.codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            track.inputEnded = true;
            return true;
        }
        ByteBuffer buffer = track.codec.getInputBuffer(index);
        if (buffer == null || buffer.capacity() < YUV_SIZE) {
            throw new IOException("AVC encoder input buffer cannot hold packed YUV420");
        }
        render(track.inputCount, track.colorFormat, pixels, yuv, budget);
        buffer.clear();
        buffer.put(yuv);
        budget.check();
        track.codec.queueInputBuffer(index, 0, yuv.length, pts, 0);
        track.inputCount++;
        return true;
    }

    private static boolean feedAudio(Track track, Budget budget) throws IOException, TimeoutException {
        if (track.inputEnded) {
            return false;
        }
        budget.check();
        int index = track.codec.dequeueInputBuffer(DEQUEUE_US);
        if (index < 0) {
            return false;
        }
        long pts = track.inputCount * 1_000_000L / SAMPLE_RATE;
        if (track.inputCount == AUDIO_SAMPLE_COUNT) {
            track.codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            track.inputEnded = true;
            return true;
        }
        ByteBuffer buffer = track.codec.getInputBuffer(index);
        if (buffer == null || buffer.capacity() < 2) {
            throw new IOException("AAC encoder has no usable PCM input buffer");
        }
        buffer.clear();
        buffer.order(ByteOrder.nativeOrder());
        int count = Math.min(1024, Math.min(buffer.remaining() / 2,
                AUDIO_SAMPLE_COUNT - track.inputCount));
        for (int i = 0; i < count; i++) {
            double phase = 2.0 * Math.PI * TONE_HZ * (track.inputCount + i) / SAMPLE_RATE;
            buffer.putShort((short) Math.round(32767.0 * TONE_AMPLITUDE * Math.sin(phase)));
        }
        budget.check();
        track.codec.queueInputBuffer(index, 0, count * 2, pts, 0);
        track.inputCount += count;
        return true;
    }

    private static boolean drain(Track track, MediaMuxer muxer, boolean muxerStarted,
                                 Budget budget) throws IOException, TimeoutException {
        // Leave data in the codec until both formats are known, without unbounded staging.
        if (track.outputEnded || (!muxerStarted && track.trackIndex >= 0)) {
            return false;
        }
        budget.check();
        int index = track.codec.dequeueOutputBuffer(track.bufferInfo, DEQUEUE_US);
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) {
            return false;
        }
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            if (track.trackIndex >= 0 || muxerStarted) {
                throw new IOException("Encoder changed format after track registration");
            }
            track.trackIndex = muxer.addTrack(track.codec.getOutputFormat());
            return true;
        }
        if (index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
            return false;
        }
        if (index < 0) {
            throw new IOException("Unexpected encoder dequeue result");
        }
        Throwable failure = null;
        try {
            MediaCodec.BufferInfo info = track.bufferInfo;
            if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                if (!muxerStarted || track.trackIndex < 0) {
                    throw new IOException("Encoder emitted data before its format");
                }
                ByteBuffer buffer = track.codec.getOutputBuffer(index);
                if (buffer == null || info.offset < 0 || info.size > buffer.capacity()
                        || info.offset > buffer.capacity() - info.size) {
                    throw new IOException("Invalid encoded output buffer");
                }
                // Some AAC encoders timestamp their priming packet before zero.
                // MediaMuxer requires nonnegative timestamps; retain that packet.
                if (track.colorFormat == 0 && track.sampleCount == 0
                        && info.presentationTimeUs < 0) {
                    if (info.presentationTimeUs == Long.MIN_VALUE) {
                        throw new IOException("Invalid AAC priming timestamp");
                    }
                    track.timestampOffset = -info.presentationTimeUs;
                }
                if (info.presentationTimeUs > Long.MAX_VALUE - track.timestampOffset) {
                    throw new IOException("Encoder timestamp overflow");
                }
                info.presentationTimeUs += track.timestampOffset;
                if (info.presentationTimeUs < 0 || info.presentationTimeUs <= track.lastPts) {
                    throw new IOException("Non-monotonic encoder timestamps");
                }
                buffer.clear();
                buffer.position(info.offset);
                buffer.limit(info.offset + info.size);
                budget.check();
                muxer.writeSampleData(track.trackIndex, buffer, info);
                track.lastPts = info.presentationTimeUs;
                track.sampleCount++;
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                if (!track.inputEnded) {
                    throw new IOException("Encoder ended before all input was queued");
                }
                track.outputEnded = true;
            }
            return true;
        } catch (IOException | TimeoutException | RuntimeException | Error error) {
            failure = error;
            throw error;
        } finally {
            try {
                track.codec.releaseOutputBuffer(index, false);
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

    private static void render(int frame, int format, int[] pixels, byte[] yuv, Budget budget)
            throws IOException, TimeoutException {
        for (int y = 0; y < HEIGHT; y++) {
            if ((y & 15) == 0) {
                budget.check();
            }
            for (int x = 0; x < WIDTH; x++) {
                int rgb = colorAt(frame, (x + 0.5) / WIDTH, (y + 0.5) / HEIGHT);
                int offset = y * WIDTH + x;
                pixels[offset] = rgb;
                int r = (rgb >> 16) & 255;
                int g = (rgb >> 8) & 255;
                int b = rgb & 255;
                yuv[offset] = (byte) (16 + ((66 * r + 129 * g + 25 * b + 128) >> 8));
            }
        }
        int planeSize = WIDTH * HEIGHT;
        for (int y = 0; y < HEIGHT; y += 2) {
            if ((y & 15) == 0) {
                budget.check();
            }
            for (int x = 0; x < WIDTH; x += 2) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int rgb = pixels[(y + dy) * WIDTH + x + dx];
                        r += (rgb >> 16) & 255;
                        g += (rgb >> 8) & 255;
                        b += rgb & 255;
                    }
                }
                r = (r + 2) / 4;
                g = (g + 2) / 4;
                b = (b + 2) / 4;
                byte u = (byte) (128 + ((-38 * r - 74 * g + 112 * b + 128) >> 8));
                byte v = (byte) (128 + ((112 * r - 94 * g - 18 * b + 128) >> 8));
                int chroma = (y / 2) * (WIDTH / 2) + x / 2;
                if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
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
        if (track != null) {
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
        }
        return error;
    }

    private static <T extends Exception> T combine(T first, T next) {
        if (first == null) {
            return next;
        }
        if (first != next) {
            first.addSuppressed(next);
        }
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
        final Guard guard;
        final long start = System.nanoTime();
        long lastProgress = start;

        Budget(Guard guard) {
            this.guard = guard;
        }

        void check() throws IOException, TimeoutException {
            if (Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Fixture generation interrupted");
            }
            guard.check();
            long now = System.nanoTime();
            if (now - start >= TOTAL_TIMEOUT_NS || now - lastProgress >= STALL_TIMEOUT_NS) {
                throw new IOException("Fixture encoder timed out");
            }
        }

        void progress() {
            lastProgress = System.nanoTime();
        }
    }
}
