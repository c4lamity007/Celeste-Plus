"""The reset command is exercised only on disposable SQLite fixture files."""
import json
import sqlite3
from pathlib import Path

import pytest
from app.database import Database
from app.license_reset import preview, reset_with_backup
from app.license_operator import main
from app.products import issue_license


@pytest.fixture
def catalog(tmp_path):
    path = tmp_path / "fixture.sqlite3"
    database = Database(str(path))
    database.initialize()
    image = tmp_path / "cloud-image.png"
    image.write_bytes(b"owned image fixture remains untouched")
    with database.connect() as c:
        c.execute("INSERT INTO licenses(uid,key_hash,status,max_devices,created_at,label) VALUES(73,?,'active',1,1,'Owner')", (bytes(32),))
        c.executemany("INSERT INTO license_entitlements VALUES(73,?,1)", [("celeste",), ("celeste_plus",)])
        c.execute("INSERT INTO devices(device_id,license_uid,install_signing_key,token_signing_key,hardware_core_hash,monitor_hash,status,created_at) VALUES('fixture-device',73,?,?,?,?,'active',1)", [bytes(32)]*4)
        c.execute("INSERT INTO challenges(challenge_id,kind,license_uid,device_id,nonce,install_signing_key,hardware_core_hash,monitor_hash,release_id,client_sha256,created_at,expires_at) VALUES('fixture-challenge','auth',73,'fixture-device',?,?,?,?,'fixture','digest',1,9999999999)", [bytes(32)]*4)
        c.execute("INSERT INTO session_nonces VALUES('fixture-device','fixture-nonce',9999999999)")
        c.execute("INSERT INTO installer_ticket_uses VALUES('fixture-ticket','fixture-device',1,9999999999)")
        c.execute("INSERT INTO cloud_configs(config_id,owner_license_uid,slug,name,image_filename,image_sha256,image_size,created_at,updated_at) VALUES('fixture-config',73,'fixture','fixture','cloud-image.png','digest',10,1,1)")
        c.execute("INSERT INTO security_events(occurred_at,license_uid,device_id,severity,event_type) VALUES(1,73,'fixture-device','low','fixture')")
        c.execute("INSERT INTO audit_log(occurred_at,event,license_uid) VALUES(1,'fixture-audit',73)")
        c.execute("INSERT INTO admin_sessions(session_id,created_at,expires_at) VALUES('fixture-admin',1,9999999999)")
    return database, image


def plan(database):
    with database.connect() as c:
        return preview(c)


def test_reset_verified_backup_archives_history_keeps_images_admin_and_uid_sequence(catalog, tmp_path):
    database, image = catalog
    before = plan(database)
    backup = tmp_path / "private-backup.sqlite3"
    result = reset_with_backup(database.path, str(backup), before["plan_sha256"])
    assert result["result"] == "reset" and not any(result["remaining"].values())
    assert result["removed"] == before["counts"]
    assert image.read_bytes() == b"owned image fixture remains untouched"
    with sqlite3.connect(backup) as copy:
        assert preview(copy) == before
        assert copy.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
    with database.connect() as c:
        assert c.execute("SELECT COUNT(*) FROM admin_sessions").fetchone()[0] == 1
        assert c.execute("SELECT COUNT(*) FROM audit_log WHERE event='fixture-audit' AND license_uid=73").fetchone()[0] == 1
        assert c.execute("SELECT license_uid FROM security_events").fetchone()[0] is None
        assert c.execute("SELECT former_license_uid FROM archived_security_license_links").fetchone()[0] == 73
        archived = c.execute("SELECT metadata_json FROM archived_cloud_configs").fetchone()[0]
        assert json.loads(archived)["owner_license_uid"] == 73
        assert json.loads(archived)["image_filename"] == "cloud-image.png"
        assert c.execute("PRAGMA foreign_key_check").fetchone() is None
        uid, _ = issue_license(c, bytes(32), "both")
        assert uid > 73


def test_reset_rolls_back_all_changes_after_fault_and_keeps_recovery_backup(catalog, tmp_path):
    database, _ = catalog
    before = plan(database)
    backup = tmp_path / "rollback-backup.sqlite3"
    def fail():
        raise RuntimeError("isolated injected failure")
    with pytest.raises(RuntimeError, match="isolated injected"):
        reset_with_backup(database.path, str(backup), before["plan_sha256"], before_commit=fail)
    assert plan(database) == before
    with sqlite3.connect(backup) as c:
        assert preview(c) == before
    with database.connect() as c:
        assert c.execute("SELECT license_uid FROM security_events").fetchone()[0] == 73
        assert c.execute("SELECT name FROM sqlite_master WHERE name='archived_cloud_configs'").fetchone() is None


def test_reset_refuses_changed_state_and_existing_backup_without_mutation(catalog, tmp_path):
    database, _ = catalog
    before = plan(database)
    backup = tmp_path / "existing.sqlite3"
    backup.write_bytes(b"never overwrite existing file")
    with pytest.raises(ValueError, match="new file"):
        reset_with_backup(database.path, str(backup), before["plan_sha256"])
    assert backup.read_bytes() == b"never overwrite existing file"
    with database.connect() as c:
        c.execute("UPDATE licenses SET status='revoked' WHERE uid=73")
    current = plan(database)
    absent = tmp_path / "stale.sqlite3"
    with pytest.raises(ValueError, match="state changed"):
        reset_with_backup(database.path, str(absent), before["plan_sha256"])
    assert plan(database) == current and not absent.exists()


def test_reset_can_run_before_entitlement_schema_deployment(catalog, tmp_path):
    database, _ = catalog
    with database.connect() as c:
        c.execute("DROP TABLE license_entitlements")
    before = plan(database)
    assert before["counts"]["license_entitlements"] == 0
    result = reset_with_backup(database.path, str(tmp_path / "legacy-backup.sqlite3"), before["plan_sha256"])
    assert not any(result["remaining"].values())


def test_cli_preview_is_readonly_and_apply_requires_explicit_contract(catalog, capsys):
    database, _ = catalog
    before = plan(database)
    main(["preview-reset", "--database", database.path])
    assert json.loads(capsys.readouterr().out) == before
    assert plan(database) == before
    with pytest.raises(SystemExit):
        main(["reset", "--database", database.path])
    assert plan(database) == before
