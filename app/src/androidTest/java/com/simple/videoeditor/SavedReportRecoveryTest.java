package com.simple.videoeditor;

import android.content.UriPermission;
import android.net.Uri;
import android.test.AndroidTestCase;

/** Run only AFTER the host has killed a suite using a real user-selected DocumentsUI TXT. */
public final class SavedReportRecoveryTest extends AndroidTestCase {
    public void testRealDocumentReadableWithPersistedPermissionAfterKill() throws Exception {
        Uri uri = SavedReport.lastUri(getContext());
        assertNotNull("First run the real picker/force-stop host scenario", uri);
        boolean retained = false;
        for (UriPermission grant : getContext().getContentResolver().getPersistedUriPermissions()) {
            if (uri.equals(grant.getUri())) retained = grant.isReadPermission() && grant.isWritePermission();
        }
        assertTrue("SAF read/write grant must survive process death", retained);
        String[] markers = {"PASS CONTROL", "STAGE CONTROL", "END CHECKPOINT",
                "FINAL CHECK RESULTS", BuildConfig.SOURCE_REVISION};
        boolean[] found = new boolean[markers.length];
        try (java.io.InputStream input = getContext().getContentResolver().openInputStream(uri);
             java.io.InputStreamReader reader = new java.io.InputStreamReader(
                     input, java.nio.charset.StandardCharsets.UTF_8)) {
            char[] buffer = new char[4096];
            String carry = "";
            int count;
            while ((count = reader.read(buffer)) != -1) {
                String chunk = carry + new String(buffer, 0, count);
                for (int i = 0; i < markers.length; i++) found[i] |= chunk.contains(markers[i]);
                carry = chunk.substring(Math.max(0, chunk.length() - 128));
            }
        }
        assertTrue(found[0]);
        assertTrue(found[1]);
        assertTrue(found[2]);
        assertFalse(found[3]);
        assertTrue(SavedReport.location(getContext()).contains("RUNNING"));
        assertTrue(found[4]);
    }
}
