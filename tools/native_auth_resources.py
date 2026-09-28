"""Exact native-auth resource approvals; no protection is inferred from a DLL.

Both the inventory and independent evidence index require caller-reviewed SHA256
pins. This module verifies their byte bindings. It never creates approvals,
executes evidence tools or loads DLLs. Larger entries require exact native approval.
"""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import json
from pathlib import Path, PurePosixPath
import re

MAX_ENTRY = 8 * 1024 * 1024
MAX_NATIVE_ENTRY = 32 * 1024 * 1024
MAX_NATIVE_TOTAL = 64 * 1024 * 1024
MAX_NATIVE_COUNT = 4
MAX_BUDGET = 16 * 1024
BUDGET_RESOURCE = "META-INF/celeste-beta/native-auth-resources.txt"
BUDGET_FILENAME = "native-auth-resources.txt"
MAX_METADATA = 1024 * 1024
HEX = re.compile(r"[0-9a-f]{64}\Z")
ROLES = {"payload", "bootstrap-a"}
MEDIA_DLLS = frozenset("mediaplayerinfo/natives/win/" + name for name in
                      ("MediaPlayerInfo.dll", "CelesteMediaCleanup.dll"))
FIELDS = {"artifact", "resource", "loader", "kind", "size", "sha256", "loader_sha256", "source_sha256",
          "producer_sha256", "selection_sha256", "input_sha256", "custody_sha256", "receipt_sha256", "report_sha256"}
EVIDENCE = {"source": "source_sha256", "producer": "producer_sha256", "selection": "selection_sha256",
            "input": "input_sha256", "output": "sha256", "loader": "loader_sha256",
            "custody": "custody_sha256", "receipt": "receipt_sha256", "report": "report_sha256"}


def require(condition, message):
    if not condition:
        raise ValueError("Native auth resources: " + message)


def sha(data):
    return hashlib.sha256(data).hexdigest()


def read(path: Path, limit: int) -> bytes:
    require(path.is_file() and not path.is_symlink() and 0 < path.stat().st_size <= limit,
            "missing, linked, empty or oversized evidence")
    with path.open("rb") as stream:
        data = stream.read(limit + 1)
    require(0 < len(data) <= limit, "evidence exceeds its bound")
    return data


