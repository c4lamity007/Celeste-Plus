# Celeste authentication hardening

This is the original integrated implementation record. The Celeste+ portions,
shared server source, and Plus publication helpers are now carried in the
separate Celeste-Plus repository. Historical Standard-launcher paths and dual
product CI results below describe the original workspace. Use
[the Plus transfer record](plus-source-transfer-20260927.md) for current paths,
verification, and deployment status.

Threat model and remediation brief supplied by **648k**. This implementation
adapts that brief to the actual Rust launchers, Java runtime, and Python
FastAPI/SQLite service. This document is an implementation and rollout record,
not a claim that an attacker-controlled client can be made unpatchable.

## Release status

This source revision requires a coordinated client/server release. It has
not been deployed. Production native publication requires independently checked
protection evidence and a signing setup. VMProtect Lite 3.10.4 is available at
`C:/Users/c4lam/OneDrive/Desktop/VMP/VMProtect Lite 3.10.4/VMProtect.exe` and is
being integrated in the coordinated protection chat. Its GUI executable alone
does not supply an approved protection project, independent verification or
code-signing credential. Lite has no console tool for a hosted CI job.
The coordinated chat installed a GUI control bridge and verified its MCP
connection. It also completed a GUI compilation of a copied vendor sample with
three procedures; the output was not executed. That sample does not verify
Celeste's protection coverage, runtime behavior or an unattended CI adapter.
Neither the bridge nor sample evidence populates the protector approval policy.

The real API origin is `https://qb0f90cf11f4d1d52.fly.dev`. Standard and Plus
production packagers now reject a copied example domain or a different origin.
Offline fixtures retain their reserved test domains and never contact this API.

## Architecture facts that affect the remediation

- `.celeste-storage-check-*.probe` is a randomly named, create-new storage
  permissions probe. Its presence is not the authorization decision.
- `device` is a server installation identifier. It references the stored
  installation signing key and hardware binding; it is not a raw HWID hash.
  Comparing it directly with an unauthenticated hardware header would be wrong.
- Windows installation credentials already use DPAPI. An access grant alone
  cannot authorize a download: the server requires a fresh proof signed by the
  installation key, bound to that exact grant and HTTP request (DPoP).
- Artifact routes already query current device, license and product entitlement
  state. Authentication checks the hardware core and monitor binding.
- Runtime checks already ran every 30 seconds. The new activation check requires
  an additional fresh nonce-bound server session, and gameplay now enforces its
  expiration independently of the heartbeat executor.
- Plus Bootstrap A and shared host support JARs have on-disk requirements.
  Bootstrap B and the premium payload parts load from retained memory. This
  implementation does not claim that every executable byte remains diskless.

## Deliverable 1 — server authorization

The existing `/v1/installer/start` and `/v1/installer/complete` flow, `/v1/beta/manifest`,
individual artifact routes, and `/v1/session/check` remain the authority.
Fresh catalog requests include a 24-byte random request nonce, echoed inside
the Ed25519-signed manifest. Captured successful responses cannot satisfy a new
request. Live device, license, entitlement and DPoP checks remain mandatory.
Pre-authentication attempts no longer overwrite trusted device activity.

The implementation retains installation proof binding rather than adding a
Bearer-only `/v1/validate` path. A raw HWID supplied by a requester is not proof
of that requester's machine. A captured token does not supply its signing key.
The database is SQLite, with shared atomic transactions; Redis is not configured.

Every response receives a server-generated request ID. API errors retain the
existing `detail` field and add a code, message, retry delay and matching request
ID. Atomic SQLite transactions limit artifact delivery to ten requests per
token and fifty per IP each hour, across normal and Plus resource routes.
Operator access-grant revocation is checked before delivery and again after expensive
Plus artifact verification. Denied heartbeat replays, inactive accounts and
integrity failures do not overwrite successful device activity. No refund webhook is invented:
billing must actually revoke the license or entitlement for server checks to
observe a refund.
Revoking one access grant does not revoke its license, installation keys or an
already issued 90-second runtime lease. Those have separate server controls.

