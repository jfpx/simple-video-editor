package com.simple.videoeditor;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Color;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/** Reuses the border palette, with bounded ARGB channels for arbitrary saved colors. */
final class TitleColorPicker {
    interface ColorSelected { void accept(int color); }

    static void show(Activity activity, int label, int original, ColorSelected selected) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        TextView swatch = new TextView(activity);
        swatch.setMinHeight(padding * 3);
        content.addView(swatch);
        int[] channels = {Color.alpha(original), Color.red(original), Color.green(original), Color.blue(original)};
        SeekBar[] bars = new SeekBar[4];
        String[] names = activity.getResources().getStringArray(R.array.title_color_channels);
        TextView[] values = new TextView[4];
        Runnable refresh = () -> {
            int color = Color.argb(channels[0], channels[1], channels[2], channels[3]);
            swatch.setBackgroundColor(color);
            swatch.setText(String.format(java.util.Locale.ROOT, "#%08X", color));
            swatch.setTextColor(channels[1] + channels[2] + channels[3] > 380 ? Color.BLACK : Color.WHITE);
            for (int i = 0; i < 4; i++) if (values[i] != null)
                values[i].setText(names[i] + ": " + channels[i] + " / 255");
        };
        for (int i = 0; i < 4; i++) {
            final int channel = i;
            values[i] = new TextView(activity);
            content.addView(values[i]);
            bars[i] = new SeekBar(activity);
            bars[i].setContentDescription(names[i]);
            bars[i].setMax(255);
            bars[i].setProgress(channels[i]);
            bars[i].setMinimumHeight(padding * 3);
            bars[i].setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar bar, int value, boolean user) {
                    channels[channel] = value;
                    refresh.run();
                }
                public void onStartTrackingTouch(SeekBar bar) {}
                public void onStopTrackingTouch(SeekBar bar) {}
            });
            content.addView(bars[i]);
        }
        String[] palette = activity.getResources().getStringArray(R.array.editor_border_colors);
        for (int row = 0; row < 2; row++) {
            LinearLayout buttons = new LinearLayout(activity);
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                Button button = new Button(activity);
                button.setText(palette[index]);
                button.setOnClickListener(v -> {
                    int color = new VideoBorder(true, index, 1, VideoBorder.Scope.TITLE).color();
                    bars[0].setProgress(255);
                    bars[1].setProgress(Color.red(color));
                    bars[2].setProgress(Color.green(color));
                    bars[3].setProgress(Color.blue(color));
                });
                buttons.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
            }
            content.addView(buttons);
        }
        refresh.run();
        new AlertDialog.Builder(activity).setTitle(label).setView(content)
                .setPositiveButton(R.string.editor_save, (dialog, which) ->
                        selected.accept(Color.argb(channels[0], channels[1], channels[2], channels[3])))
                .setNegativeButton(R.string.editor_cancel, null).show();
    }
}
