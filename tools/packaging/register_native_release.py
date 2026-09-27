"""Prepare (default) or register an approved native release with the live service.

No server runtime signing key is used. The separate CI publication seed is read
only from CELESTE_NATIVE_RELEASE_PRIVATE_KEY (canonical base64url, 32 bytes).
Protection receipts must bind the FINAL distributed executable. Authenticode is
checked separately by the Sign workflow; this tool does not perform that check.
An explicitly authorized unsigned release must record that exception separately.
Preparation performs
no network access; --publish is an explicit, coordinated deployment operation.
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import time
import urllib.error
import urllib.parse
import urllib.request

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey

import native_protection_gate as protection

PRODUCTION_ORIGIN = "https://qb0f90cf11f4d1d52.fly.dev"
SERVER_PUBLIC_KEY = "rhLhkicLy3laX7_Q7rSv9OGVyrSHr1OyEZW8_hJfoKY"
PRODUCTS = {"celeste_plus"}
PLATFORMS = {"windows-x64", "linux-x64", "macos-x64", "macos-arm64"}
HEX = re.compile(r"[0-9a-f]{64}\Z")
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_-]{0,79}\Z")
MAX_RESPONSE = 16_384


def canonical(value) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def decode(value: str) -> bytes:
    if not isinstance(value, str) or not re.fullmatch(r"[A-Za-z0-9_-]+", value):
        raise ValueError("Invalid signed encoding")
    result = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    if b64(result) != value:
        raise ValueError("Noncanonical signed encoding")
    return result


def strict_json(raw: bytes | str):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("Duplicate signed field")
            result[key] = value
        return result
    def reject_constant(_):
        raise ValueError("Invalid JSON constant")
    return json.loads(raw, object_pairs_hook=unique, parse_constant=reject_constant)


def checked_file(path: Path) -> dict:
    if path.stat().st_size > MAX_RESPONSE:
        raise ValueError("Publication evidence exceeds bounds")
    value = strict_json(path.read_bytes())
    if not isinstance(value, dict):
        raise ValueError("Publication evidence must be an object")
    return value


def publication_key() -> Ed25519PrivateKey:
    try:
        raw = decode(os.environ.get("CELESTE_NATIVE_RELEASE_PRIVATE_KEY", ""))
        if len(raw) != 32:
            raise ValueError()
        return Ed25519PrivateKey.from_private_bytes(raw)
    except (ValueError, TypeError):
        raise ValueError("Set the separate CI Ed25519 seed in CELESTE_NATIVE_RELEASE_PRIVATE_KEY; no unsigned fallback") from None


def verify_token(token: str, key: Ed25519PublicKey, header: dict) -> dict:
    try:
        if not isinstance(token, str) or not 1 <= len(token) <= 12_288:
            raise ValueError()
        first, second, signature = token.split(".")
        key.verify(decode(signature), (first + "." + second).encode("ascii"))
        if strict_json(decode(first)) != header:
            raise ValueError()
        claims = strict_json(decode(second))
        if not isinstance(claims, dict):
            raise ValueError()
        return claims
    except (ValueError, TypeError, InvalidSignature, UnicodeError, RecursionError):
        raise ValueError("Signed publication or manifest is invalid") from None


def binary_metadata(value: dict) -> dict:
    if (not isinstance(value, dict) or set(value) != {"sha256", "size", "release_id", "protection_receipt_sha256"}
            or not isinstance(value["sha256"], str) or not HEX.fullmatch(value["sha256"])
            or not isinstance(value["protection_receipt_sha256"], str) or not HEX.fullmatch(value["protection_receipt_sha256"])
            or not isinstance(value["release_id"], str) or not IDENTIFIER.fullmatch(value["release_id"])
            or type(value["size"]) is not int or not 0 < value["size"] <= 256 * 1024 * 1024):
        raise ValueError("Invalid approved executable metadata")
    return dict(value)


def retained_binaries(path: Path | None, key: Ed25519PublicKey, product: str, platform: str,
                      sequence: int) -> list[dict]:
    if path is None:
        return []
    envelope = checked_file(path)
    if set(envelope) != {"publication"}:
        raise ValueError("Retention requires a previous CI-signed publication envelope")
    value = verify_token(envelope["publication"], key, {"alg": "EdDSA", "typ": "CST"})
    if (set(value) != {"aud", "product", "platform", "iat", "nbf", "exp", "sequence", "binaries"}
            or value["aud"] != "celeste-native-publication" or value["product"] != product or value["platform"] != platform
            or any(type(value[name]) is not int for name in ("iat", "nbf", "exp", "sequence"))
            or not 0 < value["sequence"] < sequence or value["iat"] < 0 or value["nbf"] != value["iat"]
            or not value["iat"] < value["exp"] <= value["iat"] + 300
            or not isinstance(value["binaries"], list) or not 1 <= len(value["binaries"]) <= 3):
        raise ValueError("Retained publication must be an older matching policy with at most three approved binaries")
    # Expiry is deliberately ignored only for this historical, authenticated
    # policy. The new publication gets a fresh bounded authorization window.
    binaries = [binary_metadata(item) for item in value["binaries"]]
    if len({item["sha256"] for item in binaries}) != len(binaries):
        raise ValueError("Duplicate retained executable")
    return binaries


def protected_binary(binary: Path, receipt: Path, release_id: str, product: str,
                     platform: str, expected_sha256: str) -> dict:
    if product not in PRODUCTS or platform not in PLATFORMS or not IDENTIFIER.fullmatch(release_id):
        raise ValueError("Invalid release identity")
    if not HEX.fullmatch(expected_sha256) or protection.sha(binary) != expected_sha256:
        raise ValueError("Packaged executable differs from the expected final distributed bytes")
    evidence = protection.validate(binary, receipt, release_id=release_id, product=product, platform=platform)
    if evidence["binary_sha256"] != expected_sha256:
        raise ValueError("Protected executable changed during verification")
    return binary_metadata({"sha256": expected_sha256, "size": binary.stat().st_size,
                            "release_id": release_id, "protection_receipt_sha256": evidence["receipt_sha256"]})


def prepare(binary: Path, receipt: Path, release_id: str, product: str, platform: str,
            expected_sha256: str, sequence: int, key: Ed25519PrivateKey, retain: Path | None = None,
            *, now: int | None = None) -> tuple[dict, dict]:
    if type(sequence) is not int or not 0 < sequence < 2**63:
        raise ValueError("Publication sequence must be a positive signed 64-bit integer")
    current = protected_binary(binary, receipt, release_id, product, platform, expected_sha256)
    binaries = [current] + retained_binaries(retain, key.public_key(), product, platform, sequence)
    if len({item["sha256"] for item in binaries}) != len(binaries):
        raise ValueError("Current executable cannot also appear in retained policy")
    now = int(time.time()) if now is None else now
    claims = {"aud": "celeste-native-publication", "product": product, "platform": platform,
              "iat": now, "nbf": now, "exp": now + 300, "sequence": sequence, "binaries": binaries}
    signed = b64(canonical({"alg": "EdDSA", "typ": "CST"})) + "." + b64(canonical(claims))
    return {"publication": signed + "." + b64(key.sign(signed.encode("ascii")))}, claims


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError("Native publication redirects are forbidden")


def http_json(request: urllib.request.Request) -> dict:
    url = urllib.parse.urlsplit(request.full_url)
    if (url.scheme + "://" + url.netloc != PRODUCTION_ORIGIN
            or url.path != "/v1/client-manifest" or url.fragment):
        raise ValueError("Native publication is restricted to the reviewed production origin")
    with urllib.request.build_opener(NoRedirect()).open(request, timeout=25) as response:
        if (response.status != 200 or response.geturl() != request.full_url
                or response.headers.get_content_type() != "application/json"
                or response.headers.get("Content-Encoding", "identity") != "identity"):
            raise ValueError("Unexpected native publication response")
        raw = response.read(MAX_RESPONSE + 1)
    if len(raw) > MAX_RESPONSE:
        raise ValueError("Native publication response exceeds bounds")
    value = strict_json(raw)
    if not isinstance(value, dict):
        raise ValueError("Native publication response must be an object")
    return value


def verify_live_manifest(response: dict, claims: dict, nonce: str, *, now: int | None = None,
                         public_key: Ed25519PublicKey | None = None) -> None:
    if set(response) != {"manifest"}:
        raise ValueError("Missing signed native manifest")
    key = public_key or Ed25519PublicKey.from_public_bytes(decode(SERVER_PUBLIC_KEY))
    value = verify_token(response["manifest"], key, {"alg": "EdDSA", "kid": "celeste-release-1", "typ": "CST"})
    now = int(time.time()) if now is None else now
    if (set(value) != {"aud", "iat", "nbf", "exp", "product", "platform", "sequence", "request_nonce", "sha256"}
            or value["aud"] != "celeste-native-manifest" or value["product"] != claims["product"]
            or value["platform"] != claims["platform"] or value["request_nonce"] != nonce
            or len(decode(nonce)) != 24 or value["sequence"] != claims["sequence"]
            or any(type(value[name]) is not int for name in ("iat", "nbf", "exp", "sequence"))
            or not 0 <= value["iat"] <= now + 5 or value["nbf"] != value["iat"]
            or not now < value["exp"] <= value["iat"] + 120 or value["exp"] <= value["iat"]
            or not isinstance(value["sha256"], list)
            or any(not isinstance(item, str) or not HEX.fullmatch(item) for item in value["sha256"])
            or sorted(value["sha256"]) != sorted(item["sha256"] for item in claims["binaries"])):
        raise ValueError("Live signed policy does not match the requested publication")


def manifest_request(claims: dict) -> tuple[urllib.request.Request, str]:
    nonce = b64(secrets.token_bytes(24))
    url = PRODUCTION_ORIGIN + f'/v1/client-manifest?product={claims["product"]}&platform={claims["platform"]}'
    return urllib.request.Request(url, headers={"X-Celeste-Request-Nonce": nonce,
        "Accept": "application/json", "Accept-Encoding": "identity", "Cache-Control": "no-store"}), nonce


def require_starting_policy(claims: dict, prior: dict | None, bootstrap_empty: bool) -> None:
    if (prior is None) == (not bootstrap_empty):
        raise ValueError("Publication requires either an approved retained policy or explicit empty bootstrap")
    request, nonce = manifest_request(claims)
    try:
        response = http_json(request)
    except urllib.error.HTTPError as error:
        # Only the real endpoint's explicit absent-policy response can bootstrap;
        # network failures, generic outages, 404 and invalid manifests cannot.
        if (not bootstrap_empty or error.code != 503 or error.geturl() != request.full_url
                or error.headers.get_content_type() != "application/json"):
            raise ValueError("Cannot verify the current native publication policy") from None
        raw = error.read(MAX_RESPONSE + 1)
        if len(raw) > MAX_RESPONSE:
            raise ValueError("Cannot verify the current native publication policy")
        value = strict_json(raw)
        if not isinstance(value, dict) or value.get("detail") != "native release is not registered":
            raise ValueError("The service did not confirm an empty native policy")
        return
    if bootstrap_empty:
        raise ValueError("An existing policy cannot be replaced through empty bootstrap")
    verify_live_manifest(response, prior, nonce)
    old_hashes = {item["sha256"] for item in prior["binaries"]}
    if not old_hashes.issubset({item["sha256"] for item in claims["binaries"]}):
        raise ValueError("Staged publication must retain every currently approved executable")


def publish(envelope: dict, claims: dict, recheck, *, prior: dict | None = None,
            bootstrap_empty: bool = False) -> dict:
    """Retry the exact signed token; never regenerate a same-sequence token."""
    def require_unchanged():
        if recheck() != claims["binaries"][0]:
            raise ValueError("Final binary or protection receipt changed after publication preparation")
    require_unchanged()
    if int(time.time()) >= claims["exp"]:
        raise ValueError("Prepared publication expired; prepare a higher-sequence release")
    require_starting_policy(claims, prior, bootstrap_empty)
    body = canonical(envelope)
    for attempt in range(3):
        require_unchanged()
        if int(time.time()) >= claims["exp"]:
            raise ValueError("Prepared publication expired; prepare a higher-sequence release")
        request = urllib.request.Request(PRODUCTION_ORIGIN + "/v1/client-manifest", data=body,
            headers={"Content-Type": "application/json", "Accept": "application/json", "Accept-Encoding": "identity"}, method="POST")
        try:
            result = http_json(request)
            if (set(result) != {"registered", "sequence"} or result["registered"] is not True
                    or type(result["sequence"]) is not int or result["sequence"] != claims["sequence"]):
                raise ValueError("Native publication acknowledgement is invalid")
            break
        except urllib.error.HTTPError as error:
            if attempt == 2 or error.code < 500:
                raise ValueError("Native publication rejected; inspect service status without exposing the signed token") from None
        except (urllib.error.URLError, TimeoutError):
            if attempt == 2:
                raise ValueError("Native publication could not be confirmed; retry the same token or use a higher sequence") from None
        time.sleep(attempt + 1)
    request, nonce = manifest_request(claims)
    try:
        response = http_json(request)
        verify_live_manifest(response, claims, nonce)
    except (ValueError, urllib.error.URLError, TimeoutError):
        raise ValueError("Publication may be registered, but fresh signed policy verification failed; do not announce success") from None
    require_unchanged()
    return {"status": "registered-and-verified", "sequence": claims["sequence"],
            "product": claims["product"], "platform": claims["platform"],
            "sha256": [item["sha256"] for item in claims["binaries"]]}


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--receipt", type=Path, required=True)
    parser.add_argument("--release-id", required=True)
    parser.add_argument("--product", choices=sorted(PRODUCTS), required=True)
    parser.add_argument("--platform", choices=sorted(PLATFORMS), required=True)
    parser.add_argument("--expected-sha256", required=True)
    parser.add_argument("--sequence", type=int, required=True)
    parser.add_argument("--retain", type=Path, help="Previous CI-signed publication envelope; at most three retained binaries")
    parser.add_argument("--bootstrap-empty", action="store_true", help="Explicit first registration; requires the live endpoint to confirm no existing policy")
    parser.add_argument("--output", type=Path, required=True, help="New private output file containing the short-lived publication token")
    parser.add_argument("--publish", action="store_true", help="Explicitly register with the production service and verify its fresh signed policy")
    args = parser.parse_args(argv)
    try:
        key = publication_key()
        if args.retain and args.bootstrap_empty:
            raise ValueError("Choose retained policy or explicit initial bootstrap, not both")
        def recheck():
            return protected_binary(args.binary, args.receipt, args.release_id, args.product, args.platform, args.expected_sha256)
        envelope, claims = prepare(args.binary, args.receipt, args.release_id, args.product, args.platform,
            args.expected_sha256, args.sequence, key, args.retain)
        with args.output.open("xb") as output:
            output.write(canonical(envelope) + b"\n")
        prior = verify_token(checked_file(args.retain)["publication"], key.public_key(), {"alg": "EdDSA", "typ": "CST"}) if args.retain else None
        result = publish(envelope, claims, recheck, prior=prior, bootstrap_empty=args.bootstrap_empty) if args.publish else {
            "status": "prepared-not-published", "sequence": args.sequence, "product": args.product,
            "platform": args.platform, "expires_at": claims["exp"], "sha256": args.expected_sha256,
            "publication_sha256": hashlib.sha256(envelope["publication"].encode("ascii")).hexdigest()}
        print(json.dumps(result, sort_keys=True))
        return 0
    except (ValueError, OSError, KeyError, TypeError, RecursionError):
        # Do not print library errors, request bodies, private keys or signed tokens.
        parser.exit(1, "Native release preparation or verification failed. Check protection evidence, final hash, signing material, sequence, and server status.\n")


if __name__ == "__main__":
    raise SystemExit(main())
