# Celeste+ module delivery protocol (version 1)

This service is an opt-in portion of the independent-module migration. Do not enable it or retire the legacy delivery route before the resident ownership gate, protected release, matching loader, and runtime migration pass together.

## Operator configuration

- `CELESTE_MODULE_RELEASE_DIR`: absolute directory containing `module-release.json` and `modules/<artifact_id>.jar`.
- `CELESTE_MODULE_KEY_ENCRYPTION_KEY`: persistent, randomly generated 32-byte base64url key. The existing restricted `_FILE` secret mount is supported on Linux. Never commit the key or generate a replacement automatically at startup.
- Both settings must be present together. Celeste+ and authentication protocol 2 must be enabled. They are independent of the separate Celeste product.
- Personalizations are stored in `module_personalizations` in the existing SQLite database. The account mapping and opaque watermark stay server-side; the client container includes only the opaque marker.
- Stored module content keys are AES-GCM wrapped with the dedicated operator key. Wrap AAD binds account, release catalog, module identity, and the entire personalized manifest digest. Back up that key with the database. Key loss or accidental replacement fails closed; it does not silently regenerate or serve plaintext.
- Ciphertext retention grows with authorized accounts and releases. Retain the active and approved rollback release during rollout; prune retired release personalizations according to the operator's storage/rollback policy. This protocol does not delete rollback data automatically.

## Approved catalog

`module-release.json` has exact keys:

`schema`, `product`, `release_id`, `host_abi`, `runtime_sha256`, `executable_root_sha256`, `modules`.

Schema is `celeste-module-release-1`, product is `celeste_plus`, and host ABI is 1. Module records have exact keys `id`, `version`, `artifact_id`, `entrypoint`, `filename`, `sha256`, `size`, `entries_sha256`. Opaque artifact identifiers are 32 lowercase hexadecimal characters. The filename must be that identifier plus `.jar`; the file lives in `modules/`.

The executable root is SHA-256 over canonical JSON with schema `celeste-module-executables-1` and keys `schema`, `product`, `release_id`, `host_abi`, and the exact ordered `modules` list. The resident host pins that root before its own protection. The final catalog adds the resident runtime identity afterward, avoiding a circular hash dependency.

Bounds are 512 module records, 512 KiB catalog, 16 MiB per compressed JAR and 256 MiB total compressed release size. The runtime loader independently validates expanded entry sizes and class/resource ownership before defining classes.

## Requests and lease authority

All three routes require an existing `celeste-session` grant, a live Plus entitlement/device, the matching resident runtime release/catalog, and fresh installation and token-key request proofs. Each enable requires new server authorization, even when ciphertext and a still-valid key are already present; ciphertext caching only avoids the artifact transfer. Long-lived installer/client grants cannot access these routes.

Required headers include `Authorization: DPoP <session>`, `X-Device-Id`, `X-Celeste-Delivery-Key`, `X-Celeste-Request-Nonce`, and `DPoP`. The DPoP extensions bind:

- `hwid`: the exact canonical hardware header.
- `cek` and `cnonce`: X25519 recipient key and request nonce.
- `bht`: base64url SHA-256 of the exact HTTP request body bytes, including the empty body on GET.
- `meh`: base64url SHA-256 of decoded `X-Celeste-Module-Evidence` bytes.

`X-Celeste-Module-Evidence` is base64url canonical JSON with exact keys
`{schema: "celeste-module-evidence-1", hardware: {system, board, processor, monitors}, hwid_sha256, observed_sha256}`.
The hardware fields contain the existing hashed identifiers, not raw serial numbers.
Monitors are a sorted, unique list of 1 to 8 hashes. Both canonical hardware-core and
monitor fingerprints must match enrollment. HWID must be explicitly enrolled and
match the request and runtime lease. The freshly observed resident digest must
match the approved runtime identity. Evidence is at most 4 KiB decoded.

`X-Celeste-Module-Token-Proof` is the base64url Ed25519 token-key signature over
UTF-8 `celeste-module-proof-v1\n` followed by the exact compact DPoP string. This
binds the independent key to the same token, body, evidence, recipient, and nonce.
Missing evidence or either key proof fails closed on all three endpoints. Signed
catalog/capability responses include `evidence_sha256` (hex) for client comparison.
Live key/device state is rechecked after slow preparation too. The signed hardware, process and session identity must also match an active per-process runtime session record. Different legitimate JVM processes on the same installation remain independent. Replacing a session in one process retires its old session ID; a late heartbeat cannot make that retired ID current again. Records older than one hour are pruned, beyond the maximum signed-request and runtime-lease lifetimes. The existing runtime_bindings row remains hardware-conflict telemetry.

Success responses use the existing `CSTAE1` recipient-encrypted envelope and `application/octet-stream`, with `Cache-Control: no-store`. Authenticate the entire envelope before parsing or exposing any plaintext.

### GET `/v1/modules/catalog`

Encrypted JSON: `{catalog, proof}`. The signed `celeste-module-catalog` proof binds the canonical catalog digest, executable root, ABI, complete runtime session identity, originating session-token SHA-256, and request nonce. The client compares the approved executable root to its resident pin.

### POST `/v1/modules/authorize`

Body: `{catalog_sha256, modules: [module_id, ...]}`. Duplicate/unknown identifiers and empty batches are rejected. There is no active-module-count limit. Clients divide large activations/renewals into batches of 32 to fit the existing 16 KiB request limit.

Encrypted JSON: `{catalog_sha256, expires_at, capabilities: [...]}`. Each item has `{module_id, capability, key, manifest, url}`. The independent 32-byte content key is base64url encoded only inside this encrypted response.

