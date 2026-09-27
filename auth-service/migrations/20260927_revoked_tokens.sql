-- Additive and idempotent: preserves license, device, entitlement and native policy rows.
CREATE TABLE IF NOT EXISTS revoked_tokens (
    jti TEXT PRIMARY KEY,
    token_sha256 TEXT NOT NULL,
    revoked_at INTEGER NOT NULL,
    expires_at INTEGER NOT NULL,
    reason TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_revoked_tokens_expiry ON revoked_tokens(expires_at);
