"""Actual HTTP/DPoP/X25519/AEAD module delivery with disposable identities."""
from concurrent.futures import ThreadPoolExecutor
from dataclasses import replace
import asyncio
import hashlib
import io
import json
import secrets
import struct
import threading
import time
import tracemalloc
from types import SimpleNamespace
import zipfile

import pytest
from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.asymmetric import x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from fastapi.testclient import TestClient

from app.main import create_app
from app.module_delivery import MAGIC, content_aad, executable_root, read_catalog
from app.security import b64d, b64e, canonical_json
from dpop_client import proof
from test_dpop import env
from test_protocol import raw_public, hardware
from test_protocol_v2 import v2, auth, session, post_session, decrypt, HWID


@pytest.fixture
def modules(v2, tmp_path):
    root = tmp_path / "modules"
    root.mkdir()
    (root / "modules").mkdir()
    from app.beta_delivery import beta_runtime_catalog
    runtime = beta_runtime_catalog(v2.config, v2.uid)
    jars, items = {}, []
    for index, identity in enumerate(("Auto Tool", "Ground Shaders")):
        stream = io.BytesIO()
        with zipfile.ZipFile(stream, "w", zipfile.ZIP_STORED) as jar:
            jar.writestr(f"fixture/Module{index}.class", b"fixture-not-executable-" + secrets.token_bytes(2048))
        encoded = stream.getvalue()
        opaque = f"{index + 1:032x}"
        (root / "modules" / (opaque + ".jar")).write_bytes(encoded)
        items.append({"id": identity, "version": runtime["release_id"], "artifact_id": opaque,
            "entrypoint": f"fixture.Module{index}", "filename": opaque + ".jar", "size": len(encoded),
            "sha256": hashlib.sha256(encoded).hexdigest(), "entries_sha256": str(index + 1) * 64})
        jars[identity] = encoded
    catalog = {"schema": "celeste-module-release-1", "product": "celeste_plus", "release_id": runtime["release_id"],
               "host_abi": 1, "runtime_sha256": runtime["runtime_sha256"], "modules": items}
    catalog["executable_root_sha256"] = executable_root(catalog)
    (root / "module-release.json").write_bytes(canonical_json(catalog))
    config = replace(v2.config, module_release_dir=str(root), module_key_encryption_key=b"K" * 32)
    app = create_app(config)
    with TestClient(app, base_url="https://testserver") as client:
        e = SimpleNamespace(**{**vars(v2), "app": app, "config": config, "client": client,
            "catalog": catalog, "digest": hashlib.sha256(canonical_json(catalog)).hexdigest(), "jars": jars})
        assert auth(e).status_code == 200
        e.session_body = session(e)
        response = post_session(e, e.session_body)
        assert response.status_code == 200, response.text
        e.session_token = response.json()["session_token"]
        yield e


def request(e, path, *, method="GET", value=None, capability=None, token=None, hwid=HWID, changes=None, raw_body=None, evidence=None):
    token = token or e.session_token
    body = raw_body if raw_body is not None else canonical_json(value) if value is not None else b""
    private = x25519.X25519PrivateKey.generate()
    public, nonce = raw_public(private), secrets.token_urlsafe(24)
    evidence = canonical_json(evidence if evidence is not None else {
        "schema": "celeste-module-evidence-1", "hardware": hardware(), "hwid_sha256": hwid,
        "observed_sha256": e.catalog["runtime_sha256"]})
    extensions = {"meh": b64e(hashlib.sha256(evidence).digest()), "cek": public, "cnonce": nonce, "hwid": hwid, "bht": b64e(hashlib.sha256(body).digest())}
    if changes:
        extensions.update(changes)
    headers = {"Authorization": "DPoP " + token, "X-Device-Id": hwid, "X-Celeste-Delivery-Key": public,
        "X-Celeste-Request-Nonce": nonce, "DPoP": proof(e.install, method, "https://testserver" + path, token,
                                                       payload_updates=extensions)}
    headers["X-Celeste-Module-Evidence"] = b64e(evidence)
    headers["X-Celeste-Module-Token-Proof"] = b64e(e.token.sign(("celeste-module-proof-v1\n" + headers["DPoP"]).encode("ascii")))
    if capability:
        headers["X-Celeste-Module-Capability"] = capability
    if method == "POST":
        headers["Content-Type"] = "application/json"
    return private, headers, body


