package com.simple.videoeditor;

import android.content.Context;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/** Named recipes own durable assets, never the source video or the merge selection. */
final class WholeEditPresets {
    private static final MediaCopy.Progress NO_PROGRESS = bytes -> {};
    private static final Object STORE_LOCK = new Object();
    private static final java.util.Set<String> IN_FLIGHT = new java.util.HashSet<>();
    private final File directory;
    private final AtomicFile index;
    private final Context context;

    WholeEditPresets(Context context) {
        this.context = context.getApplicationContext();
        directory = new File(context.getFilesDir(), "whole-edit-presets");
        index = new AtomicFile(new File(directory, "index.json"));
    }

    String[] names() throws Exception {
        synchronized (STORE_LOCK) { return namesLocked(); }
    }

    private String[] namesLocked() throws Exception {
        List<String> names = new ArrayList<>();
        Iterator<String> keys = readIndex().keys();
        while (keys.hasNext()) names.add(keys.next());
        java.util.Collections.sort(names);
        return names.toArray(new String[0]);
    }

    JSONObject load(String name) throws Exception {
        synchronized (STORE_LOCK) { return loadLocked(name); }
    }

    private JSONObject loadLocked(String name) throws Exception {
        JSONObject recipe = readIndex().getJSONObject(name);
        int version = recipe.getInt("version");
        if (version == 1) {
            JSONArray old = recipe.getJSONArray("crop");
            recipe.put("crop", new JSONArray(CropRemoval.removed((float) old.getDouble(0),
                    (float) old.getDouble(1), (float) old.getDouble(2), (float) old.getDouble(3))));
            recipe.put("version", 2);
        } else if (version != 2 && version != 3 && version != 4 && version != 5 && version != 6) {
            throw new IOException("Unsupported whole-edit preset version: " + version);
        }
        crop(recipe);
        migrateTitle(recipe);
        ColorAdjustment.fromRecipe(recipe);
        VideoBorder.fromRecipe(recipe).validateTitle(recipe.optBoolean("titleEnabled", false));
        OfflineMusicCatalog.load(context).validateSelection(BackgroundMusic.fromRecipe(recipe));
        for (String key : new String[]{"introAsset", "musicAsset", "pngAsset"}) {
            File file = asset(recipe, key);
            if (version >= 2 && recipe.getBoolean(key + "Required") != (file != null)) {
                throw new IOException("Missing asset reference: " + key);
            }
        }
        return recipe;
    }

    void save(String name, JSONObject recipe, File intro, File music, File png) throws Exception {
        saveInternal(name, recipe, intro, null, music, png, NO_PROGRESS);
    }

    void saveWithVideoSource(String name, JSONObject recipe, VideoSource intro,
                                          File music, File png, MediaCopy.Progress progress) throws Exception {
        saveInternal(name, recipe, null, intro, music, png, progress == null ? NO_PROGRESS : progress);
    }

    private void saveInternal(String name, JSONObject recipe, File introFile, VideoSource introSource,
                              File music, File png, MediaCopy.Progress progress) throws Exception {
        name = name.trim();
        if (name.isEmpty() || name.length() > 80) throw new IOException("Preset name must be 1–80 characters");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create preset directory");
        synchronized (STORE_LOCK) {
            JSONObject all = readIndex();
            if (!all.has(name) && all.length() >= 30) throw new IOException("At most 30 whole-edit presets");
        }
        if (recipe.has("title")) IntroTemplate.fromJsonStrict(recipe.getJSONObject("title"));
        JSONObject saved = new JSONObject(recipe.toString());
        migrateTitle(saved);
        List<File> created = new ArrayList<>();
        boolean committed = false;
        try {
            VideoBorder.fromRecipe(saved).validateTitle(saved.optBoolean("titleEnabled", false));
            BackgroundMusic bgm = BackgroundMusic.fromRecipe(saved);
            OfflineMusicCatalog.load(context).validateSelection(bgm);
            bgm.putRecipe(saved);
            saved.put("colorAdjustment", ColorAdjustment.fromRecipe(saved).toJson());
            saved.put("version", 6);
            saved.put("introAssetRequired", introFile != null || introSource != null).put("musicAssetRequired", music != null)
                    .put("pngAssetRequired", png != null);
            saved.put("introAsset", introSource != null
                    ? copy(introSource, EditConfig.MAX_VIDEO_BYTES, created, progress)
                    : copy(introFile, EditConfig.MAX_VIDEO_BYTES, created));
            saved.put("musicAsset", copy(music, EditConfig.MAX_VIDEO_BYTES, created));
            saved.put("pngAsset", copy(png, PngWatermark.MAX_BYTES, created));
            synchronized (STORE_LOCK) {
                MediaCopy.checkCancelled();
                JSONObject all = readIndex();
                if (!all.has(name) && all.length() >= 30) throw new IOException("At most 30 whole-edit presets");
                all.put(name, saved);
                writeIndex(all);
                committed = true;
                for (File file : created) IN_FLIGHT.remove(file.getAbsolutePath());
                collectUnused(all);
            }
        } finally {
            synchronized (STORE_LOCK) {
                for (File file : created) {
                    IN_FLIGHT.remove(file.getAbsolutePath());
                    if (!committed) file.delete();
                }
            }
        }
    }

    void delete(String name) throws Exception {
        synchronized (STORE_LOCK) {
            JSONObject all = readIndex();
            all.remove(name);
            writeIndex(all);
            collectUnused(all);
        }
    }

