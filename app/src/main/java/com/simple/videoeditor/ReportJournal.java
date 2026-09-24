package com.simple.videoeditor;

import android.util.AtomicFile;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;

/** One global lock covers recovery, append, generation publication and opening stable readers. */
final class ReportJournal {
    private ReportJournal() {}

    interface FaultInjector { void after(String stage) throws IOException; }
    static volatile FaultInjector faultInjector;

    private static void after(String stage) throws IOException {
        FaultInjector injector = faultInjector;
        if (injector != null) injector.after(stage);
    }

    private static AtomicFile commitFile(File file) {
        return new AtomicFile(new File(file.getPath() + ".commit"));
    }

    static synchronized boolean exists(File file) {
        return file.isFile() || new File(file + ".bak").isFile()
                || commitFile(file).getBaseFile().isFile()
                || new File(file + ".commit.bak").isFile();
    }

    static synchronized void replace(File file, String... parts) throws IOException {
        directory(file);
        Boundary previous = recoverBoundary(file);
        // Stabilize a publication whose previous caller failed after rename, before reusing a slot.
        syncDirectory(file.getParentFile());
        int slot = previous.slot == 0 ? 1 : 0;
        File next = generation(file, slot);
        // Unlink rather than truncate: an already-open reader may still own this inode.
        if (next.exists() && !next.delete()) throw new IOException("Cannot remove inactive generation");
        after("generation-unlink");
        try (FileOutputStream output = new FileOutputStream(next);
             OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
            after("generation-open");
            for (String part : parts) write(writer, part);
            writer.flush();
            after("generation-write");
            output.getFD().sync();
            after("generation-sync");
        }
        syncDirectory(file.getParentFile());
        after("generation-directory");
        // The one atomic marker contains BOTH generation and offset. Never reset the old data.
        commit(file, new Boundary(slot, next.length()));
        if (file.exists() && !file.delete()) throw new IOException("Cannot retire legacy report");
        File legacyPending = new File(file + ".new");
        if (legacyPending.exists() && !legacyPending.delete()) {
            throw new IOException("Cannot retire incomplete legacy report");
        }
        after("legacy-retire");
        syncDirectory(file.getParentFile());
    }

    static synchronized FileInputStream open(File file) throws IOException {
        Boundary boundary = recoverBoundary(file);
        return new FileInputStream(generation(file, boundary.slot));
    }

    static synchronized void recover(File file) throws IOException {
        recoverBoundary(file);
    }

    private static Boundary recoverBoundary(File file) throws IOException {
        // Legacy reports used AtomicFile for the entire TXT.
        if (file.isFile() || new File(file.getPath() + ".bak").isFile()) {
            try (FileInputStream ignored = new AtomicFile(file).openRead()) { }
        }
        AtomicFile marker = commitFile(file);
        if (!marker.getBaseFile().exists()
                && !new File(marker.getBaseFile().getPath() + ".bak").exists()) {
            return new Boundary(-1, file.length());
        }
        Boundary boundary;
        try (DataInputStream input = new DataInputStream(marker.openRead())) {
            long value = input.readLong();
            boundary = value == -1 ? new Boundary(input.readInt(), input.readLong())
                    : new Boundary(-1, value);
            if (input.read() != -1 || (value == -1 && boundary.slot != 0 && boundary.slot != 1)) {
                throw new IOException("Invalid report generation marker: " + file);
            }
        }
        File data = generation(file, boundary.slot);
        long length = boundary.length;
        if (!data.isFile() || length < 0 || length > data.length()) {
            throw new IOException("Invalid report commit offset: " + file);
        }
        if (data.length() != length) {
            try (RandomAccessFile output = new RandomAccessFile(data, "rw")) {
                output.setLength(length);
                output.getFD().sync();
            }
        }
        return boundary;
    }

    static synchronized void append(File file, String... parts) throws IOException {
        try {
            directory(file);
            Boundary before = recoverBoundary(file);
            File data = generation(file, before.slot);
            // Establish a recovery boundary before the first append (including legacy reports).
            if (!data.exists()) {
                try (FileOutputStream output = new FileOutputStream(data)) { output.getFD().sync(); }
            }
            if (!commitFile(file).getBaseFile().exists()) commit(file, before);
            try (FileOutputStream output = new FileOutputStream(data, true);
                 OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) {
                for (String part : parts) write(writer, part);
                writer.flush();
                after("append-write");
                output.getFD().sync();
                after("append-sync");
            }
            commit(file, new Boundary(before.slot, data.length()));
        } catch (RuntimeException error) {
            throw new IOException("Private report storage failed", error);
        }
    }

    static void write(OutputStreamWriter writer, String text) throws IOException {
        for (int offset = 0; offset < text.length(); offset += 4096) {
            writer.write(text, offset, Math.min(4096, text.length() - offset));
        }
    }

    private static void commit(File file, Boundary boundary) throws IOException {
        File marker = commitFile(file).getBaseFile();
        File pending = new File(marker + ".next");
        // Normalize legacy AtomicFile backups before publication; a stale .bak must never undo it.
        if (new File(marker + ".bak").isFile()) {
            try (FileInputStream ignored = commitFile(file).openRead()) { }
        }
        syncDirectory(file.getParentFile());
        try (FileOutputStream output = new FileOutputStream(pending)) {
            after("marker-open");
            DataOutputStream data = new DataOutputStream(output);
            if (boundary.slot != -1) {
                data.writeLong(-1);
                data.writeInt(boundary.slot);
            }
            data.writeLong(boundary.length);
            data.flush();
            after("marker-write");
            output.getFD().sync();
            after("marker-sync");
        }
        try {
            Os.rename(pending.getPath(), marker.getPath());
        } catch (ErrnoException error) {
            throw new IOException("Cannot publish report commit", error);
        }
        after("marker-publish");
        syncDirectory(file.getParentFile());
        after("marker-directory");
    }

    private static File generation(File file, int slot) {
        return slot == -1 ? file : new File(file + ".generation-" + slot);
    }

    private static void directory(File file) throws IOException {
        File parent = file.getParentFile();
        if (!parent.isDirectory()) {
            if (!parent.mkdirs()) throw new IOException("Cannot create report directory");
            syncDirectory(parent.getParentFile());
        }
    }

    private static void syncDirectory(File directory) throws IOException {
        try {
            FileDescriptor descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
            try { Os.fsync(descriptor); }
            finally { Os.close(descriptor); }
        } catch (ErrnoException error) {
            throw new IOException("Cannot sync report directory", error);
        }
    }

    private static final class Boundary {
        final int slot;
        final long length;
        Boundary(int slot, long length) { this.slot = slot; this.length = length; }
    }
}
