package com.simple.videoeditor;

import android.test.InstrumentationTestCase;

public final class DirectVideoUiTest extends InstrumentationTestCase {
    public void testStockSafConsentCancellationRetryAndRecreation() throws Exception {
        SelectedFramePreviewTest ui = new SelectedFramePreviewTest();
        ui.injectInstrumentation(getInstrumentation());
        ui.setUp();
        try { ui.directSafNoCopyConsentCancelRetryAndRestoredGrant(); }
        finally { ui.tearDown(); }
    }
}