def document(data: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate JSON field")
            result[key] = value
        return result
    try:
        value = json.loads(data.decode("utf-8"), object_pairs_hook=unique)
    except (UnicodeError, json.JSONDecodeError, RecursionError) as error:
        raise ValueError("Native auth resources: invalid JSON evidence") from error
    require(isinstance(value, dict), "JSON evidence must be an object")
    return value


def path_name(name) -> bool:
    return (isinstance(name, str) and 1 <= len(name) <= 512 and name.isascii()
            and all(33 <= ord(c) <= 126 for c in name) and "\\" not in name and ":" not in name
            and not name.startswith("/") and all(part not in {"", ".", ".."} for part in name.split("/")))


def pe_header(data: bytes) -> bytes | None:
    if len(data) < 64 or data[:2] != b"MZ":
        return None
    offset = int.from_bytes(data[60:64], "little")
    if not 64 <= offset <= len(data) - 26 or data[offset:offset + 4] != b"PE\0\0":
        return None
    return data[offset:offset + 26]


def require_dll(data: bytes):
    header = pe_header(data)
    require(header is not None and len(data) <= MAX_NATIVE_ENTRY
            and int.from_bytes(header[4:6], "little") == 0x8664
            and int.from_bytes(header[22:24], "little") & 0x2000
            and header[24:26] == b"\x0b\x02", "resource must be a bounded x64 PE32+ DLL")


@dataclass(frozen=True)
class Approval:
    inventory: dict
    inventory_sha256: str
    evidence_sha256: str

    def summary(self):
        return {"inventory_sha256": self.inventory_sha256, "evidence_sha256": self.evidence_sha256,
                "inventory": self.inventory}


def load(inventory_path: Path, inventory_hash: str, evidence_path: Path, evidence_hash: str,
         release_id: str | None = None) -> Approval:
    require(isinstance(inventory_hash, str) and HEX.fullmatch(inventory_hash)
            and isinstance(evidence_hash, str) and HEX.fullmatch(evidence_hash), "two explicit reviewed pins are required")
    inventory_bytes = read(inventory_path, MAX_METADATA)
    evidence_bytes = read(evidence_path, MAX_METADATA)
    require(sha(inventory_bytes) == inventory_hash and sha(evidence_bytes) == evidence_hash, "reviewed approval pin mismatch")
    inventory, index = document(inventory_bytes), document(evidence_bytes)
    require(set(inventory) == {"schema", "product", "platform", "release_id", "resources"}
            and inventory["schema"] == "celeste-native-auth-resources-1", "unsupported inventory schema")
    require(inventory["product"] == "celeste_plus" and inventory["platform"] == "windows-x64"
            and isinstance(inventory["release_id"], str)
            and re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}", inventory["release_id"])
            and (release_id is None or inventory["release_id"] == release_id), "wrong release/product/platform")
    require(set(index) == {"schema", "product", "platform", "release_id", "inventory_sha256", "approvals"}
            and index["schema"] == "celeste-native-auth-approval-index-1"
            and index["inventory_sha256"] == inventory_hash
            and all(index[key] == inventory[key] for key in ("product", "platform", "release_id")), "wrong evidence index binding")
    resources, approvals = inventory["resources"], index["approvals"]
    require(isinstance(resources, list) and 1 <= len(resources) <= MAX_NATIVE_COUNT and isinstance(approvals, list)
            and len(approvals) == len(resources), "invalid approval/resource count")
    require(all(isinstance(item, dict) and type(item.get("size")) is int
                and 90 <= item["size"] <= MAX_NATIVE_ENTRY for item in resources),
            "DLL exceeds approved native entry bound")
    require(sum(item["size"] for item in resources) <= MAX_NATIVE_TOTAL,
            "Approved native aggregate exceeds 64 MiB")
    identities, loaders = set(), set()
    for item, approved in zip(resources, approvals):
        require(isinstance(item, dict) and set(item) == FIELDS and isinstance(item["artifact"], str) and item["artifact"] in ROLES
                and item["kind"] == "windows-x64-dll", "invalid resource declaration")
        resource, loader = item["resource"], item["loader"]
        require(path_name(resource) and resource.startswith("META-INF/")
                and not resource.startswith(("META-INF/celeste-beta/", "META-INF/jars/", "META-INF/.r/"))
                and resource != "META-INF/MANIFEST.MF" and not resource.endswith((".class", ".jar")), "invalid native resource path")
        require(path_name(loader) and "/" in loader and loader.endswith(".class")
                and not loader.startswith(("java/", "javax/", "jdk/", "sun/", "com/sun/", "net/", "org/",
                                           "dev/celestebeta/host/", "dev/redstones/", "META-INF/")), "invalid native loader path")
        identity, owner = (item["artifact"], resource), (item["artifact"], loader)
        require(identity not in identities and owner not in loaders, "duplicate native resource or loader")
        identities.add(identity); loaders.add(owner)
        require(type(item["size"]) is int and 90 <= item["size"] <= MAX_NATIVE_ENTRY, "DLL exceeds approved native entry bound")
        require(all(isinstance(item[key], str) and HEX.fullmatch(item[key]) for key in FIELDS if key.endswith("sha256")),
                "invalid evidence hash")
        require(item["input_sha256"] != item["sha256"], "protected DLL is identical to its unprotected input")
        require(isinstance(approved, dict) and set(approved) == {"artifact", "resource", "protection", "virtualized_functions", "checks", "evidence"}
                and (approved["artifact"], approved["resource"]) == identity
                and approved["protection"] == "native-virtualization"
                and type(approved["virtualized_functions"]) is int and 0 < approved["virtualized_functions"] <= 4096
                and approved["checks"] == {"transform": "passed", "jni": "passed", "protocol": "passed", "lifecycle": "passed"},
                "missing independently reviewed native checks")
        evidence = approved["evidence"]
        require(isinstance(evidence, dict) and set(evidence) == set(EVIDENCE), "incomplete evidence set")
        snapshots = {}
        for kind, hash_field in EVIDENCE.items():
            ref = evidence[kind]
            limit = MAX_NATIVE_ENTRY if kind in {"input", "output"} else MAX_ENTRY
            require(isinstance(ref, dict) and set(ref) == {"path", "size", "sha256"}
                    and isinstance(ref["path"], str) and 0 < len(ref["path"]) <= 4096
                    and type(ref["size"]) is int and 0 < ref["size"] <= limit
                    and ref["sha256"] == item[hash_field], "invalid evidence reference")
            path = Path(ref["path"])
            if not path.is_absolute():
                require(path_name(ref["path"]), "unsafe relative evidence path")
                path = evidence_path.resolve().parent / path
            data = read(path, limit)
            require(len(data) == ref["size"] and sha(data) == ref["sha256"], "evidence bytes changed: " + kind)
            snapshots[kind] = data
        require_dll(snapshots["output"])
        require(len(snapshots["output"]) == item["size"], "DLL size differs from inventory")
        require(snapshots["loader"].startswith(b"\xca\xfe\xba\xbe"), "loader is not a JVM class")
        custody = document(snapshots["custody"])
        require(custody.get("schema") == "celeste-native-auth-custody-1"
                and custody.get("resource") == resource and isinstance(custody.get("loader"), str)
                and custody["loader"] + ".class" == loader
                and custody.get("loader_sha256") == item["loader_sha256"]
                and custody.get("final_dll_sha256") == item["sha256"]
                and custody.get("original_dll_sha256") == item["input_sha256"]
                and type(custody.get("changed_entries")) is int and custody["changed_entries"] == 2,
                "custody provenance disagrees with approved bytes")
    return Approval(inventory, inventory_hash, evidence_hash)


