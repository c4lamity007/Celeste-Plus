# Independent module delivery

Status: implementation and local verification in progress. This document is not a production release approval.

## What stays resident

The resident runtime contains authentication, module descriptors and saved settings, keybinds, neutral game hooks, shared action/rotation arbitration, and generic rendering/resource infrastructure. Each of the 86 module implementations has its own artifact and child classloader. Module-specific algorithms, shaders, and renderers belong to the corresponding artifact.

The build checks exact class and resource ownership, forbidden references to other modules, and JVM member access across classloaders. An immutable catalog of approved executable digests is included in the resident release. Personalized metadata cannot change those executable digests.

## Enabling and disabling

Saved user intent is separate from runtime state. The UI reports loading, starting, stopping, and failures while settings remain accessible.

Every enable requires a fresh server authorization, including a repeated enable during the same session. Authorization requests may batch up to 32 module IDs; each returned module capability is checked individually. Up to two downloads run concurrently. Network access, verification, and payload preparation run outside the game and render threads.

A valid response is tied to the requested activation generation. Disabling while a request is in flight makes that response ineligible to activate the module. Re-enabling waits for the previous generation to finish retiring.

On disable, callbacks stop dispatching immediately. The owner scope detaches registrations, releases gameplay ownership, drains work, disposes GPU resources on the required thread, wipes owned plaintext buffers, and drops references. A failed or timed-out retirement quarantines the module instead of running another instance. Normal gameplay does not force garbage collection. Defined JVM classes may remain in memory until the JVM unloads their classloader.

## Cache and latency

The on-disk cache is bounded to 256 MiB and contains encrypted containers only. It stores no usable content keys, private keys, or reusable authorization capabilities. Cache existence never authorizes activation.

A warm enable still makes a live authorization request; successful authorization allows the encrypted cached artifact to be reused without another artifact download. A cold enable additionally downloads the artifact. After publishing an authorized session, the authorization worker prefetches the signed catalog; first activation shares that request if it is still pending. Prefetch never authorizes or loads a module. Actual latency depends on the server round trip, download size, verification, and module initialization. No in-game latency figure is claimed by the local fixtures.

A same-origin `503` with a single numeric `Retry-After` of one to three seconds can retry at most three times on the delivery worker. Waits include up to 250 ms of jitter and total at most ten seconds. Waits and HTTP timeouts must fit the original runtime lease and, for artifact downloads, the original module capability. Each retry collects fresh evidence and creates new recipient keys, nonce, and proofs. Authentication denials, integrity failures, rate-limit responses, and arbitrary I/O failures are not retried. Exhausted retries remain visible failures. Disabling invalidates the activation generation immediately and requests cancellation of that exact generation. Queued preparation is removed; running delivery and retry waits are interrupted cooperatively. A shared authorization batch is interrupted only when all of its requests are canceled. The canceled acquisition completes after its owned preparation buffers and any uninstalled lease are cleaned up, so re-enabling cannot race the retiring worker. Failed cleanup quarantines the module. Closing the provider interrupts all of its workers.

Content keys remain in the authorized running session only. They expire with their module capability or the host lease, whichever ends first. Heartbeats run every 30 seconds; authorization cannot exceed 90 seconds. Network failures do not extend a lease, and explicit rejection closes access immediately. Restarting the process requires fresh authorization.

### Authentication performance and retirement

Fresh resident integrity measurements hash the retained bytes during a synchronous lifetime borrow, without cloning the entire resident payload. The full digest is still recomputed for request evidence. Hashing does not hold the monitor used for normal class and resource reads. Retirement immediately rejects new access and waits for the active borrow before wiping retained bytes, including when the closing thread is interrupted.

Each hardware evidence request captures SMBIOS once and feeds the existing legacy and v2 parsers from that same capture. The legacy first-CPU identity and v2 sorted multi-CPU identity remain unchanged. The capture is request-local, raw buffers are wiped, and the next request collects fresh firmware, registry, disk, machine GUID, and monitor evidence again.

Module preparation uses bounded, exact-size ZIP entry buffers when sizes are declared, and wipes owned staging buffers for streaming entries. Authentication tags, archive and entry digests, CRC checks, expansion limits, and verification before class definition remain required. Heartbeat module hashing holds neither the game/render authorization monitor nor the class/resource read monitor; its lifetime borrow still prevents retirement from wiping bytes mid-hash. Verified asset copies are prepared on delivery workers, then transferred to the scoped registry on the owner thread. Canceled or failed preparations wipe untransferred copies. Prepared modules retain their originating runtime identity and cannot be revived by a different session.

Verification of an immutable, already signed session object is reused in memory; deadlines and current identity are checked on every use. A replacement heartbeat token is verified again. Every module activation still performs its live server request. Optional cache directory, read, or write failures can fall back to authenticated in-memory delivery without replacing conflicting files or skipping verification.

