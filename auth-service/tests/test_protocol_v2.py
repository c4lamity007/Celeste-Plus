"""Real v2 HTTP gates with isolated database/keys; no production credentials."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import hashlib
import json
import secrets
import time
from types import SimpleNamespace

import pytest
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from fastapi.testclient import TestClient

from app.main import create_app
from app.beta_delivery import beta_runtime_catalog
from app.security import b64d, b64e, canonical_json
from dpop_client import proof
from test_dpop import env
from test_protocol import hardware, release, raw_public

HWID = "6" * 64
CORE = hashlib.sha256(canonical_json({k: hardware()[k] for k in ("system", "board", "processor")})).hexdigest()


@pytest.fixture
def v2(env):
    config = replace(env.config, plus_auth_protocol=2, tls_leaf_sha256=("a" * 64, "b" * 64))
    app = create_app(config)
    with TestClient(app, base_url="https://testserver") as client:
        yield SimpleNamespace(**{**vars(env), "app": app, "client": client, "config": config})


def auth(e, *, scope="installer", hwid=HWID, complete=True):
    body = {**release(), "product": "celeste_plus", "device_id": e.enrolled["device_id"], "hardware": hardware()}
    if hwid is not None:
        body["hwid_sha256"] = hwid
    if scope == "auth":
        catalog = beta_runtime_catalog(e.config, e.uid)
        body.update(release_id=catalog["release_id"], client_version=catalog["client_version"],
                    client_sha256=catalog["runtime_sha256"], beta_catalog_sha256=catalog["catalog_sha256"])
    start = e.client.post(f"/v1/{scope}/start", json=body)
    if start.status_code != 200:
        return start
    challenge = start.json()
    message = b64d(challenge["message"])
    decoded = json.loads(message)
    assert decoded["protocol"] == ("cst-auth-2" if hwid else "cst-auth-1")
    assert decoded.get("hwid_sha256") == hwid
    body = {"challenge_id": challenge["challenge_id"], "install_signature": b64e(e.install.sign(message)),
            "token_signature": b64e(e.token.sign(message))}
    if not complete:
        return body
    path = f"/v1/{scope}/complete"
    return e.client.post(path, json=body, headers={"DPoP": proof(e.install, "POST", "https://testserver" + path)})


def access(e, path, token, **extensions):
    return {"Content-Type": "application/json", "Authorization": "DPoP " + token, "DPoP": proof(e.install, "POST", "https://testserver" + path, token,
                                                             payload_updates=extensions)}


def session(e, *, core=CORE, hwid=HWID, pid=1234, identity=None, nonce=None):
    catalog = beta_runtime_catalog(e.config, e.uid)
    body = {**release(), "product": "celeste_plus", "device_id": e.enrolled["device_id"],
            "release_id": catalog["release_id"], "client_version": catalog["client_version"],
            "client_sha256": catalog["runtime_sha256"], "beta_catalog_sha256": catalog["catalog_sha256"],
            "timestamp": int(time.time()), "nonce": nonce or secrets.token_urlsafe(24),
            "observed_sha256": catalog["runtime_sha256"], "event": "heartbeat", "hardware_sha256": core,
            "process_id": pid, "session_id": identity or secrets.token_urlsafe(24)}
    if hwid is not None:
        body["hwid_sha256"] = hwid
    message = (f"celeste-session-v1\n{body['device_id']}\n{body['timestamp']}\n{body['nonce']}\n"
               f"{body['client_sha256']}\n{body['observed_sha256']}\n{body['event']}\n{body['game_uuid']}\n{body['game_name']}\n"
               f"{body['release_id']}\n{body['client_version']}\n{body['beta_catalog_sha256']}\n"
               f"celeste-session-v2\n{core}\n{hwid or ''}\n{pid}\n{body['session_id']}").encode()
    body.update(install_signature=b64e(e.install.sign(message)), token_signature=b64e(e.token.sign(message)))
    return body


def post_session(e, body):
    path = "/v1/session/check"
    return e.client.post(path, json=body, headers={"DPoP": proof(e.install, "POST", "https://testserver" + path)})


def test_hwid_migration_requires_fresh_dual_proof_and_cannot_be_rebound(v2):
    pending = auth(v2, complete=False)
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT hwid_sha256 FROM devices").fetchone()[0] is None
    invalid = {**pending, "token_signature": b64e(ed25519.Ed25519PrivateKey.generate().sign(b"wrong"))}
    path = "/v1/installer/complete"
    assert v2.client.post(path, json=invalid, headers={"DPoP": proof(v2.install, "POST", "https://testserver" + path)}).status_code == 401
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT hwid_sha256 FROM devices").fetchone()[0] is None
    good = v2.client.post(path, json=pending, headers={"DPoP": proof(v2.install, "POST", "https://testserver" + path)})
    assert good.status_code == 200, good.text
    for changed in ("7" * 64, None):
        denied = auth(v2, hwid=changed)
        assert denied.status_code == 401 and denied.json()["error_code"] == "device_mismatch"
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT hwid_sha256 FROM devices").fetchone()[0] == HWID
        assert c.execute("SELECT COUNT(*) FROM active_tokens WHERE status='active'").fetchone()[0] == 1


def test_concurrent_different_hardware_revokes_grants_and_requires_matching_fresh_proof(v2):
    tokens = [auth(v2).json()["grant"] for _ in range(2)]
    assert post_session(v2, session(v2)).status_code == 200
    for pid in (4321, 5432):
        assert post_session(v2, session(v2, pid=pid)).status_code == 200  # Same device permits another JVM/reload.
    conflict = post_session(v2, session(v2, hwid="7" * 64))
    assert conflict.status_code == 409 and conflict.json()["error_code"] == "concurrent_session_rejected"
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM revoked_tokens").fetchone()[0] == 2
        assert c.execute("SELECT COUNT(*) FROM active_tokens WHERE status='revoked'").fetchone()[0] == 2
        assert c.execute("SELECT status FROM licenses").fetchone()[0] == "active"
        assert c.execute("SELECT status FROM devices").fetchone()[0] == "active"
    assert post_session(v2, session(v2)).status_code == 409
    assert auth(v2).status_code == 200  # Fresh old-hardware + dual proofs clear runtime lock, not old token revocations.
    assert post_session(v2, session(v2)).status_code == 200
    path = "/v1/validate"
    for token in tokens:
        denied = v2.client.post(path, headers={**access(v2, path, token, hwid=HWID), "X-Device-Id": HWID})
        assert denied.status_code == 401 and denied.json()["error_code"] == "token_revoked"


@pytest.mark.parametrize("field,value", [("hardware_sha256", "7" * 64), ("hwid_sha256", "7" * 64), ("process_id", 9999), ("session_id", "A" * 32)])
def test_unsigned_session_field_tampering_cannot_revoke_or_update(v2, field, value):
    auth(v2)
    assert post_session(v2, session(v2)).status_code == 200
    body = session(v2)
    body[field] = value
    assert post_session(v2, body).status_code == 401
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM revoked_tokens").fetchone()[0] == 0
        assert c.execute("SELECT conflicted_at FROM runtime_bindings").fetchone()[0] is None


def test_old_or_replayed_hardware_request_never_creates_conflict(v2):
    auth(v2)
    assert post_session(v2, session(v2, core="9" * 64)).status_code == 401  # No recent trusted session yet.
    body = session(v2)
    assert post_session(v2, body).status_code == 200
    assert post_session(v2, body).status_code == 401
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM revoked_tokens").fetchone()[0] == 0


def test_v2_requires_session_fields_and_allows_non_windows_without_hwid(v2):
    assert auth(v2, hwid=None).status_code == 200
    assert post_session(v2, session(v2, hwid=None)).status_code == 200
    body = session(v2, hwid=None)
    for key in ("hardware_sha256", "process_id", "session_id"):
        body.pop(key)
    denied = post_session(v2, body)
    assert denied.status_code == 401 and denied.json()["error_code"] == "reactivation_required"


def test_rotate_is_atomic_proof_bound_and_old_token_stops_working(v2):
    token = auth(v2).json()["grant"]
    path = "/v1/rotate"
    def rotate():
        return v2.client.post(path, headers={**access(v2, path, token, hwid=HWID), "X-Device-Id": HWID})
    with ThreadPoolExecutor(max_workers=2) as pool:
        responses = list(pool.map(lambda _: rotate(), range(2)))
    assert sorted(r.status_code for r in responses) == [200, 401]
    renewed = next(r.json()["grant"] for r in responses if r.status_code == 200)
    old, new = v2.app.state.signer.verify(token), v2.app.state.signer.verify(renewed)
    assert new["jti"] != old["jti"] and new["exp"] - new["iat"] == 600
    assert all(new[k] == old[k] for k in ("sub", "device", "product", "cnf", "aud", "auth_protocol"))
    with v2.app.state.database.connect() as c:
        assert tuple(c.execute("SELECT status,rotated_to FROM active_tokens WHERE jti=?", (old["jti"],)).fetchone()) == ("rotated", new["jti"])
        assert c.execute("SELECT COUNT(*) FROM revoked_tokens").fetchone()[0] == 0
    path = "/v1/validate"
    assert v2.client.post(path, headers={**access(v2, path, renewed, hwid=HWID), "X-Device-Id": HWID}).status_code == 200
    for extra in ({}, {"hwid": "8" * 64}):
        assert v2.client.post(path, headers={**access(v2, path, renewed, **extra), "X-Device-Id": HWID}).status_code == 401


def encrypted_request(e, token, path, manifest, *, hwid=HWID):
    private = x25519.X25519PrivateKey.generate()
    public = raw_public(private)
    nonce = secrets.token_urlsafe(24)
    headers = {"Authorization": "DPoP " + token, "X-Celeste-Beta-Manifest": manifest,
               "X-Celeste-Delivery-Key": public, "X-Celeste-Request-Nonce": nonce, "X-Device-Id": hwid,
               "DPoP": proof(e.install, "GET", "https://testserver" + path, token,
                             payload_updates={"cek": public, "cnonce": nonce, "hwid": hwid})}
    return private, headers


def decrypt(content, private, token, path, headers):
    assert content[:6] == b"CSTAE1"
    info = ("celeste-artifact-v1\n" + path + "\n" + headers["X-Celeste-Request-Nonce"] + "\n" + headers["X-Celeste-Delivery-Key"]).encode()
    shared = private.exchange(x25519.X25519PublicKey.from_public_bytes(content[6:38]))
    key = HKDF(algorithm=hashes.SHA256(), length=32, salt=hashlib.sha256(token.encode()).digest(), info=info).derive(shared)
    return AESGCM(key).decrypt(content[38:50], content[50:], info)


def test_all_four_artifacts_encrypted_recipient_bound_and_no_v2_plaintext_fallback(v2):
    token = auth(v2).json()["grant"]
    path = "/v1/beta/manifest"
    manifest = v2.client.get(path, headers={"Authorization": "DPoP " + token, "X-Celeste-Request-Nonce": secrets.token_urlsafe(24),
        "DPoP": proof(v2.install, "GET", "https://testserver" + path, token)}).json()["manifest"]
    catalog = v2.app.state.signer.verify(manifest)
    for item in catalog["artifacts"]:
        private, headers = encrypted_request(v2, token, item["url"], manifest)
        response = v2.client.get(item["url"], headers=headers)
        assert response.status_code == 200, response.text
        assert len(response.content) == item["size"] + 66
        plain = decrypt(response.content, private, token, item["url"], headers)
        assert hashlib.sha256(plain).hexdigest() == item["sha256"]
        with pytest.raises(InvalidTag):
            decrypt(response.content, x25519.X25519PrivateKey.generate(), token, item["url"], headers)
        assert v2.client.get(item["url"], headers=headers).status_code == 401
    item = catalog["artifacts"][0]
    _, headers = encrypted_request(v2, token, item["url"], manifest)
    headers["X-Celeste-Delivery-Key"] = raw_public(x25519.X25519PrivateKey.generate())
    assert v2.client.get(item["url"], headers=headers).status_code == 401
    headers.pop("X-Celeste-Delivery-Key")
    denied = v2.client.get(item["url"], headers=headers)
    assert denied.status_code == 400 and denied.json()["error_code"] == "invalid_delivery_key"


def test_transport_policy_uses_only_operator_pins_and_signed_request_nonce(v2):
    nonce = secrets.token_urlsafe(24)
    response = v2.client.get("/v1/transport-policy?product=celeste_plus", headers={"X-Celeste-Request-Nonce": nonce})
    assert response.status_code == 200
    token = response.json()["policy"]
    first, second, signature = token.split(".")
    ed25519.Ed25519PrivateKey.from_private_bytes(v2.config.signing_private_key).public_key().verify(b64d(signature), (first + "." + second).encode())
    claims = json.loads(b64d(second))
    assert claims == {"aud": "celeste-transport-policy", "product": "celeste_plus", "origin": "https://testserver",
                      "request_nonce": nonce, "iat": claims["iat"], "nbf": claims["iat"], "exp": claims["iat"] + 120,
                      "leaf_sha256": ["a" * 64, "b" * 64]}
    with TestClient(create_app(replace(v2.config, tls_leaf_sha256=())), base_url="https://testserver") as unavailable:
        assert unavailable.get("/v1/transport-policy?product=celeste_plus", headers={"X-Celeste-Request-Nonce": nonce}).status_code == 503


def rebind_start(e, key, *, signing=None, hardware_value=None):
    signing = signing or ed25519.Ed25519PrivateKey.generate()
    body = {**release(), "product": "celeste_plus", "license_key": key,
            "install_signing_key": raw_public(signing), "install_encryption_key": raw_public(x25519.X25519PrivateKey.generate()),
            "hardware": hardware_value or hardware(), "hwid_sha256": "8" * 64}
    return e.client.post("/v1/activate", json=body), signing


def rebind_complete(e, start, signing, *, valid=True):
    message = b64d(start.json()["message"])
    path = "/v1/activate/complete"
    body = {"challenge_id": start.json()["challenge_id"], "install_signature": b64e(signing.sign(message if valid else b"wrong"))}
    return e.client.post(path, json=body, headers={"DPoP": proof(signing, "POST", "https://testserver" + path)})


def test_rebind_cooldown_and_license_key_are_required_without_changing_existing_activation(v2):
    result, _ = rebind_start(v2, v2.license_key)
    assert result.status_code == 409 and result.json()["error_code"] == "rebind_cooldown"
    assert result.json()["retry_after_seconds"] > 0
    with v2.app.state.database.connect() as c:
        c.execute("UPDATE devices SET created_at=?", (int(time.time()) - 8 * 86400,))
    denied, _ = rebind_start(v2, "CSP-WRONG-LICENSE-KEY-123456789")
    assert denied.status_code == 401
    pending, signing = rebind_start(v2, v2.license_key)
    assert pending.status_code == 200
    assert rebind_complete(v2, pending, signing, valid=False).status_code == 401
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM devices WHERE status='active'").fetchone()[0] == 1
        assert c.execute("SELECT COUNT(*) FROM device_rebindings").fetchone()[0] == 0


def test_two_eligible_rebind_completions_cannot_evict_a_newly_rebound_device(v2):
    token = auth(v2).json()["grant"]
    old_device = v2.enrolled["device_id"]
    with v2.app.state.database.connect() as c:
        c.execute("UPDATE devices SET created_at=?", (int(time.time()) - 8 * 86400,))
        before = dict(c.execute("SELECT * FROM licenses").fetchone())
    pending = [rebind_start(v2, v2.license_key) for _ in range(2)]
    assert all(start.status_code == 200 for start, _ in pending)
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda pair: rebind_complete(v2, *pair), pending))
    assert sorted(r.status_code for r in results) == [200, 409]
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM devices WHERE status='active'").fetchone()[0] == 1
        assert c.execute("SELECT status FROM devices WHERE device_id=?", (old_device,)).fetchone()[0] == "revoked"
        assert c.execute("SELECT COUNT(*) FROM device_rebindings").fetchone()[0] == 1
        assert dict(c.execute("SELECT * FROM licenses").fetchone()) == before
        assert c.execute("SELECT status FROM active_tokens").fetchone()[0] == "revoked"
    again, _ = rebind_start(v2, v2.license_key)
    assert again.status_code == 409 and again.json()["error_code"] == "rebind_cooldown"


@pytest.mark.parametrize("field", ["hwid_sha256", "hardware_sha256"])
def test_signed_session_conflict_is_blocked_after_waiting_for_database_lock(v2, field):
    auth(v2)
    assert post_session(v2, session(v2)).status_code == 200
    kwargs = {"hwid" if field == "hwid_sha256" else "core": "9" * 64}
    results = []
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda _: post_session(v2, session(v2, **kwargs)), range(2)))
    assert [r.status_code for r in results] == [409, 409]
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM revoked_tokens").fetchone()[0] == 1


def test_v2_session_response_contains_signed_process_and_hardware_binding(v2):
    auth(v2)
    body = session(v2)
    response = post_session(v2, body)
    claims = v2.app.state.signer.verify(response.json()["session_token"])
    for field in ("hardware_sha256", "hwid_sha256", "process_id", "session_id"):
        assert claims[field] == body[field]


def test_existing_plus_session_lease_cannot_use_cloud_during_hardware_conflict(v2):
    assert auth(v2).status_code == 200
    response = post_session(v2, session(v2))
    lease = response.json()["session_token"]
    path = "/v1/configs"

    def cloud():
        return v2.client.get(path, headers={"Authorization": "DPoP " + lease,
            "DPoP": proof(v2.install, "GET", "https://testserver" + path, lease)})

    assert cloud().status_code == 200
    assert post_session(v2, session(v2, hwid="9" * 64)).status_code == 409
    denied = cloud()
    assert denied.status_code == 409 and denied.json()["error_code"] == "concurrent_session_rejected"


@pytest.mark.parametrize("scope,audience", [("installer", "celeste-installer"), ("auth", "celeste-client")])
def test_conflict_between_proof_commit_and_grant_registration_cannot_issue(v2, monkeypatch, scope, audience):
    auth(v2)
    assert post_session(v2, session(v2)).status_code == 200
    pending = auth(v2, scope=scope, complete=False)
    original = v2.app.state.signer.sign
    intervened = []

    def sign(claims):
        if claims.get("aud") == audience and not intervened:
            intervened.append(True)
            conflict = post_session(v2, session(v2, hwid="9" * 64))
            assert conflict.status_code == 409
        return original(claims)

    monkeypatch.setattr(v2.app.state.signer, "sign", sign)
    path = f"/v1/{scope}/complete"
    response = v2.client.post(path, json=pending, headers={"DPoP": proof(v2.install, "POST", "https://testserver" + path)})
    assert intervened
    assert response.status_code == 409 and response.json()["error_code"] == "concurrent_session_rejected"
    with v2.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM active_tokens WHERE status='active'").fetchone()[0] == 0
        assert connection.execute("SELECT conflicted_at FROM runtime_bindings").fetchone()[0] is not None


@pytest.mark.parametrize("scope", ["installer", "auth", "stage2", "stage3", "stage4"])
def test_plus_hwid_migration_preserves_standard_and_standard_cannot_clear_plus_conflict(v2, scope):
    assert auth(v2).status_code == 200

    def standard():
        start = v2.client.post(f"/v1/{scope}/start", json={**release(), "product": "celeste",
            "device_id": v2.enrolled["device_id"], "hardware": hardware()})
        assert start.status_code == 200, start.text
        message = b64d(start.json()["message"])
        transcript = json.loads(message)
        assert transcript["protocol"] == "cst-auth-1" and "hwid_sha256" not in transcript
        path = f"/v1/{scope}/complete"
        result = v2.client.post(path, json={"challenge_id": start.json()["challenge_id"],
            "install_signature": b64e(v2.install.sign(message)), "token_signature": b64e(v2.token.sign(message))},
            headers={"DPoP": proof(v2.install, "POST", "https://testserver" + path)})
        assert result.status_code == 200, result.text
        claims = v2.app.state.signer.verify(result.json()["grant"])
        assert claims["product"] == "celeste" and "auth_protocol" not in claims
        return result.json()["grant"]

    old_standard = standard()
    assert post_session(v2, session(v2)).status_code == 200
    assert post_session(v2, session(v2, hwid="9" * 64)).status_code == 409
    path = "/v1/validate"
    assert v2.client.post(path, headers={**access(v2, path, old_standard, hwid=CORE), "X-Device-Id": CORE}).status_code == 200
    standard()
    with v2.app.state.database.connect() as connection:
        assert connection.execute("SELECT hwid_sha256 FROM devices").fetchone()[0] == HWID
        assert connection.execute("SELECT conflicted_at FROM runtime_bindings").fetchone()[0] is not None
    assert post_session(v2, session(v2)).status_code == 409
    assert auth(v2, hwid=None).status_code == 401
    assert auth(v2).status_code == 200


@pytest.mark.parametrize("change,code", [("hardware", "device_mismatch"), ("expiry", "subscription_inactive"), ("revoke", "token_revoked")])
def test_validate_errors_are_typed_and_never_issue_a_new_grant(v2, change, code):
    from app.token_revocation import revoke_access_token
    token = auth(v2).json()["grant"]
    current = "8" * 64 if change == "hardware" else HWID
    if change == "expiry":
        with v2.app.state.database.connect() as c:
            c.execute("UPDATE licenses SET expires_at=?", (int(time.time()) - 1,))
    if change == "revoke":
        revoke_access_token(v2.app.state.database, v2.app.state.signer, token, "manual withdrawal")
    path = "/v1/validate"
    response = v2.client.post(path, headers={**access(v2, path, token, hwid=current), "X-Device-Id": current})
    assert response.status_code == 401 and response.json()["error_code"] == code
    assert "grant" not in response.json()


def test_lifecycle_migration_is_additive_idempotent_and_preserves_audits(v2):
    from pathlib import Path
    token = auth(v2).json()["grant"]
    script = (Path(__file__).parents[1] / "migrations/20260927_auth_lifecycle.sql").read_text()
    with v2.app.state.database.connect() as c:
        before = [tuple(r) for r in c.execute("SELECT * FROM active_tokens")]
        licenses = [tuple(r) for r in c.execute("SELECT * FROM licenses")]
        c.executescript(script)
        c.executescript(script)
        assert [tuple(r) for r in c.execute("SELECT * FROM active_tokens")] == before
        assert [tuple(r) for r in c.execute("SELECT * FROM licenses")] == licenses


def test_pre_conflict_prepared_completion_cannot_clear_runtime_lock(v2):
    auth(v2)
    pending = auth(v2, complete=False)
    assert post_session(v2, session(v2)).status_code == 200
    assert post_session(v2, session(v2, hwid="7" * 64)).status_code == 409
    path = "/v1/installer/complete"
    response = v2.client.post(path, json=pending, headers={"DPoP": proof(v2.install, "POST", "https://testserver" + path)})
    assert response.status_code == 401
    assert post_session(v2, session(v2)).status_code == 409
    assert auth(v2).status_code == 200


def test_concurrent_first_hwid_bindings_cannot_overwrite_each_other(v2):
    pending = [auth(v2, hwid=hwid, complete=False) for hwid in (HWID, "7" * 64)]
    path = "/v1/installer/complete"
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda body: v2.client.post(path, json=body,
            headers={"DPoP": proof(v2.install, "POST", "https://testserver" + path)}), pending))
    assert sorted(r.status_code for r in results) == [200, 401]
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM active_tokens").fetchone()[0] == 1
        assert c.execute("SELECT hwid_sha256 FROM devices").fetchone()[0] in (HWID, "7" * 64)


def test_signed_heartbeat_expiring_while_waiting_for_lock_is_denied(v2, monkeypatch):
    from test_grant_validation import mutate_before_authorization_lock
    auth(v2)
    body = session(v2)
    expired = body["timestamp"] + 61
    mutate_before_authorization_lock(v2, monkeypatch, lambda c: monkeypatch.setattr(time, "time", lambda: expired))
    assert post_session(v2, body).status_code == 401
    with v2.app.state.database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM runtime_bindings").fetchone()[0] == 0


def test_public_protocol_vectors_match_actual_server_outputs():
    from generate_protocol_v2_fixtures import vectors, delivery_vector, ROOT, REPOSITORY
    for name, expected in vectors().items():
        assert json.loads((ROOT / name).read_bytes()) == expected
    assert json.loads((REPOSITORY / 'beta-lab/delivery-client/fixtures/server-schema2-v2-golden.json').read_bytes()) == delivery_vector()
