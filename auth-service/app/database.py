from __future__ import annotations

import sqlite3
import time
from contextlib import contextmanager
from pathlib import Path


AUTH_EXPIRY_PRUNE_BATCH = 128


SCHEMA = """
PRAGMA journal_mode=WAL;
PRAGMA foreign_keys=ON;

CREATE TABLE IF NOT EXISTS licenses (
    uid INTEGER PRIMARY KEY AUTOINCREMENT,
    key_hash BLOB NOT NULL UNIQUE,
    key_prefix TEXT NOT NULL DEFAULT '',
    label TEXT NOT NULL DEFAULT '',
    status TEXT NOT NULL CHECK(status IN ('active', 'revoked')),
    max_devices INTEGER NOT NULL DEFAULT 1 CHECK(max_devices BETWEEN 1 AND 8),
    created_at INTEGER NOT NULL,
    expires_at INTEGER,
    duration_kind TEXT NOT NULL DEFAULT 'lifetime',
    duration_seconds INTEGER,
    activated_at INTEGER,
    revoked_at INTEGER,
    revoked_reason TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS license_entitlements (
    license_uid INTEGER NOT NULL REFERENCES licenses(uid) ON DELETE CASCADE,
    product TEXT NOT NULL CHECK(product IN ('celeste', 'celeste_plus')),
    created_at INTEGER NOT NULL,
    PRIMARY KEY(license_uid, product)
);

CREATE TABLE IF NOT EXISTS devices (
    device_id TEXT PRIMARY KEY,
    license_uid INTEGER NOT NULL REFERENCES licenses(uid),
    install_signing_key BLOB NOT NULL,
    token_signing_key BLOB NOT NULL,
    hardware_core_hash BLOB NOT NULL,
    monitor_hash BLOB NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('active', 'revoked')),
    created_at INTEGER NOT NULL,
    last_seen_at INTEGER,
    last_game_uuid TEXT NOT NULL DEFAULT '',
    last_game_name TEXT NOT NULL DEFAULT '',
    activation_ip TEXT NOT NULL DEFAULT '',
    last_ip TEXT NOT NULL DEFAULT '',
    last_call_at INTEGER,
    last_call_endpoint TEXT NOT NULL DEFAULT '',
    last_integrity_sha256 TEXT NOT NULL DEFAULT '',
    last_integrity_at INTEGER,
    tamper_count INTEGER NOT NULL DEFAULT 0,
    UNIQUE(license_uid, install_signing_key)
);

CREATE TABLE IF NOT EXISTS challenges (
    challenge_id TEXT PRIMARY KEY,
    kind TEXT NOT NULL CHECK(kind IN ('enroll', 'auth')),
    scope TEXT NOT NULL DEFAULT 'client',
    product TEXT NOT NULL DEFAULT 'celeste' CHECK(product IN ('celeste', 'celeste_plus')),
    license_uid INTEGER NOT NULL REFERENCES licenses(uid),
    device_id TEXT,
    nonce BLOB NOT NULL,
    install_signing_key BLOB NOT NULL,
    install_encryption_key BLOB,
    hardware_core_hash BLOB NOT NULL,
    monitor_hash BLOB NOT NULL,
    release_id TEXT NOT NULL,
    client_sha256 TEXT NOT NULL,
    game_uuid TEXT NOT NULL DEFAULT '',
    game_name TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    consumed_at INTEGER
);

CREATE INDEX IF NOT EXISTS idx_challenges_expiry ON challenges(expires_at);

CREATE TABLE IF NOT EXISTS admin_sessions (
    session_id TEXT PRIMARY KEY,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    ip_hash BLOB,
    user_agent_hash BLOB
);

CREATE TABLE IF NOT EXISTS session_nonces (
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    nonce TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY(device_id, nonce)
);

CREATE INDEX IF NOT EXISTS idx_session_nonces_expiry ON session_nonces(expires_at);

CREATE TABLE IF NOT EXISTS installer_ticket_uses (
    jti TEXT PRIMARY KEY,
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    consumed_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS dpop_replays (
    jkt TEXT NOT NULL,
    jti TEXT NOT NULL,
    expires_at INTEGER NOT NULL,
    PRIMARY KEY(jkt, jti)
);
CREATE INDEX IF NOT EXISTS idx_dpop_replays_expiry ON dpop_replays(expires_at);

CREATE TABLE IF NOT EXISTS revoked_tokens (
    jti TEXT PRIMARY KEY,
    token_sha256 TEXT NOT NULL,
    revoked_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    reason TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_revoked_tokens_expiry ON revoked_tokens(expires_at);

CREATE TABLE IF NOT EXISTS security_events (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at INTEGER NOT NULL,
    license_uid INTEGER REFERENCES licenses(uid),
    device_id TEXT,
    severity TEXT NOT NULL,
    event_type TEXT NOT NULL,
    details TEXT NOT NULL DEFAULT '',
    client_ip TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS rate_limits (
    bucket BLOB PRIMARY KEY,
    count INTEGER NOT NULL,
    window_started_at INTEGER NOT NULL,
    blocked_until INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS audit_log (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    occurred_at INTEGER NOT NULL,
    event TEXT NOT NULL,
    license_uid INTEGER,
    subject TEXT NOT NULL DEFAULT '',
    details TEXT NOT NULL DEFAULT ''
);

CREATE TABLE IF NOT EXISTS cloud_configs (
    config_id TEXT PRIMARY KEY,
    owner_license_uid INTEGER NOT NULL REFERENCES licenses(uid) ON DELETE CASCADE,
    slug TEXT NOT NULL,
    name TEXT NOT NULL,
    author_name TEXT NOT NULL DEFAULT '',
    image_filename TEXT NOT NULL,
    image_sha256 TEXT NOT NULL,
    image_size INTEGER NOT NULL,
    modules_json TEXT NOT NULL DEFAULT '[]',
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    downloads INTEGER NOT NULL DEFAULT 0,
    published INTEGER NOT NULL DEFAULT 1 CHECK(published IN (0, 1)),
    UNIQUE(owner_license_uid, slug)
);

CREATE INDEX IF NOT EXISTS idx_audit_license ON audit_log(license_uid, id DESC);
CREATE INDEX IF NOT EXISTS idx_security_license ON security_events(license_uid, id DESC);
CREATE INDEX IF NOT EXISTS idx_devices_license ON devices(license_uid);
CREATE INDEX IF NOT EXISTS idx_installer_ticket_expiry ON installer_ticket_uses(expires_at);
CREATE INDEX IF NOT EXISTS idx_cloud_configs_public ON cloud_configs(published, updated_at DESC);

CREATE TABLE IF NOT EXISTS native_release_policies (
    product TEXT NOT NULL CHECK(product IN ('celeste','celeste_plus')),
    platform TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    publication_sha256 TEXT NOT NULL,
    binaries_json TEXT NOT NULL,
    published_at INTEGER NOT NULL,
    PRIMARY KEY(product,platform)
);
"""


