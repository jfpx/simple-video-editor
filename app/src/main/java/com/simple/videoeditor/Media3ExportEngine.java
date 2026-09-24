package com.simple.videoeditor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.util.Log;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Effect;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Size;
import androidx.media3.effect.Crop;
import androidx.media3.effect.DebugTraceUtil;
import androidx.media3.effect.GlMatrixTransformation;
import androidx.media3.effect.MatrixTransformation;
import androidx.media3.effect.OverlayEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.effect.ScaleAndRotateTransformation;
import androidx.media3.effect.SpeedChangeEffect;
import androidx.media3.effect.TextOverlay;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

/** One export at a time; Transformer access runs on main, diagnostics on the emitting thread. */
@UnstableApi
public final class Media3ExportEngine {
    private static final long PROGRESS_INTERVAL_MS = 250L;
    private static final String TAG = "Media3ExportEngine";

    public interface Listener {
        void onProgress(int percent);
        void onCompleted(File output);
        void onError(Exception error);
        default void onCodecs(String videoEncoder, String audioEncoder) {}
        default void onElapsed(long elapsedMs, String pass) {}
        /** Synchronous checkpoint; may throw to abort export if diagnostic persistence fails. */
        default void onDiagnostic(String message) {}
    }

    private final Context context;
    private final OfflineMusicCatalog suppliedMusicCatalog;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile ExportJob activeJob;

    public Media3ExportEngine(Context context) {
        this(context, null);
    }

    Media3ExportEngine(Context context, OfflineMusicCatalog catalog) {
        this.context = Objects.requireNonNull(context, "context").getApplicationContext();
        suppliedMusicCatalog = catalog;
    }

    /** The destination must not exist. Invalid requests are reported without disturbing an export. */
    public void export(EditConfig config, File output, Listener listener) {
        requireMainThread();
        Objects.requireNonNull(listener, "listener");
        if (activeJob != null) {
            listener.onError(new IllegalStateException("An export is already running"));
            return;
        }
        if (config == null || output == null) {
            listener.onError(new IllegalArgumentException("config and output are required"));
            return;
        }
        ExportJob job = new ExportJob(output, listener,
                VideoEncodingSettings.compatibilityEnabled(context));
        activeJob = job;
        boolean provider = isContent(config.input) || isContent(introUri(config))
                || isContent(config.replacementMusic);
        for (EditConfig.ImportedVideo clip : config.appendedVideos) provider |= isContent(clip.uri);
        if (provider) {
            job.preparing = true;
            job.preparer = new Thread(() -> prepare(job, config, output), "video-export-prepare");
            job.preparer.start();
        } else prepare(job, config, output);
    }

    private static boolean isContent(Uri uri) { return uri != null && "content".equals(uri.getScheme()); }

    private void prepare(ExportJob job, EditConfig config, File output) {
        try {
            if (config.inputSource != null) {
                config.inputSource.checkCurrent(context);
            }
            if (config.introVideo != null && config.introVideo.source != null)
                config.introVideo.source.checkCurrent(context);
            for (EditConfig.ImportedVideo clip : config.appendedVideos)
                if (clip.source != null) clip.source.checkCurrent(context);
            MediaCopy.checkCancelled();
            if (config.introTitle != null && config.introTitle.sourceFrameBackground
                    && config.titleBackground == null)
                throw new IOException("Extract the original source frame before exporting its title background");
            // Atomically reserve ownership, so a failed request never removes an existing file.
            if (!output.createNewFile()) {
                throw new IOException("Output already exists; choose a new file");
            }
            job.ownsOutput = true;
            diagnostic(job, "encodingMode=" + VideoEncodingSettings.modeName(job.softwareOnly)
                    + " softwareOnly=" + job.softwareOnly
                    + (job.softwareOnly
                    ? " exact-size/rate; no hardware fallback; slower; HDR inputs unsupported"
                    : " existing device selection and HDR negotiation"));
            job.traceRequested = config.speed != 1f || config.replacementMusic != null || config.backgroundMusic.enabled;
            if (job.traceRequested) {
                // Transformer.start resets the bounded first/last-event trace for each pass.
                // This is process-global Media3 diagnostic state, not a timestamp/frame repair.
                job.enabledTracing = !DebugTraceUtil.enableTracing;
                DebugTraceUtil.enableTracing = true;
            }
            diagnostic(job, "timeline trimMs=" + config.startMs + ".." + config.endMs
                    + " speed=" + config.speed + " expectedMainDurationUs="
                    + Math.round((config.endMs - config.startMs) * 1000d / config.speed)
                    + " introClip=" + (introUri(config) != null)
                    + " titleDurationMs=" + (config.introTitle == null ? 0 : config.introTitle.durationMs)
                    + " appendedClipCount=" + config.appendedVideos.size()
                    + " loopingMusic=" + (config.replacementMusic != null || config.backgroundMusic.enabled)
                    + " bgm={" + config.backgroundMusic + "} sourceGain=" + config.sourceGain()
                    + " twoPass=" + needsMusicPass(config));
            if (config.speed != 1f || introUri(config) != null || config.introTitle != null
                    || !config.appendedVideos.isEmpty()) {
                float importedFrameRate = introUri(config) == null ? Format.NO_VALUE : probeVideoFrameRate(introUri(config));
                for (EditConfig.ImportedVideo clip : config.appendedVideos) {
                    importedFrameRate = maxKnownFrameRate(importedFrameRate, probeVideoFrameRate(clip.uri));
                }
                job.encoderFrameRate = encodingFrameRate(config, probeVideoFrameRate(config.input), importedFrameRate);
            }
            if (needsMusicPass(config)) {
                File intermediate = new File(output.getParentFile(),
                        ".music-video-" + UUID.randomUUID() + ".mp4");
                if (!intermediate.createNewFile()) {
                    throw new IOException("Cannot reserve intermediate video");
                }
                job.intermediate = intermediate;
                job.pendingMusic = config.replacementMusic;
                job.pendingMusicDurationUs = editedAudioDurationUs(config);
                if (config.backgroundMusic.enabled) {
                    if (job.pendingMusicDurationUs == C.TIME_UNSET) {
                        throw new IOException("Library BGM requires immutable intro duration metadata");
                    }
                    OfflineMusicCatalog catalog = suppliedMusicCatalog == null
                            ? OfflineMusicCatalog.load(context) : suppliedMusicCatalog;
                    OfflineMusicCatalog.Track track = catalog.require(config.backgroundMusic.trackId);
                    String assetPath = track.path;
                    if (track.isVorbis()) {
                        job.libraryLoopDurationUs = (track.frames * 1_000_000L + track.sampleRate - 1) / track.sampleRate;
                    }
                    File libraryMusic = new File(output.getParentFile(), ".library-" + UUID.randomUUID()
                            + assetPath.substring(assetPath.lastIndexOf('.')));
                    catalog.copyVerified(config.backgroundMusic.trackId, libraryMusic);
                    job.libraryMusic = libraryMusic;
                    job.pendingMusic = Uri.fromFile(job.libraryMusic);
                    job.backgroundMusic = config.backgroundMusic;
                }
            }
            Uri titleImage = null;
            if (config.introTitle != null) {
                job.titleImage = new File(output.getParentFile(),
                        ".intro-title-" + UUID.randomUUID() + ".png");
                if (!job.titleImage.createNewFile()) {
                    job.titleImage = null;
                    throw new IOException("Cannot reserve intro image");
                }
                renderIntroTitle(config, job.titleImage);
                titleImage = Uri.fromFile(job.titleImage);
            }
            Composition composition;
            if (needsSeparateSourceAudioSequence(config)) {
                List<Uri> silenceAudioGaps = prepareSilenceAudioGaps(job, config, output.getParentFile());
                composition = createCompositionWithPreparedAudioGaps(config, titleImage, silenceAudioGaps);
            } else {
                Uri delayedAudioSilence = null;
                if (needsDelayedMainAudio(config)) {
                    SourceAudioFormat sourceAudio = probeSourceAudioFormat(config.input);
                    if (sourceAudio != null) {
                        job.delayedAudioSilence = new File(output.getParentFile(),
                                ".intro-audio-delay-" + UUID.randomUUID() + ".wav");
                        if (!job.delayedAudioSilence.createNewFile()) {
                            job.delayedAudioSilence = null;
                            throw new IOException("Cannot reserve intro audio delay");
                        }
                        writeSilentPcmWav(job.delayedAudioSilence, config.introTitle.durationMs, sourceAudio);
                        delayedAudioSilence = Uri.fromFile(job.delayedAudioSilence);
                    }
                }
                composition = createComposition(config, titleImage, delayedAudioSilence);
            }
            diagnostic(job, "timing stage=preparation elapsedMs=" + elapsedMs(job.startedNs));
            MediaCopy.checkCancelled();
            Runnable ready = () -> {
                job.preparing = false;
                if (job.cancelRequested) {
                    fail(job, new CancellationException("Export cancelled during preparation"));
                    return;
                }
                try {
                    startTransformation(job, composition,
                            job.intermediate == null ? output : job.intermediate, true);
                    mainHandler.post(job.pollProgress);
                } catch (RuntimeException error) { fail(job, error); }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) ready.run();
            else mainHandler.post(ready);
        } catch (IOException | RuntimeException error) {
            Runnable failed = () -> {
                job.preparing = false;
                fail(job, job.cancelRequested ? new CancellationException("Export cancelled during preparation") : error);
            };
            if (Looper.myLooper() == Looper.getMainLooper()) failed.run();
            else mainHandler.post(failed);
        }
    }

