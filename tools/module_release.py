"""Exact ownership and immutable executable catalog for independent Celeste+ modules.

These helpers never sign, upload, decrypt, or approve a production release. The
resident trust root is assembled before resident protection; personalized inert
records are added by the authenticated service without changing these JAR bytes.
"""
from __future__ import annotations

import fnmatch
import functools
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import re
import secrets
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
ANCHOR = "META-INF/celeste-beta/module-executables.json"
SCHEMA = "celeste-module-release-1"
EXECUTABLE_SCHEMA = "celeste-module-executables-1"
OWNERSHIP_SCHEMA = "celeste-module-ownership-1"
PRODUCT = "celeste_plus"
MAX_MODULES = 256
MAX_ENTRY = 8 * 1024 * 1024
MAX_MODULE = 16 * 1024 * 1024
MAX_MODULE_EXPANDED = 64 * 1024 * 1024
MAX_MODULE_ENTRIES = 10000
MAX_EXPANDED = 128 * 1024 * 1024
MAX_ENTRIES = 65535
ID = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\Z")
BINARY = re.compile(r"[A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)+\Z")
HEX = re.compile(r"[0-9a-f]{64}\Z")
OPAQUE = re.compile(r"[0-9a-f]{32}\Z")
PLATFORM = ("java/", "javax/", "jdk/", "sun/", "com/sun/")
IMPLEMENTATION = ("dev/c4lam/celeste/module/impl/", "dev/c4lam/celeste/module/expansion/", "dev/c4lam/celeste/module/payload/", "dev/c4lam/celeste/render/model/")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        require(key not in result, "Duplicate JSON key: " + key)
        result[key] = value
    return result


def parsed(data):
    value = json.loads(data, object_pairs_hook=strict_object)
    require(isinstance(value, dict), "Expected JSON object")
    return value


def safe_name(name):
    return (isinstance(name, str) and 0 < len(name) <= 1024 and not name.startswith("/")
            and "\\" not in name and ":" not in name and all(ord(c) >= 32 for c in name)
            and all(part not in ("", ".", "..") for part in name.split("/")))


def bounded(path, maximum=MAX_EXPANDED):
    path = Path(path)
    require(path.is_file() and not path.is_symlink()
            and not getattr(path, "is_junction", lambda: False)(), "Missing or linked input")
    require(path.stat().st_size <= maximum, "Input exceeds size bound")
    with path.open("rb") as stream:
        result = stream.read(maximum + 1)
    require(len(result) <= maximum, "Input changed beyond size bound")
    return result


def archive_entries(data, maximum=MAX_EXPANDED, max_entry=MAX_ENTRY, *, max_expanded=MAX_EXPANDED, max_entries=MAX_ENTRIES,
                    parent_dependency=False):
    require(0 < len(data) <= maximum, "Invalid compressed archive size")
    result, total, seen = {}, 0, set()
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(len(archive.infolist()) <= max_entries, "Too many archive entries")
        for item in archive.infolist():
            name = item.filename
            require(name not in seen, "Duplicate archive entry")
            seen.add(name)
            require(safe_name(name.rstrip("/") if item.is_dir() else name), "Unsafe archive entry")
            require(not item.flag_bits & 1, "Encrypted ZIP is not an executable artifact")
            mode = item.external_attr >> 16
            require(mode & 0o170000 != 0o120000, "Symlink archive entry")
            if item.is_dir():
                require(item.file_size == 0, "Nonempty archive directory")
                continue
            require(0 <= item.file_size <= max_entry, "Archive entry exceeds bound")
            total += item.file_size
            require(total <= max_expanded, "Expanded archive exceeds bound")
            with archive.open(item) as stream:
                content = stream.read(max_entry + 1)
            require(len(content) == item.file_size, "Archive size differs from metadata")
            require(parent_dependency or (not name.startswith("META-INF/versions/") and name != "module-info.class"),
                    "Multi-release/module class ownership needs a reviewed contract")
            result[name] = content
    require(result, "Empty executable archive")
    return result


