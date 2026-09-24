"""Independent original-recording PCM oracle for bounded 54s/80s exports."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
RATE = 48000
MAX_LAG = 2048
PCM16_STEP = 1 / 32768


def envelope_errors(observed, truth, gain):
    expected = gain * np.sqrt(np.mean(truth.reshape(-1, 2400) ** 2, axis=1))
    actual = np.sqrt(np.mean(observed.reshape(-1, 2400) ** 2, axis=1))
    # Relative gain is undefined below one native PCM16 step. Still check those
    # original quiet endings: RMS error must fit one step, not an unbounded ratio.
    quiet = expected < PCM16_STEP
    assert np.all(np.abs(actual[quiet] - expected[quiet]) <= PCM16_STEP), (
        "non-silent original ending", actual[quiet].tolist(), expected[quiet].tolist())
    relative = actual[~quiet] / expected[~quiet] - 1
    assert np.all(np.abs(relative) <= .25), ("missing music envelope", relative.tolist())
    return relative, [{"bin": int(i), "expectedRms": float(expected[i]), "actualRms": float(actual[i])}
                      for i in np.flatnonzero(quiet)]


def decode(path, max_seconds=92, mono_duplicate=False):
    result = subprocess.run(["ffmpeg", "-v", "error", "-i", str(path), "-map", "0:a:0",
                             "-t", str(max_seconds), "-ar", str(RATE),
                             *(["-af", "pan=stereo|c0=c0|c1=c0"] if mono_duplicate else []), "-ac", "2",
                             "-f", "f32le", "-acodec", "pcm_f32le", "-"],
                            capture_output=True, timeout=45, check=True)
    assert not result.stderr, result.stderr.decode("utf-8", "replace")
    pcm = np.frombuffer(result.stdout, dtype="<f4").reshape(-1, 2).astype(np.float64)
    assert len(pcm) <= max_seconds * RATE and np.isfinite(pcm).all()
    return pcm


def compare(actual, source, gain, duration, output_seconds=54, *, aligned_tail=False):
    # MP4/AAC-LC tail quantization is at most one 1024-frame packet, not a missing loop.
    # The exploratory 54s export decodes to 2,591,744 frames (256 short, 5.33ms).
    assert output_seconds in (4, 54, 80, 160)
    assert abs(len(actual) - output_seconds * RATE) <= 1024, len(actual)
    reference_frames = len(actual) + (MAX_LAG if aligned_tail else 0)
    reference = np.tile(source, ((reference_frames + len(source) - 1) // len(source), 1))[:reference_frames]
    # Preserve every-second/tail coverage and check every complete repeat boundary.
    windows = [(float(t), .5) for t in range(1, output_seconds)]
    if not aligned_tail:
        windows += [(output_seconds - .55, .5)]
    for wrap in np.arange(duration, output_seconds - .65, duration):
        windows += [(float(wrap) - .12, .24), (float(wrap) + .15, .5)]
    records = []
    for start, seconds in windows:
        first, count = round(start * RATE), round(seconds * RATE)
        for channel in (0, 1):
            truth = reference[first:first + count, channel]
            search = actual[first - MAX_LAG:first + count + MAX_LAG, channel]
            size = 1 << (len(search) + len(truth) - 2).bit_length()
            corr = np.fft.irfft(np.fft.rfft(search, size) *
                                np.fft.rfft(truth[::-1], size), size)
            valid = corr[count - 1:len(search)]
            energies = search * search
            summed = np.concatenate(([0.0], np.cumsum(energies)))
            rms_sq = summed[count:] - summed[:-count]
            scores = valid / np.sqrt(np.maximum(rms_sq * np.dot(truth, truth), 1e-30))
            offset = int(np.argmax(scores))
            observed = search[offset:offset + count]
            similarity = float(scores[offset])
            amplitude = float(np.sqrt(np.mean(observed ** 2) / np.mean(truth ** 2)))
            assert similarity >= .90, ("source correlation", start, channel, similarity)
            assert abs(amplitude / gain - 1) <= .12, ("independent gain", start, channel, amplitude, gain)
            # 50ms RMS envelopes catch missing spans, not only average track energy.
            n = count // 2400 * 2400
            envelope, quiet = envelope_errors(observed[:n], truth[:n], gain)
            records.append(dict(start=start, seconds=seconds, channel=channel, lag=offset - MAX_LAG,
                                correlation=similarity, amplitude=amplitude,
                                envelopeMaxRelativeError=float(np.max(np.abs(envelope), initial=0)),
                                subPcm16StepEndingBins=quiet))
        if aligned_tail and len(records) == 2:
            # A 4s AAC output can be 512 frames short with 2048 frames of priming.
            # Align the full final window to actual EOS, rather than searching a
            # truncated window that cannot contain the already-established lag.
            lag = max(r["lag"] for r in records)
            end = min(len(reference), len(actual) - lag)
            windows.append(((end - RATE // 2) / RATE, .5))
    lags = [r["lag"] for r in records]
    assert max(lags) - min(lags) <= 256, ("loop scheduling drift", min(lags), max(lags))
    return dict(minCorrelation=min(r["correlation"] for r in records), lagRange=[min(lags), max(lags)],
                windows=records)


def verify(evidence, revision, output):
    catalog = json.loads((ROOT / r"app\src\main\assets\music\catalog.json").read_text(encoding="utf-8"))
    # This retained regression suite covers the six original fixtures, not the entire expanded library.
    tracks = {t["id"]: t for t in catalog["tracks"][:6]}
    sources = {key: decode(ROOT / "app" / "src" / "main" / "assets" / Path(*t["path"].split("/")))
               for key, t in tracks.items()}
    native_decodes = []
    for path in sorted(evidence.rglob("decode.json")):
        record = json.loads(path.read_text(encoding="utf-8"))
        assert record["revision"] == revision and record["nativeStatus"] == "PASS"
        assert {t["id"] for t in record["tracks"]} == set(tracks) and len(record["tracks"]) == len(tracks)
        for decoded in record["tracks"]:
            track = tracks[decoded["id"]]
            assert decoded["assetSha256"] == track["sha256"]
            assert decoded["granuleFrames"] == decoded["cappedFrames"] == track["frames"]
            assert track["frames"] <= decoded["nativeFrames"] <= track["frames"] + 4096
        native_decodes.append(record)
    assert sorted(d["policy"] for d in native_decodes) == ["default", "software-avc"]
    results = []
    seen = set()
    for result_file in sorted(evidence.rglob("*result.json")):
        record = json.loads(result_file.read_text(encoding="utf-8"))
        if result_file.name in ("expanded-result.json", "mixed-result.json") or (
                record.get("trackId") and record["trackId"] not in tracks):
            continue
        if record.get("revision") != revision:
            continue
        policy = record["policy"]
        entries = record.get("outputs", [dict(file=record.get("output"), sha256=record.get("sha256"))])
        for entry in entries:
            if not entry["file"]:
                continue
            path = result_file.parent / entry["file"]
            assert hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"]
            name = path.stem.removeprefix("ui-")
            track_id, percent = name.rsplit("-", 1)
            gain = int(percent) / 100
            track = tracks[track_id]
            actual = decode(path)
            source = sources[track_id]
            duration = track["frames"] / track["sampleRate"]
            seconds = 80 if track["frames"] > 54 * track["sampleRate"] else 54
            if path.name.startswith("ui-"):
                seconds = 54
                assert record.get("freshPublishedResult") is True
                assert record["publicUri"] != record["previousPublicUri"]
            assert entry.get("durationSeconds", record.get("durationSeconds")) == seconds
            probe = json.loads(subprocess.check_output(
                ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(path)], timeout=30))
            video = [s for s in probe["streams"] if s["codec_type"] == "video"]
            audio = [s for s in probe["streams"] if s["codec_type"] == "audio"]
            assert len(video) == len(audio) == 1
            assert (video[0]["width"], video[0]["height"], video[0]["codec_name"]) == (160, 120, "h264")
            assert abs(float(video[0]["duration"]) - seconds) <= .001
            assert (int(audio[0]["sample_rate"]), audio[0]["channels"], audio[0]["codec_name"]) == (RATE, 2, "aac")
            report = compare(actual, source, gain, duration, seconds)
            negatives = []
            for kind in ("no-loop", "muted", "wrong-gain"):
                broken = actual.copy()
                if kind == "no-loop":
                    broken[round(duration * RATE):] = 0
                elif kind == "muted":
                    broken[:] = 0
                else:
                    broken *= .5
                try:
                    compare(broken, source, gain, duration, seconds)
                except AssertionError:
                    negatives.append(kind)
                else:
                    raise AssertionError("Negative control accepted: " + kind)
            role = "ui" if path.name.startswith("ui-") else "native"
            identity = (policy, role, track_id, percent)
            assert identity not in seen, ("duplicate real export", identity)
            seen.add(identity)
            results.append(dict(file=str(path), policy=policy, role=role, sha256=entry["sha256"],
                                trackId=track_id, gain=gain, sourceSha256=track["sha256"], durationSeconds=seconds,
                                rejectedControls=negatives, **report))
    expected = {(policy, "native", track, gain) for policy in ("default", "software-avc")
                for track in tracks for gain in ("50", "100")}
    expected |= {(policy, "ui", "unsolved-investigation", "50") for policy in ("default", "software-avc")}
    assert seen == expected, ("incomplete real-track policy inventory", seen, expected)
    output.write_text(json.dumps(dict(status="PASS", revision=revision, exports=len(results),
                                     nativeDecodes=native_decodes,
                                     limits=dict(correlation=.90, gainRelativeError=.12,
                                                  envelopeRelativeError=.25, maxLagFrames=MAX_LAG,
                                                  subPcm16StepEndingRmsError=PCM16_STEP,
                                                 maxLagSpreadFrames=256, aacTailFrames=1024),
                                     perceptualListening=False, results=results), indent=2), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    verify(args.evidence, args.revision, args.output)
