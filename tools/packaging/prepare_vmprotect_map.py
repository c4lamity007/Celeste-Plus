"""Derive seven readable MAP labels without changing executable bytes.

This records target selection only. It neither configures VMProtect nor proves
that a protector transformed any function. Private maps must stay unpublished.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import struct

MAX_BINARY = 128 * 1024 * 1024
MAX_MAP = 32 * 1024 * 1024
MAX_RECORD = 1024 * 1024
MAX_FUNCTIONS = 65536
TARGETS = (
    ("15native_manifest6verify", "celeste_verify_native"),
    ("4auth12verify_token", "celeste_verify_token"),
    ("4dpop15require_binding", "celeste_require_dpop"),
    ("6attach17validate_endpoint", "celeste_check_endpoint"),
    ("6attach17verify_inspection", "celeste_check_attach"),
    ("8delivery15verify_manifest", "celeste_verify_catalog"),
    ("8delivery16verify_installer", "celeste_verify_grant"),
)
SYMBOL = re.compile(rb"(?m)^[ \t]+([0-9a-fA-F]{4}):([0-9a-fA-F]{8})[ \t]+([^ \t\r\n]+)[ \t]+([0-9a-fA-F]{16})(?=[ \t\r\n]|$)([^\r\n]*)")


def _sha(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _read(path: Path, maximum: int) -> bytes:
    with Path(path).open("rb") as stream:
        value = stream.read(maximum + 1)
    if not value or len(value) > maximum:
        raise ValueError("Missing, empty or oversized mapping input")
    return value


def _json(value: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, item in pairs:
            if key in result:
                raise ValueError("Duplicate mapping evidence field")
            result[key] = item
        return result

    def invalid_constant(_):
        raise ValueError("Non-finite mapping evidence number")

    try:
        result = json.loads(value.decode("utf-8-sig"), object_pairs_hook=unique,
                            parse_constant=invalid_constant)
    except (UnicodeError, json.JSONDecodeError) as error:
        raise ValueError("Invalid mapping evidence JSON") from error
    if not isinstance(result, dict):
        raise ValueError("Mapping evidence must be an object")
    return result


class _Pe:
    def __init__(self, data: bytes):
        self.data = data
        if not 64 <= len(data) <= MAX_BINARY or data[:2] != b"MZ":
            raise ValueError("Expected a bounded Windows PE executable")
        pe, = self.unpack("<I", 60)
        if pe < 64 or self.read(pe, 4) != b"PE\0\0":
            raise ValueError("Invalid PE signature")
        machine, count, _, _, _, optional_size, _ = self.unpack("<HHIIIHH", pe + 4)
        optional = pe + 24
        self.read(optional, optional_size)
        if machine != 0x8664 or optional_size < 112 or self.unpack("<H", optional)[0] != 0x20b:
            raise ValueError("Expected PE32+ x64")
        directories, = self.unpack("<I", optional + 108)
        if not 5 <= directories <= (optional_size - 112) // 8 or not 1 <= count <= 96:
            raise ValueError("Invalid PE directories or sections")
        self.image_base, = self.unpack("<Q", optional + 24)
        self.image_size, headers_size = self.unpack("<II", optional + 56)
        table = optional + optional_size
        if not table + count * 40 <= headers_size <= len(data):
            raise ValueError("Invalid PE header extent")
        self.sections = []
        for index in range(count):
            at = table + index * 40
            virtual_size, rva, raw_size, offset = self.unpack("<IIII", at + 8)
            flags, = self.unpack("<I", at + 36)
            extent = max(virtual_size, raw_size)
            if rva < headers_size or rva + extent > self.image_size:
                raise ValueError("Invalid PE section address")
            if raw_size:
                self.read(offset, raw_size)
                if offset < headers_size:
                    raise ValueError("PE section overlaps headers")
            for previous in self.sections:
                if extent and previous[1] and _overlap((rva, rva + extent), (previous[0], previous[0] + previous[1])):
                    raise ValueError("Overlapping PE virtual sections")
                if raw_size and previous[3] and _overlap((offset, offset + raw_size), (previous[2], previous[2] + previous[3])):
                    raise ValueError("Overlapping PE file sections")
            self.sections.append((rva, extent, offset, raw_size, flags))
        certificate = self.unpack("<II", optional + 112 + 4 * 8)
        if certificate != (0, 0):
            raise ValueError("Expected unsigned candidate; certificate table is present")
        exception_rva, exception_size = self.unpack("<II", optional + 112 + 3 * 8)
        if not exception_rva or not exception_size or exception_size % 12 or exception_size > 12 * MAX_FUNCTIONS:
            raise ValueError("Missing or malformed PE exception directory")
        exception_offset, _ = self.raw(exception_rva, exception_size)
        self.functions = {}
        previous_end = 0
        for offset in range(exception_offset, exception_offset + exception_size, 12):
            begin, end, unwind = self.unpack("<III", offset)
            if begin < previous_end or end <= begin or end > self.image_size:
                raise ValueError("Unsorted, overlapping or malformed PE function ranges")
            _, section = self.raw(begin, end - begin)
            if not section[4] & 0x20000000:
                raise ValueError("PE function range is not executable")
            self.read(self.raw(unwind, 4)[0], 4)
            self.functions[begin] = (end, unwind)
            previous_end = end

    def read(self, offset: int, size: int) -> bytes:
        if offset < 0 or size < 0 or offset > len(self.data) - size:
            raise ValueError("Truncated PE data")
        return self.data[offset:offset + size]

    def unpack(self, fmt: str, offset: int):
        return struct.unpack(fmt, self.read(offset, struct.calcsize(fmt)))

    def raw(self, address: int, size: int):
        matches = [(offset + address - rva, section) for section in self.sections
                   for rva, _, offset, raw_size, _ in [section]
                   if address >= rva and address - rva <= raw_size and size <= raw_size - (address - rva)]
        if size <= 0 or len(matches) != 1:
            raise ValueError("Unmapped or ambiguous PE RVA")
        self.read(matches[0][0], size)
        return matches[0]


def _overlap(first, second):
    return first[0] < second[1] and second[0] < first[1]


def _derive(binary: bytes, original_map: bytes, candidate_bytes: bytes) -> tuple[bytes, dict]:
    candidate = _json(candidate_bytes)
    if (candidate.get("schema") != "celeste-windows-metadata-candidate-1"
            or candidate.get("product") != "celeste_plus"
            or candidate.get("target") != "x86_64-pc-windows-msvc"
            or candidate.get("artifact") != "Celeste+.exe"
            or candidate.get("status") != "unsigned-local-candidate"
            or candidate.get("metadataCheck") != "passed"
            or type(candidate.get("size")) is not int
            or candidate["size"] != len(binary)
            or candidate.get("sha256") != _sha(binary)
            or candidate.get("privateMapSha256") != _sha(original_map)):
        raise ValueError("Candidate does not bind this unsigned Plus executable and original MAP")
    if len(original_map) > MAX_MAP or b"\0" in original_map:
        raise ValueError("Invalid or oversized MSVC MAP")
    pe = _Pe(binary)
    bases = re.findall(rb"(?m)^[ \t]*Preferred load address is ([0-9a-fA-F]{16})[ \t]*\r?$", original_map)
    if len(bases) != 1 or int(bases[0], 16) != pe.image_base:
        raise ValueError("MAP preferred load address differs from the PE")
    entries = list(SYMBOL.finditer(original_map))
    replacements = []
    functions = []
    selected = set()
    for suffix, alias in TARGETS:
        wanted = re.compile(rb"_RNvNtC[A-Za-z0-9_]+_11CelestePlus" + suffix.encode("ascii"))
        found = [entry for entry in entries if wanted.fullmatch(entry[3])]
        if len(found) != 1:
            raise ValueError("Each approved Rust target must have exactly one original MAP symbol")
        entry = found[0]
        if not re.match(rb"[ \t]+f(?:[ \t]|$)", entry[5]):
            raise ValueError("Target MAP symbol is not a function")
        if re.search(rb"(?<!\S)" + alias.encode("ascii") + rb"(?!\S)", original_map):
            raise ValueError("Alias already exists in the original MAP")
        segment, segment_offset, address = (int(entry[index], 16) for index in (1, 2, 4))
        rva = address - pe.image_base
        if not 1 <= segment <= len(pe.sections) or rva != pe.sections[segment - 1][0] + segment_offset:
            raise ValueError("MAP segment offset and virtual address disagree")
        if rva not in pe.functions or rva in selected:
            raise ValueError("Target does not uniquely begin a PE exception function range")
        end, unwind = pe.functions[rva]
        raw, section = pe.raw(rva, end - rva)
        if not section[4] & 0x20000000 or section[4] & 0x80000000:
            raise ValueError("Target code is not read-only executable data")
        if any(other is not entry and address <= int(other[4], 16) < pe.image_base + end for other in entries):
            raise ValueError("Target has aliased or interior MAP symbols")
        unwind_offset, _ = pe.raw(unwind, 4)
        version_flags, prolog, codes, _ = pe.unpack("<BBBB", unwind_offset)
        # The reviewed functions have simple unwind v1 records. Refuse chained
        # or handler-bearing fragments rather than guessing complete boundaries.
        if version_flags != 1 or prolog > end - rva:
            raise ValueError("Target has an unreviewed exception/unwind layout")
        pe.raw(unwind, (4 + codes * 2 + 3) & ~3)
        selected.add(rva)
        symbol = entry[3].decode("ascii")
        replacements.append((entry.start(3), entry.end(3), alias.encode("ascii").ljust(len(entry[3]))))
        functions.append({"symbol": symbol, "alias": alias, "segment": f"{segment:04x}",
                          "segment_offset": f"{segment_offset:08x}", "address": f"{address:016x}",
                          "rva": f"{rva:08x}", "end_rva": f"{end:08x}", "size": end - rva,
                          "unwind_rva": f"{unwind:08x}", "region_sha256": _sha(binary[raw:raw + end - rva])})
    aliased = original_map
    for start, end, replacement in sorted(replacements, reverse=True):
        aliased = aliased[:start] + replacement + aliased[end:]
    record = {"schema": "celeste-vmp-map-alias-1", "product": "celeste_plus", "platform": "windows-x64",
              "input_sha256": _sha(binary), "binary_size": len(binary), "candidate_sha256": _sha(candidate_bytes),
              "original_map_sha256": _sha(original_map), "aliased_map_sha256": _sha(aliased),
              "functions": functions, "status": "mapping-only-not-protection-evidence"}
    return aliased, record


def validate_alias_map(binary: Path, original_map: Path, alias_map: Path,
                       mapping_record: Path, candidate: Path) -> dict:
    """Re-derive all evidence; never infer transformed-code coverage from aliases."""
    derived, expected = _derive(_read(binary, MAX_BINARY), _read(original_map, MAX_MAP),
                                _read(candidate, MAX_RECORD))
    if _read(alias_map, MAX_MAP) != derived:
        raise ValueError("Derived MAP changed beyond the seven approved label replacements")
    record = _json(_read(mapping_record, MAX_RECORD))
    # Canonical JSON comparison also distinguishes integer fields from booleans.
    if json.dumps(record, sort_keys=True) != json.dumps(expected, sort_keys=True):
        raise ValueError("Mapping record differs from the executable/MAP/candidate evidence")
    return expected


def prepare(binary: Path, original_map: Path, candidate: Path, output: Path) -> dict:
    """Write a fresh, self-contained mapping bundle after validating every input."""
    binary_bytes = _read(binary, MAX_BINARY)
    map_bytes = _read(original_map, MAX_MAP)
    candidate_bytes = _read(candidate, MAX_RECORD)
    aliased, record = _derive(binary_bytes, map_bytes, candidate_bytes)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    for name, value in (("Celeste+.exe", binary_bytes), ("Celeste+.map", aliased),
                        ("Celeste+.original.map", map_bytes), ("candidate.json", candidate_bytes),
                        ("mapping.json", (json.dumps(record, indent=2) + "\n").encode("utf-8"))):
        with (output / name).open("xb") as stream:
            stream.write(value)
    return validate_alias_map(output / "Celeste+.exe", output / "Celeste+.original.map",
                              output / "Celeste+.map", output / "mapping.json", output / "candidate.json")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("prepare", "validate"):
        command = commands.add_parser(name)
        command.add_argument("--binary", type=Path, required=True)
        command.add_argument("--original-map", type=Path, required=True)
        command.add_argument("--candidate", type=Path, required=True)
        if name == "prepare":
            command.add_argument("--output-directory", type=Path, required=True)
        else:
            command.add_argument("--alias-map", type=Path, required=True)
            command.add_argument("--mapping-record", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "prepare":
            record = prepare(args.binary, args.original_map, args.candidate, args.output_directory)
        else:
            record = validate_alias_map(args.binary, args.original_map, args.alias_map, args.mapping_record, args.candidate)
    except (OSError, ValueError, struct.error) as error:
        parser.exit(2, f"Alias mapping rejected: {error}\n")
    print(json.dumps({"status": record["status"], "input_sha256": record["input_sha256"],
                      "aliased_map_sha256": record["aliased_map_sha256"], "functions": len(record["functions"])}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
