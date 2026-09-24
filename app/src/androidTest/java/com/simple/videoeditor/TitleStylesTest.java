package com.simple.videoeditor;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.AndroidTestCase;
import com.simple.videoeditor.oracle.OracleGeneratedContract;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

@androidx.media3.common.util.UnstableApi
public final class TitleStylesTest extends AndroidTestCase {
    public void testFade3s() throws Exception { check("fade", 3000, "sans-serif", "normal"); }
    public void testFade5s() throws Exception { check("fade", 5000, "serif", "bold"); }
    public void testSlide3s() throws Exception { check("slide", 3000, "monospace", "italic"); }
    public void testSlide5s() throws Exception { check("slide", 5000, "sans-serif", "bold_italic"); }
    public void testTypewriter3s() throws Exception { check("typewriter", 3000, "serif", "normal"); }
    public void testTypewriter5s() throws Exception { check("typewriter", 5000, "monospace", "bold"); }
    public void testScale3s() throws Exception { check("scale", 3000, "sans-serif", "italic"); }
    public void testScale5s() throws Exception { check("scale", 5000, "serif", "bold_italic"); }
    public void testLowerThird3s() throws Exception { check("lower-third", 3000, "monospace", "normal"); }
    public void testLowerThird5s() throws Exception { check("lower-third", 5000, "sans-serif", "bold"); }

    public void testWrappingSafeOverflowFontsAlignmentAndGradient() throws Exception {
        for (String family : new String[]{"sans-serif", "serif", "monospace"}) {
            for (String weight : new String[]{"normal", "bold", "italic", "bold_italic"}) {
                for (String align : new String[]{"left", "center", "right"}) {
                    IntroTemplate template = new IntroTemplate();
                    template.setAnimation("fade"); template.setFontFamily(family);
                    template.setFontStyle(weight); template.setAlignment(align); template.setTextSize(160);
                    template.setBackgroundColor(0xFF001020); template.setGradientColor(0xFF304060);
                    String text = "MULTILINE\nA genuine paragraph that must wrap to fit the bounded canvas safely. "
                            + "LongTokenWithoutSpacesMustAlsoWrapRatherThanOverflow. More words for the final line.";
                    EditConfig.IntroTitle title = new EditConfig.Builder(Uri.EMPTY, 1).sourceSize(320, 240)
                            .introTemplate(template, text).build().introTitle;
                    Bitmap bitmap = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
                    TitleRenderer.draw(new Canvas(bitmap), 320, 240, title, 1200000);
                    int white = 0;
                    for (int y = 0; y < 240; y++) for (int x = 0; x < 320; x++) {
                        if ((bitmap.getPixel(x, y) >> 16 & 255) > 180) {
                            white++;
                            assertTrue("text remains in safe area", x >= 16 && x < 304 && y >= 12 && y < 228);
                        }
                    }
                    assertTrue("wrapped text actually rendered", white > 100);
                    assertFalse("real gradient, not palette label only", bitmap.getPixel(2, 2) == bitmap.getPixel(317, 237));
                    bitmap.recycle();
                }
            }
        }
    }

