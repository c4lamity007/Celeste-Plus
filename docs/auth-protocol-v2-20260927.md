# Celeste+ protocol 2 implementation and rollout

## Status

This is the implementation record for the next coordinated Celeste+ release.
The current release and client verification scope is Windows x64, per the user's
request. Candidate and service-test CI now use Windows; Linux and macOS client
work is deferred. The existing Fly backend remains part of the matched release.
It is **not a production deployment record**. The live release remains
`celeste-plus-20260927-double-anchor-01`, Fly version 131, until a separate
coordinated rollout is completed. The old published loader cannot use protocol 2
artifact delivery. Distribute a matching new protected loader with the new A/B
and runtime files, then fully restart Minecraft.

The shared service still supports Standard Celeste. New Plus session conflicts,
hardware migration and encrypted delivery are product-scoped; a Both license
must retain its Standard access. Existing paid license activation and expiry
are preserved during rebind and conflict recovery.

The supplied 648k audit is the remediation checklist. Its description of a Rust
injected runtime is not this repository's architecture: Rust launches a Java
resident host and disposable Java payload. The implementation follows those
actual boundaries. No client protection can make code impossible to inspect in
an authorized process or turn client-supplied hardware facts into attestation.

## Deliverables

These estimates describe the engineering effort for implementing and verifying
each area from scratch; they are not measurements of this chat's elapsed time.

| Deliverable | Implementation | Estimate |
| --- | --- | --- |
| 1. Server authorization | `auth-service/app/grant_routes.py`, `auth_lifecycle.py`, `token_revocation.py`, `artifact_envelope.py`, `transport_policy.py`, and the existing shared route guards. Live license/device/product checks, grant state, replay protection, signed hardware/process sessions, encrypted downloads, typed errors and request IDs. | 2–3 days |
| 2. Hardware identity | `beta-lab/native-shared/src/hardware_v2.rs` and runtime `HardwareIdentityV2.java`. Matching Windows CPU/system-disk/MachineGuid normalization and hashing; original hardware binding remains required for migration. | 0.5–1 day |
| 3. Launch authorization | Native `auth.rs`, `delivery.rs`, `bootstrap_cache.rs`, plus Java `HttpsTransport`, `BetaDeliveryClient`, `PinnedTls` and runtime `AuthServiceClient`. Fresh online verification before launch, short grants, pre-request TLS pins, authenticated artifact decryption and private cache custody. | 1–2 days |
| 4. Runtime authorization | Runtime `AuthBootstrap`/`AuthServiceClient`, canonical `ResourceReloadBridge`, resident lifecycle and `SupportFileCustodian`. Signed JVM/hardware identity, 30-second refresh, 90-second lease and independent monotonic expiry guard. | 1–2 days |
| 5. Native protection | Existing self-hash/native manifest, `debug_guard.rs`, environment policy, pinned Rust toolchain, warning-free build, metadata gate and selected VMProtect boundaries. New bytes still need their own protection review. | 1–2 days plus protection review |
| 6. Release automation | `.github/workflows/release.yml`, `run_vmprotect_mcp.ps1`, signing wrapper and `publish_native_github.py`. Private draft/assets, exact signed policy registration/readback, then publication. Requires provisioned runner, reviewed tool bundle and Windows signing identity. | 1–2 days plus identity provisioning |
| 7. Activation/rebind | Proof-based `/v1/activate` aliases, atomic device replacement, seven-day cooldown or thirty-day inactivity, explicit target selection and admin override. Additive migration under `auth-service/migrations/`. | 1 day |
| 8. Grant rotation | `/v1/rotate`, transactionally unique new JTI, old grant marked rotated; native rotates near expiry and falls back to fresh dual-proof authentication after bounded failures. | 0.5–1 day |

The exact wire format, endpoint requirements, error codes and cryptographic
transcripts are documented in [PROTOCOL-V2.md](../auth-service/PROTOCOL-V2.md).
The checked-in files contain the implementation; this record does not duplicate
source or introduce a second protocol definition.

## Decisions that preserve existing guarantees

