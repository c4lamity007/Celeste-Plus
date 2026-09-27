from __future__ import annotations

import base64
import hashlib
import json
import re
import time
from dataclasses import replace

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ed25519, x25519
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.hkdf import HKDF
from dpop_client import TestClient, register_key, proof

from app.config import Settings
from app.database import Database
from app.main import create_app
from app.security import PASSWORD_HASHER, b64d, b64e, hash_license


def settings(tmp_path) -> Settings:
    release_dir = tmp_path / "releases"
    release_dir.mkdir()
    first = b"first protected release chunk"
    second = b"second protected release chunk"
    (release_dir / "chunk-a.bin").write_bytes(first)
    (release_dir / "chunk-b.bin").write_bytes(second)
    loader = b"second stage loader"
    (release_dir / "stage-two.bin").write_bytes(loader)
    loader_three = b"third stage loader"
    (release_dir / "stage-three.bin").write_bytes(loader_three)
    loader_four = b"fourth stage loader"
    (release_dir / "stage-four.bin").write_bytes(loader_four)
    bootstrap = b"mods folder bootstrap"
    (release_dir / "celeste-bootstrap.jar").write_bytes(bootstrap)
    return Settings(
        database_path=str(tmp_path / "auth.sqlite3"),
        signing_private_key=bytes(range(32)),
        key_pepper=bytes(range(1, 33)),
        session_secret=bytes(range(2, 34)),
        admin_password_hash=PASSWORD_HASHER.hash("correct horse battery staple"),
        allowed_hosts=("testserver",),
        release_id="r-2026-08-30",
        release_sha256="a" * 64,
        client_version="1.0.0",
        chunk_a_id="4e95b7f8d3",
        chunk_b_id="a2016c934b",
        chunk_a_sha256=hashlib.sha256(first).hexdigest(),
        chunk_b_sha256=hashlib.sha256(second).hexdigest(),
        artifact_dir=str(release_dir),
        installer_compatible_sha256=frozenset({"a" * 64}),
        cloud_config_dir=str(tmp_path / "cloud-configs"),
        bootstrap_id="bootstrap-test",
        bootstrap_sha256=hashlib.sha256(bootstrap).hexdigest(),
        bootstrap_filename="celeste-bootstrap.jar",
        loader_id="loader-test",
        loader_sha256=hashlib.sha256(loader).hexdigest(),
        loader_three_id="loader-three-test",
        loader_three_sha256=hashlib.sha256(loader_three).hexdigest(),
        loader_three_filename="stage-three.bin",
        loader_four_id="loader-four-test",
        loader_four_sha256=hashlib.sha256(loader_four).hexdigest(),
        loader_four_filename="stage-four.bin",
    )


def raw_public(key) -> str:
    return b64e(key.public_key().public_bytes(serialization.Encoding.Raw, serialization.PublicFormat.Raw))


def hardware(monitor: str = "4" * 64) -> dict:
    return {
        "system": "1" * 64,
        "board": "2" * 64,
        "processor": "3" * 64,
        "monitors": [monitor],
    }


def release() -> dict:
    return {
        "release_id": "r-2026-08-30",
        "client_version": "1.0.0",
        "client_sha256": "a" * 64,
        "game_uuid": "12345678-1234-1234-1234-1234567890ab",
        "game_name": "AuditOnlyName",
    }


def create_license(config: Settings, key: str) -> int:
    database = Database(config.database_path)
    with database.connect() as connection:
        cursor = connection.execute(
            "INSERT INTO licenses(key_hash,label,status,max_devices,created_at) VALUES(?,?,'active',1,1)",
            (hash_license(config.key_pepper, key), "test"),
        )
        uid = int(cursor.lastrowid)
        connection.execute("INSERT INTO license_entitlements(license_uid,product,created_at) VALUES(?,'celeste',1)", (uid,))
        return uid


