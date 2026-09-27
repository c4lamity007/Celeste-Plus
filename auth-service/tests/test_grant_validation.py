from __future__ import annotations

import json
import time
from types import SimpleNamespace
from contextlib import contextmanager
from dataclasses import replace

import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519
from fastapi.testclient import TestClient

import app.main as main
import app.security as security
from app.dpop import confirmation
from app.main import create_app
from app.security import GrantSigner, b64d, b64e, canonical_json
from test_dpop import env, grant, headers
from test_protocol import create_license


SEED = bytes(range(32))
HEADER = {"alg": "EdDSA", "kid": "celeste-release-1", "typ": "CST"}
DEVICE = "00000000-0000-0000-0000-000000000007"


@pytest.fixture
def claims():
    now = int(time.time())
    return {"aud": "celeste-installer", "product": "celeste", "sub": "7", "device": DEVICE,
            "iat": now, "nbf": now - 5, "exp": now + 600, "jti": "isolated-ticket-12345678",
            "cnf": confirmation(bytes(range(32)))}


def sign_raw(header, body):
    first, second = b64e(header), b64e(body)
    signed = f"{first}.{second}"
    return signed + "." + b64e(ed25519.Ed25519PrivateKey.from_private_bytes(SEED).sign(signed.encode("ascii")))


@pytest.mark.parametrize("audience,lifetime", [
    ("celeste-client", 600), ("celeste-installer", 600), ("celeste-stage-two", 600),
    ("celeste-stage-three", 600), ("celeste-stage-four", 600), ("celeste-session", 90),
    ("celeste-beta-manifest", 120), ("celeste-installation", 3650 * 86400),
])
def test_current_audiences_keep_exact_lifetimes_and_reject_extension(claims, audience, lifetime):
    signer = GrantSigner(SEED)
    claims.update(aud=audience, exp=claims["iat"] + lifetime)
    if audience == "celeste-installation":
        claims.pop("nbf")
        claims.pop("product")
        claims.update(install_key=b64e(bytes(range(32))), token_key=b64e(bytes(reversed(range(32)))))
    if audience == "celeste-session":
        claims.update(nonce="isolated-session-12345678", status="active")
    assert signer.verify(signer.sign(claims)) == claims
    claims["exp"] += 1
    assert signer.verify(signer.sign(claims)) is None


@pytest.mark.parametrize("lifetime", [1, 37, 1200, 3600])
def test_configured_grant_lifetime_is_preserved(claims, lifetime, monkeypatch):
    monkeypatch.setattr(security.time, "time", lambda: claims["iat"])
    signer = GrantSigner(SEED, grant_ttl_seconds=lifetime)
    claims["exp"] = claims["iat"] + lifetime
    assert signer.verify(signer.sign(claims)) == claims
    claims["exp"] += 1
    assert signer.verify(signer.sign(claims)) is None


@pytest.mark.parametrize("lifetime", [0, -1, True, "600", None, 600.0])
def test_invalid_grant_lifetime_configuration_fails_closed(lifetime):
    with pytest.raises(ValueError):
        GrantSigner(SEED, grant_ttl_seconds=lifetime)


@pytest.mark.parametrize("field", ["iat", "nbf", "exp"])
@pytest.mark.parametrize("value", [None, True, False, "future", 1.5, [], {}, float("nan"), float("inf")])
def test_numeric_dates_are_strict_integers(claims, field, value):
    claims[field] = value
    signer = GrantSigner(SEED)
    assert signer.verify(signer.sign(claims)) is None


@pytest.mark.parametrize("field", ["iat", "nbf", "exp"])
def test_numeric_date_strings_are_not_coerced(claims, field):
    claims[field] = str(claims[field])
    signer = GrantSigner(SEED)
    assert signer.verify(signer.sign(claims)) is None


@pytest.mark.parametrize("field", ["aud", "sub", "device", "cnf", "iat", "nbf", "exp", "product", "jti"])
def test_missing_required_access_claims_are_rejected(claims, field):
    claims.pop(field)
    signer = GrantSigner(SEED)
    assert signer.verify(signer.sign(claims)) is None


