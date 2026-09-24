package com.simple.videoeditor.oracle;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;

/** JVM-only adapter: reuse parity decoding, not Android codec validation. */
public final class OracleAndroidDecoder {
    public static boolean injectImageFailure;
    private final Map<String, Object> diagnostics = new LinkedHashMap<>();
    private Observer observer;

    public interface Observer {
        void checkpoint(Map<String, Object> diagnostics) throws IOException;
    }

    public void setObserver(Observer observer) {
        this.observer = observer;
    }

    public OracleCoreVerifier.Candidate decode(File file, OracleContract contract,
                                               OracleContract.OracleCase oracleCase) throws IOException {
        try {
            Constructor<LocalParityMain> constructor =
                    LocalParityMain.class.getDeclaredConstructor(File.class);
            constructor.setAccessible(true);
            Object parity = constructor.newInstance(new File(System.getProperty("schema.oracleRoot")));
            Method decode = LocalParityMain.class.getDeclaredMethod("decode",
                    File.class, OracleContract.OracleCase.class, boolean.class);
            decode.setAccessible(true);
            OracleCoreVerifier.Candidate candidate =
                    (OracleCoreVerifier.Candidate) decode.invoke(parity, file, oracleCase, true);
            diagnostics.clear();
            diagnostics.put("backend", "LocalParityMain ffmpeg (JVM test adapter; not Android)");
            diagnostics.put("sparse", true);
            diagnostics.put("video", track(candidate.video != null, candidate.videoTrackCount));
            diagnostics.put("audio", track(candidate.audio != null, candidate.audioTrackCount));
            if (observer != null) observer.checkpoint(new LinkedHashMap<>(diagnostics));
            return candidate;
        } catch (InvocationTargetException error) {
            throw new IOException("Local parity decode failed", error.getCause());
        } catch (ReflectiveOperationException error) {
            throw new IOException("LocalParityMain adapter signature changed", error);
        }
    }

    private static Map<String, Object> track(boolean present, int count) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("present", present);
        values.put("track_count", count);
        values.put("decoder_name", "ffmpeg");
        return values;
    }

    public Map<String, Object> getDiagnostics() { return diagnostics; }

    public OracleCoreVerifier.RgbImage loadAssetImage(InputStream input) throws IOException {
        if (injectImageFailure) {
            throw new IllegalStateException("schema-regression injected expected-image failure");
        }
        BufferedImage image = ImageIO.read(input);
        if (image == null) throw new IOException("Invalid expected image");
        byte[] rgb = new byte[image.getWidth() * image.getHeight() * 3];
        int offset = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int pixel = image.getRGB(x, y);
                rgb[offset++] = (byte) (pixel >> 16);
                rgb[offset++] = (byte) (pixel >> 8);
                rgb[offset++] = (byte) pixel;
            }
        }
        return new OracleCoreVerifier.RgbImage(image.getWidth(), image.getHeight(), rgb);
    }
}
