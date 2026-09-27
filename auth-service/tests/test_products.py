"""Product authorization contracts; all keys/databases/artifacts are disposable fixtures."""
from __future__ import annotations
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
from types import SimpleNamespace

import pytest
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from dpop_client import TestClient, register_key
from app.main import create_app
from app.beta_delivery import beta_runtime_catalog
from app.products import CELESTE, PLUS, issue_license, selected_products
from app.security import b64d, b64e, hash_license
from test_beta_delivery import stage_beta
from test_protocol import settings, release, hardware, raw_public, enroll


@pytest.fixture
def env(tmp_path):
    directory = tmp_path / "plus"
    stage_beta(directory)
    config = replace(settings(tmp_path), plus_enabled=True, beta_release_dir=str(directory))
    app = create_app(config)
    with TestClient(app, base_url="https://testserver") as client:
        yield SimpleNamespace(config=config, app=app, db=app.state.database, client=client)


def issue(env, selection, uid=None, label="fixture"):
    with env.db.connect() as c:
        if uid is not None:
            c.execute("INSERT INTO sqlite_sequence(name,seq) SELECT 'licenses',? WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='licenses')", (uid-1,))
            c.execute("UPDATE sqlite_sequence SET seq=? WHERE name='licenses'", (uid-1,))
        return issue_license(c, env.config.key_pepper, selection, label, max_devices=1)


def begin_enroll(env, key, product):
    signing = register_key(ed25519.Ed25519PrivateKey.generate())
    body = {**release(), "product": product, "license_key": key, "hardware": hardware(),
            "install_signing_key": raw_public(signing), "install_encryption_key": raw_public(x25519.X25519PrivateKey.generate())}
    return env.client.post("/v1/enroll/start", json=body), signing


def finish(env, start, signing, token=None, path="enroll"):
    message = b64d(start.json()["message"])
    body = {"challenge_id": start.json()["challenge_id"], "install_signature": b64e(signing.sign(message))}
    if token is not None:
        body["token_signature"] = b64e(token.sign(message))
    return env.client.post(f"/v1/{path}/complete", json=body)


def runtime_body(env, enrolled, product):
    body = {**release(), "product": product, "device_id": enrolled["device_id"], "hardware": hardware()}
    if product == PLUS:
        catalog = beta_runtime_catalog(env.config, enrolled["uid"])
        body.update(release_id=catalog["release_id"], client_version=catalog["client_version"],
                    client_sha256=catalog["runtime_sha256"], beta_catalog_sha256=catalog["catalog_sha256"])
    return body


def heartbeat(body, signing, token, nonce="fixture-session-nonce-0001"):
    body = {k: v for k, v in body.items() if k != "hardware"}
    body.update(timestamp=int(time.time()), nonce=nonce, observed_sha256=body["client_sha256"], event="heartbeat")
    message = (f"celeste-session-v1\n{body['device_id']}\n{body['timestamp']}\n{body['nonce']}\n"
               f"{body['client_sha256']}\n{body['observed_sha256']}\n{body['event']}\n{body['game_uuid']}\n{body['game_name']}")
    if "beta_catalog_sha256" in body:
        message += f"\n{body['release_id']}\n{body['client_version']}\n{body['beta_catalog_sha256']}"
    body.update(install_signature=b64e(signing.sign(message.encode())), token_signature=b64e(token.sign(message.encode())))
    return body


@pytest.mark.parametrize("selection", [CELESTE, PLUS, "both"])
@pytest.mark.parametrize("requested", [CELESTE, PLUS])
def test_product_matrix_enrollment_installer_runtime_delivery_session(env, selection, requested):
    uid, key = issue(env, selection, uid=42)
    assert uid == 42
    if requested not in selected_products(selection):
        start, _ = begin_enroll(env, key, requested)
        assert start.status_code == 403
        with env.db.connect() as c:
            assert c.execute("SELECT COUNT(*) FROM devices").fetchone()[0] == 0
            assert c.execute("SELECT COUNT(*) FROM challenges").fetchone()[0] == 0
        return
    enrolled, signing, token = enroll(env.client, key, product=requested)
    installer = env.client.post("/v1/installer/start", json={**release(), "product": requested,
        "device_id": enrolled["device_id"], "hardware": hardware(), "license_key": key})
    assert installer.status_code == 200, installer.text
    assert json.loads(b64d(installer.json()["message"]))["product"] == requested
    done = finish(env, installer, signing, token, "installer")
    assert done.status_code == 200, done.text
    grant = done.json()["grant"]
    assert env.app.state.signer.verify(grant)["product"] == requested
    headers = {"Authorization": "Bearer " + grant}
    normal = env.client.get(f"/v1/bootstrap/{env.config.bootstrap_id}", headers=headers)
    plus = env.client.get("/v1/beta/manifest", headers=headers)
    assert normal.status_code == (200 if requested == CELESTE else 401)
    assert plus.status_code == (200 if requested == PLUS else 401)
    body = runtime_body(env, enrolled, requested)
    start = env.client.post("/v1/auth/start", json=body)
    assert start.status_code == 200, start.text
    authenticated = finish(env, start, signing, token, "auth")
    assert authenticated.status_code == 200, authenticated.text
    claims = env.app.state.signer.verify(authenticated.json()["grant"])
    assert claims["product"] == requested and claims["sub"] == "42"
    response = env.client.post("/v1/session/check", json=heartbeat(body, signing, token))
    assert response.status_code == 200, response.text
    assert env.app.state.signer.verify(response.json()["session_token"])["product"] == requested
    assert env.client.get("/v1/configs", headers={"Authorization": "Bearer " + authenticated.json()["grant"]}).status_code == 200


