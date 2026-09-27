"""Real HTTP endpoints with disposable keys/artifacts/SQLite; no client auto-proof.

Adversarial requests use FastAPI's plain TestClient, never the regression adapter.
"""
from __future__ import annotations

import hashlib
import json
import secrets
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from types import SimpleNamespace

import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from fastapi.testclient import TestClient

from app.dpop import confirmation
from app.main import create_app
from app.security import b64d, b64e
from dpop_client import proof
from test_beta_delivery import stage_beta
from test_products import heartbeat
from test_protocol import settings, create_license, enroll, release, hardware, raw_public


@pytest.fixture
def env(tmp_path):
    beta_root = tmp_path / "beta"
    stage_beta(beta_root)
    config = replace(settings(tmp_path), plus_enabled=True, beta_release_dir=str(beta_root))
    app = create_app(config)
    key = "CST-DPOP-ISOLATED-LOCAL-TEST-123456789"
    uid = create_license(config, key)
    with app.state.database.connect() as connection:
        connection.execute("INSERT INTO license_entitlements VALUES(?,'celeste_plus',1)", (uid,))
    with TestClient(app, base_url="https://testserver") as client:
        enrolled, install, token = enroll(client, key)
        yield SimpleNamespace(config=config, app=app, client=client, enrolled=enrolled, install=install,
                              token=token, uid=uid, license_key=key)


def headers(env, method, path, token=None, **kwargs):
    result = {"DPoP": proof(env.install, method, "https://testserver" + path, token, **kwargs)}
    if method == "GET" and path == "/v1/beta/manifest":
        result["X-Celeste-Request-Nonce"] = secrets.token_urlsafe(24)
    if token is not None:
        result["Authorization"] = "DPoP " + token
    return result


def pending(env, scope, product="celeste"):
    result = env.client.post(f"/v1/{scope}/start", json={**release(), "product": product,
        "device_id": env.enrolled["device_id"], "hardware": hardware()})
    assert result.status_code == 200, result.text
    challenge = result.json()
    message = b64d(challenge["message"])
    return {"challenge_id": challenge["challenge_id"], "install_signature": b64e(env.install.sign(message)),
            "token_signature": b64e(env.token.sign(message))}


def grant(env, scope="installer", product="celeste"):
    path = f"/v1/{scope}/complete"
    result = env.client.post(path, json=pending(env, scope, product), headers=headers(env, "POST", path))
    assert result.status_code == 200, result.text
    token = result.json()["grant"]
    assert env.app.state.signer.verify(token)["cnf"] == confirmation(b64d(raw_public(env.install)))
    return token


def test_all_protected_routes_require_fresh_token_bound_proof(env):
    installer = grant(env)
    client_grant = grant(env, "auth")
    second = grant(env, "stage2")
    third = grant(env, "stage3")
    fourth = grant(env, "stage4")
    plus = grant(env, product="celeste_plus")
    routes = [
        (f"/v1/bootstrap/{env.config.bootstrap_id}", installer),
        (f"/v1/loader/{env.config.loader_id}", client_grant),
        (f"/v1/loader/{env.config.loader_three_id}", second),
        (f"/v1/loader/{env.config.loader_four_id}", third),
        (f"/v1/blob/{env.config.chunk_a_id}", fourth),
        (f"/v1/blob/{env.config.chunk_b_id}", fourth),
        ("/v1/configs", client_grant),
        ("/v1/beta/manifest", plus),
    ]
    manifest = None
    for path, token in routes:
        assert env.client.get(path, headers={"Authorization": "DPoP " + token}).status_code == 401
        valid = headers(env, "GET", path, token)
        bearer = {**valid, "Authorization": "Bearer " + token}
        assert env.client.get(path, headers=bearer).status_code == 401
        result = env.client.get(path, headers=valid)
        assert result.status_code == 200, result.text
        if path == "/v1/beta/manifest":
            manifest = result.json()["manifest"]
        assert env.client.get(path, headers=valid).status_code == 401
    claims = env.app.state.signer.verify(manifest)
    assert claims["cnf"] == env.app.state.signer.verify(plus)["cnf"]
    for artifact in claims["artifacts"]:
        path = artifact["url"]
        base = {"Authorization": "DPoP " + plus, "X-Celeste-Beta-Manifest": manifest}
        assert env.client.get(path, headers=base).status_code == 401
        valid = {**base, **headers(env, "GET", path, plus)}
        result = env.client.get(path, headers=valid)
        assert result.status_code == 200, result.text
        assert hashlib.sha256(result.content).hexdigest() == artifact["sha256"]
        assert env.client.get(path, headers=valid).status_code == 401
    path = "/v1/installer/consume"
    assert env.client.post(path, json={}, headers={"Authorization": "Bearer " + installer}).status_code == 401
    assert env.client.post(path, json={}, headers={"Authorization": "DPoP " + installer}).status_code == 401
    valid = headers(env, "POST", path, installer)
    assert env.client.post(path, json={}, headers=valid).status_code == 200
    assert env.client.post(path, json={}, headers=valid).status_code == 401


