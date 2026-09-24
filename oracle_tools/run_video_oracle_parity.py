from __future__ import annotations

import sys

sys.dont_write_bytecode = True

import argparse
import hashlib
import importlib.util
import json
import math
import os
import subprocess
import time
from datetime import datetime, timezone
from pathlib import Path


DEFAULT_REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_ORACLE_ROOT = Path(r"D:\jfpx\video-oracle")
REPORT_NAME = "video_oracle_parity_report.json"
EXPECTED_PINS = {
    "manifest": ("manifest.json", "c0095261eed250fc8db6d49ce0d0cb9498e1552adac2367f0d4baff86b29362f"),
    "android_cases": ("android_cases.json", "e0bde89572334532b7a32947702d03e6f10904fb14eb40a903bd93a138b293a2"),
    "contract_text": ("CONTRACT.txt", "9f5523d13533b20a0f2846d9bef0318c1d861acda42c942c543d94afcd11d5cb"),
    "oracle_py": ("oracle.py", "cf69ba00a8e82b8fec70cff5c96b8237e1673401ca4afff73cddd14f4aee18a1"),
}


class ArgumentParser(argparse.ArgumentParser):
    def error(self, message: str) -> None:
        raise ValueError(message)


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def inside(path: Path, root: Path) -> bool:
    return path == root or root in path.parents


def safe_build_root(repo_root: Path, oracle_root: Path) -> Path:
    build = (repo_root / "app" / "build" / "oracle-parity").resolve()
    oracle_root = oracle_root.resolve()
    require(
        not inside(build, oracle_root) and not inside(oracle_root, build),
        f"Unsafe output/frozen-root overlap: {build} and {oracle_root}",
    )
    # Existing redirected outputs (including classes) must not bypass the root check.
    if build.exists():
        require(build.is_dir(), f"Build output is not a directory: {build}")
        for directory, directories, files in os.walk(build, followlinks=False):
            for name in directories + files:
                path = Path(directory) / name
                resolved = path.resolve()
                require(
                    inside(resolved, build) and not inside(resolved, oracle_root),
                    f"Unsafe redirected output: {path}",
                )
                require(
                    not path.is_symlink()
                    and not (hasattr(path, "is_junction") and path.is_junction()),
                    f"Linked output is not allowed: {path}",
                )
                if path.is_file():
                    require(path.stat().st_nlink == 1, f"Hard-linked output is not allowed: {path}")
    return build


def discover_oracle_root(repo_root: Path) -> Path:
    metadata = repo_root / "local.properties"
    if metadata.is_file():
        for line in metadata.read_text(encoding="utf-8-sig").splitlines():
            key, separator, value = line.partition("=")
            if separator and key.strip() in ("oracle.root", "oracle_root"):
                value = value.strip().replace("\\\\", "\\").replace("\\:", ":")
                require(bool(value), f"Empty oracle root in {metadata}")
                root = Path(value).expanduser()
                return (root if root.is_absolute() else repo_root / root).resolve()
    return DEFAULT_ORACLE_ROOT.resolve()


def load_json(text: str):
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result

    def invalid_constant(value):
        raise ValueError(f"Non-finite JSON number: {value}")

    def finite_float(value):
        parsed = float(value)
        require(math.isfinite(parsed), f"Non-finite JSON number: {value}")
        return parsed

    return json.loads(
        text, object_pairs_hook=object_pairs, parse_constant=invalid_constant, parse_float=finite_float,
    )


