"""Local beta-delivery fixtures only; no real licenses, keys or release files."""
from __future__ import annotations

import hashlib
import json
import time
from dataclasses import replace
from types import SimpleNamespace

import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519
from dpop_client import TestClient, register_key

import app.beta_delivery as beta_delivery
from app.beta_delivery import ARTIFACT_FILENAMES, ARTIFACT_ROLES, MANIFEST_TTL_SECONDS, RELEASE_SCHEMA
from app.config import Settings
from app.main import create_app
from app.security import GrantSigner, b64d, b64e, hash_license
from app.dpop import confirmation
from test_protocol import enroll, hardware, release, settings, raw_public


OWNER = 7
NONOWNER = 8
OWNER_DEVICE = "00000000-0000-0000-0000-000000000007"
OTHER_DEVICE = "00000000-0000-0000-0000-000000000008"


def write_catalog(root, catalog):
    (root / "beta-release.json").write_text(json.dumps(catalog), encoding="utf-8")


def stage_beta(root):
    root.mkdir()
    catalog = {"schema": RELEASE_SCHEMA, "release_id": "owner-beta-test-1", "client_version": "1.0.0-beta.1",
               "runtime_sha256": "d" * 64, "artifacts": []}
    for artifact_id, role in ARTIFACT_ROLES.items():
        content = f"isolated test bytes: {artifact_id}".encode()
        filename = ARTIFACT_FILENAMES[artifact_id]
        (root / filename).write_bytes(content)
        catalog["artifacts"].append({
            "id": artifact_id, "role": role, "filename": filename,
            "sha256": hashlib.sha256(content).hexdigest(), "size": len(content),
        })
    write_catalog(root, catalog)
    return catalog


@pytest.fixture
def beta(tmp_path):
    root = tmp_path / "beta"
    catalog = stage_beta(root)
    config = replace(settings(tmp_path), plus_enabled=True, beta_release_dir=str(root))
    app = create_app(config)
    install_keys = {uid: register_key(ed25519.Ed25519PrivateKey.from_private_bytes(bytes([uid]) * 32)) for uid in (OWNER, NONOWNER)}
    with app.state.database.connect() as connection:
        for uid, device, label in ((OWNER, OWNER_DEVICE, "ordinary display label"), (NONOWNER, OTHER_DEVICE, "Owner")):
            connection.execute(
                "INSERT INTO licenses(uid,key_hash,label,status,max_devices,created_at) VALUES(?,?,?,'active',1,1)",
                (uid, bytes([uid]) * 32, label),
            )
            connection.execute("INSERT INTO license_entitlements VALUES(?,'celeste',1)", (uid,))
            if uid == OWNER:
                connection.execute("INSERT INTO license_entitlements VALUES(?,'celeste_plus',1)", (uid,))
            connection.execute(
                """INSERT INTO devices(device_id,license_uid,install_signing_key,token_signing_key,
                   hardware_core_hash,monitor_hash,status,created_at) VALUES(?,?,?,?,?,?,'active',1)""",
                (device, uid, b64d(raw_public(install_keys[uid])), *([bytes([uid]) * 32] * 3)),
            )
    now = int(time.time())
    claims = {
        "aud": "celeste-installer", "product": "celeste_plus", "release": config.release_id,
        "client_sha256": config.release_sha256, "payload_release": config.release_id,
        "payload_sha256": config.release_sha256, "bootstrap": config.bootstrap_id,
        "bootstrap_sha256": config.bootstrap_sha256, "sub": str(OWNER), "device": OWNER_DEVICE,
        "jti": "isolated-installer-ticket-7", "iat": now, "nbf": now - 5, "exp": now + 600,
        "cnf": confirmation(b64d(raw_public(install_keys[OWNER]))),
    }
    token = app.state.signer.sign(claims)
    with TestClient(app, base_url="https://testserver") as client:
        yield SimpleNamespace(root=root, catalog=catalog, config=config, app=app, client=client,
                              claims=claims, token=token, install_keys=install_keys, headers={"Authorization": f"Bearer {token}"})


def normal_headers(beta):
    return {"Authorization": "Bearer " + beta.app.state.signer.sign({**beta.claims, "product": "celeste"})}


