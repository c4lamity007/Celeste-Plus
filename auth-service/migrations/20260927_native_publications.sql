-- Additive. Existing licenses, devices, credentials and entitlements are preserved.
CREATE TABLE IF NOT EXISTS native_release_policies (
    product TEXT NOT NULL CHECK(product IN ('celeste','celeste_plus')),
    platform TEXT NOT NULL,
    sequence INTEGER NOT NULL,
    publication_sha256 TEXT NOT NULL,
    binaries_json TEXT NOT NULL,
    published_at INTEGER NOT NULL,
    PRIMARY KEY(product,platform)
);
