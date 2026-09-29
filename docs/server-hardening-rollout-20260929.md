# Server hardening rollout — September 29, 2026

The shared Celeste backend was updated and verified before the next Celeste+
payload release. It retains the Standard release, the current Plus catalog,
existing native loader registry and signing identities.

## Changes

- Admin password checks reserve a persistent attempt before the expensive hash,
  with one expensive verifier at a time in the current single-worker process.
- Authentication and heartbeat reuse verified immutable catalog metadata.
  Changed file identity, timestamps, links or manifest bytes invalidate the cache;
  artifact downloads still verify their bytes and all existing authorization gates.
- Rejected proofs keep their audit event after rolling back the rejected operation.
- Secret settings are redacted from object representations. Optional restricted
  POSIX secret-file inputs are supported. This rollout keeps the existing credential
  configuration and does not rotate keys or enable new secret mounts.

## Deployment

| Item | Verified value |
| --- | --- |
| Fly app | `qb0f90cf11f4d1d52` |
| Machine | `85d426c4429798` |
| Machine version | `01M3QGK5VH570MZGKVFGFVQ7J9` |
| Previous image | `sha256:0774e344e9e5dfda5cda669c5e7f90c62e7811a77210a7a429b9a51d0a1a76d9` |
| Backend image | `sha256:906191938065e254a6c2615cd3164d44f00681601f32986abd1bccca3cd5ef00` |
| Source inventory | 30 files: 25 unchanged, three modified, two added |
| Retained game artifacts | All 108 files retain their exact hashes |
| Native registry | Existing sequence 3 and all three approved binary records |
| Public loader | Existing 68,233,216-byte executable; downloaded hash verified |

The image inherits the pinned live image and copies only the approved `app/`
tree. The exact previous Fly configuration, dependency lock and entrypoint are
retained. The generic repository image was not used to replace the artifact
directories. The update targeted only the existing machine and SQLite volume.

A consistent private SQLite backup was created on the existing volume at
`/data/backups/server-hardening-20260929-01/auth.sqlite3`. The source quick check
and backup integrity check passed. The backup file is mode `0600`, its directory
is `0700`, and both belong to service user 10001. No customer rows or credentials
were exported.

## Verification

- Server suite: **546 passed**, three platform-related skips. The actual POSIX
  secret-file integration case was skipped on Windows; new mounts are not enabled.
- Deployment wrapper: **10 tests passed**, including changed-source rejection,
  exact full snapshot enforcement and stale-baseline rejection.
- Full live hashes match all 30 backend sources and all 108 retained game files.
- Database schema, native policy/registry, public configuration, dependencies and
  entrypoint match the previous deployment.
- Health, Standard identity, signed transport/native/loader policies and actual
  public loader download hash passed. All five anonymous Plus resource requests
  were denied.

These checks do not claim a real-customer login, a production load test or
Minecraft injection. Subscription, HWID, installation-proof, replay and
revocation behavior were covered by disposable-license server tests.

## Evidence

Ignored local evidence is in `build/server-hardening-rollout-20260929/`:

| Receipt | SHA-256 |
| --- | --- |
| Stage | `0d24a54cfbb8569a035fe686f2246edb1010d1c33f684f7558cd8c2f0d0fa257` |
| Before | `5364b1e5205750882c9e5114272dd709c57e12474280a10b2fa4051e4c4ae3b8` |
| Backup | `46b5c322a4ea80c9b4e4dfb623f6272dbab6b2c565207a1b231b8d82163f3872` |
| Live after | `32389875ee846bdfe0b0fe3f896244c4700667d7da8ad61430fab124a1b62a67` |
| Image build | `577213b73fd3afe4b6fcbd674f415450e7ecef0c8509492f31b33594875db5ea` |

The next Plus catalog image must inherit the verified backend image above and
preserve its 30-file source map. The next release ID is
`celeste-plus-20260929-auth-visuals-03`.

## Rollback and remaining work

For a backend code rollback, redeploy the previous pinned image with the retained
`rollback.fly.toml`, targeting the same machine and preserving the current volume.
Do not restore an older SQLite database to reverse application code; doing so
could discard current subscriptions, revocations and audit records.

Admin MFA or a trusted private access gateway, KMS/HSM signing and coordinated
trust-root rotation remain separate deployment/design work. This release does
not claim those protections are enabled.
