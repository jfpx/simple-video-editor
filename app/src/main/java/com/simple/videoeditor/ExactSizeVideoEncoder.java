package com.simple.videoeditor;

import android.content.Context;
import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaCodecInfo.EncoderCapabilities;
import android.media.MediaCodecInfo.VideoCapabilities;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;
import android.util.Log;

import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.transformer.AppEncoderQualityPolicy;
import androidx.media3.transformer.Codec;
import androidx.media3.transformer.DefaultCodec;
import androidx.media3.transformer.ExportException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Capability queries are advisory: only successful native configuration establishes usability. */
@UnstableApi
final class ExactSizeVideoEncoder {
    private static final String TAG = "ExactSizeVideoEncoder";
    private static final int[] PROFILES = {
            CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileMain,
            CodecProfileLevel.AVCProfileBaseline
    };
    private static final int[] LEVELS = {
            CodecProfileLevel.AVCLevel1, CodecProfileLevel.AVCLevel1b,
            CodecProfileLevel.AVCLevel11, CodecProfileLevel.AVCLevel12,
            CodecProfileLevel.AVCLevel13, CodecProfileLevel.AVCLevel2,
            CodecProfileLevel.AVCLevel21, CodecProfileLevel.AVCLevel22,
            CodecProfileLevel.AVCLevel3, CodecProfileLevel.AVCLevel31,
            CodecProfileLevel.AVCLevel32, CodecProfileLevel.AVCLevel4,
            CodecProfileLevel.AVCLevel41, CodecProfileLevel.AVCLevel42,
            CodecProfileLevel.AVCLevel5, CodecProfileLevel.AVCLevel51,
            CodecProfileLevel.AVCLevel52, CodecProfileLevel.AVCLevel6,
            CodecProfileLevel.AVCLevel61, CodecProfileLevel.AVCLevel62
    };

    interface Diagnostics {
        void log(String message);
    }

    static final class DiagnosticDeliveryException extends RuntimeException {
        DiagnosticDeliveryException(RuntimeException cause) {
            super("Encoder diagnostic delivery failed", cause);
        }
    }

    interface Creator {
        Codec create(Attempt attempt) throws ExportException;
    }

    static final class Attempt {
        final String name;
        final boolean software;
        final String policy;
        final Format format;
        final MediaFormat mediaFormat;

        Attempt(String name, boolean software, String policy, Format format, MediaFormat mediaFormat) {
            this.name = name;
            this.software = software;
            this.policy = policy;
            this.format = format;
            this.mediaFormat = mediaFormat;
        }

        String describe() {
            return "encoder=" + name + " software=" + software + " policy=" + policy
                    + " format=" + mediaFormat;
        }
    }

    static Codec create(Context context, Format requested) throws ExportException {
        return create(context, requested, message -> {});
    }

    static Codec create(Context context, Format requested, Diagnostics sink) throws ExportException {
        return create(context, requested, false, sink);
    }

    static Codec create(Context context, Format requested, boolean softwareOnly, Diagnostics sink)
            throws ExportException {
        Diagnostics diagnostics = message -> {
            for (int offset = 0; offset < message.length(); offset += 3000) {
                Log.i(TAG, message.substring(offset, Math.min(message.length(), offset + 3000)));
            }
            try {
                sink.log(message);
            } catch (RuntimeException saving) {
                // Capability-query catches must not misclassify a failed durable checkpoint.
                throw new DiagnosticDeliveryException(saving);
            }
        };
        long startedNs = System.nanoTime();
        StringBuilder capabilities = new StringBuilder();
        Diagnostics grouped = message -> {
            if (capabilities.length() + message.length() > 12_000 && capabilities.length() > 0) {
                diagnostics.log(capabilities.toString());
                capabilities.setLength(0);
            }
            capabilities.append(message).append('\n');
        };
        List<Attempt> attempts = candidates(requested,
                new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos(), softwareOnly, grouped);
        capabilities.append("candidate-discovery elapsedMs=").append(elapsedMs(startedNs))
                .append(" attempts=").append(attempts.size());
        diagnostics.log(capabilities.toString());
        return configure(attempts, attempt -> new DefaultCodec(context, attempt.format,
                attempt.mediaFormat, attempt.name, false, null), softwareOnly, diagnostics);
    }

    static List<Attempt> candidates(Format requested, MediaCodecInfo[] infos, Diagnostics diagnostics) {
        return candidates(requested, infos, false, diagnostics);
    }