def module_entries(data):
    return archive_entries(data, MAX_MODULE, max_expanded=MAX_MODULE_EXPANDED, max_entries=MAX_MODULE_ENTRIES)


def source_copy_plan(root):
    """Compute exact copies from pinned originals without changing the repository."""
    root = Path(root).resolve(strict=True)
    path = root / "runtime-overlay/module-private-source-copies.json"
    if not path.exists():
        return [], []
    value = parsed(bounded(path, 1024 * 1024))
    require(set(value) == {"schema", "copies"} and value["schema"] == "celeste-module-source-copies-1",
            "Unrecognized private source copy contract")
    require(isinstance(value["copies"], list) and 0 < len(value["copies"]) <= 512, "Invalid source copy count")
    manifest = root / "runtime-overlay/manifest.json"
    base_paths = set()
    if manifest.is_file():
        # A generated helper must never overwrite a pre-existing pinned-core class.
        # The overlay manifest binds these base hashes independently of this copy recipe.
        base_paths = {"runtime-overlay/" + row["path"] for row in parsed(bounded(manifest, 16 * 1024 * 1024))["files"]
                      if row.get("baseSha256") is not None}
    paths, targets, generated = [path], set(), []
    for spec in value["copies"]:
        require(set(spec) in ({"source", "source_sha256", "target", "replacements"}, {"source", "source_sha256", "target", "replacements", "literal_replacements"}), "Invalid source copy fields")
        names = [spec["source"], spec["target"]]
        require(all(safe_name(name) and name.startswith("runtime-overlay/src/main/java/") and name.endswith(".java")
                    for name in names) and names[0] != names[1], "Unsafe source copy path")
        require(names[1] not in base_paths, "Private source target collides with pinned-core source")
        require(names[1] not in targets, "Duplicate private source target")
        targets.add(names[1])
        files = [root / name for name in names]
        for filename in files:
            require(filename.resolve(strict=False).is_relative_to(root), "Source copy escapes repository")
            for part in (filename, *filename.parents):
                if part == root:
                    break
                require(not part.is_symlink() and not getattr(part, "is_junction", lambda: False)(),
                        "Linked source copy input")
        source = bounded(files[0], MAX_ENTRY)
        require(HEX.fullmatch(spec["source_sha256"] or "") and sha(source) == spec["source_sha256"],
                "Private source original changed")
        replacements = spec["replacements"]
        require(isinstance(replacements, dict) and 0 < len(replacements) <= 64, "Invalid identifier replacements")
        require(all(re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", old) and isinstance(new, str)
                    and re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", new) and old != new
                    for old, new in replacements.items()), "Non-identifier source replacement")
        # Simultaneous replacement makes the result independent of JSON map order.
        expression = r"(?<![\w$])(?:" + "|".join(map(re.escape, sorted(replacements))) + r")(?![\w$])"
        actual = re.sub(expression, lambda match: replacements[match[0]], source.decode("utf-8"))
        literals = spec.get("literal_replacements", {})
        require(isinstance(literals, dict) and len(literals) <= 64 and all(
            isinstance(old, str) and isinstance(new, str) and 2 <= len(old) <= 2048
            and 0 < len(new) <= 4096 and old != new and old in actual for old, new in literals.items()),
            "Invalid or stale literal source replacements")
        if literals:
            actual = re.sub("|".join(map(re.escape, sorted(literals, key=lambda key: (-len(key), key)))),
                            lambda match: literals[match[0]], actual)
        actual = actual.encode("utf-8")
        generated.append((files[1], actual))
        paths.extend(files)
    require(not any(root / spec["source"] in {path for path, _ in generated} for spec in value["copies"]),
            "Source copy chains must use a reviewed original, not another generated target")
    return paths, generated


