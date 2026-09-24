from contextlib import contextmanager
import json
from pathlib import Path
import subprocess
import unittest
from unittest.mock import patch

import privacy_export as export


class ReadOnlyBaselineTestCase(unittest.TestCase):
    def setUp(self):
        self.repo = Path(__file__).resolve().parents[1]
        for owner, name in ((export, "git"), (Path, "write_bytes"), (Path, "write_text")):
            guard = patch.object(
                owner, name, side_effect=AssertionError("Read-only baseline used " + name))
            guard.start()
            self.addCleanup(guard.stop)

    @contextmanager
    def read_override(self, target, replacement):
        target = Path(target)
        read_bytes = Path.read_bytes
        reads = []

        def snapshot_read(path):
            if path == target:
                reads.append(path)
                if isinstance(replacement, OSError):
                    raise replacement
                return replacement
            return read_bytes(path)

        with patch.object(Path, "read_bytes", snapshot_read):
            yield
        self.assertTrue(reads, "The mutated file was not read")


class BaselineFixtureTests(ReadOnlyBaselineTestCase):
    def test_default_and_explicit_fixture_load_deterministically(self):
        baseline = export.load_baseline()
        self.assertIsInstance(baseline, dict)
        self.assertTrue(baseline)
        self.assertEqual(baseline, export.load_baseline())
        self.assertEqual(baseline, export.load_baseline(export.BASELINE_PATH))

    def test_fixture_changed_bytes_fail_immutable_hash(self):
        path = Path(export.BASELINE_PATH)
        raw = path.read_bytes()
        changed = raw + b" "
        self.assertEqual(json.loads(raw), json.loads(changed))
        self.assertLessEqual(len(changed), export.MAX_BASELINE_BYTES)
        with self.read_override(path, changed):
            with self.assertRaises(ValueError):
                export.load_baseline()

    def test_fixture_truncated_json_is_rejected(self):
        path = Path(export.BASELINE_PATH)
        truncated = path.read_bytes().rstrip()[:-1]
        with self.assertRaises(ValueError):
            json.loads(truncated)
        with self.read_override(path, truncated):
            with self.assertRaises(ValueError):
                export.load_baseline()

    def test_fixture_over_byte_bound_is_rejected(self):
        path = Path(export.BASELINE_PATH)
        raw = path.read_bytes()
        self.assertLessEqual(len(raw), export.MAX_BASELINE_BYTES)
        oversized = raw + b" " * (export.MAX_BASELINE_BYTES + 1 - len(raw))
        self.assertEqual(len(oversized), export.MAX_BASELINE_BYTES + 1)
        with self.read_override(path, oversized):
            with self.assertRaises(ValueError):
                export.load_baseline()

    def test_checkout_newlines_preserve_authority(self):
        path = Path(export.BASELINE_PATH)
        baseline = export.load_baseline()
        raw = path.read_bytes().replace(b"\r\n", b"\n")
        with self.read_override(path, raw.replace(b"\n", b"\r\n")):
            self.assertEqual(baseline, export.load_baseline())

    def test_reference_inputs_are_readable_sanitized_documents(self):
        baseline = export.load_baseline()
        self.assertEqual(12, len(baseline["metadata"]))
        self.assertEqual(1403, len(baseline["unchanged_assets"]))
        for record in baseline["metadata"]:
            self.assertIsInstance(record["reference"], dict)
            export.public_strings(record["reference"])
        handoff = next(r for r in baseline["metadata"]
                       if r["path"] == export.PROVENANCE + "handoff.json")
        self.assertEqual("reviewed-input", handoff["reference"]["root"])
        self.assertNotEqual(handoff["original_sha256"], handoff["reference_sha256"])

    def test_explicit_historical_failure_never_falls_back(self):
        failure = subprocess.CalledProcessError(128, ["git", "ls-tree"])
        with patch.object(export, "git", side_effect=failure) as git:
            with self.assertRaises(subprocess.CalledProcessError):
                export.derive(self.repo, original=export.ORIGINAL_REVISION)
        git.assert_called_once_with(
            self.repo, "ls-tree", "-r", "--name-only", export.ORIGINAL_REVISION)


