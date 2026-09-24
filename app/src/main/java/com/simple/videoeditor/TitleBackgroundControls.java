package com.simple.videoeditor;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.TextView;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Generation-gated worker ownership prevents old source/time requests replacing current pixels. */
final class TitleBackgroundControls {
    interface Geometry { EditConfig current(); }
    static final int SAVE_IMAGE = 1010;
    private final Activity activity;
    private final Runnable changed;
    private final Geometry currentGeometry;
    private final CheckBox enabled;
    private final EditText seconds;
    private final TextView status;
    private final ImageView image;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private int generation, snapshotGeneration = -1;
    private boolean closed, syncing, busy, titleEnabled, saving;
    private String requested;
    private EditConfig geometry;
    private TitleBackground snapshot;
    private Bitmap preview;
    private Runnable pending;
    private java.util.concurrent.Future<?> task;

    TitleBackgroundControls(Activity activity, Geometry currentGeometry, Runnable changed) {
        this.activity = activity; this.currentGeometry = currentGeometry; this.changed = changed;
        enabled = activity.findViewById(R.id.cbTitleSourceFrame);
        seconds = activity.findViewById(R.id.etTitleSourceSeconds);
        status = activity.findViewById(R.id.tvTitleSourceStatus);
        image = activity.findViewById(R.id.ivTitleSourceFrame);
        enabled.setOnCheckedChangeListener((button, value) -> { if (!syncing) changed.run(); });
        seconds.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { if (!syncing) changed.run(); }
            public void afterTextChanged(Editable e) {}
        });
        activity.findViewById(R.id.btnExtractTitleFrame).setOnClickListener(v -> {
            requested = null; changed.run();
        });
        activity.findViewById(R.id.btnSaveTitleFrame).setOnClickListener(v -> {
            if (!ready() || busy || saving) return;
            if (Build.VERSION.SDK_INT < 29) {
                activity.startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .addCategory(Intent.CATEGORY_OPENABLE).setType("image/png")
                        .putExtra(Intent.EXTRA_TITLE, filename()), SAVE_IMAGE);
            } else save(null);
        });
    }

    void apply(IntroTemplate title) {
        boolean different = enabled.isChecked() != title.hasSourceFrameBackground()
                || !seconds.getText().toString().equals(title.getSourceFrameSeconds());
        syncing = true;
        enabled.setChecked(title.hasSourceFrameBackground());
        seconds.setText(title.getSourceFrameSeconds());
        syncing = false;
        if (different) invalidateRequest();
        setBusy(busy, titleEnabled);
    }

    void put(IntroTemplate title) {
        title.setSourceFrameBackground(enabled.isChecked());
        title.setSourceFrameSeconds(seconds.getText().toString());
    }

    boolean isSaving() { return saving; }

    void setBusy(boolean busy, boolean titleEnabled) {
        if (this.titleEnabled && !titleEnabled) invalidateRequest();
        this.busy = busy; this.titleEnabled = titleEnabled;
        enabled.setEnabled(!busy && titleEnabled);
        boolean active = !busy && titleEnabled && enabled.isChecked();
        seconds.setEnabled(active);
        activity.findViewById(R.id.btnExtractTitleFrame).setEnabled(active && geometry != null);
        activity.findViewById(R.id.btnSaveTitleFrame).setEnabled(active && ready());
    }

    void refresh(EditConfig config) {
        geometry = config;
        if (closed) return;
        if (!titleEnabled || !enabled.isChecked()) {
            invalidateRequest();
            image.setImageDrawable(null);
            status.setText(R.string.title_frame_hint);
            setBusy(busy, titleEnabled);
            return;
        }
        try {
            if (config == null) throw new IllegalArgumentException(activity.getString(R.string.title_frame_select_source));
            long time = TitleBackground.parseTime(seconds.getText().toString(), config.sourceDurationMs);
            String key = TitleBackground.key(config, time);
            if (key.equals(requested)) return;
            invalidateRequest();
            // Invalidate during atomic preset/source loading, but start work only after it clears.
            if (busy) {
                setBusy(busy, titleEnabled);
                return;
            }
            requested = key;
            int token = generation;
            status.setText(R.string.title_frame_extracting);
            pending = () -> task = worker.submit(() -> {
                TitleBackground captured = null;
                Bitmap small = null;
                try {
                    captured = TitleBackground.capture(activity.getApplicationContext(), config, time);
                    small = captured.decode(640);
                    TitleBackground result = captured;
                    Bitmap bitmap = small;
                    main.post(() -> {
                        if (closed || token != generation || !key.equals(currentKey())) {
                            bitmap.recycle(); result.file.delete(); return;
                        }
                        discardSnapshot();
                        snapshot = result;
                        snapshotGeneration = token;
                        preview = bitmap;
                        image.setImageBitmap(bitmap);
                        status.setText(activity.getString(R.string.title_frame_ready, time / 1000d,
                                result.width, result.height));
                        changed.run();
                    });
                } catch (Exception | OutOfMemoryError error) {
                    if (captured != null) captured.file.delete();
                    if (small != null) small.recycle();
                    main.post(() -> {
                        if (!closed && token == generation) {
                            status.setText(activity.getString(R.string.title_frame_failed) + ": " + error.getMessage());
                            changed.run();
                        }
                    });
                }
            });
            main.postDelayed(pending, 250);
        } catch (IllegalArgumentException error) {
            invalidateRequest();
            status.setText(error.getMessage());
        }
        setBusy(busy, titleEnabled);
    }

    TitleBackground snapshot(EditConfig config) {
        if (!enabled.isChecked()) return null;
        long time = TitleBackground.parseTime(seconds.getText().toString(), config.sourceDurationMs);
        if (!ready() || !snapshot.key.equals(TitleBackground.key(config, time)))
            throw new IllegalArgumentException(activity.getString(R.string.title_frame_not_ready));
        return snapshot;
    }

    Bitmap preview() { return ready() ? preview : null; }

    private boolean ready() {
        return snapshot != null && snapshotGeneration == generation
                && snapshot.key.equals(requested) && preview != null
                && snapshot.key.equals(currentKey());
    }

    private String currentKey() {
        if (closed || !titleEnabled || !((CheckBox) activity.findViewById(R.id.cbEnableIntro)).isChecked()
                || !enabled.isChecked()) return null;
        try {
            EditConfig current = currentGeometry.current();
            return current == null ? null : TitleBackground.key(current,
                    TitleBackground.parseTime(seconds.getText().toString(), current.sourceDurationMs));
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private void invalidateRequest() {
        ++generation;
        if (pending != null) main.removeCallbacks(pending);
        if (task != null) task.cancel(true);
        requested = null;
        image.setImageDrawable(null);
        ((TitlePreviewView) activity.findViewById(R.id.titleCanvasPreview)).setBackgroundFrame(null);
    }

    private String filename() {
        return "title-bg-original-" + snapshot.timeMs + "ms-" + java.util.UUID.randomUUID() + ".png";
    }

    void save(Uri document) {
        if (!ready() || busy || saving || closed) return;
        TitleBackground frame = snapshot;
        String name = filename();
        saving = true;
        changed.run();
        worker.execute(() -> {
            try {
                Uri uri = PublishedImage.publish(activity.getApplicationContext(), frame, name, document, cancelled);
                String actualName = PublishedImage.name(activity.getApplicationContext(), uri);
                main.post(() -> {
                    if (!closed) {
                        status.setText(activity.getString(R.string.title_frame_saved) + "\n" + actualName + "\n" + uri
                                + "\n" + frame.width + "×" + frame.height + " · " + frame.timeMs + " ms (closest)");
                        saving = false; changed.run();
                    }
                });
            } catch (Exception error) {
                main.post(() -> {
                    if (!closed) {
                        status.setText(activity.getString(R.string.title_frame_failed) + ": " + error.getMessage());
                        saving = false; changed.run();
                    }
                });
            }
        });
    }

    private void discardSnapshot() {
        image.setImageDrawable(null);
        ((TitlePreviewView) activity.findViewById(R.id.titleCanvasPreview)).setBackgroundFrame(null);
        // Displayed previews are GC-owned: RenderThread can still reference the old bitmap.
        if (snapshot != null) snapshot.file.delete();
        preview = null; snapshot = null;
    }

    void close() {
        closed = true;
        cancelled.set(true);
        invalidateRequest();
        // File deletion follows any active save; do not invalidate its input mid-stream.
        TitleBackground old = snapshot;
        snapshot = null;
        discardSnapshot();
        worker.execute(() -> { if (old != null) old.file.delete(); });
        worker.shutdown();
    }
}
