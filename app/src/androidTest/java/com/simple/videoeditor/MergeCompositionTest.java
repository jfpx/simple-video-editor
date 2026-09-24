package com.simple.videoeditor;

import android.net.Uri;
import android.test.AndroidTestCase;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@UnstableApi
public final class MergeCompositionTest extends AndroidTestCase {
    public void testMergeRejectsUnknownIntroAndNullAppendedMetadata() {
        expectReject("immutable imported intro metadata", () -> mergeBuilder()
                .intro(Uri.parse("file:///unknown-intro.mp4")).build());
        expectReject("Appended clip", () -> mergeBuilder()
                .appendVideos(Arrays.asList((EditConfig.ImportedVideo) null)).build());
    }

    public void testMergeAggregateAndOutputBounds() {
        expectReject("256 MiB", () -> mergeBuilder().mainSourceMetadata(true, EditConfig.MAX_VIDEO_BYTES)
                .appendVideos(Arrays.asList(
                        clip("file:///a.mp4", 1000, 320, 180, true, EditConfig.MAX_VIDEO_BYTES),
                        clip("file:///b.mp4", 1000, 320, 180, true, 1))).build());
        expectReject("4096", () -> mergeBuilder().outputHeight(4320).build());
        EditConfig expanded = mergeBuilder().sourceSize(4096, 4096).rotation(45).build();
        expectReject("4096", () -> Media3ExportEngine.editedCanvas(expanded));
        assertEquals(4320, new EditConfig.Builder(Uri.EMPTY, 1000).outputHeight(4320).build().outputHeight);
    }

