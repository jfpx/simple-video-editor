package com.simple.videoeditor;

import android.content.Context;
import android.content.res.AssetManager;

import com.simple.videoeditor.oracle.OracleAndroidDecoder;
import com.simple.videoeditor.oracle.OracleContract;
import com.simple.videoeditor.oracle.OracleCoreVerifier;
import com.simple.videoeditor.oracle.OracleGeneratedContract;
import com.simple.videoeditor.oracle.IntroOracleContract;
import com.simple.videoeditor.oracle.TextOracleContract;
import com.simple.videoeditor.oracle.TitleOracleContract;
import com.simple.videoeditor.oracle.WatermarkOracleContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public final class OracleVerifier {
    public static final String DECODER_KEY = "decoder";

    private static final String ASSET_ROOT = "video-oracle";
    private static final String CONTRACT_ASSET = ASSET_ROOT + "/android-contract.json";
    private static final String FIXTURE_ASSET = ASSET_ROOT + "/standard.mp4";
    private static final String CONTROLS_ASSET = ASSET_ROOT + "/controls.json";
    private static final String CONTROLS_SHA256 =
            "f6a1c46c5769f458545733e050c71680639f590547f580e0f270195b8de6b805";

    private final Context context;
    private final AssetManager assets;
    private final OracleContract contract;
    private final OracleAndroidDecoder decoder = new OracleAndroidDecoder();
    private final OracleCoreVerifier coreVerifier = new OracleCoreVerifier();
    private final Map<String, OracleCoreVerifier.RgbImage> expectedImages =
            new LinkedHashMap<String, OracleCoreVerifier.RgbImage>();
    private boolean validated;
    private boolean provenanceValidated;

    public OracleVerifier(Context context) {
        this(context, OracleGeneratedContract.create());
    }

    OracleVerifier(Context context, OracleContract contract) {
        if (context == null) {
            throw new IllegalArgumentException("context");
        }
        this.context = context.getApplicationContext();
        this.assets = this.context.getAssets();
        if (contract == null) throw new IllegalArgumentException("contract");
        this.contract = contract;
    }

    public void setObserver(OracleAndroidDecoder.Observer observer) {
        decoder.setObserver(observer);
    }

    public JSONObject verify(String caseId, File candidate) throws IOException {
        OracleContract.OracleCase oracleCase = findCase(caseId);
        prepareFixture();
        clearExpectedImages();
        try {
            OracleCoreVerifier.Candidate decoded = decoder.decode(candidate, contract, oracleCase);
            OracleCoreVerifier.Report report = coreVerifier.verify(contract, caseId, decoded,
                    new OracleCoreVerifier.ExpectedImageProvider() {
                        @Override
                        public OracleCoreVerifier.RgbImage load(OracleContract.OracleCase oracleCase,
                                                                OracleContract.Probe probe,
                                                                int matchedSourceFrame) throws IOException {
                            OracleContract.Asset frameAsset = oracleCase.frameAssets.get(Integer.valueOf(matchedSourceFrame));
                            if (frameAsset != null) {
                                return loadExpectedImage(frameAsset.path);
                            }
                            throw new IOException("Missing pinned expected frame: "
                                    + oracleCase.id + "/" + matchedSourceFrame);
                        }
                    });
            Map<String, Object> evidence = report.toMap();
            evidence.put("schema", "independent-video-oracle-android-report");
            evidence.put("version", "1.0.0");
            evidence.put("contract_schema", contract.schema);
            evidence.put("contract_version", contract.version);
            evidence.put("pins", reportPins());
            evidence.put(DECODER_KEY, decoder.getDiagnostics());
            return toJson(evidence);
        } finally {
            clearExpectedImages();
        }
    }

    public JSONObject loadContract() throws IOException {
        byte[] bytes = readAsset(CONTRACT_ASSET);
        String actualHash = sha256(bytes);
        if (!actualHash.equals(contract.pins.assetContractSha256)) {
            throw new IOException("Contract hash mismatch: " + actualHash);
        }
        try {
            return new JSONObject(new String(bytes, "UTF-8"));
        } catch (JSONException error) {
            throw new IOException("Invalid contract JSON", error);
        }
    }

    public synchronized JSONObject loadControls() throws IOException {
        byte[] bytes = readAsset(CONTROLS_ASSET);
        if (!CONTROLS_SHA256.equals(sha256(bytes))) {
            throw new IOException("Controls manifest hash mismatch");
        }
        try {
            JSONObject manifest = new JSONObject(new String(bytes, "UTF-8"));
            JSONArray controls = manifest.getJSONArray("controls");
            if (controls.length() != 36
                    || !contract.pins.assetContractSha256.equals(
                            manifest.getString("android_contract_sha256"))) {
                throw new IOException("Controls contract mismatch");
            }
            if (!provenanceValidated) {
                JSONArray provenance = manifest.getJSONArray("provenance");
                for (int index = 0; index < provenance.length(); index++) {
                    JSONObject entry = provenance.getJSONObject(index);
                    byte[] original = readAsset(assetPath(entry.getString("asset")));
                    if (original.length != entry.getLong("bytes")
                            || !sha256(original).equals(entry.getString("sha256"))) {
                        throw new IOException("Frozen provenance integrity mismatch: "
                                + entry.getString("asset"));
                    }
                }
                provenanceValidated = true;
            }
            return manifest;
        } catch (JSONException error) {
            throw new IOException("Invalid controls JSON", error);
        }
    }

    public synchronized File prepareControl(JSONObject control) throws IOException {
        if (control == null) {
            throw new IOException("Missing control vector");
        }
        try {
            JSONArray controls = loadControls().getJSONArray("controls");
            JSONObject trusted = null;
            for (int index = 0; index < controls.length(); index++) {
                JSONObject entry = controls.getJSONObject(index);
                if (entry.getString("id").equals(control.getString("id"))) {
                    trusted = entry;
                    break;
                }
            }
            if (trusted == null || trusted.length() != control.length()) {
                throw new IOException("Untrusted control vector");
            }
            Iterator<String> keys = trusted.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!trusted.get(key).equals(control.get(key))) {
                    throw new IOException("Control vector mismatch: " + key);
                }
            }
            String id = trusted.getString("id");
            if (!id.matches("[A-Za-z0-9_-]+")) {
                throw new IOException("Invalid control id");
            }
            findCase(trusted.getString("case"));
            File root = new File(context.getFilesDir().getCanonicalFile(), "video-oracle");
            File directory = new File(root, "controls");
            if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile())) {
                throw new IOException("Linked controls directory");
            }
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("Unable to create controls directory");
            }
            File output = new File(directory, id + ".mp4");
            if (!output.getCanonicalFile().equals(output.getAbsoluteFile())) {
                throw new IOException("Linked control output");
            }
            long expectedBytes = trusted.getLong("bytes");
            String expectedHash = trusted.getString("sha256");
            if (output.isFile() && output.length() == expectedBytes
                    && expectedHash.equals(sha256(output))) {
                return output;
            }
            try {
                copyAsset(assetPath(trusted.getString("asset")), output);
                if (output.length() != expectedBytes || !expectedHash.equals(sha256(output))) {
                    throw new IOException("Control integrity mismatch: " + id);
                }
            } catch (IOException error) {
                if (output.exists() && !output.delete()) {
                    error.addSuppressed(new IOException("Unable to remove incomplete control"));
                }
                throw error;
            }
            return output;
        } catch (JSONException error) {
            throw new IOException("Invalid control vector", error);
        }
    }

    public File prepareFixture() throws IOException {
        validateAssets();
        File directory = new File(context.getFilesDir(), "video-oracle");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("Unable to create oracle directory");
        }
        File fixture = new File(directory, "standard.mp4");
        if (fixture.isFile() && fixture.length() == contract.fixture.bytes
                && contract.fixture.sha256.equals(sha256(fixture))) {
            return fixture;
        }
        copyAsset(FIXTURE_ASSET, fixture);
        String actualHash = sha256(fixture);
        if (!contract.fixture.sha256.equals(actualHash) || fixture.length() != contract.fixture.bytes) {
            throw new IOException("Fixture integrity mismatch");
        }
        return fixture;
    }

    public static File prepareFixture(Context context) throws IOException {
        return new OracleVerifier(context).prepareFixture();
    }

    File prepareMusicAsset(OracleContract.Asset asset) throws IOException {
        if (asset != com.simple.videoeditor.oracle.MusicOracleContract.MUSIC
                && asset != com.simple.videoeditor.oracle.MusicOracleContract.REFERENCE
                && asset != com.simple.videoeditor.oracle.MusicOracleContract.NO_LOOP) {
            throw new IOException("Unknown supplementary music asset");
        }
        return prepareSupplementAsset("music-oracle", asset);
    }

    File prepareIntroAsset(OracleContract.Asset asset) throws IOException {
        if (!IntroOracleContract.ASSETS.contains(asset)) {
            throw new IOException("Unknown supplementary intro asset");
        }
        return prepareSupplementAsset(IntroOracleContract.ASSET_DIRECTORY, asset);
    }

    File prepareTextAsset(OracleContract.Asset asset) throws IOException {
        if (!TextOracleContract.ASSETS.contains(asset)) throw new IOException("Unknown text asset");
        return prepareSupplementAsset(TextOracleContract.ASSET_DIRECTORY, asset);
    }

    File prepareTitleAsset(OracleContract.Asset asset) throws IOException {
        if (!TitleOracleContract.ASSETS.contains(asset)) throw new IOException("Unknown title asset");
        return prepareSupplementAsset(TitleOracleContract.ASSET_DIRECTORY, asset);
    }

    File prepareWatermarkAsset(OracleContract.Asset asset) throws IOException {
        if (!WatermarkOracleContract.ASSETS.contains(asset)) throw new IOException("Unknown watermark asset");
        return prepareSupplementAsset(WatermarkOracleContract.ASSET_DIRECTORY, asset);
    }

    private File prepareSupplementAsset(String directory, OracleContract.Asset asset) throws IOException {
        checkInterrupted();
        File root = new File(context.getFilesDir().getCanonicalFile(), directory);
        if (!root.getCanonicalFile().equals(root.getAbsoluteFile())) {
            throw new IOException("Linked supplement directory");
        }
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create supplement directory");
        File output = new File(root, asset.path);
        if (!output.getCanonicalFile().equals(output.getAbsoluteFile())) {
            throw new IOException("Linked supplement asset output");
        }
        if (output.isFile() && output.length() == asset.bytes && asset.sha256.equals(sha256(output))) {
            return output;
        }
        try {
            copyAsset(directory + "/" + asset.path, output);
            if (output.length() != asset.bytes || !asset.sha256.equals(sha256(output))) {
                throw new IOException("Supplement asset integrity mismatch: " + asset.path);
            }
        } catch (IOException error) {
            if (output.exists() && !output.delete()) {
                error.addSuppressed(new IOException("Cannot remove incomplete supplement asset"));
            }
            throw error;
        }
        return output;
    }

    private synchronized void validateAssets() throws IOException {
        if (validated) {
            return;
        }
        checkInterrupted();
        String contractHash = sha256(readAsset(CONTRACT_ASSET));
        if (!contract.pins.assetContractSha256.equals(contractHash)) {
            throw new IOException("Contract asset hash mismatch");
        }
        if (IntroOracleContract.VERSION.equals(contract.version)) {
            for (OracleContract.Asset asset : IntroOracleContract.ASSETS) {
                checkInterrupted();
                byte[] bytes = readAsset(IntroOracleContract.ASSET_DIRECTORY + "/" + asset.path);
                if (bytes.length != asset.bytes || !asset.sha256.equals(sha256(bytes))) {
                    throw new IOException("Intro supplement integrity mismatch: " + asset.path);
                }
            }
        }
        validateAsset(contract.fixture);
        if (WatermarkOracleContract.VERSION.equals(contract.version)) {
            for (OracleContract.Asset asset : WatermarkOracleContract.ASSETS) {
                checkInterrupted();
                byte[] bytes = readAsset(WatermarkOracleContract.ASSET_DIRECTORY + "/" + asset.path);
                if (bytes.length != asset.bytes || !asset.sha256.equals(sha256(bytes))) {
                    throw new IOException("Watermark supplement integrity mismatch: " + asset.path);
                }
            }
        }
        if (TitleOracleContract.VERSION.equals(contract.version)) {
            for (OracleContract.Asset asset : TitleOracleContract.ASSETS) {
                checkInterrupted();
                byte[] bytes = readAsset(TitleOracleContract.ASSET_DIRECTORY + "/" + asset.path);
                if (bytes.length != asset.bytes || !asset.sha256.equals(sha256(bytes))) {
                    throw new IOException("Title supplement integrity mismatch: " + asset.path);
                }
            }
        }
        if (TextOracleContract.VERSION.equals(contract.version)) {
            for (OracleContract.Asset asset : TextOracleContract.ASSETS) {
                checkInterrupted();
                byte[] bytes = readAsset(TextOracleContract.ASSET_DIRECTORY + "/" + asset.path);
                if (bytes.length != asset.bytes || !asset.sha256.equals(sha256(bytes))) {
                    throw new IOException("Text supplement integrity mismatch: " + asset.path);
                }
            }
        }
        for (OracleContract.OracleCase oracleCase : contract.cases) {
            checkInterrupted();
            for (OracleContract.Probe probe : oracleCase.probes) {
                checkInterrupted();
                validateAsset(probe.image);
            }
            int start = (int) Math.round(oracleCase.operation.trimStartSeconds * contract.source.fps);
            int stop = (int) Math.round(oracleCase.operation.trimEndSeconds * contract.source.fps);
            if (oracleCase.frameAssets.size() != stop - start) {
                throw new IOException("Incomplete pinned expected frames: " + oracleCase.id);
            }
            for (int frame = start; frame < stop; frame++) {
                checkInterrupted();
                OracleContract.Asset asset = oracleCase.frameAssets.get(Integer.valueOf(frame));
                if (asset == null) {
                    throw new IOException("Missing pinned expected frame: " + oracleCase.id + "/" + frame);
                }
                validateAsset(asset);
            }
        }
        validated = true;
    }

    private void validateAsset(OracleContract.Asset asset) throws IOException {
        checkInterrupted();
        byte[] bytes = readAsset(assetPath(asset.path));
        if (bytes.length != asset.bytes) {
            throw new IOException("Asset length mismatch for " + asset.path);
        }
        String actualHash = sha256(bytes);
        if (!asset.sha256.equals(actualHash)) {
            throw new IOException("Asset hash mismatch for " + asset.path);
        }
    }

    private synchronized OracleCoreVerifier.RgbImage loadExpectedImage(String relativePath) throws IOException {
        checkInterrupted();
        OracleCoreVerifier.RgbImage cached = expectedImages.get(relativePath);
        if (cached != null) {
            return cached;
        }
        InputStream input = assets.open(assetPath(relativePath));
        try {
            OracleCoreVerifier.RgbImage image = decoder.loadAssetImage(input);
            expectedImages.put(relativePath, image);
            return image;
        } finally {
            input.close();
        }
    }

    private static String assetPath(String relativePath) {
        return ASSET_ROOT + "/" + relativePath.replace('\\', '/');
    }

    private byte[] readAsset(String assetPath) throws IOException {
        InputStream input = assets.open(assetPath);
        try {
            byte[] buffer = new byte[8192];
            int read;
            ByteCollector collector = new ByteCollector();
            while ((read = input.read(buffer)) >= 0) {
                checkInterrupted();
                collector.append(buffer, read);
            }
            return collector.toArray();
        } finally {
            input.close();
        }
    }

    private void copyAsset(String assetPath, File output) throws IOException {
        try (InputStream input = assets.open(assetPath);
             FileOutputStream stream = new FileOutputStream(output, false)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                checkInterrupted();
                stream.write(buffer, 0, read);
            }
            stream.flush();
        }
    }

    private static JSONObject toJson(Map<String, Object> map) throws IOException {
        return new JSONObject(map);
    }

    private Map<String, Object> reportPins() {
        Map<String, Object> pins = new LinkedHashMap<String, Object>();
        pins.put("source_root", contract.pins.sourceRoot);
        pins.put("handoff_version", contract.pins.handoffVersion);
        pins.put("fixture", assetPin(contract.fixture.path, contract.fixture.sha256));
        pins.put("asset_contract", assetPin(contract.pins.assetContractPath,
                contract.pins.assetContractSha256));
        Map<String, Object> manifest = assetPin(contract.pins.manifestPath, contract.pins.manifestSha256);
        manifest.put("schema", contract.pins.manifestSchema);
        manifest.put("version", contract.pins.manifestVersion);
        pins.put("manifest", manifest);
        pins.put("android_cases", assetPin(contract.pins.androidCasesPath, contract.pins.androidCasesSha256));
        pins.put("contract_text", assetPin(contract.pins.contractTextPath, contract.pins.contractTextSha256));
        pins.put("oracle_py", assetPin(contract.pins.oraclePyPath, contract.pins.oraclePySha256));
        if (IntroOracleContract.VERSION.equals(contract.version)) {
            Map<String, Object> supplement = new LinkedHashMap<>();
            supplement.put("version", IntroOracleContract.VERSION);
            supplement.put("contract_sha256", IntroOracleContract.CONTRACT_SHA256);
            supplement.put("assets", IntroOracleContract.ASSET_HASHES);
            pins.put("supplement", supplement);
        }
        if (TextOracleContract.VERSION.equals(contract.version)) {
            Map<String, Object> supplement = new LinkedHashMap<>();
            supplement.put("version", TextOracleContract.VERSION);
            supplement.put("contract_sha256", TextOracleContract.MANIFEST.sha256);
            supplement.put("assets", TextOracleContract.ASSET_HASHES);
            pins.put("supplement", supplement);
        }
        if (TitleOracleContract.VERSION.equals(contract.version)) {
            Map<String, Object> supplement = new LinkedHashMap<>();
            supplement.put("version", TitleOracleContract.VERSION);
            supplement.put("contract_sha256", TitleOracleContract.MANIFEST.sha256);
            supplement.put("assets", TitleOracleContract.ASSET_HASHES);
            supplement.put("expected_edit_config", contract.requireCase(TitleOracleContract.CASE_ID).androidEditConfig);
            pins.put("supplement", supplement);
        }
        if (WatermarkOracleContract.VERSION.equals(contract.version)) {
            Map<String, Object> supplement = new LinkedHashMap<>();
            supplement.put("version", WatermarkOracleContract.VERSION);
            supplement.put("contract_sha256", WatermarkOracleContract.MANIFEST.sha256);
            supplement.put("assets", WatermarkOracleContract.ASSET_HASHES);
            supplement.put("expected_edit_config", contract.requireCase(WatermarkOracleContract.CASE_ID).androidEditConfig);
            pins.put("supplement", supplement);
        }
        return pins;
    }

    private static Map<String, Object> assetPin(String path, String sha256) {
        Map<String, Object> pin = new LinkedHashMap<String, Object>();
        pin.put("path", path);
        pin.put("sha256", sha256);
        return pin;
    }

    private static String sha256(File file) throws IOException {
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[8192];
            int read;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        } finally {
            input.close();
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(bytes);
            return hex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException(error);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.US, "%02x", value & 0xFF));
        }
        return builder.toString();
    }

    private OracleContract.OracleCase findCase(String caseId) throws IOException {
        try {
            return contract.requireCase(caseId);
        } catch (IllegalArgumentException error) {
            throw new IOException("Unknown oracle case: " + caseId, error);
        }
    }

    private synchronized void clearExpectedImages() {
        expectedImages.clear();
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Oracle verification interrupted");
        }
    }

    private static final class ByteCollector {
        private byte[] bytes = new byte[8192];
        private int size;

        void append(byte[] source, int count) {
            ensure(size + count);
            System.arraycopy(source, 0, bytes, size, count);
            size += count;
        }

        byte[] toArray() {
            byte[] copy = new byte[size];
            System.arraycopy(bytes, 0, copy, 0, size);
            return copy;
        }

        private void ensure(int required) {
            if (required <= bytes.length) {
                return;
            }
            int next = bytes.length;
            while (next < required) {
                next *= 2;
            }
            byte[] grown = new byte[next];
            System.arraycopy(bytes, 0, grown, 0, size);
            bytes = grown;
        }
    }
}
