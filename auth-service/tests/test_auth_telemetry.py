"""Unauthenticated attempts must not replace the device's trusted telemetry."""
from __future__ import annotations

import time

import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519

from app.beta_delivery import beta_runtime_catalog
from app.security import b64d, b64e
from dpop_client import proof
from test_dpop import env
from test_protocol import hardware, release


def device(env):
    with env.app.state.database.connect() as connection:
        return dict(connection.execute(
            "SELECT * FROM devices WHERE device_id=?", (env.enrolled["device_id"],)
        ).fetchone())


@pytest.mark.parametrize("scope,product", [
    ("auth", "celeste"),
    ("auth", "celeste_plus"),
    ("installer", "celeste"),
    ("installer", "celeste_plus"),
    ("stage2", "celeste"),
    ("stage3", "celeste"),
    ("stage4", "celeste"),
])
def test_only_verified_completion_updates_trusted_telemetry(env, monkeypatch, scope, product):
    # Exercise the production proxy-IP path without sharing any real deployment state.
    monkeypatch.setenv("FLY_APP_NAME", "isolated-telemetry-test")
    with env.app.state.database.connect() as connection:
        connection.execute("""
            UPDATE devices SET last_seen_at=101,last_game_uuid='previous-uuid',
                last_game_name='PreviousName',last_call_at=102,last_call_endpoint='/previous',
                last_ip='192.0.2.10',last_integrity_sha256=?,last_integrity_at=103
            WHERE device_id=?
        """, ("c" * 64, env.enrolled["device_id"]))
    trusted = device(env)
    body = {**release(), "device_id": env.enrolled["device_id"],
            "hardware": hardware(), "product": product}
    if scope == "auth" and product == "celeste_plus":
        catalog = beta_runtime_catalog(env.config, env.uid)
        body.update(release_id=catalog["release_id"], client_version=catalog["client_version"],
                    client_sha256=catalog["runtime_sha256"], beta_catalog_sha256=catalog["catalog_sha256"])
    start_path = f"/v1/{scope}/start"
    complete_path = f"/v1/{scope}/complete"
    attempt_headers = {"fly-client-ip": "203.0.113.10"}

    for mismatched in ({**hardware(), "system": "9" * 64}, hardware(monitor="8" * 64)):
        denied = env.client.post(start_path, json={**body, "hardware": mismatched}, headers=attempt_headers)
        assert denied.status_code == 401, denied.text
        assert device(env) == trusted

    start = env.client.post(start_path, json=body, headers=attempt_headers)
    assert start.status_code == 200, start.text
    # Knowing the device ID and HWID is not proof of possession of either key.
    assert device(env) == trusted
    challenge = start.json()
    message = b64d(challenge["message"])
    completion = {"challenge_id": challenge["challenge_id"],
                  "install_signature": b64e(env.install.sign(message)),
                  "token_signature": b64e(env.token.sign(message))}
    attacker = ed25519.Ed25519PrivateKey.generate()
    for signature in ("install_signature", "token_signature"):
        denied = env.client.post(complete_path,
            json={**completion, signature: b64e(attacker.sign(message))},
            headers={**attempt_headers, "DPoP": proof(env.install, "POST", "https://testserver" + complete_path)})
        assert denied.status_code == 401, denied.text
        assert device(env) == trusted

    for invalid_proof in ({}, {"DPoP": proof(attacker, "POST", "https://testserver" + complete_path)}):
        denied = env.client.post(complete_path, json=completion, headers={**attempt_headers, **invalid_proof})
        assert denied.status_code == 401, denied.text
        assert device(env) == trusted

    before = int(time.time())
    accepted = env.client.post(complete_path, json=completion, headers={
        "fly-client-ip": "198.51.100.20",
        "DPoP": proof(env.install, "POST", "https://testserver" + complete_path),
    })
    assert accepted.status_code == 200, accepted.text
    updated = device(env)
    completed_at = updated["last_seen_at"]
    assert before <= completed_at <= int(time.time())
    expected = {**trusted, "last_seen_at": completed_at, "last_game_uuid": body["game_uuid"],
                "last_game_name": body["game_name"], "last_call_at": completed_at,
                "last_call_endpoint": complete_path, "last_ip": "198.51.100.20"}
    if scope == "auth":
        expected.update(last_integrity_sha256=body["client_sha256"], last_integrity_at=completed_at)
    # Scoped installer/loader authentication must preserve the last runtime integrity observation.
    assert updated == expected

    replay = env.client.post(complete_path, json=completion, headers={
        **attempt_headers, "DPoP": proof(env.install, "POST", "https://testserver" + complete_path),
    })
    assert replay.status_code == 401, replay.text
    assert device(env) == updated


