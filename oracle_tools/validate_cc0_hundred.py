"""Bounded one-owner candidate validation; no network, publication or shared APK writes."""
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
import zipfile

sys.dont_write_bytecode = True
import run_editor_workflow as workflow
import run_emulator_validation as oracle
from music_preview_run import MusicPreviewRun, restore_owned_settings
from validate_music_preview import stop_owned_app

ROOT = Path(__file__).resolve().parents[1]
ADB = ROOT / r".local-sdk\platform-tools\adb.exe"


def digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", default="emulator-5580")
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, default=ROOT / "app" / "build")
    parser.add_argument("--recover", type=Path, help="Terminal same-APK run; reuse only complete passing stages")
    parser.add_argument("--search-fix-from", type=Path,
                        help="Prior complete music proof; renderer unchanged, fresh native decode and visible UI checks")
    args = parser.parse_args()
    os.chdir(ROOT)
    evidence = args.evidence.resolve()
    owner = args.evidence_root.resolve()
    assert evidence != owner and evidence.is_relative_to(owner)
    evidence.mkdir(parents=True, exist_ok=False)
    prior = json.loads((args.recover / "summary.json").read_text()) if args.recover else None
    reference = json.loads((args.search_fix_from / "summary.json").read_text()) if args.search_fix_from else None
    if reference:
        assert not prior and reference["status"] == reference["cleanup"] == "PASS"
        assert reference["expandedPcm"] == reference["originalSixPcm"] == "PASS"
        changed = subprocess.check_output(
            ["git", "diff", "--name-only", reference["revision"], "HEAD", "--", *workflow.CANDIDATE_INPUTS],
            text=True).splitlines()
        allowed = {"app/src/main/java/com/simple/videoeditor/OfflineMusicControls.java",
                   "app/src/androidTest/java/com/simple/videoeditor/RealMusicLibraryTest.java",
                   "app/src/androidTest/java/com/simple/videoeditor/MusicCatalogScaleTest.java"}
        assert set(changed) <= allowed, changed
    if prior:
        assert prior["status"] in ("PASS", "FAIL", "CANCELLED") and prior["cleanup"] == "PASS"
        revision, token = prior["revision"], prior["token"]
        subprocess.run(["git", "diff", "--exit-code", revision, "HEAD", "--", r"app\src",
                        r"app\build.gradle", "build.gradle", "gradle.properties"], check=True)
    else:
        revision = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        token = uuid.uuid4().hex
    runner = MusicPreviewRun(evidence, ROOT / r"app\build\cc0-hundred-integration-progress.txt",
                            revision=revision, token=token, actualTracks=118, addedTracks=112,
                            audioBytes=256956282, full118RenderCoverage=False, published=False)
    summary = runner.summary
    if reference:
        summary["unchangedRendererProof"] = dict(revision=reference["revision"],
            evidence=str(args.search_fix_from), summarySha256=digest(args.search_fix_from / "summary.json"),
            changedAppFiles=changed, scope="prior both-policy 36 expanded +26 original PCM exports and86 music methods")
    oracle.ACTIVE_EVIDENCE = evidence
    env = dict(os.environ, VIDEO_EDITOR_REVISION=revision)
    original = None
    protected = json.loads((ROOT / r"app\build\cc0-hundred-protected.json").read_text())

    def run(command, name, timeout=900):
        assert shutil.disk_usage(evidence).free >= 8 * 1024 ** 3, "8 GiB reserve"
        with (evidence / name).open("wb") as out, (evidence / (name + ".stderr")).open("wb") as err:
            subprocess.run([str(a) for a in command], env=env, stdout=out, stderr=err,
                           timeout=timeout, check=True)

    def configure(action="query", **kwargs):
        record = workflow.configure_encoding(ADB, args.serial, evidence, action, **kwargs)
        workflow.assert_revision(record, revision)
        return record

    def native(classes, name, timeout=1800):
        if prior and prior.get("native", {}).get(name, {}).get("passed"):
            path = args.recover / (name + ".txt")
            parsed = oracle.parse_instrumentation(path.read_text(errors="replace"))
            workflow.assert_inventory(parsed, workflow.inventory(classes))
            assert parsed == prior["native"][name]
            summary["native"][name] = parsed
            summary.setdefault("reused", {})[name] = dict(path=str(path), sha256=digest(path))
            shutil.copyfile(path, evidence / path.name)
            runner.note("Reused exact-APK complete stage " + name)
            return
        runner.note("Starting " + name)
        run([ADB, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
             ",".join("com.simple.videoeditor." + c for c in classes),
             "-e", "real_music_token", token, oracle.RUNNER], name + ".txt", timeout)
        parsed = oracle.parse_instrumentation((evidence / (name + ".txt")).read_text(errors="replace"))
        summary["native"][name] = parsed
        runner.persist()
        workflow.assert_inventory(parsed, workflow.inventory(classes))
        runner.note(f"{name}: PASS {parsed['ok_tests']}")

    def body():
        nonlocal original
        runner.note("Building revision-bound 118-track candidate")
        for p in protected:
            assert digest(p["path"]) == p["sha256"]
        if prior:
            summary["candidate"], summary["apks"] = prior["candidate"], prior["apks"]
            summary["recoveredFrom"] = dict(path=str(args.recover), sha256=digest(args.recover / "summary.json"))
            for item in summary["apks"]:
                assert digest(item["path"]) == item["sha256"]
        else:
            run([ROOT / "gradlew.bat", "--offline", "--no-daemon", "--console=plain",
                 ":app:assembleDebug", ":app:assembleDebugAndroidTest", ":app:testDebugUnitTest"], "build.txt")
            candidate = Path(r"D:\jfpx\apk") / ("cc0-100-candidate-" + revision[:12])
            candidate.mkdir(exist_ok=False)
            summary["candidate"] = str(candidate)
            summary["apks"] = []
            for relative in (r"outputs\apk\debug\app-debug.apk", r"outputs\apk\androidTest\debug\app-debug-androidTest.apk"):
                source = ROOT / r"app\build" / relative
                destination = candidate / source.name
                shutil.copyfile(source, destination)
                run([ROOT / r".local-sdk\build-tools\35.0.0\apksigner.bat", "verify", "--print-certs", destination],
                    source.name + ".signer.txt")
                summary["apks"].append(dict(path=str(destination), sha256=digest(destination),
                                            bytes=destination.stat().st_size))
                runner.persist()
                run([ADB, "-s", args.serial, "install", "-r", "-t", destination], source.name + ".install.txt", 180)
        with zipfile.ZipFile(summary["apks"][0]["path"]) as apk:
            assert apk.testzip() is None
            manifest = json.loads((ROOT / r"app\src\main\assets\music\manifest.json").read_text())
            for row in manifest["files"]:
                data = apk.read("assets/music/" + row["path"])
                assert len(data) == row["bytes"] and hashlib.sha256(data).hexdigest() == row["sha256"]
            assert apk.read("assets/music/manifest.json") == (ROOT / r"app\src\main\assets\music\manifest.json").read_bytes()
            packaged = {n for n in apk.namelist() if n.startswith("assets/music/")}
            assert packaged == {"assets/music/" + r["path"] for r in manifest["files"]} | {"assets/music/manifest.json"}
            summary["apkMusicManifestFiles"] = len(manifest["files"]) + 1
        for item, package in zip(summary["apks"], ("com.simple.videoeditor", "com.simple.videoeditor.test")):
            remote = subprocess.check_output([str(ADB), "-s", args.serial, "shell", "pm", "path", package],
                                             timeout=30, text=True).strip().removeprefix("package:")
            assert remote.startswith("/") and "\n" not in remote
            installed = evidence / ("installed-" + Path(item["path"]).name)
            run([ADB, "-s", args.serial, "pull", remote, installed], installed.name + ".pull.txt", 180)
            assert digest(installed) == item["sha256"]
            installed.unlink()
        if prior:
            original = prior["originalPreferences"]
            checked = configure(preferences="verify", preference_token=token)
            assert checked["preferences"] == original["preferences"] and checked["after"] == original["before"]
        else:
            original = configure(preferences="snapshot", preference_token=token)
        summary["originalPreferences"] = original
        configure("default")
        if reference:
            native(["MusicCatalogScaleTest"], "visible-search-smoke", 180)
        native(["RealMusicLibraryTest#testAll118NativeDecodeAndStableRecipeRoundTrips",
                *([] if reference else ["MusicCatalogScaleTest"])],
               "all-native-and-scale", 5400)
        for policy in ("default", "software-avc"):
            configure(policy)
            native(["ShortMusicPreviewTest"], "preview-" + policy)
            real = ["RealMusicLibraryTest#testExpandedRepresentativeLoopsAndGain",
                    "RealMusicLibraryTest#testOriginalCatalogPinsAndNegativeBounds",
                    "RealMusicLibraryTest#testExpandedRealSearchAuditionExportPresetLanguageRestartShare",
                    "RealMusicLibraryTest#testNativeFullOriginalLoopsAndIndependentGain",
                    "RealMusicLibraryTest#testRealSoundUiAuditionExportPresetLanguageRestartShare",
                    "RealMusicLibraryTest#testAuditionLifecycleAndCancelExport"]
            if reference:
                real.remove("RealMusicLibraryTest#testNativeFullOriginalLoopsAndIndependentGain")
                if policy == "software-avc":
                    real = ["RealMusicLibraryTest#testExpandedRealSearchAuditionExportPresetLanguageRestartShare"]
            native(real, "real-" + policy, 2400)
            if not reference:
                native(workflow.MUSIC_CLASSES, "music-regressions-" + policy, 2400)
        runner.note("Harvesting exact-token evidence and comparing independent source PCM")
        archive_path = evidence / "real.tar"
        run([ADB, "-s", args.serial, "exec-out", "run-as", "com.simple.videoeditor",
             "tar", "-cf", "-", "files/real-music-evidence/" + token], archive_path.name, 180)
        destination = evidence / "real"
        prefix = "files/real-music-evidence/" + token + "/"
        with tarfile.open(archive_path) as archive:
            for member in archive.getmembers():
                if not member.isfile():
                    continue
                relative = member.name.removeprefix(prefix)
                assert member.name.startswith(prefix) and "\\" not in relative and ":" not in relative
                assert not any(part in ("", ".", "..") for part in relative.split("/"))
                assert member.size <= 64 * 1024 * 1024
                target = destination.joinpath(*relative.split("/"))
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as src, target.open("xb") as out:
                    shutil.copyfileobj(src, out)
        run([sys.executable, "-B", ROOT / r"oracle_tools\verify_cc0_hundred_exports.py",
             "--evidence", destination, "--revision", revision, "--output", evidence / "expanded-pcm.json",
             "--scope", "search-fix" if reference else "both-policies"],
            "expanded-pcm.txt", 1200)
        summary["expandedPcm"] = "PASS"
        if not reference:
            run([sys.executable, "-B", ROOT / r"oracle_tools\verify_real_music_exports.py",
                 "--evidence", destination, "--revision", revision, "--output", evidence / "original-six-pcm.json"],
                "original-six-pcm.txt", 600)
            summary["originalSixPcm"] = "PASS"
        else:
            summary["originalSixPcm"] = "REUSED unchanged assets/catalog/renderer; see unchangedRendererProof"
        run([sys.executable, "-B", "-m", "unittest", "discover", "-s", "oracle_tools",
             "-p", "test_real_music_catalog.py", "-q"], "host-catalog.txt", 300)
        summary["hostCatalog"] = "PASS"
        for p in protected:
            assert digest(p["path"]) == p["sha256"]
        summary["protectedFilesUnchanged"] = len(protected)
        summary["notRerun"] = ["full frozen 17-export/60-control suites", "inherited 46/47 pixel suite",
                               "physical-device/listening validation"]
        runner.note("Targeted expanded-library validation complete")

    def cleanup():
        if original is not None:
            summary["restoration"] = restore_owned_settings(
                lambda: stop_owned_app(ADB, args.serial, evidence, "cleanup-stop.txt", env),
                configure, original, token)
        if "candidate" in summary:
            (evidence / "receipt.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")

    signal.signal(signal.SIGTERM, lambda signum, frame: (_ for _ in ()).throw(KeyboardInterrupt()))
    code = runner.execute(body, cleanup)
    if "candidate" in summary:
        receipt = "receipt-recovery-" + evidence.name + ".json" if prior else "receipt.json"
        (Path(summary["candidate"]) / receipt).write_text(json.dumps(summary, indent=2), encoding="utf-8")
    return code


if __name__ == "__main__":
    raise SystemExit(main())
