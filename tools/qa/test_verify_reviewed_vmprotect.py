"""Exact-review guards; private real-evidence checks skip in a public checkout.

No protected program is executed, and no fixture is accepted as an actual build.
The optional real checks require the private review and its pinned Capstone.
"""
from __future__ import annotations
import importlib.util
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
import verify_reviewed_vmprotect as verifier


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
        functions = [{"alias": f"fixture_{number}"} for number in range(7)]
        document = ET.Element("Document")
        procedures = ET.SubElement(ET.SubElement(document, "Protection"), "Procedures")
        for function in functions:
            ET.SubElement(procedures, "Procedure", {"MapAddress": function["alias"],
                          "IncludedInCompilation": "true", "CompilationType": "0"})
        return document, functions

    def test_project_must_select_exactly_seven_unique_aliases(self):
        document, functions = self.project()
        verifier.check_project(ET.tostring(document), functions)
        procedures = document.find("./Protection/Procedures")
        procedures.remove(procedures[-1])
        with self.assertRaisesRegex(ValueError, "exactly seven"):
            verifier.check_project(ET.tostring(document), functions)
        document, functions = self.project()
        procedures = document.find("./Protection/Procedures")
        procedures[-1].set("MapAddress", procedures[0].get("MapAddress"))
        with self.assertRaisesRegex(ValueError, "differ"):
            verifier.check_project(ET.tostring(document), functions)

    def test_project_must_use_explicit_virtualization_and_no_scripts(self):
        for field, value in (("CompilationType", "1"), ("CompilationType", "2"), ("IncludedInCompilation", "false")):
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


class ActualPrivateReviewTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = ROOT / "build/protection-20260927"
        cls.binary = cls.directory / "native-vmp-candidate/Celeste+.exe"
        cls.original = cls.directory / "native-input/Celeste+.exe"
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

    def test_actual_seven_function_review_passes_with_explicit_limits(self):
        report = verifier.verify(self.binary, self.original, self.directory)
        self.assertEqual(7, report["virtualized_functions"])
        self.assertEqual(7, len(report["function_coverage"]))
        self.assertTrue(all(row["transformation_verified"] for row in report["function_coverage"]))
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
        project_path.write_bytes(project_path.read_bytes().replace(b'CompilationType="0"', b'CompilationType="1"', 1))
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


if __name__ == "__main__":
    unittest.main()
