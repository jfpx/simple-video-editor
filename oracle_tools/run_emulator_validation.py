from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tarfile
import time
import uuid
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

sys.dont_write_bytecode = True

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ADB = ROOT / ".local-sdk" / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
DEFAULT_APP_APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"
DEFAULT_TEST_APK = ROOT / "app" / "build" / "outputs" / "apk" / "androidTest" / "debug" / "app-debug-androidTest.apk"
DEFAULT_EVIDENCE = ROOT / "app" / "build" / "emulator-validation" / f"run-{datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S%fZ')}-{uuid.uuid4().hex[:8]}"
RUNNER = "com.simple.videoeditor.test/android.test.InstrumentationTestRunner"
APP_PACKAGE = "com.simple.videoeditor"
STAGE_METHODS = {
    "smoke": "com.simple.videoeditor.EmulatorValidationTest#testSmokeRotate90ExportThroughProductionEngine",
    "suite": "com.simple.videoeditor.EmulatorValidationTest#testProductionSelfTestRunnerTerminalSuiteJson",
}
REGRESSION_CLASSES = [
    "com.simple.videoeditor.OracleVerifierTest",
    "com.simple.videoeditor.MusicCompositionTest",
    "com.simple.videoeditor.IntroCompositionTest",
    "com.simple.videoeditor.MusicOracleTest",
    "com.simple.videoeditor.IntroOracleTest",
    "com.simple.videoeditor.TextOracleTest",
    "com.simple.videoeditor.TitleOracleTest",
    "com.simple.videoeditor.WatermarkOracleTest",
]
# Original 69-test inventory plus five focused PNG watermark native tests.
REGRESSION_METHODS = {
    "OracleVerifierTest": (
        "testShippedSourcePassesOnlyIdentity",
        "testUnknownCaseIsSetupError",
        "testCaseSummaryRequiresDecoderEvidence",
        "testCaseSummaryPreservesMissingCandidateFailure",
        "testCheckerRuntimeErrorIsNotMismatch",
        "testDecoderDiagnosticsResetAndSnapshot",
        "testRealFullAndSparseDecodeWithPngExpectations",
        "testYuvPlaneOffsetsStridesAndOddCrop",
        "testColorMetadataFallbackAndDeadline",
        "testExtendedColorStandardUsesDocumentedBt601Matrix",
        "testUnsupportedColorMetadataStillRejected",
        "testPcmOffsetsAlignmentBudgetAndFormatChanges",
        "testDecoderHonorsInterruptionBeforeOpeningMedia",
        "testGaplessPaddingRebasesAndroidAudioPacketPts",
        "testGaplessPaddingDoesNotHideRealTimestampGap",
        "testGaplessPaddingPreservesQuantizedPtsAndSampleCounts",
        "testGaplessAccountingAcrossCompleteAacTimelines",
        "testGaplessPaddingDoesNotHideDroppedPcmOrAccessUnit",
        "testGaplessPaddingDoesNotAcceptCancellingGapAndOverlap",
        "testGaplessPaddingRequiresExactMetadataAndZeroStart",
        "testGaplessAccountingRequires1024SampleAacLcConfig",
    ),
    "MusicCompositionTest": (
        "testDefaultStillRetainsOriginalAudio",
        "testEncodingRateUsesRetimedMainAndUnchangedIntro",
        "testReplacementLoopsWithoutMixingOrSourceGain",
        "testSilentIntroCanPrecedeAudibleMain",
        "testMuteDoesNotForceSilentAudioTrack",
        "testIntroFitsEditedMainCanvasWithoutMainTrimOrSpeed",
        "testIntroRequiresMainDisplaySize",
        "testAnamorphicSourceUsesMedia3DisplayCanvas",
        "testBuilderSnapshotAndClearingSelections",
        "testSpeedMusicRendersBeforeLoopingToAvoidMedia3DurationBug",
        "testExportShortMusicLoopsAcrossIntroAndSlowMain",
        "testExportShortMusicLoopsAcrossIntroAndFastMain",
        "testExportLongMusicStopsAtTrimmedVideoEnd",
        "testExportSilentIntroThenAudibleSpeedEditedMain",
        "testSourceSpeedUsesOverflowSafePitchProcessor",
        "testPitchSearchDoesNotOverflowAtOrdinaryOrLoudLevels",
        "testPitchProcessorPreservesToneChangesAndAntiPhaseStereo",
        "testPitchProcessorChunkingDrainFlushAndExactDurations",
        "testPitchProcessorSilenceAndFormatValidation",
        "testPitchProcessorDrainsThroughMedia3PipelineWithGain",
    ),
    "IntroCompositionTest": (
        "testTitleSnapshotSurvivesTemplateAndBuilderMutation",
        "testClearingTitleKeepsImportedIntroAndMusic",
        "testInvalidTitleSettingsAreExplicitlyRejected",
        "testTitleThenFullIntroThenMainWithMainOnlyEdits",
        "testTitleMuteAndReplacementMusicStructures",
        "testTitleOnlyDelaysMainAudioWithoutForcedSilentTrack",
        "testExportTitleThenFastTrimmedMainPixelsAndPcm",
        "testTitleAudioCannotFallBackToDefaultFormatGap",
        "testTitleWithNoSourceAudioDoesNotSynthesizeATrack",
        "testExportTitleThenSlowTrimmedMainPixelsAndPcm",
        "testExportTitleImportedIntroFastMainAndLoopedMusic",
        "testExportTitleImportedIntroSlowMainAndLoopedMusic",
        "testExportMutedTitleAndMainHasNoAudioTrack",
        "testOversizedTitleExportRejectsAndCleansReservedFiles",
    ),
    "MusicOracleTest": ("testIndependentReferenceAndAudioNegatives",),
    "IntroOracleTest": (
        "testFrozenReferencePasses",
        "testMissingIntroFailsBeforeFrameDecoding",
        "testWrongDurationFailsBeforeFrameDecoding",
        "testReversedOrderFailsContentNotDurationOrFrameCount",
        "testWrongOriginalAudioPreservesVideoAndIntroAudio",
        "testFormatterRequiresCanonicalDecoderObject",
        "testMedia3ImportedIntroExportPassesFrozenOracle",
        "testIntroAssetRejectsCanonicalFileSymlink",
        "testIntroAssetRejectsCanonicalDirectorySymlink",
    ),
    "TextOracleTest": (
        "testPinnedControlsThroughAndroidDecoderAndFormatter",
        "testMedia3ActualTextExportMatchesIndependentOracle",
    ),
    "TitleOracleTest": (
        "testPinnedTitleControlsAndMetadataGate",
        "testActualGeneratedTitleExportThroughProductionPath",
    ),
    "WatermarkOracleTest": (
        "testPngSnapshotBoundsAndValidation",
        "testDefaultOffSnapshotPlacementAndClear",
        "testCacheCopyIsIndependentOfProviderAndFile",
        "testPinnedControlsThroughAndroidDecoderAndFormatter",
        "testActualPngWatermarkExportThroughProductionPath",
    ),
}
STAGE_TIMEOUTS = {"smoke": 300, "suite": 1100, "regressions": 1800}
PROGRESS = ROOT / "app" / "build" / "emulator-validation" / "progress.txt"
ACTIVE_EVIDENCE: Path | None = None
ACTIVE_STAGE = "startup"
COMMAND_NUMBER = 0
CANCEL_FILE: Path | None = None


