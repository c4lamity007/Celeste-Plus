"""Real HTTP/state regressions with local fixture keys; never contacts production."""
from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from dataclasses import replace
from pathlib import Path
import os
import sqlite3
import threading
import time

import pytest
from fastapi import HTTPException
from fastapi.testclient import TestClient as RawClient

import app.main as main
import app.beta_delivery as delivery
import app.release_cache as cache
from app.config import Settings
from app.main import create_app
from app.database import Database
from app.security import b64e, b64d
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from dpop_client import TestClient, register_key
from test_beta_delivery import beta
from test_beta_runtime import runtime, begin, completion, heartbeat
from test_protocol import settings, release, hardware, create_license, raw_public


def test_admin_reserves_before_hash_and_denies_sixth_attempt(tmp_path, monkeypatch):
    config = settings(tmp_path)
    app = create_app(config)
    seen = []

    def verify(*args):
        with app.state.database.connect() as connection:
            seen.append(connection.execute("SELECT count FROM rate_limits").fetchone()[0])
        return False

    monkeypatch.setattr(main, "verify_admin_password", verify)
    with RawClient(app, base_url="https://testserver") as client:
        for _ in range(5):
            assert client.post("/admin/login", data={"password": "wrong"}).status_code == 401
        assert client.post("/admin/login", data={"password": "wrong"}).status_code == 429
    assert seen == [1, 2, 3, 4, 5]


def test_admin_verification_is_process_bounded_and_released_on_error(tmp_path, monkeypatch):
    config = settings(tmp_path)
    apps = [create_app(config), create_app(config)]
    entered, release = threading.Event(), threading.Event()
    calls = []

    def verify(*args):
        calls.append(1)
        entered.set()
        assert release.wait(5)
        raise RuntimeError("synthetic verifier error")

    monkeypatch.setattr(main, "verify_admin_password", verify)
    def first():
        with RawClient(apps[0], base_url="https://testserver", raise_server_exceptions=False) as client:
            return client.post("/admin/login", data={"password": "wrong"}).status_code

    with ThreadPoolExecutor(max_workers=1) as pool:
        task = pool.submit(first)
        try:
            assert entered.wait(5)
            with RawClient(apps[1], base_url="https://testserver") as client:
                response = client.post("/admin/login", data={"password": "wrong"})
                assert response.status_code == 503
                assert response.headers["retry-after"] == "1"
            assert len(calls) == 1
        finally:
            release.set()
        assert task.result(timeout=5) == 500
    monkeypatch.setattr(main, "verify_admin_password", lambda *args: False)
    with RawClient(apps[1], base_url="https://testserver") as client:
        assert client.post("/admin/login", data={"password": "wrong"}).status_code == 401


def test_runtime_reuses_verified_metadata_but_not_authorization(runtime, monkeypatch):
    cache._ENTRIES.clear()
    original = delivery._read_release
    calls = []
    def checked(*args):
        calls.append(1)
        # A second writer can enter while release bytes are being hashed.
        with sqlite3.connect(runtime.beta.config.database_path, timeout=0.01) as connection:
            connection.execute("BEGIN IMMEDIATE")
        return original(*args)
    monkeypatch.setattr(delivery, "_read_release", checked)
    start = begin(runtime)
    assert start.status_code == 200
    assert runtime.beta.client.post("/v1/auth/complete", json=completion(runtime, start)).status_code == 200
    for n in range(3):
        assert runtime.beta.client.post("/v1/session/check", json=heartbeat(runtime, nonce=f"heartbeat-local-nonce-{n:04d}")).status_code == 200
    assert len(calls) == 1
    with runtime.beta.app.state.database.connect() as connection:
        connection.execute("UPDATE licenses SET status='revoked' WHERE uid=7")
    assert runtime.beta.client.post("/v1/session/check", json=heartbeat(runtime, nonce="heartbeat-local-nonce-last")).status_code == 401


def test_cold_completion_verifies_outside_writer_transaction(runtime, monkeypatch):
    start = begin(runtime)
    cache._ENTRIES.clear()
    original = delivery._read_release
    entered = []
    def checked(*args):
        with sqlite3.connect(runtime.beta.config.database_path, timeout=0.01) as connection:
            connection.execute("BEGIN IMMEDIATE")
        entered.append(1)
        return original(*args)
    monkeypatch.setattr(delivery, "_read_release", checked)
    assert runtime.beta.client.post("/v1/auth/complete", json=completion(runtime, start)).status_code == 200
    assert entered == [1]


