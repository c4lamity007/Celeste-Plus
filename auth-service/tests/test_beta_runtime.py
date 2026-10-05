"""Beta runtime protocol integration uses only local fixture enrollments and keys."""
from __future__ import annotations

import base64
import hashlib
import json
import time
from dataclasses import replace
from types import SimpleNamespace

import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519
from dpop_client import TestClient

import app.main as main
from app.beta_delivery import beta_runtime_catalog
from app.main import create_app
from app.security import b64d, b64e, hash_license
from test_beta_delivery import beta, write_catalog
from test_protocol import enroll, hardware, release


@pytest.fixture
def runtime(beta):
    identities = {}
    for uid in (7, 8):
        key = f"CST-BETATEST-RUNTIME-LOCAL-OWNER-{uid}77777"
        with beta.app.state.database.connect() as connection:
            connection.execute("DELETE FROM devices WHERE license_uid=?", (uid,))
            connection.execute("UPDATE licenses SET key_hash=? WHERE uid=?", (hash_license(beta.config.key_pepper, key), uid))
        enrolled, install, token = enroll(beta.client, key)
        identities[uid] = SimpleNamespace(device=enrolled["device_id"], install=install, token=token)
    catalog = beta_runtime_catalog(beta.config, 7)
    body = {**release(), "release_id": catalog["release_id"], "client_version": catalog["client_version"],
            "client_sha256": catalog["runtime_sha256"], "beta_catalog_sha256": catalog["catalog_sha256"],
            "device_id": identities[7].device, "hardware": hardware(), "product": "celeste_plus"}
    return SimpleNamespace(beta=beta, identities=identities, catalog=catalog, body=body)


def begin(runtime, **updates):
    return runtime.beta.client.post("/v1/auth/start", json={**runtime.body, **updates})


def completion(runtime, start, uid=7):
    identity = runtime.identities[uid]
    challenge = start.json()
    message = b64d(challenge["message"])
    return {"challenge_id": challenge["challenge_id"], "install_signature": b64e(identity.install.sign(message)),
            "token_signature": b64e(identity.token.sign(message))}


def authenticate(runtime):
    start = begin(runtime)
    assert start.status_code == 200, start.text
    message = json.loads(b64d(start.json()["message"]))
    assert message["scope"] == "beta:" + runtime.catalog["catalog_sha256"]
    assert message["release"] == runtime.catalog["release_id"]
    assert message["client_sha256"] == runtime.catalog["runtime_sha256"]
    result = runtime.beta.client.post("/v1/auth/complete", json=completion(runtime, start))
    assert result.status_code == 200, result.text
    return result.json()


def heartbeat(runtime, uid=7, suffix=True, **updates):
    identity = runtime.identities[uid]
    body = {key: value for key, value in runtime.body.items() if key != "hardware"}
    body.update(device_id=identity.device, timestamp=int(time.time()), nonce="beta-heartbeat-nonce-0001",
                observed_sha256=runtime.catalog["runtime_sha256"], event="heartbeat")
    body.update(updates)
    # Independent client-side transcript construction, not the service's helper.
    message = ("celeste-session-v1\n"
               f"{body['device_id']}\n{body['timestamp']}\n{body['nonce']}\n"
               f"{body['client_sha256']}\n{body['observed_sha256']}\n{body['event']}\n"
               f"{body['game_uuid']}\n{body['game_name']}")
    if suffix and body.get("beta_catalog_sha256") is not None:
        message += f"\n{body['release_id']}\n{body['client_version']}\n{body['beta_catalog_sha256']}"
    body.update(install_signature=b64e(identity.install.sign(message.encode())),
                token_signature=b64e(identity.token.sign(message.encode())))
    return body


def assert_not_revoked(runtime, uid=7):
    with runtime.beta.app.state.database.connect() as connection:
        license_row = connection.execute("SELECT status,revoked_reason FROM licenses WHERE uid=?", (uid,)).fetchone()
        device = connection.execute("SELECT status,tamper_count FROM devices WHERE license_uid=?", (uid,)).fetchone()
        assert tuple(license_row) == ("active", "")
        assert tuple(device) == ("active", 0)


