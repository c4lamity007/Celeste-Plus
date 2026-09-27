"""Synthetic import tests; no VMP, signing, network, or production approval."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools/packaging"), str(ROOT / "tools/qa")]
import import_gui_native_candidate as importer
import native_protection_gate as gate
from test_native_protection_gate import ProtectionFixture
from test_prepare_vmprotect_map import fixture as mapping_fixture
from test_windows_release_metadata import fixture as pe_fixture


class GuiCandidateImportTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.source = self.base / "source"
        self.candidate = self.base / "candidate"
        self.candidate.mkdir()
        self.output = self.base / "imported"
        data = bytearray(pe_fixture())
        struct.pack_into("<Q", data, 176, 0x140000000)  # PE32+ ImageBase
        struct.pack_into("<I", data, 428, 0x60000020)  # executable code section
        self.input_bytes = bytes(data)
        (self.candidate / "Celeste+.exe").write_bytes(self.input_bytes)
        self.protected = self.base / "gui-output.exe"
        self.protected.write_bytes(self.input_bytes + b"synthetic transformation; not real VMP")
        self.fixture = ProtectionFixture(self.base, self.protected, platform="windows-x64")
        self.symbol = "_R_fixture_selected_function"
        self.map = self.candidate / "Celeste+.map"
        self.map.write_text(" 0001:00000010  " + self.symbol + " 0000000140001010 f fixture.obj\n")
        self.project = self.base / "configuration.fixture"
        doc = ET.Element("Document")
        protection = ET.SubElement(doc, "Protection", {
            "InputFileName": str(self.candidate / "Celeste+.exe"), "OutputFileName": str(self.protected)})
        ET.SubElement(ET.SubElement(protection, "Procedures"), "Procedure", {
            "MapAddress": self.symbol, "CompilationType": "0", "IncludedInCompilation": "true"})
        self.project.write_bytes(ET.tostring(doc))
        self.evidence = self.base / "compiler-evidence.txt"
        self.evidence.write_bytes(b"synthetic compiler observation, not genuine protection evidence")
        for folder in ("shared-auth", "beta-lab/native-shared/src", "beta-lab/native-launcher/src"):
            path = self.source / folder / "fixture.rs"
            path.parent.mkdir(parents=True)
            path.write_bytes(b"// isolated source fixture\n")
        for name in ("beta-lab/native-launcher/Cargo.toml", "beta-lab/native-launcher/Cargo.lock",
                     "beta-lab/native-launcher/assets/celeste-logo.png",
                     "tools/packaging/build-windows-native-candidate.ps1", "tools/packaging/windows_release_metadata.py", "rust-toolchain.toml"):
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"isolated fixture")
        self.record = {
            "schema": "celeste-windows-metadata-candidate-1", "product": "celeste_plus",
            "target": "x86_64-pc-windows-msvc", "artifact": "Celeste+.exe",
            "status": "unsigned-local-candidate", "metadataCheck": "passed",
            "sha256": importer.sha(self.input_bytes), "size": len(self.input_bytes),
            "privateMapSha256": importer.sha(self.map.read_bytes()),
            "sourceFiles": [{"path": name, "sha256": importer.sha((self.source / name).read_bytes())}
                            for name in sorted(importer.source_files(self.source))],
        }
        self.write_candidate()
        f = self.fixture
        f.record["input"] = {"path": str(self.candidate / "Celeste+.exe"), "sha256": self.record["sha256"]}
        f.record["configuration"]["sha256"] = importer.sha(self.project.read_bytes())
        f.policy_record["approved_protectors"][0]["configuration_sha256"] = f.record["configuration"]["sha256"]
        f.policy.write_text(json.dumps(f.policy_record))
        f.report.update({"map_sha256": self.record["privateMapSha256"],
                         "compile_evidence_sha256": importer.sha(self.evidence.read_bytes()),
                         "virtualized_functions": 1,
                         "function_coverage": [{"map_symbol": self.symbol, "input_va": 0x140001010,
                            "project_compilation_type": "0", "mode": "virtualization", "transformation_verified": True}]})
        f.write_report()
        self.addCleanup(patch.stopall)
        patch.object(gate, "POLICY_FILE", f.policy).start()
        patch.object(gate, "source_digest", return_value=f.source_sha256).start()

    def write_candidate(self):
        (self.candidate / "candidate.json").write_text(json.dumps(self.record))

    def run_import(self, **kwargs):
        return importer.import_candidate(self.candidate, self.protected, self.fixture.receipt,
                                         self.evidence, self.output, release_id=self.fixture.record["release_id"], root=self.source, **kwargs)

    def assert_refused(self):
        with self.assertRaises((ValueError, OSError)):
            self.run_import()
        self.assertFalse((self.output / "protected-candidates.json").exists())

    def alias_fixture(self, profile=importer.map_alias.DEFAULT_PROFILE):
        self.input_bytes, original_map = mapping_fixture(profile)
        (self.candidate / "Celeste+.exe").write_bytes(self.input_bytes)
        self.map.write_bytes(original_map)
        self.protected.write_bytes(self.input_bytes + b"synthetic changed output; not real virtualization")
        self.record.update({"sha256": importer.sha(self.input_bytes), "size": len(self.input_bytes),
                            "privateMapSha256": importer.sha(original_map)})
        self.write_candidate()
        directory = self.base / "derived"
        mapping = importer.map_alias.prepare(self.candidate / "Celeste+.exe", self.map,
                                             self.candidate / "candidate.json", directory, profile=profile)
        args = {"derived_map": directory / "Celeste+.map", "map_alias_record": directory / "mapping.json", "profile": profile}
        doc = ET.Element("Document")
        protection = ET.SubElement(doc, "Protection", {
            "InputFileName": str(directory / "Celeste+.exe"), "OutputFileName": str(self.protected)})
        procedures = ET.SubElement(protection, "Procedures")
        for item in mapping["functions"]:
            ET.SubElement(procedures, "Procedure", {"MapAddress": item["alias"], "CompilationType": "0",
                                                    "IncludedInCompilation": "true"})
        self.project.write_bytes(ET.tostring(doc))
        f = self.fixture
        f.record.update({"binary_sha256": importer.sha(self.protected.read_bytes()),
                         "binary_size": self.protected.stat().st_size})
        f.record["input"]["sha256"] = self.record["sha256"]
        f.record["configuration"]["sha256"] = importer.sha(self.project.read_bytes())
        f.policy_record["approved_protectors"][0]["configuration_sha256"] = f.record["configuration"]["sha256"]
        f.policy.write_text(json.dumps(f.policy_record))
        f.report.update({"binary_sha256": f.record["binary_sha256"], "virtualized_functions": len(mapping["functions"]),
                         "map_sha256": self.record["privateMapSha256"],
                         "derived_map_sha256": mapping["aliased_map_sha256"],
                         "map_alias_record_sha256": importer.sha(args["map_alias_record"].read_bytes()),
                         "function_coverage": [{"map_symbol": item["alias"], "original_symbol": item["symbol"],
                            "input_va": int(item["address"], 16), "project_compilation_type": "0",
                            "mode": "virtualization", "transformation_verified": True} for item in mapping["functions"]]})
        f.write_report()
        return args

    def test_import_has_exact_sign_layout_but_no_signing_or_publication_claim(self):
        result = self.run_import()
        self.assertEqual("prepared-unsigned-not-published", result["status"])
        self.assertFalse(result["signed"])
        self.assertFalse(result["published"])
        product = self.output / "celeste_plus"
        self.assertEqual(self.input_bytes, (product / "candidate/Celeste+.exe").read_bytes())
        self.assertEqual(self.protected.read_bytes(), (product / "Celeste+.exe").read_bytes())
        prior = json.loads((self.output / "protected-candidates.json").read_text())
        self.assertEqual(1, len(prior))
        self.assertEqual(importer.sha(self.protected.read_bytes()), prior[0]["protected_sha256"])
        gate.validate(product / "Celeste+.exe", product / "protection-receipt.json",
                      release_id=self.fixture.record["release_id"], product="celeste_plus", platform="windows-x64")

    def test_actual_production_policy_refuses_synthetic_receipt_before_creating_output(self):
        with patch.object(gate, "POLICY_FILE", ROOT / "tools/packaging/native-protection-policy.json"):
            self.assert_refused()
        self.assertFalse(self.output.exists())

    def test_changed_candidate_input_or_map_is_refused(self):
        for path in (self.candidate / "Celeste+.exe", self.map):
            original = path.read_bytes()
            with self.subTest(path=path.name):
                path.write_bytes(original + b"changed")
                self.assert_refused()
                path.write_bytes(original)

    def test_missing_changed_or_partial_source_inventory_is_refused(self):
        original = copy.deepcopy(self.record)
        self.record["sourceFiles"].pop()
        self.write_candidate()
        self.assert_refused()
        self.record = original
        self.write_candidate()
        (self.source / self.record["sourceFiles"][0]["path"]).write_bytes(b"new source")
        self.assert_refused()

    def test_wrong_product_or_stale_receipt_is_refused(self):
        self.record["product"] = "celeste"
        self.write_candidate()
        self.assert_refused()
        self.record["product"] = "celeste_plus"
        self.write_candidate()
        self.fixture.record["binary_sha256"] = "a" * 64
        self.fixture.write_record()
        self.assert_refused()

    def test_compiler_observation_alone_cannot_create_protection_evidence(self):
        self.fixture.report.pop("function_coverage")
        self.fixture.write_report()
        self.assert_refused()

    def test_unverified_partial_wrong_mode_or_wrong_address_coverage_is_refused(self):
        original = copy.deepcopy(self.fixture.report)
        changes = [("transformation_verified", False), ("mode", "mutation"),
                   ("input_va", 0x140001020), ("project_compilation_type", "other")]
        for key, value in changes:
            with self.subTest(key=key):
                self.fixture.report = copy.deepcopy(original)
                self.fixture.report["function_coverage"][0][key] = value
                self.fixture.write_report()
                self.assert_refused()

    def test_changed_compile_evidence_is_refused(self):
        self.evidence.write_bytes(b"another job")
        self.assert_refused()

    def test_unresolved_map_or_non_executable_function_is_refused(self):
        original = self.fixture.report["function_coverage"][0]["input_va"]
        for address in (0x140008000, 0x140000010):
            self.map.write_text(f" 0001:00000010  {self.symbol} {address:016x} f fixture.obj\n")
            self.record["privateMapSha256"] = importer.sha(self.map.read_bytes())
            self.write_candidate()
            self.fixture.report["map_sha256"] = self.record["privateMapSha256"]
            self.fixture.report["function_coverage"][0]["input_va"] = address
            self.fixture.write_report()
            self.assert_refused()
        self.fixture.report["function_coverage"][0]["input_va"] = original

    def test_already_signed_pe_is_refused_before_gate(self):
        data = bytearray(self.protected.read_bytes())
        struct.pack_into("<II", data, 296, 992, 32)
        self.protected.write_bytes(data)
        self.assert_refused()

    def test_fresh_directory_required_and_existing_contents_preserved(self):
        self.output.mkdir()
        keep = self.output / "keep.txt"
        keep.write_text("existing")
        self.assert_refused()
        self.assertEqual("existing", keep.read_text())

    def test_change_during_gate_validation_cannot_import_different_bytes(self):
        original_validate = gate.validate
        def validate(*args, **kwargs):
            result = original_validate(*args, **kwargs)
            self.protected.write_bytes(self.protected.read_bytes() + b"race")
            return result
        with patch.object(gate, "validate", side_effect=validate):
            self.assert_refused()
        self.assertFalse(self.output.exists())

    def test_imported_receipt_is_not_reusable_after_authenticode_changes_bytes(self):
        self.run_import()
        product = self.output / "celeste_plus"
        binary = product / "Celeste+.exe"
        binary.write_bytes(binary.read_bytes() + b"simulated signing change")
        with self.assertRaises(ValueError):
            gate.validate(binary, product / "protection-receipt.json", release_id=self.fixture.record["release_id"],
                          product="celeste_plus", platform="windows-x64")

    def test_alias_inputs_must_be_supplied_together(self):
        for kwargs in ({"derived_map": self.map}, {"map_alias_record": self.map}):
            with self.subTest(kwargs=list(kwargs)):
                with self.assertRaisesRegex(ValueError, "supplied together"):
                    self.run_import(**kwargs)
                self.assertFalse(self.output.exists())

    def test_missing_explicit_project_compilation_type_is_refused(self):
        doc = ET.fromstring(self.project.read_bytes())
        doc.find("./Protection/Procedures/Procedure").attrib.pop("CompilationType")
        with self.assertRaisesRegex(ValueError, "explicit compilation type"):
            importer.selected_functions(ET.tostring(doc), self.map.read_bytes(), self.input_bytes)

    def test_policy_change_during_final_validation_cannot_write_completion_marker(self):
        original_validate = gate.validate
        calls = 0
        def validate(*args, **kwargs):
            nonlocal calls
            calls += 1
            if calls == 2:
                policy = json.loads(self.fixture.policy.read_bytes())
                policy["review_note"] = "changed during validation"
                self.fixture.policy.write_text(json.dumps(policy))
            return original_validate(*args, **kwargs)
        with patch.object(gate, "validate", side_effect=validate):
            self.assert_refused()
        self.assertEqual(2, calls)

    def test_validated_aliases_preserve_original_map_and_bind_both_identities(self):
        args = self.alias_fixture()
        result = self.run_import(**args)
        self.assertEqual(7, len(result["selected_functions"]))
        self.assertEqual(self.map.read_bytes(), (self.output / "celeste_plus/candidate/Celeste+.map").read_bytes())
        self.assertEqual(args["derived_map"].read_bytes(), (self.output / "celeste_plus/gui-evidence/derived.map").read_bytes())
        self.assertEqual(args["map_alias_record"].read_bytes(),
                         (self.output / "celeste_plus/gui-evidence/map-alias-record.json").read_bytes())
        self.assertTrue(all(row["original_symbol"].startswith("_RNvNtCsFixture_11CelestePlus")
                            and row["map_symbol"].startswith("celeste_") for row in result["selected_functions"]))

    def test_alias_report_must_bind_derived_map_and_record(self):
        args = self.alias_fixture()
        original = copy.deepcopy(self.fixture.report)
        for field in ("derived_map_sha256", "map_alias_record_sha256"):
            self.fixture.report = copy.deepcopy(original)
            self.fixture.report.pop(field)
            self.fixture.write_report()
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "exact derived MAP"):
                self.run_import(**args)
            self.assertFalse(self.output.exists())

    def test_alias_coverage_requires_the_original_function_identity(self):
        args = self.alias_fixture()
        self.fixture.report["function_coverage"][0]["original_symbol"] = "different_original_function"
        self.fixture.write_report()
        with self.assertRaisesRegex(ValueError, "transformed-function"):
            self.run_import(**args)
        self.assertFalse(self.output.exists())

    def test_alias_validation_is_real_and_refuses_a_changed_map_even_with_rebound_report(self):
        args = self.alias_fixture()
        path = args["derived_map"]
        path.write_bytes(path.read_bytes() + b"altered footer")
        self.fixture.report["derived_map_sha256"] = importer.sha(path.read_bytes())
        self.fixture.write_report()
        with self.assertRaisesRegex(ValueError, "beyond the seven"):
            self.run_import(**args)
        self.assertFalse(self.output.exists())

    def test_gui_project_cannot_omit_a_validated_alias(self):
        args = self.alias_fixture()
        doc = ET.fromstring(self.project.read_bytes())
        procedures = doc.find("./Protection/Procedures")
        procedures.remove(procedures[0])
        self.project.write_bytes(ET.tostring(doc))
        self.fixture.record["configuration"]["sha256"] = importer.sha(self.project.read_bytes())
        self.fixture.write_record()
        with self.assertRaisesRegex(ValueError, "exactly the validated MAP aliases"):
            self.run_import(**args)
        self.assertFalse(self.output.exists())

    def test_gui_input_requires_the_same_companion_map_used_for_selection(self):
        args = self.alias_fixture()
        doc = ET.fromstring(self.project.read_bytes())
        doc.find("./Protection").set("InputFileName", str(self.candidate / "Celeste+.exe"))
        self.project.write_bytes(ET.tostring(doc))
        self.fixture.record["configuration"]["sha256"] = importer.sha(self.project.read_bytes())
        self.fixture.write_record()
        with self.assertRaisesRegex(ValueError, "companion MAP differs"):
            self.run_import(**args)
        self.assertFalse(self.output.exists())

    def test_explicit_v2_profile_requires_all_fourteen_transformed_functions(self):
        args = self.alias_fixture(importer.map_alias.V2_PROFILE)
        result = self.run_import(**args)
        self.assertEqual(14, len(result["selected_functions"]))
        self.assertEqual(14, len({row["map_symbol"] for row in result["selected_functions"]}))
        self.assertFalse(result["signed"])
        self.assertFalse(result["published"])
        self.assertEqual(self.protected.read_bytes(), (self.output / "celeste_plus/Celeste+.exe").read_bytes())

    def test_v2_record_cannot_implicitly_change_default_profile(self):
        args = self.alias_fixture(importer.map_alias.V2_PROFILE)
        args.pop("profile")
        with self.assertRaises(ValueError):
            self.run_import(**args)
        self.assertFalse(self.output.exists())

    def test_v2_profile_requires_bound_alias_evidence(self):
        with self.assertRaisesRegex(ValueError, "requires its validated derived MAP"):
            self.run_import(profile=importer.map_alias.V2_PROFILE)
        self.assertFalse(self.output.exists())

    def test_v2_profile_does_not_approve_synthetic_protection(self):
        args = self.alias_fixture(importer.map_alias.V2_PROFILE)
        with patch.object(gate, "POLICY_FILE", ROOT / "tools/packaging/native-protection-policy.json"):
            with self.assertRaises(ValueError):
                self.run_import(**args)
        self.assertFalse(self.output.exists())

    def test_v2_profile_rejects_only_seven_covered_functions(self):
        args = self.alias_fixture(importer.map_alias.V2_PROFILE)
        self.fixture.report["function_coverage"] = self.fixture.report["function_coverage"][:7]
        self.fixture.report["virtualized_functions"] = 7
        self.fixture.write_report()
        with self.assertRaisesRegex(ValueError, "cover every selected function"):
            self.run_import(**args)
        self.assertFalse(self.output.exists())

    def test_unknown_profile_is_refused_before_creating_output(self):
        with self.assertRaisesRegex(ValueError, "Unknown MAP selection profile"):
            self.run_import(profile="unreviewed")
        self.assertFalse(self.output.exists())


if __name__ == "__main__":
    unittest.main()
