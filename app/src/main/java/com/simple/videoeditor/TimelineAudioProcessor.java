package com.simple.videoeditor;

import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/** Bounds final mixed PCM, including mixer-inserted silence, to the edited timeline. */
@UnstableApi
public final class TimelineAudioProcessor extends BaseAudioProcessor {
    private final long durationUs;
    private long remainingFrames;
    private boolean ended;

    public TimelineAudioProcessor(long durationUs) {
        if (durationUs < 0) throw new IllegalArgumentException("Negative timeline duration");
        this.durationUs = durationUs;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat format) throws UnhandledAudioFormatException {
        if (format.sampleRate <= 0 || format.channelCount <= 0 || format.bytesPerFrame <= 0) {
            throw new UnhandledAudioFormatException(format);
        }
        return format;
    }

    @Override
    protected void onFlush() {
        ended = false;
        if (inputAudioFormat == AudioFormat.NOT_SET) return;
        long seconds = durationUs / 1_000_000;
        long fraction = durationUs % 1_000_000 * inputAudioFormat.sampleRate / 1_000_000;
        remainingFrames = seconds > (Long.MAX_VALUE - fraction) / inputAudioFormat.sampleRate
                ? Long.MAX_VALUE : seconds * inputAudioFormat.sampleRate + fraction;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        if (!input.hasRemaining()) return;
        if (ended || inputAudioFormat == AudioFormat.NOT_SET) {
            throw new IllegalStateException("Configure and flush before input; no input after EOS");
        }
        int bytesPerFrame = inputAudioFormat.bytesPerFrame;
        if (input.remaining() % bytesPerFrame != 0) {
            throw new IllegalArgumentException("Input must contain complete PCM frames");
        }
        if (hasPendingOutput()) return;
        if (remainingFrames == 0) {
            input.position(input.limit());
            return;
        }
        int frames = (int) Math.min(remainingFrames,
                Math.min(input.remaining() / bytesPerFrame, Math.max(1, 32768 / bytesPerFrame)));
        int limit = input.limit();
        input.limit(input.position() + frames * bytesPerFrame);
        ByteBuffer output = replaceOutputBuffer(input.remaining());
        output.put(input).flip();
        input.limit(limit);
        remainingFrames -= frames;
        // Drain the upstream pipeline normally. Do not fabricate EOS or guess AAC priming.
        if (remainingFrames == 0) input.position(limit);
    }

    @Override
    protected void onQueueEndOfStream() {
        ended = true;
    }

    @Override
    protected void onReset() {
        remainingFrames = 0;
        ended = false;
    }
}