    private void startTransformation(ExportJob job, Composition composition, File output,
                                     boolean forceVideoEncoding) {
        job.passStartedNs = System.nanoTime();
        diagnostic(job, "start pass=" + (job.musicPass ? "music-transmux" : "edit")
                + " forceVideoEncoding=" + forceVideoEncoding
                + " transmuxVideo=" + composition.transmuxVideo
                + " sequences=" + composition.sequences.size()
                + " exportElapsedMs=" + elapsedMs(job.startedNs));
        Transformer.Builder transformerBuilder = new Transformer.Builder(context);
        if (job.softwareOnly) {
            transformerBuilder.setAssetLoaderFactory(
                    new DefaultAssetLoaderFactory(context,
                            new CompatibilityDecoderFactory(
                                    new DefaultDecoderFactory.Builder(context).build()),
                            Clock.DEFAULT));
        }
        job.transformer = transformerBuilder
                .setLooper(Looper.getMainLooper())
                .setMaxDelayBetweenMuxerSamplesMs(C.TIME_UNSET)
                .setEncoderFactory(new StrictEncoderFactory(context, forceVideoEncoding,
                        job.encoderFrameRate, job.softwareOnly, message -> diagnostic(job, message)))
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .experimentalSetTrimOptimizationEnabled(false)
                .addListener(new Transformer.Listener() {
                    @Override
                    public void onCompleted(Composition composition, ExportResult result) {
                        if (activeJob != job) return;
                        try {
                            if (result.videoEncoderName != null) {
                                job.selectedVideoBackend = result.videoEncoderName;
                            }
                            diagnostic(job, "completed pass=" + (job.musicPass ? "music-transmux" : "edit")
                                    + " encodingMode=" + VideoEncodingSettings.modeName(job.softwareOnly)
                                    + " selectedVideoBackend=" + job.selectedVideoBackend
                                    + " durationMs=" + result.durationMs
                                    + " videoFrameCount=" + result.videoFrameCount
                                    + " videoConversion=" + result.videoConversionProcess
                                    + " audioConversion=" + result.audioConversionProcess
                                    + " passElapsedMs=" + elapsedMs(job.passStartedNs)
                                    + " exportElapsedMs=" + elapsedMs(job.startedNs));
                            logTimelineTrace(job, "completed");
                            job.listener.onCodecs(result.videoEncoderName, result.audioEncoderName);
                        } catch (RuntimeException error) {
                            fail(job, error);
                            return;
                        }
                        if (job.pendingMusic != null) {
                            startMusicPass(job);
                        } else {
                            complete(job);
                        }
                    }

                    @Override
                    public void onError(Composition composition, ExportResult result,
                                        ExportException error) {
                        fail(job, error);
                    }
                })
                .build();
        job.transformer.start(composition, output.getAbsolutePath());
        diagnostic(job, "timing stage=transformer-start pass="
                + (job.musicPass ? "music-transmux" : "edit")
                + " elapsedMs=" + elapsedMs(job.passStartedNs)
                + " exportElapsedMs=" + elapsedMs(job.startedNs));
    }

    private void startMusicPass(ExportJob job) {
        if (activeJob != job) return;
        job.transformer = null;
        try {
            if (!job.intermediate.isFile() || job.intermediate.length() == 0) {
                throw new IOException("Transformer produced no intermediate video");
            }
            Composition composition = job.backgroundMusic == null
                    ? createMusicComposition(Uri.fromFile(job.intermediate), job.pendingMusic, job.pendingMusicDurationUs)
                    : createLibraryMixComposition(Uri.fromFile(job.intermediate), job.pendingMusic,
                            job.pendingMusicDurationUs, job.backgroundMusic.gain, job.libraryLoopDurationUs);
            job.pendingMusic = null;
            job.musicPass = true;
            startTransformation(job, composition, job.output, false);
        } catch (IOException | RuntimeException error) {
            fail(job, error);
        }
    }

    public boolean isRunning() {
        return activeJob != null;
    }

    public void cancel() {
        ExportJob job = activeJob;
        if (job == null) {
            return;
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            cancelJob(job);
        } else {
            mainHandler.post(() -> cancelJob(job));
        }
    }

    private void cancelJob(ExportJob job) {
        if (activeJob != job) return;
        job.cancelRequested = true;
        if (job.preparing) {
            job.preparer.interrupt();
        } else fail(job, new CancellationException("Export cancelled"));
    }

    static Composition createComposition(EditConfig config) {
        return createComposition(config, null);
    }

