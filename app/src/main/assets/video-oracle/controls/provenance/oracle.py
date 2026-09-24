"""Independent, synthetic Android editor oracle. Run with --help."""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np
from PIL import Image, __version__ as pillow_version


ROOT = Path(__file__).resolve().parent
VERSION = "1.0.0"
W, H, FPS, SECONDS, SR = 320, 240, 24, 4, 48000
FREQUENCIES = [440, 660, 880, 1100]
PEAK = 0.32
COMMANDS = []
TOOLS = {}
TOL = {
    "rgb_region_max_channel_error": 18.0,
    "rgb_frame_mae": 12.0,
    "rgb_frame_p95_absolute_error": 45.0,
    "barcode_luma_error": 36.0,
    "marker_max_channel_error": 36.0,
    "source_frame_alignment": 1,
    "video_start_seconds": 0.005,
    "video_duration_extra_seconds": 0.012,
    "pts_cadence_seconds": 0.002,
    "audio_start_seconds": 0.025,
    "audio_pts_continuity_seconds": 0.00005,
    "audio_duration_seconds": 0.065,
    "audio_rms_relative": 0.12,
    "audio_rms_absolute": 0.002,
    "audio_frequency_hz": 12.0,
}


def relative(path):
    return str(Path(path).resolve().relative_to(ROOT))


