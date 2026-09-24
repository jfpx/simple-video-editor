package com.simple.videoeditor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable cached suite outcome suitable for UI use without disk I/O. */
final class SuiteOutcome {
    static final String EVIDENCE_SCHEMA = "phone-video-oracle-suite-v1";
    private static final String SCHEMA = "suite-outcome-v1";

    private final String state;
    private final String headline;
    private final String details;
    private final boolean success;
    private final String mode;
    private final int exportsPassed;
    private final int exportsFailed;
    private final int exportsErrors;
    private final int exportsUnrun;
    private final int exportsTotal;
    private final int controlsPassed;
    private final int controlsFailed;
    private final int controlsErrors;
    private final int controlsUnrun;
    private final int controlsTotal;
    private final int omittedExports;
    private final int omittedControls;
    private final String reason;
    private final String nextStep;

    private SuiteOutcome(String state, String headline, boolean success, String mode,
            Counts exports, Counts controls, int omittedExports, int omittedControls,
            String reason, String nextStep) {
        this.state = state;
        this.headline = headline;
        this.success = success;
        this.mode = mode;
        this.exportsPassed = exports.passed;
        this.exportsFailed = exports.failed;
        this.exportsErrors = exports.errors;
        this.exportsUnrun = exports.unrun;
        this.exportsTotal = exports.total;
        this.controlsPassed = controls.passed;
        this.controlsFailed = controls.failed;
        this.controlsErrors = controls.errors;
        this.controlsUnrun = controls.unrun;
        this.controlsTotal = controls.total;
        this.omittedExports = omittedExports;
        this.omittedControls = omittedControls;
        this.reason = reason;
        this.nextStep = nextStep;
        StringBuilder text = new StringBuilder("模式：").append(mode)
                .append("\n导出：").append(exportsPassed).append('/').append(exportsTotal)
                .append(" 通过，").append(exportsFailed).append(" 失败，")
                .append(exportsErrors).append(" 错误，").append(exportsUnrun).append(" 未运行")
                .append("\n对照：").append(controlsPassed).append('/').append(controlsTotal)
                .append(" 通过，").append(controlsFailed).append(" 失败，")
                .append(controlsErrors).append(" 错误，").append(controlsUnrun).append(" 未运行");
        if (omittedExports > 0 || omittedControls > 0) {
            text.append("\n未覆盖：").append(omittedExports).append(" 导出，")
                    .append(omittedControls).append(" 对照");
        }
        text.append("\n原因：").append(reason)
                .append("\n下一步：").append(nextStep);
        details = text.toString();
    }

    String headline() { return headline; }
    String details() { return details; }
    boolean isSuccess() { return success; }
    String state() { return state; }
    SuiteOutcome cancelled() {
        return manual("CANCELLED", "未通过", false, mode,
                new Counts(exportsPassed, exportsFailed, exportsErrors, exportsUnrun, exportsTotal),
                new Counts(controlsPassed, controlsFailed, controlsErrors, controlsUnrun, controlsTotal),
                omittedExports, omittedControls,
                "套件已取消；当前结果不完整。",
                "重新运行所选模式以获得权威结果。");
    }

    JSONObject toJson() throws JSONException {
        return new JSONObject().put("schema", SCHEMA).put("state", state)
                .put("headline", headline).put("success", success).put("mode", mode)
                .put("reason", reason).put("next_step", nextStep)
                .put("omitted_exports", omittedExports).put("omitted_controls", omittedControls)
                .put("exports", countsJson(exportsPassed, exportsFailed, exportsErrors, exportsUnrun, exportsTotal))
                .put("controls", countsJson(controlsPassed, controlsFailed, controlsErrors, controlsUnrun, controlsTotal));
    }

    static SuiteOutcome running(SelfTestPlan plan) {
        return create("RUNNING", "运行中", false, plan, Counts.none(plan.cases.size()),
                Counts.none(plan.controls.size()), "套件正在运行；完成前不能判定通过。",
                "等待结束；如中断或取消，请重新运行所选模式。");
    }

    static SuiteOutcome cancelled(SelfTestPlan plan) {
        return create("CANCELLED", "未通过", false, plan, Counts.none(plan.cases.size()),
                Counts.none(plan.controls.size()), "套件已取消；当前结果不完整。",
                "重新运行所选模式以获得权威结果。");
    }

