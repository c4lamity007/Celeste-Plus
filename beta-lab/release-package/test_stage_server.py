"""Offline staging regression: owned temporary bytes only; no server or credentials."""
from __future__ import annotations

import importlib.util
import json
import tempfile
import tomllib
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("stage_server", Path(__file__).with_name("stage_server.py"))
stage_server = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(stage_server)


class StagingCheck(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="celeste-stage-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.server = self.root / "auth-service"
        self.production = self.server / "releases"
        self.beta = self.root / "assembled"
        self.production.mkdir(parents=True)
        self.beta.mkdir()
        self.output = self.root / "build/module-qa/server-context"
        for name in stage_server.APP_FILES:
            path = self.server / "app" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("# fixture source\n", encoding="utf-8")
        for name in ("Dockerfile", "fly.toml", "requirements.txt", "entrypoint.sh"):
            (self.server / name).write_bytes((stage_server.ROOT / "auth-service" / name).read_bytes())
        artifacts = {}
        for name in stage_server.PRODUCTION_FILES:
            if name != "release-manifest.json":
                data = ("owned production fixture " + name).encode()
                (self.production / name).write_bytes(data)
                artifacts[name] = {"id": name.replace(".", "-"), "file": name,
                                   "sha256": stage_server.sha(data), "size": len(data)}
        self.manifest = {"protocol": "cst-auth-1", "release": "production-test", "protectedDigest": "a" * 64,
                         "jarSha256": artifacts["celeste-client-1.0.0-configured.jar"]["sha256"],
                         "jarSize": artifacts["celeste-client-1.0.0-configured.jar"]["size"],
                         "bootstrap": artifacts["mixinextras-0.5.4.jar"], "loader": artifacts["stage-two.bin"],
                         "loaderThree": artifacts["stage-three.bin"], "loaderFour": artifacts["stage-four.bin"],
                         "chunks": [artifacts["chunk-a.bin"], artifacts["chunk-b.bin"]]}
        encoded = json.dumps(self.manifest).encode()
        (self.production / "release-manifest.json").write_bytes(encoded)
        self.manifest_pin = stage_server.sha(encoded)
        self.legacy_pin = artifacts["CelesteLoader.jar"]["sha256"]
        self.catalog = {"schema": "celeste-beta-release-2", "release_id": "owner-beta-test",
                        "client_version": "1.0-beta", "runtime_sha256": "b" * 64, "artifacts": []}
        for identity, name in stage_server.BETA_FILES.items():
            data = ("owned beta fixture " + name).encode()
            (self.beta / name).write_bytes(data)
            self.catalog["artifacts"].append({"id": identity, "filename": name,
                                              "role": "bootstrap" if identity.startswith("bootstrap") else "payload",
                                              "size": len(data), "sha256": stage_server.sha(data)})
        self.write_catalog()

    def write_catalog(self):
        (self.beta / "beta-release.json").write_text(json.dumps(self.catalog), encoding="utf-8")

    def stage(self):
        return stage_server.stage(self.root, self.beta, self.output, self.manifest_pin, self.legacy_pin)

    def test_exact_context_and_only_beta_configuration_changes(self):
        for root in (self.server, self.server / "app", self.production, self.beta):
            (root / ".env").write_bytes(b"must not be read or copied\xff")
            (root / "credentials.json").write_bytes(b"not a credential; excluded fixture")
        receipt = self.stage()
        self.assertEqual(receipt, stage_server.verify(self.output))
        self.assertEqual(len(receipt["files"]), 40)
        self.assertFalse((self.output / "releases/release.env").exists())
        self.assertFalse(list(self.output.rglob(".env")))
        original = tomllib.loads((self.server / "fly.toml").read_text())
        copied = tomllib.loads((self.output / "fly.toml").read_text())
        self.assertEqual(copied["env"].pop("CELESTE_PLUS_ENABLED"), "true")
        self.assertNotIn("owner_uid", receipt)
        self.assertEqual(receipt["product"], "celeste_plus")
        self.assertEqual(copied["env"].pop("CELESTE_BETA_RELEASE_DIR"), "/srv/auth/owner-beta/owner-beta-test")
        self.assertEqual(original, copied)
        ignore = (self.output / ".dockerignore").read_text().splitlines()
        self.assertEqual(ignore[0], "**")
        self.assertTrue(all(line.startswith("!") and not line.endswith("/") and "*" not in line
                            for line in ignore[1:]))
        docker = (self.output / "Dockerfile").read_text()
        addition = "COPY --chown=auth:auth owner-beta ./owner-beta\nRUN chmod -R a=rX ./owner-beta\n"
        self.assertEqual(docker.replace(addition, ""), (self.server / "Dockerfile").read_text())
        for name in stage_server.PRODUCTION_FILES:
            self.assertEqual((self.output / "releases" / name).read_bytes(), (self.production / name).read_bytes())

    def test_existing_output_never_overwritten(self):
        self.stage()
        before = (self.output / "staging-report.json").read_bytes()
        with self.assertRaisesRegex(ValueError, "already exists"):
            self.stage()
        self.assertEqual(before, (self.output / "staging-report.json").read_bytes())

    def test_output_bounds(self):
        for output in (self.root / "outside", self.root / "build/module-qa", self.root / "build/module-qa/../outside"):
            with self.subTest(output=output), self.assertRaises(ValueError):
                stage_server.stage(self.root, self.beta, output, self.manifest_pin, self.legacy_pin)

    def test_production_hashes_fail_before_output(self):
        for name in ("celeste-client-1.0.0-configured.jar", "CelesteLoader.jar", "stage-two.bin", "release-manifest.json"):
            path = self.production / name
            original = path.read_bytes()
            path.write_bytes(original + b"changed")
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "mismatch"):
                self.stage()
            self.assertFalse(self.output.exists())
            path.write_bytes(original)

    def test_catalog_rejects_mutations(self):
        original = json.dumps(self.catalog)
        mutations = (
            lambda c: c.update(schema="celeste-beta-release-1"),
            lambda c: c.update(release_id="production-test"),
            lambda c: c.update(runtime_sha256="a" * 64),
            lambda c: c.update(release_id="../other"),
            lambda c: c["artifacts"][0].update(filename="../production.bin"),
            lambda c: c["artifacts"][0].update(role="payload"),
            lambda c: c["artifacts"][0].update(size=True),
            lambda c: c["artifacts"][0].update(size=64 * 1024 * 1024 + 1),
            lambda c: c["artifacts"][0].update(sha256="0" * 64),
            lambda c: c["artifacts"][1].update(id="bootstrap-a"),
        )
        for index, mutation in enumerate(mutations):
            self.catalog = json.loads(original)
            mutation(self.catalog)
            self.write_catalog()
            with self.subTest(index=index), self.assertRaises(ValueError):
                self.stage()
            self.assertFalse(self.output.exists())

    def test_duplicate_catalog_keys(self):
        encoded = json.dumps(self.catalog)
        (self.beta / "beta-release.json").write_text('{"schema":"wrong",' + encoded[1:])
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.stage()

    def test_missing_app_and_secret_patterns_fail_before_output(self):
        source = self.server / "app/main.py"
        original = source.read_bytes()
        source.unlink()
        with self.assertRaises(OSError):
            self.stage()
        source.write_bytes(original + ("ghp_" + "A" * 36).encode())
        with self.assertRaisesRegex(ValueError, "value redacted"):
            self.stage()
        self.assertFalse(self.output.exists())

    def test_verifier_rejects_extra_and_changed_files(self):
        self.stage()
        extra = self.output / ".env"
        extra.write_text("excluded fixture")
        with self.assertRaisesRegex(ValueError, "gained/lost"):
            stage_server.verify(self.output)
        extra.unlink()
        (self.output / "app/main.py").write_text("changed")
        with self.assertRaisesRegex(ValueError, "mismatch"):
            stage_server.verify(self.output)

    def test_hardlinked_inputs_rejected(self):
        original = self.beta / "payload-a.bin"
        link = self.beta / "fixture-link"
        try:
            link.hardlink_to(original)
        except OSError:
            self.skipTest("Filesystem does not support hard links")
        with self.assertRaisesRegex(ValueError, "single regular"):
            self.stage()

    def test_receipt_cannot_expand_allowlist_or_change_identity(self):
        self.stage()
        path = self.output / "staging-report.json"
        original = path.read_text()
        receipt = json.loads(original)
        receipt["files"][".env"] = {"size": 0, "sha256": stage_server.sha(b"")}
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "exact allowlist"):
            stage_server.verify(self.output)
        receipt = json.loads(original)
        receipt["beta_release"] = "../outside"
        path.write_text(json.dumps(receipt))
        with self.assertRaisesRegex(ValueError, "Invalid staging receipt"):
            stage_server.verify(self.output)


if __name__ == "__main__":
    unittest.main()