SCHEMA += """
-- Additive only. No customer/device rows or existing grant state are removed.
CREATE TABLE IF NOT EXISTS active_tokens (
    jti TEXT PRIMARY KEY,
    token_sha256 TEXT NOT NULL,
    license_uid INTEGER NOT NULL,
    device_id TEXT NOT NULL,
    product TEXT NOT NULL,
    audience TEXT NOT NULL,
    issued_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('active','rotated','revoked')),
    rotated_to TEXT,
    changed_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_active_tokens_device ON active_tokens(device_id, status, expires_at);
CREATE INDEX IF NOT EXISTS idx_active_tokens_expiry ON active_tokens(expires_at);
CREATE TABLE IF NOT EXISTS runtime_sessions (
    device_id TEXT NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
    process_id INTEGER NOT NULL,
    session_id TEXT NOT NULL,
    hardware_sha256 TEXT NOT NULL,
    last_seen_at INTEGER NOT NULL,
    status TEXT NOT NULL CHECK(status IN ('active','retired')),
    PRIMARY KEY(device_id,process_id,session_id)
);
CREATE INDEX IF NOT EXISTS idx_runtime_sessions_expiry ON runtime_sessions(last_seen_at);

CREATE TABLE IF NOT EXISTS runtime_bindings (
    device_id TEXT PRIMARY KEY REFERENCES devices(device_id) ON DELETE CASCADE,
    hardware_sha256 TEXT NOT NULL,
    process_id INTEGER NOT NULL,
    session_id TEXT NOT NULL,
    last_seen_at INTEGER NOT NULL,
    conflicted_at INTEGER
);
CREATE TABLE IF NOT EXISTS device_rebindings (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    license_uid INTEGER NOT NULL,
    previous_device_id TEXT NOT NULL,
    next_device_id TEXT NOT NULL,
    occurred_at INTEGER NOT NULL,
    reason TEXT NOT NULL
);

"""


