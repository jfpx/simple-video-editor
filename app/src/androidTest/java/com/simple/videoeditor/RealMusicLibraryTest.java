package com.simple.videoeditor;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real APK recordings, never an injected catalog; independent host PCM checks inspect all exports. */
public final class RealMusicLibraryTest extends InstrumentationTestCase {
    private File directory;
    private String workflowToken;
    private Context context() { return getInstrumentation().getTargetContext(); }
    @Override protected void setUp() throws Exception {
        super.setUp();
        android.os.Bundle arguments = (android.os.Bundle) getInstrumentation().getClass()
                .getMethod("getArguments").invoke(getInstrumentation());
        workflowToken = arguments.getString("real_music_token", "standalone");
        assertTrue(workflowToken.matches("[a-zA-Z0-9-]{1,64}"));
        directory = new File(context().getFilesDir(), "real-music-evidence/" + workflowToken
                + "/" + getName() + "-" + UUID.randomUUID());
        assertTrue(directory.mkdirs());
    }

    @Override protected void runTest() throws Throwable {
        try { super.runTest(); }
        catch (Throwable failure) {
            try { screenshot(getName() + "-failed"); }
            catch (Throwable capture) { failure.addSuppressed(capture); }
            throw failure;
        }
    }

    public void testAuditionLifecycleAndCancelExport() throws Exception {
        android.content.SharedPreferences prefs = context().getSharedPreferences("offline_music", Context.MODE_PRIVATE);
        String previous = prefs.getString("selection", null);
        AtomicReference<MainActivity> current = new AtomicReference<>(launch());
        try {
            MainActivity initial = current.get();
            await(() -> !(Boolean) field(initial, "restoringUi"), 30);
            ((java.util.concurrent.ExecutorService) field(initial, "publicationWorker")).submit(() -> {}).get(20, TimeUnit.SECONDS);
            File source = source();
            ui(() -> {
                controls(initial).apply(new BackgroundMusic(true, "heavenly-loop", .5f, true));
                ((com.google.android.material.tabs.TabLayout) initial.findViewById(R.id.editorTabs)).getTabAt(1).select();
                initial.onActivityResult((Integer) field(initial, "VIDEO_PICK_CODE"), Activity.RESULT_OK,
                        new Intent().setData(Uri.fromFile(source)));
            });
            await(() -> !(Boolean) field(initial, "loading"), 30);
            OfflineMusicAudition audition = audition(initial);
            startAudition(initial);
            File snapshot = (File) field(audition, "snapshot");
            getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_HOME);
            await(() -> !audition.isActive(), 10);
            assertStopped(audition, snapshot);
            try (android.os.ParcelFileDescriptor command = getInstrumentation().getUiAutomation().executeShellCommand(
                    "am start --activity-reorder-to-front -n com.simple.videoeditor/.MainActivity");
                 InputStream output = new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {
                read(output);
            }
            MainActivity resumed = current.get();
            await(() -> !(Boolean) field(resumed, "restoringUi") && resumed.hasWindowFocus(), 30);
            startAudition(resumed);
            OfflineMusicAudition resumedAudition = audition(resumed);
            snapshot = (File) field(resumedAudition, "snapshot");
            ui(() -> ((android.media.AudioManager.OnAudioFocusChangeListener) field(resumedAudition, "focus"))
                    .onAudioFocusChange(android.media.AudioManager.AUDIOFOCUS_LOSS));
            assertStopped(resumedAudition, snapshot);
            startAudition(resumed);
            snapshot = (File) field(resumedAudition, "snapshot");
            ui(() -> controls(resumed).apply(new BackgroundMusic(true, "unsolved-investigation", 1f, false)));
            assertStopped(resumedAudition, snapshot);

            startAudition(resumed);
            snapshot = (File) field(resumedAudition, "snapshot");
            android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation()
                    .addMonitor(MainActivity.class.getName(), null, false);
            try {
                ui(resumed::recreate);
                MainActivity recreated = (MainActivity) monitor.waitForActivityWithTimeout(15000);
                assertNotNull(recreated); current.set(recreated);
                await(() -> !(Boolean) field(recreated, "restoringUi")
                        && !(Boolean) field(recreated, "restoringAssets"), 30);
                assertStopped(resumedAudition, snapshot);
                assertFalse(audition(recreated).isActive());
                assertEquals("unsolved-investigation", controls(recreated).snapshot().trackId);
            } finally { getInstrumentation().removeMonitor(monitor); }

            MainActivity ready = current.get();
            startAudition(ready);
            OfflineMusicAudition exportingAudition = audition(ready);
            snapshot = (File) field(exportingAudition, "snapshot");
            PublishedVideo oldResult = (PublishedVideo) field(ready, "lastVideo");
            ui(() -> {
                assertTrue(ready.findViewById(R.id.btnProcess).isEnabled());
                ready.findViewById(R.id.btnProcess).performClick();
                assertTrue((Boolean) field(ready, "exporting"));
                assertFalse(exportingAudition.isActive());
                assertTrue(ready.findViewById(R.id.btnCancelExport).isEnabled());
                ready.findViewById(R.id.btnCancelExport).performClick();
            });
            await(() -> !(Boolean) field(ready, "exporting") && !(Boolean) field(ready, "publishing"), 30);
            assertStopped(exportingAudition, snapshot);
            assertSame("cancel must not replace the previous result", oldResult, field(ready, "lastVideo"));
            assertTrue(ready.findViewById(R.id.btnProcess).isEnabled());
            assertFalse(((android.widget.TextView) ready.findViewById(R.id.tvErrorDetails)).getText().toString().isEmpty());
            screenshot("cancelled-real-export");
            result("lifecycle-result.json", new JSONObject().put("backgroundStop", true)
                    .put("focusLossCallback", true).put("selectionStop", true).put("recreationStop", true)
                    .put("exportStop", true).put("cancelPreservesResult", true));
        } finally {
            ui(() -> current.get().finish());
            assertTrue(prefs.edit().putString("selection", previous).commit());
        }
    }