    static Composition createComposition(EditConfig config, Uri titleImage) {
        if (needsSeparateSourceAudioSequence(config) && requiredSilenceGapCount(config) > 0) {
            throw new IllegalArgumentException("Merge audio gaps require prepared silent clip snapshots");
        }
        if (titleImage != null && needsDelayedMainAudio(config)) {
            throw new IllegalArgumentException("Title audio requires a probed source audio delay");
        }
        return createComposition(config, titleImage, null);
    }

    /** A null delay means that the source probe found no audio track. */
    static Composition createComposition(EditConfig config, Uri titleImage, Uri delayedAudioSilence) {
        if (needsSeparateSourceAudioSequence(config)) {
            if (requiredSilenceGapCount(config) > 0) {
                throw new IllegalArgumentException("Merge audio gaps require prepared silent clip snapshots");
            }
            return createCompositionWithPreparedAudioGaps(config, titleImage, Collections.emptyList());
        }
        boolean delayMainAudio = needsDelayedMainAudio(config);
        List<EditedMediaItem> videoItems = buildVideoTimeline(config, titleImage,
                delayMainAudio || config.sourceGain() == 0f || config.replacementMusic != null);
        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        sequences.add(new EditedMediaItemSequence.Builder(videoItems).build());
        if (config.replacementMusic != null && !needsMusicPass(config)) {
            // A looping audio-only sequence is bounded by the non-looping video sequence,
            // including its intro and trim. Speed changes use a rendered intermediate instead.
            sequences.add(createMusicSequence(config.replacementMusic));
        } else if (delayMainAudio && delayedAudioSilence != null) {
            sequences.add(createDelayedMainAudioSequence(config, delayedAudioSilence));
        }
        return new Composition.Builder(sequences)
                .setEffects(createTimelineAudioEffects(config))
                // Media3 otherwise rejects a silent first clip followed by an audible one.
                .experimentalSetForceAudioTrack(needsForcedAudioTrack(config, delayMainAudio))
                .build();
    }

    static Composition createCompositionWithPreparedAudioGaps(EditConfig config, Uri titleImage,
                                                              List<Uri> silenceAudioGaps) {
        if (!needsSeparateSourceAudioSequence(config)) {
            throw new IllegalArgumentException("Prepared merge audio gaps are only valid for merged source audio");
        }
        return buildCompositionWithPreparedAudioGaps(config, titleImage, silenceAudioGaps);
    }

    private static Composition buildCompositionWithPreparedAudioGaps(EditConfig config, Uri titleImage,
                                                                     List<Uri> silenceAudioGaps) {
        List<EditedMediaItem> videoItems = buildVideoTimeline(config, titleImage, true);
        List<EditedMediaItemSequence> sequences = new ArrayList<>();
        sequences.add(new EditedMediaItemSequence.Builder(videoItems).build());
        if (config.replacementMusic != null && !needsMusicPass(config)) {
            sequences.add(createMusicSequence(config.replacementMusic));
        } else if (config.sourceGain() != 0f) {
            EditedMediaItemSequence sourceAudio = createSeparateSourceAudioSequence(config, silenceAudioGaps);
            if (sourceAudio != null) {
                sequences.add(sourceAudio);
            }
        }
        return new Composition.Builder(sequences)
                .setEffects(createTimelineAudioEffects(config)).build();
    }

    private static Effects createTimelineAudioEffects(EditConfig config) {
        return createTimelineAudioEffects(editedAudioDurationUs(config));
    }

    private static Effects createTimelineAudioEffects(long durationUs) {
        // A legacy URI-only intro has no known duration. Never truncate it to the main clip.
        if (durationUs == C.TIME_UNSET) {
            return new Effects(Collections.emptyList(), Collections.emptyList());
        }
        // Composition audio effects run AFTER Media3's mixer, so its leading silence also
        // counts against the endpoint. Per-item trimming cannot enforce that boundary.
        return new Effects(Collections.singletonList(new TimelineAudioProcessor(durationUs)),
                Collections.emptyList());
    }

    static long editedAudioDurationUs(EditConfig config) {
        if (config.intro != null && config.introVideo == null) return C.TIME_UNSET;
        long durationUs = Math.round((config.endMs - config.startMs) * 1000d / config.speed);
        try {
            if (config.introTitle != null) {
                durationUs = Math.addExact(durationUs, config.introTitle.durationMs * 1000L);
            }
            if (config.introVideo != null) {
                durationUs = Math.addExact(durationUs, config.introVideo.durationMs * 1000L);
            }
            for (EditConfig.ImportedVideo clip : config.appendedVideos) {
                durationUs = Math.addExact(durationUs, clip.durationMs * 1000L);
            }
            return durationUs;
        } catch (ArithmeticException overflow) {
            return C.TIME_UNSET;
        }
    }

    private static boolean needsDelayedMainAudio(EditConfig config) {
        return config.introTitle != null
                && config.mainFileSizeBytes <= 0
                && config.intro == null
                && config.introVideo == null
                && config.appendedVideos.isEmpty()
                && config.replacementMusic == null
                && config.sourceGain() != 0f;
    }

    private static boolean needsForcedAudioTrack(EditConfig config, boolean delayMainAudio) {
        return !delayMainAudio
                && !needsSeparateSourceAudioSequence(config)
                && (config.intro != null || config.introTitle != null)
                && config.introVideo == null
                && config.appendedVideos.isEmpty()
                && config.replacementMusic == null
                && config.sourceGain() != 0f;
    }

    private static EditedMediaItemSequence createDelayedMainAudioSequence(EditConfig config,
                                                                          Uri delayedAudioSilence) {
        // Media3 1.5.1 SequenceAssetLoader$GapSignalingAssetLoader hardcodes gap PCM as 44.1kHz stereo,
        // so use a real silent WAV matching the source audio format before the main audio clip.
        return new EditedMediaItemSequence.Builder(
                new EditedMediaItem.Builder(MediaItem.fromUri(delayedAudioSilence))
                        .setRemoveVideo(true)
                        .build())
                .addItem(createEditedItem(config, false, true))
                .build();
    }

    private static List<EditedMediaItem> buildVideoTimeline(EditConfig config, Uri titleImage,
                                                            boolean removeSourceAudio) {
        List<EditedMediaItem> videoItems = new ArrayList<>();
        if (config.introTitle != null) {
            if (titleImage == null) {
                throw new IllegalArgumentException("A template intro requires its rendered title image");
            }
            List<Effect> titleEffects = new ArrayList<>();
            if (!config.introTitle.animation.equals("legacy")) {
                titleEffects.add(new OverlayEffect(Collections.singletonList(
                        new AnimatedTitleOverlay(config.introTitle, config.titleBackground))));
            }
            if (config.border.appliesTo(true)) titleEffects.add(BorderOverlay.effect(config.border));
            videoItems.add(new EditedMediaItem.Builder(new MediaItem.Builder()
                    .setUri(titleImage).setMimeType(MimeTypes.IMAGE_PNG)
                    .setImageDurationMs(config.introTitle.durationMs).build())
                    .setFrameRate(30)
                    .setRemoveAudio(true)
                    .setEffects(new Effects(Collections.emptyList(), titleEffects))
                    .build());
        }
        Uri introUri = introUri(config);
        if (introUri != null) {
            videoItems.add(createImportedVideoItem(introUri, config.introVideo, editedCanvas(config),
                    removeSourceAudio, config.sourceGain(), config.border));
        }
        videoItems.add(createEditedItem(config,
                removeSourceAudio || config.sourceGain() == 0f || config.replacementMusic != null, false));
        for (EditConfig.ImportedVideo clip : config.appendedVideos) {
            videoItems.add(createImportedVideoItem(clip.uri, clip, editedCanvas(config),
                    removeSourceAudio || config.sourceGain() == 0f || config.replacementMusic != null, config.sourceGain(), config.border));
        }
        return videoItems;
    }

