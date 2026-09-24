from contextlib import contextmanager
import bz2
import gzip
import io
import json
import lzma
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import Mock, patch
import zipfile
import zlib

import privacy_export as export
import privacy_gate as gate


def archive(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as handle:
        for name, content in entries:
            handle.writestr(name, content)
    return output.getvalue()


def qcompress(content):
    return len(content).to_bytes(4, "big") + zlib.compress(content)


class GateTests(unittest.TestCase):
    def setUp(self):
        root = Path(os.environ.get("PRIVACY_TEST_ROOT", ".privacy-work")).resolve()
        root.mkdir(exist_ok=True)
        self.directory = tempfile.TemporaryDirectory(prefix="gate-test-", dir=root)
        self.addCleanup(self.directory.cleanup)
        self.scanner = gate.Scanner(self.directory.name)

    def scan(self, content, name="fixture.txt"):
        return self.scanner.stream(io.BytesIO(content), name, len(content))

    @contextmanager
    def directory_metadata(self, entry):
        path = Path(self.directory.name) / "empty.zip"
        path.write_bytes(archive([]))
        with patch.object(gate.zipfile, "ZipFile") as constructor:
            handle = constructor.return_value.__enter__.return_value
            handle.infolist.return_value = [entry]
            yield path, handle

    def test_directory_declared_payload_rejected_before_open(self):
        for size in (-1, 1, gate.MAX_FILE + 1):
            with self.subTest(size=size):
                entry = zipfile.ZipInfo("folder/")
                entry.file_size = size
                with self.directory_metadata(entry) as (path, handle):
                    with self.assertRaisesRegex(gate.GateError, "Nonempty archive directory"):
                        self.scanner.archive(path, "fixture.zip", 1)
                    handle.open.assert_not_called()

    def test_directory_decoded_content_must_be_empty(self):
        with self.directory_metadata(zipfile.ZipInfo("folder/")) as (path, handle):
            stream = handle.open.return_value.__enter__.return_value
            stream.read.return_value = b"x"
            with self.assertRaisesRegex(gate.GateError, "Expanded byte bound"):
                self.scanner.archive(path, "fixture.zip", 1)
            stream.read.assert_called_once_with(1)
            self.assertEqual(1, self.scanner.count)
            self.assertEqual(1, self.scanner.total)
            self.assertEqual([], self.scanner.entries)

    def test_directory_crc_and_read_errors_fail_cli(self):
        errors = (zipfile.BadZipFile("CRC failure"), EOFError("short stream"),
                  zlib.error("decode failure"), OSError("read failure"))
        report = Path(self.directory.name) / "report.json"
        for error in errors:
            with self.subTest(error=type(error).__name__):
                with self.directory_metadata(zipfile.ZipInfo("folder/")) as (path, handle):
                    stream = handle.open.return_value.__enter__.return_value
                    stream.read.side_effect = error
                    args = ["privacy_gate", "--mode", "artifact", "--artifact", str(path),
                            "--scratch", self.directory.name, "--report", str(report)]
                    with patch.object(sys, "argv", args), patch("builtins.print"):
                        self.assertEqual(1, gate.main())
                    result = json.loads(report.read_text())
                    self.assertFalse(result["passed"])
                    self.assertEqual([type(error).__name__], result["errors"])
                    stream.read.assert_called_once_with(1)

    def test_directory_open_errors_propagate(self):
        with self.directory_metadata(zipfile.ZipInfo("folder/")) as (path, handle):
            handle.open.side_effect = zipfile.BadZipFile("header failure")
            with self.assertRaises(zipfile.BadZipFile):
                self.scanner.archive(path, "fixture.zip", 1)

    def test_directory_filename_policy_and_empty_digest(self):
        names = (("user.keystore/", "forbidden-filename"),
                 (".cop" + "ilot/session-state/" + "fake/", "session-path"),
                 ("fixture_/" + "home/" + "fake-person/", "user-path"))
        for name, rule in names:
            with self.subTest(rule=rule):
                self.scanner = gate.Scanner(self.directory.name)
                with self.directory_metadata(zipfile.ZipInfo(name)) as (path, handle):
                    stream = handle.open.return_value.__enter__.return_value
                    stream.read.return_value = b""
                    self.scanner.archive(path, "fixture.zip", 1)
                    stream.read.assert_called_once_with(1)
                self.assertEqual([rule], [f["rule"] for f in self.scanner.findings])
                self.assertEqual(export.digest(b""), self.scanner.findings[0]["sha256"])
                self.assertEqual(1, self.scanner.count)
                self.assertEqual(0, self.scanner.total)
                self.assertEqual(0, self.scanner.entries[0]["bytes"])
                if rule != "forbidden-filename":
                    self.assertTrue(self.scanner.entries[0]["path"].startswith("redacted-name-"))

    def test_empty_directories_stored_and_deflated(self):
        for compression in (zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED):
            with self.subTest(compression=compression):
                output = io.BytesIO()
                with zipfile.ZipFile(output, "w", compression) as handle:
                    handle.writestr("folder/", b"")
                data = output.getvalue()
                self.scanner = gate.Scanner(self.directory.name)
                self.scan(data, "fixture.zip")
                self.assertFalse(self.scanner.findings)
                self.assertEqual(2, self.scanner.count)
                self.assertEqual(len(data), self.scanner.total)
                self.assertEqual({"path": "fixture.zip!folder/", "sha256": export.digest(b""),
                                  "bytes": 0}, self.scanner.entries[-1])
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_directory_suffixes_do_not_require_file_content(self):
        self.scan(archive([(name, b"") for name in
                           ("data.json/", "nested.zip/", "data.gz/", "song.mmpz/")]),
                  "fixture.zip")
        self.assertEqual(5, self.scanner.count)
        self.assertFalse(self.scanner.findings)
        self.assertEqual([0, 0, 0, 0], [e["bytes"] for e in self.scanner.entries[1:]])

    def test_nested_directory_accounting_at_exact_budgets(self):
        inner = archive([("inner/", b""), ("safe.txt", b"safe")])
        outer = archive([("first/", b""), ("inner.zip", inner), ("last/", b"")])
        total = len(outer) + len(inner) + 4
        with patch.object(gate, "MAX_ENTRIES", 6), patch.object(gate, "MAX_TOTAL", total):
            self.scan(outer, "fixture.zip")
        self.assertEqual(6, self.scanner.count)
        self.assertEqual(6, len(self.scanner.entries))
        self.assertEqual(total, self.scanner.total)
        self.assertFalse(self.scanner.findings)

    def test_nested_directory_count_budget_is_shared(self):
        inner = archive([("inner/", b""), ("safe.txt", b"safe")])
        outer = archive([("first/", b""), ("inner.zip", inner), ("last/", b"")])
        with patch.object(gate, "MAX_ENTRIES", 5):
            with self.assertRaisesRegex(gate.GateError, "File/count bound"):
                self.scan(outer, "fixture.zip")
        self.assertEqual(5, self.scanner.count)
        self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_nested_directory_byte_budget_is_shared(self):
        inner = archive([("inner/", b""), ("safe.txt", b"safe")])
        outer = archive([("first/", b""), ("inner.zip", inner), ("last/", b"")])
        with patch.object(gate, "MAX_TOTAL", len(outer) + len(inner) + 3):
            with self.assertRaisesRegex(gate.GateError, "Expanded byte bound"):
                self.scan(outer, "fixture.zip")
        self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_directory_entry_limit_precedes_read(self):
        with self.directory_metadata(zipfile.ZipInfo("folder/")) as (path, handle):
            self.scanner.count = gate.MAX_ENTRIES
            with self.assertRaisesRegex(gate.GateError, "File/count bound"):
                self.scanner.archive(path, "fixture.zip", 1)
            handle.open.return_value.__enter__.return_value.read.assert_not_called()

    def test_directory_other_metadata_checks_remain_required(self):
        for attribute, value in (("external_attr", 0o120777 << 16), ("flag_bits", 1),
                                 ("compress_type", zipfile.ZIP_BZIP2)):
            with self.subTest(attribute=attribute):
                entry = zipfile.ZipInfo("folder/")
                setattr(entry, attribute, value)
                with self.directory_metadata(entry) as (path, handle):
                    with self.assertRaises(gate.GateError):
                        self.scanner.archive(path, "fixture.zip", 1)
                    handle.open.assert_not_called()

    def test_qcompress_benign_xml_and_empty_payload_accounting(self):
        for content in (b'<lmms-project creator="LMMS"><song/></lmms-project>', b""):
            with self.subTest(size=len(content)):
                self.scanner = gate.Scanner(self.directory.name)
                data = qcompress(content)
                self.assertEqual(export.digest(data), self.scan(data, "song.MMPZ"))
                self.assertEqual([
                    {"path": "song.MMPZ", "sha256": export.digest(data), "bytes": len(data)},
                    {"path": "song.MMPZ!song.mmp", "sha256": export.digest(content),
                     "bytes": len(content)}], self.scanner.entries)
                self.assertEqual(2, self.scanner.count)
                self.assertEqual(len(data) + len(content), self.scanner.total)
                self.assertFalse(self.scanner.findings)
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_private_payload_and_names_use_shared_rules(self):
        private = "C:" + "\\" + "Users" + "\\" + "fake-person" + "\\" + "song.wav"
        content = ('<lmms-project sample="' + private + '"/>').encode("utf-16le")
        data = archive([("song.mmpz", qcompress(content))])
        with patch.object(gate, "CHUNK", 32):
            self.scan(data, "fixture.zip")
        decoded = self.scanner.entries[-1]
        self.assertEqual("fixture.zip!song.mmpz!song.mmp", decoded["path"])
        self.assertIn({"path": decoded["path"], "rule": "user-path",
                       "sha256": export.digest(content)}, self.scanner.findings)
        self.scanner = gate.Scanner(self.directory.name)
        name = "gh" + "p_" + "X" * 36 + ".mmpz"
        self.scan(archive([(name, qcompress(b"<lmms-project/>"))]), "fixture.zip")
        self.assertTrue(all(e["path"].startswith("redacted-name-")
                            for e in self.scanner.entries[1:]))
        self.assertTrue(any(f["rule"] == "github-token" for f in self.scanner.findings))

    def test_qcompress_history_style_label_uses_explicit_filename(self):
        content = ("gh" + "p_" + "X" * 36).encode()
        data = qcompress(content)
        self.scanner.stream(io.BytesIO(data), "git:synthetic:project", len(data),
                            filename="music/song.mmpz")
        self.assertEqual("git:synthetic:project!song.mmp", self.scanner.entries[-1]["path"])
        self.assertTrue(any(f["sha256"] == export.digest(content)
                            for f in self.scanner.findings))

    def test_qcompress_signature_scans_renamed_and_recursive_payloads(self):
        content = ("gh" + "p_" + "X" * 36).encode()
        for data, name, count in (
                (qcompress(content), "renamed.bin", 2),
                (qcompress(qcompress(content)), "outer.mmpz", 3),
                (archive([("renamed.bin", qcompress(content))]), "fixture.zip", 3)):
            with self.subTest(name=name):
                self.scanner = gate.Scanner(self.directory.name)
                self.scan(data, name)
                self.assertEqual(count, len(self.scanner.entries))
                self.assertEqual(export.digest(content), self.scanner.entries[-1]["sha256"])
                self.assertIn({"path": self.scanner.entries[-1]["path"],
                               "rule": "github-token", "sha256": export.digest(content)},
                              self.scanner.findings)
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_renamed_malformed_stream_fails_closed(self):
        for data in (qcompress(b"<song/>")[:-1], qcompress(b"<song/>") + b"x"):
            with self.subTest(size=len(data)):
                self.scanner = gate.Scanner(self.directory.name)
                with self.assertRaises(gate.GateError):
                    self.scan(data, "renamed.bin")
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_unsupported_compressed_payload_is_not_ignored(self):
        for compress in (zlib.compress, gzip.compress, bz2.compress, lzma.compress):
            with self.subTest(compress=compress.__module__):
                self.scanner = gate.Scanner(self.directory.name)
                with self.assertRaisesRegex(gate.GateError, "Unsupported compressed"):
                    self.scan(qcompress(compress(b"<song/>")), "song.mmpz")
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_short_underlying_reads_still_validate_end_and_trailing(self):
        case = self

        class ShortReader(io.BytesIO):
            def read(self, size):
                case.assertGreater(size, 0)
                case.assertLessEqual(size, gate.CHUNK)
                return super().read(min(size, 1))

        content = b"<song/>"
        for suffix in (b"", b"x"):
            with self.subTest(trailing=bool(suffix)):
                source = ShortReader(zlib.compress(content) + suffix)
                reader = gate.QtCompressedReader(source)
                if suffix:
                    with self.assertRaisesRegex(gate.GateError, "Trailing Qt"):
                        while reader.read(3):
                            pass
                else:
                    chunks = []
                    while block := reader.read(3):
                        chunks.append(block)
                    self.assertEqual(content, b"".join(chunks))
                    self.assertEqual(b"", reader.read(3))

    def test_qcompress_recursive_wrappers_share_all_budgets(self):
        content = b"<song/>"
        inner = qcompress(content)
        outer = qcompress(inner)
        total = len(outer) + len(inner) + len(content)
        for bound, limit, error in (("MAX_DEPTH", 1, "depth"),
                                    ("MAX_ENTRIES", 2, "File/count bound"),
                                    ("MAX_TOTAL", total - 1, "Expanded byte bound")):
            with self.subTest(bound=bound):
                self.scanner = gate.Scanner(self.directory.name)
                with patch.object(gate, bound, limit):
                    with self.assertRaisesRegex(gate.GateError, error):
                        self.scan(outer, "outer.mmpz")
                self.assertEqual([], list(Path(self.directory.name).iterdir()))
        self.scanner = gate.Scanner(self.directory.name)
        with patch.object(gate, "MAX_DEPTH", 2), patch.object(
                gate, "MAX_ENTRIES", 3), patch.object(gate, "MAX_TOTAL", total):
            self.scan(outer, "outer.mmpz")
        self.assertEqual(3, self.scanner.count)
        self.assertEqual(total, self.scanner.total)

    def test_qcompress_encoded_private_names_are_redacted_everywhere(self):
        name = "folder/" + "\\u0067" + "h" + "p_" + "X" * 36 + ".mmpz"
        entry = zipfile.ZipInfo("fixture.mmpz")
        entry.filename = name
        self.scan(archive([(entry, qcompress(b"<song/>"))]), "fixture.zip")
        self.assertTrue(all(e["path"].startswith("redacted-name-")
                            for e in self.scanner.entries[1:]))
        named_findings = [f for f in self.scanner.findings if f["path"] != "fixture.zip"]
        self.assertTrue(named_findings)
        self.assertTrue(all(f["path"].startswith("redacted-name-") for f in named_findings))

    def test_qcompress_nested_archive_names_and_exact_shared_budgets(self):
        inner = archive([("folder/", b""), ("user.keystore", b"fake")])
        compressed = qcompress(inner)
        outer = archive([("song.mmpz", compressed), ("last/", b"")])
        total = len(outer) + len(compressed) + len(inner) + 4
        with patch.object(gate, "MAX_TOTAL", total), patch.object(gate, "MAX_ENTRIES", 6):
            self.scan(outer, "fixture.zip")
        self.assertEqual(6, self.scanner.count)
        self.assertEqual(6, len(self.scanner.entries))
        self.assertEqual(total, self.scanner.total)
        self.assertIn({"path": "fixture.zip!song.mmpz!song.mmp!user.keystore",
                       "rule": "forbidden-filename", "sha256": export.digest(b"fake")},
                      self.scanner.findings)
        self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_inside_nested_archives(self):
        data = archive([("inner.zip", archive([("song.mmpz", qcompress(b"<song/>"))]))])
        with patch.object(gate, "MAX_DEPTH", 3):
            self.scan(data, "fixture.zip")
        self.assertEqual("fixture.zip!inner.zip!song.mmpz!song.mmp",
                         self.scanner.entries[-1]["path"])
        self.assertFalse(self.scanner.findings)

    def test_qcompress_malformed_incomplete_trailing_and_declared_size(self):
        valid = qcompress(b"<song/>")
        corrupt = valid[:-1] + bytes([valid[-1] ^ 1])
        cases = [
            (b"", "Missing Qt"), (b"\x00\x00\x00", "Missing Qt"),
            ((7).to_bytes(4, "big") + b"invalid", "Invalid Qt"),
            (valid[:4], "Incomplete Qt"), (valid[:-1], "Incomplete Qt"),
            (corrupt, "Invalid Qt"),
            (valid + b"x", "Trailing Qt"),
            (valid + zlib.compress(b"second"), "Trailing Qt"),
            ((6).to_bytes(4, "big") + valid[4:], "Expanded byte bound"),
            ((8).to_bytes(4, "big") + valid[4:], "Truncated entry"),
            ((0).to_bytes(4, "big") + valid[4:], "Expanded byte bound"),
            (archive([]), "Invalid Qt"),
        ]
        for data, message in cases:
            with self.subTest(message=message, size=len(data)):
                self.scanner = gate.Scanner(self.directory.name)
                with self.assertRaisesRegex(gate.GateError, message):
                    self.scan(data, "song.mmpz")
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_trailing_data_in_next_input_chunk(self):
        valid = qcompress(b"<song/>")
        with patch.object(gate, "CHUNK", len(valid) - 4):
            with self.assertRaisesRegex(gate.GateError, "Trailing Qt"):
                self.scan(valid + b"x", "song.mmpz")
        self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_rejects_header_only_even_for_zero_size(self):
        with self.assertRaisesRegex(gate.GateError, "Incomplete Qt"):
            self.scan(b"\x00" * 4, "song.mmpz")

    def test_qcompress_rejects_raw_gzip_and_dictionary_streams(self):
        content = b"<song/>"
        encoders = [zlib.compressobj(wbits=-15), zlib.compressobj(wbits=31),
                    zlib.compressobj(zdict=b"<song/>")]
        for encoder in encoders:
            with self.subTest(encoder=encoders.index(encoder)):
                self.scanner = gate.Scanner(self.directory.name)
                data = len(content).to_bytes(4, "big") + encoder.compress(content) + encoder.flush()
                with self.assertRaisesRegex(gate.GateError, "Invalid Qt"):
                    self.scan(data, "song.mmpz")
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_size_ratio_aggregate_and_count_before_decoding(self):
        data = qcompress(b"x" * 128)
        cases = [("MAX_FILE", 64, "Qt expansion ratio/size"),
                 ("MAX_RATIO", 2, "Qt expansion ratio/size"),
                 ("MAX_TOTAL", len(data) + 127, "Expanded byte bound"),
                 ("MAX_ENTRIES", 1, "File/count bound")]
        for bound, limit, message in cases:
            with self.subTest(bound=bound):
                self.scanner = gate.Scanner(self.directory.name)
                with patch.object(gate, bound, limit), patch.object(
                        gate.QtCompressedReader, "read") as read:
                    with self.assertRaisesRegex(gate.GateError, message):
                        self.scan(data, "song.mmpz")
                    read.assert_not_called()
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_false_small_header_cannot_bypass_output_bound(self):
        data = (1).to_bytes(4, "big") + zlib.compress(b"x" * 128)
        with patch.object(gate, "CHUNK", 16), patch.object(gate, "MAX_FILE", 32):
            with self.assertRaisesRegex(gate.GateError, "Expanded byte bound"):
                self.scan(data, "song.mmpz")
        self.assertEqual(1, len(self.scanner.entries))
        self.assertLessEqual(self.scanner.total, len(data) + 16)

    def test_qcompress_depth_in_both_nested_directions(self):
        fixtures = [(archive([("song.mmpz", qcompress(b"<song/>"))]), "fixture.zip"),
                    (qcompress(archive([("safe.txt", b"safe")])), "song.mmpz")]
        for data, name in fixtures:
            with self.subTest(name=name):
                self.scanner = gate.Scanner(self.directory.name)
                with patch.object(gate, "MAX_DEPTH", 1):
                    with self.assertRaisesRegex(gate.GateError, "depth"):
                        self.scan(data, name)
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_nested_aggregate_and_count_fail_closed(self):
        inner = archive([("song.mmpz", qcompress(b"x" * 64))])
        outer = archive([("inner.zip", inner)])
        total = len(outer) + len(inner) + len(qcompress(b"x" * 64)) + 64
        for bound, limit in (("MAX_TOTAL", total - 1), ("MAX_ENTRIES", 3)):
            with self.subTest(bound=bound):
                self.scanner = gate.Scanner(self.directory.name)
                with patch.object(gate, bound, limit):
                    with self.assertRaises(gate.GateError):
                        self.scan(outer, "fixture.zip")
                self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_nested_archive_directory_payload_stays_rejected(self):
        data = qcompress(archive([("folder/", b"hidden")]))
        with self.assertRaisesRegex(gate.GateError, "Nonempty archive directory"):
            self.scan(data, "song.mmpz")
        self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_qcompress_all_decompress_calls_are_bounded(self):
        content = b"<song>" + b"x" * 256 + b"</song>"
        decoder = zlib.decompressobj()
        with patch.object(gate.zlib, "decompressobj", return_value=decoder) as factory:
            with patch.object(gate, "CHUNK", 16), patch.object(
                    gate.zlib, "decompress", side_effect=AssertionError("Unbounded decode")):
                tracked = Mock(wraps=decoder)
                tracked.flush.side_effect = AssertionError("Unbounded flush")
                factory.return_value = tracked
                # Native decoder state properties must remain live, not mocked values.
                def decode(block, limit):
                    self.assertGreater(limit, 0)
                    self.assertLessEqual(limit, 16)
                    result = decoder.decompress(block, limit)
                    tracked.unconsumed_tail = decoder.unconsumed_tail
                    tracked.unused_data = decoder.unused_data
                    tracked.eof = decoder.eof
                    return result
                tracked.decompress.side_effect = decode
                self.scan(qcompress(content), "song.mmpz")
                self.assertGreater(tracked.decompress.call_count, 1)
                tracked.flush.assert_not_called()
        self.assertEqual(export.digest(content), self.scanner.entries[-1]["sha256"])

    def test_qcompress_nested_failure_is_cli_error_not_clean_report(self):
        path = Path(self.directory.name) / "fixture.zip"
        path.write_bytes(archive([("song.mmpz", qcompress(b"<song/>")[:-1])]))
        report = Path(self.directory.name) / "report.json"
        args = ["privacy_gate", "--mode", "artifact", "--artifact", str(path),
                "--scratch", self.directory.name, "--report", str(report)]
        with patch.object(sys, "argv", args), patch("builtins.print"):
            self.assertEqual(1, gate.main())
        result = json.loads(report.read_text())
        self.assertFalse(result["passed"])
        self.assertEqual(["Incomplete Qt compressed stream"], result["errors"])
        self.assertFalse(list(Path(self.directory.name).glob("privacy-entry-*")))

    def test_private_path_variants(self):
        path = "C:" + "\\" + "Users" + "\\" + "fake-person" + "\\" + "evidence.json"
        variants = [path, path.replace("\\", "/"), json.dumps({"path": path}),
                    "".join("\\u%04x" % ord(c) for c in path)]
        for value in variants:
            for encoding in ("utf-8", "utf-16le"):
                with self.subTest(encoding=encoding, variant=variants.index(value)):
                    self.scanner.findings.clear()
                    self.scan(value.encode(encoding))
                    self.assertIn("user-path", [f["rule"] for f in self.scanner.findings])

    def test_session_path(self):
        self.scan(("/work/" + ".cop" + "ilot/session-state/" + "fake").encode())
        self.assertTrue(self.scanner.findings)

    def test_secret_patterns_synthetic_only(self):
        values = ["gh" + "p_" + "X" * 36, "github_" + "pat_" + "X" * 60,
                  "-----BEGIN " + "PRIVATE KEY-----", "https://" + "fake:fake@example.invalid/"]
        for value in values:
            self.scanner.findings.clear()
            self.scan(value.encode())
            self.assertTrue(self.scanner.findings)

    def test_public_credits_licenses_ids_and_numbers(self):
        self.scan(b'CC0 artist@example.org https://creativecommons.org/publicdomain/zero/1.0/ '
                  b'https://opengameart.org/users/artist href="/users/artist" '
                  b'com.simple.videoeditor 0.000001 48000 #ff00aa 118')
        self.assertEqual([], self.scanner.findings)
        self.scan(b'{"source":"https://opengameart.org/users/artist"}', "catalog.json")
        self.assertEqual([], self.scanner.findings)

    def test_absolute_metadata_even_without_username(self):
        value = "D:" + "\\" + "workspace" + "\\" + "fixture.mp4"
        self.scan(json.dumps({"candidate": value}).encode(), "results.json")
        self.assertIn("private-metadata", [f["rule"] for f in self.scanner.findings])

    def test_device_metadata(self):
        self.scan(json.dumps({"device_" + "serial": "FAKEDEVICE12345"}).encode())
        self.assertEqual("device-identity", self.scanner.findings[0]["rule"])

    def test_chunk_boundary(self):
        value = b"x" * (gate.CHUNK - 4) + ("gh" + "p_" + "X" * 36).encode()
        self.scan(value)
        self.assertTrue(self.scanner.findings)

    def test_nested_binary_archive_scanned_and_hashed(self):
        secret = ("gh" + "p_" + "X" * 36).encode()
        nested = archive([("report.txt", secret)])
        self.scan(archive([("disguised.bin", nested)]), "candidate.apk")
        self.assertEqual(3, len(self.scanner.entries))
        self.assertTrue(any(f["path"].endswith("report.txt") for f in self.scanner.findings))
        self.assertTrue(all(len(e["sha256"]) == 64 for e in self.scanner.entries))
        self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_depth_fail_closed(self):
        data = b"safe"
        for _ in range(gate.MAX_DEPTH + 1):
            data = archive([("nested.zip" if data.startswith(b"PK") else "safe.txt", data)])
        with self.assertRaises(gate.GateError):
            self.scan(data, "candidate.apk")
        self.assertEqual([], list(Path(self.directory.name).iterdir()))

    def test_bomb_bound_without_large_allocation(self):
        with patch.object(gate, "MAX_TOTAL", 100):
            with self.assertRaises(gate.GateError):
                self.scan(archive([("zeros.txt", b"0" * 200)]), "candidate.apk")

    def test_size_and_count_bounds(self):
        with patch.object(gate, "MAX_FILE", 2):
            with self.assertRaises(gate.GateError):
                self.scan(b"123")
        with patch.object(gate, "MAX_ENTRIES", 0):
            with self.assertRaises(gate.GateError):
                self.scan(b"")

    def test_truncated_and_invalid_archives(self):
        for data in (b"not a zip", b"PK\x03\x04broken"):
            with self.assertRaises((gate.GateError, zipfile.BadZipFile)):
                self.scan(data, "candidate.apk")

    def test_path_traversal_duplicate_encrypted_unsupported(self):
        for name in ("../escape.txt", "C:/escape.txt", "/absolute.txt"):
            with self.assertRaises(gate.GateError):
                self.scan(archive([(name, b"x")]), "candidate.apk")
        with self.assertRaises(gate.GateError):
            self.scan(b"\x1f\x8bcompressed", "data.bin")

    def test_forbidden_filename(self):
        self.scan(archive([("assets/user.keystore", b"fake")]), "candidate.apk")
        self.assertEqual("forbidden-filename", self.scanner.findings[0]["rule"])

    def test_archive_root_forbidden_filename(self):
        self.scan(archive([("user.keystore", b"fake")]), "candidate.apk")
        self.assertEqual("forbidden-filename", self.scanner.findings[0]["rule"])

    def test_history_checks_all_names_for_same_blob(self):
        repo = Path(self.directory.name) / "repo"
        repo.mkdir()
        def git(*args):
            return subprocess.check_output(
                ["git", "-C", str(repo), *args], stderr=subprocess.PIPE)
        git("init", "-q")
        (repo / "fixture.bin").write_bytes(b"synthetic fixture")
        git("add", ".")
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "commit", "-qm", "synthetic fixture")
        (repo / "user.keystore").write_bytes(b"synthetic fixture")
        git("add", ".")
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "commit", "-qm", "synthetic alias")
        git("rm", "user.keystore")
        git("-c", "user.name=Test", "-c", "user.email=test@example.invalid",
            "commit", "-qm", "remove alias from current tree")
        gate.scan_history(self.scanner, repo, ["--all"])
        self.assertTrue(any(f["rule"] == "forbidden-filename" and
                            f["path"].endswith(":user.keystore")
                            for f in self.scanner.findings))

    def test_incompatible_modes_fail_closed(self):
        report = Path(self.directory.name) / "report.json"
        for options in (["--mode", "artifact", "--source-only"],
                        ["--mode", "current", "--all-refs"],
                        ["--mode", "history", "--source-only"],
                        ["--mode", "history", "--all-refs", "--ref", "HEAD"]):
            args = ["privacy_gate", *options, "--scratch", self.directory.name,
                    "--report", str(report)]
            with patch.object(sys, "argv", args), patch("builtins.print"):
                self.assertEqual(1, gate.main())
            result = json.loads(report.read_text())
            self.assertFalse(result["passed"])
            self.assertNotEqual("inspected-local-reachable-objects", result["history_status"])

    def test_exception_requires_exact_bytes_and_rule(self):
        data = ("gh" + "p_" + "X" * 36).encode()
        self.scanner.exceptions = [{
            "path": "fixture.txt", "sha256": export.digest(data), "rule": "github-token",
            "rationale": "Reviewed synthetic noncredential fixture, not a real token."}]
        self.scan(data)
        self.assertFalse(self.scanner.findings)
        self.scan(data + b"changed")
        self.assertTrue(self.scanner.findings)

    def test_truncation(self):
        with self.assertRaises(gate.GateError):
            self.scanner.stream(io.BytesIO(b"one"), "fixture.txt", 4)


class ExportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = Path(__file__).resolve().parents[1]
        cls.files, cls.originals, cls.outputs, cls.replacements = export.derive(cls.repo)

    def test_deterministic_export(self):
        self.assertEqual(self.outputs, export.derive(self.repo)[2])

    def test_intro_manifest_pin_and_size(self):
        name = "app/src/main/assets/intro-oracle/manifest.json"
        contract = (self.repo / "app/src/main/java/com/simple/videoeditor/oracle/"
                    "IntroOracleContract.java").read_text()
        self.assertIn(export.digest(self.outputs[name]), contract)
        self.assertIn('"manifest.json", ' + str(len(self.outputs[name])) + ",", contract)

    def test_stale_intro_pin_is_rejected(self):
        contract = self.repo / "app/src/main/java/com/simple/videoeditor/oracle/IntroOracleContract.java"
        manifest = "app/src/main/assets/intro-oracle/manifest.json"
        read_bytes = Path.read_bytes
        def stale_read(path):
            data = read_bytes(path)
            if path == contract:
                data = data.replace(export.digest(self.outputs[manifest]).encode(),
                                    export.digest(self.originals[manifest]).encode())
            return data
        with patch.object(Path, "read_bytes", stale_read), self.assertRaisesRegex(
                ValueError, "Stale public pin consumer"):
            export.run(self.repo)

    def test_closed_schema(self):
        raw = self.originals[export.PROVENANCE + "handoff.json"]
        with self.assertRaises(ValueError):
            export.sanitize("handoff.json", raw + b" ", "unused")

    def test_public_deny_fields(self):
        for value in ({"device_" + "serial": "fake"}, {"path": "file:" + "///private"},
                      {"path": "content:" + "//private"}, {"auth_" + "token": "fake"}):
            with self.assertRaises(ValueError):
                export.public_strings(value)

    def test_all_measurements_and_assertions_unchanged(self):
        def measurements(value, path=()):
            if isinstance(value, dict):
                for key, child in value.items():
                    if key == "bytes" and "sha256" in value:
                        continue
                    if key == "total_bytes_excluding_inventory":
                        continue
                    yield from measurements(child, path + (key,))
            elif isinstance(value, list):
                for index, child in enumerate(value):
                    yield from measurements(child, path + (index,))
            elif not isinstance(value, str):
                yield path, value
        for name, raw in self.originals.items():
            self.assertEqual(list(measurements(json.loads(raw))),
                             list(measurements(json.loads(self.outputs[name]))), name)

    def test_original_mapping_exactly_44_string_leaves(self):
        count = 0

        def differences(a, b):
            if isinstance(a, dict):
                return sum(differences(a[key], b[key]) for key in a)
            if isinstance(a, list):
                return sum(differences(x, y) for x, y in zip(a, b))
            return int(a != b)
        root = json.loads(self.originals[export.PROVENANCE + "handoff.json"])["root"]
        for name in export.ORIGINAL_PINS:
            raw = self.originals[export.PROVENANCE + name]
            count += differences(json.loads(raw), export.sanitize(name, raw, root))
        self.assertEqual(44, count)


if __name__ == "__main__":
    unittest.main()
