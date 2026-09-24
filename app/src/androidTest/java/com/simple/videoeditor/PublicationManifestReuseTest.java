package com.simple.videoeditor;

import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.net.Uri;
import android.provider.MediaStore;
import android.test.AndroidTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
public final class PublicationManifestReuseTest extends AndroidTestCase {
    private Context context;
    private PublicationDraftStore store;
    private final List<Uri> owned = new ArrayList<>();

    @Override protected void setUp() throws Exception {
        super.setUp();
        File root = new File(getContext().getFilesDir(), "hash-reuse-test-" + UUID.randomUUID());
        assertTrue(root.mkdirs());
        context = new ContextWrapper(getContext()) { @Override public File getFilesDir() { return root; } };
        store = new PublicationDraftStore(context);
    }

    @Override protected void tearDown() throws Exception {
        for (Uri uri : owned) getContext().getContentResolver().delete(uri, null, null);
        remove(context.getFilesDir());
        super.tearDown();
    }

    private void remove(File file) {
        if (file.isDirectory()) for (File child : file.listFiles()) remove(child);
        assertTrue(file.delete());
    }

    private Uri video(String name, boolean changed) throws Exception {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContext().getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        assertNotNull(uri); owned.add(uri);
        try (InputStream input = new FileInputStream(new OracleVerifier(context).prepareFixture());
             OutputStream output = getContext().getContentResolver().openOutputStream(uri)) {
            byte[] buffer = new byte[65536]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            if (changed) output.write(new byte[]{0, 0, 0, 8, 'f', 'r', 'e', 'e'});
        }
        ContentValues finished = new ContentValues(); finished.put(MediaStore.MediaColumns.IS_PENDING, 0);
        getContext().getContentResolver().update(uri, finished, null, null);
        return uri;
    }

    private PublicationDraftMedia.VerifiedVideo verify(Uri uri) throws Exception {
        return PublicationDraftMedia.verify(getContext(), uri, new PublicationDraftMedia.Reads());
    }

    private PublicationDraftStore.Resolution resolve(PublicationDraftMedia.VerifiedVideo video) throws Exception {
        return store.resolve(video, null, "", null, null, false, UUID.randomUUID().toString());
    }

    public void testRenamedCopyReusesAllMetadataAndRecoversDeletedUri() throws Exception {
        Uri first = video("original.mp4", false), copy = video("renamed.mp4", false);
        JSONObject draft = resolve(verify(first)).value;
        draft.put("title", "用户标题🎬").put("description", "Do not replace\n第二行")
                .put("tags", new JSONArray().put("tag")).put("privacy", "unlisted")
                .put("audience", "not-made-for-kids").put("publicationLicense", "creative-commons-attribution")
                .put("coverUri", "content://retained/cover").put("musicCredits", "original immutable evidence");
        store.save(draft);
        getContext().getContentResolver().delete(first, null, null);
        owned.remove(first);
        try { PublicationDraftMedia.share(getContext(), draft, false); fail(); }
        catch (IOException | SecurityException expected) {}
        JSONObject reused = resolve(verify(copy)).value;
        assertEquals(draft.getString("id"), reused.getString("id"));
        assertEquals(PublicationDraftMedia.name(context, copy), reused.getString("mediaName"));
        for (String key : PublicationDraftStore.EDITABLE) assertEquals(draft.get(key).toString(), reused.get(key).toString());
        assertEquals(draft.getString("coverUri"), reused.getString("coverUri"));
        assertEquals(draft.getString("musicCredits"), reused.getString("musicCredits"));
        JSONObject reloaded = new PublicationDraftStore(context).load(reused.getString("id"));
        assertEquals(copy, PublicationDraftMedia.share(getContext(), reloaded, false).getParcelableExtra(Intent.EXTRA_STREAM));
        assertEquals(1, store.list().size());
        try { store.saveMetadata(draft, "stale"); fail(); } catch (IOException expected) {}
        reloaded.put("description", "Further edit");
        store.saveMetadata(reloaded, "next");
    }

