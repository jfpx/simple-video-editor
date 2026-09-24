"""Verify the shipped text pack, or independently encode a NEW analytic pack with FFmpeg.

No font files, Android renderer, or candidate-derived expectations are used.
O is an ellipse ring; I is a rectangle. These are topology witnesses, not a
pixel-exact rendering of an OEM font. Run from the repository root.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess
import zlib

ASSETS = Path(r"app\src\main\assets\text-oracle")
BASE = Path(r"app\src\main\assets\video-oracle")
SOURCE_SHA = "f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2"


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def png(path, wrong=False, dx=0, dy=0, scale=1, color=(255, 255, 255), backing=True):
    # 4x area coverage of original geometric primitives; no installed/bundled font.
    raw = bytearray()
    for y in range(240):
        raw.append(0)
        for x in range(320):
            coverage = 0
            for sy in range(4):
                for sx in range(4):
                    px = (x + (sx + .5) / 4 - 160 - dx) / scale + 160
                    py = (y + (sy + .5) / 4 - 120 - dy) / scale + 120
                    ox = 165 if wrong else 154
                    ix0, ix1 = (140, 145) if wrong else (175, 180)
                    outer = ((px - ox) / 15) ** 2 + ((py - 120.5) / 17.5) ** 2 <= 1
                    inner = ((px - ox) / 10.5) ** 2 + ((py - 120.5) / 13) ** 2 < 1
                    coverage += (outer and not inner) or (ix0 <= px < ix1 and 103 <= py < 138)
            alpha_ink = coverage / 16
            panel_x = (x + .5 - 160 - dx) / scale + 160
            panel_y = (y + .5 - 120 - dy) / scale + 120
            alpha_panel = .6 if backing and 136 <= panel_x < 184 and 91 <= panel_y < 149 else 0
            alpha = alpha_ink + alpha_panel * (1 - alpha_ink)
            raw.extend(round(c * alpha_ink / alpha) if alpha else 0 for c in color)
            raw.append(round(255 * alpha))
    def chunk(kind, data):
        return struct.pack(">I", len(data)) + kind + data + struct.pack(">I", zlib.crc32(kind + data))
    path.write_bytes(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", 320, 240, 8, 6, 0, 0, 0))
                     + chunk(b"IDAT", zlib.compress(bytes(raw), 9)) + chunk(b"IEND", b""))


def encode(output, name, **kwargs):
    image = output / (name + ".png")
    png(image, **kwargs)
    command = ["ffmpeg", "-v", "error", "-nostdin", "-y", "-i", str(BASE / "standard.mp4"),
               "-loop", "1", "-framerate", "24", "-i", str(image),
               "-filter_complex", "[0:v][1:v]overlay=shortest=1:format=auto,format=yuv420p[v]",
               "-map", "[v]", "-map", "0:a:0", "-c:v", "libx264", "-preset", "medium", "-crf", "16",
               "-threads", "1", "-c:a", "copy", "-t", "4", "-movflags", "+faststart",
               str(output / (name + ".mp4"))]
    subprocess.run(command, check=True, timeout=120, capture_output=True)
    image.unlink()
    return command


def generate(output):
    if output.exists() and any(output.iterdir()):
        raise ValueError("Generation requires a new/empty repository-local directory; never overwrite pins")
    if not output.resolve().is_relative_to(Path.cwd().resolve()):
        raise ValueError("Output must be repository-local")
    output.mkdir(parents=True, exist_ok=True)
    assert sha(BASE / "standard.mp4") == SOURCE_SHA
    # Six pixels exceeds the fixed four-pixel position tolerance while keeping
    # both glyphs and backing inside the exclusion, leaving visible codes intact.
    commands = [encode(output, "reference"), encode(output, "wrong-text", wrong=True),
                encode(output, "wrong-position", dx=6)]
    shutil.copyfile(BASE / "standard.mp4", output / "no-text.mp4")
    manifest = {
        "version": "text-topology-1", "case": "text_overlay", "text": "OI",
        "source_sha256": SOURCE_SHA, "base_manifest_sha256":
            "c0095261eed250fc8db6d49ce0d0cb9498e1552adac2367f0d4baff86b29362f",
        "production_renderer_used": False, "native_executed": False,
        "font": "None. Original analytic ellipse/rectangle primitives; no font dependency or copied outlines.",
        "editor_semantics": {"overlayText": "OI", "foreground": "#FFFFFFFF",
                             "backing": "#99000000", "absolute_size_px": 48, "anchor": "center",
                             "all_other_edits": "frozen identity"},
        "expected": {"glyphs_left_to_right": ["one closed O ring", "solid narrow I stem"],
                     "O_width": [25, 34], "I_width": [3, 8], "cap_height": [31, 39],
                     "gap": [4, 13], "ink_center": [159.5, 119.5], "center_tolerance": [4, 8],
                     "ring_fill": [.22, .60], "stem_fill_min": .80,
                     "ink_threshold_min_channel": 185, "ink_threshold_chroma_max": 45,
                     "backing_multiplier": .4, "backing_mae_max": 18,
                     "fixed_exclusion_xyxy": [130, 84, 190, 158],
                     "visible_barcode_bits": [0, 1, 5, 6], "spatial_text_probes": 9,
                     "background_audio_tolerances": "unchanged frozen v1"},
        "limits": ["Bounded OI topology, not general OCR or pixel-exact glyph identity.",
                   "Confusable glyphs with the same topology are not distinguished.",
                   "OEM fonts outside fixed bounds fail honestly; no calibration or fallback.",
                   "All frame PTS and four unoccluded code bits checked; nine text/spatial probes.",
                   "Fixed center exclusion hides covered marker regions; horizontal motion remains checked.",
                   "Generated titles, other strings/styles/positions and compositions unverified."],
        "controls": [
            {"path": "reference.mp4", "pass": True, "required_failures": []},
            {"path": "wrong-text.mp4", "pass": False, "required_failures": ["text.probe_0.content"]},
            {"path": "no-text.mp4", "pass": False, "required_failures": ["text.probe_0.content"]},
            {"path": "wrong-position.mp4", "pass": False, "required_failures": ["text.probe_0.position"]}],
        "ffmpeg": subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True,
                                 check=True).stdout.splitlines()[0],
        "commands": commands,
        "assets": [{"path": p.name, "bytes": p.stat().st_size, "sha256": sha(p)}
                   for p in sorted(output.glob("*.mp4"))],
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps([{"path": p.name, "bytes": p.stat().st_size, "sha256": sha(p)}
                      for p in sorted(output.iterdir())], indent=2))


def verify():
    manifest = json.loads((ASSETS / "manifest.json").read_text(encoding="utf-8"))
    assert manifest["version"] == "text-topology-1" and manifest["text"] == "OI"
    assert sha(BASE / "standard.mp4") == SOURCE_SHA
    contract = Path(r"app\src\main\java\com\simple\videoeditor\oracle\TextOracleContract.java").read_text()
    assert sha(ASSETS / "manifest.json") in contract
    for item in manifest["assets"]:
        path = ASSETS / item["path"]
        assert path.stat().st_size == item["bytes"] and sha(path) == item["sha256"]
        assert item["sha256"] in contract
    print("PASS text pack pins (manifest + 4 controls), frozen source unchanged; no native claim")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate", type=Path, help="Explicit new repository-local output, never overwrite")
    args = parser.parse_args()
    generate(args.generate) if args.generate else verify()