    static SuiteOutcome finalizing(SelfTestPlan plan, JSONArray exports, JSONArray controls)
            throws JSONException {
        SnapshotSummary exportSummary = snapshotSummary(exports, plan.cases, false);
        SnapshotSummary controlSummary = snapshotSummary(controls, plan.controls, true);
        return create("FINALIZING", "正在完成", false, plan,
                exportSummary.counts, controlSummary.counts,
                "正在提交最终报告和 suite.json；完成前不能判定通过。",
                "等待最终保存完成；此阶段不再接受取消。");
    }

    static SuiteOutcome missing(String reason) {
        return manual("MISSING", "未通过", false, "UNKNOWN", Counts.zero(), Counts.zero(),
                0, 0, reason, "重新运行所选模式以生成新的权威 suite.json。");
    }

    static SuiteOutcome stale(String reason) {
        return manual("STALE", "未通过", false, "UNKNOWN", Counts.zero(), Counts.zero(),
                0, 0, reason, "不要复用较旧结果；请重新运行最新所选模式。");
    }

    static SuiteOutcome storageError(String mode, Counts exports, Counts controls, int omittedExports,
            int omittedControls, String reason) {
        return manual("STORAGE_ERROR", "未通过", false, mode, exports, controls,
                omittedExports, omittedControls, reason, "先修复存储问题，再重新运行所选模式。");
    }

    static SuiteOutcome storageError(SelfTestPlan plan, JSONArray exports, JSONArray controls, String reason)
            throws JSONException {
        SnapshotSummary exportSummary = snapshotSummary(exports, plan.cases, false);
        SnapshotSummary controlSummary = snapshotSummary(controls, plan.controls, true);
        int omittedExports = plan.isShort() ? SelfTestPlan.FULL_CASES.size() - plan.cases.size() : 0;
        int omittedControls = plan.isShort() ? fullControlCount() - plan.controls.size() : 0;
        return storageError(plan.mode.name(), exportSummary.counts, controlSummary.counts, omittedExports,
                omittedControls, reason);
    }

    static SuiteOutcome malformed(String reason) {
        return manual("MALFORMED", "未通过", false, "UNKNOWN", Counts.zero(), Counts.zero(),
                0, 0, reason, "丢弃该损坏结果并重新运行。");
    }

    static SuiteOutcome fromSnapshot(SelfTestPlan plan, String status, boolean complete,
            boolean fixtureVerified, JSONArray exports, JSONArray controls, boolean cancelled,
            boolean timedOut, boolean hasFatal, boolean storageError) throws JSONException {
        SnapshotSummary exportSummary = snapshotSummary(exports, plan.cases, false);
        SnapshotSummary controlSummary = snapshotSummary(controls, plan.controls, true);
        String fullSuiteStatus = plan.isShort() ? "NOT_RUN" : status;
        return resolved(plan, status, complete, fixtureVerified, cancelled, timedOut,
                hasFatal, storageError, fullSuiteStatus, exportSummary.counts,
                controlSummary.counts, false,
                exportSummary.inventoryValid && controlSummary.inventoryValid);
    }

