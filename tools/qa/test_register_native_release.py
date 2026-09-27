"""Synthetic publication tests; none access the production service or secrets."""
from __future__ import annotations

import contextlib
import copy
from email.message import Message
import io
import json
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch
import urllib.error
import urllib.request

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

ROOT = Path(__file__).resolve().parents[2]
sys.path[:0] = [str(ROOT / "tools/packaging"), str(ROOT / "tools/qa"), str(ROOT / "auth-service")]
import register_native_release as release
import native_protection_gate as gate
from test_native_protection_gate import ProtectionFixture
from app.native_manifest import publication_claims
from app.security import GrantSigner


class NativeRegistrationTest(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.fixture = ProtectionFixture(self.root)
        self.addCleanup(patch.stopall)
        patch.object(gate, "POLICY_FILE", self.fixture.policy).start()
        patch.object(gate, "source_digest", return_value=self.fixture.source_sha256).start()
        self.key = Ed25519PrivateKey.from_private_bytes(bytes([0x66] * 32))
        self.now = 1_800_000_000

    def prepare(self, **overrides):
        values = dict(binary=self.fixture.binary, receipt=self.fixture.receipt,
                      release_id=self.fixture.record["release_id"], product="celeste_plus", platform="linux-x64",
                      expected_sha256=gate.sha(self.fixture.binary), sequence=7, key=self.key, now=self.now)
        return release.prepare(**(values | overrides))

    def sign(self, claims, key=None):
        key = key or self.key
        first = release.b64(release.canonical({"alg": "EdDSA", "typ": "CST"}))
        second = release.b64(release.canonical(claims))
        signed = first + "." + second
        return {"publication": signed + "." + release.b64(key.sign(signed.encode("ascii")))}

    def test_real_server_accepts_exact_prepared_contract(self):
        envelope, claims = self.prepare()
        public = self.key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        self.assertEqual(claims, publication_claims(envelope["publication"], public, self.now))
        self.assertEqual(claims["iat"], claims["nbf"])
        self.assertEqual(300, claims["exp"] - claims["iat"])
        self.assertEqual(gate.sha(self.fixture.binary), claims["binaries"][0]["sha256"])
        self.assertEqual(gate.sha(self.fixture.receipt), claims["binaries"][0]["protection_receipt_sha256"])

    def test_current_empty_operator_policy_blocks_synthetic_protection(self):
        with patch.object(gate, "POLICY_FILE", ROOT / "tools/packaging/native-protection-policy.json"):
            with self.assertRaisesRegex(ValueError, "No reviewed protector"):
                self.prepare()

    def test_wrong_final_hash_changed_signature_bytes_and_stale_receipt_fail(self):
        with self.assertRaisesRegex(ValueError, "final distributed bytes"):
            self.prepare(expected_sha256="f" * 64)
        self.fixture.binary.write_bytes(self.fixture.binary.read_bytes() + b"new Authenticode signature")
        with self.assertRaisesRegex(ValueError, "differs from"):
            self.prepare()

    def test_sequence_product_platform_and_release_are_bounded(self):
        for value in [0, -1, True, 2**63]:
            with self.subTest(value=value), self.assertRaises(ValueError):
                self.prepare(sequence=value)
        for field, value in [("product", "other"), ("product", "celeste"), ("platform", "unknown"), ("release_id", "../../release")]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.prepare(**{field: value})

    def test_retention_requires_an_older_matching_signed_policy(self):
        _, older = self.prepare(sequence=6, now=self.now - 3600)
        older["binaries"][0]["sha256"] = "a" * 64
        path = self.root / "prior.json"
        path.write_bytes(release.canonical(self.sign(older)))
        _, current = self.prepare(retain=path)
        self.assertEqual(2, len(current["binaries"]))
        self.assertEqual("a" * 64, current["binaries"][1]["sha256"])
        for field, value in [("sequence", 7), ("product", "celeste"), ("platform", "windows-x64"),
                             ("binaries", older["binaries"] * 4)]:
            changed = copy.deepcopy(older)
            changed[field] = value
            path.write_bytes(release.canonical(self.sign(changed)))
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.prepare(retain=path)
        path.write_bytes(release.canonical(self.sign(older, Ed25519PrivateKey.generate())))
        with self.assertRaises(ValueError):
            self.prepare(retain=path)
        path.write_bytes(release.canonical({"binaries": older["binaries"]}))
        with self.assertRaises(ValueError):
            self.prepare(retain=path)

    def test_retained_duplicates_and_unsigned_fields_fail(self):
        _, older = self.prepare(sequence=6)
        path = self.root / "prior.json"
        path.write_bytes(release.canonical(self.sign(older)))
        with self.assertRaisesRegex(ValueError, "also appear"):
            self.prepare(retain=path)
        signed = release.canonical(self.sign(older)).decode()
        path.write_text(signed.replace('{', '{"publication":"bad",', 1))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.prepare(retain=path)

    def test_missing_or_malformed_key_never_has_unsigned_fallback(self):
        for value in ["", "x", "=" * 43, release.b64(bytes(31))]:
            with patch.dict(os.environ, {"CELESTE_NATIVE_RELEASE_PRIVATE_KEY": value}), self.assertRaises(ValueError) as raised:
                release.publication_key()
            self.assertNotIn("=" * 43, str(raised.exception))

    def manifest(self, claims, nonce, **overrides):
        value = {"aud": "celeste-native-manifest", "product": claims["product"], "platform": claims["platform"],
                 "iat": self.now, "nbf": self.now, "exp": self.now + 120, "sequence": claims["sequence"],
                 "request_nonce": nonce, "sha256": [item["sha256"] for item in claims["binaries"]]}
        value.update(overrides)
        return {"manifest": GrantSigner(bytes(range(32))).sign(value)}

    def test_actual_server_signature_requires_fresh_nonce_exact_policy(self):
        _, claims = self.prepare()
        nonce = release.b64(bytes(range(24)))
        server_key = Ed25519PrivateKey.from_private_bytes(bytes(range(32))).public_key()
        release.verify_live_manifest(self.manifest(claims, nonce), claims, nonce, now=self.now, public_key=server_key)
        for field, value in [("request_nonce", release.b64(bytes(24))), ("sequence", 6), ("sequence", True),
                             ("product", "celeste"), ("platform", "windows-x64"), ("exp", self.now),
                             ("exp", self.now + 121), ("sha256", ["b" * 64]),
                             ("sha256", [claims["binaries"][0]["sha256"]] * 2)]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                release.verify_live_manifest(self.manifest(claims, nonce, **{field: value}), claims, nonce,
                                             now=self.now, public_key=server_key)
        with self.assertRaises(ValueError):
            release.verify_live_manifest(self.manifest(claims, nonce), claims, nonce, now=self.now)

    def test_prepare_cli_is_offline_and_does_not_print_signed_token(self):
        output = self.root / "prepared.json"
        args = ["--binary", str(self.fixture.binary), "--receipt", str(self.fixture.receipt),
                "--release-id", self.fixture.record["release_id"], "--product", "celeste_plus", "--platform", "linux-x64",
                "--sequence", "7", "--expected-sha256", gate.sha(self.fixture.binary), "--output", str(output)]
        stream = io.StringIO()
        with patch.dict(os.environ, {"CELESTE_NATIVE_RELEASE_PRIVATE_KEY": release.b64(bytes([0x66] * 32))}), \
             patch.object(release, "http_json") as network, contextlib.redirect_stdout(stream):
            self.assertEqual(0, release.main(args))
            network.assert_not_called()
        self.assertEqual("prepared-not-published", json.loads(stream.getvalue())["status"])
        self.assertNotIn(json.loads(output.read_bytes())["publication"], stream.getvalue())

    def test_cli_rejects_standard_product_before_reading_key_or_network(self):
        args = ["--binary", str(self.fixture.binary), "--receipt", str(self.fixture.receipt),
                "--release-id", self.fixture.record["release_id"], "--product", "celeste", "--platform", "linux-x64",
                "--sequence", "7", "--expected-sha256", gate.sha(self.fixture.binary),
                "--output", str(self.root / "rejected.json")]
        with patch.object(release, "publication_key") as key, patch.object(release, "http_json") as network, \
             contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as rejected:
            release.main(args)
        self.assertEqual(2, rejected.exception.code)
        key.assert_not_called()
        network.assert_not_called()
        self.assertFalse((self.root / "rejected.json").exists())

    def test_post_retries_exact_token_and_verifies_real_fresh_manifest(self):
        envelope, claims = self.prepare()
        calls = []
        def request(req):
            calls.append(req)
            if len(calls) == 1:
                raise self.unregistered(req.full_url)
            if len(calls) == 2:
                raise urllib.error.URLError("test connection failure")
            if req.get_method() == "POST":
                return {"registered": True, "sequence": 7}
            return self.manifest(claims, req.get_header("X-celeste-request-nonce"))
        server_public = GrantSigner(bytes(range(32))).public_key_b64()
        with patch.object(release, "http_json", side_effect=request), patch.object(release.time, "time", return_value=self.now), \
             patch.object(release.time, "sleep"), patch.object(release, "SERVER_PUBLIC_KEY", server_public):
            result = release.publish(envelope, claims, lambda: claims["binaries"][0], bootstrap_empty=True)
        self.assertEqual(calls[1].data, calls[2].data)
        self.assertEqual("registered-and-verified", result["status"])
        self.assertEqual(32, len(calls[3].get_header("X-celeste-request-nonce")))

    def test_http_success_without_signed_confirmation_is_not_success(self):
        envelope, claims = self.prepare()
        url = release.PRODUCTION_ORIGIN + '/v1/client-manifest?product=celeste_plus&platform=linux-x64'
        with patch.object(release, "http_json", side_effect=[self.unregistered(url), {"registered": True, "sequence": 7}, {"status": "ok"}]), \
             patch.object(release.time, "time", return_value=self.now), self.assertRaisesRegex(ValueError, "do not announce"):
            release.publish(envelope, claims, lambda: claims["binaries"][0], bootstrap_empty=True)

    def test_recheck_and_expiry_prevent_network_side_effects(self):
        envelope, claims = self.prepare()
        with patch.object(release, "http_json") as network, patch.object(release.time, "time", return_value=claims["exp"]):
            with self.assertRaisesRegex(ValueError, "expired"):
                release.publish(envelope, claims, lambda: claims["binaries"][0])
            network.assert_not_called()

    def test_receipt_change_after_preparation_prevents_any_post(self):
        envelope, claims = self.prepare()
        changed = claims["binaries"][0] | {"protection_receipt_sha256": "e" * 64}
        with patch.object(release, "http_json") as network, self.assertRaisesRegex(ValueError, "changed after"):
            release.publish(envelope, claims, lambda: changed)
        network.assert_not_called()

    def unregistered(self, url, detail="native release is not registered", code=503):
        headers = Message()
        headers["Content-Type"] = "application/json"
        return urllib.error.HTTPError(url, code, "test-only error", headers, io.BytesIO(release.canonical({"detail": detail})))

    def test_missing_retention_or_existing_policy_cannot_bootstrap(self):
        envelope, claims = self.prepare()
        with patch.object(release, "http_json") as network, patch.object(release.time, "time", return_value=self.now):
            with self.assertRaisesRegex(ValueError, "requires either"):
                release.publish(envelope, claims, lambda: claims["binaries"][0])
            network.assert_not_called()
        with patch.object(release, "http_json", return_value={"manifest": "existing"}), patch.object(release.time, "time", return_value=self.now):
            with self.assertRaisesRegex(ValueError, "existing policy"):
                release.publish(envelope, claims, lambda: claims["binaries"][0], bootstrap_empty=True)

    def test_bootstrap_does_not_treat_an_outage_or_missing_endpoint_as_empty(self):
        _, claims = self.prepare()
        request, _ = release.manifest_request(claims)
        for error in [self.unregistered(request.full_url, "backend unavailable"), self.unregistered(request.full_url, code=404),
                      self.unregistered("https://elsewhere.invalid")]:
            with patch.object(release, "http_json", side_effect=error), self.assertRaises(ValueError):
                release.require_starting_policy(claims, None, True)

    def test_live_retention_must_match_prior_and_preserve_every_existing_hash(self):
        _, current = self.prepare()
        prior = copy.deepcopy(current)
        prior["sequence"] = 6
        prior["binaries"][0]["sha256"] = "d" * 64
        current["binaries"] += prior["binaries"]
        server_key = GrantSigner(bytes(range(32))).public_key_b64()
        def response(request):
            return self.manifest(prior, request.get_header("X-celeste-request-nonce"))
        with patch.object(release, "http_json", side_effect=response), patch.object(release, "SERVER_PUBLIC_KEY", server_key), patch.object(release.time, "time", return_value=self.now):
            release.require_starting_policy(current, prior, False)
            current["binaries"].pop()
            with self.assertRaisesRegex(ValueError, "retain every"):
                release.require_starting_policy(current, prior, False)
            prior["sequence"] = 5
            with patch.object(release, "http_json", side_effect=lambda request: self.manifest(prior, request.get_header("X-celeste-request-nonce"), sequence=6)):
                with self.assertRaisesRegex(ValueError, "does not match"):
                    release.require_starting_policy(current, prior, False)

    def test_only_reviewed_origin_and_endpoint_and_no_redirects(self):
        for url in ["http://qb0f90cf11f4d1d52.fly.dev/v1/client-manifest", "https://api.celeste.example/v1/client-manifest",
                    release.PRODUCTION_ORIGIN + "/v1/client-manifest/../elsewhere", release.PRODUCTION_ORIGIN + "/v1/client-manifest#fragment"]:
            with patch.object(urllib.request, "build_opener") as opener, self.assertRaises(ValueError):
                release.http_json(urllib.request.Request(url))
            opener.assert_not_called()
        with self.assertRaises(ValueError):
            release.NoRedirect().redirect_request(None, None, 302, None, None, "https://elsewhere.invalid")

    def test_production_manifest_key_matches_compiled_plus_client(self):
        for path in ["beta-lab/native-launcher/src/release.rs"]:
            self.assertIn('SERVER_KEY: &str = "' + release.SERVER_PUBLIC_KEY + '"', (ROOT / path).read_text())


if __name__ == "__main__":
    unittest.main()
