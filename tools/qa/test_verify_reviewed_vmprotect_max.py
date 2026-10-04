"""Exact-review guards; private real-evidence checks skip in a public checkout.

No protected program is executed, and no fixture is accepted as an actual build.
The optional real checks require the private review and its pinned Capstone.
"""
from __future__ import annotations
import importlib.util
import copy
import json
from pathlib import Path
import shutil
import sys
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools/packaging"))
import verify_reviewed_vmprotect_max as verifier


class ReviewGuardTest(unittest.TestCase):
    def test_duplicate_nonfinite_and_nonobject_json_are_rejected(self):
        for raw in (b'{"a":1,"a":2}', b'{"a":NaN}', b'[]', b'null'):
            with self.subTest(raw=raw), self.assertRaises(ValueError):
                verifier.object_json(raw)

    def test_private_inventory_cannot_escape_its_directory(self):
        for name in ("../secret", "/absolute", "C:/secret", "a\\b", "a/../b", "a//b", "./file", ""):
            with self.subTest(name=name), self.assertRaises(ValueError):
                verifier.relative_path(Path("review"), name)
        self.assertEqual(Path("review/a/b.json"), verifier.relative_path(Path("review"), "a/b.json"))

    def test_an_unreviewed_index_is_not_accepted(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            (path / "coverage-review-index.json").write_text('{"schema":"made-up","files":[]}')
            with self.assertRaisesRegex(ValueError, "not been reviewed"):
                verifier.load_review(path)

    def test_arbitrary_binary_cannot_reach_the_review_loader(self):
        with tempfile.TemporaryDirectory() as temporary:
            binary = Path(temporary) / "example.bin"
            binary.write_bytes(b"synthetic, never a protected artifact")
            with patch.object(verifier, "load_review") as review:
                with self.assertRaisesRegex(ValueError, "exact reviewed"):
                    verifier.verify(binary, binary, Path(temporary))
                review.assert_not_called()

    def test_oversized_and_empty_inputs_are_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "record"
            for data in (b"", b"12345"):
                path.write_bytes(data)
                with self.assertRaises(ValueError):
                    verifier.read(path, 4)

    @staticmethod
    def project():
        functions = [{"alias": f"fixture_{number}"} for number in range(14)]
        document = ET.Element("Document")
        procedures = ET.SubElement(ET.SubElement(document, "Protection", {"Options": "466688", "VMComplexity": "100"}), "Procedures")
        for function in functions:
            ET.SubElement(procedures, "Procedure", {"MapAddress": function["alias"],
                          "IncludedInCompilation": "true", "CompilationType": "2"})
        return document, functions

    def test_project_must_select_exactly_fourteen_unique_aliases(self):
        document, functions = self.project()
        verifier.check_project(ET.tostring(document), functions)
        procedures = document.find("./Protection/Procedures")
        procedures.remove(procedures[-1])
        with self.assertRaisesRegex(ValueError, "exactly fourteen"):
            verifier.check_project(ET.tostring(document), functions)
        document, functions = self.project()
        procedures = document.find("./Protection/Procedures")
        procedures[-1].set("MapAddress", procedures[0].get("MapAddress"))
        with self.assertRaisesRegex(ValueError, "differ"):
            verifier.check_project(ET.tostring(document), functions)

    def test_project_must_use_explicit_ultra_and_no_scripts(self):
        for field, value in (("CompilationType", "1"), ("CompilationType", "0"), ("IncludedInCompilation", "false")):
            document, functions = self.project()
            document.find("./Protection/Procedures/Procedure").set(field, value)
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                verifier.check_project(ET.tostring(document), functions)
        document, functions = self.project()
        ET.SubElement(document, "Script")
        with self.assertRaisesRegex(ValueError, "no script"):
            verifier.check_project(ET.tostring(document), functions)
        with self.assertRaisesRegex(ValueError, "entities"):
            verifier.check_project(b'<!DOCTYPE Document []><Document/>', functions)

    def test_exact_reviewed_options_and_complexity_are_required(self):
        for field, value in (("Options", "332032"), ("VMComplexity", "20"), ("VMComplexity", "101")):
            document, functions = self.project()
            document.find("Protection").set(field, value)
            with self.subTest(field=field, value=value), self.assertRaisesRegex(ValueError, "options or complexity"):
                verifier.check_project(ET.tostring(document), functions)


class ActualPrivateReviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = ROOT / "build/protection-20260927-max/review"
        cls.binary = cls.directory.parent / "compile-ultra-01/jobs/2587f750-7e3b-44c1-8a68-a07917ba0ab6/output/Celeste+.exe"
        cls.original = cls.directory.parent / "aliased-input-04/Celeste+.exe"
        if not all(path.is_file() for path in (cls.binary, cls.original, cls.directory / "coverage-review-index.json")):
            raise unittest.SkipTest("Private reviewed artifact/captures unavailable; public guards remain active")
        if importlib.util.find_spec("capstone") is None:
            raise unittest.SkipTest("Private instruction checks require the review's pinned Capstone decoder")
        import capstone
        review, _ = verifier.load_review(cls.directory)
        if capstone.__version__ != review["capstone_version"]:
            raise unittest.SkipTest("Installed decoder differs from private review; verifier itself refuses this mismatch")

    def copy_review(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        target = Path(temporary.name)
        index_path = self.directory / "coverage-review-index.json"
        index = json.loads(index_path.read_bytes())
        shutil.copyfile(index_path, target / index_path.name)
        for item in index["files"]:
            destination = verifier.relative_path(target, item["path"])
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(self.directory / item["path"], destination)
        return target

    def test_actual_fourteen_function_review_passes_with_explicit_limits(self):
        review, _ = verifier.load_review(self.directory)
        if verifier.gate.source_digest("celeste_plus") != review["source_sha256"]:
            # This exact historical review must reject subsequent source changes.
            # New release work does not turn the old receipt into a reusable pass.
            with self.assertRaisesRegex(ValueError, "Current native sources differ from review"):
                verifier.verify(self.binary, self.original, self.directory)
            return
        report = verifier.verify(self.binary, self.original, self.directory)
        self.assertEqual(14, report["virtualized_functions"])
        self.assertEqual(14, len(report["function_coverage"]))
        self.assertTrue(all(row["transformation_verified"] for row in report["function_coverage"]))
        self.assertTrue(all(row["mode"] == "ultra" and row["project_compilation_type"] == "2"
                            for row in report["function_coverage"]))
        self.assertNotIn("environment_gate_tests", report)
        self.assertFalse(report["environment_tests_attested"])
        self.assertFalse(report["auth_attach_verified"])
        self.assertFalse(report["performance_verified"])
        self.assertFalse(report["publication_approved"])

    def test_unprotected_or_changed_binary_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "exact reviewed"):
            verifier.verify(self.original, self.original, self.directory)
        with tempfile.TemporaryDirectory() as temporary:
            changed = Path(temporary) / "changed.exe"
            raw = self.binary.read_bytes()
            changed.write_bytes(raw[:-1] + bytes([raw[-1] ^ 1]))
            with self.assertRaisesRegex(ValueError, "exact reviewed"):
                verifier.verify(changed, self.original, self.directory)

    def test_tampered_capture_is_rejected_even_with_locally_updated_index(self):
        directory = self.copy_review()
        index_path = directory / "coverage-review-index.json"
        index = json.loads(index_path.read_bytes())
        item = next(row for row in index["files"] if row["path"].endswith("/observation.json"))
        path = directory / item["path"]
        path.write_bytes(path.read_bytes() + b" ")
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            verifier.verify(self.binary, self.original, directory)
        item["sha256"] = verifier.sha(path.read_bytes())
        item["bytes"] = path.stat().st_size
        index_path.write_text(json.dumps(index))
        with self.assertRaisesRegex(ValueError, "not been reviewed"):
            verifier.verify(self.binary, self.original, directory)

    def test_missing_alias_or_changed_project_is_rejected(self):
        directory = self.copy_review()
        review, _ = verifier.load_review(directory)
        mapping_path = directory / review["mapping"]
        original = mapping_path.read_bytes()
        record = json.loads(original)
        record["functions"].pop()
        mapping_path.write_text(json.dumps(record))
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            verifier.verify(self.binary, self.original, directory)
        mapping_path.write_bytes(original)
        project_path = directory / review["project"]
        project_path.write_bytes(project_path.read_bytes().replace(b'CompilationType="2"', b'CompilationType="1"', 1))
        with self.assertRaisesRegex(ValueError, "evidence changed"):
            verifier.verify(self.binary, self.original, directory)

    def test_changed_native_source_identity_is_rejected(self):
        with patch.object(verifier.gate, "source_digest", return_value="0" * 64):
            with self.assertRaisesRegex(ValueError, "sources differ"):
                verifier.verify(self.binary, self.original, self.directory)

    def test_instruction_records_are_redecoded_from_actual_capture_bytes(self):
        import capstone
        review, files = verifier.load_review(self.directory)
        observation = verifier.object_json(files[review["observations"][0]])
        block = observation["blocks"][0]
        base = int(observation["image_base"], 16)
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        decoder.detail = True
        verifier.check_block(block, base, decoder)
        block["instructions"][0]["mnemonic"] = "ret"
        with self.assertRaisesRegex(ValueError, "differs from captured bytes"):
            verifier.check_block(block, base, decoder)

    def test_real_compile_evidence_requires_same_job_refusal_and_stable_output(self):
        review, original_files = verifier.load_review(self.directory)
        original_record = verifier.object_json(original_files[review["compile_evidence"]])
        cases = [
            ("different protector", lambda r: r["installation"].update(sha256="0" * 64)),
            ("different output job", lambda r: r["output"].update(job_id="wrong-job")),
            ("different compiler process", lambda r: r["execute_decline"].update(pid=1)),
            ("executed", lambda r: r.update(execution_performed=True)),
            ("wrong project", lambda r: r["job"].update(project_sha256="0" * 64)),
            ("missing No", lambda r: r.update(execute_decline={})),
            ("different job No", lambda r: r["execute_decline"].update(job_id="other-job")),
            ("unstable file", lambda r: next(row for row in reversed(r["observations"])
                if row.get("phase") == "completed_artifact_read")["output"].update(output_sha256="0" * 64)),
            ("one observation", lambda r: r.update(observations=[row for row in r["observations"]
                if row.get("phase") != "completed_artifact_read"])),
            ("missing registered compiler", lambda r: r.update(last_gui={"windows":[]})),
        ]
        for label, mutate in cases:
            record = copy.deepcopy(original_record)
            mutate(record)
            files = dict(original_files)
            files[review["compile_evidence"]] = json.dumps(record).encode()
            with self.subTest(case=label), patch.object(verifier, "load_review", return_value=(review, files)):
                with self.assertRaises(ValueError):
                    verifier.verify(self.binary, self.original, self.directory)

    def test_mixed_process_lifetime_or_unreviewed_observer_is_rejected(self):
        review, original_files = verifier.load_review(self.directory)
        if verifier.gate.source_digest("celeste_plus") != review["source_sha256"]:
            # This private receipt predates the current native sources. Verify
            # that it remains rejected; its nested observations cannot be used
            # to exercise later validation stages until freshly reviewed.
            with self.assertRaisesRegex(ValueError, "Current native sources differ from review"):
                verifier.verify(self.binary, self.original, self.directory)
            return
        for field, value in (("process_created_filetime", 1), ("observer_sha256", "0" * 64)):
            files = dict(original_files)
            name = review["observations"][1]
            record = verifier.object_json(files[name])
            record[field] = value
            files[name] = json.dumps(record).encode()
            with self.subTest(field=field), patch.object(verifier, "load_review", return_value=(review, files)):
                with self.assertRaisesRegex(ValueError, "process lifetimes|observer differs"):
                    verifier.verify(self.binary, self.original, self.directory)


@unittest.skipUnless(importlib.util.find_spec("capstone"), "Instruction edge checks require Capstone")
class ComputedTrampolineTest(unittest.TestCase):
    def decode(self, raw):
        import capstone
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        decoder.detail = True
        data = bytes.fromhex(raw)
        block = {"address": "0x1000", "size": len(data)}
        return capstone, block, list(decoder.disasm(data, 0x1000))

    def test_call_next_pop_constant_lea_jmp_has_only_the_computed_target(self):
        # Synthetic instructions, independent of private addresses: CALL next;
        # POP RAX; LEA RAX,[RAX+8]; JMP RAX.
        capstone, block, decoded = self.decode("e80000000058488d4008ffe0")
        self.assertTrue(verifier.connected(block, decoded, 0x100d, 0, capstone))
        self.assertFalse(verifier.connected(block, decoded, 0x100e, 0, capstone))

    def test_nonconstant_index_or_different_register_cannot_be_guessed(self):
        for raw in ("e80000000058488d440808ffe0",  # LEA includes RCX index
                    "e80000000059488d4008ffe0",  # POP RCX disagrees
                    "e80100000058488d4008ffe0"): # CALL does not push POP address
            capstone, block, decoded = self.decode(raw)
            with self.subTest(raw=raw):
                self.assertFalse(verifier.connected(block, decoded, 0x100d, 0, capstone))

    def test_interposed_register_shift_keeps_exact_call_next_target(self):
        # CALL next; SAL R9B,3; ROR EAX,1; POP R8; ADD R8,18; JMP R8.
        capstone, block, decoded = self.decode("e80000000041c0e103d1c841584983c01241ffe0")
        self.assertTrue(verifier.connected(block, decoded, 0x1017, 0, capstone))
        self.assertFalse(verifier.connected(block, decoded, 0x1018, 0, capstone))
        # Mutating RSP or memory may change the saved address and must be refused.
        for raw in ("e80000000048c1e40341584983c01241ffe0",
                    "e800000000c024240341584983c01241ffe0",
                    "e8000000005041584983c01241ffe0"):
            capstone, block, decoded = self.decode(raw)
            with self.subTest(raw=raw):
                self.assertFalse(verifier.connected(block, decoded, 0x1017, 0, capstone))

    def test_adjusted_return_requires_exact_caller_stack_and_restore(self):
        import capstone
        decoder = capstone.Cs(capstone.CS_ARCH_X86, capstone.CS_MODE_64)
        decoder.detail = True
        # CALL0x2000 at0x1000; flag-preserving ADD saved return,+16; RET.
        callers = list(decoder.disasm(bytes.fromhex("e8fb0f0000"), 0x1000))
        raw = bytes.fromhex("9c488344240810ff34249d488d642408c3")
        code = list(decoder.disasm(raw, 0x2000))
        self.assertTrue(verifier.adjusted_return(code, callers, 0x1015, 0, capstone))
        self.assertFalse(verifier.adjusted_return(code, callers, 0x1016, 0, capstone))
        self.assertFalse(verifier.adjusted_return(code, [], 0x1015, 0, capstone))
        for changed in (raw.replace(bytes.fromhex("44240810"), bytes.fromhex("44241010")),
                        raw[:-1] + bytes.fromhex("c20800"),
                        raw.replace(bytes.fromhex("8d642408"), bytes.fromhex("8d642410"))):
            with self.subTest(changed=changed.hex()):
                self.assertFalse(verifier.adjusted_return(list(decoder.disasm(changed, 0x2000)),
                                                          callers, 0x1015, 0, capstone))


if __name__ == "__main__":
    unittest.main()
