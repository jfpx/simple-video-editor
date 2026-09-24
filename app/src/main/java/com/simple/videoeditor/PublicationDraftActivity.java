package com.simple.videoeditor;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

/** Local metadata only. Pickers and explicit copy/share actions are the only external handoffs. */
public final class PublicationDraftActivity extends AppCompatActivity {
    static final String EXPORT_URI = "publicationExportUri";
    private static final int IMPORT = 801, EXPORT = 802, VIDEO = 803, COVER = 804, OPEN_VIDEO = 805;
    private static final class PendingWork {
        final ExecutorService worker = Executors.newSingleThreadExecutor();
        final PublicationDraftMedia.Reads reads = new PublicationDraftMedia.Reads();
    }
    private PendingWork pendingWork;
    private ExecutorService worker;
    private PublicationDraftMedia.Reads reads;
    private final List<View> controls = new ArrayList<>();
    private final EditText[] fields = new EditText[4];
    private final Spinner[] choices = new Spinner[4];
    private final String[][] values = {{"youtube", "other"}, {"private", "unlisted", "public"},
            {"unspecified", "made-for-kids", "not-made-for-kids"},
            {"unspecified", "youtube-standard", "creative-commons-attribution"}};
    private final String[] choiceKeys = {"platform", "privacy", "audience", "publicationLicense"};
    private final String[] keys = {"title", "description", "tags", "language"};
    private LinearLayout body;
    private TextView message;
    private JSONObject draft;
    private String baseline, pendingManifest, pickerId;
    private String pendingOperation, restoreOperation;
    private boolean pendingMutation;
    private boolean stateSaved;
    private Runnable deferredResult;
    private boolean busy, destroyed;
    private boolean videoReadable, coverReadable;
    private int generation;
    private Future<?> job;
    private Button shareVideo, shareCover;

    @Override protected void onCreate(Bundle state) {
        UiLocales.initialize(this);
        super.onCreate(state);
        // Reconciliation queues behind the old worker; explicit exit cancels that same owner.
        pendingWork = (PendingWork) getLastCustomNonConfigurationInstance();
        if (pendingWork == null) pendingWork = new PendingWork();
        worker = pendingWork.worker;
        reads = pendingWork.reads;
        reads.progress = bytes -> runOnUiThread(() -> {
            if (!destroyed && busy && !stateSaved) message.setText(getString(R.string.draft_hash_progress, bytes));
        });
        setTitle(R.string.draft_title);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        message = new TextView(this);
        message.setId(R.id.draftMessage);
        message.setPadding(16, 8, 16, 8);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(message);
        ScrollView scroll = new ScrollView(this);
        body = new LinearLayout(this);
        body.setPadding(16, 8, 16, 24);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button cancel = new Button(this);
        cancel.setText(R.string.draft_cancel);
        cancel.setOnClickListener(v -> onBackPressed());
        root.addView(cancel);
        setContentView(root);
        if (state != null && state.containsKey("draft")) {
            try {
                draft = new JSONObject(state.getString("draft"));
                baseline = state.getString("baseline");
                pendingManifest = state.getString("manifest");
                pickerId = state.getString("picker");
                restoreOperation = state.getString("operation", "");
                renderForm();
                for (int i = 0; i < fields.length; i++) fields[i].setText(state.getString(keys[i], ""));
                for (int i = 0; i < choices.length; i++) choices[i].setSelection(state.getInt("choice" + i));
                JSONObject base = new JSONObject(draft.toString());
                JSONObject edited = form();
                String operation = restoreOperation;
                run(false, () -> new PublicationDraftStore(this).reconcile(base, operation), current -> {
                    restoreOperation = null;
                    draft = current;
                    baseline = current.toString();
                    renderForm();
                    // Only editable fields that actually differ from the captured base are overlaid.
                    // Bindings, grants, provenance and the revision always come from durable storage.
                    boolean sameDraft = base.getString("id").equals(current.getString("id"));
                    for (int i = 0; sameDraft && i < fields.length; i++) {
                        String original = i == 2 ? PublicationDraftMedia.tags(base) : base.getString(keys[i]);
                        if (!state.getString(keys[i], "").equals(original))
                            fields[i].setText(state.getString(keys[i], ""));
                    }
                    for (int i = 0; sameDraft && i < choices.length; i++)
                        if (!edited.optString(choiceKeys[i], "unspecified").equals(base.optString(choiceKeys[i], "unspecified")))
                            choices[i].setSelection(state.getInt("choice" + i));
                    if (pickerId == null) checkAvailability();
                }, () -> restoreOperation = null);
            } catch (Exception error) {
                restoreOperation = null;
                error(error);
            }
        } else if (getIntent().hasExtra(EXPORT_URI)) {
            String uri = getIntent().getStringExtra(EXPORT_URI);
            run(false, () -> {
                PublicationExportSnapshot.load(getApplicationContext(), uri);
                PublicationDraftStore store = new PublicationDraftStore(this);
                try {
                    return PublicationDraftMedia.verify(this, Uri.parse(uri), reads);
                } catch (java.io.InterruptedIOException cancelled) { throw cancelled; }
                catch (java.io.FileNotFoundException | SecurityException unavailable) {
                    JSONObject existing = store.existingExport(uri);
                    if (existing == null) throw unavailable;
                    return existing;
                }
            }, value -> {
                if (value instanceof JSONObject) open((JSONObject) value);
                else {
                    JSONObject context = PublicationExportSnapshot.load(getApplicationContext(), uri);
                    resolve((PublicationDraftMedia.VerifiedVideo) value, null, uri,
                            context.getString("musicCredits"), null, false);
                }
            });
        } else showList();
    }

