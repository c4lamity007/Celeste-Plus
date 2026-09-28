"""Artifact identity uses real HTTP/DPoP/AEAD, without the auto-proof adapter."""
import secrets

import pytest

from dpop_client import proof
from test_dpop import env, grant, headers
from test_protocol_v2 import v2, auth, encrypted_request, decrypt, HWID, CORE


def manifest(e, token):
    path = "/v1/beta/manifest"
    response = e.client.get(path, headers={"Authorization": "DPoP " + token,
        "X-Celeste-Request-Nonce": secrets.token_urlsafe(24),
        "DPoP": proof(e.install, "GET", "https://testserver" + path, token)})
    assert response.status_code == 200, response.text
    value = response.json()["manifest"]
    return value, e.app.state.signer.verify(value)["artifacts"][0]["url"]


@pytest.mark.parametrize("case", ["missing", "duplicate", "upper", "uuid", "wrong", "unsigned", "proof-mismatch"])
def test_artifact_hardware_missing_wrong_or_unsigned_denied(v2, case):
    token = auth(v2).json()["grant"]
    signed, path = manifest(v2, token)
    _, request = encrypted_request(v2, token, path, signed)
    if case == "missing":
        request.pop("X-Device-Id")
    elif case == "duplicate":
        request = list(request.items()) + [("X-Device-Id", HWID)]
    elif case == "upper":
        request["X-Device-Id"] = "A" * 64
    elif case == "uuid":
        request["X-Device-Id"] = v2.enrolled["device_id"]
    elif case == "wrong":
        _, request = encrypted_request(v2, token, path, signed, hwid="8" * 64)
    else:
        extensions = {"cek": request["X-Celeste-Delivery-Key"], "cnonce": request["X-Celeste-Request-Nonce"]}
        if case == "proof-mismatch":
            extensions["hwid"] = "8" * 64
        request["DPoP"] = proof(v2.install, "GET", "https://testserver" + path, token, payload_updates=extensions)
    denied = v2.client.get(path, headers=request)
    assert denied.status_code == 401
    assert denied.headers["content-type"].startswith("application/json")
    if case not in ("unsigned", "proof-mismatch"):
        assert denied.json()["error_code"] == "device_mismatch"
    # Denied requests return no artifact; a fresh complete proof still works.
    private, valid = encrypted_request(v2, token, path, signed)
    response = v2.client.get(path, headers=valid)
    assert response.status_code == 200, response.text
    assert decrypt(response.content, private, token, path, valid)
    assert v2.client.get(path, headers=valid).status_code == 401


def test_legacy_plus_protocol_still_requires_signed_hardware(env):
    token = grant(env, product="celeste_plus")
    signed, path = manifest(env, token)
    request = {**headers(env, "GET", path, token), "X-Celeste-Beta-Manifest": signed}
    missing = dict(request)
    missing.pop("X-Device-Id")
    assert env.client.get(path, headers=missing).status_code == 401
    unsigned = {**request, "DPoP": proof(env.install, "GET", "https://testserver" + path, token)}
    assert env.client.get(path, headers=unsigned).status_code == 401
    assert env.client.get(path, headers=request).status_code == 200


def test_core_fallback_stops_after_fresh_hwid_enrollment(v2):
    token = auth(v2, hwid=None).json()["grant"]
    signed, path = manifest(v2, token)
    private, request = encrypted_request(v2, token, path, signed, hwid=CORE)
    response = v2.client.get(path, headers=request)
    assert response.status_code == 200
    assert decrypt(response.content, private, token, path, request)
    assert auth(v2).status_code == 200  # Actual dual-proof migration.
    _, request = encrypted_request(v2, token, path, signed, hwid=CORE)
    denied = v2.client.get(path, headers=request)
    assert denied.status_code == 401 and denied.json()["error_code"] == "device_mismatch"
    _, request = encrypted_request(v2, token, path, signed)
    assert v2.client.get(path, headers=request).status_code == 200


@pytest.mark.parametrize("phase", ["read", "encrypt"])
def test_hardware_binding_rechecked_after_slow_artifact_work(v2, monkeypatch, phase):
    import app.beta_delivery as module
    token = auth(v2).json()["grant"]
    signed, path = manifest(v2, token)
    _, request = encrypted_request(v2, token, path, signed)
    name = "_read_release" if phase == "read" else "encrypt_chunks"
    original = getattr(module, name)

    def changed(*args, **kwargs):
        result = original(*args, **kwargs)
        with v2.app.state.database.connect() as connection:
            connection.execute("UPDATE devices SET hwid_sha256=? WHERE device_id=?", ("8" * 64, v2.enrolled["device_id"]))
        return result

    monkeypatch.setattr(module, name, changed)
    denied = v2.client.get(path, headers=request)
    assert denied.status_code == 401 and denied.json()["error_code"] == "device_mismatch"