def test_both_one_slot_reuses_identical_device_with_explicit_key_for_each_product(env):
    uid, key = issue(env, "both")
    enrolled, signing, token = enroll(env.client, key)
    for product in (CELESTE, PLUS, CELESTE, PLUS):
        start = env.client.post("/v1/installer/start", json={**release(), "product": product,
            "license_key": key, "device_id": enrolled["device_id"], "hardware": hardware()})
        assert start.status_code == 200, start.text
        done = finish(env, start, signing, token, "installer")
        assert done.status_code == 200 and done.json()["uid"] == uid
    with env.db.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM devices WHERE license_uid=?", (uid,)).fetchone()[0] == 1
        assert c.execute("SELECT COUNT(*) FROM audit_log WHERE event='device_enrolled'").fetchone()[0] == 1
    start, _ = begin_enroll(env, key, PLUS)
    assert start.status_code == 409  # Reuse works; a second activation still respects the cap.


def test_explicit_key_binding_rejects_stale_identity_before_challenge_or_device_touch(env):
    _, old = issue(env, "both")
    _, replacement = issue(env, "both")
    enrolled, _, _ = enroll(env.client, old)
    def snapshot():
        with env.db.connect() as c:
            return tuple(c.execute("SELECT * FROM devices").fetchone()), c.execute("SELECT COUNT(*) FROM challenges").fetchone()[0], c.execute("SELECT COUNT(*) FROM audit_log").fetchone()[0]
    before = snapshot()
    body = {**release(), "product": PLUS, "license_key": replacement, "device_id": enrolled["device_id"], "hardware": hardware()}
    assert env.client.post("/v1/installer/start", json=body).status_code == 401
    assert snapshot() == before
    body.pop("license_key")
    assert env.client.post("/v1/installer/start", json=body).status_code == 200


def test_concurrent_pending_enrollments_cannot_overbook_last_slot(env):
    _, key = issue(env, "both")
    starts = [begin_enroll(env, key, product) for product in (CELESTE, PLUS)]
    assert [start.status_code for start, _ in starts] == [200, 200]
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(lambda pair: finish(env, *pair), starts))
    assert sorted(result.status_code for result in results) == [200, 409]
    with env.db.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM devices").fetchone()[0] == 1


@pytest.mark.parametrize("phase", ["enroll", "installer", "auth"])
def test_entitlement_removed_between_start_and_complete_is_denied(env, phase):
    uid, key = issue(env, CELESTE)
    if phase == "enroll":
        start, signing = begin_enroll(env, key, CELESTE)
        token = None
    else:
        enrolled, signing, token = enroll(env.client, key)
        start = env.client.post(f"/v1/{phase}/start", json=runtime_body(env, enrolled, CELESTE))
    assert start.status_code == 200
    with env.db.connect() as c:
        c.execute("DELETE FROM license_entitlements WHERE license_uid=?", (uid,))
    assert finish(env, start, signing, token, phase).status_code == 403


