# Double Anchor Macro: five-tick sequence

Release: `celeste-plus-20260927-double-anchor-01`.

## Changes

With the swap-delay chance at zero, the sequence is:

1. Anchor + use.
2. Glowstone + use.
3. Anchor + use + use, exploding and replacing at the original position in one tick.
4. Glowstone + use.
5. Totem + use.

The replacement no longer searches for an adjacent support. Delayed client block
updates do not postpone the last two uses or cause repeated glowstone clicks.
The configured two-tick swap chance still applies to whole swap stages; it never
splits the two uses in step 3. Each modifier hold allows one double sequence,
followed by ordinary Anchor Macro cycles until the modifier is released.

Safe Anchor and Silent Safe Anchor sources are unchanged. The existing protected
Windows loader and its server allowlist remain the approved native release.

## Verification

- 336 behavior checks exercise the actual coordinator, scheduler and slot manager,
  including unsupported placement, delayed block updates, 0/100% swap delay,
  visible/silent slots, one pair per hold, looping and cancellation.
- The prior coordinator fails the same-tick replacement assertion.
- The game module's incremental `:build` passed. The inherited unqualified `build`
  also selects an unused legacy bootstrap loader; that target still fails on
  missing auth imports and was not used to package this release.
- All 18 protected Java release stages passed, including 783 payload declaration
  checks, 111 bootstrap helper checks and 442 protected bootstrap checks in each
  of three fresh JVM runs. The authentication ABI repair is retained.
- All 188 runtime overlay files and the locked manifest were verified before
  building. The built coordinator contains the new combined explode/replace action.
- Auth delivery tests using the actual protected catalog passed 95 checks, with
  one Windows symlink-privilege skip. The tested backend matches the live source.

The behavior fixture models Minecraft prediction and server handling. It is not
live gameplay or anticheat acceptance evidence. This exact release still needs
an authenticated in-game check.

## Artifact identity

| Item | SHA-256 |
| --- | --- |
| Remapped runtime input | `3312f7a7baa04eec9e5de3007357dd48438488c314c7f1f8153dbc2b0541685c` |
| Protected runtime | `73165646befbc0d53a546e32ac744419cb52c591cf7f640f7d63fd57804d6165` |
| Protection receipt | `cda00b5ef53a4dfcf8740ea36804fa207d4cdc000faea0184b7a9fe0c5865764` |
| Release catalog | `9ab78066c29d3d4f448ce54c7247ecd5caa56ce63d5501ef819fbfd55846c600` |

Evidence directory: `build/module-qa/java-protected-candidate-20260927-double-anchor-01/`.
Generated artifacts, mappings and local evidence remain ignored.

## Publication

Published on 2026-09-27 at Fly version **131**. Implementation commit:
`47f3307b0c07a144f6a0d97660e6f9990cbc6822` in `c4lamity007/Celeste-Plus`.

Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:36a491e47ccf51e9327d1d6c7073f04fda71a13791f0370e240492756b7327ba`.
The machine passed its health check. Live verification matched all five new
catalog files, preserved all 23 existing artifacts and all 21 backend source files,
and verified signed native manifests, fresh nonce binding and unauthorized request
rejection. Native policy remains sequence 1 for the existing approved loader.

Only the active Plus release directory changed. Standard Celeste remains
`celeste-20260922-bootstrap-verifyfix-01`; the database mount, backend and server
verification keys remain unchanged.

Live evidence: `build/fly-rollout-20260927/double-anchor-live-verification.json`,
SHA-256 `e4ce253fd837a619f1f30dc1a591ad692282877f20309e20224e482c30f6325f`.
Actual-catalog test evidence: `build/module-qa/double-anchor-release-catalog-review/result.json`.

Use the existing Celeste+ loader. Fully restart Minecraft, then load Celeste+ to
start with the new bootstrap and payload. The earlier user-confirmed injection
success applies to the preceding auth repair; live gameplay for this new sequence
remains unverified.
