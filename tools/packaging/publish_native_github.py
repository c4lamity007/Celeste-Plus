"""Publish verified signed bytes to the private Plus repository after server registration."""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import subprocess
import time
import urllib.error
import urllib.parse
import urllib.request

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
import native_protection_gate as gate
import register_native_release as registration

REPOSITORY = "c4lamity007/Celeste-Plus"
API = "https://api.github.com/repos/" + REPOSITORY
MAX_ASSET = 256 * 1024 * 1024


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Unexpected publication redirect")


class ProductionTransport:
    def __init__(self):
        nonce = registration.b64(secrets.token_bytes(24))
        request = urllib.request.Request(registration.PRODUCTION_ORIGIN + "/v1/transport-policy?product=celeste_plus",
            headers={"X-Celeste-Request-Nonce": nonce, "Accept": "application/json", "Accept-Encoding": "identity"})
        response = self.read(request, urllib.request.build_opener(NoRedirect()))
        if set(response) != {"policy"}:
            raise ValueError("Missing signed production transport policy")
        claims = registration.verify_token(response["policy"], Ed25519PublicKey.from_public_bytes(
            registration.decode(registration.SERVER_PUBLIC_KEY)), {"alg": "EdDSA", "kid": "celeste-release-1", "typ": "CST"})
        now = int(time.time())
        if (set(claims) != {"aud", "product", "origin", "request_nonce", "iat", "nbf", "exp", "leaf_sha256"}
                or claims["aud"] != "celeste-transport-policy" or claims["product"] != "celeste_plus"
                or claims["origin"] != registration.PRODUCTION_ORIGIN or claims["request_nonce"] != nonce
                or any(type(claims[k]) is not int for k in ("iat", "nbf", "exp"))
                or not 0 <= claims["iat"] <= now + 5 or claims["nbf"] != claims["iat"]
                or not now < claims["exp"] <= claims["iat"] + 120 or claims["exp"] <= claims["iat"]
                or not isinstance(claims["leaf_sha256"], list) or not 1 <= len(claims["leaf_sha256"]) <= 4
                or any(not isinstance(pin, str) or not re.fullmatch(r"[0-9a-f]{64}", pin) for pin in claims["leaf_sha256"])):
            raise ValueError("Invalid or expired signed transport policy")
        self.expires = claims["exp"]
        self.pins = frozenset(claims["leaf_sha256"])
        owner = self

        class Connection(http.client.HTTPSConnection):
            def connect(self):
                super().connect()  # Default certificate chain + hostname validation first.
                if time.time() >= owner.expires or hashlib.sha256(self.sock.getpeercert(binary_form=True)).hexdigest() not in owner.pins:
                    self.close()
                    raise ValueError("Production TLS leaf is not in its current signed policy")

        class Handler(urllib.request.HTTPSHandler):
            def https_open(self, request):
                return self.do_open(Connection, request, context=ssl.create_default_context())

        self.opener = urllib.request.build_opener(NoRedirect(), Handler())

    @staticmethod
    def read(request, opener):
        with opener.open(request, timeout=25) as response:
            if response.status != 200 or response.geturl() != request.full_url or response.headers.get_content_type() != "application/json":
                raise ValueError("Unexpected production response")
            raw = response.read(registration.MAX_RESPONSE + 1)
        if len(raw) > registration.MAX_RESPONSE:
            raise ValueError("Production response exceeds bounds")
        value = registration.strict_json(raw)
        if not isinstance(value, dict):
            raise ValueError("Expected a production JSON object")
        return value

    def request(self, request):
        parsed = urllib.parse.urlsplit(request.full_url)
        if parsed.scheme + "://" + parsed.netloc != registration.PRODUCTION_ORIGIN or parsed.path != "/v1/client-manifest":
            raise ValueError("Unexpected production publication target")
        if time.time() >= self.expires:
            raise ValueError("Transport policy expired; obtain a fresh reviewed publication session")
        return self.read(request, self.opener)


