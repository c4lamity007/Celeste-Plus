# DPoP authentication

Implemented for standard Celeste (Windows, macOS, Linux) and Celeste+ (Windows).
Published together on 2026-09-20: Fly release v93, standard artifact release
`celeste-20260920-dpop-02`, Celeste+ `celeste-plus-20260920-dpop-01`, and native
launcher build `20260920.2`. See [release verification](releases/20260920-dpop.md).

## Protocol

The installation's existing Ed25519 key signs a fresh RFC 9449 DPoP JWT for
each protected HTTP request. The header contains only its public Ed25519 JWK.
The proof binds the HTTP method, HTTPS origin/path, issue time, unique random
proof ID, and (for token-authorized operations) SHA-256 of the exact token.
Query and fragment are excluded from `htu` per the standard. Existing signed
challenge/heartbeat bodies remain in place; DPoP does not replace them.

New signed installation certificates, access grants, session tokens and beta
manifests include the RFC 7638/8037 `cnf.jkt` thumbprint. Resource requests use
`Authorization: DPoP <token>` plus the `DPoP` proof header, never Bearer fallback.
The server checks both the token binding and the stored installation key.

Protected paths:

- Enrollment/authentication/installer/stage completion and session checks:
  proof without `ath`, retaining their existing signed body checks.
- Bootstrap, staged loader, split payload and beta artifact/manifest downloads:
  token-bound proof with `ath`.
- Installer ticket consumption and authenticated cloud-config requests:
  token-bound proof with `ath`.

Challenge-start routes remain unchanged. Admin website/session authentication
is separate and unchanged.

Proof freshness is 60 seconds, with at most 5 seconds of future clock skew.
The server atomically consumes `(key thumbprint, proof ID)` in SQLite before
releasing protected content. Reusing a captured proof is rejected, including
across service restarts/workers using the same database. Expired entries are
pruned. Clients generate a new proof on each new HTTP request.

## Existing installations and platform handling

Saved installation certificates issued before DPoP remain usable: their
server-signed `install_key` establishes the signing public key. Clients verify
that the private/public pair matches. Newly issued certificates and grants
must have matching `cnf`; missing binding is not a compatibility fallback.
No license reset, private-key rotation, or new credential format is required.

The shared Rust signer is platform-independent. Java uses JDK Ed25519 support.
Standard Celeste retains the existing platform-specific credential protection.
Celeste+ Bootstrap A reads the existing selected-instance DPAPI-protected
credential through the game's JNA API, validates its signed installation
certificate and exposes only a signing capability to its delivery client.
Private keys are not added to launcher IPC. Missing protection support fails
closed instead of falling back to plaintext storage.

## Coordinated release requirement

This protocol intentionally does not downgrade. A new client rejects an old
server's unbound grants; an old client is rejected by the new server. Do not
publish just the server, a single loader, or these unregistered candidate EXEs.

For a release:

1. Finish the normal obfuscation/package flow for the updated standard stages,
   both Celeste+ bootstraps, both payload parts, and both native launchers.
   Derive signed release metadata and digest allowlists from final artifacts.
2. Explicitly configure `CELESTE_PUBLIC_ORIGIN` to the public HTTPS origin
   actually used by clients (currently `https://qb0f90cf11f4d1d52.fly.dev`).
   No path/query/fragment or credentials. If unset, the first configured
   allowed host is used with HTTPS; arbitrary forwarded-host headers are not
   trusted for the proof target.
3. Back up the auth database and use a coordinated maintenance rollout for the
   server, artifact catalogs, stages/payloads and launcher distribution. The
   additive startup migration creates only the DPoP replay table/index; it
   does not remove licenses, installations or cloud configs.
4. All serving workers must use the same replay database. Independently copied
   Fly machine volumes are not a shared replay store; use one authoritative
   database or implement a shared atomic replay store before scaling that way.
5. Test an existing activation and a new activation for both products, protected
   downloads, heartbeat/revocation and cloud configs. For rollback restore the
   matching previous client/server/artifact set, not only one component.

Existing installed launchers need the matching protocol update. The normal
public update-metadata route is not DPoP-protected and does not deliver client
payloads, but this alone does not prove every old launcher can self-update.

## Local verification (2026-09-20)

- Server: 186 passed, one existing Windows symlink-permission skip. Includes
  adversarial missing/forged/replayed/wrong-key/wrong-target/wrong-token proofs,
  concurrency/restart checks, and revocation/entitlement tests.
- Native standard: 77 passed, two existing ignored; native Celeste+: 64 passed,
  three existing ignored. Both Windows release candidates built offline.
- Java signer: 23 checks. Actual client protocol: 683 checks, including
  token-bound downloads, cloud GET/POST and installer consume. Normal client
  and all normal stages/bootstrap build successfully.
- Beta Java delivery: 761 checks; protected-runtime credential parser: 18;
  Bootstrap A/B checks and JAR builds pass. Server-schema fixture regenerated.
- Real Java and Rust synthetic proofs independently accepted by the Python
  verifier, then rejected on replay. No real credentials or live network used.
- Shared Rust proof module cross-checks pass for Linux x64 and macOS x64/arm64.
  Whole native app tests, release builds and hardware-collection smoke checks
  also passed on macOS x64/arm64 and Linux through platform-native CI. Linux
  distribution uses the Ubuntu 22.04 / glibc 2.35 baseline. Mac applications
  retain ad-hoc signatures; they are not Apple-notarized.
- Final obfuscated normal bootstrap, configured client and Celeste+ Bootstrap A
  pass the actual archived-signer tests (16, 13 and 13 checks respectively).
  Exact final catalogs pass synthetic enrollment, both loader chains, all ten
  artifact downloads, heartbeat, cloud listing and replay rejection.
- Fly v93 is healthy; all 13 deployed file hashes match the tested release,
  the DPoP verifier and replay table are present, and the existing database,
  cloud-config paths and approved installer channel are unchanged. No live
  license was created/reset for testing. Full licensed in-game testing across
  all operating systems remains unverified.

DPoP prevents a stolen access token by itself from authorizing new requests.
It is not TLS replacement, endpoint attestation, or a guarantee against code
extraction on a licensed compromised machine. A live interceptor able to read
plaintext and race a still-unused valid proof may win its first use; key theft
or access to the legitimate signer also exceeds token-only replay protection.
Continue validating HTTPS certificates and server-signed release metadata.
