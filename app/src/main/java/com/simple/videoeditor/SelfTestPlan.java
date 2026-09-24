package com.simple.videoeditor;

import com.simple.videoeditor.oracle.IntroOracleContract;
import com.simple.videoeditor.oracle.TextOracleContract;
import com.simple.videoeditor.oracle.TitleOracleContract;
import com.simple.videoeditor.oracle.WatermarkOracleContract;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Two fixed inventories, not a configurable job scheduler. */
final class SelfTestPlan {
    enum Mode { FULL, SHORT_DIAGNOSTIC }

    static final List<String> FULL_CASES = Collections.unmodifiableList(Arrays.asList(
            "identity", "crop", "rotate90", "rotate180", "rotate270", "trim",
            "resize", "mute", "volume25", "speed2", "speed_half", "combo",
            "music_loop", IntroOracleContract.CASE_ID, TextOracleContract.CASE_ID,
            TitleOracleContract.CASE_ID, WatermarkOracleContract.CASE_ID));
    static final List<String> SHORT_CASES = Collections.unmodifiableList(Arrays.asList(
            "identity", "speed2", "music_loop"));
    static final List<String> SHORT_CONTROLS = Collections.unmodifiableList(Arrays.asList(
            "positive_reference_identity", "positive_reference_speed2",
            "negative_unchanged_source_as_speed2", "negative_speed_changes_pitch",
            "negative_frozen_video_correct_duration", "negative_frozen_markers_advancing_barcode",
            "negative_audio_timestamp_gap", "music_reference", "music_unchanged", "music_no_loop"));
    private static final List<String> FULL_CONTROLS = fullControls();
    static final String SHORT_NOTICE =
            "短诊断 / SHORT_DIAGNOSTIC：仅 identity、speed2、music_loop；3 个导出、10 个对照（3 正 / 7 负）。\n"
            + "有限覆盖 / LIMITED COVERAGE：其余导出和对照未运行，不能代表完整套件通过。\n"
            + "用于收集颜色与尾帧证据，不代表已修复 Samsung；601/709 原因仍未证实。\n"
            + "素材、阈值、超时不变；不是固定时长快速测试，手机 identity 曾约 139 秒。\n";

    final Mode mode;
    final List<String> cases;
    final List<String> controls;

    SelfTestPlan(Mode mode) {
        if (mode == null) throw new IllegalArgumentException("Diagnostic mode is required");
        this.mode = mode;
        cases = mode == Mode.FULL ? FULL_CASES : SHORT_CASES;
        controls = mode == Mode.FULL ? FULL_CONTROLS : SHORT_CONTROLS;
    }

    boolean isShort() { return mode == Mode.SHORT_DIAGNOSTIC; }

    String successStatus() { return isShort() ? "SUBSET_PASS" : "PASS"; }

    JSONObject metadata() throws JSONException {
        List<String> omittedCases = new ArrayList<>(FULL_CASES);
        omittedCases.removeAll(cases);
        List<String> omittedControls = new ArrayList<>(FULL_CONTROLS);
        omittedControls.removeAll(controls);
        int positives = 0;
        for (String id : controls) if (positive(id)) positives++;
        return new JSONObject().put("mode", mode.name())
                .put("planned_cases", new JSONArray(cases))
                .put("planned_controls", new JSONArray(controls))
                .put("planned_export_count", cases.size())
                .put("planned_control_count", controls.size())
                .put("planned_positive_control_count", positives)
                .put("planned_negative_control_count", controls.size() - positives)
                .put("coverage", isShort() ? "LIMITED_SUBSET" : "FULL_FROZEN_SUITE")
                .put("omitted_cases", new JSONArray(omittedCases))
                .put("omitted_controls", new JSONArray(omittedControls))
                .put("full_suite_export_count", FULL_CASES.size())
                .put("full_suite_control_count", FULL_CONTROLS.size());
    }

    String description() {
        return (isShort() ? SHORT_NOTICE : "完整诊断 / FULL：固定完整套件；不是全部功能覆盖。\n")
                + "MODE " + mode + "\nPLANNED CASES (" + cases.size() + "): " + cases
                + "\nPLANNED CONTROLS (" + controls.size() + "): " + controls + "\n";
    }

    static boolean positive(String id) {
        return id.startsWith("positive_") || id.endsWith("_reference") || id.equals("reference_imported_intro");
    }

    private static List<String> fullControls() {
        List<String> controls = new ArrayList<>();
        for (String id : FULL_CASES.subList(0, 12)) controls.add("positive_reference_" + id);
        controls.addAll(Arrays.asList("positive_standard", "positive_stress_rotate90_crf28",
                "positive_stress_combo_crf28", "positive_stress_speed2_cfr24",
                "positive_stress_speed_half_cfr24"));
        for (String id : FULL_CASES.subList(1, 12)) controls.add("negative_unchanged_source_as_" + id);
        controls.addAll(Arrays.asList("negative_wrong_crop_same_dimensions",
                "negative_wrong_rotation_same_dimensions", "negative_wrong_trim_same_duration",
                "negative_wrong_volume_half_not_quarter", "negative_speed_changes_pitch",
                "negative_frozen_video_correct_duration", "negative_frozen_markers_advancing_barcode",
                "negative_audio_timestamp_gap", "music_reference", "music_unchanged", "music_no_loop"));
        for (IntroOracleContract.Control c : IntroOracleContract.CONTROLS) controls.add(c.id);
        for (TextOracleContract.Control c : TextOracleContract.CONTROLS) controls.add(c.id);
        for (TitleOracleContract.Control c : TitleOracleContract.CONTROLS) controls.add(c.id);
        for (WatermarkOracleContract.Control c : WatermarkOracleContract.CONTROLS) controls.add(c.id);
        return Collections.unmodifiableList(controls);
    }
}
