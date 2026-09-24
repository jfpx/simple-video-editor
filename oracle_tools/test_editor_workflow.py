import copy
import contextlib
import io
import json
from pathlib import Path
import shutil
import subprocess
import tarfile
import unittest
from unittest.mock import patch, MagicMock
import uuid
import xml.etree.ElementTree as ET
import run_editor_workflow as workflow
import run_report_crash_validation as recovery
import test_emulator_validation as fixtures


class ReportRecoveryUiTests(unittest.TestCase):
    def test_recovery_opens_selftest_inside_more_not_old_english_main_label(self):
        ui = MagicMock()
        recovery.open_selftest(ui)
        ui.panel_control.assert_called_once_with("panelMore", "btnSelfTest")
        ui.tap.assert_called_once_with(ui.panel_control.return_value)

    def test_recovery_buttons_match_both_locales_and_android_uppercase(self):
        for resource in ("secondary_short_start", "secondary_start", "secondary_open_report",
                         "secondary_share_report", "secondary_cancel", "secondary_copy_report",
                         "secondary_copy_preview"):
            for label in recovery.localized_labels(resource):
                with self.subTest(resource=resource, label=label):
                    tree = ET.Element("hierarchy")
                    button = ET.SubElement(tree, "node", {
                        "class": "android.widget.Button", "text": label.upper(), "enabled": "false"})
                    self.assertIs(button, recovery.localized_button(tree, resource))
                    self.assertEqual("false", button.get("enabled"))

    def test_recovery_rejects_hint_substrings_missing_and_ambiguous_buttons(self):
        for class_name, text, count in (
                ("android.widget.TextView", "Choose TXT and start", 1),
                ("android.widget.Button", "Press Choose TXT and start for offline checks.", 1),
                ("android.widget.Button", "Choose TXT and start", 0),
                ("android.widget.Button", "Choose TXT and start", 2)):
            tree = ET.Element("hierarchy")
            for _ in range(count):
                ET.SubElement(tree, "node", {"class": class_name, "text": text})
            with self.subTest(class_name=class_name, text=text, count=count), self.assertRaises(AssertionError):
                recovery.localized_button(tree, "secondary_start")

    def test_copy_confirmation_checks_localized_acknowledgement_not_pass_verdict(self):
        labels = recovery.localized_labels("secondary_copied")
        self.assertEqual(2, len(labels))
        self.assertTrue(any("已复制" in label for label in labels))
        self.assertTrue(any("visible report copied" in label for label in labels))
        self.assertNotIn("all pass", labels)

    def test_external_origin_uses_localized_prefix_when_raw_header_is_outside_tail(self):
        for origin in recovery.localized_labels("secondary_external_origin"):
            tree = ET.Element("hierarchy")
            ET.SubElement(tree, "node", {"text": origin + "[TAIL PREVIEW ONLY]\nLARGE_UI_LAST_EVENT_511"})
            recovery.assert_external_origin(tree)
        for wrong in ("RESTORED SNAPSHOT", "SAVED EXTERNAL TXT", "ALL PASS",
                      "OPEN SAVED TXT FAILED"):
            tree = ET.Element("hierarchy")
            ET.SubElement(tree, "node", {"text": wrong})
            with self.subTest(wrong=wrong), self.assertRaises(AssertionError):
                recovery.assert_external_origin(tree)


class WorkflowInventoryTests(unittest.TestCase):
    def test_feature_inventory_contains_all_ten_title_exports_and_ui(self):
        expected = workflow.inventory(workflow.FEATURES)
        self.assertEqual(36, len(expected))
        self.assertIn("com.simple.videoeditor.SelectedFramePreviewTest#testDisabledPortraitPngPresetOnLandscapePreservesAssetAndReenableValidation", expected)
        self.assertIn("com.simple.videoeditor.SelectedFramePreviewTest#testWholePresetRealPickerSecondDurationAtomicValidationAndTitleControls", expected)
        self.assertIn("com.simple.videoeditor.SelectedFramePreviewTest#testIndependentTitleAppearanceNativeControlsAndExports", expected)
        self.assertIn("com.simple.videoeditor.SelectedFramePreviewTest#testTitleOriginalFrameRealControlsSavePresetAndStaleRequests", expected)
        contracts = {
            "com.simple.videoeditor.TitleAppearanceTest#" + method for method in (
                "testLegacyMappingAndRoundTripPreserveAppearance",
                "testStrictTypesAndRangesRejectInsteadOfDefault",
                "testCustomDurationStyleSnapshotsAndSizeScaling",
                "testCorruptStoreIsNotOverwrittenWithDefaults",
                "testDraftCanBeCorrectedButPresetRejectsInvalidAndIntegralFloatTypes",
            )
        }
        self.assertEqual(contracts, workflow.inventory(["TitleAppearanceTest"]))
        for contract in contracts:
            self.assertIn(contract, expected)
        for style in ("Fade", "Slide", "Typewriter", "Scale", "LowerThird"):
            for duration in (3, 5):
                self.assertIn(f"com.simple.videoeditor.TitleStylesTest#test{style}{duration}s", expected)

    def test_exit_zero_cannot_replace_complete_passing_inventory(self):
        expected = {"Example#testOne", "Example#testTwo"}
        parsed = {"passed": True, "ok_tests": 2, "test_counts": {"PASS": 2},
                  "tests": [{"class": "Example", "test": "testOne"}, {"class": "Example", "test": "testTwo"}]}
        workflow.assert_inventory(parsed, expected)
        for mutation in ("missing", "wrong", "failed", "count"):
            changed = copy.deepcopy(parsed)
            if mutation == "missing": changed["tests"].pop()
            if mutation == "wrong": changed["tests"][1]["test"] = "testOther"
            if mutation == "failed": changed["passed"] = False
            if mutation == "count": changed["ok_tests"] = 1
            with self.subTest(mutation=mutation), self.assertRaises(RuntimeError):
                workflow.assert_inventory(changed, expected)

    def test_stateful_saf_fixtures_are_not_run_against_old_selected_documents(self):
        for name in ("SavedReportRecoveryTest", "LargeReportRecoveryTest"):
            self.assertNotIn(name, workflow.SUPPLEMENTAL)
            self.assertEqual(1, len(workflow.inventory([name])))
        self.assertEqual(172, len(workflow.inventory(workflow.SUPPLEMENTAL)))

    def test_ui_inventory_includes_exact_fixed_preview_and_localized_summary_tests(self):
        self.assertEqual(["SelectedFramePreviewTest", "FixedEditorUiTest", "SuiteSummaryUiTest"],
                         workflow.UI_CLASSES)
        self.assertEqual([15, 15, 6], [len(workflow.inventory([name])) for name in workflow.UI_CLASSES])
        expected = workflow.inventory(workflow.UI_CLASSES)
        self.assertEqual(36, len(expected))
        for method in (
                "SelectedFramePreviewTest#testIndependentTitleAppearanceNativeControlsAndExports",
                "SelectedFramePreviewTest#testTitleOriginalFrameRealControlsSavePresetAndStaleRequests",
                "FixedEditorUiTest#testTitleDraftAndAppearanceSurviveLocaleRecreation",
                "FixedEditorUiTest#testTitleFrameLocaleRecreationAndInactiveControls"):
            self.assertIn("com.simple.videoeditor." + method, expected)
        self.assertEqual(36, len(workflow.inventory(workflow.FEATURES)))
        self.assertEqual(223, len(workflow.inventory(
            workflow.FEATURES + workflow.SUPPLEMENTAL + ["FixedEditorUiTest"])))

    def test_review_regressions_keep_png_and_real_preset_paths_in_inventory(self):
        self.assertEqual(14, len(workflow.inventory(workflow.TITLE_BACKGROUND_CLASSES)))
        expected = workflow.inventory(workflow.FEATURES)
        for method in ("testTitlePresetTimestampOnlyPublishesAndExports3Seconds",
                       "testTitlePresetTimestampOnlyPublishesAndExports5Seconds",
                       "testTitlePresetBackgroundEnableOnlyRestartsCapture",
                       "testTitleBusyPresetInvalidatesQueuedCaptureAndSaveChecksLiveSettings"):
            self.assertIn("com.simple.videoeditor.SelectedFramePreviewTest#" + method, expected)

    def test_border_exact_inventory_includes_negative_controls_and_native_boundaries(self):
        expected = workflow.inventory(workflow.BORDER_CLASSES)
        self.assertEqual(10, len(expected))
        for method in ("testTitleOnly3SecondsStopsAtBoundary", "testTitleOnly5SecondsStopsAtBoundary",
                       "testWholeTimelineSpeedMusicTransmuxDoesNotDrawTwice",
                       "testWholeTimelineIncludesGeneratedImportedMainAndEveryMerge",
                       "testCropRotateResizeFinalPixelWidthAndStaticPreview"):
            self.assertIn("com.simple.videoeditor.BorderExportTest#" + method, expected)
        self.assertIn("com.simple.videoeditor.VideoBorderTest#testSameCheckerRejectsOmittedWrongColorWidthScopeAndPadding",
                       expected)

    def test_music_inventory_keeps_legacy_and_exact_pcm_checks(self):
        expected = workflow.inventory(workflow.MUSIC_CLASSES)
        self.assertEqual(5, len(workflow.inventory(["OfflineMusicTest"])))
        for name in ("MusicCompositionTest", "TimelineAudioCompositionTest", "TimelineAudioAacTest",
                     "WholeEditPresetsTest"):
            self.assertTrue(workflow.inventory([name]).issubset(expected))
        self.assertIn("com.simple.videoeditor.OfflineMusicTest#testNativeIndependentGainsLoopAndTimeline", expected)
        self.assertIn("com.simple.videoeditor.OfflineMusicTest#testSoundTabSyntheticSelectionGainClearAndMediaStoreExport",
                       expected)
        self.assertEqual(4, len(workflow.inventory(workflow.REAL_MUSIC_CLASSES)))
        self.assertIn("com.simple.videoeditor.RealMusicLibraryTest#testAuditionLifecycleAndCancelExport",
                      workflow.inventory(workflow.REAL_MUSIC_CLASSES))

    def test_color_exact_inventory_and_invalid_target_rejected(self):
        expected = workflow.inventory(workflow.COLOR_CLASSES)
        self.assertEqual(12, len(expected))
        self.assertIn("com.simple.videoeditor.ColorAdjustmentTest#testExpectedCheckerRejectsOmittedAndWrongSettings",
                      expected)
        self.assertIn("com.simple.videoeditor.ColorAdjustmentExportTest#testGeneratedImportedAndAppendedSegmentsUnaffected",
                      expected)
        with self.assertRaises(RuntimeError):
            workflow.inventory(["ColorAdjustmentTest#testNonexistent"])