    public void testDifferentHashSameNameCreatesFreshAndCannotClobberBinding() throws Exception {
        PublicationDraftMedia.VerifiedVideo one = verify(video("same.mp4", false));
        PublicationDraftMedia.VerifiedVideo two = verify(video("same.mp4", true));
        JSONObject original = resolve(one).value;
        original.put("title", "Keep me").put("musicCredits", "First export evidence"); store.save(original);
        String before = store.load(original.getString("id")).toString();
        assertFalse(one.fingerprint.sha.equals(two.fingerprint.sha));
        try { store.resolve(two, original, "", null, null, false, "mismatch"); fail(); }
        catch (IOException expected) {}
        JSONObject other = resolve(two).value;
        assertFalse(original.getString("id").equals(other.getString("id")));
        assertEquals(before, store.load(original.getString("id")).toString());
        assertFalse(other.getString("musicCredits").equals(original.getString("musicCredits")));
        assertEquals(2, store.list().size());
    }

    public void testAtomicConcurrentFindCreateBindUsesOneId() throws Exception {
        PublicationDraftMedia.VerifiedVideo a = verify(video("one.mp4", false));
        PublicationDraftMedia.VerifiedVideo b = verify(video("two.mp4", false));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<JSONObject> first = pool.submit(() -> {
                start.await();
                return new PublicationDraftStore(context).resolve(a, null, "", null, null, false, "first").value;
            });
            Future<JSONObject> second = pool.submit(() -> {
                start.await();
                return new PublicationDraftStore(context).resolve(b, null, "", null, null, false, "second").value;
            });
            start.countDown();
            assertEquals(first.get(20, TimeUnit.SECONDS).getString("id"), second.get(20, TimeUnit.SECONDS).getString("id"));
            assertEquals(1, store.list().size());
        } finally { pool.shutdownNow(); }
    }

    public void testDuplicatesRequireChoiceAndStaleChoiceFailsWithoutLosingText() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("video.mp4", false));
        JSONObject one = resolve(bytes).value;
        JSONObject two = bytes.bind(PublicationDraftStore.create("Intentional alternate")).put("description", "Other text");
        store.save(two);
        String beforeOne = store.load(one.getString("id")).toString(), beforeTwo = store.load(two.getString("id")).toString();
        PublicationDraftStore.Resolution choice = resolve(bytes);
        assertNull(choice.value); assertEquals(2, choice.choices.size());
        assertEquals(beforeOne, store.load(one.getString("id")).toString());
        assertEquals(beforeTwo, store.load(two.getString("id")).toString());
        JSONObject edited = new JSONObject(two.toString()).put("description", "New concurrent text");
        store.save(edited);
        try { store.resolve(bytes, null, "", null, two, false, "stale"); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
        assertEquals(one.getString("id"), store.resolve(bytes, null, "", null, one, false, "choose").value.getString("id"));
        assertEquals("New concurrent text", store.load(two.getString("id")).getString("description"));
    }

    public void testImportedClaimsCannotGrantAccessOrSilentlyReplaceExisting() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("actual.mp4", false));
        JSONObject original = resolve(bytes).value;
        JSONObject portable = PublicationDraftStore.portable(original).put("title", "Imported alternate")
                .put("videoUri", "content://arbitrary/private").put("mediaVerified", true).put("id", original.getString("id"));
        JSONObject imported = PublicationDraftStore.importManifest(new ByteArrayInputStream(PublicationDraftStore.encode(portable)));
        assertFalse(imported.getBoolean("mediaVerified")); assertEquals("", imported.getString("videoUri"));
        store.save(imported);
        assertEquals(2, resolve(bytes).choices.size());
        JSONObject chosen = store.resolve(bytes, imported, "", null, imported, false, "explicit").value;
        assertEquals(bytes.uri.toString(), chosen.getString("videoUri"));
        assertEquals("Imported alternate", chosen.getString("title"));
        assertEquals(original.toString(), store.load(original.getString("id")).toString());
        portable.put("mediaSha256", String.format(Locale.ROOT, "%064d", 1));
        JSONObject fake = PublicationDraftStore.importManifest(new ByteArrayInputStream(PublicationDraftStore.encode(portable)));
        store.save(fake);
        try { store.resolve(bytes, fake, "", null, null, false, "forged"); fail(); } catch (IOException expected) {}
        assertEquals("", store.load(fake.getString("id")).getString("videoUri"));
    }

    public void testExportCreditsConflictIsExplicitEvidenceNeverMerged() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("export.mp4", false));
        JSONObject one = store.resolve(bytes, null, bytes.uri.toString(), "recipe one", null, false, "one").value;
        PublicationDraftStore.Resolution conflict = store.resolve(bytes, null, bytes.uri.toString(), "recipe two", null, false, "two");
        assertNull(conflict.value); assertTrue(conflict.freshAllowed); assertEquals(1, conflict.choices.size());
        JSONObject separate = store.resolve(bytes, null, bytes.uri.toString(), "recipe two", null, true, "keep-both").value;
        assertFalse(one.getString("id").equals(separate.getString("id")));
        assertEquals("recipe one", store.load(one.getString("id")).getString("musicCredits"));
        assertEquals("recipe two", separate.getString("musicCredits"));
    }

    public void testLegacyUnhashedExportIsAnExplicitAlternateNotDiscarded() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("old-export.mp4", false));
        JSONObject legacy = store.forExport(bytes.uri.toString(), bytes.name, "old evidence");
        legacy.put("title", "Already edited before hashing"); store.save(legacy);
        JSONObject hashed = bytes.bind(PublicationDraftStore.create("Other manifest"));
        store.save(hashed);
        PublicationDraftStore.Resolution choice = store.resolve(bytes, null, bytes.uri.toString(),
                "old evidence", null, false, "lookup");
        assertNull(choice.value); assertEquals(2, choice.choices.size());
        JSONObject selected = store.resolve(bytes, null, bytes.uri.toString(),
                "old evidence", legacy, false, "selected").value;
        assertEquals(legacy.getString("id"), selected.getString("id"));
        assertEquals("Already edited before hashing", selected.getString("title"));
        assertEquals(hashed.toString(), store.load(hashed.getString("id")).toString());
    }

    public void testLegacyHashBackupAndPartialNewRecoveryWithoutVideoScan() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("legacy.mp4", false));
        JSONObject legacy = resolve(bytes).value;
        legacy.remove("mediaVerified");
        File dir = new File(context.getFilesDir(), "publication-drafts");
        File base = new File(dir, legacy.getString("id") + ".json");
        try (OutputStream out = new FileOutputStream(base)) { out.write(PublicationDraftStore.encode(legacy)); }
        assertTrue(base.renameTo(new File(base.getPath() + ".bak")));
        try (OutputStream out = new FileOutputStream(base)) { out.write("{partial".getBytes("UTF-8")); }
        try (OutputStream out = new FileOutputStream(new File(base.getPath() + ".new"))) { out.write("{partial".getBytes("UTF-8")); }
        File corrupt = new File(dir, UUID.randomUUID() + ".json");
        try (OutputStream out = new FileOutputStream(corrupt)) { out.write("{broken".getBytes("UTF-8")); }
        JSONObject recovered = resolve(bytes).value;
        assertEquals(legacy.getString("id"), recovered.getString("id"));
        assertTrue(recovered.getBoolean("mediaVerified"));
        assertTrue(corrupt.exists());
    }

    public void testCancelAndAtomicFailureCreateNoHalfDraft() throws Exception {
        Uri uri = video("cancel.mp4", false);
        PublicationDraftMedia.Reads reads = new PublicationDraftMedia.Reads();
        reads.progress = ignored -> reads.cancel();
        try { PublicationDraftMedia.verify(context, uri, reads); fail(); } catch (IOException expected) {}
        assertEquals(0, store.list().size());
        PublicationDraftMedia.VerifiedVideo bytes = verify(uri);
        PublicationDraftStore failing = new PublicationDraftStore(context, () -> { throw new IOException("Injected fsync/commit failure"); });
        try { failing.resolve(bytes, null, "", null, null, false, "failed"); fail(); } catch (IOException expected) {}
        assertEquals(0, store.list().size());
    }

    public void testMutationAfterFingerprintAndPendingPublicationRejected() throws Exception {
        Uri uri = video("mutable.mp4", false);
        PublicationDraftMedia.VerifiedVideo bytes = verify(uri);
        try (OutputStream out = context.getContentResolver().openOutputStream(uri, "wa")) { out.write(1); }
        try { bytes.checkCurrent(context, new PublicationDraftMedia.Reads()); fail(); } catch (IOException expected) {}
        ContentValues pending = new ContentValues(); pending.put(MediaStore.MediaColumns.IS_PENDING, 1);
        context.getContentResolver().update(uri, pending, null, null);
        try { verify(uri); fail(); } catch (IOException expected) {}
        assertEquals(0, store.list().size());
    }

    public void testMutationDuringFingerprintRejectsWithoutCreatingDraft() throws Exception {
        Uri uri = video("mutating.mp4", false);
        PublicationDraftMedia.Reads reads = new PublicationDraftMedia.Reads();
        java.util.concurrent.atomic.AtomicBoolean changed = new java.util.concurrent.atomic.AtomicBoolean();
        reads.progress = ignored -> {
            if (changed.getAndSet(true)) return;
            try (OutputStream output = context.getContentResolver().openOutputStream(uri, "wa")) {
                output.write(new byte[65536]);
            } catch (IOException error) { throw new RuntimeException(error); }
        };
        try { PublicationDraftMedia.verify(context, uri, reads); fail(); } catch (IOException expected) {}
        assertTrue(changed.get());
        assertEquals(0, store.list().size());
    }

    public void testCrossDraftCallbackReconcileDoesNotBorrowForeignRevision() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("existing.mp4", false));
        JSONObject target = resolve(bytes).value;
        JSONObject blank = PublicationDraftStore.create("Untouched source editor"); store.save(blank);
        PublicationDraftMedia.VerifiedVideo copy = verify(video("copy.mp4", false));
        JSONObject reused = store.resolve(copy, blank, "", null, null, false, "cross-draft").value;
        assertEquals(target.getString("id"), store.reconcile(blank, "cross-draft").getString("id"));
        assertEquals(blank.toString(), store.load(blank.getString("id")).toString());
        try { store.saveMetadata(target, "stale-ui"); fail(); } catch (IOException expected) {}
        reused.put("title", "New edit"); store.saveMetadata(reused, "edit");
        JSONObject secondBlank = PublicationDraftStore.create("Another source"); store.save(secondBlank);
        JSONObject unchangedBinding = store.resolve(copy, secondBlank, "", null, null, false, "same-uri-transition").value;
        assertEquals(unchangedBinding.getString("id"), store.reconcile(secondBlank, "same-uri-transition").getString("id"));
    }

    public void testPendingSwitchAfterOtherEditorSaveNeverReturnsSource() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("target.mp4", false));
        JSONObject target = resolve(bytes).value;
        JSONObject source = PublicationDraftStore.create("Source A"); store.save(source);
        String operation = UUID.randomUUID().toString();
        JSONObject switched = store.resolve(bytes, source, "", null, target, false, operation, source).value;
        assertEquals("A receipt-only switch must not invalidate another editor", target.toString(), switched.toString());
        switched.put("description", "B saved by another editor");
        new PublicationDraftStore(context).save(switched);
        try { new PublicationDraftStore(context).reconcile(source, operation); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("target changed")); }
        assertEquals(source.toString(), store.load(source.getString("id")).toString());
    }

    public void testNoOpNullBasePendingSwitchNeverReturnsSource() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("no-op.mp4", false));
        JSONObject target = resolve(bytes).value;
        JSONObject source = PublicationDraftStore.create("Source A"); store.save(source);
        String operation = UUID.randomUUID().toString();
        store.resolve(bytes, null, "", null, target, false, operation, source);
        JSONObject restored = new PublicationDraftStore(context).reconcile(source, operation);
        assertEquals("A current source snapshot must authenticate the switch from A",
                target.getString("id"), restored.getString("id"));
        try { new PublicationDraftStore(context).reconcile(null, operation); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("source mismatch")); }
        String nullSourceOperation = UUID.randomUUID().toString();
        store.resolve(bytes, null, "", null, target, false, nullSourceOperation, null);
        assertEquals(target.getString("id"), new PublicationDraftStore(context).reconcile(null, nullSourceOperation).getString("id"));
        try { new PublicationDraftStore(context).reconcile(source, nullSourceOperation); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("source mismatch")); }
    }

    public void testSourceRevisionAndReceiptIdentityCannotBeReassigned() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("receipt-source.mp4", false));
        JSONObject target = resolve(bytes).value;
        JSONObject source = PublicationDraftStore.create("A"); store.save(source);
        JSONObject base = new JSONObject(source.toString());
        String operation = UUID.randomUUID().toString();
        store.resolve(bytes, null, "", null, target, false, operation, base);
        try { store.resolve(bytes, null, "", null, target, false, operation, base); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("already used")); }
        source.put("description", "Another editor changed A"); store.save(source);
        try { new PublicationDraftStore(context).reconcile(base, operation); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
        try { store.resolve(bytes, null, "", null, target, false, UUID.randomUUID().toString(), base); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
        assertEquals(target.toString(), store.load(target.getString("id")).toString());
    }

    public void testSwitchFaultBoundariesNeverRestoreSourceOrOverwriteLaterTarget() throws Exception {
        PublicationDraftMedia.VerifiedVideo initial = verify(video("fault-original.mp4", false));
        PublicationDraftMedia.VerifiedVideo copy = verify(video("fault-copy.mp4", false));
        for (String stage : new String[]{PublicationDraftStore.FAULT_BEFORE_RECEIPT_COMMIT,
                PublicationDraftStore.FAULT_AFTER_RECEIPT_COMMIT, PublicationDraftStore.FAULT_BEFORE_TARGET_COMMIT,
                PublicationDraftStore.FAULT_AFTER_TARGET_COMMIT}) {
            JSONObject source = PublicationDraftStore.create("Source A"); store.save(source);
            JSONObject target = initial.bind(PublicationDraftStore.create("Target B"))
                    .put("description", "B metadata retained");
            store.save(target);
            String before = target.toString(), operation = UUID.randomUUID().toString();
            IOException failure = new IOException(stage);
            PublicationDraftStore failing = new PublicationDraftStore(context, fault -> {
                if (stage.equals(fault)) throw failure;
            });
            try { failing.resolve(copy, source, "", null, target, false, operation); fail(); }
            catch (IOException expected) { assertSame(failure, expected); }
            JSONObject durable = new PublicationDraftStore(context).load(target.getString("id"));
            if (stage.equals(PublicationDraftStore.FAULT_AFTER_TARGET_COMMIT)) {
                assertEquals(copy.uri.toString(), durable.getString("videoUri"));
                assertEquals(durable.toString(), new PublicationDraftStore(context).reconcile(source, operation).toString());
            } else {
                assertEquals(before, durable.toString());
                try { new PublicationDraftStore(context).reconcile(source, operation); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("did not finish")); }
            }
            durable.put("description", "Another editor owns this revision"); store.save(durable);
            try { new PublicationDraftStore(context).reconcile(source, operation); fail(); }
            catch (IOException expected) {}
            assertEquals(durable.toString(), store.load(target.getString("id")).toString());
            assertEquals(source.toString(), store.load(source.getString("id")).toString());
        }
    }

    public void testNoOpReceiptBoundaryAndDeletedSwitchTargetFailClosed() throws Exception {
        PublicationDraftMedia.VerifiedVideo bytes = verify(video("no-op-fault.mp4", false));
        JSONObject target = resolve(bytes).value;
        JSONObject source = PublicationDraftStore.create("Source A"); store.save(source);
        for (String stage : new String[]{PublicationDraftStore.FAULT_BEFORE_RECEIPT_COMMIT,
                PublicationDraftStore.FAULT_AFTER_RECEIPT_COMMIT}) {
            String operation = UUID.randomUUID().toString();
            IOException failure = new IOException(stage);
            PublicationDraftStore failing = new PublicationDraftStore(context, fault -> {
                assertFalse(fault.contains("target"));
                if (stage.equals(fault)) throw failure;
            });
            try { failing.resolve(bytes, null, "", null, target, false, operation, source); fail(); }
            catch (IOException expected) { assertSame(failure, expected); }
            if (stage.equals(PublicationDraftStore.FAULT_AFTER_RECEIPT_COMMIT))
                assertEquals(target.toString(), new PublicationDraftStore(context).reconcile(source, operation).toString());
            else {
                try { new PublicationDraftStore(context).reconcile(source, operation); fail(); }
                catch (IOException expected) { assertTrue(expected.getMessage().contains("did not finish")); }
            }
            assertEquals(target.toString(), store.load(target.getString("id")).toString());
        }
        String operation = UUID.randomUUID().toString();
        store.resolve(bytes, null, "", null, target, false, operation, source);
        store.delete(target.getString("id"));
        try { new PublicationDraftStore(context).reconcile(source, operation); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
        assertEquals(source.toString(), store.load(source.getString("id")).toString());
    }

    public void testFourGiBStreamingCountWith64KiBBuffersNoDiskAllocation() throws Exception {
        final long size = (1L << 32) + 13;
        InputStream generated = new InputStream() {
            long remaining = size;
            @Override public int read() { throw new AssertionError("Block reads required"); }
            @Override public int read(byte[] data, int offset, int length) {
                assertTrue(length <= 65536);
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                Arrays.fill(data, offset, offset + count, (byte) 7);
                remaining -= count; return count;
            }
        };
        assertEquals(size, PublicationDraftMedia.hash(generated).bytes);
    }
}
