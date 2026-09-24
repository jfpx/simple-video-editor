"""Reproducible build and exact-inventory native checks on an already-owned emulator.

Never creates an AVD, downloads an SDK, uninstalls an app, kills adb, or promotes APKs.
Use --stop-emulator only when this serial is exclusively owned by this invocation.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import time
import types
import uuid
import xml.etree.ElementTree as ET

sys.dont_write_bytecode = True
import run_emulator_validation as oracle
import run_watermark_ui_validation as watermark_ui
from verify_color_adjust_exports import verify_color_exports

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.simple.videoeditor"
JAVA = ROOT / r"app\src\androidTest\java\com\simple\videoeditor"
FEATURES = ["SelectedFramePreviewTest", "WholeEditPresetsTest", "TitleStylesTest", "TitleAppearanceTest"]
UI_CLASSES = ["SelectedFramePreviewTest", "FixedEditorUiTest", "SuiteSummaryUiTest"]
BORDER_CLASSES = ["VideoBorderTest", "BorderExportTest"]
TITLE_BACKGROUND_CLASSES = ["TitleBackgroundTest", "UnlimitedProcessingTest", "MergeImportTest",
                            "PngWatermarkImportTest"]
COLOR_CLASSES = ["ColorAdjustmentTest", "ColorAdjustmentExportTest",
                 "SelectedFramePreviewTest#testColorSlidersResetPresetNewSourcePreviewMoviesOpenShare",
                 "FixedEditorUiTest#testColorLocaleRecreationDisabledValuesAndBusyGroups"]
MUSIC_CLASSES = ["OfflineMusicTest", "MusicCompositionTest", "TimelineAudioCompositionTest",
                 "TimelineAudioAacTest", "WholeEditPresetsTest"]
REAL_MUSIC_CLASSES = ["RealMusicLibraryTest#" + method for method in (
    "testAuditionLifecycleAndCancelExport", "testOriginalCatalogPinsAndNegativeBounds",
    "testNativeFullOriginalLoopsAndIndependentGain",
    "testRealSoundUiAuditionExportPresetLanguageRestartShare")]
SUPPLEMENTAL = [
    "EditorSuiteModesTest",
    "ShortDiagnosticTest", "SoftwareAvcExportTest", "MergeCompositionTest", "MergeExportTest",
    "MergeImportTest", "CompactSuiteIndexTest", "SuiteSummaryUiTest", "ReportMemoryTest",
    "ReportJournalTest", "ReportResponsivenessTest",
    "SavedReportTest", "PublishedVideoTest", "PublishedVideoSafTest",
    "SuiteOutcomeTest", "PhoneCodecReportingTest",
]
CONFIG_TEST = PACKAGE + ".EditorWorkflowSettingsTest#testConfigure"
WORKFLOW_PROGRESS = ROOT / r"app\build\workflow-review-progress.txt"
UI_PROGRESS = ROOT / r"app\build\fixed-preview-ui-progress.txt"
ASSURANCE_PROGRESS = ROOT / r"app\build\ui-assurance-progress.txt"
CANDIDATE_INPUTS = ["app", "build.gradle", "settings.gradle", "gradle.properties", "gradle", "gradlew", "gradlew.bat"]


def is_build_input(name):
    return (name in CANDIDATE_INPUTS or name.startswith(("app/", "gradle/"))
            or name.endswith((".java", ".xml", ".gradle", ".json", ".py")))


def _ui_progress(message):
    UI_PROGRESS.parent.mkdir(parents=True, exist_ok=True)
    with UI_PROGRESS.open("a", encoding="utf-8") as stream:
        stream.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} UI workflow: {message}\n")


class _UiCommandError(RuntimeError):
    def __init__(self, command, result):
        self.returncode = result.returncode
        self.stdout = result.stdout
        self.stderr = result.stderr
        self.category = ("UIAUTOMATOR_INFRASTRUCTURE"
                         if "uiautomator" in command else "HOST_COMMAND")
        super().__init__(
            f"UI command failed (exit {result.returncode}): {command!r}; "
            f"stdout={result.stdout.decode('utf-8', 'replace')!r}; "
            f"stderr={result.stderr.decode('utf-8', 'replace')!r}")


def _ui_check_output(command, timeout=30):
    result = oracle.run_cmd([str(word) for word in command], timeout=timeout, cwd=ROOT)
    if result.returncode != 0 or ("uiautomator" in command and b"ERROR:" in result.stderr):
        raise _UiCommandError(command, result)
    return result.stdout


def screenshot_rgb(path, crop=None):
    # Rebind only this function's globals, never the shared subprocess module.
    original = watermark_ui.screenshot_rgb
    bounded = types.FunctionType(original.__code__, dict(original.__globals__,
                                subprocess=types.SimpleNamespace(check_output=_ui_check_output)))
    return bounded(path, crop)


def verify_preview(evidence, baseline, marked):
    original = watermark_ui.verify_preview
    bounded = types.FunctionType(original.__code__, dict(original.__globals__, screenshot_rgb=screenshot_rgb))
    return bounded(evidence, baseline, marked)


def compile_ui_oracle(evidence, *, env=None):
    classes = evidence / "oracle-classes"
    classes.mkdir(parents=True, exist_ok=False)
    java_root = ROOT / r"app\src\main\java\com\simple\videoeditor\oracle"
    sources = [java_root / name for name in (
        "OracleContract.java", "OracleCoreVerifier.java", "OracleGeneratedContract.java", "OraclePcmUtils.java",
        "IntroOracleContract.java", "TextOracleContract.java", "TextOracleVerifier.java",
        "TitleOracleContract.java", "TitleOracleVerifier.java",
        "WatermarkOracleContract.java", "WatermarkOracleVerifier.java",
    )] + [ROOT / "oracle_tools" / name for name in ("LocalParityMain.java", "WatermarkParityMain.java")]
    javac = str(Path(env["JAVA_HOME"]) / r"bin\javac.exe") if env and env.get("JAVA_HOME") else "javac"
    result = oracle.run_cmd(
        [javac, "-J-XX:-UsePerfData", "-J-Djava.io.tmpdir=" + str(evidence),
         "-encoding", "UTF-8", "-source", "8", "-target", "8", "-proc:none", "-implicit:none",
         "-d", str(classes), *[str(path) for path in sources]],
        cwd=ROOT, env=env, timeout=120, stdout_path=evidence / "oracle-compile.txt",
        stderr_path=evidence / "oracle-compile.stderr.txt")
    oracle.require(result.returncode == 0, "UI oracle compilation failed; see oracle-compile.stderr.txt")
    return classes


def verify_output(output, evidence, *, env=None):
    """Run the existing frozen full/sparse watermark protocol without changing its tolerances."""
    classes = evidence / "oracle-classes"
    oracle.require((classes / r"com\simple\videoeditor\oracle\WatermarkParityMain.class").is_file(),
                   "Current-run UI oracle classes are missing")
    java = str(Path(env["JAVA_HOME"]) / r"bin\java.exe") if env and env.get("JAVA_HOME") else "java"
    result = oracle.run_cmd(
        [java, "-Xmx768m", "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + str(evidence),
         "-cp", str(classes), "com.simple.videoeditor.oracle.WatermarkParityMain",
         str(output), str(evidence / "user-export")], cwd=ROOT, env=env, timeout=180,
        stdout_path=evidence / "independent-verification.txt",
        stderr_path=evidence / "independent-verification.stderr.txt")
    oracle.require(result.returncode == 0, "Frozen output oracle failed; see independent-verification.txt")
    reports = [json.loads((evidence / f"user-export-{mode}.json").read_text(encoding="utf-8"))
               for mode in ("full", "sparse")]
    oracle.require(all(report.get("status") == "PASS" for report in reports) and reports[0] == reports[1],
                   "Frozen full/sparse output reports failed or disagree")
    return reports[0]


def localized_strings(name):
    return {node.text for folder in ("values", "values-en")
            for path in (ROOT / "app" / "src" / "main" / "res" / folder).glob("*.xml")
            for node in ET.parse(path).getroot().findall("string")
            if node.get("name") == name}


class _EditorUiWorkflow:
    PANELS = ("panelPicture", "panelSound", "panelTitle", "panelAssets", "panelMore")
    DOCUMENT_PACKAGES = ("com.android.documentsui", "com.google.android.documentsui")
    RESOLVER_PACKAGES = ("android", "com.android.intentresolver", "com.google.android.intentresolver")

    def __init__(self, adb, serial, evidence, remote_xml):
        self.adb, self.serial, self.evidence, self.remote_xml = adb, serial, evidence, remote_xml
        self.frame = 0
        self._dump_restart_used = False
        self._picker_capture = False
        self.failure_diagnostics = False

    def capture_failure(self, label, deadline):
        """Capture the visible window before the sole retry; never replace the original error."""
        diagnostics = {}
        commands = (
            ("screen.png", ("exec-out", "screencap", "-p")),
            ("windows.txt", ("shell", "dumpsys", "window", "windows")),
            ("memory.txt", ("shell", "cat", "/proc/meminfo")),
            ("storage.txt", ("shell", "df", "-h", "/data", "/sdcard")),
            ("logcat.txt", ("logcat", "-d", "-b", "all")),
        )
        for suffix, words in commands:
            seconds = deadline - time.monotonic()
            if seconds <= .5:
                diagnostics[suffix] = "SKIPPED: original capture deadline exhausted"
                continue
            try:
                path = self.evidence / (label + "-" + suffix)
                path.write_bytes(self.command(*words, timeout=min(3, seconds)))
                diagnostics[suffix] = str(path)
            except Exception as error:
                diagnostics[suffix] = repr(error)
        return diagnostics

    def command(self, *words, timeout=30):
        return _ui_check_output([self.adb, "-s", self.serial, *words], timeout=timeout)

    def shell(self, *words, timeout=30):
        return self.command("shell", *words, timeout=timeout).decode("utf-8", "replace").strip()

    def screen(self, label=None, *, deadline=None):
        self.frame += 1
        label = label or f"ui-{self.frame:04d}"
        deadline = min(time.monotonic() + 30, deadline) if deadline is not None else time.monotonic() + 30

        def remaining():
            oracle.check_cancel()
            seconds = deadline - time.monotonic()
            oracle.require(seconds > 0, "Hierarchy capture deadline exceeded: " + label)
            return seconds

        for attempt in (1, 2):
            self.shell("rm", "-f", self.remote_xml, timeout=remaining())
            started = time.monotonic()
            try:
                # API34 DocumentsUI exposes null unimportant children to the legacy
                # dumper's NAF walk. Keep full editor containers; filter only the picker.
                flags = ("--compressed",) if self._picker_capture else ()
                output = self.shell("uiautomator", "dump", *flags, self.remote_xml, timeout=remaining())
            except _UiCommandError as error:
                elapsed = time.monotonic() - started
                # The recorded picker dump exited 137 silently after 1.6s, not at its idle deadline.
                # Permit one new process per workflow, never reconnect a live/timed-out bridge.
                null_root = (error.returncode == 0 and not error.stdout
                             and error.stderr.strip() == b"ERROR: null root node returned by UiTestAutomationBridge.")
                early_kill = error.returncode == 137 and not error.stdout and not error.stderr and elapsed < 5
                restart = (not self._dump_restart_used and attempt == 1 and (early_kill or null_root)
                           and deadline - time.monotonic() > .3)
                diagnostics = (self.capture_failure(f"{label}-dump-{attempt}", deadline)
                               if self.failure_diagnostics else {})
                restart = restart and deadline - time.monotonic() > .3
                oracle.write_json(self.evidence / f"{label}-dump-{attempt}-failure.json",
                                  dict(returncode=error.returncode, stdout=error.stdout.decode("utf-8", "replace"),
                                        stderr=error.stderr.decode("utf-8", "replace"),
                                        elapsed=elapsed, restart=restart, null_root=null_root,
                                        category=error.category, diagnostics=diagnostics))
                if not restart:
                    raise
                self._dump_restart_used = True
                _ui_progress(f"{label}: {'null root during window transition' if null_root else 'silent early dump exit 137'}; "
                             "one bounded fresh capture; original evidence retained")
                oracle.check_cancel()
                time.sleep(.3)
                continue
            oracle.require("ERROR:" not in output, "Hierarchy dump failed: " + output)
            break
        data = self.command("exec-out", "cat", self.remote_xml, timeout=remaining())
        (self.evidence / (label + ".xml")).write_bytes(data)
        ui = ET.fromstring(data)
        oracle.require(ui.tag == "hierarchy", "Invalid hierarchy root: " + ui.tag)
        (self.evidence / (label + ".png")).write_bytes(
            self.command("exec-out", "screencap", "-p", timeout=remaining()))
        remaining()
        return ui

    @staticmethod
    def find(ui, resource_id):
        if ui is None:
            return None
        if ":id/" not in resource_id:
            resource_id = PACKAGE + ":id/" + resource_id
        return next((node for node in ui.iter("node") if node.get("resource-id") == resource_id), None)

    @staticmethod
    def bounds(node):
        oracle.require(node is not None, "Missing UI node")
        rect = list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))
        oracle.require(len(rect) == 4 and rect[2] > rect[0] and rect[3] > rect[1],
                       f"Invalid visible bounds: {node.attrib}")
        return rect

    def tap(self, node):
        oracle.require(node is not None and node.get("enabled") == "true", "Missing/disabled UI control")
        left, top, right, bottom = self.bounds(node)
        self.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(.3)

    def wait(self, predicate, label, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            oracle.check_cancel()
            ui = self.screen(deadline=deadline)
            if predicate(ui):
                # Label the very capture that satisfied the predicate, without starting another bridge.
                for suffix in (".xml", ".png"):
                    source = self.evidence / (f"ui-{self.frame:04d}" + suffix)
                    (self.evidence / (label + suffix)).write_bytes(source.read_bytes())
                return ui
            time.sleep(min(.3, max(0, deadline - time.monotonic())))
        raise RuntimeError("Timed out waiting for " + label)

    def ready(self, label):
        def loaded(ui):
            node = self.find(ui, "btnProcess")
            error = self.find(ui, "tvErrorDetails")
            oracle.require(error is None or not error.get("text"), "Editor error: " + str(
                error.attrib if error is not None else ""))
            return node is not None and node.get("enabled") == "true"
        return self.wait(loaded, label, 75)

    def fixed(self, resource_id, owner):
        ui = self.screen()
        container = self.find(ui, owner)
        node = self.find(container, resource_id)
        oracle.require(node is not None, f"{resource_id} must belong to fixed {owner}")
        self.bounds(node)
        return node

    def select_panel(self, panel):
        index = self.PANELS.index(panel)
        ui = self.screen()
        tabs = self.find(ui, "editorTabs")
        oracle.require(tabs is not None, "Missing fixed editorTabs")
        candidates = [node for node in tabs.iter("node")
                      if node.get("class") == "android.app.ActionBar$Tab"
                      or (node.get("class") == "android.widget.LinearLayout"
                          and (node.get("clickable") == "true"
                               or node.get("focusable") == node.get("selected") == "true"))]
        oracle.require(len(candidates) == len(self.PANELS)
                       and {node.get("index") for node in candidates} == {str(i) for i in range(len(self.PANELS))},
                       "Expected exactly five uniquely indexed structural peer tabs")
        target = next((node for node in candidates if node.get("index") == str(index)), None)
        oracle.require(target is not None, f"Missing structural tab {index} under editorTabs")
        if target.get("selected") == "true":
            oracle.require(self.find(ui, panel) is not None, "Selected peer panel is missing: " + panel)
            return
        self.tap(target)
        self.wait(lambda tree: self.find(tree, panel) is not None, "panel-" + panel)

    def panel_control(self, panel, resource_id):
        self.select_panel(panel)
        # Only the selected ScrollView's interior can receive a swipe.
        for direction in (1, -1):
            previous = None
            for _ in range(12):
                ui = self.screen()
                container = self.find(ui, panel)
                oracle.require(container is not None, "Selected panel disappeared: " + panel)
                left, top, right, bottom = self.bounds(container)
                node = self.find(container, resource_id)
                if node is not None:
                    x1, y1, x2, y2 = self.bounds(node)
                    if left <= x1 < x2 <= right and top + 4 <= y1 < y2 <= bottom - 4:
                        return node
                signature = ET.tostring(container)
                if signature == previous:
                    break
                previous = signature
                oracle.require(bottom - top >= 40, "Selected panel too small for a safe swipe")
                x = (left + right) // 2
                high, low = top + (bottom - top) // 5, bottom - (bottom - top) // 5
                start, end = (low, high) if direction == 1 else (high, low)
                self.shell("input", "swipe", str(x), str(start), str(x), str(end), "300")
        raise RuntimeError(f"Missing control in selected {panel}: {resource_id}")

    def input_value(self, panel, resource_id, value):
        self.tap(self.panel_control(panel, resource_id))
        self.shell("input", "keyevent", "KEYCODE_MOVE_END")
        # The numeric fields have a maximum length of twelve characters.
        self.shell("input", "keyevent", *(["KEYCODE_DEL"] * 16))
        self.shell("input", "text", value)
        self.shell("input", "keyevent", "KEYCODE_BACK")
        node = self.panel_control(panel, resource_id)
        oracle.require(node.get("text") == value, f"Input did not persist: {resource_id}")

    def music_status(self, resource):
        node = self.panel_control("panelSound", "tvSelectedMusic")
        oracle.require(any(node.get("text", "").startswith(label) for label in localized_strings(resource)),
                       "Imported music status disagrees with localized resource: " + resource)

    def pick(self, filename, label):
        self._picker_capture = True
        try:
            self._pick(filename, label)
        finally:
            self._picker_capture = False

    def _pick(self, filename, label):
        ui = self.wait(lambda tree: any(node.get("package") in self.DOCUMENT_PACKAGES
                                       for node in tree.iter("node")), label + "-picker")
        package = next(node.get("package") for node in ui.iter("node")
                       if node.get("package") in self.DOCUMENT_PACKAGES)
        def system(tree, *names):
            return next((node for name in names for node in tree.iter("node")
                         if node.get("resource-id") in (package + ":id/" + name, "android:id/" + name)), None)
        def document(tree):
            directory = system(tree, "dir_list")
            if directory is None:
                return None
            matches = [node for node in directory.iter("node")
                       if node.get("resource-id") in ("android:id/title", package + ":id/title")
                       and node.get("text") == filename and node.get("enabled") == "true"]
            if not matches:
                matches = [node for node in directory.iter("node") if node.get("enabled") == "true"
                           and re.match(re.escape(filename) + r"(?:$|[,，、،;；\n])",
                                        node.get("content-desc", ""))]
            oracle.require(len(matches) <= 1, "Ambiguous fixture filename in real picker")
            return matches[0] if matches else None
        if document(ui) is None:
            # Reading provider roots requires MANAGE_DOCUMENTS; resolve its public localized resource instead.
            user = self.shell("am", "get-current-user")
            oracle.require(re.fullmatch(r"\d+", user) is not None, "Cannot resolve the active Android user")
            title = self.shell("cmd", "overlay", "lookup", "--user", user,
                               "com.android.providers.downloads",
                               "com.android.providers.downloads:string/root_downloads")
            (self.evidence / (label + "-downloads-label.txt")).write_text(title, encoding="utf-8")
            oracle.require(bool(title) and "\n" not in title and not title.startswith("Error"),
                           "Cannot resolve the Downloads provider's localized root label")
            toolbar = system(ui, "toolbar")
            # Compression can omit the non-accessible toolbar container, not its button.
            menus = [node for node in (toolbar if toolbar is not None else ui).iter("node")
                     if node.get("class") == "android.widget.ImageButton"
                     and node.get("clickable") == "true" and node.get("package") == package]
            oracle.require(len(menus) == 1, "Cannot uniquely identify the picker roots button")
            self.tap(menus[0])
            ui = self.wait(lambda tree: system(tree, "roots_list") is not None, label + "-roots")
            root_list = system(ui, "roots_list")
            matches = [node for node in root_list.iter("node") if node.get("resource-id") == "android:id/title"
                       and node.get("text") == title]
            oracle.require(len(matches) == 1, "Cannot uniquely identify the Downloads provider root")
            self.tap(matches[0])
            ui = self.wait(lambda tree: system(tree, "roots_list") is None, label + "-downloads")
            if document(ui) is None:
                self.tap(system(ui, "option_menu_search", "menu_search"))
                ui = self.wait(lambda tree: system(tree, "search_src_text") is not None, label + "-search")
                self.tap(system(ui, "search_src_text"))
                self.shell("input", "text", filename)
                self.shell("input", "keyevent", "KEYCODE_ENTER")
                ui = self.wait(lambda tree: document(tree) is not None, label + "-file", 45)
        self.tap(document(ui))
        # Some DocumentsUI versions require explicit confirmation even for a single selection.
        ui = self.screen()
        if any(node.get("package") in self.DOCUMENT_PACKAGES for node in ui.iter("node")):
            confirm = system(ui, "action_menu_select", "button1")
            if confirm is not None:
                self.tap(confirm)
        self.ready(label + "-loaded")

    def output_location(self):
        self.tap(self.fixed("btnOutputLocation", "editorActionDock"))
        ui = self.wait(lambda tree: self.find(tree, "android:id/message") is not None, "output-location")
        lines = self.find(ui, "android:id/message").get("text", "").splitlines()
        oracle.require(len(lines) >= 4, "Missing actual published output details")
        location = lines[-1]
        oracle.require(location.startswith("content://"), "UI export was not published to a real content provider")
        self.tap(self.find(ui, "android:id/button1"))
        return location

    def open_or_share(self, share, uri):
        label = "share" if share else "open"
        targets = self.shell("cmd", "package", "query-activities", "--brief",
                             "-a", "android.intent.action.SEND" if share else "android.intent.action.VIEW",
                             "-t", "video/mp4", "-d", uri)
        packages = set(re.findall(r"(?m)^\s*([\w.]+)/[\w.$]+", targets)) - {PACKAGE}
        self.tap(self.fixed("btnShareVideo" if share else "btnOpenOutputFolder", "editorActionDock"))
        def external(tree):
            for node in tree.iter("node"):
                package = node.get("package")
                if package in self.RESOLVER_PACKAGES and node.get("resource-id", "").split(":id/")[-1] in (
                        "resolver_list", "chooser_header", "profile_pager", "content_preview_file_layout"):
                    return True
                if not share and package in packages:
                    return True
            return False
        ui = self.wait(external, label + "-output", 45)
        activities = self.shell("dumpsys", "activity", "activities")
        (self.evidence / (label + "-activities.txt")).write_text(activities, encoding="utf-8")
        oracle.require(uri in activities, label + " activity has no actual output URI")
        result = {"uri": uri, "packages": sorted({node.get("package") for node in ui.iter("node")
                                                if node.get("package")}), "targets": targets}
        self.shell("input", "keyevent", "KEYCODE_BACK")
        self.wait(lambda tree: self.find(tree, "editorActionDock") is not None, label + "-returned")
        return result


def run_ui_workflow(adb, serial, evidence, *, env=None):
    """Drive only the supplied device; return persisted real picker/pixel/export/chooser evidence."""
    evidence = oracle.checked_evidence_path(Path(evidence))
    oracle.disk_preflight(evidence, 2 * 1024 ** 3)
    evidence.mkdir(parents=True, exist_ok=False)
    compile_ui_oracle(evidence, env=env)
    token = "editor-ui-" + uuid.uuid4().hex
    source_name, png_name, music_name = token + "-source.mp4", token + "-watermark.png", token + "-music.wav"
    remote_xml = "/sdcard/" + token + ".xml"
    fixtures = ["/sdcard/Download/" + name for name in (source_name, png_name, music_name)]
    ui = _EditorUiWorkflow(adb, serial, evidence, remote_xml)
    ui.failure_diagnostics = True
    previous_evidence = oracle.ACTIVE_EVIDENCE
    oracle.ACTIVE_EVIDENCE = evidence
    result = None
    try:
        _ui_progress("pushing uniquely owned SAF fixtures; " + str(evidence))
        for local, remote in zip((ROOT / r"app\src\main\assets\video-oracle\standard.mp4",
                                  ROOT / r"app\src\main\assets\watermark-oracle\watermark.png",
                                  ROOT / r"app\src\main\assets\music-oracle\music.wav"), fixtures):
            ui.command("push", str(local), remote)
            ui.shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
                     "-d", "file://" + remote)
        ui.shell("am", "force-stop", PACKAGE)
        ui.shell("am", "start", "-W", "-f", "0x10008000", "-n", PACKAGE + "/.MainActivity")
        ui.wait(lambda tree: ui.find(tree, "btnSelectVideo") is not None, "01-main")
        ui.tap(ui.fixed("btnSelectVideo", "editorPreviewHeader"))
        ui.pick(source_name, "02-video")
        trim = ui.panel_control("panelPicture", "cbEnableTrim")
        if trim.get("checked") != "true":
            ui.tap(trim)
        ui.input_value("panelPicture", "etTrimStart", "0")
        ui.input_value("panelPicture", "etTrimEnd", "4")
        baseline = ui.screen("03-preview-off")
        oracle.require(ui.panel_control("panelAssets", "cbEnableWatermark").get("checked") == "false",
                       "Watermark must start disabled")
        ui.tap(ui.panel_control("panelAssets", "btnSelectWatermark"))
        ui.pick(png_name, "04-png")
        enable = ui.panel_control("panelAssets", "cbEnableWatermark")
        oracle.require(enable.get("checked") == "false", "Real PNG selection silently enabled watermark")
        ui.tap(enable)
        for resource_id, value in (("etWatermarkWidth", "30"), ("etWatermarkX", "0"), ("etWatermarkY", "0")):
            ui.input_value("panelAssets", resource_id, value)
        ui.screen("05-custom-inputs")
        ui.screen("06-custom-preview")
        for resource_id, value in (("etWatermarkWidth", "20"), ("etWatermarkX", "75"), ("etWatermarkY", "75")):
            ui.input_value("panelAssets", resource_id, value)
        ui.screen("07-oracle-inputs")
        marked = ui.screen("08-preview-on")
        verify_preview(evidence, baseline, marked)
        _ui_progress("real video/PNG pickers and frozen preview pixels passed; exporting four seconds")
        old = ui.find(marked, "tvOutputPath")
        old_name = old.get("text") if old is not None else None
        ui.tap(ui.fixed("btnProcess", "editorActionDock"))
        def exported(tree):
            error = ui.find(tree, "tvErrorDetails")
            oracle.require(error is None or not error.get("text"), "Production export error: " + str(
                error.attrib if error is not None else ""))
            node = ui.find(tree, "tvOutputPath")
            process = ui.find(tree, "btnProcess")
            return (node is not None and node.get("text", "").endswith(".mp4")
                    and node.get("text") != old_name and process is not None and process.get("enabled") == "true")
        completed = ui.wait(exported, "09-export-completed", 240)
        name = ui.find(completed, "tvOutputPath").get("text")
        uri = ui.output_location()
        journal_data = ui.command("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/published-video.xml")
        (evidence / "published-video.xml").write_bytes(journal_data)
        journal = {node.get("name"): node.text for node in ET.fromstring(journal_data).findall("string")}
        oracle.require(journal.get("uri") == uri and journal.get("name") == name,
                       "UI output and durable publication journal disagree")
        private = journal.get("savedPrivate") or journal.get("private", "")
        oracle.require(re.fullmatch(r"/data/(?:user/0|data)/" + re.escape(PACKAGE)
                                    + r"/files/exports/edited_[\w.-]+\.mp4", private),
                       "Publication journal does not identify an owned production export")
        public_bytes = ui.command("exec-out", "content", "read", "--uri", uri, timeout=60)
        private_bytes = ui.command("exec-out", "run-as", PACKAGE, "cat", private, timeout=60)
        oracle.require(bool(public_bytes) and public_bytes == private_bytes,
                       "Actual provider bytes do not match the production export")
        output = evidence / "user-export.mp4"
        output.write_bytes(public_bytes)
        report = verify_output(output, evidence, env=env)
        _ui_progress("actual provider/private bytes and frozen full/sparse oracle passed; checking open/share")
        opened = ui.open_or_share(False, uri)
        shared = ui.open_or_share(True, uri)
        ui.tap(ui.panel_control("panelAssets", "btnClearWatermark"))
        oracle.require(ui.panel_control("panelAssets", "cbEnableWatermark").get("checked") == "false",
                       "Clearing the watermark did not disable it")
        cleared = ui.screen("10-preview-cleared")
        rect = ui.bounds(ui.find(cleared, "ivVideoThumbnail"))
        oracle.require(rect == ui.bounds(ui.find(baseline, "ivVideoThumbnail")),
                       "Clearing changed the fixed preview geometry")
        oracle.require(screenshot_rgb(evidence / "03-preview-off.png", rect)
                        == screenshot_rgb(evidence / "10-preview-cleared.png", rect),
                        "Clearing did not restore the baseline preview pixels")
        ui.tap(ui.panel_control("panelSound", "btnSelectMusic"))
        ui.pick(music_name, "11-imported-music")
        ui.music_status("editor_music_ready")
        oracle.require(ui.panel_control("panelSound", "cbLibraryMusic").get("checked") == "false",
                       "Importing replacement music must disable the library")
        ui.screen("12-imported-music-ready")
        left, top, right, bottom = ui.bounds(ui.panel_control("panelSound", "btnSelectMusic"))
        x, y = str((left + right) // 2), str((top + bottom) // 2)
        ui.shell("input", "swipe", x, y, x, y, "1200")
        ui.music_status("editor_music_original")
        ui.screen("13-imported-music-cleared")
        result = {"status": "PASS", "serial": serial, "provider": "real DocumentsUI Downloads",
                   "source": source_name, "watermark": png_name, "output": uri, "private_output": private,
                   "output_name": name, "output_sha256": hashlib.sha256(public_bytes).hexdigest(),
                   "output_oracle": report, "open": opened, "share": shared,
                   "imported_music_picker": True, "imported_music_clear": True,
                   "directory_picker": "NOT_RUN: production UI requires API <29; API29+ uses Movies"}
    finally:
        original_error = sys.exc_info()[1]
        cancellation = oracle.CANCEL_FILE
        oracle.CANCEL_FILE = None
        try:
            try:
                ui.shell("rm", "-f", remote_xml, *fixtures)
            except (Exception, KeyboardInterrupt) as error:
                if original_error is None:
                    raise
                if isinstance(error, KeyboardInterrupt) and not isinstance(original_error, KeyboardInterrupt):
                    error.cleanup_errors = (getattr(error, "cleanup_errors", [])
                                            + getattr(original_error, "cleanup_errors", [])
                                            + ["UI workflow failed before cleanup cancellation: " + str(original_error)])
                    raise error from original_error
                original_error.cleanup_errors = getattr(original_error, "cleanup_errors", []) + [
                    "UI fixture cleanup: " + str(error)]
        finally:
            oracle.CANCEL_FILE = cancellation
            oracle.ACTIVE_EVIDENCE = previous_evidence
    result["fixtures_cleaned"] = True
    oracle.write_json(evidence / "summary.json", result)
    _ui_progress("PASS real picker/export/open/share/output pixel workflow; owned fixtures cleaned")
    return result


def configure_encoding(adb, serial, evidence, action="query", *, preferences=None, preference_token=None):
    token = uuid.uuid4().hex
    log = evidence / f"encoding-{action}-{token}.txt"
    preference_args = (["-e", "workflow_preferences", preferences,
                        "-e", "workflow_preferences_token", preference_token] if preferences else [])
    result = oracle.run_cmd([str(adb), "-s", serial, "shell", "am", "instrument", "-w", "-r",
                             "-e", "workflow_action", action, "-e", "workflow_token", token,
                             *preference_args, "-e", "class", CONFIG_TEST, oracle.RUNNER], timeout=45, stdout_path=log)
    text = result.stdout.decode("utf-8", "replace")
    records = re.findall(r"^INSTRUMENTATION_STATUS: workflow=(.+)\r?\nINSTRUMENTATION_STATUS_CODE: 2\s*$",
                         text, re.MULTILINE)
    clean = re.sub(r"^INSTRUMENTATION_STATUS: workflow=.+\r?\nINSTRUMENTATION_STATUS_CODE: 2\s*$",
                   "", text, flags=re.MULTILINE)
    oracle.require(result.returncode == 0 and len(records) == 1,
                   f"Native encoding configuration failed; see {log}")
    assert_inventory(oracle.parse_instrumentation(clean), {CONFIG_TEST})
    record = json.loads(records[0])
    oracle.require(record["token"] == token and record["action"] == action
                   and type(record["before"]) is bool and type(record["after"]) is bool
                   and record["policy"] == ("software-avc" if record["after"] else "default"),
                   "Invalid native encoding attestation")
    if action != "query":
        oracle.require(record["policy"] == action, "Native encoding setting not applied")
    if preferences:
        oracle.require(record.get("preferences_action") == preferences
                       and record.get("preferences_token") == preference_token
                       and set(record.get("preferences", {})) == {"ui_locales", "offline_music", "intro_templates"},
                       "Missing owned preference snapshot/restore attestation")
    return record


def assert_revision(record, revision):
    oracle.require(record.get("source_revision") == revision and record.get("test_source_revision") == revision,
                   f"Installed app/test revision mismatch: expected={revision}, actual={record}")


def terminalize(summary, status):
    if summary.get("status") == "RUNNING":
        summary["status"] = status
    for stage in summary.get("stages", {}).values():
        if stage.get("status") == "RUNNING":
            stage["status"] = status
            stage["finished_at"] = time.time()
    if "software" in summary:
        terminalize(summary["software"], status)


def inventory(classes):
    tests = set()
    for name in classes:
        name, separator, selected = name.partition("#")
        methods = re.findall(r"public\s+void\s+(test\w+)\s*\(", (JAVA / (name + ".java")).read_text(encoding="utf-8"))
        oracle.require(bool(methods), f"No test methods found: {name}")
        if separator:
            oracle.require(selected in methods, f"No test method found: {name}#{selected}")
            methods = [selected]
        tests.update(f"{PACKAGE}.{name}#{method}" for method in methods)
    return tests


def assert_inventory(parsed, expected):
    actual = {f"{test['class']}#{test['test']}" for test in parsed["tests"]}
    oracle.require(parsed["passed"] and actual == expected and parsed["ok_tests"] == len(expected),
                   f"Inventory/outcome mismatch: missing={sorted(expected-actual)}, unexpected={sorted(actual-expected)}; "
                   f"outcomes={parsed['test_counts']}")


def extract_real_music(archive_path, destination, revision, token):
    """Accept only this invocation's native evidence, never an older same-revision pass."""
    prefix = "files/real-music-evidence/" + token + "/"
    with tarfile.open(archive_path) as archive:
        members = [member for member in archive.getmembers() if member.isfile()]
        oracle.require(bool(members), "Missing real music evidence")
        records = []
        for member in members:
            relative = member.name.removeprefix(prefix)
            oracle.require(member.name.startswith(prefix) and "\\" not in relative and ":" not in relative
                           and not any(part in ("", ".", "..") for part in relative.split("/")),
                           "Unexpected real music archive path")
            oracle.require(member.size <= 64 * 1024 * 1024, "Real music evidence file exceeds bound")
            target = destination.joinpath(*relative.split("/"))
            oracle.require(target.resolve().is_relative_to(destination.resolve()),
                           "Real music evidence escaped destination")
            target.parent.mkdir(parents=True, exist_ok=True)
            with archive.extractfile(member) as source, target.open("xb") as output:
                shutil.copyfileobj(source, output)
            if relative.endswith("-result.json"):
                record = json.loads(target.read_text(encoding="utf-8"))
                oracle.require(record.get("revision") == revision and record.get("workflowToken") == token
                               and record.get("nativeStatus") == "PASS", "Stale or failed real music evidence")
                records.append((target.name, record.get("policy")))
        expected = {(name + "-result.json", policy) for name in ("native", "ui", "lifecycle")
                    for policy in ("default", "software-avc")}
        oracle.require(set(records) == expected and len(records) == len(expected),
                       "Missing or duplicate real music native/UI/lifecycle policy evidence")
    return records


