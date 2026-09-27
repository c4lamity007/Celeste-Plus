#!/usr/bin/env python3
"""Validate and archive an unsigned Celeste+ native CI candidate."""
from __future__ import annotations

import argparse
import gzip
import hashlib
import io
import json
import os
import plistlib
import re
import struct
import sys
import tarfile
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "packaging"))
from native_protection_gate import validate as validate_native_protection


PLATFORMS = {
    "windows-x64": ("x86_64-pc-windows-msvc", "pe-x86_64"),
    "linux-x64": ("x86_64-unknown-linux-gnu", "elf-x86_64"),
    "macos-arm64": ("aarch64-apple-darwin", "macho-arm64"),
    "macos-x64": ("x86_64-apple-darwin", "macho-x86_64"),
}


def executable_format(data: bytes) -> str:
    if len(data) >= 64 and data[:2] == b"MZ":
        header = struct.unpack_from("<I", data, 0x3C)[0]
        if header + 6 > len(data) or data[header : header + 4] != b"PE\0\0":
            raise ValueError("Malformed PE executable")
        machine = struct.unpack_from("<H", data, header + 4)[0]
        if machine != 0x8664:
            raise ValueError(f"Expected PE x86_64 machine 0x8664, got 0x{machine:04x}")
        return "pe-x86_64"
    if len(data) >= 20 and data[:4] == b"\x7fELF":
        if data[4:6] != b"\x02\x01":
            raise ValueError("Only little-endian 64-bit ELF candidates are supported")
        machine = struct.unpack_from("<H", data, 18)[0]
        if machine != 62:
            raise ValueError(f"Expected ELF x86_64 machine 62, got {machine}")
        return "elf-x86_64"
    if len(data) >= 8 and data[:4] == b"\xcf\xfa\xed\xfe":
        cpu = struct.unpack_from("<I", data, 4)[0]
        if cpu == 0x0100000C:
            return "macho-arm64"
        if cpu == 0x01000007:
            return "macho-x86_64"
        raise ValueError(f"Unsupported 64-bit Mach-O CPU type 0x{cpu:08x}")
    raise ValueError("Candidate is not a supported 64-bit PE, ELF, or Mach-O executable")


def tar_entry(name: str, data: bytes, mode: int) -> tuple[tarfile.TarInfo, io.BytesIO]:
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = mode
    info.mtime = 0
    info.uid = 0
    info.gid = 0
    info.uname = "root"
    info.gname = "root"
    return info, io.BytesIO(data)


def macos_info_plist() -> bytes:
    return plistlib.dumps(
        {
            "CFBundleDisplayName": "Celeste+",
            "CFBundleExecutable": "CelestePlus",
            "CFBundleIdentifier": "dev.celeste.client.plus",
            "CFBundleInfoDictionaryVersion": "6.0",
            "CFBundleName": "Celeste+",
            "CFBundlePackageType": "APPL",
            "CFBundleShortVersionString": "0.1.0",
            "CFBundleVersion": "1",
            "NSHighResolutionCapable": True,
        },
        fmt=plistlib.FMT_XML,
        sort_keys=True,
    )


