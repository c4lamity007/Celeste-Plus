from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import struct
import tempfile
from pathlib import Path, PurePosixPath, PureWindowsPath


MAX_BINARY_BYTES = 128 * 1024 * 1024
# Windows paths are case insensitive; POSIX roots are not. In particular,
# the relative macOS JDK suffix Contents/Home is not an absolute /home path.
HOME_PATH = re.compile(rb"(?<![A-Za-z0-9_./\\-])(?:(?i:[a-z]:[\\/]Users[\\/][^\x00\r\n\\/]{1,128}[\\/])|/(?:Users|home)/[^\x00\r\n/]{1,128}/)")
UTF16_ASCII = re.compile(rb"(?:[\x20-\x7e]\x00){4,}")
REMOVABLE_DEBUG_TYPES = frozenset((2, 13, 16))  # CodeView, POGO, reproducibility.


def pe_checksum(data: bytes, checksum_offset: int) -> int:
    """PE checksum, excluding its own DWORD (not an Authenticode signature)."""
    total = 0
    for offset in range(0, len(data), 2):
        if checksum_offset <= offset < checksum_offset + 4:
            continue
        total += data[offset] | ((data[offset + 1] if offset + 1 < len(data) else 0) << 8)
        total = (total & 0xffff) + (total >> 16)
    total = (total & 0xffff) + (total >> 16)
    return total + len(data)


def strip_debug_bytes(data: bytes) -> bytes:
    """Remove reviewed debug records from an unsigned PE; reject ambiguous layouts.

    LLVM strip does not remove POGO records embedded in a live .rdata section.
    Only debug payloads, their directory and its header pointer are zeroed here;
    the PE checksum is then updated. This must run before protection and signing.
    """
    inspected = inspect_bytes(data)
    if inspected["certificate_table_present"]:
        raise ValueError("Refusing to strip a certificate-bearing executable; strip before signing")
    pe, = struct.unpack_from("<I", data, 60)
    _, section_count, _, symbols, symbol_count, optional_size, _ = struct.unpack_from("<HHIIIHH", data, pe + 4)
    if symbols or symbol_count:
        raise ValueError("Strip COFF symbols with LLVM before removing PE debug metadata")
    optional = pe + 24
    checksum_offset = optional + 64
    headers_size, = struct.unpack_from("<I", data, optional + 60)
    section_table = optional + optional_size
    if not section_table + section_count * 40 <= headers_size <= len(data):
        raise ValueError("Invalid PE header extent")
    directory_count, = struct.unpack_from("<I", data, optional + 108)

    def overlap(first, second):
        return first[0] < second[1] and second[0] < first[1]

    sections = []
    for index in range(section_count):
        base = section_table + index * 40
        virtual_size, address, raw_size, raw_start, relocations, lines, relocation_count, line_count, flags = struct.unpack_from("<IIIIIIHHI", data, base + 8)
        extent = max(virtual_size, raw_size)
        if address < headers_size or address + extent > 0x100000000:
            raise ValueError("Invalid PE section address")
        if relocations or lines or relocation_count or line_count:
            raise ValueError("Unreviewed PE section relocation or line metadata")
        if raw_size and (raw_start < headers_size or raw_start > len(data) - raw_size):
            raise ValueError("Invalid PE section file extent")
        section = (address, extent, raw_start, raw_size, flags)
        for previous in sections:
            if (extent and previous[1] and overlap((address, address + extent), (previous[0], previous[0] + previous[1]))) or (raw_size and previous[3] and overlap((raw_start, raw_start + raw_size), (previous[2], previous[2] + previous[3]))):
                raise ValueError("Overlapping PE sections")
        sections.append(section)

    def mapped_range(address, size):
        matches = [(start + address - rva, flags) for rva, _, start, raw_size, flags in sections
                   if address >= rva and address - rva <= raw_size and size <= raw_size - (address - rva)]
        if size <= 0 or address + size > 0x100000000 or len(matches) != 1:
            raise ValueError("Unmapped or ambiguous PE metadata range")
        start, flags = matches[0]
        return (start, start + size), flags

    protected_ranges = [(0, headers_size)]
    debug_range = None
    for index in range(directory_count):
        address, size = struct.unpack_from("<II", data, optional + 112 + index * 8)
        if bool(address) != bool(size):
            raise ValueError("Inconsistent PE data directory")
        if not size:
            continue
        if index == 4:
            raise ValueError("Refusing to strip a certificate-bearing executable")
        region, flags = mapped_range(address, size)
        if index == 6:
            if flags & (0x20000000 | 0x80000000):
                raise ValueError("Debug directory overlaps executable or writable data")
            debug_range = region
        else:
            protected_ranges.append(region)
    if debug_range is None:
        return data

    erasures = [debug_range]
    for entry in range(debug_range[0], debug_range[1], 28):
        kind, size, address, offset = struct.unpack_from("<IIII", data, entry + 12)
        if kind not in REMOVABLE_DEBUG_TYPES:
            raise ValueError("Unreviewed PE debug type; refusing to modify executable")
        if not size:
            if address or offset:
                raise ValueError("Empty debug payload has a nonempty pointer")
            continue
        if not address:
            # Unmapped overlay payloads need separate review; current MSVC
            # candidates keep both the directory and records in .rdata.
            raise ValueError("Unmapped debug payload is not eligible for stripping")
        region, flags = mapped_range(address, size)
        if region[0] != offset or flags & (0x20000000 | 0x80000000):
            raise ValueError("Debug payload overlaps executable/writable data or has inconsistent pointers")
        if any(overlap(region, existing) for existing in erasures):
            raise ValueError("Overlapping or shared PE debug payloads")
        erasures.append(region)
    if any(overlap(region, protected) for region in erasures for protected in protected_ranges):
        raise ValueError("Debug metadata overlaps another PE data directory")
    result = bytearray(data)
    for start, end in erasures:
        result[start:end] = bytes(end - start)
    struct.pack_into("<II", result, optional + 112 + 6 * 8, 0, 0)
    struct.pack_into("<I", result, checksum_offset, pe_checksum(result, checksum_offset))
    cleaned = bytes(result)
    if inspect_bytes(cleaned)["debug_types"]:
        raise ValueError("Debug removal did not clear the debug directory")
    return cleaned


