"""Local public-loader fixtures; no real licenses, keys or executable runs."""
from __future__ import annotations

import asyncio
from dataclasses import replace
import hashlib
import json
import os
from pathlib import Path
import secrets
import stat
import tracemalloc
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives import serialization
from fastapi import HTTPException
from fastapi.testclient import TestClient
import pytest

from app.config import Settings
from app.main import create_app
from app.native_download import CHUNK_BYTES, MAX_BYTES, ORIGIN, PublicNativeStore, download_path
from app.security import b64e
from test_beta_delivery import stage_beta
from test_native_manifest import claims, get, publish, verified_manifest
from test_protocol import settings


@pytest.fixture
def public(tmp_path):
    data = b"isolated protected launcher fixture\x00" * 4096
    digest = hashlib.sha256(data).hexdigest()
    root = tmp_path / "public-native"
    root.mkdir()
    path = root / (digest + ".exe")
    path.write_bytes(data)
    key = Ed25519PrivateKey.generate()
    beta = tmp_path / "beta"
    catalog = stage_beta(beta)
    config = replace(settings(tmp_path), plus_enabled=True, plus_auth_protocol=2, beta_release_dir=str(beta),
        public_native_dir=str(root), plus_download_sha256=digest,
        native_release_public_key=key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))
    app = create_app(config)
    client = TestClient(app, base_url="https://testserver")
    value = claims(3)
    entry = {"sha256": digest, "size": len(data), "release_id": "public-loader-fixture-3",
             "protection_receipt_sha256": "d" * 64}
    # The retained old binary is deliberately first. It must not become current.
    value["binaries"].append(entry)
    assert publish(client, key, value).status_code == 200
    return SimpleNamespace(client=client, app=app, config=config, database=app.state.database,
        signer=app.state.signer, key=key, value=value, entry=entry, data=data, digest=digest,
        root=root, path=path, catalog=catalog)


def manifest(public, nonce=None, **kwargs):
    return public.client.get("/v1/launcher/manifest?platform=windows-x64",
        headers={"X-Celeste-Request-Nonce": nonce or secrets.token_urlsafe(24)}, **kwargs)


def update_policy(public, entries):
    with public.database.connect() as connection:
        connection.execute("UPDATE native_release_policies SET sequence=sequence+1,binaries_json=? WHERE product='celeste_plus'",
                           (json.dumps(entries),))


def test_public_manifest_signature_exact_context_and_selected_bytes(public):
    nonce = secrets.token_urlsafe(24)
    response = manifest(public, nonce)
    assert response.status_code == 200
    body = verified_manifest(public.signer, response)
    assert set(body) == {"aud", "iat", "nbf", "exp", "request_nonce", "product", "platform", "sequence",
                         "release_id", "sha256", "size", "url"}
    assert body["aud"] == "celeste-launcher-download"
    assert body["product"] == "celeste_plus" and body["platform"] == "windows-x64"
    assert body["request_nonce"] == nonce and body["sequence"] == 3
    assert body["sha256"] == public.digest and body["size"] == len(public.data)
    assert body["release_id"] == public.entry["release_id"]
    assert body["url"] == ORIGIN + download_path(public.digest)
    assert body["iat"] == body["nbf"] and body["exp"] - body["iat"] == 120
    assert response.headers["cache-control"] == "no-store"
    download = public.client.get(download_path(public.digest))
    assert download.status_code == 200 and download.content == public.data
    assert hashlib.sha256(download.content).hexdigest() == body["sha256"]
    assert download.headers["content-length"] == str(body["size"])
    assert download.headers["content-type"] == "application/octet-stream"
    assert download.headers["content-disposition"] == 'attachment; filename="Celeste+.exe"'
    assert download.headers["cache-control"] == "no-store"
    assert download.headers["x-content-type-options"] == "nosniff"
    # Existing native self-integrity schema remains exact and keeps all approvals.
    native = verified_manifest(public.signer, get(public.client, secrets.token_urlsafe(24)))
    assert set(native) == {"aud", "iat", "nbf", "exp", "request_nonce", "product", "platform", "sequence", "sha256"}
    assert native["sha256"] == ["a" * 64, public.digest]


@pytest.mark.parametrize("nonce", [None, "", "a" * 31, "a" * 33, "a" * 32 + "=", "+" * 32])
def test_public_nonce_required(public, nonce):
    headers = {} if nonce is None else {"X-Celeste-Request-Nonce": nonce}
    response = public.client.get("/v1/launcher/manifest?platform=windows-x64", headers=headers)
    assert response.status_code == 400