def enroll(
    client: TestClient,
    license_key: str,
    client_sha256: str | None = None,
    release_id: str | None = None,
    client_version: str | None = None,
    product: str = "celeste",
):
    install_signing = register_key(ed25519.Ed25519PrivateKey.generate())
    install_encryption = x25519.X25519PrivateKey.generate()
    start = client.post(
        "/v1/enroll/start",
        json={
            **release(),
            **({"client_sha256": client_sha256} if client_sha256 is not None else {}),
            **({"release_id": release_id} if release_id is not None else {}),
            **({"client_version": client_version} if client_version is not None else {}),
            "license_key": license_key,
            "product": product,
            "install_signing_key": raw_public(install_signing),
            "install_encryption_key": raw_public(install_encryption),
            "hardware": hardware(),
        },
    )
    assert start.status_code == 200, start.text
    challenge = start.json()
    message = b64d(challenge["message"])
    complete = client.post(
        "/v1/enroll/complete",
        headers={"DPoP": proof(install_signing, "POST", str(client.base_url).rstrip("/") + "/v1/enroll/complete")},
        json={
            "challenge_id": challenge["challenge_id"],
            "install_signature": b64e(install_signing.sign(message)),
        },
    )
    assert complete.status_code == 200, complete.text
    result = complete.json()
    envelope = result["token_envelope"]
    ephemeral = x25519.X25519PublicKey.from_public_bytes(b64d(envelope["ephemeral_key"], 32))
    shared = install_encryption.exchange(ephemeral)
    # The challenge nonce is part of the signed message; decode the canonical
    # transcript to obtain the HKDF salt exactly as the client does.
    nonce = b64d(json.loads(message)["nonce"], 32)
    derived = HKDF(
        algorithm=hashes.SHA256(),
        length=32,
        salt=nonce,
        info=b"celeste-install-token-v1",
    ).derive(shared)
    token_seed = AESGCM(derived).decrypt(b64d(envelope["nonce"], 12), b64d(envelope["ciphertext"]), message)
    return result, install_signing, ed25519.Ed25519PrivateKey.from_private_bytes(token_seed)