    static List<Attempt> candidates(Format requested, MediaCodecInfo[] infos, boolean softwareOnly,
                                    Diagnostics diagnostics) {
        if (ColorInfo.isTransferHdr(requested.colorInfo)) {
            throw new IllegalArgumentException("兼容编码不支持 HDR / Exact-size AVC requires SDR, not HDR");
        }
        if (!MimeTypes.VIDEO_H264.equals(requested.sampleMimeType)
                || requested.width <= 0 || requested.height <= 0) {
            throw new IllegalArgumentException("Exact-size encoder requires positive-size SDR AVC");
        }
        float rate = requested.frameRate == Format.NO_VALUE ? 30f : requested.frameRate;
        if (!(rate > 0f) || Float.isInfinite(rate)) {
            throw new IllegalArgumentException("Invalid encoding frame rate: " + rate);
        }
        diagnostics.log("encodingMode=" + VideoEncodingSettings.modeName(softwareOnly)
                + " requested=" + requested + " codecs=" + requested.codecs
                + " exact=" + requested.width + "x" + requested.height + " rate=" + rate
                + " operating-rate=omitted priority=omitted; no rejected field presumed");
        List<MediaCodecInfo> ordered = new ArrayList<>(Arrays.asList(infos));
        Collections.sort(ordered, (left, right) -> {
            int softwareOrder = Boolean.compare(isSoftware(left), isSoftware(right));
            return softwareOrder != 0 ? softwareOrder : left.getName().compareTo(right.getName());
        });
        List<Attempt> attempts = new ArrayList<>();
        for (MediaCodecInfo info : ordered) {
            if (!info.isEncoder() || (Build.VERSION.SDK_INT >= 29 && info.isAlias())
                    || (softwareOnly && !isSoftware(info)) || !supportsAvc(info)) continue;
            try {
                addCandidates(requested, rate, info, attempts, diagnostics);
            } catch (IllegalArgumentException | IllegalStateException unavailable) {
                diagnostics.log("capability-query-rejected encoder=" + info.getName()
                        + " cause=" + unavailable);
            }
        }
        return Collections.unmodifiableList(attempts);
    }

    private static void addCandidates(Format requested, float rate, MediaCodecInfo info,
                                      List<Attempt> attempts, Diagnostics diagnostics) {
        CodecCapabilities caps = info.getCapabilitiesForType(MimeTypes.VIDEO_H264);
        VideoCapabilities video = caps.getVideoCapabilities();
        EncoderCapabilities encoder = caps.getEncoderCapabilities();
        boolean surface = false;
        for (int color : caps.colorFormats) surface |= color == CodecCapabilities.COLOR_FormatSurface;
        boolean exact = video != null && video.isSizeSupported(requested.width, requested.height)
                && video.areSizeAndRateSupported(requested.width, requested.height, rate);
        StringBuilder profiles = new StringBuilder();
        for (CodecProfileLevel pair : caps.profileLevels) {
            profiles.append(pair.profile).append('/').append(pair.level).append(' ');
        }
        diagnostics.log("capabilities encoder=" + info.getName() + " software=" + isSoftware(info)
                + " surface=" + surface + " exactSizeAndRate=" + exact
                + " colors=" + Arrays.toString(caps.colorFormats) + " profiles/levels=" + profiles
                + (video == null ? " video=null" : " widths=" + video.getSupportedWidths()
                + " heights=" + video.getSupportedHeights() + " alignment="
                + video.getWidthAlignment() + "x" + video.getHeightAlignment()
                + " rates=" + video.getSupportedFrameRates() + " bitrates=" + video.getBitrateRange())
                + " VBR=" + (encoder != null && encoder.isBitrateModeSupported(1))
                + " CBR=" + (encoder != null && encoder.isBitrateModeSupported(2))
                + " CQ=" + (encoder != null && encoder.isBitrateModeSupported(0)));
        if (!surface || !exact || encoder == null) return;
        int target = AppEncoderQualityPolicy.bitrate(info.getName(),
                requested.width, requested.height, rate);
        if (target <= 0) throw new IllegalArgumentException("Invalid quality bitrate: " + target);
        int bitrate = video.getBitrateRange().clamp(target);
        // Source codecs/CSD describe the input bitstream, not the newly encoded AVC stream.
        Format format = requested.buildUpon().setFrameRate(rate).setAverageBitrate(bitrate)
                .setCodecs(null).setInitializationData(Collections.emptyList()).build();
        int[] modes = {EncoderCapabilities.BITRATE_MODE_VBR, EncoderCapabilities.BITRATE_MODE_CBR};
        for (int mode : modes) {
            if (!encoder.isBitrateModeSupported(mode)) continue;
            if (Build.VERSION.SDK_INT >= 24) {
                for (int profile : profilesFor(Build.VERSION.SDK_INT, Build.DEVICE)) {
                    int level = lowestLevel(caps, profile, format);
                    if (level == Format.NO_VALUE) continue;
                    MediaFormat media = profileFormat(format, mode, profile, level,
                            Build.VERSION.SDK_INT, Build.DEVICE);
                    addIfSupported(info, caps, format, media, "explicit-" + profile + "/" + level,
                            attempts, diagnostics);
                }
            }
            // Pre-29 fallback negotiates only the level, keeping Baseline's no-B-frame guarantee.
            addIfSupported(info, caps, format, profileFormat(format, mode,
                            Format.NO_VALUE, Format.NO_VALUE, Build.VERSION.SDK_INT, Build.DEVICE),
                    Build.VERSION.SDK_INT >= 24 && Build.VERSION.SDK_INT < 29
                            ? "baseline-encoder-level" : "encoder-profile-level",
                    attempts, diagnostics);
        }
        diagnostics.log("quality encoder=" + info.getName() + " targetBitrate=" + target
                + " supportedBitrate=" + bitrate);
    }