HTTP transports have explicit owners. Retiring a delivery client cancels its pending requests, and closing the last transport owner shuts down the shared pinned clients. Policy renewal, certificate pinning, and response verification remain mandatory. There is no forced garbage collection during gameplay.

The backend separately bounds preparation and streaming concurrency to two each. Slow downloads cannot monopolize permission renewal. Intermediate checks in a batch reuse its verified release metadata while checking live device, entitlement, revocation, and session state for every item. Final release checks still precede response issuance. Personalization encrypts framed sections directly instead of assembling additional full plaintext containers.

Bootstrap downloads hash their selected response bytes independently and reuse only verified release metadata. Every file's identity/change stamp and the manifest bytes are checked; the metadata verification expires after five minutes. Authorization is never stored in this cache. Download allowance is charged after admission, with another live check after any database wait. Busy attempts still consume one-use proofs and request abuse limits.

Unauthenticated request-body buffering has separate admission pools: four configuration uploads of at most 1,500,000 bytes each, and 32 ordinary bodies of at most 16,384 bytes each, per application worker. Each body has one 15-second deadline that incoming chunks cannot extend. Admission exhaustion returns 503 with Retry-After; an expired body read returns 408. Permits end before downstream authorization or streaming, and incomplete staging buffers are released. These limits bound body buffering, not total service memory or distributed denial-of-service exposure.

Native bootstrap delivery decrypts and authenticates in the owned transport allocation, then reuses that allocation for verified plaintext. It wipes failed inputs and the discarded framing tail. Resident-control endpoints are keyed by exact process lifetime and retired when Windows confirms that process has exited or its PID has been reused. A slow command holds only that process's lock. Owned control keys and transport buffers use zeroizing storage; this does not promise erasure of all library-internal or JSON/string copies. These native source changes require fresh protection evidence before publication.

Within one module HTTP request, successful cryptographic checks are retained as immutable request facts. Every use compares the exact method/path/headers/body and current enrolled proof keys and hardware binding. Entitlement, revocation, session replacement, release identity, and clock/lease expiry are still checked live; the next HTTP request verifies fresh proofs again. No facts persist across requests.

Expiry housekeeping uses indexed batches of at most 128 rows within successful authentication transactions. Session replay markers survive the final second accepted by the signed timestamp window. Live, rotated, and revoked grant records are retained through their signed expiry.

Local synthetic measurements from the October 2 audit:

| Workload | Before | After |
| --- | --- | --- |
| Fresh 32 MiB resident hash, allocated bytes | 33,721,582 B | 61,478 B |
| Fresh 32 MiB resident hash, mean time | 19.53 ms | 13.50 ms |
| Prepare 86 archives with 33 entries each, allocated bytes | 83.9 MiB | 38.6 MiB |
| Same preparation workload, median time | 165.5 ms | 151.9 ms |
| Server peak Python allocations: two 16 MiB preparations and two held downloads | 176.13 MiB | 96.07 MiB |
| Native 16 MiB decrypt, allocated bytes excluding prepared encrypted input | 33,554,733 B | 285 B |
| Same native decrypt, median of seven local iterations | 17.923 ms | 9.670 ms |
| Warm synthetic authorization for 32 modules, median of seven requests | 205.247 ms | 173.704 ms |
| Same 32-module request: repeated signature/recipient/evidence checks | 35 each | 1 each |

These are disposable fixtures, not Minecraft frame-time, network latency, or production capacity measurements. Preparation timing includes decryption, hashes, ZIP extraction, retained integrity and cleanup, but excludes network and JVM class definition. Server allocation figures exclude SQLite and native allocator overhead. Existing shared-IP rate limits may still constrain many installations behind one NAT; these measurements do not establish capacity for 25 concurrent users.

Encrypted caching reduces bandwidth; it does not make executable code impossible to collect. An authorized user can enable modules sequentially and attempt to capture decrypted memory. Watermarks aid investigation of recovered containers, but stripped class-only dumps may lose them. A watermark alone is not grounds for automatic account punishment.

## Request and response binding

Module delivery uses the existing Celeste auth origin:

https://qb0f90cf11f4d1d52.fly.dev

The module catalog, authorization, and artifact requests each carry newly collected hashed system/SMBIOS, board, processor, monitor, and HWID evidence, plus the observed resident client digest. Windows evidence collection uses firmware/registry APIs without a WMI round trip. Raw serial numbers and private keys are not sent.

The installation-key DPoP signature binds the HTTP method and target, runtime token hash, device hash, exact request-body hash, exact evidence hash, fresh recipient public key, and request nonce. A separate token-key signature countersigns that exact DPoP proof. The server checks both proofs, enrolled device identity, current entitlement/revocation state, runtime session, and approved release.