def test_beta_owner_auth_and_signed_heartbeat_use_runtime_identity(runtime):
    authenticated = authenticate(runtime)
    assert set(authenticated) == {"grant", "uid", "expires_at", "runtime"}
    claims = runtime.beta.app.state.signer.verify(authenticated["grant"])
    assert claims["aud"] == "celeste-client"
    assert claims["sub"] == "7"
    assert claims["release"] == runtime.catalog["release_id"]
    assert claims["client_sha256"] == runtime.catalog["runtime_sha256"]
    assert claims["client_version"] == runtime.catalog["client_version"]
    assert claims["beta_catalog_sha256"] == runtime.catalog["catalog_sha256"]
    assert not {"stage2", "stage2_sha256", "chunks"}.intersection(claims)
    assert authenticated["runtime"] == {"release": claims["release"], "sha256": claims["client_sha256"],
                                       "client_version": claims["client_version"], "catalog_sha256": claims["beta_catalog_sha256"]}
    body = heartbeat(runtime)
    response = runtime.beta.client.post("/v1/session/check", json=body)
    assert response.status_code == 200, response.text
    session = runtime.beta.app.state.signer.verify(response.json()["session_token"])
    assert session["aud"] == "celeste-session"
    for field in ("release", "client_sha256", "client_version", "beta_catalog_sha256", "device", "sub"):
        assert session[field] == claims[field]
    assert session["nonce"] == body["nonce"]
    assert runtime.beta.client.post("/v1/session/check", json=body).status_code == 401
    assert_not_revoked(runtime)
    for token in (authenticated["grant"], response.json()["session_token"]):
        headers = {"Authorization": f"Bearer {token}"}
        assert runtime.beta.client.get(f"/v1/blob/{runtime.beta.config.chunk_a_id}", headers=headers).status_code == 401
        assert runtime.beta.client.get(f"/v1/loader/{runtime.beta.config.loader_id}", headers=headers).status_code == 401
        assert runtime.beta.client.get(f"/v1/bootstrap/{runtime.beta.config.bootstrap_id}", headers=headers).status_code == 401


@pytest.mark.parametrize("changes", [
    {"release_id": "unknown-beta"}, {"client_version": "other-version"}, {"client_sha256": "e" * 64},
    {"beta_catalog_sha256": "0" * 64}, {"beta_catalog_sha256": None},
])
def test_unknown_or_unpinned_runtime_rejected_without_tamper_mutation(runtime, changes):
    assert begin(runtime, **changes).status_code == 409
    body = heartbeat(runtime, event="integrity_mismatch", observed_sha256="f" * 64, **changes)
    assert runtime.beta.client.post("/v1/session/check", json=body).status_code == 409
    assert_not_revoked(runtime)
    with runtime.beta.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM session_nonces").fetchone()[0] == 0


def test_nonowner_with_valid_dual_proofs_never_gets_beta_or_tamper_mutation(runtime):
    assert begin(runtime, device_id=runtime.identities[8].device).status_code == 403
    body = heartbeat(runtime, uid=8, event="integrity_mismatch", observed_sha256="f" * 64)
    assert runtime.beta.client.post("/v1/session/check", json=body).status_code == 403
    assert_not_revoked(runtime, 8)


def test_disabled_beta_preserves_production_auth_and_rejects_beta_context(runtime):
    config = replace(runtime.beta.config, plus_enabled=False, beta_release_dir="")
    with TestClient(create_app(config), base_url="https://testserver") as client:
        assert client.post("/v1/auth/start", json=runtime.body).status_code == 409
        assert client.post("/v1/session/check", json=heartbeat(runtime, event="integrity_mismatch")).status_code == 409
        prod_start = client.post("/v1/auth/start", json={**release(), "device_id": runtime.identities[7].device, "hardware": hardware()})
        assert prod_start.status_code == 200
        completed = client.post("/v1/auth/complete", json=completion(runtime, prod_start))
        assert completed.status_code == 200
        assert set(completed.json()) == {"grant", "uid", "expires_at", "artifact", "loader"}
        grant = runtime.beta.app.state.signer.verify(completed.json()["grant"])
        assert "beta_catalog_sha256" not in grant
        assert grant["release"] == config.release_id
    assert_not_revoked(runtime)


@pytest.mark.parametrize("change", ["hardware", "monitor"])
def test_beta_keeps_hardware_and_monitor_binding(runtime, change):
    profile = hardware()
    if change == "hardware": profile["board"] = "e" * 64
    else: profile["monitors"] = ["e" * 64]
    assert begin(runtime, hardware=profile).status_code == 401
    assert_not_revoked(runtime)


@pytest.mark.parametrize("change", ["missing_token", "wrong_install", "wrong_token"])
def test_beta_complete_keeps_dual_proof_and_one_time_challenge(runtime, change):
    start = begin(runtime)
    assert start.status_code == 200
    body = completion(runtime, start)
    original = dict(body)
    if change == "missing_token": body.pop("token_signature")
    else:
        field = "install_signature" if change == "wrong_install" else "token_signature"
        body[field] = b64e(ed25519.Ed25519PrivateKey.generate().sign(b"not the signed challenge"))
    assert runtime.beta.client.post("/v1/auth/complete", json=body).status_code == 401
    assert runtime.beta.client.post("/v1/auth/complete", json=original).status_code == 200
    assert runtime.beta.client.post("/v1/auth/complete", json=original).status_code == 401
    assert_not_revoked(runtime)


