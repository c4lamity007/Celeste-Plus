"""RFC 9449 request proofs, bound to the existing installation Ed25519 key."""
from __future__ import annotations

import hashlib
import hmac
import json
import re
import time
from urllib.parse import urlsplit

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from fastapi import HTTPException, Request

from .security import b64d, b64e, canonical_json

MAX_AGE = 60
FUTURE_SKEW = 5
_B64URL = re.compile(r"[A-Za-z0-9_-]+\Z")
_JTI = re.compile(r"[A-Za-z0-9_-]{22,128}\Z")


def key_thumbprint(public_key: bytes) -> str:
    return b64e(hashlib.sha256(canonical_json({"crv": "Ed25519", "kty": "OKP", "x": b64e(public_key)})).digest())


def confirmation(public_key: bytes) -> dict[str, str]:
    return {"jkt": key_thumbprint(public_key)}


def denied() -> HTTPException:
    return HTTPException(401, "invalid DPoP proof", headers={"WWW-Authenticate": 'DPoP error="invalid_dpop_proof"'})


def access_token(request: Request) -> str:
    values = request.headers.getlist("authorization")
    if len(values) != 1:
        raise denied()
    parts = values[0].split(" ")
    if len(parts) != 2 or parts[0].lower() != "dpop" or not 1 <= len(parts[1]) <= 16384:
        raise denied()
    return parts[1]


def _decode(value: str) -> bytes:
    if not _B64URL.fullmatch(value):
        raise ValueError("invalid encoding")
    result = b64d(value)
    if b64e(result) != value:
        raise ValueError("noncanonical encoding")
    return result


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate member")
        result[key] = value
    return result


class DPoPVerifier:
    def __init__(self, settings):
        origin = settings.public_origin or ("https://" + settings.allowed_hosts[0])
        parsed = urlsplit(origin)
        if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
                or parsed.query or parsed.fragment or parsed.path not in ("", "/") or "*" in parsed.netloc):
            raise RuntimeError("CELESTE_PUBLIC_ORIGIN must be a trusted HTTPS origin")
        # Accessing port also rejects malformed port numbers during startup.
        port = parsed.port
        if port is not None and not 1 <= port <= 65535:
            raise RuntimeError("CELESTE_PUBLIC_ORIGIN has an invalid port")
        hostname = parsed.hostname.lower()
        if ":" in hostname:
            hostname = "[" + hostname + "]"
        self.origin = "https://" + hostname + (f":{port}" if port not in (None, 443) else "")

    def require(self, connection, request: Request, public_key: bytes, *, token: str | None = None, claims=None) -> None:
        """Verify and atomically consume jti in the caller's SQLite transaction.

        A successful authorization must commit this insertion before serving bytes.
        Failed operations may roll back; they never release protected content.
        """
        try:
            values = request.headers.getlist("dpop")
            if len(values) != 1 or not 1 <= len(values[0]) <= 4096:
                raise ValueError("missing proof")
            first, second, third = values[0].split(".")
            header = json.loads(_decode(first), object_pairs_hook=_object)
            payload = json.loads(_decode(second), object_pairs_hook=_object)
            if not isinstance(header, dict) or not isinstance(payload, dict):
                raise ValueError("invalid proof")
            if header.get("typ") != "dpop+jwt" or header.get("alg") != "EdDSA" or "crit" in header:
                raise ValueError("invalid header")
            jwk = header.get("jwk")
            if not isinstance(jwk, dict) or set(jwk) != {"kty", "crv", "x"} or jwk.get("kty") != "OKP" or jwk.get("crv") != "Ed25519":
                raise ValueError("invalid public key")
            supplied_key = _decode(jwk["x"])
            if len(supplied_key) != 32 or not hmac.compare_digest(supplied_key, public_key):
                raise ValueError("wrong installation")
            signature = _decode(third)
            Ed25519PublicKey.from_public_bytes(supplied_key).verify(signature, f"{first}.{second}".encode("ascii"))
            now = int(time.time())
            iat = payload.get("iat")
            jti = payload.get("jti")
            raw_path = request.scope.get("raw_path", request.url.path.encode("ascii")).split(b"?", 1)[0].decode("ascii")
            if (type(iat) is not int or not now - MAX_AGE <= iat <= now + FUTURE_SKEW
                    or not isinstance(jti, str) or not _JTI.fullmatch(jti)
                    or payload.get("htm") != request.method.upper()
                    or payload.get("htu") != self.origin + raw_path):
                raise ValueError("invalid target or freshness")
            thumbprint = key_thumbprint(public_key)
            if token is None:
                if "ath" in payload:
                    raise ValueError("unexpected access token hash")
            else:
                expected = b64e(hashlib.sha256(token.encode("ascii")).digest())
                if not isinstance(payload.get("ath"), str) or not hmac.compare_digest(payload["ath"], expected):
                    raise ValueError("wrong access token")
                if not isinstance(claims, dict) or claims.get("cnf") != {"jkt": thumbprint}:
                    raise ValueError("unbound token")
            connection.execute("DELETE FROM dpop_replays WHERE expires_at < ?", (now,))
            inserted = connection.execute(
                "INSERT OR IGNORE INTO dpop_replays(jkt,jti,expires_at) VALUES(?,?,?)",
                (thumbprint, jti, iat + MAX_AGE),
            )
            if inserted.rowcount != 1:
                raise ValueError("replayed proof")
        except (ValueError, TypeError, KeyError, UnicodeError, InvalidSignature, RecursionError):
            raise denied() from None
