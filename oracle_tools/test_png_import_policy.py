"""Execute the production PNG copy/check methods against a virtual slow provider.

Only Android provider/clock and decoding are substituted here; native import tests
exercise the real decoder, CRC and resource limits.
"""
import os
from pathlib import Path
import subprocess
import unittest


ROOT = Path(__file__).resolve().parents[1]


class PngImportPolicyTest(unittest.TestCase):
    def test_valid_provider_beyond_60_seconds_and_101_hours_and_cancellation(self):
        production = (ROOT / r"app\src\main\java\com\simple\videoeditor\PngWatermark.java").read_text()
        methods = production[production.index("    static PngWatermark copyDocument("):
                             production.index("    public static PngWatermark fromBytes(")]
        build = ROOT / r"app\build\png-import-policy"
        build.mkdir(parents=True, exist_ok=True)
        harness = r"""
package com.simple.videoeditor;
import java.io.*;
import java.util.Arrays;
public final class PngWatermark {
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    static long elapsed, step;
    static boolean interrupt;
    static byte[] decoded;
    static final byte[] VALID = new byte[100000];
    static class SystemClock { static long elapsedRealtime() { return elapsed; } }
    static class Uri {}
    static class Context {
        Context getContentResolver() { return this; }
        InputStream openInputStream(Uri uri) {
            return new ByteArrayInputStream(VALID) {
                public synchronized int read(byte[] b, int o, int n) {
                    elapsed += step;
                    if (interrupt) Thread.currentThread().interrupt();
                    return super.read(b, o, n);
                }
            };
        }
    }
    static PngWatermark fromBytes(byte[] bytes) {
        decoded = bytes;
        return new PngWatermark();
    }
METHODS
    public static void main(String[] args) throws Exception {
        File target = new File(args[0], "copied.png");
        try {
            for (long delay : new long[]{61000, 101L * 3600000}) {
                elapsed = 0; step = delay; decoded = null;
                copyDocument(new Context(), new Uri(), target);
                if (elapsed <= delay || !Arrays.equals(VALID, decoded)
                        || !Arrays.equals(VALID, java.nio.file.Files.readAllBytes(target.toPath())))
                    throw new AssertionError("slow provider must copy exactly without a time budget");
            }
            interrupt = true;
            try {
                copyDocument(new Context(), new Uri(), target);
                throw new AssertionError("interruption must not be swallowed");
            } catch (InterruptedIOException expected) {
                if (!Thread.currentThread().isInterrupted()) throw new AssertionError("lost interrupt");
            } finally { Thread.interrupted(); }
            System.out.println("PASS: production copy at virtual 61s/read and 101h/read; cancellation retained");
        } finally { target.delete(); }
    }
}
""".replace("METHODS", methods)
        java = build / "PngWatermark.java"
        java.write_text(harness, encoding="utf-8")
        jdk = Path(os.environ["JAVA_HOME"]) / "bin"
        subprocess.run([str(jdk / "javac.exe"), "-encoding", "UTF-8", "-d", str(build), str(java),
                        str(ROOT / r"app\src\main\java\com\simple\videoeditor\MediaCopy.java")],
                       check=True, timeout=60)
        subprocess.run([str(jdk / "java.exe"), "-cp", str(build),
                        "com.simple.videoeditor.PngWatermark", str(build)], check=True, timeout=30)
        self.assertNotIn("SystemClock", production)
        self.assertNotIn("nanoTime", production)
        self.assertNotIn("currentTimeMillis", production)


if __name__ == "__main__":
    unittest.main()
