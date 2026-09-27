# Fake Gamble follow-up — September 26

This follow-up addresses pickup delay, opening dispensers with a fake held item,
inventory shift-click behavior and physical support from placed ghost blocks.

## Changes

- Dispensed local items call `ItemEntity.setToDefaultPickupDelay()` (10 ticks in
  Minecraft 1.21.11). Thrown items retain 40 ticks and mined drops retain 10.
  Minecraft decrements the delay on client ticks and takes the larger delay when
  merging drops. Collection still uses the native pickup particle and sound.
- Normal main-hand right-click on a real dispenser/dropper reaches vanilla block
  interaction before local item placement. Sneaking with an item keeps placement
  priority. Ghost targets and invalid reach, border or unloaded-chunk targets
  cannot use this path to send a real placement action.
- Routine updates containing the original server block state restore the tracked
  local block into client world storage. This also handles restored prediction
  state on the next module tick. A different server block state retires the ghost;
  unload, disable and world changes retain their cleanup behavior.
- Native Minecraft collision shapes supply side collision, landing, jumping and
  crouch-edge/corner support. No replacement player movement algorithm or general
  packet buffering is added.
- Real shift-click transfers in player, dispenser, chest, hopper and shulker
  layouts use ordinary predicted pickup clicks with explicit destinations. They
  skip local slots, merge compatible real stacks first, honor slot restrictions,
  retain armor/offhand equip priority and return untransferred items to the source.
- Local dispenser shifts use vanilla container slot order, preserve the source
  remainder and leave real dispenser items in place. A real server slot invalidates
  only its own stale local snapshot. Local cursors and hotbar/offhand swaps cannot
  replace that real item.

## Verification contract

Check the delay before collection, dispenser opening versus sneak placement,
real/local inventory transfer with occupied and full destinations, repeated server
block refreshes, disable/reset, and chunk unload. Use the existing `fake-gamble`
QA suite, which now also includes dispenser routing and native physics checks.

The physics harness uses unmodified Minecraft collision and sneak method bodies
with a test world storage adapter. Its separate classloader changes access flags
required by Loom's named Minecraft jar; it does not rewrite those method bodies.
This is automated engine coverage, not a live game session.

Final QA receipt: `build/module-qa/20260926T233143Z-9c2a3768/result.json`.
The incremental build and all seven recorded steps passed: selection (129),
movement (49), local items (166), ghost service (301), native physics (29),
dispenser routing (52), plus named/intermediary and combined injection hooks.
The QA workflow's own 37 checks also passed.

Source fingerprint: `842a0762630ea9fc27625a89ef3f1621e4559291b171760affbdf0cd0776072b`.
Source JAR: `13de830a7331252d2ea6e8a14fdcb297fe23a43206242eb844408084f9230229`.

## Runtime limits

The current open Minecraft window was identified as a multiplayer session. The
edited local candidate was not loaded through the normal signed launcher flow,
so live pickup animation, inventory networking and scene motion remain unverified.
No screenshot from the older session is presented as evidence for these changes.

Client-only blocks remain subject to server position corrections. Arbitrary
server loot tables and connected-block placement limitations described in the
earlier implementation notes still apply; complete server-item parity is not
claimed. Crafting-output and unsupported special-screen shift transfers are
blocked while fake slots exist, because their recipe/trade side effects cannot
be reproduced safely by these ordinary pickup transfers. Without fake slots,
those actions follow vanilla behavior.

Published as `celeste-plus-20260926-fake-gamble-03` and verified at 23:38 UTC on
September 26. See [release verification](releases/20260926-fake-gamble.md).
