"""Validate an operator-reviewed private toolchain bundle before executing it.

This does not ship a protector or infer protection from strings/section names.
The bundle pin covers its adapter; the committed policy separately approves the
protector, configuration and verifier for each product's Windows executable.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import re
import stat
import zipfile
import native_protection_gate as gate


def contained(root: Path, name: str) -> Path:
    if not isinstance(name, str) or not name or "\\" in name or ":" in name:
        raise ValueError("Invalid toolchain bundle path")
    target = (root / name).resolve()
    if not target.is_relative_to(root.resolve()) or target == root.resolve() or ".." in Path(name).parts:
        raise ValueError("Toolchain entry escapes its staging directory")
    return target


def verify(archive: Path, expected: str, destination: Path) -> dict:
    # Hash and extract the same immutable, bounded bytes. Reopening the path
    # after hashing could otherwise execute an adapter from a replaced ZIP.
    with archive.open("rb") as source:
        snapshot = source.read(512 * 1024 * 1024 + 1)
    if (len(snapshot) > 512 * 1024 * 1024 or not re.fullmatch(r"[0-9a-f]{64}", expected)
            or hashlib.sha256(snapshot).hexdigest() != expected):
        raise ValueError("Private protection bundle checksum does not match its reviewed pin")
    policy = gate.read(gate.POLICY_FILE)
    if (policy.get("schema") != "celeste-native-protection-policy-1"
            or policy.get("required_protection") != "native-virtualization"
            or policy.get("environment_policy") != "deny-vm-and-sandbox-v1"
            or not policy.get("approved_protectors")):
        raise ValueError("No native protector is approved; configure and review the real licensed toolchain first")
    if destination.exists():
        raise ValueError("Choose a fresh toolchain staging directory")
    with zipfile.ZipFile(io.BytesIO(snapshot)) as bundle:
        entries = bundle.infolist()
        if not 1 <= len(entries) <= 512 or sum(item.file_size for item in entries) > 512 * 1024 * 1024:
            raise ValueError("Protection bundle exceeds bounds")
        seen = set()
        for item in entries:
            target = contained(destination, item.filename)
            if str(target).lower() in seen or stat.S_ISLNK(item.external_attr >> 16):
                raise ValueError("Duplicate or linked toolchain entry")
            seen.add(str(target).lower())
        destination.mkdir(parents=True)
        bundle.extractall(destination)
    record = gate.read(destination / "bundle.json")
    if record.get("schema") != "celeste-native-protection-bundle-1":
        raise ValueError("Unsupported reviewed protection adapter bundle")
    def checked(entry):
        if not isinstance(entry, dict) or set(entry) != {"path", "sha256"}:
            raise ValueError("Malformed pinned toolchain entry")
        path = contained(destination, entry["path"])
        if gate.sha(path) != entry["sha256"]:
            raise ValueError("Protection toolchain file does not match its reviewed checksum")
        return str(path)
    adapter = checked(record["adapter"])
    if Path(adapter).suffix.lower() != ".ps1" or set(record["products"]) != {"celeste_plus"}:
        raise ValueError("Protection adapter must cover this repository's celeste_plus product")
    products = {}
    for product, files in record["products"].items():
        if set(files) != {"protector", "configuration", "verifier"}:
            raise ValueError("Incomplete native protection toolchain")
        products[product] = {name: checked(entry) for name, entry in files.items()}
        approved = {name + "_sha256": files[name]["sha256"] for name in files}
        approved["platform"] = "windows-x64"
        if approved not in policy["approved_protectors"]:
            raise ValueError("Bundle contains a protector/configuration/verifier absent from the reviewed policy")
    return {"schema": "celeste-native-verified-bundle-1", "archive_sha256": expected,
            "adapter": adapter, "products": products}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--destination", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify(args.archive, args.sha256, args.destination)
        args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    except (ValueError, OSError, KeyError, TypeError, zipfile.BadZipFile):
        parser.exit(1, "Native toolchain bundle verification failed; no adapter may execute.\n")
