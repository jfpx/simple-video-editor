package com.simple.videoeditor;

import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

public final class EditorSuiteModesTest extends AndroidTestCase {
    public void testActualShortDefaultThreeExportsTenControls() throws Exception { run(false); }
    public void testActualShortSoftwareThreeExportsTenControls() throws Exception { run(true); }

    private void run(boolean software) throws Exception {
        boolean original = VideoEncodingSettings.compatibilityEnabled(getContext());
        CountDownLatch done = new CountDownLatch(1);
        SelfTestRunner runner = new SelfTestRunner(getContext(), (text, running) -> {
            if (!running) done.countDown();
        });
        try {
            VideoEncodingSettings.setCompatibilityEnabled(getContext(), software);
            runner.start(null, SelfTestPlan.Mode.SHORT_DIAGNOSTIC);
            assertTrue("Native short suite deadline", done.await(8, TimeUnit.MINUTES));
            assertFalse(runner.isRunning());
            File suite = new File(runner.getReportFile().getParentFile(), "suite.json");
            try (FileInputStream input = new FileInputStream(suite); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                JSONObject report = new JSONObject(bytes.toString("UTF-8"));
                assertEquals(report.toString(), "SUBSET_PASS", report.getString("status"));
                assertEquals("NOT_RUN", report.getString("full_suite_status"));
                assertEquals("LIMITED_SUBSET", report.getString("coverage"));
                assertEquals("SUBSET_PASS", report.getJSONObject("outcome").getString("state"));
                assertTrue(report.getBoolean("complete"));
                assertEquals(3, report.getInt("planned_export_count")); assertEquals(10, report.getInt("planned_control_count"));
                assertEquals(3, report.getJSONArray("exports").length()); assertEquals(10, report.getJSONArray("checker_controls").length());
                for (String key : new String[]{"exports", "checker_controls"})
                    for (int i = 0; i < report.getJSONArray(key).length(); i++)
                        assertEquals("PASS", report.getJSONArray(key).getJSONObject(i).getString("status"));
            }
        } finally {
            if (runner.isRunning()) { runner.cancel(); assertTrue(done.await(15, TimeUnit.SECONDS)); }
            VideoEncodingSettings.setCompatibilityEnabled(getContext(), original);
        }
    }
}
