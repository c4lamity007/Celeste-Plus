"""Operator configuration stops runtime admission without modifying customer bindings."""
from __future__ import annotations

from dataclasses import replace
from pathlib import Path
import sqlite3

import pytest
from fastapi.testclient import TestClient

from app.beta_delivery import beta_runtime_catalog
from app.config import Settings
from app.main import create_app
from app.security import b64d, b64e
from test_dpop import env, grant, headers
from test_products import heartbeat
from test_protocol import hardware, release


@pytest.mark.parametrize("product", ["celeste", "celeste_plus"])
def test_operator_kill_switch_blocks_session_auth_and_download_but_preserves_activation(env, product):
    assert env.config.runtime_kill_switch is False
    body = {**release(), "product": product, "device_id": env.enrolled["device_id"], "hardware": hardware()}
    if product == "celeste_plus":
        catalog = beta_runtime_catalog(env.config, env.uid)
        body.update(release_id=catalog["release_id"], client_version=catalog["client_version"],
                    client_sha256=catalog["runtime_sha256"], beta_catalog_sha256=catalog["catalog_sha256"])

    def pending():
        response = env.client.post("/v1/auth/start", json=body)
        assert response.status_code == 200, response.text
        challenge = response.json()
        message = b64d(challenge["message"])
        return {"challenge_id": challenge["challenge_id"], "install_signature": b64e(env.install.sign(message)),
                "token_signature": b64e(env.token.sign(message))}

    complete_path = "/v1/auth/complete"
    assert env.client.post(complete_path, json=pending(), headers=headers(env, "POST", complete_path)).status_code == 200
    unconsumed = pending()
    session_path = "/v1/session/check"
    assert env.client.post(session_path, json=heartbeat(body, env.install, env.token),
                           headers=headers(env, "POST", session_path)).status_code == 200
    installer = grant(env, product=product)
    if product == "celeste_plus":
        manifest = env.client.get("/v1/beta/manifest", headers=headers(env, "GET", "/v1/beta/manifest", installer))
        assert manifest.status_code == 200, manifest.text
        signed_manifest = manifest.json()["manifest"]
        artifact = env.app.state.signer.verify(signed_manifest)["artifacts"][0]["url"]
        extra = {"X-Celeste-Beta-Manifest": signed_manifest}
    else:
        artifact = f"/v1/bootstrap/{env.config.bootstrap_id}"
        extra = {}
    assert env.client.get(artifact, headers={**headers(env, "GET", artifact, installer), **extra}).status_code == 200
    with env.app.state.database.connect() as connection:
        license_before = dict(connection.execute("SELECT * FROM licenses WHERE uid=?", (env.uid,)).fetchone())
        device_before = dict(connection.execute("SELECT * FROM devices WHERE device_id=?", (env.enrolled["device_id"],)).fetchone())
    # Recreate the server on the SAME database: an operator setting takes effect
    # without issuing a new license, device enrollment, or customer credential.
    with TestClient(create_app(replace(env.config, runtime_kill_switch=True)), base_url="https://testserver") as stopped:
        response = stopped.post(session_path, json=heartbeat(body, env.install, env.token, nonce="kill-switch-session-0002"),
                                headers=headers(env, "POST", session_path))
        assert response.status_code == 403 and response.json()["detail"] == "runtime temporarily disabled"
        assert stopped.post("/v1/auth/start", json=body).status_code == 403
        assert stopped.post(complete_path, json=unconsumed, headers=headers(env, "POST", complete_path)).status_code == 403
        assert stopped.get(artifact, headers={**headers(env, "GET", artifact, installer), **extra}).status_code == 403
        if product == "celeste_plus":
            assert stopped.get("/v1/beta/manifest", headers=headers(env, "GET", "/v1/beta/manifest", installer)).status_code == 403
    with env.app.state.database.connect() as connection:
        assert dict(connection.execute("SELECT * FROM licenses WHERE uid=?", (env.uid,)).fetchone()) == license_before
        assert dict(connection.execute("SELECT * FROM devices WHERE device_id=?", (env.enrolled["device_id"],)).fetchone()) == device_before
        assert connection.execute("SELECT consumed_at FROM challenges WHERE challenge_id=?", (unconsumed["challenge_id"],)).fetchone()[0] is None
        assert connection.execute("SELECT COUNT(*) FROM revoked_tokens").fetchone()[0] == 0
    with TestClient(create_app(env.config), base_url="https://testserver") as resumed:
        assert resumed.post(session_path, json=heartbeat(body, env.install, env.token, nonce="kill-switch-session-0003"),
                            headers=headers(env, "POST", session_path)).status_code == 200
        assert resumed.get(artifact, headers={**headers(env, "GET", artifact, installer), **extra}).status_code == 200


