package com.simple.videoeditor;

import android.app.Application;

public final class ReportApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                SavedReport.recordUncaught(error);
            } finally {
                if (previous != null) previous.uncaughtException(thread, error);
            }
        });
    }
}
