from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path

sys.dont_write_bytecode = True

FROZEN_PINS = {
    "manifest.json": "c0095261eed250fc8db6d49ce0d0cb9498e1552adac2367f0d4baff86b29362f",
    "android_cases.json": "e0bde89572334532b7a32947702d03e6f10904fb14eb40a903bd93a138b293a2",
    "CONTRACT.txt": "9f5523d13533b20a0f2846d9bef0318c1d861acda42c942c543d94afcd11d5cb",
    "oracle.py": "cf69ba00a8e82b8fec70cff5c96b8237e1673401ca4afff73cddd14f4aee18a1",
    "standard.mp4": "f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2",
}


def verify_frozen_inputs(oracle_root: Path) -> dict:
    oracle_root = oracle_root.resolve()
    checked = {}

    def verify(relative: str, expected: str, size=None):
        path = (oracle_root / relative).resolve()
        if oracle_root not in path.parents or not path.is_file():
            raise ValueError(f"Invalid frozen artifact: {relative}")
        if size is not None and path.stat().st_size != size:
            raise ValueError(f"Frozen artifact size mismatch: {relative}")
        actual = sha256(path)
        if actual != expected:
            raise ValueError(f"Frozen artifact SHA-256 mismatch: {relative}")
        checked[relative] = actual

    for relative, expected in FROZEN_PINS.items():
        verify(relative, expected)
    handoff = json.loads((oracle_root / "handoff.json").read_text(encoding="utf-8"))
    for item in handoff["primary_artifacts"]:
        verify(item["path"], item["sha256"], item["bytes"])
    inventory = json.loads((oracle_root / "inventory.json").read_text(encoding="utf-8"))
    if inventory["file_count"] != len(inventory["files"]):
        raise ValueError("Frozen inventory count mismatch")
    for item in inventory["files"]:
        verify(item["path"], item["sha256"], item["bytes"])
    return checked


def validate_output(path: Path, oracle_root: Path) -> Path:
    resolved = path.resolve()
    oracle_root = oracle_root.resolve()
    if resolved == oracle_root or oracle_root in resolved.parents or resolved in oracle_root.parents:
        raise ValueError(f"Output overlaps frozen authority: {path}")
    if path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction()):
        raise ValueError(f"Linked output is not allowed: {path}")
    if path.is_file() and path.stat().st_nlink != 1:
        raise ValueError(f"Hard-linked output is not allowed: {path}")
    return resolved


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact(path: Path, relative_to: Path) -> dict:
    return {
        "path": str(path.relative_to(relative_to)).replace("/", "\\"),
        "bytes": path.stat().st_size,
        "sha256": sha256(path),
    }


def java_string(value: str) -> str:
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""


