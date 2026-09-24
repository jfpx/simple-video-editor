package com.simple.videoeditor;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Random;

/** Exact PCM budgets and unchanged prefix bytes; AAC packet tolerances play no role. */
public final class TimelineAudioProcessorTest {
    private static int streams;

    public static void main(String[] args) throws Exception {
        boundaries();
        combo();
        lifecycle();
        System.out.println("PASS timeline PCM boundaries, combo, lifecycle; " + streams + " streams");
    }

    private static void boundaries() throws Exception {
        Random random = new Random(48256);
        for (int rate : new int[]{8000, 44100, 48000, 192000}) {
            for (int channels : new int[]{1, 2, 8}) {
                for (int encoding : new int[]{C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT}) {
                    AudioFormat format = new AudioFormat(rate, channels, encoding);
                    for (int frames : new int[]{0, 1, 1023, 1024, 1025, 48000}) {
                        long durationUs = ((frames + 1L) * 1_000_000 + rate - 1) / rate - 1;
                        check(durationUs * rate / 1_000_000 == frames, "test frame boundary");
                        byte[] input = new byte[(frames + 256) * format.bytesPerFrame];
                        random.nextBytes(input);
                        for (int chunk : new int[]{1, 997, 65536}) {
                            byte[] output = run(new TimelineAudioProcessor(durationUs), format, input, chunk);
                            check(Arrays.equals(Arrays.copyOf(input, frames * format.bytesPerFrame), output),
                                    "boundary prefix changed: " + rate + "/" + channels + "/" + frames);
                        }
                    }
                }
            }
        }
        AudioFormat format = new AudioFormat(48000, 1, C.ENCODING_PCM_16BIT);
        byte[] shortInput = new byte[123 * 2];
        random.nextBytes(shortInput);
        check(Arrays.equals(shortInput, run(new TimelineAudioProcessor(1_000_000),
                format, shortInput, 17)), "short audio must not be padded");
        check(Arrays.equals(shortInput, run(new TimelineAudioProcessor(Long.MAX_VALUE),
                format, shortInput, 17)), "large duration overflow");
        byte[] silence = new byte[48256 * 2];
        check(Arrays.equals(new byte[48000 * 2],
                run(new TimelineAudioProcessor(1_000_000), format, silence, 1024)), "silence");
    }

    private static void combo() throws Exception {
        AudioFormat format = new AudioFormat(48000, 1, C.ENCODING_PCM_16BIT);
        for (float speed : new float[]{.5f, 2f}) {
            int sourceFrames = speed == 2f ? 94 * 1024 : 24064;
            ByteBuffer source = ByteBuffer.allocate(sourceFrames * 2).order(ByteOrder.nativeOrder());
            for (int i = 0; i < sourceFrames; i++) {
                source.putShort((short) (20000 * Math.sin(2 * Math.PI * 997 * i / 48000)));
            }
            byte[] scaled = run(new PitchPreservingAudioProcessor(speed), format, source.array(), 1024);
            check(scaled.length / 2 == 48128, "speed processor must scale actual input exactly");
            byte[] mixed = new byte[128 * 2 + scaled.length];
            System.arraycopy(scaled, 0, mixed, 128 * 2, scaled.length);
            check(mixed.length / 2 == 48256, "reproduce observed mixed PCM overrun");
            byte[] result = run(new TimelineAudioProcessor(1_000_000), format, mixed, 127);
            check(result.length / 2 == 48000, "combo timeline must end at sample 48000");
            check(Arrays.equals(Arrays.copyOf(mixed, 48000 * 2), result), "mixer delay/content changed");
            check(!Arrays.equals(mixed, result), "negative control must fail without trimming");
        }
        // One composition budget spans intro silence, music/main and merge-tail content.
        byte[] timeline = new byte[48256 * 2];
        Arrays.fill(timeline, 6144 * 2, 23144 * 2, (byte) 23);
        Arrays.fill(timeline, 23144 * 2, timeline.length, (byte) 79);
        check(Arrays.equals(Arrays.copyOf(timeline, 48000 * 2),
                run(new TimelineAudioProcessor(1_000_000), format, timeline, 6144)),
                "intro/merge markers shifted or item budgets reset");
    }

    private static void lifecycle() throws Exception {
        try {
            new TimelineAudioProcessor(-1);
            throw new AssertionError("negative duration accepted");
        } catch (IllegalArgumentException expected) { }
        TimelineAudioProcessor p = new TimelineAudioProcessor(1000);
        AudioFormat stereo = new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT);
        p.configure(stereo);
        p.flush();
        ByteBuffer partial = ByteBuffer.allocateDirect(3);
        try {
            p.queueInput(partial);
            throw new AssertionError("partial frame accepted");
        } catch (IllegalArgumentException expected) {
            check(partial.position() == 0, "partial input consumed");
        }
        ByteBuffer input = ByteBuffer.allocateDirect(256);
        p.queueInput(input);
        check(input.position() == input.limit(), "overrun not consumed");
        ByteBuffer next = ByteBuffer.allocateDirect(4);
        p.queueInput(next);
        check(next.position() == 0, "pending output overwritten");
        p.queueEndOfStream();
        check(!p.isEnded(), "EOS before output drained");
        check(p.getOutput().remaining() == 48 * 4, "pending EOS output");
        check(p.isEnded(), "EOS not drained");
        try {
            p.queueInput(next);
            throw new AssertionError("input after EOS accepted");
        } catch (IllegalStateException expected) { }
        p.flush();
        check(!p.isEnded(), "flush did not clear EOS");
        byte[] bytes = new byte[200 * 4];
        new Random(11).nextBytes(bytes);
        check(Arrays.equals(Arrays.copyOf(bytes, 48 * 4), collect(p, stereo, bytes, 13)),
                "flush did not restore budget");
        p.reset();
        check(!p.isActive(), "reset active");
        AudioFormat mono = new AudioFormat(8000, 1, C.ENCODING_PCM_16BIT);
        check(run(p, mono, new byte[40], 3).length == 16, "reconfigure sample rate budget");
    }

    private static byte[] run(AudioProcessor processor, AudioFormat format, byte[] input, int chunk)
            throws Exception {
        processor.configure(format);
        processor.flush();
        return collect(processor, format, input, chunk);
    }

    private static byte[] collect(AudioProcessor processor, AudioFormat format, byte[] bytes, int chunk) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        int calls = 0;
        for (int offset = 0; offset < bytes.length;) {
            int count = Math.min(bytes.length - offset, chunk * format.bytesPerFrame);
            ByteBuffer input = ByteBuffer.allocateDirect(count).order(ByteOrder.nativeOrder());
            input.put(bytes, offset, count).flip();
            while (input.hasRemaining()) {
                int before = input.position();
                processor.queueInput(input);
                int drained = drain(processor, result);
                check(input.position() > before || drained > 0, "processor stalled");
                check(++calls < 5_000_000, "input bound");
            }
            offset += count;
        }
        processor.queueEndOfStream();
        while (!processor.isEnded()) {
            drain(processor, result);
            check(++calls < 5_000_000, "EOS bound");
        }
        check(!processor.getOutput().hasRemaining(), "output after EOS");
        streams++;
        return result.toByteArray();
    }

    private static int drain(AudioProcessor processor, ByteArrayOutputStream result) {
        ByteBuffer output = processor.getOutput();
        int count = output.remaining();
        byte[] bytes = new byte[count];
        output.get(bytes);
        result.write(bytes, 0, count);
        return count;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