    public void testMergeExactLimitsAndBuilderReuse() {
        ArrayList<EditConfig.ImportedVideo> clips = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            clips.add(clip("file:///e" + i + ".mp4", 20_000, 4096, 180, true, 1024));
        }
        EditConfig.Builder builder = new EditConfig.Builder(Uri.parse("file:///main.mp4"), 20_000)
                .sourceSize(320, 180).mainSourceMetadata(true, 1024).appendVideos(clips);
        EditConfig snapshot = builder.build();
        clips.clear();
        builder.appendVideos(null).mergeMode(false);
        assertEquals(5, snapshot.appendedVideos.size());
        assertTrue(snapshot.mergeEnabled);
        assertFalse(builder.build().mergeEnabled);
        expectReject("120 second", () -> new EditConfig.Builder(Uri.EMPTY, 20_001)
                .sourceSize(320, 180).mainSourceMetadata(true, 1024)
                .appendVideos(snapshot.appendedVideos).build());
    }

    private static EditConfig.Builder mergeBuilder() {
        return new EditConfig.Builder(Uri.parse("file:///main.mp4"), 1000)
                .mainSourceMetadata(true, 1024).sourceSize(320, 180)
                .appendVideos(Arrays.asList(clip("file:///extra.mp4", 1000, 320, 180, true, 1024)));
    }

    public void testMergeConfigCopiesAndValidatesClipList() {
        EditConfig.ImportedVideo intro = clip("file:///intro.mp4", 1200, 320, 180, true, 8 * 1024 * 1024L);
        ArrayList<EditConfig.ImportedVideo> extras = new ArrayList<>();
        extras.add(clip("file:///extra1.mp4", 1000, 240, 240, false, 4 * 1024 * 1024L));
        EditConfig config = new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000)
                .mainSourceMetadata(true, 12 * 1024 * 1024L)
                .sourceSize(320, 180)
                .introVideo(intro)
                .appendVideos(extras)
                .trim(500, 2500)
                .speed(2f)
                .build();
        extras.clear();
        assertEquals(1, config.appendedVideos.size());
        assertEquals(intro.uri, config.intro);
        try {
            config.appendedVideos.add(intro);
            fail("Merged clip list must be immutable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
        expectReject("120 second", () -> new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000)
                .mainSourceMetadata(true, 12 * 1024 * 1024L)
                .sourceSize(320, 180)
                .appendVideos(Arrays.asList(
                        clip("file:///e1.mp4", 60_000, 320, 180, true, 1024),
                        clip("file:///e2.mp4", 60_000, 320, 180, true, 1024)))
                .speed(.5f)
                .build());
        expectReject("5 appended", () -> {
            ArrayList<EditConfig.ImportedVideo> tooMany = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                tooMany.add(clip("file:///e" + i + ".mp4", 1000, 320, 180, true, 1024));
            }
            new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000)
                    .mainSourceMetadata(true, 1024)
                    .sourceSize(320, 180)
                    .appendVideos(tooMany)
                    .build();
        });
        expectReject("Merge mode requires at least one appended clip", () ->
                new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000)
                        .mainSourceMetadata(true, 1024)
                        .sourceSize(320, 180)
                        .mergeMode(true)
                        .build());
    }

    public void testMergeSpecificLimitsAreGatedOffForIntroOnlyExports() {
        EditConfig config = new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000)
                .mainSourceMetadata(true, EditConfig.MAX_VIDEO_BYTES + 1)
                .sourceSize(EditConfig.MAX_VIDEO_DIMENSION + 1, 180)
                .introVideo(clip("file:///intro.mp4", EditConfig.MAX_MERGED_DURATION_MS + 1,
                        EditConfig.MAX_VIDEO_DIMENSION + 1, 180, true, EditConfig.MAX_VIDEO_BYTES + 1))
                .build();
        assertFalse(config.mergeEnabled);
        assertEquals(EditConfig.MAX_VIDEO_BYTES + 1, config.mainFileSizeBytes);
        expectReject("128 MiB", () -> new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000)
                .mainSourceMetadata(true, EditConfig.MAX_VIDEO_BYTES + 1)
                .sourceSize(320, 180)
                .appendVideos(Arrays.asList(clip("file:///extra.mp4", 1000, 320, 180, true, 1024)))
                .build());
        expectReject("4096", () -> new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000)
                .mainSourceMetadata(true, 1024)
                .sourceSize(EditConfig.MAX_VIDEO_DIMENSION + 1, 180)
                .appendVideos(Arrays.asList(clip("file:///extra.mp4", 1000, 320, 180, true, 1024)))
                .build());
        expectReject("120 second", () -> new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000)
                .mainSourceMetadata(true, 1024)
                .sourceSize(320, 180)
                .introVideo(clip("file:///intro.mp4", EditConfig.MAX_MERGED_DURATION_MS + 1, 320, 180, true, 1024))
                .appendVideos(Arrays.asList(clip("file:///extra.mp4", 1000, 320, 180, true, 1024)))
                .build());
    }

    public void testMergeCompositionKeepsMainOnlyEffectsAndExplicitSilentGaps() {
        EditConfig config = new EditConfig.Builder(Uri.parse("file:///main.mp4"), 2400)
                .mainSourceMetadata(true, 12 * 1024 * 1024L)
                .sourceSize(320, 180)
                .introVideo(clip("file:///intro.mp4", 1200, 320, 180, true, 8 * 1024 * 1024L))
                .introTemplate(template(), "")
                .appendVideos(Arrays.asList(
                        clip("file:///extra-silent.mp4", 1000, 240, 240, false, 4 * 1024 * 1024L),
                        clip("file:///extra-audio.mp4", 800, 320, 180, true, 4 * 1024 * 1024L),
                        clip("file:///extra-tail-silent.mp4", 700, 320, 160, false, 4 * 1024 * 1024L)))
                .trim(600, 1800)
                .crop(.25f, 0f, 1f, 1f)
                .outputHeight(120)
                .speed(2f)
                .build();
        Composition composition = Media3ExportEngine.createCompositionWithPreparedAudioGaps(config,
                Uri.parse("file:///title.png"),
                Arrays.asList(
                        Uri.parse("file:///gap-title.wav"),
                        Uri.parse("file:///gap-middle.wav"),
                        Uri.parse("file:///gap-tail.wav")));
        assertEquals(2, composition.sequences.size());
        List<EditedMediaItem> video = composition.sequences.get(0).editedMediaItems;
        assertEquals(6, video.size());
        assertEquals("file:///title.png", video.get(0).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///intro.mp4", video.get(1).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///main.mp4", video.get(2).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///extra-silent.mp4", video.get(3).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///extra-audio.mp4", video.get(4).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///extra-tail-silent.mp4", video.get(5).mediaItem.localConfiguration.uri.toString());
        assertTrue(video.get(1).removeAudio);
        assertTrue(video.get(3).removeAudio);
        assertTrue(video.get(4).removeAudio);
        assertTrue(video.get(5).removeAudio);
        assertTrue("Main keeps its own edit stack", video.get(2).effects.videoEffects.size() > 3);
        assertEquals("Extras use only aspect-fit presentation", 1, video.get(3).effects.videoEffects.size());
        assertEquals(6, composition.sequences.get(1).editedMediaItems.size());
        assertEquals("file:///gap-title.wav",
                composition.sequences.get(1).editedMediaItems.get(0).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///intro.mp4",
                composition.sequences.get(1).editedMediaItems.get(1).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///main.mp4",
                composition.sequences.get(1).editedMediaItems.get(2).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///gap-middle.wav",
                composition.sequences.get(1).editedMediaItems.get(3).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///extra-audio.mp4",
                composition.sequences.get(1).editedMediaItems.get(4).mediaItem.localConfiguration.uri.toString());
        assertEquals("file:///gap-tail.wav",
                composition.sequences.get(1).editedMediaItems.get(5).mediaItem.localConfiguration.uri.toString());
    }

    private static IntroTemplate template() {
        IntroTemplate template = new IntroTemplate();
        template.setDurationMs(1000);
        template.setBackgroundColor(0xFF663399);
        template.setTextColor(0xFFFFFFFF);
        return template;
    }

    private static EditConfig.ImportedVideo clip(String uri, long durationMs, int width, int height,
                                                 boolean hasAudio, long sizeBytes) {
        return new EditConfig.ImportedVideo(Uri.parse(uri), durationMs, width, height, hasAudio, sizeBytes);
    }

    private static void expectReject(String message, Runnable action) {
        try {
            action.run();
            fail("Expected rejection containing " + message);
        } catch (RuntimeException expected) {
            assertNotNull(expected.getMessage());
            assertTrue(expected.getMessage().contains(message));
        }
    }
}
