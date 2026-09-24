"""Generate the small, original SDR palette used only by color-adjustment tests."""
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    work = ROOT / r"app\build\color-adjust-fixture"
    work.mkdir(parents=True, exist_ok=True)
    destination = ROOT / r"app\src\androidTest\assets\color-adjust\palette.mp4"
    destination.parent.mkdir(parents=True, exist_ok=True)
    colors = [(0, 0, 0), (32, 32, 32), (64, 64, 64), (128, 128, 128),
              (192, 192, 192), (255, 255, 255), (255, 0, 0), (0, 255, 0),
              (0, 0, 255), (255, 255, 0), (0, 255, 255), (255, 0, 255),
              (180, 90, 60), (50, 130, 180), (80, 170, 100), (180, 100, 160)]
    pixels = bytearray()
    for y in range(240):
        for x in range(320):
            if y < 160:
                rgb = colors[(y // 40) * 4 + x // 80]
            else:
                ramp = x * 255 // 319
                rgb = ((ramp, ramp, ramp), (ramp, 50, 80),
                       (50, ramp, 80), (50, 80, ramp))[(y - 160) // 20]
            pixels.extend(rgb)
    ppm = work / "palette.ppm"
    ppm.write_bytes(b"P6\n320 240\n255\n" + pixels)
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-loop", "1", "-i", str(ppm), "-t", "1", "-r", "30",
                    "-vf", "scale=out_color_matrix=bt709:out_range=tv",
                    "-c:v", "libx264", "-crf", "10", "-pix_fmt", "yuv420p",
                    "-color_primaries", "bt709", "-color_trc", "bt709",
                    "-colorspace", "bt709", "-color_range", "tv", str(destination)], check=True)
    ppm.unlink()


if __name__ == "__main__":
    main()
