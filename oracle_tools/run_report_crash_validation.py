"""Real API34 DocumentsUI -> Downloads TXT -> force-stop -> reopen/copy/share.

Uses only the bundled fixture and one explicitly selected emulator. Unlike the
wrapped native fake provider tests, this exercises actual persisted SAF grants.
Build/install matching app and androidTest APKs first. No share recipient is sent data.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
import time
import tarfile
import io
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
import run_editor_workflow as workflow

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.simple.videoeditor"
SHORT_CASES = ["identity", "speed2", "music_loop"]
SHORT_CONTROLS = [
    "positive_reference_identity", "positive_reference_speed2",
    "negative_unchanged_source_as_speed2", "negative_speed_changes_pitch",
    "negative_frozen_video_correct_duration", "negative_frozen_markers_advancing_barcode",
    "negative_audio_timestamp_gap", "music_reference", "music_unchanged", "music_no_loop",
]


def localized_labels(resource: str) -> set[str]:
    labels = set()
    for directory in ("values", "values-en"):
        root = ET.parse(ROOT / "app" / "src" / "main" / "res" / directory / "secondary_strings.xml")
        label = root.find(f"./string[@name='{resource}']")
        assert label is not None and label.text, f"Missing localized resource: {resource}"
        labels.add(label.text.replace(r"\n", "\n").casefold())
    return labels


def localized_button(ui: ET.Element, resource: str) -> ET.Element:
    labels = localized_labels(resource)
    matches = [node for node in ui.iter("node")
               if node.get("class") == "android.widget.Button"
               and node.get("text", "").casefold() in labels]
    assert len(matches) == 1, f"Missing or ambiguous localized button: {resource}"
    return matches[0]


def open_selftest(ui) -> None:
    ui.tap(ui.panel_control("panelMore", "btnSelfTest"))


def assert_external_origin(ui: ET.Element) -> None:
    origins = localized_labels("secondary_external_origin")
    assert any(node.get("text", "").casefold().startswith(tuple(origins))
               for node in ui.iter("node")), "Missing localized external TXT origin"


def validate_short_suite(suite: dict) -> None:
    assert suite["mode"] == "SHORT_DIAGNOSTIC" and suite["coverage"] == "LIMITED_SUBSET"
    assert suite["status"] == "SUBSET_PASS" and suite["full_suite_status"] == "NOT_RUN"
    assert suite["complete"] is True and not suite["cancelled"] and not suite["timed_out"]
    assert not suite.get("fatal_error") and suite["fixture_verified"] is True
    assert suite["full_feature_coverage"] == "UNVERIFIED"
    assert suite["planned_cases"] == SHORT_CASES and suite["planned_controls"] == SHORT_CONTROLS
    assert suite["planned_export_count"] == len(SHORT_CASES)
    assert suite["planned_control_count"] == len(SHORT_CONTROLS)
    assert suite["planned_positive_control_count"] == 3 and suite["planned_negative_control_count"] == 7
    assert len(suite["omitted_cases"]) == len(set(suite["omitted_cases"])) == 14
    assert len(suite["omitted_controls"]) == len(set(suite["omitted_controls"])) == 50
    assert not set(SHORT_CASES).intersection(suite["omitted_cases"])
    assert not set(SHORT_CONTROLS).intersection(suite["omitted_controls"])
    for key, ids in (("exports", SHORT_CASES), ("checker_controls", SHORT_CONTROLS)):
        assert [item["id"] for item in suite[key]] == ids
        for item in suite[key]:
            expected = "PASS" if key == "exports" or item["id"] in (
                "positive_reference_identity", "positive_reference_speed2", "music_reference") else "FAIL"
            assert item["status"] == "PASS"
            assert item["expected_status"] == item["actual_status"] == expected


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", type=Path, default=ROOT / ".local-sdk" / "platform-tools" / "adb.exe")
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--short", action="store_true", help="Use the explicit short diagnostic UI entry")
    parser.add_argument("--complete-short", action="store_true", help="Wait for all three real exports, not a kill")
    parser.add_argument("--recreate-picker", action="store_true", help="Destroy stopped activity behind real SAF picker")
    parser.add_argument("--large-report", action="store_true", help="Append a 32MiB native emulator UI fixture after kill")
    args = parser.parse_args()
    assert not args.complete_short or args.short
    assert args.serial.startswith("emulator-"), "Never target a user's phone"
    evidence = args.evidence_dir.resolve()
    evidence.relative_to(ROOT)
    evidence.mkdir(parents=True, exist_ok=False)
    workflow.oracle.ACTIVE_EVIDENCE = evidence
    remote_xml = "/sdcard/report-validation-" + uuid.uuid4().hex + ".xml"
    editor = workflow._EditorUiWorkflow(args.adb.resolve(), args.serial, evidence, remote_xml)

    def adb(*words: str, timeout: int = 30, check: bool = True) -> bytes:
        result = subprocess.run([str(args.adb.resolve()), "-s", args.serial, *words],
                                capture_output=True, timeout=timeout, check=check)
        return result.stdout

    def shell(*words: str, **kwargs) -> str:
        return adb("shell", *words, **kwargs).decode("utf-8", errors="replace").strip()

    assert shell("getprop", "ro.kernel.qemu") == "1"
    assert shell("getprop", "sys.boot_completed") == "1"
    shell("input", "keyevent", "KEYCODE_WAKEUP")
    shell("wm", "dismiss-keyguard")

    def screen(name: str) -> ET.Element:
        return editor.screen(name)

    def tap(node: ET.Element) -> None:
        assert node.get("enabled") == "true", node.attrib
        left, top, right, bottom = map(int, re.findall(r"\d+", node.get("bounds", "")))
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(0.6)

    def text_node(ui: ET.Element, text: str) -> ET.Element:
        matches = [n for n in ui.iter("node") if text.lower() in n.get("text", "").lower()]
        assert matches, f"Missing visible control: {text}"
        return min(matches, key=lambda n: len(n.get("text", "")))

    shell("am", "force-stop", PACKAGE)
    shell("am", "start", "-W", "-f", "0x10008000", "-n", PACKAGE + "/.MainActivity")
    screen("01-main")
    open_selftest(editor)
    previous_runs = shell("run-as", PACKAGE, "ls", "files/selftest", check=False).splitlines()
    tap(localized_button(screen("02-selftest"), "secondary_short_start" if args.short else "secondary_start"))
    editor._picker_capture = True
    picker = screen("03-real-documentsui")
    assert any(n.get("package") == "com.android.documentsui" for n in picker.iter("node"))
    picker_pid = None
    if args.recreate_picker:
        picker_pid = shell("pidof", PACKAGE)
        assert re.fullmatch(r"\d+", picker_pid)
        # Kill only this cached background app, without force-stop (which discards the pending result).
        shell("am", "kill", PACKAGE)
        deadline = time.monotonic() + 15
        while shell("pidof", PACKAGE, check=False) and time.monotonic() < deadline:
            time.sleep(0.5)
        assert not shell("pidof", PACKAGE, check=False), "Cached app did not die; recreation is unproven"
        picker = screen("picker-after-app-process-death")
        assert any(n.get("package") == "com.android.documentsui" for n in picker.iter("node"))
        (evidence / "picker-activity-state.txt").write_text(
            shell("dumpsys", "activity", "activities"), encoding="utf-8")
    # This isolated official AOSP AVD opens its local Downloads root by default.
    # Refuse an unexpected root instead of choosing an arbitrary cloud provider.
    assert any(n.get("text") == "Downloads" for n in picker.iter("node")), "Select local Downloads"
    filename = next(n.get("text") for n in picker.iter("node")
                    if n.get("resource-id") == "android:id/title"
                    and n.get("class") == "android.widget.EditText")
    assert re.fullmatch(r"(short-diagnostic|selftest)-\d+\.txt", filename), filename
    assert filename.startswith("short-diagnostic-" if args.short else "selftest-")
    remote = "/sdcard/Download/" + filename
    existing = subprocess.run([str(args.adb.resolve()), "-s", args.serial, "shell", "test", "-e", remote],
                              capture_output=True, timeout=30)
    assert existing.returncode == 1, "Proposed document already exists; never validate a stale report"
    tap(text_node(picker, "SAVE"))
    editor._picker_capture = False
    deadline = time.monotonic() + 180
    before = b""
    while time.monotonic() < deadline:
        before = adb("exec-out", "cat", remote, check=False)
        if b"PASS CONTROL" in before and before.count(b"STAGE CONTROL") >= 2:
            break
        if b"EXTERNAL TXT SAVE FAILED" in before:
            raise AssertionError(before.decode("utf-8"))
        time.sleep(0.5)
    else:
        screen("save-or-progress-failure")
        raise AssertionError("No external checkpoint after a completed risky decoder case in 180s")
    assert b"FINAL CHECK RESULTS" not in before
    if picker_pid:
        assert shell("pidof", PACKAGE) != picker_pid, "SAF result must recreate the app process"
    if args.short:
        assert b"MODE SHORT_DIAGNOSTIC" in before and b"PLANNED CONTROLS (10)" in before
        running = screen("short-running-disabled-controls")
        for label in ("secondary_short_start", "secondary_start", "secondary_open_report", "secondary_share_report"):
            assert localized_button(running, label).get("enabled") == "false"
        assert localized_button(running, "secondary_cancel").get("enabled") == "true"
    if args.complete_short:
        deadline = time.monotonic() + 1000  # Existing 15-minute app budget plus bounded cleanup.
        while time.monotonic() < deadline:
            final = adb("exec-out", "cat", remote)
            if b"TERMINAL /" in final and final.endswith(b"END CHECKPOINT\n"):
                break
            assert b"EXTERNAL TXT SAVE FAILED" not in final
            time.sleep(2)
        else:
            screen("short-timeout")
            raise AssertionError("No terminal short diagnostic within existing app budget plus cleanup")
        (evidence / "external-terminal.txt").write_bytes(final)
        screen("short-terminal")
        archive_bytes = adb("exec-out", "run-as", PACKAGE, "tar", "-cf", "-", "files/selftest", timeout=90)
        (evidence / "short-app-data.tar").write_bytes(archive_bytes)
        with tarfile.open(fileobj=io.BytesIO(archive_bytes)) as archive:
            candidates = [m for m in archive.getmembers() if m.isfile()
                          and m.name.endswith("/suite.json")
                          and m.name.split("/")[-2] not in previous_runs]
            assert len(candidates) == 1
            member = candidates[0]
            suite = json.load(archive.extractfile(member))
            (evidence / "terminal-suite.json").write_text(json.dumps(suite, indent=2), encoding="utf-8")
            validate_short_suite(suite)
            for item in suite["exports"] + suite["checker_controls"]:
                result = json.load(archive.extractfile(member.name.rsplit("/", 1)[0] + "/" + item["report"]))
                assert result["candidate_sha256"] == item["candidate_sha256"]
                if item in suite["exports"]:
                    assert "ENCODER video=" in result["export_codecs"]
                    assert result["decoder"]
        assert b"IDENTITY COLOR DIAGNOSTIC FULL" in final
        assert b"STAGE EXPORT DIAGNOSTIC speed2" in final and b"STAGE EXPORT DIAGNOSTIC music_loop" in final
        assert b"SUBSET_PASS" in final and b"FULL FROZEN SUITE: NOT RUN / UNVERIFIED" in final
        installed = shell("pm", "path", PACKAGE).removeprefix("package:")
        assert shell("sha256sum", installed).split()[0] == suite["metadata"]["installed_apk_sha256"]
        assert re.fullmatch("[0-9a-f]{40}", suite["metadata"]["source_revision"])
        summary = {"status": "SUBSET_PASS", "serial": args.serial, "document": remote,
                   "source_revision": suite["metadata"]["source_revision"],
                   "apk_sha256": suite["metadata"]["installed_apk_sha256"],
                   "planned_cases": SHORT_CASES, "planned_controls": SHORT_CONTROLS,
                   "full_suite_status": "NOT_RUN", "real_documentsui": True,
                   "picker_recreation_requested": args.recreate_picker,
                   "picker_killed_app_pid": picker_pid,
                   "external_sha256": hashlib.sha256(final).hexdigest()}
        (evidence / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
        (evidence / "logcat.txt").write_bytes(adb("logcat", "-d", timeout=60))
        print(json.dumps(summary, indent=2))
        return
    pid = shell("pidof", PACKAGE)
    assert re.fullmatch(r"\d+", pid), pid
    (evidence / "before-kill.txt").write_bytes(before)
    # Force-stop kills without Java finally/uncaught-handler execution.
    shell("am", "force-stop", PACKAGE)
    assert not shell("pidof", PACKAGE, check=False)
    after = adb("exec-out", "cat", remote)
    assert after.startswith(before), "An append must not destroy earlier completed checkpoints"
    assert b"FINAL CHECK RESULTS" not in after
    if args.short:
        assert b"MODE SHORT_DIAGNOSTIC" in after and b"SUBSET_PASS" not in after
    (evidence / "after-kill.txt").write_bytes(after)
    if args.large_report:
        native_large = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                           PACKAGE + ".LargeReportRecoveryTest",
                           PACKAGE + ".test/android.test.InstrumentationTestRunner", timeout=180)
        (evidence / "native-large-fixture.txt").write_bytes(native_large)
        assert b"OK (1 test)" in native_large and b"FAILURES" not in native_large
        shell("am", "force-stop", PACKAGE)
        assert not shell("pidof", PACKAGE, check=False)
        # Pull, hash and check the full file as streams, not a giant UI/host String.
        large_file = evidence / "large-external.txt"
        adb("pull", remote, str(large_file), timeout=90)
        with large_file.open("rb") as stream:
            large_hash = hashlib.file_digest(stream, "sha256").hexdigest()
            stream.seek(0)
            assert stream.read(len(after)) == after
            events = 0
            for line in stream:
                if line.startswith(b'{"ui_event":'):
                    assert line.startswith(f'{{"ui_event":{events},'.encode())
                    events += 1
        assert events == 512 and large_file.stat().st_size > 32 * 1024 * 1024

    native = adb("shell", "am", "instrument", "-w", "-r", "-e", "class",
                 PACKAGE + ".SavedReportRecoveryTest",
                 PACKAGE + ".test/android.test.InstrumentationTestRunner", timeout=90)
    (evidence / "native-recovery.txt").write_bytes(native)
    assert b"OK (1 test)" in native and b"FAILURES" not in native, native.decode("utf-8")

    shell("am", "start", "-W", "-f", "0x10008000", "-n", PACKAGE + "/.MainActivity")
    screen("04-relaunched-main")
    open_selftest(editor)
    restored = screen("05-restored-interruption")
    assert "RUNNING /" in ET.tostring(restored, encoding="unicode")
    tap(localized_button(restored, "secondary_open_report"))
    opened = screen("06-opened-external-txt")
    assert_external_origin(opened)
    if args.large_report:
        visible = ET.tostring(opened, encoding="unicode")
        assert "TAIL PREVIEW ONLY" in visible and "LARGE_UI_LAST_EVENT_511" in visible
        assert len(visible) < 40000
    tap(localized_button(opened, "secondary_copy_report"))
    guard = screen("07-copy-tail-confirmation")
    assert "not the full" in ET.tostring(guard, encoding="unicode").lower() or "不是完整报告" in ET.tostring(guard, encoding="unicode")
    tap(localized_button(guard, "secondary_copy_preview"))
    copied = screen("07-copied-without-rerun")
    assert any(node.get("text", "").casefold() in localized_labels("secondary_copied")
               for node in copied.iter("node"))
    tap(localized_button(copied, "secondary_share_report"))
    sharing = screen("08-explicit-share-chooser")
    assert any(n.get("package") in ("android", "com.android.intentresolver")
               for n in sharing.iter("node")), "No Android share chooser"
    if args.large_report:
        assert shell("sha256sum", remote).split()[0] == large_hash
    else:
        assert adb("exec-out", "cat", remote) == after, "Recovery must not restart or rewrite the suite"
    summary = {
        "serial": args.serial, "killed_pid": pid, "kill": "am force-stop (no Java cleanup)",
        "provider": "real com.android.documentsui local Downloads; NOT fake provider",
        "document": remote, "bytes_after_kill": len(after),
        "sha256_after_kill": hashlib.sha256(after).hexdigest(),
        "native_persisted_permission_test": "PASS", "reopen_copy_share_without_rerun": "PASS",
        "share_recipient_selected": False,
        "mode": "SHORT_DIAGNOSTIC" if args.short else "FULL",
        "picker_recreation_requested": args.recreate_picker,
        "picker_killed_app_pid": picker_pid,
        "large_ui_bytes": large_file.stat().st_size if args.large_report else None,
        "large_ui_sha256": large_hash if args.large_report else None,
    }
    (evidence / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    shell("input", "keyevent", "KEYCODE_BACK")
    shell("rm", remote_xml)
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
