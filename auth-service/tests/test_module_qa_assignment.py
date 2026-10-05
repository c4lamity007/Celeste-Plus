"""Offline same-origin QA assignment fixtures; no live installation IDs."""
from dataclasses import replace
from pathlib import Path
from types import SimpleNamespace
import hashlib
import json
import secrets
import shutil

import pytest
from fastapi.testclient import TestClient

from app.main import create_app
from app.beta_delivery import beta_runtime_catalog
from app.module_delivery import executable_root
from app.security import canonical_json
from test_module_delivery import modules, env, v2, auth, session, post_session, call, authorize
from test_dpop import proof
from test_protocol_v2 import encrypted_request, decrypt, access, HWID


@pytest.fixture
def qa(modules, tmp_path):
    root = tmp_path / "qa-candidate"
    root.mkdir()
    beta_root = Path(modules.config.beta_release_dir)
    for filename in ("beta-release.json", "bootstrap-a.jar", "bootstrap-b.bin", "payload-a.bin", "payload-b.bin"):
        shutil.copyfile(beta_root / filename, root / filename)
    beta = json.loads((root / "beta-release.json").read_bytes())
    beta.update(release_id="qa-modular-candidate", runtime_sha256="c" * 64)
    (root / "beta-release.json").write_bytes(canonical_json(beta))
    shutil.copytree(Path(modules.config.module_release_dir) / "modules", root / "modules")
    catalog = {**modules.catalog, "release_id": beta["release_id"], "runtime_sha256": beta["runtime_sha256"]}
    catalog["executable_root_sha256"] = executable_root(catalog)
    (root / "module-release.json").write_bytes(canonical_json(catalog))
    settings = replace(modules.config, module_release_dir="", module_delivery_required=False,
        plus_qa_beta_release_dir=str(root), plus_qa_module_release_dir=str(root),
        plus_qa_installations=frozenset({modules.enrolled["device_id"]}))
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        e = SimpleNamespace(**{**vars(modules), "config": settings, "app": app, "client": client,
            "catalog": catalog, "digest": hashlib.sha256(canonical_json(catalog)).hexdigest()})
        assert auth(e).status_code == 200
        e.session_body = session(e)
        response = post_session(e, e.session_body)
        assert response.status_code == 200, response.text
        e.session_token = response.json()["session_token"]
        yield e


def manifest(e, token):
    path = "/v1/beta/manifest"
    response = e.client.get(path, headers={"Authorization": "DPoP " + token,
        "X-Celeste-Request-Nonce": secrets.token_urlsafe(24),
        "DPoP": proof(e.install, "GET", "https://testserver" + path, token)})
    assert response.status_code == 200, response.text
    signed = response.json()["manifest"]
    return signed, e.app.state.signer.verify(signed)


def test_assigned_installation_gets_exact_paired_candidate_everywhere(qa):
    installer = auth(qa).json()["grant"]
    signed, value = manifest(qa, installer)
    assert value["release_id"] == qa.catalog["release_id"]
    assert auth(qa, scope="auth").status_code == 200
    for artifact in value["artifacts"]:
        private, headers = encrypted_request(qa, installer, artifact["url"], signed)
        response = qa.client.get(artifact["url"], headers=headers)
        assert response.status_code == 200, response.text
        assert hashlib.sha256(decrypt(response.content, private, installer, artifact["url"], headers)).hexdigest() == artifact["sha256"]
    granted = authorize(qa)["capabilities"][0]
    assert call(qa, granted["url"], capability=granted["capability"])[0].status_code == 200


