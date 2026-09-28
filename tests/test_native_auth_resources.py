"""Offline semantic approval fixtures only; no DLL/tool execution or real approval."""
from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import native_auth_resources as subject

spec = importlib.util.spec_from_file_location("native_package_fixture", ROOT / "beta-lab/release-package/package_beta.py")
pack = importlib.util.module_from_spec(spec)
spec.loader.exec_module(pack)


def dll(*, machine=0x8664, characteristics=0x2002, magic=0x20b, tail=b"protected"):
    data = bytearray(512)
    data[:2] = b"MZ"
    struct.pack_into("<I", data, 60, 128)
    data[128:132] = b"PE\0\0"
    struct.pack_into("<H", data, 132, machine)
    struct.pack_into("<H", data, 150, characteristics)
    struct.pack_into("<H", data, 152, magic)
    return bytes(data) + tail


class NativeAuthResourceTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="native-auth-resource-fixture-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.resource = "META-INF/random-fixture/opaque.data"
        self.loader = "fixture/random/NativeOwner.class"
        output, raw = dll(), dll(tail=b"unprotected")
        loader = b"\xca\xfe\xba\xbe" + b"synthetic fixture class; not executable"
        custody = {"schema": "celeste-native-auth-custody-1", "scope": "fixture only, not approval",
            "resource": self.resource, "loader": self.loader[:-6], "loader_sha256": subject.sha(loader),
            "original_dll_sha256": subject.sha(raw), "final_dll_sha256": subject.sha(output), "changed_entries": 2}
        self.evidence = {"source": b'{"fixture":"source provenance"}', "producer": b'{"fixture":"producer provenance"}',
            "selection": b"reviewed fixture selection", "input": raw, "output": output, "loader": loader,
            "custody": json.dumps(custody).encode(), "receipt": b'{"fixture":"independent review receipt"}',
            "report": b'{"fixture":"transform and lifecycle results"}'}
        self.item = {"artifact": "payload", "resource": self.resource, "loader": self.loader,
                     "kind": "windows-x64-dll", "size": len(output)}
        self.item.update({field: subject.sha(self.evidence[name]) for name, field in subject.EVIDENCE.items()})
        self.inventory = {"schema": "celeste-native-auth-resources-1", "product": "celeste_plus", "platform": "windows-x64",
                          "release_id": "fixture-release-1", "resources": [self.item]}
        refs = {}
        for name, data in self.evidence.items():
            (self.root / name).write_bytes(data)
            refs[name] = {"path": name, "size": len(data), "sha256": subject.sha(data)}
        self.approved = {"artifact": "payload", "resource": self.resource, "protection": "native-virtualization",
            "virtualized_functions": 6, "checks": {name: "passed" for name in ("transform", "jni", "protocol", "lifecycle")},
            "evidence": refs}
        self.index = {"schema": "celeste-native-auth-approval-index-1", "product": "celeste_plus", "platform": "windows-x64",
                      "release_id": "fixture-release-1", "inventory_sha256": "", "approvals": [self.approved]}
        self.entries = {self.resource: output, self.loader: loader}
        self.write()

    def write(self):
        self.inventory_path = self.root / "inventory.json"
        self.index_path = self.root / "approval.json"
        self.inventory_path.write_text(json.dumps(self.inventory), encoding="utf-8")
        self.inventory_hash = subject.sha(self.inventory_path.read_bytes())
        self.index["inventory_sha256"] = self.inventory_hash
        self.index_path.write_text(json.dumps(self.index), encoding="utf-8")
        self.index_hash = subject.sha(self.index_path.read_bytes())

    def load(self):
        return subject.load(self.inventory_path, self.inventory_hash, self.index_path, self.index_hash, "fixture-release-1")

    def test_exact_approved_bytes_and_random_resource_are_accepted_only_in_declared_artifact(self):
        approval = self.load()
        self.assertEqual({self.resource}, subject.validate_entries(self.entries, "payload", approval))
        for role in ("bootstrap-a", "bootstrap-b"):
            with self.subTest(role=role), self.assertRaisesRegex(ValueError, "undeclared"):
                subject.validate_entries(self.entries, role, approval)
        with self.assertRaisesRegex(ValueError, "undeclared"):
            subject.validate_entries(self.entries, "payload", None)

    def test_renaming_pe_does_not_hide_it_and_extra_declared_resource_is_required(self):
        approval = self.load()
        for name in ("META-INF/hidden.random", "image.png", "META-INF/other.dll"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "undeclared"):
                subject.validate_entries(self.entries | {name: dll()}, "payload", approval)
        with self.assertRaisesRegex(ValueError, "absent"):
            subject.validate_entries({self.loader: self.entries[self.loader]}, "payload", approval)

    def test_resource_and_loader_tampering_rejected_even_with_otherwise_valid_approval(self):
        approval = self.load()
        for name in self.entries:
            changed = {**self.entries, name: self.entries[name] + b"changed"}
            with self.subTest(name=name), self.assertRaises(ValueError):
                subject.validate_entries(changed, "payload", approval)

    def test_all_evidence_files_have_exact_byte_bindings(self):
        for name, data in self.evidence.items():
            with self.subTest(name=name):
                (self.root / name).write_bytes(data + b"changed")
                with self.assertRaisesRegex(ValueError, "evidence bytes changed"):
                    self.load()
                (self.root / name).write_bytes(data)

    def test_explicit_pins_and_release_binding_are_not_optional(self):
        with self.assertRaisesRegex(ValueError, "pin mismatch"):
            subject.load(self.inventory_path, "0" * 64, self.index_path, self.index_hash)
        with self.assertRaisesRegex(ValueError, "pin mismatch"):
            subject.load(self.inventory_path, self.inventory_hash, self.index_path, "0" * 64)
        with self.assertRaisesRegex(ValueError, "wrong release"):
            subject.load(self.inventory_path, self.inventory_hash, self.index_path, self.index_hash, "another-release")
        self.assertIsNone(subject.from_args(SimpleNamespace()))
        with self.assertRaisesRegex(ValueError, "all four"):
            subject.from_args(SimpleNamespace(native_auth_inventory=self.inventory_path))

    def test_wrong_kind_oversize_unsafe_path_duplicate_and_missing_checks_fail(self):
        for field, value in (("kind", "windows-x64-exe"), ("size", subject.MAX_NATIVE_ENTRY + 1), ("size", True),
            ("resource", "META-INF/../outside"), ("resource", "META-INF/hidden\x7f"), ("resource", "META-INF/celeste-beta/hidden"),
            ("loader", "dev/celestebeta/host/Injected.class"), ("loader", "java/lang/Injected.class")):
            old = self.item[field]
            self.item[field] = value; self.write()
            with self.subTest(field=field, value=value), self.assertRaises(ValueError): self.load()
            self.item[field] = old
        self.approved["checks"]["lifecycle"] = "not-run"; self.write()
        with self.assertRaisesRegex(ValueError, "reviewed native checks"): self.load()
        self.approved["checks"]["lifecycle"] = "passed"
        self.inventory["resources"] *= 2; self.index["approvals"] *= 2; self.write()
        with self.assertRaisesRegex(ValueError, "duplicate native"): self.load()

    def test_actual_pe_must_be_x64_dll_not_renamed_exe_or_bad_header(self):
        for data in (b"not a DLL", dll(machine=0x14c), dll(characteristics=2), dll(magic=0x10b)):
            with self.subTest(data=data[:2]), self.assertRaisesRegex(ValueError, "x64 PE32"):
                subject.require_dll(data)

    def test_custody_is_not_accepted_as_approval_and_must_agree_with_inventory(self):
        value = json.loads(self.evidence["custody"])
        value["loader"] = "different/Owner"
        raw = json.dumps(value).encode()
        (self.root / "custody").write_bytes(raw)
        self.item["custody_sha256"] = subject.sha(raw)
        self.approved["evidence"]["custody"].update(size=len(raw), sha256=subject.sha(raw))
        self.write()
        with self.assertRaisesRegex(ValueError, "custody provenance"): self.load()
        with self.assertRaises(ValueError):
            subject.load(self.root / "custody", subject.sha(raw), self.index_path, self.index_hash)

    def test_duplicate_json_keys_and_oversized_metadata_rejected(self):
        raw = self.inventory_path.read_bytes().replace(b'"schema":', b'"schema":"duplicate", "schema":', 1)
        self.inventory_path.write_bytes(raw)
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            subject.load(self.inventory_path, subject.sha(raw), self.index_path, self.index_hash)
        self.inventory_path.write_bytes(b"x" * (subject.MAX_METADATA + 1))
        with self.assertRaisesRegex(ValueError, "oversized"):
            subject.load(self.inventory_path, subject.sha(self.inventory_path.read_bytes()), self.index_path, self.index_hash)

    def test_media_and_parent_archive_policy_preserved_without_allowing_extra_pe(self):
        entries = {name: dll() for name in subject.MEDIA_DLLS}
        entries.update({"mediaplayerinfo/natives/linux/libmedia.so": b"ELF fixture",
                        "META-INF/jars/jna.jar": b"existing parent ZIP verified elsewhere"})
        subject.validate_entries(entries, "payload", None)
        pack.validate_media_support(entries)
        with self.assertRaisesRegex(ValueError, "undeclared"):
            subject.validate_entries(entries | {"mediaplayerinfo/unexpected.bin": dll()}, "payload", None)

    def package_args(self):
        approval = self.load()
        package = self.root / "package"; package.mkdir()
        trust = b'{"fixture":"public bootstrap trust"}'
        (package / "bootstrap-trust.json").write_bytes(trust)
        budget = subject.budget_bytes(approval)
        (package / subject.BUDGET_FILENAME).write_bytes(budget)
        a = self.root / "a.jar"; b = self.root / "b.bin"
        a.write_bytes(pack.zip_bytes({"META-INF/MANIFEST.MF": b"Celeste-Beta-Bootstrap: A\n",
                                      "META-INF/celeste-beta/bootstrap-trust.json": trust}))
        b.write_bytes(pack.zip_bytes({"META-INF/MANIFEST.MF": b"Celeste-Beta-Bootstrap: B\n",
                                      "META-INF/celeste-beta/runtime-layout.json": b"{}", subject.BUDGET_RESOURCE: budget}))
        parts = [pack.zip_bytes(self.entries | {subject.BUDGET_RESOURCE: budget}), pack.zip_bytes({"fixture.txt": b"second fixture part"})]
        payloads = []
        for letter, data in zip("ab", parts):
            name = f"payload-{letter}.bin"; (package / name).write_bytes(data)
            payloads.append({"filename": name, "sha256": subject.sha(data), "size": len(data)})
        manifest = {"validation_only": False, "release_id": "fixture-release-1", "client_version": "fixture",
            "runtime_sha256": "a" * 64, "runtime_layout": {}, "host_support": {"libraries": []},
            "payloads": payloads, "native_auth": approval.summary()}
        # Synthetic receipt exercises package logic only, never claims actual Java validation.
        (package / "java-validation.json").write_text(json.dumps({"status": "passed", "runtime_sha256": "a" * 64,
            "payload_a_sha256": payloads[0]["sha256"], "payload_b_sha256": payloads[1]["sha256"]}), encoding="utf-8")
        (package / "package.json").write_text(json.dumps(manifest), encoding="utf-8")
        return SimpleNamespace(package=package, bootstrap_a=a, bootstrap_b=b, output=self.root / "release",
            native_auth_inventory=self.inventory_path, native_auth_inventory_sha256=self.inventory_hash,
            native_auth_evidence=self.index_path, native_auth_evidence_sha256=self.index_hash)

    def test_final_assembly_rechecks_exact_payload_resources_before_catalog_creation(self):
        args = self.package_args()
        pack.assemble(args)
        catalog = json.loads((args.output / "beta-release.json").read_bytes())
        self.assertEqual(4, len(catalog["artifacts"]))
        for artifact in catalog["artifacts"]:
            self.assertEqual(artifact["sha256"], subject.sha((args.output / artifact["filename"]).read_bytes()))

    def test_assembly_cannot_omit_approval_or_add_native_to_bootstrap_b(self):
        args = self.package_args()
        saved = args.native_auth_inventory
        args.native_auth_inventory = None
        with self.assertRaises(ValueError): pack.assemble(args)
        args.native_auth_inventory = saved
        entries = pack.read_zip(args.bootstrap_b.read_bytes(), "fixture")
        args.bootstrap_b.write_bytes(pack.zip_bytes(entries | {"META-INF/hidden.random": dll()}))
        with self.assertRaisesRegex(ValueError, "undeclared"): pack.assemble(args)
        self.assertFalse(args.output.exists())


if __name__ == "__main__":
    unittest.main()
