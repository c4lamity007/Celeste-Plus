# Safe Anchor / Silent Safe Anchor

Both modules live in **Crystal** and work with Anchor Macro disabled. Enable one,
hold right-click while aiming at a placement surface, and keep an anchor, two
glowstone and a totem in the hotbar. The normal module Bind toggles enablement.

**Safe Anchor** shows its turns in the camera. **Silent Safe Anchor** keeps the
camera unchanged and uses the same `STRICT` movement correction as Kill Aura's
Default mode. If both are enabled, Silent Safe Anchor takes the next cycle; an
already-running cycle keeps its selected presentation.

## Sequence and timing

With an anchor selected and **2 tick swap chance** set to zero:

| Client tick | Action |
| --- | --- |
| 1 | Select anchor and place it at the crosshair |
| 2 | Select glowstone and charge the anchor once |
| 3 | Place glowstone between the player and the anchor |
| 4 | Select totem and detonate the visible anchor |

The extra-tick chance applies to actual item-slot changes, just like Anchor Macro.
At 100%, the same starting inventory produces actions on ticks **1, 3, 4, 6**.
Charge and shield placement share the glowstone slot, so there is no artificial
swap delay between them. Starting on a different item may also delay the initial
anchor placement. The chosen delay is sampled once per stage.

Each next target is chosen after the previous action. The turn interpolates on
render frames across its one- or two-tick window, with a stable randomized curve
and a raycast-valid face point. The action tick resolves the exact endpoint even
when no frame was rendered between ticks. Frames never emit use actions. The
existing rotation manager, input lease, silent-slot manager and primary-action
scheduler perform the original sequenced interactions. When cancellation follows
an attempted interaction, its look survives through vanilla movement and its
silent slot restores afterward. Cancellation before an action remains immediate.

Four ticks describes a valid local sequence at zero swap delay. An invalid or
missed action cancels the sequence instead of being retried on a later tick.
Server acceptance, network delay and live animation timing require game testing.

## Placement and cancellation

- Checks the eight horizontal neighbors of the anchor at its height and one
  block above/below. Cover must lie between the anchor and the player's body.
- Uses reachable, visible native placement surfaces, including uneven terrain;
  excludes interactive supports and the anchor itself, which would recharge it.
- Checks native placement rules, entity collision, fluids, build limits, world
  border and loaded chunks.
- Can replace normal fire or soul fire with glowstone. It targets the flame's
  real outline when the flame blocks the support beneath it, and validates the
  native placement destination again before clicking.
- Initial anchor placement accepts inert supports and normal fire/soul fire,
  using Minecraft's placement context to replace the flame. Dropped
  items are exempt from the existing entity blocker checks for both anchor and
  glowstone placement. All other live non-spectator entities still block the
  destination, and Minecraft's normal collision check still runs. Occupancy is
  rechecked before the action. Normal target selection, scoring, timing and aim
  match the original `safe-anchor-01` release.
- Raycasts anchor faces with the proposed full glowstone cube included. Rejects
  cover that leaves no reachable view of the anchor.
- Checks the actual world again after shield placement, including between frames
  and at the final action. If the anchor is hidden, the attempt is abandoned and
  aim stops. That anchor is not automatically resumed on another press.
- Every action rechecks the required stack, target and ownership. Missing items,
  changed support, manual hotbar/input takeover, lost aim ownership, menus, lost
  focus, death, world/player changes or a missed action deadline stop the attempt.
- Activation, manual-anchor ownership tracking, held-input handling, looping and
  cancellation decisions match the original release. An eligible charged anchor that cannot
  be used safely consumes the hold; unrelated or rejected unowned starts retain
  the original native input behavior. The later activation and hold changes are
  removed at the user's request.
- Cleanup after an attempted native click, including `FAIL`, `PASS` or an
  exception, retains its look through the following movement packet and defers
  silent-slot restoration. Global cleanup also handles same-tick disable. Render
  cancellation after END remains immediate. This changes cleanup only.
- Sneaking, offhand glowstone and dimensions/positions where anchors set spawn
  points are excluded because their native right-click behavior differs.