def manifest_headers(beta, token=None):
    headers = {"Authorization": f"Bearer {token or beta.token}"}
    response = beta.client.get("/v1/beta/manifest", headers=headers)
    assert response.status_code == 200, response.text
    assert response.headers["cache-control"] == "no-store"
    return {**headers, "X-Celeste-Beta-Manifest": response.json()["manifest"]}


def artifact_url(beta, artifact_id="payload-a"):
    return f"/v1/beta/artifacts/{beta.catalog['release_id']}/{artifact_id}"


def resign_installer(beta, **updates):
    return beta.app.state.signer.sign({**beta.claims, **updates})


def test_signed_owner_manifest_and_exact_four_separate_downloads(beta):
    headers = manifest_headers(beta)
    token = headers["X-Celeste-Beta-Manifest"]
    manifest = beta.app.state.signer.verify(token)
    first, second, signature = token.split(".")
    public = ed25519.Ed25519PublicKey.from_public_bytes(b64d(beta.app.state.signer.public_key_b64(), 32))
    public.verify(b64d(signature, 64), f"{first}.{second}".encode())
    assert manifest["sub"] == "7"
    assert manifest["device"] == OWNER_DEVICE
    assert manifest["exp"] <= beta.claims["exp"]
    assert 0 < manifest["exp"] - manifest["iat"] <= MANIFEST_TTL_SECONDS
    assert manifest["installer_sha256"] == hashlib.sha256(beta.token.encode()).hexdigest()
    assert set(item["id"] for item in manifest["artifacts"]) == set(ARTIFACT_ROLES)
    assert sum(item["role"] == "bootstrap" for item in manifest["artifacts"]) == 2
    assert sum(item["role"] == "payload" for item in manifest["artifacts"]) == 2
    assert len({item["url"] for item in manifest["artifacts"]}) == 4
    for item in manifest["artifacts"]:
        assert item["url"] == artifact_url(beta, item["id"])
        assert set(item) == {"id", "role", "sha256", "size", "url"}
        response = beta.client.get(item["url"], headers=headers)
        assert response.status_code == 200
        assert response.headers["cache-control"] == "no-store"
        assert response.content == (beta.root / ARTIFACT_FILENAMES[item["id"]]).read_bytes()
        assert len(response.content) == item["size"]
        assert hashlib.sha256(response.content).hexdigest() == item["sha256"]
    # Production still accepts only its original grant/URL, with identical bytes.
    production = beta.client.get(f"/v1/bootstrap/{beta.config.bootstrap_id}", headers=normal_headers(beta))
    assert production.status_code == 200
    assert production.content == b"mods folder bootstrap"
    assert beta.client.get(f"/v1/bootstrap/bootstrap-a", headers=headers).status_code == 401


@pytest.mark.parametrize("enabled,directory", [(False, ""), (False, "configured"), (True, "")])
def test_disabled_unless_both_explicit_settings_exist(beta, enabled, directory):
    config = replace(beta.config, plus_enabled=enabled, beta_release_dir=str(beta.root) if directory else "")
    with TestClient(create_app(config), base_url="https://testserver") as client:
        assert client.get("/v1/beta/manifest", headers=beta.headers).status_code == 404
        assert client.get(artifact_url(beta), headers=beta.headers).status_code == 404
        assert client.get(f"/v1/bootstrap/{config.bootstrap_id}", headers=normal_headers(beta)).status_code == 200


def test_display_label_does_not_grant_owner_access(beta):
    token = resign_installer(beta, sub=str(NONOWNER), device=OTHER_DEVICE)
    headers = {"Authorization": f"Bearer {token}"}
    assert beta.client.get("/v1/beta/manifest", headers=headers).status_code == 403
    assert beta.client.get(artifact_url(beta), headers=headers).status_code == 403


