package com.simple.videoeditor;

import org.json.JSONException;
import org.json.JSONObject;

/** Independent library selection; a disabled selection is retained, never implicitly substituted. */
public final class BackgroundMusic {
    public static final BackgroundMusic OFF = new BackgroundMusic(false, "", 1f, false);
    public final boolean enabled, muteOriginal;
    public final String trackId;
    public final float gain;

    public BackgroundMusic(boolean enabled, String trackId, float gain, boolean muteOriginal) {
        if (trackId == null || (!trackId.isEmpty() && !trackId.matches("[a-z0-9][a-z0-9_-]{0,79}"))) {
            throw new IllegalArgumentException("Invalid library track ID");
        }
        if (Float.isNaN(gain) || gain < .5f || gain > 1f) {
            throw new IllegalArgumentException("BGM gain must be 50..100 percent");
        }
        if (enabled && trackId.isEmpty()) throw new IllegalArgumentException("Select an offline library track");
        this.enabled = enabled;
        this.trackId = trackId;
        this.gain = gain;
        this.muteOriginal = muteOriginal;
    }

    static BackgroundMusic fromRecipe(JSONObject recipe) throws JSONException {
        if (!recipe.has("bgm")) {
            if (recipe.optInt("version", 0) >= 4) throw new JSONException("Missing BGM semantics");
            return OFF;
        }
        JSONObject value = recipe.getJSONObject("bgm");
        if (value.getInt("version") != 1 || !"library-mix".equals(value.getString("mode"))) {
            throw new JSONException("Unsupported BGM semantics");
        }
        return new BackgroundMusic(value.getBoolean("enabled"), value.getString("trackId"),
                (float) value.getDouble("gain"), value.getBoolean("muteOriginal"));
    }

    void putRecipe(JSONObject recipe) throws JSONException {
        recipe.put("bgm", new JSONObject().put("version", 1).put("mode", "library-mix")
                .put("enabled", enabled).put("trackId", trackId).put("gain", gain)
                .put("muteOriginal", muteOriginal));
    }

    @Override public String toString() {
        return "library-mix enabled=" + enabled + " track=" + trackId
                + " gain=" + gain + " muteOriginal=" + muteOriginal;
    }
}
