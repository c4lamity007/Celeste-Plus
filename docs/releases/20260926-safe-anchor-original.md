# Celeste+ original Safe Anchor flow — published

Release: `celeste-plus-20260926-safe-anchor-original-01`.
Published and verified September 27, 2026 at 03:42 UTC (September 26, 8:42 PM Pacific).

## Changes

- Restores Safe Anchor and Silent Safe Anchor's original `safe-anchor-01`
  activation, manual-placement tracking, ownership, holding, looping,
  cancellation, rotations and timing.
- Keeps only placement support for dropped items and normal/soul fire.
  Dropped `ItemEntity` is exempt from the existing occupancy predicates;
  other live non-spectator entities, including players, remain blockers.
  Native placement and collision rules still run.
- Initial anchors accept flame targets through the native placement context.
  Glowstone can replace the flame using its real outline. Normal cover scoring,
  target selection and aim remain unchanged.
- Double Anchor retains its latest fixes; its implementation classes are unchanged.

## Verification

- Incremental build and eight original-contract stages passed: 604 runtime,
  39 input lease, 210 native geometry, 3,822 aim, 37 catalog, 627 Anchor/Double
  and 35 physical-key/catalog checks. QA runner tests: 42 passed.
- Compiled comparison of 17 classes verifies all original Safe activation,
  hold, ownership, cleanup and rotation methods. Only item/flame placement
  methods differ. Double/Crystal classes are byte-identical to `safe-anchor-hold-01`.
- The later strengthened slot-order check was retained and run separately.
  It fails for the original cancellation behavior after charging when no cover
  can be planned: the held slot restores after USE and before MOVEMENT.
  This failure is recorded and bound to the source JAR. PacketOrderE compliance
  is not claimed, and this result is not counted as a passing check.
- Obfuscation, protected packaging, JVM verification, DPoP and disposable
  authentication checks passed. Live health and all 13 artifact hashes verified.
  Both protected payload endpoints and the manifest return HTTP 401 without auth.
- Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. Authentication
  code, launcher identity and persistent storage configuration are unchanged.

## Update and limits

Use a successful **Stop**, then inject again. Resident support and canonical
bridges match `safe-anchor-hold-01`; restart if cleanup reports it is required.
Live reinjection and gameplay with this exact build were not exercised.
The original cancellation slot-order limitation is intentionally restored with
the requested original logic; this release is not claimed to be bug-free.

QA: `build/module-qa/20260927T033756Z-07269d43/result.json`.
Parity: `build/module-qa/safe-anchor-original-parity/comparison.json`.
Known limit: `build/module-qa/safe-anchor-original-slot-limit/evidence.json`.
Release: `build/module-qa/safe-anchor-original-release-20260926/receipt.json`.
Live: `build/module-qa/safe-anchor-original-release-20260926/live-after.json`.
Compatibility: `build/module-qa/safe-anchor-original-release-20260926/support-compatibility.json`.

Source JAR: `01ef08ef1f9397064c9cdacb15227fc0db5bf46343c103ec41b8431d3ac7c6bc`.
Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:8ad2963d995cca8797dfabf1d7f1f821b03a84c1068ed1d52657a30d510b7888`.
