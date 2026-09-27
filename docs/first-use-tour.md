# Celeste+ first-use tour and HUD refresh

The first authorized opening of the Plus GUI offers an optional tour. Each prompt
uses the normal GUI controls. Each step offers Skip, Next permits skipping an
exercise, and Settings has Replay. Escape dismisses the guide in the main GUI;
in the HUD editor it returns to the main GUI, retaining the current step. The
coach sits beside the current control with a short pointer and target outline.
It moves from the config name field to Save and points at the actual resize
handle or widget-menu row in the HUD editor. Placement prefers a 12-unit gap on
any available side, then compacts to leave controls usable. If a small editor has no free
space beside its open menu, the coach hides until that menu closes.
Tour cards use a flat dark surface without a border, drop shadow or darkened
screen backdrop. The nearby control cue and short pointer remain visible.

## Practice steps

1. Click Visuals in the sidebar.
2. Left-click ReBuff Notifier to change its actual enabled state.
3. Right-click ReBuff Notifier, then change its Alert duration slider.
4. Type ESP in search and show matching modules.
5. Enter a config name and click the normal Save action.
6. Open the real HUD editor, move a widget, resize it by a corner or scrolling,
   and toggle a widget using the right-click menu.

Changes use the same handlers and persistence as ordinary use. No stand-in
toggle or draggable sample exists. Progress is recorded only after the relevant
state changes: a successful Save, actual slider values, released HUD positions,
post-clamp scales, and a changed widget toggle. Empty-name saves, failed saves, unchanged or clamped
values, unrelated settings, and actions performed on another step do not count.
Practice never runs module actions, saves, or layout edits automatically.

The first authenticated GUI opening claims a persistent marker at
`~/.celeste/ui/first-use-plus.properties` before displaying the tour. The path is
shared across game instances for the current OS user and is independent of the
release or injection session. Finishing, skipping, closing the game midway,
restarting, and reinjecting do not make the tour appear automatically again.
The previous instance preference is honored when migrating. Config imports do
not include this preference. If it cannot be saved, automatic onboarding stays
off; the user can still choose Replay in Settings.

## Authentication integration

The current Plus launcher is `beta-lab/native-launcher`. Activation verifies the
signed `celeste_plus` entitlement and protects the selected credential locally.
Each Load obtains fresh authorization and verifies the signed manifest and exact
release/catalog before bootstrap loads the payload. The payload authenticates
through `AuthBootstrap` and initializes services before `CelesteClient.active()`
permits opening the GUI.

`CelesteClient` passes its existing `lateMode` to the screen, restricting automatic
onboarding and Replay to Plus. The tour adds no activation screen and does not
change credential handling. Existing stop and authorization-loss cleanup closes
both `CelesteScreen` and `HudEditorScreen`.

## HUD behavior

- Configs (the keybind HUD): one panel fitted to its contents, compact rows,
  aligned keycaps, and a quiet title/divider. No count badge or row pills.
- Effects: a compact shared title/icon surface, full-color sprites, and no count
  badge. Below ten seconds, an icon peels into its own name/countdown card.
  Only warning text flashes yellow. Removal fades retain the sprite until its
  transition finishes, and the tray contracts as icons leave.
- Widths and heights follow contents, with minimal margins and no empty rows.
- Coordinates shows only comma-separated numbers in a 15-unit-high panel, with
  four units of padding on each side and no icon or axis labels.

## Sidebar navigation

The GUI reopens on the last selected content tab. This preference is saved in
the local interface settings and survives a restart or reinjection. Tour steps
and imported profiles cannot overwrite it. HUD opens the editor and retains the
last content tab to return to; unknown saved tab names fall back to Sword.

## Checks and preview

`tools/qa/run-first-use.ps1` checks action prerequisites, rejected no-op/invalid
events, optional navigation, Skip, text-entry shortcuts, and persistence.
The HUD suite checks production layout/animation helpers and the text shader.
`betaRuntimeLifecycleCheck` checks Plus lifecycle states and compiles the real
screen integration.

`tools/qa/render_gui_first_use_preview.py` produces the simulated MP4 and stills
under `docs/previews/`. Its timed event trace runs the production tour state and
uses the same instruction text and completion rules. The preview uses Celeste's
font atlas and assets, and depicts the actual GUI/editor controls. It is a
simulation, not an authenticated recording or end-to-end UI test. It does not
establish that the edited payload is loaded in Minecraft.
