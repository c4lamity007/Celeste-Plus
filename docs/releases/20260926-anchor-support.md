# Celeste+ anchor placement and charge fixes — published

Release: `celeste-plus-20260926-anchor-support-01`.
Published and verified September 27, 2026 at 03:01 UTC (September 26, 8:01 PM Pacific).

## Changes

- Double Anchor stops after a rejected replacement or invalid placement result.
  Its next charge requires an actual uncharged anchor; final use requires a
  charged anchor. Targets are checked again immediately before the action.
- An accepted first charge is not retried after a delayed or rolled-back state
  update. The regression reproduces the old extra glowstone and verifies the fix.
- Lost initial targets and confirmation timeouts cancel without starting a normal
  sequence under the same held click. Air/liquid support checks apply at action
  time; ordinary Anchor charge/detonation also require a current anchor.
- Safe Anchor and Silent Safe Anchor can replace normal fire or soul fire with
  the initial anchor. Anchor and glowstone placements allow dropped items while
  retaining native collision checks and an explicit live-player occupancy guard.
- One double per modifier hold and the normal six-step item order are preserved.
  Replacement remains exactly one tick after first detonation. Stale or rejected
  state can cancel later steps; the replacement is never replayed late.

## Update

From `anchor-fixes-01`, use a successful **Stop**, then inject again. Six resident
support JARs, 32 canonical bridges and 32 transformer precursor classes match.
Restart if cleanup fails/requires restart or older incompatible support remains.
Live reinjection was not exercised.

## Verification

- Incremental build and all nine QA stages passed: 814 Safe Anchor coordinator
  checks, 562 slot-order checks, 39 input checks, 236 native geometry/collision
  checks, 3,822 aim checks, 37 catalog checks, 627 Anchor/Double Anchor checks,
  and 35 physical-key/catalog checks.
- Negative controls against the prior coordinator fail for duplicate charge,
  rejected replacement continuation, invalid target, and held-input restart.
  The support fixture implements the supplied AirLiquidPlace current-state
  predicate without depending on historical exceptions.
- Obfuscation, payload boundaries/split/provenance, protected bootstraps, Java
  verification, DPoP and disposable authentication checks passed.
- Live health, all 13 deployed artifact hashes and both protected payload
  endpoints verified. Unauthenticated manifest/payload requests return HTTP 401.
- Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. Authentication
  code, launcher identity and persistent storage configuration are unchanged.

QA: `build/module-qa/20260927T025745Z-44c73c93/result.json`.
Regression evidence: `build/module-qa/double-anchor-air-liquid-regression/`.
Release: `build/module-qa/anchor-support-release-20260926/receipt.json`.
Live: `build/module-qa/anchor-support-release-20260926/live-after.json`.
Compatibility: `build/module-qa/anchor-support-release-20260926/support-compatibility.json`.

Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:5386a5a8bafb8e0c4a49b51171e1048a43b645fae8d4874de294127bb56dfe66`.

## Remaining limit

This exact build has not been tested in an authenticated local Minecraft world.
Automated support/timing checks do not establish live Grim/server acceptance.
Delayed server updates may cause Double Anchor to cancel the second sequence.