def call(e, path, **options):
    private, headers, body = request(e, path, **options)
    response = e.client.request(options.get("method", "GET"), path, headers=headers, content=body)
    if response.status_code != 200:
        return response, None
    clear = decrypt(response.content, private, options.get("token") or e.session_token, path, headers)
    return response, clear


def authorize(e, ids=None):
    response, clear = call(e, "/v1/modules/authorize", method="POST",
        value={"catalog_sha256": e.digest, "modules": ids or [e.catalog["modules"][0]["id"]]})
    assert response.status_code == 200, response.text
    return json.loads(clear)


def test_catalog_bound_to_exact_resident_executable_anchor_session_and_request(modules):
    path = "/v1/modules/catalog"
    private, headers, body = request(modules, path)
    response = modules.client.get(path, headers=headers)
    assert response.status_code == 200, response.text
    parsed = json.loads(decrypt(response.content, private, modules.session_token, path, headers))
    assert parsed["catalog"] == modules.catalog
    claims = modules.app.state.signer.verify(parsed["proof"])
    assert claims["aud"] == "celeste-module-catalog"
    assert claims["catalog_sha256"] == modules.digest
    assert claims["executable_root_sha256"] == executable_root(parsed["catalog"])
    assert claims["request_nonce"] == headers["X-Celeste-Request-Nonce"]
    assert claims["session_token_sha256"] == hashlib.sha256(modules.session_token.encode()).hexdigest()
    assert claims["exp"] <= modules.app.state.signer.verify(modules.session_token)["exp"]
    assert response.headers["cache-control"] == "no-store"
    assert modules.client.get(path, headers=headers).status_code == 401


def test_batch_authorization_real_jar_exact_decoys_inert_and_no_client_identity_in_watermark(modules):
    batch = authorize(modules, [item["id"] for item in modules.catalog["modules"]])
    assert len(batch["capabilities"]) == 2
    keys = set()
    for granted in batch["capabilities"]:
        item = next(item for item in modules.catalog["modules"] if item["id"] == granted["module_id"])
        capability = modules.app.state.signer.verify(granted["capability"])
        manifest = granted["manifest"]
        assert capability["manifest"] == manifest and capability["module_id"] == item["id"]
        assert capability["exp"] == batch["expires_at"]
        key = b64d(granted["key"], 32)
        keys.add(key)
        response, ciphertext = call(modules, granted["url"], capability=granted["capability"])
        assert response.status_code == 200, response.text
        assert hashlib.sha256(ciphertext).hexdigest() == manifest["ciphertext_sha256"]
        container = AESGCM(key).decrypt(ciphertext[:12], ciphertext[12:], content_aad(modules.catalog, modules.digest, item))
        assert hashlib.sha256(container).hexdigest() == manifest["container_sha256"]
        assert container.startswith(MAGIC)
        length = struct.unpack_from(">I", container, 6)[0]
        metadata = json.loads(container[10:10 + length])
        count = struct.unpack_from(">I", container, 10 + length)[0]
        offset, records = 14 + length, {}
        for _ in range(count):
            identity = container[offset:offset + 16].hex()
            size = struct.unpack_from(">I", container, offset + 16)[0]
            records[identity] = container[offset + 20:offset + 20 + size]
            offset += 20 + size
        assert offset == len(container)
        assert records[metadata["real_record"]] == modules.jars[item["id"]]
        assert 0 < metadata["decoy_bytes"] <= len(modules.jars[item["id"]]) // 10
        assert len(metadata["watermark"]) == 32 and HWID not in json.dumps(metadata)
        with pytest.raises(InvalidTag):
            AESGCM(key).decrypt(ciphertext[:12], ciphertext[12:-1] + bytes([ciphertext[-1] ^ 1]), content_aad(modules.catalog, modules.digest, item))
    assert len(keys) == 2


@pytest.mark.parametrize("update", [{"bht": None}, {"bht": "wrong"}, {"hwid": None}, {"hwid": "f" * 64}, {"cek": "wrong"}, {"cnonce": "wrong"}])
def test_unsigned_body_hwid_or_recipient_rejected(modules, update):
    path = "/v1/modules/authorize"
    _, headers, body = request(modules, path, method="POST", value={"catalog_sha256": modules.digest, "modules": ["Auto Tool"]}, changes=update)
    response = modules.client.post(path, headers=headers, content=body)
    assert response.status_code == 401
    with modules.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM module_personalizations").fetchone()[0] == 0