    private OfflineMusicAudition audition(MainActivity activity) throws Exception {
        return (OfflineMusicAudition) field(controls(activity), "audition");
    }
    private void startAudition(MainActivity activity) throws Exception {
        ui(() -> {
            ((com.google.android.material.tabs.TabLayout) activity.findViewById(R.id.editorTabs)).getTabAt(1).select();
            assertTrue(activity.findViewById(R.id.btnAuditionMusic).isEnabled());
            activity.findViewById(R.id.btnAuditionMusic).performClick();
        });
        await(() -> field(audition(activity), "player") != null
                && ((MediaPlayer) field(audition(activity), "player")).isPlaying(), 15);
        screenshot("audition-active");
    }
    private void assertStopped(OfflineMusicAudition audition, File snapshot) throws Exception {
        assertNotNull(snapshot);
        assertFalse(audition.isActive());
        assertNull(field(audition, "player"));
        assertNull(field(audition, "snapshot"));
        assertFalse("owned audition copy removed", snapshot.exists());
    }

    public void testOriginalCatalogPinsAndNegativeBounds() throws Exception {
        OfflineMusicCatalog catalog = OfflineMusicCatalog.load(context());
        assertEquals(118, catalog.tracks.size());
        String[] hashes = {"a842e9e054019132cacc8fd352e7b31c000ebb51e0b227a2511e1bccb4eb166e",
                "2877e423ff90cb99b3082840e66cf79d69a809708466c6f27c750c0e1ce2a912",
                "3c328794b77d3f3742a5a8fa36606c3b780d15b0b8f6148b29a78ec6caffcd9e",
                "5f40c6b20ac4e7b6944f176ecbfd24c5839257e5c818fecca33d7ddb147a20e7",
                "2b37b5b4006a0856d5578c60ea9d932201f7b6b7d1a91505c39eeca137c53dff",
                "2be3c830b1e3bfa885ebe32496a7d7ed6e13d8f9fe1991f99d8af2c64ee23e71"};
        long[] sizes = {1224922, 2200350, 1284993, 705155, 585189, 602110};
        long[] frames = {1484075, 2116800, 1080932, 735232, 1587712, 3263488};
        JSONArray decodes = new JSONArray();
        for (int i = 0; i < hashes.length; i++) {
            OfflineMusicCatalog.Track track = catalog.tracks.get(i);
            assertEquals(hashes[i], track.sha256); assertEquals(hashes[i], track.sourceSha256);
            assertEquals(sizes[i], track.bytes); assertEquals(frames[i], track.frames);
            assertEquals(44100, track.sampleRate);
            File copy = new File(directory, track.id + ".ogg");
            catalog.copyVerified(track.id, copy);
            assertEquals(hashes[i], hash(copy));
            decodes.put(decodeOriginal(copy, track));
            assertEquals(i >= 4, track.label(true).contains("乐曲，可重复播放／衔接不保证无缝"));
            assertEquals(i >= 4, track.label(false).contains("not guaranteed seamless"));
        }
        result("decode.json", new JSONObject().put("tracks", decodes));
        byte[] json;
        try (InputStream in = context().getAssets().open("music/catalog.json")) { json = read(in); }
        JSONObject original = new JSONObject(new String(json, StandardCharsets.UTF_8));
        for (String key : new String[]{"frames", "format", "path", "sourceSha256", "bytes", "playbackKind"}) {
            JSONObject altered = new JSONObject(original.toString());
            JSONObject row = altered.getJSONArray("tracks").getJSONObject(0);
            row.put(key, key.equals("frames") ? 1484076 : key.equals("bytes") ? 1 :
                    key.equals("format") ? "mp3" : key.equals("path") ? "music/../bad.ogg" :
                    key.equals("playbackKind") ? "seamless-guaranteed" :
                            String.join("", java.util.Collections.nCopies(64, "0")));
            File rejected = new File(directory, "rejected-" + key);
            try {
                OfflineMusicCatalog bad = OfflineMusicCatalog.load(path -> path.equals("music/catalog.json")
                        ? new java.io.ByteArrayInputStream(altered.toString().getBytes(StandardCharsets.UTF_8))
                        : context().getAssets().open(path));
                bad.copyVerified("heavenly-loop", rejected);
                fail("accepted invalid " + key);
            } catch (java.io.IOException expected) { assertFalse(rejected.exists()); }
        }
        for (boolean composition : new boolean[]{false, true}) {
            JSONObject altered = new JSONObject(original.toString());
            altered.getJSONArray("tracks").getJSONObject(0)
                    .put("playbackKind", composition ? "composition-repeat" : "creator-loop")
                    .put("frames", 180L * 44100 + 1);
            try {
                OfflineMusicCatalog.load(path -> new java.io.ByteArrayInputStream(
                        altered.toString().getBytes(StandardCharsets.UTF_8)));
                fail("accepted excessive duration");
            } catch (java.io.IOException expected) {}
        }
    }