The brief's `HKDF(jti || server_master_key)` recipe cannot be decrypted by a
client that does not know the master key. No server master key is shipped to the
client. TLS provides transport encryption; this change does not introduce that
additional artifact encryption envelope or claim complete plaintext avoidance.

## Deliverable 2 — hardware binding

The existing shared Rust and Java fingerprint contract is preserved. Switching
only Rust to a CPU/disk/MachineGuid concatenation would make existing activations
and the Java runtime disagree. The server's enrolled hardware binding, both
installation signatures, and product entitlement remain required.

New native and Java environment checks reject detected VM guests and sandboxes
before enrollment/authentication or injection. Required probe failures deny
startup. Physical Windows hosts using VBS are not classified as guests solely
because the hypervisor-present bit is set. Unknown or deliberately concealed
virtualization remains outside the verified detection coverage.

## Deliverable 3 — pre-launch authorization

Cached credentials supply signing material, not permission to run. The native
launcher fetches a fresh signed catalog before using even an unchanged cached
Bootstrap A. Java Bootstrap A repeats server validation before loading B and
before payload invocation, retaining the same release/catalog throughout.
Offline, replayed, stale and rejected responses stop these boundaries.
The normal Java gateway and each following loader also require a fresh server
session before invoking the next stage. Stage four validates both payload chunks
and rechecks authorization before adding either to the runtime classpath.

The new `GET /v1/client-manifest` also supplies an online, nonce-bound allowlist of
actual approved launcher hashes. `POST /v1/client-manifest` accepts only
a short-lived statement signed by a configured CI publication key. Empty or
unconfigured publication state never authorizes arbitrary executables.
The server validates that publication signature, sequence and metadata. The
protection-receipt digest is a CI assertion; the server does not run VMProtect
or independently inspect those protected bytes. The CI-side gate requires
evidence from the pinned protector and verifier for the exact final bytes.

## Deliverable 4 — runtime revalidation

The runtime is Java; there is no invented Rust `DllMain` heartbeat. Activation
uses the current credential to obtain a fresh `/v1/session/check` result. The
server-signed lease is capped at 90 seconds and converted to a monotonic local
deadline. Every gameplay authorization query checks that deadline, including
when the network worker is stopped or blocked. Late responses cannot reopen
an expired or stopped runtime. Rejection schedules normal module cleanup on the
game thread, preserving Minecraft.

The 30-second schedule is retained. Changing this to a ten-minute poll with
three missed polls would leave much longer offline access. VM detection does
not automatically revoke a license or terminate another process.

An operator can set `CELESTE_RUNTIME_KILL_SWITCH=true` and reload the service.
It returns HTTP 403 for runtime authorization, heartbeats, manifests and artifact
delivery. The existing client rejection path unloads modules on its next check;
it does not kill Minecraft or alter licenses, bindings or pending challenges.
This is a server-enforced denial rather than a new client-trusted JSON flag.

## Deliverable 5 — binary protection

Native release packaging must prove the exact final binary, release, platform,
current source inputs, protector executable, configuration and independent
verification report. String encryption alone does not satisfy native
virtualization. The reviewed protector allowlist starts empty and therefore
blocks protected publication until the actual licensed tool and verifier are
configured.

The executable manifest is a tamper check in the unmodified launcher, not
server-side attestation of arbitrary client code. Debugger timing guesses and
silent process exits are not added as substitutes for authorization. TLS
certificate validation and the pinned Ed25519 application key remain in force;
leaf certificate hash pinning with an operational rotation policy is not yet
configured.

## Deliverable 6 — release pipeline

