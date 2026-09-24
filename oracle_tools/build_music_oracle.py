"""Generate independent, synthetic music-loop controls; never modify oracle v1."""
import array
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import wave

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "app" / "src" / "main" / "assets" / "music-oracle"
SOURCE = ROOT / "app" / "src" / "main" / "assets" / "video-oracle" / "standard.mp4"


def main():
    expected = "f46a9e6c62af19e04c914692b5e60b3ded37f4cf5bf0ef2c01480519d16482b2"
    if hashlib.sha256(SOURCE.read_bytes()).hexdigest() != expected:
        raise ValueError("Frozen source hash mismatch")
    OUT.mkdir(parents=True, exist_ok=True)
    samples = array.array("h", (
        round(0.16 * 32767 * math.sin(2 * math.pi * 1320 * i / 48000))
        for i in range(48000)
    ))
    if sys.byteorder != "little":
        samples.byteswap()
    with wave.open(str(OUT / "music.wav"), "wb") as audio:
        audio.setparams((1, 2, 48000, 0, "NONE", "not compressed"))
        audio.writeframes(samples.tobytes())
    commands = []
    for name, loop in (("reference.mp4", True), ("no-loop.mp4", False)):
        command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(SOURCE)]
        if loop:
            command += ["-stream_loop", "-1"]
        command += ["-i", str(OUT / "music.wav"), "-map", "0:v:0", "-map", "1:a:0",
                    "-c:v", "copy", "-c:a", "aac", "-b:a", "128k", "-t", "4"]
        if not loop:
            command += ["-af", "apad"]
        command += [str(OUT / name)]
        subprocess.run(command, check=True, timeout=60)
        commands.append([
            Path(value).relative_to(OUT.parent).as_posix() if Path(value).is_absolute() else value
            for value in command
        ])
    manifest = {
        "version": "music-loop-1",
        "source_sha256": expected,
        "sample_rate": 48000, "frequency_hz": 1320, "peak": 0.16,
        "input_seconds": 1, "output_seconds": 4,
        "expected_rms": 0.16 / math.sqrt(2),
        "authority": "Analytic sine PCM; independent FFmpeg looping reference. No Media3 output used.",
        "negative_controls": ["unchanged source audio", "one second music then three seconds silence"],
        "commands": commands,
        "assets": {name: {"bytes": (OUT / name).stat().st_size,
                          "sha256": hashlib.sha256((OUT / name).read_bytes()).hexdigest()}
                   for name in ("music.wav", "reference.mp4", "no-loop.mp4")},
    }
    (OUT / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(manifest["assets"], indent=2))


if __name__ == "__main__":
    main()