def strip_debug(path: Path) -> dict:
    """Atomically replace an unsigned candidate after every validation succeeds."""
    if path.is_symlink():
        raise ValueError("Refusing to replace a symlink")
    with path.open("rb") as source:
        data = source.read(MAX_BINARY_BYTES + 1)
    cleaned = strip_debug_bytes(data)
    if cleaned != data:
        temporary = None
        try:
            with tempfile.NamedTemporaryFile(dir=path.parent, prefix=path.name + ".metadata-", delete=False) as target:
                temporary = Path(target.name)
                target.write(cleaned)
                target.flush()
                os.fsync(target.fileno())
            if path.read_bytes() != data:
                raise ValueError("Candidate changed during metadata removal")
            os.replace(temporary, path)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    return {"before_sha256": hashlib.sha256(data).hexdigest(), "sha256": hashlib.sha256(cleaned).hexdigest(),
            "size": len(cleaned), "removed_debug_types": inspect_bytes(data)["debug_types"],
            "scope": "Unsigned PE metadata removal only; not protection or signing"}


def inspect_bytes(data: bytes, forbidden_prefixes: tuple[str, ...] = ()) -> dict:
    if not 64 <= len(data) <= MAX_BINARY_BYTES or data[:2] != b"MZ":
        raise ValueError("Expected a bounded Windows PE executable")

    def require(offset: int, size: int) -> None:
        if offset < 0 or size < 0 or offset > len(data) - size:
            raise ValueError("Truncated PE metadata")

    def unpack(fmt: str, offset: int):
        require(offset, struct.calcsize(fmt))
        return struct.unpack_from(fmt, data, offset)

    pe, = unpack("<I", 60)
    require(pe, 24)
    if data[pe:pe + 4] != b"PE\0\0":
        raise ValueError("Invalid PE signature")
    machine, section_count, _, symbols, symbol_count, optional_size, _ = unpack("<HHIIIHH", pe + 4)
    optional = pe + 24
    require(optional, optional_size)
    if machine != 0x8664 or optional_size < 112 or unpack("<H", optional)[0] != 0x20B:
        raise ValueError("Expected PE32+ x64")
    directories, = unpack("<I", optional + 108)
    if directories > (optional_size - 112) // 8 or not 1 <= section_count <= 96:
        raise ValueError("Invalid PE directory or section count")
    section_table = optional + optional_size
    require(section_table, section_count * 40)
    sections = []
    for index in range(section_count):
        base = section_table + index * 40
        virtual_size, address, raw_size, raw_offset = unpack("<IIII", base + 8)
        if raw_size:
            require(raw_offset, raw_size)
        sections.append((address, virtual_size, raw_offset, raw_size))

    def directory(index: int) -> tuple[int, int]:
        return unpack("<II", optional + 112 + index * 8) if index < directories else (0, 0)

    def raw_offset(address: int, size: int) -> int:
        matches = [offset + address - base for base, _, offset, raw_size in sections
                   if address >= base and address - base <= raw_size and size <= raw_size - (address - base)]
        if len(matches) != 1:
            raise ValueError("Unmapped or ambiguous PE debug directory")
        require(matches[0], size)
        return matches[0]

    debug_address, debug_size = directory(6)
    if bool(debug_address) != bool(debug_size) or debug_size % 28 or debug_size > 28 * 1024:
        raise ValueError("Invalid PE debug directory")
    debug_types = []
    if debug_size:
        start = raw_offset(debug_address, debug_size)
        for entry in range(start, start + debug_size, 28):
            kind, payload_size, payload_address, payload_offset = unpack("<IIII", entry + 12)
            if payload_size:
                require(payload_offset, payload_size)
                if payload_address and raw_offset(payload_address, payload_size) != payload_offset:
                    raise ValueError("Inconsistent PE debug payload address")
            debug_types.append(kind)
    certificate_offset, certificate_size = directory(4)
    if bool(certificate_offset) != bool(certificate_size):
        raise ValueError("Invalid PE certificate table")
    if certificate_size:
        require(certificate_offset, certificate_size)
    # Decode actual ASCII UTF-16 runs instead of joining unrelated binary bytes
    # by deleting all NULs, which can manufacture paths that were never stored.
    home_paths = bool(HOME_PATH.search(data) or any(HOME_PATH.search(match.group()[::2]) for match in UTF16_ASCII.finditer(data)))
    matched_prefixes = []
    lowered = data.lower() if forbidden_prefixes else b""
    for index, prefix in enumerate(forbidden_prefixes):
        if len(prefix) < 4 or not (PureWindowsPath(prefix).is_absolute() or PurePosixPath(prefix).is_absolute()):
            raise ValueError("Forbidden build prefix must be an absolute path, not an empty/broad marker")
        forms = {prefix, prefix.replace("\\", "/"), prefix.replace("/", "\\")}
        if any(value.encode("utf-8").lower() in lowered
               or value.encode("utf-16le").lower() in lowered for value in forms):
            matched_prefixes.append(index)
    findings = []
    if any(kind != 16 for kind in debug_types):
        findings.append("debug_directory_present")
    if symbols or symbol_count:
        findings.append("coff_symbols_present")
    if home_paths:
        findings.append("absolute_user_build_path_present")
    if matched_prefixes:
        findings.append("configured_build_prefix_present")
    return {
        "schema": "celeste-windows-release-metadata-1",
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
        "debug_types": debug_types,
        "certificate_table_present": bool(certificate_size),
        "authenticode_verified": False,
        "matched_prefix_indexes": matched_prefixes,
        "findings": findings,
        "passed": not findings,
        "scope": "Static metadata hygiene only; not code-signature, auth, virtualization or runtime verification",
    }


def inspect(path: Path, forbidden_prefixes: tuple[str, ...] = ()) -> dict:
    with path.open("rb") as source:
        data = source.read(MAX_BINARY_BYTES + 1)
    return inspect_bytes(data, forbidden_prefixes)


def main() -> int:
    parser = argparse.ArgumentParser(description="Check Windows release metadata without executing the binary")
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--forbid-prefix", action="append", default=[])
    parser.add_argument("--strip-reviewed-debug", action="store_true", help="Remove reviewed debug records from an unsigned candidate before protection/signing")
    args = parser.parse_args()
    if args.strip_reviewed_debug and args.forbid_prefix:
        parser.error("Run the forbidden-prefix audit separately after debug removal")
    try:
        result = strip_debug(args.binary) if args.strip_reviewed_debug else inspect(args.binary, tuple(args.forbid_prefix))
    except (OSError, ValueError) as error:
        parser.exit(2, str(error) + "\n")
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if args.strip_reviewed_debug or result["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
