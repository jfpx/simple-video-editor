"""Same-APK finite full/short/report regressions after the music worker is terminal."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import uuid
from music_preview_run import MusicPreviewRun, restore_owned_settings
from validate_cc0_hundred import ROOT, ADB, digest
from validate_music_preview import stop_owned_app
import run_editor_workflow as workflow
import run_emulator_validation as oracle


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--music-evidence", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, default=ROOT / "app" / "build")
    parser.add_argument("--serial", default="emulator-5580")
    args = parser.parse_args()
    os.chdir(ROOT)
    music = json.loads((args.music_evidence / "summary.json").read_text())
    assert music["status"] == music["cleanup"] == "PASS", "Music worker must finish before baseline owner starts"
    evidence = args.evidence.resolve()
    owner = args.evidence_root.resolve()
    assert evidence != owner and evidence.is_relative_to(owner)
    evidence.mkdir(parents=True, exist_ok=False)
    revision, token = music["revision"], uuid.uuid4().hex
    runner = MusicPreviewRun(evidence, ROOT / r"app\build\cc0-hundred-integration-progress.txt",
                            revision=revision, token=token, scope="full-short-report-regressions")
    oracle.ACTIVE_EVIDENCE = evidence
    env, original = dict(os.environ), None
    adb = [ADB, "-s", args.serial]
    private = adb + ["exec-out", "run-as", "com.simple.videoeditor"]

    def run(command, name, timeout=1200):
        assert shutil.disk_usage(evidence).free >= 8 * 1024 ** 3
        with (evidence / name).open("wb") as out, (evidence / (name + ".stderr")).open("wb") as err:
            subprocess.run([str(a) for a in command], stdout=out, stderr=err, timeout=timeout, check=True)

    def configure(action="query", **kwargs):
        record = workflow.configure_encoding(ADB, args.serial, evidence, action, **kwargs)
        workflow.assert_revision(record, revision)
        return record

    def native(classes, name):
        runner.note("Starting " + name)
        run(adb + ["shell", "am", "instrument", "-w", "-r", "-e", "class",
                   ",".join("com.simple.videoeditor." + c for c in classes), oracle.RUNNER], name + ".txt", 2400)
        parsed = oracle.parse_instrumentation((evidence / (name + ".txt")).read_text(errors="replace"))
        runner.summary["native"][name] = parsed
        runner.persist()
        workflow.assert_inventory(parsed, workflow.inventory(classes))
        runner.note(name + ": PASS " + str(parsed["ok_tests"]))

    def body():
        nonlocal original
        for item, package in zip(music["apks"], ("com.simple.videoeditor", "com.simple.videoeditor.test")):
            assert digest(item["path"]) == item["sha256"]
            remote = subprocess.check_output([str(a) for a in adb + ["shell", "pm", "path", package]],
                                             timeout=30, text=True).strip().removeprefix("package:")
            assert remote.startswith("/") and "\n" not in remote
            installed = subprocess.check_output([str(a) for a in adb + ["shell", "sha256sum", remote]],
                                                timeout=60, text=True).split()[0]
            assert installed == item["sha256"]
        subprocess.run(["git", "diff", "--exit-code", revision, "HEAD", "--", *workflow.CANDIDATE_INPUTS], check=True)
        original = configure(preferences="snapshot", preference_token=token)
        runner.summary["originalPreferences"] = original
        run(private + ["tar", "-cf", "-", "files/selftest"], "selftest-before.tar", 180)
        for policy in ("default", "software-avc"):
            configure(policy)
            native(["EmulatorValidationTest#testProductionSelfTestRunnerTerminalSuiteJson"], "full-" + policy)
            run(private + ["tar", "-cf", "-", "files/selftest"], "full-" + policy + ".tar", 180)
        configure("default")
        native(["EditorSuiteModesTest", "ShortDiagnosticTest", "CompactSuiteIndexTest", "ReportMemoryTest",
                "PublicationDraftMediaTest#testImmutableConfigCreditsAndSixActualTracks"], "short-report-credits")
        run(private + ["tar", "-cf", "-", "files/selftest"], "short-final.tar", 180)
        runner.summary["fullExportsPerPolicy"] = 17
        runner.summary["fullControlsPerPolicy"] = 60
        runner.summary["shortExportsPerPolicy"] = 3
        runner.summary["shortControlsPerPolicy"] = 10
        runner.summary["inheritedPixelSuite"] = "not rerun; known pre-existing 46/47 retained, no oracle changed"

    def cleanup():
        if original:
            runner.summary["restoration"] = restore_owned_settings(
                lambda: stop_owned_app(ADB, args.serial, evidence, "cleanup-stop.txt", env),
                configure, original, token)

    return runner.execute(body, cleanup)


if __name__ == "__main__":
    raise SystemExit(main())
