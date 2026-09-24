package com.simple.videoeditor;

import android.content.Context;
import android.content.ContextWrapper;
import android.test.AndroidTestCase;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

public final class PublicationDraftStoreTest extends AndroidTestCase {
    private Context context;
    private PublicationDraftStore store;

    @Override protected void setUp() throws Exception {
        super.setUp();
        File root = new File(getContext().getFilesDir(), "draft-test-" + java.util.UUID.randomUUID());
        assertTrue(root.mkdirs());
        context = new ContextWrapper(getContext()) { @Override public File getFilesDir() { return root; } };
        store = new PublicationDraftStore(context);
    }

    @Override protected void tearDown() throws Exception {
        remove(context.getFilesDir());
        super.tearDown();
    }

    private void remove(File file) {
        if (file.isDirectory()) for (File child : file.listFiles()) remove(child);
        assertTrue(file.delete());
    }

    public void testUnicodeRoundTripPortableUnboundAndSafeDelete() throws Exception {
        JSONObject draft = PublicationDraftStore.create("旅行🎬.mp4")
                .put("description", "第一行\nsecond 🎵").put("tags", new JSONArray().put("中文").put("🎬\n多行"))
                .put("videoUri", "content://media/external/video/media/1")
                .put("coverUri", "content://images/1").put("musicCredits", "CC0\n作者");
        store.save(draft);
        JSONObject saved = store.load(draft.getString("id"));
        assertEquals(draft.toString(), saved.toString());
        JSONObject manifest = PublicationDraftStore.portable(saved);
        assertFalse(manifest.has("id"));
        assertFalse(manifest.has("videoUri"));
        assertFalse(manifest.has("coverUri"));
        manifest.put("videoUri", "file:///data/private/secret").put("coverUri", "content://untrusted/1")
                .put("access_token", "ignore").put("id", "../../bad").put("status", "uploaded");
        JSONObject imported = parse(manifest.toString());
        assertFalse(imported.has("access_token"));
        assertFalse(saved.getString("id").equals(imported.getString("id")));
        assertEquals("", imported.getString("videoUri"));
        assertEquals("", imported.getString("coverUri"));
        assertEquals("draft", imported.getString("status"));
        store.save(imported);
        store.delete(saved.getString("id"));
        assertEquals(1, store.list().size());
        assertEquals("第一行\nsecond 🎵", store.load(imported.getString("id")).getString("description"));
    }