- **Manual sales:** existing admin refund/revocation controls are authoritative.
  No Stripe account or webhook is required now. A future Stripe integration must
  verify webhook signatures, deduplicate event IDs, map customers to license
  records and invoke the same transactional revocation path. Never let an
  unverified client purchase status update the license table.
- **Proof-bound requests:** retain `Authorization: DPoP` and both installation
  proofs. A hardware header or bearer token alone cannot authorize a request.
- **Short authorization:** preserve 600-second grants and 90-second runtime
  leases. Ten-minute heartbeats and day-long grants from the sample would extend
  the current revocation window. Java validates its startup grant, then renews
  runtime leases; it does not keep rotating an unused download grant.
- **Hardware conflicts:** fresh, correctly signed conflicting hardware locks
  Plus use and revokes Plus grants. Replays and invalid signatures cannot lock a
  customer out. Same-hardware multiple game JVMs remain supported. Correct fresh
  authentication clears a conflict without resurrecting old grants.
- **Durable keys:** remote errors invalidate transient authorization. They do
  not delete installation keys or certificates: `token_revoked` can describe one
  download grant, and migration errors do not prove the keys are invalid.
  Explicit user reset and server-authorized device rebind remain separate.
- **Artifact encryption:** per-request X25519/HKDF/AES-256-GCM binds the request,
  recipient key, nonce and installer grant. The server's master key is never
  distributed to clients. Plaintext size/hash are checked against the signed
  catalog after authenticated decryption.
- **Storage and shutdown:** durable Bootstrap A cache is encrypted with an
  account-protected key. Required JVM support files use private directories and
  authenticated ownership receipts. Cleanup verifies exact files and inactive
  process identities; it does not recursively delete unknown historical files.
  Resident A and loaded JNI libraries can remain until JVM exit. A hard crash
  can leave owned files for the next safe cleanup; no secure-erasure claim is made.
- **TLS rotation:** the embedded Ed25519 key verifies a fresh public leaf-pin
  policy. Normal PKIX and a listed leaf digest are both enforced before sending
  credentials. This supports reviewed certificate overlap without trusting an
  observed network certificate as policy. There is no authenticated unpinned
  fallback.
- **VM policy:** VMs and sandboxes are blocked, as explicitly requested. This
  supersedes the audit's suggestion to report VMs only. No VMProtect upgrade is
  required for the existing licensed Lite GUI and the MCP integration.

## Verification evidence

Local generated evidence is ignored by Git. These checks do not claim a live
authentication, game injection or completed publication:

| Gate | Result / evidence |
| --- | --- |
| Shared backend | 429 passed, one Windows symlink privilege skip; 195 focused checks after the final cloud-session conflict correction. `build/module-qa/backend-protocol-v2-final*.xml` |
| Cross-language fixtures | Reproducible backend vectors; Rust and Java consume the signed TLS policy and AEAD/schema vectors. Actual local Windows hardware digests matched without printing hardware values. |
| Native | Candidate 04: 114 passed, six opt-in checks skipped, pinned Rust `-D warnings` passed. Real local environment/debug checks and TLS loopback pin/hostname rejection before HTTP bytes. The Windows thread-context buffer has the required 16-byte alignment and the timing sampler stays outside virtualization. `build/native-hardening-qa/native-checks-04.json` |
| VMProtect MCP | 107 passed, two Windows permission skips. Compile 06 protected candidate 04, recorded the exact **No** response and two stable output reads, then closed cleanly. No output was executed by the bridge. Separate controlled startup passed. `build/protection-20260927-v2/mcp-compile-06/compile-evidence.json` |
| Native protection review | All 14 selected functions passed exact-artifact verification with independent review. The verifier, protection gate, GUI importer and MAP-profile suite passed 81 checks. The approved import is bound to the source inventory, saved project, loaded-code evidence and final binary. [Release record](releases/20260927-auth-v2.md) |
| Java | Host `check assemble`; delivery 1137, TLS 32, runtime protocol 933, hardware 17, support custody 12. Lifecycle 33 deterministic scenarios plus the actual 30-second heartbeat timer. `build/module-qa/java-auth-v2-final/` |
| Full runtime | Reconstructed from the pinned core plus 192 verified overlay files; root `:build` passed. The helper selects only the root runtime build and rejects CRLF overlay text before Git normalization can invalidate its hashes. `build/module-qa/runtime-auth-v2-build-02.log` |
| Fresh protected Java | All 18 stages passed for `celeste-plus-20260927-auth-v2-01`: 792 actual payload classes verified, 260 stable entries retained, 851 actual protected-bootstrap checks in each of three JVMs. Windows account lookup and canonical process-session linkage were exercised. `build/module-qa/java-protected-auth-v2-20260927-02/` |
| Publisher | 15 tests exercise real Ed25519 registration preparation/readback, failure before mutation, nonce/signer rejection, private release ordering and retry identity. OS signing/protection gates and network are fixture boundaries. |
| Packaging tools | 165 passed, one optional skip. Includes explicit 14-function profile validation, importer coverage checks and refusal to apply historical protection evidence to current source. `build/module-qa/packaging-auth-v2-final-02.log` |
| Staged backend/catalog | 144 passed, one Windows symlink privilege skip. Uses the actual four protected artifact bodies with AEAD, preserves Standard/Both scope, and migrates a prior-schema fixture twice without losing existing rows. `build/fly-rollout-20260927/auth-v2-rollout-verification.json` |