def test_unassigned_installation_keeps_default_and_cannot_choose_candidate(qa):
    settings = replace(qa.config, plus_qa_installations=frozenset())
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        e = SimpleNamespace(**{**vars(qa), "config": settings, "app": app, "client": client})
        token = auth(e).json()["grant"]
        _, value = manifest(e, token)
        assert value["release_id"] != qa.catalog["release_id"]
        # Signed candidate heartbeat and module lease from before reassignment
        # cannot select the old QA release by supplying its identities.
        assert post_session(e, session(qa)).status_code == 409
        assert call(e, "/v1/modules/catalog")[0].status_code == 409
        assert auth(e, scope="auth").status_code == 200
        response = post_session(e, session(e))
        assert response.status_code == 200
        e.session_token = response.json()["session_token"]
        e.catalog = {**e.catalog, "runtime_sha256": app.state.signer.verify(e.session_token)["client_sha256"]}
        assert call(e, "/v1/modules/catalog")[0].status_code == 404


def test_assigned_installation_cannot_downgrade_to_legacy_runtime(qa):
    default = replace(qa.config, plus_qa_installations=frozenset())
    old = SimpleNamespace(**{**vars(qa), "config": default})
    assert post_session(qa, session(old)).status_code == 409
    assert auth(old, scope="auth").status_code == 409
    assert call(qa, "/v1/modules/catalog")[0].status_code == 200


def test_assignment_is_device_specific_not_account_wide(qa):
    with qa.app.state.database.connect() as connection:
        row = connection.execute("SELECT * FROM devices WHERE device_id=?", (qa.enrolled["device_id"],)).fetchone()
        columns = [name for name in row.keys()]
        other = dict(row)
        other["device_id"] = "Z" * 32
        other["install_signing_key"] = bytes(range(32))
        connection.execute("INSERT INTO devices(" + ",".join(columns) + ") VALUES(" + ",".join("?" for _ in columns) + ")", tuple(other[name] for name in columns))
    assigned = beta_runtime_catalog(qa.config, qa.uid, qa.enrolled["device_id"])
    other = beta_runtime_catalog(qa.config, qa.uid, "Z" * 32)
    assert assigned["release_id"] != other["release_id"]
    with pytest.raises(Exception) as denied:
        beta_runtime_catalog(qa.config, qa.uid + 1, qa.enrolled["device_id"])
    assert denied.value.status_code in {401, 403}


@pytest.mark.parametrize("change", ["partial", "wrong-pair", "missing-key", "protocol", "overlap", "bad-id"])
def test_qa_startup_rejects_unusable_or_crossed_contracts(qa, change):
    settings = qa.config
    if change == "partial": settings = replace(settings, plus_qa_module_release_dir="")
    elif change == "wrong-pair": settings = replace(settings, plus_qa_module_release_dir=qa.config.beta_release_dir)
    elif change == "missing-key": settings = replace(settings, module_key_encryption_key=None)
    elif change == "protocol": settings = replace(settings, plus_auth_protocol=1)
    elif change == "overlap": settings = replace(settings, plus_qa_beta_release_dir=settings.beta_release_dir)
    else: settings = replace(settings, plus_qa_installations=frozenset({"untrusted"}))
    with pytest.raises(RuntimeError): create_app(settings)


def test_old_manifest_cannot_fetch_reassigned_release_artifacts(qa):
    installer = auth(qa).json()["grant"]
    signed, value = manifest(qa, installer)
    settings = replace(qa.config, plus_qa_installations=frozenset())
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        e = SimpleNamespace(**{**vars(qa), "config": settings, "app": app, "client": client})
        artifact = value["artifacts"][0]
        _, headers = encrypted_request(e, installer, artifact["url"], signed)
        assert client.get(artifact["url"], headers=headers).status_code in {401, 404}


def _post_runtime_grant(e, path, token):
    return e.client.post(path, headers={**access(e, path, token, hwid=HWID), "X-Device-Id": HWID})


def _fresh_runtime_grant(e):
    response = auth(e, scope="auth")
    assert response.status_code == 200, response.text
    return response.json()["grant"]


