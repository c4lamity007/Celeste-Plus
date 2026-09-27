# Anchor cleanup and Double Anchor completion

Published: `celeste-plus-20260926-anchor-packets-01`.

## Changes

- Safe/Silent Safe Anchor preserve the approved placement, activation, aiming,
  timing, dropped-item and flame behavior. After a native interaction, failed or
  cancelled actions keep the outgoing look through vanilla movement and defer
  silent-slot restoration. Cancellation before a click remains immediate.
- Global rotation cleanup handles a module disabled before its END callback.
  Cancellation in render frames after END still releases immediately.
- Double Anchor keeps its replacement click exactly one tick after the first
  explosion. If local prediction still shows the exploded anchor, it waits within
  the existing four-tick confirmation window for a real uncharged replacement,
  then performs the final glowstone and totem actions. It never repeats placement
  or invents block state. Each anchor receives one charge.

## Verification

QA receipt: `build/module-qa/20260927T041012Z-cb75b615/result.json`.
Incremental build and all ten stages passed, totaling 6,674 focused checks:
604 coordinator, 642 slot-order, 39 input, 210 geometry, 3,822 aim,
76 rotation transport, 37 catalog, 1,209 Double Anchor, and 35 Double input checks.
The QA runner's 42 tests also passed. Saved prior sources reproduce the premature
rotation release and the four-action Double Anchor stop.

Source fingerprint:
`dfaa5018dcde5e63cb5bef6e9f0a1dd6553489c545a0041c428a42439c192c0c`.
Source JAR SHA-256:
`e2bfc87c266d5737c21d3d97d6265a143ccbb2f99f7296f9dc96b8548880b993`.

The supplied PacketOrderE right-click boundary and RotationPlace post-movement
look candidates are modeled with production coordinator, slot, rotation and hook
code. This is not a live Grim test. RotationPlace's pre-flying branch can still
reject a changed target when a recent flag has activated its buffer, before the
next movement packet arrives. The approved START timing remains unchanged.

The exact candidate has not been loaded in an authenticated local QA world;
live server acceptance and animation remain unverified.

## Publication

Published and verified at `2026-09-27T04:18:57Z` (Sept 26, 9:18 PM Pacific).
Payload/bootstrap protection, provenance, JVM verification, DPoP and auth smoke
checks passed. Live verification matched 13 artifacts, both authenticated payload
endpoints, persistent storage and the existing launcher identity. Normal Celeste
remains `celeste-20260922-bootstrap-verifyfix-01`.

Compiled comparison of 20 classes confirms the original Safe Placement, Aim and
Feature classes are byte-identical, including their nested classes. The protected
package source stage matches the tested candidate.

All resident support libraries and canonical bridges match `safe-anchor-original-01`.
After a successful Stop/detach, re-inject Celeste+ to load the new payload; a
Minecraft restart is not required for that prior release. This compatibility
comparison is not an observed in-game re-injection test.

Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:78bf52ffa6f3e1a5b1ad46651093b1ad797e1b8d8f326d66a31fe377d969dcf6`.
Release evidence: `build/module-qa/anchor-packets-release-20260926/receipt.json`.
