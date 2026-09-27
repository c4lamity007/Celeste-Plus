# Celeste+ authentication audit — current Windows release

This checks the user's narrowed list against the current release, rather than
inferring behavior from strings in older executables. The examined Windows
loader is `celeste-plus-20260927-auth-recovery-01`, SHA-256
`31ce0fd052af152e5fe340847eece882bceeb11dd65414a70ac0452ab2dfd743`.
The supplied dump examined older `a5371aac…` and `33c0207d…` binaries.

## Findings

| Claim | Current implementation and remaining limit |
| --- | --- |
| No Authenticode signature | **Still unsigned.** The downloaded release returns `NotSigned`; neither Windows personal certificate store contains a code-signing identity. Earlier publication explicitly accepted Unknown publisher. Server release signatures are separate from Windows publisher signatures. |
| No VM detection | Native environment checks inspect CPUID, firmware, Windows services/files, WMI and network-adapter indicators. The protected release also enables VMProtect's virtualization-tools check. A physical Windows host using Hyper-V/VBS is distinguished from a guest. These checks can be spoofed by someone controlling the machine. |
| No runtime heartbeat | Java runtime authorization refreshes every 30 seconds. An independent monotonic lease expires within 90 seconds; a one-second supervisor recovers terminated workers with bounded retries. Startup also requires a fresh signed session response. |
| No certificate pinning | Rust and Java fetch a nonce-bound Ed25519-signed public pin policy, then require normal certificate/hostname verification **and** an approved leaf hash before sending credentials. There is no authenticated unpinned fallback. |
| No binary self-integrity check | Before authorization/attach, the native launcher hashes its executable and requires membership in a fresh nonce-bound signed server manifest. Payload/support bytes are checked against signed digests. This detects altered bytes in the normal client path; it is not remote attestation of a hostile process. |
| Shared folder / storage marker bypass | `.celeste-storage-check-*` is a temporary storage-permission probe, not an authorization marker. Cached credentials are Windows account-protected; use still requires fresh server authentication, installation-key proofs and current hardware/license state. Cached payload bytes do not supply a fresh runtime lease. |
| Server trusts subscription/HWID only at installation | Artifact requests check current device/license/entitlement/token state and require a signed HWID header plus installation-key DPoP. Download bodies are encrypted to each request's recipient key. Session renewal rechecks current authorization. |
| Rate limits | Persistent download quotas already serialize updates. This audit reproduced and repaired two races in the separate general/admin request counters; details below. |
| Packing makes an installer unpatchable | **No such guarantee is possible.** VMProtect increases reverse-engineering effort. A user controlling a licensed process can inspect or patch it. Client self-reported hashes and hardware values are not trusted hardware attestation; server checks remain essential. |

## Confirmed defects repaired

- Two concurrent requests from independent server instances could both consume
  the final allowed request in an IP bucket. The shared request limiter now takes
  a SQLite write transaction before reading and updating the counter. Thresholds
  and retry behavior are unchanged.
- The admin login precheck could read an expired counter, then delete a new
  counter written by another connection. Cleanup now deletes only the exact
  expired window it observed.

Both regression tests failed against the preceding source and passed after the
repair. The new server tests also exercise copied tokens/manifests, missing or
foreign installation proofs, persistent replay rejection for all four artifacts,
and subscription removal during both slow artifact reads and encryption. These
use disposable test identities and databases, not customer accounts.

All 12 new focused checks passed. The complete backend suite passed **453 tests**
with one existing Windows symlink-permission skip. Independent review found no
blocker in the two production changes. Evidence is under
`build/module-qa/server-findings-20260927/`.

Only `auth-service/app/main.py` and `auth-service/app/database.py` change production
behavior. No client protocol, database schema, native loader or Java payload
change is required.

## Trusted Windows signing

The existing signing pipeline supports a provisioned Windows certificate-store
identity or a PFX identity. It signs after VMProtect, requires SHA-256 and an
RFC 3161 timestamp, verifies the expected signer, then verifies protection and
registers the hash of the **final signed bytes**. An unsigned release receipt
cannot be reused for newly signed bytes.

Trusted signing requires a real identity and a certificate/service. No purchase
or identity enrollment has been made. Microsoft Artifact Signing currently
starts at **US$9.99/month**; eligibility depends on the signer's country and
whether they are an individual or organization. The existing local Store/PFX
wrapper does not claim that the Azure signing service is already connected.
See [Microsoft pricing](https://azure.microsoft.com/en-us/products/artifact-signing/)
and [identity requirements](https://learn.microsoft.com/en-us/azure/artifact-signing/quickstart).

A valid publisher signature does not guarantee that SmartScreen immediately
stops warning about a new executable. See Microsoft's
[code-signing comparison](https://learn.microsoft.com/en-us/windows/apps/package-and-deploy/code-signing-options).

## Verification scope

Fresh read-only production checks matched Fly version 134: all 26 backend source
files, all 43 artifact files, public configuration and native policy sequence 3.
Fresh transport/native policy signatures and request nonces verified; requests
without authorization to all four Plus artifact routes returned HTTP 401.
Evidence: `build/fly-rollout-auth-recovery-20260927/live-short-list-reaudit.json`.

Fresh native tests passed 34 checks, with two opt-in host probes skipped. Tests
include TLS rejection before HTTP bytes, signed manifest tamper/replay rejection,
VM-policy fixtures, DPAPI corruption and encrypted-cache corruption. The exact
published executable still passes its source-bound protection gate and all 14
reviewed VMProtect paths.

Fresh Java checks passed 40 lifecycle scenarios (including the real 30-second
scheduled heartbeat), 32 TLS checks, 1,250 delivery checks and 933 runtime protocol
checks. Current Java source inputs match the published protected candidate's
receipt. Logs are under `build/module-qa/java-narrow-auth-audit-20260927/`.

This is not a live licensed Minecraft injection test or an interactive
mitmproxy session. Tests of protocol rejection and TLS handshakes do not imply
that a modified authorized process cannot be inspected or patched.
