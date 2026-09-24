"""Actual first/middle/last picker checks on the already-verified, idle APK."""
import argparse
from collections import Counter
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid
import xml.etree.ElementTree as ET
from music_preview_run import MusicPreviewRun, restore_owned_settings
from validate_cc0_hundred import ROOT, ADB, digest
from validate_music_preview import stop_owned_app
import run_editor_workflow as workflow
import run_emulator_validation as oracle
from verify_real_music_exports import compare, decode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--evidence", type=Path, required=True)
    parser.add_argument("--evidence-root", type=Path, default=ROOT / "app" / "build")
    args = parser.parse_args()
    os.chdir(ROOT)
    baseline = json.loads((args.baseline / "summary.json").read_text())
    assert baseline["status"] == baseline["cleanup"] == "PASS"
    evidence = args.evidence.resolve()
    owner = args.evidence_root.resolve()
    assert evidence != owner and evidence.is_relative_to(owner)
    evidence.mkdir(parents=True, exist_ok=False)
    revision, token = baseline["revision"], uuid.uuid4().hex
    runner = MusicPreviewRun(evidence, ROOT / r"app\build\cc0-hundred-integration-progress.txt",
                            revision=revision, scope="actual-global-picker-and-longest-ui-export")
    oracle.ACTIVE_EVIDENCE = evidence
    adb = [str(ADB), "-s", "emulator-5580"]
    original = None
    remote = "/sdcard/cc0-picker-" + token + ".xml"
    source_name = "cc0-picker-source-" + token + ".mp4"
    source_remote = "/sdcard/Download/" + source_name

    def command(*args, timeout=30):
        return subprocess.check_output(adb + list(args), timeout=timeout)

    def configure(action="query", **kwargs):
        record = workflow.configure_encoding(ADB, "emulator-5580", evidence, action, **kwargs)
        workflow.assert_revision(record, revision)
        return record

    def tree():
        for attempt in range(5):
            command("shell", "uiautomator", "dump", remote)
            try:
                return ET.fromstring(command("exec-out", "cat", remote))
            except ET.ParseError:
                time.sleep(1)
        raise AssertionError("No valid foreground UI hierarchy after bounded retries")

    def find(predicate):
        for attempt in range(5):
            nodes = [n for n in tree().iter("node") if predicate(n)]
            if nodes:
                return nodes[0]
            time.sleep(.5)
        raise AssertionError("Required visible UI node missing")

    def tap(node):
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.attrib["bounds"]))
        assert x2 > x1 and y2 > y1
        command("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))

    def button(name):
        node = find(lambda n: n.get("resource-id", "").endswith("/" + name))
        assert node.get("enabled") == "true", name
        tap(node)

    def selection():
        raw = command("exec-out", "run-as", "com.simple.videoeditor", "cat",
                      "shared_prefs/offline_music.xml")
        value = ET.fromstring(raw).find("./string[@name='selection']")
        return json.loads(value.text)["bgm"]

    def movies():
        data = command("shell", "content query --uri content://media/external/video/media "
                       "--projection _id:_display_name:relative_path "
                       "--where \"owner_package_name='com.simple.videoeditor'\"").decode()
        return {int(value) for value in re.findall(r"_id=(\d+)", data)}

    def body():
        nonlocal original
        original = configure(preferences="snapshot", preference_token=token)
        command("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        command("shell", "wm", "dismiss-keyguard")
        command("shell", "am", "start", "-n", "com.simple.videoeditor/.MainActivity")
        tap(find(lambda n: n.get("text") in ("声音", "Sound")))
        tracks = json.loads((ROOT / r"app\src\main\assets\music\catalog.json").read_text(encoding="utf-8"))["tracks"]
        counts = Counter(t["mood"] + " / " + t["style"] for t in tracks)
        counts.update(t["moodZh"] + " / " + t["styleZh"] for t in tracks)
        chosen = [tracks[i] for i in (0, 59, 117)]
        chosen.append(next(t for t in tracks if t["id"] == "abstraction-8fae5917ba33861a49611966"))
        runner.summary["selections"] = []
        for track in chosen:
            before = selection()["trackId"]
            button("btnLibraryTrack")
            categories = tree()
            labels = [n.get("text") for n in categories.iter("node") if re.search(r"\(\d+\)$", n.get("text", ""))]
            assert labels
            for label in labels:
                category, _, count = label.rpartition(" (")
                assert counts[category] == int(count[:-1]), label
            (evidence / (track["id"] + "-categories.xml")).write_bytes(ET.tostring(categories, encoding="utf-8"))
            tap(find(lambda n: n.get("resource-id") == "android:id/button3"))
            search = find(lambda n: n.get("class") == "android.widget.EditText")
            tap(search)
            command("shell", "input", "text", "no-matching-track-999")
            time.sleep(.3)
            assert selection()["trackId"] == before
            command("shell", "input", "keyevent", "KEYCODE_MOVE_END")
            command("shell", "input", "keyevent", "--longpress", "KEYCODE_DEL")
            # Android input's longpress delete is device-dependent: select all first.
            command("shell", "input", "keycombination", "113", "29")
            command("shell", "input", "keyevent", "KEYCODE_DEL")
            assert re.fullmatch(r"[A-Za-z0-9 _-]+", track["title"])
            command("shell", "input", "text", track["title"].replace(" ", "%s"))
            command("shell", "input", "keyevent", "KEYCODE_BACK")
            assert find(lambda n: n.get("class") == "android.widget.EditText").get("text") == track["title"]
            (evidence / (track["id"] + "-filtered.xml")).write_bytes(ET.tostring(tree(), encoding="utf-8"))
            tap(find(lambda n: n.get("class") != "android.widget.EditText" and
                     any(title in n.get("text", "") for title in (track["title"], track["titleZh"]))))
            for attempt in range(50):
                if selection()["trackId"] == track["id"]:
                    break
                time.sleep(.1)
            assert selection()["trackId"] == track["id"], (track["id"], selection())
            label = find(lambda n: n.get("resource-id", "").endswith("/tvLibraryTrack")).get("text")
            button("btnAuditionMusic")
            time.sleep(2)
            status = find(lambda n: n.get("resource-id", "").endswith("/tvMusicAuditionStatus")).get("text")
            assert "10" in status, status
            button("btnAuditionMusic")
            stopped = find(lambda n: n.get("resource-id", "").endswith("/tvMusicAuditionStatus")).get("text")
            assert "已停止" in stopped or "stopped" in stopped, stopped
            (evidence / (track["id"] + ".png")).write_bytes(command("exec-out", "screencap", "-p"))
            runner.summary["selections"].append(dict(id=track["id"], label=label, status=status,
                                                     categories=labels, filteredSelectionStable=True))
            runner.note("Actual picker/preview/stop verified: " + track["id"])
        command("push", str(ROOT / r"app\src\androidTest\assets\real-music\source4.mp4"), source_remote)
        button("btnSelectVideo")
        physical = workflow._EditorUiWorkflow(ADB, "emulator-5580", evidence, remote)
        physical.pick(source_name, "owned-source4")
        tap(find(lambda n: n.get("text") in ("声音", "Sound")))
        checkbox = find(lambda n: n.get("resource-id", "").endswith("/cbLibraryMusic"))
        if checkbox.get("checked") != "true":
            tap(checkbox)
        mute = find(lambda n: n.get("resource-id", "").endswith("/cbMuteOriginal"))
        if mute.get("checked") != "true":
            tap(mute)
        selected = selection()
        assert selected["trackId"] == chosen[-1]["id"] and selected["enabled"] and selected["muteOriginal"]
        before = movies()
        button("btnProcess")
        for attempt in range(90):
            nodes = list(tree().iter("node"))
            process = next((n for n in nodes if n.get("resource-id", "").endswith("/btnProcess")), None)
            if process is not None and process.get("enabled") == "true":
                break
            time.sleep(2)
        else:
            raise AssertionError("UI export deadline")
        final = tree()
        (evidence / "longest-export.xml").write_bytes(ET.tostring(final, encoding="utf-8"))
        (evidence / "longest-export-wait.png").write_bytes(command("exec-out", "screencap", "-p"))
        new = movies() - before
        assert len(new) == 1, "Must publish one NEW output, never share an old fallback"
        uri = "content://media/external/video/media/" + str(new.pop())
        output = evidence / "longest-ui.mp4"
        output.write_bytes(command("exec-out", "content", "read", "--uri", uri, timeout=60))
        probe = json.loads(subprocess.check_output(
            ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(output)], timeout=30))
        video = next(s for s in probe["streams"] if s["codec_type"] == "video")
        seconds = round(float(video["duration"]))
        track = chosen[-1]
        source = decode(ROOT / r"app\src\main\assets" / Path(*track["path"].split("/")), 180)
        pcm = compare(decode(output, 180), source, selected["gain"], track["frames"] / 44100,
                      seconds, aligned_tail=True)
        (evidence / "longest-ui-pcm.json").write_text(json.dumps(pcm, indent=2))
        runner.summary["publicOutput"] = dict(uri=uri, trackId=track["id"], seconds=seconds,
                                              gain=selected["gain"], minCorrelation=pcm["minCorrelation"],
                                              file=output.name, sha256=digest(output))
        (evidence / "longest-export-complete.png").write_bytes(command("exec-out", "screencap", "-p"))
        button("btnShareVideo")
        chooser = tree()
        assert any("resolver" in n.get("package", "") or "intentresolver" in n.get("package", "")
                   for n in chooser.iter("node")), "Real share chooser missing"
        runner.summary["longestUiExportShare"] = True

    def cleanup():
        try:
            command("shell", "rm", "-f", remote, source_remote)
        finally:
            if original:
                runner.summary["restoration"] = restore_owned_settings(
                    lambda: stop_owned_app(ADB, "emulator-5580", evidence, "cleanup-stop.txt", dict(os.environ)),
                    configure, original, token)

    return runner.execute(body, cleanup)


if __name__ == "__main__":
    raise SystemExit(main())
