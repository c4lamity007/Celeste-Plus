import hashlib
import copy
import json
import os
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import urllib.error
import urllib.parse

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "packaging"))
import publish_native_github as publisher


class PublicationBoundaryTests(unittest.TestCase):
    def test_existing_exact_asset_is_idempotent(self):
        github = publisher.GitHub("fixture-no-real-token")
        content = b"fixture"
        release = {"id": 1, "draft": True, "assets": [{"name": "Celeste+.exe", "size": len(content),
                   "digest": "sha256:" + hashlib.sha256(content).hexdigest()}]}
        with patch.object(github, "request") as request:
            github.asset(release, "Celeste+.exe", content, "application/octet-stream")
            request.assert_not_called()

    def test_mismatched_asset_or_published_release_never_overwritten(self):
        github = publisher.GitHub("fixture-no-real-token")
        for release in ({"id": 1, "draft": True, "assets": [{"name": "Celeste+.exe", "size": 0, "digest": "wrong"}]},
                        {"id": 1, "draft": False, "assets": []}):
            with patch.object(github, "request") as request, self.assertRaises(ValueError):
                github.asset(release, "Celeste+.exe", b"fixture", "application/octet-stream")
            request.assert_not_called()

    def test_remote_asset_digest_must_match(self):
        github = publisher.GitHub("fixture-no-real-token")
        release = {"id": 1, "draft": True, "assets": []}
        with patch.object(github, "request", return_value={"name": "Celeste+.exe", "size": 7, "digest": "wrong"}), self.assertRaises(ValueError):
            github.asset(release, "Celeste+.exe", b"fixture", "application/octet-stream")

    def test_github_token_never_sent_to_other_repo_or_origin(self):
        github = publisher.GitHub("fixture-no-real-token")
        for url in ["https://example.com/repos/c4lamity007/Celeste-Plus", "http://api.github.com/repos/c4lamity007/Celeste-Plus", "https://api.github.com/repos/c4lamity007/Celeste", "https://api.github.com/repos/c4lamity007/Celeste-Plus-Other"]:
            with patch("urllib.request.build_opener") as opener, self.assertRaises(ValueError):
                github.request("POST", url, {})
            opener.assert_not_called()

    def test_registration_target_and_policy_expiry_checked_before_http(self):
        transport = object.__new__(publisher.ProductionTransport)
        transport.expires = 0
        for url in [publisher.registration.PRODUCTION_ORIGIN + "/v1/client-manifest", "https://example.com/v1/client-manifest"]:
            with patch.object(transport, "read") as read, self.assertRaises(ValueError):
                transport.request(publisher.urllib.request.Request(url, data=b"sensitive"))
            read.assert_not_called()


