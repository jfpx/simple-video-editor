package com.simple.videoeditor;

import android.util.AtomicFile;
import android.util.JsonWriter;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilterOutputStream;
import java.io.FilterWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;

/** The suite is an index, never a second copy of measurements or exception diagnostics. */
final class CompactSuiteIndex {
    static final int MAX_BYTES = 256 * 1024;
    static final String MANIFEST = "run-metadata.json";
    static final String BINDING_SCHEMA = "selftest-report-binding-v2";

    private CompactSuiteIndex() {}

    static JSONArray results(JSONArray raw, int maximum) throws IOException, JSONException {
        if (maximum < 0 || maximum > 60) throw new IOException("Invalid suite inventory limit");
        if (raw.length() > maximum) throw new IOException("Suite result inventory exceeds its fixed plan");
        JSONArray index = new JSONArray();
        for (int i = 0; i < raw.length(); i++) {
            JSONObject source = raw.getJSONObject(i);
            JSONObject row = new JSONObject();
            copyString(source, row, "id", 128);
            copyString(source, row, "status", 16);
            copyString(source, row, "expected_status", 16);
            copyString(source, row, "actual_status", 16);
            copyString(source, row, "candidate_sha256", 64);
            copyString(source, row, "input_sha256", 64);
            copyString(source, row, "report", 160);
            if (row.has("report") && !row.getString("report").matches("[a-z0-9_-]+\\.json")) {
                throw new IOException("Invalid suite diagnostic report reference");
            }
            index.put(row);
        }
        return index;
    }

    private static void copyString(JSONObject source, JSONObject target, String key, int maximum)
            throws IOException, JSONException {
        if (!source.has(key)) return;
        Object value = source.get(key);
        if (!(value instanceof String) || ((String) value).length() > maximum) {
            throw new IOException("Invalid or oversized suite field: " + key);
        }
        target.put(key, value);
    }

    static JSONObject reference(String field) throws JSONException {
        return new JSONObject().put("report", MANIFEST).put("field", field);
    }

    static JSONObject metadata(JSONObject raw) throws IOException, JSONException {
        JSONObject index = reference("metadata");
        copyString(raw, index, "source_revision", 40);
        copyString(raw, index, "installed_apk_sha256", 64);
        return index;
    }

    static JSONObject binding(JSONObject raw) throws IOException, JSONException {
        JSONObject index = new JSONObject().put("schema", BINDING_SCHEMA);
        copyString(raw, index, "run_id", 64);
        copyString(raw, index, "mode", 32);
        copyString(raw, index, "selected_report_mode", 32);
        index.put("selected_report_required", raw.getBoolean("selected_report_required"));
        index.put("selected_report_uri_sha256", digest(raw.getString("selected_report_uri")));
        // Hash empty destinations too: v2 binds the entire selection without dropping any value.
        index.put("selected_report_destination_sha256",
                digest(raw.optString("selected_report_destination", "")));
        return index;
    }

