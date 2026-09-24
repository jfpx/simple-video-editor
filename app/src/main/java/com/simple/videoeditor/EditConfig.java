package com.simple.videoeditor;

import android.net.Uri;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Immutable edit coordinates refer to the display-oriented source, before user rotation. */
public final class EditConfig {
    public static final int MAX_APPENDED_VIDEO_COUNT = 5;
    public static final int MAX_TOTAL_MAIN_AND_EXTRA_COUNT = 6;
    public static final long MAX_VIDEO_BYTES = 128L * 1024L * 1024L;
    public static final long MAX_AGGREGATE_VIDEO_BYTES = 256L * 1024L * 1024L;
    public static final long MAX_MERGED_DURATION_MS = 120_000L;
    public static final int MAX_VIDEO_DIMENSION = 4096;

    public final Uri input;
    public final VideoSource inputSource;
    public final long sourceDurationMs;
    public final long startMs;
    public final long endMs;
    public final float cropLeft;
    public final float cropTop;
    public final float cropRight;
    public final float cropBottom;
    public final int rotationDegrees;
    public final int outputHeight;
    public final float speed;
    public final float volume;
    public final String overlayText;
    public final Watermark watermark;
    public final VideoBorder border;
    public final ColorAdjustment colorAdjustment;
    public final Uri intro;
    public final ImportedVideo introVideo;
    public final IntroTitle introTitle;
    final TitleBackground titleBackground;
    public final Uri replacementMusic;
    public final BackgroundMusic backgroundMusic;
    public final int sourceWidth;
    public final int sourceHeight;
    public final boolean mainHasAudio;
    public final long mainFileSizeBytes;
    public final boolean mergeEnabled;
    public final List<ImportedVideo> appendedVideos;

