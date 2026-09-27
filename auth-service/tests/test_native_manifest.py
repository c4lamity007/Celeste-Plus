from __future__ import annotations

from dataclasses import replace
import secrets
import time
import json

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest

from app.database import Database
from app.native_manifest import create_native_manifest_router
from app.security import GrantSigner, b64d, b64e, canonical_json
from test_protocol import settings


@pytest.fixture
def native(tmp_path):
    key = Ed25519PrivateKey.generate()
    config = replace(settings(tmp_path), native_release_public_key=key.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    database = Database(config.database_path)
    database.initialize()
    signer = GrantSigner(config.signing_private_key)
    app = FastAPI()
    app.include_router(create_native_manifest_router(config, database, signer, lambda *args: None))
    return TestClient(app), database, signer, key


def claims(sequence=1):
    now = int(time.time())
    return {
        "aud": "celeste-native-publication", "iat": now, "nbf": now, "exp": now + 120,
        "product": "celeste_plus", "platform": "windows-x64", "sequence": sequence,
        "binaries": [{"sha256": "a" * 64, "size": 1024, "release_id": "native-fixture-1",
                      "protection_receipt_sha256": "b" * 64}],
    }


def signed(key, value):
    first = b64e(canonical_json({"alg": "EdDSA", "typ": "CST"}))
    second = b64e(canonical_json(value))
    message = (first + "." + second).encode("ascii")
    return message.decode("ascii") + "." + b64e(key.sign(message))


def get(client, nonce=None, product="celeste_plus", platform="windows-x64"):
    return client.get("/v1/client-manifest", params={"product": product, "platform": platform},
                      headers={} if nonce is None else {"X-Celeste-Request-Nonce": nonce})


def publish(client, key, value):
    return client.post("/v1/client-manifest", json={"publication": signed(key, value)})


def verified_manifest(signer, response):
    token = response.json()["manifest"]
    first, second, third = token.split(".")
    Ed25519PublicKey.from_public_bytes(b64d(signer.public_key_b64())).verify(
        b64d(third), (first + "." + second).encode("ascii"))
    body = json.loads(b64d(second))
    assert body["nbf"] <= int(time.time()) < body["exp"]
    # A public binary manifest must never become a device access grant.
    assert signer.verify(token) is None
    return body


def test_nonce_bound_registered_binary_policy(native):
    client, database, signer, key = native
    assert get(client, secrets.token_urlsafe(24)).status_code == 503
    assert publish(client, key, claims()).status_code == 200
    for nonce in (secrets.token_urlsafe(24), secrets.token_urlsafe(24)):
        response = get(client, nonce)
        assert response.status_code == 200
        assert response.headers["Cache-Control"] == "no-store"
        body = verified_manifest(signer, response)
        assert body["request_nonce"] == nonce
        assert body["aud"] == "celeste-native-manifest"
        assert body["sha256"] == ["a" * 64]
        assert body["product"] == "celeste_plus" and body["platform"] == "windows-x64"
        assert body["exp"] - body["iat"] == 120
    assert get(client, secrets.token_urlsafe(24), product="celeste").status_code == 503
    with database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM audit_log WHERE event='native_release_registered'").fetchone()[0] == 1


@pytest.mark.parametrize("nonce", [None, "", "a" * 31, "a" * 33, "a" * 32 + "=", "+" * 32])
def test_native_nonce_is_mandatory_and_canonical(native, nonce):
    assert get(native[0], nonce).status_code == 400


@pytest.mark.parametrize("mutation", ["forged", "expired", "future", "wrong-audience", "bad-hash", "duplicates",
                                      "unknown-field", "bad-product", "bad-platform", "bad-sequence", "long-validity"])
def test_rejected_publication_never_changes_policy(native, mutation):
    client, database, signer, key = native
    value = claims()
    if mutation == "forged": key = Ed25519PrivateKey.generate()
    elif mutation == "expired": value["exp"] = int(time.time()) - 1
    elif mutation == "future": value["iat"] += 60; value["nbf"] = value["iat"]
    elif mutation == "wrong-audience": value["aud"] = "celeste-native-manifest"
    elif mutation == "bad-hash": value["binaries"][0]["sha256"] = "A" * 64
    elif mutation == "duplicates": value["binaries"] *= 2
    elif mutation == "unknown-field": value["url"] = "https://not-trusted.invalid"
    elif mutation == "bad-product": value["product"] = "other"
    elif mutation == "bad-platform": value["platform"] = "windows-other"
    elif mutation == "bad-sequence": value["sequence"] = True
    elif mutation == "long-validity": value["exp"] = value["iat"] + 301
    assert publish(client, key, value).status_code == 401
    with database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM native_release_policies").fetchone()[0] == 0


def test_publication_retries_are_idempotent_and_rollback_requires_new_sequence(native):
    client, database, signer, key = native
    first = claims(2)
    assert publish(client, key, first).status_code == 200
    assert publish(client, key, first).status_code == 200
    stale = claims(1)
    assert publish(client, key, stale).status_code == 409
    same_sequence = claims(2)
    same_sequence["binaries"][0]["sha256"] = "c" * 64
    assert publish(client, key, same_sequence).status_code == 409
    newer = claims(3)
    newer["binaries"][0]["sha256"] = "c" * 64
    assert publish(client, key, newer).status_code == 200
    body = verified_manifest(signer, get(client, secrets.token_urlsafe(24)))
    assert body["sha256"] == ["c" * 64] and body["sequence"] == 3


def test_unconfigured_publication_cannot_register(tmp_path):
    config = settings(tmp_path)
    database = Database(config.database_path)
    database.initialize()
    app = FastAPI()
    app.include_router(create_native_manifest_router(config, database, GrantSigner(config.signing_private_key), lambda *args: None))
    assert publish(TestClient(app), Ed25519PrivateKey.generate(), claims()).status_code == 503


def test_duplicate_nonce_headers_rejected(native):
    response = native[0].get("/v1/client-manifest?product=celeste_plus&platform=windows-x64",
        headers=[("X-Celeste-Request-Nonce", "a" * 32), ("X-Celeste-Request-Nonce", "b" * 32)])
    assert response.status_code == 400


def test_additive_migration_preserves_existing_license_schema(native):
    from pathlib import Path
    client, database, signer, key = native
    migration = Path(__file__).resolve().parents[1] / "migrations/20260927_native_publications.sql"
    with database.connect() as connection:
        before = connection.execute("SELECT sql FROM sqlite_master WHERE name='licenses'").fetchone()[0]
        connection.executescript(migration.read_text(encoding="utf-8"))
        connection.executescript(migration.read_text(encoding="utf-8"))
        after = connection.execute("SELECT sql FROM sqlite_master WHERE name='licenses'").fetchone()[0]
        assert before == after