@pytest.mark.parametrize("value,expected", [(None, False), ("false", False), ("0", False), ("true", True),
                                           ("1", True), (" TRUE ", True), ("", None), ("yes", None), ("7", None)])
def test_kill_switch_environment_is_strict_and_defaults_off(monkeypatch, value, expected):
    for key, setting in {
        "CELESTE_ALLOWED_HOSTS": "testserver", "CELESTE_RELEASE_ID": "fixture", "CELESTE_CLIENT_VERSION": "1",
        "CELESTE_RELEASE_SHA256": "a" * 64, "CELESTE_CHUNK_A_SHA256": "a" * 64, "CELESTE_CHUNK_B_SHA256": "b" * 64,
        "CELESTE_BOOTSTRAP_SHA256": "a" * 64, "CELESTE_LOADER_SHA256": "a" * 64,
        "CELESTE_LOADER_THREE_SHA256": "a" * 64, "CELESTE_LOADER_FOUR_SHA256": "a" * 64,
        "CELESTE_SIGNING_PRIVATE_KEY": b64e(bytes(32)), "CELESTE_KEY_PEPPER": b64e(bytes(32)),
        "CELESTE_SESSION_SECRET": b64e(bytes(32)), "CELESTE_ADMIN_PASSWORD_HASH": "isolated-test",
        "CELESTE_CHUNK_A_ID": "a", "CELESTE_CHUNK_B_ID": "b", "CELESTE_BOOTSTRAP_ID": "b",
        "CELESTE_LOADER_ID": "two", "CELESTE_LOADER_THREE_ID": "three", "CELESTE_LOADER_FOUR_ID": "four",
        "CELESTE_PLUS_ENABLED": "false", "CELESTE_INSTALLER_COMPAT_SHA256": "a" * 64,
        "CELESTE_INSTALLER_ALLOW_RUNTIME_DIGEST": "false",
    }.items():
        monkeypatch.setenv(key, setting)
    monkeypatch.delenv("CELESTE_NATIVE_RELEASE_PUBLIC_KEY", raising=False)
    if value is None:
        monkeypatch.delenv("CELESTE_RUNTIME_KILL_SWITCH", raising=False)
    else:
        monkeypatch.setenv("CELESTE_RUNTIME_KILL_SWITCH", value)
    if expected is None:
        with pytest.raises(RuntimeError, match="CELESTE_RUNTIME_KILL_SWITCH"):
            Settings.from_env()
    else:
        assert Settings.from_env().runtime_kill_switch is expected


def test_revocation_sql_migration_is_idempotent_and_preserves_existing_state(env):
    path = Path(__file__).resolve().parents[1] / "migrations/20260927_revoked_tokens.sql"
    with env.app.state.database.connect() as connection:
        connection.execute("INSERT INTO revoked_tokens VALUES(?,?,?,?,?)", ("fixture-token", "a" * 64, 1, 9999999999, "test"))
        before = list(connection.execute("SELECT * FROM licenses"))
        connection.executescript(path.read_text())
        connection.executescript(path.read_text())
        assert list(connection.execute("SELECT * FROM licenses")) == before
        assert connection.execute("SELECT reason FROM revoked_tokens WHERE jti='fixture-token'").fetchone()[0] == "test"
        assert connection.execute("SELECT 1 FROM sqlite_master WHERE name='native_release_policies'").fetchone()
    with sqlite3.connect(":memory:") as fresh:
        fresh.executescript(path.read_text())
        assert [row[1] for row in fresh.execute("PRAGMA table_info(revoked_tokens)")] == [
            "jti", "token_sha256", "revoked_at", "expires_at", "reason"]
