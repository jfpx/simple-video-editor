"""Frozen independent FFmpeg intro controls. Default: verify; --generate: compare/publish.

All commands run from the repository root. Candidates and execution evidence stay
in app\\build\\intro-parity\\generation. Existing assets are never overwritten.
Requires cached FFmpeg, ffprobe, NumPy and Pillow; never downloads dependencies.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

sys.dont_write_bytecode = True

import numpy as np
import PIL
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = Path(r"oracle_tools\build_intro_oracle.py")
OUT = Path(r"app\src\main\assets\intro-oracle")
BUILD = Path(r"app\build\intro-parity\generation")
CANDIDATES = BUILD / "candidates"
AUTHORITY = Path(r"app\src\main\assets\video-oracle")
SOURCE = AUTHORITY / "standard.mp4"
CONTRACT = AUTHORITY / "android-contract.json"
SOURCE_SHA = "f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2"
CONTRACT_SHA = "74878a1d254ad63f88dca45837bb618fa0d053c5eb5fe1164e4bcb63b37231dc"
INTRO = list(range(48, 72))
ORIGINAL = list(range(96))
TIMELINES = {
    "intro.mp4": INTRO,
    "reference.mp4": INTRO + ORIGINAL,
    "missing-intro.mp4": ORIGINAL,
    "reversed-order.mp4": ORIGINAL + INTRO,
    "wrong-duration.mp4": INTRO + INTRO + ORIGINAL,
    "wrong-original-audio.mp4": INTRO + ORIGINAL,
}
FFMPEG = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
          "-y", "-fflags", "+bitexact", "-filter_complex_threads", "1"]
VIDEO = ["-c:v", "libx264", "-preset", "slow", "-crf", "10", "-profile:v", "high",
         "-pix_fmt", "yuv420p", "-threads:v", "1", "-flags:v", "+bitexact",
         "-x264-params", "threads=1:lookahead_threads=1:sync-lookahead=0",
         "-g", "24", "-keyint_min", "24", "-sc_threshold", "0", "-bf", "0",
         "-r", "24", "-fps_mode", "cfr"]
AUDIO = ["-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "1",
         "-threads:a", "1", "-flags:a", "+bitexact"]
MUX = ["-map_metadata", "-1", "-map_chapters", "-1", "-video_track_timescale", "12288",
       "-movflags", "+faststart"]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def pin(path):
    data = path.read_bytes()
    return {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}


def json_bytes(value):
    return (json.dumps(value, indent=2, ensure_ascii=True, allow_nan=False) + "\n").encode("utf-8")


def safe_output(path):
    absolute = ROOT / path
    require(absolute.resolve() == absolute, f"Redirected output: {path}")
    for item in [absolute, *absolute.parents]:
        if item == ROOT:
            break
        require(not item.is_symlink() and not item.is_junction(), f"Linked output: {item}")
    if absolute.exists():
        for item in [absolute, *absolute.rglob("*")] if absolute.is_dir() else [absolute]:
            require(not item.is_symlink() and not item.is_junction(), f"Linked output: {item}")
            require(not item.is_file() or item.stat().st_nlink == 1, f"Hard-linked output: {item}")


class Evidence:
    def __init__(self, generate):
        self.data = {"command": ["python", "-B", str(SCRIPT)] + (["--generate"] if generate else []),
                     "cwd": ".", "commands": [], "checks": {}, "status": "RUNNING"}
        index = 1
        while (BUILD / f"execution-{index:03d}.json").exists():
            index += 1
        self.path = BUILD / f"execution-{index:03d}.json"

    def run(self, command, binary=False):
        command = list(map(str, command))
        result = subprocess.run(command, cwd=ROOT, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=180, check=False)
        record = {"command": command, "returncode": result.returncode,
                  "stderr": result.stderr.decode("utf-8", errors="replace")}
        if binary:
            record.update(stdout_bytes=len(result.stdout),
                          stdout_sha256=hashlib.sha256(result.stdout).hexdigest())
        else:
            record["stdout"] = result.stdout.decode("utf-8", errors="replace")
        self.data["commands"].append(record)
        require(result.returncode == 0, f"Command failed: {command}\n{record['stderr']}")
        return result.stdout if binary else record["stdout"]

    def save(self):
        self.path.write_bytes(json_bytes(self.data))


def frozen_inputs():
    require(pin(SOURCE)["sha256"] == SOURCE_SHA, "Frozen source SHA-256 mismatch")
    require(pin(CONTRACT)["sha256"] == CONTRACT_SHA, "Frozen analytic contract SHA-256 mismatch")
    contract = json.loads(CONTRACT.read_bytes())
    identity = next(case for case in contract["cases"] if case["id"] == "identity")
    require(contract["fixture"] == {"path": "standard.mp4", **pin(SOURCE)}, "Source pin mismatch")
    require(len(identity["frame_assets"]) == 96, "Expected 96 frozen analytic frames")
    frames = []
    for index in ORIGINAL:
        asset = identity["frame_assets"][str(index)]
        path = AUTHORITY / asset["path"]
        require(path.resolve().is_relative_to((ROOT / AUTHORITY).resolve()), "Invalid analytic path")
        require(pin(path) == {k: asset[k] for k in ("bytes", "sha256")}, f"Analytic pin: {path}")
        with Image.open(path) as image:
            frames.append(np.array(image.convert("RGB"), dtype=np.int16))
    require([p["frequency_hz"] for p in identity["audio_probes"]] ==
            [440] * 3 + [660] * 3 + [880] * 3 + [1100] * 3, "Frozen tone schedule mismatch")
    return contract, identity, frames


def concat_command(inputs, destination):
    command = FFMPEG.copy()
    filters, labels = [], []
    for index, (path, seconds) in enumerate(inputs):
        command += ["-i", str(path)]
        filters += [f"[{index}:v:0]trim=end_frame={seconds * 24},setpts=PTS-STARTPTS[v{index}]",
                    f"[{index}:a:0]atrim=end_sample={seconds * 48000},asetpts=PTS-STARTPTS[a{index}]"]
        labels.append(f"[v{index}][a{index}]")
    filters.append("".join(labels) + f"concat=n={len(inputs)}:v=1:a=1[v][a]")
    return command + ["-filter_complex", ";".join(filters), "-map", "[v]", "-map", "[a]"] + \
        VIDEO + AUDIO + MUX + [str(destination)]


def generation_commands():
    intro = CANDIDATES / "intro.mp4"
    reference = CANDIDATES / "reference.mp4"
    commands = [
        FFMPEG + ["-i", str(SOURCE), "-filter_complex",
                  "[0:v:0]trim=start_frame=48:end_frame=72,setpts=PTS-STARTPTS[v];"
                  "[0:a:0]atrim=start_sample=96000:end_sample=144000,asetpts=PTS-STARTPTS[a]",
                  "-map", "[v]", "-map", "[a]"] + VIDEO + AUDIO + MUX + [str(intro)],
        concat_command([(intro, 1), (SOURCE, 4)], reference),
        ["python", "-B", "-c", "import shutil,sys; shutil.copyfile(sys.argv[1],sys.argv[2])",
         str(SOURCE), str(CANDIDATES / "missing-intro.mp4")],
        concat_command([(SOURCE, 4), (intro, 1)], CANDIDATES / "reversed-order.mp4"),
        concat_command([(intro, 1), (intro, 1), (SOURCE, 4)], CANDIDATES / "wrong-duration.mp4"),
        FFMPEG + ["-i", str(reference), "-filter_complex",
                  "[0:a:0]atrim=end_sample=48000,asetpts=PTS-STARTPTS[intro];"
                  "anullsrc=r=48000:cl=mono,atrim=end_sample=192000,asetpts=PTS-STARTPTS[silent];"
                  "[intro][silent]concat=n=2:v=0:a=1[a]",
                  "-map", "0:v:0", "-map", "[a]", "-c:v", "copy"] +
        AUDIO + MUX + [str(CANDIDATES / "wrong-original-audio.mp4")],
    ]
    return commands


def audio_probes(identity, timeline, silent_original=False):
    probes = []
    for second in range(len(timeline) // 24):
        source_second = timeline[second * 24] // 24
        for source in identity["audio_probes"]:
            if int(source["source_seconds"]) != source_second:
                continue
            probe = dict(source)
            probe["center_seconds"] = second + source["source_seconds"] % 1
            if silent_original and second >= 1:
                probe.update(frequency_hz=None, rms=0.0)
            probes.append(probe)
    return probes


def segments(timeline):
    result = []
    for output, source in enumerate(timeline):
        if not result or source != result[-1]["source_end_frame_exclusive"]:
            result.append({"output_start_frame": output, "output_start_seconds": output / 24,
                           "source_start_frame": source, "source_start_seconds": source / 24})
        result[-1].update(output_end_frame_exclusive=output + 1,
                          output_end_seconds=(output + 1) / 24,
                          source_end_frame_exclusive=source + 1, source_end_seconds=(source + 1) / 24)
    return result


def make_manifest(directory, contract, identity, tools):
    positive = ["video.duration", "video.frame_count", "video.temporal_barcode",
                "video.spatial_mae", "video.spatial_p95", "video.pixel_regions",
                "video.moving_markers", "audio.track_duration", "audio.window_*.frequency",
                "audio.window_*.rms"]
    controls = {
        "reference.mp4": {"expected_result": "PASS", "required_pass_assertion_families": positive,
                          "required_fail_assertion_families": []},
        "missing-intro.mp4": {"expected_result": "FAIL", "required_fail_assertion_families":
                              ["video.duration", "video.frame_count"], "fault": "intro omitted"},
        "reversed-order.mp4": {"expected_result": "FAIL", "required_fail_assertion_families":
                               ["video.temporal_barcode", "audio.window_*.frequency"],
                               "required_pass_assertion_families": ["video.duration", "video.frame_count"],
                               "fault": "complete original precedes intro"},
        "wrong-duration.mp4": {"expected_result": "FAIL", "required_fail_assertion_families":
                               ["video.duration", "video.frame_count"],
                               "fault": "extra complete intro only; intro + intro + original"},
        "wrong-original-audio.mp4": {"expected_result": "FAIL", "required_fail_assertion_families":
                                     ["audio.window_*.rms"], "required_pass_assertion_families":
                                     [p for p in positive if not p.startswith("audio.window_")],
                                     "fault": "reference video stream copied; intro audio content retained "
                                              "(AAC re-encoded); original four seconds replaced with silence",
                                     "required_failed_audio_probe_indices": list(range(3, 15)),
                                     "required_passed_audio_probe_indices": [0, 1, 2]},
    }
    return {
        "version": "intro-concat-1", "case": "imported_intro",
        "authority": "Independent FFmpeg decoded filter concat, NOT Media3. Expected pixels are the "
                     "frozen analytic identity PNGs in android-contract.json, NOT encoded reference output.",
        "source_sha256": SOURCE_SHA, "source": {"path": str(SOURCE), **pin(SOURCE)},
        "analytic_contract": {"path": str(CONTRACT), **pin(CONTRACT)},
        "analytic_frame_assets_root": str(AUTHORITY),
        "analytic_frame_assets": identity["frame_assets"],
        "tolerances": contract["tolerances"],
        "width": 320, "height": 240, "fps": 24, "frame_count": 120, "duration_seconds": 5,
        "sample_rate": 48000, "channels": 1, "expected_decoded_audio_samples": 240000,
        "segments": segments(INTRO + ORIGINAL), "source_frame_timeline": INTRO + ORIGINAL,
        "audio_probes": audio_probes(identity, INTRO + ORIGINAL),
        "original_audio_frequencies_hz": [440, 660, 880, 1100],
        "intro": {"asset": "intro.mp4", "segments": segments(INTRO), "audio_frequency_hz": 880,
                  "frame_count": 24, "duration_seconds": 1},
        "controls": controls,
        "asset_timelines": {name: {"frame_count": len(timeline), "duration_seconds": len(timeline) / 24,
                                  "segments": segments(timeline),
                                  "audio_probes": audio_probes(identity, timeline,
                                                               name == "wrong-original-audio.mp4")}
                            for name, timeline in TIMELINES.items()},
        "generator": {"path": str(SCRIPT), **pin(SCRIPT), "python": sys.version,
                      "numpy": np.__version__, "pillow": PIL.__version__},
        "tools": tools, "command_cwd": ".", "commands": generation_commands(),
        "regenerate_command": ["python", "-B", str(SCRIPT), "--generate"],
        "verify_command": ["python", "-B", str(SCRIPT)],
        "publication_policy": "First validated generation only. Subsequent --generate must match all "
                              "asset and manifest bytes; never repin or overwrite. Manifest SHA-256 is "
                              "returned separately in execution evidence (no self-hash).",
        "assets": {name: pin(directory / name) for name in TIMELINES},
    }


def decoded(evidence, path, video):
    args = ["-map", "0:v:0", "-pix_fmt", "rgb24", "-f", "rawvideo"] if video else \
           ["-map", "0:a:0", "-ac", "1", "-ar", "48000", "-f", "f32le"]
    raw = evidence.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-nostdin",
                        "-threads", "1", "-i", str(path), *args, "-threads", "1", "pipe:1"], True)
    if video:
        return np.frombuffer(raw, dtype=np.uint8).reshape(-1, 240, 320, 3)
    return np.frombuffer(raw, dtype="<f4").astype(np.float64)


def check_pixels(rgb, timeline, ideals, tolerances):
    metrics = dict(mae=0.0, p95=0.0, barcode=0.0, regions=0.0, markers=0.0)
    observed = []
    for image, source in zip(rgb, timeline):
        ideal = ideals[source]
        difference = np.abs(image.astype(np.int16) - ideal)
        metrics["mae"] = max(metrics["mae"], float(difference.mean()))
        metrics["p95"] = max(metrics["p95"], float(np.percentile(difference, 95)))
        number = 0
        for bit in range(7):
            x = 100 + 18 * bit
            top, bottom = (float(image[y:y + 8, x:x + 8].mean()) for y in (100, 116))
            number |= int(top > bottom) << bit
            high = 235 if source & (1 << bit) else 20
            metrics["barcode"] = max(metrics["barcode"], abs(top - high), abs(bottom - (255 - high)))
        observed.append(number)
        rectangles = []
        for y in np.linspace(6, 233, 8):
            for x in np.linspace(6, 313, 10):
                x0, y0 = int(x + 0.5), int(y + 0.5)
                rectangles.append(("regions", x0 - 2, y0 - 2, x0 + 3, y0 + 3))
        x, y = 48 + source * 6 % 216, 136 + source * 2 % 24
        rectangles += [("markers", x + 2, 178, x + 14, 190), ("markers", 146, y + 2, 158, y + 14)]
        for kind, left, top, right, bottom in rectangles:
            expected = ideal[top:bottom, left:right]
            if kind == "regions" and expected.std(axis=(0, 1)).max() >= 2:
                continue
            actual = image[top:bottom, left:right]
            metrics[kind] = max(metrics[kind], float(np.abs(actual.mean(axis=(0, 1)) -
                                                           expected.mean(axis=(0, 1))).max()))
    require(observed == timeline, "Decoded barcode timeline differs from exact source frame sequence")
    limits = dict(mae="rgb_frame_mae", p95="rgb_frame_p95_absolute_error", barcode="barcode_luma_error",
                  regions="rgb_region_max_channel_error", markers="marker_max_channel_error")
    for metric, tolerance in limits.items():
        require(metrics[metric] <= tolerances[tolerance], f"Analytic {metric}: {metrics[metric]}")
    return {"max_errors": metrics, "decoded_source_frame_timeline": observed}


def check_audio(samples, probes, tolerances):
    require(bool(np.isfinite(samples).all()), "Non-finite audio samples")
    results = []
    for probe in probes:
        start = round((probe["center_seconds"] - probe["window_seconds"] / 2) * 48000)
        window = samples[start:start + round(probe["window_seconds"] * 48000)]
        require(len(window) == round(probe["window_seconds"] * 48000), "Truncated audio window")
        rms = float(np.sqrt(np.mean(window ** 2)))
        frequency = None
        if probe["frequency_hz"] is not None:
            spectrum = np.abs(np.fft.rfft(window * np.hanning(len(window)), n=65536))
            frequency = float(np.argmax(spectrum) * 48000 / 65536)
            require(abs(frequency - probe["frequency_hz"]) <= tolerances["audio_frequency_hz"],
                    f"Audio frequency mismatch at {probe['center_seconds']}: {frequency}")
        require(abs(rms - probe["rms"]) <= max(tolerances["audio_rms_absolute"],
                                              probe["rms"] * tolerances["audio_rms_relative"]),
                f"Audio RMS mismatch at {probe['center_seconds']}: {rms}")
        results.append({"center_seconds": probe["center_seconds"], "rms": rms, "frequency_hz": frequency})
    return results


def check_controls(results, identity, tolerances):
    expected = audio_probes(identity, INTRO + ORIGINAL)
    reports = {}
    for name in TIMELINES:
        if name == "intro.mp4":
            continue
        result = results[name]
        timeline = result["video"]["decoded_source_frame_timeline"]
        assertions = {"video.duration": float(result["streams"]["format"]["duration"]) == 5,
                      "video.frame_count": len(timeline) == 120,
                      "video.temporal_barcode": timeline == INTRO + ORIGINAL}
        measured = {probe["center_seconds"]: probe for probe in result["audio"]}
        for index, probe in enumerate(expected):
            actual = measured.get(probe["center_seconds"])
            assertions[f"audio.window_{index}.rms"] = actual is not None and \
                abs(actual["rms"] - probe["rms"]) <= max(tolerances["audio_rms_absolute"],
                                                        probe["rms"] * tolerances["audio_rms_relative"])
            assertions[f"audio.window_{index}.frequency"] = actual is not None and \
                actual["frequency_hz"] is not None and \
                abs(actual["frequency_hz"] - probe["frequency_hz"]) <= tolerances["audio_frequency_hz"]
        passed = all(assertions.values())
        require(passed == (name == "reference.mp4"), f"Unexpected control outcome: {name}")
        if name in ("missing-intro.mp4", "wrong-duration.mp4"):
            require(not assertions["video.duration"] and not assertions["video.frame_count"],
                    f"Missing duration failure: {name}")
        if name == "reversed-order.mp4":
            require(assertions["video.duration"] and assertions["video.frame_count"] and
                    not assertions["video.temporal_barcode"] and
                    any(not value for key, value in assertions.items() if key.endswith(".frequency")),
                    "Reversed-order control not isolated to ordering")
        if name == "wrong-original-audio.mp4":
            require(all(value for key, value in assertions.items() if key.startswith("video.")),
                    "Wrong-audio video assertion failed")
            require(all(assertions[f"audio.window_{i}.{kind}"] for i in range(3)
                        for kind in ("rms", "frequency")), "Wrong-audio intro content changed")
            require(all(not assertions[f"audio.window_{i}.rms"] for i in range(3, 15)),
                    "Wrong-audio must fail across all four original seconds, including 880 Hz")
        reports[name] = {"result": "PASS" if passed else "FAIL", "assertions": assertions}
    return {"authority": "Independent fixture checks, not a Media3/Java verifier execution", "controls": reports}


def validate_media(directory, contract, identity, ideals, evidence):
    results = {}
    reference_rgb = None
    for name, timeline in {"standard.mp4": ORIGINAL, **TIMELINES}.items():
        path = SOURCE if name == "standard.mp4" else directory / name
        seconds = len(timeline) / 24
        info = json.loads(evidence.run(["ffprobe", "-v", "error", "-show_entries",
                          "stream=codec_type,codec_name,width,height,pix_fmt,sample_aspect_ratio,"
                          "r_frame_rate,sample_rate,channels,duration,nb_frames,start_time:"
                          "format=duration", "-of", "json", str(path)]))
        streams = info["streams"]
        require(len(streams) == 2, f"{name}: expected exactly two streams")
        video = next(s for s in streams if s["codec_type"] == "video")
        audio = next(s for s in streams if s["codec_type"] == "audio")
        require((video["codec_name"], video["width"], video["height"], video["pix_fmt"],
                 video["r_frame_rate"], int(video["nb_frames"])) ==
                ("h264", 320, 240, "yuv420p", "24/1", len(timeline)), f"{name}: video format")
        require(video.get("sample_aspect_ratio", "1:1") == "1:1", f"{name}: non-square pixels")
        require((audio["codec_name"], audio["sample_rate"], audio["channels"]) ==
                ("aac", "48000", 1), f"{name}: audio format")
        for track in [video, audio, info["format"]]:
            require(abs(float(track["duration"]) - seconds) < 0.001, f"{name}: duration")
            require(abs(float(track.get("start_time", 0))) < 0.001, f"{name}: start time")
        timestamps = json.loads(evidence.run(["ffprobe", "-v", "error", "-select_streams", "v:0",
                      "-show_frames", "-show_entries", "frame=best_effort_timestamp_time",
                      "-of", "json", str(path)]))["frames"]
        require(len(timestamps) == len(timeline), f"{name}: decoded frame count")
        require(all(abs(float(frame["best_effort_timestamp_time"]) - i / 24) < 0.00001
                    for i, frame in enumerate(timestamps)), f"{name}: frame PTS")
        rgb, samples = decoded(evidence, path, True), decoded(evidence, path, False)
        require(len(rgb) == len(timeline), f"{name}: raw frame count")
        require(0 <= len(samples) - round(seconds * 48000) < 1024, f"{name}: audio padding")
        probes = audio_probes(identity, timeline, name == "wrong-original-audio.mp4")
        results[name] = {"streams": info, "video": check_pixels(rgb, timeline, ideals, contract["tolerances"]),
                         "decoded_audio_samples": len(samples),
                         "audio": check_audio(samples, probes, contract["tolerances"])}
        if name == "reference.mp4":
            reference_rgb = rgb
        if name == "wrong-original-audio.mp4":
            require(np.array_equal(rgb, reference_rgb), "Wrong-audio video is not identical to reference")
    require(pin(directory / "missing-intro.mp4") == pin(SOURCE), "Missing-intro must be exact source copy")
    hashes = []
    for name in ("reference.mp4", "wrong-original-audio.mp4"):
        hashes.append(evidence.run(["ffmpeg", "-v", "error", "-nostdin", "-i", str(directory / name),
                                   "-map", "0:v:0", "-c:v", "copy", "-f", "hash", "-hash", "sha256", "pipe:1"]))
    require(hashes[0] == hashes[1], "Wrong-audio compressed video packets changed")
    evidence.data["checks"]["media"] = results
    evidence.data["checks"]["control_outcomes"] = check_controls(results, identity, contract["tolerances"])
    evidence.data["checks"]["wrong_audio_video_packet_hash"] = hashes[0].strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate", action="store_true", help="Generate candidates, then compare or first-publish")
    args = parser.parse_args()
    os.chdir(ROOT)
    safe_output(BUILD)
    safe_output(OUT)
    BUILD.mkdir(parents=True, exist_ok=True)
    evidence = Evidence(args.generate)
    try:
        contract, identity, ideals = frozen_inputs()
        evidence.data["checks"]["source"] = pin(SOURCE)
        tools = {tool: evidence.run([tool, "-version"]).strip() for tool in ("ffmpeg", "ffprobe")}
        manifest_path = OUT / "manifest.json"
        if manifest_path.exists():
            expected = make_manifest(OUT, contract, identity, tools)
            require(manifest_path.read_bytes() == json_bytes(expected),
                    "Frozen manifest/assets/generator/tool identity mismatch; refusing to repin")
        else:
            require(args.generate, "No frozen manifest; run --generate once to publish")
            require(not OUT.exists() or not any(OUT.iterdir()), "Assets exist without manifest; refusing to repin")
        if args.generate:
            CANDIDATES.mkdir(parents=True, exist_ok=True)
            for command in generation_commands():
                evidence.run(command)
            validate_media(CANDIDATES, contract, identity, ideals, evidence)
            candidate = json_bytes(make_manifest(CANDIDATES, contract, identity, tools))
            (CANDIDATES / "manifest.json").write_bytes(candidate)
            if manifest_path.exists():
                require(candidate == manifest_path.read_bytes(), "Regenerated manifest is not byte-identical")
                for name in TIMELINES:
                    require((CANDIDATES / name).read_bytes() == (OUT / name).read_bytes(),
                            f"Regenerated {name} is not byte-identical")
                evidence.data["checks"]["publication"] = "UNCHANGED: exact reproducibility verified"
            else:
                OUT.mkdir(parents=True, exist_ok=True)
                for name in TIMELINES:
                    with (OUT / name).open("xb") as output:
                        output.write((CANDIDATES / name).read_bytes())
                with manifest_path.open("xb") as output:
                    output.write(candidate)
                evidence.data["checks"]["publication"] = "FIRST PUBLICATION: manifest now frozen"
            for name in [*TIMELINES, "manifest.json"]:
                require(pin(CANDIDATES / name) == pin(OUT / name), f"Published bytes mismatch: {name}")
            shutil.rmtree(CANDIDATES)
        else:
            validate_media(OUT, contract, identity, ideals, evidence)
        evidence.data["pins"] = {name: pin(OUT / name) for name in [*TIMELINES, "manifest.json"]}
        evidence.data["status"] = "PASS"
        print(json.dumps({"status": "PASS", "evidence": str(evidence.path), "pins": evidence.data["pins"]}, indent=2))
    except Exception as error:
        evidence.data.update(status="FAIL", error=str(error))
        raise
    finally:
        evidence.save()


if __name__ == "__main__":
    main()
