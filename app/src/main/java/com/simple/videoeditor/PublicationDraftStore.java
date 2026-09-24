package com.simple.videoeditor;

import android.content.Context;
import android.net.Uri;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Private draft records; portable manifests never confer permission to read media. */
final class PublicationDraftStore {
    static final int MAX_BYTES = 128 * 1024;
    static final int MAX_OPERATION_RECEIPTS = 128;
    static final String SCHEMA = "video-publication-draft";
    private static final String OPERATION_SCHEMA = "video-publication-operation";
    private static final int MAX_OPERATION_ID = 160;
    private static final Object LOCK = new Object();
    static final String FAULT_BEFORE_RECEIPT_COMMIT = "before-receipt-commit";
    static final String FAULT_AFTER_RECEIPT_COMMIT = "after-receipt-commit";
    static final String FAULT_BEFORE_TARGET_COMMIT = "before-target-commit";
    static final String FAULT_AFTER_TARGET_COMMIT = "after-target-commit";
    static final String[] EDITABLE = {"title", "description", "tags", "language", "platform",
            "privacy", "audience", "publicationLicense"};
    private static final String PORTABLE = "schema version title description tags language platform privacy audience publicationLicense mediaName mediaSha256 mediaBytes durationMs musicCredits updatedAt";
    private static final String LOCAL = PORTABLE + " id status videoUri coverUri exportUri operationId mediaVerified";
    private final File directory;
    private final File operations;
    private final String unknownCredits;
    interface BeforeCommit { void run() throws IOException; }
    interface FaultInjector { void after(String stage) throws IOException; }
    private final FaultInjector faultInjector;

    PublicationDraftStore(Context context) throws IOException {
        this(context, stage -> {});
    }

    PublicationDraftStore(Context context, BeforeCommit beforeCommit) throws IOException {
        this(context, stage -> {
            if (FAULT_BEFORE_TARGET_COMMIT.equals(stage)) beforeCommit.run();
        });
    }

    PublicationDraftStore(Context context, FaultInjector faultInjector) throws IOException {
        this.faultInjector = faultInjector == null ? stage -> {} : faultInjector;
        unknownCredits = context.getString(R.string.draft_unknown_credits);
        directory = new File(context.getFilesDir().getCanonicalFile(), "publication-drafts");
        ensureDirectory(directory, "Cannot create publication draft directory");
        operations = new File(directory, "operations");
        ensureDirectory(operations, "Cannot create publication draft operation directory");
    }

    static JSONObject create(String displayName) throws JSONException {
        return new JSONObject().put("schema", SCHEMA).put("version", 1)
                .put("id", UUID.randomUUID().toString())
                .put("title", shortTitle(displayName)).put("description", "").put("tags", new JSONArray())
                .put("language", "").put("platform", "youtube").put("privacy", "private")
                .put("audience", "unspecified").put("publicationLicense", "unspecified").put("status", "draft")
                .put("mediaName", displayName).put("mediaSha256", "").put("mediaBytes", 0L)
                .put("durationMs", 0L).put("exportUri", "")
                .put("mediaVerified", false)
                .put("musicCredits", "").put("videoUri", "").put("coverUri", "")
                .put("updatedAt", 0L).put("operationId", "");
    }

    static String shortTitle(String name) {
        int end = Math.min(100, name.length());
        if (end < name.length() && Character.isHighSurrogate(name.charAt(end - 1))) end--;
        return name.substring(0, end);
    }

    void save(JSONObject value) throws IOException {
        save(value, "");
    }

    void save(JSONObject value, String operationId) throws IOException {
        synchronized (LOCK) {
        try {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Draft operation cancelled");
            operationId = operationId == null ? "" : operationId;
            PreparedTarget prepared = prepareTarget(value, operationId, false);
            if (!operationId.isEmpty()) commitReceipt(prepareReceipt(operationId, prepared.original, prepared));
            commitTarget(prepared);
            value.put("updatedAt", prepared.committed.getLong("updatedAt"));
            value.put("operationId", prepared.committed.getString("operationId"));
        } catch (JSONException error) {
            throw new IOException("Invalid publication draft", error);
        }
        }
    }

