# Celeste+ individual module audit — September 25, 2026

## Result

All **79 registered modules received a source review** of their settings,
enable/disable behavior, retained state and relevant input/action paths.
The incremental build and five focused regression suites passed. These fixes
are local and unpublished. This is **partial verification**, not an in-game
pass of all modules.

## Fixed in this pass

| Area | Corrections |
| --- | --- |
| Auto Sword | Prefer axe now checks whether the target is blocking. |
| Trigger Bot | Reaction time resets across GUI/self-shield interruptions; configured initial cooldown and shield cooldown setting are respected; interrupted axe sequences preserve manual slot changes; queued attacks recheck the live target and GUI state. |
| Sword Aura | Initial/resumed cooldown uses the configured range. |
| Catch | Action/use guards reject an open GUI. |
| Jump Reset | Pending jumps are canceled during menus, death and missing world/player state. |
| W Tap / S Tap | Cleanup restores only keys the feature actually changed. |
| Auto Pot / Throw Pot | A configured one-tick delay is honored. |
| Throw Pot / Auto Web | Physical action-key holds are tracked through suppressed input; interruptions cancel queued bursts instead of resuming them later. |
| Potion Refill | Uses an ordinary visible inventory, pauses for focus/overlay changes, and rechecks the exact screen, hover and destination before acting. |
| Auto Double Hand | Restoration follows the latest manual hotbar selection. |
| Silent Totem | Only after pop uses the local player's actual totem activation counter; moving a totem manually does not qualify. |
| Auto Tool | Visible-to-silent changes acquire the correct tool immediately after restoring the visible slot; restoration preserves the latest manual choice. |
| Auto MLG | Missing buckets/rejected pickups cannot leave recovery pending indefinitely; water is claimed while recovering so Auto Drain cannot take it; timeout/disable/world reset releases that claim. |
| Fake Gamble | Preview prizes have explicit session ownership; ordinary items named 1–9 are not classified as previews; cleanup and both screen-close paths preserve real inventory/cursor updates. |
| Shared lifecycle | Replacing the player in the same world, such as respawn, clears old targets/timers/input ownership before restarting enabled features. |
| Mode-specific controls | Item ESP box opacity, Hole ESP height, Ore ESP fill opacity, Swing Speed custom speed and Custom Fog shader/native options only appear where they apply. Saved values remain intact, including config import/export. |

## Source-review coverage

### Combat and sword: 27

Sword Aura, Auto Sword, Shield Break, Trigger Bot, Fake Lag, Mace Aura, Wind Charge,
Catch, Horizontal Pearl Catch, Mace Swap, Smash Guide, Auto Crystal Aura, Surround,
Auto Trap, Anchor Aura, Damage Render, Auto Crystal, Auto Hit Crystal, Anchor Macro,
Aim Assist, W Tap, S Tap, Jump Reset, Anti Miss, Breach Swap, Totem Hit, Lunge Swap.

Large placement/explosive sequences were reviewed at settings, lifecycle and
action-validation boundaries. Their complete world sequences were not simulated.
Mace Aura's deliberate fixed reach in Weapon only mode was not retuned.

### Inventory: 14

Auto Pot, Throw Pot, Silent Totem, Auto Extinguish, Auto Double Hand, Elytra Swap,
ReBuff Notifier, Hover Totem, Legit Auto Totem, Auto Web, Auto Drain, Potion Refill,
Fast Place, Fast EXP.

Detailed coverage:
`build/module-qa/inventory-ux-20260925/individual-module-audit.json`.

### Movement and related actions: 12

Freecam, Sprint, Safe Walk, Elytra, Auto Head Hitter, Auto Firework, Auto Tool,
Keep Sprint, Auto MLG, Web Breaker, Cover Up, Trap Save.

Review included physical versus synthetic key ownership, slot restoration,
GUI interruption, cooldown/age state, mode changes, water recovery, scan bounds,
and disable/world cleanup. No change was made to camera movement, jump timing,
placement policy or trap-warning frequency without a concrete defect.

### Visuals: 26

Player Models, Fullbright, Entity ESP, Hole ESP, Storage ESP, Block ESP,
Trajectories, Ore ESP, Arrow ESP, Item ESP, Target ESP, Jump Circles, Player ESP,
Chams, Block Highlight, Ambient Effects, Sky Lyrics, Custom Sky, Custom Fog,
Swing Speed, Hand Models, Hand Shaders, FPS Counter, Watermark, Array List,
Fake Gamble.

Detailed coverage:
`build/module-qa/visual-module-audit-20260925/coverage.md`.
The previous fog ordering and observed-only projectile trails are preserved.

## Test evidence and limits

Run: `build/module-qa/20260926T035625Z-409029d2/result.json`.

Source SHA-256:
`cd3016e5901eede0a723bfab368c83395d89575ef99ca7b2d167970f721383df`.

| Check | Result | Scope |
| --- | --- | --- |
| Incremental build | Pass | Compiles/remaps the integrated production source. |
| Module lifecycle | 520 checks | Actual manager/catalog; respawn, key edges, persistence, conditional controls and hidden-setting preservation. Includes 437 catalog uniqueness assertions. |
| Movement UX | 46 checks | Actual Auto Tool/Auto MLG code with world, input and interaction adapters. |
| Combat UX | 93 checks | Actual Trigger Bot, Jump Reset, W Tap and S Tap callbacks with game/input adapters. Other changed combat paths have integration compilation and source review. |
| Inventory UX | 86 checks | Actual six changed inventory feature state machines plus shared InventorySupport; seven old-bug regression controls fail as expected. |
| Fake Gamble ownership | 80 checks | Actual feature/helper bytecode; item ownership, cursor reconciliation and screen-close/disable cleanup with storage fixtures. |

These fixtures do not prove server acceptance, native input timing, final game
rendering or every interaction between enabled modules. Computer Use found no
Minecraft/Modrinth window or identified running candidate. No game screenshot
was fabricated or substituted. The remaining runtime pass must use the authorized
launcher and a local QA world with the candidate's build identity established.

No publication, authentication change or replacement of an existing game instance
was performed.
