"""Explicit operator-only clean-slate operation. Never called during app startup."""
from __future__ import annotations

import hashlib
import json
import os
import sqlite3
import time
import uuid
from pathlib import Path

from .security import canonical_json

TABLES = ("licenses", "devices", "challenges", "license_entitlements", "session_nonces",
          "installer_ticket_uses", "cloud_configs")


def preview(connection) -> dict:
    existing = {row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table'")}
    missing = set(TABLES) - existing
    if missing - {"license_entitlements"}:
        raise ValueError("Unsupported database schema for license reset")
    counts = {name: connection.execute(f"SELECT COUNT(*) FROM {name}").fetchone()[0] if name in existing else 0
              for name in TABLES}
    # Include identities in the digest so changes with identical counts still require a fresh preview.
    identities = {name: [list(row) for row in connection.execute(query)] for name, query in {
        "licenses": "SELECT uid,hex(key_hash),status FROM licenses ORDER BY uid",
        "devices": "SELECT device_id,license_uid,status FROM devices ORDER BY device_id",
        "cloud_configs": "SELECT config_id,owner_license_uid,image_sha256 FROM cloud_configs ORDER BY config_id",
    }.items()}
    return {"counts": counts, "plan_sha256": hashlib.sha256(canonical_json({"counts": counts, "identities": identities})).hexdigest(),
            "preserves": ["cloud image files", "archived cloud metadata", "audit history", "security history", "admin sessions", "UID sequence"]}


def reset_with_backup(database_path: str, backup_path: str, expected_plan_sha256: str, *, before_commit=None) -> dict:
    source = Path(database_path).resolve(strict=True)
    backup = Path(backup_path).resolve()
    if source == backup or backup.exists() or not backup.parent.is_dir():
        raise ValueError("Backup must be a new file in an existing private directory")
    connection = sqlite3.connect(source, timeout=5)
    connection.row_factory = sqlite3.Row
    connection.execute("PRAGMA foreign_keys=ON")
    try:
        connection.execute("BEGIN IMMEDIATE")
        plan = preview(connection)
        if plan["plan_sha256"] != expected_plan_sha256:
            raise ValueError("License state changed; review a fresh preview before reset")
        descriptor = os.open(backup, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        os.close(descriptor)
        # The writer lock prevents any change while a separate read connection takes the backup.
        with sqlite3.connect(source.as_uri() + "?mode=ro", uri=True) as reader, sqlite3.connect(backup) as destination:
            reader.backup(destination)
            if destination.execute("PRAGMA integrity_check").fetchone()[0] != "ok" or preview(destination)["plan_sha256"] != plan["plan_sha256"]:
                raise RuntimeError("Backup validation failed")
        reset_id = str(uuid.uuid4())
        now = int(time.time())
        connection.execute("""CREATE TABLE IF NOT EXISTS archived_cloud_configs (
            reset_id TEXT NOT NULL, archived_at INTEGER NOT NULL, config_id TEXT NOT NULL,
            former_license_uid INTEGER NOT NULL, metadata_json TEXT NOT NULL,
            PRIMARY KEY(reset_id, config_id))""")
        connection.execute("""CREATE TABLE IF NOT EXISTS archived_security_license_links (
            event_id INTEGER PRIMARY KEY, former_license_uid INTEGER NOT NULL, reset_id TEXT NOT NULL)""")
        for row in connection.execute("SELECT * FROM cloud_configs").fetchall():
            connection.execute("INSERT INTO archived_cloud_configs VALUES(?,?,?,?,?)",
                               (reset_id, now, row["config_id"], row["owner_license_uid"], json.dumps(dict(row), separators=(",", ":"))))
        connection.execute("""INSERT INTO archived_security_license_links SELECT id,license_uid,? FROM security_events
                              WHERE license_uid IS NOT NULL""", (reset_id,))
        connection.execute("UPDATE security_events SET license_uid=NULL WHERE license_uid IS NOT NULL")
        connection.execute("DELETE FROM challenges")
        connection.execute("DELETE FROM devices")  # Cascades replay nonces and one-time installer tickets.
        connection.execute("DELETE FROM licenses")  # Entitlements and active cloud rows cascade; archived metadata and images remain.
        remaining = preview(connection)["counts"]
        if any(remaining.values()):
            raise RuntimeError("License reset left active catalog state")
        connection.execute("INSERT INTO audit_log(occurred_at,event,subject,details) VALUES(?,?,?,?)",
                           (now, "license_catalog_reset", reset_id, json.dumps(plan["counts"], separators=(",", ":"))))
        if connection.execute("PRAGMA foreign_key_check").fetchone() is not None:
            raise RuntimeError("Foreign-key validation failed")
        if before_commit is not None:
            before_commit()  # Isolated regression seam; the operator CLI exposes no callback.
        connection.commit()
        return {"result": "reset", "reset_id": reset_id, "removed": plan["counts"], "remaining": remaining,
                "backup": str(backup), "cloud_images_untouched": True, "admin_sessions_preserved": True}
    except BaseException:
        connection.rollback()
        raise
    finally:
        connection.close()