def test_enrollment_dual_proof_monitor_lock_and_reset(tmp_path):
    legacy_digest = "b" * 64
    launcher_id = "stable-launcher-channel"
    launcher_version = "1"
    config = replace(
        settings(tmp_path),
        installer_channel_id=launcher_id,
        installer_channel_version=launcher_version,
        installer_compatible_sha256=frozenset({legacy_digest}),
    )
    assert not config.accepts_installer_identity(launcher_id, config.release_sha256)
    assert replace(config, installer_allow_runtime_digest=True).accepts_installer_identity(
        launcher_id, config.release_sha256
    )
    app = create_app(config)
    license_key = "CST-ABCDE-FGHIJ-KLMNO-PQRST-UVWXY-12345"
    uid = create_license(config, license_key)

    with TestClient(app, base_url="https://testserver") as client:
        unknown_enroll = client.post(
            "/v1/enroll/start",
            json={
                **release(),
                "release_id": launcher_id,
                "client_version": launcher_version,
                "client_sha256": "c" * 64,
                "license_key": license_key,
                "install_signing_key": raw_public(ed25519.Ed25519PrivateKey.generate()),
                "install_encryption_key": raw_public(x25519.X25519PrivateKey.generate()),
                "hardware": hardware(),
            },
        )
        assert unknown_enroll.status_code == 409

        runtime_digest_enroll = client.post(
            "/v1/enroll/start",
            json={
                **release(),
                "release_id": launcher_id,
                "client_version": launcher_version,
                "license_key": license_key,
                "install_signing_key": raw_public(ed25519.Ed25519PrivateKey.generate()),
                "install_encryption_key": raw_public(x25519.X25519PrivateKey.generate()),
                "hardware": hardware(),
            },
        )
        assert runtime_digest_enroll.status_code == 409

        enrolled, install_key, token_key = enroll(
            client,
            license_key,
            legacy_digest,
            launcher_id,
            launcher_version,
        )
        assert enrolled["uid"] == uid

        installer_start = client.post(
            "/v1/installer/start",
            json={
                **release(),
                "release_id": launcher_id,
                "client_version": launcher_version,
                "client_sha256": legacy_digest,
                "device_id": enrolled["device_id"],
                "hardware": hardware(),
            },
        )
        assert installer_start.status_code == 200, installer_start.text
        installer_message = b64d(installer_start.json()["message"])
        installer_complete = client.post(
            "/v1/installer/complete",
            json={
                "challenge_id": installer_start.json()["challenge_id"],
                "install_signature": b64e(install_key.sign(installer_message)),
                "token_signature": b64e(token_key.sign(installer_message)),
            },
        )
        assert installer_complete.status_code == 200, installer_complete.text
        installer_grant = installer_complete.json()["grant"]
        installer_claims = app.state.signer.verify(installer_grant)
        assert installer_claims is not None
        assert installer_claims["release"] == launcher_id
        assert installer_claims["client_sha256"] == legacy_digest
        assert installer_claims["payload_release"] == config.release_id
        assert installer_claims["payload_sha256"] == config.release_sha256
        bootstrap = client.get(
            "/v1/bootstrap/bootstrap-test",
            headers={"Authorization": f"Bearer {installer_grant}"},
        )
        assert bootstrap.status_code == 200
        assert bootstrap.content == b"mods folder bootstrap"
        consumed = client.post(
            "/v1/installer/consume",
            json={},
            headers={"Authorization": f"Bearer {installer_grant}"},
        )
        assert consumed.status_code == 200, consumed.text
        assert consumed.json() == {"accepted": True}
        replayed = client.post(
            "/v1/installer/consume",
            json={},
            headers={"Authorization": f"Bearer {installer_grant}"},
        )
        assert replayed.status_code == 401
        denied_stage = client.get(
            "/v1/loader/loader-test",
            headers={"Authorization": f"Bearer {installer_grant}"},
        )
        assert denied_stage.status_code == 401

        legacy_auth = client.post(
            "/v1/auth/start",
            json={
                **release(),
                "client_sha256": legacy_digest,
                "device_id": enrolled["device_id"],
                "hardware": hardware(),
            },
        )
        assert legacy_auth.status_code == 409

        wrong_monitor = client.post(
            "/v1/auth/start",
            json={**release(), "device_id": enrolled["device_id"], "hardware": hardware("9" * 64)},
        )
        assert wrong_monitor.status_code == 401

        start = client.post(
            "/v1/auth/start",
            json={**release(), "device_id": enrolled["device_id"], "hardware": hardware()},
        )
        assert start.status_code == 200, start.text
        message = b64d(start.json()["message"])
        complete = client.post(
            "/v1/auth/complete",
            json={
                "challenge_id": start.json()["challenge_id"],
                "install_signature": b64e(install_key.sign(message)),
                "token_signature": b64e(token_key.sign(message)),
            },
        )
        assert complete.status_code == 200, complete.text
        grant = complete.json()["grant"]
        loader = client.get("/v1/loader/loader-test", headers={"Authorization": f"Bearer {grant}"})
        assert loader.status_code == 200
        assert loader.content == b"second stage loader"
        denied_chunk = client.get("/v1/blob/4e95b7f8d3", headers={"Authorization": f"Bearer {grant}"})
        assert denied_chunk.status_code == 401

        stage_start = client.post(
            "/v1/stage2/start",
            json={**release(), "device_id": enrolled["device_id"], "hardware": hardware()},
        )
        assert stage_start.status_code == 200, stage_start.text
        stage_message = b64d(stage_start.json()["message"])
        stage_complete = client.post(
            "/v1/stage2/complete",
            json={
                "challenge_id": stage_start.json()["challenge_id"],
                "install_signature": b64e(install_key.sign(stage_message)),
                "token_signature": b64e(token_key.sign(stage_message)),
            },
        )
        assert stage_complete.status_code == 200, stage_complete.text
        stage_grant = stage_complete.json()["grant"]
        third = client.get(
            "/v1/loader/loader-three-test",
            headers={"Authorization": f"Bearer {stage_grant}"},
        )
        assert third.status_code == 200
        assert third.content == b"third stage loader"

        third_start = client.post(
            "/v1/stage3/start",
            json={**release(), "device_id": enrolled["device_id"], "hardware": hardware()},
        )
        assert third_start.status_code == 200, third_start.text
        third_message = b64d(third_start.json()["message"])
        third_complete = client.post(
            "/v1/stage3/complete",
            json={
                "challenge_id": third_start.json()["challenge_id"],
                "install_signature": b64e(install_key.sign(third_message)),
                "token_signature": b64e(token_key.sign(third_message)),
            },
        )
        assert third_complete.status_code == 200, third_complete.text
        third_grant = third_complete.json()["grant"]
        fourth = client.get(
            "/v1/loader/loader-four-test",
            headers={"Authorization": f"Bearer {third_grant}"},
        )
        assert fourth.status_code == 200
        assert fourth.content == b"fourth stage loader"

        fourth_start = client.post(
            "/v1/stage4/start",
            json={**release(), "device_id": enrolled["device_id"], "hardware": hardware()},
        )
        assert fourth_start.status_code == 200, fourth_start.text
        fourth_message = b64d(fourth_start.json()["message"])
        fourth_complete = client.post(
            "/v1/stage4/complete",
            json={
                "challenge_id": fourth_start.json()["challenge_id"],
                "install_signature": b64e(install_key.sign(fourth_message)),
                "token_signature": b64e(token_key.sign(fourth_message)),
            },
        )
        assert fourth_complete.status_code == 200, fourth_complete.text
        fourth_grant = fourth_complete.json()["grant"]
        chunk = client.get("/v1/blob/4e95b7f8d3", headers={"Authorization": f"Bearer {fourth_grant}"})
        assert chunk.status_code == 200
        assert chunk.content == b"first protected release chunk"

        login = client.post(
            "/admin/login",
            data={"password": "correct horse battery staple"},
            follow_redirects=True,
        )
        assert login.status_code == 200
        csrf = re.search(r'name="csrf" value="([^"]+)"', login.text).group(1)
        reset = client.post(f"/admin/licenses/{uid}/reset", data={"csrf": csrf}, follow_redirects=False)
        assert reset.status_code == 303

        after_reset = client.post(
            "/v1/auth/start",
            json={**release(), "device_id": enrolled["device_id"], "hardware": hardware()},
        )
        assert after_reset.status_code == 401