def check_cancel() -> None:
    if CANCEL_FILE is not None and CANCEL_FILE.exists():
        raise KeyboardInterrupt("Owned workflow cancellation requested")


def progress(event: str) -> None:
    PROGRESS.parent.mkdir(parents=True, exist_ok=True)
    with PROGRESS.open("a", encoding="utf-8") as stream:
        stream.write(f"{datetime.now(timezone.utc).isoformat()} pid={os.getpid()} "
                     f"evidence={ACTIVE_EVIDENCE} stage={ACTIVE_STAGE} {event}\n")
        stream.flush()
        os.fsync(stream.fileno())


def require(condition: bool, message: str) -> None:
    if not condition:
        raise RuntimeError(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


ARTIFACT_ROOT: Path | None = None


def checked_evidence_path(path: Path, root: Path | None = None) -> Path:
    path = Path(path).absolute()
    require(".." not in path.parts, "Evidence paths cannot contain parent traversal")
    for parent in (path, *path.parents):
        if parent.exists() or parent.is_symlink():
            attributes = getattr(parent.lstat(), "st_file_attributes", 0)
            require(not parent.is_symlink() and not attributes & stat.FILE_ATTRIBUTE_REPARSE_POINT,
                    "Evidence paths cannot traverse symlinks or reparse points: " + str(parent))
    base = root or ARTIFACT_ROOT or ROOT
    require(path.resolve().is_relative_to(base.resolve()), "Evidence escaped its approved root")
    return path


def disk_preflight(path: Path, reserve_bytes: int) -> dict:
    parent = Path(path).absolute()
    while not parent.exists():
        parent = parent.parent
    free = shutil.disk_usage(parent).free
    require(free >= reserve_bytes,
            f"DISK_RESERVE: {parent} has {free} free bytes; requires {reserve_bytes}. "
            "Use a new --artifact-root on a volume with sufficient space; do not delete retained evidence.")
    return {"volume_path": str(parent), "free_bytes": free, "reserve_bytes": reserve_bytes}


def create_artifact_root(path: Path, reserve_bytes: int) -> tuple[Path, dict]:
    require(path.is_absolute() and path.parent != path, "--artifact-root must be an absolute new directory")
    checked_evidence_path(path, path)
    require(not path.exists(), "--artifact-root already exists; refusing to reuse another run's files")
    space = disk_preflight(path, reserve_bytes)
    path.mkdir(parents=True, exist_ok=False)
    return path, space


def write_json(path: Path, payload: dict) -> None:
    checked_evidence_path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    text = json.dumps(payload, indent=2, ensure_ascii=True, sort_keys=False) + "\n"
    pending = path.with_name(f".{path.name}.{os.getpid()}.{uuid.uuid4().hex}.pending")
    try:
        with pending.open("x", encoding="utf-8") as stream:
            stream.write(text)
            stream.flush()
            os.fsync(stream.fileno())
        for attempt in range(4):
            try:
                os.replace(pending, path)
                break
            except PermissionError as error:
                # Windows readers can briefly deny replacement of a closed report.
                # Keep the previous authoritative report intact; never unlink it.
                if getattr(error, "winerror", None) not in (5, 32, 33) or attempt == 3:
                    raise
                time.sleep(.05 * 2 ** attempt)
    finally:
        pending.unlink(missing_ok=True)


def run_cmd(command: list[str], timeout: int | None = None, *,
             stdout_path: Path | None = None, stderr_path: Path | None = None,
             read_output: bool = True, cwd=None, env=None) -> subprocess.CompletedProcess[bytes]:
    global COMMAND_NUMBER
    require(timeout is not None and timeout > 0, "Subprocesses require a finite timeout")
    check_cancel()
    COMMAND_NUMBER += 1
    directory = ACTIVE_EVIDENCE or PROGRESS.parent
    directory.mkdir(parents=True, exist_ok=True)
    prefix = f"command-{os.getpid()}-{COMMAND_NUMBER:04d}"
    stdout_path = stdout_path or directory / f"{prefix}.stdout.txt"
    stderr_path = stderr_path or directory / f"{prefix}.stderr.txt"
    started = time.monotonic()
    with stdout_path.open("wb", buffering=0) as out, stderr_path.open("wb", buffering=0) as err:
        process = subprocess.Popen(command, stdout=out, stderr=err, cwd=cwd, env=env)
        try:
            progress(f"subprocess start child_pid={process.pid} timeout={timeout}s "
                     f"stdout={stdout_path} stderr={stderr_path} command={command!r}")
            while True:
                check_cancel()
                remaining = timeout - (time.monotonic() - started)
                if remaining <= 0:
                    raise subprocess.TimeoutExpired(command, timeout)
                try:
                    process.wait(timeout=min(1, remaining))
                    break
                except subprocess.TimeoutExpired:
                    if int(time.monotonic() - started) % 30 == 0:
                        progress(f"heartbeat child_pid={process.pid} elapsed={time.monotonic() - started:.1f}s")
        finally:
            original_error = sys.exc_info()[1]
            try:
                if process.poll() is None:
                    # The recovery Python worker can itself own adb clients.
                    if os.name == "nt":
                        stopped = subprocess.run(["taskkill.exe", "/PID", str(process.pid), "/T", "/F"],
                                                 capture_output=True, timeout=10)
                        if stopped.returncode != 0 and process.poll() is None:
                            process.kill()
                            process.wait(timeout=10)
                            raise RuntimeError(f"Owned child-tree cleanup failed: pid={process.pid}")
                    if process.poll() is None:
                        process.kill()
                    process.wait(timeout=10)
            except (Exception, KeyboardInterrupt) as cleanup_error:
                if original_error is None:
                    raise
                original_error.cleanup_errors = getattr(original_error, "cleanup_errors", []) + [str(cleanup_error)]
            os.fsync(out.fileno())
            os.fsync(err.fileno())
            progress(f"subprocess end child_pid={process.pid} returncode={process.returncode} "
                     f"elapsed={time.monotonic() - started:.1f}s")
    return subprocess.CompletedProcess(command, process.returncode,
                                       stdout_path.read_bytes() if read_output else b"",
                                       stderr_path.read_bytes() if read_output else b"")


def adb_base(adb: Path, serial: str) -> list[str]:
    return [str(adb), "-s", serial]


def adb_text(adb: Path, serial: str, *args: str, timeout: int = 60) -> tuple[int, str, str]:
    completed = run_cmd(adb_base(adb, serial) + list(args), timeout=timeout)
    return completed.returncode, completed.stdout.decode("utf-8", "replace"), completed.stderr.decode("utf-8", "replace")


def adb_binary(adb: Path, serial: str, *args: str, timeout: int = 60) -> tuple[int, bytes, bytes]:
    completed = run_cmd(adb_base(adb, serial) + list(args), timeout=timeout)
    return completed.returncode, completed.stdout, completed.stderr


def expected_stage_tests(stage: str) -> set[str]:
    if stage in STAGE_METHODS:
        return {STAGE_METHODS[stage]}
    require(stage == "regressions", f"Unknown instrumentation stage: {stage!r}")
    identities = {f"{APP_PACKAGE}.{cls}#{method}"
                  for cls, methods in REGRESSION_METHODS.items() for method in methods}
    require(len(REGRESSION_CLASSES) == 8
            and {item.split("#")[0] for item in identities} == set(REGRESSION_CLASSES)
            and all(REGRESSION_METHODS.values())
            and sum(map(len, REGRESSION_METHODS.values())) == len(identities) == 74,
            "Invalid original 69 + 5 watermark native test inventory")
    return identities


def parse_instrumentation(text: str, stage: str | None = None) -> dict:
    expected = expected_stage_tests(stage) if stage is not None else None
    tests: dict[tuple[str, str], dict] = {}
    pending: dict[str, str] = {}
    summary_line = None
    instrumentation_code = None
    terminal_codes = []
    declared_counts = set()
    errors = []
    failed = False
    for line in text.splitlines():
        if line.startswith("INSTRUMENTATION_STATUS: "):
            if terminal_codes:
                errors.append("Test status after terminal instrumentation code")
            key, _, value = line[len("INSTRUMENTATION_STATUS: "):].partition("=")
            if key in pending:
                errors.append(f"Duplicate status key: {key}")
            pending[key] = value
        elif line.startswith("INSTRUMENTATION_STATUS_CODE: "):
            try:
                code = int(line.rsplit(":", 1)[1].strip())
            except ValueError:
                errors.append("Invalid per-test status code")
                pending = {}
                continue
            if terminal_codes:
                errors.append("Per-test code after terminal instrumentation code")
            cls, test = pending.get("class"), pending.get("test")
            try:
                total, current = int(pending["numtests"]), int(pending["current"])
                declared_counts.add(total)
                if not 1 <= current <= total:
                    errors.append("Per-test current outside declared test count")
            except (KeyError, ValueError):
                current = None
                errors.append("Missing or invalid numtests/current")
            if cls and test:
                record = tests.setdefault((cls, test), {
                    "class": cls, "test": test, "status": "RUNNING",
                    "codes": [], "current": current,
                })
                record["codes"].append(code)
                if record["current"] != current:
                    errors.append(f"Changed current for {cls}#{test}")
                record["status"] = {
                    0: "PASS", -1: "ERROR", -2: "FAIL", 1: "RUNNING",
                    -3: "IGNORED", -4: "ASSUMPTION_FAILURE",
                }.get(code, "UNKNOWN")
                if code in (-1, -2):
                    failed = True
            else:
                errors.append("Per-test status missing class/test identity")
            pending = {}
        elif line.startswith("INSTRUMENTATION_CODE:"):
            try:
                instrumentation_code = int(line.split(":", 1)[1].strip())
            except ValueError:
                instrumentation_code = None
                errors.append("Invalid terminal instrumentation code")
            terminal_codes.append(instrumentation_code)
        elif line.startswith("INSTRUMENTATION_RESULT: stream="):
            summary_line = line.split("=", 1)[1]
        elif line.startswith("INSTRUMENTATION_FAILED:"):
            failed = True
        elif line.startswith("FAILURES!!!"):
            failed = True
        elif line.startswith("OK ("):
            summary_line = line
            if terminal_codes:
                errors.append("Test summary after terminal instrumentation code")
    if pending:
        errors.append("Unterminated test status bundle")
    counts = Counter(record["status"] for record in tests.values())
    summaries = re.findall(r"^OK \((\d+) tests?\)\s*$", text, re.MULTILINE)
    ok_tests = int(summaries[0]) if len(summaries) == 1 else None
    if terminal_codes != [-1]:
        errors.append("Expected exactly one successful terminal INSTRUMENTATION_CODE: -1")
    if not ok_tests or declared_counts != {ok_tests}:
        errors.append("Missing or inconsistent OK/numtests counts")
    if any(record["codes"] != [1, 0] for record in tests.values()):
        errors.append("Each test requires exactly one start and one passing completion")
    if ok_tests and (len(tests) != ok_tests
                     or {record["current"] for record in tests.values()} != set(range(1, len(tests) + 1))):
        errors.append("Incomplete or duplicate test identities/current indexes")
    actual = {f"{cls}#{test}" for cls, test in tests}
    if expected is not None and (actual != expected or ok_tests != len(expected)):
        errors.append(f"Incomplete or unexpected {stage} test inventory")
    passed = bool(ok_tests and not failed and not errors
                  and len(tests) == ok_tests and counts.get("PASS", 0) == ok_tests)
    return {
        "passed": passed,
        "summary_line": summary_line,
        "instrumentation_code": instrumentation_code,
        "ok_tests": ok_tests,
        "test_counts": dict(counts),
        "tests": list(tests.values()),
        "saw_failure_text": failed,
        "protocol_errors": errors,
        "expected_test_count": len(expected) if expected is not None else None,
        "missing_tests": sorted(expected - actual) if expected is not None else [],
        "unexpected_tests": sorted(actual - expected) if expected is not None else [],
    }


def capture_device_state(adb: Path, serial: str, evidence: Path) -> dict:
    props = {}
    for key in ("ro.kernel.qemu", "sys.boot_completed", "ro.product.model", "ro.build.version.sdk", "ro.build.fingerprint"):
        _, stdout, _ = adb_text(adb, serial, "shell", "getprop", key, timeout=30)
        props[key] = stdout.strip()
    _, codec_out, codec_err = adb_text(adb, serial, "shell", "dumpsys", "media.codec", timeout=60)
    props_path = evidence / "device-state.json"
    write_json(props_path, {"captured_at": datetime.now(timezone.utc).isoformat(), "props": props})
    (evidence / "media-codec.txt").write_text(codec_out + codec_err, encoding="utf-8", errors="replace")
    return props


def force_stop(adb: Path, serial: str) -> None:
    global CANCEL_FILE
    progress(f"stopping owned instrumentation serial={serial} package={APP_PACKAGE}")
    cancellation = CANCEL_FILE
    try:
        CANCEL_FILE = None
        rc, _, stderr = adb_text(adb, serial, "shell", "am", "force-stop", APP_PACKAGE, timeout=30)
        require(rc == 0, f"Could not stop owned instrumentation: {stderr}")
    finally:
        CANCEL_FILE = cancellation


def suite_inventory(adb: Path, serial: str) -> list[str]:
    rc, stdout, stderr = adb_text(
        adb, serial, "shell", "run-as", APP_PACKAGE, "sh", "-c",
        "'if [ -d files/selftest ]; then find files/selftest -type d; fi'")
    require(rc == 0, f"Cannot inventory existing suite directories: {stderr}")
    return stdout.splitlines()


def capture_suite(evidence: Path, stage_entry: dict, *, expected_revision=None,
                  expected_apk_hash=None, expected_policy=None) -> None:
    baseline = set(stage_entry["previous_suite_directories"])
    with tarfile.open(evidence / "suite-app-data.tar", "r:") as archive:
        candidates = [member for member in archive.getmembers()
                      if member.isfile() and member.name.removeprefix("./").startswith("files/selftest/")
                      and member.name.endswith("/suite.json")
                      and member.name.removeprefix("./").rsplit("/", 1)[0] not in baseline]
        require(len(candidates) == 1, f"Expected one fresh suite.json, found {len(candidates)}")
        member = candidates[0]
        require(member.size <= 16 * 1024 * 1024, "Unexpectedly large suite.json")
        with archive.extractfile(member) as stream:
            suite = json.load(stream)
    write_json(evidence / "terminal-suite.json", suite)
    stage_entry["terminal_suite"] = suite
    stage_entry["suite_archive_member"] = member.name
    revision = suite.get("metadata", {}).get("source_revision")
    stage_entry["source_revision"] = revision
    stage_entry["installed_apk_sha256"] = suite.get("metadata", {}).get("installed_apk_sha256")
    if expected_revision is not None:
        require(revision == expected_revision,
                f"Captured source_revision {revision!r} != expected {expected_revision!r}")
    if expected_apk_hash is not None:
        require(stage_entry["installed_apk_sha256"] == expected_apk_hash, "Captured installed APK hash mismatch")
    require(isinstance(revision, str) and
            re.fullmatch(r"[0-9a-f]{40}", revision) is not None,
            f"Missing or invalid captured source_revision: {revision!r}")
    exports, controls = suite.get("exports"), suite.get("checker_controls")
    require(suite.get("mode") == "FULL" and suite.get("coverage") == "FULL_FROZEN_SUITE"
            and suite.get("full_suite_status") == "PASS",
            "A selected diagnostic subset is not a full suite pass")
    require(suite.get("complete") is True and suite.get("status") == "PASS"
            and not suite.get("cancelled") and not suite.get("timed_out") and not suite.get("fatal_error"),
            "Captured suite is not a successful terminal result")
    require(suite.get("planned_export_count") == 17 and isinstance(exports, list) and len(exports) == 17,
            "Suite must contain exactly 17 exports")
    require(suite.get("planned_control_count") == 60 and isinstance(controls, list) and len(controls) == 60,
            "Suite must contain exactly 60 checker controls")
    require(all(item.get("status") == item.get("expected_status") == item.get("actual_status") == "PASS"
                for item in exports), "Not all exports passed")
    require(all(item.get("status") == "PASS" and item.get("expected_status") in ("PASS", "FAIL")
                and item.get("actual_status") == item.get("expected_status") for item in controls),
            "Checker controls did not match their expectations")
    require(Counter(item["expected_status"] for item in controls) == {"PASS": 22, "FAIL": 38},
            "Expected 22 positive and 38 rejected negative controls")
    stage_entry["export_count"] = len(exports)
    stage_entry["control_count"] = len(controls)
    if expected_policy is not None:
        with tarfile.open(evidence / "suite-app-data.tar", "r:") as archive:
            prefix = member.name.rsplit("/", 1)[0] + "/"
            report = archive.getmember(prefix + "report.txt")
            require(report.size <= 32 * 1024 * 1024, "Unexpectedly large policy report")
            text = archive.extractfile(report).read().decode("utf-8", "replace")
            modes = re.findall(r"\bencodingMode=([\w-]+)", text)
            completed = re.findall(r"completed pass=\S+ encodingMode=([\w-]+) selectedVideoBackend=(\S+)", text)
            stage_entry["recorded_policies"] = sorted(set(modes))
            stage_entry["completed_passes"] = [{"policy": mode, "encoder": encoder} for mode, encoder in completed]
            require(set(modes) == {expected_policy} and len(completed) >= len(exports)
                    and all(mode == expected_policy for mode, _ in completed),
                    f"Recorded encoding policy does not match {expected_policy!r}")
            for item in exports:
                raw = archive.getmember(prefix + item["report"])
                require(raw.size <= 16 * 1024 * 1024, "Unexpectedly large export report")
                result = json.load(archive.extractfile(raw))
                require(result.get("run_metadata", {}).get("source_revision") == expected_revision,
                        "Export report revision mismatch")
                require(result.get("run_metadata", {}).get("installed_apk_sha256") == expected_apk_hash,
                        "Export report installed APK hash mismatch")


def persist_summary(evidence: Path, summary: dict) -> None:
    write_json(evidence / "summary.json", summary)
    suite = summary["stages"].get("suite", {})
    write_json(evidence / "native-results.json", {
        **summary,
        "source_revision": suite.get("source_revision"),
        "terminal_suite": suite.get("terminal_suite"),
        "suite_validation_status": suite.get("status", "NOT_RUN"),
        "expected_export_count": 17,
        "expected_control_count": 60,
    })


def capture_artifacts(adb: Path, serial: str, evidence: Path, stage_name: str, summary: dict) -> None:
    logcat_path = evidence / f"{stage_name}-logcat.txt"
    rc, stdout, stderr = adb_binary(adb, serial, "logcat", "-d", "-v", "threadtime", timeout=90)
    logcat_path.write_bytes(stdout + stderr)
    capture_path = evidence / f"{stage_name}-app-data.tar"
    directories = []
    for directory in ("files/selftest", "files/emulator-validation"):
        exists, _, _ = adb_text(adb, serial, "shell", "run-as", APP_PACKAGE,
                                "test", "-d", directory)
        if exists == 0:
            directories.append(directory)
    require(bool(directories) or stage_name == "regressions",
            "No app-owned validation artifacts to capture")
    if directories:
        completed = run_cmd(adb_base(adb, serial) + [
            "exec-out", "run-as", APP_PACKAGE, "tar", "-cf", "-", *directories],
            timeout=90, stdout_path=capture_path,
            stderr_path=evidence / f"{stage_name}-app-data.stderr.txt", read_output=False)
        rc2 = completed.returncode
    else:
        rc2 = 0
    summary["artifacts"] = {
        "logcat": {
            "path": str(logcat_path),
            "sha256": sha256(logcat_path) if logcat_path.exists() and logcat_path.stat().st_size else None,
            "returncode": rc,
            "status": "PASSED" if rc == 0 and logcat_path.stat().st_size > 0 else "FAILED",
        },
        "app_data": {
            "path": str(capture_path),
            "sha256": sha256(capture_path) if capture_path.exists() and capture_path.stat().st_size else None,
            "returncode": rc2,
            "directories": directories,
            "status": ("NOT_APPLICABLE" if not directories else
                       "PASSED" if rc2 == 0 and capture_path.stat().st_size > 0 else "FAILED"),
        },
    }
    if stderr:
        (evidence / f"{stage_name}-logcat.stderr.txt").write_text(stderr.decode("utf-8", "replace"), encoding="utf-8")
    
def run_instrumentation(adb: Path, serial: str, test_class: str, timeout: int, stage_name: str, evidence: Path) -> dict:
    stdout_path = evidence / f"{stage_name}.stdout.txt"
    stderr_path = evidence / f"{stage_name}.stderr.txt"
    command = ["shell", "am", "instrument", "-w", "-r", "-e", "class", test_class, RUNNER]
    started = time.monotonic()
    try:
        completed = run_cmd(adb_base(adb, serial) + command, timeout=timeout,
                            stdout_path=stdout_path, stderr_path=stderr_path)
        raw = completed.stdout.decode("utf-8", "replace") + ("\n" + completed.stderr.decode("utf-8", "replace") if completed.stderr else "")
        parsed = parse_instrumentation(raw, stage_name)
        return {
            "command": [str(adb), "-s", serial, *command],
            "returncode": completed.returncode,
            "duration_seconds": round(time.monotonic() - started, 3),
            "stdout_path": str(stdout_path),
            "stderr_path": str(stderr_path),
            "raw_sha256": sha256(stdout_path),
            "parsed": parsed,
            "timed_out": False,
        }
    except subprocess.TimeoutExpired:
        force_stop(adb, serial)
        return {
            "command": [str(adb), "-s", serial, *command],
            "returncode": None,
            "duration_seconds": round(time.monotonic() - started, 3),
            "stdout_path": str(stdout_path),
            "stderr_path": str(stderr_path),
            "raw_sha256": sha256(stdout_path) if stdout_path.exists() and stdout_path.stat().st_size else None,
            "parsed": {"passed": False, "summary_line": None, "instrumentation_code": None, "ok_tests": None, "test_counts": {}, "tests": [], "saw_failure_text": False},
            "timed_out": True,
        }
    except (Exception, KeyboardInterrupt) as error:
        try:
            force_stop(adb, serial)
        except (Exception, KeyboardInterrupt) as cleanup_error:
            error.cleanup_errors = getattr(error, "cleanup_errors", []) + [str(cleanup_error)]
        raise


def run_stage(adb: Path, serial: str, evidence: Path, summary: dict, stage: str, *,
              expected_revision=None, expected_apk_hash=None, expected_policy=None) -> bool:
    try:
        return _run_stage(adb, serial, evidence, summary, stage, expected_revision=expected_revision,
                          expected_apk_hash=expected_apk_hash, expected_policy=expected_policy)
    except (Exception, KeyboardInterrupt) as error:
        entry = summary["stages"].setdefault(stage, {})
        entry["status"] = "CANCELLED" if isinstance(error, KeyboardInterrupt) else "FAILED"
        entry["error"] = str(error) or type(error).__name__
        entry["finished_at"] = datetime.now(timezone.utc).isoformat()
        persist_summary(evidence, summary)
        raise


def _run_stage(adb: Path, serial: str, evidence: Path, summary: dict, stage: str, *,
               expected_revision=None, expected_apk_hash=None, expected_policy=None) -> bool:
    global ACTIVE_STAGE
    ACTIVE_STAGE = stage
    progress("stage start")
    stage_entry = summary["stages"].setdefault(stage, {})
    stage_entry["status"] = "RUNNING"
    stage_entry["started_at"] = datetime.now(timezone.utc).isoformat()
    stage_entry["results"] = []
    stage_entry.update(expected_revision=expected_revision, expected_apk_hash=expected_apk_hash,
                       expected_policy=expected_policy)
    persist_summary(evidence, summary)
    if stage == "suite":
        stage_entry["previous_suite_directories"] = suite_inventory(adb, serial)
        persist_summary(evidence, summary)
    if stage in ("smoke", "suite"):
        test_class = STAGE_METHODS[stage]
        result = run_instrumentation(adb, serial, test_class, STAGE_TIMEOUTS[stage], stage, evidence)
        stage_entry["results"].append(result)
        if result["timed_out"]:
            stage_entry["status"] = "TIMEOUT"
        else:
            parsed = result["parsed"]
            stage_entry["status"] = "PASSED" if result["returncode"] == 0 and parsed["passed"] else "FAILED"
            stage_entry["instrumentation"] = parsed
            stage_entry["test_count"] = parsed["ok_tests"]
            stage_entry["per_test_status_count"] = parsed["test_counts"]
    else:
        result = run_instrumentation(adb, serial, ",".join(REGRESSION_CLASSES),
                                     STAGE_TIMEOUTS[stage], stage, evidence)
        stage_entry["results"].append(result)
        if result["timed_out"]:
            stage_entry["status"] = "TIMEOUT"
        else:
            stage_entry["status"] = "PASSED" if result["returncode"] == 0 and result["parsed"]["passed"] else "FAILED"
        stage_entry["test_count"] = result["parsed"]["ok_tests"]
        stage_entry["per_test_status_count"] = result["parsed"]["test_counts"]
    stage_entry["instrumentation_status"] = stage_entry["status"]
    stage_entry["status"] = "RUNNING"
    persist_summary(evidence, summary)
    try:
        capture_artifacts(adb, serial, evidence, stage, stage_entry)
        require(all(item["status"] in ("PASSED", "NOT_APPLICABLE")
                    for item in stage_entry["artifacts"].values()), "Artifact capture failed")
    except Exception as error:
        stage_entry["artifact_error"] = str(error)
    stage_entry["status"] = stage_entry["instrumentation_status"]
    if "artifact_error" in stage_entry and stage_entry["status"] != "TIMEOUT":
        stage_entry["status"] = "FAILED"
    if stage == "suite":
        try:
            capture_suite(evidence, stage_entry, expected_revision=expected_revision,
                          expected_apk_hash=expected_apk_hash, expected_policy=expected_policy)
        except Exception as error:
            stage_entry["suite_error"] = str(error)
            if stage_entry["status"] != "TIMEOUT":
                stage_entry["status"] = "FAILED"
    stage_entry["finished_at"] = datetime.now(timezone.utc).isoformat()
    persist_summary(evidence, summary)
    progress(f"stage end status={stage_entry['status']}")
    return stage_entry["status"] == "PASSED"


def main(argv: list[str] | None = None) -> int:
    global ACTIVE_EVIDENCE, ACTIVE_STAGE
    parser = argparse.ArgumentParser(description="Run Android instrumentation against an already-booted emulator.")
    parser.add_argument("--serial", required=True, help="Required emulator serial, emulator-<port> only")
    parser.add_argument("--stage", choices=("smoke", "suite", "regressions", "all"), default="all")
    parser.add_argument("--adb", default=str(DEFAULT_ADB))
    parser.add_argument("--app-apk", default=str(DEFAULT_APP_APK))
    parser.add_argument("--test-apk", default=str(DEFAULT_TEST_APK))
    parser.add_argument("--evidence-dir", default=str(DEFAULT_EVIDENCE))
    args = parser.parse_args(argv)
    require(re.fullmatch(r"emulator-\d+", args.serial) is not None, "--serial must be emulator-<port>")
    adb = Path(args.adb).resolve()
    app_apk = Path(args.app_apk).resolve()
    test_apk = Path(args.test_apk).resolve()
    evidence = (ROOT / args.evidence_dir).resolve()
    require(evidence.is_relative_to(ROOT), "--evidence-dir must be inside the project")
    require(adb.is_file(), f"Missing adb: {adb}")
    require(app_apk.is_file(), f"Missing app apk: {app_apk}")
    require(test_apk.is_file(), f"Missing test apk: {test_apk}")
    evidence.mkdir(parents=True, exist_ok=False)
    ACTIVE_EVIDENCE = evidence
    ACTIVE_STAGE = "setup"
    progress(f"run start serial={args.serial}")
    progress("stage start")
    summary = {
        "started_at": datetime.now(timezone.utc).isoformat(),
        "repo_root": str(ROOT),
        "serial": args.serial,
        "pid": os.getpid(),
        "requested_stage": args.stage,
        "status": "RUNNING",
        "adb": str(adb),
        "app_apk": {"path": str(app_apk), "sha256": sha256(app_apk)},
        "test_apk": {"path": str(test_apk), "sha256": sha256(test_apk)},
        "evidence_dir": str(evidence),
        "stages": {},
    }
    persist_summary(evidence, summary)
    verified_emulator = False
    instrumentation_owned = False
    try:
        props = capture_device_state(adb, args.serial, evidence)
        require(props.get("ro.kernel.qemu") == "1", "Selected device is not an emulator")
        require(props.get("sys.boot_completed") == "1", "Selected emulator is not booted")
        verified_emulator = True
        summary["device"] = props
        persist_summary(evidence, summary)
        for apk in (app_apk, test_apk):
            completed = run_cmd(adb_base(adb, args.serial) + ["install", "-r", str(apk)], timeout=300)
            out = completed.stdout.decode("utf-8", "replace") + completed.stderr.decode("utf-8", "replace")
            install_key = "app_install" if apk == app_apk else "test_install"
            summary[install_key] = {"apk": str(apk), "sha256": sha256(apk), "returncode": completed.returncode, "output": out}
            persist_summary(evidence, summary)
            require(completed.returncode == 0 and "Success" in out, f"Install failed for {apk}")
        progress("stage end status=PASSED")
        requested = ("smoke", "suite", "regressions") if args.stage == "all" else (args.stage,)
        passed = True
        for stage in requested:
            instrumentation_owned = True
            passed = run_stage(adb, args.serial, evidence, summary, stage) and passed
        summary["finished_at"] = datetime.now(timezone.utc).isoformat()
        summary["passed"] = passed
        summary["status"] = "PASSED" if passed else "FAILED"
        persist_summary(evidence, summary)
        progress(f"run end status={summary['status']}")
        return 0 if passed else 1
    except (Exception, KeyboardInterrupt) as error:
        summary["finished_at"] = datetime.now(timezone.utc).isoformat()
        summary["passed"] = False
        summary["status"] = "INTERRUPTED" if isinstance(error, KeyboardInterrupt) else "ERROR"
        summary["error"] = str(error) or type(error).__name__
        summary["cleanup_errors"] = getattr(error, "cleanup_errors", [])
        if verified_emulator and instrumentation_owned:
            try:
                force_stop(adb, args.serial)
            except (Exception, KeyboardInterrupt) as cleanup_error:
                summary["cleanup_errors"].append(str(cleanup_error))
        if ACTIVE_STAGE == "setup":
            progress(f"stage end status={summary['status']}")
        for stage in summary["stages"].values():
            if stage["status"] == "RUNNING":
                stage["status"] = "INTERRUPTED" if isinstance(error, KeyboardInterrupt) else "ERROR"
                stage["finished_at"] = summary["finished_at"]
                progress(f"stage end status={stage['status']}")
        persist_summary(evidence, summary)
        progress(f"run end status={summary['status']} error={summary['error']}")
        print(f"FAIL emulator validation: {error}", file=sys.stderr)
        return 130 if isinstance(error, KeyboardInterrupt) else 1


if __name__ == "__main__":
    sys.exit(main())