def writable(path):
    path = Path(path).resolve()
    if not path.is_relative_to(ROOT):
        raise ValueError(f"Writing outside oracle area is forbidden: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    return path


def save_json(path, data):
    writable(path).write_text(
        json.dumps(data, ensure_ascii=False, indent=2, allow_nan=False) + "\n",
        encoding="utf-8",
    )


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def artifact(path):
    return {"path": relative(path), "bytes": Path(path).stat().st_size, "sha256": sha(path)}


def run(args):
    argv = [str(a) for a in args]
    result = subprocess.run(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            cwd=ROOT, timeout=120)
    COMMANDS.append({
        "argv": argv, "windows_command": subprocess.list2cmdline(argv),
        "cwd": str(ROOT), "exit_code": result.returncode,
        "stderr": result.stderr.decode("utf-8", errors="replace")[-6000:],
    })
    if result.returncode:
        raise RuntimeError(COMMANDS[-1])
    return result.stdout


def ff(*args):
    return run([TOOLS["ffmpeg"], "-hide_banner", "-loglevel", "error", "-nostdin",
                "-y", *args])


def tool_versions():
    for name in ("ffmpeg", "ffprobe"):
        TOOLS[name] = shutil.which(name)
        if not TOOLS[name]:
            raise RuntimeError(f"{name} unavailable: encoded-media evidence cannot be generated")
    return {
        "python": sys.version, "numpy": np.__version__, "pillow": pillow_version,
        "ffmpeg": run([TOOLS["ffmpeg"], "-version"]).decode("utf-8"),
        "ffprobe": run([TOOLS["ffprobe"], "-version"]).decode("utf-8"),
        "executables": dict(TOOLS),
    }


def configs():
    base = dict(trim=[0, 4], crop=[0, 0, W, H], rotation=0,
                output_height=0, speed=1.0, volume=1.0)
    edits = [
        ("identity", {}),
        ("crop", dict(crop=[40, 40, 280, 200])),
        ("rotate90", dict(rotation=90)),
        ("rotate180", dict(rotation=180)),
        ("rotate270", dict(rotation=270)),
        ("trim", dict(trim=[1, 3])),
        ("resize", dict(output_height=120)),
        ("mute", dict(volume=0.0)),
        ("volume25", dict(volume=0.25)),
        ("speed2", dict(speed=2.0)),
        ("speed_half", dict(speed=0.5)),
        ("combo", dict(trim=[1, 3], crop=[40, 40, 280, 200],
                       rotation=90, output_height=120, speed=2.0, volume=0.25)),
    ]
    return [dict(base, **edit, id=name) for name, edit in edits]


def dimensions(c):
    l, t, r, b = c["crop"]
    w, h = r - l, b - t
    if c["rotation"] in (90, 270):
        w, h = h, w
    if c["output_height"]:
        new_h = c["output_height"]
        exact_w = w * new_h / h
        if exact_w != int(exact_w) or int(exact_w) % 2 or new_h % 2:
            raise ValueError("v1 deliberately only covers exact, even-sized resizes")
        w, h = int(exact_w), new_h
    return w, h


def source_frame(n):
    a = np.zeros((H, W, 3), np.uint8)
    a[:120, :160] = [210, 40, 40]
    a[:120, 160:] = [35, 190, 55]
    a[120:, :160] = [40, 65, 215]
    a[120:, 160:] = [215, 190, 35]
    for x in range(40, W, 40):
        a[:, x:x + 2] = [28, 28, 28]
    for y in range(40, H, 40):
        a[y:y + 2] = [28, 28, 28]
    for x, y, rgb in [(8, 8, [225, 40, 210]), (288, 8, [30, 215, 215]),
                      (8, 208, [225, 135, 25]), (288, 208, [120, 60, 215])]:
        a[y:y + 24, x:x + 24] = rgb
    a[52:84, 56:64] = [230, 230, 230]
    a[76:84, 56:88] = [230, 230, 230]
    a[56:72, 224:248] = [15, 15, 15]
    a[64:80, 240:256] = [15, 15, 15]
    # Seven binary cells plus their complements; absolute source frame 0..95.
    for bit in range(7):
        x = 96 + bit * 18
        high = (n >> bit) & 1
        a[96:112, x:x + 16] = 235 if high else 20
        a[112:128, x:x + 16] = 20 if high else 235
    x = 48 + (n * 6) % 216
    a[176:192, x:x + 16] = [240, 240, 240]
    a[182:186, x + 6:x + 10] = [10, 10, 10]
    # A second moving component prevents a timecode-only edit from looking valid.
    y = 136 + (n * 2) % 24
    a[y:y + 16, 144:160] = [220, 30, 200]
    return a


def transformed(n, c):
    l, t, r, b = c["crop"]
    a = source_frame(n)[t:b, l:r]
    a = np.rot90(a, -(c["rotation"] // 90)).copy()
    if (a.shape[1], a.shape[0]) != dimensions(c):
        a = np.asarray(Image.fromarray(a).resize(dimensions(c), Image.Resampling.BILINEAR))
    return a


def map_rect(rect, c):
    l, t, r, b = c["crop"]
    x0, y0, x1, y1 = rect
    x0, x1, y0, y1 = x0 - l, x1 - l, y0 - t, y1 - t
    w, h = r - l, b - t
    rot = c["rotation"]
    if rot == 90:
        x0, y0, x1, y1 = h - y1, x0, h - y0, x1
    elif rot == 180:
        x0, y0, x1, y1 = w - x1, h - y1, w - x0, h - y0
    elif rot == 270:
        x0, y0, x1, y1 = y0, w - x1, y1, w - x0
    out_w, out_h = dimensions(c)
    scale = out_h / (w if rot in (90, 270) else h)
    rect = [math.ceil(x0 * scale), math.ceil(y0 * scale),
            math.floor(x1 * scale), math.floor(y1 * scale)]
    if not (0 <= rect[0] < rect[2] <= out_w and 0 <= rect[1] < rect[3] <= out_h):
        raise ValueError("Probe lies outside output")
    return rect


def barcode_regions(c):
    return [{"bit": bit,
             "rect": map_rect([100 + 18 * bit, 100, 108 + 18 * bit, 108], c),
             "complement_rect": map_rect([100 + 18 * bit, 116, 108 + 18 * bit, 124], c)}
            for bit in range(7)]


def marker_regions(n, c):
    x = 48 + (n * 6) % 216
    y = 136 + (n * 2) % 24
    return [map_rect([x + 2, 178, x + 14, 190], c),
            map_rect([146, y + 2, 158, y + 14], c)]


def region_mean(a, rect):
    x0, y0, x1, y1 = rect
    return a[y0:y1, x0:x1].mean(axis=(0, 1))


def pixel_regions(a):
    h, w = a.shape[:2]
    regions = []
    for y in np.linspace(6, h - 7, 8).astype(int):
        for x in np.linspace(6, w - 7, 10).astype(int):
            rect = [int(x - 2), int(y - 2), int(x + 3), int(y + 3)]
            patch = a[y - 2:y + 3, x - 2:x + 3].astype(float)
            if patch.std(axis=(0, 1)).max() < 2:
                regions.append({"rect": rect, "rgb": np.round(patch.mean(axis=(0, 1)), 3).tolist()})
    return regions


def expected(c, reference=None):
    start, end = c["trim"]
    duration = (end - start) / c["speed"]
    first, stop = int(start * FPS), int(end * FPS)
    probes = []
    for n in sorted(set(np.linspace(first, stop - 1, 9).round().astype(int).tolist())):
        a = transformed(n, c)
        name = ROOT / "frames" / c["id"] / f"source_{n:03d}.png"
        Image.fromarray(a).save(writable(name))
        probes.append({"source_frame": n, "output_seconds": (n / FPS - start) / c["speed"],
                       "image": artifact(name), "pixel_regions": pixel_regions(a),
                       "marker_regions": [{"rect": rect, "rgb": region_mean(a, rect).tolist()}
                                          for rect in marker_regions(n, c)]})
    audio_probes = []
    if c["volume"]:
        for second in range(SECONDS):
            for offset in (0.25, 0.5, 0.75):
                s = second + offset
                if start <= s < end:
                    audio_probes.append({
                        "center_seconds": (s - start) / c["speed"],
                        "window_seconds": 0.12 / c["speed"],
                        "source_seconds": s, "frequency_hz": FREQUENCIES[second],
                        "rms": PEAK * c["volume"] / math.sqrt(2),
                    })
    return {
        "id": c["id"], "operation": c,
        "android_edit_config": {
            "sourceDurationMs": 4000, "startMs": int(start * 1000), "endMs": int(end * 1000),
            "cropLeft": c["crop"][0] / W, "cropTop": c["crop"][1] / H,
            "cropRight": c["crop"][2] / W, "cropBottom": c["crop"][3] / H,
            "rotationDegrees": c["rotation"], "outputHeight": c["output_height"],
            "speed": c["speed"], "volume": c["volume"], "overlayText": "",
        },
        "width": dimensions(c)[0], "height": dimensions(c)[1],
        "duration_seconds": duration,
        "native_fps": FPS * c["speed"],
        "accepted_cfr_fps": sorted(set([float(FPS), FPS * c["speed"]])),
        "native_frame_count": stop - first,
        "audio_required": c["volume"] != 0,
        "audio_sample_rate": SR, "audio_channels": 1,
        "expected_decoded_audio_samples": round(duration * SR),
        "barcode_regions": barcode_regions(c),
        "barcode_low_rgb": 20, "barcode_high_rgb": 235,
        "probes": probes, "audio_probes": audio_probes,
        "reference": artifact(reference) if reference else None,
    }


def video_encoding(crf=18):
    return ["-c:v", "libx264", "-preset", "medium", "-crf", str(crf),
            "-threads", "1", "-pix_fmt", "yuv420p", "-g", "24", "-bf", "0",
            "-color_range", "tv", "-colorspace", "smpte170m",
            "-color_primaries", "smpte170m", "-color_trc", "smpte170m",
            "-video_track_timescale", "48000"]


def generate_fixture(target=None, frozen_markers=False):
    raw, wav = ROOT / "_source.rgb", ROOT / "source_audio.wav"
    with writable(raw).open("wb") as out:
        for n in range(FPS * SECONDS):
            a = source_frame(n)
            if frozen_markers:
                frozen = source_frame(0)
                frozen[96:128, 96:222] = a[96:128, 96:222]
                a = frozen
            out.write(a.tobytes())
    t = np.arange(SR, dtype=np.float64) / SR
    audio = np.concatenate([PEAK * np.sin(2 * np.pi * freq * t) for freq in FREQUENCIES])
    pcm = np.rint(audio * 32767).astype("<i2")
    if not frozen_markers:
        with wave.open(str(writable(wav)), "wb") as out:
            out.setnchannels(1)
            out.setsampwidth(2)
            out.setframerate(SR)
            out.writeframes(pcm.tobytes())
    fixture = writable(target or ROOT / "standard.mp4")
    try:
        ff("-f", "rawvideo", "-pixel_format", "rgb24", "-video_size", f"{W}x{H}",
           "-framerate", str(FPS), "-i", raw, "-i", wav,
           "-map", "0:v:0", "-map", "1:a:0", *video_encoding(16),
           "-c:a", "aac", "-b:a", "128k", "-ar", str(SR), "-ac", "1",
           "-t", str(SECONDS), "-map_metadata", "-1", "-movflags", "+faststart", fixture)
    finally:
        raw.unlink(missing_ok=True)
    return fixture


def reference_output(source, c, target, crf=18, pitch_wrong=False, freeze=False, output_fps=None):
    l, t, r, b = c["crop"]
    start, end = c["trim"]
    vf = [f"trim=start={start}:end={end}", "settb=1/48000",
          f"setpts=(PTS-STARTPTS)/{c['speed']}",
          f"crop={r-l}:{b-t}:{l}:{t}"]
    if c["rotation"] == 90:
        vf.append("transpose=clock")
    elif c["rotation"] == 180:
        vf.extend(["hflip", "vflip"])
    elif c["rotation"] == 270:
        vf.append("transpose=cclock")
    if c["output_height"]:
        w, h = dimensions(c)
        vf.append(f"scale={w}:{h}:flags=bilinear")
    if freeze:
        vf = [f"tpad=start_mode=clone:start_duration={SECONDS}",
              f"trim=duration={SECONDS}", *vf]
    if output_fps:
        vf.extend([f"fps=fps={output_fps}:start_time=0:round=near",
                   f"trim=duration={(end-start)/c['speed']}"])
    args = ["-i", source, "-map", "0:v:0", "-vf", ",".join(vf),
            "-fps_mode", "cfr", "-r", str(output_fps or FPS * c["speed"]), *video_encoding(crf)]
    if c["volume"]:
        af = [f"atrim=start={start}:end={end}", "asetpts=PTS-STARTPTS"]
        if pitch_wrong:
            af.extend([f"asetrate={int(SR*c['speed'])}", f"aresample={SR}"])
        elif c["speed"] != 1:
            af.append(f"atempo={c['speed']}")
        af.append(f"volume={c['volume']}")
        args += ["-map", "0:a:0", "-af", ",".join(af), "-c:a", "aac",
                 "-b:a", "128k", "-ar", str(SR), "-ac", "1"]
    else:
        args += ["-an"]
    ff(*args, "-map_metadata", "-1", "-movflags", "+faststart", writable(target))
    return target


def probe(path, frames=False):
    args = [TOOLS["ffprobe"], "-v", "error"]
    if frames:
        args += ["-select_streams", "v:0", "-show_frames",
                 "-show_entries", "frame=best_effort_timestamp_time,duration_time"]
    else:
        args += ["-show_streams", "-show_format"]
    return json.loads(run([*args, "-of", "json", path]))


def audio_frames(path):
    return json.loads(run([
        TOOLS["ffprobe"], "-v", "error", "-select_streams", "a:0", "-show_frames",
        "-show_entries", "frame=best_effort_timestamp_time,nb_samples",
        "-of", "json", path,
    ]))["frames"]


def frequency(samples, rate):
    a = samples.astype(np.float64)
    a -= a.mean()
    spectrum = np.abs(np.fft.rfft(a * np.hanning(len(a))))
    k = int(np.argmax(spectrum[1:]) + 1)
    delta = 0.0
    if 0 < k < len(spectrum) - 1:
        l, m, r = np.log(np.maximum(spectrum[k-1:k+2], 1e-20))
        denom = l - 2 * m + r
        if abs(denom) > 1e-12:
            delta = 0.5 * (l - r) / denom
    return (k + delta) * rate / len(a)


def check_output(path, e, tolerance):
    checks, metrics = [], {}

    def check(name, passed, actual=None, expected_value=None):
        checks.append({"assertion": name, "passed": bool(passed),
                       "actual": actual, "expected": expected_value})

    def finish():
        return {"case": e["id"], "candidate": str(Path(path).resolve()),
                "candidate_sha256": sha(path) if Path(path).is_file() else None,
                "passed": bool(checks) and all(x["passed"] for x in checks),
                "failed_assertions": [x["assertion"] for x in checks if not x["passed"]],
                "checks": checks, "metrics": metrics}

    try:
        check("file.exists", Path(path).is_file())
        if not checks[-1]["passed"]:
            return finish()
        check("file.bounded", 0 < Path(path).stat().st_size <= 32 * 1024 * 1024)
        if not checks[-1]["passed"]:
            return finish()
        info = probe(path)
        videos = [s for s in info["streams"] if s["codec_type"] == "video"]
        audios = [s for s in info["streams"] if s["codec_type"] == "audio"]
        check("video.stream_count", len(videos) == 1, len(videos), 1)
        check("audio.stream_count", len(audios) == int(e["audio_required"]),
              len(audios), int(e["audio_required"]))
        if len(videos) != 1:
            return finish()
        v = videos[0]
        rotation = next((float(s["rotation"]) for s in v.get("side_data_list", [])
                         if "rotation" in s), float(v.get("tags", {}).get("rotate", 0)))
        w, h = v["width"], v["height"]
        if round(rotation) % 180 == 90:
            w, h = h, w
        geometry_ok = (w, h) == (e["width"], e["height"])
        check("video.geometry", geometry_ok, [w, h], [e["width"], e["height"]])
        check("video.square_pixels", v.get("sample_aspect_ratio", "1:1") in ("1:1", "N/A"),
              v.get("sample_aspect_ratio"))
        check("video.codec", v.get("codec_name") == "h264", v.get("codec_name"), "h264")
        d = float(v.get("duration", info["format"].get("duration", 0)))
        check("video.duration", abs(d - e["duration_seconds"]) <=
              1 / min(e["accepted_cfr_fps"]) + tolerance["video_duration_extra_seconds"],
              d, e["duration_seconds"])
        check("video.decode_bound", 0 < d <= 12, d, "(0,12]")
        if not checks[-1]["passed"]:
            return finish()
        vf = probe(path, frames=True)["frames"]
        pts = np.array([float(f["best_effort_timestamp_time"]) for f in vf])
        check("video.frames_present", len(pts) > 1, len(pts), ">1")
        if len(pts) < 2:
            return finish()
        check("video.pts_start", abs(float(pts[0])) <= tolerance["video_start_seconds"],
              float(pts[0]), 0)
        steps = np.diff(pts)
        check("video.pts_monotonic", bool(np.all(steps > 0)))
        median_step = float(np.median(steps))
        rate = 1 / median_step if median_step > 0 else 0
        accepted = min(e["accepted_cfr_fps"], key=lambda r: abs(r - rate))
        check("video.fps", abs(rate - accepted) < 0.03, rate, e["accepted_cfr_fps"])
        check("video.pts_cadence", float(np.max(np.abs(steps - 1 / accepted))) <=
              tolerance["pts_cadence_seconds"], float(np.max(np.abs(steps - 1 / accepted))),
              tolerance["pts_cadence_seconds"])
        check("video.frame_count", abs(len(pts) - round(e["duration_seconds"] * accepted)) <= 1,
              len(pts), round(e["duration_seconds"] * accepted))
        check("video.pts_end", abs(float(pts[-1]) + 1 / accepted - e["duration_seconds"]) <=
              1 / accepted + tolerance["video_duration_extra_seconds"],
              float(pts[-1]) + 1 / accepted, e["duration_seconds"])
        if geometry_ok:
            raw = ff("-i", path, "-map", "0:v:0", "-an", "-fps_mode", "passthrough",
                     "-frames:v", "600", "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")
            frames = np.frombuffer(raw, np.uint8).reshape(-1, h, w, 3)
            check("video.decoded_frame_count", len(frames) == len(pts), len(frames), len(pts))
            c = e["operation"]
            first, stop = int(c["trim"][0] * FPS), int(c["trim"][1] * FPS)
            selected = {int(np.argmin(abs(pts - p["output_seconds"]))) for p in e["probes"]}
            temporal_errors, spatial = [], []
            codes = []
            for i, (frame, timestamp) in enumerate(zip(frames, pts)):
                nominal = int(math.floor((timestamp * c["speed"] + c["trim"][0]) * FPS + 0.001))
                allowed = range(max(first, nominal - tolerance["source_frame_alignment"]),
                                min(stop - 1, nominal + tolerance["source_frame_alignment"]) + 1)
                observed = []
                for region in e["barcode_regions"]:
                    observed.append([float(region_mean(frame, region["rect"]).mean()),
                                     float(region_mean(frame, region["complement_rect"]).mean())])
                best_error, best_n = 1e9, None
                for n in allowed:
                    ideal = [[235, 20] if (n >> bit) & 1 else [20, 235] for bit in range(7)]
                    error = float(np.max(np.abs(np.array(observed) - ideal)))
                    if error < best_error:
                        best_error, best_n = error, n
                temporal_errors.append(best_error)
                codes.append({"pts": float(timestamp), "expected_source_frame": nominal,
                              "matched_source_frame": best_n, "max_luma_error": best_error})
                if i in selected and best_n is not None:
                    expected_rgb = transformed(best_n, c)
                    delta = np.abs(frame.astype(float) - expected_rgb.astype(float))
                    regions = pixel_regions(expected_rgb)
                    region_error = max(float(np.max(np.abs(region_mean(frame, p["rect"]) - p["rgb"])))
                                       for p in regions)
                    marker_error = max(float(np.max(np.abs(region_mean(frame, rect) -
                                                             region_mean(expected_rgb, rect))))
                                       for rect in marker_regions(best_n, c))
                    spatial.append({
                        "pts": float(timestamp), "source_frame": best_n,
                        "mae": float(delta.mean()), "p95": float(np.percentile(delta, 95)),
                        "region_error": region_error, "region_count": len(regions),
                        "marker_error": marker_error,
                    })
            check("video.temporal_barcode", len(temporal_errors) == len(pts) and
                  max(temporal_errors, default=1e9) <= tolerance["barcode_luma_error"],
                  max(temporal_errors, default=1e9), tolerance["barcode_luma_error"])
            check("video.spatial_probe_count", len(spatial) == len(selected),
                  len(spatial), len(selected))
            check("video.spatial_mae", bool(spatial) and
                  max(p["mae"] for p in spatial) <= tolerance["rgb_frame_mae"],
                  max((p["mae"] for p in spatial), default=None), tolerance["rgb_frame_mae"])
            check("video.spatial_p95", bool(spatial) and
                  max(p["p95"] for p in spatial) <= tolerance["rgb_frame_p95_absolute_error"],
                  max((p["p95"] for p in spatial), default=None),
                  tolerance["rgb_frame_p95_absolute_error"])
            check("video.pixel_regions", bool(spatial) and
                  max(p["region_error"] for p in spatial) <= tolerance["rgb_region_max_channel_error"],
                  max((p["region_error"] for p in spatial), default=None),
                  tolerance["rgb_region_max_channel_error"])
            check("video.moving_markers", bool(spatial) and
                  max(p["marker_error"] for p in spatial) <= tolerance["marker_max_channel_error"],
                  max((p["marker_error"] for p in spatial), default=None),
                  tolerance["marker_max_channel_error"])
            metrics.update(video_spatial=spatial, video_timecodes=codes)
        if e["audio_required"] and len(audios) == 1:
            a = audios[0]
            check("audio.codec", a.get("codec_name") == "aac", a.get("codec_name"), "aac")
            rate = int(a["sample_rate"])
            check("audio.sample_rate", rate == SR, rate, SR)
            check("audio.channels", a["channels"] == 1, a["channels"], 1)
            check("audio.pts_start", abs(float(a.get("start_time", 0))) <=
                  tolerance["audio_start_seconds"], float(a.get("start_time", 0)), 0)
            ad = float(a.get("duration", 0))
            check("audio.track_duration", abs(ad - e["duration_seconds"]) <=
                  tolerance["audio_duration_seconds"], ad, e["duration_seconds"])
            af = audio_frames(path)
            audio_pts = np.array([float(f["best_effort_timestamp_time"]) for f in af])
            audio_counts = np.array([int(f["nb_samples"]) for f in af])
            check("audio.frames_present", len(af) > 1, len(af), ">1")
            if len(af) < 2:
                return finish()
            gaps = np.diff(audio_pts) - audio_counts[:-1] / rate
            check("audio.pts_continuity", float(np.max(abs(gaps))) <=
                  tolerance["audio_pts_continuity_seconds"], float(np.max(abs(gaps))),
                  tolerance["audio_pts_continuity_seconds"])
            audio_start = float(audio_pts[0])
            check("audio.decoded_pts_start", abs(audio_start) <= tolerance["audio_start_seconds"],
                  audio_start, 0)
            audio_end = float(audio_pts[-1] + audio_counts[-1] / rate)
            check("audio.decoded_pts_end", abs(audio_end - e["duration_seconds"]) <=
                  tolerance["audio_duration_seconds"], audio_end, e["duration_seconds"])
            raw = ff("-i", path, "-map", "0:a:0", "-vn", "-ac", "1", "-ar", str(SR),
                     "-t", "12", "-f", "f32le", "pipe:1")
            samples = np.frombuffer(raw, "<f4")
            check("audio.finite", bool(np.isfinite(samples).all()))
            if not np.isfinite(samples).all():
                return finish()
            check("audio.decoded_sample_count", abs(len(samples) - e["expected_decoded_audio_samples"]) <=
                  math.ceil(tolerance["audio_duration_seconds"] * SR),
                  len(samples), e["expected_decoded_audio_samples"])
            if rate == SR:
                check("audio.timestamped_sample_count", int(audio_counts.sum()) == len(samples),
                      int(audio_counts.sum()), len(samples))
            measured = []
            for index, p in enumerate(e["audio_probes"]):
                left = round((p["center_seconds"] - audio_start - p["window_seconds"] / 2) * SR)
                right = round((p["center_seconds"] - audio_start + p["window_seconds"] / 2) * SR)
                segment = samples[left:right]
                check(f"audio.window_{index}.samples", len(segment) == right - left,
                      len(segment), right - left)
                if not len(segment):
                    continue
                rms = float(np.sqrt(np.mean(segment.astype(float) ** 2)))
                hz = float(frequency(segment, SR))
                check(f"audio.window_{index}.rms", abs(rms - p["rms"]) <=
                      p["rms"] * tolerance["audio_rms_relative"] + tolerance["audio_rms_absolute"],
                      rms, p["rms"])
                check(f"audio.window_{index}.frequency", abs(hz - p["frequency_hz"]) <=
                      tolerance["audio_frequency_hz"], hz, p["frequency_hz"])
                measured.append({"center_seconds": p["center_seconds"], "rms": rms, "frequency_hz": hz})
            metrics["audio_windows"] = measured
            metrics["decoded_audio_samples"] = len(samples)
            metrics["audio_timeline"] = {
                "start_seconds": audio_start, "end_seconds": audio_end,
                "max_continuity_error_seconds": float(np.max(abs(gaps))),
                "frames": len(af), "timestamped_samples": int(audio_counts.sum()),
            }
    except Exception as error:
        check("checker.completed_without_error", False, str(error))
    return finish()


def manifest_data(versions, fixture, cases):
    return {
        "schema": "independent-video-oracle", "version": VERSION,
        "production_pipeline_used": False, "device_tested": False,
        "fixture": artifact(fixture), "analytic_audio": artifact(ROOT / "source_audio.wav"),
        "tools": versions,
        "source": {
            "width": W, "height": H, "fps": FPS, "frames": FPS * SECONDS,
            "duration_seconds": SECONDS, "sample_rate": SR, "channels": 1,
            "pcm_samples": SR * SECONDS, "tone_peak": PEAK,
            "tone_rms": PEAK / math.sqrt(2), "tone_hz_per_source_second": FREQUENCIES,
            "orientation_degrees": 0, "pixel_aspect_ratio": "1:1",
            "encoding": "H.264 yuv420p limited-range SMPTE170M/BT.601, AAC mono 128kbps",
            "ownership": "Original mathematical patterns and synthesized tones; no external media.",
        },
        "conventions": {
            "coordinate_system": "Display-oriented source, top-left origin, x right, y down.",
            "crop": "Pixel-edge rectangle [left,top,right,bottom); normalized by source width/height.",
            "order": ["trim [start,end)", "crop", "clockwise rotation", "aspect-preserving resize",
                      "divide video PTS by speed; pitch-preserving audio tempo", "linear PCM volume"],
            "resize": "Specified output height, preserve aspect, no letterbox/stretch; v1 exact even dimensions only.",
            "rounding": "All crop/trim boundaries exact even pixels/integer source frames; no rounding ambiguity.",
            "reference_interpolation": "FFmpeg bilinear encoded reference; independent NumPy/Pillow bilinear RGB expectations.",
            "timeline": "PTS rebased to zero. source_time = start + output_PTS * speed.",
            "speed": "0.5 and 2.0, pitch preserved. Native retimed FPS or 24fps CFR resampling allowed; VFR rejected.",
            "mute": "Audio track must be absent, not just silent, matching inspected EditConfig behavior.",
            "volume": "Amplitude multiplier, not dB; 0.25 is -12.0412 dB.",
            "display_rotation": "Decode display orientation before checking geometry/pixels; rotation metadata may be used.",
            "barcode": "bit i at x=96+18*i, y=96..112; complement at y=112..128; 235 for one,20 for zero.",
            "moving_markers": {
                "white_black_marker": "[48+(n*6)%216,176,64+(n*6)%216,192)",
                "magenta_marker": "[144,136+(n*2)%24,160,152+(n*2)%24)",
                "check": "Inset both source rectangles by 2 pixels, transform, compare mean RGB at nine probes.",
            },
            "reference_authority": "Analytic pixels/timecode/tone equations are authority. Reference MP4 bytes are NOT expected device bytes.",
        },
        "tolerances": TOL, "cases": cases,
        "limitations": [
            "No Android device evidence yet; these are independent local encoded-media tests.",
            "No overlays, arbitrary rotations, odd sizes, HDR, metadata-rotated input, multiple clips, or variable frame rate.",
            "Tone windows measure envelope, pitch and segment identity, not sample-exact waveform phase.",
            "Spatial checks cover nine frames plus barcode on every decoded frame, not every pixel of every frame.",
            "AAC start/tail and Sonic-vs-atempo transitions allowed only within documented bounds; no device calibration yet.",
            "Tool-version-pinned generation; other FFmpeg builds may produce different hashes. Use shipped fixture as standard.",
        ],
    }


def integrity(manifest):
    problems = []
    paths = [manifest["fixture"], manifest["analytic_audio"]]
    for case in manifest["cases"]:
        paths += [case["reference"]] + [p["image"] for p in case["probes"]]
    for entry in paths:
        path = ROOT / entry["path"]
        if not path.is_file() or path.stat().st_size != entry["bytes"] or sha(path) != entry["sha256"]:
            problems.append(entry["path"])
    return problems


def android_vectors(manifest):
    return {
        "schema": "independent-video-oracle-export-vectors", "version": VERSION,
        "manifest": {"path": "manifest.json", "sha256": sha(ROOT / "manifest.json")},
        "fixture": manifest["fixture"],
        "use": "Export vectors only. Pull real device outputs and run oracle.py verify for full validation.",
        "cases": [
            {"id": e["id"], "edit_config": e["android_edit_config"],
             "expected": {key: e[key] for key in (
                 "width", "height", "duration_seconds", "native_fps", "accepted_cfr_fps",
                 "native_frame_count", "audio_required", "audio_sample_rate",
                 "audio_channels", "expected_decoded_audio_samples")},
             "reference": e["reference"], "output_filename": e["id"] + ".mp4"}
            for e in manifest["cases"]
        ],
    }


def selftest(manifest):
    by_id = {e["id"]: e for e in manifest["cases"]}
    fixture = ROOT / manifest["fixture"]["path"]
    positives = [check_output(ROOT / e["reference"]["path"], e, TOL) for e in manifest["cases"]]
    positives.append(check_output(fixture, by_id["identity"], TOL))
    negatives = []

    def negative(label, candidate, case, required_prefixes):
        result = check_output(candidate, by_id[case], TOL)
        relevant = all(any(f.startswith(prefix) for f in result["failed_assertions"])
                       for prefix in required_prefixes)
        negatives.append({"control": label, "expected_rejection": True,
                          "required_failure_prefixes": required_prefixes,
                          "passed": not result["passed"] and relevant, "verification": result})

    noop_assertions = {
        "crop": ["video.geometry"], "rotate90": ["video.geometry"],
        "rotate180": ["video.pixel_regions"], "rotate270": ["video.geometry"],
        "trim": ["video.duration", "video.temporal_barcode", "audio.window_0.frequency"],
        "resize": ["video.geometry"], "mute": ["audio.stream_count"],
        "volume25": ["audio.window_0.rms"], "speed2": ["video.duration"],
        "speed_half": ["video.duration"], "combo": ["video.geometry", "video.duration"],
    }
    for case, assertions in noop_assertions.items():
        negative(f"unchanged_source_as_{case}", fixture, case, assertions)
    wrongs = [
        ("wrong_crop_same_dimensions", "crop", {"crop": [0, 0, 240, 160]},
         ["video.pixel_regions"], {}),
        ("wrong_rotation_same_dimensions", "rotate90", {"rotation": 270},
         ["video.pixel_regions"], {}),
        ("wrong_trim_same_duration", "trim", {"trim": [0, 2]},
         ["video.temporal_barcode", "audio.window_0.frequency"], {}),
        ("wrong_volume_half_not_quarter", "volume25", {"volume": 0.5},
         ["audio.window_0.rms"], {}),
        ("speed_changes_pitch", "speed2", {}, ["audio.window_0.frequency"], {"pitch_wrong": True}),
        ("frozen_video_correct_duration", "identity", {}, ["video.temporal_barcode"], {"freeze": True}),
    ]
    for label, case, change, assertions, options in wrongs:
        config = dict(by_id[case]["operation"], **change)
        target = ROOT / "negative" / f"{label}.mp4"
        reference_output(fixture, config, target, **options)
        negative(label, target, case, assertions)
    target = generate_fixture(ROOT / "negative" / "frozen_markers_advancing_barcode.mp4",
                              frozen_markers=True)
    negative("frozen_markers_advancing_barcode", target, "identity", ["video.moving_markers"])
    target = writable(ROOT / "negative" / "audio_timestamp_gap.mp4")
    shift = "if(between(PTS*TB,1,2),0.1/TB,0)"
    ff("-i", fixture, "-map", "0", "-c", "copy",
       "-bsf:a", f"setts=pts='PTS+{shift}':dts='DTS+{shift}'", target)
    negative("audio_timestamp_gap", target, "identity", ["audio.pts_continuity"])
    for case in ("rotate90", "combo"):
        target = ROOT / "stress" / f"{case}_crf28.mp4"
        reference_output(fixture, by_id[case]["operation"], target, crf=28)
        result = check_output(target, by_id[case], TOL)
        result["control"] = "additional_lossy_generation_crf28"
        positives.append(result)
    for case in ("speed2", "speed_half"):
        target = ROOT / "stress" / f"{case}_cfr24.mp4"
        reference_output(fixture, by_id[case]["operation"], target, output_fps=24)
        result = check_output(target, by_id[case], TOL)
        result["control"] = "legal_cfr24_resampling"
        positives.append(result)
    broken = integrity(manifest)
    return {
        "schema": "independent-video-oracle-results", "version": VERSION,
        "scope": "LOCAL ONLY; not Android/Media3/device export evidence",
        "manifest_sha256": sha(ROOT / "manifest.json"),
        "tools": manifest["tools"], "artifact_integrity_failures": broken,
        "passed": not broken and all(r["passed"] for r in positives + negatives),
        "positive_count": len(positives), "negative_count": len(negatives),
        "positives": positives, "negative_controls": negatives,
    }


def write_inventory():
    if (ROOT / "results.json").is_file():
        results = json.loads((ROOT / "results.json").read_text(encoding="utf-8"))
        manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
        principal = ["standard.mp4", "source_audio.wav", "manifest.json", "android_cases.json",
                     "oracle.py", "contract_tests.py", "CONTRACT.txt", "results.json", "commands.json",
                     "selftest_results.json", "selftest_commands.json", "cli_contract_results.json"]
        cli = ROOT / "cli_contract_results.json"
        cli_data = json.loads(cli.read_text(encoding="utf-8")) if cli.is_file() else None
        save_json(ROOT / "handoff.json", {
            "version": VERSION, "root": str(ROOT), "device_tested": False,
            "local_encoded_oracle_passed": results["passed"],
            "positive_controls": results["positive_count"],
            "negative_controls": results["negative_count"],
            "cli_contract_tests_passed": cli_data["passed"] if cli_data else None,
            "cli_evidence_matches_current_code": (
                cli_data["standards_after"]["oracle.py"] == sha(ROOT / "oracle.py")
                and cli_data["standards_after"]["manifest.json"] == sha(ROOT / "manifest.json")
            ) if cli_data else False,
            "primary_artifacts": [artifact(ROOT / name) for name in principal if (ROOT / name).is_file()],
            "all_artifact_sizes_and_sha256": "inventory.json",
            "supported_cases": [e["id"] for e in manifest["cases"]],
            "verified_rejections": [n["control"] for n in results["negative_controls"] if n["passed"]],
            "checker": {
                "command_from_oracle_directory": (
                    "python .\\oracle.py verify --case CASE --input .\\device\\CASE.mp4 "
                    "--report .\\reports\\CASE.result.json"),
                "exit_codes": {"0": "all assertions passed", "1": "candidate mismatch", "2": "setup error"},
                "contract": "CONTRACT.txt", "device_vectors": "android_cases.json",
            },
            "parent_next_steps": [
                "Wait for media3-device-selftest migration ownership to finish.",
                "Pin fixture and manifest hashes; confirm migrated EditConfig matches vectors.",
                "Add Android export driver for all 12 fresh configs; export actual standard.mp4.",
                "Pull real outputs; run offline verifier per case and retain device/codec/app revision evidence.",
                "Do not treat local reference verification as device export success.",
            ],
            "limitations": manifest["limitations"],
        })
    files = [artifact(p) for p in sorted(ROOT.rglob("*"))
             if p.is_file() and p.name != "inventory.json" and "__pycache__" not in p.parts]
    save_json(ROOT / "inventory.json", {
        "version": VERSION, "hash_algorithm": "SHA-256",
        "self_excluded": "inventory.json", "file_count": len(files),
        "total_bytes_excluding_inventory": sum(p["bytes"] for p in files), "files": files,
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("generate", help="Generate fixture, independent references, manifest and all selftests")
    sub.add_parser("selftest", help="Recheck frozen artifacts and execute positive/negative controls")
    verify = sub.add_parser("verify", help="Check one exported candidate without changing standards")
    verify.add_argument("--case", required=True)
    verify.add_argument("--input", required=True, type=Path)
    verify.add_argument("--report", required=True, type=Path,
                        help="JSON path beneath this oracle's reports directory, distinct from input")
    sub.add_parser("inventory", help="Refresh SHA-256 inventory after documentation changes")
    args = parser.parse_args()
    versions = None
    try:
        if args.command == "inventory":
            write_inventory()
            return 0
        versions = tool_versions()
        if args.command == "generate":
            fixture = generate_fixture()
            cases = []
            for config in configs():
                reference = ROOT / "reference" / f"{config['id']}.mp4"
                reference_output(fixture, config, reference)
                cases.append(expected(config, reference))
            manifest = manifest_data(versions, fixture, cases)
            save_json(ROOT / "manifest.json", manifest)
            save_json(ROOT / "android_cases.json", android_vectors(manifest))
        else:
            manifest = json.loads((ROOT / "manifest.json").read_text(encoding="utf-8"))
        if args.command in ("generate", "selftest"):
            results = selftest(manifest)
            results["runtime_tools"] = versions
            results["invocation"] = [sys.executable, *sys.argv]
            result_name = "results.json" if args.command == "generate" else "selftest_results.json"
            commands_name = "commands.json" if args.command == "generate" else "selftest_commands.json"
            save_json(ROOT / result_name, results)
            save_json(ROOT / commands_name, {"tools": versions, "commands": COMMANDS})
            (ROOT / "last_error.json").unlink(missing_ok=True)
            write_inventory()
            print(json.dumps({"passed": results["passed"], "positives": results["positive_count"],
                              "negatives": results["negative_count"], "results": str(ROOT / result_name)}))
            return 0 if results["passed"] else 1
        case = next((c for c in manifest["cases"] if c["id"] == args.case), None)
        if not case:
            raise ValueError("Unknown case: " + args.case)
        report = args.report.resolve()
        if (not report.is_relative_to(ROOT / "reports") or report.suffix.lower() != ".json"
                or report == args.input.resolve()):
            raise ValueError("Report must be a distinct .json file beneath oracle\\reports")
        problems = integrity(manifest)
        if problems:
            raise ValueError("Standard artifact integrity failed: " + str(problems))
        result = check_output(args.input.resolve(), case, manifest["tolerances"])
        result.update(manifest_sha256=sha(ROOT / "manifest.json"), tools=versions,
                      invocation=[sys.executable, *sys.argv], commands=COMMANDS)
        save_json(args.report, result)
        print(json.dumps({"passed": result["passed"], "failed_assertions": result["failed_assertions"],
                          "report": str(args.report.resolve())}))
        return 0 if result["passed"] else 1
    except Exception as error:
        save_json(ROOT / "last_error.json", {
            "passed": False, "error": str(error), "tools": versions, "commands": COMMANDS,
            "invocation": [sys.executable, *sys.argv],
        })
        print(str(error), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.dont_write_bytecode = True
    raise SystemExit(main())