def test_changed_body_and_get_body_hash_rejected(modules):
    path = "/v1/modules/authorize"
    _, headers, body = request(modules, path, method="POST", value={"catalog_sha256": modules.digest, "modules": ["Auto Tool"]})
    assert modules.client.post(path, headers=headers, content=body.replace(b"Auto Tool", b"Ground Shaders")).status_code == 401
    response, _ = call(modules, "/v1/modules/catalog", changes={"bht": "wrong"})
    assert response.status_code == 401


@pytest.mark.parametrize("audience", ["installer", "auth"])
def test_long_lived_access_grants_cannot_authorize_modules(modules, audience):
    token = auth(modules, scope=audience).json()["grant"]
    response, _ = call(modules, "/v1/modules/catalog", token=token)
    assert response.status_code == 401


@pytest.mark.parametrize("case", ["wrong-capability", "different-session", "different-module", "revoked", "entitlement", "hwid", "expired"])
def test_artifact_live_checks_and_capability_binding(modules, case):
    granted = authorize(modules)["capabilities"][0]
    token, cap, path = modules.session_token, granted["capability"], granted["url"]
    if case == "wrong-capability":
        cap = cap[:-1] + ("A" if cap[-1] != "A" else "B")
    elif case == "different-session":
        token = post_session(modules, session(modules)).json()["session_token"]
    elif case == "different-module":
        path = path.rsplit("/", 1)[0] + "/" + modules.catalog["modules"][1]["artifact_id"]
    elif case == "expired":
        claims = modules.app.state.signer.verify(token)
        claims.update(iat=int(time.time()) - 100, nbf=int(time.time()) - 105, exp=int(time.time()) - 10)
        token = modules.app.state.signer.sign(claims)
    else:
        with modules.app.state.database.connect() as connection:
            if case == "revoked":
                connection.execute("UPDATE licenses SET status='revoked'")
            elif case == "entitlement":
                connection.execute("DELETE FROM license_entitlements WHERE product='celeste_plus'")
            else:
                connection.execute("UPDATE devices SET hwid_sha256=?", ("f" * 64,))
    response, _ = call(modules, path, token=token, capability=cap)
    assert response.status_code in (401, 403), response.text


def test_renewal_same_session_reuses_encrypted_cache_and_opaque_watermark(modules):
    first = authorize(modules)["capabilities"][0]
    refreshed = post_session(modules, session(modules, identity=modules.session_body["session_id"]))
    assert refreshed.status_code == 200
    token = refreshed.json()["session_token"]
    response, _ = call(modules, first["url"], capability=first["capability"], token=token)
    assert response.status_code == 200
    modules.session_token = token
    second = authorize(modules)["capabilities"][0]
    assert first["key"] == second["key"] and first["manifest"] == second["manifest"]
    with modules.app.state.database.connect() as connection:
        row = connection.execute("SELECT * FROM module_personalizations").fetchone()
        assert b64d(first["key"]) not in bytes(row["wrapped_key"])
        assert modules.jars["Auto Tool"] not in bytes(row["ciphertext"])
        assert connection.execute("SELECT COUNT(*) FROM module_personalizations").fetchone()[0] == 1


def test_identical_concurrent_authorizations_coalesce(modules):
    with ThreadPoolExecutor(max_workers=4) as executor:
        results = list(executor.map(lambda _: authorize(modules)["capabilities"][0], range(8)))
    assert len({item["key"] for item in results}) == 1
    assert len({item["manifest"]["watermark"] for item in results}) == 1


@pytest.mark.parametrize("kind", ["missing", "duplicate", "unknown", "catalog", "extra"])
def test_bad_batch_never_personalizes(modules, kind):
    value = {"catalog_sha256": modules.digest, "modules": ["Auto Tool"]}
    if kind == "missing": value["modules"] = []
    elif kind == "duplicate": value["modules"] *= 2
    elif kind == "unknown": value["modules"] = ["Unknown"]
    elif kind == "catalog": value["catalog_sha256"] = "f" * 64
    else: value["entrypoint"] = "attacker.Evil"
    response, _ = call(modules, "/v1/modules/authorize", method="POST", value=value)
    assert response.status_code == 400
    with modules.app.state.database.connect() as connection:
        assert connection.execute("SELECT COUNT(*) FROM module_personalizations").fetchone()[0] == 0