    private void check(String style, int duration, String family, String weight) throws Exception {
        File source = new OracleVerifier(getContext(), OracleGeneratedContract.create()).prepareFixture();
        IntroTemplate template = new IntroTemplate();
        template.setAnimation(style); template.setDurationMs(duration);
        template.setFontFamily(family); template.setFontStyle(weight);
        template.setTextSize(64); template.setBackgroundColor(0xFF000000);
        template.setGradientColor(0xFF000000); template.setTextColor(0xFFFFFFFF);
        EditConfig config = new EditConfig.Builder(Uri.fromFile(source), 4000).sourceSize(320, 240)
                .trim(1000, 2000).volume(0).introTemplate(template, "OPEN STORY\nSECOND LINE").build();
        File evidence = new File(getContext().getFilesDir(), "editor-title-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        File output = new File(evidence, style + "-" + duration + ".mp4");
        if (output.exists()) assertTrue(output.delete());
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            engine.set(new Media3ExportEngine(getContext()));
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                @Override public void onProgress(int progress) {}
                @Override public void onCompleted(File file) { done.countDown(); }
                @Override public void onError(Exception error) { failure.set(error); done.countDown(); }
            });
        });
        try {
            assertTrue("Native title export deadline", done.await(200, TimeUnit.SECONDS));
            if (failure.get() != null) throw failure.get();
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(output.getAbsolutePath());
                assertEquals("320", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                assertEquals("240", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                long actualDuration = Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                assertTrue("title then main duration", Math.abs(actualDuration - duration - 1000) < 120);
                JSONArray samples = new JSONArray();
                Bitmap early = null, held = null;
                for (long time : new long[]{0, 200000, 1200000, (duration - 200) * 1000L}) {
                    Bitmap actual = retriever.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST);
                    Bitmap expected = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
                    TitleRenderer.draw(new Canvas(expected), 320, 240, config.introTitle, time);
                    double error = mae(actual, expected);
                    assertTrue(style + " preview/export time=" + time + " MAE=" + error, error < 6);
                    samples.put(new JSONObject().put("timeUs", time).put("previewExportMae", error));
                    if (time == 200000) early = actual;
                    else if (time == 1200000) held = actual;
                    else {
                        if (time > 1200000) assertTrue("hold must be stable", mae(actual, held) < 2);
                        actual.recycle();
                    }
                    expected.recycle();
                }
                assertNotNull(early); assertNotNull(held);
                int fullInk = ink(held), earlyInk = ink(early);
                assertTrue("omitted-title negative must fail independent ink content", fullInk > 300);
                assertTrue("static-title negative must fail timed content", changed(early, held) > 200);
                if (style.equals("fade")) assertTrue("real opacity ramp", energy(early) < energy(held) * .45);
                if (style.equals("typewriter")) assertTrue("partial glyph reveal", earlyInk > 0 && earlyInk < fullInk * .8);
                if (style.equals("scale")) assertTrue("scale grows glyph area", earlyInk < fullInk * .85);
                if (style.equals("slide")) assertTrue("slide shifts text from left", centerX(early) < centerX(held) - 20);
                if (style.equals("lower-third")) {
                    assertTrue("lower-third stays in lower safe area", minY(held) >= 140);
                    assertTrue("lower-third animates horizontally", centerX(early) < centerX(held) - 20);
                }
                assertTrue("safe vertical margins", minY(held) >= 12);
                Bitmap omitted = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888);
                omitted.eraseColor(android.graphics.Color.BLACK);
                assertFalse("same timed checker rejects omitted frames", acceptsTimed(omitted, omitted));
                assertFalse("same timed checker rejects static held title", acceptsTimed(held, held));
                assertTrue("same timed checker accepts actual animation", acceptsTimed(early, held));
                omitted.recycle();
                JSONObject result = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                        .put("style", style).put("durationMs", duration).put("font", family).put("weight", weight)
                        .put("samples", samples).put("heldInk", fullInk).put("earlyInk", earlyInk)
                        .put("omittedRejected", true).put("staticRejected", true).put("status", "PASS");
                try (FileOutputStream stream = new FileOutputStream(new File(evidence, style + "-" + duration + ".json"))) {
                    stream.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                early.recycle(); held.recycle();
            } finally { retriever.release(); }
        } finally {
            CountDownLatch stopped = new CountDownLatch(1);
            main.post(() -> { if (engine.get() != null) engine.get().cancel(); stopped.countDown(); });
            assertTrue(stopped.await(10, TimeUnit.SECONDS));
        }
    }

    private static int ink(Bitmap b) {
        int n = 0;
        for (int y = 0; y < b.getHeight(); y++) for (int x = 0; x < b.getWidth(); x++) if ((b.getPixel(x, y) & 255) > 25) n++;
        return n;
    }
    private static boolean acceptsTimed(Bitmap early, Bitmap held) {
        return ink(held) > 300 && changed(early, held) > 200;
    }
    private static long energy(Bitmap b) {
        long n = 0;
        for (int y = 0; y < b.getHeight(); y++) for (int x = 0; x < b.getWidth(); x++) n += b.getPixel(x, y) & 255;
        return n;
    }
    private static double centerX(Bitmap b) {
        long sum = 0, n = 0;
        for (int y = 0; y < b.getHeight(); y++) for (int x = 0; x < b.getWidth(); x++) if ((b.getPixel(x, y) & 255) > 25) { sum += x; n++; }
        return sum / (double) Math.max(1, n);
    }
    private static int minY(Bitmap b) {
        for (int y = 0; y < b.getHeight(); y++) for (int x = 0; x < b.getWidth(); x++) if ((b.getPixel(x, y) & 255) > 25) return y;
        return b.getHeight();
    }
    private static int changed(Bitmap a, Bitmap b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
            if (Math.abs((a.getPixel(x, y) & 255) - (b.getPixel(x, y) & 255)) > 25) n++;
        return n;
    }
    private static double mae(Bitmap a, Bitmap b) {
        assertNotNull(a); assertEquals(b.getWidth(), a.getWidth()); assertEquals(b.getHeight(), a.getHeight());
        long n = 0;
        for (int y = 0; y < a.getHeight(); y++) for (int x = 0; x < a.getWidth(); x++)
            for (int shift : new int[]{0, 8, 16}) n += Math.abs((a.getPixel(x, y) >> shift & 255) - (b.getPixel(x, y) >> shift & 255));
        return n / (double) (a.getWidth() * a.getHeight() * 3);
    }
}
