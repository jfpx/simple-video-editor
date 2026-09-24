"""Host-only checks: python -B oracle_tools\\test_emulator_validation.py."""

import io
import json
import tarfile
import unittest
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch

import run_emulator_validation as validation
import run_report_crash_validation as short_validation


# Captured legacy runner output, including the distinct terminal result code.
SMOKE_TRANSCRIPT = """INSTRUMENTATION_STATUS: class=com.simple.videoeditor.EmulatorValidationTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=InstrumentationTestRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
com.simple.videoeditor.EmulatorValidationTest:
INSTRUMENTATION_STATUS: test=testSmokeRotate90ExportThroughProductionEngine
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class=com.simple.videoeditor.EmulatorValidationTest
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=InstrumentationTestRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: test=testSmokeRotate90ExportThroughProductionEngine
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=
Test results for InstrumentationTestRunner=.
Time: 15.91

OK (1 test)


INSTRUMENTATION_CODE: -1
"""


def transcript(identities):
    lines = []
    for current, identity in enumerate(identities, 1):
        cls, method = identity.split("#")
        for code in (1, 0):
            lines.extend([
                f"INSTRUMENTATION_STATUS: class={cls}",
                f"INSTRUMENTATION_STATUS: current={current}",
                "INSTRUMENTATION_STATUS: id=InstrumentationTestRunner",
                f"INSTRUMENTATION_STATUS: numtests={len(identities)}",
                f"INSTRUMENTATION_STATUS: test={method}",
                f"INSTRUMENTATION_STATUS_CODE: {code}",
            ])
    return "\n".join(lines + [
        "INSTRUMENTATION_RESULT: stream=", "Time: 1.0", "",
        f"OK ({len(identities)} tests)", "", "INSTRUMENTATION_CODE: -1", "",
    ])


class InstrumentationParsingTests(unittest.TestCase):
    def test_real_legacy_transcript(self):
        parsed = validation.parse_instrumentation(SMOKE_TRANSCRIPT)
        self.assertTrue(parsed["passed"])
        self.assertEqual(parsed["instrumentation_code"], -1)
        self.assertEqual(parsed["ok_tests"], 1)
        self.assertEqual(parsed["test_counts"], {"PASS": 1})

    def test_missing_or_unsuccessful_terminal(self):
        for terminal in ("", "INSTRUMENTATION_CODE: 0", "INSTRUMENTATION_CODE: 1",
                         "INSTRUMENTATION_CODE: -2", "INSTRUMENTATION_CODE: invalid"):
            with self.subTest(terminal=terminal):
                raw = SMOKE_TRANSCRIPT.replace("INSTRUMENTATION_CODE: -1", terminal)
                self.assertFalse(validation.parse_instrumentation(raw)["passed"])

    def test_duplicate_or_premature_terminal(self):
        for raw in (SMOKE_TRANSCRIPT + "INSTRUMENTATION_CODE: -1\n",
                    "INSTRUMENTATION_CODE: -1\n" + SMOKE_TRANSCRIPT):
            with self.subTest(raw=raw):
                self.assertFalse(validation.parse_instrumentation(raw)["passed"])

    def test_all_pass_truncated_or_tampered_counts(self):
        variants = [
            SMOKE_TRANSCRIPT.replace("OK (1 test)", "OK (2 tests)"),
            SMOKE_TRANSCRIPT.replace("numtests=1", "numtests=2"),
            SMOKE_TRANSCRIPT.replace("numtests=1", "numtests=2", 1),
            SMOKE_TRANSCRIPT.replace("current=1", "current=2"),
            SMOKE_TRANSCRIPT.replace("numtests=1", "numtests=invalid"),
            SMOKE_TRANSCRIPT.replace("INSTRUMENTATION_STATUS: numtests=1\n", ""),
            SMOKE_TRANSCRIPT.replace("INSTRUMENTATION_STATUS_CODE: 0\n", ""),
            SMOKE_TRANSCRIPT[SMOKE_TRANSCRIPT.index("INSTRUMENTATION_STATUS_CODE: 1") +
                             len("INSTRUMENTATION_STATUS_CODE: 1\n"):],
            SMOKE_TRANSCRIPT.replace("OK (1 test)", ""),
            SMOKE_TRANSCRIPT.replace("OK (1 test)", "OK (1 test)\nOK (1 test)"),
            SMOKE_TRANSCRIPT + "INSTRUMENTATION_STATUS: class=unfinished\n",
        ]
        for raw in variants:
            with self.subTest(raw=raw):
                self.assertFalse(validation.parse_instrumentation(raw)["passed"])

    def test_failures_and_ignored_statuses_cannot_be_overwritten(self):
        for code in (-1, -2, -3, -4, 2):
            for overwrite in (False, True):
                with self.subTest(code=code, overwrite=overwrite):
                    start = SMOKE_TRANSCRIPT.index(
                        "INSTRUMENTATION_STATUS: class=", 1)
                    end = SMOKE_TRANSCRIPT.index("INSTRUMENTATION_RESULT:")
                    finished = SMOKE_TRANSCRIPT[start:end]
                    replacement = finished.replace("STATUS_CODE: 0", f"STATUS_CODE: {code}")
                    if overwrite:
                        replacement += finished
                    raw = SMOKE_TRANSCRIPT[:start] + replacement + SMOKE_TRANSCRIPT[end:]
                    self.assertFalse(validation.parse_instrumentation(raw)["passed"])

    def test_explicit_failure_text(self):
        for failure in ("INSTRUMENTATION_FAILED: Process crashed.", "FAILURES!!!"):
            with self.subTest(failure=failure):
                self.assertFalse(validation.parse_instrumentation(
                    SMOKE_TRANSCRIPT + failure)["passed"])


