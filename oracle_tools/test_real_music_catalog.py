"""Bounded, byte-pinned approved catalog and full original decode checks (existing FFmpeg)."""
import hashlib
import json
from pathlib import Path
import subprocess
import unittest
import numpy as np
from verify_real_music_exports import compare, envelope_errors, PCM16_STEP, RATE
from verify_cc0_hundred_exports import mixed_original_gain

ROOT = Path(__file__).resolve().parents[1] / r"app\src\main\assets\music"
MANIFEST_SHA = "b5f973aeeee9e1b10f20461456fa489a32e8c7954be3dd4951337a1448f34d9f"


class RealMusicCatalogTest(unittest.TestCase):
    def test_all_assets_and_provenance_pinned(self):
        manifest = (ROOT / "manifest.json").read_bytes()
        self.assertEqual(MANIFEST_SHA, hashlib.sha256(manifest).hexdigest())
        rows = json.loads(manifest)["files"]
        self.assertLess(len(rows), 512)
        self.assertEqual({p.relative_to(ROOT).as_posix() for p in ROOT.rglob("*") if p.is_file()},
                         {r["path"] for r in rows} | {"manifest.json"})
        for row in rows:
            path = ROOT.joinpath(*row["path"].split("/"))
            self.assertTrue(path.resolve().is_relative_to(ROOT.resolve()))
            self.assertLessEqual(path.stat().st_size, 12 * 1024 * 1024)
            data = path.read_bytes()
            self.assertEqual(row["bytes"], len(data))
            self.assertEqual(row["sha256"], hashlib.sha256(data).hexdigest())

    def test_original_full_decode_and_metadata(self):
        catalog = json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))
        self.assertEqual(118, len(catalog["tracks"]))
        self.assertEqual(256956282, sum(t["bytes"] for t in catalog["tracks"]))
        approved = json.loads((ROOT / "abstraction-provenance.json").read_text(encoding="utf-8"))["sources"]
        self.assertEqual(109, len(approved))
        expected = {t["id"] for t in approved} | {
            "shrine", "challenge-accepted", "blue-meadow-in-green-sky"}
        expected |= {"heavenly-loop", "unsolved-investigation", "ambient-relaxing-loop",
                     "underwater-ambient-pad", "happy-end-music", "the-journey-begins"}
        self.assertEqual(expected, {t["id"] for t in catalog["tracks"]})
        self.assertEqual({"heavenly-loop", "unsolved-investigation", "ambient-relaxing-loop",
                          "underwater-ambient-pad", "happy-end-music", "the-journey-begins"},
                         {t["id"] for t in catalog["tracks"][:6]})
        native_hashes = set()
        for track in catalog["tracks"]:
            path = ROOT.parent.joinpath(*track["path"].split("/"))
            self.assertEqual(track["sha256"], track["sourceSha256"])
            self.assertEqual(track["sha256"], hashlib.sha256(path.read_bytes()).hexdigest())
            self.assertEqual("CC0-1.0", track["license"])
            self.assertEqual(44100, track["sampleRate"])
            self.assertIn(track["channels"], (1, 2))
            self.assertEqual(track["frames"], track["loop"]["endFrameExclusive"])
            self.assertFalse(track["loop"]["perceptuallyVerified"])
            kind = track.get("playbackKind", "creator-loop")
            self.assertEqual(track["id"] in ("happy-end-music", "the-journey-begins",
                                           "challenge-accepted", "blue-meadow-in-green-sky"),
                              kind == "composition-repeat")
            self.assertLessEqual(track["frames"], 180 * 44100)
            pcm = subprocess.Popen(["ffmpeg", "-v", "error", "-xerror", "-i", str(path),
                                    "-map", "0:a:0", "-f", "f32le", "-acodec", "pcm_f32le", "-"],
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE)
            count, native = 0, hashlib.sha256()
            try:
                while chunk := pcm.stdout.read(65536):
                    count += len(chunk)
                    native.update(chunk)
                    self.assertLessEqual(count, 180 * 44100 * 2 * 4)
                self.assertEqual(0, pcm.wait(timeout=30))
                self.assertEqual(b"", pcm.stderr.read())
            finally:
                if pcm.poll() is None:
                    pcm.kill()
                    pcm.wait()
                pcm.stdout.close()
                pcm.stderr.close()
            self.assertEqual(track["frames"] * track["channels"] * 4, count)
            self.assertNotIn(native.hexdigest(), native_hashes, "duplicate full native PCM")
            native_hashes.add(native.hexdigest())

    def test_approved_hundred_sources_and_licenses(self):
        evidence = json.loads((ROOT / "abstraction-provenance.json").read_text(encoding="utf-8"))
        catalog = {t["id"]: t for t in json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))["tracks"]}
        for source in evidence["sources"]:
            row = catalog[source["id"]]
            for key in ("sha256", "bytes", "channels", "sampleRate", "title"):
                self.assertEqual(source[key], row[key])
            self.assertEqual(source["decodedFrames"], row["frames"])
            self.assertEqual("creator-loop", row["playbackKind"])
            license_file = ROOT.parent.joinpath(*row["licenseFile"].split("/"))
            self.assertEqual(source["licenseSha256"], hashlib.sha256(license_file.read_bytes()).hexdigest())
            self.assertTrue(source["downloadEndpoint"].startswith("https://"))
            self.assertTrue(source["archiveMember"].endswith(".ogg"))

    def test_qualified_expansion_provenance_and_original_pins(self):
        evidence = json.loads((ROOT / "expansion-provenance.json").read_text(encoding="utf-8"))
        catalog = {t["id"]: t for t in json.loads((ROOT / "catalog.json").read_text(encoding="utf-8"))["tracks"]}
        self.assertEqual(4, len(evidence["sources"]))
        for track in evidence["sources"]:
            row = catalog[track["id"]]
            self.assertEqual(track["sha256"], row["sha256"])
            self.assertEqual(track["sourceHTMLSHA256"],
                             hashlib.sha256((ROOT / "evidence" / (track["id"] + ".html")).read_bytes()).hexdigest())
            self.assertIn("CC0-1.0", ROOT.parent.joinpath(*row["licenseFile"].split("/")).read_text(encoding="utf-8"))
        for track_id, sha in (
                ("heavenly-loop", "a842e9e054019132cacc8fd352e7b31c000ebb51e0b227a2511e1bccb4eb166e"),
                ("unsolved-investigation", "2877e423ff90cb99b3082840e66cf79d69a809708466c6f27c750c0e1ce2a912")):
            self.assertEqual(sha, catalog[track_id]["sha256"])

    def test_sub_pcm16_step_endings_are_checked_not_divided_or_skipped(self):
        truth = np.full(2400, PCM16_STEP / 4)
        errors, quiet = envelope_errors(np.full(2400, PCM16_STEP / 2), truth, 1)
        self.assertEqual(0, len(errors))
        self.assertEqual(1, len(quiet))
        with self.assertRaises(AssertionError):
            envelope_errors(truth + PCM16_STEP * 1.01, truth, 1)
        audible = np.full(2400, .1)
        for broken in (np.zeros(2400), audible * .5, audible * 1.26):
            with self.assertRaises(AssertionError):
                envelope_errors(broken, audible, 1)
        self.assertEqual(0, len(envelope_errors(audible * .5, audible, .5)[1]))

    def test_short_export_tail_aligns_to_actual_eos_without_relaxing_audio_bounds(self):
        source = np.random.default_rng(118).normal(0, .05, (4 * RATE, 2))
        actual = np.concatenate((np.zeros((2048, 2)), source))[:4 * RATE - 512] * .5
        with self.assertRaises(AssertionError):
            compare(actual, source, .5, 4, 4)
        result = compare(actual, source, .5, 4, 4, aligned_tail=True)
        self.assertEqual([2048, 2048], result["lagRange"])
        for bad in (actual * .5, np.zeros_like(actual), actual.copy()):
            if np.array_equal(bad, actual):
                bad[-2400:] = 0
            with self.assertRaises(AssertionError):
                compare(bad, source, .5, 4, 4, aligned_tail=True)

    def test_stereo_original_gain_is_measured_independently_of_mono_music(self):
        music = np.random.default_rng(440).normal(0, .03, (4 * RATE, 2))
        tone = .08 * np.sin(2 * np.pi * 440 * np.arange(4 * RATE) / RATE)
        original = np.column_stack((tone, tone))
        for gain in (0, .25, .5, 1):
            actual = np.concatenate((np.zeros((2048, 2)), music + gain * original))
            if gain == .25:
                np.testing.assert_allclose(mixed_original_gain(actual, music, original, 2048), [.25, .25])
            else:
                with self.assertRaises(AssertionError):
                    mixed_original_gain(actual, music, original, 2048)


if __name__ == "__main__":
    unittest.main()