@pytest.mark.parametrize("change", ["overwrite", "restored-mtime", "replace", "manifest", "missing"])
def test_runtime_cache_detects_release_mutations(runtime, change):
    path = runtime.beta.root / "payload-a.bin"
    stamp = path.stat()
    data = path.read_bytes()
    if change == "manifest":
        path = runtime.beta.root / "beta-release.json"
        path.write_text(path.read_text().replace('"client_version":', '"duplicate": 1, "client_version":'))
    elif change == "missing":
        path.unlink()
    elif change == "replace":
        replacement = path.with_name("replacement.bin")
        replacement.write_bytes(b"X" * len(data))
        os.utime(replacement, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
        os.replace(replacement, path)
    else:
        path.write_bytes(b"X" * len(data))
        if change == "restored-mtime":
            os.utime(path, ns=(stamp.st_atime_ns, stamp.st_mtime_ns))
    assert begin(runtime).status_code == 503


def test_snapshot_mutation_during_hash_does_not_enter_cache(runtime, monkeypatch):
    cache._ENTRIES.clear()
    original = delivery._read_release
    def changed(*args):
        result = original(*args)
        path = runtime.beta.root / "payload-a.bin"
        path.write_bytes(b"X" * path.stat().st_size)
        return result
    monkeypatch.setattr(delivery, "_read_release", changed)
    assert begin(runtime).status_code == 503
    assert cache._key(runtime.beta.config) not in cache._ENTRIES


def test_cache_returns_copies_and_revalidates_expired_snapshot(runtime, monkeypatch):
    first = delivery.beta_runtime_catalog(runtime.beta.config, 7)
    first["artifacts"][0]["sha256"] = "0" * 64
    assert delivery.beta_runtime_catalog(runtime.beta.config, 7)["artifacts"][0]["sha256"] != "0" * 64
    original = delivery._read_release
    calls = []
    monkeypatch.setattr(delivery, "_read_release", lambda *args: (calls.append(1), original(*args))[1])
    monotonic = time.monotonic()
    monkeypatch.setattr(cache.time, "monotonic", lambda: monotonic + cache.MAX_AGE_SECONDS + 1)
    delivery.beta_runtime_catalog(runtime.beta.config, 7)
    assert calls == [1]


def test_completion_rejects_file_change_after_prefetch(runtime, monkeypatch):
    start = begin(runtime)
    original = main.beta_runtime_catalog
    def changed(*args):
        result = original(*args)
        path = runtime.beta.root / "payload-a.bin"
        path.write_bytes(b"X" * path.stat().st_size)
        return result
    monkeypatch.setattr(main, "beta_runtime_catalog", changed)
    assert runtime.beta.client.post("/v1/auth/complete", json=completion(runtime, start)).status_code == 503
    with runtime.beta.app.state.database.connect() as connection:
        assert connection.execute("SELECT consumed_at FROM challenges WHERE challenge_id=?", (start.json()["challenge_id"],)).fetchone()[0] is None


@pytest.mark.parametrize("scope", ["auth", "installer"])
def test_bad_dual_proof_audit_survives_and_challenge_can_retry(runtime, scope):
    if scope == "auth":
        start = begin(runtime)
    else:
        start = runtime.beta.client.post("/v1/installer/start", json={**release(), "hardware": hardware(),
            "device_id": runtime.identities[7].device, "product": "celeste_plus"})
    assert start.status_code == 200, start.text
    correct = completion(runtime, start)
    bad = {**correct, "token_signature": b64e(bytes(64))}
    assert runtime.beta.client.post(f"/v1/{scope}/complete", json=bad).status_code == 401
    with runtime.beta.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM audit_log WHERE event=? AND subject=?",
                                  (scope + "_bad_proof", correct["challenge_id"])).fetchone()[0] == 1
        assert connection.execute("SELECT consumed_at FROM challenges WHERE challenge_id=?",
                                  (correct["challenge_id"],)).fetchone()[0] is None
    assert runtime.beta.client.post(f"/v1/{scope}/complete", json=correct).status_code == 200


def test_settings_repr_redacts_secret_values(tmp_path):
    config = settings(tmp_path)
    shown = repr(config)
    for name in ("signing_private_key", "key_pepper", "session_secret", "admin_password_hash"):
        assert name not in shown
        assert repr(getattr(config, name)) not in shown


def test_enrollment_denial_is_durable_without_enrolling_device(tmp_path):
    config = settings(tmp_path)
    app = create_app(config)
    key = "CST-LOCAL-DENIAL-ENROLL-ONLY-TEST"
    uid = create_license(config, key)
    install = register_key(ed25519.Ed25519PrivateKey.generate())
    encryption = x25519.X25519PrivateKey.generate()
    with TestClient(app, base_url="https://testserver") as client:
        start = client.post("/v1/enroll/start", json={**release(), "hardware": hardware(), "license_key": key,
            "install_signing_key": raw_public(install), "install_encryption_key": raw_public(encryption)})
        assert start.status_code == 200
        challenge = start.json()
        denied = client.post("/v1/enroll/complete", json={"challenge_id": challenge["challenge_id"],
            "install_signature": b64e(bytes(64))})
        assert denied.status_code == 401
        with app.state.database.connect() as connection:
            assert connection.execute("SELECT COUNT(*) FROM audit_log WHERE event='enroll_bad_signature'").fetchone()[0] == 1
            assert connection.execute("SELECT COUNT(*) FROM devices WHERE license_uid=?", (uid,)).fetchone()[0] == 0
        accepted = client.post("/v1/enroll/complete", json={"challenge_id": challenge["challenge_id"],
            "install_signature": b64e(install.sign(b64d(challenge["message"])))})
        assert accepted.status_code == 200


def test_durable_denial_does_not_commit_other_transaction_writes(runtime, monkeypatch):
    start = begin(runtime)
    original = Database.connect
    injected = []

    class Connection:
        def __init__(self, actual):
            self.actual = actual
        def __getattr__(self, name):
            return getattr(self.actual, name)
        def execute(self, sql, *args):
            cursor = self.actual.execute(sql, *args)
            if "d.status AS device_status" in sql and "FROM challenges c" in sql and self.actual.in_transaction:
                self.actual.execute("UPDATE licenses SET label='must roll back' WHERE uid=7")
                injected.append(1)
            return cursor

    @contextmanager
    def connect(database):
        with original(database) as actual:
            yield Connection(actual)

    monkeypatch.setattr(Database, "connect", connect)
    body = {**completion(runtime, start), "token_signature": b64e(bytes(64))}
    assert runtime.beta.client.post("/v1/auth/complete", json=body).status_code == 401
    assert injected == [1]
    with original(runtime.beta.app.state.database) as connection:
        assert connection.execute("SELECT label FROM licenses WHERE uid=7").fetchone()[0] != "must roll back"
        assert connection.execute("SELECT COUNT(*) FROM audit_log WHERE event='auth_bad_proof'").fetchone()[0] == 1
