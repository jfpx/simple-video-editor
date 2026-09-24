package com.simple.videoeditor;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.test.InstrumentationTestCase;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicReference;
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.net.Uri;
import android.content.ContentValues;
import android.provider.MediaStore;
import java.util.concurrent.*;

public final class PublicationDraftLifecycleTest extends InstrumentationTestCase {
    private PublicationDraftActivity activity;
    private Context context;
    private Instrumentation instrumentation;

    public void testCommitAfterStateCapturePreservesEdits() throws Exception {
        commitAfterCapture(false);
    }

    public void testBindingCommitAfterCaptureAndLocalePreservesAssociations() throws Exception {
        commitAfterCapture(true);
    }

    public void testUnfinishedRestoreRetryCapturesNewOperation() throws Exception {
        failedRestoreRetry("unfinished");
    }

    public void testExpiredRestoreValidationAndUnavailableMediaRetry() throws Exception {
        failedRestoreRetry("expired");
    }

    public void testConflictingRestoreRequiresReopenThenCapturesNewOperation() throws Exception {
        failedRestoreRetry("conflict");
    }

    private void failedRestoreRetry(String mode) throws Exception {
        startDraft();
        PublicationDraftStore store = new PublicationDraftStore(context);
        String id = ((JSONObject) field("draft")).getString("id");
        String obsolete = java.util.UUID.randomUUID().toString();
        Uri cover = createCover();
        Uri video = createVideo("restore-retry.mp4", false, true);
        boolean videoDeleted = false;
        CountDownLatch release = new CountDownLatch(1);
        Application application = activity.getApplication();
        Application.ActivityLifecycleCallbacks capture = null;
        try {
            JSONObject base = PublicationDraftMedia.verify(context, video, new PublicationDraftMedia.Reads())
                    .bind(store.load(id)).put("coverUri", cover.toString());
            store.save(base);
            open(base); ready();
            JSONObject attempted = new JSONObject(base.toString()).put("title", "Old operation");
            if ("unfinished".equals(mode)) {
                try {
                    new PublicationDraftStore(context, stage -> {
                        if (PublicationDraftStore.FAULT_AFTER_RECEIPT_COMMIT.equals(stage))
                            throw new java.io.IOException("Unfinished restore fixture");
                    }).saveMetadata(attempted, obsolete);
                    fail("Expected unfinished target");
                } catch (java.io.IOException expected) {
                    assertEquals("Unfinished restore fixture", expected.getMessage());
                }
                assertEquals(base.toString(), store.load(id).toString());
            } else if ("conflict".equals(mode)) {
                JSONObject committed = store.saveMetadata(attempted, obsolete);
                store.saveMetadata(committed.put("title", "Other editor"), java.util.UUID.randomUUID().toString());
            }
            java.io.File receipt = new java.io.File(context.getFilesDir(), "publication-drafts/operations/"
                    + PublicationDraftMedia.hash(new java.io.ByteArrayInputStream(obsolete.getBytes("UTF-8"))).sha + ".json");
            byte[] receiptBefore = receipt.exists() ? java.nio.file.Files.readAllBytes(receipt.toPath()) : null;
            instrumentation.runOnMainSync(() -> {
                try {
                    Field token = PublicationDraftActivity.class.getDeclaredField("restoreOperation");
                    token.setAccessible(true); token.set(activity, obsolete);
                    ((EditText) activity.findViewById(R.id.draftTitle)).setText("Retained failed restore edit");
                } catch (Exception error) { throw new AssertionError(error); }
            });
            recreate();
            assertEquals("Retained failed restore edit", text(R.id.draftTitle));
            assertEquals(base.toString(), ((JSONObject) field("draft")).toString());
            assertTrue(text(R.id.draftMessage), text(R.id.draftMessage).contains(
                    "conflict".equals(mode) ? "Conflicting draft revision" : "did not finish"));
            if ("conflict".equals(mode)) {
                press(R.string.draft_save); ready();
                assertTrue(text(R.id.draftMessage).contains("Conflicting draft revision"));
                assertEquals("Other editor", store.load(id).getString("title"));
                // Explicit reopen is required for a genuinely newer source revision.
                open(store.load(id)); ready();
            }
            if ("expired".equals(mode)) {
                instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText(""));
                press(R.string.draft_save);
                assertTrue(text(R.id.draftMessage).contains("Title is required"));
                assertEquals(1, context.getContentResolver().delete(video, null, null));
                videoDeleted = true;
                instrumentation.runOnMainSync(() -> {
                    try {
                        java.lang.reflect.Method check = PublicationDraftActivity.class.getDeclaredMethod("checkAvailability");
                        check.setAccessible(true); check.invoke(activity);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                ready();
                assertFalse((boolean) field("videoReadable"));
                assertTrue((boolean) field("coverReadable"));
                assertEquals(base.toString(), ((JSONObject) field("draft")).toString());
            }
            JSONObject retryBase = new JSONObject(((JSONObject) field("draft")).toString());
            Activity old = activity;
            ExecutorService worker = (ExecutorService) field("worker");
            CountDownLatch entered = new CountDownLatch(1);
            Future<?> blocker = worker.submit(() -> {
                entered.countDown();
                try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                catch (InterruptedException error) { throw new AssertionError(error); }
            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            AtomicReference<String> newOperation = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicReference<JSONObject> durable = new AtomicReference<>();
            capture = new Application.ActivityLifecycleCallbacks() {
                public void onActivitySaveInstanceState(Activity target, Bundle state) {
                    if (target != old) return;
                    try {
                        assertEquals(retryBase.toString(), state.getString("draft"));
                        assertEquals("Retry committed", state.getString("title"));
                        assertEquals("Dirty after retry submission 🎬", state.getString("description"));
                        assertEquals("Capture must persist the retry, not the completed restore",
                                newOperation.get(), state.getString("operation"));
                    } catch (Throwable error) { failure.set(error); }
                    finally { release.countDown(); }
                    try {
                        blocker.get(5, TimeUnit.SECONDS);
                        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                        durable.set(new PublicationDraftStore(context).load(id));
                        assertEquals("Retry committed", durable.get().getString("title"));
                        assertEquals(newOperation.get(), durable.get().getString("operationId"));
                    } catch (Throwable error) { failure.compareAndSet(null, error); }
                }
                public void onActivityCreated(Activity a, Bundle b) {}
                public void onActivityStarted(Activity a) {}
                public void onActivityResumed(Activity a) {}
                public void onActivityPaused(Activity a) {}
                public void onActivityStopped(Activity a) {}
                public void onActivityDestroyed(Activity a) {}
            };
            application.registerActivityLifecycleCallbacks(capture);
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText("Retry committed"));
            press(R.string.draft_save);
            newOperation.set((String) field("pendingOperation"));
            assertNotNull(newOperation.get()); assertFalse(obsolete.equals(newOperation.get()));
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftDescription))
                    .setText("Dirty after retry submission 🎬"));
            recreate();
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertTrue(old.isDestroyed());
            assertNull(field("restoreOperation"));
            assertEquals(durable.get().toString(), ((JSONObject) field("draft")).toString());
            assertEquals("Retry committed", text(R.id.draftTitle));
            assertEquals("Dirty after retry submission 🎬", text(R.id.draftDescription));
            assertEquals(durable.get().toString(), store.reconcile(retryBase, newOperation.get()).toString());
            if (receiptBefore != null) assertTrue(java.util.Arrays.equals(receiptBefore,
                    java.nio.file.Files.readAllBytes(receipt.toPath())));
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText("Next save succeeds"));
            press(R.string.draft_save); ready();
            JSONObject saved = store.load(id);
            assertEquals("Next save succeeds", saved.getString("title"));
            assertEquals("Dirty after retry submission 🎬", saved.getString("description"));
            assertTrue(saved.getLong("updatedAt") > durable.get().getLong("updatedAt"));
            for (String key : new String[]{"id", "videoUri", "coverUri", "mediaSha256", "mediaBytes",
                    "exportUri", "musicCredits"})
                assertEquals(key, retryBase.get(key).toString(), saved.get(key).toString());
        } finally {
            release.countDown();
            if (capture != null) application.unregisterActivityLifecycleCallbacks(capture);
            finishDraft(id);
            if (!videoDeleted) context.getContentResolver().delete(video, null, null);
            context.getContentResolver().delete(cover, null, null);
        }
    }

    @androidx.media3.common.util.UnstableApi
    private void commitAfterCapture(boolean binding) throws Exception {
        startDraft();
        String id = ((JSONObject) field("draft")).getString("id");
        boolean english = UiLocales.isEnglish(context);
        Uri video = null, cover = null;
        JSONObject bound = new JSONObject(((JSONObject) field("draft")).toString());
        if (binding) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, "publication-review-video.mp4");
            values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            video = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            try (java.io.InputStream input = new java.io.FileInputStream(new OracleVerifier(context).prepareFixture());
                 java.io.OutputStream output = context.getContentResolver().openOutputStream(video)) {
                byte[] buffer = new byte[65536]; int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                java.io.DataOutputStream atom = new java.io.DataOutputStream(output);
                byte[] unique = java.util.UUID.randomUUID().toString().getBytes("UTF-8");
                atom.writeInt(unique.length + 8); atom.writeBytes("free"); atom.write(unique);
            }
            ContentValues finalized = new ContentValues(); finalized.put(MediaStore.MediaColumns.IS_PENDING, 0);
            context.getContentResolver().update(video, finalized, null, null);
            cover = createCover();
            bound.put("coverUri", cover.toString());
            new PublicationDraftStore(context).save(bound);
            open(bound); ready();
        }
        JSONObject bindingValue = bound;
        PublicationDraftMedia.VerifiedVideo verified = binding
                ? PublicationDraftMedia.verify(context, video, new PublicationDraftMedia.Reads()) : null;
        String expectedCommittedTitle = binding ? bound.getString("title") : "Committed after capture";
        ExecutorService worker = (ExecutorService) field("worker");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        Future<?> blocker = worker.submit(() -> {
            entered.countDown();
            try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Application application = activity.getApplication();
        Activity old = activity;
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivitySaveInstanceState(Activity target, Bundle state) {
                if (target != old) return;
                try {
                    assertNotNull(state.getString("draft"));
                    release.countDown();
                    blocker.get(5, TimeUnit.SECONDS);
                    worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                    assertEquals(expectedCommittedTitle, new PublicationDraftStore(context).load(id).getString("title"));
                } catch (Throwable error) { failure.set(error); }
            }
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivityDestroyed(Activity a) {}
        };
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText("Committed after capture"));
            if (binding) instrumentation.runOnMainSync(() -> {
                try {
                    java.lang.reflect.Method method = PublicationDraftActivity.class.getDeclaredMethod("resolve",
                            PublicationDraftMedia.VerifiedVideo.class, JSONObject.class, String.class, String.class,
                            JSONObject.class, boolean.class);
                    method.setAccessible(true); method.invoke(activity, verified, bindingValue, "", null, null, false);
                } catch (Exception error) { throw new AssertionError(error); }
            });
            else press(R.string.draft_save);
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftDescription)).setText("Unsaved after submission 🎬"));
            if (binding) context.getSharedPreferences("ui_locales", Context.MODE_PRIVATE).edit()
                    .putString("language", english ? "zh-CN" : "en").commit();
            recreate(!binding);
            if (failure.get() != null) throw new AssertionError(failure.get());
            assertTrue(old.isDestroyed());
            assertEquals("Unsaved after submission 🎬", text(R.id.draftDescription));
            assertEquals("Committed after capture", text(R.id.draftTitle));
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText("Next save succeeds"));
            press(R.string.draft_save); ready();
            JSONObject saved = new PublicationDraftStore(context).load(id);
            assertEquals("Next save succeeds", saved.getString("title"));
            assertEquals("Unsaved after submission 🎬", saved.getString("description"));
            if (binding) {
                assertEquals(video.toString(), saved.getString("videoUri"));
                assertEquals(cover.toString(), saved.getString("coverUri"));
                assertEquals("", saved.getString("exportUri"));
                assertFalse(saved.getString("musicCredits").isEmpty());
                assertEquals(video, PublicationDraftMedia.share(context, saved, false).getParcelableExtra(Intent.EXTRA_STREAM));
                assertEquals(cover, PublicationDraftMedia.share(context, saved, true).getParcelableExtra(Intent.EXTRA_STREAM));
                assertTrue((boolean) field("videoReadable")); assertTrue((boolean) field("coverReadable"));
            }
        } finally {
            release.countDown();
            application.unregisterActivityLifecycleCallbacks(callbacks);
            finishDraft(id);
            if (video != null) context.getContentResolver().delete(video, null, null);
            if (cover != null) context.getContentResolver().delete(cover, null, null);
            context.getSharedPreferences("ui_locales", Context.MODE_PRIVATE).edit().putString("language", english ? "en" : "zh-CN").commit();
            instrumentation.runOnMainSync(() -> UiLocales.initialize(context));
        }
    }

    public void testValidationCorrectionKeepsActualMediaShareable() throws Exception {
        startDraft();
        String id = ((JSONObject) field("draft")).getString("id");
        Uri cover = createCover();
        boolean coverDeleted = false;
        try {
            JSONObject value = new PublicationDraftStore(context).load(id).put("coverUri", cover.toString());
            new PublicationDraftStore(context).save(value);
            open(value); ready();
            assertTrue((boolean) field("coverReadable"));
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText(""));
            press(R.string.draft_save);
            assertTrue(text(R.id.draftMessage).contains("Title is required"));
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText("Corrected 🎬"));
            press(R.string.draft_save); ready();
            assertTrue("Valid cover remains shareable after metadata correction", (boolean) field("coverReadable"));
            Intent intent = PublicationDraftMedia.share(context, (JSONObject) field("draft"), true);
            assertEquals(cover, intent.getParcelableExtra(Intent.EXTRA_STREAM));
            context.getContentResolver().delete(cover, null, null);
            coverDeleted = true;
            press(R.string.draft_save); ready();
            assertFalse("Deleted cover must be probed, not optimistically enabled", (boolean) field("coverReadable"));
            assertEquals("Corrected 🎬", new PublicationDraftStore(context).load(id).getString("title"));
        } finally {
            if (!coverDeleted) context.getContentResolver().delete(cover, null, null);
            finishDraft(id);
        }
    }

    public void testConcurrentRevisionIsExplicitAndRetainsUnsavedForm() throws Exception {
        startDraft();
        String id = ((JSONObject) field("draft")).getString("id");
        try {
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText("My unsaved title"));
            JSONObject other = new PublicationDraftStore(context).load(id).put("title", "Other editor");
            new PublicationDraftStore(context).save(other);
            recreate();
            assertEquals("My unsaved title", text(R.id.draftTitle));
            assertTrue(text(R.id.draftMessage).contains("Conflicting draft revision"));
            press(R.string.draft_save); ready();
            assertTrue(text(R.id.draftMessage).contains("Conflicting draft revision"));
            assertEquals("Other editor", new PublicationDraftStore(context).load(id).getString("title"));
        } finally { finishDraft(id); }
    }

    public void testChooserSelectionAfterStateCaptureRestoresChosenDraft() throws Exception {
        startDraft();
        String sourceId = ((JSONObject) field("draft")).getString("id");
        String chosenId = null, otherId = null;
        Uri video = createVideo("publication-switch-choice.mp4", false, true);
        try {
            PublicationDraftMedia.VerifiedVideo verified = PublicationDraftMedia.verify(context, video, new PublicationDraftMedia.Reads());
            PublicationDraftStore store = new PublicationDraftStore(context);
            JSONObject chosen = verified.bind(PublicationDraftStore.create("Chosen B"));
            store.save(chosen);
            chosen.put("title", "Chosen B").put("musicCredits", "Choice B credits");
            store.save(chosen);
            chosenId = chosen.getString("id");
            JSONObject other = verified.bind(PublicationDraftStore.create("Other C"));
            other.put("musicCredits", chosen.getString("musicCredits"));
            store.save(other);
            otherId = other.getString("id");
            assertEquals(2, store.resolve(verified, null, "", null, null, false, "").choices.size());
            Application application = activity.getApplication();
            Activity old = activity;
            ExecutorService worker = (ExecutorService) field("worker");
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            AtomicReference<Future<?>> blocker = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
                public void onActivitySaveInstanceState(Activity target, Bundle state) {
                    if (target != old) return;
                    try {
                        JSONObject savedBase = new JSONObject(state.getString("draft"));
                        assertEquals(sourceId, savedBase.getString("id"));
                        assertFalse(state.getString("operation", "").isEmpty());
                        release.countDown();
                        assertNotNull(blocker.get());
                        blocker.get().get(5, TimeUnit.SECONDS);
                        worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                        assertEquals(sourceId, new PublicationDraftStore(context).load(sourceId).getString("id"));
                    } catch (Throwable error) { failure.set(error); }
                }
                public void onActivityCreated(Activity a, Bundle b) {}
                public void onActivityStarted(Activity a) {}
                public void onActivityResumed(Activity a) {}
                public void onActivityPaused(Activity a) {}
                public void onActivityStopped(Activity a) {}
                public void onActivityDestroyed(Activity a) {}
            };
            application.registerActivityLifecycleCallbacks(callbacks);
            try {
                instrumentation.runOnMainSync(() -> {
                    try {
                        java.lang.reflect.Method method = PublicationDraftActivity.class.getDeclaredMethod("resolve",
                                PublicationDraftMedia.VerifiedVideo.class, JSONObject.class, String.class, String.class,
                                JSONObject.class, boolean.class);
                        method.setAccessible(true); method.invoke(activity, verified, null, "", null, null, false);
                    } catch (Exception error) { throw new AssertionError(error); }
                });
                waitForText("Chosen B\n" + chosen.getString("id") + "\n" + chosen.getString("musicCredits"));
                blocker.set(worker.submit(() -> {
                    entered.countDown();
                    try { assertTrue(release.await(15, TimeUnit.SECONDS)); }
                    catch (InterruptedException error) { throw new AssertionError(error); }
                }));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                clickText("Chosen B\n" + chosen.getString("id") + "\n" + chosen.getString("musicCredits"));
                recreate();
                if (failure.get() != null) throw new AssertionError(failure.get());
                assertEquals(chosen.getString("id"), ((JSONObject) field("draft")).getString("id"));
                assertEquals("Chosen B", text(R.id.draftTitle));
                assertEquals(sourceId, new PublicationDraftStore(context).load(sourceId).getString("id"));
            } finally {
                release.countDown();
                application.unregisterActivityLifecycleCallbacks(callbacks);
            }
        } finally {
            context.getContentResolver().delete(video, null, null);
            finishDraft(sourceId);
            if (chosenId != null) new PublicationDraftStore(context).delete(chosenId);
            if (otherId != null) new PublicationDraftStore(context).delete(otherId);
        }
    }

    public void testExitAfterRepeatedRotationCancelsOriginalWorker() throws Exception {
        startDraft();
        String id = ((JSONObject) field("draft")).getString("id");
        ExecutorService worker = (ExecutorService) field("worker");
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.submit(() -> {
            entered.countDown();
            try { release.await(15, TimeUnit.SECONDS); }
            catch (InterruptedException expected) { interrupted.countDown(); Thread.currentThread().interrupt(); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        String original = new PublicationDraftStore(context).load(id).getString("title");
        try {
            instrumentation.runOnMainSync(() -> ((EditText) activity.findViewById(R.id.draftTitle)).setText("Cancelled edit"));
            press(R.string.draft_save);
            for (int i = 0; i < 2; i++) {
                Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(PublicationDraftActivity.class.getName(), null, false);
                instrumentation.runOnMainSync(() -> activity.recreate());
                PublicationDraftActivity restored = (PublicationDraftActivity) instrumentation.waitForMonitorWithTimeout(monitor, 15000);
                instrumentation.removeMonitor(monitor);
                assertNotNull(restored); activity = restored;
                assertEquals("Cancelled edit", text(R.id.draftTitle));
            }
            instrumentation.runOnMainSync(() -> activity.finish());
            assertTrue("Exit must interrupt the pre-rotation owner", interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(original, new PublicationDraftStore(context).load(id).getString("title"));
            activity = (PublicationDraftActivity) instrumentation.startActivitySync(new Intent(context, PublicationDraftActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            ready();
            assertNull(field("draft"));
        } finally { release.countDown(); finishDraft(id); }
    }

    private Uri createCover() throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, "publication-review-cover.png");
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        Uri cover = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(16, 16, android.graphics.Bitmap.Config.ARGB_8888);
        try (java.io.OutputStream stream = context.getContentResolver().openOutputStream(cover)) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        } finally { bitmap.recycle(); }
        return cover;
    }

    private Uri createVideo(String name, boolean changed) throws Exception {
        return createVideo(name, changed, false);
    }

    private Uri createVideo(String name, boolean changed, boolean uniqueBytes) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri video = context.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        try (java.io.InputStream input = new java.io.FileInputStream(new OracleVerifier(context).prepareFixture());
             java.io.OutputStream output = context.getContentResolver().openOutputStream(video)) {
            byte[] buffer = new byte[65536]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            if (changed) output.write(new byte[]{0, 0, 0, 8, 'f', 'r', 'e', 'e'});
            if (uniqueBytes) {
                java.io.DataOutputStream atom = new java.io.DataOutputStream(output);
                byte[] unique = java.util.UUID.randomUUID().toString().getBytes("UTF-8");
                atom.writeInt(unique.length + 8); atom.writeBytes("free"); atom.write(unique);
            }
        }
        ContentValues finalized = new ContentValues(); finalized.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(video, finalized, null, null);
        return video;
    }

    private void startDraft() throws Exception {
        instrumentation = getInstrumentation();
        context = instrumentation.getTargetContext();
        activity = (PublicationDraftActivity) instrumentation.startActivitySync(new Intent(context, PublicationDraftActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ready(); press(R.string.draft_new); ready();
    }

    private void finishDraft(String id) throws Exception {
        if (activity != null) instrumentation.runOnMainSync(() -> activity.finish());
        new PublicationDraftStore(context).delete(id);
    }

    private void open(JSONObject value) {
        instrumentation.runOnMainSync(() -> {
            try {
                java.lang.reflect.Method method = PublicationDraftActivity.class.getDeclaredMethod("open", JSONObject.class);
                method.setAccessible(true); method.invoke(activity, value);
            } catch (Exception error) { throw new AssertionError(error); }
        });
    }

    private void recreate() throws Exception {
        recreate(false);
    }

    private void recreate(boolean rotate) throws Exception {
        Activity old = activity;
        Application application = activity.getApplication();
        AtomicReference<PublicationDraftActivity> resumed = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivityResumed(Activity value) {
                if (value != old && value instanceof PublicationDraftActivity) resumed.set((PublicationDraftActivity) value);
            }
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivityDestroyed(Activity a) {}
            public void onActivitySaveInstanceState(Activity a, Bundle b) {}
        };
        application.registerActivityLifecycleCallbacks(callbacks);
        try {
            instrumentation.runOnMainSync(() -> {
                if (rotate) activity.setRequestedOrientation(activity.getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                        ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                else activity.recreate();
            });
            long deadline = SystemClock.uptimeMillis() + 15000;
            while (SystemClock.uptimeMillis() < deadline) {
                if (resumed.get() == null) { SystemClock.sleep(100); continue; }
                activity = resumed.get();
                AtomicReference<Boolean> settled = new AtomicReference<>(false);
                instrumentation.runOnMainSync(() -> {
                    try { settled.set(!activity.isDestroyed() && !(boolean) field("busy")); }
                    catch (Exception error) { throw new AssertionError(error); }
                });
                if (settled.get()) return;
                SystemClock.sleep(100);
            }
            fail("Replacement activity did not settle: destroyed=" + activity.isDestroyed() + ", " + text(R.id.draftMessage));
        } finally { application.unregisterActivityLifecycleCallbacks(callbacks); }
    }

    public void testEnglishDirtyRecreationBackSaveAndDraftOnlyDelete() throws Exception {
        instrumentation = getInstrumentation();
        context = instrumentation.getTargetContext();
        boolean english = UiLocales.isEnglish(context);
        String id = null;
        try {
            context.getSharedPreferences("ui_locales", Context.MODE_PRIVATE).edit().putString("language", "en").commit();
            instrumentation.runOnMainSync(() -> UiLocales.initialize(context));
            activity = (PublicationDraftActivity) instrumentation.startActivitySync(new Intent(context, PublicationDraftActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            ready();
            assertEquals("Publication draft", activity.getString(R.string.draft_title));
            press(R.string.draft_new); ready();
            id = ((JSONObject) field("draft")).getString("id");
            instrumentation.runOnMainSync(() -> {
                ((EditText) activity.findViewById(R.id.draftTitle)).setText("Unsaved 🎬");
                ((EditText) activity.findViewById(R.id.draftDescription)).setText("Retained through recreation\n第二行");
                ((EditText) activity.findViewById(R.id.draftTags)).setText("中文\n🎬\n");
            });
            Instrumentation.ActivityMonitor monitor = instrumentation.addMonitor(PublicationDraftActivity.class.getName(), null, false);
            instrumentation.runOnMainSync(() -> activity.recreate());
            PublicationDraftActivity restored = (PublicationDraftActivity) instrumentation.waitForMonitorWithTimeout(monitor, 15000);
            instrumentation.removeMonitor(monitor);
            assertNotNull(restored);
            activity = restored;
            ready();
            assertEquals("Unsaved 🎬", text(R.id.draftTitle));
            assertEquals("Retained through recreation\n第二行", text(R.id.draftDescription));
            assertEquals("中文\n🎬\n", text(R.id.draftTags));
            assertEquals("Whole-video publication license (intention only)", activity.getString(R.string.draft_license));
            assertTrue(activity.getString(R.string.draft_license_notice).contains("grants no rights"));
            instrumentation.runOnMainSync(() -> activity.findViewById(R.id.draftLicense).requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, activity.findViewById(R.id.draftLicense).getWidth(),
                            activity.findViewById(R.id.draftLicense).getHeight()), true));
            java.io.File evidence = new java.io.File(context.getFilesDir(), "publication-ui-evidence");
            assertTrue(evidence.isDirectory() || evidence.mkdirs());
            android.graphics.Bitmap screenshot = instrumentation.getUiAutomation().takeScreenshot();
            assertNotNull(screenshot);
            try (java.io.OutputStream output = new java.io.FileOutputStream(new java.io.File(evidence, "05-english-license.png"))) {
                assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
            } finally { screenshot.recycle(); }
            assertEquals("Untitled video", new PublicationDraftStore(context).load(id).getString("title"));
            instrumentation.runOnMainSync(() -> activity.onBackPressed());
            SystemClock.sleep(500);
            assertFalse(activity.isFinishing());
            boolean clicked = false;
            long dialogDeadline = SystemClock.uptimeMillis() + 10000;
            while (!clicked && SystemClock.uptimeMillis() < dialogDeadline) {
                AccessibilityNodeInfo root = instrumentation.getUiAutomation().getRootInActiveWindow();
                if (root != null) for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText("Save draft")) {
                    if (node.isClickable()) clicked |= node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                }
                if (!clicked) SystemClock.sleep(100);
            }
            assertTrue(clicked);
            long end = SystemClock.uptimeMillis() + 15000;
            while (!activity.isFinishing() && SystemClock.uptimeMillis() < end) SystemClock.sleep(100);
            assertTrue(activity.isFinishing());
            assertEquals("Unsaved 🎬", new PublicationDraftStore(context).load(id).getString("title"));
            new PublicationDraftStore(context).delete(id);
        } finally {
            if (activity != null) instrumentation.runOnMainSync(() -> activity.finish());
            if (id != null) new PublicationDraftStore(context).delete(id);
            context.getSharedPreferences("ui_locales", Context.MODE_PRIVATE).edit().putString("language", english ? "en" : "zh-CN").commit();
            instrumentation.runOnMainSync(() -> UiLocales.initialize(context));
        }
    }

    private Object field(String name) throws Exception {
        Field field = PublicationDraftActivity.class.getDeclaredField(name); field.setAccessible(true); return field.get(activity);
    }
    private AccessibilityNodeInfo node(AccessibilityNodeInfo root, String text) {
        if (root == null) return null;
        if (text.equals(String.valueOf(root.getText())) || text.equals(String.valueOf(root.getContentDescription()))) return root;
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo found = node(root.getChild(i), text);
            if (found != null) return found;
        }
        return null;
    }
    private void waitForText(String text) throws Exception {
        long end = SystemClock.uptimeMillis() + 10000;
        while (SystemClock.uptimeMillis() < end) {
            if (node(instrumentation.getUiAutomation().getRootInActiveWindow(), text) != null) return;
            SystemClock.sleep(100);
        }
        fail("Missing chooser text: " + text);
    }
    private void clickText(String text) throws Exception {
        waitForText(text);
        AccessibilityNodeInfo found = node(instrumentation.getUiAutomation().getRootInActiveWindow(), text);
        assertNotNull(found);
        found.refresh();
        for (AccessibilityNodeInfo target = found; target != null; target = target.getParent()) {
            if (target.isClickable() && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                SystemClock.sleep(200);
                return;
            }
        }
        android.graphics.Rect bounds = new android.graphics.Rect();
        found.getBoundsInScreen(bounds);
        long now = SystemClock.uptimeMillis();
        android.view.MotionEvent down = android.view.MotionEvent.obtain(now, now, android.view.MotionEvent.ACTION_DOWN,
                bounds.centerX(), bounds.centerY(), 0);
        android.view.MotionEvent up = android.view.MotionEvent.obtain(now, now + 80, android.view.MotionEvent.ACTION_UP,
                bounds.centerX(), bounds.centerY(), 0);
        down.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        up.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
        try {
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(down, true));
            assertTrue(instrumentation.getUiAutomation().injectInputEvent(up, true));
        } finally { down.recycle(); up.recycle(); }
        SystemClock.sleep(200);
        if (node(instrumentation.getUiAutomation().getRootInActiveWindow(), text) == null) return;
        fail("Chooser item not clickable: " + text);
    }
    private String text(int id) {
        AtomicReference<String> value = new AtomicReference<>();
        instrumentation.runOnMainSync(() -> value.set(((TextView) activity.findViewById(id)).getText().toString()));
        return value.get();
    }
    private void ready() throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> busy = new AtomicReference<>(true);
            instrumentation.runOnMainSync(() -> { try { busy.set((boolean) field("busy")); } catch (Exception error) { throw new AssertionError(error); } });
            if (!busy.get()) return;
            SystemClock.sleep(100);
        }
        fail("Draft worker did not settle");
    }
    private void press(int text) {
        instrumentation.runOnMainSync(() -> {
            Button button = button(activity.getWindow().getDecorView(), activity.getString(text));
            assertNotNull(button); assertTrue(button.isEnabled()); button.performClick();
        });
    }
    private Button button(View view, String text) {
        if (view instanceof Button && text.equals(((Button) view).getText().toString())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button result = button(((ViewGroup) view).getChildAt(i), text); if (result != null) return result;
        }
        return null;
    }
}