@pytest.mark.parametrize("changes", [
    {"device": OTHER_DEVICE}, {"device": "missing-device-00000000000000000000"},
    {"device": []}, {"sub": "8"}, {"sub": 7}, {"sub": "007"},
    {"aud": "celeste-client"}, {"aud": "celeste-beta-manifest"}, {"release": "wrong"},
    {"client_sha256": "b" * 64}, {"client_sha256": []}, {"payload_release": "wrong"},
    {"payload_sha256": "b" * 64}, {"bootstrap": "wrong"}, {"bootstrap_sha256": "b" * 64},
    {"jti": "short"}, {"jti": []}, {"exp": 1}, {"exp": "9999999999"}, {"exp": []},
    {"nbf": 9_999_999_999},
])
def test_malformed_or_mismatched_installer_grants_denied(beta, changes):
    headers = {"Authorization": f"Bearer {resign_installer(beta, **changes)}"}
    assert beta.client.get("/v1/beta/manifest", headers=headers).status_code in {401, 403}
    assert beta.client.get(artifact_url(beta), headers=headers).status_code in {401, 403}


@pytest.mark.parametrize("token", ["", "bad", "a.b.c", "x" * 20_000])
def test_missing_or_bad_signatures_fail_closed(beta, token):
    headers = {"Authorization": f"Bearer {token}"} if token else {}
    assert beta.client.get("/v1/beta/manifest", headers=headers).status_code == 401
    assert beta.client.get(artifact_url(beta), headers=headers).status_code == 401


def test_altered_valid_signature_and_foreign_signer_denied(beta):
    parts = beta.token.split(".")
    parts[2] = b64e(bytes(64))
    foreign = GrantSigner(bytes(reversed(range(32)))).sign(beta.claims)
    for token in (".".join(parts), beta.token[:-5], foreign):
        assert beta.client.get("/v1/beta/manifest", headers={"Authorization": f"Bearer {token}"}).status_code == 401


@pytest.mark.parametrize("mutation", ["license_revoke", "device_revoke", "expire", "reset", "reassign"])
def test_live_status_rechecked_on_every_request(beta, mutation):
    headers = manifest_headers(beta)
    with beta.app.state.database.connect() as connection:
        if mutation == "license_revoke":
            connection.execute("UPDATE licenses SET status='revoked' WHERE uid=7")
        elif mutation == "device_revoke":
            connection.execute("UPDATE devices SET status='revoked' WHERE device_id=?", (OWNER_DEVICE,))
        elif mutation == "expire":
            connection.execute("UPDATE licenses SET expires_at=? WHERE uid=7", (int(time.time()) - 1,))
        elif mutation == "reset":
            connection.execute("DELETE FROM devices WHERE device_id=?", (OWNER_DEVICE,))
        else:
            connection.execute("UPDATE devices SET license_uid=8 WHERE device_id=?", (OWNER_DEVICE,))
    assert beta.client.get("/v1/beta/manifest", headers=headers).status_code == 401
    for artifact_id in ARTIFACT_ROLES:
        assert beta.client.get(artifact_url(beta, artifact_id), headers=headers).status_code == 401


def test_installer_and_manifest_are_both_required_and_bound(beta):
    headers = manifest_headers(beta)
    url = artifact_url(beta)
    assert beta.client.get(url, headers=beta.headers).status_code == 401
    assert beta.client.get(url, headers={"X-Celeste-Beta-Manifest": headers["X-Celeste-Beta-Manifest"]}).status_code == 401
    assert beta.client.get(url, headers={**headers, "X-Celeste-Beta-Manifest": beta.token}).status_code == 401
    changed = {**headers, "Authorization": f"Bearer {resign_installer(beta, jti='another-valid-installer-ticket')}"}
    assert beta.client.get(url, headers=changed).status_code == 401
    # Even another grant with the same jti cannot reuse the manifest.
    changed["Authorization"] = f"Bearer {resign_installer(beta, exp=beta.claims['exp'] - 1)}"
    assert beta.client.get(url, headers=changed).status_code == 401


