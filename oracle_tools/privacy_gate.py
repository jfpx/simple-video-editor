"""Bounded, offline release gate. No uploads, network, or automatic exceptions.

Current source/APK checks and historical reachable-blob checks are intentionally
separate. A clean current candidate does not authorize publishing old history.
Findings contain rule IDs and hashes, never matching private values.
"""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import subprocess
import sys
import uuid
import zipfile
import zlib
from privacy_export import public_strings

CHUNK = 64 * 1024
MAX_FILE = 512 * 1024 * 1024
MAX_TOTAL = 2 * 1024 * 1024 * 1024
MAX_ENTRIES = 20000
MAX_DEPTH = 3
MAX_CENTRAL = 8 * 1024 * 1024
MAX_RATIO = 2000
MAX_XML_ITEMS = 20000
MAX_XML_READ = 8 * 1024 * 1024
RULES = {
    "user-path": re.compile(r"(?i:[a-z]:[/\\]+Users[/\\]+[^/\\\s\"']+)|"
                            r"(?<![A-Za-z0-9:/])/(?:Users|home)/[^/\s\"']+"),
    "session-path": re.compile(r"(?i)(?:\.copilot[/\\]+|session-state[/\\]+)[^\s\"']+"),
    "github-token": re.compile(r"(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})"),
    "private-key": re.compile(r"-----BEGIN (?:RSA |EC |DSA |OPENSSH |ENCRYPTED )?PRIVATE KEY-----"),
    "credential-url": re.compile(r"(?i)https?://[^/\s:@\"']+:[^/\s@\"']+@"),
    "device-identity": re.compile(
        r"""(?ix)(?:ro\.build\.fingerprint|device_fingerprint|device_serial|android_serial)
        ["']?\s*[:=]\s*["']?[a-z0-9][a-z0-9_./:+-]{7,}"""),
}
FORBIDDEN = re.compile(
    r"(?i)(?:^|/)(?:[^/]*\.(?:jks|keystore|p12|pfx|pem|key|hprof|dmp)|"
    r"(?:logcat|bugreport|tombstone|userlogs|phone-logs|publication-drafts)(?:[./_-]|$)|"
    r"(?:local\.properties|capi\.local\.token|id_rsa|id_ed25519))")
UNSUPPORTED = {".gz", ".tgz", ".bz2", ".xz", ".7z", ".rar", ".tar", ".zst"}
QT_COMPRESSED_EXTENSIONS = {".mmpz"}
ANDROID_BINARY_XML = b"\x03\x00\x08\x00"
ANDROID_STRING_POOL = 0x0001
ANDROID_RESOURCE_MAP = 0x0180
ANDROID_START_NAMESPACE = 0x0100
ANDROID_END_NAMESPACE = 0x0101
ANDROID_START_ELEMENT = 0x0102
ANDROID_END_ELEMENT = 0x0103
ANDROID_CDATA = 0x0104
ANDROID_BINARY_XML_CHUNKS = {
    ANDROID_STRING_POOL,
    ANDROID_RESOURCE_MAP,
    ANDROID_START_NAMESPACE,
    ANDROID_END_NAMESPACE,
    ANDROID_START_ELEMENT,
    ANDROID_END_ELEMENT,
    ANDROID_CDATA,
}
ANDROID_STRING_POOL_UTF8 = 1 << 8
ANDROID_MISSING_REF = 0xFFFFFFFF


class GateError(ValueError):
    pass


class QtCompressedReader:
    """Read one Qt qCompress zlib stream without unbounded output or flush."""

    def __init__(self, stream):
        self.stream = stream
        self.decoder = zlib.decompressobj()
        self.pending = b""
        self.finished = False

    def read(self, size):
        if size <= 0:
            raise GateError("Positive bounded read required")
        output = bytearray()
        limit = min(size, CHUNK)
        while len(output) < limit and not self.finished:
            block = self.pending or self.stream.read(CHUNK)
            if not block:
                raise GateError("Incomplete Qt compressed stream")
            try:
                output.extend(self.decoder.decompress(block, limit - len(output)))
            except zlib.error:
                raise GateError("Invalid Qt compressed stream") from None
            self.pending = self.decoder.unconsumed_tail
            if self.decoder.eof:
                if self.decoder.unused_data or self.stream.read(1):
                    raise GateError("Trailing Qt compressed data")
                self.finished = True
        return bytes(output)