Production artifacts must be built, stripped, protected, signed, verified, then
hashed and registered in that order. Authenticode changes the file bytes, so
publication evidence and the server allowlist must describe the final signed
file. Missing required secrets, protector approval or verification must stop a
production job. Unsigned platform candidates remain test artifacts.

The Windows metadata audit found POGO debug records that LLVM stripping left
inside `.rdata`. The unsigned-only cleanup validates the PE bounds and overlaps,
clears the reviewed debug records and their payloads, and updates the checksum.
It refuses signed binaries. The audit still rejects remaining debug metadata;
it now distinguishes real user paths from adjacent Java installation strings.
Fresh copies of both candidates pass this audit; their original files remain
unchanged. This is metadata verification, not evidence of virtualization,
Authenticode signing or a successful Minecraft launch.

The CI registration key is distinct from the live authentication signing key.
Only its public half belongs in `CELESTE_NATIVE_RELEASE_PUBLIC_KEY` on the
server. GitHub release publication is restricted to a private repository and
reviewed release configuration. Defining a workflow does not mean it has run.
Building and protection run before signing credentials are made available.
The protector bundle is hashed and extracted from the same immutable bytes.
Staged registrations retain every currently approved binary after verifying a
fresh signed server policy against the reviewed prior publication. Missing
history or a full four-binary policy stops staging; old entries are never
silently removed to make room. Partial-run recovery is documented separately
in `tools/packaging/NATIVE-RELEASE.md`.

The existing edition-2024 Rust projects and locked dependencies are verified
with stable Rust 1.97.1 here. Rust 1.81 compatibility is not claimed, and the
Standard native test target still reports 16 unused-code/import warnings; a
warning-free build of every target is not represented as complete. The runtime
heartbeat is Java, so `panic=abort` in the native launcher does not provide or
conflict with a Rust heartbeat respawn mechanism.

## Deliverable 7 — issuance and migration

Existing challenge/response enrollment preserves licenses, device limits,
lifetimes, timed subscriptions and product entitlements. A fabricated
`subscriptions` table or automatic seven-day rebind rule would change existing
customer access policy, so these are not introduced as auth fixes.

Native release registration adds `native_release_policies`, and access-grant
revocation adds `revoked_tokens`, without changing license or device rows. The
SQL migrations are additive and safe to run twice.
New publications replace a product/platform's approved binary list, with at
most four entries, and require a strictly increasing sequence. Exact transport
retries are idempotent; replaying an older signed publication cannot restore a
withdrawn executable.

## Deliverable 8 — renewal

Existing access grants last ten minutes by default; runtime leases last 90
seconds and are refreshed every 30 seconds using installation proofs. They are
not replaced with a 24-hour bearer token or a refresh token stored in plaintext.
Renewal failure cannot extend the signed lease. This implementation uses those
existing scoped issuance paths rather than a parallel `/v1/rotate` protocol.

## Rollout and rollback

1. Retain the currently deployed server image, both release catalogs, current
   installer downloads and a consistent database backup. Record their hashes.
   Do not reset any license or delete any customer's existing activation.
2. Configure the separate CI publication public key, actual protection verifier
   and signing service. Never place private signing material in repository files,
   receipts, workflow logs or downloaded launcher packages.
3. Build and verify matching native launchers, both Plus bootstraps/payload parts
   and normal stages affected by shared auth changes. Old clients omitting the
   request nonce receive HTTP 400; updated clients require its matching signed
   response. Do not deploy the server alone.
4. Use an announced maintenance window or equivalent coordinated distribution.
   Register exact final executable hashes with a new signed publication sequence,
   stage the matching artifacts, and verify new and existing licensed activation,
   offline failure, revoked access, restart and module cleanup before release.
5. If a candidate fails, restore a nonempty list of previous approved binary
   hashes with a new, higher publication sequence, and restore the matching
   previous application/artifact set. Registration requires one to four entries.
   Keep current license/device/revocation data. Additive tables can remain.
   An older server will ignore the new access-token revocation table; retain the
   new resource gate or enforce critical withdrawals through the existing
   device/license status before rolling back that code.
   Never restore an old database merely to undo code changes: that can revive
   revoked licenses and lose valid customer changes.
