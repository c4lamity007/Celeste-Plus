# Celeste+ anchor fixes — published

Release: `celeste-plus-20260926-anchor-fixes-01`.
Published and verified September 27, 2026 at 02:44 UTC (September 26, 7:44 PM Pacific).

## Changes

- Safe Anchor and Silent Safe Anchor consume held and queued Use before validating
  recognized starts. Failed attempts retain input until release. Cleanup after a
  native interaction defers slot restoration until the next tick.
- Glowstone can replace normal fire and soul fire using the flame's real outline
  and native placement validation.
- Double Anchor allows one pair per modifier hold, then normal Anchor Macro
  cycles until the modifier is released and pressed again. Its action items stay
  anchor, glowstone, anchor, anchor, glowstone, totem. The replacement is exactly
  one tick after the first explosion, independent of the two-tick swap chance.
- A totem/manual selection or competing silent slot owner cancels the pair while
  keeping Use consumed, preventing an extra native click. Same-tick cleanup
  preserves the interaction's slot through its movement boundary.

## Update

From `safe-anchor-01`, use a successful **Stop**, then inject again. Six resident
support JARs, 32 canonical bridges and 32 transformer precursor classes match.
Restart Minecraft if cleanup fails/requires restart or incompatible older support
remains. Live reinjection was not exercised.

## Verification

- Build and all nine recorded QA stages passed: coordinator, input, real slot
  manager, fire geometry, aim, catalog, and Double Anchor checks. QA workflow
  tests also passed. The old same-tick slot restore fails the packet-order fixture;
  the corrected code passes explicit movement-boundary checks.
- Obfuscation, payload split/provenance/boundaries, protected bootstraps, DPoP and
  disposable authentication checks passed.
- Java 21 verified 24 baseline/prepared hook targets and 440 visible plus 70
  implementation protected Bootstrap A classes.
- Live health, all 13 deployed artifact hashes, and both protected payload
  endpoints passed. Unauthenticated manifest/payload requests return HTTP 401.
- Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. Authentication
  code, launcher identity and persistent storage configuration are unchanged.

QA: `build/module-qa/20260927T023727Z-7f8c5a61/result.json`.
Release: `build/module-qa/anchor-fixes-release-20260926/receipt.json`.
Live: `build/module-qa/anchor-fixes-release-20260926/live-after.json`.
Compatibility: `build/module-qa/anchor-fixes-release-20260926/support-compatibility.json`.

Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:4abb21f46e510fb847d16434467f95d5f1d669ae268b3526d5eb402b32831bf3`.

## Remaining limit

This exact build has not been tested in an authenticated local Minecraft world.
Automated timing and packet-order checks do not establish live Grim/server
acceptance or game animation timing.
