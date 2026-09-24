package com.simple.videoeditor;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/** APK-only catalog. Rights approval is a packaging review, not inferred from a license label. */
final class OfflineMusicCatalog {
    static final int MAX_TRACKS = 256;
    static final int MAX_CATALOG_BYTES = 1024 * 1024;
    static final long MAX_LIBRARY_BYTES = 256L * 1024 * 1024;
    static final long MAX_TRACK_BYTES = 12 * 1024 * 1024;
    interface Source { InputStream open(String path) throws IOException; }
    private final Source source;
    final List<Track> tracks;

    static OfflineMusicCatalog load(Context context) throws IOException {
        return load(path -> context.getAssets().open(path));
    }

    static OfflineMusicCatalog load(Source source) throws IOException {
        try (InputStream input = source.open("music/catalog.json");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (bytes.size() + n > MAX_CATALOG_BYTES) throw new IOException("Music catalog too large");
                bytes.write(buffer, 0, n);
            }
            JSONObject root = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (root.getInt("version") != 1) throw new IOException("Unsupported music catalog version");
            JSONArray rows = root.getJSONArray("tracks");
            if (rows.length() > MAX_TRACKS) throw new IOException("Too many library tracks");
            List<Track> tracks = new ArrayList<>();
            HashSet<String> ids = new HashSet<>(), paths = new HashSet<>();
            long total = 0;
            for (int i = 0; i < rows.length(); i++) {
                Track track = new Track(rows.getJSONObject(i));
                if (!ids.add(track.id) || !paths.add(track.path)) throw new IOException("Duplicate music track");
                total += track.bytes;
                if (total > MAX_LIBRARY_BYTES) throw new IOException("Music library exceeds 256 MiB");
                tracks.add(track);
            }
            return new OfflineMusicCatalog(source, tracks);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Invalid music catalog", error);
        }
    }

    private OfflineMusicCatalog(Source source, List<Track> tracks) {
        this.source = source;
        this.tracks = Collections.unmodifiableList(tracks);
    }

    Track require(String id) throws IOException {
        for (Track track : tracks) if (track.id.equals(id)) return track;
        throw new IOException("Missing offline library track: " + id + "; no substitute selected");
    }

    void validateSelection(BackgroundMusic selection) throws IOException {
        if (!selection.trackId.isEmpty()) require(selection.trackId);
    }

    /** A fresh, exclusively owned export file survives UI selection changes; caller owns cleanup. */
    void copyVerified(String id, File destination) throws IOException {
        Track track = require(id);
        if (!destination.createNewFile()) throw new IOException("Music snapshot already exists");
        boolean complete = false;
        try (InputStream input = source.open(track.path);
             FileOutputStream output = new FileOutputStream(destination)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65536];
            long count = 0;
            int n;
            while ((n = input.read(buffer)) != -1) {
                count += n;
                if (Thread.currentThread().isInterrupted() || count > track.bytes) {
                    throw new IOException("Music asset exceeds declared bound or copy cancelled");
                }
                digest.update(buffer, 0, n);
                output.write(buffer, 0, n);
            }
            output.getFD().sync();
            StringBuilder hash = new StringBuilder(64);
            for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (count != track.bytes || !hash.toString().equals(track.sha256)) {
                throw new IOException("Music asset hash/size mismatch: " + id);
            }
            if (track.isVorbis()) validateOgg(destination, track);
            else validateWav(destination, track);
            complete = true;
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Cannot verify music asset: " + id, error);
        } finally {
            if (!complete) destination.delete();
        }
    }

    private static void validateWav(File file, Track track) throws IOException {
        // Canonical PCM avoids MP3/AAC encoder delay/padding at every loop seam.
        byte[] header = new byte[44];
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) { input.readFully(header); }
        ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        if (b.getInt(0) != 0x46464952 || b.getInt(8) != 0x45564157
                || b.getInt(12) != 0x20746d66 || b.getInt(16) != 16
                || b.getShort(20) != 1 || b.getShort(22) != 2 || b.getInt(24) != 48000
                || b.getInt(28) != 192000 || b.getShort(32) != 4 || b.getShort(34) != 16
                || b.getInt(36) != 0x61746164 || b.getInt(4) != file.length() - 8
                || b.getInt(40) != file.length() - 44 || (file.length() - 44) % 4 != 0
                || track.frames != (file.length() - 44) / 4) {
            throw new IOException("Music must be canonical 48000 Hz stereo PCM16 WAV: " + track.id);
        }
    }

    private static void validateOgg(File file, Track track) throws IOException {
        // Check the original stream's identification and final granule, without decoding or rewriting it.
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            long granule = -1, serial = -1;
            int pages = 0;
            boolean eos = false;
            while (input.getFilePointer() < input.length()) {
                byte[] header = new byte[27];
                input.readFully(header);
                ByteBuffer b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
                if (b.getInt(0) != 0x5367674f || header[4] != 0 || eos || ++pages > 8192)
                    throw new IOException("Invalid bounded Ogg stream");
                long currentSerial = Integer.toUnsignedLong(b.getInt(14));
                if (pages == 1) serial = currentSerial;
                if (serial != currentSerial || b.getInt(18) != pages - 1)
                    throw new IOException("Chained or discontinuous Ogg stream");
                int segments = header[26] & 255, length = 0;
                for (int i = 0; i < segments; i++) length += input.readUnsignedByte();
                byte[] body = new byte[length];
                input.readFully(body);
                if (pages == 1) {
                    ByteBuffer id = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN);
                    if ((header[5] & 2) == 0 || body.length != 30 || body[0] != 1
                            || !new String(body, 1, 6, StandardCharsets.US_ASCII).equals("vorbis")
                            || id.getInt(7) != 0 || body[11] != track.channels || id.getInt(12) != track.sampleRate)
                        throw new IOException("Music must match original Vorbis channels and sample rate");
                }
                granule = b.getLong(6);
                eos = (header[5] & 4) != 0;
            }
            if (!eos || granule != track.frames) throw new IOException("Ogg frame count mismatch");
        }
    }

    static final class Track {
        final String id, title, titleZh, mood, moodZh, style, styleZh;
        final String path, license, licenseUrl, sourceUrl, author, sha256, sourceSha256;
        final long bytes, frames;
        final String format, playbackKind;
        final int sampleRate, channels;
        private Track(JSONObject row) throws Exception {
            id = text(row, "id");
            if (!id.matches("[a-z0-9][a-z0-9_-]{0,79}")) throw new IOException("Invalid track ID");
            path = text(row, "path");
            if (!path.matches("music/[a-z0-9_-]+/(?:[a-z0-9_-]+/)?[a-z0-9_-]+\\.(?:wav|ogg)")) {
                throw new IOException("Invalid normalized music asset path");
            }
            title = text(row, "title"); titleZh = text(row, "titleZh");
            mood = text(row, "mood"); moodZh = text(row, "moodZh");
            style = text(row, "style"); styleZh = text(row, "styleZh");
            license = text(row, "license"); licenseUrl = url(row, "licenseUrl");
            sourceUrl = url(row, "source"); author = text(row, "author");
            if (!license.equals("CC0-1.0") && !license.equals("Public-domain")) {
                throw new IOException("Only reviewed CC0/public-domain recordings may be packaged");
            }
            sha256 = text(row, "sha256"); sourceSha256 = text(row, "sourceSha256");
            if (!sha256.matches("[0-9a-f]{64}") || !sourceSha256.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid music hash");
            }
            bytes = row.getLong("bytes"); frames = row.getLong("frames");
            format = text(row, "format");
            playbackKind = row.has("playbackKind") ? text(row, "playbackKind") : "creator-loop";
            if (!playbackKind.equals("creator-loop") && !playbackKind.equals("composition-repeat")) {
                throw new IOException("Invalid music playback kind");
            }
            boolean vorbis = isVorbis();
            sampleRate = vorbis ? 44100 : 48000;
            channels = format.equals("ogg-vorbis-44100-mono") ? 1 : 2;
            if (row.optInt("sampleRate", sampleRate) != sampleRate
                    || row.optInt("channels", channels) != channels) {
                throw new IOException("Music format/metadata mismatch");
            }
            long maxSeconds = 180L;
            if (bytes <= 44 || bytes > MAX_TRACK_BYTES || frames <= 0 || frames > maxSeconds * sampleRate
                    || (vorbis ? !path.endsWith(".ogg") || !sha256.equals(sourceSha256)
                    : !path.endsWith(".wav") || bytes != 44 + frames * 4 || !format.equals("pcm16le-48000-stereo"))) {
                throw new IOException("Invalid normalized music bounds/format");
            }
        }
        boolean isVorbis() {
            return format.equals("ogg-vorbis-44100-stereo") || format.equals("ogg-vorbis-44100-mono");
        }
        private static String text(JSONObject row, String key) throws Exception {
            String value = row.getString(key);
            if (value.trim().isEmpty() || value.length() > 1024 || value.matches("(?s).*\\p{Cntrl}.*")) {
                throw new IOException("Invalid music metadata: " + key);
            }
            return value;
        }
        private static String url(JSONObject row, String key) throws Exception {
            String value = text(row, key);
            java.net.URI uri = new java.net.URI(value);
            if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
                throw new IOException("Invalid music reference URL");
            }
            return value;
        }
        String label(boolean chinese) {
            return (chinese ? moodZh + " / " + styleZh + " - " + titleZh : mood + " / " + style + " - " + title)
                    + String.format(Locale.ROOT, " (%.1fs)", frames / (double) sampleRate)
                    + (playbackKind.equals("composition-repeat")
                    ? (chinese ? " · 乐曲，可重复播放／衔接不保证无缝"
                    : " · Composition; repeatable, not guaranteed seamless") : "");
        }
        String notice() {
            return title + "\n" + author + "\n" + license + "\n" + licenseUrl
                    + "\n" + sourceUrl + "\nSHA-256: " + sha256 + "\nSource SHA-256: " + sourceSha256;
        }
    }
}
