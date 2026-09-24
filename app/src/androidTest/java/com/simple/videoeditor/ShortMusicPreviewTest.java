package com.simple.videoeditor;

import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.widget.TextView;
import com.google.android.material.tabs.TabLayout;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real MediaPlayer timing and owned-copy cancellation, without a host-speaker requirement. */
public final class ShortMusicPreviewTest extends InstrumentationTestCase {
    private MainActivity activity;
    private OfflineMusicControls controls;
    private OfflineMusicAudition audition;
    private Context context;
    private String previous;
    private File evidence;
    private Set<String> priorCopies;

    @Override protected void setUp() throws Exception {
        super.setUp();
        context = getInstrumentation().getTargetContext();
        previous = context.getSharedPreferences("offline_music", Context.MODE_PRIVATE).getString("selection", null);
        priorCopies = copies();
        evidence = new File(context.getFilesDir(), "short-music-preview-evidence/" + getName() + "-" + UUID.randomUUID());
        assertTrue(evidence.mkdirs());
        activity = (MainActivity) getInstrumentation().startActivitySync(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        await(() -> !(Boolean) field(activity, "restoringUi") && !(Boolean) field(activity, "restoringAssets"), 30);
        ((ExecutorService) field(activity, "publicationWorker")).submit(() -> {}).get(20, TimeUnit.SECONDS);
        ui(() -> {
            controls = (OfflineMusicControls) field(activity, "offlineMusic");
            audition = (OfflineMusicAudition) field(controls, "audition");
            controls.apply(new BackgroundMusic(true, "heavenly-loop", .5f, true));
            tabs().getTabAt(1).select();
        });
    }

    @Override protected void tearDown() throws Exception {
        try {
            if (activity != null) ui(activity::finish);
            if (audition != null) {
                ExecutorService worker = (ExecutorService) field(audition, "worker");
                assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
                getInstrumentation().waitForIdleSync();
            }
            assertEquals("only this audition's copies may be removed", priorCopies, copies());
        } finally {
            context.getSharedPreferences("offline_music", Context.MODE_PRIVATE).edit().putString("selection", previous).commit();
            super.tearDown();
        }
    }

    public void testTenSecondsStartsAfterVerifiedCopyAndActualPlayback() throws Exception {
        Gate gate = new Gate();
        ui(() -> setField(controls, "catalog", gate.catalog));
        long clicked = SystemClock.elapsedRealtime();
        ui(() -> activity.findViewById(R.id.btnAuditionMusic).performClick());
        try {
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            Thread.sleep(1600);
            ui(() -> {
                assertNull(player());
                assertNull(field(audition, "deadline"));
                assertStatus(R.string.bgm_audition_preparing);
                assertEquals(.5f, ((Float) field(audition, "gain")).floatValue());
                assertTrue(controls.snapshot().muteOriginal);
            });
        } finally { gate.release.countDown(); }
        await(this::playing, 15);
        long started = SystemClock.elapsedRealtime();
        File snapshot = read(() -> (File) field(audition, "snapshot"));
        ui(() -> {
            assertFalse(player().isLooping());
            assertEquals(activity.getString(R.string.bgm_stop), text(R.id.btnAuditionMusic));
            assertFalse(activity.findViewById(R.id.btnAuditionFullMusic).isEnabled());
            assertStatus(R.string.bgm_audition_preview);
        });
        screenshot("playing");
        JSONArray positions = new JSONArray();
        int lastPosition = 0;
        while (read(audition::isActive)) {
            int position = read(() -> player() == null ? -1 : player().getCurrentPosition());
            if (position >= 0) {
                lastPosition = position;
                positions.put(new JSONObject().put("elapsedMs", SystemClock.elapsedRealtime() - started)
                        .put("positionMs", position));
            }
            assertTrue("finite native audition", SystemClock.elapsedRealtime() - started < 12000);
            Thread.sleep(80);
        }
        long elapsed = SystemClock.elapsedRealtime() - started;
        assertTrue("10s from playback, not copy: " + elapsed, elapsed >= 9700 && elapsed <= 11500);
        assertTrue("actual audio position near 10s: " + lastPosition, lastPosition >= 9200 && lastPosition <= 10500);
        assertTrue("copy time excluded", SystemClock.elapsedRealtime() - clicked >= 11500);
        assertStopped(snapshot);
        screenshot("stopped");
        result(new JSONObject().put("playbackElapsedMs", elapsed).put("lastPositionMs", lastPosition)
                .put("clickToStopMs", SystemClock.elapsedRealtime() - clicked).put("positions", positions)
                .put("independentGain", .5).put("originalMuted", true));
    }

    public void testCancelCopyReplaceTrackAndRepeatedClickNeverReviveOldPlayer() throws Exception {
        Gate gate = new Gate();
        try {
            ui(() -> setField(controls, "catalog", gate.catalog));
            ui(() -> activity.findViewById(R.id.btnAuditionMusic).performClick());
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            ui(() -> {
                activity.findViewById(R.id.btnAuditionMusic).performClick();
                assertStopped(null);
                controls.apply(new BackgroundMusic(true, "unsolved-investigation", 1f, false));
                setField(controls, "catalog", OfflineMusicCatalog.load(context));
                activity.findViewById(R.id.btnAuditionMusic).performClick();
            });
        } finally { gate.release.countDown(); }
        await(this::playing, 15);
        File copy = read(() -> (File) field(audition, "snapshot"));
        assertEquals("replacement owns exactly one extra file", priorCopies.size() + 1, copies().size());
        ui(() -> {
            assertEquals(1f, ((Float) field(audition, "gain")).floatValue());
            activity.findViewById(R.id.btnAuditionMusic).performClick();
            assertStopped(copy);
            for (int i = 0; i < 4; i++) {
                activity.findViewById(R.id.btnAuditionMusic).performClick();
                activity.findViewById(R.id.btnAuditionMusic).performClick();
            }
        });
        drain();
        assertStopped(null);
        result(new JSONObject().put("cancelDuringCopy", true).put("replacement", true).put("repeatedClick", true));
    }

    public void testStaleDeadlineAndFocusCannotStopNextPlayerAndWholeTrackRemainsAvailable() throws Exception {
        start();
        Runnable oldDeadline = read(() -> (Runnable) field(audition, "deadline"));
        AudioManager.OnAudioFocusChangeListener oldFocus = read(() ->
                (AudioManager.OnAudioFocusChangeListener) field(audition, "focus"));
        File oldCopy = read(() -> (File) field(audition, "snapshot"));
        ui(() -> {
            activity.findViewById(R.id.btnAuditionMusic).performClick();
            activity.findViewById(R.id.btnAuditionFullMusic).performClick();
        });
        await(this::playing, 15);
        MediaPlayer replacement = read(this::player);
        oldFocus.onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS);
        ui(oldDeadline::run);
        ui(() -> {
            assertSame(replacement, player());
            assertNull(field(audition, "deadline"));
            assertFalse(oldCopy.exists());
            assertStatus(R.string.bgm_audition_full);
        });
        Thread.sleep(10400);
        ui(() -> {
            assertTrue(player().isPlaying());
            assertTrue(player().getCurrentPosition() >= 10000);
            assertFalse(player().isLooping());
            activity.findViewById(R.id.btnAuditionMusic).performClick();
        });
        assertStopped(null);
        result(new JSONObject().put("staleCallbacksIgnored", true).put("wholeTrackPast10s", true));
    }

