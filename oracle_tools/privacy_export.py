"""Version 1 public provenance derivation from a reviewed immutable authority.

The input hashes are a closed schema: new upstream fields require a reviewed new
export version. Only the enumerated metadata leaves and dependent hash/size
records may change. Media, measurements, tolerances and outcomes are invariant.
Public reference inputs contain sanitized labels, not private historical bytes.
The genuine historical derivation proof is retained separately, privately.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path, PureWindowsPath
import re
import subprocess

VERSION = 1
ORIGINAL_REVISION = "23cff23b7088fa5d4125844341e7922ce0f43833"
PROVENANCE = "app/src/main/assets/video-oracle/controls/provenance/"
ORIGINAL_PINS = {
    "handoff.json": "745929241a80331cab6c284d1414de4188a19848a72cb0fb4a3fba1abfb2d17c",
    "manifest.json": "ed831215918936eeb13b95d0460f9d4d1ab889a928b815a3fdbe9fb7d8e513fe",
    "results.json": "c5ac3ffe124aee54f1757265c9653ab24cda34605b782d394e9c783b0e546823",
}
PUBLIC_FIELDS = {
    "handoff.json": set("version root device_tested local_encoded_oracle_passed "
        "positive_controls negative_controls cli_contract_tests_passed "
        "cli_evidence_matches_current_code primary_artifacts all_artifact_sizes_and_sha256 "
        "supported_cases verified_rejections checker parent_next_steps limitations".split()),
    "manifest.json": set("schema version production_pipeline_used device_tested fixture "
        "analytic_audio tools source conventions tolerances cases limitations".split()),
    "results.json": set("schema version scope manifest_sha256 tools artifact_integrity_failures "
        "passed positive_count negative_count positives negative_controls runtime_tools invocation".split()),
}
PRIVATE = re.compile(
    r"(?i)(?:(?<![a-z0-9])[a-z]:[\\/]|\\\\[^\\]+\\|(?:file|content)://|"
    r"/(?:home|data/user|sdcard)/|\.copilot[\\/]|session-state[\\/]|"
    r"https?://[^/\s:@]+:[^/\s@]+@|gh[pousr]_[a-z0-9]{20,}|"
    r"github_pat_[a-z0-9_]{30,}|-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----)")
MUSIC_ORIGINAL_PIN = "be9c7edddf1a66f830873e4fa7d8817fcd856b0eaed5e1afb4629d729625aacb"
BASELINE_PATH = Path(__file__).with_name("public_baseline.json")
BASELINE_SHA256 = "901c12cdf774532f8ab3561abd13463c412210a7db62c11567bdee91b7755f39"
MAX_BASELINE_BYTES = 8 * 1024 * 1024
REFERENCE_PINS = {
    "handoff.json": "8785b5e9da6f79b0e5e1c2f5e04a3716a7cd1ce3d80032445efbe80b2373514a",
    "manifest.json": "b3dbd58517273ce1a14bd28b006b0a738de9358358f0b46d0b0ef99b00bee630",
    "results.json": "ce76e64bc030e442eb734ee009e14dec4e746ffd107163a936435ece3aa2143c",
}
MUSIC_REFERENCE_PIN = "01540f9a1933d9088f7c5fad0f93fa4629aa4d21808cc066187f36d4c81e2bf3"
PIN_PATTERN = re.compile(rb"(?<![0-9a-f])[0-9a-f]{64}(?![0-9a-f])")


def digest(data):
    return hashlib.sha256(data).hexdigest()


def encode(doc):
    return (json.dumps(doc, ensure_ascii=False, indent=2) + "\n").encode()


def encode_like(doc, original):
    encoded = encode(doc)
    return encoded.replace(b"\n", b"\r\n") if b"\r\n" in original else encoded


def public_strings(value):
    if isinstance(value, dict):
        for key, child in value.items():
            if key.lower() in {"device_serial", "device_fingerprint", "auth_token", "access_token"}:
                raise ValueError("Forbidden public metadata key")
            public_strings(child)
    elif isinstance(value, list):
        for child in value:
            public_strings(child)
    elif isinstance(value, str) and PRIVATE.search(value):
        raise ValueError("Forbidden private public-export value (redacted)")


def sanitize(name, raw, root):
    reference = name in REFERENCE_PINS and digest(raw) == REFERENCE_PINS[name]
    if name not in ORIGINAL_PINS or not (reference or digest(raw) == ORIGINAL_PINS[name]):
        raise ValueError("Unknown original provenance version")
    doc = json.loads(raw)
    if set(doc) != PUBLIC_FIELDS[name]:
        raise ValueError("Unknown public export field")
    result = copy.deepcopy(doc)
    if name == "handoff.json":
        result["root"] = "."
    else:
        for field in ("tools", "runtime_tools"):
            if field in result:
                executables = result[field]["executables"]
                if set(executables) != {"ffmpeg", "ffprobe"}:
                    raise ValueError("Unknown tool field")
                for tool in executables:
                    executables[tool] = tool
    if name == "results.json":
        records = result["positives"] + [
            item["verification"] for item in result["negative_controls"]]
        if len(records) != 36:
            raise ValueError("Changed control coverage")
        for record in records:
            candidate = PureWindowsPath(record["candidate"])
            relative = candidate.relative_to(PureWindowsPath(root))
            if candidate.is_absolute() == reference or ".." in relative.parts:
                raise ValueError("Invalid candidate root")
            record["candidate"] = str(relative)
        result["invocation"][0] = "oracle.py"
    public_strings(result)
    return result


def sanitize_music(raw):
    reference = digest(raw) == MUSIC_REFERENCE_PIN
    if not reference and digest(raw) != MUSIC_ORIGINAL_PIN:
        raise ValueError("Unknown music metadata version")
    doc = json.loads(raw)
    if set(doc) != set("version source_sha256 sample_rate frequency_hz peak input_seconds "
                       "output_seconds expected_rms authority negative_controls commands assets".split()):
        raise ValueError("Unknown music public field")
    for row, column in ((0, 6), (0, 10), (0, 23), (1, 6), (1, 8), (1, 23)):
        path = PureWindowsPath(doc["commands"][row][column])
        if path.is_absolute() == reference or path.name not in {
                "standard.mp4", "music.wav", "reference.mp4", "no-loop.mp4"}:
            raise ValueError("Unexpected authoring command path")
        directory = "video-oracle" if path.name == "standard.mp4" else "music-oracle"
        doc["commands"][row][column] = directory + "/" + path.name
    public_strings(doc)
    return doc


def repin(value, replacements):
    if isinstance(value, dict):
        result = {key: repin(child, replacements) for key, child in value.items()}
        if value.get("sha256") in replacements and "bytes" in value:
            old, new = replacements[value["sha256"]]
            if value["bytes"] != len(old):
                raise ValueError("Original pin size mismatch")
            result["bytes"] = len(new)
        if "total_bytes_excluding_inventory" in value:
            result["total_bytes_excluding_inventory"] = sum(x["bytes"] for x in result["files"])
        return result
    if isinstance(value, list):
        return [repin(child, replacements) for child in value]
    if isinstance(value, str) and value in replacements:
        return digest(replacements[value][1])
    return value


def git(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args])


def reference_bytes(record):
    data = encode(record["reference"])
    return data.replace(b"\n", b"\r\n") if record["newline"] == "crlf" else data


def load_baseline(path=None):
    path = BASELINE_PATH if path is None else Path(path)
    if path.stat().st_size > MAX_BASELINE_BYTES:
        raise ValueError("Public baseline exceeds byte bound")
    raw = path.read_bytes()
    if len(raw) > MAX_BASELINE_BYTES:
        raise ValueError("Public baseline exceeds byte bound")
    # Only checkout line-ending conversion is permitted, never new authority.
    if digest(raw.replace(b"\r\n", b"\n")) != BASELINE_SHA256:
        raise ValueError("Public baseline immutable hash mismatch")
    baseline = json.loads(raw)
    if (set(baseline) != {"version", "provenance", "metadata", "unchanged_assets", "consumers"}
            or baseline["version"] != VERSION
            or baseline["provenance"]["original_revision"] != ORIGINAL_REVISION
            or len(baseline["metadata"]) != 12
            or len(baseline["unchanged_assets"]) != 1403
            or len(baseline["consumers"]) != 12):
        raise ValueError("Public baseline schema mismatch")
    seen = set()
    for record in baseline["metadata"] + baseline["unchanged_assets"] + baseline["consumers"]:
        name = record["path"]
        if (not name or name in seen or "\\" in name or ":" in name
                or any(part in {"", ".", ".."} for part in name.split("/"))):
            raise ValueError("Public baseline path mismatch")
        seen.add(name)
    for record in baseline["metadata"]:
        if record["newline"] not in {"lf", "crlf"}:
            raise ValueError("Public baseline newline mismatch")
        if digest(reference_bytes(record)) != record["reference_sha256"]:
            raise ValueError("Public baseline reference hash mismatch")
        name = record["path"].removeprefix(PROVENANCE)
        if name in ORIGINAL_PINS and (
                record["original_sha256"] != ORIGINAL_PINS[name]
                or record["reference_sha256"] != REFERENCE_PINS[name]):
            raise ValueError("Public baseline provenance pin mismatch")
    public_strings(baseline)
    return baseline


def derive(repo, original=None):
    """Default to public reference inputs; explicit historical derivation never falls back."""
    baseline = load_baseline() if original is None else None
    if baseline is not None:
        files = [r["path"] for r in baseline["metadata"] + baseline["unchanged_assets"]]
        originals = {r["path"]: reference_bytes(r) for r in baseline["metadata"]}
    else:
        files = git(repo, "ls-tree", "-r", "--name-only", original).decode().splitlines()
        names = [name for name in files if name.startswith("app/src/main/assets/")
                 and name.endswith(".json") and "-oracle/" in name]
        originals = {name: git(repo, "show", original + ":" + name) for name in names}
    root = json.loads(originals[PROVENANCE + "handoff.json"])["root"]
    docs = {name: sanitize(name.removeprefix(PROVENANCE), data, root)
            if name.removeprefix(PROVENANCE) in ORIGINAL_PINS else
            sanitize_music(data) if name == "app/src/main/assets/music-oracle/manifest.json"
            else json.loads(data)
            for name, data in originals.items()}
    outputs = {name: (encode_like(doc, originals[name]) if name.removeprefix(PROVENANCE) in ORIGINAL_PINS
                      else originals[name]) for name, doc in docs.items()}
    for _ in range(20):
        replacements = {digest(originals[name]): (originals[name], data)
                        for name, data in outputs.items() if originals[name] != data}
        updated = {}
        for name, doc in docs.items():
            new = repin(doc, replacements)
            updated[name] = (encode_like(new, originals[name])
                             if new != json.loads(originals[name]) else originals[name])
        if updated == outputs:
            break
        outputs = updated
    else:
        raise ValueError("Cyclic hash dependencies")
    for data in outputs.values():
        public_strings(json.loads(data))
    if baseline is not None:
        for record in baseline["metadata"]:
            data = outputs[record["path"]]
            if len(data) != record["public_bytes"] or digest(data) != record["public_sha256"]:
                raise ValueError("Public baseline derivation mismatch: " + record["path"])
    return files, originals, outputs, replacements


def run(repo, write=False):
    if write:
        raise ValueError("Public baseline verification is read-only")
    repo = Path(repo)
    baseline = load_baseline()
    files, originals, outputs, replacements = derive(repo)
    consumer_replacements = dict(replacements)
    for name, data in outputs.items():
        current = (repo / name).read_bytes()
        if current != data:
            raise ValueError("Public metadata not deterministically derived: " + name)
    for record in baseline["metadata"]:
        if record["original_sha256"] != record["public_sha256"]:
            consumer_replacements[record["original_sha256"]] = (b"", outputs[record["path"]])
    for record in baseline["consumers"]:
        name = record["path"]
        old = (repo / name).read_bytes()
        for pin in consumer_replacements:
            if pin.encode() in old:
                raise ValueError("Stale public pin consumer: " + name)
        if [p.decode() for p in PIN_PATTERN.findall(old)] != record["sha256_references"]:
            raise ValueError("Public consumer hash references changed: " + name)
    inventory = set()
    for path in (repo / "app" / "src" / "main" / "assets").rglob("*"):
        if path.is_symlink() or getattr(path, "is_junction", lambda: False)():
            raise ValueError("Linked asset is not public authority")
        if path.is_file():
            inventory.add(path.relative_to(repo).as_posix())
    if inventory != set(files):
        raise ValueError("Public asset inventory mismatch")
    media = []
    for record in baseline["unchanged_assets"]:
        name = record["path"]
        new = (repo / name).read_bytes()
        blob = hashlib.sha1(b"blob " + str(len(new)).encode() + b"\0" + new).hexdigest()
        if (blob != record["blob_sha1"] or len(new) != record["bytes"]
                or digest(new) != record["sha256"]):
            raise ValueError("Nonmetadata asset changed: " + name)
        media.append({"path": name, "sha256": digest(new), "bytes": len(new)})
    return {
        "export_version": VERSION, "original_revision": ORIGINAL_REVISION,
        "private_fields_removed": 44, "numeric_and_assertion_equivalence": True,
        "additional_internal_authoring_paths_removed": 6,
        "equivalence_method": "immutable sanitized reference transform plus exact public hashes, "
                              "sizes, asset inventory and per-consumer hash references; "
                              "genuine original-to-sanitized proof retained privately",
        "baseline_sha256": BASELINE_SHA256,
        "metadata": [{k: r[k] for k in ("path", "original_sha256", "reference_sha256", "public_sha256")}
                     for r in baseline["metadata"]],
        "unchanged_assets": media,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--write", action="store_true")
    parser.add_argument("--receipt", required=True, type=Path)
    args = parser.parse_args()
    receipt = run(Path(__file__).resolve().parents[1], args.write)
    args.receipt.write_bytes(encode(receipt))
    print(json.dumps({"private_fields_removed": 44, "unchanged_assets": len(receipt["unchanged_assets"]),
                      "numeric_and_assertion_equivalence": True}))
