package com.simple.videoeditor;

import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.test.AndroidTestCase;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** API29+ real MediaStore I/O, with boundary faults through a forwarding provider. */
public final class PublishedVideoTest extends AndroidTestCase {
    private Context isolated;
    private Forwarder provider;
    private File source;
    private final AtomicBoolean cancelled = new AtomicBoolean();

    @Override protected void setUp() throws Exception {
        super.setUp();
        String namespace = "publication-test-" + UUID.randomUUID();
        provider = new Forwarder(getContext().getContentResolver());
        ProviderInfo info = new ProviderInfo();
        info.authority = "media";
        provider.attachInfo(getContext(), info);
        ContentResolver resolver = ContentResolver.wrap(provider);
        isolated = new ContextWrapper(getContext()) {
            @Override public ContentResolver getContentResolver() { return resolver; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(namespace + name, mode);
            }
        };
        source = new File(getContext().getFilesDir(), namespace + ".mp4");
        try (InputStream input = getContext().getAssets().open("video-oracle/standard.mp4");
             FileOutputStream output = new FileOutputStream(source)) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
    }

    @Override protected void tearDown() throws Exception {
        for (Uri uri : provider.created) {
            try (Cursor cursor = provider.real.query(uri, new String[]{"_id"}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) provider.real.delete(uri, null, null);
            }
        }
        PublishedVideo.prefs(isolated).edit().clear().commit();
        source.delete();
        super.tearDown();
    }