class ArtifactRootTests(unittest.TestCase):
    def setUp(self):
        self.directory = workflow.ROOT / "app" / "build" / ("artifact-host-" + uuid.uuid4().hex)
        self.directory.mkdir(parents=True)
        self.addCleanup(shutil.rmtree, self.directory)
        previous = workflow.oracle.ARTIFACT_ROOT
        self.addCleanup(setattr, workflow.oracle, "ARTIFACT_ROOT", previous)
        workflow.oracle.ARTIFACT_ROOT = None

    def test_new_root_is_exclusive_and_json_cannot_escape(self):
        root, space = workflow.oracle.create_artifact_root(self.directory / "owned", 1024)
        workflow.oracle.ARTIFACT_ROOT = root
        self.assertGreaterEqual(space["free_bytes"], 1024)
        workflow.oracle.write_json(root / "child" / "result.json", {"status": "PASS"})
        with self.assertRaisesRegex(RuntimeError, "already exists"):
            workflow.oracle.create_artifact_root(root, 1024)
        with self.assertRaisesRegex(RuntimeError, "escaped"):
            workflow.oracle.write_json(self.directory / "outside.json", {})
        self.assertFalse((self.directory / "outside.json").exists())

    def test_absolute_non_root_non_traversing_directory_required(self):
        for path in (Path("relative"), Path(self.directory.anchor), self.directory / ".." / "escape"):
            with self.subTest(path=path), self.assertRaises(RuntimeError):
                workflow.oracle.create_artifact_root(path, 1024)

    def test_disk_reserve_fails_before_creating_root_or_report(self):
        root = self.directory / "low-space"
        with patch.object(workflow.oracle.shutil, "disk_usage", return_value=type("Space", (), {"free": 19 * 1024 ** 2})()):
            with self.assertRaisesRegex(RuntimeError, "DISK_RESERVE"):
                workflow.oracle.create_artifact_root(root, 2 * 1024 ** 3)
        self.assertFalse(root.exists())

    def test_symlink_or_junction_parent_is_rejected_without_following_it(self):
        with patch.object(Path, "lstat", return_value=type("Attributes", (), {
                "st_file_attributes": workflow.oracle.stat.FILE_ATTRIBUTE_REPARSE_POINT})()), \
                patch.object(Path, "is_symlink", return_value=False):
            with self.assertRaisesRegex(RuntimeError, "reparse"):
                workflow.oracle.checked_evidence_path(self.directory / "child", self.directory)

    def test_external_artifact_root_rejects_build_and_unsupported_phases(self):
        for arguments in (["--phase", "ui"], ["--phase", "all", "--skip-build"]):
            root = self.directory / "not-created"
            with self.assertRaisesRegex(RuntimeError, "prebuilt ui"):
                workflow.main(["--serial", "emulator-5580", "--artifact-root", str(root),
                               "--evidence-dir", str(root / "run"), *arguments])
            self.assertFalse(root.exists())

    def test_candidate_equivalence_checks_app_tests_assets_and_build_not_just_java(self):
        with patch.object(workflow.subprocess, "run", return_value=subprocess.CompletedProcess([], 0)) as run:
            scope = workflow.assert_candidate_equivalence("a" * 40)
        self.assertIn("app", scope)
        self.assertIn("build.gradle", scope)
        self.assertEqual(["git", "diff", "--exit-code", "a" * 40, "HEAD", "--", *scope], run.call_args.args[0])
        for code in (1, 128):
            with patch.object(workflow.subprocess, "run", return_value=subprocess.CompletedProcess([], code)), \
                    self.assertRaisesRegex(RuntimeError, "rebuild"):
                workflow.assert_candidate_equivalence("a" * 40)
        with self.assertRaisesRegex(RuntimeError, "exact"):
            workflow.assert_candidate_equivalence("HEAD")

    def test_build_inputs_include_wrapper_properties_scripts_and_all_app_files(self):
        for name in ("gradle.properties", "gradle/wrapper/gradle-wrapper.properties",
                     "gradle/wrapper/gradle-wrapper.jar", "gradlew", "gradlew.bat",
                     "app/proguard-rules.pro", "app/src/main/assets/music/license.txt",
                     "app/src/androidTest/assets/workflow-source-revision.txt"):
            with self.subTest(name=name):
                self.assertTrue(workflow.is_build_input(name))
        self.assertFalse(workflow.is_build_input("README.md"))

    def test_candidate_reuse_rejects_staged_unstaged_or_untracked_build_configuration(self):
        for dirty_command in ("diff", "ls-files"):
            evidence = self.directory / dirty_command
            def output(words, **kwargs):
                if words[1] == "rev-parse":
                    return "a" * 40
                if words[1] == dirty_command:
                    return "gradle/wrapper/gradle-wrapper.properties\n"
                return ""
            with patch.object(workflow.subprocess, "check_output", side_effect=output) as git, \
                    patch.object(workflow.oracle, "run_cmd") as device, \
                    self.assertRaisesRegex(RuntimeError, "Commit scoped build inputs"):
                workflow.main(["--serial", "emulator-5580", "--phase", "ui-host",
                               "--skip-build", "--skip-install", "--evidence-dir", str(evidence),
                               "--candidate-revision", "b" * 40])
            device.assert_not_called()
            self.assertFalse((evidence / "summary.json").exists())
            untracked = next(call.args[0] for call in git.call_args_list if call.args[0][1] == "ls-files")
            self.assertIn("gradle", untracked)
            self.assertIn("gradlew", untracked)

    def test_windows_atomic_report_lock_is_bounded_and_never_deletes_previous_report(self):
        path = self.directory / "summary.json"
        workflow.oracle.write_json(path, {"status": "OLD"})
        replace = workflow.oracle.os.replace
        locked = PermissionError("sharing violation")
        locked.winerror = 32
        def transient(source, destination):
            self.assertEqual({"status": "OLD"}, json.loads(path.read_text()))
            if run.call_count == 1:
                raise locked
            replace(source, destination)
        with patch.object(workflow.oracle.os, "replace", side_effect=transient) as run, \
                patch.object(workflow.oracle.time, "sleep") as sleep:
            workflow.oracle.write_json(path, {"status": "NEW"})
        self.assertEqual(2, run.call_count)
        sleep.assert_called_once_with(.05)
        with patch.object(workflow.oracle.os, "replace", side_effect=locked) as run, \
                patch.object(workflow.oracle.time, "sleep") as sleep, \
                self.assertRaises(PermissionError):
            workflow.oracle.write_json(path, {"status": "NEVER"})
        self.assertEqual(4, run.call_count)
        self.assertEqual(3, sleep.call_count)
        self.assertEqual({"status": "NEW"}, json.loads(path.read_text()))
        self.assertFalse(list(self.directory.glob("*.pending")))


