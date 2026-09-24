"""Real DocumentsUI selections, widget inputs, preview screenshots and production export.

Run only against the owned, already-booted emulator. No mocked picker/activity/config.
Independent host decoding checks the UI-exported file with the frozen PNG oracle.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.simple.videoeditor"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--evidence-dir", required=True, type=Path)
    args = parser.parse_args()
    assert args.serial.startswith("emulator-")
    evidence = args.evidence_dir.resolve()
    evidence.relative_to(ROOT)
    evidence.mkdir(parents=True, exist_ok=False)
    adb_path = ROOT / ".local-sdk" / "platform-tools" / "adb.exe"

    def adb(*words, timeout=30):
        return subprocess.check_output([str(adb_path), "-s", args.serial, *words], timeout=timeout)

    def shell(*words, **kwargs):
        return adb("shell", *words, **kwargs).decode("utf-8").strip()

    assert shell("getprop", "ro.kernel.qemu") == "1"
    assert shell("getprop", "sys.boot_completed") == "1"
    unique = evidence.name
    source_name = unique + "-source.mp4"
    png_name = unique + "-watermark.png"
    remote_xml = "/sdcard/" + unique + "-ui.xml"
    for local, name in [(ROOT / r"app\src\main\assets\video-oracle\standard.mp4", source_name),
                        (ROOT / r"app\src\main\assets\watermark-oracle\watermark.png", png_name)]:
        adb("push", str(local), "/sdcard/Download/" + name)
        shell("am", "broadcast", "-a", "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
              "-d", "file:///sdcard/Download/" + name)

    def screen(name=None):
        shell("uiautomator", "dump", remote_xml)
        data = adb("exec-out", "cat", remote_xml)
        if name:
            (evidence / (name + ".xml")).write_bytes(data)
            (evidence / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p"))
        return ET.fromstring(data)

    def bounds(node):
        return list(map(int, re.findall(r"\d+", node.get("bounds"))))

    def tap(node):
        assert node is not None and node.get("enabled") == "true"
        left, top, right, bottom = bounds(node)
        shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        time.sleep(.5)

    def find(ui, id=None, text=None, desc=None):
        return next((n for n in ui.iter("node") if
                     (id is None or n.get("resource-id") == PACKAGE + ":id/" + id)
                     and (text is None or n.get("text") == text)
                     and (desc is None or n.get("content-desc") == desc)), None)

    def scroll_find(id):
        for _ in range(30):
            node = find(screen(), id=id)
            if node is not None:
                left, top, right, bottom = bounds(node)
                if 90 <= (top + bottom) // 2 <= 790:
                    return node
                if top < 90:
                    shell("input", "swipe", "450", "350", "450", "500", "400")
                    continue
            shell("input", "swipe", "450", "650", "450", "400", "400")
        screen("missing-" + id)
        raise AssertionError("Cannot find " + id)

    def top():
        for _ in range(10):
            shell("input", "swipe", "450", "260", "450", "700", "100")

    def pick(name, label):
        ui = screen(label + "-picker")
        assert any(n.get("package") == "com.android.documentsui" for n in ui.iter("node"))
        menu = find(ui, desc="Show roots")
        if menu is not None and find(ui, text="Downloads") is None:
            tap(menu)
            roots = screen(label + "-roots")
            downloads = next((n for n in roots.iter("node")
                              if n.get("resource-id") == "android:id/title"
                              and n.get("text") == "Downloads"), None)
            assert downloads is not None
            tap(downloads)
        # Search local Downloads by exact unique name; provider remains real.
        ui = screen()
        search = find(ui, desc="Search")
        if search is not None:
            tap(search)
            shell("input", "text", name)
            shell("input", "keyevent", "KEYCODE_ENTER")
            time.sleep(1)
        ui = screen(label + "-file")
        node = next((n for n in ui.iter("node") if n.get("resource-id") == "android:id/title"
                     and n.get("text") == name and n.get("class") == "android.widget.TextView"), None)
        if node is None:
            node = next((n for n in ui.iter("node") if n.get("content-desc", "").startswith(name + ",")
                         and n.get("enabled") == "true"), None)
        assert node is not None, "Missing local document " + name
        tap(node)
        time.sleep(2)

    def input_value(id, value):
        tap(scroll_find(id))
        shell("input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(8):
            shell("input", "keyevent", "KEYCODE_DEL")
        shell("input", "text", value)
        shell("input", "keyevent", "KEYCODE_BACK")

    shell("am", "force-stop", PACKAGE)
    shell("am", "start", "-W", "-f", "0x10008000", "-n", PACKAGE + "/.MainActivity")
    tap(find(screen("01-main"), id="btnSelectVideo"))
    pick(source_name, "02-video")
    top()
    baseline = screen("03-preview-off")
    assert find(baseline, id="ivVideoThumbnail") is not None
    tap(scroll_find("cbEnableTrim"))
    input_value("etTrimEnd", "4")
    top()
    enable = scroll_find("cbEnableWatermark")
    assert enable.get("checked") == "false"
    tap(scroll_find("btnSelectWatermark"))
    pick(png_name, "04-png")
    enable = scroll_find("cbEnableWatermark")
    assert enable.get("checked") == "false", "Selection must not silently enable watermark"
    tap(enable)
    input_value("etWatermarkWidth", "30")
    input_value("etWatermarkX", "0")
    input_value("etWatermarkY", "0")
    screen("05-custom-inputs")
    top()
    screen("06-custom-preview")
    input_value("etWatermarkWidth", "20")
    input_value("etWatermarkX", "75")
    input_value("etWatermarkY", "75")
    screen("07-oracle-inputs")
    top()
    marked = screen("08-preview-on")
    verify_preview(evidence, baseline, marked)
    tap(scroll_find("btnProcess"))
    top()
    deadline = time.monotonic() + 200
    remote = None
    while time.monotonic() < deadline:
        ui = screen()
        node = find(ui, id="tvOutputPath")
        if node is not None and node.get("text", "").endswith(".mp4"):
            remote = node.get("text")
            break
        error = find(ui, id="tvErrorDetails")
        assert error is None or not error.get("text"), error.attrib if error is not None else ""
        shell("input", "swipe", "450", "650", "450", "450", "400")
        time.sleep(1)
    assert remote and remote.startswith("/data/user/0/" + PACKAGE + "/files/exports/edited_")
    screen("09-export-completed")
    output = evidence / "user-export.mp4"
    output.write_bytes(adb("exec-out", "run-as", PACKAGE, "cat", remote))
    command = ["java", "-Xmx768m", "-Djava.awt.headless=true",
               "-Djava.io.tmpdir=" + str(evidence), "-cp", str(ROOT / r"app\build\watermark-classes"),
               "com.simple.videoeditor.oracle.WatermarkParityMain", str(output), str(evidence / "user-export")]
    result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    (evidence / "independent-verification.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
    assert result.returncode == 0, result.stdout + result.stderr
    top()
    tap(scroll_find("btnClearWatermark"))
    top()
    cleared = screen("10-preview-cleared")
    # Compare just the actual ImageView; status bar clocks and transient UI may differ.
    rect = bounds(find(cleared, id="ivVideoThumbnail"))
    assert screenshot_rgb(evidence / "03-preview-off.png", rect) == screenshot_rgb(
        evidence / "10-preview-cleared.png", rect)
    summary = {"status": "PASS", "serial": args.serial, "provider": "real DocumentsUI Downloads",
               "actions": ["video picker", "PNG picker/default off", "width/X/Y input",
                           "custom preview", "oracle preview pixel checks", "production Process button",
                           "independent full/sparse export pixel/time/audio checks", "clear restores preview"],
               "output": remote, "output_sha256": hashlib.sha256(output.read_bytes()).hexdigest()}
    apk_path = shell("pm", "path", PACKAGE).split("package:", 1)[1]
    summary["installed_apk_sha256"] = hashlib.sha256(adb("exec-out", "cat", apk_path)).hexdigest()
    (evidence / "summary.json").write_text(json.dumps(summary, indent=2), encoding="utf-8")
    shell("rm", remote_xml, "/sdcard/Download/" + source_name, "/sdcard/Download/" + png_name)
    print(json.dumps(summary, indent=2))


def screenshot_rgb(path, crop=None):
    command = ["ffmpeg", "-v", "error", "-i", str(path)]
    if crop:
        left, top, right, bottom = crop
        command += ["-vf", f"crop={right-left}:{bottom-top}:{left}:{top}"]
    return subprocess.check_output(command + ["-f", "rawvideo", "-pix_fmt", "rgb24", "-"], timeout=30)


def verify_preview(evidence, baseline, marked):
    def image_node(ui):
        return next(n for n in ui.iter("node") if n.get("resource-id") == PACKAGE + ":id/ivVideoThumbnail")
    rect = list(map(int, re.findall(r"\d+", image_node(marked).get("bounds"))))
    assert image_node(baseline).get("bounds") == image_node(marked).get("bounds")
    left, top, right, bottom = rect
    width, height = right-left, bottom-top
    before = screenshot_rgb(evidence / "03-preview-off.png", rect)
    after = screenshot_rgb(evidence / "08-preview-on.png", rect)
    scale = min(width / 320, height / 240)
    dx, dy = (width - 320 * scale) / 2, (height - 240 * scale) / 2
    samples = []
    for x, y, rgb, alpha in [(202, 166, (240, 30, 40), 255), (224, 166, (25, 45, 240), 128),
                              (246, 166, (25, 230, 55), 255), (246, 180, (25, 230, 55), 128),
                              (246, 174, (25, 230, 55), 0)]:
        p = (round(dy + y * scale) * width + round(dx + x * scale)) * 3
        expected = [(rgb[c] * alpha + before[p+c] * (255-alpha)) / 255 for c in range(3)]
        actual = list(after[p:p+3])
        error = max(abs(actual[c] - expected[c]) for c in range(3))
        samples.append({"source_xy": [x, y], "alpha": alpha, "actual": actual, "expected": expected, "error": error})
        assert error <= 18, samples
    (evidence / "preview-pixels.json").write_text(json.dumps(samples, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
