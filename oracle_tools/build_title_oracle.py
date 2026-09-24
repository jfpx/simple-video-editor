"""Independent finite generated-title witnesses. No fonts or production rendering."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess

from build_text_oracle import BASE, SOURCE_SHA, png, sha

ASSETS = Path(r"app\src\main\assets\title-oracle")


def generate(output):
    if not output.resolve().is_relative_to(Path.cwd().resolve()):
        raise ValueError("Output must be repository-local")
    if output.exists() and any(output.iterdir()):
        raise ValueError("Never overwrite a pinned pack")
    output.mkdir(parents=True, exist_ok=True)
    assert sha(BASE / "standard.mp4") == SOURCE_SHA
    controls = [
        ("reference", True, []),
        ("missing-title", False, ["text.probe_0.content"]),
        ("wrong-title", False, ["text.probe_0.content"]),
        ("wrong-duration", False, ["video.duration"]),
        ("wrong-order", False, ["text.probe_0.content", "video.temporal_barcode"]),
        ("audible-title", False, ["audio.window_0.rms"]),
        ("muted-main", False, ["audio.window_3.rms"]),
        ("wrong-background", False, ["title.probe_0.background"]),
    ]
    commands = []
    for name, _, _ in controls:
        image = output / (name + ".png")
        png(image, wrong=name == "wrong-title", backing=False,
            color=(0, 0, 0) if name == "missing-title" else (255, 255, 255))
        duration = 2 if name == "wrong-duration" else 1
        background = "red" if name == "wrong-background" else "black"
        prefix = (f"color=c={background}:s=320x240:r=30:d={duration}[bg];"
                  f"[bg][1:v]overlay=shortest=1:format=auto,format=yuv420p,"
                  f"trim=duration={duration},setpts=PTS-STARTPTS[title];"
                  "[0:v]trim=duration=4,setpts=PTS-STARTPTS[main];")
        video = "[main][title]" if name == "wrong-order" else "[title][main]"
        sound = (f"sine=frequency=440:sample_rate=48000:duration={duration}"
                 if name == "audible-title" else
                 f"anullsrc=r=48000:cl=mono,atrim=duration={duration}")
        audio = f"{sound}[silence];[0:a]atrim=duration=4,asetpts=PTS-STARTPTS"
        if name == "muted-main":
            audio += ",volume=0"
        audio += "[original];"
        audio_order = "[original][silence]" if name == "wrong-order" else "[silence][original]"
        filters = prefix + video + "concat=n=2:v=1:a=0[v];" + audio + audio_order + "concat=n=2:v=0:a=1[a]"
        command = ["ffmpeg", "-v", "error", "-nostdin", "-y",
                   "-i", str(BASE / "standard.mp4"), "-loop", "1", "-framerate", "30",
                   "-i", str(image), "-filter_complex", filters, "-map", "[v]", "-map", "[a]",
                   "-c:v", "libx264", "-preset", "medium", "-crf", "16", "-threads", "1",
                   "-fps_mode", "vfr", "-video_track_timescale", "120000",
                   "-c:a", "aac", "-b:a", "192k", "-ar", "48000", "-ac", "1",
                   "-t", str(duration + 4), "-movflags", "+faststart", str(output / (name + ".mp4"))]
        subprocess.run(command, check=True, timeout=120, capture_output=True)
        commands.append(command)
        image.unlink()
    manifest = {
        "version": "title-intro-1", "case": "generated_title",
        "source_sha256": SOURCE_SHA, "production_renderer_used": False, "native_executed": False,
        "config": {"text": "OI", "textSizeSp": 48, "fontStyle": "normal",
                   "textColor": "#FFFFFFFF", "backgroundColor": "#FF000000",
                   "textX": .5, "textY": .5, "durationMs": 1000},
        "expectations": {
            "reference_scaled_density": 1, "supported_scaled_density": [.75, 4],
            "geometry": "Original analytic ellipse ring and rectangle stem, normalized to 48px em",
            "normalization": "Known display scaledDensity only; never measured candidate bounds",
            "title": "30 static frames / 1 second, white OI on black, silent PCM",
            "main": "Original 96 frames / 4 seconds at 24fps, all barcodes and frozen spatial probes",
            "audio": "Three title silence windows, twelve original tone/RMS windows; continuous PCM",
            "tolerances": "Unchanged text-topology-1 and base v1; black background MAE <=18",
            "limits": "One fixed normal OI configuration; topology not OCR; no arbitrary styles/combinations",
        },
        "controls": [{"path": n + ".mp4", "pass": p, "required_failures": f} for n, p, f in controls],
        "assets": [{"path": p.name, "bytes": p.stat().st_size, "sha256": sha(p)}
                   for p in sorted(output.glob("*.mp4"))],
        "ffmpeg": subprocess.run(["ffmpeg", "-version"], capture_output=True, text=True,
                                 check=True).stdout.splitlines()[0], "commands": commands,
    }
    (output / "manifest.json").write_bytes((json.dumps(manifest, indent=2) + "\n").encode())
    print(json.dumps([{"path": p.name, "bytes": p.stat().st_size, "sha256": sha(p)}
                      for p in sorted(output.iterdir())], indent=2))


def verify():
    manifest = json.loads((ASSETS / "manifest.json").read_bytes())
    contract = Path(r"app\src\main\java\com\simple\videoeditor\oracle\TitleOracleContract.java").read_text()
    assert manifest["version"] == "title-intro-1"
    assert sha(BASE / "standard.mp4") == SOURCE_SHA
    assert sha(ASSETS / "manifest.json") in contract
    for asset in manifest["assets"]:
        path = ASSETS / asset["path"]
        assert path.stat().st_size == asset["bytes"] and sha(path) == asset["sha256"]
        assert asset["sha256"] in contract
    print("PASS title manifest + 8 encoded controls pinned; native UNVERIFIED")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--generate", type=Path)
    args = parser.parse_args()
    generate(args.generate) if args.generate else verify()
