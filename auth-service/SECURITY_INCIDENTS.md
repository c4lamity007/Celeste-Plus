# Celeste+ security incidents

The report protocol is disabled for new mutations by default. Merely deploying
this source must not select a punitive policy.

## Explicit operator policy

`CELESTE_PLUS_SECURITY_INCIDENT_SCOPE` accepts:

| Value | Result after a valid second observation |
| --- | --- |
| Unset or empty | Reject new begin/confirm operations; no incident revocation |
| `celeste_plus` | Block this account's Plus access across its installations |
| `license` | Revoke the shared license, including Standard if it owns Standard |

Enabled policies require enabled Plus protocol 2 and both explicit allowlists:

- `CELESTE_PLUS_SECURITY_INCIDENT_INSTALLATIONS`: comma-separated installation IDs.
- `CELESTE_PLUS_SECURITY_INCIDENT_RELEASES`: comma-separated approved release IDs.

Both the authenticated installation and its server-selected release must match.
Empty lists never mean all installations or releases. An enabled scope with
either list missing fails startup. Wildcards and malformed IDs are rejected;
limits are 256 installation IDs and 64 release IDs. Enrollment on an allowed
account alone does not make another installation eligible.

Only eligible Plus heartbeats include the signed `security_incident_scope`
claim. A new client must not invent a default scope when that claim is absent.
Server mutation requests require an exact match between the signed lease scope,
request scope, and current server policy. Standard leases never carry the claim.
Every begin/confirm operation also rechecks installation and release eligibility
after acquiring its transaction, so a previously issued claim cannot bypass
removal from either list. Settings are loaded at process startup: changes require
a configuration reload/redeployment. Stored outcome retrieval remains available
under its original narrow proof requirements after eligibility is removed.

Plus-only blocks preserve licenses, device records and entitlement membership;
`products.require_entitlement` enforces a persistent `security_product_blocks`
entry on every Plus authorization path. All account Plus grants and unfinished
Plus authentication challenges are revoked/consumed. Standard remains available.
Whole-license policy also revokes the account's other product grants. Neither
policy affects other accounts. Changing or unsetting the policy does not undo
previously committed revocations or product blocks.

## Reporting contract

`POST /v1/security/incidents` takes at most 8 KiB of exact canonical JSON.
`begin` issues a signed warning; `confirm` requires another actual sample after
the warning. `outcome` retrieves the result of an ambiguous confirmation.

Both installation and token keys authenticate every call. Installation DPoP
binds method, URL, bearer context, hardware ID and exact body hash. The token key
signs `celeste-incident-proof-v1\n` plus that exact DPoP in
`X-Celeste-Incident-Token-Proof`. The body binds account, installation, runtime
PID/session, approved release/catalog/runtime digest, SMBIOS core/monitor/HWID
hashes, explicit scope, incident, request nonce, challenge and observation IDs.
No raw hardware serial numbers or unrelated process inventory are reported.

Each mutation rechecks live device/account status, subscription and entitlement,
registered token/revocation state, runtime session, current approved release,
hardware evidence, and both keys. Invalid proofs and reused nonces fail before
incident mutation. Plus legacy heartbeat mismatch reports deny further access
without invoking the old direct-license tamper action. The separate Standard
heartbeat contract is retained.

Warning delay is at least five server seconds, rounded upward, measured after
acquiring the database write transaction. Request freshness and authorization
clocks are rechecked after any lock wait. Its expiry is bounded by the
**original** runtime lease, subscription expiry and 90 seconds.
Lease refresh cannot extend an incident. The client separately requires five
seconds of warning frames actually displayed. Confirmation requires a fresh
sample ID and timestamp after the server deadline, matching the first signal's
kind, artifact and approved expected digest; observed mismatch bytes may change.

Accepted signal kinds are `self_debugger`, `resident_mutation`, and
`module_mutation`. Unknown/unavailable probes, network failures, ordinary
exceptions and failed computations are not observations. A different signal
family cannot confirm an existing warning. Server reports authenticate key
possession and context; they cannot remotely attest that a local claim is true.

## Approved integrity identities

| Signal | Artifact identity | Expected digest |
| --- | --- | --- |
| Resident mutation | `resident` | Verified runtime catalog `runtime_sha256` |
| Module mutation | Signed module descriptor `artifact_id` | `entries_sha256` |
| Self debugger | `process` | Empty; observed digest must also be empty |

