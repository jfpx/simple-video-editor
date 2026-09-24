package com.simple.videoeditor;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.IOException;

public final class OutputSharing {
    private OutputSharing() {}

    /** May also throw ActivityNotFoundException, SecurityException or IllegalArgumentException. */
    public static void preview(Activity activity, File file) throws IOException {
        Uri uri = readableUri(activity, file);
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4");
        launch(activity, intent, uri, file.getName(), activity.getString(R.string.secondary_preview_chooser));
    }

    static Intent prepareVideo(Activity activity, PublishedVideo video, boolean share) throws IOException {
        if (video == null) throw new IOException("No completed video available");
        Uri uri = video.uri == null ? readableUri(activity, video.privateFile) : video.uri;
        try (android.os.ParcelFileDescriptor descriptor =
                     activity.getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null || descriptor.getStatSize() == 0) {
                throw new IOException("Video unavailable or empty");
            }
        }
        Intent intent = share ? new Intent(Intent.ACTION_SEND).setType("video/mp4")
                .putExtra(Intent.EXTRA_STREAM, uri)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4");
        intent.setClipData(ClipData.newRawUri(video.name, uri));
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    static void launchVideo(Activity activity, Intent intent) {
        Uri uri = intent.getData();
        if (uri == null) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        launch(activity, intent, uri, activity.getString(R.string.secondary_exported_mp4),
                activity.getString(R.string.secondary_open_share_video));
    }

    /** May also throw ActivityNotFoundException, SecurityException or IllegalArgumentException. */
    public static void shareReport(Activity activity, File file) throws IOException {
        launchReport(activity, prepareReport(activity, file), activity.getString(R.string.secondary_share_report));
    }

    static Intent prepareReport(Activity activity, File file) throws IOException {
        Uri uri = readableUri(activity, file);
        return new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .putExtra(Intent.EXTRA_SUBJECT, activity.getString(R.string.secondary_report_subject));
    }

    public static void savedReport(Activity activity, Uri uri, boolean share) throws IOException {
        Intent intent = prepareSavedReport(activity, uri, share);
        launchReport(activity, intent, activity.getString(
                share ? R.string.secondary_share_saved : R.string.secondary_open_report));
    }

    static Intent prepareSavedReport(Activity activity, Uri uri, boolean share) throws IOException {
        if (uri == null) throw new IOException("No selected TXT");
        try (android.os.ParcelFileDescriptor descriptor =
                     activity.getContentResolver().openFileDescriptor(uri, "r")) {
            if (descriptor == null || descriptor.getStatSize() == 0) {
                throw new IOException("Selected TXT is empty or unavailable");
            }
        }
        return share ? new Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_STREAM, uri)
                : new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain");
    }

    static void launchReport(Activity activity, Intent intent, String title) {
        Uri uri = intent.getData();
        if (uri == null) uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        launch(activity, intent, uri, activity.getString(R.string.secondary_full_report_clip), title);
    }

    private static Uri readableUri(Activity activity, File file) throws IOException {
        if (file == null) {
            throw new IOException("No completed file is available");
        }
        File canonical = file.getCanonicalFile();
        String exports = new File(activity.getFilesDir(), "exports").getCanonicalPath()
                + File.separator;
        String selftest = new File(activity.getFilesDir(), "selftest").getCanonicalPath()
                + File.separator;
        if (!canonical.getPath().startsWith(exports)
                && !canonical.getPath().startsWith(selftest)) {
            throw new IOException("File is outside the app's exports/selftest directories: " + file);
        }
        if (!canonical.isFile() || !canonical.canRead() || canonical.length() == 0) {
            throw new IOException("File is missing, empty or unreadable: " + file);
        }
        return FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", canonical);
    }

    private static void launch(Activity activity, Intent intent, Uri uri,
            String name, String title) {
        ClipData clip = ClipData.newRawUri(name, uri);
        intent.setClipData(clip);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        Intent chooser = Intent.createChooser(intent, title);
        chooser.setClipData(clip);
        chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        activity.startActivity(chooser);
    }
}