    private EditConfig(Builder builder) {
        input = Objects.requireNonNull(builder.input, "input");
        inputSource = builder.inputSource;
        sourceDurationMs = builder.sourceDurationMs;
        startMs = builder.startMs;
        endMs = builder.endMs;
        cropLeft = builder.cropLeft;
        cropTop = builder.cropTop;
        cropRight = builder.cropRight;
        cropBottom = builder.cropBottom;
        rotationDegrees = ((builder.rotationDegrees % 360) + 360) % 360;
        outputHeight = builder.outputHeight;
        speed = builder.speed;
        volume = builder.volume;
        overlayText = Objects.requireNonNull(builder.overlayText, "overlayText");
        watermark = builder.watermark;
        intro = builder.intro;
        introVideo = builder.introVideo;
        introTitle = builder.introTitle;
        titleBackground = builder.titleBackground;
        border = builder.border;
        colorAdjustment = builder.colorAdjustment;
        border.validateTitle(introTitle != null);
        replacementMusic = builder.replacementMusic;
        backgroundMusic = Objects.requireNonNull(builder.backgroundMusic, "backgroundMusic");
        if (backgroundMusic.enabled && replacementMusic != null) {
            throw new IllegalArgumentException("Library mix and imported replacement are mutually exclusive");
        }
        sourceWidth = builder.sourceWidth;
        sourceHeight = builder.sourceHeight;
        mainHasAudio = builder.mainHasAudio;
        mainFileSizeBytes = builder.mainFileSizeBytes;
        appendedVideos = Collections.unmodifiableList(new ArrayList<>(builder.appendedVideos));
        mergeEnabled = builder.mergeEnabled || !appendedVideos.isEmpty();
        if (titleBackground != null && (introTitle == null || !introTitle.sourceFrameBackground
                || !titleBackground.key.equals(TitleBackground.key(this, introTitle.sourceFrameTimeMs)))) {
            throw new IllegalArgumentException("Title background does not match the original source/time/geometry");
        }
        if (inputSource != null && !input.equals(inputSource.uri)) {
            throw new IllegalArgumentException("inputSource must reference the same input Uri");
        }
        if (introTitle != null && introTitle.sourceFrameBackground && !input.equals(Uri.EMPTY)
                && introTitle.sourceFrameTimeMs >= sourceDurationMs)
            throw new IllegalArgumentException("Title frame time must be before original source duration");

        if (sourceDurationMs <= 0 || sourceDurationMs > Long.MAX_VALUE / 1000) {
            throw new IllegalArgumentException("sourceDurationMs must be positive and fit microseconds");
        }
        if (startMs < 0 || startMs >= endMs || endMs > sourceDurationMs) {
            throw new IllegalArgumentException("Trim must satisfy 0 <= startMs < endMs <= sourceDurationMs");
        }
        requireRange(cropLeft, 0f, 1f, "cropLeft");
        requireRange(cropTop, 0f, 1f, "cropTop");
        requireRange(cropRight, 0f, 1f, "cropRight");
        requireRange(cropBottom, 0f, 1f, "cropBottom");
        if (cropLeft >= cropRight || cropTop >= cropBottom) {
            throw new IllegalArgumentException("Crop must have positive width and height");
        }
        if (outputHeight != 0 && (outputHeight < 2 || outputHeight > 4320)) {
            throw new IllegalArgumentException("outputHeight must be 0 (original) or 2..4320 pixels");
        }
        if (mergeEnabled && outputHeight > MAX_VIDEO_DIMENSION) {
            throw new IllegalArgumentException("outputHeight must be at most 4096 pixels");
        }
        requireRange(speed, .5f, 2f, "speed");
        requireRange(volume, 0f, 3f, "volume");
        if ((intro != null || introTitle != null || mergeEnabled)
                && (sourceWidth <= 0 || sourceHeight <= 0)) {
            throw new IllegalArgumentException(
                    "Imported clips or titles require the main video's display-oriented sourceSize");
        }
        if (mainFileSizeBytes < -1) {
            throw new IllegalArgumentException("mainFileSizeBytes must be -1 (unknown) or non-negative");
        }
        if (introVideo != null && intro != null && !introVideo.uri.equals(intro)) {
            throw new IllegalArgumentException("intro and introVideo must reference the same clip");
        }
        if (mergeEnabled) {
            if (intro != null && introVideo == null) {
                throw new IllegalArgumentException("Merge requires immutable imported intro metadata");
            }
            if (appendedVideos.isEmpty()) {
                throw new IllegalArgumentException("Merge mode requires at least one appended clip");
            }
            if ((sourceWidth > MAX_VIDEO_DIMENSION || sourceHeight > MAX_VIDEO_DIMENSION)
                    && (sourceWidth > 0 && sourceHeight > 0)) {
                throw new IllegalArgumentException("Main display size must be at most 4096×4096 for merge");
            }
            if (mainFileSizeBytes <= 0) {
                throw new IllegalArgumentException("Appended clips require a known bounded main snapshot size");
            }
            if (mainFileSizeBytes > MAX_VIDEO_BYTES) {
                throw new IllegalArgumentException("Main clip exceeds the 128 MiB snapshot limit for merge");
            }
            if (appendedVideos.size() > MAX_APPENDED_VIDEO_COUNT) {
                throw new IllegalArgumentException("At most 5 appended clips are supported");
            }
            if (1 + appendedVideos.size() > MAX_TOTAL_MAIN_AND_EXTRA_COUNT) {
                throw new IllegalArgumentException("At most 6 total main+appended clips are supported");
            }
            requireMergeImportedVideo(introVideo, "Imported intro");
            for (ImportedVideo clip : appendedVideos) {
                Objects.requireNonNull(clip, "Appended clip must not be null");
                requireMergeImportedVideo(clip, "Appended clip");
            }
            long aggregateVideoBytes = mainFileSizeBytes;
            if (introVideo != null) {
                aggregateVideoBytes += introVideo.fileSizeBytes;
            }
            for (ImportedVideo clip : appendedVideos) {
                aggregateVideoBytes += clip.fileSizeBytes;
            }
            if (aggregateVideoBytes > MAX_AGGREGATE_VIDEO_BYTES) {
                throw new IllegalArgumentException("Imported video snapshots exceed the 256 MiB aggregate limit");
            }
            long mergedDurationMs = Math.round((endMs - startMs) / (double) speed)
                    + (introTitle == null ? 0 : introTitle.durationMs)
                    + (introVideo == null ? 0 : introVideo.durationMs);
            for (ImportedVideo clip : appendedVideos) {
                mergedDurationMs += clip.durationMs;
            }
            if (mergedDurationMs > MAX_MERGED_DURATION_MS) {
                throw new IllegalArgumentException("Merged sequence exceeds the 120 second limit");
            }
        }
    }