def check_source_copies(root):
    """Bind reviewed private source copies to exact UTF-8 identifier substitutions."""
    paths, generated = source_copy_plan(root)
    resource_paths, resource_generated, _ = resource_copy_plan(root)
    paths += resource_paths
    generated += resource_generated
    for target, expected in generated:
        require(bounded(target, MAX_ENTRY) == expected, "Private helper copy differs from reviewed substitutions")
    return paths


def resource_copy_plan(root):
    """Pinned inert resource copies with distinct physical ownership paths."""
    root = Path(root).resolve(strict=True)
    path = root / "runtime-overlay/module-private-resource-copies.json"
    if not path.exists(): return [], [], []
    value = parsed(bounded(path, 2 * 1024 * 1024))
    require(set(value) == {"schema", "copies", "exclude_originals"}
            and value["schema"] == "celeste-module-resource-copies-1", "Invalid private resource contract")
    require(isinstance(value["copies"], list) and 0 < len(value["copies"]) <= 4096, "Invalid resource copy count")
    paths, generated, targets, sources = [path], [], set(), set()
    for spec in value["copies"]:
        require(set(spec) == {"source", "source_sha256", "target", "module"}, "Invalid resource copy fields")
        require(isinstance(spec["module"], str) and ID.fullmatch(spec["module"]), "Invalid resource owner")
        names = [spec["source"], spec["target"]]
        prefix = "runtime-overlay/src/main/resources/assets/celeste/"
        require(all(isinstance(name, str) and safe_name(name) and name.startswith(prefix) for name in names)
                and names[0] != names[1] and names[1] not in targets, "Unsafe resource copy path")
        relative = names[1].removeprefix(prefix)
        require(relative.startswith("module/" + spec["module"] + "/")
                or relative.startswith("shaders/module/" + spec["module"] + "/"), "Resource path does not identify its owner")
        require(not any(re.search(r"\.(?:class|jar|dll|so|dylib|jnilib)$", name, re.I) for name in names), "Executable resource copy")
        files = [root / name for name in names]
        for filename in files:
            require(filename.resolve(strict=False).is_relative_to(root), "Resource copy escapes repository")
            for part in (filename, *filename.parents):
                if part == root: break
                require(not part.is_symlink() and not getattr(part, "is_junction", lambda: False)(), "Linked resource copy")
        data = bounded(files[0], MAX_ENTRY)
        require(HEX.fullmatch(spec["source_sha256"] or "") and sha(data) == spec["source_sha256"], "Private resource original changed")
        targets.add(names[1]); sources.add(names[0]); paths.extend(files); generated.append((files[1], data))
    require(not targets.intersection(sources), "Resource copy chains are forbidden")
    excluded = value["exclude_originals"]
    require(isinstance(excluded, list) and len(set(excluded)) == len(excluded)
            and all(name in sources for name in excluded), "Invalid original resource exclusions")
    return paths, generated, [name.removeprefix("runtime-overlay/") for name in excluded]


def zip_bytes(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=False) as archive:
        for name, data in sorted(entries.items()):
            require(safe_name(name), "Unsafe output archive entry")
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return output.getvalue()


def entries_digest(entries):
    """All bytes are integrity covered; this format has no excluded entries."""
    digest = hashlib.sha256()
    for name, data in sorted(entries.items()):
        encoded = name.encode("utf-8")
        digest.update(struct.pack(">I", len(encoded)))
        digest.update(encoded)
        digest.update(struct.pack(">Q", len(data)))
        digest.update(data)
    return digest.hexdigest()