@pytest.mark.parametrize("kind", ["jar", "root", "host", "path"])
def test_approved_release_identity_cannot_be_replaced(modules, kind):
    from pathlib import Path
    root = Path(modules.config.module_release_dir)
    catalog = json.loads((root / "module-release.json").read_bytes())
    if kind == "jar":
        path = root / "modules" / catalog["modules"][0]["filename"]
        encoded = path.read_bytes()
        path.write_bytes(b"x" + encoded[1:])
    elif kind == "root":
        catalog["modules"][0]["sha256"] = "f" * 64
    elif kind == "host":
        catalog["runtime_sha256"] = "f" * 64
    else:
        catalog["modules"][0]["filename"] = "../arbitrary.jar"
    (root / "module-release.json").write_bytes(canonical_json(catalog))
    response, _ = call(modules, "/v1/modules/authorize", method="POST",
        value={"catalog_sha256": hashlib.sha256(canonical_json(catalog)).hexdigest(), "modules": ["Auto Tool"]})
    assert response.status_code in (409, 503)


def test_revocation_during_personalization_never_releases_key(modules, monkeypatch):
    import app.module_delivery as module
    original = module.personalize
    def revoked(*args, **kwargs):
        result = original(*args, **kwargs)
        with modules.app.state.database.connect() as connection:
            connection.execute("UPDATE licenses SET status='revoked'")
        return result
    monkeypatch.setattr(module, "personalize", revoked)
    response, _ = call(modules, "/v1/modules/authorize", method="POST",
        value={"catalog_sha256": modules.digest, "modules": ["Auto Tool"]})
    assert response.status_code in (401, 403)


def test_cache_corruption_and_wrong_persistent_kek_fail_closed(modules):
    granted = authorize(modules)["capabilities"][0]
    with modules.app.state.database.connect() as connection:
        connection.execute("UPDATE module_personalizations SET wrapped_key=?", (b"x" * 60,))
    response, _ = call(modules, granted["url"], capability=granted["capability"])
    assert response.status_code == 503
    for change in ({"module_key_encryption_key": None}, {"module_key_encryption_key": b"short"}, {"plus_auth_protocol": 1}):
        with pytest.raises(RuntimeError):
            create_app(replace(modules.config, **change))


def test_unconfigured_service_does_not_enable_new_delivery(v2):
    assert v2.client.get("/v1/modules/catalog").status_code == 404


def test_required_cutover_rejects_missing_module_release(v2):
    with pytest.raises(RuntimeError, match="Required modular delivery"):
        create_app(replace(v2.config, module_delivery_required=True))


def test_required_cutover_accepts_only_matching_resident(modules):
    settings = replace(modules.config, module_delivery_required=True)
    app = create_app(settings)
    with TestClient(app, base_url="https://testserver") as client:
        e = SimpleNamespace(**{**vars(modules), "app": app, "config": settings, "client": client})
        response, value = call(e, "/v1/modules/catalog")
        assert response.status_code == 200, response.text
        assert json.loads(value)["catalog"] == modules.catalog


def test_required_cutover_rejects_mismatched_host_at_startup(modules):
    path = __import__("pathlib").Path(modules.config.module_release_dir) / "module-release.json"
    catalog = {**modules.catalog, "runtime_sha256": "e" * 64}
    path.write_bytes(canonical_json(catalog))
    with pytest.raises(RuntimeError, match="do not match"):
        create_app(replace(modules.config, module_delivery_required=True))


def test_cutover_revalidates_even_cached_runtime_after_module_catalog_changes(modules):
    from app.beta_delivery import beta_runtime_catalog, require_current_runtime_catalog, _read_release
    from fastapi import HTTPException
    settings = replace(modules.config, module_delivery_required=True)
    create_app(settings)
    current = beta_runtime_catalog(settings, modules.uid)
    path = __import__("pathlib").Path(settings.module_release_dir) / "module-release.json"
    path.write_bytes(canonical_json({**modules.catalog, "runtime_sha256": hashlib.sha256(b"different resident release").hexdigest()}))
    for operation in [lambda: beta_runtime_catalog(settings, modules.uid),
                      lambda: require_current_runtime_catalog(settings, current),
                      lambda: _read_release(settings), lambda: _read_release(settings, "payload-a")]:
        with pytest.raises(HTTPException) as denied:
            operation()
        assert denied.value.status_code == 503