    private static EditedMediaItemSequence createSeparateSourceAudioSequence(EditConfig config,
                                                                             List<Uri> silenceAudioGaps) {
        if (!hasAnyKnownSourceAudio(config)) {
            return null;
        }
        ArrayList<EditedMediaItem> items = new ArrayList<>();
        int silenceIndex = 0;
        if (config.introTitle != null) {
            items.add(createSilentAudioItem(requireSilenceUri(silenceAudioGaps, silenceIndex++)));
        }
        if (config.introVideo != null) {
            if (config.introVideo.hasAudio) {
                items.add(createImportedAudioItem(config.introVideo, config.sourceGain()));
            } else {
                items.add(createSilentAudioItem(requireSilenceUri(silenceAudioGaps, silenceIndex++)));
            }
        }
        if (config.mainHasAudio) {
            items.add(createEditedItem(config, false, true));
        } else {
            items.add(createSilentAudioItem(requireSilenceUri(silenceAudioGaps, silenceIndex++)));
        }
        for (EditConfig.ImportedVideo clip : config.appendedVideos) {
            if (clip.hasAudio) {
                items.add(createImportedAudioItem(clip, config.sourceGain()));
            } else {
                items.add(createSilentAudioItem(requireSilenceUri(silenceAudioGaps, silenceIndex++)));
            }
        }
        if (silenceIndex != silenceAudioGaps.size()) {
            throw new IllegalArgumentException("Unused prepared silent merge audio clips");
        }
        return new EditedMediaItemSequence.Builder(items).build();
    }

    private static EditedMediaItem createImportedVideoItem(Uri uri, EditConfig.ImportedVideo snapshot, Size canvas,
                                                           boolean removeAudio, float volume, VideoBorder border) {
        List<AudioProcessor> audioProcessors = new ArrayList<>();
        if (!removeAudio && volume != 0f && volume != 1f) {
            audioProcessors.add(new GainAudioProcessor(volume));
        }
        List<Effect> effects = new ArrayList<>();
        effects.add(Presentation.createForWidthAndHeight(canvas.getWidth(), canvas.getHeight(),
                Presentation.LAYOUT_SCALE_TO_FIT));
        if (border.appliesTo(false)) effects.add(BorderOverlay.effect(border));
        return importedItem(uri, snapshot)
                .setRemoveAudio(removeAudio)
                .setEffects(new Effects(audioProcessors, effects))
                .build();
    }

    private static EditedMediaItem createImportedAudioItem(EditConfig.ImportedVideo clip, float volume) {
        List<AudioProcessor> audioProcessors = new ArrayList<>();
        if (volume != 0f && volume != 1f) {
            audioProcessors.add(new GainAudioProcessor(volume));
        }
        return importedItem(clip.uri, clip)
                .setRemoveVideo(true)
                .setEffects(new Effects(audioProcessors, Collections.emptyList()))
                .build();
    }

