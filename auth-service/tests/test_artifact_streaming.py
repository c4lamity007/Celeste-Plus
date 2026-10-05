"""Exact wire equivalence and bounded transfer admission/lifetime, no live data."""
import asyncio
import inspect
import threading
import time
from types import SimpleNamespace

import pytest
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives import serialization
from starlette.requests import ClientDisconnect

from app import artifact_envelope as envelope
from app.artifact_transfer import ArtifactResponse, plain_chunks
from app.security import b64e
from test_dpop import env
from test_protocol_v2 import v2, auth, encrypted_request, decrypt
from test_artifact_hardware import manifest


@pytest.mark.parametrize("size", [0, 1, 65535, 65536, 65537, 1024 * 1024])
def test_streaming_gcm_is_exact_existing_wire_and_tag(size, monkeypatch):
    private = x25519.X25519PrivateKey.from_private_bytes(bytes(range(32)))
    server = x25519.X25519PrivateKey.from_private_bytes(bytes(range(32, 64)))
    public = b64e(private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    nonce = b64e(bytes(range(24)))
    token, path = "public-synthetic-token", "/v1/beta/artifacts/fixture/payload-a"
    monkeypatch.setattr(x25519.X25519PrivateKey, "generate", lambda: server)
    monkeypatch.setattr(envelope.secrets, "token_bytes", lambda n: bytes(range(n)))
    content = bytes(range(256)) * (size // 256) + bytes(range(size % 256))
    old = envelope.encrypt(content, token, path, public, nonce)
    parts = list(envelope.encrypt_chunks(content, token, path, public, nonce))
    streamed = b"".join(parts)
    assert streamed == old and len(streamed) == size + envelope.OVERHEAD_BYTES
    assert all(0 < len(part) <= envelope.CHUNK_BYTES for part in parts)
    headers = {"X-Celeste-Request-Nonce": nonce, "X-Celeste-Delivery-Key": public}
    assert decrypt(streamed, private, token, path, headers) == content
    for altered in (streamed[:-1], streamed[:-1] + bytes([streamed[-1] ^ 1])):
        with pytest.raises(InvalidTag):
            decrypt(altered, private, token, path, headers)
    with pytest.raises(InvalidTag):
        decrypt(streamed, private, token, path + "wrong", headers)


def test_invalid_recipient_fails_before_stream_is_returned():
    with pytest.raises(ValueError):
        envelope.encrypt_chunks(b"fixture", "token", "/path", b64e(bytes(32)), b64e(bytes(24)))


@pytest.mark.parametrize("mode", ["success", "send-error", "start-error", "generator-error", "cancel", "disconnect", "timeout"])
def test_response_releases_slots_and_snapshot_on_every_exit(monkeypatch, mode):
    import app.artifact_transfer as transfer
    slots = threading.BoundedSemaphore(2)
    assert slots.acquire(False)
    def broken():
        yield b"fixture"
        raise ValueError("fixture encryptor error")
    iterator = broken() if mode == "generator-error" else plain_chunks(b"data" * 50000)
    response = ArtifactResponse(iterator, 200000, slots)
    seen = []
    if mode == "timeout":
        monkeypatch.setattr(transfer, "TRANSFER_TIMEOUT_SECONDS", .02)

    async def exercise():
        async def receive():
            return {"type": "http.disconnect"}
        async def send(message):
            if mode == "timeout": await asyncio.Event().wait()
            if mode == "start-error": raise OSError("fixture header send failure")
            if message["type"] == "http.response.body" and message.get("body"):
                if mode == "send-error": raise OSError("fixture send failure")
                if mode == "cancel": raise asyncio.CancelledError()
                seen.append(bytes(message["body"]))
        scope = {"type": "http", "asgi": {"spec_version": "2.3" if mode == "disconnect" else "2.4"}}
        try:
            await response(scope, receive, send)
        except (ClientDisconnect, asyncio.CancelledError, TimeoutError, ValueError):
            assert mode in {"send-error", "start-error", "generator-error", "cancel", "timeout"}

    asyncio.run(exercise())
    assert iterator.gi_frame is None and response.body_iterator.ag_frame is None
    assert slots.acquire(False) and slots.acquire(False) and not slots.acquire(False)
    if mode == "success":
        assert b"".join(seen) == b"data" * 50000
        assert response.headers["content-length"] == "200000"


def test_slots_stay_held_during_backpressure_until_cancelled():
    slots = threading.BoundedSemaphore(2)
    assert slots.acquire(False) and slots.acquire(False)
    responses = [ArtifactResponse(plain_chunks(b"fixture" * 10000), 70000, slots) for _ in range(2)]

    async def exercise():
        started = [asyncio.Event(), asyncio.Event()]
        block = asyncio.Event()
        async def receive(): await block.wait()
        def sender(index):
            async def send(message):
                if message["type"] == "http.response.body" and message.get("body"):
                    started[index].set()
                    await block.wait()
            return send
        tasks = [asyncio.create_task(r({"type": "http", "asgi": {"spec_version": "2.4"}}, receive, sender(i)))
                 for i, r in enumerate(responses)]
        await asyncio.gather(*(event.wait() for event in started))
        assert not slots.acquire(False)
        tasks[0].cancel()
        with pytest.raises(asyncio.CancelledError): await tasks[0]
        assert slots.acquire(False) and not slots.acquire(False)
        slots.release()
        tasks[1].cancel()
        with pytest.raises(asyncio.CancelledError): await tasks[1]
        assert slots.acquire(False) and slots.acquire(False) and not slots.acquire(False)
    asyncio.run(exercise())


def slots_for(e):
    route = next(r for r in e.app.routes if getattr(r, "path", "") == "/v1/beta/artifacts/{release_id}/{artifact_id}")
    return inspect.getclosurevars(route.endpoint).nonlocals["transfer_slots"]


def request_for(e):
    token = auth(e).json()["grant"]
    signed, path = manifest(e, token)
    private, headers = encrypted_request(e, token, path, signed)
    return token, signed, path, private, headers


@pytest.mark.parametrize("change", ["hwid", "entitlement", "revoked", "expired"])
def test_wait_revalidates_before_any_snapshot_allocation(v2, monkeypatch, change):
    import app.beta_delivery as delivery
    token, signed, path, _, headers = request_for(v2)
    slots = slots_for(v2)
    acquire = slots.acquire
    def waited(*args, **kwargs):
        result = acquire(*args, **kwargs)
        with v2.app.state.database.connect() as c:
            if change == "hwid": c.execute("UPDATE devices SET hwid_sha256=?", ("8" * 64,))
            elif change == "entitlement": c.execute("DELETE FROM license_entitlements WHERE product='celeste_plus'")
            elif change == "revoked": c.execute("UPDATE devices SET status='revoked'")
        if change == "expired":
            expiry = v2.app.state.signer.verify(signed)["exp"]
            monkeypatch.setattr(delivery, "time", SimpleNamespace(time=lambda: expiry))
        return result
    monkeypatch.setattr(slots, "acquire", waited)
    monkeypatch.setattr(delivery, "_read_release", lambda *a: pytest.fail("allocated after authorization expired"))
    response = v2.client.get(path, headers=headers)
    assert response.status_code in {401, 403}
    assert acquire(False) and acquire(False) and not acquire(False)


def test_unauthorized_request_never_waits_or_reads(v2, monkeypatch):
    import app.beta_delivery as delivery
    _, _, path, _, headers = request_for(v2)
    headers.pop("X-Device-Id")
    monkeypatch.setattr(slots_for(v2), "acquire", lambda *a, **k: pytest.fail("unauthorized admission"))
    monkeypatch.setattr(delivery, "_read_release", lambda *a: pytest.fail("unauthorized read"))
    assert v2.client.get(path, headers=headers).status_code == 401


def test_busy_wait_is_bounded_and_no_snapshot_allocated(v2, monkeypatch):
    import app.beta_delivery as delivery
    _, _, path, _, headers = request_for(v2)
    slots = slots_for(v2)
    assert slots.acquire(False) and slots.acquire(False)
    monkeypatch.setattr(delivery, "TRANSFER_WAIT_SECONDS", .03)
    monkeypatch.setattr(delivery, "_read_release", lambda *a: pytest.fail("busy snapshot read"))
    started = time.monotonic()
    response = v2.client.get(path, headers=headers)
    assert response.status_code == 503 and response.headers["retry-after"] == "1"
    assert .025 <= time.monotonic() - started < 1
    assert not slots.acquire(False)
    slots.release(); slots.release()


def test_short_wait_then_download_keeps_exact_recipient_wire(v2):
    token, _, path, private, headers = request_for(v2)
    slots = slots_for(v2)
    assert slots.acquire(False) and slots.acquire(False)
    release = threading.Timer(.05, slots.release)
    release.start()
    try:
        response = v2.client.get(path, headers=headers)
        assert response.status_code == 200, response.text
        assert decrypt(response.content, private, token, path, headers)
        assert int(response.headers["content-length"]) == len(response.content)
        assert slots.acquire(False) and not slots.acquire(False)
        slots.release()
    finally:
        release.join()
        slots.release()


def test_busy_retries_preserve_allowance_but_consume_proof(v2, monkeypatch):
    import app.beta_delivery as delivery
    from app.security import keyed_digest
    token, signed, path, _, _ = request_for(v2)
    token_bucket = keyed_digest(v2.config.key_pepper, "artifact-token", v2.app.state.signer.verify(token)["jti"])
    slots = slots_for(v2)
    assert slots.acquire(False) and slots.acquire(False)
    monkeypatch.setattr(delivery, "TRANSFER_WAIT_SECONDS", 0)
    try:
        for _ in range(11):
            _, headers = encrypted_request(v2, token, path, signed)
            assert v2.client.get(path, headers=headers).status_code == 503
        # Busy attempts still consume one-use proofs; replay cannot be retried.
        assert v2.client.get(path, headers=headers).status_code == 401
        with v2.app.state.database.connect() as c:
            assert c.execute("SELECT count FROM rate_limits WHERE bucket=?", (token_bucket,)).fetchone() is None
    finally:
        slots.release()
        slots.release()
    # Successful admission keeps the existing limit and rejects before reading
    # a snapshot once exhausted. Both quota buckets advance together.
    for _ in range(10):
        private, headers = encrypted_request(v2, token, path, signed)
        response = v2.client.get(path, headers=headers)
        assert response.status_code == 200
        assert decrypt(response.content, private, token, path, headers)
    monkeypatch.setattr(delivery, "_read_release", lambda *a: pytest.fail("over-quota snapshot read"))
    _, headers = encrypted_request(v2, token, path, signed)
    assert v2.client.get(path, headers=headers).status_code == 429
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT count FROM rate_limits WHERE bucket=?", (token_bucket,)).fetchone()[0] == 10
    assert slots.acquire(False) and slots.acquire(False) and not slots.acquire(False)


def test_snapshot_failure_releases_slot(v2, monkeypatch):
    from fastapi import HTTPException
    import app.beta_delivery as delivery
    _, _, path, _, headers = request_for(v2)
    def invalid(*args): raise HTTPException(503, "fixture corrupt catalog")
    monkeypatch.setattr(delivery, "_read_release", invalid)
    assert v2.client.get(path, headers=headers).status_code == 503
    slots = slots_for(v2)
    assert slots.acquire(False) and slots.acquire(False) and not slots.acquire(False)


def test_quota_reservation_wait_rechecks_authority_before_snapshot(v2, monkeypatch):
    import app.beta_delivery as delivery
    from app.database import Database
    _, _, path, _, headers = request_for(v2)
    original = Database.consume_artifact_allowance

    def waited(connection, token_bucket, ip_bucket):
        result = original(connection, token_bucket, ip_bucket)
        connection.execute("UPDATE devices SET status='revoked'")
        return result

    monkeypatch.setattr(Database, "consume_artifact_allowance", staticmethod(waited))
    monkeypatch.setattr(delivery, "_read_release", lambda *a: pytest.fail("allocated after admission revocation"))
    assert v2.client.get(path, headers=headers).status_code == 401
    slots = slots_for(v2)
    assert slots.acquire(False) and slots.acquire(False) and not slots.acquire(False)


@pytest.mark.parametrize("reject_at", [0, 1, 2])
def test_guard_withholds_final_authentication_tag_and_releases_slot(reject_at):
    private = x25519.X25519PrivateKey.generate()
    public = b64e(private.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    nonce = b64e(bytes(range(24)))
    token, path = "synthetic-module-token", "/v1/modules/artifacts/fixture/module"
    clear = b"encrypted-fixture" * 10000
    parts = envelope.encrypt_chunks(clear, token, path, public, nonce)
    slots = threading.BoundedSemaphore(1)
    assert slots.acquire(False)
    seen, checks = [], []
    async def guard():
        checks.append(len(seen))
        if reject_at and len(checks) == reject_at:
            raise PermissionError("fixture revoked")
    response = ArtifactResponse(parts, len(clear) + envelope.OVERHEAD_BYTES, slots, authorization_guard=guard)
    async def exercise():
        async def receive():
            await asyncio.Event().wait()
        async def send(message):
            if message["type"] == "http.response.body" and message.get("body"):
                seen.append(bytes(message["body"]))
        try:
            await response({"type": "http", "asgi": {"spec_version": "2.4"}}, receive, send)
        except (PermissionError, ClientDisconnect):
            assert reject_at
    asyncio.run(exercise())
    assert parts.gi_frame is None and response.body_iterator.ag_frame is None
    assert slots.acquire(False) and not slots.acquire(False)
    headers = {"X-Celeste-Request-Nonce": nonce, "X-Celeste-Delivery-Key": public}
    if reject_at == 0:
        assert decrypt(b"".join(seen), private, token, path, headers) == clear
        assert len(checks) == 2
    elif reject_at == 1:
        assert not seen
    else:
        assert seen and sum(map(len, seen)) == len(clear) + envelope.OVERHEAD_BYTES - 16
        with pytest.raises(InvalidTag):
            decrypt(b"".join(seen), private, token, path, headers)


def test_guard_periodic_checks_are_bounded_by_elapsed_transfer_time(monkeypatch):
    import app.artifact_transfer as transfer
    clock = [0.0]
    monkeypatch.setattr(transfer, "time", SimpleNamespace(monotonic=lambda: clock[0]))
    checks = []
    slots = threading.BoundedSemaphore(1)
    assert slots.acquire(False)
    def chunks():
        for _ in range(100): yield b"fixture"
    async def guard(): checks.append(clock[0])
    response = ArtifactResponse(chunks(), 700, slots, authorization_guard=guard)
    async def exercise():
        async def receive(): await asyncio.Event().wait()
        async def send(message):
            if message["type"] == "http.response.body" and message.get("body"):
                clock[0] += 0.05
        await response({"type": "http", "asgi": {"spec_version": "2.4"}}, receive, send)
    asyncio.run(exercise())
    assert 5 <= len(checks) <= 6
    assert slots.acquire(False)


@pytest.mark.parametrize("change", ["license", "device", "entitlement", "hwid", "token", "key", "manifest-expiry"])
def test_beta_stream_revalidates_before_releasing_authentication_tag(v2, monkeypatch, change):
    """A bootstrap transfer must not authenticate after its authority is lost."""
    import app.beta_delivery as delivery
    token, signed, path, _, headers = request_for(v2)
    manifest_expiry = v2.app.state.signer.verify(signed)["exp"]
    original = delivery.encrypt_chunks

    def interrupted(*args):
        parts = list(original(*args))
        yield from parts[:-1]
        if change == "manifest-expiry":
            monkeypatch.setattr(delivery, "time", SimpleNamespace(time=lambda: manifest_expiry))
        else:
            with v2.app.state.database.connect() as connection:
                if change == "license": connection.execute("UPDATE licenses SET status='revoked'")
                elif change == "device": connection.execute("UPDATE devices SET status='revoked'")
                elif change == "entitlement": connection.execute("DELETE FROM license_entitlements WHERE product='celeste_plus'")
                elif change == "hwid": connection.execute("UPDATE devices SET hwid_sha256=?", ("8" * 64,))
                elif change == "token": connection.execute("UPDATE active_tokens SET status='rotated'")
                else: connection.execute("UPDATE devices SET install_signing_key=?", (bytes(32),))
        yield parts[-1]

    monkeypatch.setattr(delivery, "encrypt_chunks", interrupted)
    with pytest.raises(RuntimeError, match="response already started"):
        v2.client.get(path, headers=headers)
    slots = slots_for(v2)
    assert slots.acquire(False) and slots.acquire(False) and not slots.acquire(False)


def test_beta_stream_rechecks_after_response_preparation_before_first_byte(v2, monkeypatch):
    import app.beta_delivery as delivery
    _, _, path, _, headers = request_for(v2)
    original = delivery.ArtifactResponse

    class RevokedBeforeSend(original):
        async def __call__(self, scope, receive, send):
            with v2.app.state.database.connect() as connection:
                connection.execute("UPDATE licenses SET status='revoked'")
            await super().__call__(scope, receive, send)

    monkeypatch.setattr(delivery, "ArtifactResponse", RevokedBeforeSend)
    with pytest.raises(RuntimeError, match="response already started"):
        v2.client.get(path, headers=headers)
    slots = slots_for(v2)
    assert slots.acquire(False) and slots.acquire(False) and not slots.acquire(False)
