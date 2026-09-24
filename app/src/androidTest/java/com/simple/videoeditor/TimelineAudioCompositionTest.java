package com.simple.videoeditor;

import android.net.Uri;
import android.test.AndroidTestCase;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.transformer.Composition;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;

public final class TimelineAudioCompositionTest extends AndroidTestCase {
    public void testComboUsesPostMixBudgetNotItemSpeedBudget() throws Exception {
        EditConfig config = builder().trim(750, 2750).speed(2f).build();
        Composition composition = Media3ExportEngine.createComposition(config);
        assertEquals(1_000_000L, Media3ExportEngine.editedAudioDurationUs(config));
        assertEquals(48000, outputFrames(composition, 48256));
        assertTrue(composition.sequences.get(0).editedMediaItems.get(0)
                .effects.audioProcessors.get(0) instanceof PitchPreservingAudioProcessor);
        assertTrue(composition.effects.videoEffects.isEmpty());
    }

    public void testSpeedHalfAndDoubleAndFractionalMillisecondEndpoint() throws Exception {
        for (float speed : new float[]{.5f, 1f, 2f}) {
            EditConfig config = builder().trim(750, 2751).speed(speed).build();
            long expectedUs = Math.round(2_001_000d / speed);
            assertEquals(expectedUs, Media3ExportEngine.editedAudioDurationUs(config));
            assertEquals((int) (expectedUs * 48000 / 1_000_000),
                    outputFrames(Media3ExportEngine.createComposition(config), 200000));
        }
    }

    public void testTitleAndImportedIntroAndMergeAreNotScaledOrCutOff() throws Exception {
        IntroTemplate title = new IntroTemplate();
        title.setDurationMs(500);
        EditConfig config = builder().mainSourceMetadata(true, 1024)
                .trim(750, 2750).speed(2f)
                .introTemplate(title, "")
                .introVideo(clip("intro", 700))
                .appendVideos(Arrays.asList(clip("append-a", 300), clip("append-b", 900)))
                .build();
        Composition composition = Media3ExportEngine.createCompositionWithPreparedAudioGaps(
                config, Uri.parse("file:///title.png"),
                Collections.singletonList(Uri.parse("file:///title-silence.wav")));
        assertEquals(3_400_000L, Media3ExportEngine.editedAudioDurationUs(config));
        assertEquals(163200, outputFrames(composition, 163456));
        assertEquals(5, composition.sequences.get(0).editedMediaItems.size());
        assertEquals(5, composition.sequences.get(1).editedMediaItems.size());
    }

    public void testLegacyIntroUnknownDurationIsNotGuessed() {
        EditConfig config = builder().intro(Uri.parse("file:///legacy-intro.mp4"))
                .speed(2f).replacementMusic(Uri.parse("file:///music.m4a")).build();
        assertEquals(C.TIME_UNSET, Media3ExportEngine.editedAudioDurationUs(config));
        assertTrue(Media3ExportEngine.createComposition(config).effects.audioProcessors.isEmpty());
        assertTrue(musicPass(config).effects.audioProcessors.isEmpty());
    }

    public void testMusicAndSilentRoutingRemainIntact() throws Exception {
        EditConfig silent = builder().speed(2f).volume(0f).build();
        assertTrue(Media3ExportEngine.createComposition(silent).sequences.get(0)
                .editedMediaItems.get(0).removeAudio);
        Uri music = Uri.parse("file:///music.m4a");
        EditConfig normal = builder().replacementMusic(music).build();
        Composition composition = Media3ExportEngine.createComposition(normal);
        assertTrue(composition.sequences.get(1).isLooping);
        assertEquals(192000, outputFrames(composition, 192256));
        for (float speed : new float[]{.5f, 2f}) {
            EditConfig edited = builder().replacementMusic(music).speed(speed)
                    .trim(750, 2751).build();
            assertTrue(Media3ExportEngine.needsMusicPass(edited));
            Composition firstPass = Media3ExportEngine.createComposition(edited);
            assertEquals(1, firstPass.sequences.size());
            Composition finalPass = musicPass(edited);
            assertTrue(finalPass.transmuxVideo);
            assertTrue(finalPass.effects.videoEffects.isEmpty());
            assertTrue(finalPass.sequences.get(0).editedMediaItems.get(0).removeAudio);
            assertTrue(finalPass.sequences.get(0).editedMediaItems.get(0)
                    .effects.videoEffects.isEmpty());
            assertTrue(finalPass.sequences.get(1).isLooping);
            assertTrue(finalPass.sequences.get(1).editedMediaItems.get(0)
                    .effects.audioProcessors.isEmpty());
            int expectedFrames = (int) (Math.round(2_001_000d / speed) * 48000 / 1_000_000);
            assertEquals(expectedFrames, outputFrames(firstPass, expectedFrames + 256));
            assertEquals(expectedFrames, outputFrames(finalPass, expectedFrames + 256));
        }
    }

    public void testMusicPassIncludesTitleImportedIntroAndEveryMergedClip() throws Exception {
        IntroTemplate title = new IntroTemplate();
        title.setDurationMs(500);
        for (float speed : new float[]{.5f, 2f}) {
            EditConfig config = builder().mainSourceMetadata(true, 1024)
                    .trim(750, 2751).speed(speed).introTemplate(title, "")
                    .introVideo(clip("intro", 700))
                    .appendVideos(Arrays.asList(clip("append-a", 300), clip("append-b", 900)))
                    .replacementMusic(Uri.parse("file:///music.m4a")).build();
            long expectedUs = Math.round(2_001_000d / speed) + 2_400_000L;
            assertEquals(expectedUs, Media3ExportEngine.editedAudioDurationUs(config));
            Composition firstPass = Media3ExportEngine.createComposition(
                    config, Uri.parse("file:///title.png"));
            assertEquals(1, firstPass.sequences.size());
            assertEquals(5, firstPass.sequences.get(0).editedMediaItems.size());
            int expectedFrames = (int) (expectedUs * 48000 / 1_000_000);
            assertEquals(expectedFrames, outputFrames(musicPass(config), expectedFrames + 256));
        }
    }

    private static Composition musicPass(EditConfig config) {
        return Media3ExportEngine.createMusicComposition(Uri.parse("file:///rendered.mp4"),
                config.replacementMusic, Media3ExportEngine.editedAudioDurationUs(config));
    }

    private static EditConfig.Builder builder() {
        return new EditConfig.Builder(Uri.parse("file:///main.mp4"), 4000).sourceSize(320, 240);
    }

    private static EditConfig.ImportedVideo clip(String name, long durationMs) {
        return new EditConfig.ImportedVideo(Uri.parse("file:///" + name + ".mp4"),
                durationMs, 320, 240, true, 1024);
    }

    private static int outputFrames(Composition composition, int suppliedFrames) throws Exception {
        assertEquals(1, composition.effects.audioProcessors.size());
        AudioProcessor processor = composition.effects.audioProcessors.get(0);
        assertTrue(processor instanceof TimelineAudioProcessor);
        processor.configure(new AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT));
        processor.flush();
        ByteBuffer input = ByteBuffer.allocateDirect(suppliedFrames * 2);
        int outputFrames = 0;
        int calls = 0;
        while (input.hasRemaining()) {
            processor.queueInput(input);
            ByteBuffer output = processor.getOutput();
            outputFrames += output.remaining() / 2;
            output.position(output.limit());
            assertTrue("PCM stalled", ++calls < 1000);
        }
        processor.queueEndOfStream();
        assertTrue(processor.isEnded());
        processor.reset();
        return outputFrames;
    }
}