    File asset(JSONObject recipe, String key) throws Exception {
        if (recipe.isNull(key)) return null;
        JSONObject ref = recipe.getJSONObject(key);
        String name = ref.getString("file");
        if (!name.matches("[a-f0-9-]+\\.asset")) throw new IOException("Invalid preset asset name");
        File file = new File(directory, name);
        if (!file.isFile() || file.length() != ref.getLong("bytes")
                || !digest(file).equals(ref.getString("sha256"))) {
            throw new IOException("Missing or changed preset asset: " + key + "; preset not applied");
        }
        return file;
    }

    static float[] crop(JSONObject recipe) throws JSONException {
        JSONArray values = recipe.getJSONArray("crop");
        return CropRemoval.retained(values.getString(0), values.getString(1),
                values.getString(2), values.getString(3));
    }

    private static void migrateTitle(JSONObject recipe) throws JSONException {
        if (recipe.has("title")) recipe.put("title", new JSONObject(
                IntroTemplate.fromJsonStrict(recipe.getJSONObject("title")).toJson()));
    }

    static long[] trim(JSONObject recipe, long duration) throws Exception {
        long head = recipe.getLong("headMs"), tail = recipe.getLong("tailMs");
        if (head < 0 || tail < 0 || tail >= duration || head >= duration - tail) {
            throw new IOException("Source too short for saved start offset/end cut; preset not applied");
        }
        return new long[]{head, duration - tail};
    }

    private JSONObject copy(File source, long limit, List<File> created) throws Exception {
        if (source == null) return null;
        if (!source.isFile() || source.length() <= 0 || source.length() > limit) {
            throw new IOException("Preset asset missing or exceeds bounded size");
        }
        return copyAsset(() -> new FileInputStream(source), source.length(), limit, created, NO_PROGRESS,
                "Preset asset missing or exceeds bounded size");
    }

    private JSONObject copy(VideoSource source, long limit, List<File> created, MediaCopy.Progress progress)
            throws Exception {
        if (source == null) return null;
        source.checkCurrent(context);
        if (source.sizeBytes == 0 || source.sizeBytes > limit) {
            throw new IOException("Preset asset missing or exceeds bounded size");
        }
        JSONObject copied = copyAsset(() -> openVideoInputStream(source), source.sizeBytes, limit, created,
                progress == null ? NO_PROGRESS : progress, "Video source size changed");
        source.checkCurrent(context);
        return copied;
    }

    private JSONObject copyAsset(StreamSource source, long expectedBytes, long limit, List<File> created,
                                 MediaCopy.Progress progress, String sizeMismatchMessage) throws Exception {
        requireAvailableSpace(expectedBytes);
        File target = new File(directory, UUID.randomUUID() + ".asset");
        synchronized (STORE_LOCK) {
            created.add(target);
            IN_FLIGHT.add(target.getAbsolutePath());
        }
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long written;
        try (InputStream input = source.open();
             FileOutputStream output = new FileOutputStream(target);
             DigestOutputStream hashed = new DigestOutputStream(output, digest)) {
            written = MediaCopy.copy(input, hashed, limit, progress);
            hashed.flush();
            output.getFD().sync();
        }
        if (written <= 0) throw new IOException("Preset asset missing or exceeds bounded size");
        if (expectedBytes > 0 && written != expectedBytes) throw new IOException(sizeMismatchMessage);
        return new JSONObject().put("file", target.getName()).put("bytes", written)
                .put("sha256", hex(digest.digest()));
    }

    private void requireAvailableSpace(long bytes) throws IOException {
        if (bytes <= 0) return;
        long usable = directory.getUsableSpace();
        if (usable >= 0 && usable < bytes) throw new IOException("Insufficient preset storage space");
    }

    private InputStream openVideoInputStream(VideoSource source) throws Exception {
        try {
            return VideoSource.openReadStream(context, source.uri);
        } catch (SecurityException error) {
            throw new IOException("Read access to video source was denied", error);
        }
    }

    private interface StreamSource {
        InputStream open() throws Exception;
    }

    private static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    private JSONObject readIndex() throws Exception {
        if (!directory.exists()) return new JSONObject();
        try (FileInputStream input = index.openRead(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (bytes.size() + n > 1024 * 1024) throw new IOException("Preset index exceeds 1 MiB");
                bytes.write(buffer, 0, n);
            }
            return new JSONObject(bytes.toString("UTF-8"));
        } catch (java.io.FileNotFoundException missing) {
            if (!index.getBaseFile().exists()) return new JSONObject();
            throw missing;
        }
    }

    private void writeIndex(JSONObject all) throws IOException {
        FileOutputStream output = index.startWrite();
        try {
            output.write(all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            index.finishWrite(output);
        } catch (IOException error) {
            index.failWrite(output);
            throw error;
        }
    }

    private void collectUnused(JSONObject all) throws JSONException {
        java.util.Set<String> used = new java.util.HashSet<>();
        Iterator<String> names = all.keys();
        while (names.hasNext()) {
            JSONObject recipe = all.getJSONObject(names.next());
            for (String key : new String[]{"introAsset", "musicAsset", "pngAsset"}) {
                if (!recipe.isNull(key)) used.add(recipe.getJSONObject(key).getString("file"));
            }
        }
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) {
            if (file.getName().endsWith(".asset") && !used.contains(file.getName())
                    && !IN_FLIGHT.contains(file.getAbsolutePath())) file.delete();
        }
    }

    private static String digest(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
}