    static SuiteOutcome fromEvidence(JSONObject evidence) throws JSONException {
        if (evidence == null) return malformed("suite.json 内容缺失。");
        try {
            if (!EVIDENCE_SCHEMA.equals(strictString(evidence, "schema"))) {
                return malformed("suite.json schema 缺失或非法。");
            }
            String modeName = strictString(evidence, "mode");
            SelfTestPlan.Mode mode = SelfTestPlan.Mode.valueOf(modeName);
            SelfTestPlan plan = new SelfTestPlan(mode);
            String status = strictString(evidence, "status");
            boolean complete = strictBoolean(evidence, "complete");
            boolean fixtureVerified = strictBoolean(evidence, "fixture_verified");
            boolean cancelled = strictBoolean(evidence, "cancelled");
            boolean timedOut = strictBoolean(evidence, "timed_out");
            boolean storageOk = strictBoolean(evidence, "report_storage_ok");
            String fullSuiteStatus = strictString(evidence, "full_suite_status");
            JSONArray plannedCases = strictArray(evidence, "planned_cases");
            JSONArray plannedControls = strictArray(evidence, "planned_controls");
            JSONArray exports = strictArray(evidence, "exports");
            JSONArray controls = strictArray(evidence, "checker_controls");
            JSONObject saved = strictObject(evidence, "outcome");
            String mismatch = validatePlan(evidence, plan, plannedCases, plannedControls,
                    exports, controls, status, fullSuiteStatus);
            if (mismatch != null) {
                return create("INCONSISTENT", "未通过", false, plan,
                        counts(exports), counts(controls), mismatch,
                        "丢弃该结果并按所选模式重跑。");
            }
            Counts exportCounts = counts(exports);
            Counts controlCounts = counts(controls);
            boolean hasFatal = evidence.has("fatal_error") && !evidence.isNull("fatal_error");
            boolean hasStorageError = !storageOk
                    || (evidence.has("report_storage_error") && !evidence.isNull("report_storage_error"));
            SuiteOutcome storedExpected = resolved(plan, status, complete, fixtureVerified, cancelled,
                    timedOut, hasFatal, hasStorageError, fullSuiteStatus, exportCounts, controlCounts,
                    false, true);
            SuiteOutcome outcome = resolved(plan, status, complete, fixtureVerified, cancelled,
                    timedOut, hasFatal, hasStorageError, fullSuiteStatus, exportCounts, controlCounts,
                    true, true);
            String storedError = validateStored(saved, storedExpected);
            if (storedError != null) {
                return create("INCONSISTENT", "未通过", false, plan, exportCounts, controlCounts,
                        storedError, "丢弃该结果并按所选模式重跑。");
            }
            return outcome;
        } catch (IllegalArgumentException error) {
            return malformed(error.getMessage());
        }
    }

    private static SuiteOutcome resolved(SelfTestPlan plan, String status, boolean complete,
            boolean fixtureVerified, boolean cancelled, boolean timedOut, boolean hasFatal,
            boolean storageError, String fullSuiteStatus, Counts exportCounts, Counts controlCounts,
            boolean restored, boolean successInventoryValid) {
        if (storageError) {
            return create("STORAGE_ERROR", "未通过", false, plan, exportCounts, controlCounts,
                    "报告或元数据保存失败；结果不具权威性。",
                    "先修复存储问题，再重新运行所选模式。");
        }
        if (cancelled) {
            return create("CANCELLED", "未通过", false, plan, exportCounts, controlCounts,
                    "套件已取消；当前结果不完整。",
                    "重新运行所选模式以获得权威结果。");
        }
        if (!complete) {
            return create(restored ? "INTERRUPTED" : "RUNNING", restored ? "未通过" : "运行中", false, plan,
                    exportCounts, controlCounts,
                    restored ? "套件在完成前中断；RUNNING 快照不能代表通过。"
                            : "套件仍在运行；完成前不能判定通过。",
                    restored ? "重新运行所选模式；中断前产物只作诊断。"
                            : "等待结束；如中断或取消，请重新运行所选模式。");
        }
        if (timedOut) {
            return create("ERROR", "未通过", false, plan, exportCounts, controlCounts,
                    "套件超时；未形成权威结论。",
                    "检查卡住步骤后重新运行。");
        }
        if (hasFatal) {
            return create("ERROR", "未通过", false, plan, exportCounts, controlCounts,
                    "套件出现执行错误；未形成权威结论。",
                    "查看 fatal_error 或对应 JSON 后修复并重跑。");
        }
        if (!fixtureVerified) {
            return create("FAIL", "未通过", false, plan, exportCounts, controlCounts,
                    "冻结基准素材或引用校验未通过。",
                    "修复基准素材/引用后重跑。");
        }
        if (plan.isShort()) {
            if ("SUBSET_PASS".equals(status) && "NOT_RUN".equals(fullSuiteStatus)
                    && successInventoryValid && exportCounts.allPass() && controlCounts.allPass()) {
                return create("SUBSET_PASS", "SUBSET PASS", true, plan, exportCounts, controlCounts,
                        "短诊断所选项目通过。",
                        "若需整套权威结论，请运行 FULL。");
            }
            if ("PASS".equals(status)) {
                return create("INCONSISTENT", "未通过", false, plan, exportCounts, controlCounts,
                        "短诊断不得标记为 PASS/ALL PASS。",
                        "按短诊断或 FULL 重新运行，勿复用错误模式结果。");
            }
        } else if ("PASS".equals(status) && "PASS".equals(fullSuiteStatus)
                && successInventoryValid && exportCounts.allPass() && controlCounts.allPass()) {
            return create("ALL_PASS", "ALL PASS", true, plan, exportCounts, controlCounts,
                    "本套件已覆盖项目全部通过。",
                    "如需验证未覆盖范围，请另做专项测试。");
        }
        if ("PASS".equals(status) || "SUBSET_PASS".equals(status)) {
            return create("INCONSISTENT", "未通过", false, plan, exportCounts, controlCounts,
                    "状态与明细计数或固定库存不一致；结果不能信任。",
                    "丢弃该结果并重新运行。");
        }
        if ("ERROR".equals(status)) {
            return create("ERROR", "未通过", false, plan, exportCounts, controlCounts,
                    "套件记录为 ERROR；未形成权威结论。",
                    "查看错误详情并修复后重跑。");
        }
        if ("FAIL".equals(status)) {
            return create("FAIL", "未通过", false, plan, exportCounts, controlCounts,
                    "存在明确失败项。",
                    "查看失败的导出或对照 JSON 并修复后重跑。");
        }
        return create("FAIL", "未通过", false, plan, exportCounts, controlCounts,
                "并非所有所选导出与对照都通过，因此不能判定通过。",
                "补齐未运行项并修复异常后重跑；输出可预览但不代表通过。");
    }

