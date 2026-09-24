package com.simple.videoeditor;

import android.content.Context;

/** Shared production preference; each export snapshots it before preparing any clips. */
final class VideoEncodingSettings {
    private static final String PREFERENCES = "video-encoding";
    private static final String COMPATIBILITY = "software-avc";

    static boolean compatibilityEnabled(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(COMPATIBILITY, false);
    }

    static void setCompatibilityEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(COMPATIBILITY, enabled).apply();
    }

    static boolean setCompatibilityEnabledAndWait(Context context, boolean enabled) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putBoolean(COMPATIBILITY, enabled).commit();
    }

    static String modeName(boolean enabled) {
        return enabled ? "software-avc" : "default";
    }

    private VideoEncodingSettings() {}
}
