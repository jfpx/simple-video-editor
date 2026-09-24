"""Offline producer/consumer regression; all artifacts stay in app\\build\\schema-regression.

Compiles complete, current OracleVerifier and SelfTestRunner sources, not an
extracted/reimplemented formatter. Only Context, AssetManager and decoding are
substituted. Uses the existing 36-control parity report as independent evidence.
No Gradle build, network, new dependencies, app construction or Android execution.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[1]
BUILD = Path(r"app\build\schema-regression")
JAVA = Path(r"app\src\main\java\com\simple\videoeditor")
TOOLS = Path(r"oracle_tools\schema_regression")


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def safe_output():
    require(BUILD.resolve() == ROOT / BUILD, "Redirected build output is not allowed")
    if BUILD.exists():
        for path in [BUILD, *BUILD.rglob("*")]:
            require(not path.is_symlink() and not (hasattr(path, "is_junction") and path.is_junction()),
                    f"Linked output is not allowed: {path}")
            require(not path.is_file() or path.stat().st_nlink == 1, f"Hard-linked output: {path}")
    BUILD.mkdir(parents=True, exist_ok=True)


def run(command, log, expected_failure=False):
    completed = subprocess.run([str(part) for part in command], text=True, encoding="utf-8",
                               errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                               timeout=900)
    (BUILD / log).write_text(completed.stdout, encoding="utf-8")
    if expected_failure:
        require(completed.returncode != 0 and "org.json.JSONException" in completed.stdout
                and '["decode"]' in completed.stdout,
                f"Old typo did not fail for the expected reason; see {BUILD / log}")
    else:
        require(completed.returncode == 0, f"Command failed; see {BUILD / log}\n{completed.stdout[-6000:]}")
    return completed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--oracle-root", required=True, type=Path)
    parser.add_argument("--check-old-typo", action="store_true",
                        help="Compile a build-only typo mutation and require the same regression to fail")
    parser.add_argument("--producer-only", action="store_true",
                        help="Check real verify before parent extraction; explicitly NOT a formatter regression pass")
    args = parser.parse_args()
    require(not (args.producer_only and args.check_old_typo), "Mutation requires the full formatter regression")
    oracle_root = args.oracle_root.resolve()
    os.chdir(ROOT)
    require(oracle_root.is_dir(), f"Missing frozen oracle: {oracle_root}")
    require(not BUILD.resolve().is_relative_to(oracle_root)
            and not oracle_root.is_relative_to(BUILD.resolve()), "Frozen/output overlap")
    safe_output()
    cache = Path.home() / r".gradle\caches\modules-2\files-2.1"
    jars = sorted((cache / "org.json").rglob("*.jar"))
    aars = sorted((cache / r"androidx.media3\media3-common").rglob("*.aar"))
    require(jars and aars, "Cached org.json jar and media3-common aar required; no downloads")
    android = Path(r".local-sdk\platforms\android-35\android.jar")
    app_classes = Path(r"app\build\intermediates\javac\debug\compileDebugJavaWithJavac\classes")
    require(android.is_file() and (app_classes / r"com\simple\videoeditor\SelfTestRunner.class").is_file(),
            "Existing Android SDK 35 and debug compiled classes required")
    for tool in ("javac", "java", "ffmpeg", "ffprobe"):
        require(shutil.which(tool), f"Missing local tool: {tool}")
    sources = [JAVA / "OracleVerifier.java", JAVA / "SelfTestRunner.java"]
    sources += [JAVA / "oracle" / f"{name}.java" for name in
                 ("OracleContract", "OracleCoreVerifier", "OracleGeneratedContract", "OraclePcmUtils",
                    "MusicOracleContract", "IntroOracleContract", "TextOracleContract", "TextOracleVerifier",
                    "TitleOracleContract", "TitleOracleVerifier",
                    "WatermarkOracleContract", "WatermarkOracleVerifier")]
    source_hashes = {str(path): hashlib.sha256(path.read_bytes()).hexdigest()
                     for path in sources + [Path(r"oracle_tools\LocalParityMain.java"), *TOOLS.glob("*.java")]}
    formatter = sources[1].read_text(encoding="utf-8")
    if not args.producer_only:
        require("static String formatCaseResult(JSONObject result)" in formatter,
                "Parent's package-private SelfTestRunner.formatCaseResult is not present yet")
        require("log(run, formatCaseResult(result))" in formatter, "Production must call the tested formatter")
        require("getJSONObject(OracleVerifier.DECODER_KEY)" in formatter, "Formatter must use producer key")
        require('public static final String DECODER_KEY = "decoder"' in sources[0].read_text(encoding="utf-8"),
                "Parent's public OracleVerifier.DECODER_KEY is not present yet")
    with zipfile.ZipFile(aars[-1]) as archive:
        (BUILD / "media3-common.jar").write_bytes(archive.read("classes.jar"))
    classes = BUILD / "classes"
    mutant_classes = BUILD / "mutant-classes"
    for directory in (classes, mutant_classes):
        if directory.exists():
            shutil.rmtree(directory)
        directory.mkdir()
    cp = os.pathsep.join(map(str, [jars[-1], android, BUILD / "media3-common.jar", app_classes]))
    # Compile against the real SDK first; only the second compilation shadows Context/AssetManager.
    base = ["javac", "-encoding", "UTF-8", "-proc:none", "-implicit:none",
            "-sourcepath", str(BUILD / "empty-sourcepath")]
    (BUILD / "empty-sourcepath").mkdir(exist_ok=True)
    run(base + ["-cp", cp, "-d", classes, *sources, Path(r"oracle_tools\LocalParityMain.java"),
                TOOLS / "OracleAndroidDecoder.java"], "compile-production.txt")
    run(base + ["-cp", str(classes) + os.pathsep + cp, "-d", classes,
                TOOLS / "Context.java", TOOLS / "AssetManager.java", TOOLS / "SchemaRegressionMain.java"],
        "compile-harness.txt")
    runtime_cp = str(classes) + os.pathsep + cp
    command = ["java", "-Xmx768m", "-Djava.awt.headless=true",
               "-Djava.io.tmpdir=" + str(BUILD.resolve()), "-cp", runtime_cp,
               "com.simple.videoeditor.SchemaRegressionMain", oracle_root]
    if args.producer_only:
        command.append("--producer-only")
    completed = run(command, "regression.txt")
    print(completed.stdout.strip().splitlines()[-1])
    provenance = {"sources_sha256": source_hashes, "json_jar": str(jars[-1]), "android_jar": str(android),
                  "media3_aar": str(aars[-1]), "app_classes": str(app_classes),
                  "oracle_root": str(oracle_root), "old_typo_rejected": False,
                  "formatter_verified": not args.producer_only}
    if args.check_old_typo:
        original = "getJSONObject(OracleVerifier.DECODER_KEY)"
        require(formatter.count(original) == 1, "Mutation must target exactly one decoder read")
        mutant = BUILD / "mutant-source" / "SelfTestRunner.java"
        mutant.parent.mkdir(exist_ok=True)
        mutant.write_text(formatter.replace(original, 'getJSONObject("decode")'), encoding="utf-8")
        run(base + ["-cp", cp + os.pathsep + str(classes), "-d", mutant_classes, mutant],
            "compile-mutation.txt")
        run(["java", "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + str(BUILD.resolve()),
             "-cp", str(mutant_classes) + os.pathsep + runtime_cp,
             "com.simple.videoeditor.SchemaRegressionMain", "--replay"],
            "old-typo.txt", expected_failure=True)
        provenance["old_typo_rejected"] = True
        print("PASS old-typo mutation rejected: JSONException on decode (same harness, recorded real results)")
    for path, digest in source_hashes.items():
        require(hashlib.sha256(Path(path).read_bytes()).hexdigest() == digest, f"Source changed during run: {path}")
    prefix = "producer-" if args.producer_only else ""
    (BUILD / f"{prefix}provenance.json").write_text(json.dumps(provenance, indent=2), encoding="utf-8")
    print(f"Evidence: {BUILD}\\{prefix}results.json")


if __name__ == "__main__":
    try:
        main()
    except (OSError, RuntimeError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        print(f"FAIL schema regression: {error}", file=sys.stderr)
        sys.exit(1)
