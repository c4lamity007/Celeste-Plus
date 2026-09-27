# Celeste+ UI and module UX audit — September 25, 2026

This pass fixes reproducible code and layout defects. It is not a claim that all
79 modules have been exercised in Minecraft. The changes are local and unpublished.

## Fixes

### Main GUI

- Config inspection has a bounded list with its own scroll position, an explicit
  close control, and Esc dismissal. Its mouse/keyboard input cannot reach the
  config actions or Dynamic Island underneath.
- Setting labels, descriptions, module cards, config cards and value chips fit
  measured text budgets. Truncated setting text remains available on hover.
- Setting input regions follow their expansion clip. Hidden friend rows and
  suggestions cannot accept clicks outside the content viewport.
- Scroll targets are clamped when input arrives, removing accumulated overshoot
  at either end of a list.
- Sidebar navigation clears invisible text-field focus. A custom menu key does
  not close the GUI while the user is entering text.
- Search reveal, truncation and deletion preserve complete Unicode codepoints.
- Only the left mouse button changes sliders/ranges or ends their drags. Right
  click retains the existing previous-option behavior for selection controls.

### Popups and editors

- Color pickers stay within the screen and flip above swatches near its bottom.
  Drawing and HSV hit regions move together. Esc/outside click dismisses the
  picker; popup input and scrolling cannot reach underlying controls.
- The block picker accumulates fractional trackpad scrolling, supports dragging
  its scrollbar and Page Up/Page Down, and reserves room for the search caret.
- The Hand Models editor fits short as well as narrow windows. Labels stay away
  from the header/footer and each other, including when both hands share a position.
- Small HUD widgets retain a draggable center. Foreground widget bodies block
  resize handles on widgets behind them. Context menus block scroll resizing,
  unrelated mouse-button releases cannot finish a transform, and reinitialization
  clears stale editor interaction state.

### Text

- Normal text, gradient text and measurement use the same glyph resolution.
  Unsupported visible codepoints now occupy a visible replacement box instead
  of disappearing and collapsing surrounding text.
- Target initials retain the entire first codepoint.
- **Remaining limitation:** the bundled atlases still lack accented Latin, CJK
  and emoji glyphs. Replacement boxes make missing coverage explicit; they do
  not provide correct glyphs for those characters. Existing ASCII/Cyrillic/UI
  symbol coverage is retained.

### Shared module behavior

- Physical key edges are tracked while screens, overlays or focus rules suppress
  module input. Closing a GUI with a held key no longer toggles the module again.
- Block ESP and Storage ESP now recognize their existing `Keybind` setting.
- Explicit saves and imports update the saved snapshot only after successful IO,
  allowing normal persistence to retry a failed write.

## Verification

**Result: partial verification.** The incremental build and all nine regression
suites passed. The QA runner records runtime as blocked until an identified
candidate is available in Minecraft.

The combined `ui-ux` suite builds incrementally and runs production logic checks
for main GUI content geometry, color-picker draw/input alignment, scrolling,
hand-editor placement, actual font/atlas geometry, HUD input, module lifecycle,
first-use behavior, sidebar memory, Block ESP, Hand Models and HUD layout.

Run: `build/module-qa/20260926T034010Z-6f4e911c/result.json`.
Source SHA-256: `b6f55967166cb039608877082aff4a3c0b5c830deff69ec596c199383f3d6343`.

The module audit checked all 79 catalog entries and their 358 settings for unique
registration, plus keybind and persistence behavior. Regression controls restoring
each of the three module bugs failed at the expected assertions. Input lifecycle
code was also reviewed for Sprint, Safe Walk, Auto Head Hitter, Keep Sprint,
Jump Reset, W Tap and S Tap. Their game behavior was not tested in this pass.

Font and input fixtures use production logic with replaced game/graphics
boundaries. They do not prove final compositing, animation smoothness or actual
Minecraft event delivery. No Minecraft/Modrinth window or identified running
candidate was available through Computer Use. No in-game screenshot is attached.
Runtime verification remains pending through the authorized launcher/local-world
flow; this pass did not publish a release or bypass authentication to obtain it.
