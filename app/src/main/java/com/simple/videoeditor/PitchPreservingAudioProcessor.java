package com.simple.videoeditor;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;

import java.nio.ByteBuffer;

/**
 * Streaming PCM16 time scaling for the editor's 0.5x..2x range.
 * Inserts or removes waveform periods using complementary linear crossfades.
 * Uses wide AMDF arithmetic to avoid the level-dependent period-selection
 * overflow in Media3 1.5.1's Sonic implementation.
 */
@UnstableApi
public final class PitchPreservingAudioProcessor extends BaseAudioProcessor {
    private final double speed;
    private short[] samples = new short[0];
    private short[] coarse = new short[0];
    private int channels;
    private int minPeriod;
    private int maxPeriod;
    private int lookahead;
    private int frames;
    private long inputFrames;
    private long outputFrames;
    private long copyFrames;
    private double copyRemainder;
    private int pendingPeriod;
    private boolean ended;
    private long targetFrames;

    public PitchPreservingAudioProcessor(float speed) {
        if (Float.isNaN(speed) || speed < .5f || speed > 2f) {
            throw new IllegalArgumentException("speed must be finite and within 0.5..2");
        }
        this.speed = speed;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat format) throws UnhandledAudioFormatException {
        if (format.encoding != C.ENCODING_PCM_16BIT
                || format.sampleRate < 8000 || format.sampleRate > 192000
                || format.channelCount < 1 || format.channelCount > 8) {
            throw new UnhandledAudioFormatException(format);
        }
        return speed == 1 ? AudioFormat.NOT_SET : format;
    }

    @Override
    public long getDurationAfterProcessorApplied(long durationUs) {
        return Math.round(durationUs / speed);
    }

    @Override
    protected void onFlush() {
        frames = 0;
        inputFrames = 0;
        outputFrames = 0;
        copyFrames = 0;
        copyRemainder = 0;
        pendingPeriod = 0;
        ended = false;
        targetFrames = 0;
        if (inputAudioFormat == AudioFormat.NOT_SET) return;
        channels = inputAudioFormat.channelCount;
        minPeriod = inputAudioFormat.sampleRate / 400;
        maxPeriod = inputAudioFormat.sampleRate / 65;
        lookahead = 2 * maxPeriod;
        samples = new short[2 * lookahead * channels];
        coarse = new short[lookahead * channels];
    }

    @Override
    protected void onReset() {
        samples = new short[0];
        coarse = new short[0];
        channels = 0;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        if (!input.hasRemaining()) return;
        if (ended) throw new IllegalStateException("Input after end of stream");
        if (channels == 0) throw new IllegalStateException("Configure and flush before input");
        if (input.remaining() % (channels * 2) != 0) {
            throw new IllegalArgumentException("Input must contain complete PCM frames");
        }
        int count = Math.min(input.remaining() / (channels * 2),
                samples.length / channels - frames);
        for (int i = 0; i < count * channels; i++) {
            samples[frames * channels + i] = input.getShort();
        }
        frames += count;
        inputFrames += count;
    }

    @Override
    protected void onQueueEndOfStream() {
        ended = true;
        targetFrames = Math.round(inputFrames / speed);
    }

    @Override
    public boolean isEnded() {
        return super.isEnded() && ended && outputFrames == targetFrames;
    }

    @Override
    public ByteBuffer getOutput() {
        if (hasPendingOutput()) return super.getOutput();
        if (ended && outputFrames == targetFrames) return EMPTY_BUFFER;
        if (speed == 1) return frames == 0 ? EMPTY_BUFFER : copyOutput(frames);
        if (copyFrames > 0 && frames > lookahead) {
            int count = (int) Math.min(copyFrames, frames - lookahead);
            copyFrames -= count;
            return copyOutput(count);
        }

        // Every streaming step leaves at least 2 * maxPeriod real frames.
        // This covers the pending period's rate error, including copy rounding.
        // Use the same readiness rule at EOS to keep the schedule chunk invariant.
        if (frames < 2 * lookahead) return ended ? drainOutput() : EMPTY_BUFFER;
        if (pendingPeriod == 0) {
            pendingPeriod = findPeriod();
            // Pay for the edit before applying it, carrying fractional copies
            // between cycles. Near unity this may span many input buffers.
            double copies = pendingPeriod * (speed > 1
                    ? (2 - speed) / (speed - 1) : (2 * speed - 1) / (1 - speed))
                    + copyRemainder;
            copyFrames = Math.round(copies);
            copyRemainder = copies - copyFrames;
            if (copyFrames > 0) {
                int count = (int) Math.min(copyFrames, frames - lookahead);
                copyFrames -= count;
                return copyOutput(count);
            }
        }
        int period = pendingPeriod;
        pendingPeriod = 0;
        return periodOutput(period, speed < 1);
    }

