package com.simple.videoeditor;

import android.test.InstrumentationTestCase;

/** Must run in a fresh instrumentation process after DirectVideoUiTest. */
public final class DirectVideoRestoreTest extends InstrumentationTestCase {
    public void testPersistedSafSourceInFreshProcess() throws Exception {
        SelectedFramePreviewTest ui = new SelectedFramePreviewTest();
        ui.injectInstrumentation(getInstrumentation());
        ui.setUp();
        try { ui.directSafGrantSurvivesFreshInstrumentationProcess(); }
        finally { ui.tearDown(); }
    }
}
