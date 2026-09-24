package com.simple.videoeditor;

import android.app.Activity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

final class TitleAppearanceControls {
    interface TemplateSource { IntroTemplate get(); }

    private final Activity activity;
    private final TemplateSource current;
    private final Runnable changed;
    private boolean syncing;

    TitleAppearanceControls(Activity activity, TemplateSource current, Runnable changed) {
        this.activity = activity;
        this.current = current;
        this.changed = changed;
        bind(R.id.btnTitleTextColor, R.string.title_text_color, 0);
        bind(R.id.btnTitleBackgroundColor, R.string.title_background_color, 1);
        bind(R.id.btnTitleGradientColor, R.string.title_gradient_color, 2);
        ((CheckBox) activity.findViewById(R.id.cbTitleGradient)).setOnCheckedChangeListener((button, enabled) -> {
            IntroTemplate title = current.get();
            if (syncing || title == null) return;
            if (enabled) title.setGradientColor(title.getBackgroundColor());
            else title.clearGradient();
            sync();
            changed.run();
        });
        Spinner layout = activity.findViewById(R.id.spinnerTitleLayout);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(activity,
                R.array.title_layouts, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        layout.setAdapter(adapter);
        layout.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                if (syncing || current.get() == null || Integer.valueOf(position).equals(layout.getTag())
                        || position != layout.getSelectedItemPosition()) return;
                layout.setTag(position);
                current.get().setLayout(position == 0 ? "canvas" : "legacy-sp");
                sync();
                changed.run();
            }
        });
    }

    private void bind(int id, int label, int channel) {
        activity.findViewById(id).setOnClickListener(view -> {
            IntroTemplate title = current.get();
            if (title == null) return;
            int value = channel == 0 ? title.getTextColor()
                    : channel == 1 ? title.getBackgroundColor() : title.getGradientColor();
            TitleColorPicker.show(activity, label, value, color -> {
                if (current.get() != title || activity.isFinishing()) return;
                if (channel == 0) title.setTextColor(color);
                else if (channel == 1) title.setBackgroundColor(color);
                else title.setGradientColor(color);
                Spinner palette = activity.findViewById(R.id.spinnerTitlePalette);
                palette.setTag(0);
                palette.setSelection(0);
                sync();
                changed.run();
            });
        });
    }

    void sync() {
        IntroTemplate title = current.get();
        if (title == null) return;
        syncing = true;
        title.setLayout(title.getLayout());
        Spinner layout = activity.findViewById(R.id.spinnerTitleLayout);
        int position = title.getLayout().equals("canvas") ? 0 : 1;
        layout.setTag(position);
        layout.setSelection(position);
        ((TextView) activity.findViewById(R.id.tvTitleSizeUnit)).setText(position == 0
                ? R.string.title_size_canvas : R.string.title_size_legacy);
        ((CheckBox) activity.findViewById(R.id.cbTitleGradient)).setChecked(title.hasGradient());
        label(R.id.btnTitleTextColor, R.string.title_text_color, title.getTextColor());
        label(R.id.btnTitleBackgroundColor, R.string.title_background_color, title.getBackgroundColor());
        label(R.id.btnTitleGradientColor, R.string.title_gradient_color, title.getGradientColor());
        syncing = false;
    }

    private void label(int id, int label, int color) {
        ((Button) activity.findViewById(id)).setText(activity.getString(label)
                + String.format(java.util.Locale.ROOT, " #%08X", color));
    }
}