@pytest.mark.parametrize("updates", [
    {"aud": []}, {"aud": {}}, {"aud": "unknown"}, {"sub": "007"}, {"sub": 7}, {"sub": True},
    {"sub": "-7"}, {"sub": "0"}, {"sub": str(2**63)}, {"sub": "9" * 4000},
    {"sub": "٧"}, {"device": 7}, {"device": {}}, {"device": "tiny"}, {"device": "x" * 49},
    {"cnf": None}, {"cnf": []}, {"cnf": {"jkt": None}}, {"cnf": {"jkt": "wrong"}},
    {"cnf": {"jkt": b64e(bytes(32)), "extra": "ignored"}}, {"product": []}, {"product": "both"},
    {"jti": 1}, {"jti": "tiny"}, {"jti": "!" * 32}, {"jti": "x" * 129},
    {"chunks": None}, {"chunks": "chunk-id"}, {"chunks": [None]}, {"chunks": [{}]},
    {"chunks": []}, {"chunks": ["x"] * 9},
])
def test_malformed_common_claims_fail_closed(claims, updates):
    signer = GrantSigner(SEED)
    assert signer.verify(signer.sign({**claims, **updates})) is None


def test_temporal_order_and_future_issuance_are_checked(claims, monkeypatch):
    now = claims["iat"]
    monkeypatch.setattr(security.time, "time", lambda: now)
    signer = GrantSigner(SEED)
    for changes in ({"iat": now + 6}, {"iat": -1}, {"nbf": -1}, {"nbf": now + 6},
                    {"exp": now}, {"iat": now + 2, "exp": now + 1}):
        assert signer.verify(signer.sign({**claims, **changes})) is None
    assert signer.verify(signer.sign({**claims, "iat": now + 5, "exp": now + 600})) is not None


@pytest.mark.parametrize("updates", [
    {"kid": "another-key"}, {"kid": None}, {"kid": []}, {"crit": []}, {"crit": ["unknown"]},
    {"jku": "https://untrusted.invalid/keys"}, {"alg": "none"}, {"alg": "HS256"}, {"typ": "JWT"},
])
def test_unexpected_token_headers_are_rejected(claims, updates):
    token = sign_raw(canonical_json({**HEADER, **updates}), canonical_json(claims))
    assert GrantSigner(SEED).verify(token) is None


@pytest.mark.parametrize("location", ["header", "claims", "nested"])
def test_duplicate_json_members_are_rejected_even_with_valid_signature(claims, location):
    header, body = canonical_json(HEADER), canonical_json(claims)
    if location == "header":
        header = header[:-1] + b',"kid":"celeste-release-1"}'
    elif location == "claims":
        body = body[:-1] + b',"sub":"7"}'
    else:
        binding = canonical_json(claims["cnf"])
        body = body.replace(binding, binding[:-1] + b',"jkt":' + json.dumps(claims["cnf"]["jkt"]).encode() + b'}')
    assert GrantSigner(SEED).verify(sign_raw(header, body)) is None


@pytest.mark.parametrize("part", [0, 1, 2])
def test_padding_and_noncanonical_segment_encodings_are_rejected(claims, part):
    signer = GrantSigner(SEED)
    pieces = signer.sign(claims).split(".")
    pieces[part] += "="
    if part != 2:
        signed = ".".join(pieces[:2])
        pieces[2] = b64e(ed25519.Ed25519PrivateKey.from_private_bytes(SEED).sign(signed.encode("ascii")))
    assert signer.verify(".".join(pieces)) is None


def test_signature_unused_bits_cannot_create_an_alternate_token(claims):
    signer = GrantSigner(SEED)
    token = signer.sign(claims)
    alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    alternate = token[:-1] + alphabet[alphabet.index(token[-1]) + 1]
    assert b64d(alternate.split(".")[2]) == b64d(token.split(".")[2])
    assert signer.verify(alternate) is None


@pytest.mark.parametrize("encoding", ["utf-16", "utf-32", "utf-8-sig"])
def test_signed_json_must_be_utf8_without_bom(claims, encoding):
    token = sign_raw(canonical_json(HEADER), canonical_json(claims).decode().encode(encoding))
    assert GrantSigner(SEED).verify(token) is None


@pytest.mark.parametrize("token", [None, 123, "", "x" * 16385, "a.b", "a.b.c.d", "..", "💥.a.a"])
def test_invalid_token_envelopes_never_throw(token):
    assert GrantSigner(SEED).verify(token) is None