class WorkflowAttestationTests(unittest.TestCase):
    revision = "a" * 40
    apk_hash = "c" * 64

    def setUp(self):
        self.directory = workflow.ROOT / "app" / "build" / ("workflow-host-" + uuid.uuid4().hex)
        self.directory.mkdir(parents=True)
        self.sdk = self.directory / "sdk"
        (self.sdk / "build-tools" / "35").mkdir(parents=True)
        (self.sdk / "build-tools" / "35" / "apksigner.bat").write_text("mock signer")
        self.addCleanup(shutil.rmtree, self.directory)
        self.original_cancel = workflow.oracle.CANCEL_FILE
        self.original_evidence = workflow.oracle.ACTIVE_EVIDENCE
        self.addCleanup(setattr, workflow.oracle, "CANCEL_FILE", self.original_cancel)
        self.addCleanup(setattr, workflow.oracle, "ACTIVE_EVIDENCE", self.original_evidence)

    def run_workflow(self, *, stale=None, wrong_policy=None, interrupt=None, restore_error=False,
                     installed_revision=None, installed_hash=None, lost_write=False, phase="full",
                      restore_fault=None, ui_error=None, omit_fixed=False, border_fault=None, color_fault=None,
                      preference_fault=False):
        evidence = self.directory / ("run-" + uuid.uuid4().hex)
        state = {"enabled": True, "actions": []}
        def config(adb, serial, directory, action="query", **kwargs):
            self.assertEqual("emulator-5580", serial)
            before = state["enabled"]
            if lost_write and action == "query" and len(state["actions"]) == 2:
                state["enabled"] = True
            state["actions"].append(action)
            if restore_fault and len(state["actions"]) >= 8:
                point, error = restore_fault
                if (point == "write" and action == "software-avc") or (point == "query" and action == "query"):
                    state["restore_active"] = True
                    raise error
            if restore_error and action == "software-avc" and state.get("interrupted"):
                raise RuntimeError("restore failed")
            if action != "query":
                state["enabled"] = action == "software-avc"
            return dict(source_revision=installed_revision or self.revision,
                         test_source_revision=self.revision, before=before, after=state["enabled"],
                         action=action, policy="software-avc" if state["enabled"] else "default",
                         preferences={"ui_locales": {"language": "en" if preference_fault
                                      and kwargs.get("preferences") == "verify" else "zh-CN"},
                                      "offline_music": {}, "intro_templates": {"original_animated_styles_v1": True}})

        def output(words, **kwargs):
            if words[0] == "git":
                return self.revision if words[1] == "rev-parse" else ""
            return "package:/installed/base.apk" if "path" in words else (installed_hash or self.apk_hash) + "  /installed/base.apk"

        def direct_run(words, **kwargs):
            if interrupt == "failure-capture" and "test" in words:
                raise KeyboardInterrupt("inside failure capture")
            return subprocess.CompletedProcess(words, 1)

        def stop_owned(*args):
            state["restore_active"] = False

        def command(words, **kwargs):
            log = kwargs.get("stdout_path")
            if log:
                log.write_bytes(b"")
            if "class" in words:
                names = words[words.index("class") + 1].split(",")
                ids = workflow.inventory([name.rsplit(".", 1)[1] for name in names])
                if omit_fixed:
                    ids = {name for name in ids if ".FixedEditorUiTest#" not in name}
                log.write_text(fixtures.transcript(sorted(ids)), encoding="utf-8")
            if interrupt == "feature-capture" and "tar" in words:
                state["interrupted"] = True
                raise KeyboardInterrupt("inside feature capture")
            if "tar" in words and log.name == "public-output-preview-evidence.tar":
                records = {
                    "result.json": dict(status="PASS", revision=self.revision, width=256, height=192,
                                        location="Movies/", uri="content://media/video/1"),
                    "preset-result.json": dict(status="PASS", revision=self.revision,
                                               missingAssetAtomic=True, shortSourceAtomic=True,
                                               publicOutput="content://media/video/2"),
                    "border-result.json": dict(status="PASS", revision=self.revision,
                                               location="Movies/", uri="content://media/video/3",
                                               titleBoundaryMs=3000, presetRoundTrip=True)}
                with tarfile.open(log, "w") as archive:
                    for filename, record in records.items():
                        payload = json.dumps(record).encode()
                        member = tarfile.TarInfo("files/public-output-preview-evidence/" + filename)
                        member.size = len(payload)
                        archive.addfile(member, io.BytesIO(payload))
            if "tar" in words and log.name == "border-export-evidence.tar":
                records = [dict(name=name, policy=policy, revision=self.revision, status="PASS")
                           for name in ("title-3000", "title-5000", "whole-music", "whole-merge", "geometry-90", "geometry-37")
                           for policy in ("default", "software")]
                if border_fault == "missing": records.pop()
                if border_fault == "stale": records[0]["revision"] = "b" * 40
                if border_fault == "failed": records[0]["status"] = "FAIL"
                with tarfile.open(log, "w") as archive:
                    for record in records:
                        payload = json.dumps(record).encode()
                        member = tarfile.TarInfo("files/border-export-evidence/" + record["name"] + "-" + record["policy"] + ".json")
                        member.size = len(payload)
                        archive.addfile(member, io.BytesIO(payload))
            if "tar" in words and log.name == "offline-music-evidence.tar":
                with tarfile.open(log, "w") as archive:
                    for policy in ("default", "software-avc"):
                        for role in ("native", "ui"):
                            record = dict(revision=self.revision, status="PASS", syntheticOnly=True, policy=policy)
                            if role == "ui": record["publicOutputs"] = []
                            payload = json.dumps(record).encode()
                            member = tarfile.TarInfo(f"files/offline-music-evidence/{policy}/{role}/"
                                                    + ("ui-result.json" if role == "ui" else "result.json"))
                            member.size = len(payload)
                            archive.addfile(member, io.BytesIO(payload))
            if "tar" in words and log.name == "real-music-evidence.tar":
                token = words[-1].split("/")[-1]
                with tarfile.open(log, "w") as archive:
                    for policy in ("default", "software-avc"):
                        for role in ("native", "ui", "lifecycle"):
                            payload = json.dumps(dict(revision=self.revision, workflowToken=token,
                                                      nativeStatus="PASS", policy=policy)).encode()
                            member = tarfile.TarInfo(f"files/real-music-evidence/{token}/{policy}/{role}-result.json")
                            member.size = len(payload)
                            archive.addfile(member, io.BytesIO(payload))
            if log and log.name == "independent-real-music.txt":
                (evidence / "independent-real-music.json").write_text('{"status":"PASS","exports":10}')
            if "tar" in words and log.name == "color-adjust-evidence.tar":
                records = [dict(test=test.split("#")[1], policy=policy, revision=self.revision, status="PASS")
                           for test in workflow.inventory(["ColorAdjustmentExportTest"])
                           for policy in ("default", "software")]
                if color_fault == "missing": records.pop()
                if color_fault == "stale": records[0]["revision"] = "b" * 40
                if color_fault == "failed": records[0]["status"] = "FAIL"
                if color_fault == "wrong-policy": records[0]["policy"] = "other"
                with tarfile.open(log, "w") as archive:
                    for record in records:
                        payload = json.dumps(record).encode()
                        member = tarfile.TarInfo("files/color-adjust-evidence/" + record["test"] + "-"
                                               + record["policy"] + "-result.json")
                        member.size = len(payload)
                        archive.addfile(member, io.BytesIO(payload))
            if any("run_report_crash_validation.py" in word for word in words):
                directory = Path(words[words.index("--evidence-dir") + 1])
                directory.mkdir()
                (directory / "summary.json").write_text("{}", encoding="utf-8")
                for cls, filename in (("LargeReportRecoveryTest", "native-large-fixture.txt"),
                                      ("SavedReportRecoveryTest", "native-recovery.txt")):
                    (directory / filename).write_text(fixtures.transcript(sorted(workflow.inventory([cls]))), encoding="utf-8")
            return subprocess.CompletedProcess(words, 0, b"", b"")

        def instrumentation(adb, serial, cls, timeout, stage, directory):
            if interrupt == "full" and stage == "suite":
                state["interrupted"] = True
                raise KeyboardInterrupt("inside full")
            parsed = workflow.oracle.parse_instrumentation(
                fixtures.transcript(sorted(workflow.oracle.expected_stage_tests(stage))), stage)
            return dict(timed_out=False, returncode=0, parsed=parsed)

        def capture(adb, serial, directory, stage, entry):
            if (interrupt == "capture" or (interrupt == "software-capture" and state["enabled"])) and stage == "suite":
                state["interrupted"] = True
                raise KeyboardInterrupt("inside capture")
            entry["artifacts"] = {}
            if stage != "suite":
                return
            policy = "software-avc" if state["enabled"] else "default"
            revision = "b" * 40 if stale == policy else self.revision
            recorded = "software-avc" if wrong_policy == policy else policy
            suite = fixtures.WatermarkSuiteCountTests.suite()
            suite["metadata"] = dict(source_revision=revision, installed_apk_sha256=self.apk_hash)
            files = {}
            for i, item in enumerate(suite["exports"]):
                item["report"] = f"export-{i}.json"
                files[item["report"]] = json.dumps({"run_metadata": {"source_revision": revision,
                                                                    "installed_apk_sha256": self.apk_hash}}).encode()
            files["suite.json"] = json.dumps(suite).encode()
            files["report.txt"] = (
                f"completed pass=edit encodingMode={recorded} selectedVideoBackend=c2.android.avc.encoder\n" * 17).encode()
            with tarfile.open(directory / "suite-app-data.tar", "w") as archive:
                for name, payload in files.items():
                    member = tarfile.TarInfo("files/selftest/new-run/" + name)
                    member.size = len(payload)
                    archive.addfile(member, io.BytesIO(payload))

        with contextlib.ExitStack() as stack:
            stack.enter_context(patch.object(workflow, "WORKFLOW_PROGRESS", self.directory / "progress.txt"))
            stack.enter_context(patch.object(workflow, "ASSURANCE_PROGRESS", self.directory / "assurance.txt"))
            stack.enter_context(patch.object(workflow, "configure_encoding", side_effect=config))
            stack.enter_context(patch.object(workflow.subprocess, "check_output", side_effect=output))
            stack.enter_context(patch.object(workflow.subprocess, "run", side_effect=direct_run))
            stack.enter_context(patch.object(workflow.oracle, "sha256", return_value=self.apk_hash))
            stack.enter_context(patch.object(workflow.oracle, "run_cmd", side_effect=command))
            stack.enter_context(patch.object(workflow.oracle, "capture_device_state",
                                            return_value={"ro.kernel.qemu": "1", "sys.boot_completed": "1"}))
            stop = stack.enter_context(patch.object(workflow.oracle, "force_stop", side_effect=stop_owned))
            stack.enter_context(patch.object(workflow.oracle, "suite_inventory", return_value=[]))
            stack.enter_context(patch.object(workflow.oracle, "run_instrumentation", side_effect=instrumentation))
            stack.enter_context(patch.object(workflow.oracle, "capture_artifacts", side_effect=capture))
            stack.enter_context(patch.object(workflow, "verify_color_exports", return_value={"status": "PASS"}))
            real_ui = stack.enter_context(patch.object(workflow, "run_ui_workflow",
                side_effect=ui_error, return_value={"status": "PASS", "fixtures_cleaned": True}))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            stack.enter_context(contextlib.redirect_stderr(io.StringIO()))
            code = workflow.main(["--serial", "emulator-5580", "--phase", phase, "--skip-build", "--skip-install",
                                  "--sdk", str(self.sdk),
                                  "--evidence-dir", str(evidence), "--protected-apk", str(self.directory / "protected.apk"),
                                  "--protected-receipt", str(self.directory / "protected.txt")])
        state["real_ui_calls"] = real_ui.call_count
        summary = json.loads((evidence / "summary.json").read_text())
        native = json.loads((evidence / "native-results.json").read_text())
        self.assertEqual(summary["status"], native["status"])
        self.assertEqual(self.revision, summary["revision"])
        self.assertEqual(summary["protected_before"], summary["protected_after"])
        self.assertIn("finished", summary)
        self.assertNotIn("RUNNING", [summary["status"]] + [s["status"] for s in summary["stages"].values()])
        return code, summary, state, stop

    def test_skip_build_full_matching_revision_and_explicit_policies_restore_original_on(self):
        code, summary, state, _ = self.run_workflow()
        self.assertEqual(0, code)
        self.assertEqual("PASS", summary["status"])
        self.assertEqual(["default"], summary["stages"]["suite"]["recorded_policies"])
        self.assertEqual(["software-avc"], summary["software"]["stages"]["suite"]["recorded_policies"])
        self.assertTrue(state["enabled"])
        self.assertTrue(summary["encoding_preference_restored"])
        self.assertEqual(["query", "default", "query", "default", "query",
                          "software-avc", "query", "software-avc", "query"], state["actions"])
        for key in ("revision", "head", "apks", "protected_before", "protected_after"):
            self.assertEqual(summary[key], summary["software"][key])

    def test_old_full_capture_cannot_pass_either_policy_or_rewrite_expected_revision(self):
        for policy in ("default", "software-avc"):
            with self.subTest(policy=policy):
                code, summary, state, _ = self.run_workflow(stale=policy)
                self.assertEqual(1, code)
                self.assertEqual("FAIL", summary["status"])
                selected = summary if policy == "default" else summary["software"]
                self.assertEqual("FAILED", selected["stages"]["suite"]["status"])
                self.assertEqual("b" * 40, selected["stages"]["suite"]["source_revision"])
                self.assertEqual(self.revision, selected["stages"]["suite"]["expected_revision"])
                self.assertTrue(state["enabled"])

    def test_default_label_cannot_disguise_second_software_run(self):
        code, summary, state, _ = self.run_workflow(wrong_policy="default")
        self.assertEqual(1, code)
        self.assertEqual("FAILED", summary["stages"]["suite"]["status"])
        self.assertTrue(state["enabled"])

    def test_lost_async_setting_write_is_rejected_before_native_groups(self):
        code, summary, state, _ = self.run_workflow(lost_write=True)
        self.assertEqual(1, code)
        self.assertEqual({}, summary["stages"])
        self.assertIn("did not persist", summary["error"])
        self.assertTrue(summary["encoding_preference_restored"])
        self.assertTrue(state["enabled"])

    def test_all_phases_reject_installed_old_revision_before_tests(self):
        for phase in ("full", "all", "features", "ui", "titles", "border", "music", "color"):
            with self.subTest(phase=phase):
                code, summary, state, _ = self.run_workflow(installed_revision="b" * 40, phase=phase)
                self.assertEqual(1, code)
                self.assertEqual("FAIL", summary["status"])
                self.assertEqual({}, summary["stages"])
                self.assertEqual("b" * 40, summary["installed_attestation"]["source_revision"])
                self.assertTrue(state["enabled"])

    def test_installed_hash_mismatch_still_captures_actual_revision_without_running_tests(self):
        code, summary, state, _ = self.run_workflow(installed_hash="d" * 64)
        self.assertEqual(1, code)
        self.assertEqual({}, summary["stages"])
        self.assertEqual(self.revision, summary["installed_attestation"]["source_revision"])
        self.assertEqual("d" * 64, summary["apks"][0]["sha256"])
        self.assertEqual(self.apk_hash, summary["apks"][0]["local_sha256"])
        self.assertTrue(state["enabled"])

    def test_interrupt_full_and_capture_terminal_cancel_stop_and_restore(self):
        for location in ("full", "capture"):
            with self.subTest(location=location):
                code, summary, state, stop = self.run_workflow(interrupt=location)
                self.assertEqual(130, code)
                self.assertEqual("CANCELLED", summary["status"])
                self.assertFalse(summary["passed"])
                self.assertTrue(summary["cancelled"])
                self.assertEqual("CANCELLED", summary["stages"]["suite"]["status"])
                self.assertTrue(summary["encoding_preference_restored"])
                self.assertTrue(state["enabled"])
                stop.assert_called_once()
                self.assertEqual("emulator-5580", stop.call_args.args[1])

    def test_cleanup_error_keeps_original_cancel_nonzero_and_records_failure(self):
        code, summary, _, _ = self.run_workflow(interrupt="capture", restore_error=True)
        self.assertEqual(130, code)
        self.assertEqual("inside capture", summary["error"])
        self.assertFalse(summary["encoding_preference_restored"])
        self.assertIn("restore failed", summary["cleanup_errors"][0])

    def test_restore_timeout_or_interrupt_stops_new_owned_instrumentation(self):
        for point in ("write", "query"):
            for interrupted in (False, True):
                error = KeyboardInterrupt("restore interrupted") if interrupted else subprocess.TimeoutExpired("restore", 30)
                with self.subTest(point=point, interrupted=interrupted):
                    code, summary, state, stop = self.run_workflow(restore_fault=(point, error))
                    self.assertEqual(130 if interrupted else 1, code)
                    self.assertEqual("CANCELLED" if interrupted else "FAIL", summary["status"])
                    self.assertFalse(summary["encoding_preference_restored"])
                    self.assertFalse(state["restore_active"])
                    self.assertTrue(summary["restore_instrumentation_stopped"])
                    stop.assert_called_once()
                    self.assertEqual("emulator-5580", stop.call_args.args[1])

    def test_interrupt_during_failure_capture_is_terminal_cancel_and_continues_cleanup(self):
        code, summary, state, stop = self.run_workflow(stale="default", interrupt="failure-capture")
        self.assertEqual(130, code)
        self.assertEqual("CANCELLED", summary["status"])
        self.assertTrue(summary["cancelled"])
        self.assertFalse(summary["passed"])
        self.assertTrue(summary["encoding_preference_restored"])
        self.assertTrue(state["enabled"])
        self.assertIn("failure evidence capture", str(summary["cleanup_errors"]))
        stop.assert_called_once()

    def test_software_and_feature_capture_interruptions_restore_original_on(self):
        for location, phase in (("software-capture", "full"), ("feature-capture", "titles")):
            with self.subTest(location=location):
                code, summary, state, _ = self.run_workflow(interrupt=location, phase=phase)
                self.assertEqual(130, code)
                self.assertEqual("CANCELLED", summary["status"])
                self.assertTrue(state["enabled"])
                self.assertTrue(summary["encoding_preference_restored"])
                if phase == "full":
                    self.assertEqual("CANCELLED", summary["software"]["status"])
                    self.assertEqual("CANCELLED", summary["software"]["stages"]["suite"]["status"])

    def test_interrupt_kills_only_owned_child_tree_with_bounded_wait(self):
        process = MagicMock(pid=12345, returncode=None)
        process.wait.side_effect = [KeyboardInterrupt("child"), None]
        process.poll.return_value = None
        with patch.object(workflow.oracle.subprocess, "Popen", return_value=process), \
                patch.object(workflow.oracle.subprocess, "run",
                             return_value=subprocess.CompletedProcess([], 0)) as kill, \
                patch.object(workflow.oracle, "progress"), \
                self.assertRaises(KeyboardInterrupt):
            workflow.oracle.run_cmd(["owned-worker"], timeout=20,
                                    stdout_path=self.directory / "stdout", stderr_path=self.directory / "stderr")
        process.kill.assert_called_once()
        self.assertEqual(10, process.wait.call_args.kwargs["timeout"])
        if workflow.os.name == "nt":
            self.assertEqual(["taskkill.exe", "/PID", "12345", "/T", "/F"], kill.call_args.args[0])
            self.assertEqual(10, kill.call_args.kwargs["timeout"])

    def test_native_bridge_rejects_test_apk_revision_mismatch(self):
        with self.assertRaises(RuntimeError):
            workflow.assert_revision(dict(source_revision=self.revision, test_source_revision="b" * 40), self.revision)

    def test_editor_preference_restore_mismatch_cannot_pass_even_when_encoder_restored(self):
        code, summary, _, stop = self.run_workflow(phase="color", preference_fault=True)
        self.assertEqual(1, code)
        self.assertEqual("FAIL", summary["status"])
        self.assertIn("preferences not restored", str(summary["cleanup_errors"]))
        self.assertNotIn("editor_preferences_restored", summary)
        stop.assert_called_once()

    def test_border_phase_requires_both_policies_exact_inventory_and_fresh_outputs(self):
        code, summary, state, _ = self.run_workflow(phase="border")
        self.assertEqual(0, code)
        for name in ("border-default", "border-software"):
            self.assertEqual("PASSED", summary["stages"][name]["status"])
            self.assertEqual(10, summary["stages"][name]["instrumentation"]["ok_tests"])
        self.assertEqual(12, len(summary["border_outputs"]))
        self.assertTrue(state["enabled"])
        for fault in ("missing", "stale", "failed"):
            with self.subTest(fault=fault):
                code, summary, state, _ = self.run_workflow(phase="border", border_fault=fault)
                self.assertEqual(1, code)
                self.assertEqual("FAIL", summary["status"])
                self.assertTrue(state["enabled"])

    def test_color_phase_both_policies_and_negative_evidence_guards(self):
        code, summary, state, *_ = self.run_workflow(phase="color")
        self.assertEqual(0, code)
        for policy in ("default", "software"):
            entry = summary["stages"]["color-" + policy]
            self.assertEqual("PASSED", entry["status"])
            self.assertEqual(12, entry["instrumentation"]["ok_tests"])
        self.assertEqual(8, len(summary["color_outputs"]))
        self.assertTrue(summary["encoding_preference_restored"])
        for fault in ("missing", "stale", "failed", "wrong-policy"):
            with self.subTest(fault=fault):
                code, summary, *_ = self.run_workflow(phase="color", color_fault=fault)
                self.assertNotEqual(0, code)
                self.assertEqual("FAIL", summary["status"])

    def test_music_phase_requires_synthetic_and_real_lifecycle_in_both_policies(self):
        code, summary, state, _ = self.run_workflow(phase="music")
        self.assertEqual(0, code)
        for name, count in (("music", 43), ("real-music", 4)):
            for policy in ("default", "software"):
                entry = summary["stages"][name + "-" + policy]
                self.assertEqual("PASSED", entry["status"])
                self.assertEqual(count, entry["instrumentation"]["ok_tests"])
        self.assertEqual(6, len(summary["real_music_inventory"]))
        self.assertEqual(10, summary["independent_real_music"]["exports"])
        self.assertTrue(state["enabled"])
        self.assertTrue(summary["encoding_preference_restored"])

    def test_ui_phase_requires_all_36_native_tests_and_real_flow(self):
        code, summary, state, _ = self.run_workflow(phase="ui")
        self.assertEqual(0, code)
        self.assertEqual(36, len(summary["stages"]["features"]["expected"]))
        self.assertEqual(36, summary["stages"]["features"]["instrumentation"]["ok_tests"])
        for method in (
                "SelectedFramePreviewTest#testIndependentTitleAppearanceNativeControlsAndExports",
                "FixedEditorUiTest#testTitleDraftAndAppearanceSurviveLocaleRecreation"):
            self.assertIn("com.simple.videoeditor." + method,
                          summary["stages"]["features"]["expected"])
        self.assertEqual("PASSED", summary["stages"]["ui-workflow"]["status"])
        self.assertEqual(1, state["real_ui_calls"])
        self.assertTrue(state["enabled"])
        self.assertTrue(summary["encoding_preference_restored"])

    def test_ui_host_is_scoped_recovery_not_native_or_all_gate(self):
        code, summary, state, _ = self.run_workflow(phase="ui-host")
        self.assertEqual(0, code)
        self.assertEqual({"ui-workflow"}, set(summary["stages"]))
        self.assertEqual("ui-host", summary["phase"])
        self.assertEqual(1, state["real_ui_calls"])
        self.assertTrue(summary["editor_preferences_restored"])
        self.assertEqual("PASSED", summary["stages"]["ui-workflow"]["status"])
        self.assertEqual(1, state["real_ui_calls"])
        self.assertTrue(state["enabled"])
        self.assertTrue(summary["encoding_preference_restored"])

    def test_missing_fixed_native_inventory_cannot_be_masked_by_real_flow_success(self):
        code, summary, _, _ = self.run_workflow(phase="ui", omit_fixed=True)
        self.assertEqual(1, code)
        self.assertEqual("FAILED", summary["stages"]["features"]["status"])
        self.assertIn("FixedEditorUiTest", str(summary["validation_errors"]))

    def test_real_ui_failure_and_interrupt_restore_original_encoding_and_terminalize(self):
        for error in (RuntimeError("pixel mismatch"), KeyboardInterrupt("inside real UI")):
            with self.subTest(error=type(error).__name__):
                code, summary, state, stop = self.run_workflow(phase="ui", ui_error=error)
                interrupted = isinstance(error, KeyboardInterrupt)
                self.assertEqual(130 if interrupted else 1, code)
                self.assertEqual("CANCELLED" if interrupted else "FAILED",
                                 summary["stages"]["ui-workflow"]["status"])
                self.assertTrue(summary["encoding_preference_restored"])
                self.assertTrue(state["enabled"])
                stop.assert_called_once()

    def test_native_bridge_requires_complete_test_token_and_recorded_policy(self):
        token = "fixed-token"
        record = dict(token=token, action="default", before=True, after=False, policy="default",
                      source_revision=self.revision, test_source_revision=self.revision)
        for mutation in ("none", "token", "policy", "terminal", "missing"):
            changed = dict(record)
            if mutation == "token": changed["token"] = "old-token"
            if mutation == "policy": changed["policy"] = "software-avc"
            raw = ("INSTRUMENTATION_STATUS: workflow=" + json.dumps(changed)
                   + "\nINSTRUMENTATION_STATUS_CODE: 2\n"
                   + fixtures.transcript([workflow.CONFIG_TEST]))
            if mutation == "terminal": raw = raw.replace("INSTRUMENTATION_CODE: -1", "INSTRUMENTATION_CODE: 0")
            if mutation == "missing": raw = fixtures.transcript([workflow.CONFIG_TEST])
            with self.subTest(mutation=mutation), \
                    patch.object(workflow.uuid, "uuid4", return_value=MagicMock(hex=token)), \
                    patch.object(workflow.oracle, "run_cmd",
                                 return_value=subprocess.CompletedProcess([], 0, raw.encode(), b"")):
                if mutation == "none":
                    self.assertEqual(record, workflow.configure_encoding(Path("adb.exe"), "emulator-5580",
                                                                         self.directory, "default"))
                else:
                    with self.assertRaises(RuntimeError):
                        workflow.configure_encoding(Path("adb.exe"), "emulator-5580", self.directory, "default")