def safe_label(name):
    if any(pattern.search(normalize(name)) for pattern in RULES.values()):
        return "redacted-name-sha256:" + hashlib.sha256(name.encode()).hexdigest()
    return name


def normalize(text):
    text = re.sub(r"\\u([0-9a-fA-F]{4})", lambda m: chr(int(m[1], 16)), text)
    return text.replace("\\/", "/").replace("\\\\", "\\")


def zlib_header(data):
    return (len(data) == 2 and data[0] & 15 == 8 and data[0] >> 4 <= 7
            and int.from_bytes(data, "big") % 31 == 0)


def android_binary_xml(data, suffix):
    return suffix == ".xml" and data.startswith(ANDROID_BINARY_XML)


def qt_compressed_file(data, suffix):
    return suffix in QT_COMPRESSED_EXTENSIONS or zlib_header(data[4:6])


class AndroidBinaryXmlValidator:
    def __init__(self, path, size, on_string=None):
        self.path = path
        self.size = size
        self.on_string = on_string

    def validate(self):
        with self.path.open("rb") as stream:
            chunk_type, header_size, total_size = self._read_chunk_header(stream)
            if chunk_type != 0x0003 or header_size != 8 or total_size != self.size or total_size % 4:
                raise GateError("Invalid Android binary XML")
            offset = 8
            saw_string_pool = False
            saw_resource_map = False
            namespace_depth = 0
            element_depth = 0
            saw_root = False
            string_count = 0
            namespaces = []
            elements = []
            chunk_count = 0
            while offset < self.size:
                chunk_count += 1
                if chunk_count > MAX_XML_ITEMS:
                    raise GateError("Android binary XML parser bound exceeded")
                chunk_start = offset
                chunk_type, chunk_header, chunk_size = self._read_chunk_header(stream)
                if (chunk_type not in ANDROID_BINARY_XML_CHUNKS or chunk_header < 8 or
                        chunk_size < chunk_header or chunk_size % 4 or
                        chunk_start + chunk_size > self.size):
                    raise GateError("Invalid Android binary XML")
                if chunk_type == ANDROID_STRING_POOL:
                    if saw_string_pool or chunk_start != 8:
                        raise GateError("Invalid Android binary XML")
                    string_count = self._validate_string_pool(
                        stream, chunk_start, chunk_header, chunk_size)
                    saw_string_pool = True
                elif not saw_string_pool:
                    raise GateError("Invalid Android binary XML")
                elif chunk_type == ANDROID_RESOURCE_MAP:
                    if saw_resource_map or saw_root or namespace_depth:
                        raise GateError("Invalid Android binary XML")
                    self._validate_resource_map(stream, chunk_header, chunk_size)
                    saw_resource_map = True
                elif chunk_type == ANDROID_START_NAMESPACE:
                    namespaces.append(
                        self._validate_namespace(stream, chunk_header, chunk_size, string_count))
                    namespace_depth += 1
                elif chunk_type == ANDROID_END_NAMESPACE:
                    if namespace_depth <= 0:
                        raise GateError("Invalid Android binary XML")
                    if self._validate_namespace(stream, chunk_header, chunk_size, string_count) != namespaces.pop():
                        raise GateError("Invalid Android binary XML")
                    namespace_depth -= 1
                elif chunk_type == ANDROID_START_ELEMENT:
                    element = self._validate_start_element(stream, chunk_header, chunk_size, string_count)
                    if element_depth == 0 and saw_root:
                        raise GateError("Invalid Android binary XML")
                    elements.append(element)
                    element_depth += 1
                    saw_root = True
                elif chunk_type == ANDROID_END_ELEMENT:
                    if element_depth <= 0:
                        raise GateError("Invalid Android binary XML")
                    if self._validate_end_element(stream, chunk_header, chunk_size, string_count) != elements.pop():
                        raise GateError("Invalid Android binary XML")
                    element_depth -= 1
                else:
                    if element_depth <= 0:
                        raise GateError("Invalid Android binary XML")
                    self._validate_cdata(stream, chunk_header, chunk_size, string_count)
                offset += chunk_size
            if (offset != self.size or not saw_string_pool or not saw_root or
                    namespace_depth or element_depth or namespaces or elements):
                raise GateError("Invalid Android binary XML")

    def _validate_string_pool(self, stream, chunk_start, header_size, chunk_size):
        if header_size != 0x001C:
            raise GateError("Invalid Android binary XML")
        body = self._read_exact(stream, 20)
        string_count, style_count, flags, strings_start, styles_start = struct.unpack("<5I", body)
        if string_count + style_count > MAX_XML_ITEMS:
            raise GateError("Android binary XML parser bound exceeded")
        table_offset = header_size + 4 * (string_count + style_count)
        if strings_start < table_offset or strings_start > chunk_size:
            raise GateError("Invalid Android binary XML")
        if style_count and not styles_start:
            raise GateError("Invalid Android binary XML")
        if not style_count and styles_start:
            raise GateError("Invalid Android binary XML")
        if styles_start and (styles_start < strings_start or styles_start > chunk_size):
            raise GateError("Invalid Android binary XML")
        offsets = [struct.unpack("<I", self._read_exact(stream, 4))[0] for _ in range(string_count)]
        for _ in range(style_count):
            style_offset = struct.unpack("<I", self._read_exact(stream, 4))[0]
            if style_offset >= chunk_size - styles_start:
                raise GateError("Invalid Android binary XML")
        strings_base = chunk_start + strings_start
        strings_limit = chunk_start + (styles_start or chunk_size)
        if stream.tell() > strings_base or strings_limit < strings_base:
            raise GateError("Invalid Android binary XML")
        seen_offsets = set()
        decoded_bytes = 0
        for value in offsets:
            if value in seen_offsets:
                continue
            seen_offsets.add(value)
            if value >= strings_limit - strings_base:
                raise GateError("Invalid Android binary XML")
            text, consumed = self._decode_string(stream, strings_base + value, strings_limit, flags)
            decoded_bytes += consumed
            if decoded_bytes > strings_limit - strings_base:
                raise GateError("Invalid Android binary XML")
            self._emit_string(text)
        stream.seek(chunk_start + chunk_size)
        return string_count

    def _validate_resource_map(self, stream, header_size, chunk_size):
        if header_size != 8 or (chunk_size - 8) % 4:
            raise GateError("Invalid Android binary XML")
        stream.seek(chunk_size - 8, io.SEEK_CUR)

    def _validate_namespace(self, stream, header_size, chunk_size, string_count):
        if header_size != 0x0010 or chunk_size != 0x0018:
            raise GateError("Invalid Android binary XML")
        _, comment = struct.unpack("<2I", self._read_exact(stream, 8))
        prefix, uri = struct.unpack("<2I", self._read_exact(stream, 8))
        self._require_string_ref(comment, string_count, missing=True)
        self._require_string_ref(prefix, string_count, missing=True)
        self._require_string_ref(uri, string_count)
        return prefix, uri

    def _validate_start_element(self, stream, header_size, chunk_size, string_count):
        if header_size != 0x0010:
            raise GateError("Invalid Android binary XML")
        _, comment = struct.unpack("<2I", self._read_exact(stream, 8))
        element_namespace, element_name, attribute_start, attribute_size, attribute_count, id_index, class_index, style_index = (
            struct.unpack("<2I6H", self._read_exact(stream, 20)))
        if (attribute_start != 0x0014 or attribute_size != 0x0014 or
                chunk_size != 0x0024 + attribute_count * attribute_size):
            raise GateError("Invalid Android binary XML")
        self._require_string_ref(comment, string_count, missing=True)
        self._require_string_ref(element_namespace, string_count, missing=True)
        self._require_string_ref(element_name, string_count)
        self._require_attribute_index(id_index, attribute_count)
        self._require_attribute_index(class_index, attribute_count)
        self._require_attribute_index(style_index, attribute_count)
        for _ in range(attribute_count):
            namespace, name, raw_value, value_size, value_zero, value_type, value_data = struct.unpack(
                "<3IHBBI", self._read_exact(stream, attribute_size))
            if value_size != 8 or value_zero != 0:
                raise GateError("Invalid Android binary XML")
            self._require_string_ref(namespace, string_count, missing=True)
            self._require_string_ref(name, string_count)
            self._require_string_ref(raw_value, string_count, missing=True)
            if value_type == 0x03:
                self._require_string_ref(value_data, string_count)
        return element_namespace, element_name

    def _validate_end_element(self, stream, header_size, chunk_size, string_count):
        if header_size != 0x0010 or chunk_size != 0x0018:
            raise GateError("Invalid Android binary XML")
        _, comment = struct.unpack("<2I", self._read_exact(stream, 8))
        namespace, name = struct.unpack("<2I", self._read_exact(stream, 8))
        self._require_string_ref(comment, string_count, missing=True)
        self._require_string_ref(namespace, string_count, missing=True)
        self._require_string_ref(name, string_count)
        return namespace, name

    def _validate_cdata(self, stream, header_size, chunk_size, string_count):
        if header_size != 0x0010 or chunk_size != 0x001C:
            raise GateError("Invalid Android binary XML")
        _, comment = struct.unpack("<2I", self._read_exact(stream, 8))
        data, value_size, value_zero, value_type, value_data = struct.unpack(
            "<IHBBI", self._read_exact(stream, 12))
        self._require_string_ref(comment, string_count, missing=True)
        self._require_string_ref(data, string_count)
        if value_zero != 0:
            raise GateError("Invalid Android binary XML")
        if value_size == 0:
            if value_type or value_data:
                raise GateError("Invalid Android binary XML")
            return
        if value_size != 8:
            raise GateError("Invalid Android binary XML")
        if value_type == 0x03:
            self._require_string_ref(value_data, string_count)

    def _decode_string(self, stream, offset, limit, flags):
        stream.seek(offset)
        start = offset
        if flags & ANDROID_STRING_POOL_UTF8:
            utf16_length = self._read_length8(stream, limit)
            encoded_length = self._read_length8(stream, limit)
            data = self._read_exact_limited(stream, encoded_length, limit)
            if self._read_exact_limited(stream, 1, limit) != b"\x00":
                raise GateError("Invalid Android binary XML")
            try:
                text = data.decode("utf-8", "surrogatepass")
            except UnicodeDecodeError:
                raise GateError("Invalid Android binary XML") from None
            if len(text.encode("utf-16le", "surrogatepass")) // 2 != utf16_length:
                raise GateError("Invalid Android binary XML")
            return text, stream.tell() - start
        encoded_length = self._read_length16(stream, limit)
        data = self._read_exact_limited(stream, encoded_length * 2, limit)
        if self._read_exact_limited(stream, 2, limit) != b"\x00\x00":
            raise GateError("Invalid Android binary XML")
        try:
            text = data.decode("utf-16le")
        except UnicodeDecodeError:
            raise GateError("Invalid Android binary XML") from None
        if len(data) // 2 != encoded_length:
            raise GateError("Invalid Android binary XML")
        return text, stream.tell() - start

    def _read_length8(self, stream, limit):
        first = self._read_exact_limited(stream, 1, limit)[0]
        if first & 0x80:
            return ((first & 0x7F) << 8) | self._read_exact_limited(stream, 1, limit)[0]
        return first

    def _read_length16(self, stream, limit):
        first = struct.unpack("<H", self._read_exact_limited(stream, 2, limit))[0]
        if first & 0x8000:
            return ((first & 0x7FFF) << 16) | struct.unpack(
                "<H", self._read_exact_limited(stream, 2, limit))[0]
        return first

    def _emit_string(self, text):
        if self.on_string is not None:
            self.on_string(text)

    def _require_attribute_index(self, index, count):
        if index > count:
            raise GateError("Invalid Android binary XML")

    def _require_string_ref(self, value, string_count, missing=False):
        if missing and value == ANDROID_MISSING_REF:
            return
        if value >= string_count:
            raise GateError("Invalid Android binary XML")

    def _read_chunk_header(self, stream):
        return struct.unpack("<HHI", self._read_exact(stream, 8))

    def _read_exact_limited(self, stream, size, limit):
        if stream.tell() + size > limit:
            raise GateError("Invalid Android binary XML")
        return self._read_exact(stream, size)

    def _read_exact(self, stream, size):
        if size > MAX_XML_READ:
            raise GateError("Android binary XML parser bound exceeded")
        data = stream.read(size)
        if len(data) != size:
            raise GateError("Invalid Android binary XML")
        return data