    private static String validatePlan(JSONObject evidence, SelfTestPlan plan, JSONArray plannedCases,
            JSONArray plannedControls, JSONArray exports, JSONArray controls, String status,
            String fullSuiteStatus) throws JSONException {
        if (plannedCases.length() != plan.cases.size()
                || strictInt(evidence, "planned_export_count") != plan.cases.size()) {
            return "planned_export_count 与 " + plan.mode + " 固定库存不一致。";
        }
        if (plannedControls.length() != plan.controls.size()
                || strictInt(evidence, "planned_control_count") != plan.controls.size()) {
            return "planned_control_count 与 " + plan.mode + " 固定库存不一致。";
        }
        if (exports.length() != plan.cases.size() || controls.length() != plan.controls.size()) {
            return "导出或对照结果数量与固定库存不一致。";
        }
        for (int i = 0; i < plan.cases.size(); i++) {
            if (!plan.cases.get(i).equals(strictString(plannedCases, i, "planned_cases"))
                    || !plan.cases.get(i).equals(strictString(strictObject(exports, i, "exports"), "id"))) {
                return "导出库存顺序与 " + plan.mode + " 固定计划不一致。";
            }
        }
        for (int i = 0; i < plan.controls.size(); i++) {
            if (!plan.controls.get(i).equals(strictString(plannedControls, i, "planned_controls"))
                    || !plan.controls.get(i).equals(strictString(strictObject(controls, i, "checker_controls"), "id"))) {
                return "对照库存顺序与 " + plan.mode + " 固定计划不一致。";
            }
        }
            String resultsError = validateResults(exports, plan.cases, "导出", false);
            if (resultsError != null) return resultsError;
            resultsError = validateResults(controls, plan.controls, "对照", true);
            if (resultsError != null) return resultsError;
        if (plan.isShort()) {
            if (!"NOT_RUN".equals(fullSuiteStatus)) {
                return "短诊断 full_suite_status 必须为 NOT_RUN。";
            }
        } else if (!fullSuiteStatus.isEmpty() && !fullSuiteStatus.equals(status)) {
            return "FULL 结果的 full_suite_status 与 suite status 不一致。";
        }
        return null;
    }

    private static String validateStored(JSONObject stored, SuiteOutcome expected) {
        try {
            if (!SCHEMA.equals(strictString(stored, "schema"))) {
                return "durable outcome schema 缺失或非法。";
            }
            if (!expected.state.equals(strictString(stored, "state"))
                    || !expected.headline.equals(strictString(stored, "headline"))
                    || expected.success != strictBoolean(stored, "success")
                    || !expected.mode.equals(strictString(stored, "mode"))
                    || !expected.reason.equals(strictString(stored, "reason"))
                    || !expected.nextStep.equals(strictString(stored, "next_step"))
                    || expected.omittedExports != strictInt(stored, "omitted_exports")
                    || expected.omittedControls != strictInt(stored, "omitted_controls")) {
                return "durable outcome 元数据与 suite 明细不一致。";
            }
            JSONObject exports = strictObject(stored, "exports");
            JSONObject controls = strictObject(stored, "controls");
            String exportError = validateStoredCounts(exports, expected.exportsPassed, expected.exportsFailed,
                    expected.exportsErrors, expected.exportsUnrun, expected.exportsTotal, "exports");
            return exportError != null ? exportError
                    : validateStoredCounts(controls, expected.controlsPassed, expected.controlsFailed,
                    expected.controlsErrors, expected.controlsUnrun, expected.controlsTotal, "controls");
        } catch (IllegalArgumentException error) {
            return "durable outcome 类型非法: " + error.getMessage();
        }
    }