class FixedUiWorkflowTests(unittest.TestCase):
    def setUp(self):
        self.directory = workflow.ROOT / "app" / "build" / ("fixed-ui-host-" + uuid.uuid4().hex)
        self.directory.mkdir(parents=True)
        self.addCleanup(shutil.rmtree, self.directory)
        self.ui = workflow._EditorUiWorkflow(Path("adb.exe"), "emulator-5580",
                                             self.directory, "/sdcard/owned-ui.xml")
        self.original_evidence = workflow.oracle.ACTIVE_EVIDENCE
        self.original_cancel = workflow.oracle.CANCEL_FILE
        self.addCleanup(setattr, workflow.oracle, "ACTIVE_EVIDENCE", self.original_evidence)
        self.addCleanup(setattr, workflow.oracle, "CANCEL_FILE", self.original_cancel)
        self.progress = patch.object(workflow, "_ui_progress")
        self.progress.start()
        self.addCleanup(self.progress.stop)

    @staticmethod
    def node(resource_id, parent=None, **attrs):
        if ":id/" not in resource_id:
            resource_id = workflow.PACKAGE + ":id/" + resource_id
        node = ET.Element("node", {"resource-id": resource_id, "enabled": "true",
                                  "bounds": "[0,0][400,600]", **attrs})
        if parent is not None:
            parent.append(node)
        return node

    def test_fixed_controls_require_correct_owner_without_any_swipe(self):
        tree = ET.Element("hierarchy")
        header = self.node("editorPreviewHeader", tree)
        button = self.node("btnSelectVideo", header)
        dock = self.node("editorActionDock", tree)
        self.node("btnProcess", dock)
        with patch.object(self.ui, "screen", return_value=tree), patch.object(self.ui, "shell") as shell:
            self.assertIs(button, self.ui.fixed("btnSelectVideo", "editorPreviewHeader"))
            with self.assertRaisesRegex(RuntimeError, "must belong"):
                self.ui.fixed("btnSelectVideo", "editorActionDock")
            shell.assert_not_called()

    def test_import_music_status_uses_current_cn_en_resources_and_rejects_stale_state(self):
        for resource in ("editor_music_ready", "editor_music_original"):
            for text in workflow.localized_strings(resource):
                node = self.node("tvSelectedMusic", text=text + "\nmore details")
                with patch.object(self.ui, "panel_control", return_value=node):
                    self.ui.music_status(resource)
                    other = "editor_music_ready" if resource.endswith("original") else "editor_music_original"
                    with self.assertRaises(RuntimeError):
                        self.ui.music_status(other)

    def test_real_music_archive_requires_current_token_revision_and_exact_policies(self):
        revision, token = "a" * 40, "owned-token"
        for fault in ("none", "stale", "token", "missing", "duplicate", "failed", "traversal", "drive"):
            path = self.directory / (fault + ".tar")
            records = [(name, policy) for name in ("native", "ui", "lifecycle")
                       for policy in ("default", "software-avc")]
            if fault == "missing": records.pop()
            if fault == "duplicate": records.append(records[0])
            with tarfile.open(path, "w") as archive:
                for index, (name, policy) in enumerate(records):
                    record = dict(revision="b" * 40 if fault == "stale" else revision,
                                  workflowToken="old" if fault == "token" else token,
                                  nativeStatus="FAIL" if fault == "failed" else "PASS", policy=policy)
                    payload = json.dumps(record).encode()
                    folder = "../escape" if fault == "traversal" else "C:/escape" if fault == "drive" else str(index)
                    member = tarfile.TarInfo(f"files/real-music-evidence/{token}/{folder}/{name}-result.json")
                    member.size = len(payload)
                    archive.addfile(member, io.BytesIO(payload))
            with self.subTest(fault=fault):
                if fault == "none":
                    self.assertEqual(6, len(workflow.extract_real_music(
                        path, self.directory / fault, revision, token)))
                else:
                    with self.assertRaises(RuntimeError):
                        workflow.extract_real_music(path, self.directory / fault, revision, token)

    def test_all_five_tabs_use_structural_ids_not_translated_labels(self):
        for labels in (("Picture", "Sound", "Title", "Assets", "More"),
                       ("画面", "声音", "片头", "素材", "更多")):
            tree = ET.Element("hierarchy")
            tabs = self.node("editorTabs", tree)
            for index, label in enumerate(labels):
                self.node("tab", tabs, **{"class": "android.app.ActionBar$Tab",
                                         "index": str(index), "text": label})
                self.node(self.ui.PANELS[index], tree)
            with patch.object(self.ui, "screen", return_value=tree), \
                    patch.object(self.ui, "wait") as wait, patch.object(self.ui, "tap") as tap:
                for index, panel in enumerate(self.ui.PANELS):
                    self.ui.select_panel(panel)
                    self.assertEqual(str(index), tap.call_args.args[0].get("index"))
                    self.assertTrue(wait.call_args.args[0](tree))

    def test_panel_hunt_is_bounded_and_never_swipes_header_tabs_or_dock(self):
        tree = ET.Element("hierarchy")
        self.node("panelAssets", tree, bounds="[20,200][380,450]")
        with patch.object(self.ui, "select_panel") as select, \
                patch.object(self.ui, "screen", return_value=tree), \
                patch.object(self.ui, "shell") as shell:
            with self.assertRaisesRegex(RuntimeError, "Missing control in selected panelAssets"):
                self.ui.panel_control("panelAssets", "etWatermarkY")
        select.assert_called_once_with("panelAssets")
        self.assertEqual(2, shell.call_count)
        for call in shell.call_args_list:
            self.assertEqual(("input", "swipe"), call.args[:2])
            x1, y1, x2, y2 = map(int, call.args[2:6])
            self.assertTrue(20 < x1 == x2 < 380)
            self.assertTrue(200 < y1 < 450 and 200 < y2 < 450)

    def test_selected_material_tab_is_nonclickable_but_still_a_structural_peer(self):
        tree = ET.Element("hierarchy")
        tabs = self.node("editorTabs", tree)
        strip = self.node("strip", tabs, **{"class": "android.widget.LinearLayout", "index": "0"})
        for index, panel in enumerate(self.ui.PANELS):
            self.node("tab", strip, **{"class": "android.widget.LinearLayout", "index": str(index),
                                      "clickable": "false" if index == 0 else "true", "focusable": "true",
                                      "selected": "true" if index == 0 else "false"})
        self.node("panelPicture", tree)
        with patch.object(self.ui, "screen", return_value=tree), patch.object(self.ui, "tap") as tap, \
                patch.object(self.ui, "wait") as wait:
            self.ui.select_panel("panelPicture")
        tap.assert_not_called()
        wait.assert_not_called()

    def picker_tree(self, package, filename=None):
        tree = ET.Element("hierarchy")
        directory = self.node(package + ":id/dir_list", tree, package=package)
        if filename:
            self.node("android:id/title", directory, text=filename, package=package)
        toolbar = self.node(package + ":id/toolbar", tree, package=package)
        self.node("android:id/home", toolbar,
                  **{"class": "android.widget.ImageButton", "clickable": "true", "package": package})
        self.node(package + ":id/option_menu_search", tree, package=package)
        return tree

    def test_real_picker_uses_provider_localized_resource_and_stable_search_ids(self):
        for package, title, compressed in (("com.android.documentsui", "Downloads", False),
                                           ("com.google.android.documentsui", "下载", False),
                                           ("com.android.documentsui", "Downloads", True),
                                           ("com.google.android.documentsui", "下载", True)):
            with self.subTest(package=package):
                filename = "editor-ui-owned-source.mp4"
                initial = self.picker_tree(package)
                if compressed:
                    toolbar = self.ui.find(initial, package + ":id/toolbar")
                    initial.extend(list(toolbar))
                    initial.remove(toolbar)
                roots = ET.Element("hierarchy")
                root_list = self.node(package + ":id/roots_list", roots, package=package)
                root = self.node("android:id/title", root_list, text=title)
                search = self.picker_tree(package)
                self.node(package + ":id/search_src_text", search)
                found = self.picker_tree(package, filename)
                stages = iter((initial, roots, initial, search, found))
                def wait(predicate, label, *args):
                    tree = next(stages)
                    self.assertTrue(predicate(tree), label)
                    return tree
                with patch.object(self.ui, "wait", side_effect=wait), \
                        patch.object(self.ui, "screen", return_value=ET.Element("hierarchy")), \
                        patch.object(self.ui, "shell", side_effect=lambda *args:
                                     "0" if args == ("am", "get-current-user") else title) as shell, \
                        patch.object(self.ui, "tap") as tap, patch.object(self.ui, "ready"):
                    self.ui.pick(filename, "fixture")
                self.assertIn(root, [call.args[0] for call in tap.call_args_list])
                shell.assert_any_call("am", "get-current-user")
                shell.assert_any_call("cmd", "overlay", "lookup", "--user", "0",
                                      "com.android.providers.downloads",
                                      "com.android.providers.downloads:string/root_downloads")
                self.assertNotIn("query", [word for call in shell.call_args_list for word in call.args])
                shell.assert_any_call("input", "text", filename)

    def test_compressed_picker_rejects_ambiguous_roots_buttons(self):
        package = "com.android.documentsui"
        tree = self.picker_tree(package)
        toolbar = self.ui.find(tree, package + ":id/toolbar")
        tree.extend(list(toolbar))
        tree.remove(toolbar)
        self.node("android:id/other", tree, **{
            "class": "android.widget.ImageButton", "clickable": "true", "package": package})
        with patch.object(self.ui, "wait", return_value=tree), \
                patch.object(self.ui, "shell", side_effect=lambda *args:
                             "0" if args == ("am", "get-current-user") else "Downloads"), \
                self.assertRaisesRegex(RuntimeError, "uniquely identify"):
            self.ui.pick("owned.mp4", "ambiguous-roots")

    def test_picker_rejects_unresolved_android_user_without_guessing_provider_language(self):
        tree = self.picker_tree("com.android.documentsui")
        with patch.object(self.ui, "wait", return_value=tree), \
                patch.object(self.ui, "shell", return_value="current") as shell, \
                self.assertRaisesRegex(RuntimeError, "active Android user"):
            self.ui.pick("owned.mp4", "invalid-user")
        shell.assert_called_once_with("am", "get-current-user")

    def test_picker_rejects_duplicate_exact_fixture_names(self):
        package = "com.android.documentsui"
        tree = self.picker_tree(package, "owned.mp4")
        self.node("android:id/title", self.ui.find(tree, package + ":id/dir_list"),
                  text="owned.mp4")
        with patch.object(self.ui, "wait", return_value=tree), \
                self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            self.ui.pick("owned.mp4", "ambiguous")

    def test_screen_retains_hierarchy_and_png_through_bounded_owned_adb(self):
        def command(words, **kwargs):
            payload = b"<hierarchy/>" if "cat" in words else b"PNG"
            self.assertEqual(["adb.exe", "-s", "emulator-5580"], words[:3])
            self.assertGreater(kwargs["timeout"], 0)
            self.assertLessEqual(kwargs["timeout"], 30)
            return subprocess.CompletedProcess(words, 0, payload, b"")
        with patch.object(workflow.oracle, "run_cmd", side_effect=command) as run:
            self.assertEqual("hierarchy", self.ui.screen("retained").tag)
        self.assertEqual(4, run.call_count)
        self.assertEqual(b"<hierarchy/>", (self.directory / "retained.xml").read_bytes())
        self.assertEqual(b"PNG", (self.directory / "retained.png").read_bytes())

    def test_picker_capture_filters_null_unimportant_children_only_during_picker(self):
        def picker(filename, label):
            with self.capture_commands([(1., 0, b"dumped", b"")]) as (run, _):
                self.ui.screen(label)
            dumps = [call.args[0] for call in run.call_args_list if "uiautomator" in call.args[0]]
            self.assertEqual(["shell", "uiautomator", "dump", "--compressed", self.ui.remote_xml],
                             dumps[0][3:])
        with patch.object(self.ui, "_pick", side_effect=picker):
            self.ui.pick("owned.mp4", "compressed-picker")
        self.assertFalse(self.ui._picker_capture)
        with self.capture_commands([(1., 0, b"dumped", b"")]) as (run, _):
            self.ui.screen("full-editor")
        self.assertFalse(any("--compressed" in call.args[0] for call in run.call_args_list))

    def test_picker_failure_and_cancellation_restore_full_editor_capture(self):
        for error in (RuntimeError("picker failed"), KeyboardInterrupt("cancelled")):
            with self.subTest(error=error), patch.object(self.ui, "_pick", side_effect=error), \
                    self.assertRaises(type(error)):
                self.ui.pick("owned.mp4", "failed-picker")
            self.assertFalse(self.ui._picker_capture)

    @contextlib.contextmanager
    def capture_commands(self, dumps, *, xml=b"<hierarchy/>"):
        clock = [100.]
        outcomes = iter(dumps)

        def command(words, **kwargs):
            self.assertEqual(["adb.exe", "-s", "emulator-5580"], words[:3])
            self.assertGreater(kwargs["timeout"], 0)
            self.assertLessEqual(kwargs["timeout"], 30)
            elapsed, code, stdout, stderr = .1, 0, b"", b""
            if "uiautomator" in words:
                outcome = next(outcomes)
                if isinstance(outcome, BaseException):
                    raise outcome
                elapsed, code, stdout, stderr = outcome
            elif "cat" in words:
                stdout = xml
            elif "screencap" in words:
                stdout = b"PNG"
            clock[0] += elapsed
            return subprocess.CompletedProcess(words, code, stdout, stderr)

        def sleep(seconds):
            clock[0] += seconds

        with patch.object(workflow.oracle, "run_cmd", side_effect=command) as run, \
                patch.object(workflow.time, "monotonic", side_effect=lambda: clock[0]), \
                patch.object(workflow.time, "sleep", side_effect=sleep):
            yield run, clock

    def test_wait_labels_the_matching_capture_without_a_second_dump(self):
        xml = ET.tostring(self.picker_tree("com.android.documentsui", "owned.mp4"))
        with self.capture_commands([(3.4, 0, b"UI hierchary dumped to: owned", b"")], xml=xml) as (run, _):
            tree = self.ui.wait(lambda ui: self.ui.find(ui, "com.android.documentsui:id/dir_list") is not None,
                                "02-video-picker")
        self.assertEqual("hierarchy", tree.tag)
        self.assertEqual(1, sum("uiautomator" in call.args[0] for call in run.call_args_list))
        for suffix in (".xml", ".png"):
            self.assertEqual((self.directory / ("ui-0001" + suffix)).read_bytes(),
                             (self.directory / ("02-video-picker" + suffix)).read_bytes())

    def test_early_silent_137_restarts_once_with_fresh_xml_and_persisted_evidence(self):
        with self.capture_commands([(1.6, 137, b"", b""), (2.8, 0, b"dumped", b"")]) as (run, _):
            self.assertEqual("hierarchy", self.ui.screen("recovered").tag)
        commands = [call.args[0][3:] for call in run.call_args_list]
        self.assertEqual([["shell", "rm", "-f", self.ui.remote_xml],
                          ["shell", "uiautomator", "dump", self.ui.remote_xml]] * 2, commands[:4])
        self.assertEqual(["exec-out", "cat", self.ui.remote_xml], commands[4])
        self.assertEqual(6, len(commands))
        record = json.loads((self.directory / "recovered-dump-1-failure.json").read_text())
        self.assertEqual(137, record["returncode"])
        self.assertTrue(record["restart"])
        self.assertAlmostEqual(1.6, record["elapsed"])
        self.assertEqual(b"PNG", (self.directory / "recovered.png").read_bytes())
        self.assertTrue(self.ui._dump_restart_used)

    def test_repeated_kills_fail_without_reading_stale_xml(self):
        with self.capture_commands([(1.6, 137, b"", b"")] * 2) as (run, _), \
                self.assertRaisesRegex(RuntimeError, "exit 137"):
            self.ui.screen("killed")
        self.assertEqual(4, run.call_count)
        self.assertFalse((self.directory / "killed.xml").exists())
        self.assertFalse((self.directory / "killed.png").exists())
        self.assertFalse(json.loads((self.directory / "killed-dump-2-failure.json").read_text())["restart"])

    def test_exit_zero_null_root_stderr_is_not_success_and_recaptures_once(self):
        error = b"ERROR: null root node returned by UiTestAutomationBridge.\n"
        with self.capture_commands([(1., 0, b"", error), (1., 0, b"dumped", b"")]) as (run, _):
            self.assertEqual("hierarchy", self.ui.screen("null-root").tag)
        record = json.loads((self.directory / "null-root-dump-1-failure.json").read_text())
        self.assertTrue(record["null_root"])
        self.assertTrue(record["restart"])
        self.assertEqual(error.decode(), record["stderr"])
        self.assertEqual(6, run.call_count)

    def test_real_capture_records_screen_and_resources_before_sole_retry(self):
        self.ui.failure_diagnostics = True
        error = b"ERROR: null root node returned by UiTestAutomationBridge.\n"
        with self.capture_commands([(1., 0, b"", error), (1., 0, b"dumped", b"")]) as (run, _):
            self.ui.screen("diagnosed")
        calls = [call.args[0] for call in run.call_args_list]
        dumps = [i for i, words in enumerate(calls) if "uiautomator" in words]
        self.assertEqual(2, len(dumps))
        self.assertTrue(any("screencap" in words for words in calls[dumps[0] + 1:dumps[1]]))
        record = json.loads((self.directory / "diagnosed-dump-1-failure.json").read_text())
        self.assertEqual("UIAUTOMATOR_INFRASTRUCTURE", record["category"])
        self.assertEqual(5, len(record["diagnostics"]))
        self.assertEqual(b"PNG", Path(record["diagnostics"]["screen.png"]).read_bytes())

    def test_repeated_null_root_stderr_fails_without_accepting_stale_xml(self):
        error = b"ERROR: null root node returned by UiTestAutomationBridge.\n"
        with self.capture_commands([(1., 0, b"", error)] * 2) as (run, _), \
                self.assertRaisesRegex(RuntimeError, "null root"):
            self.ui.screen("null-root-terminal")
        self.assertEqual(4, run.call_count)
        self.assertFalse((self.directory / "null-root-terminal.xml").exists())
        self.assertFalse(json.loads(
            (self.directory / "null-root-terminal-dump-2-failure.json").read_text())["restart"])

    def test_other_exit_zero_stderr_errors_are_terminal(self):
        with self.capture_commands([(1., 0, b"", b"ERROR: could not get idle state.")]) as (run, _), \
                self.assertRaisesRegex(RuntimeError, "idle state"):
            self.ui.screen("stderr-idle")
        self.assertEqual(2, run.call_count)
        self.assertFalse(self.ui._dump_restart_used)

    def test_restart_allowance_is_not_reset_by_another_screen_or_wait(self):
        with self.capture_commands([(1.6, 137, b"", b""), (2.8, 0, b"", b""),
                                    (1.6, 137, b"", b"")]) as (run, _):
            self.ui.screen("first")
            with self.assertRaisesRegex(RuntimeError, "exit 137"):
                self.ui.wait(lambda ui: True, "second")
        self.assertEqual(3, sum("uiautomator" in call.args[0] for call in run.call_args_list))
        self.assertFalse((self.directory / "second.xml").exists())

    def test_idle_errors_bridge_errors_and_slow_or_non_silent_kills_are_not_retried(self):
        outcomes = [(10., 1, b"ERROR: could not get idle state.", b""),
                    (1., 1, b"", b"UiAutomation not connected"),
                    (1.6, 137, b"ERROR: could not get idle state.", b""),
                    (1.6, 137, b"", b"Killed"),
                    (5., 137, b"", b""),
                    (1., 1, b"", b""),
                    (10., 0, b"ERROR: could not get idle state.", b"")]
        for index, outcome in enumerate(outcomes):
            with self.subTest(outcome=outcome), self.capture_commands([outcome]) as (run, _), \
                    self.assertRaises(RuntimeError):
                self.ui.screen("terminal-" + str(index))
            self.assertEqual(2, run.call_count)
            self.assertFalse(self.ui._dump_restart_used)

    def test_command_failure_reports_exit_code_and_both_streams(self):
        with self.capture_commands([(1., 7, b"stdout reason", b"stderr reason")]), \
                self.assertRaises(workflow._UiCommandError) as caught:
            self.ui.screen("diagnostic")
        for text in ("exit 7", "stdout reason", "stderr reason", "uiautomator", "emulator-5580"):
            self.assertIn(text, str(caught.exception))

    def test_timeout_and_interrupt_propagate_without_starting_another_bridge(self):
        for error in (subprocess.TimeoutExpired("uiautomator", 30), KeyboardInterrupt("cancelled")):
            with self.subTest(error=error), self.capture_commands([error]) as (run, _), \
                    self.assertRaises(type(error)) as caught:
                self.ui.screen("interrupted")
            self.assertIs(error, caught.exception)
            self.assertEqual(2, run.call_count)
            self.assertFalse(self.ui._dump_restart_used)

    def test_cancellation_between_dump_attempts_prevents_restart(self):
        cancel = self.directory / "cancel"
        workflow.oracle.CANCEL_FILE = cancel
        with self.capture_commands([(1.6, 137, b"", b"")]) as (run, _), \
                patch.object(workflow.time, "sleep", side_effect=lambda seconds: cancel.touch()), \
                self.assertRaisesRegex(KeyboardInterrupt, "cancellation requested"):
            self.ui.screen("cancelled")
        self.assertEqual(2, run.call_count)

    def test_wait_and_capture_commands_share_one_deadline_even_after_restart(self):
        with self.capture_commands([(1.6, 137, b"", b""), (1., 0, b"", b"")]) as (run, _):
            self.ui.wait(lambda ui: True, "bounded", timeout=4)
        timeouts = [call.kwargs["timeout"] for call in run.call_args_list]
        self.assertEqual(4, timeouts[0])
        self.assertTrue(all(after < before for before, after in zip(timeouts, timeouts[1:])))
        self.assertLess(timeouts[-1], 1)

    def test_exhausted_wait_budget_cannot_start_another_dump_or_claim_success(self):
        with self.capture_commands([(1.6, 137, b"", b"")]) as (run, _), \
                self.assertRaisesRegex(RuntimeError, "exit 137"):
            self.ui.wait(lambda ui: True, "exhausted", timeout=1.8)
        self.assertEqual(2, run.call_count)
        self.assertFalse((self.directory / "exhausted.png").exists())
        with self.capture_commands([(1., 0, b"", b"")]) as (run, _), \
                self.assertRaisesRegex(RuntimeError, "deadline exceeded"):
            self.ui.wait(lambda ui: True, "over-budget", timeout=1.25)
        self.assertEqual(4, run.call_count)
        self.assertFalse((self.directory / "over-budget.png").exists())

    def test_invalid_hierarchy_is_retained_but_never_retried_or_screenshotted(self):
        for xml, error in ((b"<hierarchy", ET.ParseError), (b"<not-hierarchy/>", RuntimeError)):
            with self.subTest(xml=xml), self.capture_commands([(1., 0, b"", b"")], xml=xml) as (run, _), \
                    self.assertRaises(error):
                self.ui.screen("invalid")
            self.assertEqual(3, run.call_count)
            self.assertEqual(xml, (self.directory / "invalid.xml").read_bytes())
            self.assertFalse((self.directory / "invalid.png").exists())

    def test_fixture_cleanup_preserves_interrupt_and_restores_cancellation_context(self):
        cancel = self.directory / "cancel"
        workflow.oracle.CANCEL_FILE = cancel
        for cleanup_fails in (False, True):
            with self.subTest(cleanup_fails=cleanup_fails):
                evidence = self.directory / ("interrupt-" + str(cleanup_fails))
                ui = MagicMock()
                ui.command.side_effect = KeyboardInterrupt("owned push interrupted")
                def shell(*args):
                    self.assertIsNone(workflow.oracle.CANCEL_FILE)
                    if cleanup_fails:
                        raise RuntimeError("cleanup failed")
                ui.shell.side_effect = shell
                with patch.object(workflow, "_EditorUiWorkflow", return_value=ui), \
                        patch.object(workflow, "compile_ui_oracle"), \
                        self.assertRaisesRegex(KeyboardInterrupt, "owned push interrupted") as caught:
                    workflow.run_ui_workflow(Path("adb.exe"), "emulator-5580", evidence)
                self.assertIs(cancel, workflow.oracle.CANCEL_FILE)
                self.assertIs(self.original_evidence, workflow.oracle.ACTIVE_EVIDENCE)
                self.assertFalse((evidence / "summary.json").exists())
                cleanup = ui.shell.call_args.args
                self.assertEqual(("rm", "-f"), cleanup[:2])
                self.assertEqual(6, len(cleanup))
                self.assertTrue(cleanup[-1].endswith("-music.wav"))
                self.assertTrue(all("editor-ui-" in path for path in cleanup[2:]))
                if cleanup_fails:
                    self.assertIn("cleanup failed", caught.exception.cleanup_errors[0])

    def test_cleanup_interrupt_after_ui_failure_remains_cancellation_with_original_diagnostic(self):
        evidence = self.directory / "cleanup-cancelled"
        cancel = self.directory / "cancel"
        workflow.oracle.CANCEL_FILE = cancel
        original = RuntimeError("owned push failed")
        original.cleanup_errors = ["earlier cleanup detail"]
        interrupted = KeyboardInterrupt("fixture cleanup interrupted")
        interrupted.cleanup_errors = ["interrupted child cleanup detail"]
        ui = MagicMock()
        ui.command.side_effect = original
        ui.shell.side_effect = interrupted
        with patch.object(workflow, "_EditorUiWorkflow", return_value=ui), \
                patch.object(workflow, "compile_ui_oracle"), \
                self.assertRaises(KeyboardInterrupt) as caught:
            workflow.run_ui_workflow(Path("adb.exe"), "emulator-5580", evidence)
        self.assertIs(interrupted, caught.exception)
        self.assertIs(original, caught.exception.__cause__)
        self.assertIn("earlier cleanup detail", caught.exception.cleanup_errors)
        self.assertIn("interrupted child cleanup detail", caught.exception.cleanup_errors)
        self.assertIn("owned push failed", caught.exception.cleanup_errors[-1])
        self.assertIs(cancel, workflow.oracle.CANCEL_FILE)
        self.assertIs(self.original_evidence, workflow.oracle.ACTIVE_EVIDENCE)
        self.assertFalse((evidence / "summary.json").exists())

    def run_real_flow(self, *, different_bytes=False, stale_clear=False):
        evidence = self.directory / ("flow-" + uuid.uuid4().hex)
        ui = MagicMock()
        ui.find.side_effect = workflow._EditorUiWorkflow.find
        ui.bounds.side_effect = workflow._EditorUiWorkflow.bounds
        baseline = ET.Element("hierarchy")
        self.node("ivVideoThumbnail", baseline)
        completed = ET.fromstring(ET.tostring(baseline))
        self.node("tvOutputPath", completed, text="edited_owned.mp4")
        self.node("btnProcess", completed)
        self.node("btnSelectVideo", completed)
        uri = "content://media/external/video/media/123"
        private = "/data/user/0/" + workflow.PACKAGE + "/files/exports/edited_owned.mp4"
        journal = ET.Element("map")
        for key, value in (("uri", uri), ("name", "edited_owned.mp4"), ("private", private)):
            ET.SubElement(journal, "string", name=key).text = value
        def command(*args, **kwargs):
            if "published-video.xml" in args[-1]:
                return ET.tostring(journal)
            return b"private" if different_bytes and args[-1] == private else b"export"
        ui.command.side_effect = command
        ui.panel_control.side_effect = lambda panel, resource_id: self.node(resource_id, checked="false")
        ui.screen.return_value = baseline
        def wait(predicate, label, *args):
            self.assertTrue(predicate(completed), label)
            return completed
        ui.wait.side_effect = wait
        ui.output_location.return_value = uri
        ui.open_or_share.return_value = {"uri": uri}
        env = {"JAVA_HOME": "owned-jdk"}
        with patch.object(workflow, "_EditorUiWorkflow", return_value=ui), \
                patch.object(workflow, "compile_ui_oracle") as compile_oracle, \
                patch.object(workflow, "verify_preview") as preview, \
                patch.object(workflow, "verify_output", return_value={"status": "PASS"}) as output, \
                patch.object(workflow, "screenshot_rgb",
                             side_effect=[b"\x80\x80\x80", b"\x81\x80\x80" if stale_clear else b"\x80\x80\x80"]):
            result = workflow.run_ui_workflow(Path("adb.exe"), "emulator-5580", evidence, env=env)
        preview.assert_called_once_with(evidence, baseline, baseline)
        compile_oracle.assert_called_once_with(evidence, env=env)
        output.assert_called_once_with(evidence / "user-export.mp4", evidence, env=env)
        self.assertEqual(result, json.loads((evidence / "summary.json").read_text()))
        self.assertEqual(b"export", (evidence / "user-export.mp4").read_bytes())
        self.assertEqual([(False, uri), (True, uri)],
                         [call.args for call in ui.open_or_share.call_args_list])
        self.assertEqual(("rm", "-f"), ui.shell.call_args.args[:2])
        return result

    def test_real_flow_requires_provider_bytes_pixels_and_both_external_actions(self):
        result = self.run_real_flow()
        self.assertEqual("PASS", result["status"])
        self.assertTrue(result["fixtures_cleaned"])

    def test_real_flow_rejects_public_private_byte_mismatch(self):
        with self.assertRaisesRegex(RuntimeError, "provider bytes"):
            self.run_real_flow(different_bytes=True)

    def test_real_flow_rejects_stale_cleared_preview(self):
        with self.assertRaisesRegex(RuntimeError, "baseline preview pixels"):
            self.run_real_flow(stale_clear=True)

    def test_frozen_output_requires_exact_full_sparse_report_agreement(self):
        for mode, report in (("full", {"status": "PASS", "pixels": 5}),
                             ("sparse", {"status": "PASS", "pixels": 5})):
            (self.directory / ("user-export-" + mode + ".json")).write_text(json.dumps(report))
        with patch.object(Path, "is_file", return_value=True), \
                patch.object(workflow.oracle, "run_cmd",
                             return_value=subprocess.CompletedProcess([], 0)) as run:
            self.assertEqual(report, workflow.verify_output(self.directory / "video.mp4", self.directory))
            self.assertEqual(180, run.call_args.kwargs["timeout"])
            (self.directory / "user-export-sparse.json").write_text('{"status":"PASS","pixels":6}')
            with self.assertRaisesRegex(RuntimeError, "disagree"):
                workflow.verify_output(self.directory / "video.mp4", self.directory)

    def test_oracle_compiles_current_sources_into_fresh_run_directory(self):
        env = {"JAVA_HOME": "owned-jdk"}
        with patch.object(workflow.oracle, "run_cmd",
                          return_value=subprocess.CompletedProcess([], 0)) as run:
            classes = workflow.compile_ui_oracle(self.directory, env=env)
            words = run.call_args.args[0]
            self.assertEqual(str(Path("owned-jdk") / r"bin\javac.exe"), words[0])
            self.assertEqual(str(classes), words[words.index("-d") + 1])
            self.assertEqual(13, len([word for word in words if word.endswith(".java")]))
            self.assertTrue(all(Path(word).is_file() for word in words if word.endswith(".java")))
            self.assertEqual(120, run.call_args.kwargs["timeout"])
            self.assertEqual(env, run.call_args.kwargs["env"])
            with self.assertRaises(FileExistsError):
                workflow.compile_ui_oracle(self.directory, env=env)
            self.assertEqual(1, run.call_count, "Existing classes must not be silently reused")

    def test_oracle_compile_failure_never_enters_picker_or_publishes_pass(self):
        evidence = self.directory / "compile-failed"
        ui = MagicMock()
        with patch.object(workflow, "_EditorUiWorkflow", return_value=ui), \
                patch.object(workflow.oracle, "run_cmd",
                             return_value=subprocess.CompletedProcess([], 1)), \
                self.assertRaisesRegex(RuntimeError, "compilation failed"):
            workflow.run_ui_workflow(Path("adb.exe"), "emulator-5580", evidence)
        ui.command.assert_not_called()
        ui.shell.assert_not_called()
        ui.pick.assert_not_called()
        self.assertFalse((evidence / "summary.json").exists())


if __name__ == "__main__":
    unittest.main()
