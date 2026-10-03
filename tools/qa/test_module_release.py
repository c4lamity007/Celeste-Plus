"""Real compiled-class ownership/catalog regressions; no production approval."""
from __future__ import annotations

import copy
import ast
import importlib.util
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "tools"))
import module_release as subject
import module_access
import protect_java_release as protection
import protect_module_release as preparation
import modular_runtime_profile as source_profile


class ModuleReleaseCheck(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory(prefix="celeste-module-ownership-")
        cls.directory = Path(cls.temporary.name)
        source = ROOT / "beta-lab/module-package-fixtures/src"
        cls.classes = cls.directory / "classes"
        cls.classes.mkdir()
        home = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"))
        javac = home / "bin" / ("javac.exe" if os.name == "nt" else "javac")
        result = subprocess.run([str(javac), "--release", "21", "-proc:none", "-encoding", "UTF-8", "-d", str(cls.classes),
                                 *map(str, sorted(source.rglob("*.java")))], capture_output=True, text=True, encoding="utf-8")
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        cls.all_entries = {p.relative_to(cls.classes).as_posix(): p.read_bytes() for p in cls.classes.rglob("*.class")}

    @classmethod
    def tearDownClass(cls):
        cls.temporary.cleanup()

    def setUp(self):
        self.entries = {k: v for k, v in self.all_entries.items() if not k.endswith(("BadAlpha.class", "BadResident.class", "ReflectionLeak.class")) and "/runtimeaccess/" not in k}
        self.entries.update({"fabric.mod.json": b'{"id":"fixture"}', "assets/celeste/alpha.glsl": b"void main() {}"})
        self.policy = {"schema": subject.OWNERSHIP_SCHEMA, "host_abi": 1,
            "resident": {"entries": ["fixture/Api.class", "dev/c4lam/celeste/module/delivery/ModuleEntrypoint.class", "fabric.mod.json"],
                         "exports": ["fixture.Api", "dev.c4lam.celeste.module.delivery.ModuleEntrypoint"], "resource_exports": []},
            "modules": [
                {"id": "alpha", "version": "v1", "entrypoint": "dev.c4lam.celeste.module.impl.alpha.Alpha",
                 "entries": ["dev/c4lam/celeste/module/impl/alpha/Alpha*.class", "assets/celeste/alpha.glsl"]},
                {"id": "beta", "version": "v1", "entrypoint": "dev.c4lam.celeste.module.impl.beta.Beta",
                 "entries": ["dev/c4lam/celeste/module/impl/beta/Beta.class"]}],
            "dynamic_dependencies": [], "resource_dependencies": []}

    def split(self):
        return subject.partition(self.entries, self.policy)

    def test_exact_coverage_actual_bytecode_and_private_helper(self):
        owned, proof = self.split()
        self.assertEqual(set().union(*(set(v) for v in owned.values())), set(self.entries))
        self.assertIn("dev/c4lam/celeste/module/impl/alpha/Alpha$Algorithm.class", owned["alpha"])
        self.assertEqual(proof["entry_count"], len(self.entries))
        self.assertGreater(proof["boundaries"]["checked_internal_edges"], 0)

    def test_unassigned_and_double_owned_entries_rejected(self):
        self.entries["unknown.dat"] = b"unknown"
        with self.assertRaisesRegex(ValueError, "exactly one owner"):
            self.split()
        self.policy["resident"]["entries"].append("unknown.dat")
        self.policy["modules"][0]["entries"].append("unknown.dat")
        with self.assertRaisesRegex(ValueError, "exactly one owner"):
            self.split()

    def test_all_classes_resident_fallback_is_forbidden(self):
        self.policy["resident"]["entries"].append("dev/c4lam/celeste/module/impl/alpha/Alpha$Algorithm.class")
        self.policy["modules"][0]["entries"][0] = "dev/c4lam/celeste/module/impl/alpha/Alpha.class"
        with self.assertRaisesRegex(ValueError, "implementation retained in resident"):
            self.split()

    def test_direct_cross_module_reference_rejected(self):
        name = "dev/c4lam/celeste/module/impl/alpha/BadAlpha.class"
        self.entries[name] = self.all_entries[name]
        self.policy["modules"][0]["entries"].append(name)
        with self.assertRaisesRegex(ValueError, "Forbidden class ownership edge"):
            self.split()

    def test_resident_implementation_reference_rejected(self):
        self.entries["fixture/BadResident.class"] = self.all_entries["fixture/BadResident.class"]
        self.policy["resident"]["entries"].append("fixture/BadResident.class")
        with self.assertRaisesRegex(ValueError, "Forbidden class ownership edge"):
            self.split()

    def test_literal_reflection_reference_is_not_a_boundary_bypass(self):
        name = "dev/c4lam/celeste/module/impl/alpha/ReflectionLeak.class"
        self.entries[name] = self.all_entries[name]
        self.policy["modules"][0]["entries"].append(name)
        with self.assertRaisesRegex(ValueError, "Forbidden class ownership edge"):
            self.split()

    def test_undeclared_resident_api_not_exported(self):
        self.policy["resident"]["exports"].remove("fixture.Api")
        with self.assertRaisesRegex(ValueError, "Forbidden class ownership edge"):
            self.split()

    def test_different_loader_package_access_requires_public_or_valid_protected_api(self):
        classes = {name.removesuffix(".class"): module_access.info(data) for name, data in self.all_entries.items()
                   if name.startswith("fixture/runtimeaccess/")}
        bad = module_access.audit(classes, {"alpha": ["fixture/runtimeaccess/AccessCalls"]})
        self.assertEqual({issue[3] for issue in bad["issues"]}, {"hidden", "protectedValue"})
        good = module_access.audit(classes, {"alpha": ["fixture/runtimeaccess/AccessChild"]})
        self.assertEqual(good["issues"], [])
        self.assertGreater(good["checked"], 0)

    def test_class_literals_and_array_components_require_public_resident_classes(self):
        classes = {name.removesuffix(".class"): module_access.info(data) for name, data in self.all_entries.items()
                   if name.startswith("fixture/runtimeaccess/")}
        source = "fixture/runtimeaccess/AccessClassReferences"
        self.assertIn("fixture/runtimeaccess/HiddenApi", classes[source]["class_refs"])
        result = module_access.audit(classes, {"alpha": [source]})
        self.assertTrue(any(issue[2:4] == ["fixture/runtimeaccess/HiddenApi", "<class>"] for issue in result["issues"]))
        # The array-only CP spelling is also normalized to its component class.
        raw = self.all_entries[source + ".class"]
        self.assertIn(b"[Lfixture/runtimeaccess/HiddenApi;", raw)

    def test_protected_instance_receiver_cannot_be_proven_by_cp_alone(self):
        classes = {name.removesuffix(".class"): module_access.info(data) for name, data in self.all_entries.items()
                   if name.startswith("fixture/runtimeaccess/")}
        result = module_access.audit(classes, {"alpha": ["fixture/runtimeaccess/ProtectedReceiverChild"]})
        self.assertTrue(any(issue[3] == "instanceValue" and "receiver evidence" in issue[5] for issue in result["issues"]))

    def test_constructors_are_not_inherited_and_fields_search_interfaces_first(self):
        # Model resolution after parsing; these tables express JVM lookup order independently of javac rewriting a field owner.
        def cls(name, parent=None, interfaces=(), fields=None, methods=None, refs=()):
            return {"name": name, "flags": 1, "parent": parent, "interfaces": list(interfaces),
                    "fields": fields or {}, "methods": methods or {}, "refs": refs, "class_refs": set()}
        classes = {
            "Base": cls("Base", fields={("value", "I"): 0}, methods={("<init>", "()V"): 1}),
            "Face": cls("Face", fields={("value", "I"): 0x19}),
            "Derived": cls("Derived", "Base", ["Face"]),
            "Source": cls("Source", refs=[(9, "Derived", "value", "I"), (10, "Derived", "<init>", "()V")]),
        }
        result = module_access.audit(classes, {"alpha": ["Source"]})
        self.assertEqual([issue[3] for issue in result["issues"]], ["<init>"])
        self.assertIn("unresolved resident constructor", result["issues"][0][5])

    def test_generic_type_variables_are_not_class_dependencies(self):
        codec = subject._codec()
        self.assertEqual(codec.descriptor_classes("(TLease;)Ljava/util/List<TLease;>;"), {"java/util/List"})
        self.assertEqual(codec.descriptor_classes("<Lease:Ljava/lang/Object;:Ljava/io/Closeable;>(TLease;)V"),
                         {"java/lang/Object", "java/io/Closeable"})
        self.assertEqual(codec.descriptor_classes("Lfixture/Outer<TLease;>.Inner<Ljava/lang/String;>;"),
                         {"fixture/Outer", "fixture/Outer$Inner", "java/lang/String"})
        self.assertEqual(codec.descriptor_classes("([Lfixture/Hidden;I)[[Lfixture/Other;"), {"fixture/Hidden", "fixture/Other"})
        raw = self.all_entries["fixture/runtimeaccess/GenericLease.class"]
        name, refs, _ = subject.class_data(raw)
        self.assertEqual(name, "fixture/runtimeaccess/GenericLease")
        self.assertNotIn("ease", refs)
        self.assertIn("fixture/runtimeaccess/AccessApi", refs)


    def test_resident_factory_and_model_implementation_leaks_rejected(self):
        for name in ("dev/c4lam/celeste/module/payload/LeakedEntrypoint.class", "dev/c4lam/celeste/render/model/LeakedModel.class"):
            with self.subTest(name=name):
                self.entries[name] = next(value for path, value in self.entries.items() if path.endswith(".class"))
                self.policy["resident"]["entries"].append(name)
                with self.assertRaisesRegex(ValueError, "implementation retained in resident"):
                    self.split()
                self.entries.pop(name)
                self.policy["resident"]["entries"].remove(name)

    def test_private_resource_copy_has_exact_pin_owner_and_bytes(self):
        with tempfile.TemporaryDirectory(prefix="module-resource-copy-") as directory:
            root = Path(directory)
            source = "runtime-overlay/src/main/resources/assets/celeste/shaders/core/test.fsh"
            target = "runtime-overlay/src/main/resources/assets/celeste/shaders/module/alpha/core/test.fsh"
            raw = b"#version 150\nvoid main() {}\n"
            for name in (source, target):
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(raw)
            contract = root / "runtime-overlay/module-private-resource-copies.json"
            value = {"schema": "celeste-module-resource-copies-1", "copies": [{"source": source, "target": target,
                     "source_sha256": subject.sha(raw), "module": "alpha"}], "exclude_originals": [source]}
            contract.write_text(json.dumps(value))
            self.assertEqual(len(subject.check_source_copies(root)), 3)
            self.assertEqual(subject.resource_copy_plan(root)[2], [source.removeprefix("runtime-overlay/")])
            (root / target).write_bytes(b"changed")
            with self.assertRaisesRegex(ValueError, "differs from reviewed"):
                subject.check_source_copies(root)
            value["copies"][0]["module"] = "other"
            contract.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, "identify its owner"):
                subject.resource_copy_plan(root)
            value["copies"][0]["module"] = "alpha"; contract.write_text(json.dumps(value))
            (root / source).write_bytes(b"changed original")
            with self.assertRaisesRegex(ValueError, "original changed"):
                subject.resource_copy_plan(root)

    def test_external_parent_cannot_smuggle_an_unowned_sibling_implementation(self):
        with self.assertRaisesRegex(ValueError, "unowned Celeste classes"):
            subject.partition(self.entries, self.policy, {"dev/c4lam/celeste/module/impl/HiddenFeature"})

    def test_explicit_dynamic_edges_cannot_cross_module_ownership(self):
        self.policy["dynamic_dependencies"].append({"source": self.policy["modules"][0]["entrypoint"],
                                                    "target": self.policy["modules"][1]["entrypoint"]})
        with self.assertRaisesRegex(ValueError, "Forbidden class ownership edge"):
            self.split()

    def test_resource_edges_respect_ownership(self):
        edge = {"source": self.policy["modules"][1]["entrypoint"], "target": "assets/celeste/alpha.glsl"}
        self.policy["resource_dependencies"].append(edge)
        with self.assertRaisesRegex(ValueError, "Forbidden resource ownership edge"):
            self.split()
        self.policy["resource_dependencies"][0]["source"] = self.policy["modules"][0]["entrypoint"]
        self.split()

    def test_no_embedded_jar_native_or_preexisting_trust_anchor(self):
        for suffix in ("jar", "dll", "so", "dylib"):
            with self.subTest(suffix=suffix):
                name = "assets/celeste/nested." + suffix
                changed = dict(self.entries, **{name: b"hidden"})
                policy = copy.deepcopy(self.policy)
                policy["modules"][0]["entries"].append(name)
                with self.assertRaisesRegex(ValueError, "nested executable/native"):
                    subject.partition(changed, policy)
        self.entries[subject.ANCHOR] = b"oldroot"
        with self.assertRaisesRegex(ValueError, "preexisting module trust root"):
            self.split()

    def test_missing_factory_and_unused_pattern_rejected(self):
        self.policy["modules"][0]["entrypoint"] = "fixture.Absent"
        with self.assertRaisesRegex(ValueError, "Entrypoint not owned"):
            self.split()
        self.policy["modules"][0]["entries"].append("missing.dat")
        with self.assertRaisesRegex(ValueError, "matches no entries"):
            self.split()

    def artifacts(self):
        owned, _ = self.split()
        records, blobs = [], {}
        for spec in self.policy["modules"]:
            data = subject.zip_bytes(owned[spec["id"]])
            record = subject.module_record(spec, data)
            records.append(record)
            blobs[record["filename"]] = data
        executable = subject.executable_catalog("release-1", records)
        return subject.full_catalog(executable, "1" * 64), blobs, subject.canonical(executable)

    def test_separate_resident_identity_and_pinned_executable_root(self):
        catalog, artifacts, anchor = self.artifacts()
        result = subject.verify_catalog(catalog, artifacts, anchor)
        self.assertEqual(catalog["runtime_sha256"], "1" * 64)
        self.assertEqual(catalog["executable_root_sha256"], subject.sha(anchor))
        self.assertNotIn("runtime_sha256", result)
        changed = subject.full_catalog(result, "2" * 64)
        self.assertEqual(catalog["executable_root_sha256"], changed["executable_root_sha256"])

    def test_catalog_mutation_cannot_keep_resident_trust_root(self):
        catalog, artifacts, anchor = self.artifacts()
        catalog["modules"][0]["version"] = "other-version"
        executable = subject.executable_catalog(catalog["release_id"], catalog["modules"])
        catalog["executable_root_sha256"] = subject.sha(subject.canonical(executable))
        with self.assertRaisesRegex(ValueError, "Resident module trust anchor differs"):
            subject.verify_catalog(catalog, artifacts, anchor)

    def test_fixed_executable_and_canonical_entries_are_both_verified(self):
        catalog, artifacts, anchor = self.artifacts()
        name = catalog["modules"][0]["filename"]
        artifacts[name] += b"tampered"
        with self.assertRaisesRegex(ValueError, "Fixed executable bytes changed"):
            subject.verify_catalog(catalog, artifacts, anchor)
        catalog, artifacts, anchor = self.artifacts()
        catalog["modules"][0]["entries_sha256"] = "0" * 64
        executable = subject.executable_catalog(catalog["release_id"], catalog["modules"])
        anchor = subject.canonical(executable)
        catalog["executable_root_sha256"] = subject.sha(anchor)
        with self.assertRaisesRegex(ValueError, "Executable inventory differs"):
            subject.verify_catalog(catalog, artifacts, anchor)

    def test_no_unlisted_or_omitted_artifacts(self):
        catalog, artifacts, anchor = self.artifacts()
        artifacts["not-approved.jar"] = b"unexpected"
        with self.assertRaisesRegex(ValueError, "Unlisted or missing"):
            subject.verify_catalog(catalog, artifacts, anchor)

    def test_ids_are_opaque_and_artifact_bytes_remain_deterministic(self):
        owned, _ = self.split()
        data = subject.zip_bytes(owned["alpha"])
        self.assertEqual(data, subject.zip_bytes(dict(reversed(list(owned["alpha"].items())))))
        a, b = [subject.module_record(self.policy["modules"][0], data) for _ in range(2)]
        self.assertNotEqual(a["artifact_id"], b["artifact_id"])
        self.assertEqual(a["sha256"], b["sha256"])
        self.assertRegex(a["artifact_id"], "^[0-9a-f]{32}$")

    def test_duplicate_ids_and_inert_metadata_are_not_executable_records(self):
        catalog, _, _ = self.artifacts()
        rows = catalog["modules"]
        for change in (lambda: rows.append(copy.deepcopy(rows[0])), lambda: rows[0].update(role="decoy")):
            with self.subTest(change=change):
                change()
                with self.assertRaises(ValueError):
                    subject.executable_catalog("release-1", rows)

    def test_archive_traversal_duplicates_and_symlinks_rejected(self):
        for names in (("../escape",), ("duplicate", "duplicate")):
            output = io.BytesIO()
            with zipfile.ZipFile(output, "w") as archive:
                for name in names:
                    archive.writestr(name, b"bad")
            with self.assertRaises(ValueError):
                subject.archive_entries(output.getvalue())
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            entry = zipfile.ZipInfo("link"); entry.external_attr = 0o120777 << 16
            archive.writestr(entry, b"target")
        with self.assertRaisesRegex(ValueError, "Symlink"):
            subject.archive_entries(output.getvalue())

    def test_every_resource_including_metadata_is_integrity_covered(self):
        for name in ("asset", "META-INF/MANIFEST.MF", "META-INF/.r/4d7a9c2e"):
            self.assertNotEqual(subject.entries_digest({name: b"a"}), subject.entries_digest({name: b"b"}))
        self.assertNotEqual(subject.entries_digest({"ab": b"c"}), subject.entries_digest({"a": b"bc"}))

    def test_module_archive_bounds_match_the_real_receiver(self):
        self.assertEqual(subject.MAX_MODULE, 16 * 1024 * 1024)
        self.assertEqual(subject.MAX_MODULE_EXPANDED, 64 * 1024 * 1024)
        self.assertEqual(subject.MAX_MODULE_ENTRIES, 10000)
        with patch.object(subject, "MAX_MODULE", 8):
            with self.assertRaisesRegex(ValueError, "compressed archive size"):
                subject.module_entries(b"too long to be an archive")
        data = subject.zip_bytes({"one": b"123456", "two": b"123456"})
        with patch.object(subject, "MAX_MODULE_EXPANDED", 10):
            with self.assertRaisesRegex(ValueError, "Expanded archive"):
                subject.module_entries(data)
        with patch.object(subject, "MAX_MODULE_ENTRIES", 1):
            with self.assertRaisesRegex(ValueError, "Too many archive entries"):
                subject.module_entries(data)

    def test_parent_multirelease_is_explicit_and_cannot_enter_module(self):
        entries = {"META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nMulti-Release: true\r\n",
                   "fixture/Base.class": b"base", "META-INF/versions/21/fixture/Current.class": b"21",
                   "META-INF/versions/22/fixture/Future.class": b"22", "module-info.class": b"module"}
        data = subject.zip_bytes(entries)
        self.assertEqual(preparation.dependency_classes(data), {"fixture/Base", "fixture/Current"})
        with self.assertRaisesRegex(ValueError, "Multi-release/module"):
            subject.module_entries(data)
        entries["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\n"
        self.assertEqual(preparation.dependency_classes(subject.zip_bytes(entries)), {"fixture/Base"})

    def test_nested_parent_libraries_must_be_declared_without_omissions(self):
        entries = {"fabric.mod.json": b'{"jars":[{"file":"META-INF/jars/dependency.jar"}]}',
                   "META-INF/jars/dependency.jar": b"exact bytes"}
        self.assertEqual(preparation.nested_dependencies(entries), {"META-INF/jars/dependency.jar": b"exact bytes"})
        entries["META-INF/jars/hidden.jar"] = b"other"
        with self.assertRaisesRegex(ValueError, "declared exactly once"):
            preparation.nested_dependencies(entries)

    def test_private_source_copy_is_exact_and_source_bound(self):
        with tempfile.TemporaryDirectory(prefix="module-copy-") as directory:
            root = Path(directory)
            source = "runtime-overlay/src/main/java/fixture/Original.java"
            target = "runtime-overlay/src/main/java/fixture/Private.java"
            raw = b"package fixture;\r\nclass Original { String OriginalSuffix; }\r\n"
            (root / source).parent.mkdir(parents=True)
            (root / source).write_bytes(raw)
            (root / target).write_bytes(raw.replace(b"class Original", b"class Private"))
            spec = {"schema": "celeste-module-source-copies-1", "copies": [{"source": source, "target": target,
                    "source_sha256": subject.sha(raw), "replacements": {"Original": "Private"}}]}
            (root / "runtime-overlay/module-private-source-copies.json").write_bytes(subject.canonical(spec))
            self.assertEqual(len(subject.check_source_copies(root)), 3)
            (root / "runtime-overlay/manifest.json").write_text(json.dumps({"files": [
                {"path": target.removeprefix("runtime-overlay/"), "baseSha256": "0" * 64}]}))
            with self.assertRaisesRegex(ValueError, "collides with pinned-core"):
                subject.source_copy_plan(root)
            (root / "runtime-overlay/manifest.json").write_text(json.dumps({"files": []}))
            (root / target).write_bytes(b"package fixture; class Private { int changed; }")
            with self.assertRaisesRegex(ValueError, "differs from reviewed"):
                subject.check_source_copies(root)
            (root / source).write_bytes(raw + b"// changed original")
            with self.assertRaisesRegex(ValueError, "original changed"):
                subject.check_source_copies(root)

    def test_existing_package_validation_command_keeps_its_cli_contract(self):
        tree = ast.parse(Path(protection.__file__).read_text(encoding="utf-8"))
        calls = [node for node in ast.walk(tree) if isinstance(node, ast.Call) and node.args
                 and isinstance(node.args[0], ast.Constant) and node.args[0].value == "payload-validation"]
        self.assertEqual(len(calls), 1)
        literal_arguments = [node.value for node in calls[0].args[1].elts[2:] if isinstance(node, ast.Constant)]
        self.assertEqual(literal_arguments, ["--package", "--java-home", "--parent-classpath"])

    def test_modular_profile_excludes_sources_before_compile_and_pins_marker(self):
        with tempfile.TemporaryDirectory(prefix="module-profile-") as directory:
            root = Path(directory)
            repo, runtime = root / "repo", root / "runtime"
            template = repo / source_profile.TEMPLATE
            template.parent.mkdir(parents=True)
            template.write_bytes((ROOT / source_profile.TEMPLATE).read_bytes())
            overlay = {name: b"// reviewed fixture source\n" for name in (source_profile.REGISTRY, *source_profile.EXCLUDED)}
            for name, data in overlay.items():
                (runtime / name).parent.mkdir(parents=True, exist_ok=True)
                (runtime / name).write_bytes(data)
            proof = source_profile.apply(runtime, overlay, repo)
            self.assertEqual((runtime / source_profile.REGISTRY).read_bytes(), template.read_bytes())
            for name in source_profile.EXCLUDED:
                self.assertFalse((runtime / name).exists())
                self.assertEqual((runtime / ".prepare/module-profile-excluded" / name).read_bytes(), overlay[name])
            entries = {source_profile.MARKER: (runtime / "src/main/resources" / source_profile.MARKER).read_bytes()}
            with patch.object(source_profile.prepare_runtime, "read_contract", return_value=({}, {}, overlay)):
                self.assertEqual(source_profile.verify(entries, repo), proof)
                name = source_profile.EXCLUDED[0].removeprefix("src/main/java/").removesuffix(".java") + ".class"
                entries[name] = b"legacy bytecode"
                with self.assertRaisesRegex(ValueError, "Excluded legacy"):
                    source_profile.verify(entries, repo)
            self.assertNotIn(b"Legacy", (runtime / source_profile.REGISTRY).read_bytes())

    def test_resident_runner_refuses_unpinned_module_anchor(self):
        from argparse import Namespace
        args = Namespace(input=Path("unused"), release_id="release-1")
        with self.assertRaisesRegex(ValueError, "exact executable preparation"):
            protection.checked_module_preparation(args, subject.zip_bytes({subject.ANCHOR: b"fake"}))
        args.module_preparation = Path("fake.json")
        with self.assertRaisesRegex(ValueError, "path and exact pin"):
            protection.checked_module_preparation(args, subject.zip_bytes({"anything": b"ordinary"}))
        args.module_preparation = None
        self.assertIsNone(protection.checked_module_preparation(args, subject.zip_bytes({"anything": b"ordinary"})))


if __name__ == "__main__":
    unittest.main()