@pytest.mark.parametrize("product", ["celeste", "celeste_plus"])
@pytest.mark.parametrize("failure", ["replay", "revoked", "tamper", "bad_signature"])
def test_denied_heartbeat_does_not_replace_trusted_telemetry(env, monkeypatch, product, failure):
    from test_products import heartbeat

    monkeypatch.setenv("FLY_APP_NAME", "isolated-session-telemetry-test")
    body = {**release(), "device_id": env.enrolled["device_id"], "product": product}
    if product == "celeste_plus":
        catalog = beta_runtime_catalog(env.config, env.uid)
        body.update(release_id=catalog["release_id"], client_version=catalog["client_version"],
                    client_sha256=catalog["runtime_sha256"], beta_catalog_sha256=catalog["catalog_sha256"])
    path = "/v1/session/check"

    def post(value, ip):
        return env.client.post(path, json=value, headers={"fly-client-ip": ip,
            "DPoP": proof(env.install, "POST", "https://testserver" + path)})

    good = heartbeat(body, env.install, env.token)
    assert post(good, "192.0.2.10").status_code == 200
    trusted = device(env)
    denied = good if failure == "replay" else heartbeat(body, env.install, env.token, nonce="different-session-nonce-0002")
    if failure == "revoked":
        with env.app.state.database.connect() as connection:
            connection.execute("UPDATE licenses SET status='revoked',revoked_reason='refund' WHERE uid=?", (env.uid,))
    elif failure == "bad_signature":
        denied["token_signature"] = b64e(ed25519.Ed25519PrivateKey.generate().sign(b"forged"))
    elif failure == "tamper":
        denied["observed_sha256"] = "f" * 64
        transcript = (f"celeste-session-v1\n{denied['device_id']}\n{denied['timestamp']}\n{denied['nonce']}\n"
                      f"{denied['client_sha256']}\n{denied['observed_sha256']}\n{denied['event']}\n"
                      f"{denied['game_uuid']}\n{denied['game_name']}")
        if product == "celeste_plus":
            transcript += f"\n{denied['release_id']}\n{denied['client_version']}\n{denied['beta_catalog_sha256']}"
        denied["install_signature"] = b64e(env.install.sign(transcript.encode()))
        denied["token_signature"] = b64e(env.token.sign(transcript.encode()))
    response = post(denied, "203.0.113.99")
    assert response.status_code == 401, response.text
    updated = device(env)
    for field in ("last_seen_at", "last_game_uuid", "last_game_name", "last_call_at", "last_call_endpoint",
                  "last_ip", "last_integrity_sha256", "last_integrity_at"):
        assert updated[field] == trusted[field], field
    if failure == "tamper" and product == "celeste":
        assert updated["status"] == "revoked" and updated["tamper_count"] == trusted["tamper_count"] + 1
        with env.app.state.database.connect() as connection:
            assert connection.execute("SELECT status FROM licenses WHERE uid=?", (env.uid,)).fetchone()[0] == "revoked"
            assert connection.execute("SELECT COUNT(*) FROM security_events WHERE device_id=?", (env.enrolled["device_id"],)).fetchone()[0] > 0
    elif failure == "tamper":
        # Plus denies this legacy report. Punishment requires a fresh second
        # observation under the signed incident policy and warning deadline.
        assert updated["status"] == trusted["status"] == "active"
        assert updated["tamper_count"] == trusted["tamper_count"]
        with env.app.state.database.connect() as connection:
            assert connection.execute("SELECT status FROM licenses WHERE uid=?", (env.uid,)).fetchone()[0] == "active"
