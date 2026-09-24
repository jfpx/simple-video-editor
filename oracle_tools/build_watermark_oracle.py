"""Independent synthetic transparent PNG and encoded witnesses; never uses Android rendering.

Run from the repository root. Default verifies pins; --generate requires a NEW
repository-local directory. Existing packs and analytic expected frames are read-only.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import zlib

ASSETS = Path(r"app\src\main\assets\watermark-oracle")
BASE = Path(r"app\src\main\assets\video-oracle")
SOURCE_SHA = "f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2"
VERSION = "png-watermark-1"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def rgba(x, y):
    if 4 <= x < 20 and 4 <= y < 28:
        return 240, 30, 40, 255
    if 24 <= x < 44 and 4 <= y < 28:
        return 25, 45, 240, 128
    if 48 <= x < 60 and 4 <= y < 16:
        return 25, 230, 55, 255
    if 48 <= x < 60 and 20 <= y < 28:
        return 25, 230, 55, 128
    # Hidden contrasting RGB makes forced opacity an observable defect.
    return 25, 230, 55, 0


def png_bytes():
    raw = bytearray()
    for y in range(32):
        raw.append(0)
        for x in range(64):
            raw.extend(rgba(x, y))

    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))

    return (b"\x89PNG\r\n\x1a\n"
            + chunk(b"IHDR", struct.pack(">IIBBBBB", 64, 32, 8, 6, 0, 0, 0))
            + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def composite(frame, dx=0, opaque=False):
    result = bytearray(frame)
    for y in range(32):
        for x in range(64):
            *color, alpha = rgba(x, y)
            if opaque:
                alpha = 255
            p = ((156 + y) * 320 + 192 + dx + x) * 3
            for channel in range(3):
                result[p + channel] = (color[channel] * alpha
                                       + frame[p + channel] * (255 - alpha) + 127) // 255
    return result


def generate(output):
    if not output.resolve().is_relative_to(Path.cwd().resolve()):
        raise ValueError("Output must be repository-local")
    if output.exists() and any(output.iterdir()):
        raise ValueError("New/empty output required; never overwrite pins")
    assert sha(BASE / "standard.mp4") == SOURCE_SHA
    output.mkdir(parents=True, exist_ok=True)
    (output / "watermark.png").write_bytes(png_bytes())
    decode = ["ffmpeg", "-v", "error", "-nostdin", "-i", str(BASE / "standard.mp4"),
              "-map", "0:v:0", "-pix_fmt", "rgb24", "-f", "rawvideo", "-"]
    frames = subprocess.run(decode, capture_output=True, check=True, timeout=120).stdout
    frame_bytes = 320 * 240 * 3
    assert len(frames) == 96 * frame_bytes
    commands = [decode]
    for name, dx, opaque in (("reference", 0, False), ("misplaced", 8, False), ("opaque", 0, True)):
        video = b"".join(composite(frames[i:i + frame_bytes], dx, opaque)
                         for i in range(0, len(frames), frame_bytes))
        command = ["ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "rawvideo", "-pixel_format", "rgb24",
                   "-video_size", "320x240", "-framerate", "24", "-i", "-",
                   "-i", str(BASE / "standard.mp4"), "-map", "0:v:0", "-map", "1:a:0",
                   "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-threads", "1",
                   "-pix_fmt", "yuv420p", "-c:a", "copy", "-t", "4", "-movflags", "+faststart",
                   str(output / (name + ".mp4"))]
        subprocess.run(command, input=video, capture_output=True, check=True, timeout=120)
        commands.append(command)
    shutil.copyfile(BASE / "standard.mp4", output / "omitted.mp4")
    manifest = {
        "version": VERSION, "case": "png_watermark", "source_sha256": SOURCE_SHA,
        "base_manifest_sha256": "c0095261eed250fc8db6d49ce0d0cb9498e1552adac2367f0d4baff86b29362f",
        "production_renderer_used": False, "native_executed": False,
        "fixture": "Original disjoint red/blue/green rectangles; straight RGBA alpha 0/128/255; no external art",
        "editor_semantics": {"watermark": "watermark.png", "widthFraction": .2, "x": .75, "y": .75,
                             "source_size": [64, 32], "canvas": [320, 240], "rect_xyxy": [192, 156, 256, 188],
                             "all_other_edits": "frozen identity"},
        "expected": {"blend": "(foreground * alpha + analytic_background * (255-alpha))/255",
                     "geometry": "red [4,4,20,28] A255; blue [24,4,44,28] A128; green [48,4,60,16] A255; green [48,20,60,28] A128; otherwise A0",
                     "region_max_channel_mae": 18, "edge_margin_px": 2,
                     "fixed_exclusion_xyxy": [188, 152, 268, 192], "spatial_probes": 9,
                     "visible_barcode_bits": [0, 1, 2, 3, 4, 5, 6],
                     "outside_timing_audio_tolerances": "unchanged frozen v1"},
        "limits": ["Only this fixed watermark on identity main segment; no UI/SAF/preview claim.",
                   "Other sizes/positions, scaling, title/intro/text combinations remain unverified.",
                   "Nine spatial probes; all frame PTS/barcodes/audio retained.",
                   "Fixed exclusion includes codec halo and displaced control; overlapping moving marker skipped only there.",
                   "Reference blends independently decoded source RGB; checker blends original pinned analytic frames, never reference/candidate pixels."],
        "controls": [
            {"path": "reference.mp4", "pass": True, "required_failures": []},
            {"path": "omitted.mp4", "pass": False, "required_failures": ["watermark.probe_0.opaque_red"]},
            {"path": "misplaced.mp4", "pass": False, "required_failures": ["watermark.probe_0.opaque_red"]},
            {"path": "opaque.mp4", "pass": False, "required_failures": ["watermark.probe_0.half_blue", "watermark.probe_0.transparent"]}],
        "ffmpeg": subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True,
                                 check=True).stdout.splitlines()[0],
        "commands": commands,
        "assets": [{"path": p.name, "bytes": p.stat().st_size, "sha256": sha(p)}
                   for p in sorted(output.iterdir())],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps([{"path": p.name, "bytes": p.stat().st_size, "sha256": sha(p)}
                      for p in sorted(output.iterdir())], indent=2))


def verify():
    manifest = json.loads((ASSETS / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["version"] == VERSION and len(manifest["controls"]) == 4
    assert sha(BASE / "standard.mp4") == SOURCE_SHA
    assert (ASSETS / "watermark.png").read_bytes() == png_bytes()
    contract = Path(r"app\src\main\java\com\simple\videoeditor\oracle\WatermarkOracleContract.java").read_text()
    assert sha(ASSETS / "manifest.json") in contract
    for item in manifest["assets"]:
        path = ASSETS / item["path"]
        assert path.stat().st_size == item["bytes"] and sha(path) == item["sha256"]
        assert item["sha256"] in contract
    print("PASS watermark manifest + synthetic PNG + 4 encoded controls; frozen source unchanged; native UNVERIFIED")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate", type=Path)
    args = parser.parse_args()
    generate(args.generate) if args.generate else verify()
