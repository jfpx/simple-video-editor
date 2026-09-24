"""Offline checks for ordinary-processing wording; diagnostic deadlines are separate."""
from pathlib import Path
import re
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app" / "src" / "main" / "res"


class ProcessingStatusTest(unittest.TestCase):
    def test_both_locales_cover_start_progress_import_and_compatibility(self):
        for locale, phrase, cancel in (
                ("values", "无应用内处理时限", "可手动取消"),
                ("values-en", "no in-app processing time limit", "cancel manually")):
            with self.subTest(locale=locale):
                strings = {node.attrib["name"]: "".join(node.itertext())
                           for node in ET.parse(RES / locale / "editor_strings.xml").getroot()
                           if node.tag == "string"}
                for key in ("editor_export_starting", "editor_export_progress",
                            "editor_loading_media", "editor_loading_clips",
                            "editor_compatibility_hint"):
                    self.assertIn(phrase, strings[key], key)
                    self.assertIn(cancel, strings[key], key)
                self.assertIn("%1$d%%", strings["editor_export_progress"])
                self.assertNotIn("editor_media_timeout", strings)
                self.assertNotIn("editor_append_timeout", strings)
                self.assertIn("自测" if locale == "values" else "self-tests",
                              strings["editor_compatibility_hint"])

    def test_no_obsolete_processing_deadline_in_packaged_strings(self):
        obsolete = re.compile(
            r"180\s*(?:秒|second|s\b)|(?:限时\s*60\s*秒|超过\s*60\s*秒|"
            r"60\s*s\s*limit|exceeded\s*60\s*seconds)|"
            r"大视频可能超时|large videos may time out", re.IGNORECASE)
        for path in RES.glob("values*/*.xml"):
            with self.subTest(resource=str(path.relative_to(RES))):
                self.assertIsNone(obsolete.search(path.read_text(encoding="utf-8")))

    def test_fixed_status_dock_wraps_explanation_and_keeps_cancel(self):
        android = "{http://schemas.android.com/apk/res/android}"
        layout = ET.parse(RES / "layout" / "activity_main.xml").getroot()
        nodes = {node.get(android + "id"): node for node in layout.iter()}
        status = nodes["@+id/tvProgress"]
        self.assertEqual("3", status.get(android + "maxLines"))
        self.assertEqual("polite", status.get(android + "accessibilityLiveRegion"))
        self.assertIn("@+id/btnCancelExport", nodes)


if __name__ == "__main__":
    unittest.main()
