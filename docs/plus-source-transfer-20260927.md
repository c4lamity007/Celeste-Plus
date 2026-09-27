# Celeste+ source and authentication handoff

## Repository

The destination is `https://github.com/c4lamity007/Celeste-Plus`, on `main`.
The earlier update to the separate Celeste development branch was restored to
`25b69e69add42462eb88640fad13a725a4a4144d`. Celeste/main was not changed.

This update starts from Plus commit `74212a6` and imports current development
source `ae01f2d08bb70904cc8ff95102e56f555662e870`. It preserves the standalone
project layout and its additional loader/resource retirement fixes.

## Included work

- All 568 game source/resource files are carried by the pinned core plus 188
  overlay files: 567 exactly match current development, and `CelesteClient`
  also preserves the existing standalone owned-screen cleanup. This includes module changes, Double Anchor and Safe
  Anchor, dropped-item/flame placement, Fake Gamble, HUDs, first-use tour,
  remembered GUI tabs, fog rendering, trajectories, and runtime authentication.
- Plus native authentication includes online native-manifest approval,
  installation proof binding, fresh catalog nonces, and VM/sandbox gates.
  Required Rust helpers live in `beta-lab/native-shared/` and `shared-auth/`.
- Bootstrap A revalidates before loading B and before invoking the payload.
  Java auth helpers live under `beta-lab/shared-auth/`. Existing standalone
  cleanup behavior is preserved, including rollback after denied readiness.
- `auth-service/` contains the exact coordinated backend source from the same
  development revision. Live account/device/product checks, nonce validation,
  proof validation, runtime leases, revocation, and native registration are
  included. This is the shared service; Plus requests use `celeste_plus`.
- Native publication helpers and CI are adapted to this repository's Plus
  product and paths. Standard launcher implementation/build jobs are excluded.

The original source split is recorded in `tests/source-provenance.json`.
`tests/source-update-provenance.json` records this update separately so the old
source hashes are not mislabeled as current.

## Verification

- Runtime reconstruction: 568 files, zero missing/extra/unexpected differences;
  one explicitly recorded merge preserves existing standalone screen cleanup.
- Fresh reconstructed runtime: `compileJava` and `processResources` passed;
  all ten processed PNG assets retained their exact hashes.
- Runtime preparation: 20 tests passed; one host symlink test skipped.
- Plus Rust launcher: 93 tests passed, four explicit platform/fixture tests
  ignored. Formatting and compilation checks passed.
- Native packaging/registration gates: 71 offline tests passed.
- Server staging: 10 tests passed. Bootstrap protection boundaries: 28 tests
  passed.
- Standalone Java host/auth/delivery/lifecycle checks and artifact assembly
  passed with a deliberately nonexistent Minecraft jar path, confirming default
  checks do not depend on a local game cache. The disposable-loader retention
  check collected all 32 retired payload loaders. The separate explicit real
  Minecraft schema gate passed 170 checks with the supplied 1.21.11 jar.
- The unchanged shared backend previously passed 398 tests with one host
  filesystem skip at this source revision. No new server deployment is claimed.

Fixture and compilation results do not establish live Minecraft behavior.
This repository transfer does not alter the tested module algorithms.

## Production status

This is a source update, not a live release. New clients and the backend require
a coordinated rollout: the new catalog request nonce is mandatory. Existing
published clients do not gain the new checks just because this source is pushed.

Protected native publication is blocked until the real Celeste protection
configuration/verifier, code-signing identity, and server publication key are
configured and verified. The checked-in protector approval list remains empty.
The Plus CI workflow produces unsigned candidates only. No production service,
published executable, or protected payload was replaced during this transfer.
