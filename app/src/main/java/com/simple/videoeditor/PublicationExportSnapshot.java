package com.simple.videoeditor;

import android.content.Context;
import org.json.JSONObject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Small independent per-output provenance, captured from the completed immutable configuration. */
final class PublicationExportSnapshot {
    private static String key(String uri) {
        return UUID.nameUUIDFromBytes(uri.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String credits(Context context, EditConfig config) throws IOException {
        if (config.replacementMusic != null)
            return "Imported replacement audio / 导入替换音频: rights unknown; verify permission / 授权未知，请核实。";
        if (!config.backgroundMusic.enabled)
            return "No library music added / 未添加曲库音乐。Original/imported audio rights not verified / 原始及导入音频授权未核实。";
        OfflineMusicCatalog.Track track = OfflineMusicCatalog.load(context).require(config.backgroundMusic.trackId);
        return track.title + " / " + track.titleZh + "\n" + track.author + "\n"
                + track.license + "\n" + track.licenseUrl + "\n" + track.sourceUrl + "\n"
                + "Library mix / 曲库混音: " + config.backgroundMusic.trackId
                + "; gain=" + config.backgroundMusic.gain + "; muteOriginal=" + config.backgroundMusic.muteOriginal
                + "\nOther source audio rights not verified / 其他源音频授权未核实。";
    }

    static void record(Context context, PublishedVideo video, EditConfig config) throws Exception {
        if (video.uri == null) return;
        String descriptor = new JSONObject().put("uri", video.uri.toString()).put("name", video.name)
                .put("musicCredits", credits(context, config)).toString();
        if (!context.getSharedPreferences("publication-export-snapshots", Context.MODE_PRIVATE).edit()
                .putString(key(video.uri.toString()), descriptor).commit())
            throw new IOException("Cannot persist export music provenance");
    }

    static JSONObject load(Context context, String uri) throws Exception {
        String descriptor = context.getSharedPreferences("publication-export-snapshots", Context.MODE_PRIVATE)
                .getString(key(uri), null);
        if (descriptor == null) throw new IOException("Export provenance unavailable; create a draft and select media explicitly");
        JSONObject value = new JSONObject(descriptor);
        if (!uri.equals(value.getString("uri"))) throw new IOException("Export association mismatch");
        return value;
    }
}
