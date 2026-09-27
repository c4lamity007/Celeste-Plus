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