@pytest.mark.parametrize("updates", [{"aud": []}, {"exp": "9999999999"}, {"sub": "9" * 100}, {"chunks": None}])
def test_invalid_signed_grants_return_401_not_server_errors(env, updates):
    token = grant(env, "stage4")
    claims = env.app.state.signer.verify(token)
    malformed = env.app.state.signer.sign({**claims, **updates})
    path = f"/v1/blob/{env.config.chunk_a_id}"
    assert env.client.get(path, headers=headers(env, "GET", path, malformed)).status_code == 401
    assert env.client.get(path, headers=headers(env, "GET", path, token)).status_code == 200


def test_create_app_preserves_nondefault_grant_lifetime(env):
    config = replace(env.config, grant_ttl_seconds=1200)
    app = create_app(config)
    path = f"/v1/bootstrap/{config.bootstrap_id}"
    with TestClient(app, base_url="https://testserver") as client:
        configured = SimpleNamespace(**{**vars(env), "client": client, "app": app, "config": config})
        token = grant(configured)
        claims = app.state.signer.verify(token)
        assert claims["exp"] - claims["iat"] == 1200
        assert client.get(path, headers=headers(env, "GET", path, token)).status_code == 200
    assert env.client.get(path, headers=headers(env, "GET", path, token)).status_code == 401


def mutate_before_authorization_lock(env, monkeypatch, change):
    original = env.app.state.database.connect
    changed = []

    class Connection:
        def __init__(self, delegate):
            self.delegate = delegate

        def execute(self, sql, *args, **kwargs):
            if sql == "BEGIN IMMEDIATE" and not changed:
                changed.append(True)
                with original() as connection:
                    change(connection)
            return self.delegate.execute(sql, *args, **kwargs)

        def __getattr__(self, name):
            return getattr(self.delegate, name)

    @contextmanager
    def connect():
        with original() as connection:
            yield Connection(connection)

    monkeypatch.setattr(env.app.state.database, "connect", connect)
    return changed


@pytest.mark.parametrize("change", ["license", "device", "entitlement", "expiry", "key", "reassignment"])
def test_cloud_final_transaction_rechecks_live_identity(env, monkeypatch, change):
    token = grant(env, "auth")
    path = "/v1/configs"
    request_headers = headers(env, "GET", path, token)
    other_uid = create_license(env.config, "CST-ISOLATED-SECOND-OWNER-123456789") if change == "reassignment" else None

    def mutate(connection):
        if change == "license":
            connection.execute("UPDATE licenses SET status='revoked' WHERE uid=?", (env.uid,))
        elif change == "device":
            connection.execute("UPDATE devices SET status='revoked'")
        elif change == "entitlement":
            connection.execute("DELETE FROM license_entitlements WHERE license_uid=?", (env.uid,))
        elif change == "expiry":
            connection.execute("UPDATE licenses SET expires_at=1 WHERE uid=?", (env.uid,))
        elif change == "key":
            connection.execute("UPDATE devices SET install_signing_key=?", (bytes(reversed(range(32))),))
        else:
            connection.execute("UPDATE devices SET license_uid=?", (other_uid,))

    changed = mutate_before_authorization_lock(env, monkeypatch, mutate)
    response = env.client.get(path, headers=request_headers)
    assert changed
    assert response.status_code == (403 if change == "entitlement" else 401), response.text


@pytest.mark.parametrize("target", ["cloud", "bootstrap"])
def test_token_expiring_while_waiting_for_auth_lock_is_denied(env, monkeypatch, target):
    token = grant(env, "auth" if target == "cloud" else "installer")
    claims = env.app.state.signer.verify(token)
    now = int(time.time())
    claims["exp"] = now + 1
    token = env.app.state.signer.sign(claims)
    path = "/v1/configs" if target == "cloud" else f"/v1/bootstrap/{env.config.bootstrap_id}"
    request_headers = headers(env, "GET", path, token)

    def expire(connection):
        monkeypatch.setattr(main.time, "time", lambda: now + 2)

    changed = mutate_before_authorization_lock(env, monkeypatch, expire)
    result = env.client.get(path, headers=request_headers)
    assert changed
    assert result.status_code == 401, result.text
