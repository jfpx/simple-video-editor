package com.simple.videoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import com.simple.videoeditor.oracle.OracleGeneratedContract;
import java.io.File;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

@androidx.media3.common.util.UnstableApi
public final class BorderExportTest extends InstrumentationTestCase {
    private File evidence, source;
    private final List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());

    @Override protected void setUp() throws Exception {
        super.setUp();
        evidence = new File(context().getFilesDir(), "border-export-evidence");
        assertTrue(evidence.isDirectory() || evidence.mkdirs());
        source = new OracleVerifier(context(), OracleGeneratedContract.create()).prepareFixture();
    }

    public void testTitleOnly3SecondsStopsAtBoundary() throws Exception { title(3000); }
    public void testTitleOnly5SecondsStopsAtBoundary() throws Exception { title(5000); }

    private void title(int duration) throws Exception {
        EditConfig.Builder builder = base().introTemplate(template(duration), "OPEN STORY");
        compare("title-" + duration, builder, new VideoBorder(true, 0, 4, VideoBorder.Scope.TITLE),
                new long[]{0, 200000, (duration - 100) * 1000L, duration * 1000L,
                        (duration + 100) * 1000L, (duration + 700) * 1000L}, duration, false);
    }

    public void testWholeTimelineIncludesGeneratedImportedMainAndEveryMerge() throws Exception {
        timeline(false);
    }

    public void testWholeTimelineSpeedMusicTransmuxDoesNotDrawTwice() throws Exception {
        timeline(true);
    }

    private void timeline(boolean music) throws Exception {
        File fixtureDir = new File(evidence, "fixtures-" + UUID.randomUUID());
        MergeSupplementalFixtureFactory.FixtureSet fixtures = MergeSupplementalFixtureFactory.prepare(
                getInstrumentation().getContext(), fixtureDir, () -> {});
        MergeSupplementalFixtureFactory.GeneratedClip main = fixtures.get("main_audio_rot90");
        EditConfig.Builder builder = new EditConfig.Builder(Uri.fromFile(main.file), main.spec.durationMs)
                .sourceSize(main.spec.displayWidth(), main.spec.displayHeight())
                .mainSourceMetadata(true, main.file.length()).mergeMode(true)
                .introTemplate(template(3000), "TITLE").introVideo(fixtures.get("intro_audio").imported())
                .appendVideos(Arrays.asList(fixtures.get("extra_silent_square").imported(),
                        fixtures.get("extra_audio_rot270").imported(), fixtures.get("extra_silent_wide").imported()))
                .trim(600, 1800).crop(.25f, 0, 1, 1).outputHeight(120).speed(2f);
        if (music) builder.replacementMusic(Uri.fromFile(fixtures.get("intro_audio").file));
        try {
            compare(music ? "whole-music" : "whole-merge", builder,
                    new VideoBorder(true, 0, 5, VideoBorder.Scope.WHOLE),
                    new long[]{0, 2900000, 3100000, 4000000, 4400000, 5100000, 6100000, 6900000},
                    3000, music);
            if (music) {
                String text = String.join("\n", diagnostics);
                assertTrue("real second pass", text.contains("start pass=music-transmux"));
                assertTrue("second pass keeps video bytes", text.contains("transmuxVideo=true"));
                assertEquals("exactly one music pass", 1, occurrences(text, "completed pass=music-transmux"));
                assertEquals("exactly one edit pass", 1, occurrences(text, "completed pass=edit"));
            }
        } finally { delete(fixtureDir); }
    }

    public void testCropRotateResizeFinalPixelWidthAndStaticPreview() throws Exception {
        for (int rotation : new int[]{90, 37}) {
            EditConfig.Builder builder = base().crop(.125f, .125f, .875f, .875f)
                    .rotation(rotation).outputHeight(160);
            VideoBorder border = new VideoBorder(true, 2, 8, VideoBorder.Scope.WHOLE);
            compare("geometry-" + rotation, builder, border, new long[]{0, 300000, 800000}, 0, false);
            Bitmap original = SelectedFramePreview.render(builder.border(VideoBorder.OFF).build());
            Bitmap actual = SelectedFramePreview.render(builder.border(border).build());
            try { BorderPixelChecks.check(actual, original, 8, 0xFF0066FF, true, 0, 0); }
            finally { original.recycle(); actual.recycle(); }
        }
    }

    private void compare(String name, EditConfig.Builder builder, VideoBorder border, long[] times,
                         int titleMs, boolean music) throws Exception {
        EditConfig off = builder.border(VideoBorder.OFF).build();
        EditConfig on = builder.border(border).build();
        String policy = VideoEncodingSettings.compatibilityEnabled(context()) ? "software" : "default";
        File reference = export(off, name + "-" + policy + "-off");
        File output = export(on, name + "-" + policy + "-on");
        MediaMetadataRetriever original = new MediaMetadataRetriever(), actual = new MediaMetadataRetriever();
        JSONArray samples = new JSONArray();
        try {
            original.setDataSource(reference.getAbsolutePath()); actual.setDataSource(output.getAbsolutePath());
            for (int key : new int[]{MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH,
                    MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT, MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO}) {
                assertEquals("border cannot change geometry/audio", original.extractMetadata(key), actual.extractMetadata(key));
            }
            long duration = Long.parseLong(actual.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            assertEquals("duration unaffected", Long.parseLong(original.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION)), duration, 40);
            assertEquals("full edited timeline", Media3ExportEngine.editedAudioDurationUs(on) / 1000d, duration, 120d);
            if (music) assertEquals("yes", actual.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
            for (long time : times) {
                assertTrue("sample inside actual timeline " + time + "/" + duration, time < duration * 1000);
                Bitmap expected = original.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST);
                Bitmap frame = actual.getFrameAtTime(time, MediaMetadataRetriever.OPTION_CLOSEST);
                assertNotNull(expected); assertNotNull(frame);
                boolean applies = border.scope == VideoBorder.Scope.WHOLE || time < titleMs * 1000L;
                try {
                    BorderPixelChecks.check(frame, expected, border.percent, border.color(), applies, 32, 6);
                    if (time == times[0]) {
                        try (FileOutputStream stream = new FileOutputStream(new File(evidence, name + "-" + policy + ".png"))) {
                            assertTrue(frame.compress(Bitmap.CompressFormat.PNG, 100, stream));
                        }
                    }
                    samples.put(new JSONObject().put("timeUs", time).put("borderExpected", applies));
                } finally { expected.recycle(); frame.recycle(); }
            }
            JSONObject result = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION).put("policy", policy)
                    .put("name", name).put("status", "PASS").put("samples", samples).put("durationMs", duration)
                    .put("output", output.getName()).put("reference", reference.getName())
                    .put("diagnostics", new JSONArray(diagnostics));
            try (FileOutputStream stream = new FileOutputStream(new File(evidence, name + "-" + policy + ".json"))) {
                stream.write(result.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
        } finally { original.release(); actual.release(); }
    }

    private File export(EditConfig config, String name) throws Exception {
        File output = new File(evidence, name + "-" + UUID.randomUUID() + ".mp4");
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<String> encoder = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        diagnostics.clear();
        main.post(() -> {
            engine.set(new Media3ExportEngine(context()));
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                @Override public void onProgress(int progress) {}
                @Override public void onDiagnostic(String message) { diagnostics.add(message); }
                @Override public void onCodecs(String video, String audio) { if (video != null) encoder.set(video); }
                @Override public void onCompleted(File file) { done.countDown(); }
                @Override public void onError(Exception failure) { error.set(failure); done.countDown(); }
            });
        });
        try {
            assertTrue("finite native border export", done.await(200, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            assertTrue(output.isFile() && output.length() > 0);
            if (VideoEncodingSettings.compatibilityEnabled(context()))
                SoftwareAvcExportTest.assertSoftwareDiagnostics(diagnostics, encoder.get());
            return output;
        } finally {
            CountDownLatch stopped = new CountDownLatch(1);
            main.post(() -> { if (engine.get() != null) engine.get().cancel(); stopped.countDown(); });
            assertTrue(stopped.await(10, TimeUnit.SECONDS));
        }
    }

    private EditConfig.Builder base() {
        return new EditConfig.Builder(Uri.fromFile(source), 4000).sourceSize(320, 240).trim(1000, 2000).volume(0);
    }

    private static IntroTemplate template(int duration) {
        IntroTemplate title = new IntroTemplate();
        title.setAnimation("fade"); title.setDurationMs(duration); title.setTextSize(48);
        title.setBackgroundColor(0xFF203040); title.setGradientColor(0xFF203040);
        return title;
    }

    private Context context() { return getInstrumentation().getTargetContext(); }
    private static int occurrences(String text, String value) {
        int count = 0;
        for (String line : text.split("\n")) if (line.startsWith(value)) count++;
        return count;
    }
    private static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }
}