def declared_entries(artifact: str, approval: Approval | None) -> dict:
    return {} if approval is None else {item["resource"]: item for item in approval.inventory["resources"]
                                        if item["artifact"] == artifact}


def entry_limit(name: str, artifact: str, approval: Approval | None) -> int:
    item = declared_entries(artifact, approval).get(name)
    return MAX_ENTRY if item is None else min(item["size"], MAX_NATIVE_ENTRY)


def validate_entry(name: str, data: bytes, artifact: str, approval: Approval | None):
    item = declared_entries(artifact, approval).get(name)
    require(len(data) <= entry_limit(name, artifact, approval), "entry exceeds its exact approved/default bound: " + name)
    if item is not None:
        require(len(data) == item["size"] and sha(data) == item["sha256"], "declared DLL is absent or changed")
        require_dll(data)


def budget_bytes(approval: Approval | None, artifact: str = "payload") -> bytes | None:
    entries = declared_entries(artifact, approval)
    if not entries:
        return None
    require(len(entries) <= MAX_NATIVE_COUNT and sum(item["size"] for item in entries.values()) <= MAX_NATIVE_TOTAL,
            "native budget exceeds aggregate/count bounds")
    data = ("celeste-native-auth-budget-1\n" + "".join(
        f'{item["size"]} {item["sha256"]} {name}\n' for name, item in sorted(entries.items()))).encode("ascii")
    require(len(data) <= MAX_BUDGET, "native budget metadata exceeds its bound")
    return data


def validate_entries(entries: dict[str, bytes], artifact: str, approval: Approval | None) -> set[str]:
    declared = declared_entries(artifact, approval)
    for name, item in declared.items():
        data, loader = entries.get(name), entries.get(item["loader"])
        require(data is not None and len(data) == item["size"] and sha(data) == item["sha256"], "declared DLL is absent or changed")
        require_dll(data)
        require(loader is not None and loader.startswith(b"\xca\xfe\xba\xbe")
                and sha(loader) == item["loader_sha256"], "declared loader is absent or changed")
    legacy = MEDIA_DLLS if artifact == "payload" else frozenset()
    for name, data in entries.items():
        validate_entry(name, data, artifact, approval)
        if name in legacy:
            continue  # Existing paired-media validation and exact parent hashes still apply.
        native_name = (PurePosixPath(name).suffix.lower() in {".dll", ".so", ".dylib", ".jnilib"}
                       and not (artifact == "payload" and name.startswith("mediaplayerinfo/")
                                and PurePosixPath(name).suffix.lower() in {".so", ".dylib", ".jnilib"}))
        require(not (pe_header(data) is not None or native_name) or name in declared,
                "undeclared native resource: " + name)
    return set(declared)


def add_arguments(parser):
    parser.add_argument("--native-auth-inventory", type=Path)
    parser.add_argument("--native-auth-inventory-sha256")
    parser.add_argument("--native-auth-evidence", type=Path)
    parser.add_argument("--native-auth-evidence-sha256")


def from_args(args, release_id=None):
    values = [getattr(args, name, None) for name in ("native_auth_inventory", "native_auth_inventory_sha256",
              "native_auth_evidence", "native_auth_evidence_sha256")]
    require(all(value is None for value in values) or all(value is not None for value in values), "supply all four native approval arguments")
    return None if values[0] is None else load(*values, release_id=release_id)


def cli_args(args):
    if getattr(args, "native_auth_inventory", None) is None:
        return []
    return [value for name in ("native-auth-inventory", "native-auth-inventory-sha256", "native-auth-evidence", "native-auth-evidence-sha256")
            for value in ("--" + name, str(getattr(args, name.replace("-", "_"))) if name.endswith("sha256")
                          else str(getattr(args, name.replace("-", "_")).resolve(strict=True)))]