    private static EditedMediaItem.Builder importedItem(Uri uri, EditConfig.ImportedVideo snapshot) {
        if (snapshot == null) return new EditedMediaItem.Builder(MediaItem.fromUri(uri));
        // Both sequences share one bounded presentation interval, not separate AAC/video tails.
        MediaItem item = new MediaItem.Builder().setUri(uri)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(snapshot.durationMs).build()).build();
        return new EditedMediaItem.Builder(item).setDurationUs(snapshot.durationMs * 1000L);
    }

    private static EditedMediaItem createSilentAudioItem(Uri uri) {
        return new EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setRemoveVideo(true)
                .build();
    }

    private static Uri requireSilenceUri(List<Uri> silenceAudioGaps, int index) {
        if (index >= silenceAudioGaps.size()) {
            throw new IllegalArgumentException("Missing prepared silent merge audio clip " + (index + 1));
        }
        return silenceAudioGaps.get(index);
    }

    private static boolean needsSeparateSourceAudioSequence(EditConfig config) {
        return config.replacementMusic == null
                && config.sourceGain() != 0f
                && (config.intro == null || config.introVideo != null)
                && (config.introTitle != null || config.introVideo != null || !config.appendedVideos.isEmpty())
                && config.mainFileSizeBytes > 0;
    }

    private static boolean hasAnyKnownSourceAudio(EditConfig config) {
        if (config.introVideo != null && config.introVideo.hasAudio) return true;
        if (config.mainHasAudio) return true;
        for (EditConfig.ImportedVideo clip : config.appendedVideos) {
            if (clip.hasAudio) return true;
        }
        return false;
    }

    private static int requiredSilenceGapCount(EditConfig config) {
        if (!needsSeparateSourceAudioSequence(config) || !hasAnyKnownSourceAudio(config)) {
            return 0;
        }
        int count = config.introTitle == null ? 0 : 1;
        if (config.introVideo != null && !config.introVideo.hasAudio) count++;
        if (!config.mainHasAudio) count++;
        for (EditConfig.ImportedVideo clip : config.appendedVideos) {
            if (!clip.hasAudio) count++;
        }
        return count;
    }

    private static Uri introUri(EditConfig config) {
        return config.introVideo != null ? config.introVideo.uri : config.intro;
    }

    private List<Uri> prepareSilenceAudioGaps(ExportJob job, EditConfig config, File directory)
            throws IOException {
        int count = requiredSilenceGapCount(config);
        if (count == 0) {
            return Collections.emptyList();
        }
        SourceAudioFormat format = probeFirstAvailableAudioFormat(config);
        if (format == null) {
            throw new IOException("Merged source audio metadata reported audio, but no audio track was readable");
        }
        ArrayList<Uri> uris = new ArrayList<>(count);
        if (config.introTitle != null) {
            uris.add(createSilenceAudioGap(job, directory, config.introTitle.durationMs, format));
        }
        if (config.introVideo != null && !config.introVideo.hasAudio) {
            uris.add(createSilenceAudioGap(job, directory, (int) config.introVideo.durationMs, format));
        }
        if (!config.mainHasAudio) {
            uris.add(createSilenceAudioGap(job, directory,
                    (int) Math.round((config.endMs - config.startMs) / (double) config.speed), format));
        }
        for (EditConfig.ImportedVideo clip : config.appendedVideos) {
            if (!clip.hasAudio) {
                uris.add(createSilenceAudioGap(job, directory, (int) clip.durationMs, format));
            }
        }
        return uris;
    }

    private Uri createSilenceAudioGap(ExportJob job, File directory, int durationMs, SourceAudioFormat format)
            throws IOException {
        File file = new File(directory, ".merge-audio-gap-" + UUID.randomUUID() + ".wav");
        if (!file.createNewFile()) {
            throw new IOException("Cannot reserve merge audio gap");
        }
        try {
            writeSilentPcmWav(file, durationMs, format);
        } catch (IOException | RuntimeException error) {
            if (!file.delete() && file.exists()) {
                error.addSuppressed(new IOException("Could not delete failed merge audio gap file"));
            }
            throw error;
        }
        job.silenceAudioGaps.add(file);
        return Uri.fromFile(file);
    }

    private SourceAudioFormat probeFirstAvailableAudioFormat(EditConfig config) throws IOException {
        if (config.introVideo != null && config.introVideo.hasAudio) {
            return probeSourceAudioFormat(config.introVideo.uri);
        }
        if (config.mainHasAudio) {
            return probeSourceAudioFormat(config.input);
        }
        for (EditConfig.ImportedVideo clip : config.appendedVideos) {
            if (clip.hasAudio) {
                return probeSourceAudioFormat(clip.uri);
            }
        }
        return null;
    }

    static Size editedCanvas(EditConfig config) {
        Size canvas = new Size(config.sourceWidth, config.sourceHeight);
        for (Effect effect : createEditedItem(config).effects.videoEffects) {
            if (effect instanceof GlMatrixTransformation) {
                canvas = ((GlMatrixTransformation) effect).configure(
                        canvas.getWidth(), canvas.getHeight());
            }
        }
        if (config.mergeEnabled && (canvas.getWidth() > EditConfig.MAX_VIDEO_DIMENSION
                || canvas.getHeight() > EditConfig.MAX_VIDEO_DIMENSION)) {
            throw new IllegalArgumentException("Edited main canvas must be at most 4096 pixels per side");
        }
        return canvas;
    }

    private void renderIntroTitle(EditConfig config, File output) throws IOException {
        Size size = editedCanvas(config);
        // Media3's default bitmap loader downsamples larger images; reject instead of changing canvas.
        if (size.getWidth() > 4096 || size.getHeight() > 4096) {
            throw new IllegalArgumentException("Template intros support canvases up to 4096 pixels per side");
        }
        EditConfig.IntroTitle title = config.introTitle;
        Bitmap bitmap = null;
        Bitmap background = null;
        try {
            if (config.titleBackground != null) background = config.titleBackground.decode(4096);
            bitmap = Bitmap.createBitmap(size.getWidth(), size.getHeight(), Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            TitleRenderer.draw(canvas, size.getWidth(), size.getHeight(), title, 0, background,
                    context.getResources().getDisplayMetrics().scaledDensity);
            try (FileOutputStream stream = new FileOutputStream(output)) {
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("Cannot encode intro image");
                }
            }
        } catch (OutOfMemoryError error) {
            throw new IOException("Insufficient memory to render the intro title", error);
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (background != null) background.recycle();
        }
    }

    static boolean needsMusicPass(EditConfig config) {
        // In 1.5.1 TransformerInternal.onMediaItemChanged bounds loops using pre-effect durations.
        // Render speed changes first, then use that real video's timeline to bound native music.
        return config.backgroundMusic.enabled || (config.replacementMusic != null && config.speed != 1f);
    }

    static Size displaySize(int width, int height, int rotation, float pixelRatio) {
        if (width <= 0 || height <= 0 || pixelRatio <= 0
                || Float.isNaN(pixelRatio) || Float.isInfinite(pixelRatio)) {
            throw new IllegalArgumentException("Invalid source display geometry");
        }
        if (Math.abs(rotation % 180) == 90) {
            int swap = width;
            width = height;
            height = swap;
        }
        // Match VideoFrameProcessingWrapper then DefaultVideoFrameProcessor in Media3 1.5.1.
        if (pixelRatio > 1f) width = (int) (width * pixelRatio);
        else if (pixelRatio < 1f) height = (int) (height / pixelRatio);
        return new Size(width, height);
    }

    static Composition createMusicComposition(Uri video, Uri music) {
        return createMusicComposition(video, music, C.TIME_UNSET);
    }

    static Composition createMusicComposition(Uri video, Uri music, long editedDurationUs) {
        EditedMediaItem picture = new EditedMediaItem.Builder(MediaItem.fromUri(video))
                .setRemoveAudio(true).build();
        return new Composition.Builder(
                new EditedMediaItemSequence.Builder(picture).build(), createMusicSequence(music))
                // Looping stops at buffer start timestamps, not at a crossing buffer's last frame.
                // Use the original full edit budget, not the intermediate's encoded duration.
                .setEffects(createTimelineAudioEffects(editedDurationUs))
                .setTransmuxVideo(true)
                .build();
    }

    private static EditedMediaItemSequence createMusicSequence(Uri uri) {
        EditedMediaItem music = new EditedMediaItem.Builder(MediaItem.fromUri(uri))
                .setRemoveVideo(true).build();
        return new EditedMediaItemSequence.Builder(music).setIsLooping(true).build();
    }

    static Composition createLibraryMixComposition(Uri video, Uri music, long durationUs, float gain) {
        return createLibraryMixComposition(video, music, durationUs, gain, C.TIME_UNSET);
    }

    private static Composition createLibraryMixComposition(Uri video, Uri music, long durationUs, float gain,
                                                           long loopDurationUs) {
        // Source gain/speed/mute were applied once in the edit pass. Normalize both mixer inputs;
        // otherwise a 44.1 kHz mono original and 48 kHz stereo library are incompatible in Media3.
        EditedMediaItem picture = new EditedMediaItem.Builder(MediaItem.fromUri(video))
                .setEffects(normalizedMixEffects(1f)).build();
        Effects normalized = normalizedMixEffects(gain);
        List<AudioProcessor> backgroundProcessors = new ArrayList<>();
        // Android Vorbis can emit samples beyond the final Ogg granule. Retain exactly the
        // original native frames before resampling on each loop, not the padded codec block.
        if (loopDurationUs != C.TIME_UNSET) backgroundProcessors.add(new TimelineAudioProcessor(loopDurationUs));
        backgroundProcessors.addAll(normalized.audioProcessors);
        EditedMediaItem background = new EditedMediaItem.Builder(MediaItem.fromUri(music))
                .setRemoveVideo(true).setEffects(new Effects(backgroundProcessors, Collections.emptyList())).build();
        return new Composition.Builder(new EditedMediaItemSequence.Builder(picture).build(),
                new EditedMediaItemSequence.Builder(background).setIsLooping(true).build())
                .setEffects(createTimelineAudioEffects(durationUs)).setTransmuxVideo(true).build();
    }

    private static Effects normalizedMixEffects(float gain) {
        androidx.media3.common.audio.SonicAudioProcessor resampler =
                new androidx.media3.common.audio.SonicAudioProcessor();
        resampler.setOutputSampleRateHz(48000);
        androidx.media3.common.audio.ChannelMixingAudioProcessor channels =
                new androidx.media3.common.audio.ChannelMixingAudioProcessor();
        channels.putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(1, 2));
        channels.putChannelMixingMatrix(androidx.media3.common.audio.ChannelMixingMatrix.create(2, 2));
        List<AudioProcessor> processors = new ArrayList<>();
        processors.add(resampler);
        processors.add(channels);
        if (gain != 1f) processors.add(new GainAudioProcessor(gain));
        return new Effects(processors, Collections.emptyList());
    }

    private static EditedMediaItem createEditedItem(EditConfig config) {
        return createEditedItem(config,
                config.sourceGain() == 0f || config.replacementMusic != null, false);
    }

    private static EditedMediaItem createEditedItem(EditConfig config,
                                                    boolean removeAudio,
                                                    boolean removeVideo) {
        MediaItem mediaItem = new MediaItem.Builder()
                .setUri(config.input)
                .setClippingConfiguration(new MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(config.startMs)
                        .setEndPositionMs(config.endMs)
                        .setStartsAtKeyFrame(false)
                        .build())
                .build();
        List<Effect> videoEffects = removeVideo ? Collections.emptyList() : createVideoEffects(config);
        List<AudioProcessor> audioProcessors = removeAudio
                ? Collections.emptyList()
                : createAudioProcessors(config);
        return new EditedMediaItem.Builder(mediaItem)
                .setRemoveAudio(removeAudio)
                .setRemoveVideo(removeVideo)
                .setEffects(new Effects(audioProcessors, videoEffects))
                .build();
    }

    static List<Effect> createVideoEffects(EditConfig config) {
        List<Effect> videoEffects = createGeometryEffects(config);
        addVideoAppearance(config, videoEffects);
        return videoEffects;
    }

    static List<Effect> createGeometryEffects(EditConfig config) {
        List<Effect> videoEffects = new ArrayList<>();
        // Media3 decodes source rotation before effects. NDC's Y axis points upwards.
        videoEffects.add(new Crop(
                2f * config.cropLeft - 1f, 2f * config.cropRight - 1f,
                1f - 2f * config.cropBottom, 1f - 2f * config.cropTop));
        videoEffects.add(new ScaleAndRotateTransformation.Builder()
                .setRotationDegrees(-config.rotationDegrees)
                .build());
        if (config.outputHeight != 0) {
            videoEffects.add(Presentation.createForHeight(config.outputHeight));
        }
        videoEffects.add(new EvenPixelSize());
        return videoEffects;
    }

    private static void addVideoAppearance(EditConfig config, List<Effect> videoEffects) {
        if (!config.colorAdjustment.isIdentity()) {
            videoEffects.add(new ColorAdjustmentEffect(config.colorAdjustment));
        }
        if (!config.overlayText.trim().isEmpty()) {
            SpannableString text = new SpannableString(config.overlayText);
            text.setSpan(new ForegroundColorSpan(Color.WHITE), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new BackgroundColorSpan(0x99000000), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new AbsoluteSizeSpan(48), 0, text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            videoEffects.add(new OverlayEffect(
                    Collections.singletonList(TextOverlay.createStaticTextOverlay(text))));
        }
        if (config.watermark != null) {
            videoEffects.add(new OverlayEffect(
                    Collections.singletonList(new PngWatermarkOverlay(config.watermark))));
        }
        if (config.border.appliesTo(false)) videoEffects.add(BorderOverlay.effect(config.border));
        if (config.speed != 1f) {
            videoEffects.add(new SpeedChangeEffect(config.speed));
        }
    }

    private static List<AudioProcessor> createAudioProcessors(EditConfig config) {
        List<AudioProcessor> audioProcessors = new ArrayList<>();
        if (config.speed != 1f) {
            if (config.sourceGain() != 0f && config.replacementMusic == null) {
                audioProcessors.add(new PitchPreservingAudioProcessor(config.speed));
            }
        }
        if (config.replacementMusic == null && config.sourceGain() != 0f && config.sourceGain() != 1f) {
            audioProcessors.add(new GainAudioProcessor(config.sourceGain()));
        }
        return audioProcessors;
    }

    private void pollProgress(ExportJob job) {
        if (activeJob != job) {
            return;
        }
        int percent;
        try {
            int state = job.transformer.getProgress(job.progress);
            int progress = job.progress.progress;
            if (job.intermediate != null) {
                progress = job.musicPass ? 70 + progress * 30 / 100 : progress * 70 / 100;
            }
            percent = state == Transformer.PROGRESS_STATE_AVAILABLE
                    ? Math.max(job.lastProgress, Math.min(99, progress))
                    : Math.max(0, job.lastProgress);
        } catch (RuntimeException error) {
            fail(job, error);
            return;
        }
        mainHandler.postDelayed(job.pollProgress, PROGRESS_INTERVAL_MS);
        job.listener.onElapsed(elapsedMs(job.startedNs), job.musicPass ? "music-transmux" : "edit");
        if (percent != job.lastProgress) {
            job.lastProgress = percent;
            job.listener.onProgress(percent);
        }
    }

    private void complete(ExportJob job) {
        if (activeJob != job) {
            return;
        }
        long cleanupStartedNs = System.nanoTime();
        try {
            if (!job.output.isFile() || job.output.length() == 0) {
                throw new IOException("Transformer produced no output");
            }
            deleteWorkFiles(job);
            diagnostic(job, "timing stage=export-completed cleanupMs=" + elapsedMs(cleanupStartedNs)
                    + " exportTotalMs=" + elapsedMs(job.startedNs));
        } catch (IOException | RuntimeException error) {
            fail(job, error);
            return;
        }
        detach(job);
        // Transformer releases codecs, GL resources and audio processors before completion.
        job.transformer = null;
        try {
            job.listener.onProgress(100);
        } finally {
            job.listener.onCompleted(job.output);
        }
    }

    private void fail(ExportJob job, Exception error) {
        if (activeJob != job) {
            return;
        }
        long cleanupStartedNs = System.nanoTime();
        try {
            diagnostic(job, "failed pass=" + (job.musicPass ? "music-transmux" : "edit")
                    + " exportElapsedMs=" + elapsedMs(job.startedNs)
                    + " passElapsedMs=" + (job.passStartedNs == 0 ? 0 : elapsedMs(job.passStartedNs))
                    + " cause=" + error);
            logTimelineTrace(job, "failed-before-cancel");
        } catch (RuntimeException saving) {
            if (saving != error) error.addSuppressed(saving);
        }
        detach(job);
        if (job.transformer != null) {
            try {
                job.transformer.cancel();
            } catch (RuntimeException releaseError) {
                error.addSuppressed(releaseError);
            } finally {
                job.transformer = null;
            }
        }
        if (job.ownsOutput) {
            try {
                if (!job.output.delete() && job.output.exists()) {
                    error.addSuppressed(new IOException("Could not delete partial output"));
                }
            } catch (SecurityException deleteError) {
                error.addSuppressed(deleteError);
            }
        }
        try {
            deleteWorkFiles(job);
        } catch (IOException | RuntimeException cleanupError) {
            error.addSuppressed(cleanupError);
        }
        try {
            diagnostic(job, "timing stage=export-failed cleanupMs=" + elapsedMs(cleanupStartedNs)
                    + " exportTotalMs=" + elapsedMs(job.startedNs));
        } catch (RuntimeException saving) {
            if (saving != error) error.addSuppressed(saving);
        }
        job.listener.onError(error);
    }

    private static void deleteWorkFiles(ExportJob job) throws IOException {
        IOException error = null;
        for (File file : new File[]{job.intermediate, job.titleImage, job.delayedAudioSilence, job.libraryMusic}) {
            try {
                if (file != null && !file.delete() && file.exists()) {
                    throw new IOException("Could not delete composition work file: " + file.getName());
                }
            } catch (IOException | SecurityException failure) {
                if (error == null) error = new IOException("Could not clean composition work files");
                error.addSuppressed(failure);
            }
        }
        for (File file : job.silenceAudioGaps) {
            try {
                if (!file.delete() && file.exists()) {
                    throw new IOException("Could not delete merge audio gap file: " + file.getName());
                }
            } catch (IOException | SecurityException failure) {
                if (error == null) error = new IOException("Could not clean composition work files");
                error.addSuppressed(failure);
            }
        }
        if (error != null) throw error;
    }

    private void detach(ExportJob job) {
        mainHandler.removeCallbacks(job.pollProgress);
        activeJob = null;
        if (job.enabledTracing) DebugTraceUtil.enableTracing = false;
    }

    private static void logTimelineTrace(ExportJob job, String stage) {
        if (!job.traceRequested || job.transformer == null) return;
        // Includes decoder input/render, effect/GL timestamps, encoder EOS and muxer samples/EOS.
        // Codec output polling may log the same buffer more than once; it is not a frame counter.
        String summary = DebugTraceUtil.generateTraceSummary();
        if (summary.length() > 96_000) {
            summary = summary.substring(0, 48_000) + "\n[trace middle omitted: "
                    + (summary.length() - 96_000) + " chars]\n"
                    + summary.substring(summary.length() - 48_000);
        }
        diagnostic(job, "timelineTrace stage=" + stage + " pass="
                + (job.musicPass ? "music-transmux" : "edit") + "\n" + summary);
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000;
    }

    private static void diagnostic(ExportJob job, String message) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            java.util.concurrent.CountDownLatch delivered = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicReference<RuntimeException> failure =
                    new java.util.concurrent.atomic.AtomicReference<>();
            new Handler(Looper.getMainLooper()).post(() -> {
                try { diagnostic(job, message); }
                catch (RuntimeException error) { failure.set(error); }
                finally { delivered.countDown(); }
            });
            try { delivered.await(); }
            catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Preparation cancelled while reporting diagnostics");
            }
            if (failure.get() != null) throw failure.get();
            return;
        }
        // Bound checkpoint size/line length without truncating selected native MediaFormats.
        for (int offset = 0; offset < message.length(); offset += 12_000) {
            String part = message.substring(offset, Math.min(message.length(), offset + 12_000));
            StringBuilder lines = new StringBuilder();
            if (message.length() > 12_000) {
                lines.append("diagnostic part=").append(offset / 12_000 + 1)
                        .append('/').append((message.length() + 11_999) / 12_000).append('\n');
            }
            for (int line = 0; line < part.length(); line += 2000) {
                String text = part.substring(line, Math.min(part.length(), line + 2000));
                Log.i(TAG, text);
                lines.append(text).append('\n');
            }
            job.listener.onDiagnostic(lines.toString());
        }
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("export must be called on the main looper");
        }
    }

    private final class ExportJob {
        final File output;
        final Listener listener;
        final boolean softwareOnly;
        Thread preparer;
        boolean preparing;
        boolean cancelRequested;
        final long startedNs = System.nanoTime();
        long passStartedNs;
        final ProgressHolder progress = new ProgressHolder();
        final Runnable pollProgress = () -> pollProgress(this);
        Transformer transformer;
        File intermediate;
        File titleImage;
        File delayedAudioSilence;
        Uri pendingMusic;
        File libraryMusic;
        BackgroundMusic backgroundMusic;
        long pendingMusicDurationUs = C.TIME_UNSET;
        long libraryLoopDurationUs = C.TIME_UNSET;
        boolean musicPass;
        boolean ownsOutput;
        boolean traceRequested;
        boolean enabledTracing;
        float encoderFrameRate = Format.NO_VALUE;
        String selectedVideoBackend;
        int lastProgress = -1;
        final List<File> silenceAudioGaps = new ArrayList<>();

        ExportJob(File output, Listener listener, boolean softwareOnly) {
            this.output = output;
            this.listener = listener;
            this.softwareOnly = softwareOnly;
        }
    }

    private static final class EvenPixelSize implements MatrixTransformation {
        private final Matrix identity = new Matrix();

        @Override
        public Size configure(int width, int height) {
            // Crop and arbitrary rotation can produce odd dimensions, rejected by AVC encoders.
            // Keep the entire selected rectangle; the documented alignment is at most one pixel.
            return new Size((width + 1) & ~1, (height + 1) & ~1);
        }

        @Override
        public Matrix getMatrix(long presentationTimeUs) {
            return identity;
        }

        @Override
        public boolean isNoOp(int width, int height) {
            return (width & 1) == 0 && (height & 1) == 0;
        }
    }

    private static final class StrictEncoderFactory implements Codec.EncoderFactory {
        private final DefaultEncoderFactory delegate;
        private final Context context;
        private final boolean forceVideoEncoding;
        private final float frameRate;
        private final boolean softwareOnly;
        private final ExactSizeVideoEncoder.Diagnostics diagnostics;

        StrictEncoderFactory(Context context, boolean forceVideoEncoding, float frameRate,
                             boolean softwareOnly, ExactSizeVideoEncoder.Diagnostics diagnostics) {
            this.context = context;
            delegate = new DefaultEncoderFactory.Builder(context)
                    .setRequestedVideoEncoderSettings(videoEncoderSettings())
                    .setEnableFallback(false).build();
            this.forceVideoEncoding = forceVideoEncoding;
            this.frameRate = frameRate;
            this.softwareOnly = softwareOnly;
            this.diagnostics = diagnostics;
        }

        @Override
        public Codec createForAudioEncoding(Format format) throws ExportException {
            Codec codec = delegate.createForAudioEncoding(format);
            Format actual = codec.getConfigurationFormat();
            if (actual.sampleRate != format.sampleRate || actual.channelCount != format.channelCount) {
                codec.release();
                throw new IllegalStateException("Encoder changed the requested audio format");
            }
            return codec;
        }

        @Override
        public Codec createForVideoEncoding(Format format) throws ExportException {
            // Media3 1.5.1 uses the first input's rate, ignoring SpeedChangeEffect and later clips.
            // This is a rate-control hint only; frame presentation timestamps remain untouched.
            if (frameRate != Format.NO_VALUE) {
                format = format.buildUpon().setFrameRate(frameRate).build();
            }
            if (softwareOnly && ColorInfo.isTransferHdr(format.colorInfo)) {
                throw ExportException.createForCodec(
                        new IllegalArgumentException("兼容编码不支持 HDR / Software AVC requires SDR"),
                        ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                        new ExportException.CodecInfo(format.toString(), true, false, null));
            }
            // Default mode retains Media3's HDR negotiation; compatibility never delegates video.
            Codec codec;
            try {
                codec = ColorInfo.isTransferHdr(format.colorInfo)
                        ? delegate.createForVideoEncoding(format)
                        : ExactSizeVideoEncoder.create(context, format, softwareOnly, diagnostics);
            } catch (ExactSizeVideoEncoder.DiagnosticDeliveryException error) {
                // Media3's GL onOutputSizeChanged callback handles ExportException, not runtime.
                throw ExportException.createForUnexpected(error);
            }
            Format actual = codec.getConfigurationFormat();
            if (actual.width != format.width || actual.height != format.height) {
                codec.release();
                throw new IllegalStateException("Encoder changed the requested dimensions");
            }
            return codec;
        }

        @Override
        public boolean videoNeedsEncoding() {
            // Even trim-only exports must decode preroll and discard frames before the trim point.
            return forceVideoEncoding;
        }
    }

    static VideoEncoderSettings videoEncoderSettings() {
        // The default low-bitrate heuristic visibly discards detail in small edited canvases.
        // Use Media3's codec-aware quality policy, not a fixture-specific bitrate or profile.
        return new VideoEncoderSettings.Builder()
                .experimentalSetEnableHighQualityTargeting(true)
                .build();
    }

    static float encodingFrameRate(EditConfig config, float mainFrameRate, float introFrameRate) {
        float rate = knownFrameRateOrDefault(mainFrameRate) * config.speed;
        if (introUri(config) != null || !config.appendedVideos.isEmpty()) {
            rate = Math.max(rate, knownFrameRateOrDefault(introFrameRate));
        }
        if (config.introTitle != null) rate = Math.max(rate, 30f);
        return rate;
    }

    private static float maxKnownFrameRate(float left, float right) {
        if (!(left > 0f) || Float.isInfinite(left)) return right;
        if (!(right > 0f) || Float.isInfinite(right)) return left;
        return Math.max(left, right);
    }

    private static float knownFrameRateOrDefault(float rate) {
        return rate > 0f && !Float.isInfinite(rate) ? rate : 30f;
    }

    private float probeVideoFrameRate(Uri input) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            VideoSource.setDataSource(context, extractor, input);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("video/")) continue;
                if (!format.containsKey(MediaFormat.KEY_FRAME_RATE)) return Format.NO_VALUE;
                try {
                    return format.getInteger(MediaFormat.KEY_FRAME_RATE);
                } catch (ClassCastException notInteger) {
                    return format.getFloat(MediaFormat.KEY_FRAME_RATE);
                }
            }
            return Format.NO_VALUE;
        } catch (RuntimeException error) {
            throw new IOException("Cannot inspect source video frame rate", error);
        } finally {
            extractor.release();
        }
    }

    static final class GainAudioProcessor extends BaseAudioProcessor {
        private final float gain;

        GainAudioProcessor(float gain) {
            this.gain = gain;
        }

        @Override
        protected AudioFormat onConfigure(AudioFormat format) throws UnhandledAudioFormatException {
            if (format.encoding != C.ENCODING_PCM_16BIT && format.encoding != C.ENCODING_PCM_FLOAT) {
                throw new UnhandledAudioFormatException(format);
            }
            return format;
        }

        @Override
        public void queueInput(ByteBuffer input) {
            int sampleBytes = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT ? 4 : 2;
            if (input.remaining() % sampleBytes != 0) {
                throw new IllegalArgumentException("Incomplete PCM sample");
            }
            input.order(ByteOrder.nativeOrder());
            ByteBuffer output = replaceOutputBuffer(input.remaining());
            while (input.hasRemaining()) {
                if (sampleBytes == 4) {
                    output.putFloat(Math.max(-1f, Math.min(1f, input.getFloat() * gain)));
                } else {
                    int sample = Math.round(input.getShort() * gain);
                    output.putShort((short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample)));
                }
            }
            output.flip();
        }
    }

    private SourceAudioFormat probeSourceAudioFormat(Uri input) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        try {
            VideoSource.setDataSource(context, extractor, input);
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat format = extractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime == null || !mime.startsWith("audio/")) continue;
                if (!format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                        || !format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                    throw new IOException("Source audio format is missing sample rate or channel count");
                }
                int sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                int channelCount = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                if (sampleRate <= 0 || channelCount <= 0) {
                    throw new IOException("Source audio format has invalid sample rate or channel count");
                }
                return new SourceAudioFormat(sampleRate, channelCount);
            }
            return null;
        } catch (IOException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IOException("Cannot inspect source audio format", error);
        } finally {
            extractor.release();
        }
    }

    private static void writeSilentPcmWav(File output, int durationMs,
                                          SourceAudioFormat format) throws IOException {
        if (durationMs <= 0) {
            throw new IllegalArgumentException("Intro audio delay must be positive");
        }
        long frameCount = Math.round((double) durationMs * format.sampleRate / 1000d);
        long dataBytes = frameCount * format.channelCount * 2L;
        long byteRate = (long) format.sampleRate * format.channelCount * 2L;
        if (dataBytes > 0xFFFFFFFFL - 36L) {
            throw new IOException("Intro audio delay is too large for PCM WAV");
        }
        if (byteRate > Integer.MAX_VALUE) {
            throw new IOException("Source audio format byte rate is unsupported");
        }
        try (FileOutputStream stream = new FileOutputStream(output)) {
            ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
            header.put("RIFF".getBytes(StandardCharsets.US_ASCII));
            header.putInt((int) (36L + dataBytes));
            header.put("WAVE".getBytes(StandardCharsets.US_ASCII));
            header.put("fmt ".getBytes(StandardCharsets.US_ASCII));
            header.putInt(16);
            header.putShort((short) 1);
            header.putShort((short) format.channelCount);
            header.putInt(format.sampleRate);
            header.putInt((int) byteRate);
            header.putShort((short) (format.channelCount * 2));
            header.putShort((short) 16);
            header.put("data".getBytes(StandardCharsets.US_ASCII));
            header.putInt((int) dataBytes);
            stream.write(header.array());
            byte[] silence = new byte[(int) Math.min(8192L, Math.max(1L, dataBytes))];
            for (long remaining = dataBytes; remaining > 0; remaining -= silence.length) {
                stream.write(silence, 0, (int) Math.min(silence.length, remaining));
            }
        }
    }

    private static final class SourceAudioFormat {
        final int sampleRate;
        final int channelCount;

        SourceAudioFormat(int sampleRate, int channelCount) {
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
        }
    }
}
