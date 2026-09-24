"""Representative expanded-library PCM proof; no all-track render claim."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import numpy as np
from verify_real_music_exports import compare, decode, RATE

ROOT = Path(__file__).resolve().parents[1]


def mixed_original_gain(actual, music, original, lag, expected=.25):
    start, end = RATE, 3 * RATE
    phase = 2 * np.pi * 440 * np.arange(end - start) / RATE
    basis = np.column_stack((np.sin(phase), np.cos(phase)))
    gains = []
    for channel in (0, 1):
        residual = actual[start + lag:end + lag, channel] - music[start:end, channel]
        observed = np.linalg.lstsq(basis, residual, rcond=None)[0]
        reference = np.linalg.lstsq(basis, original[start:end, channel], rcond=None)[0]
        gain = float(np.linalg.norm(observed) / np.linalg.norm(reference))
        assert abs(gain / expected - 1) <= .12, ("independent original mix gain", channel, gain)
        gains.append(gain)
    return gains


def verify(evidence, revision, output, scope="both-policies"):
    catalog = json.loads((ROOT / r"app\src\main\assets\music\catalog.json").read_text(encoding="utf-8"))
    tracks = {t["id"]: t for t in catalog["tracks"]}
    decodes = list(evidence.rglob("all-native-decode.json"))
    assert len(decodes) == 1
    native = json.loads(decodes[0].read_text())
    assert native["revision"] == revision and native["complete"] and native["nativeStatus"] == "PASS"
    assert len(native["tracks"]) == len(tracks) == 118
    assert {t["id"] for t in native["tracks"]} == set(tracks)
    assert len({t["nativePcmSha256"] for t in native["tracks"]}) == 118
    for t in native["tracks"]:
        original = tracks[t["id"]]
        assert t["assetSha256"] == original["sha256"]
        assert t["cappedFrames"] == t["granuleFrames"] == original["frames"]
        assert original["frames"] <= t["nativeFrames"] <= original["frames"] + 4096
    reports = []
    seen = set()
    for receipt in sorted(evidence.rglob("*result.json")):
        record = json.loads(receipt.read_text(encoding="utf-8"))
        if receipt.name not in ("expanded-result.json", "mixed-result.json", "ui-result.json"):
            continue
        if (receipt.name == "ui-result.json" and not record.get("trackId", "").startswith("abstraction-")
                and scope != "search-fix"):
            continue
        assert record["revision"] == revision and record["nativeStatus"] == "PASS"
        if receipt.name == "expanded-result.json":
            assert record["complete"] and len(record["outputs"]) == 16
            entries = record["outputs"]
        else:
            entries = [dict(record, file=record.get("file", record.get("output")), gain=.5)]
        for entry in entries:
            path = receipt.parent / entry["file"]
            assert hashlib.sha256(path.read_bytes()).hexdigest() == entry["sha256"]
            track = tracks[entry["trackId"]]
            source = decode(ROOT / r"app\src\main\assets" / Path(*track["path"].split("/")),
                            180, track["channels"] == 1)
            actual = decode(path, 180)
            duration = track["frames"] / track["sampleRate"]
            seconds, gain = entry["durationSeconds"], entry["gain"]
            key = (record["policy"], track["id"], gain, seconds, receipt.name)
            assert key not in seen, ("Duplicate export coverage", key)
            seen.add(key)
            probe = json.loads(subprocess.check_output(
                ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(path)], timeout=30))
            video = [s for s in probe["streams"] if s["codec_type"] == "video"]
            audio = [s for s in probe["streams"] if s["codec_type"] == "audio"]
            assert len(video) == len(audio) == 1
            assert (video[0]["width"], video[0]["height"], video[0]["codec_name"]) == (160, 120, "h264")
            assert abs(float(video[0]["duration"]) - seconds) <= .001
            assert (int(audio[0]["sample_rate"]), audio[0]["channels"], audio[0]["codec_name"]) == (RATE, 2, "aac")
            if receipt.name == "mixed-result.json":
                music = source[:4 * RATE] * gain
                original = decode(ROOT / r"app\src\androidTest\assets\real-music\source4.mp4", 4)
                truth = music.copy()
                truth[:len(original)] += original * entry["originalGain"]
                report = compare(actual, truth, 1, 4, 4, aligned_tail=True)
                report["originalToneGains"] = mixed_original_gain(
                    actual, music, original, report["lagRange"][0])
                for wrong in (0, .5, 1):
                    lag = report["lagRange"][0]
                    broken = np.zeros_like(actual)
                    count = min(len(broken) - lag, len(music), len(original))
                    broken[lag:lag + count] = music[:count] + original[:count] * wrong
                    try:
                        mixed_original_gain(broken, music, original, lag)
                    except AssertionError:
                        pass
                    else:
                        raise AssertionError("Accepted missing/wrong original gain")
                report["originalGainNegativesRejected"] = [0, .5, 1]
            else:
                report = compare(actual, source, gain, duration, seconds, aligned_tail=True)
            negatives = []
            if receipt.name != "mixed-result.json":
                for kind in ("wrong-gain", "muted", "wrong-track", "no-loop"):
                    if kind == "no-loop" and duration >= seconds:
                        continue
                    broken = actual.copy()
                    if kind == "wrong-gain":
                        broken *= .5
                    elif kind == "muted":
                        broken[:] = 0
                    elif kind == "wrong-track":
                        other = tracks["heavenly-loop"]
                        other_pcm = decode(ROOT / r"app\src\main\assets" / Path(*other["path"].split("/")))
                        broken = np.tile(other_pcm, (len(actual) // len(other_pcm) + 1, 1))[:len(actual)] * gain
                    else:
                        broken[round(duration * RATE):] = 0
                    try:
                        compare(broken, source, gain, duration, seconds, aligned_tail=True)
                    except AssertionError:
                        negatives.append(kind)
                    else:
                        raise AssertionError("Accepted " + kind)
                    del broken
            reports.append(dict(file=str(path), policy=record["policy"], trackId=track["id"],
                                gain=gain, seconds=seconds, negativesRejected=negatives, **report))
            del source, actual
    expected = 36 if scope == "both-policies" else 20
    assert len(reports) == expected, len(reports)
    assert sum(r["policy"] == "software-avc" for r in reports) == (18 if scope == "both-policies" else 1)
    mono = "abstraction-cfc56edbe16dc83cde0d0344"
    longest = "abstraction-8fae5917ba33861a49611966"
    representatives = [catalog["tracks"][i]["id"] for i in (6, 60, 114)] + [
        mono, longest, "shrine", "challenge-accepted", "blue-meadow-in-green-sky"]
    expected_keys = set()
    for policy in ("default", "software-avc"):
        if scope == "both-policies" or policy == "default":
            expected_keys.update((policy, identifier, gain, 160 if identifier in (mono, longest) else 4,
                                  "expanded-result.json") for identifier in representatives for gain in (1, .5))
            expected_keys.add((policy, mono, .5, 4, "mixed-result.json"))
        expected_keys.add((policy, mono, .5, 54, "ui-result.json"))
    if scope == "search-fix":
        expected_keys.add(("default", "unsolved-investigation", .5, 54, "ui-result.json"))
    assert seen == expected_keys, (expected_keys - seen, seen - expected_keys)
    result = dict(status="PASS", revision=revision, nativeDecodedTracks=118,
                  scope=scope, representativeExports=len(reports), full118RenderCoverage=False, exports=reports)
    output.write_text(json.dumps(result, indent=2), encoding="utf-8")
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scope", choices=("both-policies", "search-fix"), default="both-policies")
    args = parser.parse_args()
    verify(args.evidence, args.revision, args.output, args.scope)
