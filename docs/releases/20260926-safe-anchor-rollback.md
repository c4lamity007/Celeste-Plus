# Celeste+ Safe Anchor placement rollback — published

Release: `celeste-plus-20260926-safe-anchor-rollback-01`.
Published and verified September 27, 2026 at 03:16 UTC (September 26, 8:16 PM Pacific).

## Changes

- Restores Safe Anchor and Silent Safe Anchor placement, targeting and aim from
  `anchor-fixes-01`, before the latest placement extension.
- Keeps dropped items allowed by exempting only `ItemEntity` from the two existing
  entity blocker checks. All other live non-spectator entities, including players,
  remain blockers. Native placement and collision checks still run.
- Keeps the earlier glowstone replacement of fire and soul fire. Initial anchor
  targeting uses the earlier inert-support rules again.
- Keeps the latest Double Anchor fixes, six-action flow, exact next-tick
  replacement and one double sequence per modifier hold.

## Update

From `anchor-support-01`, use a successful **Stop**, then inject again. Six resident
support JARs, 32 canonical bridges and 32 transformer precursor classes match.
Restart if cleanup fails/requires restart or older incompatible support remains.
Live reinjection was not exercised.

## Verification

- Incremental build and all nine QA stages passed: 894 Safe coordinator checks,
  562 slot-order checks, 39 input checks, 210 geometry checks, 3,822 aim checks,
  37 catalog checks, 627 Anchor/Double checks and 35 physical-key/catalog checks.
- Dropped-item coverage includes anchor and shield placement before and after
  queuing, unchanged seeded placement/aim targets, and native collision behavior.
- Compiled comparison of nine Safe classes with `anchor-fixes-01` found only the
  two intended entity predicate changes. All eight Crystal/Double classes are
  byte-identical to `anchor-support-01`.
- Obfuscation, payload boundaries/split/provenance, protected bootstraps, Java
  verification, DPoP and disposable authentication checks passed.
- Live health and all 13 artifact hashes verified. Both protected payload
  endpoints and the manifest reject unauthenticated requests with HTTP 401.
- Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. Authentication
  code, launcher identity and persistent storage configuration are unchanged.

QA: `build/module-qa/20260927T031251Z-04db97dd/result.json`.
Parity: `build/module-qa/safe-anchor-rollback-parity/comparison.json`.
Release: `build/module-qa/safe-anchor-rollback-release-20260926/receipt.json`.
Live: `build/module-qa/safe-anchor-rollback-release-20260926/live-after.json`.
Compatibility: `build/module-qa/safe-anchor-rollback-release-20260926/support-compatibility.json`.

Source JAR: `a9d5b3130355757785d99c246a14a58abe830ff81836c240da8ba0c707b49343`.
Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:c7229ed93f1a805258fc72128c72037e28469fdb59e738e66b63a65bf073e944`.

## Remaining limit

This exact build has not been tested in an authenticated local Minecraft world.
Automated checks and compiled parity do not establish live server acceptance or
confirm the reported glowstone-on-top behavior in game.