@pytest.mark.parametrize("change", ["revoke", "expire", "device_revoke", "reassign", "challenge_expire"])
def test_beta_complete_rechecks_live_owner_and_expiry(runtime, change):
    start = begin(runtime)
    assert start.status_code == 200
    with runtime.beta.app.state.database.connect() as connection:
        if change == "revoke": connection.execute("UPDATE licenses SET status='revoked' WHERE uid=7")
        elif change == "expire": connection.execute("UPDATE licenses SET expires_at=1 WHERE uid=7")
        elif change == "device_revoke": connection.execute("UPDATE devices SET status='revoked' WHERE license_uid=7")
        elif change == "reassign": connection.execute("UPDATE devices SET license_uid=8 WHERE device_id=?", (runtime.identities[7].device,))
        else: connection.execute("UPDATE challenges SET expires_at=1 WHERE challenge_id=?", (start.json()["challenge_id"],))
    assert runtime.beta.client.post("/v1/auth/complete", json=completion(runtime, start)).status_code == 401
    if change != "challenge_expire":
        assert runtime.beta.client.post("/v1/session/check", json=heartbeat(runtime, event="integrity_mismatch")).status_code in {401, 403}
    with runtime.beta.app.state.database.connect() as connection:
        assert connection.execute("SELECT SUM(tamper_count) FROM devices").fetchone()[0] == 0


@pytest.mark.parametrize("change", ["release", "version", "runtime_digest", "artifact", "invalid_artifact", "disable"])
def test_stale_catalog_rejected_at_complete_session_and_cloud_without_revocation(runtime, change):
    authenticated = authenticate(runtime)
    start = begin(runtime)
    assert start.status_code == 200
    old_body = heartbeat(runtime, event="integrity_mismatch", observed_sha256="f" * 64)
    catalog = runtime.beta.catalog
    if change == "release": catalog["release_id"] = "beta-next-release"
    elif change == "version": catalog["client_version"] = "1.0.0-beta.2"
    elif change == "runtime_digest": catalog["runtime_sha256"] = "e" * 64
    elif change == "artifact":
        entry = catalog["artifacts"][0]
        data = b"different valid bootstrap bytes"
        (runtime.beta.root / entry["filename"]).write_bytes(data)
        entry.update(size=len(data), sha256=hashlib.sha256(data).hexdigest())
    elif change == "invalid_artifact": (runtime.beta.root / "payload-a.bin").write_bytes(b"bad bytes")
    write_catalog(runtime.beta.root, catalog)
    app = runtime.beta.app if change != "disable" else create_app(replace(runtime.beta.config, plus_enabled=False))
    with TestClient(app, base_url="https://testserver") as client:
        assert client.post("/v1/auth/complete", json=completion(runtime, start)).status_code in {409, 503}
        assert client.post("/v1/session/check", json=old_body).status_code in {409, 503}
        headers = {"Authorization": f"Bearer {authenticated['grant']}"}
        assert client.get("/v1/configs", headers=headers).status_code in {401, 409, 503}
        assert client.post("/v1/configs", headers=headers, json=cloud_body()).status_code in {401, 409, 503}
    assert_not_revoked(runtime)
    with runtime.beta.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM session_nonces").fetchone()[0] == 0
        assert connection.execute("SELECT COUNT(*) FROM cloud_configs").fetchone()[0] == 0


@pytest.mark.parametrize("change", ["wrong_install", "wrong_token", "no_signed_suffix", "old_timestamp"])
def test_beta_session_dual_signatures_and_freshness_stay_required(runtime, change):
    body = heartbeat(runtime, suffix=change != "no_signed_suffix", event="integrity_mismatch", observed_sha256="f" * 64)
    if change == "old_timestamp": body = heartbeat(runtime, timestamp=1)
    elif change.startswith("wrong_"):
        field = "install_signature" if change == "wrong_install" else "token_signature"
        body[field] = b64e(ed25519.Ed25519PrivateKey.generate().sign(b"different message"))
    assert runtime.beta.client.post("/v1/session/check", json=body).status_code == 401
    assert_not_revoked(runtime)
    with runtime.beta.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM session_nonces").fetchone()[0] == 0


@pytest.mark.parametrize("event,observed", [("integrity_mismatch", "d" * 64), ("heartbeat", "f" * 64)])
def test_current_beta_integrity_violation_still_revokes_owner(runtime, event, observed):
    body = heartbeat(runtime, event=event, observed_sha256=observed)
    assert runtime.beta.client.post("/v1/session/check", json=body).status_code == 401
    with runtime.beta.app.state.database.connect() as connection:
        assert connection.execute("SELECT status FROM licenses WHERE uid=7").fetchone()[0] == "revoked"
        device = connection.execute("SELECT status,tamper_count FROM devices WHERE license_uid=7").fetchone()
        assert tuple(device) == ("revoked", 1)
    assert_not_revoked(runtime, 8)