A capability has audience `celeste-module-capability`, the complete personalized manifest, module identity, approved catalog/root, and runtime-session binding. Its expiry is no later than the current host lease, license expiry, or 90 seconds. The existing 30-second heartbeat obtains a fresh host lease; the client then renews module permissions in batches. Reusing ciphertext or possessing an old content key does not authorize a new session or extend a lease.

### GET `/v1/modules/artifacts/{release_id}/{artifact_id}`

Also requires `X-Celeste-Module-Capability`. Its module, manifest, account/device, hardware, runtime release, process and session identity must match. A newer host token for the same runtime session can use a still-valid capability. A different process/session cannot.

The recipient envelope contains the stored personalized ciphertext, not plaintext executable content. Live entitlement/HWID/revocation/release checks run again after preparation and before response release. Transfer concurrency is bounded at two through response completion/cancellation. Module streams repeat live checks off the event loop before their first bytes, at a maximum once-per-second cadence during transfer, and before releasing the final authentication tag. Revocation, session replacement, or lease expiry leaves an incomplete envelope that cannot authenticate; cancellation releases the transfer permit.

## Personalized container

Ciphertext is `nonce[12] || AES-GCM(container)[including tag]`. Its AAD is canonical JSON:

`{schema: celeste-module-aead-1, product: celeste_plus, release_id, module_id, artifact_id, catalog_sha256}`.

Container framing uses network byte order:

1. ASCII `CSTMC1`.
2. Unsigned 32-bit metadata byte length, then canonical metadata JSON.
3. Unsigned 32-bit record count.
4. Each record: opaque 16-byte ID, unsigned 32-bit byte length, raw bytes.

Exactly one authenticated `real_record` is the unchanged approved JAR. Other records are inert random data and must never enter a classloader. Combined decoy/padding bytes, including each decoy's record header, cannot exceed 10% of the approved compressed JAR size.

Metadata schema is `celeste-module-container-1`; the external signed manifest uses `celeste-module-personalization-1` and adds exact plaintext/ciphertext lengths and digests. Both bind module/release/ABI, executable digest/size, entry inventory hash, opaque watermark, real record ID, transformation version, and decoy byte count.

Personalization does not change or authorize executable entries. The deterministic transformation receipt is the authenticated mapping from the fixed executable digest/version to the persisted personalized container digest/version. Attribution is supporting evidence only: a class-only dump can discard the container marker.

## Verification

`tests/test_module_delivery.py` uses real HTTP, disposable identities, installation DPoP, X25519, and AES-GCM. It covers batch activation, exact JAR preservation, decoy bounds, request/body/recipient/HWID proof binding, replay, cross-session/module rejection, entitlement revocation, expiry, catalog/executable tampering, slow-work revalidation, concurrent cache coalescing, and corrupted wrapping keys.

`beta-lab/delivery-client/fixtures/module-delivery-v1-golden.json` is a disposable Python-to-Java container/AEAD interoperability fixture. Its key is synthetic fixture data, never a deployment secret. These checks do not establish in-game behavior or a production protection pass.

## Production cutover gate

Set `CELESTE_PLUS_REQUIRE_MODULE_DELIVERY=true` only after the complete modular
release passes ownership and protection verification. Startup then requires both
module delivery credentials and a beta resident catalog whose release ID and
runtime digest exactly match `module-release.json`. Every Plus bootstrap
manifest/artifact and runtime catalog reuse repeats this binding, including cached
metadata and the final auth-commit check. Merely keeping an old full payload in a
beta directory no longer makes it distributable. Standard Celeste routes continue
to require their separate product grant.

Retain signed rollback evidence and both matching directories for an approved
**modular** release. Rollback stages that exact host/module pair with the cutover
gate still enabled. There is no per-request legacy override. The false default is
only for staging an unfinished migration; it must not be left false at production
cutover. Changing this operator policy requires restarting the service.

For the first cutover only, retain the exact signed legacy release as an inactive
emergency rollback with its prior image/configuration and loader. Restoring that
contract is an explicit coordinated emergency action, never a per-request fallback.
The newer native policy must continue to allow the retained loader; replaying an
older native publication sequence is rejected. See MODULE-ROLLOUT.md.


## Installation-specific QA before cutover

The same approved origin supports a server-controlled QA assignment:

- `CELESTE_PLUS_QA_BETA_RELEASE_DIR`: absolute candidate resident release directory.
- `CELESTE_PLUS_QA_MODULE_RELEASE_DIR`: its exact matching module release directory.
- `CELESTE_PLUS_QA_INSTALLATIONS`: comma-separated enrolled installation IDs; at most 16 for this explicit QA cohort.

Both candidate directories are required together, must be isolated from default
release directories, and must pass the mandatory modular host/catalog binding at
startup. The persistent module KEK and Plus protocol 2 are still required. The
candidate directories may be staged with an empty installation list, which does
not assign any customer to QA.

Only the authenticated enrolled installation selects this pair. The account,
license key, display name, a requested release ID, or a custom request header do
not opt a device into QA. Manifest, bootstrap artifacts, runtime authentication,
heartbeat, cloud-config authorization, module catalog, authorization, and module
artifacts use the same assignment. Normal installations keep the default Plus
release. Standard Celeste remains on its separate contract.

Assignments are immutable process configuration. Changing them requires a
controlled restart/deployment that drains the prior backend instance; do not mix
old and new assignment configurations behind the same origin during QA handoff.
A reassigned installation must reload its matching host. Its former release
identity and capabilities fail closed against the replacement configuration.
No live assignment is made by the staging tools or tests.