    JSONObject saveMetadata(JSONObject form, String operationId) throws IOException {
        synchronized (LOCK) {
            try {
                validate(form, true);
                JSONObject current = load(form.getString("id"));
                if (current.getLong("updatedAt") != form.getLong("updatedAt"))
                    throw new IOException("Conflicting draft revision; edits retained. Reopen to review the saved draft.");
                for (String key : EDITABLE) current.put(key, form.get(key));
                save(current, operationId);
                return current;
            } catch (JSONException error) { throw new IOException("Invalid draft metadata", error); }
        }
    }

    JSONObject reconcile(JSONObject base, String pendingOperation) throws IOException {
        synchronized (LOCK) {
            try {
                if (pendingOperation == null || pendingOperation.isEmpty()) {
                    validate(base, true);
                    JSONObject current = load(base.getString("id"));
                    if (current.getLong("updatedAt") != base.getLong("updatedAt"))
                        throw new IOException("Conflicting draft revision; edits retained. Reopen to review the saved draft.");
                    return current;
                }
                validateOperationId(pendingOperation, false);
                JSONObject receipt = null;
                boolean receiptPresent = false;
                try {
                    receipt = loadReceipt(pendingOperation);
                    receiptPresent = true;
                } catch (java.io.FileNotFoundException missing) {
                    // No durable receipt means the operation never completed crash-consistently.
                }
                if (receiptPresent) return targetFromReceipt(base, receipt);
                throw new IOException("Pending draft operation expired, was cancelled, or did not finish durably");
            } catch (JSONException error) { throw new IOException("Invalid draft snapshot", error); }
        }
    }

    JSONObject load(String id) throws IOException {
        synchronized (LOCK) {
        AtomicFile target = new AtomicFile(file(id));
        try (FileInputStream input = target.openRead()) {
            JSONObject value = parse(input);
            validate(value, true);
            if (!id.equals(value.getString("id"))) throw new IOException("Draft identity mismatch");
            return value;
        } catch (JSONException error) {
            throw new IOException("Invalid stored publication draft", error);
        }
        }
    }

    List<JSONObject> list() throws IOException {
        synchronized (LOCK) {
            return catalog();
        }
    }

    void delete(String id) throws IOException {
        synchronized (LOCK) {
        File file = file(id);
        new AtomicFile(file).delete();
        if (file.exists() || new File(file.getPath() + ".bak").exists()
                || new File(file.getPath() + ".new").exists()) {
            throw new IOException("Cannot delete publication draft");
        }
        }
    }

    JSONObject forExport(String uri, String name, String credits) throws IOException {
        synchronized (LOCK) {
            try {
                for (JSONObject draft : list()) {
                    if (uri.equals(draft.getString("exportUri"))) return draft;
                }
                JSONObject draft = create(name).put("videoUri", uri).put("exportUri", uri)
                        .put("musicCredits", credits);
                save(draft);
                return draft;
            } catch (JSONException error) { throw new IOException(error); }
        }
    }

