package com.simple.videoeditor;

import android.app.Instrumentation;
import android.os.Bundle;
import android.test.InstrumentationTestCase;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** Test-APK-only bridge: use the production settings API, never edit private XML via adb. */
public final class EditorWorkflowSettingsTest extends InstrumentationTestCase {
    public void testConfigure() throws Exception {
        Instrumentation instrumentation = getInstrumentation();
        Bundle arguments = (Bundle) instrumentation.getClass().getMethod("getArguments").invoke(instrumentation);
        String action = arguments.getString("workflow_action", "query");
        if (!action.equals("query") && !action.equals("default") && !action.equals("software-avc"))
            throw new IllegalArgumentException("Unknown workflow action");
        String testRevision;
        try (InputStream input = instrumentation.getContext().getAssets().open("workflow-source-revision.txt")) {
            byte[] bytes = new byte[128];
            int length = input.read(bytes);
            testRevision = new String(bytes, 0, length, StandardCharsets.UTF_8).trim();
        }
        // Reflection avoids javac inlining the test APK's copy of the app constant.
        String appRevision = (String) Class.forName("com.simple.videoeditor.BuildConfig")
                .getField("SOURCE_REVISION").get(null);
        boolean before = VideoEncodingSettings.compatibilityEnabled(instrumentation.getTargetContext());
        if (!action.equals("query")) {
            assertTrue("Encoding preference must reach disk before instrumentation exits",
                    VideoEncodingSettings.setCompatibilityEnabledAndWait(
                            instrumentation.getTargetContext(), action.equals("software-avc")));
        }
        boolean after = VideoEncodingSettings.compatibilityEnabled(instrumentation.getTargetContext());
        JSONObject attestation = new JSONObject()
                .put("source_revision", appRevision).put("test_source_revision", testRevision)
                .put("token", arguments.getString("workflow_token"))
                .put("action", action).put("before", before).put("after", after)
                .put("policy", VideoEncodingSettings.modeName(after));
        String preferenceAction = arguments.getString("workflow_preferences");
        if (preferenceAction != null) {
            String owner = arguments.getString("workflow_preferences_token", "");
            assertTrue(owner.matches("[a-f0-9]{32}"));
            java.io.File snapshot = new java.io.File(instrumentation.getTargetContext().getFilesDir(),
                    "workflow-preferences-" + owner + ".json");
            if (preferenceAction.equals("snapshot")) {
                assertFalse("never overwrite a settings snapshot", snapshot.exists());
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(snapshot)) {
                    output.write(preferences().toString().getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            } else if (preferenceAction.equals("restore")) {
                JSONObject original;
                try (java.io.FileInputStream input = new java.io.FileInputStream(snapshot);
                     java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192]; int count;
                    while ((count = input.read(buffer)) != -1) {
                        assertTrue(bytes.size() + count <= 2 * 1024 * 1024);
                        bytes.write(buffer, 0, count);
                    }
                    original = new JSONObject(bytes.toString("UTF-8"));
                }
                for (String name : PREFERENCE_NAMES) {
                    android.content.SharedPreferences.Editor editor = instrumentation.getTargetContext()
                            .getSharedPreferences(name, 0).edit().clear();
                    JSONObject values = original.getJSONObject(name);
                    java.util.Iterator<String> keys = values.keys();
                    while (keys.hasNext()) {
                        String key = keys.next();
                        Object value = values.get(key);
                        if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                        else {
                            assertTrue("unexpected snapshot value", value instanceof String);
                            editor.putString(key, (String) value);
                        }
                    }
                    assertTrue("settings restore persisted: " + name, editor.commit());
                }
                String language = original.getJSONObject("ui_locales").optString("language", "zh-CN");
                instrumentation.runOnMainSync(() -> {
                    androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                            androidx.core.os.LocaleListCompat.forLanguageTags(language));
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        instrumentation.getTargetContext().getSystemService(android.app.LocaleManager.class)
                                .setApplicationLocales(android.os.LocaleList.forLanguageTags(language));
                    }
                });
            } else {
                assertEquals("verify", preferenceAction);
            }
            attestation.put("preferences", preferences())
                    .put("preferences_action", preferenceAction).put("preferences_token", owner);
        }
        Bundle result = new Bundle();
        result.putString("workflow", attestation.toString());
        instrumentation.sendStatus(2, result);
    }

    private static final String[] PREFERENCE_NAMES = {"ui_locales", "offline_music", "intro_templates"};
    private JSONObject preferences() throws Exception {
        JSONObject result = new JSONObject();
        for (String name : PREFERENCE_NAMES) {
            JSONObject values = new JSONObject();
            for (java.util.Map.Entry<String, ?> entry : getInstrumentation().getTargetContext()
                    .getSharedPreferences(name, 0).getAll().entrySet()) {
                assertTrue("unexpected setting type: " + name + "/" + entry.getKey(),
                        entry.getValue() instanceof String || entry.getValue() instanceof Boolean);
                values.put(entry.getKey(), entry.getValue());
            }
            result.put(name, values);
        }
        return result;
    }
}
