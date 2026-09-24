package com.simple.videoeditor;

import org.json.JSONException;
import org.json.JSONObject;

/** Opaque inset border; percentages refer to the final canvas's shorter side. */
public final class VideoBorder {
    public enum Scope { TITLE, WHOLE }
    private static final int[] COLORS = {0xFFFF0000, 0xFFFFD600, 0xFF0066FF, 0xFF00CC66, 0xFFFFFFFF, 0xFF000000};
    public static final VideoBorder OFF = new VideoBorder(false, 0, 2, Scope.TITLE);
    public final boolean enabled;
    public final int colorIndex;
    public final int percent;
    public final Scope scope;

    public VideoBorder(boolean enabled, int colorIndex, int percent, Scope scope) {
        if (colorIndex < 0 || colorIndex >= COLORS.length || percent < 1 || percent > 8 || scope == null) {
            throw new IllegalArgumentException("Border requires an opaque palette color, 1–8 percent and a scope");
        }
        this.enabled = enabled;
        this.colorIndex = colorIndex;
        this.percent = percent;
        this.scope = scope;
    }

    public int color() { return COLORS[colorIndex]; }
    public boolean appliesTo(boolean generatedTitle) {
        return enabled && (scope == Scope.WHOLE || generatedTitle);
    }
    public void validateTitle(boolean generatedTitleEnabled) {
        if (enabled && scope == Scope.TITLE && !generatedTitleEnabled) {
            throw new IllegalArgumentException("Title-only border requires an enabled generated title");
        }
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("enabled", enabled).put("color", colorIndex)
                .put("percent", percent).put("scope", scope.name());
    }

    static VideoBorder fromRecipe(JSONObject recipe) throws JSONException {
        if (!recipe.has("border")) return OFF;
        JSONObject value = recipe.getJSONObject("border");
        return new VideoBorder(value.getBoolean("enabled"), value.getInt("color"),
                value.getInt("percent"), Scope.valueOf(value.getString("scope")));
    }

    @Override public String toString() {
        return "enabled=" + enabled + ", rgb=" + Integer.toHexString(color())
                + ", shortSidePercent=" + percent + ", scope=" + scope;
    }
}
