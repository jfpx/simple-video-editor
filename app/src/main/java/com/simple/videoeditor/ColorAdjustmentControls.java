package com.simple.videoeditor;

import android.app.Activity;
import android.widget.CheckBox;
import android.widget.SeekBar;
import android.widget.TextView;

final class ColorAdjustmentControls {
    private final Activity activity;
    private final CheckBox enabled;
    private final SeekBar brightness, contrast, saturation;
    private final Runnable changed;
    private boolean applying, busy;

    ColorAdjustmentControls(Activity activity, Runnable changed) {
        this.activity = activity;
        this.changed = changed;
        enabled = activity.findViewById(R.id.cbEnableColor);
        brightness = activity.findViewById(R.id.colorBrightness);
        contrast = activity.findViewById(R.id.colorContrast);
        saturation = activity.findViewById(R.id.colorSaturation);
        enabled.setOnCheckedChangeListener((button, checked) -> notifyChanged());
        SeekBar.OnSeekBarChangeListener listener = new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar bar, int value, boolean fromUser) { notifyChanged(); }
            @Override public void onStartTrackingTouch(SeekBar bar) {}
            @Override public void onStopTrackingTouch(SeekBar bar) {}
        };
        brightness.setOnSeekBarChangeListener(listener);
        contrast.setOnSeekBarChangeListener(listener);
        saturation.setOnSeekBarChangeListener(listener);
        activity.findViewById(R.id.btnResetColor).setOnClickListener(v ->
                apply(new ColorAdjustment(enabled.isChecked(), 0, 100, 100)));
        refresh();
    }

    ColorAdjustment snapshot() {
        return new ColorAdjustment(enabled.isChecked(), brightness.getProgress() - 100,
                contrast.getProgress(), saturation.getProgress());
    }

    void apply(ColorAdjustment value) {
        applying = true;
        try {
            brightness.setProgress(value.brightness + 100);
            contrast.setProgress(value.contrast);
            saturation.setProgress(value.saturation);
            enabled.setChecked(value.enabled);
        } finally { applying = false; }
        notifyChanged();
    }

    void setBusy(boolean busy) { this.busy = busy; refresh(); }

    private void notifyChanged() {
        refresh();
        if (!applying) changed.run();
    }

    private void refresh() {
        label(R.id.colorBrightnessLabel, R.string.editor_color_brightness, brightness.getProgress() - 100);
        label(R.id.colorContrastLabel, R.string.editor_color_contrast, contrast.getProgress());
        label(R.id.colorSaturationLabel, R.string.editor_color_saturation, saturation.getProgress());
        enabled.setEnabled(!busy);
        for (SeekBar bar : new SeekBar[]{brightness, contrast, saturation})
            bar.setEnabled(!busy && enabled.isChecked());
        activity.findViewById(R.id.btnResetColor).setEnabled(!busy);
    }

    private void label(int id, int resource, int value) {
        ((TextView) activity.findViewById(id)).setText(activity.getString(resource, value));
    }
}