Settings match Anchor Macro: **Auto swap anchor**, **Only own anchors**,
**Hold to loop**, **Silent swap**, and **2 tick swap chance**. Safe variants use
one charge and one shield placement; they do not inherit Double Anchor Macro or
the base macro's Double glowstone option. With Auto swap anchor off, select an
anchor first; the first placement is native and the original coordinator observes
and adopts it for the remaining sequence. Existing anchors require ownership
unless Only own anchors is off.

## Verification

Current cleanup correction: `celeste-plus-20260926-anchor-packets-01`.
The incremental build and ten stages passed, including 642 slot-order and 76
production rotation transport checks. Compiled comparison preserves the original
Placement, Aim and Feature classes byte-for-byte. Protected packaging, auth and
live artifacts/endpoints passed. See [release notes](releases/20260926-anchor-packets.md).

Use `module_qa.py check --suite safe-anchor` for the current coordinator, including
slot ordering and rotation transport checks. The historical `safe-anchor-original`
profile records the requested rollback before these cleanup corrections.

The supplied `RotationPlace` checks the outgoing current, mixed and previous
look against the clicked block after movement. The cleanup regression verifies
these candidates using the production rotation manager and outgoing hooks. Once
that check has a recent flag, its pre-flying branch can reject a new target before
the movement packet carrying that target's look arrives. This limitation remains
with the approved START action timing; automated checks do not certify live Grim
compatibility.

Historical original-profile verification covered the restored coordinator,
input, geometry, timed-aim, catalog and Double Anchor contract. At that snapshot,
the separately retained `safe-anchor-slot-order` test failed on the restored
cancellation behavior. That snapshot did not certify PacketOrderE compliance.
The current full suite requires the stricter test to pass. Geometry and aim
checks compile against the mapped Minecraft classes.

Original-flow restoration with items and flames:
`build/module-qa/20260927T033756Z-07269d43/result.json`.
The incremental build and eight applicable stages passed: 604 original runtime
checks, 39 input lease, 210 geometry, 3,822 aim, 37 catalog, 627 Anchor/Double and
35 physical-key/catalog checks. All 42 QA runner tests passed.
Compiled comparison against `safe-anchor-01` confirms the original activation,
hold, ownership, cancellation and aim methods. Only item/flame placement methods
differ; current Double Anchor classes are unchanged.
Source fingerprint: `af00be2218c5cc3a72384c11a38abc5fdaf80edadbb2b96d7bc6437949f95b02`.
The unchanged later slot-order test was run separately and failed as expected
for the original cancellation path after charging with no available cover.
Evidence: `build/module-qa/safe-anchor-original-parity/comparison.json` and
`build/module-qa/safe-anchor-original-slot-limit/evidence.json`.

Historical held-input correction (subsequently reverted by request):
`build/module-qa/20260927T032620Z-a462bb9f/result.json`.
All nine stages passed, including 1,439 runtime checks with the production
coordinator, scheduler and input lease. Old-source negative controls fail for
both rejected existing-anchor input and a stale target between held loops; the
modeled vanilla input phase emits an extra interaction with the old coordinator.
The correction passes these cases, valid second-loop recovery and physical
release, while the 210 native geometry and 562 slot-order checks still pass.
Source fingerprint: `b588d2c8551d5e7b512263167c74527ebc6eda494d553aef8f77472e6a520881`.
Compiled parity confirms only `onStartTick`, `start` and `recognizedActivation`
changed; Placement, Aim, Feature and all Crystal/Double classes are byte-identical
to `safe-anchor-rollback-01`.
Evidence: `build/module-qa/safe-anchor-held-input-fixed/` and
`build/module-qa/safe-anchor-hold-parity/comparison.json`.