@pytest.mark.parametrize("scope", ["auth", "installer", "stage2", "stage3", "stage4"])
def test_completion_cannot_issue_unbound_grants(env, scope):
    path = f"/v1/{scope}/complete"
    body = pending(env, scope)
    assert env.client.post(path, json=body).status_code == 401
    other = ed25519.Ed25519PrivateKey.generate()
    assert env.client.post(path, json=body, headers={"DPoP": proof(other, "POST", "https://testserver" + path)}).status_code == 401
    assert env.client.post(path, json=body, headers=headers(env, "GET", path)).status_code == 401
    assert env.client.post(path, json=body, headers=headers(env, "POST", path, "not-an-access-token")).status_code == 401
    valid = headers(env, "POST", path)
    result = env.client.post(path, json=body, headers=valid)
    assert result.status_code == 200, result.text
    assert env.app.state.signer.verify(result.json()["grant"])["cnf"] == confirmation(b64d(raw_public(env.install)))
    assert env.client.post(path, json=body, headers=valid).status_code == 401


def test_enrollment_requires_matching_installation_proof(tmp_path):
    config = settings(tmp_path)
    app = create_app(config)
    key = "CST-DPOP-ENROLL-LOCAL-TEST-123456789"
    create_license(config, key)
    install = ed25519.Ed25519PrivateKey.generate()
    with TestClient(app, base_url="https://testserver") as client:
        start = client.post("/v1/enroll/start", json={**release(), "license_key": key, "hardware": hardware(),
            "install_signing_key": raw_public(install), "install_encryption_key": raw_public(x25519.X25519PrivateKey.generate())})
        assert start.status_code == 200
        body = {"challenge_id": start.json()["challenge_id"], "install_signature": b64e(install.sign(b64d(start.json()["message"])))}
        assert client.post("/v1/enroll/complete", json=body).status_code == 401
        wrong = ed25519.Ed25519PrivateKey.generate()
        assert client.post("/v1/enroll/complete", json=body, headers={"DPoP": proof(wrong, "POST", "https://testserver/v1/enroll/complete")}).status_code == 401
        result = client.post("/v1/enroll/complete", json=body, headers={"DPoP": proof(install, "POST", "https://testserver/v1/enroll/complete")})
        assert result.status_code == 200
        assert app.state.signer.verify(result.json()["device_certificate"])["cnf"] == confirmation(b64d(raw_public(install)))


def test_heartbeat_proof_and_new_session_binding(env):
    path = "/v1/session/check"
    body = heartbeat({**release(), "device_id": env.enrolled["device_id"]}, env.install, env.token)
    assert env.client.post(path, json=body).status_code == 401
    assert env.client.post(path, json=body, headers=headers(env, "POST", path, "unexpected-token")).status_code == 401
    result = env.client.post(path, json=body, headers=headers(env, "POST", path))
    assert result.status_code == 200, result.text
    token = result.json()["session_token"]
    assert env.app.state.signer.verify(token)["cnf"] == confirmation(b64d(raw_public(env.install)))
    assert env.client.get("/v1/configs", headers={"Authorization": "Bearer " + token}).status_code == 401
    assert env.client.get("/v1/configs", headers=headers(env, "GET", "/v1/configs", token)).status_code == 200


def test_invalid_request_proofs_are_rejected_without_consuming_valid_proof(env):
    token = grant(env)
    path = f"/v1/bootstrap/{env.config.bootstrap_id}"
    now = int(time.time())
    invalid = [
        {"htm": "POST"}, {"htm": "get"}, {"htu": "https://evil.invalid" + path},
        {"htu": "http://testserver" + path}, {"htu": "https://testserver/v1/bootstrap/other"},
        {"htu": "https://testserver" + path + "?q=1"}, {"htu": "https://testserver" + path + "#fragment"},
        {"iat": now - 61}, {"iat": now + 10}, {"iat": str(now)}, {"iat": True},
        {"jti": "tiny"}, {"jti": "x" * 129}, {"jti": "!" * 32}, {"ath": "wrong"}, {"ath": None},
    ]
    for change in invalid:
        result = env.client.get(path, headers=headers(env, "GET", path, token, payload_updates=change))
        assert result.status_code == 401, change
        assert result.headers["www-authenticate"].startswith("DPoP")
    for change in ({"typ": "JWT"}, {"alg": "none"}, {"alg": "HS256"}, {"crit": ["unknown"]},
                   {"jwk": {"kty": "OKP", "crv": "Ed25519", "x": raw_public(env.install), "d": "private"}}):
        assert env.client.get(path, headers=headers(env, "GET", path, token, header_updates=change)).status_code == 401
    other = ed25519.Ed25519PrivateKey.generate()
    wrong = {"Authorization": "DPoP " + token, "DPoP": proof(other, "GET", "https://testserver" + path, token)}
    assert env.client.get(path, headers=wrong).status_code == 401
    valid = headers(env, "GET", path, token)
    first, second, sig = valid["DPoP"].split(".")
    forged = {**valid, "DPoP": f"{first}.{second}.{b64e(bytes(64))}"}
    assert env.client.get(path, headers=forged).status_code == 401
    assert env.client.get(path + "?q=ignored", headers=valid).status_code == 200


