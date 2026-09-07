#!/usr/bin/env python3
"""Offline beta staging/validation only; never runs an obfuscator or signs/deploys artifacts."""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import stat
import zipfile
from pathlib import Path

SCHEMA = "celeste-beta-obfuscation-stage-1"
PAYLOAD_SCHEMA = "celeste-beta-payload-obfuscation-stage-2"
HOST = "dev/celestebeta/bootstrap/external/BootstrapHost.class"
STABLE_PREFIXES = (
    "dev/celestebeta/attach/", "dev/c4lam/celeste/beta/delivery/",
    "dev/c4lam/celeste/beta/memory/", "com/google/gson/", "org/objectweb/asm/",
)
PAYLOAD_ENTRY = "dev/c4lam/celeste/beta/lifecycle/BetaPayloadSession"
PUBLIC_SESSION_METHODS = {"startFromRetainedParts", "ready", "stopped", "stop", "status"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def read_archive(path: Path) -> dict[str, bytes]:
    require(path.is_file() and path.stat().st_size <= 128 * 1024 * 1024, "Missing or oversized input archive")
    result: dict[str, bytes] = {}
    total = 0
    with zipfile.ZipFile(path) as archive:
        for entry in archive.infolist():
            name = entry.filename
            require(name and not name.startswith("/") and "\\" not in name
                    and all(part not in ("", ".", "..") for part in name.rstrip("/").split("/")), "Unsafe archive member")
            require(not stat.S_ISLNK(entry.external_attr >> 16), "Archive symlinks are unsupported")
            if entry.is_dir():
                continue
            require(name not in result and len(result) < 65536, "Duplicate or excessive archive members")
            require(entry.file_size <= 64 * 1024 * 1024, "Oversized archive member")
            total += entry.file_size
            require(total <= 256 * 1024 * 1024, "Expanded archive exceeds staging bound")
            result[name] = archive.read(entry)
    return result


def write_archive(path: Path, entries: dict[str, bytes]) -> None:
    require(not path.exists(), f"Refusing to replace existing archive: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "x", zipfile.ZIP_DEFLATED) as archive:
        for name, value in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, value)


def stable(name: str) -> bool:
    if not name.endswith(".class"):
        return True  # Trust metadata, native/support JARs and every authored resource stay exact.
    if name.startswith(STABLE_PREFIXES):
        return True
    canonical = "dev/celestebeta/host/"
    return name.startswith(canonical) and "/" not in name[len(canonical):]


def stage_a(source: Path, output: Path) -> dict[str, object]:
    source = source.resolve(strict=True)
    entries = read_archive(source)
    require(HOST in entries, "Input is missing the real beta BootstrapHost")
    manifest = entries.get("META-INF/MANIFEST.MF", b"").replace(b"\r\n", b"\n")
    for line in (b"Celeste-Beta-Bootstrap: A", b"Agent-Class: dev.celestebeta.attach.AgentMain",
                 b"Main-Class: dev.celestebeta.attach.AttachCli"):
        require(line in manifest.splitlines(), "Input lacks the fixed beta-A manifest contract")
    frozen = {name: value for name, value in entries.items() if stable(name)}
    thin = {name: value for name, value in entries.items() if not stable(name)}
    for prefix in STABLE_PREFIXES:
        require(any(name.startswith(prefix) and name.endswith(".class") for name in frozen), f"Missing stable A dependency: {prefix}")
    require(HOST in thin and all(name.endswith(".class") for name in thin), "Invalid implementation-only split")
    output = output.resolve()
    require(not output.exists(), "Use a fresh A staging directory")
    output.mkdir(parents=True)
    write_archive(output / "bootstrap-a-stable.jar", frozen)
    write_archive(output / "bootstrap-a-thin.jar", thin)
    record = {"schema": SCHEMA, "source_jar": str(source), "source_sha256": digest(source.read_bytes()),
              "stable": {name: digest(value) for name, value in sorted(frozen.items())},
              "implementation": {name: digest(value) for name, value in sorted(thin.items())}}
    (output / "stage.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return {"stable_entries": len(frozen), "implementation_classes": len(thin), "staging": str(output)}


def merge_a(stage: Path, protected: Path, output: Path) -> dict[str, object]:
    record = json.loads((stage / "stage.json").read_text(encoding="utf-8"))
    require(record.get("schema") == SCHEMA, "Unknown A staging schema")
    source = Path(record["source_jar"])
    require(digest(source.read_bytes()) == record["source_sha256"], "Source A changed after staging")
    originals = read_archive(source)
    frozen = read_archive(stage / "bootstrap-a-stable.jar")
    thin = read_archive(stage / "bootstrap-a-thin.jar")
    require({name: digest(value) for name, value in frozen.items()} == record["stable"], "Stable A inputs changed")
    require({name: digest(value) for name, value in thin.items()} == record["implementation"], "Thin A inputs changed")
    require(frozen | thin == originals and not frozen.keys() & thin.keys(), "A split no longer exactly conserves its source")
    implementation = read_archive(protected)
    require(HOST in implementation, "Protected A lost the fixed reflective BootstrapHost entry")
    require(not frozen.keys() & implementation.keys(), "Obfuscator output collides with untouched A entries")
    require(not any(stable(name) and name.endswith(".class") for name in implementation), "Protected A generated a class inside a stable boundary")
    combined = frozen | implementation
    write_archive(output, combined)
    actual = read_archive(output)
    require(all(actual.get(name) == value for name, value in frozen.items()), "Merged A changed stable bytes")
    return {"archive": str(output.resolve()), "sha256": digest(output.read_bytes()),
            "stable_entries_verified": len(frozen), "protected_entries": len(implementation)}


def payload_entrypoint(mapping: Path, archive: Path | None = None) -> str:
    value = json.loads(mapping.read_text(encoding="utf-8"))
    require(value.get("schemaVersion") == 1 and value.get("format") == "word-obfuscator-symbol-map", "Unsupported symbol mapping")
    classes = value.get("classes")
    require(isinstance(classes, dict), "Missing class mappings")
    mapped = classes.get(PAYLOAD_ENTRY, PAYLOAD_ENTRY)
    require(isinstance(mapped, str) and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*(/[A-Za-z_$][A-Za-z0-9_$]*)+", mapped) is not None,
            "Payload lifecycle class is in an unsupported package")
    require(not mapped.startswith(("java/", "javax/", "jdk/", "sun/", "net/minecraft/", "dev/celestebeta/")), "Lifecycle class moved into a parent-owned namespace")
    methods = value.get("methods", {})
    require(isinstance(methods, dict), "Malformed member mapping")
    # This profile intentionally disables closed-world/public-member renaming.
    # Reject a future tool's public lifecycle mapping rather than guess its ABI.
    for key in methods:
        if key.startswith(PAYLOAD_ENTRY + ".") or key.startswith(PAYLOAD_ENTRY + "#"):
            member = key[len(PAYLOAD_ENTRY) + 1:].split("(", 1)[0]
            require(member not in PUBLIC_SESSION_METHODS, "Public lifecycle methods changed; explicit layout mapping review required")
    if archive is not None:
        require(mapped + ".class" in read_archive(archive), "Mapped lifecycle entry is absent from the protected payload")
    return mapped.replace("/", ".")


def payload_boundary(name: str) -> bool:
    return name.startswith(("dev/celestebeta/host/", "dev/redstones/mediaplayerinfo/",
                            "mediaplayerinfo/", "META-INF/jars/", "assets/celeste/", "META-INF/celeste-beta/")) \
        or name == "META-INF/.r/4d7a9c2e" \
        or name == "dev/c4lam/celeste/auth/InstallCredential.class" \
        or re.fullmatch(r"dev/c4lam/celeste/auth/HardwareFingerprint\$[^/]+Property\.class", name) is not None \
        or re.fullmatch(r"dev/c4lam/celeste/render/font/FontData(?:\$[^/]+)?\.class", name) is not None


def payload_stable(name: str) -> bool:
    # Mixins retain their exact original annotation, callback and accessor contracts.
    # Every original resource stays outside the pass, including framework metadata,
    # signed delivery descriptors, relative assets and authored native/support JARs.
    return not name.endswith(".class") or payload_boundary(name) or name.startswith("dev/c4lam/celeste/mixin/")


def stage_payload(source: Path, output: Path) -> dict[str, object]:
    source = source.resolve(strict=True)
    entries = read_archive(source)
    require(PAYLOAD_ENTRY + ".class" in entries, "Input lacks the fixed beta payload lifecycle entry")
    for prefix in ("dev/celestebeta/host/", "dev/redstones/mediaplayerinfo/"):
        require(any(name.startswith(prefix) and name.endswith(".class") for name in entries),
                f"Input lacks required stable payload classes: {prefix}")
    frozen = {name: value for name, value in entries.items() if payload_stable(name)}
    thin = {name: value for name, value in entries.items() if not payload_stable(name)}
    require(PAYLOAD_ENTRY + ".class" in thin, "Lifecycle entry must remain in the implementation pass")
    output = output.resolve()
    require(not output.exists(), "Use a fresh payload staging directory")
    output.mkdir(parents=True)
    write_archive(output / "payload-stable.jar", frozen)
    write_archive(output / "payload-thin.jar", thin)
    record = {"schema": PAYLOAD_SCHEMA, "source_jar": str(source), "source_sha256": digest(source.read_bytes()),
              "stable": {name: digest(value) for name, value in sorted(frozen.items())},
              "implementation": {name: digest(value) for name, value in sorted(thin.items())}}
    (output / "payload-stage.json").write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    return {"stable_entries": len(frozen), "stable_classes": sum(name.endswith(".class") for name in frozen),
            "implementation_classes": len(thin), "staging": str(output)}


def merge_payload(stage: Path, protected: Path, mapping: Path, output: Path) -> dict[str, object]:
    record = json.loads((stage / "payload-stage.json").read_text(encoding="utf-8"))
    require(record.get("schema") == PAYLOAD_SCHEMA, "Unknown payload staging schema")
    source = Path(record["source_jar"])
    require(digest(source.read_bytes()) == record["source_sha256"], "Source payload changed after staging")
    originals = read_archive(source)
    frozen = read_archive(stage / "payload-stable.jar")
    thin = read_archive(stage / "payload-thin.jar")
    require({name: digest(value) for name, value in frozen.items()} == record["stable"], "Stable payload inputs changed")
    require({name: digest(value) for name, value in thin.items()} == record["implementation"], "Thin payload inputs changed")
    require(frozen | thin == originals and not frozen.keys() & thin.keys(), "Payload split no longer exactly conserves its source")
    require(all(payload_stable(name) for name in frozen) and not any(payload_stable(name) for name in thin),
            "Payload split violates the stable boundary")
    symbols = json.loads(mapping.read_text(encoding="utf-8"))
    require(symbols.get("schemaVersion") == 1 and symbols.get("format") == "word-obfuscator-symbol-map",
            "Unsupported symbol mapping")
    # BOTH rename flags are disabled in v2. Nonempty maps (even identity maps)
    # indicate a stale sidecar or a different pass; never guess descriptor syntax.
    for kind in ("classes", "fields", "methods"):
        require(isinstance(symbols.get(kind), dict) and not symbols[kind],
                f"Payload v2 requires empty {kind} mappings; names must remain unchanged")
    implementation = read_archive(protected)
    require(not frozen.keys() & implementation.keys(), "Obfuscator output collides with untouched payload entries")
    require(not any(payload_stable(name) and name.endswith(".class") for name in implementation),
            "Protected payload generated a class inside a stable boundary")
    require(thin.keys() <= implementation.keys(), "Protected payload lost or renamed an original implementation class")
    added = implementation.keys() - thin.keys()
    changed = [name for name, value in thin.items() if implementation[name] != value]
    helpers = [name for name in added if name.endswith(".class")]
    resources = [name for name in added if not name.endswith(".class")]
    require(changed, "Protected payload changed no original implementation classes")
    require(helpers and resources, "Protected payload has no generated helpers and vault resources; inspect actual protection counts")
    require(not any(payload_boundary(name) for name in added), "Protected payload added an entry inside a stable boundary")
    combined = frozen | implementation
    write_archive(output, combined)
    actual = read_archive(output)
    require(all(actual.get(name) == value for name, value in frozen.items()), "Merged payload changed stable bytes")
    check_payload_boundaries(source, output)
    return {"archive": str(output.resolve()), "sha256": digest(output.read_bytes()),
            "stable_entries_verified": len(frozen), "original_implementation_classes": len(thin),
            "changed_original_classes": len(changed), "generated_helpers": len(helpers),
            "generated_resources": len(resources), "protected_entries": len(implementation),
            "compressed_bytes": output.stat().st_size, "expanded_bytes": sum(map(len, combined.values()))}


def check_payload_boundaries(source: Path, protected: Path) -> dict[str, object]:
    original, output = read_archive(source), read_archive(protected)
    kept = {name: value for name, value in original.items() if payload_boundary(name)}
    require(any(name.startswith("dev/celestebeta/host/") for name in kept), "Input has no canonical bridges")
    require(any(name.startswith("dev/redstones/mediaplayerinfo/") for name in kept), "Input has no native-media wrappers")
    require({name for name in output if payload_boundary(name)} == kept.keys(), "Protected payload added or removed a kept boundary entry")
    require(all(output[name] == value for name, value in kept.items()), "Protected payload changed canonical/JNI/model/asset boundary bytes")
    return {"verified_unchanged_entries": len(kept), "source_sha256": digest(source.read_bytes()),
            "protected_sha256": digest(protected.read_bytes())}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    stage = commands.add_parser("stage-a")
    stage.add_argument("--source", type=Path, required=True)
    stage.add_argument("--output", type=Path, required=True)
    merge = commands.add_parser("merge-a")
    merge.add_argument("--stage", type=Path, required=True)
    merge.add_argument("--protected", type=Path, required=True)
    merge.add_argument("--output", type=Path, required=True)
    payload_stage = commands.add_parser("stage-payload")
    payload_stage.add_argument("--source", type=Path, required=True)
    payload_stage.add_argument("--output", type=Path, required=True)
    payload_merge = commands.add_parser("merge-payload")
    payload_merge.add_argument("--stage", type=Path, required=True)
    payload_merge.add_argument("--protected", type=Path, required=True)
    payload_merge.add_argument("--mapping", type=Path, required=True)
    payload_merge.add_argument("--output", type=Path, required=True)
    entry = commands.add_parser("payload-entrypoint")
    entry.add_argument("--mapping", type=Path, required=True)
    entry.add_argument("--archive", type=Path)
    boundaries = commands.add_parser("check-payload-boundaries")
    boundaries.add_argument("--source", type=Path, required=True)
    boundaries.add_argument("--protected", type=Path, required=True)
    args = parser.parse_args()
    try:
        if args.command == "stage-a":
            print(json.dumps(stage_a(args.source, args.output)))
        elif args.command == "merge-a":
            print(json.dumps(merge_a(args.stage, args.protected, args.output)))
        elif args.command == "stage-payload":
            print(json.dumps(stage_payload(args.source, args.output)))
        elif args.command == "merge-payload":
            print(json.dumps(merge_payload(args.stage, args.protected, args.mapping, args.output)))
        elif args.command == "payload-entrypoint":
            print(payload_entrypoint(args.mapping, args.archive))
        else:
            print(json.dumps(check_payload_boundaries(args.source, args.protected)))
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as failure:
        parser.exit(1, f"Beta obfuscation preparation rejected: {failure}\n")


if __name__ == "__main__":
    main()