def cloud_body():
    image = base64.b64decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=")
    return {"name": "Local beta config", "image": b64e(image), "modules": ["HUD"]}


def test_beta_client_and_session_grants_allow_same_owner_cloud_operations(runtime):
    authenticated = authenticate(runtime)
    headers = {"Authorization": f"Bearer {authenticated['grant']}"}
    published = runtime.beta.client.post("/v1/configs", headers=headers, json=cloud_body())
    assert published.status_code == 200
    session = runtime.beta.client.post("/v1/session/check", json=heartbeat(runtime))
    assert session.status_code == 200
    headers = {"Authorization": f"Bearer {session.json()['session_token']}"}
    listing = runtime.beta.client.get("/v1/configs", headers=headers)
    assert listing.status_code == 200
    assert listing.json()["configs"][0]["owned"] is True
    image = runtime.beta.client.get(f"/v1/configs/{published.json()['id']}/image", headers=headers)
    assert image.status_code == 200
    with runtime.beta.app.state.database.connect() as connection:
        connection.execute("UPDATE licenses SET status='revoked' WHERE uid=7")
    assert runtime.beta.client.get("/v1/configs", headers=headers).status_code == 401
    assert runtime.beta.client.post("/v1/configs", headers=headers, json=cloud_body()).status_code == 401


def test_beta_auth_scope_cannot_cross_into_installer_or_stage_authorization(runtime):
    start = begin(runtime)
    assert start.status_code == 200
    body = completion(runtime, start)
    for scope in ("installer", "stage2", "stage3", "stage4"):
        assert runtime.beta.client.post(f"/v1/{scope}/complete", json=body).status_code == 401
        assert runtime.beta.client.post(f"/v1/{scope}/start", json=runtime.body).status_code == 409
    assert runtime.beta.client.post("/v1/auth/complete", json=body).status_code == 200


@pytest.mark.parametrize("changes", [
    {"runtime_sha256": "a" * 64}, {"runtime_sha256": "wrong"}, {"runtime_sha256": "D" * 64},
    {"client_version": ""}, {"client_version": "spaces are invalid"}, {"client_version": "x" * 41},
    {"schema": "celeste-beta-release-1"},
])
def test_invalid_v2_runtime_metadata_is_fail_closed(runtime, changes):
    runtime.beta.catalog.update(changes)
    write_catalog(runtime.beta.root, runtime.beta.catalog)
    assert begin(runtime).status_code == 503
    assert runtime.beta.client.post("/v1/session/check", json=heartbeat(runtime, event="integrity_mismatch")).status_code == 503
    assert_not_revoked(runtime)


@pytest.mark.parametrize("change", ["nonowner", "mismatched_device", "noncanonical_subject", "expired_grant"])
def test_beta_cloud_grants_remain_bound_to_live_owner_subject(runtime, change):
    claims = runtime.beta.app.state.signer.verify(authenticate(runtime)["grant"])
    if change == "nonowner": claims.update(sub="8", device=runtime.identities[8].device)
    elif change == "mismatched_device": claims["device"] = runtime.identities[8].device
    elif change == "noncanonical_subject": claims["sub"] = "007"
    else: claims["exp"] = 1
    grant = runtime.beta.app.state.signer.sign(claims)
    headers = {"Authorization": f"Bearer {grant}"}
    assert runtime.beta.client.get("/v1/configs", headers=headers).status_code == (403 if change == "nonowner" else 401)
    assert runtime.beta.client.post("/v1/configs", headers=headers, json=cloud_body()).status_code == (403 if change == "nonowner" else 401)
    assert_not_revoked(runtime)
    assert_not_revoked(runtime, 8)


@pytest.mark.parametrize("change", ["revocation", "grant_expiry"])
def test_cloud_rechecks_authorization_after_catalog_validation(runtime, monkeypatch, change):
    authenticated = authenticate(runtime)
    headers = {"Authorization": f"Bearer {authenticated['grant']}"}
    original = main.beta_runtime_catalog
    future = authenticated["expires_at"] + 1

    def change_after_validation(*args, **kwargs):
        result = original(*args, **kwargs)
        if change == "revocation":
            with runtime.beta.app.state.database.connect() as connection:
                connection.execute("UPDATE licenses SET status='revoked' WHERE uid=7")
        else:
            monkeypatch.setattr(main.time, "time", lambda: future)
        return result

    monkeypatch.setattr(main, "beta_runtime_catalog", change_after_validation)
    assert runtime.beta.client.post("/v1/configs", headers=headers, json=cloud_body()).status_code == 401
    with runtime.beta.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM cloud_configs").fetchone()[0] == 0
