"""Pinned private toolchain bundle validation; fake tools are never executed."""
from pathlib import Path
import hashlib
import json
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/packaging"))
import native_release_bundle as bundle
import native_protection_gate as gate


class NativeReleaseBundleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.files = {"adapter.ps1": b"throw 'test-only; never executed'", "protector.exe": b"test protector",
                      "configuration.vmp": b"test config", "verifier.exe": b"test verifier"}
        def entry(path):
            return {"path": path, "sha256": hashlib.sha256(self.files[path]).hexdigest()}
        tools = {"protector": entry("protector.exe"), "configuration": entry("configuration.vmp"), "verifier": entry("verifier.exe")}
        self.record = {"schema": "celeste-native-protection-bundle-1", "adapter": entry("adapter.ps1"),
                       "products": {"celeste_plus": tools}}
        approved = {name + "_sha256": value["sha256"] for name, value in tools.items()}
        approved["platform"] = "windows-x64"
        self.policy = self.root / "test-policy.json"
        self.policy.write_text(json.dumps({"schema": "celeste-native-protection-policy-1", "required_protection": "native-virtualization",
            "environment_policy": "deny-vm-and-sandbox-v1", "approved_protectors": [approved]}))
        self.patch = patch.object(gate, "POLICY_FILE", self.policy)
        self.patch.start()
        self.addCleanup(self.patch.stop)

    def archive(self, extra=None):
        archive = self.root / "bundle.zip"
        with zipfile.ZipFile(archive, "w") as output:
            for name, data in self.files.items():
                output.writestr(name, data)
            output.writestr("bundle.json", json.dumps(self.record))
            if extra:
                output.writestr(*extra)
        return archive

    def verify(self, archive):
        return bundle.verify(archive, gate.sha(archive), self.root / "extracted")

    def test_reviewed_bundle_resolves_exact_files_without_executing(self):
        result = self.verify(self.archive())
        self.assertEqual("celeste-native-verified-bundle-1", result["schema"])
        self.assertEqual(self.files["adapter.ps1"], Path(result["adapter"]).read_bytes())
        self.assertEqual({"celeste_plus"}, set(result["products"]))

    def test_archive_checksum_is_checked_before_extracting(self):
        archive = self.archive()
        with self.assertRaisesRegex(ValueError, "checksum"):
            bundle.verify(archive, "a" * 64, self.root / "extracted")
        self.assertFalse((self.root / "extracted").exists())

    def test_archive_replacement_after_hash_cannot_change_extracted_adapter(self):
        archive = self.archive()
        expected = gate.sha(archive)
        original_open = zipfile.ZipFile
        def replace_on_open(source, *args, **kwargs):
            archive.write_bytes(b"changed archive after snapshot")
            return original_open(source, *args, **kwargs)
        with patch.object(bundle.zipfile, "ZipFile", side_effect=replace_on_open):
            result = bundle.verify(archive, expected, self.root / "extracted")
        self.assertNotEqual(expected, gate.sha(archive))
        self.assertEqual(self.files["adapter.ps1"], Path(result["adapter"]).read_bytes())

    def test_empty_policy_blocks_before_extracting(self):
        policy = gate.read(self.policy)
        policy["approved_protectors"] = []
        self.policy.write_text(json.dumps(policy))
        with self.assertRaisesRegex(ValueError, "No native protector"):
            self.verify(self.archive())
        self.assertFalse((self.root / "extracted").exists())

    def test_real_policy_rejects_unapproved_bundle(self):
        actual = ROOT / "tools/packaging/native-protection-policy.json"
        synthetic_tuple = gate.read(self.policy)["approved_protectors"][0]
        self.assertNotIn(synthetic_tuple, gate.read(actual)["approved_protectors"])
        with patch.object(gate, "POLICY_FILE", actual):
            with self.assertRaisesRegex(ValueError, "No native protector|absent from the reviewed policy"):
                self.verify(self.archive())

    def test_path_escape_absolute_ads_and_backslashes_are_rejected(self):
        # Python's ZIP writer normalizes Windows backslashes; exercise the
        # bundle's literal path validator directly for that representation.
        with self.assertRaises(ValueError):
            bundle.contained(self.root / "extracted", "sub\\outside.exe")
        for path in ["../outside.exe", "/outside.exe", "C:/outside.exe", "adapter.ps1:stream"]:
            with self.subTest(path=path), self.assertRaises(ValueError):
                self.verify(self.archive((path, b"never write outside")))
            self.assertFalse((self.root / "extracted").exists())

    def test_zip_symlinks_and_case_alias_duplicates_are_rejected(self):
        link = zipfile.ZipInfo("link")
        link.create_system = 3
        link.external_attr = 0o120777 << 16
        for entry in [(link, b"/outside"), ("ADAPTER.PS1", b"replacement")]:
            with self.subTest(entry=entry[0]), self.assertRaises(ValueError):
                self.verify(self.archive(entry))
            self.assertFalse((self.root / "extracted").exists())

    def test_inner_file_mutations_are_rejected(self):
        self.files["protector.exe"] = b"other tool"
        with self.assertRaisesRegex(ValueError, "checksum"):
            self.verify(self.archive())

    def test_checksum_matching_unapproved_configuration_is_rejected(self):
        self.files["configuration.vmp"] = b"changed config"
        for product in self.record["products"].values():
            product["configuration"]["sha256"] = hashlib.sha256(self.files["configuration.vmp"]).hexdigest()
        with self.assertRaisesRegex(ValueError, "absent from"):
            self.verify(self.archive())

    def test_existing_directory_is_not_overwritten(self):
        target = self.root / "extracted"
        target.mkdir()
        (target / "keep").write_text("existing")
        with self.assertRaisesRegex(ValueError, "fresh"):
            self.verify(self.archive())
        self.assertEqual("existing", (target / "keep").read_text())


if __name__ == "__main__":
    unittest.main()
