package com.simple.videoeditor;

import android.os.Bundle;
import android.os.Process;
import android.test.InstrumentationTestCase;

import java.io.File;

/** Destructive kill/recover pairs require an explicit host-selected durability boundary. */
public final class ReportJournalDeathTest extends InstrumentationTestCase {
    private Bundle arguments() throws Exception {
        return (Bundle) getInstrumentation().getClass().getMethod("getArguments")
                .invoke(getInstrumentation());
    }

    private File root() {
        return new File(getInstrumentation().getTargetContext().getFilesDir(), "journal-death-test");
    }

    private boolean hostSelectedBoundary() throws Exception {
        Bundle args = arguments();
        if (args.containsKey("journalStage")) return true;
        assertFalse("journalFormat requires journalStage", args.containsKey("journalFormat"));
        assertFalse("journalOperation requires journalStage", args.containsKey("journalOperation"));
        assertFalse("journalKilledPid requires journalStage", args.containsKey("journalKilledPid"));
        android.util.Log.i("ReportJournalDeath", "NOT RUN: host-only process-death scenario; no journalStage");
        return false;
    }

    public void testKillAtDurabilityBoundary() throws Exception {
        if (!hostSelectedBoundary()) return;
        String stage = arguments().getString("journalStage");
        String format = arguments().getString("journalFormat");
        assertNotNull("host must select a durability boundary", stage);
        assertNotNull(format);
        ReportJournalTest.delete(root());
        assertTrue(root().mkdirs());
        File file = new File(root(), "report.txt");
        ReportJournalTest.seed(file, format);
        boolean append = "append".equals(arguments().getString("journalOperation"));
        if (append) ReportJournal.append(file, "");
        ReportJournal.faultInjector = current -> {
            if (stage.equals(current)) {
                Bundle evidence = new Bundle();
                evidence.putString("journal_stage", stage);
                evidence.putString("journal_format", format);
                evidence.putString("journal_revision", BuildConfig.SOURCE_REVISION);
                evidence.putInt("journal_pid", Process.myPid());
                getInstrumentation().sendStatus(2, evidence);
                android.util.Log.i("ReportJournalDeath", "KILL stage=" + stage + " format=" + format
                        + " revision=" + BuildConfig.SOURCE_REVISION);
                Process.killProcess(Process.myPid());
                throw new AssertionError("kill returned");
            }
        };
        if (append) ReportJournal.append(file, ReportJournalTest.NEW);
        else ReportJournal.replace(file, ReportJournalTest.NEW);
        fail("kill boundary not reached");
    }

    public void testRecoverAfterProcessDeath() throws Exception {
        if (!hostSelectedBoundary()) return;
        String stage = arguments().getString("journalStage");
        assertNotNull(stage);
        assertFalse("recovery must execute in a fresh process",
                String.valueOf(Process.myPid()).equals(arguments().getString("journalKilledPid")));
        boolean append = "append".equals(arguments().getString("journalOperation"));
        String expected = append ? ReportJournalTest.OLD
                + (ReportJournalTest.published(stage) ? ReportJournalTest.NEW : "")
                : ReportJournalTest.published(stage) ? ReportJournalTest.NEW : ReportJournalTest.OLD;
        File file = new File(root(), "report.txt");
        assertEquals(expected, ReportJournalTest.read(file));
        ReportJournal.append(file, "recovered\n");
        assertEquals(expected + "recovered\n", ReportJournalTest.read(file));
        ReportJournalTest.delete(root());
    }
}