@pytest.mark.parametrize("changes", [
    {"aud": "celeste-installer"}, {"sub": "8"}, {"device": OTHER_DEVICE},
    {"catalog_sha256": "0" * 64}, {"release_id": "another-beta"}, {"artifacts": []},
    {"exp": 1}, {"iat": 9_999_999_999}, {"nbf": 9_999_999_999},
    {"iat": 1, "exp": 9_999_999_999}, {"exp": "9999999999"},
])
def test_signed_but_wrong_manifest_cannot_authorize_download(beta, changes):
    headers = manifest_headers(beta)
    claims = beta.app.state.signer.verify(headers["X-Celeste-Beta-Manifest"])
    headers["X-Celeste-Beta-Manifest"] = beta.app.state.signer.sign({**claims, **changes})
    assert beta.client.get(artifact_url(beta), headers=headers).status_code == 401


@pytest.mark.parametrize("mutation", [
    "traversal", "absolute", "backslash", "missing", "extra", "duplicate", "wrong_role",
    "url", "schema", "production_id", "release_traversal", "size", "bool_size", "digest",
    "corrupt", "remove_file", "duplicate_json", "malformed_json", "oversized_json",
])
def test_invalid_catalog_or_any_artifact_denies_entire_release(beta, mutation):
    headers = manifest_headers(beta)
    url = artifact_url(beta)
    catalog = beta.catalog
    first = catalog["artifacts"][0]
    if mutation == "traversal": first["filename"] = "../releases/celeste-bootstrap.jar"
    elif mutation == "absolute": first["filename"] = str(beta.root.parent / "releases" / "celeste-bootstrap.jar")
    elif mutation == "backslash": first["filename"] = "..\\releases\\celeste-bootstrap.jar"
    elif mutation == "missing": catalog["artifacts"].pop()
    elif mutation == "extra": catalog["artifacts"].append(dict(first))
    elif mutation == "duplicate": catalog["artifacts"][1] = dict(first)
    elif mutation == "wrong_role": first["role"] = "payload"
    elif mutation == "url": first["url"] = "https://foreign.invalid/leak"
    elif mutation == "schema": catalog["schema"] = "wrong"
    elif mutation == "production_id": catalog["release_id"] = beta.config.release_id
    elif mutation == "release_traversal": catalog["release_id"] = "../escape"
    elif mutation == "size": first["size"] += 1
    elif mutation == "bool_size": first["size"] = True
    elif mutation == "digest": first["sha256"] = "b" * 64
    elif mutation == "corrupt": (beta.root / first["filename"]).write_bytes(b"tampered")
    elif mutation == "remove_file": (beta.root / first["filename"]).unlink()
    write_catalog(beta.root, catalog)
    if mutation == "duplicate_json":
        text = json.dumps(catalog)
        (beta.root / "beta-release.json").write_text('{"schema":"wrong",' + text[1:])
    elif mutation == "malformed_json": (beta.root / "beta-release.json").write_text("{")
    elif mutation == "oversized_json": (beta.root / "beta-release.json").write_text(" " * 16_385)
    assert beta.client.get("/v1/beta/manifest", headers=beta.headers).status_code == 503
    assert beta.client.get(url, headers=headers).status_code == 503


def test_changed_but_valid_release_invalidates_previous_manifest(beta):
    headers = manifest_headers(beta)
    entry = beta.catalog["artifacts"][0]
    content = b"a different valid beta artifact"
    (beta.root / entry["filename"]).write_bytes(content)
    entry.update(size=len(content), sha256=hashlib.sha256(content).hexdigest())
    write_catalog(beta.root, beta.catalog)
    assert beta.client.get(artifact_url(beta), headers=headers).status_code == 401
    fresh_headers = manifest_headers(beta)
    assert beta.client.get(artifact_url(beta), headers=fresh_headers).status_code == 200


@pytest.mark.parametrize("relation", ["same", "child", "parent", "relative", "missing"])
def test_beta_directory_never_exposes_production_or_arbitrary_root(beta, relation):
    paths = {"same": beta.config.artifact_dir, "child": beta.root.parent / "releases" / "beta",
             "parent": beta.root.parent, "relative": "relative-beta", "missing": beta.root / "absent"}
    if relation == "child": paths["child"].mkdir()
    config = replace(beta.config, beta_release_dir=str(paths[relation]))
    with TestClient(create_app(config), base_url="https://testserver") as client:
        assert client.get("/v1/beta/manifest", headers=beta.headers).status_code == 503
        assert client.get(f"/v1/bootstrap/{config.bootstrap_id}", headers=normal_headers(beta)).status_code == 200