GitHub Actions run `36346071298` for source revision `332e666` did not start any
jobs because of an account billing or spending-limit block. It supplies no CI
test evidence; the results above are local checks. Paid account settings were
not changed.

The fresh Java receipt is SHA-256
`0ad0e352b383e5c4545796f6940933cf5877a12f8dac1cecede77c50f0bb3665`.
Candidate 02's cold verification-fixture medians were 0.369 seconds before
protection and 0.433 seconds after protection. These include JVM startup and
verification; they do not measure Minecraft startup or FPS. This candidate
regenerated evidence after line-ending normalization; its runtime input JAR was
byte-identical to candidate 01.

A failed protection run is retained as failure evidence. An MCP compile result
called `compiled-pending-independent-review` is not a protection receipt.
Historical seven-function coverage cannot approve newly built native source.

Startup testing caught a native release-build defect before deployment: the
dependency's Windows `CONTEXT` type had eight-byte alignment, but x64
`GetThreadContext` requires sixteen. The optimized build passed a misaligned
buffer, received error 998 and correctly denied startup. An explicitly aligned
wrapper fixes the API call; the denial policy is unchanged. The optimized
diagnostic reproduced the failure before the fix and passed afterward. Rejected
candidate 03 and its protected output remain diagnostic evidence only.

## Provisioning and costs

The current manual-sales path, SQLite backend, installed VMProtect Lite license
and MCP bridge need no new payment provider or VMProtect license upgrade.
Existing Fly/GitHub usage and infrastructure charges continue under their own
accounts. No purchase is made by this implementation.

