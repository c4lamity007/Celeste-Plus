"""Prepare a supplied complete Celeste core JAR for authenticated Celeste+ delivery."""
from __future__ import annotations

import argparse
import base64
import hashlib
import importlib.util
import io
import json
import re
import struct
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import native_auth_resources
AUTH = "META-INF/.r/4d7a9c2e"
HOST_ROOT = "META-INF/celeste-beta/host-jars/"
SOURCE_ROOT = "META-INF/celeste-beta/source-root/"
MAX_FILE = 8 * 1024 * 1024
MAX_TOTAL = 128 * 1024 * 1024
MAX_PART = 64 * 1024 * 1024
CANONICAL_BRIDGE = "dev/celestebeta/host/"
MEDIA = "dev/redstones/mediaplayerinfo/"
EXPECTED_PARENT = ("java/", "javax/", "jdk/", "sun/", "com/sun/", "net/minecraft/", "net/fabricmc/",
                   "org/spongepowered/", "com/llamalad7/", "org/lwjgl/", "org/joml/", "org/slf4j/",
                   "org/jetbrains/", "com/google/", "com/mojang/", "it/unimi/", "org/apache/", "io/netty/")


def sha(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def canonical(value: object) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False).encode("utf-8")


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def validate_media_support(media: dict[str, bytes]) -> None:
    """Windows playback and its JVM-exit cleanup must be delivered together."""
    prefix = "mediaplayerinfo/natives/win/"
    playback = media.get(prefix + "MediaPlayerInfo.dll")
    cleanup = media.get(prefix + "CelesteMediaCleanup.dll")
    if playback is None and cleanup is None:
        return
    require(playback is not None and cleanup is not None,
            "Windows media requires both MediaPlayerInfo.dll and CelesteMediaCleanup.dll")
    for name, data in (("MediaPlayerInfo.dll", playback), ("CelesteMediaCleanup.dll", cleanup)):
        require(len(data) >= 64 and data[:2] == b"MZ", f"Invalid Windows media DLL: {name}")
        offset = int.from_bytes(data[60:64], "little")
        require(64 <= offset <= len(data) - 26, f"Invalid Windows media PE header: {name}")
        pe = data[offset:offset + 26]
        require(pe[:4] == b"PE\x00\x00" and int.from_bytes(pe[4:6], "little") == 0x8664
                and int.from_bytes(pe[22:24], "little") & 0x2000 and pe[24:26] == b"\x0b\x02",
                f"Windows media must use x64 DLLs: {name}")


def read_zip(data: bytes, label: str, native_approval=None, artifact="payload") -> dict[str, bytes]:
    require(len(data) <= MAX_PART, f"{label}: compressed input exceeds 64 MiB")
    result: dict[str, bytes] = {}
    total = 0
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        require(len(archive.infolist()) < 65535, f"{label}: too many ZIP entries")
        for entry in archive.infolist():
            name = entry.filename
            require(name and not name.startswith("/") and "\\" not in name and ":" not in name
                    and not any(ord(c) < 32 for c in name), f"{label}: noncanonical entry {name!r}")
            require(all(part not in ("", ".", "..") for part in name.rstrip("/").split("/")), f"{label}: invalid path {name!r}")
            require(name not in result, f"{label}: duplicate entry {name!r}")
            if entry.is_dir():
                require(entry.file_size == 0, f"{label}: nonempty directory")
                continue
            limit = native_auth_resources.entry_limit(name, artifact, native_approval)
            require(entry.file_size <= limit, f"{label}: entry exceeds approved/default integrity limit: {name}")
            total += entry.file_size
            require(total <= MAX_TOTAL, f"{label}: expanded input exceeds 128 MiB")
            with archive.open(entry) as stream:
                value = stream.read(limit + 1)  # Bounded even if the ZIP advertises a false size.
            require(len(value) == entry.file_size, f"{label}: ZIP entry size changed: {name}")
            native_auth_resources.validate_entry(name, value, artifact, native_approval)
            result[name] = value
    require(bool(result), f"{label}: empty archive")
    return result


def zip_bytes(entries: dict[str, bytes]) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9, allowZip64=False) as archive:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o100644 << 16
            archive.writestr(info, data)
    return output.getvalue()


