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

Prepared and protected; production rollout verification is pending.
