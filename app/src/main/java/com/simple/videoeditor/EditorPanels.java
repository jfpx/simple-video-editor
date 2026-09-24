package com.simple.videoeditor;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.LinearLayout;
import java.util.IdentityHashMap;
import com.google.android.material.tabs.TabLayout;

/** Owns only view state, never media or export configuration. */
final class EditorPanels {
    private final Activity activity;
    private final ScrollView[] panels = new ScrollView[5];
    private final int[] focus = new int[5], scroll = new int[5];
    private final TabLayout tabs;
    private int selected;
    private boolean restoring;
    private final IdentityHashMap<View, LinearLayout.LayoutParams> originalParams = new IdentityHashMap<>();
    private final IdentityHashMap<EditText, Bundle> disabledSelections = new IdentityHashMap<>();

    EditorPanels(Activity activity, Runnable refresh) {
        this.activity = activity;
        int[] ids = {R.id.panelPicture, R.id.panelSound, R.id.panelTitle,
                R.id.panelAssets, R.id.panelMore};
        for (int i = 0; i < ids.length; i++) {
            panels[i] = activity.findViewById(ids[i]);
            panels[i].setSmoothScrollingEnabled(false);
        }
        tabs = activity.findViewById(R.id.editorTabs);
        tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                if (!restoring) rememberFocus();
                selected = tab.getPosition();
                for (int i = 0; i < panels.length; i++)
                    panels[i].setVisibility(i == selected ? View.VISIBLE : View.GONE);
                if (!restoring) {
                    restoreFocus();
                    restoreScrollAfterLayout();
                    refresh.run();
                }
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) { if (!restoring) refresh.run(); }
        });
        View root = activity.findViewById(R.id.editorRoot);
        disableFullscreenIme(root);
        root.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (b - t == ob - ot && r - l == or - ol) return;
            // adjustResize already removes the IME height. Do not apply it twice as padding.
            float density = activity.getResources().getDisplayMetrics().density;
            int available = Math.round((b - t) / density);
            android.widget.TextView geometry = activity.findViewById(R.id.tvGeometryStatus);
            geometry.setMaxLines(available < 450 ? 1 : 2);
            compactLayout(available, density);
        });
    }

    private void compactLayout(int available, float density) {
        boolean compact = available < 360;
        int rowHeight = Math.round((available < 260 ? 32 : 40) * density);
        LinearLayout header = activity.findViewById(R.id.editorPreviewHeader);
        LinearLayout picker = (LinearLayout) header.getChildAt(0);
        LinearLayout dock = activity.findViewById(R.id.editorActionDock);
        header.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        picker.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        dock.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        dock.setGravity(android.view.Gravity.CENTER_VERTICAL);
        resize(picker, compact, Math.round(140 * density), ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        resize(picker.getChildAt(0), compact, ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, 0);
        resize(picker.getChildAt(1), compact, ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, 0);
        View image = activity.findViewById(R.id.ivVideoThumbnail);
        resize(image, compact, 0, rowHeight + Math.round(16 * density), 1);
        if (!compact) {
            int height = Math.round(Math.max(48, Math.min(160, (available - 220) * .32f)) * density);
            if (image.getLayoutParams().height != height) {
                image.getLayoutParams().height = height;
                image.requestLayout();
            }
        }
        // The full static-frame status remains the image's accessibility description.
        header.getChildAt(2).setVisibility(compact ? View.GONE : View.VISIBLE);
        resize(header.getChildAt(3), compact, 0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ((android.widget.Button) activity.findViewById(R.id.btnProcess)).setText(
                compact ? R.string.editor_process_short : R.string.process_video);
        resize(tabs, compact, ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, 0);
        for (int i = 0; i < dock.getChildCount(); i++) {
            View child = dock.getChildAt(i);
            boolean button = child.getId() == R.id.btnProcess;
            boolean bar = child.getId() == R.id.progressBar;
            resize(child, compact, button ? Math.round(88 * density) : bar ? Math.round(4 * density) : 0,
                    button ? rowHeight : ViewGroup.LayoutParams.WRAP_CONTENT, button || bar ? 0 : 1);
        }
        for (int id : new int[]{R.id.btnCancelExport, R.id.btnErrorDetails}) {
            View button = activity.findViewById(id);
            resize(button, compact, ViewGroup.LayoutParams.WRAP_CONTENT, rowHeight, 0);
        }
        View actions = (View) activity.findViewById(R.id.btnShareVideo).getParent();
        resize(actions, compact, ViewGroup.LayoutParams.MATCH_PARENT, rowHeight, 0);
    }

    private void resize(View view, boolean compact, int width, int height, float weight) {
        LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
        if (!originalParams.containsKey(view)) originalParams.put(view, new LinearLayout.LayoutParams(params));
        LinearLayout.LayoutParams target = compact ? new LinearLayout.LayoutParams(params)
                : new LinearLayout.LayoutParams(originalParams.get(view));
        if (compact) { target.width = width; target.height = height; target.weight = weight; }
        if (params.width != target.width || params.height != target.height || params.weight != target.weight)
            view.setLayoutParams(target);
    }

    private static void disableFullscreenIme(View view) {
        if (view instanceof EditText) {
            EditText text = (EditText) view;
            text.setImeOptions(text.getImeOptions() | android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) disableFullscreenIme(group.getChildAt(i));
        }
    }

    private void rememberFocus() {
        scroll[selected] = panels[selected].getScrollY();
        View view = panels[selected].findFocus();
        if (view instanceof EditText) focus[selected] = view.getId();
    }

    private void restoreFocus() {
        View view = activity.findViewById(focus[selected]);
        if (view instanceof EditText && view.isEnabled() && !view.hasFocus()) {
            EditText text = (EditText) view;
            int start = text.getSelectionStart(), end = text.getSelectionEnd();
            text.requestFocus();
            text.setSelection(start, end);
        }
    }

    void setEnabled(View view, boolean enabled) {
        if (view.isEnabled() == enabled) return;
        if (view instanceof EditText) {
            EditText text = (EditText) view;
            int start = text.getSelectionStart(), end = text.getSelectionEnd();
            if (!enabled && text.isEnabled()) {
                Bundle selection = new Bundle();
                selection.putString("text", text.getText().toString());
                selection.putInt("start", start);
                selection.putInt("end", end);
                disabledSelections.put(text, selection);
            } else if (enabled && !text.isEnabled()) {
                Bundle selection = disabledSelections.remove(text);
                if (selection != null && text.getText().toString().equals(selection.getString("text"))) {
                    start = selection.getInt("start");
                    end = selection.getInt("end");
                }
            }
            text.setEnabled(enabled);
            text.setSelection(start, end);
        } else view.setEnabled(enabled);
    }

    void restoreFocusAndScroll() {
        restoreFocus();
        restoreScrollAfterLayout();
    }

    private void restoreScrollAfterLayout() {
        final int index = selected;
        final int position = scroll[index];
        ScrollView panel = panels[index];
        // ScrollView.onLayout may scroll to its focused child. Restore the user's
        // viewport afterwards, when a previously GONE panel has real dimensions.
        panel.getViewTreeObserver().addOnPreDrawListener(new android.view.ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                panel.getViewTreeObserver().removeOnPreDrawListener(this);
                // EditText also brings its cursor into view during pre-draw.
                panel.post(() -> {
                    if (selected == index) {
                        // A second immediate smooth-scroll request cancels ScrollView's
                        // pending focus animation; scrollTo alone leaves it running.
                        panel.smoothScrollTo(0, position);
                        panel.smoothScrollTo(0, position);
                    }
                });
                return true;
            }
        });
        panel.invalidate();
    }

    void clearTitleFocus() {
        focus[2] = 0;
    }

    Bundle save() {
        rememberFocus();
        Bundle state = new Bundle();
        state.putInt("tab", selected);
        state.putIntArray("focus", focus.clone());
        state.putIntArray("scroll", scroll.clone());
        saveWidgets(activity.findViewById(R.id.editorRoot), state);
        return state;
    }

    void restore(Bundle state) {
        if (state == null) return;
        restoring = true;
        disabledSelections.clear();
        restoreWidgets(activity.findViewById(R.id.editorRoot), state);
        int[] savedFocus = state.getIntArray("focus"), savedScroll = state.getIntArray("scroll");
        if (savedFocus != null) System.arraycopy(savedFocus, 0, focus, 0, Math.min(5, savedFocus.length));
        if (savedScroll != null) System.arraycopy(savedScroll, 0, scroll, 0, Math.min(5, savedScroll.length));
        selected = Math.max(0, Math.min(4, state.getInt("tab")));
        tabs.selectTab(tabs.getTabAt(selected));
        for (int i = 0; i < panels.length; i++)
            panels[i].setVisibility(i == selected ? View.VISIBLE : View.GONE);
        restoring = false;
        panels[selected].post(() -> {
            restoreFocusAndScroll();
        });
    }

    private void saveWidgets(View view, Bundle state) {
        String key = Integer.toString(view.getId());
        if (view instanceof EditText) {
            EditText text = (EditText) view;
            state.putString(key + "text", text.getText().toString());
            Bundle selection = disabledSelections.get(text);
            boolean retained = !text.isEnabled() && selection != null
                    && text.getText().toString().equals(selection.getString("text"));
            state.putInt(key + "start", retained ? selection.getInt("start") : text.getSelectionStart());
            state.putInt(key + "end", retained ? selection.getInt("end") : text.getSelectionEnd());
        } else if (view instanceof CheckBox) {
            state.putBoolean(key + "check", ((CheckBox) view).isChecked());
        } else if (view instanceof Spinner) {
            state.putInt(key + "option", ((Spinner) view).getSelectedItemPosition());
        } else if (view instanceof SeekBar) {
            state.putInt(key + "time", ((SeekBar) view).getProgress());
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) saveWidgets(group.getChildAt(i), state);
        }
    }

    private void restoreWidgets(View view, Bundle state) {
        String key = Integer.toString(view.getId());
        if (view instanceof EditText && state.containsKey(key + "text")) {
            EditText text = (EditText) view;
            text.setText(state.getString(key + "text"));
            int length = text.length();
            text.setSelection(Math.max(0, Math.min(length, state.getInt(key + "start"))),
                    Math.max(0, Math.min(length, state.getInt(key + "end"))));
            if (!text.isEnabled()) {
                Bundle selection = new Bundle();
                selection.putString("text", text.getText().toString());
                selection.putInt("start", text.getSelectionStart());
                selection.putInt("end", text.getSelectionEnd());
                disabledSelections.put(text, selection);
            }
        } else if (view instanceof CheckBox && state.containsKey(key + "check")) {
            ((CheckBox) view).setChecked(state.getBoolean(key + "check"));
        } else if (view instanceof Spinner && state.containsKey(key + "option")) {
            Spinner spinner = (Spinner) view;
            int option = state.getInt(key + "option");
            if (option >= 0 && option < spinner.getCount()) spinner.setSelection(option);
        } else if (view instanceof SeekBar && state.containsKey(key + "time")) {
            ((SeekBar) view).setProgress(state.getInt(key + "time"));
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) restoreWidgets(group.getChildAt(i), state);
        }
    }
}