class StageInventoryTests(unittest.TestCase):
    def test_complete_stage_inventories(self):
        for stage, count in (("smoke", 1), ("suite", 1), ("regressions", 74)):
            with self.subTest(stage=stage):
                identities = validation.expected_stage_tests(stage)
                self.assertEqual(len(identities), count)
                parsed = validation.parse_instrumentation(transcript(sorted(identities)), stage)
                self.assertTrue(parsed["passed"], parsed)
                self.assertEqual(parsed["expected_test_count"], count)
        regression_classes = {item.split("#")[0]
                              for item in validation.expected_stage_tests("regressions")}
        self.assertEqual(regression_classes, set(validation.REGRESSION_CLASSES))

    def test_smoke_and_suite_require_exact_method_and_one_test(self):
        smoke = validation.STAGE_METHODS["smoke"]
        suite = validation.STAGE_METHODS["suite"]
        for stage, identities in (("smoke", [suite]), ("suite", [smoke]),
                                  ("smoke", [smoke, suite]), ("suite", [smoke, suite]),
                                  ("smoke", [smoke.replace("EmulatorValidationTest", "StaleTest")])):
            with self.subTest(stage=stage, identities=identities):
                self.assertFalse(validation.parse_instrumentation(
                    transcript(identities), stage)["passed"])

    def test_regression_short_count_even_with_consistent_all_pass_summary(self):
        identities = sorted(validation.expected_stage_tests("regressions"))
        for selected in (identities[:1], identities[:-1],
                         [next(item for item in identities if item.startswith(cls + "#"))
                          for cls in validation.REGRESSION_CLASSES]):
            with self.subTest(count=len(selected)):
                raw = transcript(selected)
                self.assertTrue(validation.parse_instrumentation(raw)["passed"])
                self.assertFalse(validation.parse_instrumentation(raw, "regressions")["passed"])

    def test_regression_same_count_wrong_identity_or_duplicate(self):
        identities = sorted(validation.expected_stage_tests("regressions"))
        for replacement in (identities[1], identities[0] + "Stale",
                            "com.simple.videoeditor.StaleTest#testStale"):
            with self.subTest(replacement=replacement):
                raw = transcript([replacement, *identities[1:]])
                self.assertFalse(validation.parse_instrumentation(raw, "regressions")["passed"])

    def test_unknown_stage_fails_explicitly(self):
        with self.assertRaisesRegex(RuntimeError, "Unknown instrumentation stage"):
            validation.parse_instrumentation(SMOKE_TRANSCRIPT, "unknown")

    def test_runner_applies_stage_inventory_without_device_or_files(self):
        for stage in ("smoke", "suite", "regressions"):
            with self.subTest(stage=stage), \
                    patch.object(validation, "run_cmd", return_value=CompletedProcess(
                        [], 0, SMOKE_TRANSCRIPT.encode(), b"")) as command, \
                    patch.object(validation, "sha256", return_value="hash"):
                selected = validation.STAGE_METHODS.get(stage, ",".join(validation.REGRESSION_CLASSES))
                result = validation.run_instrumentation(
                    Path("adb.exe"), "emulator-5554", selected, 10, stage,
                    validation.PROGRESS.parent)
                self.assertEqual(result["parsed"]["passed"], stage == "smoke")
                self.assertEqual(command.call_args.args[0][-1], validation.RUNNER)
                self.assertEqual(result["raw_sha256"], "hash")


