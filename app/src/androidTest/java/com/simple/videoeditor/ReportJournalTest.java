package com.simple.videoeditor;

import android.test.AndroidTestCase;
import android.util.AtomicFile;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class ReportJournalTest extends AndroidTestCase {
    static final String OLD = "OLD complete report 中文😀\nEND CHECKPOINT\n";
    static final String NEW = "NEW first checkpoint 中文😀\nEND CHECKPOINT\n";
    static final String[] FORMATS = {"whole", "whole-backup", "offset", "offset-backup", "generation"};
    static final String[] REPLACE_STAGES = {"generation-unlink", "generation-open", "generation-write",
            "generation-sync", "generation-directory", "marker-open", "marker-write", "marker-sync",
            "marker-publish", "marker-directory", "legacy-retire"};
    static final String[] APPEND_STAGES = {"append-write", "append-sync", "marker-open", "marker-write",
            "marker-sync", "marker-publish", "marker-directory"};
    private File root;

    @Override protected void setUp() throws Exception {
        super.setUp();
        root = new File(getContext().getFilesDir(), "journal-test-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
    }

    @Override protected void tearDown() throws Exception {
        ReportJournal.faultInjector = null;
        delete(root);
        super.tearDown();
    }

    static void delete(File file) {
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    static void seed(File file, String format) throws Exception {
        if ("generation".equals(format)) {
            ReportJournal.replace(file, OLD);
        } else {
            SavedReport.atomic(file, OLD);
            if (format.startsWith("offset")) {
                try (FileOutputStream output = new FileOutputStream(file + ".commit")) {
                    new DataOutputStream(output).writeLong(file.length());
                    output.getFD().sync();
                }
                if (format.endsWith("backup")) {
                    assertTrue(new File(file + ".commit").renameTo(new File(file + ".commit.bak")));
                    try (FileOutputStream output = new FileOutputStream(file + ".commit")) {
                        new DataOutputStream(output).writeLong(Long.MAX_VALUE);
                        output.getFD().sync();
                    }
                }
            } else if (format.endsWith("backup")) {
                assertTrue(file.renameTo(new File(file + ".bak")));
                try (FileOutputStream output = new FileOutputStream(file)) {
                    output.write("incomplete legacy replacement".getBytes(StandardCharsets.UTF_8));
                    output.getFD().sync();
                }
            }
        }
    }

    static String read(File file) throws IOException {
        return SavedReportTest.read(ReportJournal.open(file));
    }

    static boolean published(String stage) {
        return "marker-publish".equals(stage) || "marker-directory".equals(stage)
                || "legacy-retire".equals(stage);
    }

    public void testEveryReplacementInterruptionPreservesOldOrCompleteNew() throws Exception {
        for (String format : FORMATS) for (String stage : REPLACE_STAGES) {
            File file = new File(root, format + "-" + stage);
            seed(file, format);
            final boolean[] reached = {false};
            ReportJournal.faultInjector = current -> {
                if (stage.equals(current)) {
                    reached[0] = true;
                    throw new IOException("injected " + stage);
                }
            };
            try {
                ReportJournal.replace(file, NEW);
                fail(format + " missed " + stage);
            } catch (IOException expected) {
                assertTrue(expected.getMessage(), reached[0]);
            } finally { ReportJournal.faultInjector = null; }
            assertEquals(format + " " + stage, published(stage) ? NEW : OLD, read(file));
            // Retry, alternate slots and append; neither a stale marker nor an orphan may win.
            ReportJournal.replace(file, NEW);
            ReportJournal.append(file, "next\n");
            assertEquals(NEW + "next\n", read(file));
        }
    }

    public void testEveryAppendInterruptionDiscardsOnlyUncommittedTail() throws Exception {
        for (String format : FORMATS) for (String stage : APPEND_STAGES) {
            File file = new File(root, format + "-" + stage);
            seed(file, format);
            // Whole-file formats first establish the old offset, not a new report generation.
            ReportJournal.append(file, "");
            ReportJournal.faultInjector = current -> {
                if (stage.equals(current)) throw new IOException("injected " + stage);
            };
            try {
                ReportJournal.append(file, NEW);
                fail("injection not reached " + stage);
            } catch (IOException expected) {
                assertEquals("injected " + stage, expected.getMessage());
            } finally { ReportJournal.faultInjector = null; }
            String expected = OLD + (published(stage) ? NEW : "");
            assertEquals(format + " " + stage, expected, read(file));
            ReportJournal.append(file, "after\n");
            assertEquals(expected + "after\n", read(file));
        }
    }

    public void testOpenReadersSurviveSlotReuseAndRetentionIsBounded() throws Exception {
        File file = new File(root, "report.txt");
        seed(file, "offset-backup");
        try (FileInputStream old = ReportJournal.open(file)) {
            ReportJournal.replace(file, NEW);
            try (FileInputStream first = ReportJournal.open(file)) {
                for (int i = 0; i < 20; i++) ReportJournal.replace(file, "replacement " + i);
                assertEquals(NEW, SavedReportTest.read(first));
            }
            assertEquals(OLD, SavedReportTest.read(old));
        }
        assertEquals("replacement 19", read(file));
        assertEquals(3, root.listFiles().length); // Two bounded slots and one combined marker.
    }

    public void testMalformedCommittedOffsetsStillFailExplicitly() throws Exception {
        File file = new File(root, "invalid");
        seed(file, "offset");
        try (FileOutputStream output = new FileOutputStream(file + ".commit")) {
            new DataOutputStream(output).writeLong(Long.MAX_VALUE);
            output.getFD().sync();
        }
        try {
            ReportJournal.replace(file, NEW);
            fail("corruption must not become empty success");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("Invalid report commit offset"));
        }
        assertEquals(OLD, SavedReportTest.read(new FileInputStream(file)));
    }

    public void testLegacyAtomicFileNewAndBackupFormatsRemainReadable() throws Exception {
        File file = new File(root, "legacy");
        SavedReport.atomic(file, OLD);
        FileOutputStream pending = new AtomicFile(file).startWrite();
        pending.write(NEW.getBytes(StandardCharsets.UTF_8));
        pending.getFD().sync();
        pending.close(); // Process-death image: intentionally neither finishWrite nor failWrite.
        assertEquals(OLD, read(file));
        ReportJournal.replace(file, NEW);
        assertEquals(NEW, read(file));
    }
}