def test_duplicate_nonce_and_other_platform_rejected(public):
    response = public.client.get("/v1/launcher/manifest?platform=windows-x64",
        headers=[("X-Celeste-Request-Nonce", "a" * 32), ("X-Celeste-Request-Nonce", "b" * 32)])
    assert response.status_code == 400
    for platform in ("linux-x64", "macos-arm64", "celeste", "../windows-x64"):
        assert public.client.get("/v1/launcher/manifest", params={"platform": platform},
            headers={"X-Celeste-Request-Nonce": "a" * 32}).status_code == 400


@pytest.mark.parametrize("tail", ["a" * 64 + "/Celeste+.exe", "A" * 64 + "/Celeste+.exe",
    "../beta-release.json", "%2e%2e%2fsecret/Celeste+.exe", "bad/Celeste+.exe"])
def test_retained_or_unselected_paths_not_public(public, tail):
    response = public.client.get("/v1/launcher/windows-x64/" + tail)
    assert response.status_code == 404
    assert public.data not in response.content


def test_ranges_head_and_unrelated_filename_not_supported(public):
    assert public.client.get(download_path(public.digest), headers={"Range": "bytes=0-0"}).status_code == 416
    assert public.client.head(download_path(public.digest)).status_code == 405
    assert public.client.get(download_path(public.digest).replace("Celeste+.exe", "anything.exe")).status_code == 404


@pytest.mark.parametrize("change", ["missing", "corrupt", "truncated", "oversized", "directory", "hardlink"])
def test_invalid_staged_files_never_get_signed_or_served(public, change):
    if change == "missing": public.path.unlink()
    elif change == "corrupt": public.path.write_bytes(b"x" * len(public.data))
    elif change == "truncated": public.path.write_bytes(public.data[:-1])
    elif change == "oversized": public.path.write_bytes(public.data + b"x")
    elif change == "directory": public.path.unlink(); public.path.mkdir()
    elif change == "hardlink": os.link(public.path, public.root / "second-link.exe")
    assert manifest(public).status_code == 503
    assert public.client.get(download_path(public.digest)).status_code == 503


def test_symlink_never_served(public):
    target = public.root / "actual.exe"
    public.path.rename(target)
    try:
        public.path.symlink_to(target)
    except OSError:
        pytest.skip("Windows symlink privilege unavailable")
    assert manifest(public).status_code == 503


def test_nonregular_source_is_rejected_before_open(public, monkeypatch):
    original = Path.lstat
    def nonregular(path, *args, **kwargs):
        value = original(path, *args, **kwargs)
        if path == public.path:
            fields = list(value)
            fields[0] = stat.S_IFIFO | 0o600
            return os.stat_result(fields)
        return value
    monkeypatch.setattr(Path, "lstat", nonregular)
    monkeypatch.setattr(os, "open", lambda *args, **kwargs: pytest.fail("opened nonregular file"))
    store = PublicNativeStore(public.config, public.database)
    with pytest.raises(HTTPException) as error:
        store.ready()
    assert error.value.status_code == 503


@pytest.mark.parametrize("change", ["disabled", "not-registered", "too-small-cap", "invalid-cap", "relative", "overlap"])
def test_disabled_invalid_and_protected_roots_fail_closed(public, change):
    config = public.config
    if change == "disabled": config = replace(config, public_native_dir="", plus_download_sha256="")
    elif change == "not-registered": update_policy(public, [public.value["binaries"][0]])
    elif change == "too-small-cap": config = replace(config, public_native_max_bytes=len(public.data) - 1)
    elif change == "invalid-cap": config = replace(config, public_native_max_bytes=MAX_BYTES + 1)
    elif change == "relative": config = replace(config, public_native_dir="relative")
    elif change == "overlap": config = replace(config, artifact_dir=str(public.root.parent))
    store = PublicNativeStore(config, public.database)
    with pytest.raises(HTTPException) as error:
        store.ready()
    assert error.value.status_code == 503


def test_withdrawal_during_initial_hash_and_warm_cache_is_checked(public, monkeypatch):
    store = PublicNativeStore(public.config, public.database)
    read = store._read_snapshot
    def withdraw_after_read(entry):
        data = read(entry)
        update_policy(public, [public.value["binaries"][0]])
        return data
    monkeypatch.setattr(store, "_read_snapshot", withdraw_after_read)
    with pytest.raises(HTTPException) as error:
        store.ready()
    assert error.value.status_code == 503
    # Cached bytes never override a subsequent policy removal either.
    with pytest.raises(HTTPException):
        store.ready()