def test_staging_does_not_implicitly_cut_over_existing_plus_release(modules):
    from app.module_delivery import require_modular_runtime
    require_modular_runtime(modules.config, {"release_id": "legacy-staged", "runtime_sha256": "f" * 64})
    assert not modules.config.module_delivery_required


@pytest.mark.parametrize("endpoint", ["catalog", "authorize", "artifact"])
@pytest.mark.parametrize("change", ["system", "board", "processor", "monitor", "missing-monitor", "hwid", "digest", "missing-evidence", "missing-token-proof", "wrong-token-key", "unbound-evidence", "proof-transplant"])
def test_every_module_request_requires_fresh_complete_dual_signed_device_evidence(modules, endpoint, change):
    granted = authorize(modules)["capabilities"][0] if endpoint == "artifact" else None
    path = granted["url"] if granted else "/v1/modules/" + endpoint
    options = {"method": "POST", "value": {"catalog_sha256": modules.digest, "modules": ["Auto Tool"]}} if endpoint == "authorize" else {}
    if granted:
        options["capability"] = granted["capability"]
    evidence = {"schema": "celeste-module-evidence-1", "hardware": hardware(), "hwid_sha256": HWID,
                "observed_sha256": modules.catalog["runtime_sha256"]}
    if change in ("system", "board", "processor"):
        evidence["hardware"][change] = "9" * 64
    elif change == "monitor": evidence["hardware"]["monitors"] = ["9" * 64]
    elif change == "missing-monitor": evidence["hardware"].pop("monitors")
    elif change == "hwid": evidence["hwid_sha256"] = "9" * 64
    elif change == "digest": evidence["observed_sha256"] = "9" * 64
    if change == "unbound-evidence": options["changes"] = {"meh": None}
    _, headers, body = request(modules, path, evidence=evidence, **options)
    if change == "missing-evidence": headers.pop("X-Celeste-Module-Evidence")
    elif change == "missing-token-proof": headers.pop("X-Celeste-Module-Token-Proof")
    elif change == "wrong-token-key":
        headers["X-Celeste-Module-Token-Proof"] = b64e(modules.install.sign(("celeste-module-proof-v1\n" + headers["DPoP"]).encode()))
    elif change == "proof-transplant":
        _, other, _ = request(modules, path, **options)
        headers["X-Celeste-Module-Token-Proof"] = other["X-Celeste-Module-Token-Proof"]
    response = modules.client.request(options.get("method", "GET"), path, headers=headers, content=body)
    assert response.status_code == 401, response.text
    if endpoint != "artifact":
        with modules.app.state.database.connect() as connection:
            assert connection.execute("SELECT COUNT(*) FROM module_personalizations").fetchone()[0] == 0


@pytest.mark.parametrize("column,value", [("hwid_sha256", None), ("token_signing_key", bytes(32)), ("install_signing_key", bytes(32)), ("monitor_hash", bytes(32)), ("hardware_core_hash", bytes(32))])
def test_live_device_identity_changes_reject_old_evidence_even_after_authorization(modules, column, value):
    granted = authorize(modules)["capabilities"][0]
    with modules.app.state.database.connect() as connection:
        connection.execute("UPDATE devices SET " + column + "=?", (value,))
    for path, options in [("/v1/modules/catalog", {}), ("/v1/modules/authorize", {"method": "POST", "value": {"catalog_sha256": modules.digest, "modules": ["Auto Tool"]}}), (granted["url"], {"capability": granted["capability"]})]:
        response, _ = call(modules, path, **options)
        assert response.status_code == 401, response.text


def test_module_session_without_explicit_hwid_cannot_downgrade_to_core_fingerprint(modules):
    claims = modules.app.state.signer.verify(modules.session_token)
    claims.pop("hwid_sha256")
    response, _ = call(modules, "/v1/modules/catalog", token=modules.app.state.signer.sign(claims))
    assert response.status_code == 401


