# Celeste+ hand visuals — 2026-09-20

Published `celeste-plus-20260920-hand-shaders-01` to the existing Fly application.
Deployment image: `registry.fly.io/qb0f90cf11f4d1d52:deployment-01M300JAMYX8117DYJ437YSBMP`.
Standard Celeste remains byte-identical to `celeste-20260920-dpop-02`.

## Included changes

- Fake Gamble uses the existing pick callback exactly once; only middle-clicked
  dispensers participate. Selected markers persist across rounds and clear on reset.
- Hand Models adds independent physical-left/right dragging of real first-person
  models, saved normalized positions, reset, mirrored inward yaw and a fixed tilt.
- Hand Shaders offers Particles (default), Outline, and Glow. Every mode preserves
  the original held-item texture. Particles are frame-timed analytical glow motes
  constrained to the current held-model silhouette, not world entities or a flat fill.
- The entire deferred hand draw/flush is captured together against private depth,
  then composited once. Glow uses two half-resolution passes; particles use a
  bounded nine-emitter neighborhood. Disable/reload/disconnect release resources,
  and capture cleanup restores the original render targets even on exceptions.

## Delivery and verification

Existing compatible obfuscation profiles protected the payload and both bootstrap
implementations. The release retains Bootstrap A, Bootstrap B, and two distinct
payload endpoints. The updated Java hook implementation is delivered in Bootstrap A;
the current Celeste+ Windows launcher does not need replacing. Restart Minecraft
before loading this release because the shared resident bridge has changed.

The incremental build, hand layout/pose tests, Fake Gamble selection checks,
362 visual hook checks, 4,734 combined hook checks, and production GLSL GPU checks
passed. GPU checks cover visible animated exterior motes, original material,
moving masks, seams/cutouts, opacity, resizing and premultiplied-alpha fallback.
The preview uses a real vanilla sword texture in a synthetic offscreen pose;
it is not an in-game screenshot. Actual gameplay/Sodium visual QA remains unverified.

Protected artifact/DPoP checks and disposable-database delivery tests passed.
After deployment, all 13 expected standard/Plus artifact hashes matched; health
checks passed and DPoP replay storage was present. Installer channel policy,
standard release pins, signing secrets, licenses and cloud storage were not changed.

Build/GPU evidence: `build/module-qa/20260920T181400Z-5a923016/`.
Protection, staging, pre-deploy snapshot and live verification:
`build/module-qa/hand-shaders-release-20260920/`.

Rollback only if needed: use that directory's saved `rollback-fly.toml` and the
pre-deploy v93 image recorded in `live-before.json`, on the same Fly machine.
No secret or database rollback is needed for this visual-only release.
