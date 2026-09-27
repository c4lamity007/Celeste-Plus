"""Import an independently verified unsigned GUI candidate into the Sign layout.

This does not infer protection from changed bytes, execute a verifier, approve a
tool, sign, or publish. An existing approved verifier must already have issued a
genuine protection receipt plus the additional GUI evidence bindings below.
The existing Sign phase must verify protection again over final signed bytes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import xml.etree.ElementTree as ET

import native_protection_gate as gate
import prepare_vmprotect_map as map_alias
import windows_release_metadata as metadata

ROOT = Path(__file__).resolve().parents[2]
MAX_BINARY = 256 * 1024 * 1024
MAX_MAP = 64 * 1024 * 1024
PRODUCT = "celeste_plus"
PLATFORM = "windows-x64"


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def plain(path):
    path = Path(os.path.abspath(path))
    for current in (path, *path.parents):
        if current.exists() or current.is_symlink():
            info = current.lstat()
            require(not stat.S_ISLNK(info.st_mode) and not getattr(info, "st_file_attributes", 0) & 0x400,
                    "GUI import paths must not traverse links or reparse points")
    return path


def snapshot(path, maximum):
    path = plain(path)
    with path.open("rb") as source:
        before = os.fstat(source.fileno())
        require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum, "Missing or oversized GUI import input")
        data = source.read(maximum + 1)
        after = os.fstat(source.fileno())
    require(len(data) == before.st_size and before.st_size == after.st_size
            and before.st_mtime_ns == after.st_mtime_ns, "GUI import input changed while reading")
    return data


def object_json(data):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "Duplicate GUI import evidence field")
            result[key] = value
        return result
    value = json.loads(data.decode("utf-8-sig"), object_pairs_hook=unique)
    require(isinstance(value, dict), "Expected GUI import evidence object")
    return value


def source_files(root):
    result = set()
    for folder in ("shared-auth", "beta-lab/native-shared/src", "beta-lab/native-launcher/src"):
        directory = root / folder
        require(directory.is_dir(), "Native source folder is unavailable")
        result.update(p.relative_to(root).as_posix() for p in directory.rglob("*.rs"))
    result.update(("beta-lab/native-launcher/Cargo.toml", "beta-lab/native-launcher/Cargo.lock",
                   "beta-lab/native-launcher/assets/celeste-logo.png",
                   "tools/packaging/build-windows-native-candidate.ps1", "tools/packaging/windows_release_metadata.py"))
    return result


def check_sources(record, root):
    entries = record.get("sourceFiles")
    require(isinstance(entries, list), "Candidate source inventory is missing")
    files = {}
    for item in entries:
        require(isinstance(item, dict) and set(item) == {"path", "sha256"}, "Invalid candidate source entry")
        name = item["path"]
        require(isinstance(name, str) and name not in files and "\\" not in name,
                "Duplicate or invalid candidate source path")
        files[name] = item["sha256"]
    require(set(files) == source_files(root), "Candidate source inventory differs from the standalone build inputs")
    for name, expected in files.items():
        require(sha(snapshot(root / name, MAX_MAP)) == expected, "Candidate sources changed after compilation")


def selected_functions(project, map_bytes, input_bytes):
    require(b"<!DOCTYPE" not in project.upper() and b"<!ENTITY" not in project.upper(), "Project entities are forbidden")
    document = ET.fromstring(project)
    require(document.tag == "Document" and len(list(document.iter())) <= 4096, "Unsupported GUI project")
    nodes = list(document.iter("Protection"))
    require(len(nodes) == 1 and nodes[0] in list(document), "Expected one direct project protection section")
    protection = nodes[0]
    # Read-only inspection never executes project Lua or invokes the GUI.
    for script in document.iter("Script"):
        require(not (script.text or "").strip() and not script.get("FileName"), "Scripted GUI projects require a separate reviewed workflow")
    selected = [p for p in protection.findall("./Procedures/Procedure")
                if p.get("IncludedInCompilation", "").lower() == "true"]
    require(1 <= len(selected) <= 256, "GUI project has no bounded explicit function selection")
    symbols = {}
    for match in re.finditer(r"^\s*[0-9A-Fa-f]{4}:[0-9A-Fa-f]{8,16}\s+(\S+)\s+([0-9A-Fa-f]{8,16})\s+f\s", map_bytes.decode("utf-8-sig"), re.M):
        symbols.setdefault(match[1], []).append(int(match[2], 16))
    pe = struct.unpack_from("<I", input_bytes, 60)[0]
    image_base = struct.unpack_from("<Q", input_bytes, pe + 24 + 24)[0]
    optional_size = struct.unpack_from("<H", input_bytes, pe + 20)[0]
    sections = struct.unpack_from("<H", input_bytes, pe + 6)[0]
    executable_ranges = []
    for index in range(sections):
        base = pe + 24 + optional_size + index * 40
        _, address, raw_size, _ = struct.unpack_from("<IIII", input_bytes, base + 8)
        flags = struct.unpack_from("<I", input_bytes, base + 36)[0]
        if flags & 0x20000000:
            executable_ranges.append((image_base + address, image_base + address + raw_size))
    result = []
    for procedure in selected:
        name = procedure.get("MapAddress")
        matches = symbols.get(name, [])
        require(len(matches) == 1 and any(start <= matches[0] < end for start, end in executable_ranges),
                "Selected GUI function does not resolve uniquely into executable input code")
        require(name not in {item["map_symbol"] for item in result}, "Duplicate GUI function selection")
        compilation_type = procedure.get("CompilationType")
        require(isinstance(compilation_type, str) and compilation_type.isdecimal(), "GUI function has no explicit compilation type")
        result.append({"map_symbol": name, "input_va": matches[0], "project_compilation_type": compilation_type})
    return protection, result


def import_candidate(candidate_directory, protected_binary, receipt, compile_evidence, output, *, release_id, root=ROOT,
                     derived_map=None, map_alias_record=None):
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", release_id), "Invalid release identity")
    require((derived_map is None) == (map_alias_record is None), "Derived MAP and its alias record must be supplied together")
    root = plain(root)
    candidate_directory, protected_binary, receipt, compile_evidence, output = map(
        plain, (candidate_directory, protected_binary, receipt, compile_evidence, output))
    require(not output.exists(), "Use a fresh GUI import output directory")
    blobs = {}
    def read(path, limit=1024 * 1024):
        path = plain(path)
        data = snapshot(path, limit)
        blobs[path] = data
        return data
    candidate_bytes = read(candidate_directory / "candidate.json")
    candidate = object_json(candidate_bytes)
    require((candidate.get("schema"), candidate.get("product"), candidate.get("target"), candidate.get("artifact"),
             candidate.get("status"), candidate.get("metadataCheck")) ==
            ("celeste-windows-metadata-candidate-1", PRODUCT, "x86_64-pc-windows-msvc", "Celeste+.exe",
             "unsigned-local-candidate", "passed"), "Candidate is not a verified unsigned Plus Windows input")
    check_sources(candidate, root)
    input_path = candidate_directory / "Celeste+.exe"
    input_bytes = read(input_path, MAX_BINARY)
    protected_bytes = read(protected_binary, MAX_BINARY)
    map_bytes = read(candidate_directory / "Celeste+.map", MAX_MAP)
    require(candidate.get("sha256") == sha(input_bytes) and candidate.get("size") == len(input_bytes)
            and candidate.get("privateMapSha256") == sha(map_bytes), "Candidate executable or private MAP changed")
    require(input_bytes != protected_bytes, "GUI output is identical to unprotected input")
    for data in (input_bytes, protected_bytes):
        inspected = metadata.inspect_bytes(data)
        require(inspected["passed"] and not inspected["certificate_table_present"],
                "Import requires unsigned x64 PE bytes that pass metadata hygiene")
    alias_record = None
    selected_map_bytes = map_bytes
    if derived_map is not None:
        derived_map, map_alias_record = plain(derived_map), plain(map_alias_record)
        selected_map_bytes = read(derived_map, MAX_MAP)
        alias_record_bytes = read(map_alias_record)
        alias_record = map_alias.validate_alias_map(input_path, candidate_directory / "Celeste+.map", derived_map,
                                                    map_alias_record, candidate_directory / "candidate.json")
        require(alias_record["input_sha256"] == sha(input_bytes)
                and alias_record["candidate_sha256"] == sha(candidate_bytes)
                and alias_record["original_map_sha256"] == sha(map_bytes)
                and alias_record["aliased_map_sha256"] == sha(selected_map_bytes)
                and alias_record == object_json(alias_record_bytes), "MAP alias validation differs from imported snapshots")
    receipt_bytes = read(receipt)
    record = object_json(receipt_bytes)
    evidence = {}
    for name in ("input", "protector", "configuration", "verifier", "report"):
        item = record[name]
        path = Path(item["path"])
        path = plain(path if path.is_absolute() else receipt.parent / path)
        evidence[name] = (path, read(path, MAX_BINARY if name in ("input", "protector", "verifier") else 1024 * 1024))
        require(sha(evidence[name][1]) == item["sha256"], "Protection evidence changed")
    require(evidence["input"][1] == input_bytes, "Protection receipt belongs to another compiled input")
    project, selected = selected_functions(evidence["configuration"][1], selected_map_bytes, input_bytes)
    if alias_record is not None:
        aliases = {item["alias"]: item for item in alias_record["functions"]}
        require({item["map_symbol"] for item in selected} == set(aliases), "GUI project must select exactly the validated MAP aliases")
        for item in selected:
            original = aliases[item["map_symbol"]]
            require(item["input_va"] == int(original["address"], 16), "Selected alias has a different original function address")
            item["original_symbol"] = original["symbol"]
    for field, expected in (("InputFileName", input_bytes), ("OutputFileName", protected_bytes)):
        path = Path(project.get(field, ""))
        path = path if path.is_absolute() else evidence["configuration"][0].parent / path
        require(read(path, MAX_BINARY) == expected, "GUI project belongs to another input or output")
        if field == "InputFileName":
            require(read(path.with_suffix(".map"), MAX_MAP) == selected_map_bytes,
                    "GUI input companion MAP differs from the verified selection MAP")
    compile_bytes = read(compile_evidence, 8 * 1024 * 1024)
    report = object_json(evidence["report"][1])
    require(report.get("map_sha256") == sha(map_bytes) and report.get("compile_evidence_sha256") == sha(compile_bytes),
            "Approved verifier must bind the private MAP and actual compiler evidence")
    if alias_record is not None:
        require(report.get("derived_map_sha256") == sha(selected_map_bytes)
                and report.get("map_alias_record_sha256") == sha(alias_record_bytes),
                "Approved verifier must bind the exact derived MAP and validated alias record")
    coverage = report.get("function_coverage")
    require(isinstance(coverage, list) and len(coverage) == len(selected)
            and report.get("virtualized_functions") == len(selected), "Approved verifier must cover every selected function")
    for expected in selected:
        matches = [item for item in coverage if isinstance(item, dict) and item.get("map_symbol") == expected["map_symbol"]]
        require(len(matches) == 1 and all(matches[0].get(key) == value for key, value in expected.items())
                and matches[0].get("transformation_verified") is True
                and matches[0].get("mode") in ("virtualization", "ultra"),
                "Verifier lacks exact transformed-function/mode evidence; project selection alone is insufficient")
    policy_bytes = read(gate.POLICY_FILE)
    verification = gate.validate(protected_binary, receipt, release_id=release_id, product=PRODUCT, platform=PLATFORM)
    require(verification["binary_sha256"] == sha(protected_bytes) and verification["receipt_sha256"] == sha(receipt_bytes)
            and verification["policy_sha256"] == sha(policy_bytes), "Gate verification differs from imported snapshots")
    # Gate checks are path-based: reject changes between our immutable reads and
    # validation, then publish only the exact bytes read above.
    for path, data in blobs.items():
        require(snapshot(path, len(data)) == data, "GUI evidence changed during import verification")
    check_sources(candidate, root)
    require(gate.source_digest(PRODUCT) == record["source_sha256"], "Native source changed during import")
    output.mkdir(parents=True, exist_ok=False)
    product_dir = output / PRODUCT
    candidate_dir = product_dir / "candidate"
    evidence_dir = product_dir / "gui-evidence"
    candidate_dir.mkdir(parents=True)
    evidence_dir.mkdir()
    (candidate_dir / "Celeste+.exe").write_bytes(input_bytes)
    (candidate_dir / "Celeste+.map").write_bytes(map_bytes)
    (candidate_dir / "candidate.json").write_bytes(candidate_bytes)
    final_binary = product_dir / "Celeste+.exe"
    final_binary.write_bytes(protected_bytes)
    (evidence_dir / "original-receipt.json").write_bytes(receipt_bytes)
    (evidence_dir / "compile-evidence.bin").write_bytes(compile_bytes)
    (evidence_dir / "policy.json").write_bytes(policy_bytes)
    if alias_record is not None:
        (evidence_dir / "derived.map").write_bytes(selected_map_bytes)
        (evidence_dir / "map-alias-record.json").write_bytes(alias_record_bytes)
    for name, (_, data) in evidence.items():
        path = evidence_dir / (name + ".evidence")
        path.write_bytes(data)
        record[name]["path"] = path.relative_to(product_dir).as_posix()
    imported_receipt = product_dir / "protection-receipt.json"
    imported_receipt.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    copied_verification = gate.validate(final_binary, imported_receipt, release_id=release_id, product=PRODUCT, platform=PLATFORM)
    require(copied_verification["binary_sha256"] == sha(protected_bytes)
            and copied_verification["policy_sha256"] == sha(policy_bytes), "Policy or output changed while completing GUI import")
    check_sources(candidate, root)
    require(gate.source_digest(PRODUCT) == record["source_sha256"], "Native source changed before import completion")
    require(sha(snapshot(final_binary, MAX_BINARY)) == sha(protected_bytes), "Imported protected binary changed")
    result = {"schema": "celeste-gui-native-import-1", "status": "prepared-unsigned-not-published",
              "product": PRODUCT, "release_id": release_id, "source_sha256": record["source_sha256"],
              "input_sha256": sha(input_bytes), "protected_sha256": sha(protected_bytes),
              "map_sha256": sha(map_bytes), "compile_evidence_sha256": sha(compile_bytes),
              "original_receipt_sha256": sha(receipt_bytes), "selected_functions": selected,
              "signed": False, "published": False}
    if alias_record is not None:
        result.update({"derived_map_sha256": sha(selected_map_bytes), "map_alias_record_sha256": sha(alias_record_bytes)})
    (output / "gui-import.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    # Completion marker written last. The normal Sign phase still verifies its
    # reviewed bundle, source, signatures and new receipt over final signed bytes.
    (output / "protected-candidates.json").write_text(json.dumps([{key: result[key] for key in
        ("product", "release_id", "input_sha256", "protected_sha256")}], indent=2) + "\n", encoding="utf-8")
    return result


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("candidate-directory", "protected-binary", "receipt", "compile-evidence", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--derived-map", type=Path)
    parser.add_argument("--map-alias-record", type=Path)
    args = parser.parse_args(argv)
    try:
        result = import_candidate(args.candidate_directory, args.protected_binary, args.receipt,
                                  args.compile_evidence, args.output, release_id=args.release_id,
                                  derived_map=args.derived_map, map_alias_record=args.map_alias_record)
        print(json.dumps({key: result[key] for key in ("status", "product", "release_id", "input_sha256", "protected_sha256", "signed", "published")}, sort_keys=True))
    except (OSError, ValueError, KeyError, TypeError, ET.ParseError, struct.error) as error:
        parser.exit(1, "GUI native import refused: " + str(error) + "\n")


if __name__ == "__main__":
    main()