def test_challenge_is_one_time_and_requires_both_signatures(tmp_path):
    config = settings(tmp_path)
    app = create_app(config)
    license_key = "CST-55555-44444-33333-22222-11111-AAAAA"
    create_license(config, license_key)
    with TestClient(app, base_url="https://testserver") as client:
        enrolled, install_key, token_key = enroll(client, license_key)
        start = client.post(
            "/v1/auth/start",
            json={**release(), "device_id": enrolled["device_id"], "hardware": hardware()},
        ).json()
        message = b64d(start["message"])
        missing = client.post(
            "/v1/auth/complete",
            json={
                "challenge_id": start["challenge_id"],
                "install_signature": b64e(install_key.sign(message)),
            },
        )
        assert missing.status_code == 401
        valid_body = {
            "challenge_id": start["challenge_id"],
            "install_signature": b64e(install_key.sign(message)),
            "token_signature": b64e(token_key.sign(message)),
        }
        assert client.post("/v1/auth/complete", json=valid_body).status_code == 200
        assert client.post("/v1/auth/complete", json=valid_body).status_code == 401


def test_admin_login_counts_only_failures_and_success_clears_them(tmp_path):
    config = settings(tmp_path)
    app = create_app(config)
    with TestClient(app, base_url="https://testserver") as client:
        for _ in range(5):
            failed = client.post("/admin/login", data={"password": "wrong"})
            assert failed.status_code == 401

        successful = client.post(
            "/admin/login",
            data={"password": "correct horse battery staple"},
            follow_redirects=False,
        )
        assert successful.status_code == 303

        client.cookies.clear()
        assert client.post("/admin/login", data={"password": "wrong"}).status_code == 401