The resident digest equals `AuthConfig.releaseDigest` and the signed session's
`client_sha256`. Module `entries_sha256` hashes exact sorted retained entries
using their name and content lengths. It is **not** the module JAR SHA, either
payload delivery part's hash, personalized container hash, or ciphertext hash.
The resolver reads the current selected owner/public catalogs; the client cannot
invent approved artifacts or expected digests.

## Lost responses and cancellation

A lost confirmation response does not justify resubmitting stale evidence as a
new strike. An authenticated `outcome` request retains the exact attempted
confirmation identity and evidence with fresh request proofs:

- A committed result returns signed `revoked`, without another punitive change.
- Signed `pending` first atomically tombstones that uncommitted observation ID.
  A delayed original confirm can never commit afterward. A retry requires an
  actually new matching sample under the still-valid original warning.

Exact outcomes remain readable for ten minutes using the original public-key
snapshots, even after revocation/lease expiry or a later operator scope change.
This exception only returns that incident's diagnostic result. It cannot issue
tokens, extend a lease, authorize artifacts or act on another incident. The client
uses a shorter bounded lookup period and must ignore responses from retired
authorization generations. Diagnostic audience `celeste-security-incident` is
never accepted as a gameplay or executable-delivery audience.

Limits are one pending warning per runtime session, six new incidents per account
per minute, and sixty authenticated calls per device per approximately 36-second
nonce window. Indexed pruning removes at most 128 expired rows per table/call.
Successful confirmation, account/product enforcement and audit record commit in
one SQLite transaction.

## Artifact guard workers

Module and resident download live checks use a worker pool separate from blocked
download admission workers. They still run at the start, during long transfers
and before releasing the final authentication tag. Both existing transfer limits
remain two. Cancellation, disconnect or the 30-second transfer deadline retain
the transfer slot until an already-running finite database check returns; stopping
the await cannot abort that worker. No further artifact bytes are released after
cancellation. This bounded cleanup may briefly extend response retirement beyond
the transfer deadline; it cannot admit unlimited orphan checks.

## Schema migration and rollback

Incident rows retain their signed account and installation identifiers without a
foreign key to mutable enrollment records. An authorized device-binding reset
can delete the device while preserving warning/confirmation history and bounded
outcome retrieval using the original public keys. It does **not** clear a Plus
account block. The existing reset action may reactivate a whole license, but a
separate Plus block continues to deny Plus entitlement checks.

A Plus block follows the account lifetime: its license foreign key cascades only
when an operator intentionally deletes the license, such as the existing explicit
full catalog reset. That operation takes its verified recovery backup first.
Incident history and audit history remain; deleted account UIDs are not reused by
the existing reset workflow.

Startup upgrades only the exact earlier incident-table definitions. It preserves
all rows and indexes in one savepoint, rejects unknown custom constraints/indexes/
triggers or inbound references, and leaves that migration unchanged on failure.
Take a consistent SQLite backup before deployment; leave incident tables present
when rolling back code.

A backend rollback **must retain the centralized Plus-block entitlement check**
once any Plus block exists. The older backend does not understand these blocks;
rolling back to it could allow new authentication because Plus-only enforcement
intentionally preserves the license and entitlement membership. Unsetting the
incident policy stops new mutations but does not make an old backend safe.
Rolling back only a client/owner artifact does not remove server enforcement.

With the policy unset and no prior enforcement records, old owner/public clients
receive their normal leases without the optional scope claim. Standard's contract
is unchanged. Legacy Plus mismatch reports now fail closed without bypassing the
new warning flow to immediately revoke a license.

## Verification and deployment

Focused tests:

```text
python -m pytest tests/test_security_incidents.py tests/test_security_incident_routes.py tests/test_security_incident_eligibility.py tests/test_live_guard_retirement.py
```

Run existing protocol, product, DPoP, module delivery and artifact streaming
regressions before rollout. Tests use synthetic keys, disposable SQLite and
in-process ASGI; they do not contact production or act on a real account/process.
Actual game warning rendering, host-generation retirement, protected integrity
events and native early-exit behavior require separate client verification.
Passive memory reads/dumps and every possible JVM transformation are not reliably
detected. A native protector that exits before Java runs cannot display its Java
warning. Changed protected code requires fresh protection evidence before release.