@functools.lru_cache(maxsize=1)
def _codec():
    spec = importlib.util.spec_from_file_location("celeste_exact_class_codec", ROOT / "beta-lab/release-package/package_beta.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def class_data(data):
    """Reuse the release parser and separately collect literal constant strings."""
    own, refs = _codec().class_info(data)
    stream = io.BytesIO(data)
    stream.seek(8)
    def take(count):
        value = stream.read(count)
        require(len(value) == count, "Truncated class constant pool")
        return value
    def u2():
        return struct.unpack(">H", take(2))[0]
    count, values, literals, index = u2(), {}, [], 1
    while index < count:
        tag = take(1)[0]
        if tag == 1:
            # JVM modified UTF-8 can encode NUL/surrogates; class-name/path
            # literals tested here are ASCII. Bytecode references use the codec.
            values[index] = take(u2()).decode("utf-8", errors="replace")
        elif tag == 8:
            literals.append(u2())
        elif tag in (7, 16, 19, 20):
            take(2)
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            take(4)
        elif tag in (5, 6):
            take(8); index += 1
        elif tag == 15:
            take(3)
        else:
            raise ValueError("Unsupported class constant-pool tag")
        index += 1
    return own, refs, {values[i] for i in literals}


def _patterns(value, label):
    require(isinstance(value, list) and value and len(value) <= MAX_ENTRIES, "Missing " + label)
    require(len(set(value)) == len(value), "Duplicate " + label)
    require(all(safe_name(p) and not any(c in p for c in "?[]") for p in value), "Unsafe " + label)
    return value


def _matches(name, patterns):
    return any(fnmatch.fnmatchcase(name, pattern) for pattern in patterns)


def partition(entries, policy, parent_classes=frozenset()):
    """Require one reviewed owner per entry and strict dependency direction."""
    require(set(policy) == {"schema", "host_abi", "resident", "modules", "dynamic_dependencies", "resource_dependencies"}
            and policy["schema"] == OWNERSHIP_SCHEMA and policy["host_abi"] == 1, "Invalid ownership schema")
    require(ANCHOR not in entries, "Source must not supply a preexisting module trust root")
    resident = policy["resident"]
    require(set(resident) == {"entries", "exports", "resource_exports"}, "Invalid resident ownership")
    patterns = {"resident": _patterns(resident["entries"], "resident entries")}
    require(isinstance(policy["modules"], list) and 0 < len(policy["modules"]) <= MAX_MODULES, "Invalid module count")
    specs = {}
    for module in policy["modules"]:
        require(set(module) == {"id", "version", "entrypoint", "entries"}, "Invalid module ownership fields")
        identity = module["id"]
        require(isinstance(identity, str) and ID.fullmatch(identity) and identity != "resident"
                and identity not in specs, "Invalid or duplicate module identity")
        require(isinstance(module["version"], str) and ID.fullmatch(module["version"]), "Invalid module version")
        require(isinstance(module["entrypoint"], str) and BINARY.fullmatch(module["entrypoint"]), "Invalid entrypoint")
        specs[identity] = module
        patterns[identity] = _patterns(module["entries"], "module entries")
    owned = {owner: {} for owner in patterns}
    owners = {}
    for name, value in sorted(entries.items()):
        hits = [owner for owner, globs in patterns.items() if _matches(name, globs)]
        require(len(hits) == 1, "Entry must have exactly one owner: " + name)
        owner = hits[0]
        require(owner != "resident" or not (name.endswith(".class") and name.startswith(IMPLEMENTATION)),
                "Module implementation retained in resident: " + name)
        require(owner == "resident" or not re.search(r"\.(?:jar|dll|so|dylib|jnilib)$", name, re.I),
                "Module cannot conceal nested executable/native artifact: " + name)
        owned[owner][name] = value
        owners[name] = owner
    for owner, globs in patterns.items():
        require(owned[owner], "Empty owner artifact: " + owner)
        if owner != "resident":
            require(len(owned[owner]) <= MAX_MODULE_ENTRIES
                    and sum(map(len, owned[owner].values())) <= MAX_MODULE_EXPANDED
                    and all(len(value) <= MAX_ENTRY for value in owned[owner].values()),
                    "Module ownership exceeds runtime memory bounds")
        require(all(any(fnmatch.fnmatchcase(name, pattern) for name in owned[owner]) for pattern in globs),
                "Ownership pattern matches no entries: " + owner)
    for owner, spec in specs.items():
        require(spec["entrypoint"].replace(".", "/") + ".class" in owned[owner], "Entrypoint not owned by module")
    audit = audit_boundaries(owned, resident, policy["dynamic_dependencies"], policy["resource_dependencies"], parent_classes)
    return owned, {"schema": "celeste-module-ownership-proof-1", "ownership_sha256": sha(canonical(policy)),
                   "source_entries_sha256": entries_digest(entries), "entry_count": len(entries),
                   "owners": {owner: {"entries": len(values), "entries_sha256": entries_digest(values)}
                              for owner, values in sorted(owned.items())}, "boundaries": audit}


def audit_boundaries(owned, resident, dynamic_dependencies, resource_dependencies, parent_classes=frozenset()):
    require(not any(name.startswith("dev/c4lam/celeste/") for name in parent_classes),
            "External parent dependency contains unowned Celeste classes")
    class_owners, resource_owners, parsed_classes = {}, {}, {}
    for owner, entries in owned.items():
        for name, value in entries.items():
            if name.endswith(".class"):
                declared, refs, literals = class_data(value)
                require(name == declared + ".class", "Class name/path mismatch")
                require(declared not in class_owners, "Duplicate executable class ownership")
                class_owners[declared] = owner
                parsed_classes[declared] = (refs, literals)
            else:
                require(name not in resource_owners, "Duplicate resource ownership")
                resource_owners[name] = owner
    exports = resident["exports"]
    require(isinstance(exports, list) and len(set(exports)) == len(exports), "Invalid resident exports")
    require(all(isinstance(name, str) and BINARY.fullmatch(name) and class_owners.get(name.replace(".", "/")) == "resident"
                for name in exports), "Export is not an exact resident class")
    exports = {name.replace(".", "/") for name in exports}
    resources = resident["resource_exports"]
    require(isinstance(resources, list) and len(set(resources)) == len(resources)
            and all(resource_owners.get(name) == "resident" for name in resources), "Invalid resident resource exports")
    resources = set(resources)
    edges = set()
    def class_edge(source, target):
        require(source in class_owners, "Dependency source is absent")
        if target not in class_owners:
            require(target in parent_classes or target.startswith(PLATFORM), "Unresolved external class: " + target)
            return
        left, right = class_owners[source], class_owners[target]
        require(left == right or (left != "resident" and right == "resident" and target in exports),
                "Forbidden class ownership edge: " + source + " -> " + target)
        edges.add((source, target))
    def resource_edge(source, target):
        require(source in class_owners and target in resource_owners, "Unresolved resource dependency")
        left, right = class_owners[source], resource_owners[target]
        require(left == right or (left != "resident" and right == "resident" and target in resources),
                "Forbidden resource ownership edge: " + source + " -> " + target)
        edges.add((source, "resource:" + target))
    for source, (refs, literals) in parsed_classes.items():
        for target in refs:
            class_edge(source, target)
        for literal in literals:
            target = literal.replace(".", "/")
            if target in class_owners:
                class_edge(source, target)
            path = literal.lstrip("/")
            if path in resource_owners:
                resource_edge(source, path)
    for rows, action in ((dynamic_dependencies, class_edge), (resource_dependencies, resource_edge)):
        require(isinstance(rows, list) and len(rows) <= MAX_ENTRIES, "Invalid explicit dependency inventory")
        for row in rows:
            require(isinstance(row, dict) and set(row) == {"source", "target"}, "Invalid explicit dependency")
            source = row["source"].replace(".", "/")
            target = row["target"].replace(".", "/") if action is class_edge else row["target"]
            action(source, target)
    import module_access
    access_classes = {name.removesuffix(".class"): module_access.info(value)
                      for entries in owned.values() for name, value in entries.items() if name.endswith(".class")}
    access = module_access.audit(access_classes, {owner: [name.removesuffix(".class") for name in entries if name.endswith(".class")]
                                               for owner, entries in owned.items() if owner != "resident"})
    require(not access["issues"], "Illegal cross-loader member access: " + str(access["issues"][:1]))
    return {"classes": len(class_owners), "resources": len(resource_owners), "checked_resident_member_accesses": access["checked"], "checked_internal_edges": len(edges),
            "exports_sha256": sha(canonical(sorted(exports))), "resource_exports_sha256": sha(canonical(sorted(resources))),
            "edge_sha256": sha(canonical(sorted(edges))), "computed_reflection_requires_declared_dependencies": True}


def executable_catalog(release_id, records):
    require(isinstance(release_id, str) and ID.fullmatch(release_id), "Invalid release identity")
    require(isinstance(records, list) and 0 < len(records) <= MAX_MODULES, "Invalid executable record count")
    seen, opaque = set(), set()
    for record in records:
        require(set(record) == {"id", "artifact_id", "version", "entrypoint", "filename", "sha256", "size", "entries_sha256"},
                "Invalid executable record")
        identity, artifact = record["id"], record["artifact_id"]
        require(isinstance(identity, str) and ID.fullmatch(identity) and identity not in seen, "Invalid module identity")
        require(isinstance(artifact, str) and OPAQUE.fullmatch(artifact) and artifact not in opaque, "Invalid opaque identity")
        require(record["filename"] == artifact + ".jar" and ID.fullmatch(record["version"])
                and BINARY.fullmatch(record["entrypoint"]), "Invalid executable mapping")
        require(HEX.fullmatch(record["sha256"]) and HEX.fullmatch(record["entries_sha256"])
                and type(record["size"]) is int and 0 < record["size"] <= MAX_MODULE, "Invalid executable integrity")
        seen.add(identity); opaque.add(artifact)
    require(sum(record["size"] for record in records) <= MAX_EXPANDED, "Combined executable bytes exceed bound")
    return {"schema": EXECUTABLE_SCHEMA, "product": PRODUCT, "release_id": release_id,
            "host_abi": 1, "modules": sorted(records, key=lambda record: record["id"])}


def module_record(spec, artifact):
    entries = module_entries(artifact)
    require(spec["entrypoint"].replace(".", "/") + ".class" in entries, "Protected module lost entrypoint")
    opaque = secrets.token_hex(16)
    return {"id": spec["id"], "artifact_id": opaque, "version": spec["version"], "entrypoint": spec["entrypoint"],
            "filename": opaque + ".jar", "sha256": sha(artifact), "size": len(artifact), "entries_sha256": entries_digest(entries)}


def full_catalog(executables, runtime_sha256):
    checked = executable_catalog(executables["release_id"], executables["modules"])
    require(checked == executables and HEX.fullmatch(runtime_sha256), "Invalid finalized resident identity")
    return {**checked, "schema": SCHEMA, "runtime_sha256": runtime_sha256,
            "executable_root_sha256": sha(canonical(checked))}


def verify_catalog(catalog, artifacts, resident_anchor):
    require(set(catalog) == {"schema", "product", "release_id", "host_abi", "runtime_sha256", "executable_root_sha256", "modules"}
            and catalog["schema"] == SCHEMA and catalog["product"] == PRODUCT and catalog["host_abi"] == 1,
            "Invalid module release catalog")
    executable = executable_catalog(catalog["release_id"], catalog["modules"])
    require(HEX.fullmatch(catalog["runtime_sha256"]) and catalog["executable_root_sha256"] == sha(canonical(executable)),
            "Executable catalog root differs")
    require(resident_anchor == canonical(executable), "Resident module trust anchor differs")
    require(set(artifacts) == {row["filename"] for row in executable["modules"]}, "Unlisted or missing module artifact")
    for row in executable["modules"]:
        data = artifacts[row["filename"]]
        require(len(data) == row["size"] and sha(data) == row["sha256"], "Fixed executable bytes changed")
        require(entries_digest(module_entries(data)) == row["entries_sha256"], "Executable inventory differs")
    return executable