    private ByteBuffer copyOutput(int count) {
        ByteBuffer output = replaceOutputBuffer(count * channels * 2);
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < channels; c++) output.putShort(sample(i, c));
        }
        discard(count);
        outputFrames += count;
        output.flip();
        return super.getOutput();
    }

    private ByteBuffer periodOutput(int period, boolean insert) {
        int count = insert ? 2 * period : period;
        ByteBuffer output = replaceOutputBuffer(count * channels * 2);
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < channels; c++) {
                if (insert && i < period) {
                    output.putShort(sample(i, c));
                } else {
                    int offset = insert ? i - period : i;
                    int a = sample(insert ? period + offset : offset, c);
                    int b = sample(insert ? offset : period + offset, c);
                    output.putShort((short) (((long) a * (period - offset)
                            + (long) b * offset) / period));
                }
            }
        }
        discard(insert ? period : 2 * period);
        outputFrames += count;
        output.flip();
        return super.getOutput();
    }

    private ByteBuffer drainOutput() {
        long remaining = targetFrames - outputFrames;
        if (remaining == frames) return copyOutput(frames);
        if (frames == 0 || remaining <= 0) {
            throw new IllegalStateException("Invalid time-scaling tail");
        }
        int period = frames >= lookahead ? findPeriod()
                : frames == 1 ? 1
                : bestPeriod(samples, Math.min(minPeriod, frames / 2),
                        Math.min(maxPeriod, frames / 2));
        long adjustment = remaining - frames;
        if (frames >= 2 * period) {
            if (adjustment >= period) return periodOutput(period, true);
            if (adjustment <= -period && (remaining > period || frames == 2 * period)) {
                return periodOutput(period, false);
            }
        }

        // Finish the residual adjustment with a real-signal splice. A shortened
        // tail may need a larger removal when another full edit would exhaust
        // its output budget. All channels share the same offsets and weights.
        int count = (int) remaining;
        int shift = count - frames;
        int overlap = shift > 0 ? Math.min(period, frames - shift)
                : Math.min(period, count);
        int start = shift > 0 ? shift + (frames - shift - overlap) / 2
                : (count - overlap) / 2;
        ByteBuffer output = replaceOutputBuffer(count * channels * 2);
        for (int i = 0; i < count; i++) {
            for (int c = 0; c < channels; c++) {
                if (frames == 1) {
                    output.putShort(sample(0, c));
                } else if (i < start) {
                    output.putShort(sample(i, c));
                } else if (i >= start + overlap || overlap == 1) {
                    output.putShort(sample(i - shift, c));
                } else {
                    int offset = i - start;
                    long a = sample(i, c);
                    long b = sample(i - shift, c);
                    output.putShort((short) ((a * (overlap - 1 - offset)
                            + b * offset) / (overlap - 1)));
                }
            }
        }
        discard(frames);
        outputFrames += count;
        output.flip();
        return super.getOutput();
    }

    private short sample(int frame, int channel) {
        return samples[frame * channels + channel];
    }

    private void discard(int count) {
        int removed = Math.min(count, frames);
        frames -= removed;
        System.arraycopy(samples, removed * channels, samples, 0, frames * channels);
    }

    private int findPeriod() {
        int step = Math.max(1, inputAudioFormat.sampleRate / 4000);
        int coarseFrames = lookahead / step;
        for (int i = 0; i < coarseFrames; i++) {
            for (int c = 0; c < channels; c++) {
                long sum = 0;
                for (int j = 0; j < step; j++) sum += samples[(i * step + j) * channels + c];
                coarse[i * channels + c] = (short) (sum / step);
            }
        }
        int estimate = bestPeriod(coarse, minPeriod / step, maxPeriod / step) * step;
        return bestPeriod(samples, Math.max(minPeriod, estimate - 4 * step),
                Math.min(maxPeriod, estimate + 4 * step));
    }

    private int bestPeriod(short[] data, int first, int last) {
        int best = first;
        long bestDifference = Long.MAX_VALUE;
        for (int period = first; period <= last; period++) {
            long difference = 0;
            int length = period * channels;
            // Keep channels separate: averaging L/R would erase anti-phase stereo.
            for (int i = 0; i < length; i++) {
                difference += Math.abs((int) data[i] - data[length + i]);
            }
            // Both the AMDF sum and the cross products must be wide. PCM16 at
            // ordinary music levels already overflows a 32-bit sum * period.
            if (bestDifference == Long.MAX_VALUE
                    || difference * best < bestDifference * period) {
                best = period;
                bestDifference = difference;
            }
        }
        return best;
    }
}