def test_session_heartbeat_tracks_activity_and_tamper_auto_revokes(tmp_path):
    config = settings(tmp_path)
    app = create_app(config)
    license_key = "CST-LIVE1-LIVE2-LIVE3-LIVE4-LIVE5-LIVE6"
    uid = create_license(config, license_key)
    database = Database(config.database_path)
    with database.connect() as connection:
        connection.execute(
            "UPDATE licenses SET duration_kind='day',duration_seconds=86400 WHERE uid=?",
            (uid,),
        )

    with TestClient(app, base_url="https://testserver") as client:
        enrolled, install_key, token_key = enroll(client, license_key)
        with database.connect() as connection:
            license_row = connection.execute("SELECT * FROM licenses WHERE uid=?", (uid,)).fetchone()
            assert license_row["activated_at"] is not None
            assert int(license_row["expires_at"]) - int(license_row["activated_at"]) == 86400

        def check(nonce: str, observed: str, event: str):
            timestamp = int(time.time())
            message = (
                "celeste-session-v1\n"
                f"{enrolled['device_id']}\n{timestamp}\n{nonce}\n"
                f"{'a' * 64}\n{observed}\n{event}\n"
                "12345678-1234-1234-1234-1234567890ab\nAuditOnlyName"
            ).encode()
            return client.post(
                "/v1/session/check",
                json={
                    **release(),
                    "device_id": enrolled["device_id"],
                    "timestamp": timestamp,
                    "nonce": nonce,
                    "observed_sha256": observed,
                    "event": event,
                    "install_signature": b64e(install_key.sign(message)),
                    "token_signature": b64e(token_key.sign(message)),
                },
            )

        active = check("heartbeat_nonce_0001", "a" * 64, "heartbeat")
        assert active.status_code == 200, active.text
        assert active.json()["status"] == "active"

        tampered = check("heartbeat_nonce_0002", "b" * 64, "integrity_mismatch")
        assert tampered.status_code == 401
        with database.connect() as connection:
            license_row = connection.execute("SELECT * FROM licenses WHERE uid=?", (uid,)).fetchone()
            device = connection.execute("SELECT * FROM devices WHERE license_uid=?", (uid,)).fetchone()
            event = connection.execute("SELECT * FROM security_events WHERE license_uid=?", (uid,)).fetchone()
            assert license_row["status"] == "revoked"
            assert "integrity mismatch" in license_row["revoked_reason"]
            assert device["last_call_endpoint"] == "/v1/session/check"
            assert int(device["tamper_count"]) == 1
            assert event["event_type"] == "client_integrity_mismatch"


def test_admin_duration_creation_and_license_detail(tmp_path):
    config = settings(tmp_path)
    app = create_app(config)
    database = Database(config.database_path)
    with TestClient(app, base_url="https://testserver") as client:
        login = client.post(
            "/admin/login",
            data={"password": "correct horse battery staple"},
            follow_redirects=True,
        )
        csrf = re.search(r'name="csrf" value="([^"]+)"', login.text).group(1)
        created = client.post(
            "/admin/licenses",
            data={"csrf": csrf, "product": "celeste", "label": "One day", "duration": "day", "max_devices": "1"},
        )
        assert created.status_code == 200
        assert "License created" in created.text
        displayed_key = re.search(r"<code>(CST-[A-F0-9-]+)</code>", created.text).group(1)
        with database.connect() as connection:
            row = connection.execute("SELECT * FROM licenses ORDER BY uid DESC LIMIT 1").fetchone()
            assert row["duration_kind"] == "day"
            assert int(row["duration_seconds"]) == 86400
            assert row["activated_at"] is None
            assert row["key_prefix"] == displayed_key[:11]
            assert displayed_key not in {str(value) for value in row}
        detail = client.get(f"/admin/licenses/{row['uid']}")
        assert detail.status_code == 200
        assert "One day" in detail.text
        assert ">Day<" in detail.text
        assert f"{displayed_key[:11]}…" in detail.text