def descriptor_classes(value: str) -> set[str]:
    """Read JVM descriptors/generic signatures without mistaking type variables for classes."""
    refs: set[str] = set()
    length = len(value)

    def field_type(index: int) -> int:
        require(index < length, "Truncated type signature")
        token = value[index]
        if token in "+-[":
            return field_type(index + 1)
        if token in "BCDFIJSZV*":
            return index + 1
        if token == "T":
            end = value.find(";", index + 1)
            require(end > index + 1, "Invalid type-variable signature")
            return end + 1
        require(token == "L", "Invalid class signature token")
        index += 1
        name = ""
        while index < length:
            start = index
            while index < length and value[index] not in "<.;":
                index += 1
            require(index > start, "Empty class signature segment")
            name += value[start:index]
            refs.add(name)
            if index < length and value[index] == "<":
                index += 1
                while index < length and value[index] != ">":
                    index = field_type(index)
                require(index < length, "Unclosed generic type arguments")
                index += 1
            require(index < length, "Unclosed class signature")
            if value[index] == ";":
                return index + 1
            require(value[index] == ".", "Invalid nested class signature")
            name += "$"
            index += 1
        raise ValueError("Truncated class signature")

    index = 0
    if value.startswith("<"):
        index = 1
        while index < length and value[index] != ">":
            colon = value.find(":", index)
            require(colon > index, "Invalid formal type parameter")
            index = colon + 1
            if index < length and value[index] != ":":
                index = field_type(index)
            while index < length and value[index] == ":":
                index = field_type(index + 1)
        require(index < length, "Unclosed formal type parameters")
        index += 1
    while index < length:
        if value[index] in "()^":
            index += 1
        else:
            index = field_type(index)
    return refs


def class_info(data: bytes) -> tuple[str, set[str]]:
    """Constant-pool class/member descriptor dependencies, without initializing code."""
    stream = io.BytesIO(data)
    def u1(): return struct.unpack(">B", stream.read(1))[0]
    def u2(): return struct.unpack(">H", stream.read(2))[0]
    require(stream.read(4) == b"\xca\xfe\xba\xbe", "Invalid class magic")
    minor, major = struct.unpack(">HH", stream.read(4))
    require(major <= 65, f"Class requires Java newer than 21: {major}")
    pool: list[object] = [None] * u2()
    class_indices, descriptors = [], []
    index = 1
    while index < len(pool):
        tag = u1()
        if tag == 1:
            pool[index] = stream.read(u2()).decode("utf-8", errors="replace")
        elif tag == 7:
            pool[index] = u2()
            class_indices.append(index)
        elif tag == 12:
            u2()
            descriptors.append(u2())
        elif tag == 16:
            descriptors.append(u2())
        elif tag in (3, 4, 9, 10, 11, 17, 18):
            stream.read(4)
        elif tag in (5, 6):
            stream.read(8)
            index += 1
        elif tag in (8, 19, 20):
            stream.read(2)
        elif tag == 15:
            stream.read(3)
        else:
            raise ValueError(f"Unsupported class constant-pool tag {tag}")
        index += 1
    u2()
    own_name = pool[pool[u2()]]
    u2()
    for _ in range(u2()):
        u2()
    for _ in range(2):  # Declared field and method descriptors need no NameAndType CP entry.
        for _ in range(u2()):
            u2()
            u2()
            descriptors.append(u2())
            for _ in range(u2()):
                attribute_name = pool[u2()]
                length = struct.unpack(">I", stream.read(4))[0]
                value = stream.read(length)
                require(len(value) == length, "Truncated class attribute")
                if attribute_name == "Signature":
                    require(length == 2, "Invalid generic signature attribute")
                    descriptors.append(struct.unpack(">H", value)[0])
    # Class-level generic inheritance bounds are attributes after the members.
    for _ in range(u2()):
        attribute_name = pool[u2()]
        length = struct.unpack(">I", stream.read(4))[0]
        value = stream.read(length)
        require(len(value) == length, "Truncated class attribute")
        if attribute_name == "Signature":
            require(length == 2, "Invalid generic signature attribute")
            descriptors.append(struct.unpack(">H", value)[0])
    refs: set[str] = set()
    for index in class_indices:
        value = pool[pool[index]]
        if value.startswith("["):
            refs.update(descriptor_classes(value))
        else:
            refs.add(value)
    for index in descriptors:
        refs.update(descriptor_classes(pool[index]))
    return own_name, refs