    /** Derived index: only bounded private manifests, never a walk over video files. */
    private List<JSONObject> catalog() throws IOException {
        File[] entries = directory.listFiles();
        if (entries == null) throw new IOException("Cannot list publication drafts");
        java.util.Set<String> ids = new java.util.TreeSet<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.endsWith(".json.bak")) ids.add(name.substring(0, name.length() - 9));
            else if (name.endsWith(".json")) ids.add(name.substring(0, name.length() - 5));
        }
        List<JSONObject> result = new ArrayList<>();
        for (String id : ids) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelled");
            try { result.add(load(id)); }
            catch (java.io.InterruptedIOException cancelled) { throw cancelled; }
            catch (IOException invalidRecord) { /* Preserve invalid files and backups for explicit recovery. */ }
        }
        return result;
    }

    JSONObject existingExport(String uri) throws IOException {
        synchronized (LOCK) {
            for (JSONObject value : catalog())
                if (uri.equals(value.optString("exportUri"))) return value;
            return null;
        }
    }

    static final class Resolution {
        final JSONObject value;
        final List<JSONObject> choices;
        final boolean freshAllowed, reused;
        Resolution(JSONObject value, List<JSONObject> choices, boolean freshAllowed, boolean reused) {
            this.value = value; this.choices = choices; this.freshAllowed = freshAllowed; this.reused = reused;
        }
    }

    // Lookup, optimistic revision check, creation and binding share the same process-wide lock.
    Resolution resolve(PublicationDraftMedia.VerifiedVideo video, JSONObject base,
            String exportUri, String credits, JSONObject selected, boolean fresh, String operation) throws Exception {
        return resolve(video, base, exportUri, credits, selected, fresh, operation, base);
    }

    Resolution resolve(PublicationDraftMedia.VerifiedVideo video, JSONObject base,
            String exportUri, String credits, JSONObject selected, boolean fresh,
            String operation, JSONObject sourceSnapshot) throws Exception {
        synchronized (LOCK) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Cancelled");
            operation = operation == null ? "" : operation;
            validateOperationId(operation, false);
            if (base != null) {
                requireRevision(base);
                if (!base.getString("mediaSha256").isEmpty() && !video.matches(base))
                    throw new IOException("Different bytes require a separate draft; original metadata retained");
            }
            if (sourceSnapshot != null) sourceSnapshot = requireRevision(sourceSnapshot);
            List<JSONObject> matches = new ArrayList<>();
            JSONObject legacy = null;
            boolean conflict = false;
            for (JSONObject value : catalog()) {
                if (video.matches(value)) {
                    matches.add(value);
                    if (credits != null && !credits.equals(value.getString("musicCredits"))) conflict = true;
                } else if (!exportUri.isEmpty() && exportUri.equals(value.getString("exportUri"))
                        && value.getString("mediaSha256").isEmpty()) legacy = value;
            }
            if (legacy != null && !matches.isEmpty()) matches.add(legacy);
            JSONObject target;
            if (selected != null) {
                target = requireRevision(selected);
                if (!video.matches(target) && !(target.getString("mediaSha256").isEmpty()
                        && !exportUri.isEmpty() && exportUri.equals(target.getString("exportUri"))
                        && exportUri.equals(video.uri.toString())))
                    throw new IOException("Selected draft no longer matches this video");
            } else if (!fresh && (matches.size() > 1 || conflict
                    || matches.size() == 1 && !matches.get(0).getBoolean("mediaVerified"))) {
                return new Resolution(null, matches, conflict, false);
            } else if (!fresh && !matches.isEmpty()) {
                target = matches.get(0);
            } else if (!fresh && base != null) {
                target = load(base.getString("id"));
            } else if (!fresh && legacy != null) {
                target = legacy;
            } else {
                target = create(video.name).put("musicCredits", credits == null ? unknownCredits : credits)
                        .put("exportUri", exportUri);
            }
            boolean reused = video.matches(target);
            String before = target.toString();
            video.bind(target);
            boolean writesTarget = !target.toString().equals(before);
            if (writesTarget || !operation.isEmpty())
                target = commitResolvedTarget(target, operation, sourceSnapshot, writesTarget);
            return new Resolution(target, new ArrayList<>(), false, reused);
        }
    }

    private JSONObject requireRevision(JSONObject snapshot) throws Exception {
        validate(snapshot, true);
        JSONObject current = load(snapshot.getString("id"));
        if (current.getLong("updatedAt") != snapshot.getLong("updatedAt"))
            throw new IOException("Conflicting draft revision; edits retained. Reopen to review the saved draft.");
        return current;
    }

    static JSONObject portable(JSONObject value) throws IOException {
        try {
            validate(value, true);
            JSONObject result = new JSONObject();
            for (String field : PORTABLE.split(" ")) result.put(field, value.get(field));
            return result;
        } catch (JSONException error) {
            throw new IOException("Invalid portable publication draft", error);
        }
    }

    static JSONObject importManifest(InputStream input) throws IOException {
        try {
            JSONObject imported = parse(input);
            validate(imported, false);
            JSONObject result = create(imported.getString("mediaName"));
            for (String field : new String[]{"title", "description", "tags", "language", "platform",
                     "privacy", "audience", "publicationLicense", "mediaName", "mediaSha256", "mediaBytes", "durationMs", "musicCredits"}) {
                result.put(field, imported.get(field));
            }
            // Ignore all imported URI/path/identity/status fields; require an explicit media picker.
            result.put("status", "draft").put("videoUri", "").put("coverUri", "");
            result.put("mediaVerified", false);
            return result;
        } catch (JSONException error) {
            throw new IOException("Invalid imported publication manifest", error);
        }
    }

    static byte[] encode(JSONObject value) throws IOException {
        byte[] bytes = (value.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Publication manifest exceeds 128 KiB");
        return bytes;
    }

    private JSONObject targetFromReceipt(JSONObject base, JSONObject receipt) throws IOException, JSONException {
        validateReceiptSource(base, receipt);
        long expectedRevision = receipt.getLong("targetRevision");
        long priorRevision = receipt.getLong("priorTargetRevision");
        String expectedDigest = receipt.getString("targetDigest");
        String priorDigest = receipt.getString("priorTargetDigest");
        JSONObject target;
        try {
            target = load(receipt.getString("targetId"));
        } catch (java.io.InterruptedIOException cancelled) {
            throw cancelled;
        } catch (java.io.FileNotFoundException missing) {
            if (priorRevision == 0) {
                throw new IOException("Pending draft operation was cancelled or did not finish durably", missing);
            }
            throw new IOException("Conflicting draft revision; pending target changed or was deleted. Reopen to review the saved draft.", missing);
        } catch (IOException missingOrInvalid) {
            throw new IOException("Conflicting draft revision; pending target changed or was deleted. Reopen to review the saved draft.", missingOrInvalid);
        }
        String actualDigest = digest(encode(target));
        if (target.getLong("updatedAt") == expectedRevision
                && expectedDigest.equals(actualDigest)) return target;
        if (target.getLong("updatedAt") == priorRevision && priorDigest.equals(actualDigest)) {
            throw new IOException("Pending draft operation was cancelled or did not finish durably");
        }
        throw new IOException("Conflicting draft revision; pending target changed or was deleted. Reopen to review the saved draft.");
    }

    private JSONObject commitResolvedTarget(JSONObject target, String operation, JSONObject sourceSnapshot,
            boolean writesTarget) throws IOException, JSONException {
        PreparedTarget prepared = prepareTarget(target, operation, !writesTarget);
        if (!operation.isEmpty()) {
            JSONObject currentSource = currentSource(sourceSnapshot, prepared.original);
            commitReceipt(prepareReceipt(operation, currentSource, prepared));
        }
        if (prepared.needsWrite) {
            commitTarget(prepared);
            target.put("updatedAt", prepared.committed.getLong("updatedAt"));
            target.put("operationId", prepared.committed.getString("operationId"));
        } else {
            target = prepared.committed;
        }
        return target;
    }

    private PreparedTarget prepareTarget(JSONObject value, String operationId, boolean allowNoOp) throws IOException, JSONException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Draft operation cancelled");
        validate(value, true);
        operationId = operationId == null ? "" : operationId;
        validateOperationId(operationId, false);
        String id = value.getString("id");
        File path = file(id);
        AtomicFile target = new AtomicFile(path);
        JSONObject original = null;
        long previous = 0;
        if (path.exists() || new File(path + ".bak").exists()) {
            original = load(id);
            previous = original.getLong("updatedAt");
            if (previous != value.getLong("updatedAt")) throw new IOException("Stale draft; reopen before saving");
        } else if (value.getLong("updatedAt") != 0) {
            throw new IOException("Draft deleted; cannot resurrect stale edits");
        }
        JSONObject committed = new JSONObject(value.toString());
        boolean needsWrite = true;
        if (allowNoOp && original != null && committed.toString().equals(original.toString())) {
            committed = original;
            needsWrite = false;
        } else {
            if (previous == Long.MAX_VALUE) throw new IOException("Draft revision exhausted");
            committed.put("updatedAt", Math.max(previous + 1, System.currentTimeMillis()));
            committed.put("operationId", operationId);
            validate(committed, true);
        }
        byte[] bytes = encode(committed);
        return new PreparedTarget(target, original, committed, bytes, digest(bytes),
                previous, original == null ? "" : digest(encode(original)), needsWrite);
    }

    private JSONObject currentSource(JSONObject sourceSnapshot, JSONObject sameTarget) throws IOException, JSONException {
        if (sourceSnapshot == null) return null;
        validate(sourceSnapshot, true);
        if (sameTarget != null && sourceSnapshot.getString("id").equals(sameTarget.getString("id"))) {
            if (sameTarget.getLong("updatedAt") != sourceSnapshot.getLong("updatedAt")) {
                throw new IOException("Conflicting draft revision; edits retained. Reopen to review the saved draft.");
            }
            return sameTarget;
        }
        JSONObject current = load(sourceSnapshot.getString("id"));
        if (current.getLong("updatedAt") != sourceSnapshot.getLong("updatedAt")) {
            throw new IOException("Conflicting draft revision; edits retained. Reopen to review the saved draft.");
        }
        return current;
    }

    private PreparedReceipt prepareReceipt(String operation, JSONObject source, PreparedTarget target) throws IOException {
        try {
            validateOperationId(operation, true);
            if (source != null) validate(source, true);
            JSONObject receipt = new JSONObject()
                    .put("schema", OPERATION_SCHEMA)
                    .put("version", 1)
                    .put("operation", operation)
                    .put("sourceId", source == null ? "" : source.getString("id"))
                    .put("sourceRevision", source == null ? 0L : source.getLong("updatedAt"))
                    .put("targetId", target.committed.getString("id"))
                    .put("targetRevision", target.committed.getLong("updatedAt"))
                    .put("targetDigest", target.digest)
                    .put("priorTargetRevision", target.previousRevision)
                    .put("priorTargetDigest", target.previousDigest)
                    .put("createdAt", System.currentTimeMillis());
            validateReceipt(receipt);
            return new PreparedReceipt(new AtomicFile(operationFile(operation)), encode(receipt), operation);
        } catch (JSONException error) {
            throw new IOException("Invalid publication draft operation receipt", error);
        }
    }

    private JSONObject loadReceipt(String operation) throws IOException {
        try (FileInputStream input = new AtomicFile(operationFile(operation)).openRead()) {
            JSONObject receipt = parse(input);
            validateReceipt(receipt);
            if (!operation.equals(receipt.getString("operation")))
                throw new IOException("Pending draft operation receipt is invalid");
            return receipt;
        } catch (java.io.InterruptedIOException cancelled) {
            throw cancelled;
        } catch (java.io.FileNotFoundException missing) {
            throw missing;
        } catch (IOException error) {
            throw new IOException("Pending draft operation receipt is invalid", error);
        } catch (JSONException error) {
            throw new IOException("Pending draft operation receipt is invalid", error);
        }
    }

    private void ensureReceiptCapacity(String operation) throws IOException {
        File receipt = operationFile(operation);
        if (receipt.exists() || new File(receipt.getPath() + ".bak").exists()) {
            throw new IOException("Pending draft operation token already used");
        }
        while (receiptNames().size() >= MAX_OPERATION_RECEIPTS) {
            evictOldestReceipt();
        }
    }

    private File operationFile(String operation) throws IOException {
        validateOperationId(operation, true);
        StringBuilder hash = new StringBuilder();
        try {
            for (byte b : MessageDigest.getInstance("SHA-256").digest(operation.getBytes(StandardCharsets.UTF_8))) {
                hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            }
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
        File result = new File(operations, hash + ".json");
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File path = new File(result.getPath() + suffix);
            if (!path.getCanonicalFile().equals(path.getAbsoluteFile()))
                throw new IOException("Linked publication draft operation receipt");
        }
        return result;
    }

    private File file(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
            throw new IOException("Invalid publication draft ID");
        }
        File result = new File(directory, id + ".json");
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File path = new File(result.getPath() + suffix);
            if (!path.getCanonicalFile().equals(path.getAbsoluteFile()))
                throw new IOException("Linked publication draft file");
        }
        return result;
    }

    private static JSONObject parse(InputStream input) throws IOException, JSONException {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(readBytes(input))).toString();
        } catch (CharacterCodingException error) {
            throw new IOException("Manifest must be valid UTF-8", error);
        }
        boolean quoted = false, escaped = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted && c < 0x20) throw new IOException("Unescaped JSON control character");
            if (escaped) escaped = false;
            else if (quoted && c == '\\') escaped = true;
            else if (c == '"') quoted = !quoted;
        }
        try (android.util.JsonReader reader = new android.util.JsonReader(new java.io.StringReader(text))) {
            reader.setLenient(false);
            Object result = readJson(reader, 0);
            if (!(result instanceof JSONObject) || reader.peek() != android.util.JsonToken.END_DOCUMENT)
                throw new IOException("Expected exactly one JSON object");
            return (JSONObject) result;
        } catch (IllegalStateException | NumberFormatException error) {
            throw new IOException("Malformed JSON", error);
        }
    }

    private static Object readJson(android.util.JsonReader reader, int depth) throws IOException, JSONException {
        if (depth > 8) throw new IOException("Manifest nesting exceeds limit");
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate JSON key");
                    object.put(key, readJson(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) array.put(readJson(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING: return reader.nextString();
            case NUMBER:
                String number = reader.nextString();
                if (!number.matches("-?(0|[1-9][0-9]*)")) throw new IOException("Integer required");
                return Long.parseLong(number);
            case BOOLEAN: return reader.nextBoolean();
            case NULL: reader.nextNull(); return JSONObject.NULL;
            default: throw new IOException("Malformed JSON");
        }
    }

    private static byte[] readBytes(InputStream input) throws IOException {
        if (input == null) throw new IOException("No manifest input stream");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Draft operation cancelled");
            if (count > MAX_BYTES - output.size()) throw new IOException("Publication manifest exceeds 128 KiB");
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void validate(JSONObject value, boolean local) throws JSONException, IOException {
        if (!SCHEMA.equals(value.get("schema")) || integer(value, "version") != 1) {
            throw new IOException("Unsupported publication manifest version");
        }
        // Optional additions to v1: missing means unspecified, never inferred from music provenance.
        if (!value.has("publicationLicense")) value.put("publicationLicense", "unspecified");
        oneOf(value, "publicationLicense", "unspecified", "youtube-standard", "creative-commons-attribution");
        text(value, "title", 100);
        if (value.getString("title").trim().isEmpty()) throw new IOException("Title is required");
        text(value, "description", 5000);
        text(value, "musicCredits", 16000);
        text(value, "mediaName", 512);
        text(value, "language", 35);
        oneOf(value, "platform", "youtube", "other");
        oneOf(value, "privacy", "private", "unlisted", "public");
        oneOf(value, "audience", "unspecified", "made-for-kids", "not-made-for-kids");
        text(value, "mediaSha256", 64);
        String sha = value.getString("mediaSha256");
        if (!sha.isEmpty() && !sha.matches("[0-9a-f]{64}")) throw new IOException("Invalid media SHA-256");
        for (String key : new String[]{"mediaBytes", "durationMs", "updatedAt"})
            if (integer(value, key) < 0) throw new IOException("Invalid field: " + key);
        JSONArray tags = value.getJSONArray("tags");
        if (tags.length() > 30) throw new IOException("Too many tags");
        int total = 0;
        for (int i = 0; i < tags.length(); i++) {
            Object tag = tags.get(i);
            if (!(tag instanceof String) || ((String) tag).length() > 100) throw new IOException("Invalid tag");
            unicode((String) tag);
            total += ((String) tag).length();
        }
        if (total > 500) throw new IOException("Tags exceed 500 characters");
        if (local) {
            // Legacy hashes were computed locally only when a video had actually been bound.
            if (!value.has("mediaVerified")) value.put("mediaVerified",
                    !sha.isEmpty() && !value.optString("videoUri").isEmpty());
            if (!(value.get("mediaVerified") instanceof Boolean)) throw new IOException("Invalid hash verification flag");
            if (!value.has("operationId")) value.put("operationId", "");
            text(value, "operationId", MAX_OPERATION_ID);
            text(value, "id", 36);
            if (!value.getString("id").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}"))
                throw new IOException("Invalid draft ID");
            java.util.Iterator<String> keys = value.keys();
            java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(LOCAL.split(" ")));
            while (keys.hasNext()) if (!allowed.contains(keys.next())) throw new IOException("Unknown local field");
            oneOf(value, "status", "draft", "ready", "shared");
            for (String key : new String[]{"videoUri", "coverUri", "exportUri"}) {
                text(value, key, 4096);
                String uri = value.getString(key);
                if (uri.length() > 4096 || (!uri.isEmpty() && !"content".equals(Uri.parse(uri).getScheme()))) {
                    throw new IOException("Invalid local media binding");
                }
            }
        }
        encode(value);
    }

    private static void validateReceipt(JSONObject value) throws JSONException, IOException {
        if (!OPERATION_SCHEMA.equals(value.get("schema")) || integer(value, "version") != 1) {
            throw new IOException("Pending draft operation receipt is invalid");
        }
        text(value, "operation", MAX_OPERATION_ID);
        validateOperationId(value.getString("operation"), true);
        text(value, "sourceId", 36);
        if (!value.getString("sourceId").isEmpty()
                && !value.getString("sourceId").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IOException("Pending draft operation receipt is invalid");
        }
        long sourceRevision = integer(value, "sourceRevision");
        if (sourceRevision < 0 || value.getString("sourceId").isEmpty() != (sourceRevision == 0))
            throw new IOException("Pending draft operation receipt is invalid");
        text(value, "targetId", 36);
        if (!value.getString("targetId").matches("[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}")) {
            throw new IOException("Pending draft operation receipt is invalid");
        }
        long targetRevision = integer(value, "targetRevision");
        long priorRevision = integer(value, "priorTargetRevision");
        text(value, "targetDigest", 64);
        text(value, "priorTargetDigest", 64);
        if (targetRevision <= 0 || priorRevision < 0 || priorRevision > targetRevision
                || !value.getString("targetDigest").matches("[0-9a-f]{64}")
                || !(priorRevision == 0 ? value.getString("priorTargetDigest").isEmpty()
                    : value.getString("priorTargetDigest").matches("[0-9a-f]{64}"))
                || integer(value, "createdAt") < 0)
            throw new IOException("Pending draft operation receipt is invalid");
        java.util.Iterator<String> keys = value.keys();
        java.util.Set<String> allowed = new java.util.HashSet<>(java.util.Arrays.asList(
                "schema", "version", "operation", "sourceId", "sourceRevision", "targetId", "targetRevision",
                "targetDigest", "priorTargetRevision", "priorTargetDigest", "createdAt"));
        while (keys.hasNext()) if (!allowed.contains(keys.next())) {
            throw new IOException("Pending draft operation receipt is invalid");
        }
    }

    private void commitReceipt(PreparedReceipt receipt) throws IOException {
        // Durable intent precedes the target rename. Recovery only accepts the exact target;
        // it never replays an interrupted write or trusts a mutable draft operationId.
        ensureReceiptCapacity(receipt.operation);
        commitAtomic(receipt.file, operations, receipt.bytes,
                "Publication draft operation receipt was not durably saved",
                FAULT_BEFORE_RECEIPT_COMMIT, FAULT_AFTER_RECEIPT_COMMIT);
    }

    private void commitTarget(PreparedTarget target) throws IOException {
        if (!target.needsWrite) return;
        commitAtomic(target.file, directory, target.bytes,
                "Publication draft was not durably saved",
                FAULT_BEFORE_TARGET_COMMIT, FAULT_AFTER_TARGET_COMMIT);
    }

    private void commitAtomic(AtomicFile file, File parent, byte[] bytes, String failure,
            String beforeStage, String afterStage) throws IOException {
        syncDirectory(parent);
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
            faultInjector.after(beforeStage);
            if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Draft operation cancelled");
            file.finishWrite(output);
        } catch (IOException | RuntimeException error) {
            file.failWrite(output);
            throw error;
        }
        syncDirectory(parent);
        try (FileInputStream input = file.openRead()) {
            if (!java.util.Arrays.equals(bytes, readBytes(input))) throw new IOException(failure);
        }
        faultInjector.after(afterStage);
    }

    private void validateReceiptSource(JSONObject base, JSONObject receipt) throws IOException, JSONException {
        if (base == null) {
            if (!receipt.getString("sourceId").isEmpty() || receipt.getLong("sourceRevision") != 0) {
                throw new IOException("Pending draft operation source mismatch; reopen to review the current draft.");
            }
            return;
        }
        validate(base, true);
        if (!base.getString("id").equals(receipt.getString("sourceId"))
                || base.getLong("updatedAt") != receipt.getLong("sourceRevision")) {
            throw new IOException("Pending draft operation source mismatch; reopen to review the current draft.");
        }
        if (!receipt.getString("sourceId").equals(receipt.getString("targetId"))) {
            JSONObject current = load(receipt.getString("sourceId"));
            if (current.getLong("updatedAt") != receipt.getLong("sourceRevision"))
                throw new IOException("Conflicting draft revision; operation source changed. Reopen to review the saved draft.");
        }
    }

    private java.util.Set<String> receiptNames() throws IOException {
        File[] entries = operations.listFiles();
        if (entries == null) throw new IOException("Cannot list publication draft operation receipts");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (File entry : entries) {
            String name = entry.getName();
            if (name.endsWith(".json.bak") || name.endsWith(".json.new")) names.add(name.substring(0, name.length() - 9));
            else if (name.endsWith(".json")) names.add(name.substring(0, name.length() - 5));
        }
        return names;
    }

    private void evictOldestReceipt() throws IOException {
        File[] entries = operations.listFiles((dir, name) -> name.endsWith(".json")
                || name.endsWith(".json.bak") || name.endsWith(".json.new"));
        if (entries == null || entries.length == 0) {
            throw new IOException("Publication draft operation receipt retention exceeded");
        }
        File oldest = entries[0];
        for (File entry : entries) {
            if (entry.lastModified() < oldest.lastModified()) oldest = entry;
        }
        File base = oldest.getName().endsWith(".bak") || oldest.getName().endsWith(".new")
                ? new File(oldest.getParentFile(), oldest.getName().substring(0, oldest.getName().length() - 4))
                : oldest;
        for (String suffix : new String[]{"", ".bak", ".new"}) {
            File file = new File(base.getPath() + suffix);
            if (file.exists() && !file.delete()) {
                throw new IOException("Cannot evict old publication draft operation receipt");
            }
        }
        syncDirectory(operations);
    }

    private static void ensureDirectory(File directory, String message) throws IOException {
        if (!directory.getCanonicalFile().equals(directory.getAbsoluteFile()))
            throw new IOException("Linked publication draft directory");
        if (directory.isDirectory()) return;
        File parent = directory.getParentFile();
        if (!directory.mkdirs()) throw new IOException(message);
        if (parent != null) syncDirectory(parent);
        syncDirectory(directory);
    }

    private static void syncDirectory(File directory) throws IOException {
        try {
            FileDescriptor descriptor = Os.open(directory.getPath(), OsConstants.O_RDONLY, 0);
            try { Os.fsync(descriptor); }
            finally { Os.close(descriptor); }
        } catch (ErrnoException error) {
            throw new IOException("Cannot sync publication draft directory", error);
        }
    }

    private static String digest(byte[] bytes) throws IOException {
        try {
            StringBuilder result = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException("SHA-256 unavailable", impossible);
        }
    }

    private static final class PreparedTarget {
        final AtomicFile file;
        final JSONObject original;
        final JSONObject committed;
        final byte[] bytes;
        final String digest;
        final long previousRevision;
        final String previousDigest;
        final boolean needsWrite;
        PreparedTarget(AtomicFile file, JSONObject original, JSONObject committed, byte[] bytes,
                String digest, long previousRevision, String previousDigest, boolean needsWrite) {
            this.file = file;
            this.original = original;
            this.committed = committed;
            this.bytes = bytes;
            this.digest = digest;
            this.previousRevision = previousRevision;
            this.previousDigest = previousDigest;
            this.needsWrite = needsWrite;
        }
    }

    private static final class PreparedReceipt {
        final AtomicFile file;
        final byte[] bytes;
        final String operation;
        PreparedReceipt(AtomicFile file, byte[] bytes, String operation) {
            this.file = file;
            this.bytes = bytes;
            this.operation = operation;
        }
    }

    private static void validateOperationId(String operation, boolean required) throws IOException {
        if (operation == null) {
            if (required) throw new IOException("Pending draft operation is missing");
            return;
        }
        if (operation.isEmpty()) {
            if (required) throw new IOException("Pending draft operation is missing");
            return;
        }
        unicode(operation);
        if (operation.length() > MAX_OPERATION_ID) throw new IOException("Invalid field: operationId");
    }

    private static long integer(JSONObject value, String key) throws JSONException, IOException {
        Object field = value.get(key);
        if (!(field instanceof Integer) && !(field instanceof Long)) throw new IOException("Integer required: " + key);
        return ((Number) field).longValue();
    }

    private static void text(JSONObject value, String key, int max) throws JSONException, IOException {
        Object field = value.get(key);
        if (!(field instanceof String) || ((String) field).length() > max) {
            throw new IOException("Invalid field: " + key);
        }
        unicode((String) field);
    }

    private static void unicode(String text) throws IOException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                    throw new IOException("Unpaired Unicode surrogate");
            } else if (Character.isLowSurrogate(c)) throw new IOException("Unpaired Unicode surrogate");
        }
    }

    private static void oneOf(JSONObject value, String key, String... choices) throws JSONException, IOException {
        text(value, key, 64);
        String actual = value.getString(key);
        for (String choice : choices) if (choice.equals(actual)) return;
        throw new IOException("Invalid field: " + key);
    }
}