@pytest.mark.parametrize("suffix", ["../payload-a", "%2e%2e%2fpayload-a", "..%5cpayload-a", "chunk-a.bin", "beta-release.json"])
def test_url_traversal_and_unknown_artifact_cannot_read_files(beta, suffix):
    headers = manifest_headers(beta)
    response = beta.client.get(f"/v1/beta/artifacts/{beta.catalog['release_id']}/{suffix}", headers=headers)
    assert response.status_code == 404
    assert beta.client.get(f"/v1/beta/artifacts/{beta.config.release_id}/payload-a", headers=headers).status_code == 404


def test_manifest_lifetime_capped_by_installer_expiry(beta):
    token = resign_installer(beta, exp=int(time.time()) + 20)
    headers = manifest_headers(beta, token)
    manifest = beta.app.state.signer.verify(headers["X-Celeste-Beta-Manifest"])
    assert manifest["exp"] == beta.app.state.signer.verify(token)["exp"]


def test_real_local_enrollment_and_installer_dual_proof_issue_compatible_grant(beta):
    key = "CST-ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-77777"
    with beta.app.state.database.connect() as connection:
        connection.execute("DELETE FROM devices WHERE license_uid=7")
        connection.execute("UPDATE licenses SET key_hash=? WHERE uid=7", (hash_license(beta.config.key_pepper, key),))
    enrolled, install_key, token_key = enroll(beta.client, key)
    assert enrolled["uid"] == OWNER
    start = beta.client.post("/v1/installer/start", json={**release(), "product": "celeste_plus", "device_id": enrolled["device_id"], "hardware": hardware()})
    assert start.status_code == 200
    challenge = start.json()
    message = b64d(challenge["message"])
    complete = beta.client.post("/v1/installer/complete", json={
        "challenge_id": challenge["challenge_id"], "install_signature": b64e(install_key.sign(message)),
        "token_signature": b64e(token_key.sign(message)),
    })
    assert complete.status_code == 200
    headers = manifest_headers(beta, complete.json()["grant"])
    for artifact_id in ARTIFACT_ROLES:
        assert beta.client.get(artifact_url(beta, artifact_id), headers=headers).status_code == 200


def test_beta_settings_are_unset_by_default():
    assert Settings.__dataclass_fields__["plus_enabled"].default is False
    assert Settings.__dataclass_fields__["beta_release_dir"].default == ""


@pytest.mark.parametrize("link_kind", ["hardlink", "symlink"])
def test_links_cannot_expose_production_files_even_with_matching_digest(beta, link_kind):
    source = beta.root.parent / "releases" / "celeste-bootstrap.jar"
    entry = beta.catalog["artifacts"][0]
    target = beta.root / entry["filename"]
    target.unlink()
    try:
        if link_kind == "hardlink":
            target.hardlink_to(source)
        else:
            target.symlink_to(source)
    except OSError as exc:
        pytest.skip(f"Local filesystem does not allow {link_kind}: {exc.winerror if hasattr(exc, 'winerror') else exc.errno}")
    content = source.read_bytes()
    entry.update(size=len(content), sha256=hashlib.sha256(content).hexdigest())
    write_catalog(beta.root, beta.catalog)
    assert beta.client.get("/v1/beta/manifest", headers=beta.headers).status_code == 503


