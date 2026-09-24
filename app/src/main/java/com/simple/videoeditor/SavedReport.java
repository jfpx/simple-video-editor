package com.simple.videoeditor;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.AtomicFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.locks.ReentrantLock;

/** Private committed journal first, then append-only, closed/fsynced SAF checkpoints. */
final class SavedReport {
    private static volatile SavedReport active;
    private final Context context;
    private final Uri uri;
    private final String destination;
    private final ReentrantLock lock = new ReentrantLock();
    private int previousLength;
    private byte[] previousHash;
    private boolean initialized;
    private boolean failed;

    SavedReport(Context context, Uri uri, String destination) {
        this.context = context.getApplicationContext();
        this.uri = uri;
        this.destination = destination;
    }

    static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences("saved-selftest-report", Context.MODE_PRIVATE);
    }

    static File snapshot(Context context) {
        return new File(context.getFilesDir(), "selftest/saved-report.txt");
    }

    static Uri lastUri(Context context) {
        String value = preferences(context).getString("uri", "");
        return value.isEmpty() ? null : Uri.parse(value);
    }

    static String location(Context context) {
        SharedPreferences prefs = preferences(context);
        return prefs.getString("destination", "尚未选择 TXT 保存位置")
                + "\n" + prefs.getString("state", "请选择本机 Downloads / 下载目录；不会自动上传。")
                + "\n" + prefs.getString("error", "");
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static synchronized SavedReport create(Context context, Uri uri, int grantedFlags) throws IOException {
        return create(context, uri, grantedFlags, SelfTestPlan.Mode.FULL);
    }

    @androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
    static synchronized SavedReport create(Context context, Uri uri, int grantedFlags,
            SelfTestPlan.Mode mode) throws IOException {
        SelfTestPlan plan = new SelfTestPlan(mode);
        if (active != null || SelfTestRunner.hasRunningSuite()) {
            throw new IOException("Previous suite/report still owns cleanup. Wait before choosing a new TXT.");
        }
        int flags = grantedFlags & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        int required = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
        if (flags != required) throw new IOException("Provider did not grant read/write access");
        context.getContentResolver().takePersistableUriPermission(uri, flags);
        String name = "report.txt";
        try (Cursor cursor = context.getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) name = cursor.getString(0);
        }
        // Reject pipes/non-seekable providers and existing content, never truncate an older report.
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
            if (descriptor == null || descriptor.getStatSize() != 0) {
                throw new IOException("Choose a NEW empty TXT in local Downloads; provider must support disk files");
            }
        }
        SavedReport saved = new SavedReport(context, uri, name + "\n" + uri);
        if (!preferences(context).edit().putString("uri", uri.toString())
                .putString("mode", mode.name())
                .putString("destination", saved.destination).putString("error", "")
                .putString("state", "PREPARING / 准备报告").commit()) {
            throw new IOException("Cannot persist selected destination");
        }
        try {
            saved.appendCheckpoint("SELFTEST TXT — incremental checkpoints; no user media or network.\n"
                + plan.description()
                + "Destination: " + saved.destination + "\n"
                + "Revision: " + BuildConfig.SOURCE_REVISION + "\n"
                + "App: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")\n"
                + "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " API " + Build.VERSION.SDK_INT
                + " Android " + Build.VERSION.RELEASE + "\n"
                + "Codec/config: bundled 320x240 24fps AVC / mono 48k AAC; selected codecs pending.\n"
                + "A RUNNING checkpoint after relaunch means INTERRUPTED, not PASS.\n"
                + "Native crash, OOM, force-stop or power loss may leave only the last completed checkpoint;"
                    + " an incomplete trailing checkpoint must not be treated as success.\n", false);
        } catch (IOException | RuntimeException error) {
            saved.release();
            throw error;
        }
        return saved;
    }

    void release() {
        if (active == this) active = null;
    }

    void checkpoint(String text, boolean terminal) throws IOException {
        // Compatibility for callers supplying cumulative text. Production uses deltas exclusively.
        lock.lock();
        try {
            boolean cumulative = previousHash != null && text.length() >= previousLength
                    && java.util.Arrays.equals(previousHash, hashPrefix(text, previousLength));
            appendCheckpoint(cumulative ? text.substring(previousLength) : text, terminal);
            previousLength = text.length();
            previousHash = hashPrefix(text, text.length());
        } finally {
            lock.unlock();
        }
    }

    private static byte[] hashPrefix(String text, int length) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < length; i++) {
                char value = text.charAt(i);
                digest.update((byte) (value >>> 8));
                digest.update((byte) value);
            }
            return digest.digest();
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    void appendCheckpoint(String text, boolean terminal) throws IOException {
        lock.lock();
        try {
            if (failed) throw new IOException("External TXT saving previously failed; suite stopped");
            String state = terminal ? "TERMINAL / 已结束，查看结果" : "RUNNING / 重启后表示上次中断，未验证";
            String header = "\nCHECKPOINT utc_ms=" + System.currentTimeMillis() + " " + state + "\n";
            try {
                if (!initialized) {
                    ReportJournal.replace(snapshot(context), header, text, "\nEND CHECKPOINT\n");
                    initialized = true;
                } else {
                    ReportJournal.append(snapshot(context), header, text, "\nEND CHECKPOINT\n");
                }
            } catch (IOException | RuntimeException error) {
                failed = true;
                throw new IOException("PRIVATE TXT SAVE FAILED; suite stopped", error);
            }
            try {
                append(header, text, "\nEND CHECKPOINT\n");
                if (!preferences(context).edit().putString("state", state).putString("error", "").commit()) {
                    throw new IOException("Cannot persist report status");
                }
                if (terminal) release();
                else active = this;
            } catch (IOException | RuntimeException error) {
                failed = true;
                String diagnostic = "EXTERNAL TXT SAVE FAILED / 外部 TXT 保存失败，测试停止: " + error;
                try {
                    preferences(context).edit().putString("error", diagnostic).commit();
                } catch (RuntimeException saving) {
                    error.addSuppressed(saving);
                }
                try {
                    ReportJournal.append(snapshot(context), "\n", diagnostic);
                } catch (IOException saving) {
                    error.addSuppressed(saving);
                }
                throw new IOException(diagnostic, error);
            }
        } finally {
            lock.unlock();
        }
    }

    private void append(String... parts) throws IOException {
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(uri, "rw")) {
            if (descriptor == null || descriptor.getStatSize() < 0) {
                throw new IOException("Provider is not a seekable local disk file");
            }
            try (FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor)) {
                output.getChannel().position(output.getChannel().size());
                OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                for (String part : parts) ReportJournal.write(writer, part);
                writer.flush();
                output.getFD().sync();
            }
        }
    }

    static void atomic(File file, String text) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create report directory");
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream output = atomic.startWrite();
        try {
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            output.getFD().sync();
            atomic.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            atomic.failWrite(output);
            throw error;
        }
    }

    static void recordUncaught(Throwable error) {
        SavedReport saved = active;
        if (saved == null || !saved.lock.tryLock()) return;
        try {
            StringBuilder text = new StringBuilder("\nBEST-EFFORT UNCAUGHT JAVA ERROR utc_ms=")
                    .append(System.currentTimeMillis()).append('\n').append(error.getClass().getName());
            StackTraceElement[] frames = error.getStackTrace();
            for (int i = 0; i < Math.min(24, frames.length); i++) text.append("\n at ").append(frames[i]);
            text.append("\nNative failures are not caught. Last suite checkpoint remains authoritative.\n");
            atomic(new File(saved.context.getFilesDir(), "selftest/uncaught-java.txt"), text.toString());
            ReportJournal.append(snapshot(saved.context), text.toString());
            saved.append(text.toString());
        } catch (Throwable ignored) {
            // Allocation, provider access and Java handlers themselves may fail during OOM.
        } finally {
            saved.lock.unlock();
        }
    }
}