class PublicBaselineTests(ReadOnlyBaselineTestCase):
    MUSIC_MANIFEST = "app/src/main/assets/music-oracle/manifest.json"
    INTRO_MANIFEST = "app/src/main/assets/intro-oracle/manifest.json"
    INTRO_CONTRACT = (
        "app/src/main/java/com/simple/videoeditor/oracle/IntroOracleContract.java")
    MUSIC_CONTRACT = (
        "app/src/main/java/com/simple/videoeditor/oracle/MusicOracleContract.java")

    def setUp(self):
        super().setUp()
        self.derived = export.derive(self.repo)
        self.files, self.originals, self.outputs, self.replacements = self.derived

    def assert_run_rejects(self, name, replacement, errors=ValueError):
        with self.read_override(self.repo / name, replacement):
            with self.assertRaises(errors):
                export.run(self.repo, write=False)

    def mutated_document(self, name, mutate):
        path = self.repo / name
        raw = path.read_bytes()
        self.assertEqual(raw, self.outputs[name])
        doc = json.loads(raw)
        mutate(doc)
        changed = export.encode_like(doc, raw)
        self.assertNotEqual(raw, changed)
        return changed

    def assert_consumer_rejects(self, contract, manifest, replacement):
        raw = (self.repo / contract).read_bytes()
        pin = export.digest(self.outputs[manifest]).encode()
        self.assertIn(pin, raw)
        self.assertNotEqual(pin, replacement)
        changed = raw.replace(pin, replacement)
        self.assertNotIn(pin, changed)
        self.assert_run_rejects(contract, changed)

    def test_derive_is_deterministic_without_git_history(self):
        self.assertTrue(self.files)
        self.assertTrue(self.outputs)
        self.assertEqual(set(self.originals), set(self.outputs))
        self.assertTrue(set(self.outputs).issubset(self.files))
        self.assertEqual(self.derived, export.derive(self.repo))

    def test_run_verifies_public_checkout_without_git_history(self):
        receipt = export.run(self.repo, write=False)
        self.assertIs(receipt["numeric_and_assertion_equivalence"], True)
        self.assertEqual(export.VERSION, receipt["export_version"])
        self.assertEqual(set(self.outputs), {row["path"] for row in receipt["metadata"]})
        for row in receipt["metadata"]:
            self.assertEqual(export.digest(self.outputs[row["path"]]), row["public_sha256"])
        self.assertTrue(receipt["unchanged_assets"])
        for row in receipt["unchanged_assets"]:
            raw = (self.repo / row["path"]).read_bytes()
            self.assertEqual(len(raw), row["bytes"])
            self.assertEqual(export.digest(raw), row["sha256"])

    def test_music_numeric_metadata_changes_are_rejected(self):
        for key in ("sample_rate", "frequency_hz"):
            with self.subTest(key=key):
                def mutate(doc):
                    self.assertIsInstance(doc[key], (int, float))
                    doc[key] += 1
                self.assert_run_rejects(
                    self.MUSIC_MANIFEST, self.mutated_document(self.MUSIC_MANIFEST, mutate))

    def test_results_manifest_hashgraph_change_is_rejected(self):
        name = export.PROVENANCE + "results.json"

        def mutate(doc):
            self.assertEqual(len(doc["manifest_sha256"]), 64)
            pin = export.ORIGINAL_PINS["manifest.json"]
            self.assertNotEqual(doc["manifest_sha256"], pin)
            doc["manifest_sha256"] = pin

        self.assert_run_rejects(name, self.mutated_document(name, mutate))

    def test_inventory_total_change_is_rejected(self):
        name = export.PROVENANCE + "inventory.json"

        def mutate(doc):
            doc["total_bytes_excluding_inventory"] += 1

        self.assert_run_rejects(name, self.mutated_document(name, mutate))

    def test_inventory_entry_size_change_is_rejected(self):
        name = export.PROVENANCE + "inventory.json"

        def mutate(doc):
            self.assertTrue(doc["files"])
            doc["files"][0]["bytes"] += 1

        self.assert_run_rejects(name, self.mutated_document(name, mutate))

    def test_inventory_entry_removal_is_rejected(self):
        name = export.PROVENANCE + "inventory.json"

        def mutate(doc):
            self.assertTrue(doc["files"])
            removed = doc["files"].pop()
            doc["file_count"] -= 1
            doc["total_bytes_excluding_inventory"] -= removed["bytes"]

        self.assert_run_rejects(name, self.mutated_document(name, mutate))

    def test_changed_asset_bytes_are_rejected(self):
        receipt = export.run(self.repo, write=False)
        name = receipt["unchanged_assets"][0]["path"]
        raw = (self.repo / name).read_bytes()
        self.assertTrue(raw)
        changed = bytes([raw[0] ^ 1]) + raw[1:]
        self.assertEqual(len(raw), len(changed))
        self.assert_run_rejects(name, changed)

    def test_intro_wrong_public_pin_is_rejected(self):
        pin = export.digest(b"synthetic wrong public manifest").encode()
        self.assert_consumer_rejects(self.INTRO_CONTRACT, self.INTRO_MANIFEST, pin)

    def test_intro_removed_public_pin_is_rejected(self):
        self.assert_consumer_rejects(self.INTRO_CONTRACT, self.INTRO_MANIFEST, b"")

    def test_music_historical_pin_is_rejected(self):
        raw = (self.repo / self.MUSIC_CONTRACT).read_bytes()
        self.assertNotIn(export.MUSIC_ORIGINAL_PIN.encode(), raw)
        self.assert_run_rejects(
            self.MUSIC_CONTRACT, raw + b"\n// " + export.MUSIC_ORIGINAL_PIN.encode())

    def test_missing_metadata_or_asset_is_rejected(self):
        receipt = export.run(self.repo, write=False)
        for name in (self.MUSIC_MANIFEST, receipt["unchanged_assets"][0]["path"]):
            with self.subTest(name=name):
                self.assert_run_rejects(
                    name, FileNotFoundError("Synthetic missing baseline file"),
                    errors=(ValueError, OSError))

    def test_public_write_mode_is_rejected_without_mutation(self):
        with self.assertRaises(ValueError):
            export.run(self.repo, write=True)

    def test_extra_or_missing_asset_inventory_is_rejected(self):
        root = self.repo / "app" / "src" / "main" / "assets"
        paths = list(root.rglob("*"))
        extra = root / "synthetic-extra.json"
        asset = next(path for path in paths if path.is_file())
        is_file = Path.is_file
        for changed in (paths + [extra], [path for path in paths if path != asset]):
            with self.subTest(extra=len(changed) > len(paths)):
                with patch.object(Path, "rglob", return_value=iter(changed)), patch.object(
                        Path, "is_file", lambda path: path == extra or is_file(path)):
                    with self.assertRaisesRegex(ValueError, "inventory mismatch"):
                        export.run(self.repo)


if __name__ == "__main__":
    unittest.main()
