# Server hardening

## Implemented

- Admin sign-in reserves its rate-limit attempt in a SQLite write transaction
  before password verification. One Argon2 verification can run per process;
  excess concurrent requests receive `503` with `Retry-After: 1`. Five attempts
  per IP are admitted before backoff. A successful sign-in clears that IP's
  attempt counter. Configure one service worker on the current small machine;
  additional processes each have their own one-verification concurrency bound.
- Runtime catalog metadata is reused only after the entire release has passed
  digest verification. Every reuse checks the manifest bytes and all five file
  identities, sizes, link counts, modes, modification times and change times.
  Linux uses inode change time; Windows uses `FileBasicInfo.ChangeTime` rather
  than creation time. Changed files invalidate the entry and require a complete
  verification. Cached metadata also expires after five minutes. Cache entries
  contain no license state, private settings or response payload bytes.
- Authentication completion verifies a cold catalog before acquiring the SQLite
  writer lock, then checks the file snapshot again inside the transaction. Live
  device, license, entitlement, challenge, signature and DPoP checks remain in
  place. Artifact downloads still verify all four actual files and send the
  exact checked selected bytes; their authorization/encryption flow is unchanged.
- Rejected enrollment, authentication and scoped proofs roll back the auth
  transaction, commit only their denial audit record, then return an error.
- Settings representations hide signing seeds, key peppers, session secrets
  and admin password hashes.

## Optional secret mounts

The Linux service accepts each of these settings either directly or through an
absolute file path in the corresponding `_FILE` variable:

| Existing secret variable | Optional path variable |
| --- | --- |
| `CELESTE_SIGNING_PRIVATE_KEY` | `CELESTE_SIGNING_PRIVATE_KEY_FILE` |
| `CELESTE_KEY_PEPPER` | `CELESTE_KEY_PEPPER_FILE` |
| `CELESTE_SESSION_SECRET` | `CELESTE_SESSION_SECRET_FILE` |
| `CELESTE_ADMIN_PASSWORD_HASH` | `CELESTE_ADMIN_PASSWORD_HASH_FILE` |

Configure exactly one source for each secret. Files must be regular files with
one hard link, owned by root or the service user, and mode `0400` or `0600`.
Group/other permissions, symlinks, oversized files, empty files and concurrent
changes are rejected. Mount them in a private directory that untrusted users
cannot write. Key files contain the same base64url-encoded 32-byte value as the
existing variable; the password file contains the Argon2 encoded hash.

Existing environment secrets remain supported. File mounts are optional and
require deployment configuration; this change does not create mounts, move
live secrets or rotate any key. Native Windows development keeps using
environment secrets because the mount option requires POSIX permission checks.
Restricted files reduce environment exposure; the Python process still loads
and can access the secret. This is not a hardware-backed or nonexportable key.

## Deployment actions and remaining work

1. Deploy the tested backend, retaining its existing live protocol, entitlement,
   publication and TLS-pin configuration. These source changes are not a rollout.
2. To use secret mounts, provision private files through the deployment secret
   facility, remove each replaced environment value, configure the matching
   `_FILE` path, and verify startup and a synthetic authorized request before
   routing traffic. Do not put secret files in the image or repository.
3. Put `/admin` behind a trusted identity gateway with phishing-resistant MFA,
   or implement and enroll application MFA in a separately tested rollout.
   Password login remains available in this version; no live MFA or private
   ingress is claimed. A gateway must protect both login and all admin paths,
   strip untrusted identity headers and retain an audited recovery procedure.
4. Grant signing still uses the existing key and fixed key ID. A managed
   signer/HSM and overlapping key rotation require coordinated client trust
   changes and a recovery plan. Do not rotate the server root unilaterally:
   current clients pin its public key. The separate native publication private
   key stays outside the online signing service.
5. Measure concurrency and latency in staging before increasing worker count or
   capacity. Local regression tests are not a production load test. A privileged
   host compromise can still alter both files and metadata; the catalog cache
   does not establish a trust boundary against a compromised server OS.