def test_duplicate_malformed_and_oversized_proof_headers_fail_closed(env):
    token = grant(env)
    path = f"/v1/bootstrap/{env.config.bootstrap_id}"
    valid = headers(env, "GET", path, token)
    for value in ["", "a.b.c", "[]", "x" * 4097, valid["DPoP"] + "=", valid["DPoP"] + "," + valid["DPoP"]]:
        assert env.client.get(path, headers={**valid, "DPoP": value}).status_code == 401
    duplicates = [("Authorization", "DPoP " + token), ("DPoP", valid["DPoP"]), ("DPoP", valid["DPoP"])]
    assert env.client.get(path, headers=duplicates).status_code == 401
    first, second, third = valid["DPoP"].split(".")
    raw = b'{"typ":"dpop+jwt","typ":"dpop+jwt","alg":"EdDSA","jwk":' + json.dumps({"kty":"OKP","crv":"Ed25519","x":raw_public(env.install)}).encode() + b'}'
    first = b64e(raw)
    duplicate = f"{first}.{second}.{b64e(env.install.sign(f'{first}.{second}'.encode()))}"
    assert env.client.get(path, headers={**valid, "DPoP": duplicate}).status_code == 401
    assert env.client.get(path, headers=valid).status_code == 200


def test_access_token_and_confirmation_cannot_be_swapped(env):
    token = grant(env)
    other_token = grant(env)
    path = f"/v1/bootstrap/{env.config.bootstrap_id}"
    valid = headers(env, "GET", path, token)
    assert env.client.get(path, headers={**valid, "Authorization": "DPoP " + other_token}).status_code == 401
    claims = env.app.state.signer.verify(token)
    for cnf in (None, {}, {"jkt": "wrong"}):
        modified = {**claims, "cnf": cnf}
        swapped = env.app.state.signer.sign(modified)
        assert env.client.get(path, headers=headers(env, "GET", path, swapped)).status_code == 401
    assert env.client.get(path, headers=valid).status_code == 200


def test_replay_is_atomic_across_app_instances_and_restart(env):
    token = grant(env)
    path = f"/v1/bootstrap/{env.config.bootstrap_id}"
    valid = headers(env, "GET", path, token)
    apps = [create_app(env.config), create_app(env.config)]
    def fetch(app):
        with TestClient(app, base_url="https://testserver") as client:
            return client.get(path, headers=valid).status_code
    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(fetch, apps)) == [200, 401]
    assert fetch(create_app(env.config)) == 401
    assert env.client.get(path, headers=headers(env, "GET", path, token)).status_code == 200


@pytest.mark.parametrize("change", ["device", "license", "expiry", "entitlement"])
def test_live_revocation_still_blocks_valid_proof(env, change):
    token = grant(env)
    path = f"/v1/bootstrap/{env.config.bootstrap_id}"
    with env.app.state.database.connect() as connection:
        if change == "device":
            connection.execute("UPDATE devices SET status='revoked'")
        elif change == "license":
            connection.execute("UPDATE licenses SET status='revoked'")
        elif change == "expiry":
            connection.execute("UPDATE licenses SET expires_at=?", (int(time.time()) - 1,))
        else:
            connection.execute("DELETE FROM license_entitlements WHERE product='celeste'")
    assert env.client.get(path, headers=headers(env, "GET", path, token)).status_code in {401, 403}


def test_trusted_external_origin_ignores_proxy_host_and_scheme(env):
    token = grant(env)
    path = f"/v1/bootstrap/{env.config.bootstrap_id}"
    app = create_app(replace(env.config, public_origin="https://public.example"))
    with TestClient(app, base_url="http://testserver") as client:
        public_proof = proof(env.install, "GET", "https://public.example" + path, token)
        result = client.get(path, headers={"Authorization": "DPoP " + token, "DPoP": public_proof,
            "X-Forwarded-Host": "evil.invalid", "X-Forwarded-Proto": "http", "Forwarded": "host=evil.invalid;proto=http"})
        assert result.status_code == 200
        assert client.get(path, headers=headers(env, "GET", path, token)).status_code == 401
