package com.simple.videoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

public final class TitleBackgroundTest extends InstrumentationTestCase {
    private Context context;
    private File source, evidence;

    protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        source = new OracleVerifier(context, com.simple.videoeditor.oracle.OracleGeneratedContract.create()).prepareFixture();
        evidence = new File(context.getFilesDir(), "title-background-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
    }

    public void testStrictAbsoluteMillisecondsAndOldTemplateDefaultOff() throws Exception {
        IntroTemplate template = new IntroTemplate();
        assertFalse(template.hasSourceFrameBackground());
        JSONObject old = new JSONObject(template.toJson());
        old.remove("schemaVersion"); old.remove("layout");
        old.remove("sourceFrameBackground"); old.remove("sourceFrameSeconds");
        assertFalse(IntroTemplate.fromJsonStrict(old.toString()).hasSourceFrameBackground());
        assertEquals(1234L, TitleBackground.parseTime("1.234", 4000));
        for (String invalid : new String[]{"", ".", "-", "-0.001", "4", "4.001", "NaN", "1.0001"}) {
            try { TitleBackground.parseTime(invalid, 4000); fail(invalid); }
            catch (IllegalArgumentException expected) {}
        }
        template.setSourceFrameBackground(true); template.setSourceFrameSeconds("1.234");
        IntroTemplate restored = IntroTemplate.fromJsonStrict(template.toJson());
        assertTrue(restored.hasSourceFrameBackground());
        assertEquals("1.234", restored.getSourceFrameSeconds());
        assertFalse("Recipes must not contain another input path", restored.toJson().contains(source.getAbsolutePath()));
    }