def test_snapshot_is_read_once_and_replacement_does_not_change_served_bytes(public, monkeypatch):
    store = PublicNativeStore(public.config, public.database)
    first, sequence, entry = store.ready()
    assert b"".join(first.read_at(i) for i in range(0, len(first), CHUNK_BYTES)) == public.data
    public.path.write_bytes(b"x" * len(public.data))
    monkeypatch.setattr(store, "_read_snapshot", lambda _: pytest.fail("reopened verified source"))
    second, _, _ = store.ready()
    assert second is first
    assert b"".join(second.read_at(i) for i in range(0, len(second), CHUNK_BYTES)) == public.data
    update_policy(public, [public.value["binaries"][0]])
    with pytest.raises(HTTPException):
        store.ready()


def test_snapshot_concurrent_offsets_and_unlinked_source_remain_exact(public):
    store = PublicNativeStore(public.config, public.database)
    snapshot, _, _ = store.ready()
    public.path.unlink()
    offsets = list(range(0, len(snapshot), CHUNK_BYTES))
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(snapshot.read_at, (offsets + list(reversed(offsets))) * 20))
    expected = [public.data[i:i + CHUNK_BYTES] for i in (offsets + list(reversed(offsets))) * 20]
    assert results == expected
    assert snapshot.read_at(len(snapshot)) == b""
    for invalid in (-1, len(snapshot) + 1):
        with pytest.raises(ValueError): snapshot.read_at(invalid)
    if os.name != "nt":
        info = os.fstat(snapshot._file.fileno())
        assert info.st_nlink == 0 and stat.S_IMODE(info.st_mode) == 0o600


