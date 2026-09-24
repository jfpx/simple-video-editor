"""Independent scalar SDR oracle over actual FFmpeg-decoded native color exports."""
import json
from pathlib import Path
import tarfile
import run_emulator_validation as oracle


def expected(rgb, brightness, contrast, saturation):
    linear = [v / 255 / 4.5 if v / 255 < .0812
              else ((v / 255 + .099) / 1.099) ** (20 / 9) for v in rgb]
    luminance = sum(v * w for v, w in zip(linear, (.2126, .7152, .0722)))
    pivot = ((.5 + .099) / 1.099) ** (20 / 9)
    transformed = [max(0, min(1, (luminance + saturation / 100 * (v - luminance) - pivot)
                             * contrast / 100 + pivot + brightness / 100)) for v in linear]
    return tuple(round(255 * (v * 4.5 if v < .018 else 1.099 * v ** .45 - .099))
                 for v in transformed)


def verify_color_exports(archive_path, evidence, revision):
    directory = evidence / "independent-color"
    directory.mkdir()
    points = [(x, y) for y in (20, 60, 100, 140, 170, 190, 210, 230) for x in (40, 120, 200, 280)]

    def decode(file):
        result = oracle.run_cmd(["ffmpeg", "-v", "error", "-i", str(file),
                                 "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
                                timeout=30, stdout_path=directory / (file.stem + ".rgb"),
                                stderr_path=directory / (file.stem + ".stderr.txt"))
        oracle.require(result.returncode == 0 and len(result.stdout) == 320 * 240 * 3,
                       "Independent color FFmpeg decode failed: " + file.name)
        return result.stdout

    def pixel(frame, x, y):
        offset = (y * 320 + x) * 3
        return tuple(frame[offset:offset + 3])

    reports = []
    with tarfile.open(archive_path) as archive:
        prefix = "files/color-adjust-evidence/"
        source = directory / "palette-source.mp4"
        source.write_bytes(archive.extractfile(prefix + "palette.mp4").read())
        canonical_source_rgb = decode(source)
        # The feature starts after the device decoder. Its untouched decoded source
        # is the input, not FFmpeg's potentially different YUV-to-RGB interpretation.
        decoded_source = directory / "source-decoded.png"
        decoded_source.write_bytes(archive.extractfile(prefix + "source-decoded.png").read())
        source_rgb = decode(decoded_source)
        source_decoder_delta = max(abs(a-b) for a,b in zip(source_rgb, canonical_source_rgb))
        receipts = [json.load(archive.extractfile(m)) for m in archive.getmembers()
                    if m.name.startswith(prefix) and m.name.endswith(".mp4.json")]
        for record in receipts:
            if record.get("revision") != revision or not record.get("name", "").startswith("palette-"):
                continue
            filename = record["output"]
            oracle.require(Path(filename).name == filename and filename.endswith(".mp4"), "Unsafe color output path")
            file = directory / filename
            file.write_bytes(archive.extractfile(prefix + filename).read())
            frame = decode(file)
            settings = record["color"]
            wanted = [expected(pixel(source_rgb, x, y), settings["brightness"],
                               settings["contrast"], settings["saturation"]) for x, y in points]
            error = max(abs(a - b) for (x, y), ref in zip(points, wanted)
                        for a, b in zip(pixel(frame, x, y), ref))
            canonical_error = max(abs(a-b) for x,y in points for a,b in zip(pixel(frame,x,y),
                                  expected(pixel(canonical_source_rgb,x,y), settings["brightness"],
                                           settings["contrast"], settings["saturation"])))
            oracle.require(error <= 24, f"Independent palette mismatch {filename}: {error}")
            if settings["saturation"] == 0:
                spread = max(max(pixel(frame, x, y)) - min(pixel(frame, x, y)) for x, y in points)
                oracle.require(spread <= 3, "Actual encoded saturation-zero output is not grayscale")
            omitted = max(abs(a - b) for (x, y), ref in zip(points, wanted)
                          for a, b in zip(pixel(source_rgb, x, y), ref))
            wrong = max(abs(a - b) for (x, y), ref in zip(points, wanted)
                        for a, b in zip(expected(pixel(source_rgb, x, y), -50, 0, 200), ref))
            oracle.require(omitted > 24 and wrong > 24, "Color negative control is not discriminating")
            reports.append(dict(output=filename, policy=record["policy"], settings=settings,
                                max_channel_error=error, canonical_source_error=canonical_error,
                                omitted_error=omitted, wrong_settings_error=wrong))
    oracle.require({(r["policy"], r["settings"]["brightness"]) for r in reports}
                   == {(p, b) for p in ("default", "software") for b in (0, 25, -20)},
                   "Missing independent color exports for both policies")
    result = dict(status="PASS", revision=revision, outputs=reports,
                  reference="untouched device-decoded input; independent scalar math and FFmpeg output",
                  source_decoder_max_channel_difference=source_decoder_delta)
    (directory / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result
