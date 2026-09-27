# Celeste+ visible totem refill — 2026-09-20

Published release: `celeste-plus-20260920-legit-totem-01` (Fly v99).

## Hover Totem

- Replaced render-stale focus with current scaled-cursor hit testing for the
  ordinary visible survival inventory. The real recipe-book child supplies the
  vanilla offset; a narrow open book masks the underlying inventory entirely.
- Removed the unrelated grounded/stationary predicate from this visible
  inventory action. Vanilla inventory opening/input handling is unchanged.
- Reserve replenishment uses normal hotbar SWAP rather than QUICK_MOVE, which
  could fail with a full hotbar. A `Hotbar slot` setting selects the destination
  (default 3). Existing any-hotbar-totem stopping and Replace offhand behavior
  remain in place.
- Revalidate the exact screen, handler, world, current cursor, source, empty
  carried stack and still-needed destination immediately before the action.

## Legit Auto Totem

This is a new module under Crystal, disabled by default. When enabled:

- Moves the actual cursor inside the focused game window, once per rendered
  frame, with no overlay cursor. Manual movement/clicks temporarily take priority.
- Selects only main-inventory totems, ordered by distance from the window center
  where vanilla unlocks the cursor. It retains that center-out origin throughout
  the refill rather than continually choosing the nearest slot to the moving
  cursor. Screen and recipe-book offsets are accounted for.
- Uses the configured offhand binding first, then the chosen hotbar binding via
  vanilla screen input. Both transfers replace non-totems atomically, returning
  the displaced stack to the source rather than carrying/dropping it.
- `Speed` defaults to 650 GUI pixels/second (100–2000). `Reaction` defaults to
  80 ms (0–500) on each actual totem. During that wait, a small bounded continuous
  movement stays inside the slot. Cursor travel does not count as reaction time.
- `Auto open` defaults on, after one pop. Its `Open after pops` child offers
  1 or 2 and is visible only while Auto open is enabled. Actual local-player
  entity status 35 events are counted, not hand-item disappearance. Old pops
  are discarded on enable/world replacement and after opening/cancellation.
- `Auto close` defaults on. It closes only after this session performed a refill,
  both offhand and configured hotbar slot contain totems, the cursor stack is
  empty, and a subsequent tick confirms the local inventory state. A manually
  opened already-filled inventory is not immediately closed.
- Closed/replaced inventory, focus loss, overlay, resize, invalid source, world
  change and disable invalidate the relevant movement/action plan. Esc is not
  followed by an automatic reopen. Only the ordinary survival InventoryScreen
  is handled; creative, silent and unknown-layout custom screens are excluded.
- Keyboard/scancode and non-primary mouse bindings use the real screen input
  path. Navigation, chat, drop/inventory conflicts and primary-button bindings
  are not reinterpreted as swaps. An input consumed without filling its target
  receives a retry pause, not a fallback click or a success/close.
- Hover Totem yields while Legit Auto Totem is enabled. Silent Totem cleans up
  only its own pending silent screen and then yields as well.

## Checks and limitations

Final build QA: `build/module-qa/20260921T030738Z-5424f3e4/result.json`.
Input JAR SHA-256:
`cc6c25c0fb2b9bed7f6ddf2bfd12ae2000404d91f6fd4235e10dac4021567bc6`.

Focused tests passed: 59 cursor ordering/motion/reaction/reset checks and 28
pop-policy/settings checks, including the close-condition matrix. They passed
again with the protected payload first on the class path. The actual named and
intermediary gameplay callback fixtures passed 494 checks, and the combined
hooks passed 4,738 checks. Vanilla 1.21.11 bytecode inspection verifies the totem
status branch reaches the single normal TAIL exactly once on the client thread.

The 494 executable gameplay checks also passed with the actual protected
bootstrap A and payload; class-load logging confirms those protected artifacts
supplied the transformer and totem tracker. Final artifact SHA-256 values match
the assembled manifest, and the changed production classes survive the split
with their exact protected bytes. Loader compatibility, protected DPoP checks,
and disposable-database auth/download smoke checks passed before deployment.

The incremental build and narrow production compilation passed. Native game UI
control and an identified edited build in an authorized local world are not
available. Real cursor animation, recipe-book interaction and gameplay behavior
remain unverified in-game; no screenshot or gameplay test is claimed.

## Delivery

The user explicitly authorized publication through Fly auth. The existing
obfuscation, two bootstraps and two-part payload delivery are retained. Standard
Celeste stays pinned to `celeste-20260920-dpop-02`. No auth implementation,
license, secret, cloud-config or account changes are part of this update.
Operational logs and the v98 rollback baseline are under
`build/module-qa/legit-totem-release-20260920/`.

## Live verification

Deployment completed on machine `85d426c4429798`, with passing service health.
Image: `registry.fly.io/qb0f90cf11f4d1d52:deployment-01M30Z9SPHZ7T38CZTNFAY4G67`.
Digest: `sha256:b7b5ff8e734af4e84623824b6283e0c7e0b277e0f62c86f7ae32e50300232437`.

Read-only live verification matched all 13 deployed artifact/manifest hashes,
the DPoP module and replay table, unchanged installer compatibility, and unchanged
database/cloud-config paths. Standard Celeste remains pinned to the previous
release. No native launcher was replaced. Fully restart Minecraft and use the
existing Celeste+ launcher, then enable Crystal > Legit Auto Totem to use the
new visible-refill behavior.