class Scanner:
    def __init__(self, scratch, exceptions=()):
        self.scratch = Path(scratch).resolve()
        if not self.scratch.is_dir() or self.scratch.is_symlink():
            raise GateError("Explicit existing nonlinked scratch directory required")
        self.entries = []
        self.findings = []
        self.total = 0
        self.count = 0
        self.exceptions = exceptions
        self.used_exceptions = set()

    def finding(self, name, rule, digest=None):
        for index, exception in enumerate(self.exceptions):
            if (exception["path"] == name and exception["rule"] == rule and
                    exception["sha256"] == digest):
                self.used_exceptions.add(index)
                return
        self.findings.append({"path": safe_label(name), "rule": rule, "sha256": digest})

    def stream(self, stream, name, size, depth=0, *, filename=None, directory=False):
        if directory and size != 0:
            raise GateError("Nonempty archive directory")
        if size < 0 or size > MAX_FILE or self.count >= MAX_ENTRIES:
            raise GateError("File/count bound exceeded")
        self.count += 1
        filename = name if filename is None else filename
        suffix = "" if directory else PurePosixPath(filename.replace("\\", "/")).suffix.lower()
        first = stream.read(1 if directory else CHUNK)
        archived = first.startswith((b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"))
        # Qt has no fixed magic: recognize its zlib header after the size word,
        # including renamed files and another Qt wrapper in the decoded payload.
        android_xml = android_binary_xml(first, suffix)
        qt_compressed = qt_compressed_file(first, suffix)
        if qt_compressed and archived:
            raise GateError("Invalid Qt compressed signature")
        if suffix in {".apk", ".zip", ".jar"} and not archived:
            raise GateError("Invalid archive signature")
        if (suffix in UNSUPPORTED or zlib_header(first[:2]) or
                first.startswith((b"\x1f\x8b", b"7z\xbc\xaf", b"Rar!",
                                  b"BZh", b"\xfd7zXZ\x00"))):
            raise GateError("Unsupported compressed content")
        spool = None
        spool_path = None
        if archived or qt_compressed or android_xml:
            spool_path = self.scratch / ("privacy-entry-" + uuid.uuid4().hex)
            spool = spool_path.open("xb")
        sha = hashlib.sha256()
        seen = set()
        tail = ""
        length = 0
        metadata = bytearray() if suffix == ".json" else None
        try:
            block = first
            while block:
                length += len(block)
                self.total += len(block)
                if length > size or self.total > MAX_TOTAL:
                    raise GateError("Expanded byte bound exceeded")
                sha.update(block)
                if spool:
                    spool.write(block)
                if metadata is not None:
                    if length > 16 * 1024 * 1024:
                        raise GateError("JSON metadata bound exceeded")
                    metadata.extend(block)
                text = tail + block.replace(b"\x00", b"").decode("utf-8", errors="replace")
                for rule, pattern in RULES.items():
                    if pattern.search(normalize(text)):
                        seen.add(rule)
                tail = text[-4096:]
                block = stream.read(CHUNK)
            if length != size:
                raise GateError("Truncated entry")
            digest = sha.hexdigest()
            self.entries.append({"path": safe_label(name), "sha256": digest, "bytes": size})
            if spool:
                spool.close()
                spool = None
            if android_xml:
                def inspect_string(text):
                    decoded = normalize(text)
                    for rule, pattern in RULES.items():
                        if pattern.search(decoded):
                            seen.add(rule)
                try:
                    AndroidBinaryXmlValidator(spool_path, size, inspect_string).validate()
                except GateError:
                    if not qt_compressed:
                        raise
                else:
                    qt_compressed = False
            for rule in sorted(seen):
                self.finding(name, rule, digest)
            if metadata is not None:
                try:
                    document = json.loads(metadata)
                except (ValueError, RecursionError):
                    raise GateError("Invalid JSON metadata") from None
                try:
                    public_strings(document)
                except ValueError:
                    self.finding(name, "private-metadata", digest)
            for rule, pattern in RULES.items():
                if pattern.search(normalize(filename)):
                    self.finding(name, rule, digest)
            if FORBIDDEN.search(filename.replace("\\", "/")):
                self.finding(name, "forbidden-filename", digest)
            if spool:
                spool.close()
            if spool_path:
                if archived:
                    if depth >= MAX_DEPTH:
                        raise GateError("Nested archive depth exceeded")
                    self.archive(spool_path, name, depth + 1)
                elif qt_compressed:
                    if depth >= MAX_DEPTH:
                        raise GateError("Nested archive depth exceeded")
                    self.qcompress(spool_path, name, filename, depth + 1)
            return digest
        finally:
            if spool and not spool.closed:
                spool.close()
            if spool_path:
                spool_path.unlink(missing_ok=True)

    def qcompress(self, path, name, filename, depth):
        with path.open("rb") as stream:
            header = stream.read(4)
            if len(header) != 4:
                raise GateError("Missing Qt compressed length header")
            size = struct.unpack(">I", header)[0]
            compressed_size = path.stat().st_size - 4
            if size > MAX_FILE or size > max(1, compressed_size) * MAX_RATIO:
                raise GateError("Qt expansion ratio/size exceeded")
            if self.total + size > MAX_TOTAL:
                raise GateError("Expanded byte bound exceeded")
            payload = PurePosixPath(filename.replace("\\", "/")).with_suffix(".mmp").name
            self.stream(QtCompressedReader(stream), name + "!" + payload, size, depth,
                        filename=payload)

    def archive(self, path, name, depth):
        with path.open("rb") as stream:
            stream.seek(max(0, path.stat().st_size - 65557))
            end = stream.read(65557)
        offset = end.rfind(b"PK\x05\x06")
        if offset < 0 or len(end) - offset < 22:
            raise GateError("Missing ZIP directory")
        record = struct.unpack_from("<4s4H2LH", end, offset)
        if record[1] or record[2] or record[3] != record[4] or record[4] == 65535:
            raise GateError("Unsupported multipart/ZIP64 directory")
        if record[5] > MAX_CENTRAL or record[4] + self.count > MAX_ENTRIES:
            raise GateError("ZIP central-directory bound exceeded")
        with zipfile.ZipFile(path) as archive:
            names = set()
            for entry in archive.infolist():
                raw_name = entry.orig_filename
                relative = raw_name.replace("\\", "/")
                parts = PurePosixPath(relative).parts
                if (relative in names or relative.startswith("/") or ":" in relative or
                        ".." in parts or "\x00" in relative):
                    raise GateError("Unsafe/duplicate archive entry")
                names.add(relative)
                mode = entry.external_attr >> 16
                if stat.S_ISLNK(mode) or entry.flag_bits & 1:
                    raise GateError("Linked/encrypted archive entry")
                if entry.compress_type not in {zipfile.ZIP_STORED, zipfile.ZIP_DEFLATED}:
                    raise GateError("Unsupported ZIP compression")
                directory = entry.is_dir()
                if directory and entry.file_size != 0:
                    raise GateError("Nonempty archive directory")
                if (entry.file_size > MAX_FILE or
                        entry.file_size > max(1, entry.compress_size) * MAX_RATIO):
                    raise GateError("Archive expansion ratio/size exceeded")
                with archive.open(entry) as stream:
                    self.stream(stream, name + "!" + raw_name, entry.file_size, depth,
                                filename=raw_name, directory=directory)

    def file(self, path, label):
        path = Path(path)
        if path.is_symlink() or (hasattr(path, "is_junction") and path.is_junction()):
            raise GateError("Linked input")
        with path.open("rb") as stream:
            return self.stream(stream, label, path.stat().st_size)


def command(repo, *args):
    return subprocess.check_output(["git", "-C", str(repo), *args], stderr=subprocess.PIPE)


def load_exceptions(path):
    if not path:
        return []
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    if data.get("version") != 1 or set(data) != {"version", "exceptions"}:
        raise GateError("Invalid exception schema")
    for item in data["exceptions"]:
        if (set(item) != {"path", "sha256", "rule", "rationale"} or
                item["rule"] not in {*RULES, "forbidden-filename", "private-metadata"} or
                not re.fullmatch("[a-f0-9]{64}", item["sha256"]) or
                len(item["rationale"]) < 30 or any(x in item["path"] for x in "*?")):
            raise GateError("Invalid narrowly hashed exception")
    return data["exceptions"]


def scan_tree(scanner, repo):
    paths = command(repo, "ls-files", "-z", "--cached", "--others", "--exclude-standard")
    for relative in sorted(set(paths.decode().split("\x00")) - {""}):
        if relative.startswith(".git/"):
            raise GateError("Unexpected git control file")
        path = repo / relative
        if not path.is_file():
            raise GateError("Missing tracked input")
        scanner.file(path, relative)


def scan_history(scanner, repo, refs):
    if command(repo, "rev-parse", "--is-shallow-repository").strip() != b"false":
        raise GateError("History audit requires complete local history")
    if not refs:
        raise GateError("Explicit outgoing refs or --all-refs required")
    for ref in refs:
        if ref != "--all" and (ref.startswith("-") or not ref.strip()):
            raise GateError("Invalid ref")
    objects = command(repo, "rev-list", "--objects", *refs).decode().splitlines()
    if len(objects) > 100000:
        raise GateError("History object-count bound exceeded")
    ids = [line.partition(" ")[0] for line in objects]
    checked = subprocess.run(
        ["git", "-C", str(repo), "cat-file", "--batch-check"],
        input=("\n".join(ids) + "\n").encode(), stdout=subprocess.PIPE,
        stderr=subprocess.PIPE, check=True).stdout.splitlines()
    if len(checked) != len(objects):
        raise GateError("Incomplete object inventory")
    descriptions = {row.decode().split()[0]: row for row in checked}
    inputs = {}
    for line, description in zip(objects, checked):
        oid, kind, _ = description.decode().split()
        if kind in {"commit", "tag"}:
            inputs[(oid, "")] = description
        elif kind == "tree" and not line.partition(" ")[2]:
            # rev-list gives each blob only one name; inspect every root tree
            # so a rename or a second path cannot hide filename-based policy.
            for entry in command(repo, "ls-tree", "-rz", oid).split(b"\0"):
                if not entry:
                    continue
                header, path = entry.split(b"\t", 1)
                mode, entry_kind, blob = header.decode().split()
                if entry_kind != "blob" or mode == "120000":
                    raise GateError("Linked historical input")
                inputs[(blob, path.decode())] = descriptions[blob]
                if len(inputs) > MAX_ENTRIES:
                    raise GateError("History path-count bound exceeded")
        elif kind == "blob":
            inputs[(oid, line.partition(" ")[2])] = description
    proc = subprocess.Popen(["git", "-C", str(repo), "cat-file", "--batch"],
                            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                            stderr=subprocess.DEVNULL)

    class LimitedReader:
        def __init__(self, remaining):
            self.remaining = remaining

        def read(self, size):
            data = proc.stdout.read(min(size, self.remaining))
            self.remaining -= len(data)
            return data

    try:
        for (oid, filename), description in inputs.items():
            oid, kind, size_text = description.decode().split()
            size = int(size_text)
            if size > MAX_FILE:
                raise GateError("Historical object exceeds bound")
            proc.stdin.write((oid + "\n").encode())
            proc.stdin.flush()
            if proc.stdout.readline().strip() != description:
                raise GateError("Object header mismatch")
            scanner.stream(LimitedReader(size), "git:" + oid + ":" + filename, size,
                           filename=filename)
            if proc.stdout.read(1) != b"\n":
                raise GateError("Object stream framing mismatch")
    finally:
        proc.stdin.close()
        proc.stdout.close()
        proc.wait()
    if proc.returncode:
        raise GateError("Git object read failed")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("current", "history", "artifact"), required=True)
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--artifact", type=Path, action="append", default=[])
    parser.add_argument("--source-only", action="store_true")
    parser.add_argument("--ref", action="append", default=[])
    parser.add_argument("--all-refs", action="store_true")
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    parser.add_argument("--allowlist", type=Path)
    args = parser.parse_args()
    report = {"version": 1, "mode": args.mode, "passed": False,
              "history_status": "not-inspected" if args.mode != "history" else "incomplete",
              "errors": [], "entries": [], "findings": []}
    try:
        scanner = Scanner(args.scratch, load_exceptions(args.allowlist))
        if ((args.source_only and args.mode != "current") or
                ((args.ref or args.all_refs) and args.mode != "history") or
                (args.mode == "history" and args.artifact) or
                (args.ref and args.all_refs)):
            raise GateError("Options do not match the selected mode")
        if args.mode in {"current", "history"}:
            report["source_head"] = command(args.repo, "rev-parse", "HEAD").decode().strip()
        if args.mode == "current":
            scan_tree(scanner, args.repo.resolve())
            if not args.source_only and not args.artifact:
                raise GateError("Missing rebuilt APK evidence")
            if args.source_only and args.artifact:
                raise GateError("Ambiguous source-only invocation")
        if args.mode == "history":
            report["requested_refs"] = ["--all"] if args.all_refs else args.ref
            report["local_ref_inventory"] = command(
                args.repo, "for-each-ref", "--format=%(refname) %(objectname)").decode().splitlines()
            scan_history(scanner, args.repo.resolve(), ["--all"] if args.all_refs else args.ref)
            report["history_status"] = "inspected-local-reachable-objects"
        if args.mode == "artifact" and not args.artifact:
            raise GateError("Missing artifact")
        for artifact in args.artifact:
            if artifact.suffix.lower() not in {".apk", ".zip"}:
                raise GateError("Unknown release artifact type")
            scanner.file(artifact, artifact.name)
        report["entries"] = scanner.entries
        report["findings"] = scanner.findings
        report["expanded_bytes"] = scanner.total
        report["used_exception_indices"] = sorted(scanner.used_exceptions)
        report["passed"] = not scanner.findings
    except Exception as error:
        report["errors"].append(str(error) if isinstance(error, GateError) else type(error).__name__)
        if "scanner" in locals():
            report["entries"] = scanner.entries
            report["findings"] = scanner.findings
    args.report.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"passed": report["passed"], "mode": args.mode,
                      "findings": len(report["findings"]), "errors": report["errors"]}))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    sys.exit(main())
