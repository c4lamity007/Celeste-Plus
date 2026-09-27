# Fake Gamble local items

## Behavior

- Dispensed local items now use Minecraft's default dropped-item pickup delay (10 ticks). Thrown items wait 40 ticks; mined drops wait 10 ticks.
- Pickup uses Minecraft's collection particle and sound, preserves the uncollected remainder of a partial stack, and only lets the local player collect local items.
- Local ownership is independent of the visible gamble number. Placing and mining a prize removes its custom name; the resulting item remains local.
- Block prizes use `BlockItem.place` directly for placement state, collision checks, orientation, placement sound, and stack consumption. Placement writes are tracked in a bounded local transaction.
- Local mining uses the block's vanilla breaking delta, tool eligibility, crack stages, hit/break sounds, particles and hand animation. It does not damage a real held tool.
- Finishing local mining keeps the held gesture local until release, preventing the next held tick from starting a real break behind the vanished ghost.
- Repeated server updates containing the unchanged original state preserve the local overlay, including native collision and crouch-edge support. A genuinely changed server block wins. Disable/world changes restore only tracked states that still match their local overlay; chunk unloads remove affected local state.
- A normal main-hand right-click opens a real dispenser/dropper before fake held-item placement. Sneaking with a fake item retains local placement behavior.

## Interaction isolation

Startup mixins and Celeste+ injection hooks intercept owned local placement, mining, item/entity use, selected-stack drop, and offhand swaps before their corresponding server actions. This does not pause or buffer general networking.

Real items take priority over stale local inventory snapshots. Local actions do not overwrite or relocate real server stacks. Whole-stack throwing, taking the last item with right-click, compatible stack merging, and unnamed pickup-all handling have focused regressions.

## Auto Tool and Auto Double Hand

Auto Double Hand visibly selects a hotbar totem. Auto Tool visibly selects a tool with **Silent swap off**; its existing silent setting keeps visible selection unchanged. Earlier fixes change restoration: a newer manual selection becomes the restore target. They do not remove automatic selection.

Auto Tool's visible mode also recognizes Fake Gamble's local mining. A hidden server slot lease cannot change a local ghost's mining speed, so silent mode does not acquire one for ghost mining.

## Fidelity limits

The default iron/diamond ore drops honor harvesting, Silk Touch and Fortune. Arbitrary configured blocks fall back to their placed item instead of evaluating a server's loot tables. Placements that would reshape nonreplaceable neighbors outside their target (such as connected fences/stairs) are rejected and rolled back; this avoids incorrectly treating a neighboring real block or separate ghost as part of the new placement. Multipart placement into air/replaceable cells and target slab/layer merges remain supported. Local fake items also remain separate from real inventory stacks even when their visible names match. These are not a claim of complete parity with server-owned items.

Actual motion, rendering and packet behavior still require a local-world check with the edited candidate loaded. No matching Minecraft/Celeste window was available during this task. Automated fixtures are not in-game evidence.

## Verification

The focused runner is `module_qa.py check --module "Fake Gamble" --module "Auto Tool" --suite fake-gamble --java-home <JDK21> --runtime-classpath-file <resolved runtime jars>`.

Final evidence: `build/module-qa/20260926T042035Z-0ccd4d2a/result.json`.

- Incremental build passed (20.24 seconds).
- Selection: 129 checks; Auto Tool/MLG: 49; local items: 136; ghost blocks: 279; named/intermediary and combined hooks: 5,940.
- Additional focused inventory checks: 86; QA workflow checks: 37.
- Source SHA-256: `e254d4f21625c75ee0f4b60e3ada3ca3dbd243f7b359ab59048a819ed3b77c2f`.
- Built JAR SHA-256: `0ad0d9216df3c7ad29bcfb6285b59815bd1ab3561d43b96a0e7f57f83246f046`.
- Runtime status: **blocked**; overall verification: **partial**. No edited candidate was loaded for live pickup/mining animation or packet verification. No game screenshot is claimed.

Published as `celeste-plus-20260925-fake-gamble-02` after additional hook frame
fixes and release verification. See [release notes](releases/20260925-fake-gamble.md)
for the final QA receipt, deployment evidence and Minecraft restart requirement.

The pickup delay, dispenser interaction, inventory transfer and collision follow-up
is documented in [September 26 follow-up](fake-gamble-fidelity-20260926.md).
Those later source changes are not part of release `fake-gamble-02`.