    private static void requireRange(float value, float min, float max, String name) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be finite and in [" + min + ", " + max + "]");
        }
    }

    float sourceGain() {
        return backgroundMusic.enabled && backgroundMusic.muteOriginal ? 0f : volume;
    }

    private static void requireMergeImportedVideo(ImportedVideo clip, String label) {
        if (clip == null) return;
        if (clip.fileSizeBytes <= 0) throw new IllegalArgumentException(label + " requires a known bounded size for merge");
        if (clip.durationMs > MAX_MERGED_DURATION_MS) {
            throw new IllegalArgumentException(label + " exceeds the 120 second limit");
        }
        if (clip.width > MAX_VIDEO_DIMENSION || clip.height > MAX_VIDEO_DIMENSION) {
            throw new IllegalArgumentException(label + " exceeds the 4096 px display-size limit");
        }
        if (clip.fileSizeBytes > MAX_VIDEO_BYTES) {
            throw new IllegalArgumentException(label + " exceeds the 128 MiB snapshot limit");
        }
    }

    @Override
    public String toString() {
        return "EditConfig{input=" + input + ", inputSource=" + (inputSource == null ? "none" : inputSource.name)
                + ", sourceDurationMs=" + sourceDurationMs
                + ", startMs=" + startMs + ", endMs=" + endMs
                + ", cropLeft=" + cropLeft + ", cropTop=" + cropTop
                + ", cropRight=" + cropRight + ", cropBottom=" + cropBottom
                + ", rotationDegrees=" + rotationDegrees + ", outputHeight=" + outputHeight
                + ", pixelAlignment=roundUpToEven, speed=" + speed + ", volume=" + volume + ", overlayText='"
                + overlayText + "', intro=" + intro + ", introTitle="
                + (introTitle == null ? "none" : introTitle.durationMs + "ms")
                + ", replacementMusic=" + replacementMusic
                + ", bgm={" + backgroundMusic + "}"
                + ", watermark=" + watermark
                + ", border={" + border + "}"
                + ", colorAdjustment={" + colorAdjustment + "}"
                + ", sourceSize=" + sourceWidth + "x" + sourceHeight
                + ", mainHasAudio=" + mainHasAudio
                + ", mainFileSizeBytes=" + mainFileSizeBytes
                + ", mergeEnabled=" + mergeEnabled
                + ", appendedVideos=" + appendedVideos + "}";
    }

    public static final class ImportedVideo {
        public final Uri uri;
        public final VideoSource source;
        public final long durationMs;
        public final int width;
        public final int height;
        public final boolean hasAudio;
        public final long fileSizeBytes;

        public ImportedVideo(Uri uri, long durationMs, int width, int height,
                             boolean hasAudio, long fileSizeBytes) {
            this(uri, durationMs, width, height, hasAudio, fileSizeBytes, null);
        }

        public ImportedVideo(Uri uri, long durationMs, int width, int height,
                             boolean hasAudio, long fileSizeBytes, VideoSource source) {
            this.uri = Objects.requireNonNull(uri, "uri");
            this.source = source;
            if (source != null && !uri.equals(source.uri)) throw new IllegalArgumentException("Imported source Uri mismatch");
            this.durationMs = durationMs;
            this.width = width;
            this.height = height;
            this.hasAudio = hasAudio;
            this.fileSizeBytes = fileSizeBytes;
            if (durationMs <= 0 || durationMs > Long.MAX_VALUE / 1000L) {
                throw new IllegalArgumentException("Imported clip duration must be positive and fit microseconds");
            }
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("Imported clip display size must be positive");
            }
            if (fileSizeBytes < -1 || fileSizeBytes == 0) {
                throw new IllegalArgumentException("Imported clip fileSizeBytes must be positive or unknown");
            }
        }

        @Override
        public String toString() {
            return "ImportedVideo{uri=" + uri + ", durationMs=" + durationMs
                    + ", size=" + width + "x" + height + ", hasAudio=" + hasAudio
                    + ", fileSizeBytes=" + fileSizeBytes + "}";
        }
    }

    /** Immutable title-card snapshot; placed before an imported intro and the edited main video. */
    public static final class IntroTitle {
        public final String text;
        public final int textSizeSp, textColor, backgroundColor, durationMs;
        public final float textX, textY;
        public final String fontStyle;
        public final String animation, fontFamily, alignment, layout;
        public final int gradientColor;
        public final boolean sourceFrameBackground;
        public final long sourceFrameTimeMs;

        private IntroTitle(IntroTemplate template, String text) {
            this.text = Objects.requireNonNull(text, "intro text");
            textSizeSp = template.getTextSize();
            textColor = template.getTextColor();
            backgroundColor = template.getBackgroundColor();
            durationMs = template.getDurationMs();
            textX = template.getTextX();
            textY = template.getTextY();
            fontStyle = Objects.requireNonNull(template.getFontStyle(), "intro font style");
            animation = template.getAnimation();
            fontFamily = template.getFontFamily();
            alignment = template.getAlignment();
            layout = template.getLayout();
            gradientColor = template.getGradientColor();
            sourceFrameBackground = template.hasSourceFrameBackground();
            sourceFrameTimeMs = sourceFrameBackground
                    ? TitleBackground.parseTime(template.getSourceFrameSeconds(), Long.MAX_VALUE / 1000) : 0;
            if (!java.util.Arrays.asList("legacy", "fade", "slide", "typewriter", "scale", "lower-third", "dissolve").contains(animation)
                    || !java.util.Arrays.asList("sans-serif", "serif", "monospace").contains(fontFamily)
                    || !java.util.Arrays.asList("left", "center", "right").contains(alignment)
                    || !java.util.Arrays.asList("legacy-sp", "canvas").contains(layout)) {
                throw new IllegalArgumentException("Unsupported title animation, font or alignment");
            }
            if (text.length() > 512 || text.split("\\n", -1).length > 8 || textSizeSp > 160) {
                throw new IllegalArgumentException("Titles allow at most 512 characters, 8 explicit lines, size 1–160");
            }
            if (durationMs <= 0 || textSizeSp <= 0) {
                throw new IllegalArgumentException("Intro duration and text size must be positive");
            }
            requireRange(textX, 0f, 1f, "intro textX");
            requireRange(textY, 0f, 1f, "intro textY");
            if (!fontStyle.equals("normal") && !fontStyle.equals("bold")
                    && !fontStyle.equals("italic") && !fontStyle.equals("bold_italic")) {
                throw new IllegalArgumentException("Unsupported intro font style: " + fontStyle);
            }
        }
    }

    public static final class Watermark {
        public final PngWatermark image;
        public final float widthFraction, x, y;

        private Watermark(PngWatermark image, float widthFraction, float x, float y) {
            this.image = Objects.requireNonNull(image, "watermark image");
            requireRange(widthFraction, .05f, .5f, "watermark width");
            requireRange(x, 0f, 1f, "watermark X");
            requireRange(y, 0f, 1f, "watermark Y");
            this.widthFraction = widthFraction;
            this.x = x;
            this.y = y;
        }

        @Override public String toString() {
            return image.width + "x" + image.height + ", width=" + widthFraction + ", x=" + x + ", y=" + y;
        }
    }

    public static final class Builder {
        private final Uri input;
        private VideoSource inputSource;
        private final long sourceDurationMs;
        private long startMs;
        private long endMs;
        private float cropLeft;
        private float cropTop;
        private float cropRight = 1f;
        private float cropBottom = 1f;
        private int rotationDegrees;
        private int outputHeight;
        private float speed = 1f;
        private float volume = 1f;
        private String overlayText = "";
        private Watermark watermark;
        private VideoBorder border = VideoBorder.OFF;
        private ColorAdjustment colorAdjustment = ColorAdjustment.OFF;
        private Uri intro;
        private ImportedVideo introVideo;
        private IntroTitle introTitle;
        private TitleBackground titleBackground;
        private Uri replacementMusic;
        private BackgroundMusic backgroundMusic = BackgroundMusic.OFF;
        private int sourceWidth;
        private int sourceHeight;
        private boolean mainHasAudio = true;
        private long mainFileSizeBytes;
        private boolean mergeEnabled;
        private List<ImportedVideo> appendedVideos = Collections.emptyList();

        public Builder(Uri input, long sourceDurationMs) {
            this.input = Objects.requireNonNull(input, "input");
            this.sourceDurationMs = sourceDurationMs;
            endMs = sourceDurationMs;
        }

        public Builder trim(long startMs, long endMs) {
            this.startMs = startMs;
            this.endMs = endMs;
            return this;
        }

        public Builder inputSource(VideoSource source) {
            inputSource = source;
            return this;
        }

        public Builder crop(float left, float top, float right, float bottom) {
            cropLeft = left;
            cropTop = top;
            cropRight = right;
            cropBottom = bottom;
            return this;
        }

        public Builder rotation(int clockwiseDegrees) {
            rotationDegrees = clockwiseDegrees;
            return this;
        }

        public Builder outputHeight(int height) {
            outputHeight = height;
            return this;
        }

        public Builder speed(float speed) {
            this.speed = speed;
            return this;
        }

        public Builder volume(float volume) {
            this.volume = volume;
            return this;
        }

        public Builder overlayText(String text) {
            overlayText = Objects.requireNonNull(text, "overlayText");
            return this;
        }

        public Builder watermark(PngWatermark image, float widthFraction, float x, float y) {
            watermark = image == null ? null : new Watermark(image, widthFraction, x, y);
            return this;
        }

        public Builder border(VideoBorder border) {
            this.border = Objects.requireNonNull(border, "border");
            return this;
        }

        /** Selection-time metadata; direct documents are rechecked before export. */
        public Builder mainSourceMetadata(boolean hasAudio, long fileSizeBytes) {
            mainHasAudio = hasAudio;
            mainFileSizeBytes = fileSizeBytes;
            return this;
        }

        /** Main dimensions after metadata rotation and pixel-aspect expansion, before user edits. */
        public Builder sourceSize(int width, int height) {
            if (width <= 0 || height <= 0) {
                throw new IllegalArgumentException("sourceSize must be positive");
            }
            sourceWidth = width;
            sourceHeight = height;
            return this;
        }

        /** Prepends the complete intro at normal speed, fitted to the edited main video's canvas. */
        public Builder intro(Uri intro) {
            this.intro = intro;
            introVideo = null;
            return this;
        }

        /** Prepends the complete intro at normal speed using a validated immutable snapshot. */
        public Builder introVideo(ImportedVideo intro) {
            introVideo = intro;
            this.intro = intro == null ? null : intro.uri;
            return this;
        }

        /** Snapshots every template setting; null clears the generated title without clearing video. */
        public Builder introTemplate(IntroTemplate template, String text) {
            introTitle = template == null ? null : new IntroTitle(template, text);
            return this;
        }

        Builder titleBackground(TitleBackground background) {
            titleBackground = background;
            return this;
        }

        /** Replaces all source audio, looping from time zero at native speed/gain until video ends. */
        public Builder replacementMusic(Uri music) {
            replacementMusic = music;
            return this;
        }

        public Builder backgroundMusic(BackgroundMusic music) {
            backgroundMusic = Objects.requireNonNull(music, "backgroundMusic");
            return this;
        }

        public Builder colorAdjustment(ColorAdjustment adjustment) {
            colorAdjustment = Objects.requireNonNull(adjustment, "colorAdjustment");
            return this;
        }

        /** Enables merge-only validation even before appended clips are attached. */
        public Builder mergeMode(boolean enabled) {
            mergeEnabled = enabled;
            return this;
        }

        /** Appends zero to five complete clips after the edited main clip. */
        public Builder appendVideos(List<ImportedVideo> clips) {
            appendedVideos = clips == null ? Collections.emptyList() : new ArrayList<>(clips);
            return this;
        }

        public EditConfig build() {
            return new EditConfig(this);
        }
    }
}
