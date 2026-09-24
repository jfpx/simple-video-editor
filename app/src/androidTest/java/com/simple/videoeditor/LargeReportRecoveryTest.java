package com.simple.videoeditor;

import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.test.AndroidTestCase;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Host-only fixture extension of its newly selected real Downloads document, never a user's phone. */
public final class LargeReportRecoveryTest extends AndroidTestCase {
    public void testExtendSelectedEmulatorDocumentForLargeUiRecovery() throws Exception {
        assertTrue("ranchu".equals(android.os.Build.HARDWARE)
                || "goldfish".equals(android.os.Build.HARDWARE));
        Uri uri = SavedReport.lastUri(getContext());
        assertNotNull(uri);
        char[] data = new char[64 * 1024];
        Arrays.fill(data, 'z');
        String payload = new String(data);
        try (ParcelFileDescriptor descriptor = getContext().getContentResolver().openFileDescriptor(uri, "rw");
             FileOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(descriptor);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            assertTrue(descriptor.getStatSize() > 0);
            output.getChannel().position(output.getChannel().size());
            writer.write("\nLARGE UI RECOVERY FIXTURE — not suite results\n");
            for (int i = 0; i < 512; i++) {
                writer.write("{\"ui_event\":" + i + ",\"payload\":\"" + payload + "中文😀\"}\n");
            }
            writer.write("LARGE_UI_LAST_EVENT_511 — full TXT preserved\n");
            writer.flush();
            output.getFD().sync();
        }
    }
}