    public void testMoviesRootUniqueNamesFinalBytesAndDurableRecovery() throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        PublishedVideo second = PublishedVideo.publish(isolated, source, cancelled);
        assertFalse(first.name.equals(second.name));
        assertEquals("Movies/", second.location);
        assertEquals(second.uri, PublishedVideo.recover(isolated).uri);
        assertFalse(PublishedVideo.prefs(isolated).contains("pending"));
        assertFalse(PublishedVideo.prefs(isolated).contains("reserved"));
        assertBytes(first.uri);
        assertBytes(second.uri);
        assertEquals(2, provider.pendingAtOpen);
    }

    public void testCancelledBeforeCreationRetainsPrivateWithoutRow() throws Exception {
        cancelled.set(true);
        fails();
        assertTrue(provider.created.isEmpty());
        assertPrivateRecovery();
    }

    public void testCancelledDuringCopyDeletesPendingAndRetainsPrivate() throws Exception {
        provider.cancelAtOpen = cancelled;
        fails();
        assertEquals(1, provider.created.size());
        assertGone(provider.created.get(0));
        assertPrivateRecovery();
    }

    public void testStreamFailureDeletesPendingAndRetainsPrivate() throws Exception {
        provider.failOpen = true;
        fails();
        assertGone(provider.created.get(0));
        assertPrivateRecovery();
    }

    public void testFinalizeFailureDeletesCompletePendingCopy() throws Exception {
        provider.failFinalize = true;
        fails();
        assertGone(provider.created.get(0));
        assertPrivateRecovery();
    }

    public void testCancellationAtFinalizeStillDeletesRow() throws Exception {
        provider.cancelAtFinalize = cancelled;
        fails();
        assertGone(provider.created.get(0));
        assertPrivateRecovery();
    }

    public void testCancelledFinalizedRowIsNeverAdoptedAfterCleanupFailure() throws Exception {
        provider.cancelAtFinalize = cancelled;
        provider.failDelete = true;
        fails();
        assertRecoveryCleanupBlocked();
        provider.failDelete = false;
        assertPrivateRecovery();
        assertGone(provider.created.get(0));
        assertJournalCleared();
        cancelled.set(false);
        provider.cancelAtFinalize = null;
        PublishedVideo result = PublishedVideo.publish(isolated, source, cancelled);
        assertEquals(result.uri, PublishedVideo.recover(isolated).uri);
        assertJournalCleared();
    }

    public void testFailureAfterFinalizeIsNeverAdoptedAfterCleanupFailure() throws Exception {
        provider.failDescribe = true;
        provider.failDelete = true;
        fails();
        provider.failDescribe = false;
        assertRecoveryCleanupBlocked();
        provider.failDelete = false;
        assertPrivateRecovery();
        assertGone(provider.created.get(0));
        assertJournalCleared();
    }

    public void testRollbackIntentIsDurableBeforeDelete() throws Exception {
        provider.cancelAtFinalize = cancelled;
        provider.beforeDelete = () -> assertTrue(PublishedVideo.prefs(isolated)
                .getBoolean("rollback", false));
        fails();
        assertPrivateRecovery();
        assertJournalCleared();
    }

    public void testCleanupFailureKeepsJournalUntilRecoverySucceeds() throws Exception {
        provider.failOpen = true;
        provider.failDelete = true;
        fails();
        assertTrue(PublishedVideo.prefs(isolated).contains("pending"));
        provider.failOpen = false;
        provider.failDelete = false;
        assertPrivateRecovery();
        assertGone(provider.created.get(0));
        assertFalse(PublishedVideo.prefs(isolated).contains("pending"));
    }

    public void testDeathBetweenInsertAndJournalRecoversReservedName() throws Exception {
        String name = "edited_" + UUID.randomUUID() + ".mp4";
        PublishedVideo.prefs(isolated).edit().putString("reserved", name)
                .putString("private", source.getAbsolutePath()).commit();
        Uri row = provider.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, pending(name));
        assertPrivateRecovery();
        assertGone(row);
        assertFalse(PublishedVideo.prefs(isolated).contains("reserved"));
    }

    public void testDeathAfterCopyBeforeFinalizeDeletesPending() throws Exception {
        provider.failFinalize = true;
        provider.failDelete = true;
        fails();
        provider.failFinalize = false;
        provider.failDelete = false;
        assertPrivateRecovery();
        assertGone(provider.created.get(0));
    }

    public void testDeathAfterDeleteBeforeJournalClearIsIdempotent() throws Exception {
        Uri row = provider.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                pending("edited_" + UUID.randomUUID() + ".mp4"));
        PublishedVideo.prefs(isolated).edit().putString("pending", row.toString())
                .putString("private", source.getAbsolutePath()).commit();
        provider.real.delete(row, null, null);
        assertPrivateRecovery();
        assertFalse(PublishedVideo.prefs(isolated).contains("pending"));
    }

    public void testDeathAfterFinalizeBeforeResultCommitAdoptsValidPublicRow() throws Exception {
        PublishedVideo result = PublishedVideo.publish(isolated, source, cancelled);
        PublishedVideo.prefs(isolated).edit().remove("uri").remove("location")
                .putString("pending", result.uri.toString())
                .putString("pendingLocation", "Movies").commit();
        assertEquals(result.uri, PublishedVideo.recover(isolated).uri);
        assertBytes(result.uri);
        assertJournalCleared();
    }

    public void testDeletedPublicResultFallsBackHonestlyToPrivate() throws Exception {
        PublishedVideo result = PublishedVideo.publish(isolated, source, cancelled);
        provider.real.delete(result.uri, null, null);
        PublishedVideo recovered = PublishedVideo.recover(isolated);
        assertNull(recovered.uri);
        assertTrue(recovered.status.contains("unavailable"));
        assertEquals(source, recovered.privateFile);
    }

    public void testFailedReplacementPreservesPreviousResultAndPrivateBinding() throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        // Exercise migration from the original journal, which has no savedPrivate.
        assertTrue(PublishedVideo.prefs(isolated).edit().remove("savedPrivate").commit());
        File replacement = new File(source.getParentFile(), source.getName() + "-next.mp4");
        try {
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(replacement)) {
                byte[] buffer = new byte[8192];
                int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
            }
            provider.cancelAtFinalize = cancelled;
            provider.failDelete = true;
            try {
                PublishedVideo.publish(isolated, replacement, cancelled);
                fail("Cancelled replacement must fail");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("private MP4 retained"));
            }
            assertEquals(first.uri.toString(), PublishedVideo.prefs(isolated).getString("uri", null));
            provider.failDelete = false;
            PublishedVideo recovered = PublishedVideo.recover(isolated);
            assertEquals(first.uri, recovered.uri);
            assertEquals(first.name, recovered.name);
            assertEquals(source, recovered.privateFile);
            assertEquals(replacement.getAbsolutePath(), PublishedVideo.prefs(isolated).getString("private", null));
            assertTrue(replacement.length() > 0);
            assertBytes(first.uri);
            assertGone(provider.created.get(1));
            assertJournalCleared();
        } finally {
            replacement.delete();
        }
    }

    public void testCancelledNewAttemptPreservesRecoveredCommittedRow() throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        assertTrue(PublishedVideo.prefs(isolated).edit().remove("uri")
                .putString("pending", first.uri.toString()).putString("pendingLocation", "Movies").commit());
        cancelled.set(true);
        fails();
        assertEquals(1, provider.created.size());
        assertEquals(first.uri, PublishedVideo.recover(isolated).uri);
        assertBytes(first.uri);
        assertJournalCleared();
    }

    public void testUnavailablePendingStateNeverDeletesCommittedVideo() throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        assertTrue(PublishedVideo.prefs(isolated).edit().remove("uri")
                .putString("pending", first.uri.toString()).putString("pendingLocation", "Movies").commit());
        provider.nullPendingQuery = true;
        try {
            PublishedVideo.recover(isolated);
            fail("Unavailable state must not authorize deletion");
        } catch (IOException expected) {
            assertTrue(PublishedVideo.prefs(isolated).contains("pending"));
        }
        assertBytes(first.uri);
        provider.nullPendingQuery = false;
        assertEquals(first.uri, PublishedVideo.recover(isolated).uri);
        assertJournalCleared();
    }

    public void testCancellationDuringDescriptionRollsBackBeforeSuccessCommit() throws Exception {
        provider.cancelAtDescribe = cancelled;
        fails();
        assertGone(provider.created.get(0));
        assertPrivateRecovery();
        assertJournalCleared();
    }

    public void testFalseFinalCommitRestoresPreviousBindingBeforeCleanup() throws Exception {
        failedFinalCommit(false, false);
    }

    public void testFalseFinalCommitAndDeleteFailureRetainDurableJournal() throws Exception {
        failedFinalCommit(true, false);
    }

    public void testRepeatedFalseCommitsRecoverFromLastDurableJournal() throws Exception {
        failedFinalCommit(true, true);
    }

    private void failedFinalCommit(boolean denyDelete, boolean keepFailing) throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        FaultPublicationPreferences faults = new FaultPublicationPreferences(PublishedVideo.prefs(isolated));
        Context original = isolated;
        isolated = new ContextWrapper(original) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return faults.prefs;
            }
        };
        File replacement = new File(source.getParentFile(), source.getName() + "-commit.mp4");
        try {
            try (FileInputStream input = new FileInputStream(source);
                 FileOutputStream output = new FileOutputStream(replacement)) {
                byte[] bytes = new byte[8192];
                int count;
                while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
            }
            faults.failFinal = true;
            faults.keepFailing = keepFailing;
            provider.failDelete = denyDelete;
            provider.beforeDelete = () -> {
                assertEquals(first.uri.toString(), faults.prefs.getString("uri", null));
                assertEquals(source.getAbsolutePath(), faults.prefs.getString("savedPrivate", null));
                assertTrue(faults.durable.get("rollback").equals(true));
                assertEquals(provider.created.get(1).toString(), faults.durable.get("pending"));
            };
            try {
                PublishedVideo.publish(isolated, replacement, cancelled);
                fail("False commit must fail publication");
            } catch (IOException expected) {
                assertTrue(expected.getMessage().contains("private MP4 retained"));
            }
            assertTrue("Fault must mutate memory before returning false", faults.mutatedOnFailure);
            assertEquals(first.uri.toString(), faults.prefs.getString("uri", null));
            assertEquals(first.name, faults.prefs.getString("name", null));
            assertEquals(first.location, faults.prefs.getString("location", null));
            assertEquals(source.getAbsolutePath(), faults.prefs.getString("savedPrivate", null));
            assertEquals("unrelated", faults.prefs.getString("sentinel", null));
            assertBytes(first.uri);
            if (denyDelete) {
                assertEquals(provider.created.get(1).toString(), faults.durable.get("pending"));
                assertEquals(true, faults.durable.get("rollback"));
                assertEquals(first.uri.toString(), faults.durable.get("uri"));
            }
            faults.restart();
            provider.failDelete = false;
            provider.beforeDelete = null;
            PublishedVideo recovered = PublishedVideo.recover(isolated);
            assertEquals(first.uri, recovered.uri);
            assertEquals(source, recovered.privateFile);
            assertGone(provider.created.get(1));
            assertJournalCleared();
            assertTrue(replacement.length() > 0);
        } finally {
            isolated = original;
            replacement.delete();
        }
    }

    public void testFalseRecoveryCommitKeepsPreviousBindingAndPendingRecord() throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        PublishedVideo next = PublishedVideo.publish(isolated, source, cancelled);
        SharedPreferences prefs = PublishedVideo.prefs(isolated);
        assertTrue(prefs.edit().putString("uri", first.uri.toString()).putString("name", first.name)
                .putString("pending", next.uri.toString()).putString("pendingLocation", "Movies").commit());
        FaultPublicationPreferences faults = new FaultPublicationPreferences(prefs);
        Context context = new ContextWrapper(isolated) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return faults.prefs;
            }
        };
        faults.failFinal = true;
        try {
            PublishedVideo.recover(context);
            fail("False recovery commit must fail");
        } catch (IOException expected) {
            assertEquals(first.uri.toString(), faults.prefs.getString("uri", null));
            assertEquals(next.uri.toString(), faults.prefs.getString("pending", null));
        }
        faults.restart();
        assertEquals(next.uri, PublishedVideo.recover(context).uri);
        assertBytes(first.uri);
        assertBytes(next.uri);
    }

    public void testRealAndroidDiskCommitFailureKeepsPreviousAndRestartCleanup() throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        SharedPreferences prefs = PublishedVideo.prefs(isolated);
        String namespace = source.getName().substring(0, source.getName().length() - 4);
        File xml = new File(getContext().getApplicationInfo().dataDir,
                "shared_prefs/" + namespace + PublishedVideo.PREFS + ".xml");
        File backup = new File(xml.getPath() + ".bak");
        File blocker = new File(xml, "block-write");
        String reloadedName = namespace + "-reloaded";
        File reloaded = new File(xml.getParentFile(), reloadedName + ".xml");
        provider.finalized = false;
        provider.beforeDescribe = () -> {
            provider.beforeDescribe = null;
            assertTrue(xml.renameTo(backup));
            assertTrue(xml.mkdir());
            try {
                assertTrue(blocker.createNewFile());
            } catch (IOException error) {
                throw new AssertionError(error);
            }
        };
        try {
            fails();
            assertTrue("Real failed commit keeps a backup", backup.isFile());
            assertEquals(first.uri.toString(), prefs.getString("uri", null));
            assertEquals(first.name, prefs.getString("name", null));
            assertTrue(prefs.contains("pending"));
            assertTrue(prefs.getBoolean("rollback", false));
            // Load the actual last durable XML through a fresh Android preferences instance.
            try (FileInputStream input = new FileInputStream(backup);
                 FileOutputStream output = new FileOutputStream(reloaded)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                output.getFD().sync();
            }
            Context fresh = new ContextWrapper(isolated) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return getContext().getSharedPreferences(reloadedName, mode);
                }
            };
            assertEquals(first.uri.toString(), PublishedVideo.prefs(fresh).getString("uri", null));
            assertTrue(PublishedVideo.prefs(fresh).getBoolean("rollback", false));
            assertEquals(first.uri, PublishedVideo.recover(fresh).uri);
            assertGone(provider.created.get(1));
            assertBytes(first.uri);
        } finally {
            provider.beforeDescribe = null;
            blocker.delete();
            if (xml.isDirectory()) xml.delete();
            if (backup.exists()) assertTrue(backup.renameTo(xml));
            getContext().getSharedPreferences(reloadedName, 0).edit().clear().commit();
            reloaded.delete();
        }
    }

    public void testFalsePendingCommitRecoversDurableReservationAfterRepeatedDiskFailure() throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        FaultPublicationPreferences faults = new FaultPublicationPreferences(PublishedVideo.prefs(isolated));
        Context context = new ContextWrapper(isolated) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return faults.prefs;
            }
        };
        faults.failAt = 3;
        faults.keepFailing = true;
        try {
            PublishedVideo.publish(context, source, cancelled);
            fail("Pending journal failure must fail publication");
        } catch (IOException expected) {
            assertEquals(first.uri.toString(), faults.prefs.getString("uri", null));
            assertFalse(faults.prefs.contains("pending"));
            assertTrue(faults.prefs.contains("reserved"));
            assertEquals(true, faults.durable.get("rollback"));
        }
        faults.restart();
        assertEquals(first.uri, PublishedVideo.recover(context).uri);
        assertGone(provider.created.get(1));
        assertBytes(first.uri);
        assertFalse(faults.prefs.contains("reserved"));
    }

    public void testFalseJournalClearRestoresRetryAfterConfirmedDeletion() throws Exception {
        PublishedVideo first = PublishedVideo.publish(isolated, source, cancelled);
        Uri pending = provider.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                pending("edited_" + UUID.randomUUID() + ".mp4"));
        assertTrue(PublishedVideo.prefs(isolated).edit().putString("pending", pending.toString())
                .putBoolean("rollback", true).commit());
        FaultPublicationPreferences faults = new FaultPublicationPreferences(PublishedVideo.prefs(isolated));
        Context context = new ContextWrapper(isolated) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return faults.prefs;
            }
        };
        faults.failAt = 1;
        try {
            PublishedVideo.recover(context);
            fail("False journal clear must fail recovery");
        } catch (IOException expected) {
            assertEquals(pending.toString(), faults.prefs.getString("pending", null));
            assertEquals(first.uri.toString(), faults.prefs.getString("uri", null));
        }
        assertGone(pending);
        faults.restart();
        assertEquals(first.uri, PublishedVideo.recover(context).uri);
        assertFalse(faults.prefs.contains("pending"));
        assertBytes(first.uri);
    }

    private void fails() throws Exception {
        try {
            PublishedVideo.publish(isolated, source, cancelled);
            fail("Publication must not report success");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("private MP4 retained"));
        }
    }

    private void assertRecoveryCleanupBlocked() throws Exception {
        try {
            PublishedVideo.recover(isolated);
            fail("Failed cleanup must block recovery, never adopt a rolled-back row");
        } catch (SecurityException expected) {
            assertTrue(PublishedVideo.prefs(isolated).contains("pending"));
            assertTrue(PublishedVideo.prefs(isolated).getBoolean("rollback", false));
            assertFalse(PublishedVideo.prefs(isolated).contains("uri"));
        }
    }

    private void assertJournalCleared() {
        SharedPreferences prefs = PublishedVideo.prefs(isolated);
        assertFalse(prefs.contains("pending"));
        assertFalse(prefs.contains("pendingLocation"));
        assertFalse(prefs.contains("reserved"));
        assertFalse(prefs.contains("reservedTree"));
        assertFalse(prefs.contains("rollback"));
    }

    private void assertPrivateRecovery() throws Exception {
        PublishedVideo result = PublishedVideo.recover(isolated);
        assertNotNull(result);
        assertNull(result.uri);
        assertEquals(source, result.privateFile);
        assertTrue(source.length() > 0);
    }

    private void assertGone(Uri uri) {
        try (Cursor cursor = provider.real.query(uri, new String[]{"_id"}, null, null, null)) {
            assertNotNull(cursor);
            assertFalse(cursor.moveToFirst());
        }
    }

    private void assertBytes(Uri uri) throws Exception {
        assertTrue(java.util.Arrays.equals(digest(new FileInputStream(source)),
                digest(provider.real.openInputStream(uri))));
    }

    private static byte[] digest(InputStream input) throws Exception {
        try (InputStream stream = input) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int n;
            while ((n = stream.read(buffer)) != -1) digest.update(buffer, 0, n);
            return digest.digest();
        }
    }

    private static ContentValues pending(String name) {
        ContentValues values = new ContentValues();
        values.put("_display_name", name);
        values.put("mime_type", "video/mp4");
        values.put("relative_path", "Movies");
        values.put("is_pending", 1);
        return values;
    }

    private static final class Forwarder extends ContentProvider {
        final ContentResolver real;
        final List<Uri> created = new ArrayList<>();
        boolean failOpen, failFinalize, failDelete, failDescribe, finalized, nullPendingQuery;
        AtomicBoolean cancelAtOpen, cancelAtFinalize, cancelAtDescribe;
        Runnable beforeDelete, beforeDescribe;
        int pendingAtOpen;
        Forwarder(ContentResolver real) { this.real = real; }
        @Override public boolean onCreate() { return true; }
        @Override public String getType(Uri uri) { return real.getType(uri); }
        @Override public Cursor query(Uri uri, String[] columns, String where, String[] args, String order) {
            if (nullPendingQuery && "is_pending".equals(columns[0])) return null;
            if (cancelAtDescribe != null && finalized && "_display_name".equals(columns[0])) {
                cancelAtDescribe.set(true);
            }
            if (failDescribe && finalized && "_display_name".equals(columns[0])) {
                throw new IllegalStateException("injected finalized description failure");
            }
            if (beforeDescribe != null && finalized && "_display_name".equals(columns[0])) {
                beforeDescribe.run();
            }
            return real.query(uri, columns, where, args, order);
        }
        @Override public Uri insert(Uri uri, ContentValues values) {
            Uri result = real.insert(uri, values);
            created.add(result);
            return result;
        }
        @Override public int update(Uri uri, ContentValues values, String where, String[] args) {
            if (failFinalize) return 0;
            int count = real.update(uri, values, where, args);
            finalized = count == 1;
            if (cancelAtFinalize != null) cancelAtFinalize.set(true);
            return count;
        }
        @Override public int delete(Uri uri, String where, String[] args) {
            if (beforeDelete != null) beforeDelete.run();
            if (failDelete) throw new SecurityException("injected cleanup denial");
            return real.delete(uri, where, args);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
            if (mode.contains("w")) {
                try (Cursor cursor = real.query(uri, new String[]{"is_pending"}, null, null, null)) {
                    assertTrue(cursor.moveToFirst());
                    assertEquals(1, cursor.getInt(0));
                    pendingAtOpen++;
                }
                if (failOpen) throw new FileNotFoundException("injected stream failure");
                if (cancelAtOpen != null) cancelAtOpen.set(true);
            }
            return real.openFileDescriptor(uri, mode);
        }
    }
}