class WatermarkSuiteCountTests(unittest.TestCase):
    @staticmethod
    def suite(exports=17, positives=22, negatives=38):
        return {
            "metadata": {"source_revision": "a" * 40},
            "complete": True, "status": "PASS", "cancelled": False, "timed_out": False,
            "mode": "FULL", "coverage": "FULL_FROZEN_SUITE", "full_suite_status": "PASS",
            "planned_export_count": exports, "planned_control_count": positives + negatives,
            "exports": [{"status": "PASS", "expected_status": "PASS", "actual_status": "PASS"}
                        for _ in range(exports)],
            "checker_controls": [{"status": "PASS", "expected_status": status, "actual_status": status}
                                 for status in ["PASS"] * positives + ["FAIL"] * negatives],
        }

    def capture(self, suite):
        data = io.BytesIO()
        with tarfile.open(fileobj=data, mode="w") as archive:
            payload = json.dumps(suite).encode()
            member = tarfile.TarInfo("files/selftest/new-run/suite.json")
            member.size = len(payload)
            archive.addfile(member, io.BytesIO(payload))
        data.seek(0)
        archive = tarfile.open(fileobj=data, mode="r:")
        stage = {"previous_suite_directories": []}
        with patch.object(validation.tarfile, "open", return_value=archive), \
                patch.object(validation, "write_json"):
            validation.capture_suite(Path("unused-in-memory-evidence"), stage)
        return stage

    def test_appended_watermark_counts_are_accepted(self):
        result = self.capture(self.suite())
        self.assertEqual(result["export_count"], 17)
        self.assertEqual(result["control_count"], 60)

    def test_frozen_old_counts_or_partial_new_controls_are_not_current_pass(self):
        for suite in (self.suite(16, 21, 35), self.suite(17, 21, 35),
                      self.suite(17, 23, 37), self.suite(18, 22, 38)):
            with self.subTest(exports=suite["planned_export_count"], controls=suite["planned_control_count"]):
                with self.assertRaises(RuntimeError):
                    self.capture(suite)

    def test_decoder_errors_cannot_count_as_rejected_negatives(self):
        for status in ("ERROR", "NOT RUN", "PASS"):
            suite = self.suite()
            suite["checker_controls"][-1]["actual_status"] = status
            with self.subTest(status=status), self.assertRaisesRegex(RuntimeError, "did not match"):
                self.capture(suite)

    def test_persisted_expected_counts_include_watermark(self):
        with patch.object(validation, "write_json") as write:
            validation.persist_summary(Path("unused-in-memory-evidence"), {"stages": {}})
        result = write.call_args.args[1]
        self.assertEqual(result["expected_export_count"], 17)
        self.assertEqual(result["expected_control_count"], 60)

    def test_short_mode_cannot_pass_full_gate_even_with_full_counts(self):
        suite = self.suite()
        suite["mode"] = "SHORT_DIAGNOSTIC"
        with self.assertRaisesRegex(RuntimeError, "subset"):
            self.capture(suite)


class ShortSuiteCoverageTests(unittest.TestCase):
    def suite(self):
        return dict(mode="SHORT_DIAGNOSTIC", coverage="LIMITED_SUBSET", status="SUBSET_PASS",
                    full_suite_status="NOT_RUN", full_feature_coverage="UNVERIFIED",
                    complete=True, cancelled=False, timed_out=False, fixture_verified=True,
                    planned_cases=short_validation.SHORT_CASES,
                    planned_controls=short_validation.SHORT_CONTROLS,
                    planned_export_count=3, planned_control_count=10,
                    planned_positive_control_count=3, planned_negative_control_count=7,
                    omitted_cases=[f"omitted{i}" for i in range(14)],
                    omitted_controls=[f"omitted{i}" for i in range(50)],
                    exports=[dict(id=i, status="PASS", expected_status="PASS", actual_status="PASS")
                             for i in short_validation.SHORT_CASES],
                    checker_controls=[dict(id=i, status="PASS", expected_status=s, actual_status=s)
                                      for i, s in zip(short_validation.SHORT_CONTROLS,
                                                      ["PASS"] * 2 + ["FAIL"] * 5 + ["PASS", "FAIL", "FAIL"])])

    def test_exact_short_inventory(self):
        short_validation.validate_short_suite(self.suite())

    def test_wrong_identity_or_duplicate_with_same_count_rejected(self):
        for key in ("exports", "checker_controls"):
            suite = self.suite()
            suite[key][-1] = suite[key][0]
            with self.assertRaises(AssertionError):
                short_validation.validate_short_suite(suite)

    def test_error_negative_partial_and_blanket_pass_rejected(self):
        for key, value in (("status", "PASS"), ("complete", False), ("cancelled", True),
                           ("timed_out", True), ("full_suite_status", "PASS"),
                           ("planned_control_count", 60), ("fatal_error", "IOException")):
            suite = self.suite()
            suite[key] = value
            with self.subTest(key=key), self.assertRaises(AssertionError):
                short_validation.validate_short_suite(suite)
        suite = self.suite()
        suite["checker_controls"][-1]["actual_status"] = "ERROR"
        with self.assertRaises(AssertionError):
            short_validation.validate_short_suite(suite)


if __name__ == "__main__":
    unittest.main()
