# Celeste+ Safe Anchor and Double Anchor — published

Release: `celeste-plus-20260926-safe-anchor-01`.
Published and verified September 27, 2026 at 02:18 UTC (September 26, 7:18 PM Pacific).

## Changes

- **Silent Safe Anchor** and **Safe Anchor** are independent Crystal modules.
  They place an anchor, charge it, place glowstone cover, and detonate with a
  totem. The valid zero-delay sequence takes four client ticks.
- Cover planning checks eight directions and height choices, placement rules,
  reach, collision and remaining anchor visibility. Randomized frame-based turns
  reach the target on the scheduled action tick. Silent mode uses Kill Aura's
  default `STRICT` movefix. Invalid stages and unsafe charged starts cancel.
- **Double Anchor Macro** modifies Anchor Macro while its Hold key is down.
  The first explosion is followed by replacement exactly one client tick later;
  the other actual swaps follow the existing two-tick chance.
- Crystal macros recheck exclusive held-use ownership before submitting or
  executing actions so the Safe Anchor sequence cannot double-fire with them.

Behavior and limits: [Safe Anchor](../safe-anchor-20260926.md),
[Double Anchor Macro](../double-anchor-macro-20260926.md).

## Update

From `fake-gamble-03`, use a successful **Stop**, then inject again. Six resident
support JARs, 32 canonical bridge classes and 32 transformer precursor classes
match the previous release. Protected transformer bytes differ after obfuscation;
successful Stop removes the old transformer and restores its targets.
Restart Minecraft if cleanup fails/requires restart or incompatible older support
remains. Live reinjection was not exercised.

## Verification

- Incremental build and all six recorded QA stages passed: 304 coordinator checks,
  165 native geometry checks, 3,822 aim checks, 37 catalog checks and 164 Anchor/
  Double Anchor regressions. QA workflow tests also passed (42).
- Obfuscation, implementation boundaries, payload split/provenance, protected
  bootstrap checks and DPoP checks passed.
- Java 21 verified all 24 pristine/prepared hook targets and all 440 visible/70
  implementation protected Bootstrap A classes.
- Disposable authentication tests verified four Plus and six standard artifacts,
  including rejected replay and invalid installer digest cases.
- Live health, all 13 deployed artifact hashes and protected manifest/payload-a/
  payload-b endpoints verified. Unauthenticated protected access returns 401.
- Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. Authentication
  code, launcher identity and persistent storage configuration are unchanged.

QA: `build/module-qa/20260927T005958Z-de59627d/result.json`.
Release: `build/module-qa/safe-anchor-release-20260926/receipt.json`.
Live: `build/module-qa/safe-anchor-release-20260926/live-after.json`.
Compatibility: `build/module-qa/safe-anchor-release-20260926/support-compatibility.json`.

Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:28a5448ba1610b46e1a9601fa7cdfac90ffeaf39e051c25d2c13d9d9028a3085`.

## Remaining limit

This exact build has not been tested in an authenticated local Minecraft world.
Automated geometry and timing checks do not establish live server acceptance,
movement correction or animation timing. No multiplayer screenshot is claimed as
candidate evidence.
