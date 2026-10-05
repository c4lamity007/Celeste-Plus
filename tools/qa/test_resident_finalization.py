"""Offline release-byte consistency fixtures; these do not establish VMP approval."""
import copy
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import module_release as modules
import protect_module_release as subject


class ResidentFinalizationCheck(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="celeste-resident-final-")
        self.root = Path(self.temporary.name)
        self.package_dir, self.release_dir = self.root / "package", self.root / "release"
        self.package_dir.mkdir()
        self.release_dir.mkdir()
        self.parts = [{modules.ANCHOR: b"fixture trust root", modules._codec().AUTH: b"fixture descriptor"},
                      {"assets/celeste/fixture": b"validated bytes"}]
        self.package = {"schema": "celeste-beta-payload-package-1", "validation_only": False,
                        "release_id": "fixture-1", "runtime_sha256": "", "payloads": []}
        self.beta = {"schema": "celeste-beta-release-2", "release_id": "fixture-1", "artifacts": []}
        self.validation = {"status": "passed"}
        self.rebuild()

    def tearDown(self):
        self.temporary.cleanup()

    def rebuild(self):
        merged = self.parts[0] | self.parts[1]
        digest = modules._codec().runtime_digest(merged)
        for metadata in (self.package, self.beta, self.validation):
            metadata["runtime_sha256"] = digest
        self.package["payloads"] = []
        self.beta["artifacts"] = []
        for identity, filename, data in (
            ("bootstrap-a", "bootstrap-a.jar", b"fixture A, not a protected executable"),
            ("bootstrap-b", "bootstrap-b.bin", b"fixture B, not a protected executable"),
            ("payload-a", "payload-a.bin", modules.zip_bytes(self.parts[0])),
            ("payload-b", "payload-b.bin", modules.zip_bytes(self.parts[1])),
        ):
            record = {"id": identity, "filename": filename, "size": len(data), "sha256": modules.sha(data),
                      "role": "payload" if identity.startswith("payload") else "bootstrap"}
            self.beta["artifacts"].append(record)
            (self.release_dir / filename).write_bytes(data)
            if identity.startswith("payload"):
                self.package["payloads"].append({k: record[k] for k in ("filename", "size", "sha256")})
                self.validation[identity.replace("-", "_") + "_sha256"] = record["sha256"]
                (self.package_dir / filename).write_bytes(data)
        self.save()

    def save(self):
        for path, metadata in ((self.package_dir / "package.json", self.package),
                               (self.package_dir / "java-validation.json", self.validation),
                               (self.release_dir / "beta-release.json", self.beta)):
            path.write_bytes(modules.canonical(metadata))

    def verify(self):
        self.save()
        return subject.verified_resident_entries(self.package_dir, self.release_dir, self.package, self.beta)

    def test_exact_released_bytes_succeed(self):
        self.assertEqual(self.verify(), self.parts[0] | self.parts[1])

    def test_copied_payload_and_bootstrap_must_match_catalog(self):
        for record in self.beta["artifacts"]:
            path = self.release_dir / record["filename"]
            original = path.read_bytes()
            path.write_bytes(original + b"changed")
            with self.subTest(artifact=record["id"]), self.assertRaisesRegex(ValueError, "Released resident artifact changed"):
                self.verify()
            path.write_bytes(original)

    def test_original_validated_package_cannot_drift(self):
        path = self.package_dir / "payload-a.bin"
        path.write_bytes(path.read_bytes() + b"changed")
        with self.assertRaisesRegex(ValueError, "Verified resident package part changed"):
            self.verify()

    def test_java_validation_is_required_and_exact(self):
        original = copy.deepcopy(self.validation)
        for key, value in (("status", "failed"), ("runtime_sha256", "f"*64),
                           ("payload_a_sha256", "a"*64), ("payload_b_sha256", "b"*64)):
            self.validation = original | {key: value}
            with self.subTest(key=key), self.assertRaisesRegex(ValueError, "validation"):
                self.verify()
        self.validation = original
        self.save()
        (self.package_dir / "java-validation.json").unlink()
        with self.assertRaisesRegex(ValueError, "Missing or linked"):
            subject.verified_resident_entries(self.package_dir, self.release_dir, self.package, self.beta)

    def test_fixture_and_wrong_schema_cannot_finalize(self):
        self.package["validation_only"] = True
        with self.assertRaisesRegex(ValueError, "release state"):
            self.verify()
        self.package["validation_only"] = False
        self.beta["schema"] = "wrong"
        with self.assertRaisesRegex(ValueError, "release state"):
            self.verify()

    def test_runtime_digest_is_recomputed_from_actual_bytes(self):
        for metadata in (self.package, self.beta, self.validation):
            metadata["runtime_sha256"] = "f"*64
        with self.assertRaisesRegex(ValueError, "canonical digest differs"):
            self.verify()

    def test_exact_artifact_set_rejects_missing_duplicate_and_extra(self):
        original = copy.deepcopy(self.beta["artifacts"])
        for records in (original[:-1], original + [original[0]], original[:-1] + [original[0]]):
            self.beta["artifacts"] = records
            with self.subTest(count=len(records)), self.assertRaises(ValueError):
                self.verify()
        self.beta["artifacts"] = original

    def test_artifact_identity_role_path_and_size_are_bounded(self):
        original = copy.deepcopy(self.beta["artifacts"][0])
        for key, value in (("id", "other"), ("filename", "../other.jar"), ("role", "payload"),
                           ("size", True), ("size", 0), ("size", 64*1024*1024+1), ("sha256", "bad")):
            self.beta["artifacts"][0] = original | {key: value}
            with self.subTest(key=key, value=value), self.assertRaisesRegex(ValueError, "identity or bounds"):
                self.verify()

    def test_two_ordered_parts_are_required(self):
        self.package["payloads"].reverse()
        with self.assertRaisesRegex(ValueError, "two ordered"):
            self.verify()

    def test_duplicate_entry_across_parts_is_rejected(self):
        self.parts[1][modules.ANCHOR] = self.parts[0][modules.ANCHOR]
        self.rebuild()
        with self.assertRaisesRegex(ValueError, "Duplicate resident part entry"):
            self.verify()

    def test_changed_descriptor_is_covered_by_exact_part_hash(self):
        # Descriptor is excluded only from its self-referential canonical digest.
        # It remains covered by the validated archive and final artifact hashes.
        changed = self.parts[0] | {modules._codec().AUTH: b"changed descriptor"}
        (self.release_dir / "payload-a.bin").write_bytes(modules.zip_bytes(changed))
        with self.assertRaisesRegex(ValueError, "artifact changed"):
            self.verify()

    def test_full_finalization_uses_release_bytes_before_publishing_modules(self):
        module = modules.zip_bytes({"fixture/Module.class": b"fixture, not executable"})
        record = modules.module_record({"id": "fixture", "version": "v1", "entrypoint": "fixture.Module"}, module)
        executable = modules.executable_catalog("fixture-1", [record])
        self.parts[0][modules.ANCHOR] = modules.canonical(executable)
        self.rebuild()
        (self.root / "modules").mkdir()
        (self.root / "modules" / record["filename"]).write_bytes(module)
        prepared = {"release_id": "fixture-1", "modules": [record],
                    "executable_root_sha256": modules.sha(modules.canonical(executable))}
        original = (self.release_dir / "payload-b.bin").read_bytes()
        (self.release_dir / "payload-b.bin").write_bytes(b"stale released copy")
        with patch.object(subject, "preparation", return_value=prepared):
            with self.assertRaisesRegex(ValueError, "artifact changed"):
                subject.finalize(self.root / "module-preparation.json", "c"*64, self.package_dir, self.release_dir)
            self.assertFalse((self.release_dir / "modules").exists())
            self.assertFalse((self.release_dir / "module-release.json").exists())
            (self.release_dir / "payload-b.bin").write_bytes(original)
            result = subject.finalize(self.root / "module-preparation.json", "c"*64, self.package_dir, self.release_dir)
            self.assertEqual(result["module_count"], 1)
            self.assertEqual((self.release_dir / "modules" / record["filename"]).read_bytes(), module)


if __name__ == "__main__":
    unittest.main()
