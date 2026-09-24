package com.simple.videoeditor;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

/** Host regression entry point; uses the real cached Media3 classes, not processor stubs. */
public final class PitchPreservingAudioProcessorTest {
    private static int passed;
    private static int failed;
    private static int streams;

    public static void main(String[] args) throws Exception {
        test("configuration and lifecycle", PitchPreservingAudioProcessorTest::lifecycle);
        test("short EOS tails", PitchPreservingAudioProcessorTest::shortTails);
        test("streaming chunk invariance", PitchPreservingAudioProcessorTest::chunkInvariance);
        test("speed and pitch", PitchPreservingAudioProcessorTest::pitch);
        test("streaming latency and wide counters", PitchPreservingAudioProcessorTest::latency);
        test("randomized EOS trimming", PitchPreservingAudioProcessorTest::randomTails);
        test("wide AMDF arithmetic", PitchPreservingAudioProcessorTest::wideAmdf);
        System.out.println(passed + " groups passed, " + failed + " failed; " + streams + " streams");
        if (failed != 0) throw new AssertionError("Processor regressions failed");
    }

    private static void lifecycle() throws Exception {
        for (float speed : new float[]{Float.NaN, Float.NEGATIVE_INFINITY,
                Float.POSITIVE_INFINITY, .49f, 2.01f}) {
            expect(IllegalArgumentException.class, () -> new PitchPreservingAudioProcessor(speed));
        }
        PitchPreservingAudioProcessor p = new PitchPreservingAudioProcessor(1.5f);
        expect(IllegalStateException.class, () -> p.queueInput(pcm(new short[]{1}, 0, 1)));
        for (AudioFormat f : new AudioFormat[]{new AudioFormat(7999, 1, C.ENCODING_PCM_16BIT),
                new AudioFormat(192001, 1, C.ENCODING_PCM_16BIT),
                new AudioFormat(48000, 0, C.ENCODING_PCM_16BIT),
                new AudioFormat(48000, 9, C.ENCODING_PCM_16BIT),
                new AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)}) {
            expect(AudioProcessor.UnhandledAudioFormatException.class, () -> p.configure(f));
        }
        p.configure(new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT));
        p.flush();
        check(p.isActive() && !p.isEnded(), "active after flush");
        ByteBuffer partial = ByteBuffer.allocateDirect(6).order(ByteOrder.nativeOrder());
        expect(IllegalArgumentException.class, () -> p.queueInput(partial));
        check(partial.position() == 0, "partial frame consumed on rejection");
        p.queueEndOfStream();
        check(p.isEnded() && !p.getOutput().hasRemaining(), "empty EOS");
        expect(IllegalStateException.class, () -> p.queueInput(pcm(new short[]{1, 2}, 0, 2)));
        p.flush();
        short[] input = signal(11000, 2, 48000, 440, 25000);
        byte[] first = process(p, input, 2, 1.5f, 67, true);
        p.flush();
        check(Arrays.equals(first, process(p, input, 2, 1.5f, 8192, false)), "flush replay");
        p.reset();
        check(!p.isActive() && !p.isEnded(), "reset lifecycle");
        expect(IllegalStateException.class, () -> p.queueInput(pcm(new short[]{1, 2}, 0, 2)));
        p.configure(new AudioFormat(8000, 1, C.ENCODING_PCM_16BIT));
        p.flush();
        process(p, signal(2500, 1, 8000, 200, 32000), 1, 1.5f, 17, true);
        PitchPreservingAudioProcessor unity = configured(1, 44100, 2);
        check(!unity.isActive(), "unity must bypass");
        byte[] actual = process(unity, input, 2, 1, 1, true);
        ByteBuffer expected = pcm(input, 0, input.length);
        byte[] bytes = new byte[expected.remaining()];
        expected.get(bytes);
        check(Arrays.equals(bytes, actual), "unity is not bit exact");
    }

    private static void shortTails() throws Exception {
        for (int channels : new int[]{1, 2}) {
            for (float speed : new float[]{.5f, .51f, .75f, .99f, 1f, 1.01f, 1.5f, 1.99f, 2f}) {
                for (int n = 0; n <= 100; n++) {
                    short[] input = new short[n * channels];
                    for (int i = 0; i < input.length; i++) input[i] = (short) (i % channels == 0 ? 32767 : -32768);
                    byte[] bytes = run(input, channels, 8000, speed, 13, true);
                    ByteBuffer output = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder());
                    for (int i = 0; output.hasRemaining(); i++) {
                        check(output.getShort() == (short) (i % channels == 0 ? 32767 : -32768),
                                "DC tail changed or padded: n=" + n + " speed=" + speed);
                    }
                }
            }
        }
    }

    private static void chunkInvariance() throws Exception {
        float[] speeds = {.5f, .5001f, .6f, .75f, .9f, .99f, Math.nextDown(1f),
                Math.nextUp(1f), 1.01f, 1.1f, 1.25f, 1.5f, 1.9f, 1.9999f, 2f};
        Random random = new Random(351);
        for (int rate : new int[]{8000, 44100, 48000, 192000}) {
            for (int channels : new int[]{1, 2}) {
                short[] input = new short[(rate / 3 + 19) * channels];
                for (int i = 0; i < input.length; i++) input[i] = (short) random.nextInt();
                for (float speed : speeds) {
                    byte[] expected = run(input, channels, rate, speed, input.length, true);
                    for (int chunk : new int[]{1, 127, 997}) {
                        check(Arrays.equals(expected, run(input, channels, rate, speed, chunk, chunk != 127)),
                                "chunk-dependent PCM: rate=" + rate + " channels=" + channels
                                        + " speed=" + speed + " chunk=" + chunk);
                    }
                }
            }
        }
        for (int channels : new int[]{1, 2, 8}) {
            for (int n : new int[]{3075, 3076, 3077, 6149, 6152, 10000}) {
                short[] input = signal(n, channels, 50000, 333, 30000);
                for (float speed : speeds) {
                    check(Arrays.equals(run(input, channels, 50000, speed, 1, true),
                                    run(input, channels, 50000, speed, 7777, false)),
                            "lookahead boundary changed PCM");
                }
            }
        }
    }

    private static void pitch() throws Exception {
        for (int frequency : new int[]{70, 110, 250, 997}) {
            pitch(frequency);
        }
    }

    private static void pitch(int frequency) throws Exception {
        for (int rate : new int[]{8000, 44100, 48000, 192000}) {
            for (int channels : new int[]{1, 2}) {
                for (float speed : new float[]{.5f, .6f, .75f, .9f, 1.01f, 1.25f, 1.5f, 1.9f, 2f}) {
                    short[] input = signal(rate, channels, rate, frequency, 30000);
                    byte[] bytes = run(input, channels, rate, speed, 991, true);
                    short[] output = new short[bytes.length / 2];
                    ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder()).asShortBuffer().get(output);
                    int start = rate / 10, end = output.length / channels - rate / 10;
                    int crossings = 0;
                    long energy = 0;
                    for (int i = start; i < end; i++) {
                        int value = output[i * channels];
                        if (value > 0 && output[(i - 1) * channels] <= 0) crossings++;
                        energy += (long) value * value;
                        if (channels == 2) check(value == -output[i * channels + 1], "stereo phase lost");
                    }
                    double hz = crossings * (double) rate / (end - start);
                    check(Math.abs(hz - frequency) < 5, "pitch changed: " + hz + " expected="
                            + frequency + " rate=" + rate + " speed=" + speed);
                    check(Math.sqrt(energy / (double) (end - start)) > 19000, "tone attenuated");
                }
            }
        }
    }

    private static void wideAmdf() throws Exception {
        PitchPreservingAudioProcessor p = configured(.75f, 192000, 8);
        short[] data = new short[2 * 2953 * 8];
        Random random = new Random(5151);
        for (int i = 0; i < data.length; i++) data[i] = (short) random.nextInt();
        Method method = PitchPreservingAudioProcessor.class.getDeclaredMethod(
                "bestPeriod", short[].class, int.class, int.class);
        method.setAccessible(true);
        int actual = (int) method.invoke(p, data, 480, 2953);
        int expected = 480;
        double best = Double.POSITIVE_INFINITY;
        long largestProduct = 0;
        for (int period = 480; period <= 2953; period++) {
            long difference = 0;
            for (int i = 0; i < period * 8; i++) difference += Math.abs(data[i] - data[i + period * 8]);
            largestProduct = Math.max(largestProduct, difference * period);
            double average = difference / (double) period;
            if (average < best) {
                best = average;
                expected = period;
            }
        }
        check(largestProduct > Integer.MAX_VALUE, "overflow fixture too quiet");
        check(actual == expected, "AMDF disagrees with independent normalized reference");
        Field inputFrames = PitchPreservingAudioProcessor.class.getDeclaredField("inputFrames");
        inputFrames.setAccessible(true);
        check(inputFrames.getType() == long.class, "input frame counter must be wide");
    }

    private static void latency() throws Exception {
        int rate = 48000, window = 4 * (rate / 65);
        for (float speed : new float[]{.5f, .50001f, .75f, Math.nextDown(1f),
                Math.nextUp(1f), 1.25f, 1.99999f, 2f}) {
            PitchPreservingAudioProcessor p = configured(speed, rate, 2);
            short[] input = signal(window * 3, 2, rate, 200, 30000);
            ByteBuffer buffer = pcm(input, 0, input.length);
            int start = buffer.position();
            p.queueInput(buffer);
            check(buffer.position() - start == window * 4, "input buffer not bounded to lookahead");
            int position = buffer.position();
            p.queueInput(buffer);
            check(buffer.position() == position, "full buffer over-consumed");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            check(drain(p, output, 2, true) > 0, "first output delayed beyond four periods");
            long accepted = window;
            while (buffer.hasRemaining()) {
                position = buffer.position();
                p.queueInput(buffer);
                accepted += (buffer.position() - position) / 4;
                drain(p, output, 2, true);
                long due = Math.round(accepted / (double) speed);
                long emitted = output.size() / 4;
                check(emitted <= due && due - emitted <= Math.ceil(window / (double) speed) + 1,
                        "unbounded streaming latency or output beyond EOS budget");
            }
            p.queueEndOfStream();
            while (!p.isEnded()) check(drain(p, output, 2, true) > 0, "latency EOS stalled");
            check(output.size() / 4 == Math.round(input.length / 2 / (double) speed), "latency count");
            long duration = 10_000_000_000L;
            check(p.getDurationAfterProcessorApplied(duration) == Math.round(duration / (double) speed),
                    "duration conversion overflow");
        }
        for (float speed : new float[]{.5f, .75f, 1.5f, 2f}) {
            PitchPreservingAudioProcessor p = configured(speed, rate, 1);
            long prefix = 3L << 34;
            setCounter(p, "inputFrames", prefix);
            setCounter(p, "outputFrames", Math.round(prefix / (double) speed));
            short[] input = signal(47, 1, rate, 200, 30000);
            p.queueInput(pcm(input, 0, input.length));
            p.queueEndOfStream();
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (!p.isEnded()) check(drain(p, output, 1, true) > 0, "wide counter EOS stalled");
            check(output.size() / 2 == Math.round((prefix + input.length) / (double) speed)
                    - Math.round(prefix / (double) speed), "counter truncated at 32 bits");
        }
    }

    private static void setCounter(PitchPreservingAudioProcessor p, String name, long value)
            throws Exception {
        Field field = PitchPreservingAudioProcessor.class.getDeclaredField(name);
        field.setAccessible(true);
        field.setLong(p, value);
    }

    private static void randomTails() throws Exception {
        Random random = new Random(10151);
        for (int trial = 0; trial < 1000; trial++) {
            int rate = trial % 2 == 0 ? 8000 : 48000;
            int channels = trial % 3 == 0 ? 2 : 1;
            int frames = random.nextInt(8 * (rate / 65));
            float speed = .5f + random.nextFloat() * 1.5f;
            short[] input = new short[frames * channels];
            for (int i = 0; i < input.length; i++) {
                int value = 1000 + random.nextInt(30000);
                input[i] = (short) (i % channels == 0 ? value : -value);
            }
            byte[] expected = run(input, channels, rate, speed, Math.max(1, frames), false);
            byte[] actual = run(input, channels, rate, speed, 1 + random.nextInt(173), true);
            check(Arrays.equals(expected, actual), "random EOS chunking: trial=" + trial);
            ByteBuffer output = ByteBuffer.wrap(actual).order(ByteOrder.nativeOrder());
            for (int i = 0; output.hasRemaining(); i++) {
                int value = output.getShort() * (i % channels == 0 ? 1 : -1);
                check(value >= 1000 && value < 31000, "tail padded, overflowed or crossed channels");
            }
        }
    }

    private static byte[] run(short[] input, int channels, int rate, float speed, int chunk,
            boolean drainAll) throws Exception {
        return process(configured(speed, rate, channels), input, channels, speed, chunk, drainAll);
    }

    private static PitchPreservingAudioProcessor configured(float speed, int rate, int channels)
            throws Exception {
        PitchPreservingAudioProcessor p = new PitchPreservingAudioProcessor(speed);
        p.configure(new AudioFormat(rate, channels, C.ENCODING_PCM_16BIT));
        p.flush();
        return p;
    }

    private static byte[] process(PitchPreservingAudioProcessor p, short[] input, int channels,
            float speed, int chunk, boolean drainAll) {
        streams++;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int frameBytes = channels * 2;
        for (int offset = 0; offset < input.length;) {
            int count = Math.min(chunk * channels, input.length - offset);
            ByteBuffer buffer = pcm(input, offset, count);
            int limit = buffer.limit();
            while (buffer.hasRemaining()) {
                int before = buffer.position();
                p.queueInput(buffer);
                check(buffer.limit() == limit && (buffer.position() - before) % frameBytes == 0,
                        "input accounting");
                int emitted = drain(p, output, channels, drainAll);
                check(buffer.position() > before || emitted > 0, "stream deadlocked");
                check(!p.isEnded(), "ended before EOS");
            }
            offset += count;
        }
        p.queueEndOfStream();
        int calls = 0;
        while (!p.isEnded()) {
            check(++calls < 100000, "EOS deadlocked");
            check(drain(p, output, channels, false) > 0 || p.isEnded(), "EOS made no progress");
        }
        check(!p.getOutput().hasRemaining(), "output after ended");
        long expected = Math.round((input.length / channels) / (double) speed);
        check(output.size() == expected * frameBytes, "frame count: speed=" + speed
                + " in=" + input.length / channels + " expected=" + expected
                + " actual=" + output.size() / frameBytes);
        return output.toByteArray();
    }

    private static int drain(PitchPreservingAudioProcessor p, ByteArrayOutputStream output,
            int channels, boolean all) {
        int emitted = 0;
        do {
            ByteBuffer buffer = p.getOutput();
            if (!buffer.hasRemaining()) break;
            check(buffer.isDirect() && buffer.order() == ByteOrder.nativeOrder(), "output layout");
            check(buffer.remaining() % (channels * 2) == 0, "partial output frame");
            emitted += buffer.remaining();
            while (buffer.hasRemaining()) output.write(buffer.get());
        } while (all);
        return emitted;
    }

    private static ByteBuffer pcm(short[] samples, int offset, int count) {
        ByteBuffer buffer = ByteBuffer.allocateDirect(count * 2 + 8).order(ByteOrder.nativeOrder());
        buffer.position(4);
        for (int i = offset; i < offset + count; i++) buffer.putShort(samples[i]);
        buffer.limit(buffer.position());
        buffer.position(4);
        return buffer;
    }

    private static short[] signal(int frames, int channels, int rate, double hz, int amplitude) {
        short[] result = new short[frames * channels];
        for (int i = 0; i < frames; i++) {
            short value = (short) Math.round(amplitude * Math.sin(2 * Math.PI * hz * i / rate));
            for (int c = 0; c < channels; c++) result[i * channels + c] = (short) (c % 2 == 0 ? value : -value);
        }
        return result;
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static void expect(Class<? extends Throwable> type, CheckedRunnable operation) throws Exception {
        try {
            operation.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) return;
            throw failure;
        }
        throw new AssertionError("Expected " + type.getSimpleName());
    }

    private static void test(String name, CheckedRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            failed++;
            System.out.println("FAIL " + name);
            failure.printStackTrace(System.out);
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