Each response uses a fresh recipient-encrypted X25519/HKDF-SHA256/AES-GCM channel. Signed authorization binds the request nonce and evidence to the account, installation, session, module, host interface, release, personalized artifact digest, and expiry. Authentication tags, signatures, bounds, and digests must pass before any class is defined.

These checks prevent a copied cache or replayed response from authorizing a new process. Hardware identifiers and client digests are client reports, not hardware-backed remote attestation; a fully controlled authorized client remains a different threat.

## Concurrent runtimes and session replacement

Authorized JVM processes on one installation remain independent. Runtime session records are keyed by installation, process ID, and session ID. Starting a replacement session in the same process retires the earlier session; its signed token no longer authorizes module delivery, and a late heartbeat cannot reactivate it. Other process IDs remain valid. The older runtime binding row is hardware-conflict telemetry, not a single global process lock.

Catalog authorization and artifact delivery recheck the active session after preparation. During an artifact transfer, live checks run off the event loop before the first bytes, at most once per second while transferring, and before releasing the final authentication tag. Expiry, revocation, or session replacement prevents the recipient from authenticating a complete response. Canceled transfers release their concurrency slot.

## Personalized containers

Executable bytes are identical across accounts for an approved release. The server adds bounded opaque account/release markers and signs the complete personalized container. A deterministic receipt connects the approved executable, personalization version, and final digest.

Each personalized container has an independent random encryption key, wrapped at rest using a separately configured persistent server key. No universal unlocking secret is embedded in the client. Inert decoy records use the same framing but are never defined, executed, advertised as modules, or fetched individually. Combined decoy and padding overhead is capped at 10%.

## Release and verification gates

A production cutover requires the complete module roster, exact ownership checks, Java protection, fresh native/VMP evidence for changed authentication code, protocol and lifecycle verification, actual game QA, and a matching signed resident/module release. The fixed resident transport still uses two parts; module identities are independently verified.

Before creating the final module catalog, packaging rechecks the actual staged bootstrap and resident artifact bytes, binds both resident parts to the exact Java validation receipt, and recomputes the resident canonical digest. Its self-referential auth descriptor remains covered by the exact part hashes. This consistency gate does not substitute for native protection or actual Java validation.

The Plus cutover policy requires its approved module catalog to match the served resident release and rejects the legacy Plus delivery route. Standard Celeste retains its existing contract. Retain signed rollback evidence; do not publish a partially migrated host.

### Installation-specific QA and rollout

QA uses the existing auth origin and a server-controlled list of enrolled installation IDs. The candidate resident and module directories are configured as a matching pair. Catalog, bootstrap, runtime authentication, heartbeat, and module requests select that same pair from the authenticated installation. A client cannot choose a release through an extra header or account name. Unassigned Plus installations keep the production release, and Standard remains unchanged.

An assignment change requires a controlled backend replacement that drains the previous configuration. The assigned installation fully restarts Minecraft before loading the matching candidate host. The existing approved native loader can be reused when its dynamic signed-manifest contract is unchanged; its exact bytes and current native policy must still be verified. Actual game QA must finish before enabling mandatory modular delivery for the public Plus release.

The offline tool tools/stage_modular_server.py prepares a hash-pinned context and verifies an aggregate approval from the existing service signing authority. It does not sign, assign a live installation, or deploy. Its pinned-image mode layers the complete reviewed backend and candidate onto an immutable deployed image, preserving Standard and existing native files. The candidate native policy retains the previously approved loaders as well as the new loader, so public download and emergency rollback remain coherent.

At first cutover, the previous signed legacy host may be retained as an **inactive emergency rollback** with its exact image, configuration, loader, and signed evidence. Executing that emergency rollback restores the prior contract as an explicit coordinated action; legacy and modular contracts are never served as interchangeable fallbacks. Keep the newer native allowlist containing both approved loaders rather than replaying the older policy sequence. Subsequent normal rollbacks restore a matching signed modular host/catalog pair with mandatory module delivery enabled.

Operator preparation, secret requirements, and the exact rollback procedure are documented in [MODULE-ROLLOUT.md](../auth-service/MODULE-ROLLOUT.md).

Report local fixtures, build verification, and actual game results separately. Relevant local commands and scripts include:

- `gradlew betaModuleDeliveryCheck`
- `tools/qa/module_delivery_lifecycle.py`
- `tools/qa/module_provider_integration.py`
- `tools/qa/module_resource_lifecycle.py`
- `tools/qa/module_gpu_ownership.py`
- `tools/qa/module_visual_budget.py`
- `tools/qa/test_module_release.py`
- `tools/qa/test_module_source_conservation.py`
- `beta-lab/auth-lifecycle/run-tests.ps1`

Local fixtures use synthetic identities and signed test responses. They do not establish production latency, real GPU behavior, or successful production rollout.