def test_admin_users_and_flags_views(tmp_path):
    config = settings(tmp_path)
    app = create_app(config)
    license_key = "CST-USERS-FLAGS-VIEW1-VIEW2-VIEW3-VIEW4"
    uid = create_license(config, license_key)
    database = Database(config.database_path)

    with TestClient(app, base_url="https://testserver") as client:
        enrolled, _, _ = enroll(client, license_key)
        now = int(time.time())
        with database.connect() as connection:
            connection.execute(
                "UPDATE devices SET last_seen_at=? WHERE device_id=?",
                (now, enrolled["device_id"]),
            )
            connection.execute(
                """
                INSERT INTO security_events(
                    occurred_at,license_uid,device_id,severity,event_type,details,client_ip
                ) VALUES(?,?,?,?,?,?,?)
                """,
                (now, uid, enrolled["device_id"], "high", "test_user_flag", "user flag detail", "127.0.0.1"),
            )
            connection.execute(
                """
                INSERT INTO security_events(
                    occurred_at,license_uid,device_id,severity,event_type,details,client_ip
                ) VALUES(?,NULL,'',?,?,?,?)
                """,
                (now, "high", "admin_login_failed", "website flag detail", "127.0.0.1"),
            )

        login = client.post(
            "/admin/login",
            data={"password": "correct horse battery staple"},
            follow_redirects=True,
        )
        assert login.status_code == 200
        assert 'href="/admin/users"' in login.text
        assert 'href="/admin/flags"' in login.text

        users = client.get("/admin/users")
        assert users.status_code == 200
        assert "AuditOnlyName" in users.text
        assert "Online" in users.text
        assert "signed heartbeat" in users.text

        flags = client.get("/admin/flags")
        assert flags.status_code == 200
        assert "Test User Flag" in flags.text
        assert "user flag detail" in flags.text
        assert "website flag detail" in flags.text


def test_cloud_configs_publish_list_download_and_persist_on_vps_storage(tmp_path):
    config = settings(tmp_path)
    app = create_app(config)
    license_key = "CST-CLOUD-CONFIG-IMAGE-VIEW1-VIEW2-VIEW3"
    create_license(config, license_key)

    with TestClient(app, base_url="https://testserver") as client:
        enrolled, install_key, token_key = enroll(client, license_key)
        start = client.post(
            "/v1/auth/start",
            json={**release(), "device_id": enrolled["device_id"], "hardware": hardware()},
        ).json()
        message = b64d(start["message"])
        complete = client.post(
            "/v1/auth/complete",
            json={
                "challenge_id": start["challenge_id"],
                "install_signature": b64e(install_key.sign(message)),
                "token_signature": b64e(token_key.sign(message)),
            },
        )
        assert complete.status_code == 200, complete.text
        grant = complete.json()["grant"]
        headers = {"Authorization": f"Bearer {grant}"}

        image = base64.b64decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        )
        published = client.post(
            "/v1/configs",
            headers=headers,
            json={"name": "Crystal Main", "image": b64e(image), "modules": ["Auto Crystal", "Surround"]},
        )
        assert published.status_code == 200, published.text
        config_id = published.json()["id"]

        listing = client.get("/v1/configs", headers=headers)
        assert listing.status_code == 200, listing.text
        assert listing.json()["configs"][0]["id"] == config_id
        assert listing.json()["configs"][0]["modules"] == ["Auto Crystal", "Surround"]
        assert listing.json()["configs"][0]["owned"] is True

        downloaded = client.get(f"/v1/configs/{config_id}/image", headers=headers)
        assert downloaded.status_code == 200
        assert downloaded.headers["content-type"].startswith("image/png")
        assert downloaded.content == image
        assert list((tmp_path / "cloud-configs").glob("*.png"))
        assert client.get("/v1/configs").status_code == 401

        denied_admin = client.get("/admin/configs", follow_redirects=False)
        assert denied_admin.status_code == 303
        login = client.post(
            "/admin/login",
            data={"password": "correct horse battery staple"},
            follow_redirects=False,
        )
        assert login.status_code == 303
        admin_configs = client.get("/admin/configs")
        assert admin_configs.status_code == 200
        assert "Cloud Configs" in admin_configs.text
        assert "Crystal Main" in admin_configs.text
        assert "Auto Crystal" in admin_configs.text
        admin_image = client.get(f"/admin/configs/{config_id}/image")
        assert admin_image.status_code == 200
        assert admin_image.content == image
