package com.simple.videoeditor.oracle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Independent one-second 1320 Hz music repeated over the four-second identity video. */
public final class MusicOracleContract {
    public static final String CASE_ID = "music_loop";
    public static final OracleContract.Asset MUSIC = new OracleContract.Asset(
            "music.wav", 96044, "b99c6cb28cc54b204be5318748f30a731ffc0e1072707b024e9c6baa4fd5f7a1");
    public static final OracleContract.Asset REFERENCE = new OracleContract.Asset(
            "reference.mp4", 95479, "30963ba241d853da2c52cfdd58be0a0aa77c0de70b40306a7be0ae7b5a172e7b");
    public static final OracleContract.Asset NO_LOOP = new OracleContract.Asset(
            "no-loop.mp4", 49027, "73e40da25d638ce0be7f69acbae254400cbd531e6fe79539cffcb275a1640b67");

    private MusicOracleContract() {}

    public static OracleContract create() {
        OracleContract base = OracleGeneratedContract.create();
        OracleContract.OracleCase identity = base.requireCase("identity");
        List<OracleContract.AudioProbe> audio = new ArrayList<>();
        for (OracleContract.AudioProbe probe : identity.audioProbes) {
            audio.add(new OracleContract.AudioProbe(probe.centerSeconds, probe.windowSeconds,
                    probe.centerSeconds % 1.0, 1320, 0.16 / Math.sqrt(2)));
        }
        OracleContract.OracleCase music = new OracleContract.OracleCase(
                CASE_ID, identity.operation, identity.androidEditConfig,
                identity.width, identity.height, identity.durationSeconds, identity.nativeFps,
                identity.acceptedCfrFps, identity.nativeFrameCount,
                identity.videoTrackCount, identity.audioTrackCount, identity.audioRequired,
                identity.audioSampleRate, identity.audioChannels, identity.expectedDecodedAudioSamples,
                identity.barcodeRegions, identity.probes, identity.frameAssets, audio, REFERENCE);
        return new OracleContract(base.schema, "music-loop-1", base.pins, base.source,
                base.tolerances, base.fixture, Collections.singletonList(music));
    }
}