def test_signed_responses_bind_the_exact_module_evidence(modules):
    for path, options in [("/v1/modules/catalog", {}), ("/v1/modules/authorize", {"method": "POST", "value": {"catalog_sha256": modules.digest, "modules": ["Auto Tool"]}})]:
        private, headers, body = request(modules, path, **options)
        response = modules.client.request(options.get("method", "GET"), path, headers=headers, content=body)
        assert response.status_code == 200, response.text
        clear = json.loads(decrypt(response.content, private, modules.session_token, path, headers))
        token = clear["proof"] if "proof" in clear else clear["capabilities"][0]["capability"]
        claims = modules.app.state.signer.verify(token)
        assert claims["evidence_sha256"] == hashlib.sha256(b64d(headers["X-Celeste-Module-Evidence"])).hexdigest()


@pytest.mark.parametrize("column,value", [("token_signing_key", bytes(32)), ("install_signing_key", bytes(32)), ("monitor_hash", bytes(32))])
def test_device_changes_during_personalization_never_release_key(modules, monkeypatch, column, value):
    import app.module_delivery as module
    original = module.personalize
    def changed(*args, **kwargs):
        result = original(*args, **kwargs)
        with modules.app.state.database.connect() as connection:
            connection.execute("UPDATE devices SET " + column + "=?", (value,))
        return result
    monkeypatch.setattr(module, "personalize", changed)
    response, _ = call(modules, "/v1/modules/authorize", method="POST", value={"catalog_sha256": modules.digest, "modules": ["Auto Tool"]})
    assert response.status_code == 401


@pytest.mark.parametrize("field", ["sub", "device", "cnf", "hardware_sha256", "client_sha256", "beta_catalog_sha256", "process_id", "session_id", "release", "client_version"])
def test_incomplete_signed_runtime_claims_fail_closed_without_server_error(modules, field):
    claims = modules.app.state.signer.verify(modules.session_token)
    claims.pop(field)
    response, _ = call(modules, "/v1/modules/catalog", token=modules.app.state.signer.sign(claims))
    assert response.status_code == 401


@pytest.mark.parametrize("endpoint", ["catalog", "authorize", "artifact"])
@pytest.mark.parametrize("replacement", ["missing", "hardware", "process", "session"])
def test_replaced_runtime_binding_cannot_use_old_lease(modules, endpoint, replacement):
    granted = authorize(modules)["capabilities"][0] if endpoint == "artifact" else None
    with modules.app.state.database.connect() as connection:
        if replacement == "missing":
            connection.execute("DELETE FROM runtime_sessions")
        else:
            column = {"hardware": "hardware_sha256", "process": "process_id", "session": "session_id"}[replacement]
            value = 98765 if replacement == "process" else "e" * (64 if replacement == "hardware" else 32)
            connection.execute("UPDATE runtime_sessions SET " + column + "=?", (value,))
    path = granted["url"] if granted else "/v1/modules/" + endpoint
    options = {"method": "POST", "value": {"catalog_sha256": modules.digest, "modules": ["Auto Tool"]}} if endpoint == "authorize" else {}
    if granted:
        options["capability"] = granted["capability"]
    response, _ = call(modules, path, **options)
    assert response.status_code == 401
    assert response.json()["detail"] == "runtime session replaced"


def test_new_runtime_session_invalidates_old_but_same_session_renewal_works(modules):
    old = modules.session_token
    refreshed = post_session(modules, session(modules))
    assert refreshed.status_code == 200
    newer = refreshed.json()["session_token"]
    response, _ = call(modules, "/v1/modules/catalog", token=old)
    assert response.status_code == 401
    response, _ = call(modules, "/v1/modules/catalog", token=newer)
    assert response.status_code == 200


def test_runtime_replaced_during_preparation_never_releases_key(modules, monkeypatch):
    import app.module_delivery as module
    original = module.personalize
    def changed(*args, **kwargs):
        result = original(*args, **kwargs)
        with modules.app.state.database.connect() as connection:
            connection.execute("UPDATE runtime_sessions SET session_id=?", ("e" * 32,))
        return result
    monkeypatch.setattr(module, "personalize", changed)
    response, _ = call(modules, "/v1/modules/authorize", method="POST", value={"catalog_sha256": modules.digest, "modules": ["Auto Tool"]})
    assert response.status_code == 401