Placement rollback with dropped-item exception:
`build/module-qa/20260927T031251Z-04db97dd/result.json`.
The incremental build and all nine stages passed: 894 coordinator checks, 562
slot-order checks, 39 input checks, 210 native geometry checks, 3,822 aim checks,
37 catalog checks, 627 Anchor/Double Anchor checks and 35 physical-key/catalog
checks. Adding dropped items preserves the seeded shield position and both aim
targets. Other live non-spectator entities remain excluded and native collision
checks remain enforced. Both before-queue and action-time occupancy are covered.
Source fingerprint: `1a62642f140c0ebdc4bca6ac6d29c137b1dd89e886d236fee4ff5046a88c2dfd`.
Compiled parity review compared nine Safe classes with `anchor-fixes-01`: only
the two entity predicate methods differ, each adding the dropped-item exemption.
All eight Crystal/Double classes are byte-identical to `anchor-support-01`.
Evidence: `build/module-qa/safe-anchor-rollback-parity/comparison.json`.

Historical item/fire placement run (placement extension subsequently reverted):
`build/module-qa/20260927T025745Z-44c73c93/result.json`.
The build and all nine QA stages passed, including 814 coordinator checks, 236
native geometry/collision checks, and the existing 562 production slot-manager
checks. Native dropped-item collision behavior and the explicit player-occupancy
policy are exercised alongside direct flame and support-face placement.
Source fingerprint: `fd016e655044773e95ce3a05cdaade7909e3d9b8280a7c3601eb95c16136594b`.

Earlier input correction run: `build/module-qa/20260927T023727Z-7f8c5a61/result.json`.
The incremental build and all checks passed: 654 coordinator checks, 562 checks
with the production slot manager, 39 input lease checks, 194 geometry checks,
3,822 aim checks, 37 catalog checks, 320 Anchor/Double Anchor checks and 35
physical-key/catalog checks. The QA workflow's 42 tests also passed.
Source fingerprint: `751c36c3e7a9d193a247572aeb59afb3d6ee65bd3f5265b925547f81abf4c14f`.
The packet-order regression reproduces the old charge/cancel sequence as
`HELD → USE → HELD` before movement and verifies deferred restoration in the fix.

Original release automated run: `build/module-qa/20260927T005958Z-de59627d/result.json`.
Incremental build passed, with 304 coordinator/scheduler checks, 165 native
geometry checks, 3,822 timed-aim checks, 37 catalog checks and 164 neighboring
Anchor/Double Anchor checks. The QA workflow's own 42 tests also passed.
Source fingerprint: `7da8e977adcff56ac0c1bef8d22f35720e7347d82654922289029589fa63951b`.

Live verification is pending. The observed Minecraft window is a multiplayer
session, and the edited candidate has not been loaded in a local QA world.
No game screenshot or live timing claim is supplied.

The original version was published as `celeste-plus-20260926-safe-anchor-01`; protected packaging, auth and
live artifact/endpoint verification passed. See [release notes](releases/20260926-safe-anchor.md).
The input, cancellation and fire-placement corrections are published as
`celeste-plus-20260926-anchor-fixes-01`. Protected packaging, authentication and
live artifact/dual-endpoint checks passed. See [correction release notes](releases/20260926-anchor-fixes.md).
The initial flame-placement and dropped-item/player-occupancy extension was
published in `celeste-plus-20260926-anchor-support-01`.
See [placement release notes](releases/20260926-anchor-support.md).
The previous Safe/Silent placement logic is restored in
`celeste-plus-20260926-safe-anchor-rollback-01`, retaining only the dropped-item
exception and preserving the latest Double Anchor fixes. Protected packaging,
authentication and live artifact/dual-endpoint checks passed.
See [rollback release notes](releases/20260926-safe-anchor-rollback.md).
The held-input correction is published in `celeste-plus-20260926-safe-anchor-hold-01`.
Placement and aim remain unchanged, including the dropped-item exception.
Protected packaging, authentication and live artifact/dual-endpoint verification passed.
See [held-input release notes](releases/20260926-safe-anchor-hold.md).
The original behavior is restored in `celeste-plus-20260926-safe-anchor-original-01`,
with only dropped-item and flame placement additions. Later activation, hold,
ownership and cleanup edits are removed. Original compiled parity, obfuscation,
authentication and live artifacts/endpoints are verified; the original cancellation
slot-order limitation is explicitly retained and recorded.
See [original restoration notes](releases/20260926-safe-anchor-original.md).
