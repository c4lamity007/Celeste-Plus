"""Real HTTP resource gates, disposable SQLite and installation keys only."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import json
import logging
import time

import pytest
from fastapi import FastAPI
from fastapi.testclient import TestClient

import app.beta_delivery as beta_delivery
from app.api_responses import ApiResponseMiddleware
from app.database import Database
from app.main import create_app
from app.token_revocation import revoke_access_token
from test_dpop import env, grant, headers


def bootstrap(env):
    return f"/v1/bootstrap/{env.config.bootstrap_id}"


def test_api_errors_have_unique_trace_id_and_preserve_detail_and_challenge(env, caplog):
    caplog.set_level(logging.INFO, logger="celeste.requests")
    response = env.client.get(bootstrap(env), headers={"Authorization": "Bearer private-test-token", "X-Request-Id": "forged"})
    assert response.status_code == 401
    payload = response.json()
    assert set(payload) == {"detail", "request_id", "error_code", "message", "retry_after_seconds"}
    assert payload["request_id"] == response.headers["X-Request-Id"] != "forged"
    assert len(payload["request_id"]) == 32
    assert payload["message"] == payload["detail"]
    assert payload["error_code"] == "unauthorized" and payload["retry_after_seconds"] == 300
    assert "DPoP" in response.headers["WWW-Authenticate"]
    second = env.client.get("/v1/does-not-exist")
    assert second.status_code == 404 and second.json()["error_code"] == "not_found"
    assert second.headers["X-Request-Id"] != payload["request_id"]
    records = [json.loads(record.message) for record in caplog.records if record.name == "celeste.requests"]
    assert any(record["request_id"] == payload["request_id"] and record["status"] == 401 for record in records)
    assert "private-test-token" not in caplog.text


@pytest.mark.parametrize("kind,status", [("validation", 422), ("body", 413), ("media", 415)])
def test_api_envelope_covers_request_middleware_and_validation(env, kind, status):
    if kind == "validation":
        response = env.client.post("/v1/auth/start", json={})
        assert isinstance(response.json()["detail"], list)
    else:
        response = env.client.post("/v1/auth/start", content="x" * (17000 if kind == "body" else 1),
                                   headers={"Content-Type": "application/json" if kind == "body" else "text/plain"})
    assert response.status_code == status
    assert response.json()["request_id"] == response.headers["X-Request-Id"]
    assert isinstance(response.json()["retry_after_seconds"], int)


def test_unexpected_api_failure_is_traced_without_leaking_exception(env, caplog):
    @env.app.get("/v1/local-test-failure")
    def failure():
        raise RuntimeError("private-test-secret")
    caplog.set_level(logging.INFO, logger="celeste.requests")
    response = env.client.get("/v1/local-test-failure")
    assert response.status_code == 500
    assert response.json()["error_code"] == "internal_error"
    assert response.json()["request_id"] == response.headers["X-Request-Id"]
    assert "private-test-secret" not in response.text + caplog.text


@pytest.mark.parametrize("scheme,fly,hsts", [("https", False, True), ("http", True, True), ("http", False, False)])
def test_synthesized_api_error_sets_its_own_security_headers(monkeypatch, caplog, scheme, fly, hsts):
    if fly:
        monkeypatch.setenv("FLY_APP_NAME", "isolated-test")
    else:
        monkeypatch.delenv("FLY_APP_NAME", raising=False)
    app = FastAPI()
    app.add_middleware(ApiResponseMiddleware)

    @app.get("/v1/local-test-failure")
    def failure():
        raise RuntimeError("private-test-secret")

    caplog.set_level(logging.INFO, logger="celeste.requests")
    with TestClient(app, base_url=f"{scheme}://testserver") as client:
        response = client.get("/v1/local-test-failure")
    assert response.status_code == 500
    assert response.headers["X-Content-Type-Options"] == "nosniff"
    assert response.headers["Cache-Control"] == "no-store"
    assert response.json()["request_id"] == response.headers["X-Request-Id"]
    if hsts:
        assert response.headers["Strict-Transport-Security"] == "max-age=31536000; includeSubDomains"
    else:
        assert "Strict-Transport-Security" not in response.headers
    assert "private-test-secret" not in response.text + caplog.text


def test_artifact_token_budget_is_ten_total_and_rejection_keeps_success_bytes_private(env):
    token = grant(env)
    path = bootstrap(env)
    for _ in range(10):
        response = env.client.get(path, headers=headers(env, "GET", path, token))
        assert response.status_code == 200 and response.content == b"mods folder bootstrap"
        assert len(response.headers["X-Request-Id"]) == 32
    response = env.client.get(path, headers=headers(env, "GET", path, token))
    assert response.status_code == 429
    assert response.json()["error_code"] == "rate_limited"
    assert 3500 <= int(response.headers["Retry-After"]) <= 3600
    assert response.json()["retry_after_seconds"] == int(response.headers["Retry-After"])
    assert b"mods folder bootstrap" not in response.content
    replacement = grant(env)
    assert env.client.get(path, headers=headers(env, "GET", path, replacement)).status_code == 200


def test_artifact_ip_budget_is_shared_across_tokens_and_persistent_app_instances(env):
    path = bootstrap(env)
    tokens = [grant(env) for _ in range(6)]
    for token in tokens[:5]:
        for _ in range(10):
            assert env.client.get(path, headers=headers(env, "GET", path, token)).status_code == 200
    with TestClient(create_app(env.config), base_url="https://testserver") as restarted:
        response = restarted.get(path, headers=headers(env, "GET", path, tokens[-1]))
    assert response.status_code == 429 and int(response.headers["Retry-After"]) > 3500


def test_artifact_quota_last_slot_is_atomic_across_process_equivalent_connections(env):
    token = grant(env)
    path = bootstrap(env)
    for _ in range(9):
        assert env.client.get(path, headers=headers(env, "GET", path, token)).status_code == 200
    apps = [create_app(env.config), create_app(env.config)]
    request_headers = [headers(env, "GET", path, token) for _ in apps]

    def fetch(index):
        with TestClient(apps[index], base_url="https://testserver") as client:
            return client.get(path, headers=request_headers[index]).status_code

    with ThreadPoolExecutor(max_workers=2) as pool:
        assert sorted(pool.map(fetch, range(2))) == [200, 429]


def test_fixed_hour_window_resets_and_denial_does_not_consume_other_budget(env, monkeypatch):
    base = int(time.time())
    monkeypatch.setattr("app.database.time.time", lambda: base)
    with env.app.state.database.connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        for _ in range(10):
            assert Database.consume_artifact_allowance(connection, b"token", b"ip") == 0
        assert Database.consume_artifact_allowance(connection, b"token", b"ip") == 3600
        assert tuple(connection.execute("SELECT count,window_started_at FROM rate_limits WHERE bucket=?", (b"ip",)).fetchone()) == (10, base)
    monkeypatch.setattr("app.database.time.time", lambda: base + 3600)
    with env.app.state.database.connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        assert Database.consume_artifact_allowance(connection, b"token", b"ip") == 0


def test_revoking_one_grant_preserves_other_grants_and_cannot_be_forged(env):
    token, replacement = grant(env), grant(env)
    path = bootstrap(env)
    assert env.client.get(path, headers=headers(env, "GET", path, token)).status_code == 200
    before = token.rsplit(".", 1)[0] + "." + "a" * 86
    with pytest.raises(ValueError):
        revoke_access_token(env.app.state.database, env.app.state.signer, before, "local test")
    result = revoke_access_token(env.app.state.database, env.app.state.signer, token, "operator test")
    assert result["status"] == "revoked" and token not in json.dumps(result)
    response = env.client.get(path, headers=headers(env, "GET", path, token))
    assert response.status_code == 401 and response.json()["error_code"] == "token_revoked"
    assert env.client.get(path, headers=headers(env, "GET", path, replacement)).status_code == 200
    with TestClient(create_app(env.config), base_url="https://testserver") as restarted:
        assert restarted.get(path, headers=headers(env, "GET", path, token)).status_code == 401


def test_plus_token_revocation_during_integrity_read_denies_response(env, monkeypatch):
    token = grant(env, product="celeste_plus")
    path = "/v1/beta/manifest"
    original = beta_delivery._read_release

    def revoke_during_read(*args, **kwargs):
        result = original(*args, **kwargs)
        revoke_access_token(env.app.state.database, env.app.state.signer, token, "revoked while serving")
        return result

    monkeypatch.setattr(beta_delivery, "_read_release", revoke_during_read)
    response = env.client.get(path, headers=headers(env, "GET", path, token))
    assert response.status_code == 401 and response.json()["error_code"] == "token_revoked"
    assert "manifest" not in response.json()


@pytest.mark.parametrize("reason", ["refund", "chargeback"])
def test_refund_and_chargeback_status_blocks_existing_signed_grants_immediately(env, reason):
    token = grant(env)
    with env.app.state.database.connect() as connection:
        connection.execute("UPDATE licenses SET status='revoked',revoked_reason=? WHERE uid=?", (reason, env.uid))
    path = bootstrap(env)
    assert env.client.get(path, headers=headers(env, "GET", path, token)).status_code == 401