    private static MediaFormat mediaFormat(Format format, int mode) {
        MediaFormat media = MediaFormatUtil.createMediaFormatFromFormat(format);
        media.setInteger(MediaFormat.KEY_COLOR_FORMAT, CodecCapabilities.COLOR_FormatSurface);
        media.setFloat(MediaFormat.KEY_FRAME_RATE, format.frameRate);
        media.setInteger(MediaFormat.KEY_BITRATE_MODE, mode);
        media.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        return media;
    }

    static int[] profilesFor(int sdk, String device) {
        if (sdk < 24) return new int[0];
        if (sdk < 26 || (sdk == 27
                && ("ASUS_X00T_3".equals(device) || "TC77".equals(device)))) {
            return new int[]{CodecProfileLevel.AVCProfileBaseline};
        }
        if (sdk < 29) {
            return new int[]{CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileBaseline};
        }
        return PROFILES.clone();
    }

    static MediaFormat profileFormat(Format format, int mode, int profile, int level,
                                     int sdk, String device) {
        MediaFormat media = mediaFormat(format, mode);
        if (sdk < 24) return media;
        if (profile == Format.NO_VALUE && sdk < 29) {
            profile = CodecProfileLevel.AVCProfileBaseline;
        }
        if (profile != Format.NO_VALUE) {
            boolean allowed = false;
            for (int candidate : profilesFor(sdk, device)) allowed |= candidate == profile;
            if (!allowed) throw new IllegalArgumentException("Unsafe AVC profile for API/device");
            media.setInteger(MediaFormat.KEY_PROFILE, profile);
            if (level != Format.NO_VALUE) media.setInteger(MediaFormat.KEY_LEVEL, level);
            if (sdk >= 26 && sdk < 29 && profile == CodecProfileLevel.AVCProfileHigh) {
                media.setInteger(MediaFormat.KEY_LATENCY, 1);
            }
        }
        return media;
    }

    private static long elapsedMs(long startedNs) {
        return (System.nanoTime() - startedNs) / 1_000_000;
    }

    static int lowestLevel(CodecCapabilities caps, int profile, Format format) {
        int highest = Format.NO_VALUE;
        for (CodecProfileLevel pair : caps.profileLevels) {
            if (pair.profile == profile) highest = Math.max(highest, pair.level);
        }
        for (int level : LEVELS) {
            if (level > highest) continue;
            CodecCapabilities limits = CodecCapabilities.createFromProfileLevel(
                    MimeTypes.VIDEO_H264, profile, level);
            VideoCapabilities video = limits == null ? null : limits.getVideoCapabilities();
            if (video != null && video.isSizeSupported(format.width, format.height)
                    && video.areSizeAndRateSupported(
                    format.width, format.height, format.frameRate)
                    && video.getBitrateRange().contains(format.averageBitrate)) return level;
        }
        return Format.NO_VALUE;
    }