def _assert_runtime_validation(e, path, token, expected_catalog):
    original = e.app.state.signer.verify(token)
    assert original["release"] == expected_catalog["release_id"]
    assert original["client_sha256"] == expected_catalog["runtime_sha256"]
    response = _post_runtime_grant(e, path, token)
    assert response.status_code == 200, response.text
    assert response.json()["status"] == "valid"
    current = token
    if path == "/v1/rotate":
        current = response.json()["grant"]
        renewed = e.app.state.signer.verify(current)
        for key in ("sub", "device", "product", "cnf", "release", "client_version", "client_sha256",
                    "beta_catalog_sha256", "auth_protocol"):
            assert renewed[key] == original[key]
        assert renewed["jti"] != original["jti"]
        old = _post_runtime_grant(e, "/v1/validate", token)
        assert old.status_code == 401 and old.json()["error_code"] == "token_rotated"
    else:
        assert "grant" not in response.json()
    validated = _post_runtime_grant(e, "/v1/validate", current)
    assert validated.status_code == 200, validated.text


@pytest.mark.parametrize("path", ["/v1/validate", "/v1/rotate"])
def test_assigned_runtime_grant_validation_and_rotation_select_device_candidate(qa, path):
    # This is the native injection startup sequence: auth/complete then validate,
    # before session/check or independently delivered modules can start.
    token = _fresh_runtime_grant(qa)
    _assert_runtime_validation(qa, path, token, qa.catalog)


@pytest.mark.parametrize("path", ["/v1/validate", "/v1/rotate"])
def test_unassigned_runtime_grant_validation_and_rotation_keep_public_catalog(qa, path):
    settings = replace(qa.config, plus_qa_installations=frozenset())
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        e = SimpleNamespace(**{**vars(qa), "config": settings, "app": app, "client": client})
        catalog = beta_runtime_catalog(settings, e.uid, e.enrolled["device_id"])
        assert catalog["release_id"] != qa.catalog["release_id"]
        token = _fresh_runtime_grant(e)
        _assert_runtime_validation(e, path, token, catalog)


@pytest.mark.parametrize("path", ["/v1/validate", "/v1/rotate"])
def test_assigned_runtime_rejects_legitimately_issued_public_catalog_grant(qa, path):
    # Issue a real registered grant under the previous public assignment. Changing
    # assignment must invalidate it; rewriting token claims is not the test.
    settings = replace(qa.config, plus_qa_installations=frozenset())
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        previous = SimpleNamespace(**{**vars(qa), "config": settings, "app": app, "client": client})
        public = _fresh_runtime_grant(previous)
    claims = qa.app.state.signer.verify(public)
    assert claims["release"] != qa.catalog["release_id"]
    response = _post_runtime_grant(qa, path, public)
    assert response.status_code == 401 and response.json()["error_code"] == "invalid_grant"
    assert "grant" not in response.json()
    with qa.app.state.database.connect() as connection:
        state = connection.execute("SELECT status,rotated_to FROM active_tokens WHERE jti=?", (claims["jti"],)).fetchone()
        assert tuple(state) == ("active", None)


@pytest.mark.parametrize("path", ["/v1/validate", "/v1/rotate"])
@pytest.mark.parametrize("revocation,code", [("device", "unauthorized"),
    ("license", "subscription_inactive"), ("grant", "token_revoked")])
def test_assigned_runtime_validation_and_rotation_keep_revocation_guards(qa, path, revocation, code):
    token = _fresh_runtime_grant(qa)
    claims = qa.app.state.signer.verify(token)
    with qa.app.state.database.connect() as connection:
        count = connection.execute("SELECT COUNT(*) FROM active_tokens").fetchone()[0]
        if revocation == "device":
            connection.execute("UPDATE devices SET status='revoked' WHERE device_id=?", (claims["device"],))
        elif revocation == "license":
            connection.execute("UPDATE licenses SET status='revoked' WHERE uid=?", (int(claims["sub"]),))
        else:
            connection.execute("UPDATE active_tokens SET status='revoked' WHERE jti=?", (claims["jti"],))
    response = _post_runtime_grant(qa, path, token)
    assert response.status_code == 401 and response.json()["error_code"] == code, response.text
    assert "grant" not in response.json()
    with qa.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM active_tokens").fetchone()[0] == count
        assert connection.execute("SELECT rotated_to FROM active_tokens WHERE jti=?", (claims["jti"],)).fetchone()[0] is None