    public void testFourTenCropAbsoluteTimeAndPublicPngIdentity() throws Exception {
        EditConfig config = base(0).trim(2000, 4000).build();
        TitleBackground snapshot = TitleBackground.capture(context, config, 1000);
        Bitmap original = frame(source, 1000000);
        Bitmap expected = Bitmap.createBitmap(original, 32, 24, 256, 192);
        Bitmap actual = snapshot.decode(4096);
        Uri uri = null;
        try {
            assertEquals(256, actual.getWidth()); assertEquals(192, actual.getHeight());
            assertTrue("independent center-80% mapping", mae(actual, expected) < 2);
            assertEquals(snapshot.key, TitleBackground.key(base(0).trim(3000, 4000).build(), 1000));
            uri = PublishedImage.publish(context, snapshot, "title-background-test-" + UUID.randomUUID() + ".png",
                    null, new java.util.concurrent.atomic.AtomicBoolean());
            assertEquals("image/png", context.getContentResolver().getType(uri));
            try (android.database.Cursor c = context.getContentResolver().query(uri,
                    new String[]{"relative_path", "is_pending"}, null, null, null)) {
                assertTrue(c.moveToFirst()); assertEquals("Pictures/", c.getString(0)); assertEquals(0, c.getInt(1));
            }
            try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
                Bitmap saved = BitmapFactory.decodeStream(stream);
                assertTrue(saved.sameAs(actual)); saved.recycle();
            }
            context.getContentResolver().delete(uri, null, null); uri = null;
            assertTrue("Public deletion cannot affect export snapshot", snapshot.file.isFile());
        } finally {
            if (uri != null) context.getContentResolver().delete(uri, null, null);
            original.recycle(); expected.recycle(); actual.recycle(); snapshot.file.delete();
        }
    }

    public void testRealSarCropRotationProjection() throws Exception {
        File sar = new File(evidence, "sar2.mp4");
        try (InputStream in = getInstrumentation().getContext().getAssets().open("editor-workflow/sar2.mp4");
             FileOutputStream out = new FileOutputStream(sar)) { MediaCopy.copy(in, out, 0, bytes -> {}); }
        EditConfig config = new EditConfig.Builder(Uri.fromFile(sar), 4000).sourceSize(640, 240)
                .crop(.1f, .1f, .9f, .9f).rotation(90).trim(2000, 4000).build();
        TitleBackground snapshot = TitleBackground.capture(context, config, 1000);
        Bitmap sourceFrame = frame(sar, 1000000);
        Bitmap display = Bitmap.createScaledBitmap(sourceFrame, 640, 240, true);
        Bitmap expected = Bitmap.createBitmap(192, 512, Bitmap.Config.ARGB_8888);
        for (int y = 0; y < 512; y++) for (int x = 0; x < 192; x++)
            expected.setPixel(x, y, display.getPixel(64 + y, 24 + 191 - x));
        Bitmap actual = snapshot.decode(4096);
        try { assertTrue("SAR then four edges then clockwise: " + mae(actual, expected), mae(actual, expected) < 2); }
        finally { actual.recycle(); expected.recycle(); display.recycle(); sourceFrame.recycle(); snapshot.file.delete(); sar.delete(); }
    }

    public void testNativeTitle3SecondsBeforeTrimWithAudio() throws Exception { exportTitle(3000); }
    public void testNativeTitle5SecondsBeforeTrimWithAudio() throws Exception { exportTitle(5000); }

    private void exportTitle(int duration) throws Exception {
        EditConfig geometry = base(90).trim(2000, 4000).build();
        TitleBackground snapshot = TitleBackground.capture(context, geometry, 1000);
        IntroTemplate title = new IntroTemplate();
        title.setAnimation("scale"); title.setDurationMs(duration);
        title.setSourceFrameBackground(true); title.setSourceFrameSeconds("1.000");
        String policy = VideoEncodingSettings.compatibilityEnabled(context) ? "software" : "default";
        File output = new File(evidence, duration + "-" + policy + "-" + UUID.randomUUID() + ".mp4");
        EditConfig config = base(90).trim(2000, 4000).introTemplate(title, "")
                .titleBackground(snapshot).build();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        StringBuilder diagnostics = new StringBuilder();
        getInstrumentation().runOnMainSync(() -> {
            engine.set(new Media3ExportEngine(context));
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                public void onProgress(int p) {}
                public void onDiagnostic(String line) {
                    synchronized (diagnostics) { if (diagnostics.length() < 16000) diagnostics.append(line).append('\n'); }
                }
                public void onCompleted(File file) { done.countDown(); }
                public void onError(Exception e) { error.set(e); done.countDown(); }
            });
        });
        try {
            assertTrue("self-test-only wait bound", done.await(180, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            Bitmap expected = snapshot.decode(4096);
            for (long us : new long[]{0, 200000, 1200000, duration * 1000L - 100000}) {
                Bitmap actual = frame(output, us);
                try { assertTrue("full background must stay static, not scale with animated text; MAE=" + mae(actual, expected),
                        mae(actual, expected) < 12); }
                finally { actual.recycle(); }
            }
            Bitmap after = frame(output, duration * 1000L + 500000);
            assertTrue("main must resume at trim, not still show title frame", mae(after, expected) > 1);
            after.recycle();
            save(expected, new File(evidence, duration + "-" + policy + "-snapshot.png"));
            expected.recycle();
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(output.getAbsolutePath());
                assertEquals("yes", retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                assertEquals(duration + 2000d, Double.parseDouble(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION)), 100);
            } finally { retriever.release(); }
            JSONObject record = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION).put("policy", policy)
                    .put("status", "PASS").put("titleMs", duration).put("requestedOriginalMs", 1000)
                    .put("trimStartMs", 2000).put("output", output.getName()).put("diagnostics", diagnostics.toString());
            try (FileOutputStream out = new FileOutputStream(new File(evidence, duration + "-" + policy + ".json"))) {
                out.write(record.toString(2).getBytes("UTF-8"));
            }
        } finally {
            getInstrumentation().runOnMainSync(() -> engine.get().cancel());
            snapshot.file.delete();
        }
    }

    private EditConfig.Builder base(int rotation) {
        return new EditConfig.Builder(Uri.fromFile(source), 4000).sourceSize(320, 240)
                .crop(.1f, .1f, .9f, .9f).rotation(rotation);
    }

    static Bitmap frame(File source, long timeUs) throws Exception {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try { retriever.setDataSource(source.getAbsolutePath()); return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST); }
        finally { retriever.release(); }
    }

    static double mae(Bitmap actual, Bitmap expected) {
        assertEquals(expected.getWidth(), actual.getWidth()); assertEquals(expected.getHeight(), actual.getHeight());
        long error = 0;
        for (int y = 0; y < actual.getHeight(); y++) for (int x = 0; x < actual.getWidth(); x++) {
            int a = actual.getPixel(x, y), b = expected.getPixel(x, y);
            for (int shift : new int[]{0, 8, 16}) error += Math.abs(((a >> shift) & 255) - ((b >> shift) & 255));
        }
        return error / (3d * actual.getWidth() * actual.getHeight());
    }

    private static void save(Bitmap image, File file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out)); }
    }
}
