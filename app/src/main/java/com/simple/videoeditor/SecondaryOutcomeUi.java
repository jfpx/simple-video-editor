package com.simple.videoeditor;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

/** Read-only presentation of structured results; never infers success from report text. */
final class SecondaryOutcomeUi {
    private SecondaryOutcomeUi() {}

    static String headline(Context context, SuiteOutcome outcome) {
        int label;
        switch (outcome.state()) {
            case "ALL_PASS": label = R.string.secondary_all_pass; break;
            case "SUBSET_PASS": label = R.string.secondary_subset_pass; break;
            case "RUNNING": label = R.string.secondary_running; break;
            case "FINALIZING": label = R.string.secondary_finalizing; break;
            default: label = R.string.secondary_not_passed;
        }
        return context.getString(label);
    }

    static String details(Context context, SuiteOutcome outcome) {
        try {
            JSONObject value = outcome.toJson();
            StringBuilder text = new StringBuilder(context.getString(
                    R.string.secondary_result_mode, value.getString("mode")));
            text.append('\n').append(counts(context, R.string.secondary_export_counts,
                    value.getJSONObject("exports")));
            text.append('\n').append(counts(context, R.string.secondary_control_counts,
                    value.getJSONObject("controls")));
            int exports = value.getInt("omitted_exports"), controls = value.getInt("omitted_controls");
            if (exports > 0 || controls > 0) {
                text.append('\n').append(context.getString(
                        R.string.secondary_omitted_counts, exports, controls));
            }
            text.append('\n').append(context.getString(guidance(outcome.state())));
            // Reasons can contain raw validation errors and protocol identifiers.
            text.append('\n').append(context.getString(
                    R.string.secondary_diagnostic_reason, value.getString("reason")));
            return text.toString();
        } catch (JSONException error) {
            return context.getString(R.string.secondary_result_unreadable, error.toString());
        }
    }

    private static String counts(Context context, int resource, JSONObject counts) throws JSONException {
        return context.getString(resource, counts.getInt("passed"), counts.getInt("total"),
                counts.getInt("failed"), counts.getInt("errors"), counts.getInt("unrun"));
    }

    private static int guidance(String state) {
        switch (state) {
            case "ALL_PASS": return R.string.secondary_guidance_pass;
            case "SUBSET_PASS": return R.string.secondary_guidance_subset;
            case "RUNNING": return R.string.secondary_guidance_running;
            case "FINALIZING": return R.string.secondary_guidance_finalizing;
            case "CANCELLED": return R.string.secondary_guidance_cancelled;
            case "INTERRUPTED": return R.string.secondary_guidance_interrupted;
            case "STORAGE_ERROR": return R.string.secondary_guidance_storage;
            case "MISSING": return R.string.secondary_guidance_missing;
            case "STALE": return R.string.secondary_guidance_stale;
            case "MALFORMED":
            case "INCONSISTENT": return R.string.secondary_guidance_invalid;
            case "ERROR": return R.string.secondary_guidance_error;
            default: return R.string.secondary_guidance_failed;
        }
    }

    static String savedLocation(Context context) {
        SharedPreferences preferences = SavedReport.preferences(context);
        String destination = preferences.getString("destination",
                context.getString(R.string.secondary_no_destination));
        String state = preferences.getString("state", "");
        if (state.equals("PREPARING / 准备报告")) {
            state = context.getString(R.string.secondary_destination_preparing);
        } else if (state.equals("TERMINAL / 已结束，查看结果")) {
            state = context.getString(R.string.secondary_destination_terminal);
        } else if (state.equals("RUNNING / 重启后表示上次中断，未验证")) {
            state = context.getString(R.string.secondary_destination_running);
        } else if (state.isEmpty()) {
            state = context.getString(R.string.secondary_destination_local);
        }
        return destination + "\n" + state + "\n" + preferences.getString("error", "");
    }
}
