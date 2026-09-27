# Celeste+ Safe Anchor held-input correction — published

Release: `celeste-plus-20260926-safe-anchor-hold-01`.
Published and verified September 27, 2026 at 03:29 UTC (September 26, 8:29 PM Pacific).

## Changes

- Safe Anchor and Silent Safe Anchor keep held Use consumed while a completed
  loop waits for a valid next target. Stale, missed or interactive targets no
  longer return that hold to vanilla and emit another block interaction.
- An existing anchor rejected by **Only own anchors** also consumes the press
  until physical release. Holding an anchor cannot fall through and stack it
  on an uncharged anchor; holding glowstone cannot emit extra native charges.
- Rejected ownership is checked before assigning the active anchor, so an
  untouched foreign anchor is not marked as an abandoned safe attempt.
- Placement, aim, flame handling, timing and dropped-item support are unchanged.
  Native collision and other entity exclusions, including players, remain intact.
  Double Anchor fixes are preserved.

## Update

From `safe-anchor-rollback-01`, use a successful **Stop**, then inject again.
Six resident support JARs, 32 canonical bridges and 32 transformer precursor
classes match. Restart if cleanup fails/requires restart or older incompatible
support remains. Live reinjection was not exercised.

## Verification

- Incremental build and all nine QA stages passed: 1,439 runtime, 562 slot-order,
  39 input lease, 210 geometry, 3,822 aim, 37 catalog, 627 Anchor/Double and 35
  physical-key/catalog checks.
- Runtime checks now compile the real input lease with the production coordinator
  and scheduler. Separate old-source negative controls reproduce the foreign
  anchor and held-loop leaks. A modeled vanilla input phase observes the extra
  old-code interaction; the correction consumes it.
- Cases cover stale/MISS/interactive targets, existing anchors with 0/1/4 charges,
  held and queued input, failed attempts, release/repress and recovery to a second
  four-action loop. They also retain dropped-item and player occupancy coverage.
- Compiled comparison checks 17 classes. Only the intended three Coordinator
  methods differ. Placement, Aim, Feature and Crystal/Double classes are byte-identical
  to `safe-anchor-rollback-01`.
- Obfuscation, protected packaging, JVM verification, DPoP and disposable
  authentication checks passed. Live health and all 13 artifact hashes verified.
  Both protected payload endpoints and the manifest reject unauthenticated access
  with HTTP 401.
- Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. Authentication
  code, launcher identity and persistent storage configuration are unchanged.

QA: `build/module-qa/20260927T032620Z-a462bb9f/result.json`.
Regressions: `build/module-qa/safe-anchor-held-input-fixed/`.
Parity: `build/module-qa/safe-anchor-hold-parity/comparison.json`.
Release: `build/module-qa/safe-anchor-hold-release-20260926/receipt.json`.
Live: `build/module-qa/safe-anchor-hold-release-20260926/live-after.json`.
Compatibility: `build/module-qa/safe-anchor-hold-release-20260926/support-compatibility.json`.

Source JAR: `12aaae508386264d9854d61ca20df8a1ce0d79b35c82f8cc19f3d105013d8a4a`.
Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:10248e28e59cfd009495996bdcb416268525bc01abf13393cfef03949fe94e68`.

## Remaining limit

This exact build has not been tested in an authenticated local Minecraft world.
The automated reproduction establishes the input leak; live animation and server
acceptance remain unverified.
