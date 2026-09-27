"""Raw HTTP requests: no adapter can silently add the required manifest nonce."""
from __future__ import annotations

import json
import secrets
import time

import pytest

from app.security import b64d, b64e, canonical_json
from dpop_client import proof
from test_dpop import env, grant


PATH = "/v1/beta/manifest"


def manifest_headers(env, token, nonce=None):
    headers = {"Authorization": "DPoP " + token,
               "DPoP": proof(env.install, "GET", "https://testserver" + PATH, token)}
    if nonce is not None:
        headers["X-Celeste-Request-Nonce"] = nonce
    return headers


@pytest.mark.parametrize("nonce", [None, "", "a" * 31, "a" * 33, "a" * 32 + "=",
                                  " " + "a" * 31, "a" * 31 + " ", "+" * 32, "/" * 32,
                                  "a" * 16 + "," + "b" * 15])
def test_old_clients_and_noncanonical_nonces_are_rejected(env, nonce):
    token = grant(env, product="celeste_plus")
    response = env.client.get(PATH, headers=manifest_headers(env, token, nonce))
    assert response.status_code == 400, response.text
    assert "manifest" not in response.json()


def test_duplicate_nonce_headers_are_rejected(env):
    token = grant(env, product="celeste_plus")
    headers = list(manifest_headers(env, token, secrets.token_urlsafe(24)).items())
    headers.append(("X-Celeste-Request-Nonce", secrets.token_urlsafe(24)))
    assert env.client.get(PATH, headers=headers).status_code == 400


def test_signed_manifest_is_bound_to_each_fresh_request_and_not_a_captured_response(env):
    token = grant(env, product="celeste_plus")
    nonces = [secrets.token_urlsafe(24), secrets.token_urlsafe(24)]
    assert nonces[0] != nonces[1]
    manifests = []
    for nonce in nonces:
        response = env.client.get(PATH, headers=manifest_headers(env, token, nonce))
        assert response.status_code == 200, response.text
        assert response.headers["cache-control"] == "no-store"
        signed = response.json()["manifest"]
        claims = env.app.state.signer.verify(signed)
        assert claims["request_nonce"] == nonce
        assert claims["device"] == env.enrolled["device_id"]
        assert claims["sub"] == str(env.uid)
        manifests.append((signed, claims))
    assert manifests[0][1]["catalog_sha256"] == manifests[1][1]["catalog_sha256"]
    assert manifests[0][0] != manifests[1][0]
    # An otherwise valid captured response retains the old nonce. Its signature
    # cannot be reused after replacing that value with the new expected nonce.
    assert env.app.state.signer.verify(manifests[0][0])["request_nonce"] != nonces[1]
    parts = manifests[0][0].split(".")
    claims = json.loads(b64d(parts[1]))
    claims["request_nonce"] = nonces[1]
    parts[1] = b64e(canonical_json(claims))
    assert env.app.state.signer.verify(".".join(parts)) is None


@pytest.mark.parametrize("mutation", ["license_revoke", "device_revoke", "expire", "entitlement_remove", "reset"])
def test_fresh_nonce_does_not_bypass_live_license_device_or_entitlement_checks(env, mutation):
    token = grant(env, product="celeste_plus")
    assert env.client.get(PATH, headers=manifest_headers(env, token, secrets.token_urlsafe(24))).status_code == 200
    with env.app.state.database.connect() as connection:
        if mutation == "license_revoke":
            connection.execute("UPDATE licenses SET status='revoked' WHERE uid=?", (env.uid,))
        elif mutation == "device_revoke":
            connection.execute("UPDATE devices SET status='revoked' WHERE device_id=?", (env.enrolled["device_id"],))
        elif mutation == "expire":
            connection.execute("UPDATE licenses SET expires_at=? WHERE uid=?", (int(time.time()) - 1, env.uid))
        elif mutation == "entitlement_remove":
            connection.execute("DELETE FROM license_entitlements WHERE license_uid=? AND product='celeste_plus'", (env.uid,))
        else:
            connection.execute("DELETE FROM devices WHERE device_id=?", (env.enrolled["device_id"],))
    response = env.client.get(PATH, headers=manifest_headers(env, token, secrets.token_urlsafe(24)))
    assert response.status_code == (403 if mutation == "entitlement_remove" else 401), response.text
    assert "manifest" not in response.json()