    private interface Work<T> { T run() throws Exception; }
    private interface Result<T> { void accept(T value) throws Exception; }
    private interface Action { void run() throws Exception; }

    private <T> void run(Work<T> work, Result<T> result) {
        run(false, work, result);
    }

    private <T> void run(boolean mutating, Work<T> work, Result<T> result) {
        run(mutating, work, result, () -> {});
    }

    private <T> void run(boolean mutating, Work<T> work, Result<T> result, Runnable failed) {
        if (busy || destroyed) return;
        busy = true;
        pendingMutation = mutating;
        pendingOperation = mutating ? java.util.UUID.randomUUID().toString() : null;
        int token = ++generation;
        setControls(false);
        message.setText(R.string.draft_working);
        job = worker.submit(() -> {
            try {
                T value = work.run();
                deliver(() -> {
                    if (destroyed || token != generation) return;
                    busy = false;
                    if (!stateSaved) {
                        pendingMutation = false;
                        pendingOperation = null;
                    }
                    setControls(true);
                    message.setText("");
                    try { result.accept(value); } catch (Exception error) { error(error); }
                });
            } catch (Exception error) {
                deliver(() -> {
                    if (destroyed || token != generation) return;
                    busy = false;
                    failed.run();
                    if (!stateSaved) {
                        pendingMutation = false;
                        pendingOperation = null;
                    }
                    setControls(true);
                    error(error);
                });
            }
        });
    }

    private void deliver(Runnable result) {
        runOnUiThread(() -> {
            if (destroyed) return;
            if (stateSaved) deferredResult = result;
            else result.run();
        });
    }

    @Override protected void onResume() {
        super.onResume();
        stateSaved = false;
        Runnable result = deferredResult;
        deferredResult = null;
        if (result != null) result.run();
    }

    private void setControls(boolean enabled) {
        for (View view : controls) view.setEnabled(enabled);
        if (enabled && draft != null) {
            if (shareVideo != null) shareVideo.setEnabled(videoReadable);
            if (shareCover != null) shareCover.setEnabled(coverReadable);
        }
    }

    private void reset() {
        body.removeAllViews(); controls.clear(); shareVideo = null; shareCover = null;
        videoReadable = false; coverReadable = false;
    }