def test_snapshot_large_file_has_bounded_heap(public):
    # Allocate fixture before tracing; the snapshot itself must not retain bytes.
    data = bytes(range(256)) * (8 * 1024 * 1024 // 256)
    digest = hashlib.sha256(data).hexdigest()
    (public.root / (digest + ".exe")).write_bytes(data)
    store = PublicNativeStore(public.config, public.database)
    tracemalloc.start()
    try:
        snapshot = store._read_snapshot({"sha256": digest, "size": len(data)})
        retained, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    assert retained < 256 * 1024 and peak < 1024 * 1024
    assert len(snapshot) == len(data)
    assert snapshot.read_at(0) == data[:CHUNK_BYTES]


def test_failed_snapshot_closes_private_temp_file(public, monkeypatch):
    import app.native_download as download
    created = []
    original = download.tempfile.TemporaryFile
    def record(*args, **kwargs):
        file = original(*args, **kwargs)
        created.append(file)
        return file
    monkeypatch.setattr(download.tempfile, "TemporaryFile", record)
    public.path.write_bytes(b"x" * len(public.data))
    with pytest.raises(HTTPException):
        PublicNativeStore(public.config, public.database).ready()
    assert len(created) == 1 and created[0].closed


@pytest.mark.parametrize("finish", ["success", "send-error", "cancel", "disconnect"])
def test_bounded_transfer_slots_release_on_every_exit(public, finish):
    store = PublicNativeStore(public.config, public.database)
    first = store.response(public.digest)
    second = store.response(public.digest)
    with pytest.raises(HTTPException) as error:
        store.response(public.digest)
    assert error.value.status_code == 429
    seen = []
    async def exercise(response, mode):
        async def receive():
            return {"type": "http.disconnect"}
        async def send(message):
            if message["type"] == "http.response.body" and message.get("body"):
                if mode == "send-error": raise OSError("local disconnected transport")
                if mode == "cancel": raise asyncio.CancelledError()
                seen.append(message["body"])
        scope = {"type": "http", "asgi": {"spec_version": "2.3" if mode == "disconnect" else "2.4"}}
        try:
            await response(scope, receive, send)
        except (OSError, asyncio.CancelledError):
            pass
        except Exception as exc:
            from starlette.requests import ClientDisconnect
            assert mode == "send-error" and isinstance(exc, ClientDisconnect)
    asyncio.run(exercise(first, finish))
    third = store.response(public.digest)
    asyncio.run(exercise(second, "success"))
    asyncio.run(exercise(third, "success"))
    assert all(isinstance(chunk, memoryview) and len(chunk) <= CHUNK_BYTES for chunk in seen)
    assert store._slots.acquire(blocking=False) and store._slots.acquire(blocking=False)
    assert not store._slots.acquire(blocking=False)
    store._slots.release(); store._slots.release()


def test_unavailable_file_releases_transfer_slot(public):
    store = PublicNativeStore(public.config, public.database)
    public.path.unlink()
    for _ in range(3):
        with pytest.raises(HTTPException) as error:
            store.response(public.digest)
        assert error.value.status_code == 503


def test_stalled_transfer_is_bounded_and_releases_slot(public, monkeypatch):
    monkeypatch.setattr("app.native_download.TRANSFER_TIMEOUT_SECONDS", 0.02)
    store = PublicNativeStore(public.config, public.database)
    response = store.response(public.digest)
    async def exercise():
        async def send(_):
            await asyncio.Event().wait()
        async def receive():
            await asyncio.Event().wait()
        with pytest.raises(TimeoutError):
            await response({"type": "http", "asgi": {"spec_version": "2.4"}}, receive, send)
    asyncio.run(exercise())
    assert store._slots.acquire(blocking=False) and store._slots.acquire(blocking=False)
    store._slots.release(); store._slots.release()


def test_binary_rate_limit_separate_from_metadata_and_game_auth(public):
    for _ in range(60):
        assert public.client.get(download_path(public.digest)).status_code == 200
    response = public.client.get(download_path(public.digest))
    assert response.status_code == 429 and int(response.headers["Retry-After"]) > 0
    assert manifest(public).status_code == 200
    # Public access cannot mint a license or remove protected game gates.
    assert public.client.get("/v1/beta/manifest").status_code == 401
    for artifact in public.catalog["artifacts"]:
        url = f"/v1/beta/artifacts/{public.catalog['release_id']}/{artifact['id']}"
        assert public.client.get(url).status_code == 401
    assert public.client.get("/v1/blob/" + public.config.chunk_a_id).status_code == 401
    assert public.client.get("/v1/bootstrap/" + public.config.bootstrap_id).status_code == 401


def test_manifest_rate_limit_and_replay_not_an_access_grant(public):
    response = manifest(public)
    token = response.json()["manifest"]
    for _ in range(59):
        assert manifest(public).status_code == 200
    assert manifest(public).status_code == 429
    assert public.client.get(download_path(public.digest)).status_code == 200
    assert public.client.get("/v1/beta/manifest", headers={"Authorization": "Bearer " + token}).status_code == 401


def test_public_download_env_defaults_and_validation(public, monkeypatch):
    values = {
        "CELESTE_ALLOWED_HOSTS": "testserver", "CELESTE_RELEASE_ID": "fixture", "CELESTE_CLIENT_VERSION": "1",
        "CELESTE_RELEASE_SHA256": "a" * 64, "CELESTE_CHUNK_A_SHA256": "a" * 64, "CELESTE_CHUNK_B_SHA256": "b" * 64,
        "CELESTE_BOOTSTRAP_SHA256": "b" * 64, "CELESTE_LOADER_SHA256": "c" * 64,
        "CELESTE_LOADER_THREE_SHA256": "d" * 64, "CELESTE_LOADER_FOUR_SHA256": "e" * 64,
        "CELESTE_SIGNING_PRIVATE_KEY": b64e(bytes(range(32))), "CELESTE_KEY_PEPPER": b64e(bytes(range(32))),
        "CELESTE_SESSION_SECRET": b64e(bytes(range(32))), "CELESTE_ADMIN_PASSWORD_HASH": "fixture",
        "CELESTE_CHUNK_A_ID": "a", "CELESTE_CHUNK_B_ID": "b", "CELESTE_BOOTSTRAP_ID": "b",
        "CELESTE_LOADER_ID": "l", "CELESTE_LOADER_THREE_ID": "l3", "CELESTE_LOADER_FOUR_ID": "l4",
        "CELESTE_INSTALLER_COMPAT_SHA256": "a" * 64,
        "CELESTE_PUBLIC_NATIVE_DIR": "", "CELESTE_PLUS_DOWNLOAD_SHA256": "", "CELESTE_PUBLIC_NATIVE_MAX_BYTES": str(128 * 1024 * 1024),
    }
    for name, value in values.items(): monkeypatch.setenv(name, value)
    assert Settings.from_env().public_native_dir == ""
    monkeypatch.setenv("CELESTE_PUBLIC_NATIVE_DIR", str(public.root))
    with pytest.raises(RuntimeError): Settings.from_env()
    monkeypatch.setenv("CELESTE_PLUS_DOWNLOAD_SHA256", public.digest)
    configured = Settings.from_env()
    assert configured.plus_download_sha256 == public.digest
    for bad in ("0", "-1", str(MAX_BYTES + 1), "nonsense"):
        monkeypatch.setenv("CELESTE_PUBLIC_NATIVE_MAX_BYTES", bad)
        with pytest.raises(RuntimeError): Settings.from_env()
    monkeypatch.setenv("CELESTE_PUBLIC_NATIVE_MAX_BYTES", str(MAX_BYTES))
    for bad in ("relative", ""):
        monkeypatch.setenv("CELESTE_PUBLIC_NATIVE_DIR", bad)
        with pytest.raises(RuntimeError): Settings.from_env()
    monkeypatch.setenv("CELESTE_PUBLIC_NATIVE_DIR", str(public.root))
    monkeypatch.setenv("CELESTE_PLUS_DOWNLOAD_SHA256", public.digest.upper())
    with pytest.raises(RuntimeError): Settings.from_env()