def verify_inputs(repo_root: Path, oracle_root: Path) -> dict:
    require(oracle_root.is_dir(), f"Missing frozen oracle root: {oracle_root}")
    module_path = Path(__file__).resolve().with_name("build_android_video_oracle.py")
    spec = importlib.util.spec_from_file_location("_video_oracle_frozen_verifier", module_path)
    require(spec is not None and spec.loader is not None, f"Cannot load verifier: {module_path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    verifier = getattr(module, "verify_frozen_inputs", None)
    require(callable(verifier), "build_android_video_oracle.py must expose verify_frozen_inputs(oracle_root)")
    result = verifier(oracle_root)
    require(result is not False, "verify_frozen_inputs returned False")

    # Only the read-only verifier is called; never build_contract, sync_assets or main.
    asset_root = repo_root / "app" / "src" / "main" / "assets" / "video-oracle"
    contract = load_json((asset_root / "android-contract.json").read_text(encoding="utf-8-sig"))
    require(isinstance(contract, dict) and isinstance(contract.get("pins"), dict), "Missing asset contract pins")
    checked = {}
    for key, (filename, expected) in EXPECTED_PINS.items():
        pin = contract["pins"].get(key)
        require(
            isinstance(pin, dict) and pin.get("path") == filename and pin.get("sha256") == expected,
            f"Asset contract pin does not match expected literal: {filename}",
        )
        actual = hashlib.sha256((oracle_root / filename).read_bytes()).hexdigest()
        require(actual == expected, f"Frozen input does not match expected literal: {filename}")
        checked[filename] = actual
    return checked


def validate_report(report) -> None:
    require(isinstance(report, dict), "Java stdout must be a JSON object")
    require(type(report.get("schema_version")) is int and report["schema_version"] == 1, "Invalid schema_version")
    require(type(report.get("passed")) is bool, "Missing boolean passed")
    controls = report.get("controls")
    require(isinstance(controls, list), "Missing controls array")
    require(len(controls) == 36, f"Expected 36 controls, received {len(controls)}")
    counts = {"positive": 0, "negative": 0}
    ids = set()
    for index, control in enumerate(controls):
        label = f"controls[{index}]"
        require(isinstance(control, dict), f"{label} must be an object")
        for field in ("id", "kind", "case_id", "input"):
            require(isinstance(control.get(field), str) and bool(control[field]), f"{label}: missing {field}")
        label = control["id"]
        require(label not in ids, f"Duplicate control id: {label}")
        ids.add(label)
        kind = control["kind"]
        require(kind in counts, f"{label}: invalid control kind")
        counts[kind] += 1
        expected = control.get("expected")
        require(isinstance(expected, dict), f"{label}: missing expected outcome")
        require(expected.get("passed") is (kind == "positive"), f"{label}: invalid expected.passed")
        prefixes = expected.get("failure_prefixes")
        require(
            isinstance(prefixes, list) and all(isinstance(p, str) and p for p in prefixes),
            f"{label}: invalid failure_prefixes",
        )
        require(bool(prefixes) == (kind == "negative"), f"{label}: inappropriate failure_prefixes")
        for mode in ("full", "sparse"):
            outcome = control.get(mode)
            require(isinstance(outcome, dict), f"{label}: missing {mode} outcome")
            require(isinstance(outcome.get("storage"), dict) and bool(outcome["storage"]), f"{label}: missing {mode} storage")
            core = outcome.get("report")
            require(isinstance(core, dict), f"{label}: missing {mode} core report")
            require(type(core.get("passed")) is bool, f"{label}: missing {mode} core passed")
            failures = core.get("failed_assertions")
            require(
                isinstance(failures, list) and all(isinstance(f, str) for f in failures),
                f"{label}: missing {mode} failed_assertions",
            )
            require(isinstance(core.get("checks"), list) and bool(core["checks"]), f"{label}: missing {mode} core checks")
            require(isinstance(core.get("metrics"), dict), f"{label}: missing {mode} core metrics")
            core_failures = []
            for check in core["checks"]:
                require(
                    isinstance(check, dict) and type(check.get("passed")) is bool
                    and isinstance(check.get("assertion"), str) and bool(check["assertion"]),
                    f"{label}: invalid {mode} core check",
                )
                if not check["passed"]:
                    core_failures.append(check["assertion"])
            require(core_failures == failures, f"{label}: inconsistent {mode} failed_assertions")
            require(core["passed"] == (not failures), f"{label}: inconsistent {mode} core outcome")
            require(core.get("status") == ("PASS" if core["passed"] else "FAIL"), f"{label}: invalid {mode} core status")
            require(core.get("case") == control["case_id"], f"{label}: incorrect {mode} case")
            matched = core["passed"] is expected["passed"] and all(
                any(failure.startswith(prefix) for failure in failures) for prefix in prefixes
            )
            require(outcome.get("expected_outcome_matched") is matched, f"{label}: inconsistent {mode} outcome")
            require(matched and not outcome.get("error"), f"{label}: {mode} did not match expected outcome")
        require(control.get("equivalent") is True, f"{label}: full/sparse reports not equivalent")
        require(control["full"]["report"] == control["sparse"]["report"], f"{label}: unequal full/sparse core reports")
        require(control.get("passed") is True and not control.get("error"), f"{label}: control failed")
    require(counts == {"positive": 17, "negative": 19}, f"Incorrect control counts: {counts}")
    summary = report.get("summary")
    require(isinstance(summary, dict), "Missing summary object")
    for kind, count in counts.items():
        require(type(summary.get(kind)) is int and summary[kind] == count, f"Incorrect summary.{kind}")
    checks = report.get("checks")
    require(isinstance(checks, dict) and bool(checks), "Missing checks object")
    require(
        all(value is True or (isinstance(value, dict) and value.get("passed") is True and not value.get("error"))
            for value in checks.values()),
        "Java checks must all pass",
    )
    require(report["passed"] and not report.get("error"), "Java reported a failure")


def run_process(command: list[str], cwd: Path, timeout: int, runner: dict, stage: str) -> dict:
    diagnostic = {"command": command, "cwd": str(cwd), "timeout_seconds": timeout}
    runner["processes"][stage] = diagnostic
    environment = os.environ.copy()
    for key in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "FFREPORT"):
        environment.pop(key, None)
    started = time.monotonic()
    try:
        process = subprocess.run(
            command, cwd=cwd, capture_output=True, text=True, encoding="utf-8",
            errors="replace", timeout=timeout, check=False, env=environment,
        )
        diagnostic.update(returncode=process.returncode, stdout=process.stdout, stderr=process.stderr)
    except subprocess.TimeoutExpired as exc:
        def text(value):
            return value.decode("utf-8", errors="replace") if isinstance(value, bytes) else value or ""

        diagnostic.update(returncode=None, stdout=text(exc.stdout), stderr=text(exc.stderr), timed_out=True)
    except OSError as exc:
        diagnostic.update(returncode=None, stdout="", stderr=str(exc))
    finally:
        diagnostic["duration_seconds"] = round(time.monotonic() - started, 3)
    return diagnostic


