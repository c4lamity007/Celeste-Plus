# Double Anchor Macro

Enable **Anchor Macro** and **Double Anchor Macro** in the Crystal tab. Choose a
**Hold** key in Double Anchor Macro, then hold it when starting an Anchor Macro
cycle with right-click. The ordinary **Bind** setting still toggles the module.
Both keys start unbound; the new module starts disabled.

Each press-and-hold of the modifier allows one double sequence. After that pair,
keeping the modifier held uses normal Anchor Macro cycles. Release and press the
modifier again to allow another pair. Releasing the modifier does not change a
cycle already in progress; releasing right-click cancels it.

## Timing

With an anchor already selected and Anchor Macro's two-tick chance at zero:

| Tick | Selected item | Right-click action |
| --- | --- | --- |
| 1 | Anchor | Place first anchor |
| 2 | Glowstone | Charge first anchor |
| 3 | Anchor | Detonate first anchor |
| 4 | Anchor | Place second anchor |
| 5 | Glowstone | Charge second anchor |
| 6 | Totem | Detonate second anchor |

The two-tick swap chance comes from **Anchor Macro**. At 100%, the same sequence
has use actions on ticks **1, 3, 5, 6, 8, 10**: the swaps before charging and
detonation get their extra tick. The replacement always uses the tick immediately
after the first detonation, including when its anchor moves to another hotbar slot.
Double mode uses one glowstone click per anchor regardless of **Double glowstone**.
The base module's silent-swap and hold-to-loop settings still apply.

## Interaction handling

All actions use Anchor Macro's existing input lease, slot ownership and primary
action scheduler. There is no second competing click loop or operating-system input.

The first explosion does not immediately remove its block from Minecraft's client
world. Replacement still uses the original valid placement surface on the next
tick. If local prediction reports `FAIL`/`PASS`, or still shows the old charged
anchor, the macro waits within its four-tick confirmation window for an actual
uncharged replacement. It then continues with glowstone and totem. The replacement
click is never repeated. A stale charged anchor, air or liquid is not confirmation;
timeout cancels the continuation. The macro never changes the world to manufacture
that evidence.

Each charge click requires a current uncharged anchor; final use requires a current
charged anchor. These conditions are rechecked after planning, before the action.
The first accepted charge is not retried after a delayed or rolled-back update.
Lost initial targets and confirmation timeouts cancel without starting another
sequence under the same held click. Ordinary Anchor Macro also checks its current
anchor target before charge and detonation.

Consequently, six actions occur at the stated cadence when native placement and
world state are valid. Delayed replacement confirmation can delay the last two
actions; a timeout still stops the continuation. The next-tick replacement is not
replayed later to compensate for that delay.

The macro checks item availability, reach, world border, loaded chunks and the
target's anchor behavior. It stops on menu/focus/player/world changes or manual
inventory/input takeover. Sneaking and offhand glowstone pause the double sequence
because vanilla would interpret the clicks differently. New placements require two
anchors, two glowstone and a real hotbar totem. An existing anchor needs a known or
safely inferred replacement surface; interactive supports are not inferred.

If input ownership, support or inventory changes prevent the replacement on its due
tick, the continuation is cancelled rather than replayed late. The timing contract
is for client action scheduling; server acceptance and network arrival timing are
outside the macro's control.

An automatic or manual totem selection that interrupts a visible sequence is
preserved. Cancellation keeps the held and queued Use input consumed until
release, preventing a stray native totem click. A competing silent-slot owner
also cancels the pair without restarting ordinary Anchor Macro on the same press.
Cleanup after an attempted native interaction defers silent-slot restoration
until the next tick, including exceptions and same-tick disable callbacks.

## Verification

Replacement-confirmation correction: `celeste-plus-20260926-anchor-packets-01`.
The combined build and all ten stages passed, including 1,209 Double Anchor
checks. Saved previous source reproduces the four-action stop. Cases cover
immediate and 1/2/3-tick confirmation after `FAIL`, `PASS` and `CONSUME`, timeout,
late updates, single replacement click, and unchanged slot-delay settings.
Protected packaging, auth and live artifact/endpoint verification passed.
See [release notes](releases/20260926-anchor-packets.md).

Run `module_qa.py check --module "Anchor Macro" --module "Double Anchor Macro"
--suite double-anchor --java-home <JDK21>` for the incremental build, real coordinator
and scheduler behavior checks, and physical-key/catalog checks.

Support/charge correction run: `build/module-qa/20260927T025745Z-44c73c93/result.json`.
The build and all nine QA stages passed, including 627 Double Anchor behavior
checks. Negative controls against the prior coordinator reproduce rejected
placement continuation, invalid support, target-loss restart, and the extra first
glowstone charge. The corrected coordinator stops each case while preserving
normal six-stage cadence and one pair per modifier hold.

Earlier input correction run: `build/module-qa/20260927T023727Z-7f8c5a61/result.json`.
The combined incremental build and all checks passed, including 320 Double Anchor
checks against the production coordinator, scheduler and slot manager, plus 35
physical-key/catalog checks. Cases include repeated RMB presses during one H
hold, Hold to loop, H release/repress, interruption, and actual slot/use traces.
Both zero and 100% swap-delay settings retain the exact six-step item order and
one-tick replacement. The trace clears its use flag only at a movement boundary.

Initial module receipt: `build/module-qa/20260927T002908Z-2ff17696/result.json`.
The incremental build, 162 coordinator/scheduler checks and 30 physical-key/catalog
checks passed. Additional checks passed for the QA workflow (40) and existing GUI
geometry/text handling (6,814).

Source fingerprint: `2ce747016f7049cf178f91dba38912b1206b081d097d5706585960c79fc1b420`.

Automated checks are not live Minecraft evidence. No edited candidate was loaded
in an authorized local world during this task; live animation and server interaction
timing remain unverified.

The original version was published with Safe Anchor as `celeste-plus-20260926-safe-anchor-01`. The combined
release QA includes 164 Anchor/Double Anchor checks and the shared input-ownership
regression. See [release notes](releases/20260926-safe-anchor.md).
The once-per-hold and cancellation corrections are published as
`celeste-plus-20260926-anchor-fixes-01`. See [correction release notes](releases/20260926-anchor-fixes.md).
The support validation, failed-replacement and duplicate-charge corrections are
published in `celeste-plus-20260926-anchor-support-01`.
See [placement release notes](releases/20260926-anchor-support.md).
