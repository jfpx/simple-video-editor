from __future__ import annotations

import argparse
import copy
import json
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.dont_write_bytecode = True

import build_android_video_oracle as builder
import run_video_oracle_parity as parity


class OracleToolTests(unittest.TestCase):
    def test_frozen_inventory_and_pins(self):
        checked = builder.verify_frozen_inputs(self.oracle_root)
        inventory = json.loads((self.oracle_root / "inventory.json").read_text(encoding="utf-8"))
        self.assertTrue(all(item["path"] in checked for item in inventory["files"]))
        self.assertEqual(parity.verify_inputs(self.repo_root, self.oracle_root),
                         {path: digest for path, digest in parity.EXPECTED_PINS.values()})

    def test_rejects_changed_manifest(self):
        original = builder.sha256
        with patch.object(builder, "sha256", side_effect=lambda path:
                          "0" * 64 if path.name == "manifest.json" else original(path)):
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch: manifest.json"):
                builder.verify_frozen_inputs(self.oracle_root)

    def test_rejects_changed_encoded_reference(self):
        original = builder.sha256
        with patch.object(builder, "sha256", side_effect=lambda path:
                          "0" * 64 if path.parent.name == "reference" else original(path)):
            with self.assertRaisesRegex(ValueError, "SHA-256 mismatch"):
                builder.verify_frozen_inputs(self.oracle_root)

    def test_generation_rejects_frozen_scratch(self):
        with patch.object(builder, "verify_frozen_inputs"):
            for destination in (self.oracle_root, self.oracle_root / "android-full-frames",
                                self.oracle_root.parent):
                with self.subTest(destination=str(destination)):
                    with self.assertRaisesRegex(ValueError, "overlaps frozen authority"):
                        builder.build_contract(self.oracle_root, destination)

    def test_generation_rejects_frozen_asset_output(self):
        with self.assertRaisesRegex(ValueError, "overlaps frozen authority"):
            builder.sync_assets({}, self.oracle_root, self.oracle_root,
                                self.repo_root / "app" / "build" / "oracle-generation")

    def test_output_is_under_app_build(self):
        self.assertEqual(parity.safe_build_root(self.repo_root, self.oracle_root),
                         self.repo_root / "app" / "build" / "oracle-parity")

    def test_rejects_overlapping_parity_output(self):
        build = self.repo_root / "app" / "build" / "oracle-parity"
        for root in (build, build.parent, build / "frozen"):
            with self.assertRaisesRegex(ValueError, "overlap"):
                parity.safe_build_root(self.repo_root, root)

    def test_generated_contract_matches_persisted_assets(self):
        assets = self.repo_root / "app" / "src" / "main" / "assets" / "video-oracle"
        contract_path = assets / "android-contract.json"
        contract = json.loads(contract_path.read_text(encoding="utf-8"))
        generated = (self.repo_root / "app" / "src" / "main" / "java" / "com" /
                     "simple" / "videoeditor" / "oracle" / "OracleGeneratedContract.java")
        self.assertEqual(builder.render_java(contract, builder.sha256(contract_path)),
                         generated.read_text(encoding="utf-8"))
        manifest = json.loads((self.oracle_root / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual(contract["tolerances"], manifest["tolerances"])

    def test_rejects_invalid_json_numbers_and_duplicate_keys(self):
        for text in ('{"x":1,"x":2}', '{"x":NaN}', '{"x":Infinity}', '{"x":1e999}'):
            with self.subTest(text=text):
                with self.assertRaises(ValueError):
                    parity.load_json(text)

    def test_encoded_report_validates(self):
        parity.validate_report(self.report)

    def test_encoded_outcomes_match_frozen_python_results(self):
        authority = json.loads((self.oracle_root / "results.json").read_text(encoding="utf-8"))
        records = authority["positives"] + [
            item["verification"] for item in authority["negative_controls"]
        ]
        indexed = {(item["case"], item["candidate_sha256"]): item for item in records}
        self.assertEqual(len(indexed), 36)
        for control in self.report["controls"]:
            with self.subTest(control=control["id"]):
                actual = control["full"]["report"]
                expected = indexed[(actual["case"], actual["candidate_sha256"])]
                self.assertEqual(actual["passed"], expected["passed"])
                python_checks = {item["assertion"]: item["passed"] for item in expected["checks"]}
                java_checks = {item["assertion"]: item["passed"] for item in actual["checks"]}
                for assertion in python_checks.keys() & java_checks.keys():
                    self.assertEqual(java_checks[assertion], python_checks[assertion], assertion)

    def test_checker_error_is_not_expected_negative(self):
        report = copy.deepcopy(self.report)
        control = next(item for item in report["controls"] if item["kind"] == "negative")
        for mode in ("full", "sparse"):
            control[mode]["report"]["status"] = "ERROR"
        with self.assertRaisesRegex(ValueError, "core status"):
            parity.validate_report(report)

    def test_sparse_report_difference_is_rejected(self):
        report = copy.deepcopy(self.report)
        report["controls"][0]["sparse"]["report"]["metrics"]["unexpected"] = True
        with self.assertRaisesRegex(ValueError, "unequal full/sparse"):
            parity.validate_report(report)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--oracle-root", required=True)
    args, remaining = parser.parse_known_args()
    OracleToolTests.oracle_root = Path(args.oracle_root).resolve()
    OracleToolTests.repo_root = Path(__file__).resolve().parents[1]
    report_path = (OracleToolTests.repo_root / "app" / "build" / "oracle-parity" /
                   parity.REPORT_NAME)
    OracleToolTests.report = parity.load_json(report_path.read_text(encoding="utf-8"))["java_report"]
    unittest.main(argv=[sys.argv[0], *remaining])
