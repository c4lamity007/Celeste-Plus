# License gateway

This service implements the `cst-auth-1` challenge-response protocol. An installation is authorized only when it proves:

1. the bound hardware and monitor fingerprint;
2. possession of its locally generated Ed25519 installation key; and
3. possession of the separate server-issued Ed25519 token seed, delivered once through an X25519/AES-GCM envelope and stored with Windows DPAPI.

All challenges are short-lived and one-time. Successful authentication returns a ten-minute Ed25519-signed grant. Protected release chunks require that grant and re-check current license state before delivery. While Celeste is active, a dual-key signed, nonce-protected heartbeat runs every 30 seconds. Revoked and expired licenses are stopped on the next heartbeat; an unavailable server receives a short fail-closed grace period.

The client recomputes its canonical protected-code digest during heartbeats. Standard's legacy signed mismatch path records a critical security event and revokes the shared license. Plus mismatch reports deny further access without taking that legacy revocation action. Plus instead supports the optional [security incident protocol](SECURITY_INCIDENTS.md): an explicitly eligible installation and release receive a warning, followed by a fresh matching observation before the configured product block or license revocation. This policy is disabled by default. These integrity signals cannot guarantee telemetry from a client whose reporting code has been patched out. Raw hardware, SMBIOS, and EDID data never leave the client; the dashboard displays their bound hashes and public-key fingerprints.

The admin site uses Argon2 password verification with persistent attempt admission and one expensive verifier per worker, one active server-side session bound to the login IP and browser, prepared SQL, CSRF tokens, strict cookies, Trusted Host filtering, request body limits, security headers, and audit events. It supports day, month, three-month, year, and lifetime licenses whose duration begins on first successful activation. Per-license pages expose activation and latest-call metadata, hardware and monitor hashes, key fingerprints, tamper flags, and audit history. Resetting a license deletes its device keys and challenges while preserving the license key and numeric UID.

Request-body reads have an absolute 15-second deadline and nonblocking per-worker admission: 32 ordinary bodies (16 KiB each) and a separate four configuration uploads (1,500,000 bytes each). Busy readers return `503` with `Retry-After: 1`; timed-out reads return `408`. Permits are released before route processing or artifact streaming. These limits apply to both products and do not replace authorization or rate limits.

Successful challenge/grant issuance and signed session processing prune at most 128 expired rows per operation using expiry indexes. Replay markers remain through their final accepted second; rotated and revoked grant records remain until their signed expiry. Cleanup runs inside the caller's transaction and does not remove live credentials.

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

## Plus artifact hardware binding

Every `/v1/beta/artifacts/...` GET requires one lowercase 64-character
`X-Device-Id` hardware digest, repeated as `hwid` inside the same installation
DPoP proof that signs the delivery key and nonce. The backend compares it with
the live device's enrolled v2 HWID; devices without v2 enrollment use their
existing canonical core hash. Fresh dual-proof authentication can enroll v2,
after which the core hash is no longer accepted. This applies to legacy Plus
grants too. Standard artifact routes are unchanged.

Deploy the updated native launcher, Bootstrap A and this backend together.
Older Plus clients do not send this mandatory binding and will be refused;
there is no compatibility switch to disable it. Manifest/JWT and encrypted
artifact formats remain unchanged. Rollback must restore a matching complete
client/backend release, rather than mixing clients from this transition.
