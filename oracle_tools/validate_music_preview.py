"""Preview/capacity candidate proof, or explicit same-APK missing-policy recovery.

Requires an already-owned, idle emulator. Never promotes an APK or downloads assets.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tarfile
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import run_editor_workflow as workflow
import run_emulator_validation as oracle
import verify_real_music_exports as pcm
from music_preview_run import MusicPreviewRun, restore_owned_settings

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / r".local-sdk\platform-tools\adb.exe"
PROGRESS = ROOT / r"app\build\music-preview-scale-progress.txt"


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def stop_owned_app(adb, serial, evidence, name, env):
    """Bounded recovery must remain possible below the large-evidence reserve."""
    with (evidence / name).open("wb") as output, (evidence / (name + ".stderr")).open("wb") as error:
        subprocess.run([str(adb), "-s", serial, "shell", "am", "force-stop",
                        "com.simple.videoeditor"], stdout=output, stderr=error,
                       env=env, timeout=30, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--recover", type=Path, help="Prior exact-revision evidence; only missing real-software")
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--skip-build", action="store_true")
    args = parser.parse_args()
    os.chdir(ROOT)
    evidence = args.evidence.resolve()
    if not evidence.is_relative_to(ROOT / "app" / "build"):
        raise ValueError("Evidence must be an owned app/build child")
    evidence.mkdir(parents=True, exist_ok=False)
    prior = json.loads((args.recover / "summary.json").read_text()) if args.recover else None
    revision = prior["revision"] if prior else subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    token = prior["token"] if prior else uuid.uuid4().hex
    runner = MusicPreviewRun(evidence, PROGRESS, revision=revision, token=token,
                             actualCatalogTracks=len(json.loads((ROOT / r"app\src\main\assets\music\catalog.json")
                                 .read_text(encoding="utf-8"))["tracks"]), capacityOnly=False, promoted=False,
                             scope="missing-software-recovery" if prior else "preview-capacity-candidate")
    summary = runner.summary
    oracle.ACTIVE_EVIDENCE = evidence
    env = dict(os.environ, VIDEO_EDITOR_REVISION=revision)
    original = None
    def reserve():
        free = shutil.disk_usage(evidence).free
        if free < 8 * 1024 ** 3:
            raise RuntimeError(f"8 GiB evidence reserve required, free={free}")
    def run(command, name, timeout=900, check=True):
        reserve()
        with (evidence / name).open("wb") as output, (evidence / (name + ".stderr")).open("wb") as error:
            result = subprocess.run([str(a) for a in command], stdout=output, stderr=error,
                                    env=env, timeout=timeout)
        if check and result.returncode:
            raise RuntimeError(f"{name}: exit {result.returncode}")
        return result.returncode
    def configure(action="query", **kwargs):
        record = workflow.configure_encoding(ADB, args.serial, evidence, action, **kwargs)
        workflow.assert_revision(record, revision)
        return record
    def native(classes, name):
        runner.note("Starting " + name)
        try:
            run([ADB, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                 ",".join("com.simple.videoeditor." + c for c in classes),
                 "-e", "real_music_token", token, oracle.RUNNER], name + ".txt", 1800)
        except BaseException:
            try:
                stop_owned_app(ADB, args.serial, evidence, name + "-stop.txt", env)
            except BaseException as stop_error:
                summary["errors"].append(dict(phase="native-stop", error=repr(stop_error)))
            raise
        parsed = oracle.parse_instrumentation((evidence / (name + ".txt")).read_text(errors="replace"))
        summary["native"][name] = parsed
        runner.persist()
        workflow.assert_inventory(parsed, workflow.inventory(classes))
        runner.note(f"{name}: PASS {parsed['ok_tests']} tests")
    def harvest(remote, name):
        run([ADB, "-s", args.serial, "exec-out", "run-as", "com.simple.videoeditor",
             "tar", "-cf", "-", remote], name + ".tar", 180)
        return evidence / (name + ".tar")
    def body():
        nonlocal original
        runner.note("Checking reserve, protected files and source")
        reserve()
        protected = json.loads((ROOT / r"app\build\short-preview-protected-before.json").read_text(encoding="utf-8-sig"))
        for item in protected:
            assert digest(item["path"]) == item["sha256"], item["path"]
        summary["protectedFiles"] = len(protected)
        if prior:
            summary["apks"] = prior["apks"]
            for item in prior["apks"]:
                assert digest(item["path"]) == item["sha256"]
            original = prior["originalPreferences"]
            checked = configure(preferences="verify", preference_token=token)
            assert checked["preferences"] == original["preferences"]
            assert checked["after"] == original["before"]
            for name, classes in (("preview-default", ["ShortMusicPreviewTest"]),
                                  ("preview-software-avc", ["ShortMusicPreviewTest"]),
                                   ("real-default", workflow.REAL_MUSIC_CLASSES)):
                path = args.recover / (name + ".txt")
                parsed = oracle.parse_instrumentation(path.read_text(errors="replace"))
                workflow.assert_inventory(parsed, workflow.inventory(classes))
                summary["native"][name] = dict(parsed, reusedFrom=str(path), sha256=digest(path))
        else:
            if not args.skip_build:
                runner.note("Building exact revision " + revision)
                run([ROOT / "gradlew.bat", "--no-daemon", "--console=plain", ":app:assembleDebug",
                     ":app:assembleDebugAndroidTest", ":app:testDebugUnitTest"], "build.txt")
            summary["apks"] = []
            for relative in (r"outputs\apk\debug\app-debug.apk",
                             r"outputs\apk\androidTest\debug\app-debug-androidTest.apk"):
                source = ROOT / "app" / "build" / relative
                target = evidence / source.name
                shutil.copy2(source, target)
                run([ROOT / r".local-sdk\build-tools\35.0.0\apksigner.bat", "verify", "--print-certs", target],
                    source.name + ".signature.txt")
                summary["apks"].append(dict(path=str(target), sha256=digest(target), bytes=target.stat().st_size))
                run([ADB, "-s", args.serial, "install", "-r", "-t", target], "install-" + source.name + ".txt", 120)
            original = configure(preferences="snapshot", preference_token=token)
        summary["originalPreferences"] = original
        # Verify installed byte identity, not merely an inlined revision constant.
        for item, package in zip(summary["apks"], ("com.simple.videoeditor", "com.simple.videoeditor.test")):
            output = subprocess.check_output([str(ADB), "-s", args.serial, "shell", "pm", "path", package],
                                             timeout=30, text=True)
            remote = output.strip().removeprefix("package:")
            assert remote.startswith("/") and "\n" not in remote
            installed = evidence / ("installed-" + Path(item["path"]).name)
            run([ADB, "-s", args.serial, "pull", remote, installed], installed.name + ".txt", 120)
            assert digest(installed) == item["sha256"], "Installed APK hash mismatch"
            installed.unlink()
        for policy in (("software-avc",) if prior else ("default", "software-avc")):
            configure(policy)
            if not prior:
                native(["ShortMusicPreviewTest", "MusicCatalogScaleTest"], "preview-" + policy)
            native(workflow.REAL_MUSIC_CLASSES, "real-" + policy)
        runner.note("Harvesting exact-token real exports; bounded independent PCM")
        archive = harvest("files/real-music-evidence/" + token, "real-music")
        destination = evidence / "real-music"
        workflow.extract_real_music(archive, destination, revision, token)
        pcm.verify(destination, revision, evidence / "independent-pcm.json")
        summary["independentPcm"] = "PASS"
        if not prior:
            configure("default")
            native(["OfflineMusicTest", "MusicCompositionTest", "SelectedFramePreviewTest",
                    "ShortDiagnosticTest"], "targeted-regressions")
            run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "oracle_tools",
                 "-p", "test_real_music_catalog.py", "-q"], "host-catalog.txt")
            lint_exit = run([ROOT / "gradlew.bat", "--no-daemon", "--console=plain", ":app:lintDebug"],
                            "lint.txt", 900, False)
            shutil.copy2(ROOT / r"app\build\reports\lint-results-debug.xml", evidence / "lint.xml")
            def errors(path):
                return sorted((issue.attrib["id"], issue.attrib["message"],
                               Path(issue.find("location").attrib["file"]).name)
                              for issue in ET.parse(path).getroot().findall("issue")
                              if issue.attrib["severity"] == "Error")
            before = errors(ROOT / r"app\build\short-preview-baseline\lint.xml")
            after = errors(evidence / "lint.xml")
            assert before == after and len(after) == 9, "lint error regression"
            summary["lint"] = dict(exit=lint_exit, existingErrors=len(after), newErrors=0)
        preview = harvest("files/short-music-preview-evidence", "preview-evidence")
        measurements = []
        with tarfile.open(preview) as archive:
            for member in archive.getmembers():
                if member.isfile() and member.name.endswith("result.json") and member.size < 1024 * 1024:
                    record = json.load(archive.extractfile(member))
                    if record.get("revision") == revision and "playbackElapsedMs" in record:
                        measurements.append(dict(path=member.name, **record))
        assert len(measurements) >= 2
        summary["playbackMeasurements"] = measurements
        for item in protected:
            assert digest(item["path"]) == item["sha256"], item["path"]
        runner.note("Original-six regression and preview proof complete; expanded renders require the hundred-library runner")
    def cleanup():
        if original is not None:
            summary["verifiedRestoration"] = restore_owned_settings(
                lambda: stop_owned_app(ADB, args.serial, evidence, "cleanup-owned-app-stop.txt", env),
                configure, original, token)
    def cancel(signum, frame):
        raise KeyboardInterrupt(f"signal {signum}")
    signal.signal(signal.SIGTERM, cancel)
    return runner.execute(body, cleanup)


if __name__ == "__main__":
    raise SystemExit(main())