    private static String validateStoredCounts(JSONObject stored, int passed, int failed,
            int errors, int unrun, int total, String label) {
        try {
            return strictInt(stored, "passed") == passed
                    && strictInt(stored, "failed") == failed
                    && strictInt(stored, "errors") == errors
                    && strictInt(stored, "unrun") == unrun
                    && strictInt(stored, "total") == total ? null
                    : "durable outcome " + label + " 计数与 suite 明细不一致。";
        } catch (IllegalArgumentException error) {
            return "durable outcome " + label + " 类型非法: " + error.getMessage();
        }
    }

    private static Counts counts(JSONArray results) throws JSONException {
        if (results == null) return Counts.zero();
        Counts counts = new Counts(results.length());
        for (int i = 0; i < results.length(); i++) {
            String status = strictString(strictObject(results, i, "results"), "status");
            if ("PASS".equals(status)) counts.passed++;
            else if ("FAIL".equals(status)) counts.failed++;
            else if ("ERROR".equals(status)) counts.errors++;
            else if ("UNVERIFIED".equals(status)) counts.unrun++;
            else throw new IllegalArgumentException("status 非法: " + status);
        }
        return counts;
    }

    private static SnapshotSummary snapshotSummary(JSONArray results,
            java.util.List<String> expectedIds, boolean controls) {
        Counts counts = Counts.none(expectedIds.size());
        boolean inventoryValid = results != null && results.length() == expectedIds.size();
        int limit = results == null ? 0 : Math.min(results.length(), expectedIds.size());
        for (int i = 0; i < limit; i++) {
            Object raw = results.opt(i);
            if (!(raw instanceof JSONObject)) {
                inventoryValid = false;
                continue;
            }
            JSONObject item = (JSONObject) raw;
            String expectedId = expectedIds.get(i);
            String id = optionalString(item, "id");
            if (!expectedId.equals(id)) inventoryValid = false;
            String expectedStatus = optionalString(item, "expected_status");
            String plannedStatus = controls
                    ? (SelfTestPlan.positive(expectedId) ? "PASS" : "FAIL")
                    : "PASS";
            if (!plannedStatus.equals(expectedStatus)) inventoryValid = false;
            String actualStatus = optionalString(item, "actual_status");
            if (!isActualStatus(actualStatus)) inventoryValid = false;
            String status = optionalString(item, "status");
            if ("PASS".equals(status)) {
                counts.unrun--;
                counts.passed++;
                if (!plannedStatus.equals(actualStatus)) inventoryValid = false;
            } else if ("FAIL".equals(status)) {
                counts.unrun--;
                counts.failed++;
            } else if ("ERROR".equals(status)) {
                counts.unrun--;
                counts.errors++;
            } else if ("UNVERIFIED".equals(status)) {
                // Leave counted as unrun.
            } else {
                inventoryValid = false;
            }
        }
        return new SnapshotSummary(counts, inventoryValid);
    }

    private static String validateResults(JSONArray results, java.util.List<String> expectedIds,
            String label, boolean controls) throws JSONException {
        for (int i = 0; i < results.length(); i++) {
            JSONObject item = strictObject(results, i, label);
            String id = strictString(item, "id");
            if (!expectedIds.get(i).equals(id)) {
                return label + " ID 与固定计划不一致: " + id;
            }
            String status = strictString(item, "status");
            if (!"PASS".equals(status) && !"FAIL".equals(status)
                    && !"ERROR".equals(status) && !"UNVERIFIED".equals(status)) {
                return label + " " + id + " status 非法: " + status;
            }
            String expectedStatus = strictString(item, "expected_status");
            if (!"PASS".equals(expectedStatus) && !"FAIL".equals(expectedStatus)) {
                return label + " " + id + " expected_status 非法: " + expectedStatus;
            }
            String plannedStatus = controls ? SelfTestPlan.positive(id) ? "PASS" : "FAIL" : "PASS";
            if (!plannedStatus.equals(expectedStatus)) {
                return label + " " + id + " expected_status 与固定计划不一致: " + expectedStatus;
            }
            String actualStatus = strictString(item, "actual_status");
            if (!"PASS".equals(actualStatus) && !"FAIL".equals(actualStatus)
                    && !"ERROR".equals(actualStatus) && !"NOT RUN".equals(actualStatus)) {
                return label + " " + id + " actual_status 非法: " + actualStatus;
            }
            if ("PASS".equals(status) && !expectedStatus.equals(actualStatus)) {
                return label + " " + id + " PASS 行 actual_status 必须等于 expected_status。";
            }
        }
        return null;
    }