    public void testNativeEarlyCompletionRemovesDeadlineAndRestoresButton() throws Exception {
        start();
        File copy = read(() -> (File) field(audition, "snapshot"));
        ui(() -> player().seekTo(player().getDuration() - 350));
        await(() -> !audition.isActive(), 5);
        assertStopped(copy);
        result(new JSONObject().put("nativeEarlyCompletion", true));
    }

    public void testStopFromActualPreparedNotificationReleasesEverything() throws Exception {
        AtomicReference<OfflineMusicAudition> owner = new AtomicReference<>();
        AtomicReference<File> copy = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch stopped = new CountDownLatch(1);
        ui(() -> {
            owner.set(new OfflineMusicAudition(activity, () -> {
                OfflineMusicAudition current = owner.get();
                if (current.statusResource() != R.string.bgm_audition_preview) return;
                try {
                    assertTrue(((MediaPlayer) field(current, "player")).isPlaying());
                    copy.set((File) field(current, "snapshot"));
                    current.stop();
                } catch (Throwable failure) { error.set(failure); }
                finally { stopped.countDown(); }
            }));
            owner.get().play(OfflineMusicCatalog.load(context), "heavenly-loop");
        });
        try {
            assertTrue(stopped.await(15, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError(error.get());
            ui(() -> {
                assertFalse(owner.get().isActive());
                for (String name : new String[]{"player", "snapshot", "deadline", "pending", "focus"})
                    assertNull(name, field(owner.get(), name));
                assertNotNull(copy.get());
                assertFalse(copy.get().exists());
            });
        } finally { ui(() -> owner.get().close()); }
        result(new JSONObject().put("cancelOnNativePrepared", true));
    }

    public void testTabCategoryAndOffMainThreadFocusLossStopImmediately() throws Exception {
        start();
        ui(() -> tabs().getTabAt(0).select());
        assertStopped(null);
        ui(() -> tabs().getTabAt(1).select());
        start();
        ui(() -> activity.findViewById(R.id.btnLibraryTrack).performClick());
        assertStopped(null);
        getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK);
        for (int loss : new int[]{AudioManager.AUDIOFOCUS_LOSS, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK}) {
            start();
            AudioManager.OnAudioFocusChangeListener focus = read(() ->
                    (AudioManager.OnAudioFocusChangeListener) field(audition, "focus"));
            focus.onAudioFocusChange(loss);
            await(() -> !audition.isActive(), 3);
            assertStopped(null);
        }
        result(new JSONObject().put("tabStop", true).put("categoryStop", true).put("focusLossModes", 3));
    }

    public void testBackgroundWhileCopyingRestoresButtonAndCannotStartLater() throws Exception {
        Gate gate = new Gate();
        try {
            ui(() -> setField(controls, "catalog", gate.catalog));
            ui(() -> activity.findViewById(R.id.btnAuditionMusic).performClick());
            assertTrue(gate.entered.await(5, TimeUnit.SECONDS));
            getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_HOME);
            await(() -> !audition.isActive(), 5);
        } finally { gate.release.countDown(); }
        drain();
        assertStopped(null);
        try (android.os.ParcelFileDescriptor command = getInstrumentation().getUiAutomation().executeShellCommand(
                "am start --activity-reorder-to-front -n com.simple.videoeditor/.MainActivity");
             InputStream in = new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {
            byte[] buffer = new byte[1024];
            while (in.read(buffer) != -1) {}
        }
        await(activity::hasWindowFocus, 15);
        assertStopped(null);
        result(new JSONObject().put("backgroundWhilePreparing", true).put("buttonRestored", true));
    }

    public void testVerificationFailureIsNotReportedAsNormalStopAndCanRetry() throws Exception {
        OfflineMusicCatalog bad = OfflineMusicCatalog.load(path -> path.equals("music/catalog.json")
                ? context.getAssets().open(path) : new java.io.ByteArrayInputStream(new byte[8]));
        ui(() -> { setField(controls, "catalog", bad); activity.findViewById(R.id.btnAuditionMusic).performClick(); });
        await(() -> audition.statusResource() == R.string.bgm_audition_error, 10);
        ui(() -> {
            assertFalse(audition.isActive());
            assertNull(player());
            assertNull(field(audition, "snapshot"));
            assertStatus(R.string.bgm_audition_error);
            setField(controls, "catalog", OfflineMusicCatalog.load(context));
        });
        start();
        ui(audition::stop);
        assertStopped(null);
        for (String locale : new String[]{"zh", "en"}) {
            android.content.res.Configuration configuration = new android.content.res.Configuration(context.getResources().getConfiguration());
            configuration.setLocale(java.util.Locale.forLanguageTag(locale));
            Context localized = context.createConfigurationContext(configuration);
            assertEquals(locale.equals("zh") ? "试听10秒" : "Preview 10s", localized.getString(R.string.bgm_play));
            assertEquals(locale.equals("zh") ? "整曲试听" : "Play full track", localized.getString(R.string.bgm_play_full));
        }
        result(new JSONObject().put("verificationFailureVisible", true).put("retry", true).put("bilingualLabels", true));
    }

    private final class Gate {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final OfflineMusicCatalog catalog;
        Gate() throws Exception {
            catalog = OfflineMusicCatalog.load(path -> {
                if (!path.equals("music/catalog.json")) {
                    entered.countDown();
                    boolean interrupted = false;
                    long deadline = SystemClock.elapsedRealtime() + 20000;
                    try {
                        while (release.getCount() != 0) {
                            if (SystemClock.elapsedRealtime() >= deadline) throw new java.io.IOException("test gate timeout");
                            try { release.await(100, TimeUnit.MILLISECONDS); }
                            catch (InterruptedException ignored) { interrupted = true; }
                        }
                    } finally { if (interrupted) Thread.currentThread().interrupt(); }
                }
                return context.getAssets().open(path);
            });
        }
    }
    private void start() throws Exception {
        ui(() -> activity.findViewById(R.id.btnAuditionMusic).performClick());
        await(this::playing, 15);
    }
    private boolean playing() throws Exception { return player() != null && player().isPlaying(); }
    private MediaPlayer player() throws Exception { return (MediaPlayer) field(audition, "player"); }
    private TabLayout tabs() { return activity.findViewById(R.id.editorTabs); }
    private String text(int id) { return ((TextView) activity.findViewById(id)).getText().toString(); }
    private void assertStatus(int resource) { assertEquals(activity.getString(resource), text(R.id.tvMusicAuditionStatus)); }
    private void assertStopped(File copy) throws Exception {
        ui(() -> {
            assertFalse(audition.isActive());
            for (String name : new String[]{"player", "snapshot", "deadline", "pending", "focus"})
                assertNull(name, field(audition, name));
            assertStatus(R.string.bgm_audition_stopped);
            assertTrue(activity.findViewById(R.id.btnAuditionMusic).isEnabled());
            assertEquals(activity.getString(R.string.bgm_play), text(R.id.btnAuditionMusic));
            if (copy != null) assertFalse(copy.exists());
        });
    }
    private Set<String> copies() {
        String[] names = context.getCacheDir().list((directory, name) -> name.startsWith("music-audition-"));
        return new HashSet<>(Arrays.asList(names == null ? new String[0] : names));
    }
    private void drain() throws Exception {
        ((ExecutorService) field(audition, "worker")).submit(() -> {}).get(15, TimeUnit.SECONDS);
        getInstrumentation().waitForIdleSync();
    }
    private void await(Callable<Boolean> condition, int seconds) throws Exception {
        long until = SystemClock.elapsedRealtime() + seconds * 1000L;
        while (!read(condition)) {
            assertTrue("bounded wait", SystemClock.elapsedRealtime() < until);
            Thread.sleep(30);
        }
    }
    private interface Action { void run() throws Exception; }
    private void ui(Action action) throws Exception { read(() -> { action.run(); return null; }); }
    private <T> T read(Callable<T> action) throws Exception {
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) return action.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        getInstrumentation().runOnMainSync(() -> {
            try { result.set(action.call()); } catch (Throwable error) { failure.set(error); }
        });
        if (failure.get() instanceof Error) throw (Error) failure.get();
        if (failure.get() != null) throw (Exception) failure.get();
        return result.get();
    }
    private static Object field(Object owner, String name) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true); return field.get(owner);
    }
    private static void setField(Object owner, String name, Object value) throws Exception {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true); field.set(owner, value);
    }
    private void screenshot(String name) throws Exception {
        android.graphics.Bitmap bitmap = getInstrumentation().getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try (FileOutputStream out = new FileOutputStream(new File(evidence, name + ".png"))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
    }
    private void result(JSONObject value) throws Exception {
        value.put("revision", BuildConfig.SOURCE_REVISION).put("status", "PASS");
        try (FileOutputStream out = new FileOutputStream(new File(evidence, "result.json"))) {
            out.write(value.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
