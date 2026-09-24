package com.simple.videoeditor.oracle;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Build;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Buffer-mode selection for the independent oracle, not the export pipeline. */
final class OracleVideoCodecSelector {
    interface Factory<T> {
        T create(String name) throws IOException;
        void configure(T codec, MediaFormat format);
        void release(T codec);
    }

    interface Checkpoint {
        void write(Map<String, Object> selection) throws IOException;
    }

    interface FormatSupport {
        boolean isFormatSupported(MediaFormat format);
    }

    static final Factory<MediaCodec> ANDROID = new Factory<MediaCodec>() {
        @Override public MediaCodec create(String name) throws IOException {
            return MediaCodec.createByCodecName(name);
        }
        @Override public void configure(MediaCodec codec, MediaFormat format) {
            codec.configure(format, null, null, 0);
        }
        @Override public void release(MediaCodec codec) {
            codec.release();
        }
    };

    static final class Candidate {
        final String name;
        final boolean software;
        final boolean eligibleAttempt;
        final Map<String, Object> capability;

        Candidate(String name, boolean software, boolean eligibleAttempt, Map<String, Object> capability) {
            this.name = name;
            this.software = software;
            this.eligibleAttempt = eligibleAttempt;
            this.capability = Collections.unmodifiableMap(new LinkedHashMap<>(capability));
        }