    private JSONObject decodeOriginal(File file, OfflineMusicCatalog.Track track) throws Exception {
        android.media.MediaExtractor extractor = new android.media.MediaExtractor();
        android.media.MediaCodec codec = null;
        TimelineAudioProcessor cap = new TimelineAudioProcessor(
                (track.frames * 1_000_000L + track.sampleRate - 1) / track.sampleRate);
        long rawFrames = 0, cappedFrames = 0;
        MessageDigest pcmHash = MessageDigest.getInstance("SHA-256");
        try {
            extractor.setDataSource(file.getAbsolutePath());
            assertEquals(1, extractor.getTrackCount());
            android.media.MediaFormat format = extractor.getTrackFormat(0);
            assertEquals("audio/vorbis", format.getString(android.media.MediaFormat.KEY_MIME));
            assertEquals(44100, format.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE));
            assertEquals(track.channels, format.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT));
            extractor.selectTrack(0);
            codec = android.media.MediaCodec.createDecoderByType("audio/vorbis");
            codec.configure(format, null, null, 0);
            codec.start();
            boolean inputEnd = false, outputEnd = false, configured = false;
            int bytesPerFrame = 0;
            long deadline = android.os.SystemClock.elapsedRealtime() + 90000;
            android.media.MediaCodec.BufferInfo info = new android.media.MediaCodec.BufferInfo();
            while (!outputEnd) {
                assertTrue("full original decode deadline", android.os.SystemClock.elapsedRealtime() < deadline);
                boolean progressed = false;
                for (int queued = 0; queued < 32 && !inputEnd; queued++) {
                    int index = codec.dequeueInputBuffer(0);
                    if (index < 0) break;
                    if (index >= 0) {
                        progressed = true;
                        java.nio.ByteBuffer input = codec.getInputBuffer(index);
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        inputEnd = size < 0;
                        codec.queueInputBuffer(index, 0, Math.max(0, size),
                                inputEnd ? 0 : extractor.getSampleTime(),
                                inputEnd ? android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (!inputEnd) extractor.advance();
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 0);
                if (index == android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    progressed = true;
                    assertFalse("single native PCM format", configured);
                    android.media.MediaFormat output = codec.getOutputFormat();
                    assertEquals(44100, output.getInteger(android.media.MediaFormat.KEY_SAMPLE_RATE));
                    assertEquals(track.channels, output.getInteger(android.media.MediaFormat.KEY_CHANNEL_COUNT));
                    int encoding = output.containsKey(android.media.MediaFormat.KEY_PCM_ENCODING)
                            ? output.getInteger(android.media.MediaFormat.KEY_PCM_ENCODING)
                            : android.media.AudioFormat.ENCODING_PCM_16BIT;
                    assertTrue(encoding == android.media.AudioFormat.ENCODING_PCM_16BIT
                            || encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT);
                    bytesPerFrame = track.channels * (encoding == android.media.AudioFormat.ENCODING_PCM_FLOAT ? 4 : 2);
                    cap.configure(new androidx.media3.common.audio.AudioProcessor.AudioFormat(44100, track.channels, encoding));
                    cap.flush();
                    configured = true;
                } else if (index >= 0) {
                    progressed = true;
                    if (info.size > 0) {
                        assertTrue(configured);
                        assertEquals(0, info.size % bytesPerFrame);
                        rawFrames += info.size / bytesPerFrame;
                        assertTrue("bounded native padding", rawFrames <= track.frames + 4096);
                        java.nio.ByteBuffer pcm = codec.getOutputBuffer(index);
                        pcm.position(info.offset).limit(info.offset + info.size);
                        while (pcm.hasRemaining()) {
                            cap.queueInput(pcm);
                            java.nio.ByteBuffer bounded = cap.getOutput();
                            cappedFrames += bounded.remaining() / bytesPerFrame;
                            pcmHash.update(bounded);
                        }
                    }
                    outputEnd = (info.flags & android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                }
                if (!progressed) Thread.sleep(1);
            }
            cap.queueEndOfStream();
            assertTrue(cap.isEnded());
            assertTrue("no missing original samples", rawFrames >= track.frames);
            assertEquals("exact original final granule cap", track.frames, cappedFrames);
            StringBuilder hash = new StringBuilder();
            for (byte b : pcmHash.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            return new JSONObject().put("id", track.id).put("assetSha256", hash(file))
                    .put("decoder", codec.getName()).put("nativeFrames", rawFrames)
                    .put("granuleFrames", track.frames).put("cappedFrames", cappedFrames)
                    .put("nativePcmSha256", hash.toString()).put("bytesPerFrame", bytesPerFrame);
        } finally {
            cap.reset();
            if (codec != null) codec.release();
            extractor.release();
        }
    }

    public void testNativeFullOriginalLoopsAndIndependentGain() throws Exception {
        File source = source();
        File longSource = source("source80.mp4");
        JSONArray outputs = new JSONArray();
        for (OfflineMusicCatalog.Track track : OfflineMusicCatalog.load(context()).tracks.subList(0, 6)) {
            for (int percent : new int[]{100, 50}) {
                boolean longTrack = track.frames > 54L * track.sampleRate;
                File selected = longTrack ? longSource : source;
                int seconds = longTrack ? 80 : 54;
                EditConfig config = new EditConfig.Builder(Uri.fromFile(selected), seconds * 1000L)
                        .sourceSize(160, 120).mainSourceMetadata(true, selected.length())
                        .volume(percent == 100 ? 0 : 1)
                        .backgroundMusic(new BackgroundMusic(true, track.id, percent / 100f, percent == 50)).build();
                outputs.put(export(config, track.id + "-" + percent).put("durationSeconds", seconds));
            }
        }
        result("native-result.json", new JSONObject().put("outputs", outputs)
                .put("independentHostPcmRequired", true));
    }

    public void testAll118NativeDecodeAndStableRecipeRoundTrips() throws Exception {
        OfflineMusicCatalog catalog = OfflineMusicCatalog.load(context());
        assertEquals(118, catalog.tracks.size());
        JSONArray decodes = new JSONArray();
        java.util.HashSet<String> hashes = new java.util.HashSet<>();
        for (OfflineMusicCatalog.Track track : catalog.tracks) {
            File copy = new File(directory, "selected.ogg");
            try {
                catalog.copyVerified(track.id, copy);
                JSONObject decoded = decodeOriginal(copy, track);
                assertTrue("unique native PCM", hashes.add(decoded.getString("nativePcmSha256")));
                decodes.put(decoded);
                JSONObject recipe = new JSONObject().put("version", 4);
                new BackgroundMusic(true, track.id, .5f, true).putRecipe(recipe);
                BackgroundMusic restored = BackgroundMusic.fromRecipe(new JSONObject(recipe.toString()));
                catalog.validateSelection(restored);
                assertEquals(track.id, restored.trackId);
                assertEquals(.5f, restored.gain);
                assertTrue(restored.muteOriginal);
                result("all-native-decode.json", new JSONObject().put("tracks", decodes)
                        .put("complete", decodes.length() == catalog.tracks.size()));
            } finally { assertTrue(!copy.exists() || copy.delete()); }
        }
    }

    public void testExpandedRepresentativeLoopsAndGain() throws Exception {
        OfflineMusicCatalog catalog = OfflineMusicCatalog.load(context());
        String[] ids = {catalog.tracks.get(6).id, catalog.tracks.get(60).id, catalog.tracks.get(114).id,
                "abstraction-cfc56edbe16dc83cde0d0344", "abstraction-8fae5917ba33861a49611966",
                "shrine", "challenge-accepted", "blue-meadow-in-green-sky"};
        JSONArray outputs = new JSONArray();
        File shortSource = source("source4.mp4"), longSource = source("source160.mp4");
        for (String id : ids) {
            OfflineMusicCatalog.Track track = catalog.require(id);
            boolean loop = id.equals("abstraction-cfc56edbe16dc83cde0d0344")
                    || id.equals("abstraction-8fae5917ba33861a49611966");
            int seconds = loop ? 160 : 4;
            File selected = loop ? longSource : shortSource;
            for (int percent : new int[]{100, 50}) {
                EditConfig config = new EditConfig.Builder(Uri.fromFile(selected), seconds * 1000L)
                        .sourceSize(160, 120).mainSourceMetadata(true, selected.length())
                        .volume(percent == 100 ? 0 : 1)
                        .backgroundMusic(new BackgroundMusic(true, id, percent / 100f, percent == 50)).build();
                outputs.put(export(config, id + "-" + percent).put("durationSeconds", seconds)
                        .put("trackId", id).put("gain", percent / 100.0).put("loopCrossed", loop));
                result("expanded-result.json", new JSONObject().put("outputs", outputs)
                        .put("complete", outputs.length() == ids.length * 2)
                        .put("independentHostPcmRequired", true));
            }
        }
        OfflineMusicCatalog.Track mono = catalog.require("abstraction-cfc56edbe16dc83cde0d0344");
        EditConfig mixed = new EditConfig.Builder(Uri.fromFile(shortSource), 4000)
                .sourceSize(160, 120).mainSourceMetadata(true, shortSource.length()).volume(.25f)
                .backgroundMusic(new BackgroundMusic(true, mono.id, .5f, false)).build();
        result("mixed-result.json", export(mixed, "mono-stereo-mix")
                .put("durationSeconds", 4).put("trackId", mono.id).put("gain", .5)
                .put("originalGain", .25).put("independentHostPcmRequired", true));
    }

    public void testRealSoundUiAuditionExportPresetLanguageRestartShare() throws Exception {
        verifySoundUi("unsolved-investigation", OfflineMusicCatalog.load(context()).tracks.subList(0, 6));
    }

    public void testExpandedRealSearchAuditionExportPresetLanguageRestartShare() throws Exception {
        OfflineMusicCatalog catalog = OfflineMusicCatalog.load(context());
        verifySoundUi("abstraction-cfc56edbe16dc83cde0d0344", java.util.Arrays.asList(
                catalog.tracks.get(6), catalog.tracks.get(60), catalog.tracks.get(114),
                catalog.require("abstraction-cfc56edbe16dc83cde0d0344")));
    }

    private void verifySoundUi(String selectedId, java.util.List<OfflineMusicCatalog.Track> choices) throws Exception {
        android.content.SharedPreferences prefs = context().getSharedPreferences("offline_music", Context.MODE_PRIVATE);
        String previous = prefs.getString("selection", null);
        boolean language = UiLocales.isEnglish(context());
        AtomicReference<MainActivity> current = new AtomicReference<>(launch());
        String presetName = "real-music-" + UUID.randomUUID();
        WholeEditPresets presets = new WholeEditPresets(context());
        try {
            MainActivity initial = current.get();
            await(() -> !(Boolean) field(initial, "restoringUi"), 30);
            ((java.util.concurrent.ExecutorService) field(initial, "publicationWorker")).submit(() -> {}).get(20, TimeUnit.SECONDS);
            File source = source();
            ui(() -> {
                controls(initial).apply(BackgroundMusic.OFF);
                ((com.google.android.material.tabs.TabLayout) initial.findViewById(R.id.editorTabs)).getTabAt(1).select();
                initial.onActivityResult((Integer) field(initial, "VIDEO_PICK_CODE"), Activity.RESULT_OK,
                        new Intent().setData(Uri.fromFile(source)));
            });
            await(() -> !(Boolean) field(initial, "loading") && !(Boolean) field(initial, "restoringAssets"), 30);
            for (OfflineMusicCatalog.Track track : choices) {
                chooseTrack(initial, track);
                assertEquals(track.id, controls(initial).save().getString("trackId"));
                assertEquals(50, ((android.widget.SeekBar) initial.findViewById(R.id.seekBgmGain)).getProgress());
                assertEquals(track.label(!UiLocales.isEnglish(context())),
                        ((android.widget.TextView) initial.findViewById(R.id.tvLibraryTrack)).getText().toString());
                ui(() -> initial.findViewById(R.id.btnAuditionMusic).performClick());
                OfflineMusicAudition audition = (OfflineMusicAudition) field(controls(initial), "audition");
                await(() -> field(audition, "player") != null && ((MediaPlayer) field(audition, "player")).isPlaying(), 15);
                Thread.sleep(700);
                ui(() -> assertTrue(((MediaPlayer) field(audition, "player")).getCurrentPosition() > 200));
                screenshot(track.id + "-playing");
                if (track.channels == 1) {
                    long started = android.os.SystemClock.elapsedRealtime();
                    await(() -> !audition.isActive(), 12);
                    assertTrue("mono preview lasts ten seconds", android.os.SystemClock.elapsedRealtime() - started >= 8500);
                } else {
                    ui(() -> initial.findViewById(R.id.btnAuditionMusic).performClick());
                }
                assertFalse(audition.isActive()); assertNull(field(audition, "player"));
            }
            OfflineMusicCatalog.Track previousTrack = OfflineMusicCatalog.load(context()).require(selectedId);
            chooseTrack(initial, previousTrack);
            ui(() -> {
                ((android.widget.CheckBox) initial.findViewById(R.id.cbLibraryMusic)).setChecked(true);
                ((android.widget.CheckBox) initial.findViewById(R.id.cbMuteOriginal)).setChecked(true);
                ((android.widget.SeekBar) initial.findViewById(R.id.seekBgmGain)).setProgress(0);
            });
            JSONObject recipe = (JSONObject) invoke(initial, "wholePresetSnapshot");
            presets.save(presetName, recipe, null, null, null);
            BackgroundMusic restored = BackgroundMusic.fromRecipe(new WholeEditPresets(context()).load(presetName));
            assertEquals(selectedId, restored.trackId); assertEquals(.5f, restored.gain); assertTrue(restored.muteOriginal);
            ui(() -> { controls(initial).apply(BackgroundMusic.OFF); controls(initial).apply(restored); });
            for (int pass = 0; pass < 2; pass++) {
                android.app.Instrumentation.ActivityMonitor monitor = getInstrumentation().addMonitor(MainActivity.class.getName(), null, false);
                try {
                    ui(() -> {
                        ((com.google.android.material.tabs.TabLayout) current.get().findViewById(R.id.editorTabs)).getTabAt(4).select();
                        current.get().findViewById(R.id.btnLanguage).performClick();
                    });
                    MainActivity next = (MainActivity) monitor.waitForActivityWithTimeout(15000);
                    assertNotNull(next); current.set(next);
                    await(() -> !(Boolean) field(next, "restoringUi") && !(Boolean) field(next, "restoringAssets"), 30);
                    assertSelection(next, selectedId); screenshot("language-" + pass);
                } finally { getInstrumentation().removeMonitor(monitor); }
            }
            MainActivity ready = current.get();
            startAudition(ready);
            OfflineMusicAudition exportingAudition = audition(ready);
            File auditionCopy = (File) field(exportingAudition, "snapshot");
            AtomicReference<PublishedVideo> previousResult = new AtomicReference<>();
            ui(() -> {
                previousResult.set((PublishedVideo) field(ready, "lastVideo"));
                ((com.google.android.material.tabs.TabLayout) ready.findViewById(R.id.editorTabs)).getTabAt(1).select();
                assertTrue(ready.findViewById(R.id.btnProcess).isEnabled());
                ready.findViewById(R.id.btnProcess).performClick();
                assertTrue("real export must start", (Boolean) field(ready, "exporting"));
                assertFalse(ready.findViewById(R.id.btnAuditionMusic).isEnabled());
                assertStopped(exportingAudition, auditionCopy);
            });
            await(() -> !(Boolean) field(ready, "exporting") && !(Boolean) field(ready, "publishing"), 360);
            PublishedVideo published = (PublishedVideo) field(ready, "lastVideo");
            assertNotSame("must publish this export, not reuse a previous result", previousResult.get(), published);
            assertNotNull(published); assertNotNull(published.uri); assertEquals("content", published.uri.getScheme());
            if (previousResult.get() != null) assertFalse(published.uri.equals(previousResult.get().uri));
            File output = new File(directory, "ui-" + selectedId + "-50.mp4");
            try (InputStream input = context().getContentResolver().openInputStream(published.uri)) { copy(input, output); }
            assertEquals(hash(published.privateFile), hash(output));
            screenshot("public-movies-export");
            android.app.Instrumentation.ActivityMonitor share = getInstrumentation().addMonitor(
                    new android.content.IntentFilter(Intent.ACTION_CHOOSER), null, true);
            try {
                ui(() -> ready.findViewById(R.id.btnShareVideo).performClick());
                await(() -> share.getHits() == 1, 15);
                assertEquals("real Share action launches chooser", 1, share.getHits());
            } finally { getInstrumentation().removeMonitor(share); }
            ui(ready::finish);
            current.set(launch());
            await(() -> !(Boolean) field(current.get(), "restoringUi"), 30);
            assertSelection(current.get(), selectedId);
            screenshot("restarted-selection");
            result("ui-result.json", new JSONObject().put("publicUri", published.uri.toString())
                    .put("output", output.getName()).put("sha256", hash(output)).put("durationSeconds", 54)
                    .put("freshPublishedResult", true)
                    .put("previousPublicUri", previousResult.get() == null ? "" : String.valueOf(previousResult.get().uri))
                    .put("trackId", selectedId).put("auditionTrackCount", choices.size()).put("presetRoundTrip", true).put("bilingual", true)
                    .put("activityRestart", true).put("shareChooser", true).put("independentHostPcmRequired", true));
        } finally {
            ui(() -> current.get().finish());
            if (UiLocales.isEnglish(context()) != language) ui(() -> UiLocales.switchLanguage(current.get()));
            prefs.edit().putString("selection", previous).commit();
            presets.delete(presetName);
        }
    }

    private void assertSelection(MainActivity activity, String selectedId) throws Exception {
        BackgroundMusic selection = controls(activity).snapshot();
        assertEquals(selectedId, selection.trackId);
        assertEquals(.5f, selection.gain); assertTrue(selection.enabled); assertTrue(selection.muteOriginal);
    }
    private void chooseTrack(MainActivity activity, OfflineMusicCatalog.Track track) throws Exception {
        String before = controls(activity).snapshot().trackId;
        ui(() -> activity.findViewById(R.id.btnLibraryTrack).performClick());
        clickText(context().getString(R.string.bgm_all_tracks));
        AtomicReference<android.view.accessibility.AccessibilityNodeInfo> search = new AtomicReference<>();
        await(() -> {
            search.set(findSearch(getInstrumentation().getUiAutomation().getRootInActiveWindow()));
            return search.get() != null;
        }, 10);
        assertTrue("real catalog search must be physically visible", search.get().isVisibleToUser());
        android.graphics.Rect searchBounds = new android.graphics.Rect();
        search.get().getBoundsInScreen(searchBounds);
        assertFalse(searchBounds.isEmpty());
        assertTrue(searchBounds.bottom <= context().getResources().getDisplayMetrics().heightPixels);
        android.os.Bundle text = new android.os.Bundle();
        text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                "no-matching-track-999");
        assertTrue(search.get().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text));
        getInstrumentation().waitForIdleSync();
        assertEquals("filtering never substitutes a track", before, controls(activity).snapshot().trackId);
        text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, track.title);
        assertTrue(search.get().performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text));
        getInstrumentation().waitForIdleSync();
        screenshot(track.id + "-search");
        clickText(UiLocales.isEnglish(context()) ? track.title : track.titleZh);
        assertEquals(track.id, controls(activity).snapshot().trackId);
    }
    private android.view.accessibility.AccessibilityNodeInfo findSearch(android.view.accessibility.AccessibilityNodeInfo node) {
        if (node == null) return null;
        if ("android.widget.EditText".contentEquals(node.getClassName())) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo found = findSearch(node.getChild(i));
            if (found != null) return found;
        }
        return null;
    }
    private MainActivity launch() {
        return (MainActivity) getInstrumentation().startActivitySync(new Intent(context(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
    }
    private OfflineMusicControls controls(MainActivity activity) throws Exception { return (OfflineMusicControls) field(activity, "offlineMusic"); }
    private File source() throws Exception {
        return source("source.mp4");
    }
    private File source(String name) throws Exception {
        File file = new File(directory, name);
        try (InputStream in = getInstrumentation().getContext().getAssets().open("real-music/" + name)) { copy(in, file); }
        return file;
    }
    private JSONObject export(EditConfig config, String name) throws Exception {
        File output = new File(directory, name + ".mp4");
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Exception> error = new AtomicReference<>();
        AtomicReference<Media3ExportEngine> engine = new AtomicReference<>();
        JSONArray diagnostics = new JSONArray();
        ui(() -> {
            engine.set(new Media3ExportEngine(context()));
            engine.get().export(config, output, new Media3ExportEngine.Listener() {
                public void onProgress(int progress) {}
                public void onDiagnostic(String message) { diagnostics.put(message); }
                public void onCompleted(File file) { done.countDown(); }
                public void onError(Exception failure) { error.set(failure); done.countDown(); }
            });
        });
        try {
            assertTrue("bounded real export", done.await(180, TimeUnit.SECONDS));
            if (error.get() != null) throw error.get();
            return new JSONObject().put("file", output.getName()).put("sha256", hash(output)).put("diagnostics", diagnostics);
        } finally { ui(() -> engine.get().cancel()); }
    }
    private void clickText(String text) throws Exception {
        await(() -> {
            android.view.accessibility.AccessibilityNodeInfo root = getInstrumentation().getUiAutomation().getRootInActiveWindow();
            if (root == null) return false;
            try {
                for (android.view.accessibility.AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(text)) {
                    try {
                        if ("android.widget.EditText".contentEquals(node.getClassName())) continue;
                        if (!node.isVisibleToUser()) continue;
                        if (node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)) return true;
                        android.graphics.Rect r = new android.graphics.Rect(); node.getBoundsInScreen(r);
                        if (r.isEmpty()) continue;
                        long now = android.os.SystemClock.uptimeMillis();
                        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, 0, r.centerX(), r.centerY(), 0);
                        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 50, 1, r.centerX(), r.centerY(), 0);
                        try { getInstrumentation().sendPointerSync(down); getInstrumentation().sendPointerSync(up); }
                        finally { down.recycle(); up.recycle(); }
                        return true;
                    } finally { node.recycle(); }
                }
                return false;
            } finally { root.recycle(); }
        }, 10);
        getInstrumentation().waitForIdleSync(); Thread.sleep(300);
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap image = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(image);
        try (FileOutputStream out = new FileOutputStream(new File(directory, name + ".png"))) {
            image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { image.recycle(); }
    }
    private void result(String name, JSONObject result) throws Exception {
        result.put("revision", BuildConfig.SOURCE_REVISION)
                .put("workflowToken", workflowToken)
                .put("policy", VideoEncodingSettings.modeName(VideoEncodingSettings.compatibilityEnabled(context())))
                .put("nativeStatus", "PASS");
        try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
            out.write(result.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static byte[] read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192]; int n;
        while ((n = in.read(b)) != -1) { if (out.size() + n > OfflineMusicCatalog.MAX_CATALOG_BYTES) throw new java.io.IOException("bound"); out.write(b, 0, n); }
        return out.toByteArray();
    }
    private static void copy(InputStream in, File file) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            byte[] b = new byte[65536]; int n; long count = 0;
            while ((n = in.read(b)) != -1) { count += n; assertTrue(count < 32 * 1024 * 1024); out.write(b, 0, n); }
        }
    }
    private static String hash(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] b = new byte[65536]; int n; while ((n = in.read(b)) != -1) digest.update(b, 0, n);
        }
        StringBuilder out = new StringBuilder();
        for (byte b : digest.digest()) out.append(String.format(Locale.ROOT, "%02x", b & 255));
        return out.toString();
    }
    private interface Action { void run() throws Exception; }
    private interface Condition { boolean check() throws Exception; }
    private void ui(Action action) throws Exception {
        AtomicReference<Throwable> error = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> { try { action.run(); } catch (Throwable e) { error.set(e); } });
        if (error.get() instanceof Error) throw (Error) error.get();
        if (error.get() != null) throw (Exception) error.get();
        getInstrumentation().waitForIdleSync();
    }
    private void await(Condition condition, int seconds) throws Exception {
        long end = android.os.SystemClock.elapsedRealtime() + seconds * 1000L;
        java.util.concurrent.atomic.AtomicBoolean complete = new java.util.concurrent.atomic.AtomicBoolean();
        while (true) {
            ui(() -> complete.set(condition.check()));
            if (complete.get()) return;
            assertTrue("finite wait", android.os.SystemClock.elapsedRealtime() < end);
            Thread.sleep(100);
        }
    }
    private static Object field(Object target, String name) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static Object invoke(Object target, String name) throws Exception {
        java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); return method.invoke(target);
    }
}
