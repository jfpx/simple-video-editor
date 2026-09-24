import json
from pathlib import Path
import shutil
import subprocess
import unittest
from unittest.mock import patch
import uuid

from music_preview_run import MusicPreviewRun, restore_owned_settings


class MusicPreviewRunTest(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1] / "app" / "build" / ("preview-run-test-" + uuid.uuid4().hex)
        self.root.mkdir()

    def tearDown(self):
        shutil.rmtree(self.root)

    def test_closed_console_cannot_abort_or_leave_stale_running(self):
        runner = MusicPreviewRun(self.root, self.root / "progress.txt")
        with patch("builtins.print", side_effect=OSError(22, "Invalid argument")):
            self.assertEqual(0, runner.execute(lambda: runner.note("native PASS"), lambda: None))
        saved = json.loads((self.root / "summary.json").read_text())
        self.assertEqual("PASS", saved["status"])
        self.assertEqual("PASS", saved["cleanup"])
        self.assertIn("OSError", saved["consoleError"])
        self.assertFalse((self.root / "summary.json.pending").exists())

    def test_failure_and_cleanup_failure_both_survive(self):
        runner = MusicPreviewRun(self.root, self.root / "progress.txt")
        def fail():
            raise RuntimeError("native failure")
        def cleanup():
            raise ValueError("restoration failure")
        self.assertEqual(1, runner.execute(fail, cleanup))
        saved = json.loads((self.root / "summary.json").read_text())
        self.assertEqual("FAIL", saved["status"])
        self.assertEqual(["body", "cleanup"], [e["phase"] for e in saved["errors"]])

    def test_cancel_stays_cancelled_even_when_cleanup_fails(self):
        runner = MusicPreviewRun(self.root, self.root / "progress.txt")
        def cancel():
            raise KeyboardInterrupt()
        def cleanup():
            raise RuntimeError("cleanup")
        self.assertEqual(130, runner.execute(cancel, cleanup))
        self.assertEqual("CANCELLED", json.loads((self.root / "summary.json").read_text())["status"])

    def test_successful_body_cannot_hide_failed_restoration(self):
        runner = MusicPreviewRun(self.root, self.root / "progress.txt")
        def cleanup():
            raise RuntimeError("cleanup")
        self.assertEqual(1, runner.execute(lambda: None, cleanup))
        self.assertEqual("FAIL", runner.summary["status"])

    def test_atomic_retry_preserves_previous_record_until_replace(self):
        runner = MusicPreviewRun(self.root, self.root / "progress.txt")
        import music_preview_run
        replace = music_preview_run.os.replace
        attempts = []
        def locked(source, target):
            attempts.append(json.loads(Path(target).read_text())["status"])
            if len(attempts) == 1:
                raise PermissionError("reader lock")
            replace(source, target)
        with patch.object(music_preview_run.os, "replace", side_effect=locked):
            runner.summary["status"] = "FAIL"
            runner.persist()
        self.assertEqual(["RUNNING", "RUNNING"], attempts)
        self.assertEqual("FAIL", json.loads((self.root / "summary.json").read_text())["status"])

    def test_stop_owned_app_precedes_restore_and_fresh_verification(self):
        calls = []
        original = dict(before=True, preferences={"offline_music": {"selection": "original"}})
        def configure(action="query", **kwargs):
            calls.append((action, kwargs["preferences"], kwargs["preference_token"]))
            return dict(after=True, preferences=original["preferences"])
        checked = restore_owned_settings(lambda: calls.append("stop"), configure, original, "owner")
        self.assertEqual(["stop", ("software-avc", "restore", "owner"), ("query", "verify", "owner")], calls)
        self.assertEqual(original["preferences"], checked["preferences"])

    def test_restoration_mismatch_cannot_pass(self):
        original = dict(before=False, preferences={})
        with self.assertRaisesRegex(RuntimeError, "restoration mismatch"):
            restore_owned_settings(lambda: None, lambda *a, **k: dict(after=True, preferences={}),
                                   original, "owner")

    def test_low_disk_still_stops_owned_app_and_restores_settings(self):
        import validate_music_preview as validation
        calls = []
        original = dict(before=True, preferences={"offline_music": {"selection": "original"}})
        def configure(action="query", **kwargs):
            calls.append(action)
            return dict(after=True, preferences=original["preferences"])
        with patch.object(validation.shutil, "disk_usage",
                          return_value=shutil._ntuple_diskusage(16 * 1024 ** 3, 9 * 1024 ** 3, 7 * 1024 ** 3)) as disk, \
                patch.object(validation.subprocess, "run",
                             return_value=subprocess.CompletedProcess([], 0)) as command:
            restore_owned_settings(
                lambda: validation.stop_owned_app(Path("adb.exe"), "emulator-5580", self.root,
                                                   "stop.txt", {}), configure, original, "owner")
        disk.assert_not_called()
        command.assert_called_once()
        self.assertEqual(["adb.exe", "-s", "emulator-5580", "shell", "am", "force-stop",
                          "com.simple.videoeditor"], command.call_args.args[0])
        self.assertEqual(30, command.call_args.kwargs["timeout"])
        self.assertTrue(command.call_args.kwargs["check"])
        self.assertEqual(["software-avc", "query"], calls)

    def test_cleanup_command_failure_is_not_hidden(self):
        import validate_music_preview as validation
        with patch.object(validation.subprocess, "run",
                          side_effect=subprocess.CalledProcessError(1, ["adb"])), \
                self.assertRaises(subprocess.CalledProcessError):
            validation.stop_owned_app(Path("adb.exe"), "emulator-5580", self.root, "stop.txt", {})