@pytest.mark.parametrize("change", ["revoked", "session", "expired"])
def test_module_stream_revalidates_before_emitting_final_tag(modules, monkeypatch, change):
    import app.module_delivery as module
    original = module.encrypt_chunks
    granted = authorize(modules)["capabilities"][0]
    _, headers, _ = request(modules, granted["url"], capability=granted["capability"])
    def interrupted(*args):
        chunks = list(original(*args))
        yield from chunks[:-1]
        if change == "expired":
            expiry = modules.app.state.signer.verify(granted["capability"])["exp"]
            monkeypatch.setattr(module, "time", SimpleNamespace(time=lambda: expiry))
        else:
            with modules.app.state.database.connect() as connection:
                if change == "revoked": connection.execute("UPDATE licenses SET status='revoked'")
                else: connection.execute("UPDATE runtime_sessions SET session_id=?", ("e" * 32,))
        yield chunks[-1]
    monkeypatch.setattr(module, "encrypt_chunks", interrupted)
    # ASGI has already started the response; no error response may complete the
    # encrypted stream. The generic response fixture asserts exact missing tag.
    with pytest.raises(RuntimeError, match="response already started"):
        modules.client.get(granted["url"], headers=headers)


def test_parallel_jvms_keep_independent_current_module_sessions(modules):
    first = modules.session_token
    second_response = post_session(modules, session(modules, pid=5678))
    assert second_response.status_code == 200
    second = second_response.json()["session_token"]
    for token in (first, second):
        response, _ = call(modules, "/v1/modules/catalog", token=token)
        assert response.status_code == 200
    replacement = post_session(modules, session(modules, pid=1234))
    assert replacement.status_code == 200
    late = post_session(modules, session(modules, pid=1234, identity=modules.session_body["session_id"]))
    assert late.status_code == 409
    assert late.json()["error_code"] == "concurrent_session_rejected"
    assert call(modules, "/v1/modules/catalog", token=first)[0].status_code == 401
    assert call(modules, "/v1/modules/catalog", token=second)[0].status_code == 200
    assert call(modules, "/v1/modules/catalog", token=replacement.json()["session_token"])[0].status_code == 200


@pytest.mark.parametrize("module_count", [2, 12, 86])
def test_bulk_authorization_release_scans_do_not_scale_with_module_count(modules, monkeypatch, module_count):
    """A cutover renewal checks current policy for each item without N full rosters."""
    import app.module_delivery as module
    from pathlib import Path
    root = Path(modules.config.module_release_dir)
    template = modules.catalog["modules"][0]
    for index in range(len(modules.catalog["modules"]), module_count):
        opaque = f"{index + 1:032x}"
        item = {**template, "id": f"Fixture {index}", "artifact_id": opaque, "filename": opaque + ".jar"}
        (root / "modules" / item["filename"]).write_bytes(modules.jars["Auto Tool"])
        modules.catalog["modules"].append(item)
    modules.catalog["executable_root_sha256"] = executable_root(modules.catalog)
    (root / "module-release.json").write_bytes(canonical_json(modules.catalog))
    modules.digest = hashlib.sha256(canonical_json(modules.catalog)).hexdigest()
    config = replace(modules.config, module_delivery_required=True)
    app = create_app(config)
    scans = 0
    original = module.read_catalog

    def counted(*args, **kwargs):
        nonlocal scans
        scans += 1
        return original(*args, **kwargs)

    monkeypatch.setattr(module, "read_catalog", counted)
    with TestClient(app, base_url="https://testserver") as client:
        e = SimpleNamespace(**{**vars(modules), "app": app, "config": config, "client": client})
        result = authorize(e, [item["id"] for item in modules.catalog["modules"]])
    assert len(result["capabilities"]) == module_count
    assert scans <= 8, f"Bulk authorization scanned the entire release {scans} times for {module_count} modules"


def test_slow_artifact_downloads_do_not_block_module_permission_renewals(modules, monkeypatch):
    import app.module_delivery as module
    original = module.ArtifactResponse
    ready = threading.Condition()
    release = threading.Event()
    started = 0

    class SlowResponse(original):
        async def __call__(self, scope, receive, send):
            nonlocal started
            with ready:
                started += 1
                ready.notify_all()
            await asyncio.to_thread(release.wait, 10)
            await super().__call__(scope, receive, send)

    granted = authorize(modules)["capabilities"][0]
    monkeypatch.setattr(module, "ArtifactResponse", SlowResponse)
    with ThreadPoolExecutor(max_workers=2) as workers:
        downloads = [workers.submit(call, modules, granted["url"], capability=granted["capability"]) for _ in range(2)]
        try:
            with ready:
                assert ready.wait_for(lambda: started == 2, timeout=5)
            response, _ = call(modules, "/v1/modules/authorize", method="POST",
                value={"catalog_sha256": modules.digest, "modules": ["Auto Tool"]})
            assert response.status_code == 200, response.text
        finally:
            release.set()
        assert all(download.result(timeout=5)[0].status_code == 200 for download in downloads)


