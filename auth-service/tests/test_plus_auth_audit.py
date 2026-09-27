"""Current Plus cache replay and request-limit regressions; synthetic local identities only."""
from concurrent.futures import ThreadPoolExecutor
import hashlib
import threading
import time

import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519
from fastapi.testclient import TestClient

from app.database import Database
from app.main import create_app
from app.security import keyed_digest
from dpop_client import proof
from test_artifact_hardware import manifest
from test_dpop import env, grant, headers
from test_protocol_v2 import v2, auth, encrypted_request, decrypt


def test_plus_manifest_last_request_slot_is_atomic_across_app_instances(env, monkeypatch):
    """Two legitimate requests cannot both spend the same last IP request slot."""
    monkeypatch.delenv("FLY_APP_NAME", raising=False)
    token = grant(env, product="celeste_plus")
    path = "/v1/beta/manifest"
    bucket = keyed_digest(env.config.key_pepper, "rate:beta-delivery", "testclient")
    now = int(time.time())
    with env.app.state.database.connect() as connection:
        connection.execute("INSERT INTO rate_limits(bucket,count,window_started_at,blocked_until) VALUES(?,?,?,0)",
                           (bucket, 29, now))
    requests = [headers(env, "GET", path, token) for _ in range(2)]
    apps = [create_app(env.config), create_app(env.config)]
    barrier = threading.Barrier(2)
    original = Database.rate_limit

    class InterleavedRead:
        def __init__(self, connection):
            self.connection = connection

        def execute(self, sql, arguments):
            cursor = self.connection.execute(sql, arguments)
            if sql.startswith("SELECT count, window_started_at, blocked_until FROM rate_limits"):
                row = cursor.fetchone()
                # Real interleaved readers expose the lost update. With a write
                # transaction the other connection waits, so the barrier times
                # out and the first request finishes before the second reads.
                try:
                    barrier.wait(timeout=0.5)
                except threading.BrokenBarrierError:
                    pass
                return type("Read", (), {"fetchone": lambda self: row})()
            return cursor

    def interleaved(connection, supplied, limit, window):
        return original(InterleavedRead(connection) if supplied == bucket else connection, supplied, limit, window)

    monkeypatch.setattr(Database, "rate_limit", staticmethod(interleaved))

    def request(index):
        with TestClient(apps[index], base_url="https://testserver") as client:
            return client.get(path, headers=requests[index])

    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(request, range(2)))
    assert sorted(response.status_code for response in responses) == [200, 429]
    denied = next(response for response in responses if response.status_code == 429)
    assert denied.json()["error_code"] == "rate_limited" and int(denied.headers["Retry-After"]) > 0
    assert "manifest" not in denied.json()
    with env.app.state.database.connect() as connection:
        assert connection.execute("SELECT count FROM rate_limits WHERE bucket=?", (bucket,)).fetchone()[0] == 31


def test_expired_admin_limit_read_cannot_delete_a_concurrently_reset_window(env, monkeypatch):
    """The login precheck must not erase newer failures after an expired read."""
    now = int(time.time())
    monkeypatch.setattr("app.database.time.time", lambda: now)
    bucket = b"isolated-admin-race"
    with env.app.state.database.connect() as connection:
        connection.execute("INSERT INTO rate_limits(bucket,count,window_started_at,blocked_until) VALUES(?,?,?,0)",
                           (bucket, 5, now - 901))

    class ReplacedWindow:
        def __init__(self, connection):
            self.connection = connection

        def execute(self, sql, arguments):
            cursor = self.connection.execute(sql, arguments)
            if sql.startswith("SELECT window_started_at, blocked_until FROM rate_limits"):
                old_row = cursor.fetchone()
                cursor.close()
                with env.app.state.database.connect() as writer:
                    writer.execute("BEGIN IMMEDIATE")
                    for _ in range(6):
                        Database.rate_limit(writer, bucket, 5, 900)
                return type("Read", (), {"fetchone": lambda self: old_row})()
            return cursor

    with env.app.state.database.connect() as reader:
        assert Database.rate_limit_retry_after(ReplacedWindow(reader), bucket, 900) == 0
    with env.app.state.database.connect() as connection:
        row = connection.execute("SELECT count,window_started_at,blocked_until FROM rate_limits WHERE bucket=?", (bucket,)).fetchone()
        assert row is not None, "The expired read erased the new rate-limit window"
        assert tuple(row) == (6, now, now + 2)
        assert Database.rate_limit_retry_after(connection, bucket, 900) == 2


@pytest.mark.parametrize("artifact", ["bootstrap-a", "bootstrap-b", "payload-a", "payload-b"])
def test_copied_plus_tokens_and_requests_cannot_authorize_another_installation(v2, artifact):
    token = auth(v2).json()["grant"]
    signed, _ = manifest(v2, token)
    entry = next(item for item in v2.app.state.signer.verify(signed)["artifacts"] if item["id"] == artifact)
    path = entry["url"]
    private, legitimate = encrypted_request(v2, token, path, signed)
    # Copies of a JWT, manifest and all public request/device fields lack the
    # installation secret. A fresh proof made by the other installation fails.
    copied = dict(legitimate)
    copied.pop("DPoP")
    assert v2.client.get(path, headers=copied).status_code == 401
    other = ed25519.Ed25519PrivateKey.generate()
    copied["DPoP"] = proof(other, "GET", "https://testserver" + path, token, payload_updates={
        "cek": copied["X-Celeste-Delivery-Key"], "cnonce": copied["X-Celeste-Request-Nonce"],
        "hwid": copied["X-Device-Id"]})
    assert v2.client.get(path, headers=copied).status_code == 401
    result = v2.client.get(path, headers=legitimate)
    assert result.status_code == 200
    assert hashlib.sha256(decrypt(result.content, private, token, path, legitimate)).hexdigest() == entry["sha256"]
    # A copied full valid request remains consumed across a separate app instance.
    with TestClient(create_app(v2.config), base_url="https://testserver") as restarted:
        assert restarted.get(path, headers=legitimate).status_code == 401


@pytest.mark.parametrize("change", ["license", "expiry", "entitlement"])
@pytest.mark.parametrize("phase", ["read", "encrypt"])
def test_plus_live_subscription_rechecked_after_slow_artifact_work(v2, monkeypatch, change, phase):
    import app.beta_delivery as delivery
    token = auth(v2).json()["grant"]
    signed, path = manifest(v2, token)
    _, request = encrypted_request(v2, token, path, signed)
    name = "_read_release" if phase == "read" else "encrypt"
    original = getattr(delivery, name)

    def revoke(*args, **kwargs):
        result = original(*args, **kwargs)
        with v2.app.state.database.connect() as connection:
            if change == "license":
                connection.execute("UPDATE licenses SET status='revoked' WHERE uid=?", (v2.uid,))
            elif change == "expiry":
                connection.execute("UPDATE licenses SET expires_at=1 WHERE uid=?", (v2.uid,))
            else:
                connection.execute("DELETE FROM license_entitlements WHERE license_uid=? AND product='celeste_plus'", (v2.uid,))
        return result

    monkeypatch.setattr(delivery, name, revoke)
    response = v2.client.get(path, headers=request)
    assert response.status_code == (403 if change == "entitlement" else 401)
    assert response.headers["content-type"].startswith("application/json")
    assert not response.content.startswith(b"CSTAE1")