A trusted Windows publisher identity is optional for the accepted manual
Unknown-publisher release path, but required by the new **signed** release
workflow. Microsoft lists Artifact Signing at about **US$9.99/month**, subject
to supported region and identity eligibility. Hardware/cloud-backed commercial
certificates are another option; EV is not mandatory. Confirm current pricing
and eligibility before buying. See [Microsoft's signing options](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options)
and [identity provisioning](https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart).

`Store` signing uses an already provisioned Windows CSP/KSP identity; `Pfx` is
for a legitimately exportable identity. Installing a certificate by itself does
not configure a cloud signing provider. The workflow refuses missing identities
and does not silently reuse the historical unsigned exception.

VMProtect Lite requires an unlocked interactive Windows desktop. Provision a
dedicated `celeste-release` self-hosted runner that never runs pull-request code.
The existing platform workflow continues to produce unsigned test candidates.
The protected workflow cannot run until its pinned private adapter/protection
bundle is reviewed and installed. Signing and publication credentials are only
introduced after the unprivileged build/protection stage completes.

## Leaf certificate provisioning

From this repository, use the explicit helper:

```powershell
python tools/packaging/tls_leaf_policy.py observe --output build/tls-review.json
```

Inspect the recorded origin, SNI/PKIX result, certificate identity, expiry and all
observed edge certificates. The output is a candidate observation only, not an
approved pin list. DNS observations may not cover every Fly edge region. Confirm
planned/current certificates with the endpoint operator and use the helper's
`export` or `stage-fly` command with the reviewed file's SHA-256. `stage-fly`
stages `CELESTE_TLS_LEAF_SHA256` for the exact app; it does not deploy it.

Before changing an edge certificate, provision both old and replacement leaf
digests (up to four) and verify regional coverage. Keep overlap until all edges
use the replacement, then explicitly retire the old pin. An unlisted edge fails
closed. Never auto-learn pins from a failing authenticated request.

## Coordinated rollout

1. Confirm the exact `Celeste-Plus.git` remote, reviewed source revision and
   protocol-2 artifacts. Run all applicable server/client/protection gates.
   The release workflow accepts an existing `v` tag on main using letters,
   digits, hyphens and underscores, matching backend release-ID restrictions.
2. Preserve the exact live Fly image digest, app settings, current signed native
   policy and both product release directories. Back up SQLite with its online
   backup mechanism or a stopped-service snapshot; copying only a live WAL
   database file is insufficient. Keep backups private and verify readability.
3. Plan a short coordinated maintenance window: switching the service and
   distributing a new loader are separate operations, not one atomic action.
   Stage the reviewed leaf policy. Build the deployment from the exact live
   image while retaining Standard's release and all approved artifacts; deploying
   a bare `auth-service` directory would omit staged payloads.
4. Provision protocol-2 backend source, additive schema and matching Plus
   A/B/payload files together. Set `CELESTE_PLUS_AUTH_PROTOCOL=2`. Keep the old
   native hash while adding the reviewed replacement to the signed allowlist.
   Keep Standard's configured source/release unchanged.
5. Stage a private GitHub draft with the exact final loader and checksums. The
   publisher verifies protection/signature/source, uploads assets, signs the
   new monotonic manifest policy, confirms it via fresh nonce-bound readback,
   and only then publishes the draft. Preserve the used publication envelope
   and retention policy for an idempotent retry and next release.
6. Verify public readiness/transport policy and negative authorization, then
   authenticate with an authorized test license and inject the new loader into
   a freshly restarted game. Check stop/reload, lease expiry, manual revocation
   and Standard/Both entitlement behavior. Do not infer these results from unit
   fixtures or an existing running resident host.
7. Record the actual image, schema, signed policy sequence, client hashes and
   observed results. Announce a release only after that verification. Configure
   the optional notification webhook separately when it is wanted.

## Rollback and customer recovery

1. Pause publication and preserve request IDs, the failed release's artifacts,
   manifest authorization and database backup. Never log license keys, DPoP
   tokens, private keys or raw hardware identifiers.
2. Roll back the **matched** backend image, Plus Java artifacts and public loader
   instructions together. Protocol 1 is an explicit operator rollback option;
   protocol-2 clients must not automatically downgrade on failed authorization.
3. Leave additive tables/columns in place. Do not restore an old database over
   newer revocations, license updates or customer activity. If a database repair
   is necessary, stop writes and reconcile those changes before restoring.
4. Preserve the latest native publication sequence. If an allowlist change is
   needed, issue a new higher sequence containing the exact reviewed old loader
   hash; do not replay an older signed policy or weaken the protection gate.
5. Keep current valid TLS pins even when rolling back code. Returning to an old
   certificate list after an edge rotation can lock out legitimate clients.
6. Ask affected users to fully restart Minecraft and use the matching loader.
   Do not tell them to erase installation keys for an expired/rotated token.
   A fresh dual-proof authentication recovers temporary authorization. Genuine
   hardware replacement follows the rebind cooldown or explicit admin reset.
7. Verify Plus and Standard independently, then resume publication. Record what
   was restored and which runtime behavior was actually observed.