class Database:
    def __init__(self, path: str) -> None:
        self.path = path

    def initialize(self) -> None:
        parent = Path(self.path).resolve().parent
        parent.mkdir(parents=True, exist_ok=True)
        with self.connect() as connection:
            connection.executescript(SCHEMA)
            self._migrate(connection)

    @staticmethod
    def prune_expired_auth(connection: sqlite3.Connection, table: str, now: int) -> int:
        """Caller owns the transaction; indexed cleanup has a fixed row budget.

        Keep the final accepted second: signed session timestamps have an
        inclusive freshness boundary. Never remove a replay marker early.
        Table identifiers come only from this explicit internal allowlist.
        """
        if table not in {"challenges", "session_nonces", "active_tokens"}:
            raise ValueError("unsupported auth expiry table")
        return connection.execute(
            f"DELETE FROM {table} WHERE rowid IN ("
            f"SELECT rowid FROM {table} WHERE expires_at < ? ORDER BY expires_at LIMIT ?)",
            (now, AUTH_EXPIRY_PRUNE_BATCH),
        ).rowcount

    @staticmethod
    def _migrate(connection: sqlite3.Connection) -> None:
        additions = {
            "licenses": {
                "key_prefix": "TEXT NOT NULL DEFAULT ''",
                "duration_kind": "TEXT NOT NULL DEFAULT 'lifetime'",
                "duration_seconds": "INTEGER",
                "activated_at": "INTEGER",
                "revoked_at": "INTEGER",
                "revoked_reason": "TEXT NOT NULL DEFAULT ''",
            },
            "devices": {
                "hwid_sha256": "TEXT",
                "activation_ip": "TEXT NOT NULL DEFAULT ''",
                "last_ip": "TEXT NOT NULL DEFAULT ''",
                "last_call_at": "INTEGER",
                "last_call_endpoint": "TEXT NOT NULL DEFAULT ''",
                "last_integrity_sha256": "TEXT NOT NULL DEFAULT ''",
                "last_integrity_at": "INTEGER",
                "tamper_count": "INTEGER NOT NULL DEFAULT 0",
            },
            "admin_sessions": {
                "ip_hash": "BLOB",
                "user_agent_hash": "BLOB",
            },
            "challenges": {
                "hwid_sha256": "TEXT",
                "rebind_device_id": "TEXT",
                "scope": "TEXT NOT NULL DEFAULT 'client'",
                "product": "TEXT NOT NULL DEFAULT 'celeste' CHECK(product IN ('celeste', 'celeste_plus'))",
            },
        }
        for table, columns in additions.items():
            existing = {str(row["name"]) for row in connection.execute(f"PRAGMA table_info({table})")}
            for name, declaration in columns.items():
                if name not in existing:
                    connection.execute(f"ALTER TABLE {table} ADD COLUMN {name} {declaration}")

    @contextmanager
    def connect(self):
        connection = sqlite3.connect(self.path, timeout=5.0)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys=ON")
        connection.execute("PRAGMA busy_timeout=5000")
        try:
            yield connection
            connection.commit()
        except Exception:
            connection.rollback()
            raise
        finally:
            connection.close()

    @staticmethod
    def rate_limit(connection: sqlite3.Connection, bucket: bytes, limit: int, window: int) -> int:
        now = int(time.time())
        row = connection.execute(
            "SELECT count, window_started_at, blocked_until FROM rate_limits WHERE bucket = ?",
            (bucket,),
        ).fetchone()
        if row is None or int(row["window_started_at"]) + window <= now:
            connection.execute(
                "INSERT INTO rate_limits(bucket,count,window_started_at,blocked_until) VALUES(?,?,?,0) "
                "ON CONFLICT(bucket) DO UPDATE SET count=excluded.count,window_started_at=excluded.window_started_at,blocked_until=0",
                (bucket, 1, now),
            )
            return 0
        if int(row["blocked_until"]) > now:
            return int(row["blocked_until"]) - now
        count = int(row["count"]) + 1
        blocked_until = now + min(900, 2 ** min(10, count - limit)) if count > limit else 0
        connection.execute(
            "UPDATE rate_limits SET count=?, blocked_until=? WHERE bucket=?",
            (count, blocked_until, bucket),
        )
        return max(0, blocked_until - now)

    @staticmethod
    def rate_limit_retry_after(connection: sqlite3.Connection, bucket: bytes, window: int) -> int:
        now = int(time.time())
        row = connection.execute(
            "SELECT window_started_at, blocked_until FROM rate_limits WHERE bucket = ?",
            (bucket,),
        ).fetchone()
        if row is None:
            return 0
        if int(row["window_started_at"]) + window <= now:
            # Another connection may have reset this bucket since the read.
            connection.execute("DELETE FROM rate_limits WHERE bucket = ? AND window_started_at = ?",
                               (bucket, int(row["window_started_at"])))
            return 0
        return max(0, int(row["blocked_until"]) - now)

    @staticmethod
    def clear_rate_limit(connection: sqlite3.Connection, bucket: bytes) -> None:
        connection.execute("DELETE FROM rate_limits WHERE bucket = ?", (bucket,))

    @staticmethod
    def consume_artifact_allowance(connection: sqlite3.Connection, token_bucket: bytes, ip_bucket: bytes) -> int:
        """Caller holds BEGIN IMMEDIATE; both fixed one-hour quotas change together."""
        now = int(time.time())
        counters = []
        retry = 0
        for bucket, limit in ((token_bucket, 10), (ip_bucket, 50)):
            row = connection.execute("SELECT count,window_started_at FROM rate_limits WHERE bucket=?", (bucket,)).fetchone()
            count, started = (int(row["count"]), int(row["window_started_at"])) if row else (0, now)
            if started + 3600 <= now:
                count, started = 0, now
            if count >= limit:
                retry = max(retry, started + 3600 - now)
            counters.append((bucket, count + 1, started))
        if retry:
            return retry
        connection.executemany("""INSERT INTO rate_limits(bucket,count,window_started_at,blocked_until)
            VALUES(?,?,?,0) ON CONFLICT(bucket) DO UPDATE SET count=excluded.count,
            window_started_at=excluded.window_started_at,blocked_until=0""", counters)
        return 0