    static String digest(String text) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            OutputStream sink = new OutputStream() {
                @Override public void write(int value) {}
                @Override public void write(byte[] bytes, int offset, int count) {}
            };
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new DigestOutputStream(sink, digest), StandardCharsets.UTF_8)) {
                // Writer.write(String) may allocate a char array as large as the input.
                for (int offset = 0; offset < text.length(); offset += 2048) {
                    writer.write(text, offset, Math.min(2048, text.length() - offset));
                }
            }
            char[] hex = new char[64];
            char[] digits = "0123456789abcdef".toCharArray();
            byte[] bytes = digest.digest();
            for (int i = 0; i < bytes.length; i++) {
                hex[i * 2] = digits[(bytes[i] & 255) >>> 4];
                hex[i * 2 + 1] = digits[bytes[i] & 15];
            }
            return new String(hex);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable for saved report binding", error);
        }
    }

    static String serialize(JSONObject index) throws IOException, JSONException {
        // Count encoded JSON before allocating its serialized String or UTF-8 byte array.
        // Structural/field limits also bound the work and JsonWriter's escaping allocations.
        validateSizeShape(index, 0, new int[] {0});
        OutputStream counter = new OutputStream() {
            int count;
            @Override public void write(int value) throws IOException { add(1); }
            @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                add(length);
            }
            private void add(int length) throws IOException {
                if (length > MAX_BYTES - count) throw new IOException("suite.json exceeds 256 KiB index limit");
                count += length;
            }
        };
        try (JsonWriter writer = new JsonWriter(new OutputStreamWriter(counter, StandardCharsets.UTF_8))) {
            writeJson(writer, index);
        }
        StringWriter text = new StringWriter();
        try (JsonWriter writer = new JsonWriter(text)) {
            writeJson(writer, index);
        }
        return text.toString();
    }

    private static void validateSizeShape(Object value, int depth, int[] nodes)
            throws IOException, JSONException {
        if (depth > 8 || ++nodes[0] > 4096) throw new IOException("Suite index structure exceeds limit");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (key.length() > 128) throw new IOException("Suite index key exceeds limit");
                validateSizeShape(object.get(key), depth + 1, nodes);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            if (array.length() > 128) throw new IOException("Suite index array exceeds limit");
            for (int i = 0; i < array.length(); i++) validateSizeShape(array.get(i), depth + 1, nodes);
        } else if (value instanceof String && ((String) value).length() > 8192) {
            throw new IOException("Suite index string exceeds limit");
        }
    }

    static synchronized void writeRaw(File file, JSONObject raw) throws IOException, JSONException {
        AtomicFile atomic = new AtomicFile(file);
        FileOutputStream stream = null;
        try {
            stream = atomic.startWrite();
            CountingOutputStream counted = new CountingOutputStream(stream);
            JsonWriter writer = new JsonWriter(new FilterWriter(
                    new OutputStreamWriter(counted, StandardCharsets.UTF_8)) {
                @Override public void write(String text, int offset, int length) throws IOException {
                    for (int end = offset + length; offset < end; offset += 2048) {
                        out.write(text, offset, Math.min(2048, end - offset));
                    }
                }
            });
            writeJson(writer, raw);
            writer.flush();
            stream.getFD().sync();
            atomic.finishWrite(stream);
            verifyCommit(file, counted.length);
        } catch (IOException | JSONException | RuntimeException error) {
            if (stream != null) atomic.failWrite(stream);
            throw error;
        }
    }

    private static final class CountingOutputStream extends FilterOutputStream {
        long length;
        CountingOutputStream(OutputStream output) { super(output); }
        @Override public void write(int value) throws IOException {
            out.write(value);
            length++;
        }
        @Override public void write(byte[] bytes, int offset, int count) throws IOException {
            out.write(bytes, offset, count);
            length += count;
        }
    }

    static void verifyCommit(File file, long length) throws IOException {
        // AtomicFile.finishWrite logs (rather than throws) some rename/delete failures.
        if (!file.isFile() || file.length() != length
                || new File(file.getPath() + ".new").exists()
                || new File(file.getPath() + ".bak").exists()) {
            throw new IOException("Atomic report commit failed: " + file.getName());
        }
    }

    private static void writeJson(JsonWriter writer, Object value) throws IOException, JSONException {
        if (value == null || value == JSONObject.NULL) {
            writer.nullValue();
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            writer.beginObject();
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                writer.name(key);
                writeJson(writer, object.get(key));
            }
            writer.endObject();
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            writer.beginArray();
            for (int i = 0; i < array.length(); i++) writeJson(writer, array.get(i));
            writer.endArray();
        } else if (value instanceof Boolean) {
            writer.value((Boolean) value);
        } else if (value instanceof Number) {
            writer.value((Number) value);
        } else if (value instanceof String) {
            writer.value((String) value);
        } else {
            throw new IOException("Unsupported diagnostic JSON value");
        }
    }
}