def java_number(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    text = repr(float(value))
    if "e" not in text and "." not in text:
        text += ".0"
    return text + "d"


def java_list(values, renderer):
    return "Arrays.asList(" + ", ".join(renderer(value) for value in values) + ")"


def java_value(value):
    if isinstance(value, str):
        return java_string(value)
    if isinstance(value, bool):
        return "Boolean.TRUE" if value else "Boolean.FALSE"
    if isinstance(value, int):
        return f"Integer.valueOf({value})"
    if isinstance(value, float):
        return f"Double.valueOf({java_number(value)})"
    if isinstance(value, list):
        return java_list(value, java_value)
    raise TypeError(type(value))


def java_map(mapping: dict) -> str:
    lines = ["new LinkedHashMap<String, Object>() {{"]  # noqa: RUF012
    for key, value in mapping.items():
        lines.append(f"                        put({java_string(key)}, {java_value(value)});")
    lines.append("                    }}")
    return "\n".join(lines)


def case_contract(case: dict, oracle_root: Path, oracle_module, frame_root: Path) -> dict:
    probes = []
    for probe in case["probes"]:
        probes.append({
            "source_frame": probe["source_frame"],
            "output_seconds": probe["output_seconds"],
            "image": artifact(oracle_root / probe["image"]["path"], oracle_root),
        })
    frame_assets = {}
    start = int(round(case["operation"]["trim"][0] * 24))
    stop = int(round(case["operation"]["trim"][1] * 24))
    for frame in range(start, stop):
        output = frame_root / "android-full-frames" / case["id"] / f"source_{frame:03d}.png"
        validate_output(output, oracle_root)
        output.parent.mkdir(parents=True, exist_ok=True)
        oracle_module.Image.fromarray(oracle_module.transformed(frame, case["operation"])).save(output)
        frame_assets[str(frame)] = artifact(output, frame_root)
    return {
        "id": case["id"],
        "operation": {
            "trim_start_seconds": case["operation"]["trim"][0],
            "trim_end_seconds": case["operation"]["trim"][1],
            "crop_left": case["operation"]["crop"][0],
            "crop_top": case["operation"]["crop"][1],
            "crop_right": case["operation"]["crop"][2],
            "crop_bottom": case["operation"]["crop"][3],
            "rotation_degrees": case["operation"]["rotation"],
            "output_height": case["operation"]["output_height"],
            "speed": case["operation"]["speed"],
            "volume": case["operation"]["volume"],
        },
        "android_edit_config": case["android_edit_config"],
        "width": case["width"],
        "height": case["height"],
        "duration_seconds": case["duration_seconds"],
        "native_fps": case["native_fps"],
        "accepted_cfr_fps": case["accepted_cfr_fps"],
        "native_frame_count": case["native_frame_count"],
        "video_track_count": 1,
        "audio_track_count": 1 if case["audio_required"] else 0,
        "audio_required": case["audio_required"],
        "audio_sample_rate": case["audio_sample_rate"],
        "audio_channels": case["audio_channels"],
        "expected_decoded_audio_samples": case["expected_decoded_audio_samples"],
        "probes": probes,
        "frame_assets": frame_assets,
        "audio_probes": case["audio_probes"],
        "reference": case["reference"],
    }


def build_contract(oracle_root: Path, frame_root: Path) -> dict:
    verify_frozen_inputs(oracle_root)
    validate_output(frame_root, oracle_root)
    sys.path.insert(0, str(oracle_root))
    import oracle as oracle_module  # type: ignore

    manifest = json.loads((oracle_root / "manifest.json").read_text(encoding="utf-8"))
    android_cases = json.loads((oracle_root / "android_cases.json").read_text(encoding="utf-8"))
    handoff = json.loads((oracle_root / "handoff.json").read_text(encoding="utf-8"))
    first_case = manifest["cases"][0]
    return {
        "schema": "independent-video-oracle-android-contract",
        "version": manifest["version"],
        "pins": {
            "source_root": "video-oracle",
            "handoff_version": handoff["version"],
            "manifest": {
                "path": "manifest.json",
                "schema": manifest["schema"],
                "version": manifest["version"],
                "sha256": sha256(oracle_root / "manifest.json"),
            },
            "android_cases": {
                "path": "android_cases.json",
                "schema": android_cases["schema"],
                "sha256": sha256(oracle_root / "android_cases.json"),
            },
            "contract_text": {
                "path": "CONTRACT.txt",
                "sha256": sha256(oracle_root / "CONTRACT.txt"),
            },
            "oracle_py": {
                "path": "oracle.py",
                "sha256": sha256(oracle_root / "oracle.py"),
            },
        },
        "fixture": artifact(oracle_root / manifest["fixture"]["path"], oracle_root),
        "source": {
            "width": manifest["source"]["width"],
            "height": manifest["source"]["height"],
            "fps": manifest["source"]["fps"],
            "frames": manifest["source"]["frames"],
            "sample_rate": manifest["source"]["sample_rate"],
            "channels": manifest["source"]["channels"],
            "pcm_samples": manifest["source"]["pcm_samples"],
            "tone_peak": manifest["source"]["tone_peak"],
            "tone_rms": manifest["source"]["tone_rms"],
            "barcode_bits": len(first_case["barcode_regions"]),
            "barcode_low_rgb": first_case["barcode_low_rgb"],
            "barcode_high_rgb": first_case["barcode_high_rgb"],
        },
        "tolerances": manifest["tolerances"],
        "cases": [case_contract(case, oracle_root, oracle_module, frame_root) for case in manifest["cases"]],
    }


def sync_assets(contract: dict, oracle_root: Path, asset_root: Path, frame_root: Path) -> None:
    validate_output(asset_root, oracle_root)
    asset_root.mkdir(parents=True, exist_ok=True)
    fixture = contract["fixture"]
    fixture_source = oracle_root / fixture["path"]
    fixture_target = asset_root / fixture["path"]
    validate_output(fixture_target, oracle_root)
    fixture_target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(fixture_source, fixture_target)
    for case in contract["cases"]:
        for probe in case["probes"]:
            source = oracle_root / probe["image"]["path"]
            target = asset_root / probe["image"]["path"]
            validate_output(target, oracle_root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)
        for asset in case["frame_assets"].values():
            source = frame_root / asset["path"]
            target = asset_root / asset["path"]
            validate_output(target, oracle_root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target)


def render_case(case: dict) -> list[str]:
    operation = case["operation"]
    reference = case["reference"]
    lines = [
        "        {",
        "            List<OracleContract.BarcodeRegion> barcodeRegions = OracleCoreVerifier.barcodeRegions(",
        "                    new OracleContract.Operation("
        f"{java_number(operation['trim_start_seconds'])}, {java_number(operation['trim_end_seconds'])}, "
        f"{operation['crop_left']}, {operation['crop_top']}, {operation['crop_right']}, {operation['crop_bottom']}, "
        f"{operation['rotation_degrees']}, {operation['output_height']}, {java_number(operation['speed'])}, {java_number(operation['volume'])}));",
        "            List<OracleContract.Probe> probes = new ArrayList<OracleContract.Probe>();",
    ]
    for probe in case["probes"]:
        image = probe["image"]
        lines.append(
            "            probes.add(new OracleContract.Probe("
            f"{probe['source_frame']}, {java_number(probe['output_seconds'])}, "
            f"new OracleContract.Asset({java_string(image['path'])}, {image['bytes']}L, {java_string(image['sha256'])})));"
        )
    lines.append("            LinkedHashMap<Integer, OracleContract.Asset> frameAssets = new LinkedHashMap<Integer, OracleContract.Asset>();")
    for key, asset in case["frame_assets"].items():
        lines.append(
            f"            frameAssets.put(Integer.valueOf({key}), new OracleContract.Asset({java_string(asset['path'])}, {asset['bytes']}L, {java_string(asset['sha256'])}));"
        )
    lines.append("            List<OracleContract.AudioProbe> audioProbes = new ArrayList<OracleContract.AudioProbe>();")
    for probe in case["audio_probes"]:
        lines.append(
            "            audioProbes.add(new OracleContract.AudioProbe("
            f"{java_number(probe['center_seconds'])}, {java_number(probe['window_seconds'])}, "
            f"{java_number(probe['source_seconds'])}, {java_number(probe['frequency_hz'])}, {java_number(probe['rms'])}));"
        )
    lines.extend([
        "            cases.add(new OracleContract.OracleCase(",
        f"                    {java_string(case['id'])},",
        "                    new OracleContract.Operation("
        f"{java_number(operation['trim_start_seconds'])}, {java_number(operation['trim_end_seconds'])}, "
        f"{operation['crop_left']}, {operation['crop_top']}, {operation['crop_right']}, {operation['crop_bottom']}, "
        f"{operation['rotation_degrees']}, {operation['output_height']}, {java_number(operation['speed'])}, {java_number(operation['volume'])}),",
        f"                    {java_map(case['android_edit_config'])},",
        f"                    {case['width']}, {case['height']}, {java_number(case['duration_seconds'])}, {java_number(case['native_fps'])},",
        f"                    {java_list(case['accepted_cfr_fps'], lambda value: 'Double.valueOf(' + java_number(value) + ')')},",
        f"                    {case['native_frame_count']}, {case['video_track_count']}, {case['audio_track_count']},",
        f"                    {str(case['audio_required']).lower()}, {case['audio_sample_rate']},",
        f"                    {case['audio_channels']}, {case['expected_decoded_audio_samples']},",
        "                    barcodeRegions,",
        "                    probes,",
        "                    frameAssets,",
        "                    audioProbes,",
        f"                    new OracleContract.Asset({java_string(reference['path'])}, {reference['bytes']}L, {java_string(reference['sha256'])})));",
        "        }",
    ])
    return lines


def render_java(contract: dict, asset_contract_sha256: str) -> str:
    source = contract["source"]
    tolerances = contract["tolerances"]
    pins = contract["pins"]
    lines = [
        "package com.simple.videoeditor.oracle;",
        "",
        "import java.util.ArrayList;",
        "import java.util.Arrays;",
        "import java.util.LinkedHashMap;",
        "import java.util.List;",
        "",
        "public final class OracleGeneratedContract {",
        "    private OracleGeneratedContract() {",
        "    }",
        "",
        "    public static OracleContract create() {",
        "        List<OracleContract.OracleCase> cases = new ArrayList<OracleContract.OracleCase>();",
    ]
    for case in contract["cases"]:
        lines.extend(render_case(case))
    lines.extend([
        "        return new OracleContract(",
        f"                {java_string(contract['schema'])},",
        f"                {java_string(contract['version'])},",
        "                new OracleContract.Pins(",
        f"                        {java_string(pins['source_root'])},",
        f"                        {java_string(pins['handoff_version'])},",
        f"                        {java_string(pins['manifest']['path'])},",
        f"                        {java_string(pins['manifest']['schema'])},",
        f"                        {java_string(pins['manifest']['version'])},",
        f"                        {java_string(pins['manifest']['sha256'])},",
        f"                        {java_string(pins['android_cases']['path'])},",
        f"                        {java_string(pins['android_cases']['sha256'])},",
        f"                        {java_string(pins['contract_text']['path'])},",
        f"                        {java_string(pins['contract_text']['sha256'])},",
        f"                        {java_string(pins['oracle_py']['path'])},",
        f"                        {java_string(pins['oracle_py']['sha256'])},",
        "                        \"video-oracle\\\\android-contract.json\",",
        f"                        {java_string(asset_contract_sha256)}),",
        "                new OracleContract.Source(",
        f"                        {source['width']}, {source['height']}, {source['fps']}, {source['frames']},",
        f"                        {source['sample_rate']}, {source['channels']}, {source['pcm_samples']},",
        f"                        {java_number(source['tone_peak'])}, {java_number(source['tone_rms'])},",
        f"                        {source['barcode_bits']}, {source['barcode_low_rgb']}, {source['barcode_high_rgb']}),",
        "                new OracleContract.Tolerances(",
        f"                        {java_number(tolerances['rgb_region_max_channel_error'])},",
        f"                        {java_number(tolerances['rgb_frame_mae'])},",
        f"                        {java_number(tolerances['rgb_frame_p95_absolute_error'])},",
        f"                        {java_number(tolerances['barcode_luma_error'])},",
        f"                        {java_number(tolerances['marker_max_channel_error'])},",
        f"                        {tolerances['source_frame_alignment']},",
        f"                        {java_number(tolerances['video_start_seconds'])},",
        f"                        {java_number(tolerances['video_duration_extra_seconds'])},",
        f"                        {java_number(tolerances['pts_cadence_seconds'])},",
        f"                        {java_number(tolerances['audio_start_seconds'])},",
        f"                        {java_number(tolerances['audio_pts_continuity_seconds'])},",
        f"                        {java_number(tolerances['audio_duration_seconds'])},",
        f"                        {java_number(tolerances['audio_rms_relative'])},",
        f"                        {java_number(tolerances['audio_rms_absolute'])},",
        f"                        {java_number(tolerances['audio_frequency_hz'])}),",
        f"                new OracleContract.Asset({java_string(contract['fixture']['path'])}, {contract['fixture']['bytes']}L, {java_string(contract['fixture']['sha256'])}),",
        "                cases);",
        "    }",
        "}",
    ])
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--oracle-root", required=True)
    parser.add_argument("--repo-root", default=str(Path(__file__).resolve().parents[1]))
    args = parser.parse_args()

    oracle_root = Path(args.oracle_root).resolve()
    repo_root = Path(args.repo_root).resolve()
    asset_root = repo_root / "app" / "src" / "main" / "assets" / "video-oracle"
    java_path = repo_root / "app" / "src" / "main" / "java" / "com" / "simple" / "videoeditor" / "oracle" / "OracleGeneratedContract.java"
    contract_path = asset_root / "android-contract.json"
    frame_root = repo_root / "app" / "build" / "oracle-generation"
    for output in (frame_root, asset_root, java_path, contract_path):
        validate_output(output, oracle_root)

    contract = build_contract(oracle_root, frame_root)
    sync_assets(contract, oracle_root, asset_root, frame_root)
    contract_path.write_text(json.dumps(contract, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    java_path.write_text(render_java(contract, sha256(contract_path)), encoding="utf-8")


if __name__ == "__main__":
    main()