def test_release_change_during_batch_preparation_cannot_release_old_keys(modules, monkeypatch):
    import app.module_delivery as module
    from pathlib import Path
    original = module.personalize
    changed = False

    def replace_release(*args, **kwargs):
        nonlocal changed
        result = original(*args, **kwargs)
        if not changed:
            changed = True
            catalog = {**modules.catalog, "runtime_sha256": "e" * 64}
            (Path(modules.config.module_release_dir) / "module-release.json").write_bytes(canonical_json(catalog))
        return result

    monkeypatch.setattr(module, "personalize", replace_release)
    response, _ = call(modules, "/v1/modules/authorize", method="POST",
        value={"catalog_sha256": modules.digest, "modules": [item["id"] for item in modules.catalog["modules"]]})
    assert response.status_code in (409, 503)


def test_every_batch_item_rechecks_live_authority_before_preparing(modules, monkeypatch):
    import app.module_delivery as module
    original = module.personalize
    prepared = []

    def revoke_after_first(catalog, digest, item, jar):
        result = original(catalog, digest, item, jar)
        prepared.append(item["id"])
        with modules.app.state.database.connect() as connection:
            connection.execute("UPDATE licenses SET status='revoked'")
        return result

    monkeypatch.setattr(module, "personalize", revoke_after_first)
    response, _ = call(modules, "/v1/modules/authorize", method="POST",
        value={"catalog_sha256": modules.digest, "modules": [item["id"] for item in modules.catalog["modules"]]})
    assert response.status_code in (401, 403)
    assert prepared == [modules.catalog["modules"][0]["id"]]


def test_maximum_module_personalization_uses_bounded_memory_and_standard_aead():
    import app.module_delivery as module
    jar = b"J" * module.MAX_MODULE_BYTES
    catalog = {"release_id": "memory-fixture", "host_abi": 1}
    item = {"id": "Fixture", "version": "fixture", "artifact_id": "1" * 32,
        "size": len(jar), "sha256": hashlib.sha256(jar).hexdigest(), "entries_sha256": "2" * 64}
    tracemalloc.start()
    try:
        key, ciphertext, manifest = module.personalize(catalog, "3" * 64, item, jar)
        _, peak = tracemalloc.get_traced_memory()
    finally:
        tracemalloc.stop()
    # Input bytes are already available. Preparation must not hold multiple
    # additional 16 MiB plaintext copies alongside its ciphertext allocation.
    assert peak < len(jar) * 2 + 512 * 1024
    container = AESGCM(key).decrypt(bytes(ciphertext[:12]), bytes(ciphertext[12:]),
                                    content_aad(catalog, "3" * 64, item))
    assert len(container) == manifest["container_size"]
    assert hashlib.sha256(container).hexdigest() == manifest["container_sha256"]
    assert hashlib.sha256(ciphertext).hexdigest() == manifest["ciphertext_sha256"]
    metadata_size = struct.unpack_from(">I", container, len(MAGIC))[0]
    metadata_end = len(MAGIC) + 4 + metadata_size
    metadata = json.loads(container[len(MAGIC) + 4:metadata_end])
    count = struct.unpack_from(">I", container, metadata_end)[0]
    cursor, records = metadata_end + 4, {}
    for _ in range(count):
        identity = container[cursor:cursor + 16].hex()
        length = struct.unpack_from(">I", container, cursor + 16)[0]
        records[identity] = container[cursor + 20:cursor + 20 + length]
        cursor += 20 + length
    assert cursor == len(container)
    assert records[metadata["real_record"]] == jar
    assert 0 < metadata["decoy_bytes"] <= len(jar) // 10
    with pytest.raises(InvalidTag):
        AESGCM(key).decrypt(bytes(ciphertext[:12]), bytes(ciphertext[12:]),
                            content_aad(catalog, "4" * 64, item))