def main(argv: list[str] | None = None) -> int:
    parser = ArgumentParser(description="Run read-only full/sparse Java video oracle parity.")
    parser.add_argument("--oracle-root", help="Frozen root (local.properties oracle.root, or D:\\jfpx\\video-oracle)")
    parser.add_argument("--repo-root", default=str(DEFAULT_REPO_ROOT))
    argv = sys.argv[1:] if argv is None else argv
    if "-h" in argv or "--help" in argv:
        parser.print_help()
        return 0
    runner = {
        "started_at": datetime.now(timezone.utc).isoformat(),
        "python": sys.version,
        "stage": "arguments",
        "processes": {},
        "errors": [],
    }
    report = {"schema_version": 1, "passed": False, "controls": [], "runner": runner}
    repo_root = DEFAULT_REPO_ROOT
    oracle_root = DEFAULT_ORACLE_ROOT.resolve()
    started = time.monotonic()
    try:
        args, unknown = parser.parse_known_args(argv)
        repo_root = Path(args.repo_root).expanduser().resolve()
        runner["stage"] = "preflight"
        oracle_root = (
            Path(args.oracle_root).expanduser().resolve()
            if args.oracle_root is not None else discover_oracle_root(repo_root)
        )
        runner.update(repo_root=str(repo_root), oracle_root=str(oracle_root))
        build_root = safe_build_root(repo_root, oracle_root)
        require(not unknown, f"Unrecognized arguments: {' '.join(unknown)}")
        require(repo_root.is_dir(), f"Missing repository root: {repo_root}")
        runner["verified_pins"] = verify_inputs(repo_root, oracle_root)
        java_root = repo_root / "app" / "src" / "main" / "java" / "com" / "simple" / "videoeditor" / "oracle"
        java_files = [java_root / name for name in (
            "OracleContract.java", "OracleCoreVerifier.java", "OracleGeneratedContract.java", "OraclePcmUtils.java",
            "IntroOracleContract.java",
            "TextOracleContract.java", "TextOracleVerifier.java",
            "TitleOracleContract.java", "TitleOracleVerifier.java",
            "WatermarkOracleContract.java", "WatermarkOracleVerifier.java",
        )] + [repo_root / "oracle_tools" / "LocalParityMain.java"]
        for path in java_files:
            require(path.is_file(), f"Missing Java source: {path}")
        classes = build_root / "classes"
        safe_build_root(repo_root, oracle_root)
        classes.mkdir(parents=True, exist_ok=True)
        runner["stage"] = "javac"
        compilation = run_process([
            "javac", "-J-XX:-UsePerfData", f"-J-XX:ErrorFile={build_root / 'javac_error_%p.log'}",
            "-encoding", "UTF-8", "-source", "8", "-target", "8", "-proc:none", "-implicit:none",
            "-d", str(classes), *[str(path) for path in java_files],
        ], repo_root, 120, runner, "javac")
        require(compilation["returncode"] == 0, f"javac failed (exit {compilation['returncode']}); see runner.processes.javac")
        runner["stage"] = "java"
        execution = run_process([
            "java", "-XX:-UsePerfData", f"-XX:ErrorFile={build_root / 'java_error_%p.log'}",
            "-Dfile.encoding=UTF-8", "-Djava.awt.headless=true", f"-Djava.io.tmpdir={build_root}",
            "-cp", str(classes), "com.simple.videoeditor.oracle.LocalParityMain", str(oracle_root),
        ], repo_root, 1800, runner, "java")
        # Parse even on nonzero exit, retaining partial Java results and all diagnostics.
        if execution["returncode"] != 0:
            runner["errors"].append({
                "stage": "java", "type": "ProcessError",
                "message": f"Java failed (exit {execution['returncode']}); see runner.processes.java",
            })
        runner["stage"] = "report_validation"
        java_report = load_json(execution["stdout"])
        report["java_report"] = java_report
        if isinstance(java_report, dict):
            report.update(java_report)
            report.update(schema_version=1, java_report=java_report, runner=runner, passed=False)
        validate_report(java_report)
        require(execution["returncode"] == 0, "Java exited unsuccessfully")
        report["passed"] = True
        runner["stage"] = "complete"
    except (Exception, KeyboardInterrupt) as exc:
        report["passed"] = False
        runner["errors"].append({"stage": runner["stage"], "type": type(exc).__name__, "message": str(exc)})
    finally:
        runner["finished_at"] = datetime.now(timezone.utc).isoformat()
        runner["duration_seconds"] = round(time.monotonic() - started, 3)
        # Prefer the requested repo; unsafe output roots must never receive even an error report.
        for destination in dict.fromkeys((repo_root, DEFAULT_REPO_ROOT)):
            try:
                build_root = safe_build_root(destination, oracle_root)
                build_root.mkdir(parents=True, exist_ok=True)
                report_path = build_root / REPORT_NAME
                runner["report_path"] = str(report_path)
                serialized = json.dumps(report, ensure_ascii=True, allow_nan=False, indent=2) + "\n"
                with report_path.open("w", encoding="utf-8", newline="\n") as stream:
                    stream.write(serialized)
                    stream.flush()
                    os.fsync(stream.fileno())
                break
            except (OSError, ValueError, RuntimeError) as exc:
                report["passed"] = False
                runner["errors"].append({"stage": "report_write", "type": type(exc).__name__, "message": str(exc)})
        else:
            print(json.dumps(report, ensure_ascii=True, allow_nan=False), file=sys.stderr)
        for error in runner["errors"]:
            print(f"{error['stage']}: {error['message']}", file=sys.stderr)
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