class GitHub:
    def __init__(self, token: str):
        if not token:
            raise ValueError("Missing repository-scoped GitHub token")
        self.token = token

    def request(self, method: str, url: str, data=None, content_type="application/json"):
        parsed = urllib.parse.urlsplit(url)
        allowed_path = parsed.path == "/repos/" + REPOSITORY or parsed.path.startswith("/repos/" + REPOSITORY + "/")
        if parsed.scheme != "https" or parsed.netloc not in {"api.github.com", "uploads.github.com"} or not allowed_path:
            raise ValueError("Refusing publication outside Celeste-Plus")
        raw = json.dumps(data).encode() if isinstance(data, dict) else data
        request = urllib.request.Request(url, data=raw, method=method, headers={
            "Authorization": "Bearer " + self.token, "Accept": "application/vnd.github+json",
            "Content-Type": content_type, "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "Celeste-Plus-release"})
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=60) as response:
            body = response.read(8 * 1024 * 1024 + 1)
        if len(body) > 8 * 1024 * 1024:
            raise ValueError("GitHub response exceeds bounds")
        return json.loads(body)

    def asset(self, release, name, content, content_type):
        expected = "sha256:" + hashlib.sha256(content).hexdigest()
        matches = [asset for asset in release["assets"] if asset["name"] == name]
        if matches:
            if len(matches) != 1 or matches[0].get("digest") != expected or matches[0].get("size") != len(content):
                raise ValueError("An existing release asset differs; refusing to overwrite it")
            return
        if not release["draft"]:
            raise ValueError("Refusing to add assets to an already published release")
        upload = "https://uploads.github.com/repos/" + REPOSITORY + "/releases/" + str(release["id"]) + "/assets?name=" + urllib.parse.quote(name, safe="")
        result = self.request("POST", upload, content, content_type)
        if result.get("name") != name or result.get("size") != len(content) or result.get("digest") != expected:
            raise ValueError("Uploaded asset digest was not confirmed")


def validate_candidate(directory: Path, tag: str, commit: str):
    if not re.fullmatch(r"v[A-Za-z0-9][A-Za-z0-9_-]{0,77}", tag) or not re.fullmatch(r"[0-9a-f]{40}", commit):
        raise ValueError("Invalid release tag or commit; use hyphens for version components")
    record = json.loads((directory / "release-candidates.json").read_text(encoding="utf-8-sig"))
    if isinstance(record, list):
        if len(record) != 1:
            raise ValueError("Exactly one Plus candidate is required")
        record = record[0]
    if (record["product"] != "celeste_plus" or record["platform"] != "windows-x64" or record["release_id"] != tag
            or not re.fullmatch(r"[A-Fa-f0-9]{40}", record["signer_thumbprint"])):
        raise ValueError("Candidate release identity or Windows signer is invalid")
    binary, receipt = Path(record["binary"]).resolve(strict=True), Path(record["receipt"]).resolve(strict=True)
    if not binary.is_relative_to(directory.resolve()) or not receipt.is_relative_to(directory.resolve()):
        raise ValueError("Candidate escapes the private release directory")
    identity = registration.protected_binary(binary, receipt, tag, "celeste_plus", "windows-x64", record["sha256"])
    if record["size"] != identity["size"]:
        raise ValueError("Candidate size changed")
    # Run this on Windows after the Sign phase. Check trust again immediately before upload.
    script = "$s=Get-AuthenticodeSignature -LiteralPath $env:CELESTE_VERIFY_FILE; if($s.Status -ne 'Valid' -or $s.SignerCertificate.Thumbprint -ne $env:CELESTE_VERIFY_SIGNER -or -not $s.TimeStamperCertificate){exit 1}"
    env = dict(os.environ, CELESTE_VERIFY_FILE=str(binary), CELESTE_VERIFY_SIGNER=record["signer_thumbprint"])
    checked = subprocess.run(["pwsh", "-NoProfile", "-NonInteractive", "-Command", script], env=env, capture_output=True, timeout=30)
    if checked.returncode:
        raise ValueError("Final trusted Windows signature is missing or changed")
    return record, binary, receipt


def preserve(path: Path, raw: bytes):
    if path.exists():
        if path.read_bytes() != raw:
            raise ValueError("Existing private publication evidence differs; refusing to overwrite it")
        return
    with path.open("xb") as stream:
        stream.write(raw)


def publish(directory: Path, tag: str, commit: str):
    record, binary, receipt = validate_candidate(directory, tag, commit)
    if os.environ.get("CELESTE_BACKEND_RELEASE_ID") != tag:
        raise ValueError("The protected release environment must attest the matched backend/catalog rollout first")
    sequence = int(os.environ["CELESTE_NATIVE_RELEASE_SEQUENCE"])
    prior_raw = os.environ["CELESTE_RETAINED_NATIVE_POLICY"].encode()
    if not 1 <= len(prior_raw) <= registration.MAX_RESPONSE:
        raise ValueError("Missing or oversized previous native policy; implicit allowlist replacement is forbidden")
    prior_path = directory / "retained-publication.json"
    preserve(prior_path, prior_raw)
    github = GitHub(os.environ["GITHUB_TOKEN"])
    repository = github.request("GET", API)
    if repository.get("full_name") != REPOSITORY or repository.get("private") is not True:
        raise ValueError("Production upload requires the private Celeste-Plus repository")
    try:
        release = github.request("GET", API + "/releases/tags/" + tag)
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        release = github.request("POST", API + "/releases", {"tag_name": tag, "target_commitish": commit,
            "name": "Celeste+ " + tag, "draft": True, "prerelease": False,
            "body": "Windows x64. Server-verified, VMProtect-reviewed and Windows publisher-signed.\n\nSHA-256: `" + record["sha256"] + "`"})
    if release.get("tag_name") != tag:
        raise ValueError("GitHub release tag mismatch")
    payload = binary.read_bytes()
    if len(payload) > MAX_ASSET or hashlib.sha256(payload).hexdigest() != record["sha256"]:
        raise ValueError("Final binary changed before upload")
    github.asset(release, "Celeste+.exe", payload, "application/octet-stream")
    github.asset(release, "SHA256SUMS.txt", (record["sha256"] + "  Celeste+.exe\n").encode(), "text/plain")
    # No credential is sent during policy bootstrap; subsequent service HTTP bytes
    # require PKIX plus the nonce-bound, signed leaf policy.
    transport = ProductionTransport()
    registration.http_json = transport.request
    key = registration.publication_key()
    generated, expected = registration.prepare(binary, receipt, tag, "celeste_plus", "windows-x64", record["sha256"], sequence, key, prior_path)
    envelope_path = directory / "publication-envelope.json"
    reused = envelope_path.exists()
    retention_path = directory / "retention-policy.json"
    reuse_source = envelope_path if reused else retention_path
    if reuse_source.exists():
        envelope = registration.checked_file(reuse_source)
        if set(envelope) != {"publication"}:
            raise ValueError("Invalid retained publication authorization")
        claims = registration.verify_token(envelope["publication"], key.public_key(), {"alg": "EdDSA", "typ": "CST"})
        # Only time fields may differ on a retry; preserve the exact original token.
        if set(claims) != set(expected) or any(claims[name] != expected[name] for name in expected if name not in {"iat", "nbf", "exp"}):
            raise ValueError("Retained authorization belongs to another publication")
        if any(type(claims[name]) is not int for name in ("iat", "nbf", "exp")) or claims["nbf"] != claims["iat"] or not claims["iat"] < claims["exp"] <= claims["iat"] + 300:
            raise ValueError("Invalid retained authorization lifetime")
    else:
        envelope, claims = generated, expected
    request, nonce = registration.manifest_request(claims)
    already_registered = False
    try:
        registration.verify_live_manifest(transport.request(request), claims, nonce)
        already_registered = True
    except ValueError:
        pass
    if not already_registered:
        preserve(envelope_path, registration.canonical(envelope) + b"\n")
        prior = registration.verify_token(registration.checked_file(prior_path)["publication"], key.public_key(), {"alg": "EdDSA", "typ": "CST"})
        registration.publish(envelope, claims, lambda: registration.protected_binary(
            binary, receipt, tag, "celeste_plus", "windows-x64", record["sha256"]), prior=prior)
    # A lost local authorization can be reconstructed for future retention only
    # after exact fresh server readback. It is never labelled as the accepted
    # POST token or used to replay an already-registered sequence.
    preserve(directory / "retention-policy.json", registration.canonical(envelope) + b"\n")
    evidence = {"schema": "celeste-native-registration-evidence-1", "sequence": sequence,
                "sha256": record["sha256"], "record_kind": "verified-live-policy-reconstruction"
                if already_registered and not reused else "exact-publication-authorization",
                "authorization_submitted_this_run": not already_registered}
    (directory / "publication-result.json").write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    if gate.sha(binary) != record["sha256"]:
        raise ValueError("Binary changed after server registration; keep GitHub draft private")
    if release["draft"]:
        release = github.request("PATCH", API + "/releases/" + str(release["id"]), {"draft": False})
    if release.get("draft") is not False:
        raise ValueError("GitHub publication was not confirmed")
    return {"status": "published", "repository": REPOSITORY, "tag": tag, "sha256": record["sha256"], "sequence": sequence}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    args = parser.parse_args()
    try:
        print(json.dumps(publish(args.directory.resolve(strict=True), args.tag, args.commit)))
    except (ValueError, KeyError, TypeError, OSError, subprocess.SubprocessError):
        parser.exit(1, "Release was not confirmed. Preserve the private build directory; inspect draft assets, protected evidence, signature, retained policy, backend rollout and sequence. No credentials are printed.\n")


if __name__ == "__main__":
    main()