    private static void addIfSupported(MediaCodecInfo info, CodecCapabilities caps, Format format,
                                       MediaFormat media, String policy, List<Attempt> attempts,
                                       Diagnostics diagnostics) {
        Attempt attempt = new Attempt(info.getName(), isSoftware(info), policy, format, media);
        boolean supported;
        try {
            supported = caps.isFormatSupported(media);
        } catch (IllegalArgumentException | IllegalStateException unavailable) {
            diagnostics.log("format-query-rejected " + attempt.describe() + " cause=" + unavailable);
            return;
        }
        diagnostics.log("format-support=" + supported + " encoder=" + attempt.name
                + " policy=" + policy + " bitrateMode=" + media.getInteger(MediaFormat.KEY_BITRATE_MODE));
        if (supported) attempts.add(attempt);
    }

    static Codec configure(List<Attempt> attempts, Creator creator, Diagnostics diagnostics)
            throws ExportException {
        return configure(attempts, creator, false, diagnostics);
    }

    static Codec configure(List<Attempt> attempts, Creator creator, boolean softwareOnly,
                            Diagnostics diagnostics) throws ExportException {
        List<ExportException> rejected = new ArrayList<>();
        long startedNs = System.nanoTime();
        for (int index = 0; index < attempts.size(); index++) {
            Attempt attempt = attempts.get(index);
            if (softwareOnly && !attempt.software) continue;
            diagnostics.log("before-configure attempt=" + (index + 1) + "/" + attempts.size()
                    + " " + attempt.describe());
            Codec codec;
            long configureStartedNs = System.nanoTime();
            try {
                // DefaultCodec 1.5.1 owns release on configure/create-surface/start rejection.
                codec = creator.create(attempt);
            } catch (ExportException error) {
                diagnostics.log("configure-rejected " + attempt.describe() + " code=" + error.errorCode
                        + " configureMs=" + elapsedMs(configureStartedNs)
                        + " selectionTotalMs=" + elapsedMs(startedNs) + " cause=" + error.getCause());
                if (error.errorCode != ExportException.ERROR_CODE_ENCODER_INIT_FAILED
                        && error.errorCode != ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED) {
                    for (ExportException previous : rejected) error.addSuppressed(previous);
                    throw error;
                }
                rejected.add(error);
                continue;
            } catch (RuntimeException error) {
                diagnostics.log("configure-failed encoder=" + attempt.name
                        + " configureMs=" + elapsedMs(configureStartedNs)
                        + " selectionTotalMs=" + elapsedMs(startedNs) + " cause=" + error);
                throw error;
            }
            long configureMs = elapsedMs(configureStartedNs);
            try {
                Format actual = codec.getConfigurationFormat();
                if (actual.width != attempt.format.width || actual.height != attempt.format.height) {
                    throw new IllegalStateException("Encoder changed the requested dimensions");
                }
                if (Float.compare(actual.frameRate, attempt.format.frameRate) != 0) {
                    throw new IllegalStateException("Encoder changed the requested frame rate");
                }
                diagnostics.log("configured attempt=" + (index + 1) + " " + attempt.describe()
                        + " encodingMode=" + VideoEncodingSettings.modeName(softwareOnly)
                        + " actual=" + actual + " nativeName=" + codec.getName()
                        + " configureMs=" + configureMs + " selectionTotalMs=" + elapsedMs(startedNs));
            } catch (RuntimeException error) {
                try {
                    codec.release();
                } catch (RuntimeException releaseError) {
                    if (releaseError != error) error.addSuppressed(releaseError);
                }
                throw error;
            }
            return codec;
        }
        ExportException failure = ExportException.createForCodec(
                new IllegalArgumentException((softwareOnly
                        ? "兼容编码不可用：No supported software AVC encoder configured at the requested "
                                + "exact size/frame rate; no hardware fallback or resize; attempts="
                        : "No exact-size AVC encoder configured; attempts=") + attempts.size()),
                ExportException.ERROR_CODE_ENCODING_FORMAT_UNSUPPORTED,
                new ExportException.CodecInfo("exact-size AVC", true, false, null));
        for (ExportException error : rejected) failure.addSuppressed(error);
        throw failure;
    }

    static boolean isSoftware(MediaCodecInfo info) {
        if (Build.VERSION.SDK_INT >= 29) return info.isSoftwareOnly();
        String name = info.getName().toLowerCase(Locale.ROOT);
        return name.startsWith("omx.google.") || name.startsWith("c2.android.")
                || name.startsWith("c2.google.") || name.startsWith("omx.ffmpeg.");
    }

    private static boolean supportsAvc(MediaCodecInfo info) {
        for (String type : info.getSupportedTypes()) {
            if (MimeTypes.VIDEO_H264.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    private ExactSizeVideoEncoder() {}
}
