# License gateway

This service implements the `cst-auth-1` challenge-response protocol. An installation is authorized only when it proves:

1. the bound hardware and monitor fingerprint;
2. possession of its locally generated Ed25519 installation key; and
3. possession of the separate server-issued Ed25519 token seed, delivered once through an X25519/AES-GCM envelope and stored with Windows DPAPI.

All challenges are short-lived and one-time. Successful authentication returns a ten-minute Ed25519-signed grant. Protected release chunks require that grant and re-check current license state before delivery. While Celeste is active, a dual-key signed, nonce-protected heartbeat runs every 30 seconds. Revoked and expired licenses are stopped on the next heartbeat; an unavailable server receives a short fail-closed grace period.

The client recomputes its canonical protected-code digest during heartbeats. A signed mismatch report creates a critical security event and atomically revokes the entire license instead of deleting local files. This is a fail-closed integrity signal, not a claim that telemetry can survive an attacker who has fully patched out the reporting code. Raw hardware, SMBIOS, and EDID data never leave the client; the dashboard displays their bound hashes and public-key fingerprints.

The admin site uses Argon2 password verification, failure-only persistent throttling, one active server-side session bound to the login IP and browser, prepared SQL, CSRF tokens, strict cookies, Trusted Host filtering, request body limits, security headers, and audit events. It supports day, month, three-month, year, and lifetime licenses whose duration begins on first successful activation. Per-license pages expose activation and latest-call metadata, hardware and monitor hashes, key fingerprints, tamper flags, and audit history. Resetting a license deletes its device keys and challenges while preserving the license key and numeric UID.

## Release order

Authentication metadata must be applied **after obfuscation**:

1. Build and obfuscate the client payload.
2. Obfuscate the second-stage loader while preserving `StageTwoLoader.load(Object)`.
3. Obfuscate the bootstrap loader while preserving its Fabric and bridge contracts.
4. Run `tools/package_final_release.py` with all three post-obfuscation artifacts.
5. Apply the non-secret values from `releases/release.env` as Fly secrets alongside the existing private secrets.
6. Deploy this directory.

The native EXE uses the stable channel declared in
`../native-verifier/launcher-channel.json`. Both release packagers seed the
server allowlist from that checked-in file, even if `releases/` was cleaned.
Ordinary payload, bootstrap, or loader updates therefore keep existing
launchers working. Only an intentional launcher protocol, server hostname, or
pinned signing-key migration requires a new launcher build.

The packager computes the same canonical full-payload digest as the Java bootstrap, writes the pinned endpoint/key metadata without changing that digest, splits the configured JAR into the two server-hosted chunks, and emits `release.env` from the exact packaged hashes.
