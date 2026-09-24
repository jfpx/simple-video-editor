package com.simple.videoeditor;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static junit.framework.Assert.*;

/** Host kills only the receipt PID after durable commit, then restores the existing launcher task. */
@androidx.media3.common.util.UnstableApi
public final class PublicationDraftProcessDeathFixture extends Instrumentation {
    private String mode;

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        mode = arguments == null ? null : arguments.getString("mode");
        if (mode == null) mode = "save";
        start();
    }

    @Override public void onStart() {
        try {
            switch (mode) {
                case "bind":
                    prepareSaveOrBind(true);
                    return;
                case "switch":
                    prepareSwitch(false);
                    return;
                case "switch-conflict":
                    prepareSwitch(true);
                    return;
                default:
                    prepareSaveOrBind(false);
            }
        } catch (Throwable error) {
            Bundle result = new Bundle();
            result.putString("stream", android.util.Log.getStackTraceString(error));
            finish(1, result);
        }
    }

    private void prepareSaveOrBind(boolean binding) throws Exception {
        Instrumentation instrumentation = this;
        Context context = instrumentation.getTargetContext();
        File receipt = readyFile(context);
        PublicationDraftActivity activity = launchDraft(context);
        ready(activity);
        instrumentation.runOnMainSync(() -> button(activity.getWindow().getDecorView(),
                activity.getString(R.string.draft_new)).performClick());
        ready(activity);
        JSONObject base = new JSONObject(((JSONObject) field(activity, "draft")).toString());
        Uri video = insert(context, false), cover = insert(context, true);
        try (InputStream input = new FileInputStream(new OracleVerifier(context).prepareFixture());
             OutputStream output = context.getContentResolver().openOutputStream(video)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            DataOutputStream atom = new DataOutputStream(output);
            byte[] unique = java.util.UUID.randomUUID().toString().getBytes("UTF-8");
            atom.writeInt(unique.length + 8);
            atom.writeBytes("free");
            atom.write(unique);
        }
        ContentValues finalized = new ContentValues();
        finalized.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(video, finalized, null, null);
        Bitmap bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888);
        try (OutputStream output = context.getContentResolver().openOutputStream(cover)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally { bitmap.recycle(); }
        JSONObject association = inspected(activity, context, base, video)
                .put("coverUri", cover.toString())
                .put("musicCredits", "Fixture provenance; not whole-video CC0")
                .put("publicationLicense", "creative-commons-attribution");
        store(activity, "save", JSONObject.class, association);
        instrumentation.runOnMainSync(() -> invoke(activity, "open", new Class<?>[]{JSONObject.class}, association));
        ready(activity);
        base = new JSONObject(association.toString());
        Uri selected = video;
        if (binding) {
            selected = insert(context, false);
            try (InputStream input = context.getContentResolver().openInputStream(video);
                 OutputStream output = context.getContentResolver().openOutputStream(selected)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            }
            context.getContentResolver().update(selected, finalized, null, null);
        }
        Object verified = verifiedVideo(activity, context, selected);
        JSONObject capturedBase = base;
        ExecutorService worker = (ExecutorService) field(activity, "worker");
        CountDownLatch release = new CountDownLatch(1), entered = new CountDownLatch(1), captured = new CountDownLatch(1);
        AtomicReference<Throwable> error = new AtomicReference<>();
        worker.submit(() -> {
            entered.countDown();
            try { if (!release.await(30, TimeUnit.SECONDS)) throw new AssertionError("Capture deadline"); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivitySaveInstanceState(Activity target, Bundle state) {
                if (target != activity) return;
                try {
                    JSONObject old = new JSONObject(state.getString("draft"));
                    assertEquals(capturedBase.getLong("updatedAt"), old.getLong("updatedAt"));
                    release.countDown();
                    worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                    JSONObject durable = (JSONObject) store(activity, "load", String.class, old.getString("id"));
                    assertTrue(durable.getLong("updatedAt") > old.getLong("updatedAt"));
                    assertEquals(state.getString("operation"), durable.getString("operationId"));
                    File operationReceipt = operationReceipt(activity, state.getString("operation"));
                    assertTrue(operationReceipt.isFile());
                    if (binding) {
                        assertEquals(capturedBase.getString("mediaSha256"), durable.getString("mediaSha256"));
                        assertFalse(capturedBase.getString("videoUri").equals(durable.getString("videoUri")));
                        assertEquals(capturedBase.getString("musicCredits"), durable.getString("musicCredits"));
                    }
                    writeReady(receipt, new JSONObject()
                            .put("sourceRevision", BuildConfig.SOURCE_REVISION)
                            .put("pid", android.os.Process.myPid())
                            .put("taskId", activity.getTaskId())
                            .put("id", old.getString("id"))
                            .put("mode", binding ? "bind" : "save")
                            .put("pendingOperation", state.getString("operation"))
                            .put("operationReceiptPath", operationReceipt.getAbsolutePath())
                            .put("byteIdenticalCopyReusedId", binding)
                            .put("oldRevision", old.getLong("updatedAt"))
                            .put("durable", durable)
                            .put("unsavedDescription", state.getString("description"))
                            .put("unsavedTags", state.getString("tags")));
                } catch (Throwable failure) { error.set(failure); }
                finally { captured.countDown(); }
            }
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivityDestroyed(Activity a) {}
        };
        activity.getApplication().registerActivityLifecycleCallbacks(callbacks);
        try {
            instrumentation.runOnMainSync(() -> {
                ((EditText) activity.findViewById(R.id.draftTitle)).setText("Pending process death");
                ((Spinner) activity.findViewById(R.id.draftLicense)).setSelection(2);
                if (binding) invoke(activity, "resolve", new Class<?>[]{verified.getClass(),
                        JSONObject.class, String.class, String.class, JSONObject.class, boolean.class},
                        verified, capturedBase, "", null, null, false);
                else button(activity.getWindow().getDecorView(), activity.getString(R.string.draft_save)).performClick();
                ((EditText) activity.findViewById(R.id.draftDescription)).setText("Unsaved process death 🎬");
                ((EditText) activity.findViewById(R.id.draftTags)).setText("中文\n🎬\n");
                activity.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
            assertTrue(captured.await(20, TimeUnit.SECONDS));
            if (error.get() != null) throw new AssertionError(error.get());
            SystemClock.sleep(120000);
            fail("Host must kill the identified background process after reading the receipt");
        } finally {
            release.countDown();
            activity.getApplication().unregisterActivityLifecycleCallbacks(callbacks);
        }
    }

    private void prepareSwitch(boolean conflict) throws Exception {
        Instrumentation instrumentation = this;
        Context context = instrumentation.getTargetContext();
        File receipt = readyFile(context);
        PublicationDraftActivity activity = launchDraft(context);
        ready(activity);
        instrumentation.runOnMainSync(() -> button(activity.getWindow().getDecorView(),
                activity.getString(R.string.draft_new)).performClick());
        ready(activity);
        JSONObject source = new JSONObject(((JSONObject) field(activity, "draft")).toString());
        Uri selected = insert(context, false);
        try (InputStream input = new FileInputStream(new OracleVerifier(context).prepareFixture());
              OutputStream output = context.getContentResolver().openOutputStream(selected)) {
            byte[] buffer = new byte[65536];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            DataOutputStream atom = new DataOutputStream(output);
            byte[] unique = java.util.UUID.randomUUID().toString().getBytes("UTF-8");
            atom.writeInt(unique.length + 8); atom.writeBytes("free"); atom.write(unique);
        }
        ContentValues finalized = new ContentValues();
        finalized.put(MediaStore.MediaColumns.IS_PENDING, 0);
        context.getContentResolver().update(selected, finalized, null, null);
        Object verified = verifiedVideo(activity, context, selected);
        JSONObject targetB = configured(bindVerified(verified, createDraft(activity, "Switch target B")),
                "Chosen B", "B durable description",
                new String[]{"b-tag", "shared"}, "ja", "other", "unlisted",
                "not-made-for-kids", "creative-commons-attribution", "B credits");
        store(activity, "save", JSONObject.class, targetB);
        JSONObject targetC = configured(bindVerified(verified, createDraft(activity, "Switch target C")),
                "Other C", "C alternate description",
                new String[]{"c-tag", "alternate"}, "fr", "youtube", "private",
                "made-for-kids", "youtube-standard", "C credits");
        store(activity, "save", JSONObject.class, targetC);
        final String chooserLabel = chooserLabel(targetB);
        instrumentation.runOnMainSync(() -> invoke(activity, "resolve", new Class<?>[]{verified.getClass(),
                JSONObject.class, String.class, String.class, JSONObject.class, boolean.class},
                verified, source, "", null, null, false));
        waitForText(chooserLabel);
        ExecutorService worker = (ExecutorService) field(activity, "worker");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), captured = new CountDownLatch(1);
        AtomicReference<Future<?>> blocker = new AtomicReference<>();
        blocker.set(worker.submit(() -> {
            entered.countDown();
            try { assertTrue(release.await(30, TimeUnit.SECONDS)); }
            catch (InterruptedException error) { throw new AssertionError(error); }
        }));
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Application.ActivityLifecycleCallbacks callbacks = new Application.ActivityLifecycleCallbacks() {
            public void onActivitySaveInstanceState(Activity target, Bundle state) {
                if (target != activity) return;
                try {
                    JSONObject old = new JSONObject(state.getString("draft"));
                    assertEquals(source.getString("id"), old.getString("id"));
                    assertEquals(source.getLong("updatedAt"), old.getLong("updatedAt"));
                    assertFalse(state.getString("operation", "").isEmpty());
                    release.countDown();
                    blocker.get().get(5, TimeUnit.SECONDS);
                    worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
                    JSONObject preparedTarget = (JSONObject) store(activity, "load", String.class, targetB.getString("id"));
                    JSONObject durable = preparedTarget;
                    if (conflict) {
                        JSONObject intervening = new JSONObject(preparedTarget.toString())
                                .put("title", "Conflicting B")
                                .put("description", "Intervening save after chooser selection");
                        store(activity, "save", JSONObject.class, intervening);
                        durable = intervening;
                    }
                    File operationReceipt = operationReceipt(activity, state.getString("operation"));
                    assertTrue(operationReceipt.isFile());
                    writeReady(receipt, new JSONObject()
                            .put("sourceRevision", BuildConfig.SOURCE_REVISION)
                            .put("pid", android.os.Process.myPid())
                            .put("taskId", activity.getTaskId())
                            .put("mode", conflict ? "switch-conflict" : "switch")
                            .put("pendingOperation", state.getString("operation"))
                            .put("operationReceiptPath", operationReceipt.getAbsolutePath())
                            .put("sourceDraftId", source.getString("id"))
                            .put("sourceSnapshot", old)
                            .put("targetDraftId", targetB.getString("id"))
                            .put("chooserLabel", chooserLabel)
                            .put("expectedConflict", conflict)
                            .put("expectedMessageContains", "Conflicting")
                            .put("preparedTarget", preparedTarget)
                            .put("durableTarget", durable));
                } catch (Throwable error) { failure.set(error); }
                finally { captured.countDown(); }
            }
            public void onActivityCreated(Activity a, Bundle b) {}
            public void onActivityStarted(Activity a) {}
            public void onActivityResumed(Activity a) {}
            public void onActivityPaused(Activity a) {}
            public void onActivityStopped(Activity a) {}
            public void onActivityDestroyed(Activity a) {}
        };
        activity.getApplication().registerActivityLifecycleCallbacks(callbacks);
        try {
            clickText(chooserLabel);
            instrumentation.runOnMainSync(() -> activity.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
            assertTrue(captured.await(20, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError(failure.get());
            SystemClock.sleep(120000);
            fail("Host must kill the identified background process after reading the receipt");
        } finally {
            release.countDown();
            activity.getApplication().unregisterActivityLifecycleCallbacks(callbacks);
        }
    }

    private JSONObject configured(JSONObject draft, String title, String description, String[] tags,
            String language, String platform, String privacy, String audience,
            String license, String credits) throws Exception {
        return draft.put("title", title)
                .put("description", description)
                .put("tags", new JSONArray().put(tags[0]).put(tags[1]))
                .put("language", language)
                .put("platform", platform)
                .put("privacy", privacy)
                .put("audience", audience)
                .put("publicationLicense", license)
                .put("musicCredits", credits);
    }

    private String chooserLabel(JSONObject target) throws Exception {
        return target.getString("title") + "\n" + target.getString("id") + "\n" + target.getString("musicCredits");
    }

    private File readyFile(Context context) {
        File receipt = new File(context.getFilesDir(), "publication-process-death-ready.json");
        if (receipt.exists()) assertTrue(receipt.delete());
        return receipt;
    }

    private PublicationDraftActivity launchDraft(Context context) {
        startActivitySync(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setClass(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        return (PublicationDraftActivity) startActivitySync(
                new Intent(context, PublicationDraftActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private JSONObject inspected(Activity activity, Context context, JSONObject base, Uri video) throws Exception {
        java.lang.reflect.Method inspect = Class.forName("com.simple.videoeditor.PublicationDraftMedia", true,
                activity.getClass().getClassLoader()).getDeclaredMethod("inspect", Context.class, JSONObject.class, Uri.class);
        inspect.setAccessible(true);
        return (JSONObject) inspect.invoke(null, context, base, video);
    }

    private Object verifiedVideo(Activity activity, Context context, Uri video) throws Exception {
        ClassLoader loader = activity.getClass().getClassLoader();
        Class<?> readsType = Class.forName("com.simple.videoeditor.PublicationDraftMedia$Reads", true, loader);
        java.lang.reflect.Constructor<?> readsConstructor = readsType.getDeclaredConstructor();
        readsConstructor.setAccessible(true);
        java.lang.reflect.Method verify = Class.forName("com.simple.videoeditor.PublicationDraftMedia", true, loader)
                .getDeclaredMethod("verify", Context.class, Uri.class, readsType);
        verify.setAccessible(true);
        return verify.invoke(null, context, video, readsConstructor.newInstance());
    }

    private JSONObject bindVerified(Object verified, JSONObject draft) throws Exception {
        java.lang.reflect.Method bind = verified.getClass().getDeclaredMethod("bind", JSONObject.class);
        bind.setAccessible(true);
        return (JSONObject) bind.invoke(verified, draft);
    }

    private void writeReady(File receipt, JSONObject proof) throws Exception {
        try (FileOutputStream output = new FileOutputStream(receipt)) {
            output.write(proof.toString().getBytes("UTF-8"));
            output.getFD().sync();
        }
    }

    private Uri insert(Context context, boolean cover) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME,
                "publication-death-" + System.currentTimeMillis() + (cover ? ".png" : ".mp4"));
        values.put(MediaStore.MediaColumns.MIME_TYPE, cover ? "image/png" : "video/mp4");
        if (!cover) values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(cover ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                : MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri);
        return uri;
    }

    private Object store(Activity activity, String name, Class<?> type, Object value) throws Exception {
        Class<?> store = Class.forName("com.simple.videoeditor.PublicationDraftStore", true, activity.getClass().getClassLoader());
        java.lang.reflect.Constructor<?> constructor = store.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        java.lang.reflect.Method method = store.getDeclaredMethod(name, type);
        method.setAccessible(true);
        return method.invoke(constructor.newInstance(activity), value);
    }

    private JSONObject createDraft(Activity activity, String name) throws Exception {
        Class<?> store = Class.forName("com.simple.videoeditor.PublicationDraftStore", true, activity.getClass().getClassLoader());
        java.lang.reflect.Method method = store.getDeclaredMethod("create", String.class);
        method.setAccessible(true);
        return (JSONObject) method.invoke(null, name);
    }

    private File operationReceipt(Activity activity, String operation) throws Exception {
        Class<?> store = Class.forName("com.simple.videoeditor.PublicationDraftStore", true, activity.getClass().getClassLoader());
        java.lang.reflect.Constructor<?> constructor = store.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        java.lang.reflect.Method method = store.getDeclaredMethod("operationFile", String.class);
        method.setAccessible(true);
        return (File) method.invoke(constructor.newInstance(activity), operation);
    }

    private Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private void invoke(Object target, String name, Class<?>[] types, Object... values) {
        try {
            java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(target, values);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private void ready(PublicationDraftActivity activity) throws Exception {
        long end = SystemClock.uptimeMillis() + 15000;
        while (SystemClock.uptimeMillis() < end) {
            AtomicReference<Boolean> busy = new AtomicReference<>(true);
            runOnMainSync(() -> {
                try { busy.set((boolean) field(activity, "busy")); }
                catch (Exception error) { throw new AssertionError(error); }
            });
            if (!busy.get()) return;
            SystemClock.sleep(100);
        }
        fail("Draft did not settle");
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
            if (node(getUiAutomation().getRootInActiveWindow(), text) != null) return;
            SystemClock.sleep(100);
        }
        fail("Missing chooser text: " + text);
    }

    private void clickText(String text) throws Exception {
        waitForText(text);
        AccessibilityNodeInfo found = node(getUiAutomation().getRootInActiveWindow(), text);
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
            assertTrue(getUiAutomation().injectInputEvent(down, true));
            assertTrue(getUiAutomation().injectInputEvent(up, true));
        } finally {
            down.recycle();
            up.recycle();
        }
        SystemClock.sleep(200);
    }

    private Button button(View view, String text) {
        if (view instanceof Button && text.equals(((Button) view).getText().toString())) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            Button found = button(((ViewGroup) view).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
}