@pytest.mark.parametrize("enabled", ["true", "false", "1", "0", "", "7", "Owner"])
def test_plus_environment_settings_require_boolean_flag(beta, monkeypatch, enabled):
    for key, value in {
        "CELESTE_ALLOWED_HOSTS": "testserver", "CELESTE_RELEASE_ID": beta.config.release_id,
        "CELESTE_CLIENT_VERSION": beta.config.client_version, "CELESTE_RELEASE_SHA256": "a" * 64,
        "CELESTE_CHUNK_A_SHA256": "a" * 64, "CELESTE_CHUNK_B_SHA256": "b" * 64,
        "CELESTE_BOOTSTRAP_SHA256": "a" * 64, "CELESTE_LOADER_SHA256": "a" * 64,
        "CELESTE_LOADER_THREE_SHA256": "a" * 64, "CELESTE_LOADER_FOUR_SHA256": "a" * 64,
        "CELESTE_SIGNING_PRIVATE_KEY": b64e(bytes(32)), "CELESTE_KEY_PEPPER": b64e(bytes(32)),
        "CELESTE_SESSION_SECRET": b64e(bytes(32)), "CELESTE_ADMIN_PASSWORD_HASH": "isolated-test",
        "CELESTE_CHUNK_A_ID": "a", "CELESTE_CHUNK_B_ID": "b", "CELESTE_BOOTSTRAP_ID": "b",
        "CELESTE_LOADER_ID": "two", "CELESTE_LOADER_THREE_ID": "three", "CELESTE_LOADER_FOUR_ID": "four",
        "CELESTE_PLUS_ENABLED": enabled, "CELESTE_BETA_RELEASE_DIR": str(beta.root),
        "CELESTE_INSTALLER_COMPAT_SHA256": "a" * 64, "CELESTE_INSTALLER_CHANNEL_ID": "test",
        "CELESTE_INSTALLER_CHANNEL_VERSION": "1",
    }.items():
        monkeypatch.setenv(key, value)
    if enabled in {"true", "false", "1", "0"}:
        loaded = Settings.from_env()
        assert loaded.plus_enabled == (enabled in {"true", "1"})
        assert loaded.beta_release_dir == str(beta.root)
    else:
        with pytest.raises(RuntimeError, match="CELESTE_PLUS_ENABLED"):
            Settings.from_env()


def test_beta_requests_use_bounded_separate_rate_limit(beta):
    for _ in range(30):
        assert beta.client.get("/v1/beta/manifest").status_code == 401
    response = beta.client.get("/v1/beta/manifest", headers=beta.headers)
    assert response.status_code == 429
    assert int(response.headers["retry-after"]) > 0
    assert beta.client.get(artifact_url(beta), headers=beta.headers).status_code == 429
    assert beta.client.get(f"/v1/bootstrap/{beta.config.bootstrap_id}", headers=normal_headers(beta)).status_code == 200


@pytest.mark.parametrize("limit", ["MAX_ARTIFACT_BYTES", "MAX_RELEASE_BYTES"])
def test_beta_size_limits_fail_closed(beta, monkeypatch, limit):
    headers = manifest_headers(beta)
    monkeypatch.setattr(beta_delivery, limit, 1 if limit == "MAX_ARTIFACT_BYTES" else 100)
    assert beta.client.get("/v1/beta/manifest", headers=beta.headers).status_code == 503
    assert beta.client.get(artifact_url(beta), headers=headers).status_code == 503


@pytest.mark.parametrize("endpoint", ["manifest", "artifact"])
def test_revocation_during_integrity_validation_is_rechecked_before_response(beta, monkeypatch, endpoint):
    headers = manifest_headers(beta)
    original = beta_delivery._read_release

    def revoke_after_read(*args, **kwargs):
        result = original(*args, **kwargs)
        with beta.app.state.database.connect() as connection:
            connection.execute("UPDATE licenses SET status='revoked' WHERE uid=7")
        return result

    monkeypatch.setattr(beta_delivery, "_read_release", revoke_after_read)
    url = "/v1/beta/manifest" if endpoint == "manifest" else artifact_url(beta)
    assert beta.client.get(url, headers=headers).status_code == 401


def test_download_returns_verified_snapshot_not_a_later_file_read(beta, monkeypatch):
    headers = manifest_headers(beta)
    original = beta_delivery._read_release
    expected = (beta.root / "payload-a.bin").read_bytes()

    def change_file_after_read(*args, **kwargs):
        result = original(*args, **kwargs)
        (beta.root / "payload-a.bin").write_bytes(b"unchecked replacement")
        return result

    monkeypatch.setattr(beta_delivery, "_read_release", change_file_after_read)
    response = beta.client.get(artifact_url(beta), headers=headers)
    assert response.status_code == 200
    assert response.content == expected
    assert beta.client.get(artifact_url(beta), headers=headers).status_code == 503
