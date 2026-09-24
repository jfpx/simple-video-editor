package com.simple.videoeditor;

import android.app.Activity;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.io.File;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Foreground-only audition of a verified private copy; never a service or timeline preview. */
final class OfflineMusicAudition {
    static final int PREVIEW_MILLIS = 10_000;
    private final Activity activity;
    private final Runnable changed;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private AudioManager.OnAudioFocusChangeListener focus;
    private Runnable deadline;
    private MediaPlayer player;
    private File snapshot;
    private Future<?> pending;
    private int generation;
    private boolean active;
    private boolean closed;
    private int status = R.string.bgm_audition_ready;
    private float gain = 1;

    OfflineMusicAudition(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        audio = (AudioManager) activity.getSystemService(Activity.AUDIO_SERVICE);
    }

    boolean isActive() { return active; }
    int statusResource() { return status; }

    void setGain(float value) {
        gain = value;
        if (player != null) player.setVolume(gain, gain);
    }

    void play(OfflineMusicCatalog catalog, String id) {
        play(catalog, id, true);
    }

    void play(OfflineMusicCatalog catalog, String id, boolean shortPreview) {
        stop();
        if (closed) return;
        final int token = generation;
        active = true;
        status = R.string.bgm_audition_preparing;
        changed.run();
        pending = worker.submit(() -> {
            File file = new File(activity.getCacheDir(), "music-audition-" + UUID.randomUUID() + ".audio");
            try {
                catalog.copyVerified(id, file);
                activity.runOnUiThread(() -> {
                    if (token != generation) { file.delete(); return; }
                    snapshot = file;
                    try {
                        focus = state -> activity.runOnUiThread(() -> {
                            if (token == generation && state != AudioManager.AUDIOFOCUS_GAIN) stop();
                        });
                        if (audio.requestAudioFocus(focus, AudioManager.STREAM_MUSIC,
                                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                            throw new IllegalStateException("Audio focus unavailable");
                        player = new MediaPlayer();
                        player.setAudioAttributes(new android.media.AudioAttributes.Builder()
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build());
                        player.setDataSource(file.getAbsolutePath());
                        player.setVolume(gain, gain);
                        player.setLooping(false);
                        player.setOnPreparedListener(prepared -> {
                            if (token != generation || prepared != player) return;
                            try {
                                prepared.start();
                                // Audition only: verified copy/async preparation consume none of the 10 seconds.
                                if (shortPreview) {
                                    deadline = () -> {
                                        if (token == generation && prepared == player) stop();
                                    };
                                    handler.postDelayed(deadline, PREVIEW_MILLIS);
                                }
                                status = shortPreview ? R.string.bgm_audition_preview : R.string.bgm_audition_full;
                                changed.run();
                            } catch (Exception failure) { fail(); }
                        });
                        player.setOnCompletionListener(completed -> {
                            if (token == generation && completed == player) stop();
                        });
                        player.setOnErrorListener((failed, what, extra) -> {
                            if (token == generation && failed == player) fail();
                            return true;
                        });
                        player.prepareAsync();
                    } catch (Exception failure) { fail(); }
                });
            } catch (Exception failure) {
                file.delete();
                activity.runOnUiThread(() -> { if (token == generation) fail(); });
            }
        });
    }

    private void fail() {
        stop();
        status = R.string.bgm_audition_error;
        changed.run();
        Toast.makeText(activity, R.string.bgm_audition_error, Toast.LENGTH_LONG).show();
    }

    void stop() {
        generation++;
        if (deadline != null) { handler.removeCallbacks(deadline); deadline = null; }
        if (pending != null) { pending.cancel(true); pending = null; }
        if (player != null) { player.release(); player = null; }
        if (snapshot != null) { snapshot.delete(); snapshot = null; }
        if (focus != null) { audio.abandonAudioFocus(focus); focus = null; }
        if (active) status = R.string.bgm_audition_stopped;
        active = false;
        changed.run();
    }

    void close() { closed = true; stop(); worker.shutdownNow(); }
}