    private static SuiteOutcome create(String state, String headline, boolean success,
            SelfTestPlan plan, Counts exports, Counts controls, String reason, String nextStep) {
        int omittedExports = plan.isShort() ? SelfTestPlan.FULL_CASES.size() - plan.cases.size() : 0;
        int omittedControls = plan.isShort() ? fullControlCount() - plan.controls.size() : 0;
        return new SuiteOutcome(state, headline, success, plan.mode.name(), exports, controls,
                omittedExports, omittedControls, reason, nextStep);
    }

    private static SuiteOutcome manual(String state, String headline, boolean success, String mode,
            Counts exports, Counts controls, int omittedExports, int omittedControls,
            String reason, String nextStep) {
        return new SuiteOutcome(state, headline, success, mode, exports, controls,
                omittedExports, omittedControls, reason, nextStep);
    }

    private static JSONObject countsJson(int passed, int failed, int errors, int unrun, int total)
            throws JSONException {
        return new JSONObject().put("passed", passed).put("failed", failed)
                .put("errors", errors).put("unrun", unrun).put("total", total);
    }

    private static int fullControlCount() {
        return new SelfTestPlan(SelfTestPlan.Mode.FULL).controls.size();
    }

    private static final class Counts {
        int passed;
        int failed;
        int errors;
        int unrun;
        final int total;

        Counts(int total) { this.total = total; }
        Counts(int passed, int failed, int errors, int unrun, int total) {
            this.passed = passed;
            this.failed = failed;
            this.errors = errors;
            this.unrun = unrun;
            this.total = total;
        }

        boolean allPass() { return total > 0 && passed == total && failed == 0 && errors == 0 && unrun == 0; }

        static Counts zero() { return new Counts(0); }

        static Counts none(int total) {
            Counts counts = new Counts(total);
            counts.unrun = total;
            return counts;
        }
    }

    private static final class SnapshotSummary {
        final Counts counts;
        final boolean inventoryValid;

        SnapshotSummary(Counts counts, boolean inventoryValid) {
            this.counts = counts;
            this.inventoryValid = inventoryValid;
        }
    }

    private static boolean strictBoolean(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof Boolean) return (Boolean) value;
        throw new IllegalArgumentException(key + " 必须为布尔值。");
    }

    private static int strictInt(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            int integer = ((Number) value).intValue();
            if (Double.isFinite(number) && number == integer) return integer;
        }
        throw new IllegalArgumentException(key + " 必须为整数。");
    }

    private static JSONArray strictArray(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof JSONArray) return (JSONArray) value;
        throw new IllegalArgumentException(key + " 必须为数组。");
    }

    private static JSONObject strictObject(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof JSONObject) return (JSONObject) value;
        throw new IllegalArgumentException(key + " 必须为对象。");
    }

    private static JSONObject strictObject(JSONArray array, int index, String label) throws JSONException {
        Object value = array.get(index);
        if (value instanceof JSONObject) return (JSONObject) value;
        throw new IllegalArgumentException(label + "[" + index + "] 必须为对象。");
    }

    private static String strictString(JSONObject object, String key) {
        Object value = object.opt(key);
        if (value instanceof String) return (String) value;
        throw new IllegalArgumentException(key + " 必须为字符串。");
    }

    private static String strictString(JSONArray array, int index, String label) throws JSONException {
        Object value = array.get(index);
        if (value instanceof String) return (String) value;
        throw new IllegalArgumentException(label + "[" + index + "] 必须为字符串。");
    }

    private static String optionalString(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String ? (String) value : null;
    }

    private static boolean isActualStatus(String status) {
        return "PASS".equals(status) || "FAIL".equals(status)
                || "ERROR".equals(status) || "NOT RUN".equals(status);
    }
}