    public void testStrictJsonTypesBoundsAndNoTrailingGarbage() throws Exception {
        JSONObject value = PublicationDraftStore.portable(PublicationDraftStore.create("ok"));
        String valid = value.toString();
        for (String invalid : new String[]{valid + "{}", valid + " garbage", valid.replace("\"version\":1", "\"version\":\"1\""),
                valid.replace("\"version\":1", "\"version\":1.0"), valid.replace("\"version\":1", "\"version\":2"),
                valid.replace("\"version\":1", "\"version\":1,\"version\":1"), valid.replace("\"schema\"", "'schema'"),
                "{/*comment*/" + valid.substring(1), valid.replace("\"mediaBytes\":0", "\"mediaBytes\":-1"),
                valid.substring(0, valid.length() - 1) + ",\"extra\":[[[[[[[[[]]]]]]]]]}",
                valid.replace("\"title\":\"ok\"", "\"title\":false"),
                valid.replace("\"title\":\"ok\"", "\"title\":\"raw\nnewline\""),
                valid.replace("\"title\":\"ok\"", "\"title\":\"\\ud800\"")}) {
            try { parse(invalid); fail(invalid); } catch (IOException expected) {}
        }
        try { PublicationDraftStore.importManifest(new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28})); fail(); }
        catch (IOException expected) {}
        try { PublicationDraftStore.importManifest(new ByteArrayInputStream(new byte[PublicationDraftStore.MAX_BYTES + 1])); fail(); }
        catch (IOException expected) {}
        JSONObject local = PublicationDraftStore.create("ok").put("auth", "secret");
        try { store.save(local); fail(); } catch (IOException expected) {}
        local.remove("auth");
        local.put("description", new String(new char[5001]).replace('\0', 'a'));
        try { store.save(local); fail(); } catch (IOException expected) {}
    }

    public void testAtomicFailureStaleWritesWrongIdAndDeletedDraft() throws Exception {
        JSONObject value = PublicationDraftStore.create("original");
        store.save(value);
        JSONObject stale = new JSONObject(value.toString());
        value.put("title", "failed");
        PublicationDraftStore failing = new PublicationDraftStore(context, () -> { throw new IOException("disk failure"); });
        try { failing.save(value); fail(); } catch (IOException expected) {}
        assertEquals("original", store.load(value.getString("id")).getString("title"));
        value.put("title", "new");
        store.save(value);
        try { store.save(stale); fail(); } catch (IOException expected) {}
        assertEquals("new", store.load(value.getString("id")).getString("title"));
        String otherId = java.util.UUID.randomUUID().toString();
        File original = new File(context.getFilesDir(), "publication-drafts/" + value.getString("id") + ".json");
        File wrong = new File(original.getParentFile(), otherId + ".json");
        try (java.io.FileInputStream input = new java.io.FileInputStream(original);
             java.io.FileOutputStream output = new java.io.FileOutputStream(wrong)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
        }
        try { store.load(otherId); fail(); } catch (IOException expected) {}
        assertTrue(wrong.delete());
        store.delete(value.getString("id"));
        try { store.save(value); fail(); } catch (IOException expected) {}
    }

    public void testExportAssociationNeverUsesLaterCredits() throws Exception {
        JSONObject first = store.forExport("content://export/1", "one.mp4", "first mix");
        JSONObject second = store.forExport("content://export/2", "two.mp4", "imported: unknown rights");
        JSONObject reopened = new PublicationDraftStore(context).forExport("content://export/1", "changed", "later mix");
        assertEquals(first.getString("id"), reopened.getString("id"));
        assertEquals("first mix", reopened.getString("musicCredits"));
        assertFalse(first.getString("id").equals(second.getString("id")));
        assertEquals(2, store.list().size());
        assertEquals(100, PublicationDraftStore.create(new String(new char[110]).replace('\0', 'a')).getString("title").length());
    }

    public void testLicenseIntentLegacyMigrationAndAtomicInvalidImport() throws Exception {
        JSONObject draft = PublicationDraftStore.create("license 🎬").put("musicCredits", "CC0 music only");
        store.save(draft);
        assertEquals("unspecified", draft.getString("publicationLicense"));
        for (String license : new String[]{"unspecified", "youtube-standard", "creative-commons-attribution"}) {
            draft.put("publicationLicense", license);
            store.save(draft);
            JSONObject imported = parse(PublicationDraftStore.portable(draft).toString());
            assertEquals(license, imported.getString("publicationLicense"));
            assertEquals("CC0 music only", imported.getString("musicCredits"));
            assertEquals("", imported.getString("videoUri"));
        }
        JSONObject legacy = PublicationDraftStore.portable(draft);
        legacy.remove("publicationLicense");
        assertEquals("unspecified", parse(legacy.toString()).getString("publicationLicense"));
        JSONObject oldLocal = new JSONObject(draft.toString());
        oldLocal.remove("publicationLicense");
        oldLocal.remove("operationId");
        File path = new File(context.getFilesDir(), "publication-drafts/" + draft.getString("id") + ".json");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(path)) {
            output.write(PublicationDraftStore.encode(oldLocal));
        }
        JSONObject migrated = store.load(draft.getString("id"));
        assertEquals("unspecified", migrated.getString("publicationLicense"));
        assertEquals("CC0 music only", migrated.getString("musicCredits"));
        store.save(migrated);
        String original = store.load(draft.getString("id")).toString();
        for (Object invalid : new Object[]{"cc0", "public", "", 1, JSONObject.NULL}) {
            JSONObject manifest = PublicationDraftStore.portable(migrated).put("publicationLicense", invalid);
            try { store.save(parse(manifest.toString())); fail("Invalid license accepted: " + invalid); }
            catch (IOException expected) {}
            assertEquals(original, store.load(draft.getString("id")).toString());
            assertEquals(1, store.list().size());
        }
        JSONObject portable = PublicationDraftStore.portable(draft)
                .put("operationId", "forged-portable-op")
                .put("internalop", "forged-private-token")
                .put("videoUri", "content://forged/private");
        JSONObject imported = PublicationDraftStore.importManifest(new ByteArrayInputStream(PublicationDraftStore.encode(portable)));
        assertFalse(imported.has("internalop"));
        assertEquals("", imported.getString("operationId"));
        assertEquals("", imported.getString("videoUri"));
    }

    public void testRevisionStrictlyIncreasesEvenAheadOfClock() throws Exception {
        JSONObject draft = PublicationDraftStore.create("monotonic");
        store.save(draft);
        draft.put("updatedAt", System.currentTimeMillis() + 1000000);
        File path = new File(context.getFilesDir(), "publication-drafts/" + draft.getString("id") + ".json");
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(path)) {
            output.write(PublicationDraftStore.encode(draft));
        }
        long previous = draft.getLong("updatedAt");
        JSONObject stale = new JSONObject(draft.toString());
        store.save(draft);
        assertEquals(previous + 1, draft.getLong("updatedAt"));
        try { store.save(stale); fail("Same-clock stale write accepted"); }
        catch (IOException expected) {}
        store.save(draft);
        assertEquals(previous + 2, draft.getLong("updatedAt"));
    }

    public void testReconcileOwnCommitAndMetadataCannotReplaceProvenance() throws Exception {
        JSONObject base = PublicationDraftStore.create("base").put("musicCredits", "captured immutable credits")
                .put("videoUri", "content://media/old").put("coverUri", "content://media/cover").put("status", "ready");
        store.save(base);
        String operation = java.util.UUID.randomUUID().toString();
        JSONObject worker = new JSONObject(base.toString()).put("videoUri", "content://media/rebound")
                .put("musicCredits", "worker verified provenance");
        store.save(worker, operation);
        JSONObject current = new PublicationDraftStore(context).reconcile(base, operation);
        assertEquals(worker.toString(), current.toString());
        current.put("title", "unsaved overlay").put("musicCredits", "forged stale UI credits")
                .put("videoUri", "content://untrusted/video").put("coverUri", "content://untrusted/cover");
        JSONObject saved = store.saveMetadata(current, java.util.UUID.randomUUID().toString());
        assertEquals("unsaved overlay", saved.getString("title"));
        assertEquals("worker verified provenance", saved.getString("musicCredits"));
        assertEquals("content://media/rebound", saved.getString("videoUri"));
        assertEquals("content://media/cover", saved.getString("coverUri"));
        assertEquals("ready", saved.getString("status"));
        assertFalse(PublicationDraftStore.portable(saved).has("operationId"));
        try { store.reconcile(base, operation); fail("Later writer was silently accepted"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
        try { store.saveMetadata(current, operation); fail("Stale form overwrote a later revision"); }
        catch (IOException expected) {}
    }

    public void testMissingOrUnknownPendingReceiptFailsClosed() throws Exception {
        JSONObject source = PublicationDraftStore.create("source");
        store.save(source);
        JSONObject target = PublicationDraftStore.create("target");
        store.save(target);
        String operation = "switch-no-receipt";
        target.put("title", "pending target");
        store.save(target, operation);
        assertTrue(receipt(operation).delete());
        try { new PublicationDraftStore(context).reconcile(source, operation); fail(); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().contains("expired") || expected.getMessage().contains("did not finish"));
        }
        try { new PublicationDraftStore(context).reconcile(source, "unknown-operation"); fail(); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().contains("expired") || expected.getMessage().contains("did not finish"));
        }
    }

    public void testInvalidAndMismatchedPendingReceiptFailExplicitly() throws Exception {
        JSONObject source = PublicationDraftStore.create("source");
        JSONObject target = PublicationDraftStore.create("target");
        store.save(source); store.save(target);
        String invalid = "invalid-receipt";
        new PublicationDraftStore(context).save(target, invalid);
        try (FileOutputStream output = new FileOutputStream(receipt(invalid))) {
            output.write("{broken".getBytes(StandardCharsets.UTF_8));
        }
        try { new PublicationDraftStore(context).reconcile(source, invalid); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("receipt is invalid")); }
        String mismatched = "mismatched-receipt";
        new PublicationDraftStore(context).save(target, mismatched);
        JSONObject receipt = readReceipt(mismatched);
        receipt.put("sourceId", target.getString("id")).put("sourceRevision", target.getLong("updatedAt"));
        try (FileOutputStream output = new FileOutputStream(receipt(mismatched))) {
            output.write(PublicationDraftStore.encode(receipt));
        }
        try { new PublicationDraftStore(context).reconcile(source, mismatched); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("source mismatch")); }
    }

    public void testDeletedTargetAndInvalidReceiptSchemaFailExplicitly() throws Exception {
        JSONObject target = PublicationDraftStore.create("target");
        store.save(target);
        JSONObject targetBase = new JSONObject(target.toString());
        String deleted = "deleted-target";
        new PublicationDraftStore(context).save(target, deleted);
        store.delete(target.getString("id"));
        try { new PublicationDraftStore(context).reconcile(targetBase, deleted); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("Conflicting")); }
        JSONObject replacement = PublicationDraftStore.create("replacement");
        store.save(replacement);
        JSONObject replacementBase = new JSONObject(replacement.toString());
        String invalidSchema = "invalid-schema";
        new PublicationDraftStore(context).save(replacement, invalidSchema);
        JSONObject receipt = readReceipt(invalidSchema).put("schema", "wrong");
        try (FileOutputStream output = new FileOutputStream(receipt(invalidSchema))) {
            output.write(PublicationDraftStore.encode(receipt));
        }
        try { new PublicationDraftStore(context).reconcile(replacementBase, invalidSchema); fail(); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("receipt is invalid")); }
    }

    public void testMissingReceiptDoesNotTrustMatchingCurrentOperationId() throws Exception {
        JSONObject source = PublicationDraftStore.create("source");
        store.save(source);
        String operation = "spoofed-operation";
        source.put("operationId", operation);
        File path = new File(context.getFilesDir(), "publication-drafts/" + source.getString("id") + ".json");
        try (FileOutputStream output = new FileOutputStream(path)) {
            output.write(PublicationDraftStore.encode(source));
        }
        try { new PublicationDraftStore(context).reconcile(new JSONObject(source.toString()), operation); fail(); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().contains("expired") || expected.getMessage().contains("did not finish"));
        }
    }

    public void testFaultInjectionAtReceiptAndTargetCommitBoundaries() throws Exception {
        assertFaultBoundary(PublicationDraftStore.FAULT_BEFORE_RECEIPT_COMMIT, false, false);
        assertFaultBoundary(PublicationDraftStore.FAULT_AFTER_RECEIPT_COMMIT, true, false);
        assertFaultBoundary(PublicationDraftStore.FAULT_BEFORE_TARGET_COMMIT, true, false);
        assertFaultBoundary(PublicationDraftStore.FAULT_AFTER_TARGET_COMMIT, true, true);
    }

    public void testReceiptSchemaRequiresAllFieldsAndConfinedPaths() throws Exception {
        JSONObject draft = PublicationDraftStore.create("schema"); store.save(draft);
        JSONObject base = new JSONObject(draft.toString());
        String operation = "../hashed-not-a-path";
        store.save(draft, operation);
        File path = receipt(operation);
        assertEquals(new File(context.getFilesDir(), "publication-drafts/operations").getCanonicalFile(), path.getParentFile());
        JSONObject valid = readReceipt(operation);
        for (String key : new String[]{"targetDigest", "priorTargetRevision", "priorTargetDigest", "createdAt"}) {
            JSONObject invalid = new JSONObject(valid.toString()); invalid.remove(key);
            try (FileOutputStream output = new FileOutputStream(path)) { output.write(PublicationDraftStore.encode(invalid)); }
            try { new PublicationDraftStore(context).reconcile(base, operation); fail(key); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("receipt is invalid")); }
        }
        for (JSONObject invalid : new JSONObject[]{
                new JSONObject(valid.toString()).put("targetId", "../other"),
                new JSONObject(valid.toString()).put("sourceRevision", "1"),
                new JSONObject(valid.toString()).put("targetDigest", ""),
                new JSONObject(valid.toString()).put("targetRevision", 0),
                new JSONObject(valid.toString()).put("videoUri", "content://injected")}) {
            try (FileOutputStream output = new FileOutputStream(path)) { output.write(PublicationDraftStore.encode(invalid)); }
            try { new PublicationDraftStore(context).reconcile(base, operation); fail(); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("receipt is invalid")); }
        }
        assertTrue(path.delete());
        File outside = new File(context.getFilesDir(), "outside.json");
        try (FileOutputStream output = new FileOutputStream(outside)) { output.write(PublicationDraftStore.encode(valid)); }
        android.system.Os.symlink(outside.getPath(), path.getPath());
        try {
            try { new PublicationDraftStore(context).reconcile(base, operation); fail(); }
            catch (IOException expected) { assertTrue(expected.getCause().getMessage().contains("Linked")); }
            assertTrue(outside.isFile());
        } finally { assertTrue(path.delete()); }
    }

    public void testReceiptRetentionEvictsOldEntriesAndExpiresOldOperation() throws Exception {
        JSONObject draft = PublicationDraftStore.create("retention");
        store.save(draft);
        JSONObject firstBase = null, latestBase = null;
        for (int i = 0; i <= PublicationDraftStore.MAX_OPERATION_RECEIPTS; i++) {
            JSONObject base = new JSONObject(draft.toString());
            if (i == 0) firstBase = base;
            latestBase = base;
            draft.put("title", "filled " + i);
            store.save(draft, "operation-" + i);
        }
        assertEquals("filled " + PublicationDraftStore.MAX_OPERATION_RECEIPTS,
                store.load(draft.getString("id")).getString("title"));
        assertEquals(draft.toString(), new PublicationDraftStore(context).reconcile(latestBase,
                "operation-" + PublicationDraftStore.MAX_OPERATION_RECEIPTS).toString());
        try { new PublicationDraftStore(context).reconcile(firstBase, "operation-0"); fail(); }
        catch (IOException expected) {
            assertTrue(expected.getMessage().contains("expired") || expected.getMessage().contains("did not finish"));
        }
    }

    public void testReceiptReadCancellationPreservesCheckedInterruption() throws Exception {
        JSONObject draft = PublicationDraftStore.create("cancel"); store.save(draft);
        JSONObject base = new JSONObject(draft.toString());
        store.save(draft, "cancel-read");
        Thread.currentThread().interrupt();
        try { new PublicationDraftStore(context).reconcile(base, "cancel-read"); fail(); }
        catch (java.io.InterruptedIOException expected) {}
        finally { Thread.interrupted(); }
        assertEquals(draft.toString(), new PublicationDraftStore(context).reconcile(base, "cancel-read").toString());
    }

    public void testStreamingHashBoundedReadsAndCancellation() throws Exception {
        byte[] bytes = "actual independent bytes 中文 🎬".getBytes(StandardCharsets.UTF_8);
        PublicationDraftMedia.Fingerprint hash = PublicationDraftMedia.hash(new ByteArrayInputStream(bytes));
        StringBuilder expected = new StringBuilder();
        for (byte b : java.security.MessageDigest.getInstance("SHA-256").digest(bytes))
            expected.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        assertEquals(expected.toString(), hash.sha);
        assertEquals(bytes.length, hash.bytes);
        final long size = 8L * 1024 * 1024 + 13;
        java.io.InputStream generated = new java.io.InputStream() {
            long remaining = size;
            @Override public int read() { throw new AssertionError("Must read blocks"); }
            @Override public int read(byte[] data, int off, int length) {
                assertTrue(length <= 65536);
                if (remaining == 0) return -1;
                int n = (int) Math.min(length, remaining);
                java.util.Arrays.fill(data, off, off + n, (byte) 7); remaining -= n; return n;
            }
        };
        assertEquals(size, PublicationDraftMedia.hash(generated).bytes);
        Thread.currentThread().interrupt();
        try { PublicationDraftMedia.hash(new ByteArrayInputStream(bytes)); fail(); }
        catch (java.io.InterruptedIOException expectedError) {}
        finally { Thread.interrupted(); }
    }

    private JSONObject parse(String text) throws IOException {
        return PublicationDraftStore.importManifest(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
    }

    private File receipt(String operation) throws Exception {
        java.lang.reflect.Method method = PublicationDraftStore.class.getDeclaredMethod("operationFile", String.class);
        method.setAccessible(true);
        return (File) method.invoke(store, operation);
    }

    private JSONObject readReceipt(String operation) throws Exception {
        return new JSONObject(new String(java.nio.file.Files.readAllBytes(receipt(operation).toPath()), StandardCharsets.UTF_8));
    }

    private void assertFaultBoundary(String stage, boolean receiptCommitted, boolean targetCommitted) throws Exception {
        JSONObject draft = PublicationDraftStore.create(stage);
        store.save(draft);
        JSONObject base = new JSONObject(draft.toString());
        draft.put("title", "committed " + stage);
        AtomicInteger seen = new AtomicInteger();
        PublicationDraftStore failing = new PublicationDraftStore(context, fault -> {
            seen.incrementAndGet();
            if (stage.equals(fault)) throw new IOException(stage);
        });
        try { failing.save(draft, stage); fail(stage); }
        catch (IOException expected) { assertEquals(stage, expected.getMessage()); }
        assertTrue(seen.get() > 0);
        File receipt = receipt(stage);
        assertEquals(receiptCommitted, receipt.exists() || new File(receipt.getPath() + ".bak").exists());
        JSONObject current = store.load(base.getString("id"));
        assertEquals(targetCommitted ? "committed " + stage : stage, current.getString("title"));
        if (targetCommitted) {
            assertEquals(current.toString(), new PublicationDraftStore(context).reconcile(base, stage).toString());
        } else if (receiptCommitted) {
            try { new PublicationDraftStore(context).reconcile(base, stage); fail(stage); }
            catch (IOException expected) { assertTrue(expected.getMessage().contains("did not finish")); }
        } else {
            try { new PublicationDraftStore(context).reconcile(base, stage); fail(stage); }
            catch (IOException expected) {
                assertTrue(expected.getMessage().contains("expired") || expected.getMessage().contains("did not finish"));
            }
        }
    }
}