    private void showList() {
        draft = null; baseline = null;
        reset();
        label(getString(R.string.draft_notice));
        button(R.string.draft_new, () -> run(true, () -> {
            JSONObject value = PublicationDraftStore.create(getString(R.string.draft_untitled))
                    .put("musicCredits", getString(R.string.draft_unknown_credits));
            new PublicationDraftStore(this).save(value); return value;
        }, this::open));
        button(R.string.draft_open_video, () -> picker(OPEN_VIDEO, "video/mp4"));
        button(R.string.draft_import, () -> picker(IMPORT, "application/json"));
        run(false, () -> new PublicationDraftStore(this).list(), list -> {
            for (JSONObject value : list) {
                Button item = button(value.getString("title") + "\n" + value.getString("mediaName"),
                        () -> run(false, () -> new PublicationDraftStore(this).load(value.getString("id")), this::open));
                item.setContentDescription(value.getString("title") + " · " + value.getString("mediaName"));
            }
            if (list.isEmpty()) label(getString(R.string.draft_empty));
        });
    }

    private void open(JSONObject value) throws Exception {
        draft = value;
        baseline = value.toString();
        renderForm();
        checkAvailability();
        if (value.getString("mediaName").length() > 100 && value.getString("title").length() <= 100)
            Toast.makeText(this, R.string.draft_title_shortened, Toast.LENGTH_LONG).show();
    }

