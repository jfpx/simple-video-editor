package com.simple.videoeditor.oracle;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

/** Runs the phone's music contract/core against independent FFmpeg-decoded controls. */
public final class MusicParityMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Frozen oracle root required");
        Constructor<LocalParityMain> constructor = LocalParityMain.class.getDeclaredConstructor(File.class);
        constructor.setAccessible(true);
        Object loader = constructor.newInstance(new File(args[0]));
        Method decode = LocalParityMain.class.getDeclaredMethod("decode", File.class,
                OracleContract.OracleCase.class, boolean.class);
        decode.setAccessible(true);
        Method image = LocalParityMain.class.getDeclaredMethod("loadImage", String.class);
        image.setAccessible(true);
        OracleContract contract = MusicOracleContract.create();
        OracleContract.OracleCase music = contract.requireCase(MusicOracleContract.CASE_ID);
        File[] inputs = {
            new File("app\\src\\main\\assets\\music-oracle\\reference.mp4"),
            new File("app\\src\\main\\assets\\video-oracle\\standard.mp4"),
            new File("app\\src\\main\\assets\\music-oracle\\no-loop.mp4")
        };
        for (int i = 0; i < inputs.length; i++) {
            OracleCoreVerifier.Candidate candidate = (OracleCoreVerifier.Candidate)
                    decode.invoke(loader, inputs[i], music, true);
            OracleCoreVerifier.Report report = new OracleCoreVerifier().verify(contract, music.id, candidate,
                    (oracleCase, probe, frame) -> {
                        try {
                            return (OracleCoreVerifier.RgbImage) image.invoke(loader,
                                    oracleCase.frameAssets.get(frame).path);
                        } catch (ReflectiveOperationException error) {
                            throw new java.io.IOException("Cannot load independent image", error);
                        }
                    });
            Map<String, Object> result = report.toMap();
            boolean expected = i == 0;
            if (report.passed() != expected
                    || !(expected ? "PASS" : "FAIL").equals(result.get("status"))) {
                throw new AssertionError(inputs[i] + ": " + result);
            }
            if (!expected) {
                boolean audioFailed = false;
                for (Object item : (Iterable<?>) result.get("checks")) {
                    Map<?, ?> check = (Map<?, ?>) item;
                    if (check.get("assertion").toString().startsWith("audio.window_")
                            && Boolean.FALSE.equals(check.get("passed"))) audioFailed = true;
                }
                if (!audioFailed) throw new AssertionError("Negative must reject incorrect audio: " + result);
            }
            System.out.println("PASS expected=" + result.get("status") + " file=" + inputs[i]);
        }
    }
}