class PublicationSequenceTests(unittest.TestCase):
    """Exercise the real publisher/registration verifier; only OS gates and HTTP are mocked.

    Synthetic binaries are not protected/signed release candidates. Their OS gate
    verdict is an explicit test seam, and all signing keys below are public fixtures.
    """

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.events = []
        self.posts = []
        self.nonces = []
        self.now = 1_800_000_000
        self.tag = "v2026-09-fixture"
        self.commit = "a" * 40
        self.sequence = 8
        self.ci = Ed25519PrivateKey.from_private_bytes(bytes([61]) * 32)
        self.server = Ed25519PrivateKey.from_private_bytes(bytes([62]) * 32)
        self.binary = self.root / "Celeste+.exe"
        self.binary.write_bytes(b"MZ public unprotected publication-order fixture")
        self.receipt = self.root / "receipt.json"
        self.receipt.write_text('{"fixture":"mock OS gate only"}', encoding="utf-8")
        self.record = {"product": "celeste_plus", "platform": "windows-x64", "release_id": self.tag,
                       "binary": str(self.binary), "receipt": str(self.receipt), "size": self.binary.stat().st_size,
                       "sha256": publisher.gate.sha(self.binary), "signer_thumbprint": "B" * 40}
        (self.root / "release-candidates.json").write_text(json.dumps(self.record), encoding="utf-8")
        self.prior = {"aud": "celeste-native-publication", "product": "celeste_plus", "platform": "windows-x64",
                      "iat": self.now - 5000, "nbf": self.now - 5000, "exp": self.now - 4700, "sequence": 7,
                      "binaries": [{"sha256": "c" * 64, "size": 32, "release_id": "vprevious-fixture",
                                    "protection_receipt_sha256": "d" * 64}]}
        self.live = copy.deepcopy(self.prior)
        self.repository = {"full_name": publisher.REPOSITORY, "private": True}
        self.release = None
        self.fail_posts = False
        self.bad_readback = None
        self.env = {"GITHUB_TOKEN": "public-test-placeholder", "CELESTE_BACKEND_RELEASE_ID": self.tag,
                    "CELESTE_NATIVE_RELEASE_SEQUENCE": str(self.sequence),
                    "CELESTE_RETAINED_NATIVE_POLICY": json.dumps({"publication": self.token(self.prior, self.ci)}),
                    "CELESTE_NATIVE_RELEASE_PRIVATE_KEY": publisher.registration.b64(bytes([61]) * 32)}
        self.start(patch.dict(os.environ, self.env))
        self.start(patch.object(publisher.time, "time", side_effect=lambda: self.now))
        public = self.server.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw)
        self.start(patch.object(publisher.registration, "SERVER_PUBLIC_KEY", publisher.registration.b64(public)))
        self.gate = self.start(patch.object(publisher.gate, "validate", side_effect=self.protection_gate))
        self.signature = self.start(patch.object(publisher.subprocess, "run", return_value=SimpleNamespace(returncode=0)))
        self.github_calls = self.start(patch.object(publisher.GitHub, "request", side_effect=self.github))
        self.start(patch.object(publisher, "ProductionTransport", return_value=SimpleNamespace(request=self.native)))
        # publish() temporarily replaces this module global; restore it after each case.
        self.start(patch.object(publisher.registration, "http_json"))
        self.start(patch.object(publisher.registration.time, "sleep"))
        self.start(patch("urllib.request.build_opener", side_effect=AssertionError("Unexpected real network access")))

    def start(self, item):
        value = item.start()
        self.addCleanup(item.stop)
        return value

    @staticmethod
    def token(claims, key, *, server=False):
        header = {"alg": "EdDSA", "typ": "CST"}
        if server:
            header["kid"] = "celeste-release-1"
        body = publisher.registration.b64(publisher.registration.canonical(header)) + "." + publisher.registration.b64(publisher.registration.canonical(claims))
        return body + "." + publisher.registration.b64(key.sign(body.encode("ascii")))

    def protection_gate(self, binary, receipt, **identity):
        self.events.append("protection-gate")
        self.assertEqual(identity, {"release_id": self.tag, "product": "celeste_plus", "platform": "windows-x64"})
        return {"binary_sha256": publisher.gate.sha(binary), "receipt_sha256": publisher.gate.sha(receipt)}

    def github(self, method, url, data=None, content_type="application/json"):
        if method == "GET" and url == publisher.API:
            self.events.append("repository-check")
            return self.repository
        if method == "GET" and url == publisher.API + "/releases/tags/" + self.tag:
            if self.release is None:
                error = urllib.error.HTTPError(url, 404, "fixture absent", {}, None)
                self.addCleanup(error.close)
                raise error
            return copy.deepcopy(self.release)
        if method == "POST" and url == publisher.API + "/releases":
            self.assertTrue(data["draft"])
            self.events.append("draft-created")
            self.release = {"id": 123, "tag_name": self.tag, "draft": True, "assets": []}
            return copy.deepcopy(self.release)
        if method == "POST" and url.startswith("https://uploads.github.com/"):
            self.assertTrue(self.release["draft"], "Uploads must remain private drafts")
            name = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)["name"][0]
            self.events.append("asset:" + name)
            asset = {"name": name, "size": len(data), "digest": "sha256:" + hashlib.sha256(data).hexdigest()}
            self.release["assets"].append(asset)
            return asset
        if method == "PATCH" and url == publisher.API + "/releases/123":
            self.assertEqual(data, {"draft": False})
            self.events.append("release-published")
            self.release["draft"] = False
            return copy.deepcopy(self.release)
        self.fail("Unexpected GitHub operation: " + method + " " + url)

    def native(self, request):
        self.assertTrue(request.full_url.startswith(publisher.registration.PRODUCTION_ORIGIN + "/v1/client-manifest"))
        if request.get_method() == "POST":
            self.events.append("registration-post")
            self.posts.append(request.data)
            # Preserve the exact authorization before a possibly ambiguous network result.
            self.assertEqual(json.loads((self.root / "publication-envelope.json").read_bytes()), json.loads(request.data))
            if self.fail_posts:
                raise TimeoutError("public fixture simulates an unconfirmed POST")
            envelope = json.loads(request.data)
            claims = publisher.registration.verify_token(envelope["publication"], self.ci.public_key(), {"alg": "EdDSA", "typ": "CST"})
            self.assertEqual(claims["sequence"], self.sequence)
            self.live = claims
            return {"registered": True, "sequence": self.sequence}
        self.assertEqual(request.get_method(), "GET")
        nonce = request.get_header("X-celeste-request-nonce")
        self.assertEqual(len(publisher.registration.decode(nonce)), 24)
        self.assertNotIn(nonce, self.nonces)
        self.nonces.append(nonce)
        current = self.live["sequence"] == self.sequence
        self.events.append("readback-current" if current else "readback-prior")
        claims = {"aud": "celeste-native-manifest", "product": "celeste_plus", "platform": "windows-x64",
                  "iat": self.now, "nbf": self.now, "exp": self.now + 120, "request_nonce": nonce,
                  "sequence": self.live["sequence"], "sha256": [value["sha256"] for value in self.live["binaries"]]}
        key = self.server
        if current and self.bad_readback == "nonce":
            claims["request_nonce"] = publisher.registration.b64(bytes(24))
        if current and self.bad_readback == "key":
            key = self.ci
        return {"manifest": self.token(claims, key, server=True)}

    def run_publish(self):
        return publisher.publish(self.root, self.tag, self.commit)

    def expected_current(self):
        current = {"sha256": self.record["sha256"], "size": self.record["size"], "release_id": self.tag,
                   "protection_receipt_sha256": publisher.gate.sha(self.receipt)}
        return {**self.prior, "sequence": self.sequence, "iat": self.now, "nbf": self.now, "exp": self.now + 300,
                "binaries": [current, *self.prior["binaries"]]}

    def assert_no_publication(self):
        self.assertNotIn("registration-post", self.events)
        self.assertNotIn("release-published", self.events)

    def test_invalid_protection_receipt_precedes_every_external_side_effect(self):
        self.gate.side_effect = ValueError("fixture receipt rejected")
        with self.assertRaises(ValueError):
            self.run_publish()
        self.github_calls.assert_not_called()
        self.signature.assert_not_called()
        self.assert_no_publication()

    def test_invalid_windows_signature_precedes_every_external_side_effect(self):
        self.signature.return_value.returncode = 1
        with self.assertRaisesRegex(ValueError, "signature"):
            self.run_publish()
        self.github_calls.assert_not_called()
        self.assert_no_publication()

    def test_unmatched_backend_release_prevents_draft_creation_and_registration(self):
        with patch.dict(os.environ, {"CELESTE_BACKEND_RELEASE_ID": "vother"}), self.assertRaisesRegex(ValueError, "matched backend"):
            self.run_publish()
        self.github_calls.assert_not_called()
        self.assert_no_publication()

    def test_wrong_repository_and_public_repository_never_create_draft_or_post(self):
        for repository in ({"full_name": "someone/other", "private": True},
                           {"full_name": publisher.REPOSITORY, "private": False}):
            with self.subTest(repository=repository):
                self.repository = repository
                self.events.clear()
                with self.assertRaises(ValueError):
                    self.run_publish()
                self.assert_no_publication()
                self.assertNotIn("draft-created", self.events)
                self.assertFalse(any(value.startswith("asset:") for value in self.events))

    def test_assets_are_staged_before_registration_and_publication_requires_signed_readback(self):
        result = self.run_publish()
        self.assertEqual(result["status"], "published")
        self.assertLess(self.events.index("draft-created"), self.events.index("asset:Celeste+.exe"))
        self.assertLess(self.events.index("asset:SHA256SUMS.txt"), self.events.index("registration-post"))
        self.assertLess(self.events.index("registration-post"), self.events.index("readback-current"))
        self.assertLess(self.events.index("readback-current"), self.events.index("release-published"))
        self.assertEqual(len(self.posts), 1)
        self.assertEqual(len(self.nonces), 3)
        self.assertEqual(json.loads((self.root / "publication-result.json").read_bytes())["record_kind"], "exact-publication-authorization")

    def test_http_registration_ack_without_valid_signed_readback_leaves_draft(self):
        for bad in ("nonce", "key"):
            with self.subTest(readback=bad):
                self.bad_readback = bad
                self.live = copy.deepcopy(self.prior)
                self.now += 1
                with self.assertRaisesRegex(ValueError, "signed policy verification failed"):
                    self.run_publish()
                self.assertTrue(self.release["draft"])
                self.assertNotIn("release-published", self.events)
                self.assertFalse((self.root / "retention-policy.json").exists())
                self.assertFalse((self.root / "publication-result.json").exists())

    def test_same_directory_retry_reuses_exact_authorization_after_ambiguous_post(self):
        self.fail_posts = True
        with self.assertRaisesRegex(ValueError, "could not be confirmed"):
            self.run_publish()
        retained = (self.root / "publication-envelope.json").read_bytes()
        self.assertEqual(len(self.posts), 3)
        self.assertEqual(len(set(self.posts)), 1)
        self.assertNotIn("release-published", self.events)
        self.fail_posts = False
        self.now += 17  # A regenerated signed token would have different time fields.
        self.assertEqual(self.run_publish()["status"], "published")
        self.assertEqual((self.root / "publication-envelope.json").read_bytes(), retained)
        self.assertEqual(len(set(self.posts)), 1)
        self.assertEqual(self.events.count("draft-created"), 1)
        self.assertEqual(self.events.count("asset:Celeste+.exe"), 1)

    def test_retry_after_success_does_not_post_or_upload_again(self):
        self.run_publish()
        before = (self.root / "publication-envelope.json").read_bytes()
        self.now += 31
        self.events.clear()
        self.assertEqual(self.run_publish()["status"], "published")
        self.assertEqual((self.root / "publication-envelope.json").read_bytes(), before)
        self.assert_no_publication()
        self.assertFalse(any(value.startswith("asset:") for value in self.events))
        self.assertIn("readback-current", self.events)

    def test_lost_local_authorization_is_only_recorded_as_verified_live_reconstruction(self):
        self.live = self.expected_current()
        self.assertEqual(self.run_publish()["status"], "published")
        self.assertEqual(self.posts, [])
        self.assertFalse((self.root / "publication-envelope.json").exists())
        evidence = json.loads((self.root / "publication-result.json").read_bytes())
        self.assertEqual(evidence["record_kind"], "verified-live-policy-reconstruction")
        self.assertFalse(evidence["authorization_submitted_this_run"])
        retained = (self.root / "retention-policy.json").read_bytes()
        self.now += 21
        self.assertEqual(self.run_publish()["status"], "published")
        self.assertEqual((self.root / "retention-policy.json").read_bytes(), retained)
        self.assertEqual(self.posts, [])

    def test_existing_private_evidence_is_not_overwritten_by_changed_inputs(self):
        self.run_publish()
        retained = (self.root / "retained-publication.json").read_bytes()
        with patch.dict(os.environ, {"CELESTE_RETAINED_NATIVE_POLICY": "{}"}), self.assertRaisesRegex(ValueError, "refusing to overwrite"):
            self.run_publish()
        self.assertEqual((self.root / "retained-publication.json").read_bytes(), retained)


if __name__ == "__main__":
    unittest.main()
