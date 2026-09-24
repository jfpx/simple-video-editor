package com.simple.videoeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Offline selection with independent foreground audition. */
final class OfflineMusicControls {
    private final Activity activity;
    private final Runnable changed;
    private final CheckBox enabled, mute;
    private final SeekBar gain;
    private OfflineMusicCatalog catalog;
    private String trackId = "", error = "";
    private boolean applying, busy;
    private final OfflineMusicAudition audition;

    OfflineMusicControls(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        enabled = activity.findViewById(R.id.cbLibraryMusic);
        mute = activity.findViewById(R.id.cbMuteOriginal);
        gain = activity.findViewById(R.id.seekBgmGain);
        audition = new OfflineMusicAudition(activity, this::refreshAudition);
        try { catalog = OfflineMusicCatalog.load(activity); }
        catch (Exception failure) { error = failure.getMessage(); }
        enabled.setOnCheckedChangeListener((view, checked) -> refresh());
        mute.setOnCheckedChangeListener((view, checked) -> refresh());
        gain.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int progress, boolean user) { refresh(); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        });
        activity.findViewById(R.id.btnLibraryTrack).setOnClickListener(view -> chooseCategory());
        activity.findViewById(R.id.btnAuditionMusic).setOnClickListener(view -> {
            if (audition.isActive()) audition.stop();
            else if (!busy && catalog != null && !trackId.isEmpty()) audition.play(catalog, trackId);
        });
        activity.findViewById(R.id.btnAuditionFullMusic).setOnClickListener(view -> {
            if (!busy && !audition.isActive() && catalog != null && !trackId.isEmpty())
                audition.play(catalog, trackId, false);
        });
        ((com.google.android.material.tabs.TabLayout) activity.findViewById(R.id.editorTabs))
                .addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
                    @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {}
                    @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {
                        if (tab.getPosition() == 1) audition.stop();
                    }
                    @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
                });
        activity.findViewById(R.id.btnClearLibraryTrack).setOnClickListener(view -> {
            apply(BackgroundMusic.OFF);
            refresh();
        });
        activity.findViewById(R.id.btnMusicLicenses).setOnClickListener(view -> {
            StringBuilder notice = new StringBuilder(activity.getString(R.string.bgm_license_notice));
            if (catalog != null) for (OfflineMusicCatalog.Track track : catalog.tracks) {
                notice.append("\n\n").append(track.notice());
            }
            new AlertDialog.Builder(activity).setTitle(R.string.bgm_licenses)
                    .setMessage(notice).setPositiveButton(android.R.string.ok, null).show();
        });
        try {
            String saved = activity.getSharedPreferences("offline_music", Context.MODE_PRIVATE)
                    .getString("selection", null);
            if (saved != null) apply(BackgroundMusic.fromRecipe(new JSONObject(saved)));
        } catch (Exception failure) { error = failure.getMessage(); }
        refresh();
    }

    boolean isEnabled() { return enabled.isChecked(); }

    android.os.Bundle save() {
        android.os.Bundle state = new android.os.Bundle();
        state.putString("trackId", trackId);
        state.putBoolean("enabled", enabled.isChecked());
        state.putBoolean("muteOriginal", mute.isChecked());
        state.putInt("gainProgress", gain.getProgress());
        return state;
    }

    void restore(android.os.Bundle state) {
        if (state == null) return;
        audition.stop();
        applying = true;
        try {
            trackId = state.getString("trackId", "");
            enabled.setChecked(state.getBoolean("enabled"));
            mute.setChecked(state.getBoolean("muteOriginal"));
            gain.setProgress(state.getInt("gainProgress", 50));
        } finally { applying = false; }
        refresh();
    }

    BackgroundMusic snapshot() {
        BackgroundMusic selection = new BackgroundMusic(enabled.isChecked(), trackId,
                (50 + gain.getProgress()) / 100f, mute.isChecked());
        if (selection.enabled) {
            try {
                if (catalog == null) throw new IllegalArgumentException(error);
                catalog.validateSelection(selection);
            } catch (Exception failure) { throw new IllegalArgumentException(failure.getMessage(), failure); }
        }
        return selection;
    }

    void apply(BackgroundMusic selection) {
        audition.stop();
        applying = true;
        try {
            trackId = selection.trackId;
            enabled.setChecked(selection.enabled);
            mute.setChecked(selection.muteOriginal);
            gain.setProgress(Math.round(selection.gain * 100) - 50);
        } finally { applying = false; }
        refresh();
    }

    void useImportedReplacement() {
        audition.stop();
        enabled.setChecked(false);
        refresh();
    }

    void setBusy(boolean value) {
        busy = value;
        if (busy && audition.isActive()) audition.stop();
        enabled.setEnabled(!busy);
        gain.setEnabled(!busy && enabled.isChecked());
        mute.setEnabled(!busy && enabled.isChecked());
        activity.findViewById(R.id.btnLibraryTrack).setEnabled(!busy);
        activity.findViewById(R.id.btnClearLibraryTrack).setEnabled(!busy && !trackId.isEmpty());
        activity.findViewById(R.id.btnMusicLicenses).setEnabled(!busy);
        refreshAudition();
    }

    void stopAudition() { audition.stop(); }
    void close() { audition.close(); }
    private void refreshAudition() {
        android.widget.Button button = activity.findViewById(R.id.btnAuditionMusic);
        button.setEnabled(!busy && catalog != null && !trackId.isEmpty());
        button.setText(audition.isActive() ? R.string.bgm_stop : R.string.bgm_play);
        activity.findViewById(R.id.btnAuditionFullMusic).setEnabled(
                button.isEnabled() && !audition.isActive());
        ((TextView) activity.findViewById(R.id.tvMusicAuditionStatus)).setText(audition.statusResource());
    }

    private void refresh() {
        if (applying) return;
        audition.setGain((50 + gain.getProgress()) / 100f);
        ((TextView) activity.findViewById(R.id.tvBgmGain)).setText(
                activity.getString(R.string.bgm_gain, 50 + gain.getProgress()));
        String label = activity.getString(R.string.bgm_pending);
        try {
            if (!trackId.isEmpty() && catalog != null) label = catalog.require(trackId).label(!UiLocales.isEnglish(activity));
            else if (catalog != null && !catalog.tracks.isEmpty()) label = activity.getString(R.string.bgm_choose);
            if (!error.isEmpty()) label = error;
        } catch (Exception failure) { label = failure.getMessage(); }
        ((TextView) activity.findViewById(R.id.tvLibraryTrack)).setText(label);
        setBusy(busy);
        // Store even the incomplete enabled/empty selection without treating it as exportable.
        try {
            JSONObject recipe = new JSONObject();
            new BackgroundMusic(false, trackId, (50 + gain.getProgress()) / 100f, mute.isChecked()).putRecipe(recipe);
            recipe.getJSONObject("bgm").put("enabled", enabled.isChecked() && !trackId.isEmpty());
            activity.getSharedPreferences("offline_music", Context.MODE_PRIVATE).edit()
                    .putString("selection", recipe.toString()).apply();
        } catch (Exception ignored) { /* Invalid state remains visible and fails export validation. */ }
        changed.run();
    }

    private void chooseCategory() {
        if (busy) return;
        audition.stop();
        if (catalog == null || catalog.tracks.isEmpty()) {
            new AlertDialog.Builder(activity).setMessage(error.isEmpty()
                    ? activity.getString(R.string.bgm_pending) : error)
                    .setPositiveButton(android.R.string.ok, null).show();
            return;
        }
        boolean chinese = !UiLocales.isEnglish(activity);
        List<String> categories = new ArrayList<>();
        for (OfflineMusicCatalog.Track track : catalog.tracks) {
            String category = chinese ? track.moodZh + " / " + track.styleZh : track.mood + " / " + track.style;
            if (!categories.contains(category)) categories.add(category);
        }
        List<String> categoryLabels = new ArrayList<>();
        for (String category : categories) {
            int count = 0;
            for (OfflineMusicCatalog.Track track : catalog.tracks) {
                String key = chinese ? track.moodZh + " / " + track.styleZh : track.mood + " / " + track.style;
                if (category.equals(key)) count++;
            }
            categoryLabels.add(category + " (" + count + ")");
        }
        new AlertDialog.Builder(activity).setTitle(R.string.bgm_categories)
                .setNeutralButton(R.string.bgm_all_tracks, (dialog, which) -> {
                    if (!busy) chooseTrack(catalog.tracks, chinese);
                })
                .setItems(categoryLabels.toArray(new String[0]), (dialog, index) -> {
                    if (busy) return;
                    List<OfflineMusicCatalog.Track> tracks = new ArrayList<>();
                    for (OfflineMusicCatalog.Track track : catalog.tracks) {
                        String category = chinese ? track.moodZh + " / " + track.styleZh : track.mood + " / " + track.style;
                        if (categories.get(index).equals(category)) tracks.add(track);
                    }
                    chooseTrack(tracks, chinese);
                }).show();
    }

    private void chooseTrack(List<OfflineMusicCatalog.Track> tracks, boolean chinese) {
        android.widget.EditText search = new android.widget.EditText(activity);
        search.setHint(R.string.bgm_search);
        search.setSingleLine(true);
        search.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(128)});
        List<OfflineMusicCatalog.Track> visible = new ArrayList<>(tracks);
        android.widget.ArrayAdapter<String> labels = new android.widget.ArrayAdapter<>(
                activity, android.R.layout.simple_list_item_1);
        for (OfflineMusicCatalog.Track track : visible) labels.add(track.label(chinese));
        android.widget.LinearLayout content = new android.widget.LinearLayout(activity);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.addView(search, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.ListView list = new android.widget.ListView(activity);
        list.setAdapter(labels);
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        int listHeight = Math.min(Math.round(360 * metrics.density), metrics.heightPixels / 2);
        content.addView(list, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, listHeight));
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(R.string.bgm_choose)
                .setView(content).create();
        list.setOnItemClickListener((parent, view, selected, itemId) -> {
                    if (busy) return;
                    audition.stop();
                    trackId = visible.get(selected).id;
                    refresh();
                    dialog.dismiss();
                });
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                visible.clear();
                labels.setNotifyOnChange(false);
                labels.clear();
                for (OfflineMusicCatalog.Track track : tracks) {
                    if (matches(track, s.toString())) {
                        visible.add(track);
                        labels.add(track.label(chinese));
                    }
                }
                labels.notifyDataSetChanged();
            }
            @Override public void afterTextChanged(android.text.Editable value) {}
        });
        dialog.setOnShowListener(d -> dialog.getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                        | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE));
        dialog.show();
    }

    static boolean matches(OfflineMusicCatalog.Track track, String query) {
        String metadata = (track.title + " " + track.titleZh + " " + track.mood + " " + track.moodZh
                + " " + track.style + " " + track.styleZh + " " + track.author).toLowerCase(Locale.ROOT);
        for (String word : query.trim().toLowerCase(Locale.ROOT).split("\\s+")) {
            if (!metadata.contains(word)) return false;
        }
        return true;
    }
}
