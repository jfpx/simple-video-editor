package com.simple.videoeditor;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.test.InstrumentationTestCase;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public final class VideoEncodingSettingsTest extends InstrumentationTestCase {
    public void testCompatibilityDefaultsOffInIsolatedPreferences() {
        IsolatedPreferences context = new IsolatedPreferences(
                getInstrumentation().getTargetContext(), "encoding-test-" + UUID.randomUUID());
        try {
            assertFalse(VideoEncodingSettings.compatibilityEnabled(context));
            assertEquals("default", VideoEncodingSettings.modeName(false));
            assertEquals("software-avc", VideoEncodingSettings.modeName(true));
        } finally {
            context.clear();
        }
    }

    public void testCompatibilityOptInAndOptOutPersistAcrossContextInstances() {
        Context base = getInstrumentation().getTargetContext();
        String namespace = "encoding-test-" + UUID.randomUUID();
        IsolatedPreferences first = new IsolatedPreferences(base, namespace);
        IsolatedPreferences reopened = new IsolatedPreferences(base, namespace);
        IsolatedPreferences unrelated = new IsolatedPreferences(base, namespace + "-other");
        boolean original = VideoEncodingSettings.compatibilityEnabled(first);
        try {
            assertFalse(original);
            VideoEncodingSettings.setCompatibilityEnabled(first, true);
            assertTrue(VideoEncodingSettings.compatibilityEnabled(first));
            assertTrue("Opt-in must live in preferences, not a context-local flag",
                    VideoEncodingSettings.compatibilityEnabled(reopened));
            assertFalse("Preference namespaces must remain independent",
                    VideoEncodingSettings.compatibilityEnabled(unrelated));
            assertFalse("Setter must use persistent SharedPreferences", first.names.isEmpty());
            boolean storedOptIn = false;
            for (String name : first.names) {
                SharedPreferences preferences = base.getSharedPreferences(name, Context.MODE_PRIVATE);
                storedOptIn |= preferences.getAll().containsValue(Boolean.TRUE);
                assertTrue("Pending preference writes must be durable", preferences.edit().commit());
            }
            assertTrue("Opt-in must be stored as a boolean", storedOptIn);
            VideoEncodingSettings.setCompatibilityEnabled(reopened, false);
            assertFalse("Opt-out must also persist", VideoEncodingSettings.compatibilityEnabled(first));
            assertEquals("default", VideoEncodingSettings.modeName(
                    VideoEncodingSettings.compatibilityEnabled(reopened)));
        } finally {
            try {
                VideoEncodingSettings.setCompatibilityEnabled(first, original);
            } finally {
                first.clear();
                reopened.clear();
                unrelated.clear();
            }
        }
    }

    private static final class IsolatedPreferences extends ContextWrapper {
        final Set<String> names = new HashSet<>();
        private final String namespace;

        IsolatedPreferences(Context base, String namespace) {
            super(base);
            this.namespace = namespace;
        }

        @Override public Context getApplicationContext() {
            return this;
        }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            String isolatedName = namespace + "-" + name;
            names.add(isolatedName);
            return super.getSharedPreferences(isolatedName, mode);
        }

        void clear() {
            for (String name : names) {
                assertTrue(getBaseContext().getSharedPreferences(name, Context.MODE_PRIVATE)
                        .edit().clear().commit());
            }
        }
    }
}
