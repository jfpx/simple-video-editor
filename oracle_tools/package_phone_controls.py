"""Package exact frozen controls; never import or execute the original oracle.

Run with --oracle-root PATH to package, --check for offline validation, or
--self-test for filesystem-free focused tests. --check --oracle-root PATH also
audits the complete original inventory using the existing read-only builder.
Existing differing outputs are rejected, not repaired. Unknown assets survive.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import struct
import sys
import unittest
from pathlib import Path, PureWindowsPath
from unittest.mock import patch

sys.dont_write_bytecode = True
import build_android_video_oracle as builder


REPO = Path(__file__).resolve().parents[1]
ASSETS = REPO / "app" / "src" / "main" / "assets" / "video-oracle"
JAVA = REPO / "app" / "src" / "main" / "java" / "com" / "simple" / "videoeditor"
EVIDENCE_PINS = {
    "handoff.json": "edfb961815b2f968910dfc72174be64fb705e0a5cb136f48b9275af54ff5c041",
    "inventory.json": "edc241c5669233b306b0060dff7e0df2d6d6514e9097ea9b23f1a78cddae82a5",
    "results.json": "d9d84e0247a88e4f89c72126cebb4fa2bd3152daf8f2b049b135a520a775af05",
}
# CONTRACT explicitly documents synthetic independent media and oracle.py as its
# authoring code. This copy is provenance, not a runnable offline fixture suite.
PROVENANCE_PINS = {
    **EVIDENCE_PINS,
    **{name: digest for name, digest in builder.FROZEN_PINS.items()
       if name != "standard.mp4"},
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(data):
    return hashlib.sha256(data).hexdigest()


def relative_parts(relative):
    require(isinstance(relative, str) and relative, "Empty asset path")
    path = PureWindowsPath(relative)
    parts = relative.split("\\")
    require(not path.drive and not path.root and "/" not in relative
            and all(p not in ("", ".", "..") and ":" not in p
                    and not p.endswith((" ", ".")) for p in parts),
            f"Unsafe relative path: {relative}")
    return parts


def local(root, relative):
    return root.joinpath(*relative_parts(relative))


def check_links(path):
    for component in (path, *path.parents):
        try:
            info = component.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(info.st_mode)
                and not getattr(info, "st_file_attributes", 0)
                & stat.FILE_ATTRIBUTE_REPARSE_POINT,
                f"Linked path is not allowed: {component}")
        if stat.S_ISREG(info.st_mode):
            require(info.st_nlink == 1, f"Hard-linked file is not allowed: {component}")


def verified_bytes(path, sha256, size=None):
    check_links(path)
    data = path.read_bytes()
    require(size is None or len(data) == size, f"Size mismatch: {path}")
    require(digest(data) == sha256, f"SHA-256 mismatch: {path}")
    return data


def load_authority(root):
    blobs = {name: verified_bytes(root / name, pin)
             for name, pin in PROVENANCE_PINS.items()}
    docs = {name: json.loads(data) for name, data in blobs.items()
            if name.endswith(".json")}
    inventory = docs["inventory.json"]
    entries = {item["path"]: item for item in inventory["files"]}
    require(len(entries) == len(inventory["files"]) == inventory["file_count"],
            "Frozen inventory count/uniqueness mismatch")
    for name, item in entries.items():
        relative_parts(name)
        require(item["bytes"] > 0 and re.fullmatch("[0-9a-f]{64}", item["sha256"]),
                f"Invalid inventory entry: {name}")
    for name, data in blobs.items():
        if name in entries:
            require(entries[name]["bytes"] == len(data)
                    and entries[name]["sha256"] == digest(data),
                    f"Inventory/provenance disagreement: {name}")
    for item in docs["handoff.json"]["primary_artifacts"]:
        require(entries[item["path"]] == item, "Handoff/inventory disagreement")
    return blobs, docs, entries


def derive_controls(docs, entries):
    results, handoff = docs["results.json"], docs["handoff.json"]
    manifest, vectors = docs["manifest.json"], docs["android_cases.json"]
    require(results["passed"] and not results["artifact_integrity_failures"]
            and handoff["local_encoded_oracle_passed"]
            and not handoff["device_tested"] and not manifest["device_tested"]
            and not manifest["production_pipeline_used"], "Invalid frozen scope/results")
    require(results["positive_count"] == handoff["positive_controls"] == 17
            and results["negative_count"] == handoff["negative_controls"] == 19,
            "Frozen control counts mismatch")
    require(results["manifest_sha256"] == builder.FROZEN_PINS["manifest.json"],
            "Results manifest pin mismatch")
    cases = [case["id"] for case in manifest["cases"]]
    require(cases == handoff["supported_cases"]
            == [case["id"] for case in vectors["cases"]], "Case authority disagreement")
    require([item["control"] for item in results["negative_controls"]]
            == handoff["verified_rejections"], "Negative authority disagreement")
    source_root = PureWindowsPath(handoff["root"])
    controls = []

    def record(verification, status, control_id, prefixes=()):
        candidate = PureWindowsPath(verification["candidate"])
        if handoff["root"] == ".":
            require(not candidate.is_absolute(), "Public candidate must be relative")
            relative = str(candidate)
        else:
            require(candidate.is_absolute(), "Candidate must have frozen absolute path")
            relative = str(candidate.relative_to(source_root))
        relative_parts(relative)
        require(relative == "standard.mp4" or (
            relative.split("\\")[0] in ("reference", "stress", "negative")
            and len(relative.split("\\")) == 2 and relative.endswith(".mp4")),
            "Candidate outside encoded control directories")
        item = entries[relative]
        require(verification["case"] in cases
                and verification["candidate_sha256"] == item["sha256"],
                "Candidate hash/case disagrees with authority")
        require(verification["passed"] is (status == "PASS"),
                "Frozen verification outcome mismatch")
        failures = verification["failed_assertions"]
        require(bool(failures) == (status == "FAIL"), "Failure evidence mismatch")
        for prefix in prefixes:
            require(any(failure.startswith(prefix) for failure in failures),
                    f"Missing frozen failure prefix: {prefix}")
        value = {
            "id": control_id or "positive_" + relative[:-4].replace("\\", "_"),
            "case": verification["case"],
            "expected_status": status,
            "asset": relative if relative == "standard.mp4" else "controls\\" + relative,
            "sha256": item["sha256"],
            "bytes": item["bytes"],
        }
        if prefixes:
            # The singular phone assertion uses the first required frozen prefix;
            # all additional required prefixes remain in archived results.json.
            value["expected_failure_prefix"] = prefixes[0]
        controls.append(value)

    for item in results["positives"]:
        record(item, "PASS", None)
    for item in results["negative_controls"]:
        require(item["passed"] and item["expected_rejection"]
                and item["required_failure_prefixes"], "Missing negative evidence")
        record(item["verification"], "FAIL", "negative_" + item["control"],
               item["required_failure_prefixes"])
    require(len(controls) == 36
            and sum(c["expected_status"] == "PASS" for c in controls) == 17
            and len({c["id"] for c in controls}) == 36
            and len({(c["case"], c["sha256"]) for c in controls}) == 36,
            "Control count/identity mismatch")
    return controls


def validate_android(docs, entries):
    contract_path = ASSETS / "android-contract.json"
    check_links(contract_path)
    contract_hash = builder.sha256(contract_path)
    contract = json.loads(contract_path.read_bytes())
    manifest = docs["manifest.json"]
    require(contract["tolerances"] == manifest["tolerances"],
            "Android tolerances differ from frozen authority")
    for key, name in (("manifest", "manifest.json"), ("android_cases", "android_cases.json"),
                      ("contract_text", "CONTRACT.txt"), ("oracle_py", "oracle.py")):
        require(contract["pins"][key]["sha256"] == builder.FROZEN_PINS[name],
                f"Android authority pin mismatch: {key}")
    require(contract["fixture"] == entries["standard.mp4"], "Fixture pin mismatch")
    verified_bytes(ASSETS / "standard.mp4", entries["standard.mp4"]["sha256"],
                   entries["standard.mp4"]["bytes"])
    require([c["id"] for c in contract["cases"]] == [c["id"] for c in manifest["cases"]],
            "Android case coverage mismatch")
    frames = probes = 0
    for actual, frozen in zip(contract["cases"], manifest["cases"]):
        op = frozen["operation"]
        expected_op = dict(zip(
            ("trim_start_seconds", "trim_end_seconds", "crop_left", "crop_top",
             "crop_right", "crop_bottom", "rotation_degrees", "output_height", "speed", "volume"),
            (*op["trim"], *op["crop"], op["rotation"], op["output_height"],
             op["speed"], op["volume"])))
        require(actual["operation"] == expected_op, "Android operation mismatch")
        for key in ("android_edit_config", "width", "height", "duration_seconds",
                    "native_fps", "accepted_cfr_fps", "native_frame_count",
                    "audio_required", "audio_sample_rate", "audio_channels",
                    "expected_decoded_audio_samples", "audio_probes", "reference"):
            require(actual[key] == frozen[key], f"Android case mismatch: {key}")
        expected_probes = [{key: probe[key] for key in ("source_frame", "output_seconds", "image")}
                           for probe in frozen["probes"]]
        require(actual["probes"] == expected_probes, "Frozen spatial probe mismatch")
        expected = {str(n) for n in range(round(op["trim"][0] * manifest["source"]["fps"]),
                                         round(op["trim"][1] * manifest["source"]["fps"]))}
        require(set(actual["frame_assets"]) == expected, "All-frame PNG coverage mismatch")
        images = []
        for number, item in actual["frame_assets"].items():
            require(item["path"] == (
                f"android-full-frames\\{actual['id']}\\source_{int(number):03d}.png"),
                "Unexpected all-frame PNG path")
            images.append(item)
        frames += len(images)
        for probe in actual["probes"]:
            item = probe["image"]
            require(item == entries[item["path"]], "Frozen probe inventory mismatch")
            images.append(item)
            probes += 1
        for item in images:
            data = verified_bytes(local(ASSETS, item["path"]), item["sha256"], item["bytes"])
            require(data[:8] == b"\x89PNG\r\n\x1a\n" and data[12:16] == b"IHDR"
                    and struct.unpack(">II", data[16:24]) == (actual["width"], actual["height"]),
                    f"PNG geometry/header mismatch: {item['path']}")
    generated = JAVA / "oracle" / "OracleGeneratedContract.java"
    require(generated.read_text(encoding="utf-8") == builder.render_java(contract, contract_hash),
            "Generated Java differs from builder.render_java")
    return contract_hash, frames, probes


def manifest_bytes(controls, blobs, contract_hash):
    document = {
        "schema": "independent-video-oracle-phone-controls",
        "version": "1.0.0",
        "scope": "Frozen independent LOCAL encoded controls; not device export evidence",
        "positive_count": 17,
        "negative_count": 19,
        "android_contract_sha256": contract_hash,
        "provenance": [
            {"asset": "controls\\provenance\\" + name, "sha256": digest(data), "bytes": len(data)}
            for name, data in sorted(blobs.items())
        ],
        "controls": controls,
    }
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def check_java_pin(text, expected):
    text = re.sub(r'"(?:\\.|[^"\\])*"|\'(?:\\.|[^\'\\])*\'|//[^\r\n]*|/\*.*?\*/',
                  lambda match: " " if match[0].startswith(("//", "/*")) else match[0],
                  text, flags=re.DOTALL)
    literals = re.findall(r'\b(?:public|private|protected)?\s*static\s+final\s+String\s+'
                          r'CONTROLS_SHA256\s*=\s*"([0-9a-f]{64})"\s*;', text)
    require(literals == [expected], f"OracleVerifier CONTROLS_SHA256 must equal {expected}")


def owned_target(relative, frozen_root):
    parts = relative_parts(relative)
    require(relative == "controls.json" or (parts[0] == "controls" and len(parts) > 1),
            f"Output outside owned controls paths: {relative}")
    path = local(ASSETS, relative)
    check_links(path)
    require(ASSETS.resolve() in path.resolve().parents, "Output escapes assets")
    if frozen_root is not None:
        builder.validate_output(path, frozen_root)
    return path


def publish(outputs, frozen_root):
    # Preflight every destination before any write; never replace files or remove
    # directories. Exclusive creation also prevents clobbering concurrent outputs.
    targets = [(owned_target(name, frozen_root), data) for name, data in outputs.items()]
    for path, data in targets:
        if path.exists():
            require(path.is_file() and path.read_bytes() == data,
                    f"Existing owned output differs; refusing overwrite: {path}")
    for path, data in targets:
        if path.exists():
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        check_links(path)
        with path.open("xb") as stream:
            stream.write(data)


def run(check=False, oracle_root=None):
    require(check or oracle_root is not None, "Packaging requires --oracle-root")
    if oracle_root is not None:
        oracle_root = oracle_root.absolute()
        # Pin vector authority before trusting the builder's inventory traversal.
        load_authority(oracle_root)
        checked = builder.verify_frozen_inputs(oracle_root)
        print(f"Original frozen inventory verified: {len(checked)} artifacts")
    authority_root = ASSETS / "controls" / "provenance" if check else oracle_root
    blobs, docs, entries = load_authority(authority_root)
    controls = derive_controls(docs, entries)
    contract_hash, frames, probes = validate_android(docs, entries)
    outputs = {"controls\\provenance\\" + name: data for name, data in blobs.items()}
    for control in controls:
        asset = control["asset"]
        source = local(ASSETS, asset) if check or asset == "standard.mp4" else local(
            oracle_root, asset.removeprefix("controls\\"))
        data = verified_bytes(source, control["sha256"], control["bytes"])
        if asset != "standard.mp4":
            outputs[asset] = data
    encoded = manifest_bytes(controls, blobs, contract_hash)
    outputs["controls.json"] = encoded
    if check:
        for name, data in outputs.items():
            verified_bytes(owned_target(name, oracle_root), digest(data), len(data))
        check_java_pin((JAVA / "OracleVerifier.java").read_text(encoding="utf-8"), digest(encoded))
    else:
        publish(outputs, oracle_root)
        for name, data in outputs.items():
            verified_bytes(owned_target(name, oracle_root), digest(data), len(data))
    summary = {
        "mode": "check" if check else "package",
        "controls_sha256": digest(encoded),
        "positive_count": 17, "negative_count": 19,
        "owned_files": len(outputs), "owned_bytes": sum(map(len, outputs.values())),
        "unique_media_files_including_existing_standard": len({c["asset"] for c in controls}),
        "all_frame_pngs": frames, "frozen_probe_pngs": probes,
        "generated_java_matches": True, "java_controls_pin_checked": check,
    }
    print(json.dumps(summary, sort_keys=True))
    return summary


class PackagingTests(unittest.TestCase):
    def test_safe_relative_paths(self):
        for name in ("..\\standard.mp4", "controls\\..\\standard.mp4", "C:\\x", "\\x",
                     "controls/x", "controls\\\\x", "controls\\x:stream", "controls\\x."):
            with self.subTest(name=name), self.assertRaises(ValueError):
                relative_parts(name)

    def test_ownership_boundary(self):
        for name in ("standard.mp4", "android-contract.json", "frames\\a.png",
                     "controls-unknown.json", "controls"):
            with self.subTest(name=name), self.assertRaises(ValueError):
                owned_target(name, None)

    def test_java_literal_pin(self):
        text = 'private static final String CONTROLS_SHA256 = "' + "a" * 64 + '";'
        check_java_pin(text, "a" * 64)
        for invalid in ("", text.replace("a" * 64, "b" * 64), text + text,
                        "// " + text, "/* " + text + " */",
                        text.replace('"' + "a" * 64 + '"', "computedHash()")):
            with self.subTest(text=invalid), self.assertRaises(ValueError):
                check_java_pin(invalid, "a" * 64)

    def test_hash_and_size_corruption(self):
        with patch.object(Path, "read_bytes", return_value=b"corrupt"), \
                patch(__name__ + ".check_links"):
            for pin, size in ((digest(b"original"), 7), (digest(b"corrupt"), 8)):
                with self.subTest(size=size), self.assertRaises(ValueError):
                    verified_bytes(Path("unused"), pin, size)

    def test_manifest_determinism(self):
        first = manifest_bytes([], {"b": b"b", "a": b"a"}, "f" * 64)
        self.assertEqual(first, manifest_bytes([], {"a": b"a", "b": b"b"}, "f" * 64))
        self.assertNotIn(b"\r", first)

    def test_vector_derivation_and_evidence_rejection(self):
        from copy import deepcopy

        cases = [{"id": "identity"}]
        entries, positives, negatives = {}, [], []
        for index in range(36):
            name = ("reference" if index < 17 else "negative") + f"\\control_{index}.mp4"
            sha = digest(name.encode())
            entries[name] = {"path": name, "sha256": sha, "bytes": index + 1}
            verification = {
                "case": "identity", "candidate": "C:\\frozen\\" + name,
                "candidate_sha256": sha, "passed": index < 17,
                "failed_assertions": [] if index < 17 else ["video.geometry"],
            }
            if index < 17:
                positives.append(verification)
            else:
                negatives.append({
                    "control": f"control_{index}", "passed": True, "expected_rejection": True,
                    "required_failure_prefixes": ["video.geometry"], "verification": verification,
                })
        docs = {
            "results.json": {
                "passed": True, "artifact_integrity_failures": [], "positive_count": 17,
                "negative_count": 19, "manifest_sha256": builder.FROZEN_PINS["manifest.json"],
                "positives": positives, "negative_controls": negatives,
            },
            "handoff.json": {
                "root": "C:\\frozen", "local_encoded_oracle_passed": True, "device_tested": False,
                "positive_controls": 17, "negative_controls": 19, "supported_cases": ["identity"],
                "verified_rejections": [item["control"] for item in negatives],
            },
            "manifest.json": {
                "device_tested": False, "production_pipeline_used": False, "cases": cases,
            },
            "android_cases.json": {"cases": cases},
        }
        controls = derive_controls(docs, entries)
        self.assertEqual(len(controls), 36)
        self.assertEqual(controls[0]["id"], "positive_reference_control_0")
        self.assertNotIn("expected_failure_prefix", controls[0])
        self.assertEqual(controls[-1]["expected_failure_prefix"], "video.geometry")
        for mutation in ("prefix", "hash", "outcome", "escape", "duplicate"):
            changed = deepcopy(docs)
            first = changed["results.json"]["positives"][0]
            if mutation == "prefix":
                changed["results.json"]["negative_controls"][0]["required_failure_prefixes"] = [
                    "audio.missing"]
            elif mutation == "hash":
                first["candidate_sha256"] = "0" * 64
            elif mutation == "outcome":
                first["passed"] = False
            elif mutation == "escape":
                first["candidate"] = "C:\\elsewhere\\control.mp4"
            else:
                changed["results.json"]["positives"][1] = first
            with self.subTest(mutation=mutation), self.assertRaises(ValueError):
                derive_controls(changed, entries)

    def test_publish_preserves_unknown_files(self):
        with patch(__name__ + ".owned_target", return_value=Path("controls.json")), \
                patch.object(Path, "exists", return_value=True), \
                patch.object(Path, "is_file", return_value=True), \
                patch.object(Path, "read_bytes", return_value=b"same"), \
                patch.object(Path, "open") as opened, patch.object(Path, "unlink") as removed:
            publish({"controls.json": b"same"}, None)
            opened.assert_not_called()
            removed.assert_not_called()
            with self.assertRaisesRegex(ValueError, "refusing overwrite"):
                publish({"controls.json": b"different"}, None)
            opened.assert_not_called()

    def test_symlink_and_hardlink_rejected(self):
        for info in (stat.S_IFLNK, stat.S_IFREG):
            with patch.object(Path, "lstat") as lstat:
                lstat.return_value.st_mode = info
                lstat.return_value.st_file_attributes = 0
                lstat.return_value.st_nlink = 2 if info == stat.S_IFREG else 1
                with self.assertRaises(ValueError):
                    check_links(Path("controls.json"))

    def test_builder_sync_does_not_erase_controls(self):
        contract = {"fixture": {"path": "standard.mp4"}, "cases": [{
            "probes": [{"image": {"path": "frames\\identity\\source_000.png"}}],
            "frame_assets": {"0": {"path": "android-full-frames\\identity\\source_000.png"}},
        }]}
        with patch.object(builder, "validate_output"), patch.object(Path, "mkdir"), \
                patch.object(builder.shutil, "copyfile") as copy, \
                patch.object(builder.shutil, "rmtree") as rmtree, \
                patch.object(Path, "unlink") as unlink:
            builder.sync_assets(contract, Path("frozen"), ASSETS, Path("generation"))
            self.assertEqual(copy.call_count, 3)
            self.assertTrue(all("controls" not in call.args[1].parts for call in copy.call_args_list))
            rmtree.assert_not_called()
            unlink.assert_not_called()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--oracle-root", type=Path, help="Frozen authority; optional with --check")
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--check", action="store_true", help="Read-only, offline by default")
    modes.add_argument("--self-test", action="store_true", help="Run embedded mock-based tests")
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(PackagingTests)
        return 0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1
    try:
        run(args.check, args.oracle_root)
        return 0
    except (ValueError, OSError, KeyError, TypeError, struct.error) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
