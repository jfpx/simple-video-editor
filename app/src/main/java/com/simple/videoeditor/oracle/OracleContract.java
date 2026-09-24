package com.simple.videoeditor.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class OracleContract {
    public final String schema;
    public final String version;
    public final Pins pins;
    public final Source source;
    public final Tolerances tolerances;
    public final Asset fixture;
    public final List<OracleCase> cases;
    private final Map<String, OracleCase> casesById;

    public OracleContract(String schema, String version, Pins pins, Source source,
                          Tolerances tolerances, Asset fixture, List<OracleCase> cases) {
        this.schema = require(schema, "schema");
        this.version = require(version, "version");
        this.pins = require(pins, "pins");
        this.source = require(source, "source");
        this.tolerances = require(tolerances, "tolerances");
        this.fixture = require(fixture, "fixture");
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("cases");
        }
        List<OracleCase> copy = new ArrayList<OracleCase>(cases.size());
        Map<String, OracleCase> byId = new LinkedHashMap<String, OracleCase>();
        for (OracleCase oracleCase : cases) {
            OracleCase value = require(oracleCase, "oracleCase");
            if (byId.put(value.id, value) != null) {
                throw new IllegalArgumentException("Duplicate case: " + value.id);
            }
            copy.add(value);
        }
        this.cases = Collections.unmodifiableList(copy);
        this.casesById = Collections.unmodifiableMap(byId);
    }

    public OracleCase requireCase(String caseId) {
        OracleCase oracleCase = casesById.get(caseId);
        if (oracleCase == null) {
            throw new IllegalArgumentException("Unknown case: " + caseId);
        }
        return oracleCase;
    }

    public static final class Pins {
        public final String sourceRoot;
        public final String handoffVersion;
        public final String manifestPath;
        public final String manifestSchema;
        public final String manifestVersion;
        public final String manifestSha256;
        public final String androidCasesPath;
        public final String androidCasesSha256;
        public final String contractTextPath;
        public final String contractTextSha256;
        public final String oraclePyPath;
        public final String oraclePySha256;
        public final String assetContractPath;
        public final String assetContractSha256;

        public Pins(String sourceRoot, String handoffVersion, String manifestPath,
                    String manifestSchema, String manifestVersion, String manifestSha256,
                    String androidCasesPath, String androidCasesSha256, String contractTextPath,
                    String contractTextSha256, String oraclePyPath, String oraclePySha256,
                    String assetContractPath, String assetContractSha256) {
            this.sourceRoot = require(sourceRoot, "sourceRoot");
            this.handoffVersion = require(handoffVersion, "handoffVersion");
            this.manifestPath = require(manifestPath, "manifestPath");
            this.manifestSchema = require(manifestSchema, "manifestSchema");
            this.manifestVersion = require(manifestVersion, "manifestVersion");
            this.manifestSha256 = require(manifestSha256, "manifestSha256");
            this.androidCasesPath = require(androidCasesPath, "androidCasesPath");
            this.androidCasesSha256 = require(androidCasesSha256, "androidCasesSha256");
            this.contractTextPath = require(contractTextPath, "contractTextPath");
            this.contractTextSha256 = require(contractTextSha256, "contractTextSha256");
            this.oraclePyPath = require(oraclePyPath, "oraclePyPath");
            this.oraclePySha256 = require(oraclePySha256, "oraclePySha256");
            this.assetContractPath = require(assetContractPath, "assetContractPath");
            this.assetContractSha256 = require(assetContractSha256, "assetContractSha256");
        }
    }

    public static final class Source {
        public final int width;
        public final int height;
        public final int fps;
        public final int frames;
        public final int sampleRate;
        public final int channels;
        public final int pcmSamples;
        public final double tonePeak;
        public final double toneRms;
        public final int barcodeBits;
        public final int barcodeLowRgb;
        public final int barcodeHighRgb;

        public Source(int width, int height, int fps, int frames, int sampleRate,
                      int channels, int pcmSamples, double tonePeak, double toneRms,
                      int barcodeBits, int barcodeLowRgb, int barcodeHighRgb) {
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.frames = frames;
            this.sampleRate = sampleRate;
            this.channels = channels;
            this.pcmSamples = pcmSamples;
            this.tonePeak = tonePeak;
            this.toneRms = toneRms;
            this.barcodeBits = barcodeBits;
            this.barcodeLowRgb = barcodeLowRgb;
            this.barcodeHighRgb = barcodeHighRgb;
        }
    }

    public static final class Tolerances {
        public final double rgbRegionMaxChannelError;
        public final double rgbFrameMae;
        public final double rgbFrameP95AbsoluteError;
        public final double barcodeLumaError;
        public final double markerMaxChannelError;
        public final int sourceFrameAlignment;
        public final double videoStartSeconds;
        public final double videoDurationExtraSeconds;
        public final double ptsCadenceSeconds;
        public final double audioStartSeconds;
        public final double audioPtsContinuitySeconds;
        public final double audioDurationSeconds;
        public final double audioRmsRelative;
        public final double audioRmsAbsolute;
        public final double audioFrequencyHz;

        public Tolerances(double rgbRegionMaxChannelError, double rgbFrameMae,
                          double rgbFrameP95AbsoluteError, double barcodeLumaError,
                          double markerMaxChannelError, int sourceFrameAlignment,
                          double videoStartSeconds, double videoDurationExtraSeconds,
                          double ptsCadenceSeconds, double audioStartSeconds,
                          double audioPtsContinuitySeconds, double audioDurationSeconds,
                          double audioRmsRelative, double audioRmsAbsolute,
                          double audioFrequencyHz) {
            this.rgbRegionMaxChannelError = rgbRegionMaxChannelError;
            this.rgbFrameMae = rgbFrameMae;
            this.rgbFrameP95AbsoluteError = rgbFrameP95AbsoluteError;
            this.barcodeLumaError = barcodeLumaError;
            this.markerMaxChannelError = markerMaxChannelError;
            this.sourceFrameAlignment = sourceFrameAlignment;
            this.videoStartSeconds = videoStartSeconds;
            this.videoDurationExtraSeconds = videoDurationExtraSeconds;
            this.ptsCadenceSeconds = ptsCadenceSeconds;
            this.audioStartSeconds = audioStartSeconds;
            this.audioPtsContinuitySeconds = audioPtsContinuitySeconds;
            this.audioDurationSeconds = audioDurationSeconds;
            this.audioRmsRelative = audioRmsRelative;
            this.audioRmsAbsolute = audioRmsAbsolute;
            this.audioFrequencyHz = audioFrequencyHz;
        }
    }

    public static final class Asset {
        public final String path;
        public final long bytes;
        public final String sha256;

        public Asset(String path, long bytes, String sha256) {
            this.path = require(path, "path");
            this.bytes = bytes;
            this.sha256 = require(sha256, "sha256").toLowerCase(Locale.US);
        }
    }

    public static final class Operation {
        public final double trimStartSeconds;
        public final double trimEndSeconds;
        public final int cropLeft;
        public final int cropTop;
        public final int cropRight;
        public final int cropBottom;
        public final int rotationDegrees;
        public final int outputHeight;
        public final double speed;
        public final double volume;

        public Operation(double trimStartSeconds, double trimEndSeconds, int cropLeft,
                         int cropTop, int cropRight, int cropBottom, int rotationDegrees,
                         int outputHeight, double speed, double volume) {
            this.trimStartSeconds = trimStartSeconds;
            this.trimEndSeconds = trimEndSeconds;
            this.cropLeft = cropLeft;
            this.cropTop = cropTop;
            this.cropRight = cropRight;
            this.cropBottom = cropBottom;
            this.rotationDegrees = rotationDegrees;
            this.outputHeight = outputHeight;
            this.speed = speed;
            this.volume = volume;
        }
    }

    public static final class BarcodeRegion {
        public final int bit;
        public final Rect rect;
        public final Rect complementRect;

        public BarcodeRegion(int bit, Rect rect, Rect complementRect) {
            this.bit = bit;
            this.rect = require(rect, "rect");
            this.complementRect = require(complementRect, "complementRect");
        }
    }

    public static final class Probe {
        public final int sourceFrame;
        public final double outputSeconds;
        public final Asset image;

        public Probe(int sourceFrame, double outputSeconds, Asset image) {
            this.sourceFrame = sourceFrame;
            this.outputSeconds = outputSeconds;
            this.image = require(image, "image");
        }
    }

    public static final class AudioProbe {
        public final double centerSeconds;
        public final double windowSeconds;
        public final double sourceSeconds;
        public final double frequencyHz;
        public final double rms;

        public AudioProbe(double centerSeconds, double windowSeconds, double sourceSeconds,
                          double frequencyHz, double rms) {
            this.centerSeconds = centerSeconds;
            this.windowSeconds = windowSeconds;
            this.sourceSeconds = sourceSeconds;
            this.frequencyHz = frequencyHz;
            this.rms = rms;
        }
    }

    public static final class OracleCase {
        public final String id;
        public final Operation operation;
        public final Map<String, Object> androidEditConfig;
        public final int width;
        public final int height;
        public final double durationSeconds;
        public final double nativeFps;
        public final List<Double> acceptedCfrFps;
        public final int nativeFrameCount;
        public final int videoTrackCount;
        public final int audioTrackCount;
        public final boolean audioRequired;
        public final int audioSampleRate;
        public final int audioChannels;
        public final int expectedDecodedAudioSamples;
        public final List<BarcodeRegion> barcodeRegions;
        public final List<Probe> probes;
        public final Map<Integer, Asset> frameAssets;
        public final List<AudioProbe> audioProbes;
        public final Asset reference;

        public OracleCase(String id, Operation operation, Map<String, Object> androidEditConfig,
                          int width, int height, double durationSeconds, double nativeFps,
                          List<Double> acceptedCfrFps, int nativeFrameCount,
                          int videoTrackCount, int audioTrackCount, boolean audioRequired,
                          int audioSampleRate, int audioChannels, int expectedDecodedAudioSamples,
                          List<BarcodeRegion> barcodeRegions, List<Probe> probes, Map<Integer, Asset> frameAssets,
                          List<AudioProbe> audioProbes, Asset reference) {
            this.id = require(id, "id");
            this.operation = require(operation, "operation");
            this.androidEditConfig = Collections.unmodifiableMap(
                    new LinkedHashMap<String, Object>(require(androidEditConfig, "androidEditConfig")));
            this.width = width;
            this.height = height;
            this.durationSeconds = durationSeconds;
            this.nativeFps = nativeFps;
            this.acceptedCfrFps = immutableCopy(acceptedCfrFps, "acceptedCfrFps");
            this.nativeFrameCount = nativeFrameCount;
            this.videoTrackCount = videoTrackCount;
            this.audioTrackCount = audioTrackCount;
            this.audioRequired = audioRequired;
            this.audioSampleRate = audioSampleRate;
            this.audioChannels = audioChannels;
            this.expectedDecodedAudioSamples = expectedDecodedAudioSamples;
            this.barcodeRegions = immutableCopy(barcodeRegions, "barcodeRegions");
            this.probes = immutableCopy(probes, "probes");
            this.frameAssets = immutableMap(frameAssets, "frameAssets");
            this.audioProbes = immutableCopy(audioProbes, "audioProbes");
            this.reference = require(reference, "reference");
        }
    }

    public static final class Rect {
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Rect(int left, int top, int right, int bottom) {
            if (left >= right || top >= bottom) {
                throw new IllegalArgumentException("Invalid rect");
            }
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    private static <T> T require(T value, String name) {
        if (value == null) {
            throw new IllegalArgumentException(name);
        }
        return value;
    }

    private static <T> List<T> immutableCopy(List<T> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name);
        }
        ArrayList<T> copy = new ArrayList<T>(values.size());
        for (T value : values) {
            copy.add(require(value, name));
        }
        return Collections.unmodifiableList(copy);
    }

    private static Map<Integer, Asset> immutableMap(Map<Integer, Asset> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name);
        }
        LinkedHashMap<Integer, Asset> copy = new LinkedHashMap<Integer, Asset>();
        for (Map.Entry<Integer, Asset> entry : values.entrySet()) {
            copy.put(require(entry.getKey(), name), require(entry.getValue(), name));
        }
        return Collections.unmodifiableMap(copy);
    }
}
