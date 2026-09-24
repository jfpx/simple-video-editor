package com.simple.videoeditor;

import android.content.Context;
import android.os.Build;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

/** UI language only; callers switch languages only while the editor is idle. */
public final class UiLocales {
    private static final String PREFERENCES = "ui_locales";
    private static final String LANGUAGE = "language";

    private UiLocales() {}

    public static void initialize(Context context) {
        String language = isEnglish(context) ? "en" : "zh-CN";
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(LANGUAGE, language).apply();
        LocaleListCompat locales = LocaleListCompat.forLanguageTags(language);
        if (!locales.equals(AppCompatDelegate.getApplicationLocales())) {
            AppCompatDelegate.setApplicationLocales(locales);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Api33.initialize(context, language);
        }
    }

    public static void switchLanguage(AppCompatActivity activity) {
        String language = isEnglish(activity) ? "zh-CN" : "en";
        activity.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit().putString(LANGUAGE, language).apply();
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(language));
    }

    public static boolean isEnglish(Context context) {
        return "en".equals(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getString(LANGUAGE, "zh-CN"));
    }

    @androidx.annotation.RequiresApi(33)
    private static final class Api33 {
        static void initialize(Context context, String language) {
            // AppCompat 1.6.1 has no registered delegate before the first super.onCreate().
            android.app.LocaleManager manager = context.getSystemService(android.app.LocaleManager.class);
            android.os.LocaleList locales = android.os.LocaleList.forLanguageTags(language);
            if (manager != null && !locales.equals(manager.getApplicationLocales())) {
                manager.setApplicationLocales(locales);
            }
        }
    }
}