def assert_candidate_equivalence(revision):
    oracle.require(re.fullmatch(r"[0-9a-f]{40}", revision), "Expected exact candidate revision")
    scope = CANDIDATE_INPUTS
    result = subprocess.run(["git", "diff", "--exit-code", revision, "HEAD", "--", *scope],
                            cwd=ROOT, capture_output=True, timeout=30)
    oracle.require(result.returncode == 0,
                   "Candidate production/test/build sources differ; rebuild and test the new revision")
    return scope


def main(argv=None):
    global WORKFLOW_PROGRESS, UI_PROGRESS, ASSURANCE_PROGRESS
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--sdk", type=Path, default=ROOT / ".local-sdk")
    parser.add_argument("--jdk", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--evidence-dir", type=Path, required=True)
    parser.add_argument("--artifact-root", type=Path,
                        help="Own a NEW absolute UI evidence root; requires --skip-build and --phase ui/ui-host")
    parser.add_argument("--candidate-revision",
                        help="Reuse unchanged app/test APK revision for harness-only recovery; source equivalence is mandatory")
    parser.add_argument("--phase", choices=["features", "full", "all", "titles", "ui", "ui-host", "border", "music", "color"], default="all")
    parser.add_argument("--skip-build", action="store_true")
    parser.add_argument("--skip-install", action="store_true")
    parser.add_argument("--allow-dirty", action="store_true", help="Exploration only; stamp unverified-local-source")
    parser.add_argument("--preserved-watermark-manifest-sha", help="Explicit pre-existing local asset exception; recorded in evidence")
    parser.add_argument("--stop-emulator", action="store_true")
    parser.add_argument("--protected-apk", type=Path)
    parser.add_argument("--protected-receipt", type=Path)
    parser.add_argument("--cancel-file", type=Path, help="Create this approved-root file to request bounded cancellation")
    args = parser.parse_args(argv)
    reserve = (2 if args.phase in ("ui", "ui-host") else 8) * 1024 ** 3
    if args.artifact_root:
        oracle.require(args.skip_build and args.phase in ("ui", "ui-host"),
                       "External artifact roots currently support prebuilt ui/ui-host only")
        oracle.checked_evidence_path(args.evidence_dir, args.artifact_root)
        oracle.require(args.evidence_dir.absolute() != args.artifact_root.absolute(),
                       "--evidence-dir must be a child of --artifact-root")
        oracle.ARTIFACT_ROOT, disk = oracle.create_artifact_root(args.artifact_root, reserve)
        WORKFLOW_PROGRESS = oracle.ARTIFACT_ROOT / "workflow-progress.txt"
        UI_PROGRESS = oracle.ARTIFACT_ROOT / "ui-progress.txt"
        ASSURANCE_PROGRESS = oracle.ARTIFACT_ROOT / "assurance-progress.txt"
        oracle.PROGRESS = oracle.ARTIFACT_ROOT / "commands-progress.txt"
    else:
        oracle.ARTIFACT_ROOT = None
        disk = oracle.disk_preflight(args.evidence_dir, reserve)
    evidence = oracle.checked_evidence_path(args.evidence_dir)
    evidence.mkdir(parents=True, exist_ok=False)
    previous_cancel = oracle.CANCEL_FILE
    oracle.CANCEL_FILE = oracle.checked_evidence_path(args.cancel_file) if args.cancel_file else None
    oracle.ACTIVE_EVIDENCE = evidence
    oracle.require(re.fullmatch(r"emulator-\d+", args.serial), "Only an explicitly owned emulator serial is accepted")
    adb = args.sdk.resolve() / r"platform-tools\adb.exe"
    env = dict(os.environ, ANDROID_HOME=str(args.sdk.resolve()), ANDROID_SDK_ROOT=str(args.sdk.resolve()))
    if args.artifact_root:
        env.update(TEMP=str(evidence), TMP=str(evidence))
    if args.jdk:
        env["JAVA_HOME"] = str(args.jdk.resolve())
        env["PATH"] = str(args.jdk.resolve() / "bin") + os.pathsep + env["PATH"]
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    changed = subprocess.check_output(["git", "diff", "--name-only", "HEAD"], cwd=ROOT, text=True).splitlines()
    changed += subprocess.check_output(["git", "ls-files", "--others", "--exclude-standard", "--",
                                       *CANDIDATE_INPUTS, "oracle_tools"],
                                       cwd=ROOT, text=True).splitlines()
    build_inputs = [name for name in changed if is_build_input(name)]
    manifest = "app/src/main/assets/watermark-oracle/manifest.json"
    if manifest in build_inputs and args.preserved_watermark_manifest_sha:
        oracle.require(oracle.sha256(ROOT / Path(manifest.replace("/", "\\"))) == args.preserved_watermark_manifest_sha.lower(),
                       "Pre-existing watermark manifest hash changed")
        build_inputs.remove(manifest)
    oracle.require(not build_inputs or args.allow_dirty, "Commit scoped build inputs first (or use --allow-dirty for unverified exploration)")
    revision = "unverified-local-source" if build_inputs else head
    if args.candidate_revision:
        oracle.require(args.skip_build and not args.allow_dirty and not build_inputs
                       and args.phase in ("ui", "ui-host")
                       and re.fullmatch(r"[0-9a-f]{40}", args.candidate_revision),
                       "Candidate reuse requires clean, prebuilt, exact-revision UI recovery")
        equivalent_scope = assert_candidate_equivalence(args.candidate_revision)
        revision = args.candidate_revision
    env["VIDEO_EDITOR_REVISION"] = revision
    protected = {str(path): oracle.sha256(path) for path in (args.protected_apk, args.protected_receipt) if path}
    summary = {"status": "RUNNING", "pid": os.getpid(), "serial": args.serial, "revision": revision, "head": head,
               "dirty_build_inputs": build_inputs, "preserved_watermark_manifest_sha": args.preserved_watermark_manifest_sha,
               "protected_before": protected, "stages": {}, "started": time.time(), "validation_errors": [],
               "phase": args.phase, "disk_preflight": disk, "artifact_root": str(oracle.ARTIFACT_ROOT) if oracle.ARTIFACT_ROOT else None,
               "candidate_source_equivalence": args.candidate_revision is not None}
    if args.candidate_revision:
        summary["equivalent_source_scope"] = equivalent_scope
    real_music_token = uuid.uuid4().hex
    summary["real_music_token"] = real_music_token
    def persist():
        oracle.persist_summary(evidence, summary)
        with WORKFLOW_PROGRESS.open("a", encoding="utf-8") as log:
            log.write(f"\n{time.strftime('%Y-%m-%dT%H:%M:%S')} workflow pid={os.getpid()} "
                      f"phase={args.phase} status={summary['status']} evidence={evidence}\n")
        with ASSURANCE_PROGRESS.open("a", encoding="utf-8") as log:
            log.write(f"{time.strftime('%Y-%m-%dT%H:%M:%S')} pid={os.getpid()} phase={args.phase} "
                      f"status={summary['status']} stages="
                      f"{ {name: entry['status'] for name, entry in summary['stages'].items()} } "
                      f"evidence={evidence}\n")
    def command(words, log, timeout=300):
        result = oracle.run_cmd([str(word) for word in words], cwd=ROOT, env=env,
                                stdout_path=evidence / log, stderr_path=evidence / (log + ".stderr.txt"),
                                read_output=False, timeout=timeout)
        oracle.require(result.returncode == 0, f"Command failed ({result.returncode}); see {log}")
    persist()
    verified_emulator = False
    instrumentation_owned = False
    original_encoding = None
    interrupted = False
    software_dir = None

    def select_mode(policy):
        record = configure_encoding(adb, args.serial, evidence, policy)
        summary.setdefault("encoding_changes", []).append(record)
        persist()
        assert_revision(record, revision)
        confirmed = configure_encoding(adb, args.serial, evidence)
        summary.setdefault("encoding_confirmations", []).append(confirmed)
        persist()
        assert_revision(confirmed, revision)
        oracle.require(confirmed["policy"] == policy, "Encoding policy did not persist across instrumentation processes")

    def cleanup_error(label, error):
        nonlocal interrupted
        if isinstance(error, KeyboardInterrupt):
            interrupted = True
            summary["status"] = "CANCELLED"
            summary.setdefault("error", "KeyboardInterrupt during cleanup")
        summary.setdefault("cleanup_errors", []).append(f"{label}: {error}")
        if not interrupted:
            summary["status"] = "FAIL"

    try:
        state = oracle.capture_device_state(adb, args.serial, evidence)
        oracle.require(state.get("ro.kernel.qemu") == "1" and state.get("sys.boot_completed") == "1",
                       "Owned emulator must already be booted")
        verified_emulator = True
        summary["device"] = state
        if not args.skip_build:
            command([ROOT / "gradlew.bat", "--offline", "--no-daemon", "--console=plain",
                     ":app:assembleDebug", ":app:assembleDebugAndroidTest"], "build.txt", 900)
        apks = [oracle.DEFAULT_APP_APK, oracle.DEFAULT_TEST_APK]
        summary["apks"] = []
        for apk, package in zip(apks, [PACKAGE, PACKAGE + ".test"]):
            if not args.skip_install:
                command([adb, "-s", args.serial, "install", "-r", apk], package + "-install.txt")
            remote = subprocess.check_output([str(adb), "-s", args.serial, "shell", "pm", "path", package],
                                             text=True, timeout=30).strip().removeprefix("package:")
            installed_hash = subprocess.check_output([str(adb), "-s", args.serial, "shell", "sha256sum", remote],
                                                     text=True, timeout=30).split()[0]
            summary["apks"].append({"path": str(apk), "installed_path": remote, "package": package,
                                    "sha256": installed_hash, "local_sha256": oracle.sha256(apk)})
            persist()
            if installed_hash != oracle.sha256(apk):
                summary["validation_errors"].append(f"Installed APK differs: {package}")
            signers = sorted((args.sdk.resolve() / "build-tools").glob(r"*\apksigner.bat"))
            oracle.require(bool(signers), "SDK apksigner is required for reproducible signature evidence")
            command([signers[-1], "verify", "--print-certs", apk], package + "-signature.txt")
        persist()
        instrumentation_owned = True
        summary["installed_attestation"] = configure_encoding(
            adb, args.serial, evidence, preferences="snapshot", preference_token=real_music_token)
        persist()
        assert_revision(summary["installed_attestation"], revision)
        oracle.require(not summary["validation_errors"], "Installed APK hashes differ from the requested build")
        original_encoding = summary["installed_attestation"]["after"]
        summary["original_compatibility_enabled"] = original_encoding
        persist()
        select_mode("default")
        classes = ["TitleStylesTest"] if args.phase == "titles" else UI_CLASSES if args.phase == "ui" else FEATURES
        groups = [("features", classes)] if args.phase in ("titles", "features", "all", "ui") else []
        if args.phase in ("border", "all"):
            groups += [("border-default", BORDER_CLASSES), ("border-software", BORDER_CLASSES)]
        if args.phase in ("features", "all"):
            groups += [("title-background-default", TITLE_BACKGROUND_CLASSES),
                       ("title-background-software", TITLE_BACKGROUND_CLASSES)]
        if args.phase in ("music", "all"):
            groups += [("music-default", MUSIC_CLASSES), ("music-software", MUSIC_CLASSES)]
            groups += [("real-music-default", REAL_MUSIC_CLASSES), ("real-music-software", REAL_MUSIC_CLASSES)]
        if args.phase in ("color", "all"):
            groups += [("color-default", COLOR_CLASSES), ("color-software", COLOR_CLASSES)]
        if args.phase in ("full", "all"):
            groups.append(("supplemental", SUPPLEMENTAL + (["FixedEditorUiTest"] if args.phase == "all" else [])))
        for name, classes in groups:
            if name.startswith(("border-", "music-", "real-music-", "color-", "title-background-")) or (name == "supplemental" and args.phase == "all"):
                select_mode("software-avc" if name.endswith("-software") else "default")
            expected = inventory(classes)
            log = name + ".txt"
            entry = {"expected": sorted(expected), "status": "RUNNING"}
            summary["stages"][name] = entry
            persist()
            command([adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r", "-e", "class",
                     ",".join(PACKAGE + "." + name for name in classes),
                     "-e", "real_music_token", real_music_token, oracle.RUNNER], log, 2400)
            parsed = oracle.parse_instrumentation((evidence / log).read_text(encoding="utf-8", errors="replace"))
            entry["instrumentation"] = parsed
            try:
                assert_inventory(parsed, expected)
                entry["status"] = "PASSED"
            except RuntimeError as error:
                entry["status"] = "FAILED"
                summary["validation_errors"].append(str(error))
            persist()
        if args.phase in ("features", "all"):
            command([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE,
                     "tar", "-cf", "-", "files/title-background-evidence"], "title-background-evidence.tar", 90)
            with tarfile.open(evidence / "title-background-evidence.tar") as archive:
                records = [json.load(archive.extractfile(member)) for member in archive.getmembers()
                           if member.name.endswith(".json")]
                oracle.require(len(records) == 4 and
                               {(r["titleMs"], r["policy"]) for r in records}
                               == {(ms, p) for ms in (3000, 5000) for p in ("default", "software")}
                               and all(r["revision"] == revision and r["status"] == "PASS" for r in records),
                               "Missing/stale title background exports")
                summary["title_background_outputs"] = records
            persist()
        if args.phase in ("music", "all"):
            command([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE,
                     "tar", "-cf", "-", "files/offline-music-evidence"], "offline-music-evidence.tar", 90)
            with tarfile.open(evidence / "offline-music-evidence.tar") as archive:
                results = [json.load(archive.extractfile(member)) for member in archive.getmembers()
                           if member.name.endswith(("/result.json", "/ui-result.json"))]
                current = [r for r in results if r.get("revision") == revision]
                oracle.require(all(r.get("status") == "PASS" and r.get("syntheticOnly") for r in current),
                               "Music synthetic evidence failed")
                oracle.require({("ui" if "publicOutputs" in r else "native", r["policy"]) for r in current}
                               == {(kind, policy) for kind in ("native", "ui")
                                   for policy in ("default", "software-avc")},
                               "Missing current-revision native/UI music evidence for both policies")
                summary["offline_music_outputs"] = current
            persist()
            command([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE, "tar", "-cf", "-",
                     "files/real-music-evidence/" + real_music_token], "real-music-evidence.tar", 120)
            real_directory = evidence / "real-music"
            summary["real_music_inventory"] = extract_real_music(
                evidence / "real-music-evidence.tar", real_directory, revision, real_music_token)
            command([sys.executable, "-B", ROOT / r"oracle_tools\verify_real_music_exports.py",
                     "--evidence", real_directory, "--revision", revision,
                     "--output", evidence / "independent-real-music.json"], "independent-real-music.txt", 600)
            summary["independent_real_music"] = json.loads(
                (evidence / "independent-real-music.json").read_text(encoding="utf-8"))
            oracle.require(summary["independent_real_music"].get("status") == "PASS",
                           "Independent original-track PCM verification failed")
            persist()
        if args.phase in ("color", "all"):
            command([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE,
                     "tar", "-cf", "-", "files/color-adjust-evidence",
                     "files/public-output-preview-evidence"],
                    "color-adjust-evidence.tar", 90)
            with tarfile.open(evidence / "color-adjust-evidence.tar") as archive:
                results = [json.load(archive.extractfile(member)) for member in archive.getmembers()
                           if member.name.startswith("files/color-adjust-evidence/test")
                           and member.name.endswith("-result.json")]
                expected = {(test.split("#")[1], policy)
                            for test in inventory(["ColorAdjustmentExportTest"])
                            for policy in ("default", "software")}
                oracle.require({(r["test"], r["policy"]) for r in results} == expected
                               and len(results) == len(expected)
                               and all(r["status"] == "PASS" and r["revision"] == revision for r in results),
                               "Missing, stale or failing native color palette evidence")
                summary["color_outputs"] = results
            summary["independent_color"] = verify_color_exports(
                evidence / "color-adjust-evidence.tar", evidence, revision)
            persist()
        if args.phase in ("border", "all"):
            command([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE,
                     "tar", "-cf", "-", "files/border-export-evidence"], "border-export-evidence.tar", 90)
            with tarfile.open(evidence / "border-export-evidence.tar") as archive:
                results = [json.load(archive.extractfile(member)) for member in archive.getmembers()
                           if member.name.endswith(".json")]
                expected = {(name, policy) for name in ("title-3000", "title-5000", "whole-music",
                            "whole-merge", "geometry-90", "geometry-37") for policy in ("default", "software")}
                oracle.require({(r["name"], r["policy"]) for r in results} == expected and len(results) == 12,
                               "Missing border output assertions")
                oracle.require(all(r["status"] == "PASS" and r["revision"] == revision for r in results),
                               "Border results failed or from stale revision")
                summary["border_outputs"] = results
            persist()
        if args.phase in ("full", "all"):
            recovery_dir = evidence / "saf-recovery"
            summary["stages"]["saf-recovery"] = {"status": "RUNNING"}
            persist()
            try:
                command([sys.executable, "-B", ROOT / r"oracle_tools\run_report_crash_validation.py",
                         "--serial", args.serial, "--adb", adb, "--evidence-dir", recovery_dir,
                         "--short", "--large-report"], "saf-recovery.txt", 900)
                recovery = json.loads((recovery_dir / "summary.json").read_text(encoding="utf-8"))
                for name, log in (("LargeReportRecoveryTest", "native-large-fixture.txt"),
                                  ("SavedReportRecoveryTest", "native-recovery.txt")):
                    parsed = oracle.parse_instrumentation((recovery_dir / log).read_text(encoding="utf-8"))
                    assert_inventory(parsed, inventory([name]))
                    recovery[name] = parsed
                summary["stages"]["saf-recovery"] = {"status": "PASSED", "results": recovery}
            except Exception as error:
                oracle.force_stop(adb, args.serial)
                summary["stages"]["saf-recovery"] = {"status": "FAILED", "error": str(error)}
                summary["validation_errors"].append("Real SAF recovery: " + str(error))
            persist()
            oracle.ACTIVE_EVIDENCE = evidence
            select_mode("default")
            for stage in ("smoke", "suite", "regressions"):
                if not oracle.run_stage(adb, args.serial, evidence, summary, stage,
                                        expected_revision=revision, expected_apk_hash=summary["apks"][0]["sha256"],
                                        expected_policy="default" if stage == "suite" else None):
                    summary["validation_errors"].append(f"Failed oracle stage: {stage}")
                persist()
            software_dir = evidence / "software"
            software_dir.mkdir()
            software = {key: summary[key] for key in ("serial", "revision", "head", "apks", "protected_before",
                                                     "installed_attestation", "original_compatibility_enabled")}
            software.update(stages={}, status="RUNNING", expected_policy="software-avc")
            summary["software"] = software
            oracle.ACTIVE_EVIDENCE = software_dir
            original = oracle.STAGE_METHODS["suite"]
            try:
                select_mode("software-avc")
                oracle.STAGE_METHODS["suite"] = PACKAGE + ".EmulatorValidationTest#testCompatibilityProductionSelfTestRunnerTerminalSuiteJson"
                if not oracle.run_stage(adb, args.serial, software_dir, software, "suite",
                                        expected_revision=revision, expected_apk_hash=summary["apks"][0]["sha256"],
                                        expected_policy="software-avc"):
                    summary["validation_errors"].append("Failed software 17/60 suite")
                    software["status"] = "FAIL"
                else:
                    software["status"] = "PASS"
            finally:
                oracle.STAGE_METHODS["suite"] = original
                summary["software"] = software
        if args.phase in ("titles", "features", "all", "ui"):
            for directory in ("editor-title-evidence", "public-output-preview-evidence"):
                if args.phase == "titles" and directory.startswith("public"): continue
                if args.phase == "ui" and directory.startswith("editor-title"): continue
                command([adb, "-s", args.serial, "exec-out", "run-as", PACKAGE,
                         "tar", "-cf", "-", "files/" + directory], directory + ".tar", 90)
        if args.phase in ("features", "all", "ui"):
            with tarfile.open(evidence / "public-output-preview-evidence.tar") as archive:
                for filename in ("result.json", "preset-result.json", "border-result.json"):
                    result = json.load(archive.extractfile("files/public-output-preview-evidence/" + filename))
                    oracle.require(result["status"] == "PASS" and result["revision"] == revision,
                                   "UI/output evidence is stale or failed")
                    if filename == "result.json":
                        oracle.require(result["width"] == 256 and result["height"] == 192
                                       and result["location"] == "Movies/" and result["uri"].startswith("content:"),
                                       "Crop public output geometry/URI mismatch")
                    elif filename == "preset-result.json":
                        oracle.require(result["missingAssetAtomic"] and result["shortSourceAtomic"]
                                        and result["publicOutput"].startswith("content:"), "Preset output/validation missing")
                    else:
                        oracle.require(result["presetRoundTrip"] and result["titleBoundaryMs"] == 3000
                                       and result["location"] == "Movies/" and result["uri"].startswith("content:"),
                                       "Border UI/output evidence missing")
                    summary[filename] = result
        if args.phase in ("titles", "features", "all"):
            with tarfile.open(evidence / "editor-title-evidence.tar") as archive:
                results = [json.load(archive.extractfile(member)) for member in archive.getmembers() if member.name.endswith(".json")]
                expected = {(style, duration) for style in ("fade", "slide", "typewriter", "scale", "lower-third") for duration in (3000, 5000)}
                oracle.require({(r["style"], r["durationMs"]) for r in results} == expected and len(results) == 10,
                               "Missing title output assertions")
                oracle.require(all(r["status"] == "PASS" and r["revision"] == revision
                                   and r["omittedRejected"] and r["staticRejected"] for r in results),
                               "Title results failed or from stale revision")
                summary["title_outputs"] = results
        if args.phase in ("ui", "ui-host", "all"):
            oracle.ACTIVE_EVIDENCE = evidence
            if args.phase == "all":
                select_mode("default")
            summary["stages"]["ui-workflow"] = {"status": "RUNNING"}
            persist()
            ui_result = run_ui_workflow(adb, args.serial, evidence / "ui-workflow", env=env)
            summary["stages"]["ui-workflow"]["result"] = ui_result
            oracle.require(ui_result.get("status") == "PASS", "Real UI workflow did not pass")
            summary["stages"]["ui-workflow"]["status"] = "PASSED"
            persist()
        summary["status"] = "FAIL" if summary["validation_errors"] else "PASS"
    except KeyboardInterrupt as error:
        interrupted = True
        summary["status"] = "CANCELLED"
        summary["error"] = str(error) or "KeyboardInterrupt"
        summary["cleanup_errors"] = getattr(error, "cleanup_errors", [])
    except Exception as error:
        summary["status"] = "FAIL"
        summary["error"] = str(error)
        summary["failure_category"] = getattr(error, "category", "ASSERTION_OR_RUNTIME")
        summary["cleanup_errors"] = getattr(error, "cleanup_errors", [])
        print(f"FAIL: {error}", file=sys.stderr)
    finally:
        oracle.CANCEL_FILE = None
        oracle.ACTIVE_EVIDENCE = evidence
        if summary["status"] == "RUNNING":
            summary["status"] = "FAIL"
        terminalize(summary, "CANCELLED" if interrupted else "FAILED")
        persist()
        if verified_emulator and instrumentation_owned and summary["status"] != "PASS":
            try:
                oracle.force_stop(adb, args.serial)
                summary["owned_instrumentation_stopped"] = True
            except (Exception, KeyboardInterrupt) as error:
                cleanup_error("owned instrumentation stop", error)
        if original_encoding is not None:
            try:
                record = configure_encoding(adb, args.serial, evidence,
                                            "software-avc" if original_encoding else "default",
                                            preferences="restore", preference_token=real_music_token)
                summary["encoding_restore"] = record
                assert_revision(record, revision)
                confirmed = configure_encoding(adb, args.serial, evidence,
                                               preferences="verify", preference_token=real_music_token)
                summary["encoding_restored_query"] = confirmed
                assert_revision(confirmed, revision)
                oracle.require(confirmed["after"] == original_encoding, "Encoding preference not restored")
                summary["encoding_preference_restored"] = True
                oracle.require(confirmed["preferences"] == summary["installed_attestation"]["preferences"],
                               "Editor language/music/title preferences not restored")
                summary["editor_preferences_restored"] = True
                command([adb, "-s", args.serial, "shell", "run-as", PACKAGE, "rm", "-f",
                         "files/workflow-preferences-" + real_music_token + ".json"],
                        "owned-preference-snapshot-cleanup.txt", 30)
            except (Exception, KeyboardInterrupt) as error:
                cleanup_error("encoding preference restore", error)
                summary["encoding_preference_restored"] = False
                if verified_emulator and instrumentation_owned:
                    try:
                        oracle.force_stop(adb, args.serial)
                        summary["restore_instrumentation_stopped"] = True
                    except (Exception, KeyboardInterrupt) as stop_error:
                        cleanup_error("restoration instrumentation stop", stop_error)
        if summary["status"] == "FAIL" and verified_emulator:
            try:
                existing = []
                for directory in ("editor-title-evidence", "public-output-preview-evidence"):
                    check = subprocess.run([str(adb), "-s", args.serial, "shell", "run-as", PACKAGE,
                                            "test", "-d", "files/" + directory], capture_output=True, timeout=30)
                    if check.returncode == 0: existing.append("files/" + directory)
                if existing:
                    with (evidence / "failed-feature-evidence.tar").open("wb") as archive:
                        subprocess.run([str(adb), "-s", args.serial, "exec-out", "run-as", PACKAGE,
                                        "tar", "-cf", "-", *existing], stdout=archive, check=True, timeout=90)
            except KeyboardInterrupt as capture_error:
                summary["failure_capture_error"] = str(capture_error)
                cleanup_error("failure evidence capture", capture_error)
            except Exception as capture_error:
                summary["failure_capture_error"] = str(capture_error)
        try:
            summary["protected_after"] = {path: oracle.sha256(Path(path)) for path in protected}
            oracle.require(summary["protected_after"] == protected, "Protected file hashes changed")
        except (Exception, KeyboardInterrupt) as error:
            cleanup_error("protected file verification", error)
            summary["protected_changed"] = True
        if args.stop_emulator and verified_emulator:
            try:
                command([adb, "-s", args.serial, "emu", "kill"], "owned-emulator-stop.txt", 30)
            except (Exception, KeyboardInterrupt) as error:
                cleanup_error("owned emulator stop", error)
        summary["finished"] = time.time()
        summary["cancelled"] = interrupted
        summary["passed"] = summary["status"] == "PASS"
        if software_dir is not None:
            software.update(protected_after=summary.get("protected_after"), finished=summary["finished"],
                            encoding_preference_restored=summary.get("encoding_preference_restored"))
            oracle.persist_summary(software_dir, software)
        persist()
        oracle.CANCEL_FILE = previous_cancel
    print(json.dumps({"status": summary["status"], "evidence": str(evidence), "pid": os.getpid()}))
    return 130 if interrupted else 0 if summary["status"] == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
