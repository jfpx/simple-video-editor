package com.simple.videoeditor;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.test.InstrumentationTestCase;
import androidx.media3.transformer.Composition;
import com.simple.videoeditor.oracle.OracleAndroidDecoder;
import com.simple.videoeditor.oracle.OracleCoreVerifier;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** All tones are independently synthesized by this native test, never packaged as library music. */
@androidx.media3.common.util.UnstableApi
public final class OfflineMusicTest extends InstrumentationTestCase {
    private File directory;
    private final List<String> diagnostics = Collections.synchronizedList(new ArrayList<>());
    private Context context() { return getInstrumentation().getTargetContext(); }

    @Override protected void setUp() throws Exception {
        super.setUp();
        directory = new File(context().getFilesDir(), "offline-music-evidence/" + getName() + "-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
    }

    public void testCatalogBoundsPathsHashesAndMissingAssets() throws Exception {
        File tone = tone("tone.wav", 48000, 2, 24000, 1320);
        JSONObject row = row(tone, 24000);
        OfflineMusicCatalog catalog = catalog(row, tone);
        File copy = new File(directory, "verified.wav");
        catalog.copyVerified("own-test-tone", copy);
        assertEquals(hash(tone), hash(copy));
        assertEquals("OWN TEST TONE - NOT MUSIC", catalog.require("own-test-tone").title);
        expectFailure(() -> catalog.require("missing"));
        for (String bad : new String[]{"../tone.wav", "music/calm/../tone.wav", "music//tone.wav",
                "music/calm\\tone.wav", "music/calm/%2e%2e.wav", "/music/calm/tone.wav"}) {
            JSONObject broken = new JSONObject(row.toString()).put("path", bad);
            expectFailure(() -> catalog(broken, tone));
        }
        for (String bad : new String[]{"CC-BY-4.0", "YouTube-free-use", "royalty-free"}) {
            expectFailure(() -> catalog(new JSONObject(row.toString()).put("license", bad), tone));
        }
        expectFailure(() -> catalog(new JSONObject(row.toString()).put("bytes", 13 * 1024 * 1024), tone));
        expectFailure(() -> catalog(new JSONObject(row.toString()).put("frames", 1), tone));
        expectFailure(() -> catalog(new JSONObject(row.toString()).put("source", "file:///private"), tone));
        expectFailure(() -> catalog(new JSONObject(row.toString()).put("sha256", "0"), tone));
        JSONObject changedHash = new JSONObject(row.toString()).put("sha256", String.join("", Collections.nCopies(64, "0")));
        File rejected = new File(directory, "reject.wav");
        expectFailure(() -> catalog(changedHash, tone).copyVerified("own-test-tone", rejected));
        assertFalse(rejected.exists());
        File missing = new File(directory, "missing.wav");
        expectFailure(() -> catalog(row, missing).copyVerified("own-test-tone", rejected));
        assertFalse(rejected.exists());
        File wrongRate = tone("wrong-rate.wav", 24000, 2, 24000, 1320);
        expectFailure(() -> catalog(row(wrongRate, 24000), wrongRate).copyVerified("own-test-tone", rejected));
        assertFalse(rejected.exists());
        byte[] oversized = new byte[OfflineMusicCatalog.MAX_CATALOG_BYTES + 1];
        expectFailure(() -> OfflineMusicCatalog.load(path -> new ByteArrayInputStream(oversized)));
        JSONObject duplicate = new JSONObject().put("version", 1)
                .put("tracks", new JSONArray().put(row).put(row));
        expectFailure(() -> OfflineMusicCatalog.load(path -> new ByteArrayInputStream(
                duplicate.toString().getBytes(StandardCharsets.UTF_8))));
    }

    public void testIndependentConfigAndLegacyPresetSemantics() throws Exception {
        JSONObject old = new JSONObject().put("version", 3);
        assertFalse(BackgroundMusic.fromRecipe(old).enabled);
        BackgroundMusic selection = new BackgroundMusic(true, "stable-id", .5f, true);
        selection.putRecipe(old);
        BackgroundMusic restored = BackgroundMusic.fromRecipe(new JSONObject(old.toString()));
        assertEquals(selection.trackId, restored.trackId);
        assertEquals(.5f, restored.gain); assertTrue(restored.muteOriginal);
        for (float gain : new float[]{0, -.5f, .49f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY}) {
            expectFailure(() -> new BackgroundMusic(true, "stable-id", gain, false));
        }
        for (float gain : new float[]{-1, Float.NaN, Float.NEGATIVE_INFINITY}) {
            expectFailure(() -> new EditConfig.Builder(Uri.EMPTY, 1000).volume(gain)
                    .backgroundMusic(selection).build());
        }
        expectFailure(() -> new BackgroundMusic(true, "", 1f, false));
        JSONObject missing = new JSONObject().put("version", 4);
        expectFailure(() -> BackgroundMusic.fromRecipe(missing));
        EditConfig.Builder builder = new EditConfig.Builder(Uri.EMPTY, 1000).volume(.75f);
        EditConfig muted = builder.backgroundMusic(selection).build();
        assertEquals(.75f, muted.volume); assertEquals(0f, muted.sourceGain());
        assertTrue(Media3ExportEngine.needsMusicPass(muted));
        assertTrue(Media3ExportEngine.createComposition(muted).sequences.get(0).editedMediaItems.get(0).removeAudio);
        EditConfig unmuted = builder.backgroundMusic(new BackgroundMusic(true, "stable-id", 1, false)).build();
        assertEquals(.75f, unmuted.sourceGain());
        assertFalse(Media3ExportEngine.createComposition(unmuted).sequences.get(0).editedMediaItems.get(0).removeAudio);
        expectFailure(() -> builder.replacementMusic(Uri.EMPTY).build());
        EditConfig legacy = builder.backgroundMusic(BackgroundMusic.OFF).build();
        assertFalse(Media3ExportEngine.needsMusicPass(legacy));
        assertEquals(2, Media3ExportEngine.createComposition(legacy).sequences.size());
        Composition mixed = Media3ExportEngine.createLibraryMixComposition(Uri.EMPTY, Uri.EMPTY, 1000000, .5f);
        assertTrue(mixed.transmuxVideo); assertTrue(mixed.sequences.get(1).isLooping);
        assertFalse(mixed.sequences.get(0).editedMediaItems.get(0).removeAudio);
        assertEquals(1, mixed.effects.audioProcessors.size());
        WholeEditPresets presets = new WholeEditPresets(context());
        String name = "offline-music-test-" + UUID.randomUUID();
        try {
            JSONObject recipe = new JSONObject().put("headMs", 0).put("tailMs", 0)
                    .put("crop", new JSONArray(new int[]{0, 0, 0, 0}));
            new BackgroundMusic(false, "", .5f, true).putRecipe(recipe);
            presets.save(name, recipe, null, null, null);
            JSONObject loaded = presets.load(name);
            assertEquals(6, loaded.getInt("version"));
            assertEquals(.5f, BackgroundMusic.fromRecipe(loaded).gain);
            assertTrue(BackgroundMusic.fromRecipe(loaded).muteOriginal);
            selection.putRecipe(recipe);
            expectFailure(() -> presets.save(name, recipe, null, null, null));
            assertFalse(BackgroundMusic.fromRecipe(presets.load(name)).enabled);
        } finally { presets.delete(name); }
    }

    public void testNativeIndependentGainsLoopAndTimeline() throws Exception {
        File fixture = SelfTestFixture.create(new File(directory, "fixture"), () -> {});
        File originalTone = tone("original440.wav", 44100, 1, 44100, 440);
        File source = export(new EditConfig.Builder(Uri.fromFile(fixture), 3000)
                .replacementMusic(Uri.fromFile(originalTone)).build(), null, "source440");
        File background = tone("bgm1320.wav", 48000, 2, 24000, 1320);
        OfflineMusicCatalog catalog = catalog(row(background, 24000), background);
        EditConfig.Builder base = new EditConfig.Builder(Uri.fromFile(source), 3000)
                .sourceSize(320, 240).mainSourceMetadata(true, source.length()).trim(0, 2000);
        File full = export(base.backgroundMusic(new BackgroundMusic(true, "own-test-tone", 1, true))
                .volume(.75f).build(), catalog, "mute100");
        File half = export(base.backgroundMusic(new BackgroundMusic(true, "own-test-tone", .5f, true))
                .build(), catalog, "mute50");
        File zero = export(base.backgroundMusic(new BackgroundMusic(true, "own-test-tone", 1, false))
                .volume(0).build(), catalog, "originalZero");
        File mix = export(base.volume(.5f).build(), catalog, "mixOriginal50Bgm100");
        File mixHalf = export(base.backgroundMusic(new BackgroundMusic(true, "own-test-tone", .5f, false))
                .build(), catalog, "mixOriginal50Bgm50");
        File disabled = export(base.backgroundMusic(new BackgroundMusic(false, "missing-disabled-id", .5f, true))
                .volume(1).build(), null, "disabled");
        OracleCoreVerifier.AudioTrack a = audio(full, 2), b = audio(half, 2), c = audio(zero, 2);
        OracleCoreVerifier.AudioTrack d = audio(mix, 2), e = audio(mixHalf, 2), f = audio(disabled, 2);
        double fullBgm = amplitude(a, 1320, .25, 1.5);
        assertEquals(.12, fullBgm, .012);
        assertEquals("BGM 50/100 AAC ratio", .5, amplitude(b, 1320, .25, 1.5) / fullBgm, .04);
        assertEquals("original zero cannot mute BGM", fullBgm, amplitude(c, 1320, .25, 1.5), .008);
        for (OracleCoreVerifier.AudioTrack track : new OracleCoreVerifier.AudioTrack[]{a, b, c}) {
            assertTrue("muted original 440 Hz absent", amplitude(track, 440, .25, 1.5) < .003);
        }
        assertEquals("independent original gain", .5,
                amplitude(d, 440, .25, 1.5) / amplitude(f, 440, .25, 1.5), .05);
        assertEquals("BGM does not inherit original gain", fullBgm, amplitude(d, 1320, .25, 1.5), .012);
        assertEquals("changing BGM preserves original", amplitude(d, 440, .25, 1.5),
                amplitude(e, 440, .25, 1.5), .006);
        assertEquals(.5, amplitude(e, 1320, .25, 1.5) / fullBgm, .04);
        assertTrue("disabled has no BGM", amplitude(f, 1320, .25, 1.5) < .003);
        // Check every native WAV loop seam, not merely average energy across the full clip.
        for (double seam : new double[]{.5, 1, 1.5}) {
            assertEquals(.12, amplitude(a, 1320, seam - .04, .08), .016);
        }
        IntroTemplate title = new IntroTemplate();
        title.setDurationMs(3000);
        EditConfig.ImportedVideo clip = new EditConfig.ImportedVideo(Uri.fromFile(source), 3000,
                320, 240, true, source.length());
        EditConfig longConfig = base.backgroundMusic(new BackgroundMusic(true, "own-test-tone", .5f, false))
                .volume(.5f).speed(2).introTemplate(title, "OWN TONE TEST")
                .introVideo(clip).appendVideos(Collections.singletonList(clip)).build();
        assertEquals(10000000L, Media3ExportEngine.editedAudioDurationUs(longConfig));
        File timeline = export(longConfig, catalog, "titleIntroMainSpeedMerge");
        OracleCoreVerifier.AudioTrack longAudio = audio(timeline, 10);
        for (double start : new double[]{.3, 3.3, 6.2, 7.3, 9.4}) {
            assertEquals("native-pitch continuous BGM " + start, .06,
                    amplitude(longAudio, 1320, start, .25), .008);
        }
        assertTrue(amplitude(longAudio, 440, .3, .25) < .003);
        for (double start : new double[]{3.3, 6.2, 7.3, 9.4}) {
            assertEquals("original retained at own gain/pitch " + start, .06,
                    amplitude(longAudio, 440, start, .25), .012);
            assertTrue("no doubled BGM pitch", amplitude(longAudio, 2640, start, .25) < .003);
        }
        JSONObject evidence = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                .put("policy", VideoEncodingSettings.modeName(VideoEncodingSettings.compatibilityEnabled(context())))
                .put("status", "PASS").put("syntheticOnly", true)
                .put("bgm100Amplitude", fullBgm).put("bgm50Amplitude", amplitude(b, 1320, .25, 1.5))
                .put("mixedOriginalAmplitude", amplitude(d, 440, .25, 1.5))
                .put("sourceSha256", hash(source)).put("testToneSha256", hash(background))
                .put("diagnostics", new JSONArray(diagnostics));
        write(new File(directory, "result.json"), evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        File[] leftovers = directory.listFiles((dir, name) -> name.startsWith("."));
        assertEquals("export work files cleaned", 0, leftovers.length);
    }

    private OracleCoreVerifier.AudioTrack audio(File file, double seconds) throws Exception {
        // Audio-only probe keeps the unchanged oracle RGB frame/memory budget intact on long edits.
        File audioFile = new File(directory, file.getName() + ".audio.m4a");
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaMuxer muxer = new android.media.MediaMuxer(audioFile.getAbsolutePath(),
                android.media.MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int index = -1;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                if (extractor.getTrackFormat(i).getString(android.media.MediaFormat.KEY_MIME).startsWith("audio/")) {
                    index = i; break;
                }
            }
            assertTrue(index >= 0);
            extractor.selectTrack(index);
            int track = muxer.addTrack(extractor.getTrackFormat(index));
            muxer.start();
            ByteBuffer buffer = ByteBuffer.allocateDirect(65536);
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            int packets = 0, size;
            while ((size = extractor.readSampleData(buffer, 0)) >= 0) {
                assertTrue(++packets < 2048);
                info.set(0, size, extractor.getSampleTime(), 0);
                muxer.writeSampleData(track, buffer, info);
                extractor.advance(); buffer.clear();
            }
            muxer.stop();
        } finally { extractor.release(); muxer.release(); }
        OracleCoreVerifier.Candidate candidate = new OracleAndroidDecoder().decode(audioFile);
        assertEquals(1, candidate.audioTrackCount); assertNotNull(candidate.audio.samples);
        OracleCoreVerifier.AudioTrack audio = candidate.audio;
        assertTrue(audio.samples.length >= (seconds - .04) * audio.sampleRate);
        assertTrue("no extra tail beyond bounded native AAC packets",
                audio.samples.length <= Math.ceil(seconds * audio.sampleRate) + 3 * 1024);
        return audio;
    }

    public void testSoundTabSyntheticSelectionGainClearAndMediaStoreExport() throws Exception {
        File fixture = SelfTestFixture.create(new File(directory, "ui-fixture"), () -> {});
        File tone = tone("ui1320.wav", 48000, 2, 24000, 1320);
        OfflineMusicCatalog testCatalog = catalog(row(tone, 24000), tone);
        android.content.SharedPreferences prefs = context().getSharedPreferences("offline_music", Context.MODE_PRIVATE);
        String previous = prefs.getString("selection", null);
        MainActivity activity = (MainActivity) getInstrumentation().startActivitySync(
                new android.content.Intent(context(), MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
        try {
            await(() -> !(Boolean) field(activity, "restoringUi"), 20);
            ((java.util.concurrent.ExecutorService) field(activity, "publicationWorker"))
                    .submit(() -> {}).get(15, TimeUnit.SECONDS);
            ui(() -> {
                OfflineMusicControls controls = (OfflineMusicControls) field(activity, "offlineMusic");
                controls.apply(BackgroundMusic.OFF);
                // Only the test's data source changes. All selection/export/publication code is production.
                setField(controls, "catalog", testCatalog);
                setField(activity, "exportEngine", new Media3ExportEngine(context(), testCatalog));
                ((com.google.android.material.tabs.TabLayout) activity.findViewById(R.id.editorTabs))
                        .getTabAt(1).select();
                activity.onActivityResult((Integer) field(activity, "VIDEO_PICK_CODE"),
                        android.app.Activity.RESULT_OK, new android.content.Intent().setData(Uri.fromFile(fixture)));
            });
            await(() -> !(Boolean) field(activity, "loading") && !(Boolean) field(activity, "restoringAssets"), 30);
            ui(() -> {
                assertNotNull(field(activity, "selectedMainVideo"));
                activity.findViewById(R.id.btnLibraryTrack).performClick();
            });
            clickText("test / test");
            clickText("OWN TEST TONE - NOT MUSIC");
            await(() -> "own-test-tone".equals(
                    ((OfflineMusicControls) field(activity, "offlineMusic")).save().getString("trackId")), 10);
            ui(() -> {
                ((android.widget.CheckBox) activity.findViewById(R.id.cbLibraryMusic)).setChecked(true);
                ((android.widget.CheckBox) activity.findViewById(R.id.cbMuteOriginal)).setChecked(true);
                ((android.widget.SeekBar) activity.findViewById(R.id.seekBgmGain)).setProgress(50);
                OfflineMusicControls controls = (OfflineMusicControls) field(activity, "offlineMusic");
                assertEquals("own-test-tone", controls.snapshot().trackId);
                for (String flag : new String[]{"loading", "exporting", "publishing"}) {
                    setField(activity, flag, true);
                    invoke(activity, "updateWorkControls");
                    for (int id : new int[]{R.id.cbLibraryMusic, R.id.cbMuteOriginal, R.id.seekBgmGain,
                            R.id.btnLibraryTrack, R.id.btnClearLibraryTrack, R.id.btnMusicLicenses}) {
                        assertFalse(activity.findViewById(id).isEnabled());
                    }
                    setField(activity, flag, false);
                }
                invoke(activity, "updateWorkControls");
            });
            File[] outputs = new File[2];
            JSONArray publicOutputs = new JSONArray();
            for (int i = 0; i < 2; i++) {
                final int progress = i == 0 ? 50 : 0;
                ui(() -> {
                    ((android.widget.SeekBar) activity.findViewById(R.id.seekBgmGain)).setProgress(progress);
                    assertTrue(activity.findViewById(R.id.btnProcess).isEnabled());
                    activity.findViewById(R.id.btnProcess).performClick();
                });
                await(() -> !(Boolean) field(activity, "exporting") && !(Boolean) field(activity, "publishing"), 210);
                PublishedVideo published = (PublishedVideo) field(activity, "lastVideo");
                assertNotNull(published); assertNotNull(published.uri);
                assertEquals("content", published.uri.getScheme());
                File output = new File(directory, "ui-public-" + progress + ".mp4");
                try (java.io.InputStream input = context().getContentResolver().openInputStream(published.uri);
                     FileOutputStream stream = new FileOutputStream(output)) {
                    byte[] buffer = new byte[8192]; int n, count = 0;
                    while ((n = input.read(buffer)) != -1) {
                        count += n; assertTrue(count < 16 * 1024 * 1024); stream.write(buffer, 0, n);
                    }
                }
                assertEquals(hash(published.privateFile), hash(output));
                outputs[i] = output;
                publicOutputs.put(new JSONObject().put("uri", published.uri.toString()).put("sha256", hash(output)));
            }
            OracleCoreVerifier.AudioTrack full = audio(outputs[0], 3), half = audio(outputs[1], 3);
            assertEquals(.12, amplitude(full, 1320, .3, 2), .012);
            assertEquals(.5, amplitude(half, 1320, .3, 2) / amplitude(full, 1320, .3, 2), .04);
            assertTrue("original self-test 1000 Hz muted in real UI export", amplitude(full, 1000, .3, 2) < .003);
            ui(() -> {
                OfflineMusicControls controls = (OfflineMusicControls) field(activity, "offlineMusic");
                android.os.Bundle state = controls.save();
                controls.useImportedReplacement();
                assertFalse(controls.isEnabled());
                controls.restore(state);
                assertTrue(controls.isEnabled());
                assertEquals(.5f, controls.snapshot().gain);
                activity.findViewById(R.id.btnClearLibraryTrack).performClick();
                assertFalse(controls.isEnabled()); assertEquals("", controls.snapshot().trackId);
                ((com.google.android.material.tabs.TabLayout) activity.findViewById(R.id.editorTabs)).getTabAt(4).select();
                activity.findViewById(R.id.btnMusicLicenses).performClick();
            });
            android.graphics.Bitmap screenshot = getInstrumentation().getUiAutomation().takeScreenshot();
            if (screenshot != null) {
                try (FileOutputStream out = new FileOutputStream(new File(directory, "ui-license-test-only.png"))) {
                    screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
                } finally { screenshot.recycle(); }
            }
            getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
            JSONObject result = new JSONObject().put("revision", BuildConfig.SOURCE_REVISION)
                    .put("policy", VideoEncodingSettings.modeName(VideoEncodingSettings.compatibilityEnabled(context())))
                    .put("syntheticOnly", true).put("status", "PASS").put("publicOutputs", publicOutputs);
            write(new File(directory, "ui-result.json"), result.toString(2).getBytes(StandardCharsets.UTF_8));
        } finally {
            ui(() -> activity.finish());
            prefs.edit().putString("selection", previous).commit();
        }
    }

    private void clickText(String text) throws Exception {
        await(() -> {
            android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            try {
                List<android.view.accessibility.AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
                for (android.view.accessibility.AccessibilityNodeInfo node : nodes) {
                    try {
                        if (!node.isVisibleToUser()) continue;
                        if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true;
                        }
                        android.graphics.Rect bounds = new android.graphics.Rect();
                        node.getBoundsInScreen(bounds);
                        if (bounds.isEmpty()) continue;
                        long now = android.os.SystemClock.uptimeMillis();
                        android.view.MotionEvent down = android.view.MotionEvent.obtain(
                                now, now, android.view.MotionEvent.ACTION_DOWN, bounds.centerX(), bounds.centerY(), 0);
                        android.view.MotionEvent up = android.view.MotionEvent.obtain(
                                now, now + 50, android.view.MotionEvent.ACTION_UP, bounds.centerX(), bounds.centerY(), 0);
                        try {
                            getInstrumentation().sendPointerSync(down);
                            getInstrumentation().sendPointerSync(up);
                            return true;
                        } finally { down.recycle(); up.recycle(); }
                    } finally { node.recycle(); }
                }
                StringBuilder tree = new StringBuilder();
                describe(root, tree, 0);
                write(new File(directory, "ui-last-tree.txt"), tree.toString().getBytes(StandardCharsets.UTF_8));
                return false;
            } finally { root.recycle(); }
        }, 10);
        getInstrumentation().waitForIdleSync();
        android.os.SystemClock.sleep(300);
        getInstrumentation().waitForIdleSync();
    }

    public void testLanguageRecreationRetainsSelectionAndReportsMissingTrack() throws Exception {
        android.content.SharedPreferences prefs = context().getSharedPreferences("offline_music", Context.MODE_PRIVATE);
        String previous = prefs.getString("selection", null);
        boolean originalEnglish = UiLocales.isEnglish(context());
        AtomicReference<MainActivity> current = new AtomicReference<>((MainActivity) getInstrumentation().startActivitySync(
                new android.content.Intent(context(), MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        try {
            await(() -> !(Boolean) field(current.get(), "restoringUi"), 20);
            ui(() -> ((OfflineMusicControls) field(current.get(), "offlineMusic"))
                    .apply(new BackgroundMusic(true, "deliberately-missing-id", .63f, true)));
            for (int pass = 0; pass < 2; pass++) {
                android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation()
                        .addMonitor(MainActivity.class.getName(), null, false);
                try {
                    ui(() -> {
                        ((com.google.android.material.tabs.TabLayout) current.get().findViewById(R.id.editorTabs))
                                .getTabAt(4).select();
                        current.get().findViewById(R.id.btnLanguage).performClick();
                    });
                    MainActivity recreated = (MainActivity) monitor.waitForActivityWithTimeout(15000);
                    assertNotNull(recreated); current.set(recreated);
                    await(() -> !(Boolean) field(current.get(), "restoringUi"), 20);
                    ui(() -> {
                        OfflineMusicControls controls = (OfflineMusicControls) field(current.get(), "offlineMusic");
                        android.os.Bundle state = controls.save();
                        assertTrue(state.getBoolean("enabled")); assertTrue(state.getBoolean("muteOriginal"));
                        assertEquals(13, state.getInt("gainProgress"));
                        assertEquals("deliberately-missing-id", state.getString("trackId"));
                        expectFailure(controls::snapshot);
                        assertTrue(((android.widget.TextView) current.get().findViewById(R.id.tvLibraryTrack))
                                .getText().toString().contains("deliberately-missing-id"));
                        assertEquals(UiLocales.isEnglish(context()) ? "en" : "zh",
                                current.get().getResources().getConfiguration().locale.getLanguage());
                    });
                } finally { getInstrumentation().removeMonitor(monitor); }
            }
        } finally {
            if (UiLocales.isEnglish(context()) != originalEnglish) ui(() -> UiLocales.switchLanguage(current.get()));
            ui(() -> current.get().finish());
            prefs.edit().putString("selection", previous).commit();
        }
    }

    private static void describe(android.view.accessibility.AccessibilityNodeInfo node, StringBuilder out, int depth) {
        if (depth > 15 || out.length() > 16000) return;
        out.append(node.getClassName()).append(" text=").append(node.getText())
                .append(" clickable=").append(node.isClickable()).append('\n');
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) { describe(child, out, depth + 1); child.recycle(); }
        }
    }

    private void ui(Action action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw (Exception) error.get();
        getInstrumentation().waitForIdleSync();
    }
    private interface Condition { boolean check() throws Exception; }
    private void await(Condition condition, int seconds) throws Exception {
        long deadline = android.os.SystemClock.elapsedRealtime() + seconds * 1000L;
        while (!condition.check()) {
            assertTrue("finite UI wait", android.os.SystemClock.elapsedRealtime() < deadline);
            Thread.sleep(100);
        }
        getInstrumentation().waitForIdleSync();
    }
    private static Object field(Object object, String name) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(object);
    }
    private static void setField(Object object, String name, Object value) throws Exception {
        java.lang.reflect.Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(object, value);
    }
    private static void invoke(Object object, String name) throws Exception {
        java.lang.reflect.Method method = object.getClass().getDeclaredMethod(name);
        method.setAccessible(true); method.invoke(object);
    }

    static double amplitude(OracleCoreVerifier.AudioTrack audio, double hz, double start, double duration) {
        int first = (int) (start * audio.sampleRate), count = (int) (duration * audio.sampleRate);
        assertTrue(first + count <= audio.samples.length);
        double sin = 0, cos = 0;
        for (int i = first; i < first + count; i++) {
            double phase = 2 * Math.PI * hz * i / audio.sampleRate;
            sin += audio.samples[i] * Math.sin(phase); cos += audio.samples[i] * Math.cos(phase);
        }
        return 2 * Math.hypot(sin, cos) / count;
    }

    private File export(EditConfig config, OfflineMusicCatalog catalog, String name) throws Exception {
        File output = new File(directory, name + ".mp4");
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        AtomicReference<Exception> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Handler main = new Handler(Looper.getMainLooper());
        main.post(() -> {
            engine.set(new Media3ExportEngine(context(), catalog));
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                @Override public void onProgress(int progress) {}
                @Override public void onDiagnostic(String message) { diagnostics.add(message); }
                @Override public void onCompleted(File file) { done.countDown(); }
                @Override public void onError(Exception failure) { error.set(failure); done.countDown(); }
            });
        });
        try {
            assertTrue("finite native BGM export", done.await(200, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            assertTrue(output.length() > 0);
            return output;
        } finally {
            CountDownLatch stopped = new CountDownLatch(1);
            main.post(() -> { if (engine.get() != null) engine.get().cancel(); stopped.countDown(); });
            assertTrue(stopped.await(10, TimeUnit.SECONDS));
        }
    }

    private File tone(String name, int rate, int channels, int frames, int frequency) throws Exception {
        File file = new File(directory, name);
        ByteBuffer bytes = ByteBuffer.allocate(44 + frames * channels * 2).order(ByteOrder.LITTLE_ENDIAN);
        bytes.putInt(0x46464952).putInt(bytes.capacity() - 8).putInt(0x45564157)
                .putInt(0x20746d66).putInt(16).putShort((short) 1).putShort((short) channels)
                .putInt(rate).putInt(rate * channels * 2).putShort((short) (channels * 2)).putShort((short) 16)
                .putInt(0x61746164).putInt(frames * channels * 2);
        for (int i = 0; i < frames; i++) {
            short sample = (short) Math.round(.12 * 32767 * Math.sin(2 * Math.PI * frequency * i / rate));
            for (int channel = 0; channel < channels; channel++) bytes.putShort(sample);
        }
        write(file, bytes.array());
        return file;
    }

    private JSONObject row(File tone, int frames) throws Exception {
        return new JSONObject().put("id", "own-test-tone").put("title", "OWN TEST TONE - NOT MUSIC")
                .put("titleZh", "OWN TEST TONE - NOT MUSIC").put("mood", "test").put("moodZh", "test")
                .put("style", "test").put("styleZh", "test").put("path", "music/test/own-tone.wav")
                .put("author", "Native test synthesis").put("license", "CC0-1.0")
                .put("licenseUrl", "https://creativecommons.org/publicdomain/zero/1.0/")
                .put("source", "https://example.invalid/own-generated-test-tone-not-a-recording")
                .put("sha256", hash(tone)).put("sourceSha256", hash(tone)).put("bytes", tone.length())
                .put("frames", frames).put("format", "pcm16le-48000-stereo");
    }

    private OfflineMusicCatalog catalog(JSONObject row, File tone) throws Exception {
        byte[] json = new JSONObject().put("version", 1).put("tracks", new JSONArray().put(row))
                .toString().getBytes(StandardCharsets.UTF_8);
        return OfflineMusicCatalog.load(path -> path.equals("music/catalog.json")
                ? new ByteArrayInputStream(json) : new FileInputStream(tone));
    }

    private static String hash(File file) throws Exception {
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[8192];
        try (FileInputStream input = new FileInputStream(file)) {
            int n; while ((n = input.read(buffer)) != -1) hash.update(buffer, 0, n);
        }
        StringBuilder text = new StringBuilder();
        for (byte b : hash.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 255));
        return text.toString();
    }

    private static void write(File file, byte[] bytes) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) { output.write(bytes); }
    }
    private interface Action { void run() throws Exception; }
    private static void expectFailure(Action action) throws Exception {
        try { action.run(); fail("invalid/missing music accepted"); }
        catch (IOException | IllegalArgumentException | org.json.JSONException expected) {}
    }
}