def package(binary: Path, platform: str, target: str, output: Path, abi_report: Path | None = None,
            *, release_id: str | None = None, protection_receipt: Path | None = None) -> dict[str, object]:
    if release_id is not None and not re.fullmatch(r"celeste-plus-[a-z0-9]+(?:-[a-z0-9]+)*", release_id):
        raise ValueError("Invalid Celeste+ publication release ID")
    expected = PLATFORMS.get(platform)
    if expected is None:
        raise ValueError(f"Unsupported platform label: {platform}")
    if target != expected[0]:
        raise ValueError(f"{platform} requires target {expected[0]}, not {target}")
    binary = binary.resolve(strict=True)
    data = binary.read_bytes()
    if not data or len(data) > 128 * 1024 * 1024:
        raise ValueError("Candidate is empty or exceeds the 128 MiB CI bound")
    actual_format = executable_format(data)
    if actual_format != expected[1]:
        raise ValueError(f"{platform} expected {expected[1]}, got {actual_format}")
    protection = None
    if release_id is not None:
        protection = validate_native_protection(binary, protection_receipt, release_id=release_id,
                                                product="celeste_plus", platform=platform)
        if protection["binary_sha256"] != hashlib.sha256(data).hexdigest():
            raise ValueError("Native executable changed while validating publication evidence")

    report_data = b""
    if abi_report is not None:
        report_data = abi_report.resolve(strict=True).read_bytes()
        if not report_data or len(report_data) > 1024 * 1024:
            raise ValueError("ABI report is empty or exceeds 1 MiB")
    digest = hashlib.sha256(data).hexdigest()
    executable_name = "CelestePlus.exe" if platform == "windows-x64" else "CelestePlus"
    if release_id is not None and not platform.startswith("macos-"):
        executable_name = "Celeste+.exe" if platform == "windows-x64" else "Celeste+"
    artifact_name = "Celeste+.app" if platform.startswith("macos-") else executable_name
    manifest = {
        "schema": "celeste-plus-ci-candidate-1",
        "artifact": artifact_name,
        "executable": executable_name,
        "platform": platform,
        "rust_target": target,
        "executable_format": actual_format,
        "sha256": digest,
        "size": len(data),
        "source_commit": os.environ.get("GITHUB_SHA", ""),
        "distribution_status": "unsigned-test-candidate",
        "limitations": [
            "not signed or notarized",
            "not authenticated against production",
            "not tested against a live Minecraft process",
            "not approved for publication",
        ],
    }
    if release_id is not None:
        # Publication is an explicit caller decision, not evidence of signing or
        # runtime QA. Keep those limitations in the distributed archive.
        manifest["schema"] = "celeste-plus-native-distribution-1"
        manifest["release_id"] = release_id
        manifest["distribution_status"] = "published-preview"
        manifest["native_protection"] = protection
        manifest["limitations"].remove("not approved for publication")
    if report_data:
        manifest["abi_report_sha256"] = hashlib.sha256(report_data).hexdigest()
    manifest_bytes = (json.dumps(manifest, indent=2, sort_keys=True) + "\n").encode()
    output.mkdir(parents=True, exist_ok=False)
    extension = "zip" if platform == "windows-x64" else "tar.gz"
    archive = output / f"{'Celeste+' if release_id else 'CelestePlus'}-{platform}.{extension}"
    if platform.startswith("macos-"):
        resource_root = "Celeste+.app/Contents/Resources"
        entries = [
            ("Celeste+.app/Contents/MacOS/CelestePlus", data, 0o755),
            ("Celeste+.app/Contents/Info.plist", macos_info_plist(), 0o644),
            (f"{resource_root}/build-manifest.json", manifest_bytes, 0o644),
        ]
        if report_data:
            entries.append((f"{resource_root}/native-abi.txt", report_data, 0o644))
    else:
        entries = [(executable_name, data, 0o755), ("build-manifest.json", manifest_bytes, 0o644)]
        if report_data:
            entries.append(("native-abi.txt", report_data, 0o644))
    if extension == "zip":
        with zipfile.ZipFile(archive, "x", zipfile.ZIP_DEFLATED) as bundled:
            for name, value, mode in entries:
                info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                info.external_attr = (0o100000 | mode) << 16
                bundled.writestr(info, value)
    else:
        with archive.open("xb") as raw:
            with gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as compressed:
                with tarfile.open(fileobj=compressed, mode="w") as bundled:
                    for name, value, mode in entries:
                        info, body = tar_entry(name, value, mode)
                        bundled.addfile(info, body)
    archive_digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    (output / f"{archive.name}.sha256").write_text(
        f"{archive_digest}  {archive.name}\n", encoding="ascii"
    )
    (output / "build-manifest.json").write_bytes(manifest_bytes)
    return manifest | {"archive": str(archive), "archive_sha256": archive_digest}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--platform", choices=sorted(PLATFORMS), required=True)
    parser.add_argument("--target", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--abi-report", type=Path)
    parser.add_argument("--release-id", help="Explicitly label an authorized publication; does not assert signing or runtime QA")
    parser.add_argument("--protection-receipt", type=Path, help="Required for publication; binds the protected executable and verification evidence")
    args = parser.parse_args()
    print(json.dumps(package(args.binary, args.platform, args.target, args.output, args.abi_report,
                             release_id=args.release_id, protection_receipt=args.protection_receipt), sort_keys=True))


if __name__ == "__main__":
    main()