@pytest.mark.parametrize("product", [CELESTE, PLUS])
def test_live_entitlement_revokes_grants_and_session_without_tamper_penalty(env, product):
    uid, key = issue(env, "both")
    enrolled, signing, token = enroll(env.client, key)
    body = runtime_body(env, enrolled, product)
    auth = env.client.post("/v1/auth/start", json=body)
    grant = finish(env, auth, signing, token, "auth").json()["grant"]
    install = env.client.post("/v1/installer/start", json={**release(), "product": product,
        "device_id": enrolled["device_id"], "hardware": hardware()})
    installer = finish(env, install, signing, token, "installer").json()["grant"]
    headers = {"Authorization": "Bearer " + installer}
    if product == PLUS:
        manifest = env.client.get("/v1/beta/manifest", headers=headers).json()["manifest"]
        claims = env.app.state.signer.verify(manifest)
        artifact = claims["artifacts"][0]["url"]
        headers["X-Celeste-Beta-Manifest"] = manifest
    else:
        artifact = f"/v1/bootstrap/{env.config.bootstrap_id}"
    with env.db.connect() as c:
        c.execute("DELETE FROM license_entitlements WHERE license_uid=? AND product=?", (uid, product))
    assert env.client.get(artifact, headers=headers).status_code == 403
    assert env.client.get("/v1/configs", headers={"Authorization": "Bearer " + grant}).status_code == 403
    assert env.client.post("/v1/session/check", json=heartbeat(body, signing, token)).status_code == 403
    with env.db.connect() as c:
        assert tuple(c.execute("SELECT status,tamper_count FROM devices").fetchone()) == ("active", 0)


def test_runtime_requested_product_must_match_exact_release_and_no_owner_exemption(env):
    uid, key = issue(env, "both", uid=7, label="Owner")
    enrolled, signing, token = enroll(env.client, key)
    for real, wrong in ((CELESTE, PLUS), (PLUS, CELESTE)):
        body = runtime_body(env, enrolled, real)
        assert env.client.post("/v1/auth/start", json={**body, "product": wrong}).status_code == 403
        request = heartbeat(body, signing, token)
        request["product"] = wrong
        assert env.client.post("/v1/session/check", json=request).status_code == 403
    with env.db.connect() as c:
        c.execute("DELETE FROM license_entitlements WHERE license_uid=? AND product=?", (uid, PLUS))
    response = env.client.post("/v1/installer/start", json={**release(), "device_id": enrolled["device_id"], "hardware": hardware(), "product": PLUS})
    assert response.status_code == 403


def test_product_in_challenge_cannot_be_substituted_and_bad_grant_signature_is_401(env):
    _, key = issue(env, "both")
    start, signing = begin_enroll(env, key, CELESTE)
    message = json.loads(b64d(start.json()["message"]))
    message["product"] = PLUS
    wrong = json.dumps(message, sort_keys=True, separators=(",", ":")).encode()
    result = env.client.post("/v1/enroll/complete", json={"challenge_id": start.json()["challenge_id"], "install_signature": b64e(signing.sign(wrong))})
    assert result.status_code == 401
    token = env.app.state.signer.sign({"aud": "celeste-installer", "exp": int(time.time()) + 300})
    first, second, _ = token.split(".")
    forged = first + "." + second + "." + b64e(bytes(64))
    assert env.client.get(f"/v1/bootstrap/{env.config.bootstrap_id}", headers={"Authorization": "Bearer " + forged}).status_code == 401


def test_admin_requires_deliberate_product_and_filters_and_details(env):
    page = env.client.post("/admin/login", data={"password": "correct horse battery staple"})
    csrf = re.search(r'name="csrf" value="([^"]+)"', page.text).group(1)
    assert 'value="" disabled selected>Select product' in page.text
    assert env.client.post("/admin/licenses", data={"csrf": csrf, "label": "missing"}).status_code == 422
    assert env.client.post("/admin/licenses", data={"csrf": csrf, "product": "owner"}).status_code == 422
    assert env.client.post("/admin/licenses", data={"csrf": "wrong", "product": "both"}).status_code == 403
    uids = {}
    for product in (CELESTE, PLUS, "both"):
        result = env.client.post("/admin/licenses", data={"csrf": csrf, "product": product, "label": "only-" + product})
        assert result.status_code == 200, result.text
        with env.db.connect() as c:
            uid = c.execute("SELECT uid FROM licenses WHERE label=?", ("only-" + product,)).fetchone()[0]
            uids[product] = uid
            assert {r[0] for r in c.execute("SELECT product FROM license_entitlements WHERE license_uid=?", (uid,))} == set(selected_products(product))
        assert 'ONE-TIME DISPLAY' in result.text
    for product in (CELESTE, PLUS, "both"):
        page = env.client.get("/admin", params={"product_filter": product})
        # Exact license links avoid labels repeated in the audit history.
        for candidate, uid in uids.items():
            expected = candidate == product or (product != "both" and candidate == "both")
            assert (f'href="/admin/licenses/{uid}"' in page.text) == expected
    detail = env.client.get(f"/admin/licenses/{uids['both']}")
    assert "Celeste + Celeste+" in detail.text