def class_index(entries: dict[str, bytes]) -> set[str]:
    classes = set()
    for name in entries:
        if not name.endswith(".class") or name.endswith("module-info.class"):
            continue
        if name.startswith("META-INF/versions/"):
            version, name = name[len("META-INF/versions/"):].split("/", 1)
            if int(version) > 21:
                continue
        classes.add(name[:-6])
    return classes


def runtime_digest(entries: dict[str, bytes]) -> str:
    digest = hashlib.sha256()
    for name, data in sorted(entries.items()):
        if name == AUTH:
            continue
        encoded = name.encode("utf-8")
        digest.update(struct.pack(">I", len(encoded)) + encoded + struct.pack(">Q", len(data)) + data)
    return digest.hexdigest()


def descriptor(base: str, key: str, release: str, digest: str, version: str, codec: Path) -> bytes:
    # Reuse the real descriptor format; no second implementation or production edit.
    spec = importlib.util.spec_from_file_location("celeste_production_packaging", codec.resolve(strict=True))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.release_descriptor(base, key, release, digest, version)


def prepare(args: argparse.Namespace) -> None:
    native_approval = native_auth_resources.from_args(args, args.release_id)
    source_path = args.input.resolve(strict=True)
    source_bytes = source_path.read_bytes()
    source = read_zip(source_bytes, "client input", native_approval)
    require(native_auth_resources.BUDGET_RESOURCE not in source, "Input must not supply its own native budget")
    native_budget = native_auth_resources.budget_bytes(native_approval)
    native_entries = native_auth_resources.validate_entries(source, "payload", native_approval)
    require("fabric.mod.json" in source, "Input must retain its remapped Fabric descriptor, including the actual entrypoint")
    metadata = json.loads(source["fabric.mod.json"])
    entrypoints = metadata.get("entrypoints", {}).get("client", [])
    require(len(entrypoints) == 1 and isinstance(entrypoints[0], str), "Expected one explicit client entrypoint in Fabric metadata")
    entrypoint = entrypoints[0]
    require(entrypoint.replace(".", "/") + ".class" in source, "Fabric entrypoint is absent from this artifact")
    payload_entrypoint = args.payload_entrypoint
    require(payload_entrypoint.replace(".", "/") + ".class" in source, "Payload lifecycle entrypoint is absent; build the current client or supply its mapped class name")
    require(re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", args.release_id), "Invalid beta release ID")
    require(1 <= len(args.client_version) <= 40 and all(33 <= ord(c) <= 126 for c in args.client_version), "Invalid client version")
    require(args.validation_only or (args.base_url and args.server_key), "Supply the actual public HTTPS origin/key, or explicitly choose --validation-only")
    if args.validation_only:
        require(not args.base_url and not args.server_key, "Validation mode cannot accept live origin/key")
    else:
        production = "https://qb0f90cf11f4d1d52.fly.dev"
        require(args.base_url in (production, production + "/"),
                "Production API origin must be " + production)
        args.base_url = production

    nested = [entry["file"] for entry in metadata.get("jars", [])]
    require(len(nested) == len(set(nested)), "Duplicate declared nested dependency")
    require(all(name in source and name.startswith("META-INF/jars/") and name.endswith(".jar") for name in nested), "Invalid declared nested library")
    undeclared = [name for name in source if name.startswith("META-INF/jars/") and name.endswith(".jar") and name not in nested]
    require(not undeclared, f"Undeclared nested libraries need an explicit boundary: {undeclared}")
    support: dict[str, bytes] = {Path(name).name: source[name] for name in nested}
    require(len(support) == len(nested), "Nested library filename collision")
    bridges = {name: data for name, data in source.items() if name.startswith(CANONICAL_BRIDGE)}
    media = {name: data for name, data in source.items() if name.startswith(MEDIA) or name.startswith("mediaplayerinfo/")}
    require(bool(bridges) and bool(media), "Actual client must contain canonical bridge and native-media support inputs")
    validate_media_support(media)
    require(all(name.endswith(".class") for name in bridges), "Unexpected canonical-bridge resource")
    support["celeste-beta-bridges.jar"] = zip_bytes(bridges)
    support["celeste-beta-media.jar"] = zip_bytes(media)
    support_classes: set[str] = set()
    dependency_owners: dict[str, str] = {}
    for filename, data in support.items():
        for binary in class_index(read_zip(data, filename)):
            require(binary not in support_classes, f"Duplicate parent support class: {binary}")
            support_classes.add(binary)
            dependency_owners[binary] = filename
    for required in ("com/sun/jna/Native", "oshi/SystemInfo", "dev/redstones/mediaplayerinfo/impl/win/WindowsMediaPlayerInfo"):
        require(required in support_classes, f"Required existing auth/media support is absent: {required}")

    payload: dict[str, bytes] = {}
    provenance: list[dict] = []
    for name, data in source.items():
        if name == AUTH:
            provenance.append({"source": name, "sha256": sha(data), "action": "replace mutable auth descriptor"})
            continue
        if name in bridges or name in media:
            jar = "celeste-beta-bridges.jar" if name in bridges else "celeste-beta-media.jar"
            target = HOST_ROOT + jar + "!/" + name
            action = "retain exact bytes inside integrity-backed parent support archive"
        else:
            target = SOURCE_ROOT + name if "/" not in name else name
            require(target not in payload, f"Relocated metadata collision: {target}")
            require(name in native_entries or not re.search(r"\.(dll|so|dylib|jnilib)$", target, re.I), f"Unassigned native support entry: {name}")
            require(not target.startswith("META-INF/versions/") and target != "module-info.class", "Client multi-release/module classes require a separate packaging design")
            payload[target] = data
            action = "inert source metadata" if target != name else "retain"
        provenance.append({"source": name, "sha256": sha(data), "payload": target, "action": action})

    # Preserve the library/native/bridge bytes in the exact retained integrity input
    # even though the host resolves those classes/resources from its resident URLs.
    host_entries = []
    for filename, data in sorted(support.items()):
        original = next((name for name in nested if Path(name).name == filename), None)
        backing = original or HOST_ROOT + filename
        if original is None:
            require(backing not in payload, "Parent support backing entry collision")
            payload[backing] = data
        host_entries.append({"filename": filename, "sha256": sha(data), "size": len(data), "payload_backing": backing,
                             "bootstrap_entry": HOST_ROOT + filename})
    host_contract = {"schema": "celeste-beta-host-support-1", "libraries": host_entries,
        "bootstrap_classpath": ["celeste-beta-bridges.jar"],
        "resident_url_classpath": sorted(name for name in support if name != "celeste-beta-bridges.jar"),
        "resident_owned_prefixes": ["com.sun.jna.", "oshi.", "dev.redstones.mediaplayerinfo."],
        "resident_resource_prefixes": ["com/sun/jna/", "oshi/", "mediaplayerinfo/", "dev/redstones/mediaplayerinfo/"],
        "parent": "actual verified Minecraft/Fabric game classloader",
        "must_validate_from_retained_payload": True,
        "nested_jars_are_not_automatically_classpath_entries": True}
    payload["META-INF/celeste-beta/host-support.json"] = canonical(host_contract)

    own_classes = class_index(payload)
    require(not own_classes.intersection(support_classes), "A class is owned by both payload and parent support")
    require(entrypoint.replace(".", "/") in own_classes, "Entrypoint moved out of the payload unexpectedly")
    class_prefixes = sorted({binary.rsplit("/", 1)[0].replace("/", ".") + "." for binary in own_classes if "/" in binary})
    require(all("/" in binary for binary in own_classes), "Default-package obfuscation is not supported by the current memory-loader namespace API")
    require(all(not prefix.startswith(("java.", "javax.", "jdk.", "sun.", "com.sun.")) for prefix in class_prefixes), "Payload claims a reserved platform namespace")
    for binary in support_classes:
        require(not any((binary.replace("/", ".") + ".").startswith(prefix) for prefix in class_prefixes), f"Payload package overlaps resident support: {binary}")

    external: set[str] = set()
    owners = {}
    for name, data in payload.items():
        if not name.endswith(".class"):
            continue
        declared, references = class_info(data)
        require(name == declared + ".class", f"Class path/name mismatch: {name}")
        for reference in references:
            if reference in own_classes:
                owners[reference] = "payload"
            elif reference in support_classes:
                owners[reference] = "resident:" + dependency_owners[reference]
            else:
                require(reference.startswith(EXPECTED_PARENT), f"Unclassified external class reference {reference}; explicitly resolve its owner before packaging")
                require(not any(reference.startswith(prefix.replace(".", "/")) for prefix in class_prefixes), f"Missing child-only application class: {reference}")
                external.add(reference.replace("/", "."))
                owners[reference] = "required-game-or-platform-parent"
    resources = sorted({name.split("/", 1)[0] + "/" for name in payload if not name.endswith(".class")})
    runtime_layout = {
        "schema": "celeste-beta-runtime-layout-1", "class_prefixes": class_prefixes,
        "resource_prefixes": resources, "entry_class": payload_entrypoint,
        "start_method": args.start_method, "ready_method": args.ready_method,
        "stopped_method": args.stopped_method, "stop_method": args.stop_method,
        "status_method": args.status_method,
    }
    require(all(re.fullmatch(r"[A-Za-z_$][A-Za-z0-9_$]*", runtime_layout[name]) for name in
        ("start_method", "ready_method", "stopped_method", "stop_method", "status_method")), "Invalid lifecycle method mapping")
    payload["META-INF/celeste-beta/runtime-layout.json"] = canonical(runtime_layout)
    minecraft_refs = [name for name in external if name.startswith("net.minecraft.")]
    require(bool(minecraft_refs) and all(name.startswith("net.minecraft.class_") for name in minecraft_refs),
            "Use the actual intermediary-remapped client artifact; named/development Minecraft references remain")
    require(all(name.startswith("assets/celeste/") for name in payload if name.startswith("assets/")), "Internal asset layer only supports assets/celeste")
    base = "https://fixture.invalid" if args.validation_only else args.base_url
    key = base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=") if args.validation_only else args.server_key
    bootstrap_trust = {"schema": "celeste-plus-bootstrap-trust-1", "origin": base, "server_key": key, "product": "celeste_plus"}
    payload["META-INF/celeste-beta/bootstrap-trust.json"] = canonical(bootstrap_trust)
    if native_budget is not None:
        payload[native_auth_resources.BUDGET_RESOURCE] = native_budget
    digest = runtime_digest(payload)
    payload[AUTH] = descriptor(base, key, args.release_id, digest, args.client_version, args.descriptor_module)
    require(runtime_digest(payload) == digest, "Packed auth descriptor changed canonical runtime digest")
    require(len(payload) <= 65536 and sum(map(len, payload.values())) <= MAX_TOTAL, "Payload exceeds production memory-integrity bounds")
    native_auth_resources.validate_entries(payload, "payload", native_approval)
    parts = [{}, {}]
    for name, data in sorted(payload.items()):
        index = 0 if name in {AUTH, native_auth_resources.BUDGET_RESOURCE} else hashlib.sha256(name.encode()).digest()[0] & 1
        parts[index][name] = data
    encoded = [zip_bytes(part) for part in parts]
    require(all(parts) and all(len(part) <= MAX_PART for part in encoded), "Expected two nonempty bounded payload parts")
    require(runtime_digest(parts[0] | parts[1]) == digest, "Split changed canonical digest")

    output = args.output.resolve()
    require(not output.exists(), "Output must be a fresh directory; existing release artifacts are never overwritten")
    output.mkdir(parents=True)
    (output / "host-support").mkdir()
    for filename, data in support.items():
        (output / "host-support" / filename).write_bytes(data)
    for index, data in enumerate(encoded):
        (output / f"payload-{'a' if index == 0 else 'b'}.bin").write_bytes(data)
    (output / "class-prefixes.txt").write_text("\n".join(class_prefixes) + "\n", encoding="utf-8")
    (output / "resource-prefixes.txt").write_text("\n".join(resources) + "\n", encoding="utf-8")
    (output / "runtime-layout.json").write_bytes(canonical(runtime_layout) + b"\n")
    if native_budget is not None:
        (output / native_auth_resources.BUDGET_FILENAME).write_bytes(native_budget)
    (output / "bootstrap-trust.json").write_bytes(canonical(bootstrap_trust) + b"\n")
    (output / "parent-classes.txt").write_text("\n".join(sorted(external)) + "\n", encoding="utf-8")
    (output / "host-support.tsv").write_text("".join(f"{entry['filename']}\t{entry['sha256']}\t{entry['payload_backing']}\n" for entry in host_entries), encoding="utf-8")
    manifest = {"schema": "celeste-beta-payload-package-1", "source": str(source_path), "source_sha256": sha(source_bytes),
        "release_id": args.release_id, "client_version": args.client_version, "runtime_sha256": digest,
        "validation_only": args.validation_only, "entrypoint": entrypoint, "runtime_layout": runtime_layout, "server_catalog_created": False,
        "payloads": [{"filename": f"payload-{letter}.bin", "sha256": sha(data), "size": len(data)} for letter, data in zip("ab", encoded)],
        "entry_count": len(payload), "expanded_bytes": sum(map(len, payload.values())),
        "limits": {"maxPartBytes": MAX_PART, "maxEntries": 65536, "maxEntryBytes": MAX_FILE, "maxTotalBytes": MAX_TOTAL},
        "class_prefixes": class_prefixes, "resource_prefixes": resources, "application_classes": len(own_classes),
        "host_support_classes": len(support_classes), "required_parent_classes": sorted(external),
        "class_dependency_owners": dict(sorted(owners.items())), "host_support": host_contract, "provenance": provenance,
        "reflection_strings_are_not_a_complete_dependency_graph": True,
        "remaining": ["Real bootstrap-A/B host implementation", "Actual parent-class visibility/linkage", "Complete late hook coverage", "Runtime auth and Minecraft QA"]}
    if native_approval is not None:
        manifest["native_auth"] = native_approval.summary()
    (output / "package.json").write_bytes(canonical(manifest) + b"\n")
    print(json.dumps({"status": "packaged-for-local-validation", "output": str(output), "runtime_sha256": digest,
        "application_classes": len(own_classes), "support_libraries": len(support), "parent_classes_to_verify": len(external)}))


def assemble(args: argparse.Namespace) -> None:
    package = args.package.resolve(strict=True)
    manifest = json.loads((package / "package.json").read_text(encoding="utf-8"))
    native_approval = native_auth_resources.from_args(args, manifest["release_id"])
    require(manifest.get("native_auth") == (None if native_approval is None else native_approval.summary()),
            "Native auth approval changed or is missing at final assembly")
    require(not manifest["validation_only"], "Fixture auth descriptors cannot be assembled into a server release")
    require((package / "java-validation.json").is_file(), "Run the actual memory-loader/integrity validator before final assembly")
    validation = json.loads((package / "java-validation.json").read_text())
    require(validation["runtime_sha256"] == manifest["runtime_sha256"] and validation["status"] == "passed", "Java validation belongs to another package")
    require(validation["payload_a_sha256"] == manifest["payloads"][0]["sha256"]
            and validation["payload_b_sha256"] == manifest["payloads"][1]["sha256"], "Payload metadata changed after Java validation")
    bootstraps = {"bootstrap-a": args.bootstrap_a.read_bytes(), "bootstrap-b": args.bootstrap_b.read_bytes()}
    first = read_zip(bootstraps["bootstrap-a"], "bootstrap-A", native_approval, "bootstrap-a")
    second = read_zip(bootstraps["bootstrap-b"], "bootstrap-B", None, "bootstrap-b")
    expected_budget = native_auth_resources.budget_bytes(native_approval)
    budget_file = package / native_auth_resources.BUDGET_FILENAME
    require((native_auth_resources.read(budget_file, native_auth_resources.MAX_BUDGET) if budget_file.is_file() else None) == expected_budget,
            "Packaged native budget differs from exact approval")
    require(second.get(native_auth_resources.BUDGET_RESOURCE) == expected_budget,
            "Bootstrap-B native budget differs from exact approval")
    require(native_auth_resources.BUDGET_RESOURCE not in first, "Bootstrap-A must not supply a payload native budget")
    native_auth_resources.validate_entries(first, "bootstrap-a", native_approval)
    native_auth_resources.validate_entries(second, "bootstrap-b", native_approval)
    require(first.get("META-INF/celeste-beta/bootstrap-trust.json", b"").strip() == (package / "bootstrap-trust.json").read_bytes().strip(),
            "Bootstrap-A trust profile does not match this payload package")
    require(json.loads(second.get("META-INF/celeste-beta/runtime-layout.json", b"null")) == manifest["runtime_layout"],
            "Bootstrap-B lifecycle mapping does not match the exact packaged payload")
    for name, contents, marker in (("bootstrap-A", first, "A"), ("bootstrap-B", second, "B")):
        metadata = contents.get("META-INF/MANIFEST.MF", b"").decode("utf-8")
        require(f"Celeste-Beta-Bootstrap: {marker}" in metadata, f"{name} lacks its explicit beta role manifest")
        require(not any(path.startswith("dev/celestebeta/linkage/") for path in contents), "Diagnostic linkage agents are not executable beta bootstraps")
    for library in manifest["host_support"]["libraries"]:
        contents = first.get(library["bootstrap_entry"])
        require(contents is not None and sha(contents) == library["sha256"], f"Bootstrap-A did not embed exact support: {library['filename']}")
    artifacts = dict(bootstraps)
    combined_payload = {}
    for part_index, payload in enumerate(manifest["payloads"]):
        data = (package / payload["filename"]).read_bytes()
        require(sha(data) == payload["sha256"] and len(data) == payload["size"], "Payload changed after validation")
        artifacts[payload["filename"][:-4]] = data
        part = read_zip(data, payload["filename"], native_approval)
        require(part.get(native_auth_resources.BUDGET_RESOURCE) == (expected_budget if part_index == 0 else None),
                "Native budget must appear exactly in the first payload part")
        require(not combined_payload.keys() & part.keys(), "Duplicate entry across final payload parts")
        combined_payload.update(part)
    native_auth_resources.validate_entries(combined_payload, "payload", native_approval)
    require(combined_payload.get(native_auth_resources.BUDGET_RESOURCE) == expected_budget,
            "Payload native budget differs from exact approval")
    require(len(combined_payload) <= 65536 and sum(map(len, combined_payload.values())) <= MAX_TOTAL,
            "Combined payload exceeds production memory-integrity bounds")
    require(all(0 < len(data) <= MAX_PART for data in artifacts.values()) and sum(map(len, artifacts.values())) <= MAX_TOTAL, "Release exceeds beta delivery bounds")
    output = args.output.resolve()
    require(not output.exists(), "Final assembly output must be fresh")
    output.mkdir(parents=True)
    entries = []
    for artifact_id, data in sorted(artifacts.items()):
        filename = "bootstrap-a.jar" if artifact_id == "bootstrap-a" else artifact_id + ".bin"
        (output / filename).write_bytes(data)
        entries.append({"id": artifact_id, "role": "bootstrap" if artifact_id.startswith("bootstrap") else "payload", "filename": filename, "sha256": sha(data), "size": len(data)})
    catalog = {"schema": "celeste-beta-release-2", "release_id": manifest["release_id"], "client_version": manifest["client_version"], "runtime_sha256": manifest["runtime_sha256"], "artifacts": entries}
    (output / "beta-release.json").write_bytes(canonical(catalog) + b"\n")
    print(json.dumps({"status": "assembled-not-deployed-or-game-tested", "output": str(output)}))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="operation", required=True)
    prep = sub.add_parser("prepare")
    prep.add_argument("--input", type=Path, required=True)
    prep.add_argument("--descriptor-module", type=Path, default=ROOT / "build/runtime-core/tools/package_final_release.py",
                      help="Existing descriptor codec in the pinned Celeste core checkout; never copied into this project")
    prep.add_argument("--output", type=Path, required=True)
    prep.add_argument("--release-id", required=True)
    prep.add_argument("--client-version", required=True)
    prep.add_argument("--base-url")
    prep.add_argument("--server-key")
    prep.add_argument("--validation-only", action="store_true")
    prep.add_argument("--payload-entrypoint", default="dev.c4lam.celeste.beta.lifecycle.BetaPayloadSession")
    prep.add_argument("--start-method", default="startFromRetainedParts")
    prep.add_argument("--ready-method", default="ready")
    prep.add_argument("--stopped-method", default="stopped")
    prep.add_argument("--stop-method", default="stop")
    prep.add_argument("--status-method", default="status")
    final = sub.add_parser("assemble")
    final.add_argument("--package", type=Path, required=True)
    final.add_argument("--bootstrap-a", type=Path, required=True)
    final.add_argument("--bootstrap-b", type=Path, required=True)
    final.add_argument("--output", type=Path, required=True)
    native_auth_resources.add_arguments(prep)
    native_auth_resources.add_arguments(final)
    args = parser.parse_args()
    try:
        (prepare if args.operation == "prepare" else assemble)(args)
    except (ValueError, KeyError, OSError, zipfile.BadZipFile, struct.error) as failure:
        parser.exit(1, f"Packaging rejected: {failure}\n")


if __name__ == "__main__":
    main()