    private void renderForm() throws Exception {
        reset();
        label(getString(R.string.draft_notice));
        label(draft.getString("mediaName") + "\nSHA-256: " + draft.getString("mediaSha256")
                + "\n" + draft.getLong("mediaBytes") + " bytes · " + draft.getLong("durationMs") + " ms");
        int[] labels = {R.string.draft_field_title, R.string.draft_description, R.string.draft_tags, R.string.draft_language};
        int[] ids = {R.id.draftTitle, R.id.draftDescription, R.id.draftTags, R.id.draftLanguage};
        for (int i = 0; i < fields.length; i++) {
            TextView heading = label(getString(labels[i]));
            EditText field = new EditText(this);
            field.setSaveEnabled(false);
            field.setId(ids[i]);
            heading.setLabelFor(ids[i]);
            field.setContentDescription(getString(labels[i]));
            field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            field.setText(i == 2 ? PublicationDraftMedia.tags(draft) : draft.getString(keys[i]));
            fields[i] = field; body.addView(field); controls.add(field);
        }
        int[] choiceLabels = {R.string.draft_platform, R.string.draft_privacy, R.string.draft_audience, R.string.draft_license};
        int[] arrays = {R.array.draft_platforms, R.array.draft_privacies, R.array.draft_audiences, R.array.draft_licenses};
        int[] choiceIds = {R.id.draftPlatform, R.id.draftPrivacy, R.id.draftAudience, R.id.draftLicense};
        for (int i = 0; i < choices.length; i++) {
            TextView heading = label(getString(choiceLabels[i]));
            Spinner spinner = new Spinner(this);
            spinner.setSaveEnabled(false);
            spinner.setId(choiceIds[i]);
            heading.setLabelFor(choiceIds[i]);
            spinner.setContentDescription(getString(choiceLabels[i]));
            ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this, arrays[i], android.R.layout.simple_spinner_item);
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            spinner.setAdapter(adapter);
            spinner.setSelection(java.util.Arrays.asList(values[i]).indexOf(draft.optString(choiceKeys[i], "unspecified")));
            choices[i] = spinner; body.addView(spinner); controls.add(spinner);
        }
        label(getString(R.string.draft_license_notice));
        label(getString(R.string.draft_credits) + "\n" + draft.getString("musicCredits"));
        label(getString("shared".equals(draft.getString("status")) ? R.string.draft_shared : R.string.draft_local));
        button(R.string.draft_save, () -> save(null)).setId(R.id.draftSave);
        button(R.string.draft_video, () -> save(() -> picker(VIDEO, "video/mp4")));
        button(R.string.draft_cover, () -> save(() -> picker(COVER, "image/*")));
        button(R.string.draft_hash, () -> save(() -> fingerprint()));
        button(R.string.draft_export, () -> save(() -> {
            pendingManifest = PublicationDraftStore.portable(draft).toString();
            picker(EXPORT, "application/json");
        }));
        button(R.string.draft_copy_title, () -> copy(0));
        button(R.string.draft_copy_description, () -> copy(1));
        button(R.string.draft_copy_tags, () -> copy(2));
        button(R.string.draft_copy_all, () -> copy(3));
        label(getString(R.string.draft_provider_notice));
        shareVideo = button(R.string.draft_share_video, () -> share(false));
        shareCover = button(R.string.draft_share_cover, () -> share(true));
        button(R.string.draft_delete, () -> new AlertDialog.Builder(this).setMessage(R.string.draft_delete_notice)
                .setNegativeButton(R.string.draft_keep, null).setPositiveButton(R.string.draft_delete, (d, w) -> {
                    String id = draft.optString("id");
                    run(true, () -> { new PublicationDraftStore(this).delete(id); return true; }, ignored -> showList());
                }).show());
        button(R.string.draft_list, () -> leave(this::showList));
    }

    private JSONObject form() throws Exception {
        JSONObject value = new JSONObject(draft.toString());
        for (int i : new int[]{0, 1, 3}) value.put(keys[i], fields[i].getText().toString());
        String tags = fields[2].getText().toString();
        if (!tags.equals(PublicationDraftMedia.tags(draft))) {
            JSONArray array = new JSONArray();
            for (String tag : tags.split("\\r?\\n", -1)) if (!tag.isEmpty()) array.put(tag);
            value.put("tags", array);
        }
        for (int i = 0; i < choices.length; i++)
            value.put(choiceKeys[i], values[i][choices[i].getSelectedItemPosition()]);
        return value;
    }

    private void save(Action after) {
        try {
            JSONObject value = form();
            PublicationDraftStore.portable(value);
            run(true, () -> new PublicationDraftStore(this).saveMetadata(value, pendingOperation), saved -> {
                draft = saved; baseline = saved.toString();
                checkAvailability(() -> {
                    if (videoReadable) message.setText(R.string.draft_saved);
                    if (after != null) after.run();
                });
            });
        } catch (Exception error) { error(error); }
    }

    private void copy(int kind) {
        try {
            JSONObject value = form();
            PublicationDraftStore.portable(value);
            String text = kind == 0 ? value.getString("title") : kind == 1 ? value.getString("description")
                    : kind == 2 ? PublicationDraftMedia.tags(value) : PublicationDraftMedia.combined(value);
            ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(getString(R.string.draft_title), text));
            message.setText(R.string.draft_copied);
        } catch (Exception error) { error(error); }
    }

    private void share(boolean cover) {
        save(() -> {
            JSONObject snapshot = new JSONObject(draft.toString());
            run(false, () -> PublicationDraftMedia.share(this, snapshot, cover), intent -> {
                Intent chooser = Intent.createChooser(intent, getString(R.string.draft_share_notice));
                chooser.setClipData(intent.getClipData());
                chooser.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivity(chooser);
                if (!cover) {
                    snapshot.put("status", "shared");
                    run(true, () -> { new PublicationDraftStore(this).save(snapshot, pendingOperation); return snapshot; }, saved -> {
                        draft = saved; baseline = saved.toString(); message.setText(R.string.draft_shared);
                    });
                }
            });
        });
    }

    private void checkAvailability() {
        checkAvailability(null);
    }

    private void checkAvailability(Action after) {
        if (draft == null) return;
        JSONObject snapshot = draft;
        run(false, () -> {
            boolean[] available = new boolean[2];
            for (int i = 0; i < 2; i++) {
                try { PublicationDraftMedia.share(this, snapshot, i == 1); available[i] = true; }
                catch (Exception ignored) { available[i] = false; }
            }
            return available;
        }, available -> {
            videoReadable = available[0]; coverReadable = available[1];
            shareVideo.setEnabled(videoReadable); shareCover.setEnabled(coverReadable);
            if (!available[0]) message.setText(R.string.draft_unavailable);
            if (after != null) after.run();
        });
    }

    private void fingerprint() throws Exception {
        JSONObject snapshot = new JSONObject(draft.toString());
        Uri uri = Uri.parse(snapshot.getString("videoUri"));
        run(() -> PublicationDraftMedia.verify(this, uri, reads), video -> selectVideo(video, snapshot));
    }

    private void picker(int request, String mime) {
        try {
            pickerId = draft == null ? null : draft.getString("id");
            Intent intent = new Intent(request == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                    .setType(mime).addCategory(Intent.CATEGORY_OPENABLE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            if (request == EXPORT) intent.putExtra(Intent.EXTRA_TITLE, "publication-draft.json")
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            startActivityForResult(intent, request);
        } catch (Exception error) { error(error); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (destroyed) return;
        if (busy) {
            message.postDelayed(() -> onActivityResult(request, result, data), 100);
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            pickerId = null;
            if (draft != null) checkAvailability();
            return;
        }
        Uri uri = data.getData();
        if (request != IMPORT && request != OPEN_VIDEO && (draft == null || !draft.optString("id").equals(pickerId))) {
            error(new java.io.IOException("Draft changed while picker was open")); return;
        }
        pickerId = null;
        if (request == IMPORT) {
            run(true, () -> {
                PublicationDraftMedia.requireContent(uri);
                try (InputStream input = reads.open(this, uri)) {
                    JSONObject value = PublicationDraftStore.importManifest(input);
                    new PublicationDraftStore(this).save(value); return value;
                }
            }, this::open);
        } else if (request == EXPORT) {
            String snapshot = pendingManifest;
            run(false, () -> {
                PublicationDraftMedia.requireContent(uri);
                if (snapshot == null) throw new java.io.IOException("No manifest snapshot");
                byte[] bytes = PublicationDraftStore.encode(new JSONObject(snapshot));
                try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                    if (output == null) throw new java.io.IOException("No output stream");
                    output.write(bytes); output.flush();
                }
                try (InputStream input = reads.open(this, uri)) {
                    java.io.ByteArrayOutputStream check = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192]; int n;
                    if (input == null) throw new java.io.IOException("Cannot verify manifest");
                    while ((n = input.read(buffer)) != -1) {
                        if (n > PublicationDraftStore.MAX_BYTES - check.size()) throw new java.io.IOException("Invalid manifest readback");
                        check.write(buffer, 0, n);
                    }
                    if (!java.util.Arrays.equals(bytes, check.toByteArray())) throw new java.io.IOException("Manifest readback mismatch");
                }
                return true;
            }, ignored -> { pendingManifest = null; message.setText(R.string.draft_exported); });
        } else if (request == VIDEO || request == COVER || request == OPEN_VIDEO) {
            try {
                PublicationDraftMedia.requireContent(uri);
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                JSONObject snapshot = draft == null ? null : new JSONObject(draft.toString());
                if (request == COVER) run(true, () -> {
                    PublicationDraftMedia.validateCover(this, uri, reads);
                    snapshot.put("coverUri", uri.toString());
                    new PublicationDraftStore(this).save(snapshot, pendingOperation); return snapshot;
                }, this::open);
                else run(() -> PublicationDraftMedia.verify(this, uri, reads), video -> selectVideo(video, snapshot));
            } catch (Exception error) { error(error); }
        }
    }

    private void selectVideo(PublicationDraftMedia.VerifiedVideo video, JSONObject base) throws Exception {
        boolean mismatch = base != null && (!base.getString("mediaSha256").isEmpty()
                ? !video.matches(base) : !base.getString("videoUri").isEmpty()
                && !base.getString("videoUri").equals(video.uri.toString()));
        if (mismatch) {
            new AlertDialog.Builder(this).setMessage(R.string.draft_rebind_notice)
                    .setNegativeButton(R.string.draft_keep, null)
                    .setPositiveButton(R.string.draft_rebind, (dialog, which) ->
                            resolve(video, null, "", null, null, false)).show();
        } else resolve(video, base, "", null, null, false);
    }

    private void resolve(PublicationDraftMedia.VerifiedVideo video, JSONObject base, String exportUri,
            String credits, JSONObject selected, boolean fresh) {
        JSONObject sourceSnapshot = draft == null ? base : draft;
        run(true, () -> {
            video.checkCurrent(this, reads);
            return new PublicationDraftStore(this).resolve(video, base, exportUri, credits, selected, fresh,
                    pendingOperation, sourceSnapshot);
        }, result -> {
            if (result.value != null) {
                draft = result.value; baseline = draft.toString();
                renderForm();
                checkAvailability(() -> {
                    if (result.reused && videoReadable) message.setText(R.string.draft_reused);
                });
                return;
            }
            String[] labels = new String[result.choices.size() + (result.freshAllowed ? 1 : 0)];
            for (int i = 0; i < result.choices.size(); i++) {
                JSONObject item = result.choices.get(i);
                labels[i] = item.getString("title") + "\n" + item.getString("id")
                        + "\n" + item.getString("musicCredits");
            }
            if (result.freshAllowed) labels[labels.length - 1] = getString(R.string.draft_keep_export_evidence);
            new AlertDialog.Builder(this).setTitle(getString(R.string.draft_matches, result.choices.size()))
                    .setItems(labels, (dialog, which) -> resolve(video, base, exportUri, credits,
                            which < result.choices.size() ? result.choices.get(which) : null,
                            which == result.choices.size()))
                    .setNegativeButton(R.string.draft_keep, null).show();
        });
    }

    private TextView label(String text) {
        TextView view = new TextView(this); view.setText(text); view.setTextIsSelectable(true);
        body.addView(view); return view;
    }
    private Button button(int text, Runnable action) { return button(getString(text), action); }
    private Button button(String text, Runnable action) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false);
        button.setOnClickListener(v -> action.run()); body.addView(button); controls.add(button); return button;
    }
    private void error(Exception error) {
        message.setText(getString(R.string.draft_error) + "\n" + error.getMessage());
    }

    private void leave(Runnable action) {
        if (busy) {
            new AlertDialog.Builder(this).setMessage(R.string.draft_cancel_notice)
                    .setNegativeButton(R.string.draft_keep, null)
                    .setPositiveButton(R.string.draft_discard, (d, w) -> finish()).show();
            return;
        }
        try {
            if (draft != null && !form().toString().equals(baseline)) {
                new AlertDialog.Builder(this).setMessage(R.string.draft_dirty)
                        .setNegativeButton(R.string.draft_keep, null)
                        .setNeutralButton(R.string.draft_discard, (d, w) -> action.run())
                        .setPositiveButton(R.string.draft_save, (d, w) -> save(action::run)).show();
            } else action.run();
        } catch (Exception error) { error(error); }
    }
    @Override public void onBackPressed() { leave(this::finish); }
    @Override public boolean onSupportNavigateUp() { onBackPressed(); return true; }
    @Override public Object onRetainCustomNonConfigurationInstance() { return pendingWork; }
    @Override protected void onSaveInstanceState(Bundle state) {
        stateSaved = true;
        if (draft != null) {
            state.putString("draft", draft.toString()); state.putString("baseline", baseline);
            state.putString("manifest", pendingManifest); state.putString("picker", pickerId);
            state.putString("operation", restoreOperation != null ? restoreOperation
                    : busy && pendingMutation ? pendingOperation : "");
            for (int i = 0; i < fields.length; i++) state.putString(keys[i], fields[i].getText().toString());
            for (int i = 0; i < choices.length; i++) state.putInt("choice" + i, choices[i].getSelectedItemPosition());
        }
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed = true; ++generation;
        deferredResult = null;
        if (!isChangingConfigurations()) {
            if (job != null) job.cancel(true);
            reads.cancel();
            worker.shutdownNow();
        }
        super.onDestroy();
    }
}
