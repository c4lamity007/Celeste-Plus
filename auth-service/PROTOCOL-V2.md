# Plus authentication protocol 2

This is a coordinated client/server change. Set `CELESTE_PLUS_AUTH_PROTOCOL=2`
(the default) only with matching native and Java clients. Protocol 2 grants carry
`auth_protocol: 2`; old Plus clients cannot use the new protected delivery routes.
Standard keeps its existing challenge, DPoP and delivery protocol. Explicit
protocol 1 configuration exists for transition tests, not as a client fallback.

## Installation and hardware identity

Keep the original hardware JSON and canonical core hash: SHA-256 of compact,
sorted UTF-8 JSON containing `board`, `processor`, and `system`. Enrollment and
authentication start accept an optional top-level `hwid_sha256`, a lowercase
64-character digest. Windows clients use SHA-256 of their normalized CPU serial,
system-disk serial and MachineGuid joined by colons. Non-Windows clients omit it.

If the digest is present, the signed challenge transcript contains it and uses
`protocol: "cst-auth-2"`; otherwise the original `cst-auth-1` transcript remains.
Enrollment does not bind a new HWID by itself. Binding requires a successful
authentication with matching original hardware, both installation/token key
signatures, and fresh DPoP. An existing HWID cannot be overwritten by migration.

`POST /v1/activate` and `/v1/activate/complete` alias the proof-based enrollment
flow. A paid license key and a new installation-key proof are still required.
If the device limit is reached, a previous installation is eligible for rebind
after more than seven days, or thirty days without trusted activity. With more
than one active installation the caller must select `replace_device_id`.
Rebinding revokes the selected device and grants atomically; it preserves paid
license activation and expiry. Admin reset remains available for manual support.

## Runtime sessions

Plus protocol 2 session requests additionally contain `hardware_sha256`, optional
`hwid_sha256`, positive integer `process_id`, and canonical base64url `session_id`
(24 bytes, no padding). The session ID stays stable for the same Minecraft JVM
across payload reloads. Append this exact UTF-8 suffix to the existing signed
session text, immediately after its catalog digest:

```text
\nceleste-session-v2\n{hardware_sha256}\n{hwid_sha256_or_empty}\n{process_id}\n{session_id}
```

Both key signatures and DPoP remain mandatory. The signed session response echoes
these fields. `/v1/heartbeat` aliases `/v1/session/check`.

Same-HWID multiple JVMs are permitted. Fresh signed different hardware while a
trusted runtime session was seen in the previous hour returns HTTP 409
`concurrent_session_rejected`, revokes associated active grants, and locks runtime
use. It does not ban the license. A new matching dual-proof authentication clears
the lock; it does not restore old grants. Plus challenges created before the conflict
are invalidated. A shared Both-license device retains its Standard grant access;
Standard authentication does not clear the Plus conflict. Invalid proofs, replayed requests and untrusted extra fields
cannot trigger this lock. With no recent trusted session, wrong hardware returns
`device_mismatch` instead.

## Grant validation and rotation

`POST /v1/validate` and `/v1/rotate` accept JSON `{}`, `Authorization: DPoP <grant>`,
and `X-Device-Id` containing the bound HWID, or the core hash where no HWID is bound.
The DPoP JWT must sign the same value in its `hwid` claim.

Successful validation returns `status: "valid"`, `expires_at` (UTC ISO 8601) and
`expires_epoch`. Rotation also returns `grant`. Rotation preserves the short
configured grant lifetime (normally 600 seconds), audience, subject, installation
key binding and release identity. It creates a new JTI and marks the old grant
`rotated`. Two concurrent rotations cannot both succeed. A consumed installer
ticket cannot rotate. Existing 90-second runtime leases are unchanged.

`active_tokens` stores token hashes and state, never bearer tokens. Rotation audit
records old/new hashes. All resource authorization still checks current device,
license, product entitlement and token status. Grant issue/rotation and runtime
checks recheck state inside a database write transaction.

Typed errors include `device_mismatch`, `subscription_inactive`, `token_revoked`,
`token_rotated`, `concurrent_session_rejected`, `reactivation_required`,
`rebind_cooldown`, `invalid_grant`, `invalid_delivery_key` and `runtime_disabled`.
Clients must not delete credentials based only on an HTTP status or network error.

## Encrypted Plus artifacts

Each artifact GET generates a new X25519 recipient key and 24-byte random nonce.
Send canonical unpadded base64url headers `X-Celeste-Delivery-Key` (32-byte public
key) and `X-Celeste-Request-Nonce`. Its DPoP JWT signs the exact values as `cek`
and `cnonce`. The existing installer grant and manifest headers remain required.

The response is `CSTAE1` (six ASCII bytes), server ephemeral public key (32 bytes),
AES-GCM nonce (12 bytes), and ciphertext/tag. Overhead is 66 bytes. Derive a
32-byte AES key using X25519 and HKDF-SHA256. Salt is SHA-256 of the ASCII installer
grant. HKDF info and GCM AAD are the same UTF-8 bytes:

```text
celeste-artifact-v1\n{request.path}\n{request_nonce}\n{client_public_key_header}
```

Catalog sizes/hashes describe authenticated plaintext. Verify both after decrypting.
The server rechecks authorization after encryption. No protocol 2 plaintext
fallback exists. A captured JWT alone does not provide the recipient private key
or a new valid DPoP request. This does not prevent an authorized running process
from inspecting its own decrypted code in memory.

## TLS leaf policy

`GET /v1/transport-policy?product=celeste_plus` requires a fresh canonical 24-byte
`X-Celeste-Request-Nonce`. It returns a server-signed CST `policy` with audience
`celeste-transport-policy`, exact product/origin/nonce, `iat`, `nbf=iat`,
`exp=iat+120`, and `leaf_sha256` (one to four pins).

Configure `CELESTE_TLS_LEAF_SHA256` as unique comma-separated lowercase SHA-256
leaf-certificate digests. Missing pins return 503. The endpoint never learns pins
from the network. Clients use ordinary PKIX for this credential-free request,
verify the embedded-key signature and freshness, then require PKIX plus an exact
leaf pin before sending authentication data. Operators must review/provision
current and replacement certificates before an edge rotation; an unpinned edge
fails closed.

## Database upgrade, fixtures and limits

Application startup applies idempotent table creation plus `_migrate` additions
for device/challenge HWID and selected rebind target. The SQL migration records
the new audit tables; do not run that file alone as a substitute for application
startup migration. Back up the database before coordinated rollout.

Run from `auth-service`: `python -m pytest tests -q`. Public test vectors are
generated by `python tests/generate_protocol_v2_fixtures.py`; `--check` verifies
exact reproducibility. They contain only explicitly synthetic test keys and are
consumed by Java/Rust tests. These tests do not establish live game behavior,
production certificate coverage or a completed production rollout.

Manual/admin license revocation is the current sales integration. Stripe is a
future provider integration and is not installed or required by this change.
