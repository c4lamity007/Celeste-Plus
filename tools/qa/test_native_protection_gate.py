"""Synthetic publication evidence only: these fixtures do not prove VMP protection."""
from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "packaging"))
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import native_protection_gate as gate


def digest(data):
    return hashlib.sha256(data).hexdigest()


class ProtectionFixture:
    """Approved only through an explicit temporary test policy, never the real policy."""

    def __init__(self, root, binary=None, *, product="celeste_plus", platform="linux-x64"):
        self.root = Path(root)
        self.binary = binary or self.root / "protected-fixture.bin"
        if binary is None:
            self.binary.write_bytes(b"synthetic transformed executable; not real protection")
        self.receipt = self.root / "receipt.json"
        self.policy = self.root / "test-policy.json"
        self.source_sha256 = digest(b"isolated source inputs")
        self.record = {
            "schema": "celeste-native-protection-receipt-1",
            "release_id": "celeste-plus-test-protected-01",
            "product": product, "platform": platform,
            "binary_sha256": digest(self.binary.read_bytes()),
            "binary_size": self.binary.stat().st_size,
            "source_sha256": self.source_sha256,
        }
        for name in ("input", "protector", "configuration", "verifier"):
            path = self.root / (name + ".fixture")
            path.write_bytes(("isolated synthetic " + name).encode())
            self.record[name] = {"path": path.name, "sha256": digest(path.read_bytes())}
        self.report = {
            "status": "passed", "binary_sha256": self.record["binary_sha256"],
            "source_sha256": self.source_sha256, "protection": "native-virtualization",
            "virtualized_functions": 3, "environment_policy": "deny-vm-and-sandbox-v1",
            "environment_gate_tests": "passed",
        }
        self.policy_record = {
            "schema": "celeste-native-protection-policy-1", "required_protection": "native-virtualization",
            "environment_policy": "deny-vm-and-sandbox-v1", "approved_protectors": [{
                "protector_sha256": self.record["protector"]["sha256"],
                "configuration_sha256": self.record["configuration"]["sha256"],
                "verifier_sha256": self.record["verifier"]["sha256"], "platform": platform,
            }],
        }
        self.write_report()
        self.policy.write_text(json.dumps(self.policy_record), encoding="utf-8")

    def write_record(self):
        self.receipt.write_text(json.dumps(self.record), encoding="utf-8")

    def write_report(self):
        path = self.root / "report.json"
        path.write_text(json.dumps(self.report), encoding="utf-8")
        self.record["report"] = {"path": path.name, "sha256": digest(path.read_bytes())}
        self.write_record()

    def validate(self, **overrides):
        identity = {key: self.record[key] for key in ("release_id", "product", "platform")}
        return gate.validate(self.binary, self.receipt, **(identity | overrides))


class NativeProtectionGateTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.fixture = ProtectionFixture(Path(temporary.name))
        self.policy_patch = patch.object(gate, "POLICY_FILE", self.fixture.policy)
        self.policy_patch.start()
        self.addCleanup(self.policy_patch.stop)
        self.source_patch = patch.object(gate, "source_digest", return_value=self.fixture.source_sha256)
        self.source_patch.start()
        self.addCleanup(self.source_patch.stop)

    def test_exact_synthetic_evidence_passes_only_under_explicit_test_policy(self):
        f = self.fixture
        result = f.validate()
        self.assertEqual(result, {
            "status": "verified-publication-provenance", "binary_sha256": digest(f.binary.read_bytes()),
            "receipt_sha256": digest(f.receipt.read_bytes()), "policy_sha256": digest(f.policy.read_bytes()),
            "report_sha256": digest((f.root / "report.json").read_bytes()),
            "protection": "native-virtualization", "environment_policy": "deny-vm-and-sandbox-v1",
        })

    def test_default_policy_has_no_approved_protector_and_rejects_synthetic_evidence(self):
        actual = gate.ROOT / "tools/packaging/native-protection-policy.json"
        self.assertEqual(gate.read(actual)["approved_protectors"], [])
        with patch.object(gate, "POLICY_FILE", actual):
            with self.assertRaisesRegex(ValueError, "No reviewed protector"):
                self.fixture.validate()

    def test_missing_empty_malformed_and_duplicate_receipts_fail_closed(self):
        f = self.fixture
        with self.assertRaises(ValueError):
            gate.validate(f.binary, None, release_id=f.record["release_id"], product="celeste_plus", platform="linux-x64")
        f.receipt.unlink()
        with self.assertRaises(ValueError):
            f.validate()
        for invalid in ("", "{", "[]", "null", "{}", '{"schema":1,"schema":2}', "x" * (1024 * 1024 + 1)):
            with self.subTest(invalid=invalid[:40]):
                f.receipt.write_text(invalid, encoding="utf-8")
                with self.assertRaises(ValueError):
                    f.validate()

    def test_release_product_platform_and_source_must_match(self):
        for field, value in (("release_id", "celeste-plus-other"), ("product", "celeste"), ("platform", "windows-x64")):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "another release/product/platform"):
                self.fixture.validate(**{field: value})
        with patch.object(gate, "source_digest", return_value="0" * 64):
            with self.assertRaisesRegex(ValueError, "sources changed"):
                self.fixture.validate()

    def test_binary_hash_size_and_untransformed_input_are_bound(self):
        f = self.fixture
        original = f.binary.read_bytes()
        f.binary.write_bytes(b"!" + original[1:])
        with self.assertRaisesRegex(ValueError, "differs"):
            f.validate()
        f.binary.write_bytes(original)
        f.record["binary_size"] += 1
        f.write_record()
        with self.assertRaisesRegex(ValueError, "differs"):
            f.validate()
        f.record["binary_size"] -= 1
        (f.root / f.record["input"]["path"]).write_bytes(original)
        f.record["input"]["sha256"] = digest(original)
        f.write_record()
        with self.assertRaisesRegex(ValueError, "identical to its unprotected input"):
            f.validate()

    def test_evidence_files_must_match_record_and_exist(self):
        f = self.fixture
        for name in ("input", "protector", "configuration", "verifier", "report"):
            with self.subTest(name=name):
                path = f.root / f.record[name]["path"]
                original = path.read_bytes()
                path.write_bytes(original + b"modified")
                with self.assertRaisesRegex(ValueError, "evidence changed"):
                    f.validate()
                path.unlink()
                with self.assertRaises(ValueError):
                    f.validate()
                path.write_bytes(original)

    def test_rehashing_unapproved_tool_or_configuration_cannot_approve_it(self):
        f = self.fixture
        original_record = copy.deepcopy(f.record)
        for name in ("protector", "configuration", "verifier"):
            with self.subTest(name=name):
                path = f.root / f.record[name]["path"]
                original = path.read_bytes()
                path.write_bytes(b"unreviewed replacement")
                f.record[name]["sha256"] = digest(path.read_bytes())
                f.write_record()
                with self.assertRaisesRegex(ValueError, "No reviewed protector"):
                    f.validate()
                path.write_bytes(original)
                f.record = copy.deepcopy(original_record)

    def test_report_must_prove_virtualization_and_environment_checks_for_exact_output(self):
        f = self.fixture
        original = copy.deepcopy(f.report)
        invalid_fields = {
            "status": ("failed", None), "binary_sha256": ("0" * 64,), "source_sha256": ("0" * 64,),
            "protection": ("string-obfuscation",), "virtualized_functions": (0, -1, True, "3", 3.0, None),
            "environment_policy": ("log-only",), "environment_gate_tests": ("failed", None),
        }
        for field, values in invalid_fields.items():
            for value in values:
                with self.subTest(field=field, value=value):
                    f.report = {**original, field: value}
                    f.write_report()  # Correct file hash alone must not make its claims acceptable.
                    with self.assertRaisesRegex(ValueError, "does not prove"):
                        f.validate()

    def test_weaker_policy_cannot_silently_downgrade_required_protection(self):
        f = self.fixture
        for field, value in (("required_protection", "string-obfuscation"), ("environment_policy", "log-only")):
            with self.subTest(field=field):
                f.policy.write_text(json.dumps({**f.policy_record, field: value}), encoding="utf-8")
                with self.assertRaises(ValueError):
                    f.validate()


class NativeSourceDigestTest(unittest.TestCase):
    def test_embedded_inputs_and_all_source_trees_are_pinned(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            paths = ["beta-lab/native-shared/src/environment_guard.rs", "shared-auth/dpop.rs",
                     "beta-lab/native-launcher/assets/celeste-logo.png",
                     "beta-lab/native-launcher/Cargo.toml", "beta-lab/native-launcher/Cargo.lock",
                     "beta-lab/native-launcher/src/main.rs"]
            for name in paths:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(name.encode())
            for product in ("celeste_plus",):
                expected = gate.source_digest(product, root)
                for name in paths:
                    with self.subTest(product=product, path=name):
                        path = root / name
                        original = path.read_bytes()
                        path.write_bytes(original + b"changed")
                        self.assertNotEqual(gate.source_digest(product, root), expected)
                        path.unlink()
                        with self.assertRaises(ValueError):
                            gate.source_digest(product, root)
                        path.write_bytes(original)


if __name__ == "__main__":
    unittest.main()
