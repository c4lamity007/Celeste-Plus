"""Synthetic archive/config checks; does not invoke Java, an obfuscator, signing or deployment."""
import json
from pathlib import Path
import tempfile
import unittest

import prepare_obfuscation as subject


class PreparationCheck(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="celeste-beta-obfuscation-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "original-a.jar"
        self.entries = {
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\nCeleste-Beta-Bootstrap: A\r\nAgent-Class: dev.celestebeta.attach.AgentMain\r\nMain-Class: dev.celestebeta.attach.AttachCli\r\n\r\n",
            "META-INF/celeste-beta/bootstrap-trust.json": b'{"fixture":true}',
            "META-INF/celeste-beta/host-jars/celeste-beta-bridges.jar": b"fixture stable archive",
            subject.HOST: b"fixture host original",
            "dev/celestebeta/bootstrap/external/ResidentSupport.class": b"fixture implementation",
            "dev/celestebeta/host/InteractionHookBridge.class": b"fixture canonical bridge",
        }
        for prefix in subject.STABLE_PREFIXES:
            self.entries[prefix + "Fixture.class"] = prefix.encode()
        subject.write_archive(self.source, self.entries)
        self.stage = self.root / "stage"

    def implementation(self, additional=None):
        protected = self.root / "protected.jar"
        subject.write_archive(protected, {subject.HOST: b"fixture protected host",
                                         "helper/a/B.class": b"fixture helper",
                                         "META-INF/helper/a/B.RANDOM": b"fixture ciphertext"} | (additional or {}))
        return protected

    def test_split_and_merge_conserve_exact_stable_contract(self):
        report = subject.stage_a(self.source, self.stage)
        self.assertEqual(report["implementation_classes"], 2)
        stable = subject.read_archive(self.stage / "bootstrap-a-stable.jar")
        thin = subject.read_archive(self.stage / "bootstrap-a-thin.jar")
        self.assertEqual(stable | thin, self.entries)
        self.assertFalse(stable.keys() & thin.keys())
        self.assertIn("dev/celestebeta/host/InteractionHookBridge.class", stable)
        output = self.root / "merged.jar"
        subject.merge_a(self.stage, self.implementation(), output)
        merged = subject.read_archive(output)
        self.assertEqual({name: merged[name] for name in stable}, stable)
        self.assertEqual(merged[subject.HOST], b"fixture protected host")
        self.assertIn("META-INF/helper/a/B.RANDOM", merged)
        self.assertEqual(subject.read_archive(self.source), self.entries)

    def test_stage_does_not_replace_existing_directory(self):
        self.stage.mkdir()
        with self.assertRaisesRegex(ValueError, "fresh"):
            subject.stage_a(self.source, self.stage)

    def test_merge_rejects_source_changed_since_split(self):
        subject.stage_a(self.source, self.stage)
        with self.source.open("ab") as output:
            output.write(b"changed")
        with self.assertRaisesRegex(ValueError, "Source A changed"):
            subject.merge_a(self.stage, self.implementation(), self.root / "merged.jar")

    def test_merge_rejects_original_resource_collision(self):
        subject.stage_a(self.source, self.stage)
        protected = self.implementation({"META-INF/celeste-beta/bootstrap-trust.json": b"changed"})
        with self.assertRaisesRegex(ValueError, "collides"):
            subject.merge_a(self.stage, protected, self.root / "merged.jar")

    def test_merge_rejects_new_class_in_canonical_boundary(self):
        subject.stage_a(self.source, self.stage)
        protected = self.implementation({"dev/celestebeta/host/NewHelper.class": b"unexpected"})
        with self.assertRaisesRegex(ValueError, "stable boundary"):
            subject.merge_a(self.stage, protected, self.root / "merged.jar")

    def test_merge_requires_fixed_host(self):
        subject.stage_a(self.source, self.stage)
        protected = self.root / "protected.jar"
        subject.write_archive(protected, {"elsewhere/Host.class": b"renamed"})
        with self.assertRaisesRegex(ValueError, "fixed reflective"):
            subject.merge_a(self.stage, protected, self.root / "merged.jar")

    def test_merge_never_replaces_output(self):
        subject.stage_a(self.source, self.stage)
        output = self.root / "merged.jar"
        output.write_bytes(b"existing")
        with self.assertRaisesRegex(ValueError, "replace existing"):
            subject.merge_a(self.stage, self.implementation(), output)
        self.assertEqual(output.read_bytes(), b"existing")

    def test_mapping_drives_entry_class(self):
        mapping = self.root / "mapping.json"
        mapped = "example/newPackage/Session"
        data = {"schemaVersion": 1, "format": "word-obfuscator-symbol-map", "classes": {subject.PAYLOAD_ENTRY: mapped}, "methods": {}}
        mapping.write_text(json.dumps(data), encoding="utf-8")
        archive = self.root / "payload.jar"
        subject.write_archive(archive, {mapped + ".class": b"fixture class"})
        self.assertEqual(subject.payload_entrypoint(mapping, archive), "example.newPackage.Session")
        data["methods"][subject.PAYLOAD_ENTRY + ".ready()Ljava/util/concurrent/CompletionStage;"] = "changed"
        mapping.write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "Public lifecycle"):
            subject.payload_entrypoint(mapping, archive)

    def test_mapping_rejects_parent_namespace(self):
        mapping = self.root / "mapping.json"
        mapping.write_text(json.dumps({"schemaVersion": 1, "format": "word-obfuscator-symbol-map",
                                      "classes": {subject.PAYLOAD_ENTRY: "dev/celestebeta/Bad"}, "methods": {}}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "parent-owned"):
            subject.payload_entrypoint(mapping)

    def test_profiles_keep_native_and_schema_risks_disabled(self):
        profiles = Path(__file__).with_name("profiles")
        values = [json.loads(path.read_text(encoding="utf-8")) for path in sorted(profiles.glob("*.json"))]
        self.assertEqual(len(values), 4)
        for value in values:
            for option in ("nativeTranspile", "nativeVirtualization", "targetedNativeVirtualization",
                           "virtualizeExistingNativeLibraries", "crazyObfuscation", "relocateAssets", "fragmentStringVault"):
                self.assertFalse(value[option], option)
            self.assertFalse(value["autoDetectFrameworkClasses"])
            self.assertEqual(value["priorityNativePatterns"], [])
            self.assertTrue(value["encryptStrings"])
        self.assertFalse(values[0]["transformKeptApplicationClasses"])
        self.assertIn("dev.celestebeta.host.**", values[0]["keepPatterns"])
        self.assertIn("dev.redstones.mediaplayerinfo.**", values[0]["keepPatterns"])
        self.assertTrue(values[1]["transformKeptApplicationClasses"])
        self.assertTrue(values[2]["transformKeptApplicationClasses"])
        self.assertIn("dev.celestebeta.bootstrap.internal.MemoryPayloadBootstrap$PreparedGame", values[2]["keepPatterns"])
        self.assertTrue(values[3]["transformKeptApplicationClasses"])
        self.assertFalse(values[3]["renameClasses"])
        self.assertFalse(values[3]["renamePrivateMembers"])
        self.assertEqual(sorted(profiles.glob("*.json"))[3].name, "04-payload-protected-v2.json")

    def test_payload_boundary_comparison_detects_modified_bridge_or_extra_helper(self):
        source = self.root / "payload.jar"
        entries = {"dev/celestebeta/host/TestBridge.class": b"exact bridge",
                   "dev/redstones/mediaplayerinfo/MediaInfo.class": b"exact JNI",
                   "assets/celeste/test.txt": b"exact asset", "app/Main.class": b"original body"}
        subject.write_archive(source, entries)
        protected = self.root / "payload-protected.jar"
        subject.write_archive(protected, entries | {"app/Main.class": b"changed application"})
        self.assertEqual(subject.check_payload_boundaries(source, protected)["verified_unchanged_entries"], 3)
        changed = self.root / "changed.jar"
        subject.write_archive(changed, entries | {"dev/celestebeta/host/TestBridge.class": b"changed bridge"})
        with self.assertRaisesRegex(ValueError, "changed canonical"):
            subject.check_payload_boundaries(source, changed)
        extra = self.root / "extra.jar"
        subject.write_archive(extra, entries | {"dev/celestebeta/host/Decoder.class": b"external helper"})
        with self.assertRaisesRegex(ValueError, "added or removed"):
            subject.check_payload_boundaries(source, extra)


class PayloadPreparationCheck(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="celeste-beta-payload-stage-")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / "payload-source.jar"
        self.stage = self.root / "payload-stage"
        self.stable = {
            "fabric.mod.json": b'{"entrypoints":{"client":["dev.c4lam.celeste.CelesteClient"]}}',
            "celeste.mixins.json": b'{"package":"dev.c4lam.celeste.mixin"}',
            "META-INF/MANIFEST.MF": b"Manifest-Version: 1.0\r\n\r\n",
            "META-INF/.r/4d7a9c2e": b"unchanged fixture descriptor",
            "META-INF/celeste-beta/fixture.json": b"unchanged beta metadata",
            "assets/celeste/fixture.dat": b"unchanged authored asset",
            "unusual-authored-resource.txt": b"every original nonclass remains exact",
            "mediaplayerinfo/natives/win/MediaPlayerInfo.dll": b"MZ fixture native bytes",
            "dev/celestebeta/host/InteractionHookBridge.class": b"unchanged canonical bridge",
            "dev/celestebeta/host/nested/Fixture.class": b"unchanged entire canonical prefix",
            "dev/redstones/mediaplayerinfo/MediaInfo.class": b"unchanged JNI model",
            "dev/redstones/mediaplayerinfo/impl/win/WindowsMediaPlayerInfo.class": b"unchanged JNI wrapper",
            "dev/c4lam/celeste/mixin/FixtureMixin.class": b"unchanged startup mixin",
            "dev/c4lam/celeste/mixin/module/NestedFixtureMixin.class": b"unchanged nested startup mixin",
            "dev/c4lam/celeste/auth/InstallCredential.class": b"unchanged reflected credential",
            "dev/c4lam/celeste/auth/HardwareFingerprint$ComputerSystemProperty.class": b"unchanged WMI enum",
            "dev/c4lam/celeste/render/font/FontData.class": b"unchanged reflected font root",
            "dev/c4lam/celeste/render/font/FontData$GlyphData.class": b"unchanged reflected font model",
        }
        self.thin = {
            subject.PAYLOAD_ENTRY + ".class": b"original lifecycle bytecode",
            "dev/c4lam/celeste/CelesteClient.class": b"original application entry",
            "dev/c4lam/celeste/module/Body.class": b"original application body",
            "dev/c4lam/celeste/auth/HardwareFingerprint.class": b"ordinary fingerprint implementation",
            "dev/c4lam/celeste/auth/HardwareFingerprint$State.class": b"ordinary non-property nested class",
            "dev/c4lam/celeste/render/font/FontDataExtra.class": b"ordinary class sharing model name prefix",
        }
        self.entries = self.stable | self.thin
        subject.write_archive(self.source, self.entries)
        self.mapping = self.root / "mapping.json"
        self.write_mapping()

    def write_mapping(self, **overrides):
        value = {"schemaVersion": 1, "format": "word-obfuscator-symbol-map", "classes": {}, "fields": {}, "methods": {}}
        value.update(overrides)
        self.mapping.write_text(json.dumps(value), encoding="utf-8")

    def protected_entries(self):
        return {name: value + b" protected body and string calls" for name, value in self.thin.items()} | {
            "helper/v2/Decoder.class": b"generated runtime helper",
            "META-INF/helper/v2/Decoder.random": b"generated encrypted string vault",
        }

    def protected(self, entries=None, name="protected.jar"):
        path = self.root / name
        subject.write_archive(path, self.protected_entries() if entries is None else entries)
        return path

    def rewrite_archive(self, path, entries):
        replacement = path.with_suffix(".replacement.jar")
        subject.write_archive(replacement, entries)
        replacement.replace(path)

    def rejects_merge(self, protected, output_name="merged.jar"):
        output = self.root / output_name
        with self.assertRaises(ValueError):
            subject.merge_payload(self.stage, protected, self.mapping, output)
        self.assertFalse(output.exists(), "Rejected merge must not create a partial protected payload")

    def test_stage_conserves_every_source_entry_and_only_splits_body_classes(self):
        report = subject.stage_payload(self.source, self.stage)
        stable = subject.read_archive(self.stage / "payload-stable.jar")
        thin = subject.read_archive(self.stage / "payload-thin.jar")
        self.assertEqual(stable, self.stable)
        self.assertEqual(thin, self.thin)
        self.assertEqual(stable | thin, self.entries)
        self.assertFalse(stable.keys() & thin.keys())
        self.assertTrue(all(name.endswith(".class") for name in thin))
        record = json.loads((self.stage / "payload-stage.json").read_text(encoding="utf-8"))
        self.assertEqual(record["schema"], "celeste-beta-payload-obfuscation-stage-2")
        self.assertEqual(report["stable_entries"], len(self.stable))
        self.assertEqual(report["implementation_classes"], len(self.thin))
        self.assertEqual(subject.read_archive(self.source), self.entries)

    def test_merge_changes_bodies_and_keeps_exact_original_names_boundaries_and_resources(self):
        subject.stage_payload(self.source, self.stage)
        output = self.root / "merged.jar"
        report = subject.merge_payload(self.stage, self.protected(), self.mapping, output)
        merged = subject.read_archive(output)
        self.assertEqual({name: merged[name] for name in self.stable}, self.stable)
        self.assertEqual({name: merged[name] for name in self.thin},
                         {name: value + b" protected body and string calls" for name, value in self.thin.items()})
        self.assertEqual(set(merged), set(self.stable) | set(self.protected_entries()))
        self.assertEqual(merged["helper/v2/Decoder.class"], b"generated runtime helper")
        self.assertEqual(merged["META-INF/helper/v2/Decoder.random"], b"generated encrypted string vault")
        self.assertEqual(report["stable_entries_verified"], len(self.stable))
        self.assertEqual(report["original_implementation_classes"], len(self.thin))
        self.assertEqual(report["changed_original_classes"], len(self.thin))
        self.assertEqual(report["generated_helpers"], 1)
        self.assertEqual(report["generated_resources"], 1)
        self.assertEqual(report["sha256"], subject.digest(output.read_bytes()))
        self.assertEqual(subject.read_archive(self.source), self.entries)

    def test_stage_requires_canonical_native_and_lifecycle_source_members(self):
        for index, category in enumerate(("canonical", "native", "lifecycle")):
            with self.subTest(category=category):
                entries = dict(self.entries)
                if category == "canonical":
                    entries = {name: value for name, value in entries.items() if not name.startswith("dev/celestebeta/host/")}
                elif category == "native":
                    entries = {name: value for name, value in entries.items() if not name.startswith("dev/redstones/mediaplayerinfo/")}
                else:
                    entries.pop(subject.PAYLOAD_ENTRY + ".class")
                source = self.root / f"missing-{index}.jar"
                subject.write_archive(source, entries)
                with self.assertRaises(ValueError):
                    subject.stage_payload(source, self.root / f"missing-stage-{index}")

    def test_stage_does_not_replace_existing_directory(self):
        self.stage.mkdir()
        marker = self.stage / "marker.txt"
        marker.write_bytes(b"untouched")
        with self.assertRaises(ValueError):
            subject.stage_payload(self.source, self.stage)
        self.assertEqual(marker.read_bytes(), b"untouched")
        self.assertEqual(list(self.stage.iterdir()), [marker])

    def test_merge_rejects_source_tamper(self):
        subject.stage_payload(self.source, self.stage)
        self.rewrite_archive(self.source, self.entries | {"extra-original.txt": b"source changed"})
        self.rejects_merge(self.protected())

    def test_merge_rejects_stable_archive_tamper(self):
        subject.stage_payload(self.source, self.stage)
        entries = dict(self.stable)
        entries["assets/celeste/fixture.dat"] = b"tampered stable resource"
        self.rewrite_archive(self.stage / "payload-stable.jar", entries)
        self.rejects_merge(self.protected())

    def test_merge_rejects_thin_stage_tamper(self):
        subject.stage_payload(self.source, self.stage)
        entries = dict(self.thin)
        entries[subject.PAYLOAD_ENTRY + ".class"] = b"tampered thin input"
        self.rewrite_archive(self.stage / "payload-thin.jar", entries)
        self.rejects_merge(self.protected())

    def test_merge_rejects_wrong_stage_schema(self):
        subject.stage_payload(self.source, self.stage)
        path = self.stage / "payload-stage.json"
        record = json.loads(path.read_text(encoding="utf-8"))
        record["schema"] = "celeste-beta-obfuscation-stage-1"
        path.write_text(json.dumps(record), encoding="utf-8")
        self.rejects_merge(self.protected())

    def test_merge_rejects_original_resource_and_class_collisions_even_if_identical(self):
        subject.stage_payload(self.source, self.stage)
        for index, name in enumerate(("assets/celeste/fixture.dat", "dev/celestebeta/host/InteractionHookBridge.class", "fabric.mod.json")):
            with self.subTest(name=name):
                protected = self.protected(self.protected_entries() | {name: self.stable[name]}, f"collision-{index}.jar")
                self.rejects_merge(protected, f"collision-output-{index}.jar")

    def test_merge_rejects_new_classes_inside_stable_boundaries(self):
        subject.stage_payload(self.source, self.stage)
        for index, name in enumerate(("dev/celestebeta/host/Injected.class", "dev/redstones/mediaplayerinfo/Injected.class",
                                     "dev/c4lam/celeste/mixin/module/Injected.class",
                                     "dev/c4lam/celeste/auth/HardwareFingerprint$InjectedProperty.class",
                                     "dev/c4lam/celeste/render/font/FontData$Injected.class")):
            with self.subTest(name=name):
                protected = self.protected(self.protected_entries() | {name: b"new boundary helper"}, f"boundary-{index}.jar")
                self.rejects_merge(protected, f"boundary-output-{index}.jar")

    def test_merge_requires_every_original_thin_class_not_only_lifecycle_entry(self):
        subject.stage_payload(self.source, self.stage)
        for index, name in enumerate(self.thin):
            with self.subTest(name=name):
                entries = self.protected_entries()
                entries.pop(name)
                self.rejects_merge(self.protected(entries, f"missing-thin-{index}.jar"), f"missing-output-{index}.jar")

    def test_merge_rejects_new_resources_inside_retained_boundary_namespaces(self):
        subject.stage_payload(self.source, self.stage)
        for index, name in enumerate(("assets/celeste/injected.dat", "mediaplayerinfo/injected.data", "META-INF/celeste-beta/injected.json")):
            with self.subTest(name=name):
                entries = self.protected_entries() | {name: b"new boundary resource"}
                self.rejects_merge(self.protected(entries, f"boundary-resource-{index}.jar"), f"boundary-resource-output-{index}.jar")

    def test_merge_rejects_renamed_original_thin_class_even_with_empty_mapping(self):
        subject.stage_payload(self.source, self.stage)
        entries = self.protected_entries()
        entries["renamed/ApplicationBody.class"] = entries.pop("dev/c4lam/celeste/module/Body.class")
        self.rejects_merge(self.protected(entries))

    def test_merge_rejects_any_nonempty_mapping_including_stale_identity_records(self):
        subject.stage_payload(self.source, self.stage)
        protected = self.protected()
        owner = subject.PAYLOAD_ENTRY
        cases = (
            {"classes": {owner: "renamed/Session"}},
            {"classes": {owner: owner}},
            {"fields": {owner + ".phaseLjava/lang/String;": "changed"}},
            {"fields": {owner + ".phaseLjava/lang/String;": "phase"}},
            {"methods": {owner + ".ready()Ljava/util/concurrent/CompletionStage;": "changed"}},
            {"methods": {owner + ".ready()Ljava/util/concurrent/CompletionStage;": "ready"}},
        )
        for index, case in enumerate(cases):
            with self.subTest(case=case):
                self.write_mapping(**case)
                self.rejects_merge(protected, f"mapping-output-{index}.jar")

    def test_merge_rejects_wrong_mapping_schema_format_or_map_types(self):
        subject.stage_payload(self.source, self.stage)
        protected = self.protected()
        for index, case in enumerate(({"schemaVersion": 2}, {"format": "unknown"}, {"classes": []}, {"fields": None}, {"methods": ""})):
            with self.subTest(case=case):
                self.write_mapping(**case)
                self.rejects_merge(protected, f"bad-mapping-output-{index}.jar")

    def test_merge_requires_changed_original_bodies_and_generated_helper_and_vault(self):
        subject.stage_payload(self.source, self.stage)
        cases = []
        unchanged = self.protected_entries() | self.thin
        cases.append(unchanged)
        no_helper = self.protected_entries()
        no_helper.pop("helper/v2/Decoder.class")
        cases.append(no_helper)
        no_vault = self.protected_entries()
        no_vault.pop("META-INF/helper/v2/Decoder.random")
        cases.append(no_vault)
        cases.append(dict(self.thin))
        for index, entries in enumerate(cases):
            with self.subTest(index=index):
                self.rejects_merge(self.protected(entries, f"meaningless-{index}.jar"), f"meaningless-output-{index}.jar")

    def test_merge_never_replaces_existing_output(self):
        subject.stage_payload(self.source, self.stage)
        output = self.root / "existing.jar"
        output.write_bytes(b"untouched existing artifact")
        with self.assertRaises(ValueError):
            subject.merge_payload(self.stage, self.protected(), self.mapping, output)
        self.assertEqual(output.read_bytes(), b"untouched existing artifact")


if __name__ == "__main__":
    unittest.main()