6. Use one authoritative SQLite database for all serving workers. Independent
   copied Fly volumes do not share DPoP replay, revocation or rate-limit state.

## Limits and remaining inputs

A paid customer controlling their machine can inspect delivered code and patch
local checks. VMProtect, signing and self-hashing increase the work required;
they do not turn client assertions into hardware attestation. Full prevention
of offline replication would require moving indispensable functionality to the
server or another independently trusted execution boundary.

Pending production inputs: an approved native protection project and reviewed verifier,
code-signing credentials/service, CI publication key and secret configuration,
and a coordinated authenticated release test. Full in-game testing of this
candidate has not been performed. Exact certificate pinning, an additional
artifact encryption envelope, concurrent-HWID revocation and the brief's new
activation/rotation APIs are not represented as completed features.

## Engineering effort estimates

These are rough implementation-and-review effort estimates for the full
literal brief, not elapsed agent time or a promised delivery schedule. Shared
protocol and integration work overlaps between rows. External signing and
protection setup has its own lead time.

| Deliverable | Estimated engineering effort |
| --- | --- |
| 1. Server validation, limits and revocation | 1–2 days |
| 2. Hardware identity and migration validation | 4–8 hours |
| 3. Native and loader authorization boundaries | 1–2 days |
| 4. Runtime expiry and cleanup | 4–8 hours |
| 5. Protection, signing and executable verification | 2–4 days plus external setup |
| 6. Production build and release pipeline | 1–2 days plus CI configuration |
| 7. Issuance and additive migration | 4–8 hours |
| 8. Renewal and failure handling | 4–8 hours |

## Verification record

| Area | Result and scope |
| --- | --- |
| Combined FastAPI service | **398 passed, 1 skipped** in 90.45 seconds after the final kill-switch and error-header changes. The skip is a filesystem-link capability test. Dependency deprecation warnings remain. |
| Native Standard launcher | **99 passed, 3 ignored**. Ignored host/integration cases are not counted as verified. |
| Native Plus launcher | **93 passed, 4 ignored**, with the same limitation. |
| Actual Java auth protocol | **739 checks passed**, including signed-response nonce and validity enforcement. |
| Java HTTP response bounds | **4,307 assertions passed** against the actual subscribers with a fake HTTP client. These are assertions, not separate test cases or live network tests. |
| Runtime lifecycle | **30 scenarios passed**, including the real 30-second scheduler; **29 deterministic scenarios rerun** after session publication ordering was corrected. |
| Normal loader boundaries | **904 checks passed** for fresh authorization before subsequent stages. |
| Windows release metadata | **23 tests passed**; sanitized copies of both candidates pass audit with executable section bytes preserved and valid Windows PE checksums. |
| Native publication and toolchain bundle | **18 registration tests and 9 bundle tests passed**; latest native delivery fixture subset passed **12 tests**. |
| Workflow syntax | Release YAML, **15 inline PowerShell steps**, and the signing helper parsed successfully. This does not execute the workflow or validate a production signing/protection adapter. |
| Clean-checkout hook dependency | **2 tests passed** for compiling the tracked Java source into deterministic Java 8 hook JARs. Normal candidate CI, the standalone builder and the production workflow now generate the ignored JAR; no compiled hook is committed. Updated workflow/PowerShell syntax also passes. |

Evidence is in `build/module-qa/auth-hardening-20260927/` and
`build/module-qa/native-environment-20260927/`. Java main and normal loader stages
compile with the current source. These results do not cover a protected and
signed final executable, authenticated production rollout, all guest operating
systems, or an in-game run. Release-tool tests and adapter setup are recorded in
`tools/packaging/NATIVE-RELEASE.md`.
