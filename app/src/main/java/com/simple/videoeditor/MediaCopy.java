package com.simple.videoeditor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;

/** Streaming import: byte/resource limits are independent of how long a provider takes. */
final class MediaCopy {
    interface Progress { void copied(long bytes); }

    static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Media loading cancelled");
    }

    static long copy(InputStream input, OutputStream output, long maxBytes, Progress progress) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = 0;
        while (true) {
            checkCancelled();
            int count = input.read(buffer);
            checkCancelled();
            if (count == -1) break;
            if (count == 0) continue;
            if (maxBytes > 0 && count > maxBytes - written) {
                throw new IOException("Selected video exceeds the available snapshot byte limit ("
                        + maxBytes + " bytes; 128 MiB per clip, 256 MiB aggregate)");
            }
            output.write(buffer, 0, count);
            written += count;
            progress.copied(written);
        }
        return written;
    }
}
