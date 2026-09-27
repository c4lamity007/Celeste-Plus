from __future__ import annotations

import base64
import hashlib
import hmac
import json
import re
import secrets
import time
from typing import Any

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerifyMismatchError
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF


PASSWORD_HASHER = PasswordHasher(time_cost=3, memory_cost=65536, parallelism=2)


def b64e(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")


def b64d(value: str, expected: int | None = None) -> bytes:
    try:
        decoded = base64.urlsafe_b64decode(value + "===")
    except (ValueError, TypeError) as exc:
        raise ValueError("invalid base64url") from exc
    if expected is not None and len(decoded) != expected:
        raise ValueError("invalid decoded length")
    return decoded


def canonical_json(value: dict[str, Any]) -> bytes:
    return json.dumps(value, separators=(",", ":"), sort_keys=True).encode("utf-8")


def digest_hex(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def keyed_digest(pepper: bytes, domain: str, value: str) -> bytes:
    return hmac.new(pepper, domain.encode() + b"\x00" + value.encode(), hashlib.sha256).digest()


def normalize_license(value: str) -> str:
    return "".join(character for character in value.upper() if character.isalnum())


def hash_license(pepper: bytes, value: str) -> bytes:
    normalized = normalize_license(value)
    if len(normalized) < 20 or len(normalized) > 64:
        raise ValueError("invalid license")
    return keyed_digest(pepper, "license-v1", normalized)


def verify_admin_password(encoded_hash: str, password: str) -> bool:
    try:
        return PASSWORD_HASHER.verify(encoded_hash, password)
    except (VerifyMismatchError, InvalidHashError):
        return False


def verify_ed25519(public_key: bytes, message: bytes, signature: str) -> bool:
    try:
        ed25519.Ed25519PublicKey.from_public_bytes(public_key).verify(b64d(signature, 64), message)
        return True
    except (ValueError, TypeError, InvalidSignature):
        return False


def issue_token_seed(client_encryption_key: bytes, salt: bytes, aad: bytes) -> tuple[bytes, dict[str, str]]:
    token_seed = secrets.token_bytes(32)
    server_ephemeral = x25519.X25519PrivateKey.generate()
    shared = server_ephemeral.exchange(x25519.X25519PublicKey.from_public_bytes(client_encryption_key))
    wrapping_key = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=salt,
        info=b"celeste-install-token-v1",
    ).derive(shared)
    nonce = secrets.token_bytes(12)
    ciphertext = AESGCM(wrapping_key).encrypt(nonce, token_seed, aad)
    ephemeral_public = server_ephemeral.public_key().public_bytes(
        serialization.Encoding.Raw,
        serialization.PublicFormat.Raw,
    )
    return token_seed, {
        "ephemeral_key": b64e(ephemeral_public),
        "nonce": b64e(nonce),
        "ciphertext": b64e(ciphertext),
    }


_TOKEN_HEADER = {"alg": "EdDSA", "kid": "celeste-release-1", "typ": "CST"}
_TOKEN_ENCODING = re.compile(r"[A-Za-z0-9_-]+\Z")
_TOKEN_ID = re.compile(r"[A-Za-z0-9_-]{16,128}\Z")
_TOKEN_SUBJECT = re.compile(r"[1-9][0-9]{0,18}\Z")
_ACCESS_AUDIENCES = frozenset({
    "celeste-client", "celeste-installer", "celeste-stage-two", "celeste-stage-three", "celeste-stage-four",
})


def _token_segment(value: str, expected: int | None = None) -> bytes:
    if not isinstance(value, str) or not _TOKEN_ENCODING.fullmatch(value):
        raise ValueError("invalid token encoding")
    decoded = b64d(value, expected)
    if b64e(decoded) != value:
        raise ValueError("noncanonical token encoding")
    return decoded


def _token_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate token member")
        result[key] = value
    return result


def _invalid_token_constant(value):
    raise ValueError("invalid token constant")


class GrantSigner:
    def __init__(self, private_seed: bytes, *, grant_ttl_seconds: int = 600) -> None:
        if type(grant_ttl_seconds) is not int or grant_ttl_seconds <= 0:
            raise ValueError("invalid grant lifetime")
        self._private = ed25519.Ed25519PrivateKey.from_private_bytes(private_seed)
        self._grant_ttl_seconds = grant_ttl_seconds

    def public_key_b64(self) -> str:
        value = self._private.public_key().public_bytes(
            serialization.Encoding.Raw,
            serialization.PublicFormat.Raw,
        )
        return b64e(value)

    def sign(self, claims: dict[str, Any]) -> str:
        first = b64e(canonical_json(_TOKEN_HEADER))
        second = b64e(canonical_json(claims))
        signed = f"{first}.{second}".encode("ascii")
        return f"{first}.{second}.{b64e(self._private.sign(signed))}"

    def verify(self, token: str) -> dict[str, Any] | None:
        try:
            if not isinstance(token, str) or not 1 <= len(token) <= 16384:
                return None
            first, second, signature = token.split(".")
            signed = f"{first}.{second}".encode("ascii")
            self._private.public_key().verify(_token_segment(signature, 64), signed)
            header = json.loads(_token_segment(first).decode("utf-8"), object_pairs_hook=_token_object,
                                parse_constant=_invalid_token_constant)
            claims = json.loads(_token_segment(second).decode("utf-8"), object_pairs_hook=_token_object,
                                parse_constant=_invalid_token_constant)
            if not isinstance(header, dict) or not isinstance(claims, dict):
                return None
            now = int(time.time())
            if header != _TOKEN_HEADER:
                return None
            audience = claims.get("aud")
            if not isinstance(audience, str):
                return None
            lifetime = self._grant_ttl_seconds if audience in _ACCESS_AUDIENCES else {
                "celeste-session": 90, "celeste-beta-manifest": 120, "celeste-installation": 3650 * 86400,
            }.get(audience)
            issued, expires = claims.get("iat"), claims.get("exp")
            if (lifetime is None or type(issued) is not int or type(expires) is not int
                    or not 0 <= issued <= now + 5 or expires <= now or not 0 < expires - issued <= lifetime):
                return None
            if "nbf" in claims or audience != "celeste-installation":
                not_before = claims.get("nbf")
                if type(not_before) is not int or not 0 <= not_before <= now + 5 or not_before >= expires:
                    return None
            subject, device, binding = claims.get("sub"), claims.get("device"), claims.get("cnf")
            if (not isinstance(subject, str) or not _TOKEN_SUBJECT.fullmatch(subject) or int(subject) >= 2**63
                    or not isinstance(device, str) or not 32 <= len(device) <= 48 or not device.isascii()
                    or not isinstance(binding, dict) or set(binding) != {"jkt"}):
                return None
            _token_segment(binding["jkt"], 32)
            if audience != "celeste-installation":
                if not isinstance(claims.get("product"), str) or claims["product"] not in {"celeste", "celeste_plus"}:
                    return None
            else:
                _token_segment(claims.get("install_key"), 32)
                _token_segment(claims.get("token_key"), 32)
            if audience in _ACCESS_AUDIENCES:
                if not isinstance(claims.get("jti"), str) or not _TOKEN_ID.fullmatch(claims["jti"]):
                    return None
            if audience == "celeste-session":
                nonce = claims.get("nonce")
                if claims.get("status") != "active" or not isinstance(nonce, str) or not _TOKEN_ID.fullmatch(nonce) or len(nonce) > 64:
                    return None
            if "chunks" in claims and (not isinstance(claims["chunks"], list) or not 1 <= len(claims["chunks"]) <= 8
                                       or any(not isinstance(chunk, str) or not 1 <= len(chunk) <= 80 for chunk in claims["chunks"])):
                return None
            return claims
        except (ValueError, TypeError, InvalidSignature, json.JSONDecodeError, UnicodeError, OverflowError, RecursionError):
            return None


def session_cookie(secret: bytes, session_id: str, issued_at: int) -> str:
    body = f"{session_id}.{issued_at}"
    signature = hmac.new(secret, body.encode(), hashlib.sha256).digest()
    return f"{body}.{b64e(signature)}"


def parse_session_cookie(secret: bytes, value: str, max_age: int = 3600) -> str | None:
    try:
        session_id, issued_text, signature = value.split(".", 2)
        issued_at = int(issued_text)
    except (ValueError, AttributeError):
        return None
    now = int(time.time())
    if issued_at > now + 5 or now - issued_at > max_age:
        return None
    expected = hmac.new(secret, f"{session_id}.{issued_at}".encode(), hashlib.sha256).digest()
    try:
        actual = b64d(signature, 32)
    except ValueError:
        return None
    return session_id if hmac.compare_digest(expected, actual) else None


def csrf_token(secret: bytes, session_id: str) -> str:
    return b64e(hmac.new(secret, b"csrf\x00" + session_id.encode(), hashlib.sha256).digest())


def valid_csrf(secret: bytes, session_id: str, candidate: str) -> bool:
    return hmac.compare_digest(csrf_token(secret, session_id), candidate or "")
