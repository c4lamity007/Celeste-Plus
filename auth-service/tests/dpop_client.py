"""Fixture-only client adapter: each existing regression request gets a new proof.

The adversarial DPoP tests intentionally use the unmodified FastAPI TestClient.
Only explicit, synthetic fixture keys registered here can sign requests.
"""
from __future__ import annotations

import hashlib
import json
import secrets
import time
from urllib.parse import urlsplit

from cryptography.hazmat.primitives import serialization
from fastapi.testclient import TestClient as RawTestClient

from app.security import b64d, b64e, canonical_json

_KEYS = {}


def register_key(key):
    public = b64e(key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    _KEYS[public] = key
    return key


def proof(key, method, url, token=None, *, payload_updates=None, header_updates=None):
    public = b64e(key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    header = {"typ": "dpop+jwt", "alg": "EdDSA", "jwk": {"kty": "OKP", "crv": "Ed25519", "x": public}}
    parsed = urlsplit(str(url))
    payload = {"htm": method.upper(), "htu": f"{parsed.scheme}://{parsed.netloc}{parsed.path or '/'}",
               "iat": int(time.time()), "jti": secrets.token_urlsafe(24)}
    if token is not None:
        payload["ath"] = b64e(hashlib.sha256(token.encode("ascii")).digest())
    header.update(header_updates or {})
    payload.update(payload_updates or {})
    first, second = b64e(canonical_json(header)), b64e(canonical_json(payload))
    return f"{first}.{second}.{b64e(key.sign(f'{first}.{second}'.encode('ascii')))}"


class TestClient(RawTestClient):
    __test__ = False

    def request(self, method, url, **kwargs):
        headers = dict(kwargs.pop("headers", None) or {})
        body = kwargs.get("json") or {}
        public = None
        token = None
        hardware_digest = None
        authorization = headers.get("Authorization", headers.get("authorization", ""))
        if authorization.startswith(("Bearer ", "DPoP ")):
            token = authorization.split(" ", 1)[1]
            headers.pop("authorization", None)
            headers["Authorization"] = "DPoP " + token
        with self.app.state.database.connect() as connection:
            if isinstance(body, dict) and body.get("challenge_id"):
                row = connection.execute("SELECT install_signing_key FROM challenges WHERE challenge_id=?",
                                         (body["challenge_id"],)).fetchone()
                public = b64e(bytes(row[0])) if row else None
            else:
                device = body.get("device_id") if isinstance(body, dict) else None
                if token:
                    try:
                        device = json.loads(b64d(token.split(".")[1])).get("device")
                    except (ValueError, IndexError, AttributeError):
                        pass
                if device:
                    row = connection.execute("SELECT install_signing_key,hwid_sha256,hardware_core_hash FROM devices WHERE device_id=?", (device,)).fetchone()
                    public = b64e(bytes(row[0])) if row else None
                    hardware_digest = (row[1] or bytes(row[2]).hex()) if row else None
        key = _KEYS.get(public)
        path = urlsplit(str(url)).path
        if method.upper() == "GET" and path == "/v1/beta/manifest":
            headers.setdefault("X-Celeste-Request-Nonce", secrets.token_urlsafe(24))
        if key and (token or path.endswith("/complete") or path == "/v1/session/check"):
            absolute = str(url) if str(url).startswith("https://") else str(self.base_url).rstrip("/") + str(url)
            extensions = {}
            if method.upper() == "GET" and path.startswith("/v1/beta/artifacts/") and hardware_digest:
                headers.setdefault("X-Device-Id", hardware_digest)
                extensions["hwid"] = hardware_digest
            headers.setdefault("DPoP", proof(key, method, absolute, token, payload_updates=extensions))
        return super().request(method, url, headers=headers, **kwargs)
