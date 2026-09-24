package com.simple.videoeditor;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;

/** Display only. Never used as the source for persisted or shared diagnostics. */
final class ReportPreview {
    static final int LIMIT = 16 * 1024;
    static final String NOTICE = "[TAIL PREVIEW ONLY / 仅末尾预览 — Full TXT available via Share report / 分享完整 TXT]\n";
    private final StringBuilder tail = new StringBuilder(LIMIT);

    void append(String text) {
        int start = Math.max(0, text.length() - LIMIT);
        int remove = Math.max(0, tail.length() + text.length() - start - LIMIT);
        tail.delete(0, remove);
        tail.append(text, start, text.length());
        if (tail.length() > 0 && Character.isLowSurrogate(tail.charAt(0))) tail.deleteCharAt(0);
    }

    String text() { return NOTICE + tail; }
    int length() { return tail.length(); }

    static String read(InputStream input) throws IOException {
        ReportPreview preview = new ReportPreview();
        InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Report restoration cancelled");
            preview.append(new String(buffer, 0, count));
        }
        return preview.text();
    }
}