        Map<String, Object> evidence() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("name", name);
            result.put("software", software);
            result.put("eligible_attempt", eligibleAttempt);
            result.put("capability", capability);
            return Collections.unmodifiableMap(result);
        }
    }

    static List<Candidate> enumerate(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        int width = format.getInteger(MediaFormat.KEY_WIDTH);
        int height = format.getInteger(MediaFormat.KEY_HEIGHT);
        List<Candidate> candidates = new ArrayList<>();
        for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos()) {
            if (info.isEncoder()) continue;
            boolean matchingType = false;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime)) matchingType = true;
            }
            if (!matchingType) continue;
            String name = info.getName();
            String lower = name.toLowerCase(Locale.US);
            boolean software = Build.VERSION.SDK_INT >= 29 ? info.isSoftwareOnly()
                    : lower.startsWith("omx.google.") || lower.startsWith("c2.android.")
                    || lower.startsWith("c2.google.");
            Map<String, Object> capability = new LinkedHashMap<>();
            boolean eligibleAttempt = false;
            try {
                MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(mime);
                MediaCodecInfo.VideoCapabilities video = caps.getVideoCapabilities();
                boolean flexible = false;
                List<Integer> colors = new ArrayList<>();
                for (int color : caps.colorFormats) {
                    colors.add(color);
                    flexible |= color == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible;
                }
                boolean alias = Build.VERSION.SDK_INT >= 29 && info.isAlias();
                boolean surfaceOnly = caps.isFeatureRequired(
                        MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback)
                        || caps.isFeatureRequired(MediaCodecInfo.CodecCapabilities.FEATURE_TunneledPlayback);
                boolean size = video != null && video.isSizeSupported(width, height);
                boolean color = !format.containsKey(MediaFormat.KEY_COLOR_FORMAT)
                        || colors.contains(format.getInteger(MediaFormat.KEY_COLOR_FORMAT));
                capability.put("color_formats", Collections.unmodifiableList(colors));
                capability.put("flexible_yuv420", flexible);
                capability.put("requested_color_supported", color);
                capability.put("size_supported", size);
                capability.put("surface_only", surfaceOnly);
                capability.put("alias", alias);
                if (video != null) {
                    capability.put("width_range", video.getSupportedWidths().toString());
                    capability.put("height_range", video.getSupportedHeights().toString());
                    capability.put("width_alignment", video.getWidthAlignment());
                    capability.put("height_alignment", video.getHeightAlignment());
                }
                eligibleAttempt = eligibleAttempt(format, software,
                        flexible && size && color && !surfaceOnly && !alias,
                        caps::isFormatSupported, capability);
            } catch (IllegalArgumentException error) {
                // A vendor may reject this MIME/format during capability lookup.
                capability.put("query_failure", error.toString());
            }
            candidates.add(new Candidate(name, software, eligibleAttempt, capability));
        }
        // Keep platform preference within each group; explicitly try software after hardware.
        Collections.sort(candidates, (left, right) -> Boolean.compare(left.software, right.software));
        return candidates;
    }

    static boolean eligibleAttempt(MediaFormat format, boolean software, boolean bufferCompatible,
                                   FormatSupport query, Map<String, Object> capability) {
        capability.put("level_query_performed", false);
        capability.put("level_advisory_fallback", false);
        boolean fullFormat = query.isFormatSupported(format);
        capability.put("format_supported", fullFormat);
        if (!bufferCompatible) return false;
        if (fullFormat) return true;
        if (!software || !format.containsKey(MediaFormat.KEY_LEVEL)) return false;

        MediaFormat withoutLevel = capabilityFormatWithoutLevel(format);
        capability.put("declared_level", format.getInteger(MediaFormat.KEY_LEVEL));
        capability.put("format_without_level", withoutLevel.toString());
        capability.put("level_query_performed", true);
        boolean levelAdvisory = query.isFormatSupported(withoutLevel);
        capability.put("format_without_level_supported", levelAdvisory);
        capability.put("level_advisory_fallback", levelAdvisory);
        return levelAdvisory;
    }

    static MediaFormat capabilityFormatWithoutLevel(MediaFormat original) {
        if (Build.VERSION.SDK_INT >= 29) {
            MediaFormat query = new MediaFormat(original);
            query.removeKey(MediaFormat.KEY_LEVEL);
            return query;
        }
        return legacyCapabilityFormatWithoutLevel(original);
    }

    static MediaFormat legacyCapabilityFormatWithoutLevel(MediaFormat original) {
        // API 23 has no MediaFormat copy/map API. Rebuild capability inputs only;
        // codec-specific data and the original level still reach configure unchanged.
        MediaFormat query = MediaFormat.createVideoFormat(original.getString(MediaFormat.KEY_MIME),
                original.getInteger(MediaFormat.KEY_WIDTH), original.getInteger(MediaFormat.KEY_HEIGHT));
        String[] integerKeys = {MediaFormat.KEY_PROFILE, MediaFormat.KEY_COLOR_FORMAT,
                MediaFormat.KEY_BIT_RATE, "max-bitrate", "color-standard", "color-range",
                "color-transfer", "feature-adaptive-playback", "feature-secure-playback",
                "feature-tunneled-playback", "feature-partial-frame", "feature-frame-parsing",
                "feature-multiple-frames", "feature-dynamic-timestamp", "feature-low-latency",
                "feature-dynamic-color-aspects", "feature-detached-surface"};
        for (String key : integerKeys) {
            if (original.containsKey(key)) query.setInteger(key, original.getInteger(key));
        }
        if (original.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            try {
                query.setInteger(MediaFormat.KEY_FRAME_RATE, original.getInteger(MediaFormat.KEY_FRAME_RATE));
            } catch (ClassCastException floatRate) {
                query.setFloat(MediaFormat.KEY_FRAME_RATE, original.getFloat(MediaFormat.KEY_FRAME_RATE));
            }
        }
        return query;
    }

    static <T> T configure(List<Candidate> candidates, MediaFormat format, Factory<T> factory,
                           Checkpoint checkpoint, long deadlineNs) throws IOException {
        Map<String, Object> state = new LinkedHashMap<>();
        List<Map<String, Object>> all = new ArrayList<>();
        for (Candidate candidate : candidates) all.add(candidate.evidence());
        state.put("candidates", Collections.unmodifiableList(all));
        state.put("mime", format.getString(MediaFormat.KEY_MIME));
        state.put("width", format.getInteger(MediaFormat.KEY_WIDTH));
        state.put("height", format.getInteger(MediaFormat.KEY_HEIGHT));
        state.put("configure_format", format.toString());
        List<Map<String, Object>> failures = new ArrayList<>();
        state.put("failures", Collections.emptyList());
        emit(checkpoint, state, "candidates_enumerated");
        IOException exhausted = new IOException("Oracle video decoder candidates exhausted: "
                + format + "; candidates=" + all);
        for (Candidate candidate : candidates) {
            checkDeadline(deadlineNs);
            state.put("decoder_name", candidate.name);
            state.put("software_fallback", candidate.software);
            state.put("capability", candidate.capability);
            if (!candidate.eligibleAttempt) {
                emit(checkpoint, state, "candidate_ineligible");
                continue;
            }
            emit(checkpoint, state, "before_create");
            T codec;
            try {
                codec = factory.create(candidate.name);
            } catch (IOException | IllegalArgumentException | MediaCodec.CodecException error) {
                recordFailure(state, failures, exhausted, candidate, "create", error);
                emit(checkpoint, state, "create_failed");
                continue;
            }
            boolean transferred = false;
            try {
                checkDeadline(deadlineNs);
                // Synchronous persistence must complete before entering the vendor's native code.
                emit(checkpoint, state, "before_configure");
                try {
                    factory.configure(codec, format);
                } catch (IllegalArgumentException | MediaCodec.CodecException error) {
                    recordFailure(state, failures, exhausted, candidate, "configure", error);
                    emit(checkpoint, state, "configure_failed");
                    continue;
                }
                emit(checkpoint, state, "configured");
                transferred = true;
                return codec;
            } finally {
                if (!transferred) {
                    factory.release(codec);
                    emit(checkpoint, state, "released");
                }
            }
        }
        emit(checkpoint, state, "exhausted");
        throw exhausted;
    }

    private static void recordFailure(Map<String, Object> state, List<Map<String, Object>> failures,
                                      IOException exhausted, Candidate candidate, String operation,
                                      Exception error) {
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("name", candidate.name);
        failure.put("operation", operation);
        failure.put("error", error.toString());
        if (error instanceof MediaCodec.CodecException) {
            failure.put("codec_diagnostic", ((MediaCodec.CodecException) error).getDiagnosticInfo());
        }
        failures.add(Collections.unmodifiableMap(failure));
        state.put("failures", Collections.unmodifiableList(new ArrayList<>(failures)));
        exhausted.addSuppressed(new IOException(candidate.name + " " + operation + ": " + error, error));
    }

    private static void emit(Checkpoint checkpoint, Map<String, Object> state, String stage)
            throws IOException {
        state.put("stage", stage);
        checkpoint.write(Collections.unmodifiableMap(new LinkedHashMap<>(state)));
    }

    private static void checkDeadline(long deadlineNs) throws IOException {
        if (Thread.currentThread().isInterrupted() || System.nanoTime() > deadlineNs) {
            throw new IOException("Oracle video codec selection interrupted or timed out");
        }
    }
}
