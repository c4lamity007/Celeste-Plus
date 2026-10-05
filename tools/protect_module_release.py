"""Prepare independently protected modules, then bind them to the resident pipeline.

The existing native/Java resident release gates remain authoritative. This
preparation has no signing, upload, deployment, or protection-bypass operation.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

import module_release as modules
import protect_java_release as protection

ROOT = Path(__file__).resolve().parents[1]
require = modules.require


def inventory(root):
    return {p.relative_to(root).as_posix(): modules.sha(modules.bounded(p))
            for p in sorted(root.rglob("*")) if p.is_file() and p != root / "module-preparation.json"}


def parent_classes(paths):
    names = set()
    for path in paths:
        names.update(dependency_classes(modules.bounded(path)))
    return names


def dependency_classes(data):
    entries = modules.archive_entries(data, max_entry=modules.MAX_EXPANDED, parent_dependency=True)
    manifest = entries.get("META-INF/MANIFEST.MF", b"").decode("utf-8").replace("\r\n", "\n").replace("\n ", "")
    fields = dict(line.lower().split(": ", 1) for line in manifest.splitlines() if ": " in line)
    multi_release = fields.get("multi-release") == "true"
    names = set()
    for name in entries:
        if name.startswith("META-INF/versions/"):
            match = re.fullmatch(r"META-INF/versions/([0-9]+)/(.+)", name)
            require(match is not None, "Invalid parent multi-release entry")
            if not multi_release or not 9 <= int(match[1]) <= 21:
                continue
            name = match[2]
        if name.endswith(".class") and not name.startswith("META-INF/") and name != "module-info.class":
            names.add(name[:-6])
    return names


def nested_dependencies(source):
    metadata = modules.parsed(source.get("fabric.mod.json", b"{}"))
    declared = metadata.get("jars", [])
    require(isinstance(declared, list) and all(isinstance(item, dict) and set(item) == {"file"} for item in declared),
            "Invalid declared parent libraries")
    names = [item["file"] for item in declared]
    actual = {name for name in source if name.startswith("META-INF/jars/") and name.endswith(".jar")}
    require(len(names) == len(set(names)) and set(names) == actual, "Nested libraries must be declared exactly once")
    return {name: source[name] for name in sorted(names)}


def preparation(path, expected, resident_input=None):
    path = Path(path).resolve(strict=True)
    data = modules.bounded(path, 8 * 1024 * 1024)
    require(modules.HEX.fullmatch(expected or "") and modules.sha(data) == expected,
            "Module preparation pin mismatch")
    value = modules.parsed(data)
    require(value.get("schema") == "celeste-module-preparation-1"
            and value.get("status") == "protected-modules-awaiting-resident-verification"
            and value.get("production_approved") is False, "Wrong module preparation state")
    require(inventory(path.parent) == value["files"], "Module preparation files changed")
    require(protection.source_inputs() == value["source_inputs"], "Module release sources changed")
    require(modules.sha(modules.bounded(Path(value["source"]))) == value["source_sha256"], "Original runtime changed")
    require(modules.sha(modules.bounded(Path(value["ownership"]))) == value["ownership_sha256"], "Ownership policy changed")
    for filename, digest in value["parents"].items():
        require(modules.sha(modules.bounded(filename)) == digest, "Module parent dependency changed")
    if resident_input is not None:
        require(modules.sha(modules.bounded(resident_input)) == value["resident_source_sha256"],
                "Resident input is not the exact module preparation")
    resident = modules.archive_entries(modules.bounded(path.parent / "resident-source.jar"), max_entry=modules.MAX_EXPANDED)
    executable = modules.executable_catalog(value["release_id"], value["modules"])
    require(resident.get(modules.ANCHOR) == modules.canonical(executable), "Resident module root changed")
    require(value["executable_root_sha256"] == modules.sha(modules.canonical(executable)), "Module root pin mismatch")
    return value


def verified_resident_entries(resident_package, release, package, beta):
    """Bind module finalization to validated bytes in the actual release directory.

    Earlier Java/native gates still establish provenance. This final structural
    check catches stale copies or changed staging files before recording a
    successful module receipt; it is not another production approval authority.
    """
    require(package.get("schema") == "celeste-beta-payload-package-1"
            and package.get("validation_only") is False
            and beta.get("schema") == "celeste-beta-release-2", "Invalid resident release state")
    validation = modules.parsed(modules.bounded(Path(resident_package) / "java-validation.json", 1024 * 1024))
    require(validation.get("status") == "passed"
            and validation.get("runtime_sha256") == package["runtime_sha256"],
            "Resident Java validation is missing or belongs to another runtime")
    parts = package.get("payloads")
    require(isinstance(parts, list) and len(parts) == 2
            and all(isinstance(part, dict) for part in parts)
            and [part.get("filename") for part in parts] == ["payload-a.bin", "payload-b.bin"],
            "Resident requires exactly two ordered payload parts")
    filenames = {"bootstrap-a": "bootstrap-a.jar", "bootstrap-b": "bootstrap-b.bin",
                 "payload-a": "payload-a.bin", "payload-b": "payload-b.bin"}
    records = beta.get("artifacts")
    require(isinstance(records, list) and len(records) == len(filenames), "Invalid resident artifact set")
    indexed = {}
    for record in records:
        require(isinstance(record, dict), "Invalid resident artifact record")
        identity = record.get("id")
        require(isinstance(identity, str) and identity in filenames and identity not in indexed
                and record.get("filename") == filenames[identity]
                and record.get("role") == ("bootstrap" if identity.startswith("bootstrap") else "payload")
                and isinstance(record.get("sha256"), str) and modules.HEX.fullmatch(record["sha256"])
                and type(record.get("size")) is int and 0 < record["size"] <= 64 * 1024 * 1024,
                "Invalid resident artifact identity or bounds")
        indexed[identity] = record
    payload = {}
    for identity, record in indexed.items():
        data = modules.bounded(Path(release) / record["filename"], 64 * 1024 * 1024)
        require(modules.sha(data) == record["sha256"] and len(data) == record["size"],
                "Released resident artifact changed")
        if not identity.startswith("payload-"):
            continue
        part = parts[0 if identity == "payload-a" else 1]
        require(part.get("sha256") == record["sha256"] and part.get("size") == record["size"]
                and validation.get(identity.replace("-", "_") + "_sha256") == record["sha256"],
                "Released resident part differs from Java validation")
        original = modules.bounded(Path(resident_package) / part["filename"], 64 * 1024 * 1024)
        require(modules.sha(original) == record["sha256"] and len(original) == record["size"],
                "Verified resident package part changed")
        del original
        entries = modules.archive_entries(data, max_entry=modules.MAX_EXPANDED)
        require(not payload.keys() & entries.keys(), "Duplicate resident part entry")
        payload.update(entries)
        require(len(payload) <= 65536 and sum(map(len, payload.values())) <= modules.MAX_EXPANDED,
                "Combined resident payload exceeds bound")
    # Reuse the actual package codec, including its exact descriptor exclusion.
    require(modules._codec().runtime_digest(payload) == package["runtime_sha256"] == beta["runtime_sha256"],
            "Released resident canonical digest differs")
    return payload


def finalize(path, expected, resident_package, release_directory):
    """Called by the existing release runner only after its actual gates passed."""
    value = preparation(path, expected)
    root = Path(path).resolve().parent
    package = modules.parsed(modules.bounded(Path(resident_package) / "package.json", 16 * 1024 * 1024))
    release = Path(release_directory)
    beta = modules.parsed(modules.bounded(release / "beta-release.json", 1024 * 1024))
    require(beta["release_id"] == value["release_id"] == package["release_id"], "Resident/module releases differ")
    require(beta["runtime_sha256"] == package["runtime_sha256"], "Resident package/catalog identity differs")
    payload = verified_resident_entries(resident_package, release, package, beta)
    # This is the actual retained resident bytes, not an input-only assertion.
    executable = modules.executable_catalog(value["release_id"], value["modules"])
    require(payload.get(modules.ANCHOR) == modules.canonical(executable), "Protected resident lost its executable trust root")
    require(not any(name.endswith(".class") and name.startswith(modules.IMPLEMENTATION) for name in payload),
            "Protected resident contains module implementation")
    artifacts = {row["filename"]: modules.bounded(root / "modules" / row["filename"], modules.MAX_MODULE)
                 for row in value["modules"]}
    catalog = modules.full_catalog(executable, beta["runtime_sha256"])
    modules.verify_catalog(catalog, artifacts, payload[modules.ANCHOR])
    require(not (release / "module-release.json").exists() and not (release / "modules").exists(),
            "Module release output must be fresh")
    (release / "modules").mkdir()
    for name, data in artifacts.items():
        (release / "modules" / name).write_bytes(data)
    (release / "module-release.json").write_bytes(modules.canonical(catalog) + b"\n")
    preparation(path, expected)
    return {"preparation_sha256": expected, "executable_root_sha256": value["executable_root_sha256"],
            "catalog_sha256": modules.sha(modules.bounded(release / "module-release.json")),
            "runtime_sha256": beta["runtime_sha256"], "module_count": len(artifacts),
            "artifacts": {"modules/" + name: {"sha256": modules.sha(data), "bytes": len(data)}
                          for name, data in artifacts.items()}}


def prepare(args):
    import sync_module_delivery_sources
    sync_module_delivery_sources.check(ROOT)
    source = args.input.resolve(strict=True)
    policy_path = args.ownership.resolve(strict=True)
    policy_data = modules.bounded(policy_path, 16 * 1024 * 1024)
    require(modules.HEX.fullmatch(args.ownership_sha256 or "") and modules.sha(policy_data) == args.ownership_sha256,
            "Ownership policy pin mismatch")
    policy = modules.parsed(policy_data)
    source_data = modules.bounded(source)
    source_entries = modules.archive_entries(source_data, max_entry=modules.MAX_EXPANDED)
    import modular_runtime_profile
    source_profile = modular_runtime_profile.verify(source_entries, ROOT)
    parents = protection.checked_parents(args.parent_classpath)
    parent_pins = {str(p): modules.sha(modules.bounded(p)) for p in parents}
    external = parent_classes(parents)
    # Existing embedded dependencies become parent support in the real resident
    # packaging flow; inspect their declared classes before ownership validation.
    nested = nested_dependencies(source_entries)
    for data in nested.values():
        external.update(dependency_classes(data))
    owned, proof = modules.partition(source_entries, policy, external)
    files, producer = protection.producer_bundle(args.obfuscator)
    require(producer["bundle_sha256"] == args.tool_sha256, "Unreviewed module protection producer")
    sources = protection.source_inputs()
    output = args.output.resolve()
    require(not output.exists(), "Use a fresh module preparation directory")
    output.mkdir(parents=True)
    toolchain = output / "toolchain"
    for name, data in files.items():
        target = toolchain / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
    (output / "modules").mkdir()
    (output / "work").mkdir()
    (output / "parent-libraries").mkdir()
    embedded_parents = []
    for name, data in nested.items():
        path = output / "parent-libraries" / (modules.sha(data) + ".jar")
        if path not in embedded_parents:
            path.write_bytes(data)
            embedded_parents.append(path)
    resident_api = output / "resident-api-input.jar"
    resident_api.write_bytes(modules.zip_bytes(owned["resident"]))
    java_home = args.java_home.resolve(strict=True)
    java = java_home / "bin" / ("java.exe" if os.name == "nt" else "java")
    javac = java_home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
    require(java.is_file() and javac.is_file(), "Java 21 development kit is required")
    environment = {k: v for k, v in os.environ.items() if k not in {"JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "CLASSPATH"}}
    records, protected_entries, transformations = [], {"resident": owned["resident"]}, []
    def step(name, command):
        with (output / (name + ".log")).open("xb") as log:
            result = subprocess.run(list(map(str, command)), cwd=ROOT, env=environment, stdout=log,
                                    stderr=subprocess.STDOUT, timeout=600)
        require(result.returncode == 0, "Module preparation failed: " + name)
    verifier = output / "verification-classes"
    verifier.mkdir()
    step("compile-module-verifier", [javac, "--release", "21", "-encoding", "UTF-8", "-d", verifier,
                                      ROOT / "tools/packaging/ModuleArtifactCheck.java"])
    classpath = output / "module-parent-classpath.txt"
    module_parents = [resident_api, *parents, *embedded_parents]
    classpath.write_text("\n".join(map(str, module_parents)) + "\n", encoding="utf-8")
    for spec in sorted(policy["modules"], key=lambda item: item["id"]):
        identity = spec["id"]
        directory = output / "work" / identity
        directory.mkdir()
        original, protected = directory / "input.jar", directory / "protected.jar"
        original.write_bytes(modules.zip_bytes(owned[identity]))
        modules.module_entries(modules.bounded(original, modules.MAX_MODULE))
        profile = protection.checked_profile("04-payload-protected-v2.json", original, protected, module_parents)
        profile_path = directory / "profile.json"
        profile_path.write_bytes(modules.canonical(profile) + b"\n")
        step("protect-" + identity, [java, "-Xmx2g", "-jar", toolchain / producer["entry"], "--config", profile_path])
        data = modules.bounded(protected, modules.MAX_MODULE)
        entries = modules.module_entries(data)
        require(set(owned[identity]).issubset(entries), "Protection removed/renamed required module entries")
        require(all(entries[name] == content for name, content in owned[identity].items() if not name.endswith(".class")),
                "Protection changed module resource ownership")
        require(not any(re.search(r"\.(?:jar|dll|so|dylib|jnilib)$", name, re.I) for name in entries),
                "Module protection introduced nested/native executable bytes")
        protected_entries[identity] = entries
        record = modules.module_record(spec, data)
        records.append(record)
        (output / "modules" / record["filename"]).write_bytes(data)
        step("verify-" + identity, [java, "-ea", "-Xverify:all", "-cp", verifier,
                                     "ModuleArtifactCheck", classpath, protected, spec["entrypoint"]])
        transformations.append({"id": identity, "producer_sha256": args.tool_sha256,
                                "profile_sha256": modules.sha(modules.bounded(profile_path)),
                                "input_sha256": modules.sha(modules.bounded(original)), "output_sha256": record["sha256"],
                                "verification_log_sha256": modules.sha(modules.bounded(output / ("verify-" + identity + ".log")))})
    boundary = modules.audit_boundaries(protected_entries, policy["resident"], policy["dynamic_dependencies"],
                                       policy["resource_dependencies"], external)
    executable = modules.executable_catalog(args.release_id, records)
    resident = dict(owned["resident"])
    resident[modules.ANCHOR] = modules.canonical(executable)
    resident_bytes = modules.zip_bytes(resident)
    (output / "resident-source.jar").write_bytes(resident_bytes)
    (output / "ownership-proof.json").write_bytes(modules.canonical(proof) + b"\n")
    require(modules.sha(modules.bounded(source)) == modules.sha(source_data), "Original runtime changed during preparation")
    require(modules.bounded(policy_path) == policy_data, "Ownership policy changed during preparation")
    require(protection.source_inputs() == sources, "Release source changed during module protection")
    require(protection.producer_bundle(args.obfuscator)[1] == producer, "Producer changed during preparation")
    require(all(modules.sha(modules.bounded(p)) == pin for p, pin in parent_pins.items()),
            "Parent changed during module protection")
    value = {"schema": "celeste-module-preparation-1", "status": "protected-modules-awaiting-resident-verification",
             "production_approved": False, "release_id": args.release_id, "source": str(source),
             "source_sha256": modules.sha(source_data), "ownership": str(policy_path), "ownership_sha256": args.ownership_sha256,
             "source_inputs": sources, "module_source_profile": source_profile,
             "resident_source_sha256": modules.sha(resident_bytes), "modules": executable["modules"],
             "executable_root_sha256": modules.sha(modules.canonical(executable)), "producer": producer,
             "parents": parent_pins, "transformations": transformations,
             "protected_boundaries": boundary, "files": inventory(output)}
    record_path = output / "module-preparation.json"
    record_path.write_bytes(modules.canonical(value) + b"\n")
    pin = modules.sha(modules.bounded(record_path))
    preparation(record_path, pin, output / "resident-source.jar")
    print(json.dumps({"status": value["status"], "resident_input": str(output / "resident-source.jar"),
                      "module_preparation": str(record_path), "module_preparation_sha256": pin}))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("input", "ownership", "obfuscator", "parent-classpath", "java-home", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    for name in ("ownership-sha256", "tool-sha256", "release-id"):
        parser.add_argument("--" + name, required=True)
    args = parser.parse_args()
    try:
        prepare(args)
    except (ValueError, OSError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as failure:
        parser.exit(1, "Independent module protection refused: " + str(failure) + "\n")


if __name__ == "__main__":
    main()
