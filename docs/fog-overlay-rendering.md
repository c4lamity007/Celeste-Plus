# Fog and transparent visuals

Celeste's screen-space Custom Fog pass samples terrain depth. Transparent ESP,
chams, glows and image effects do not write their depth into that terrain buffer.
Drawing them before fog therefore shades a nearby visual using the distance to
whatever terrain is behind it, creating background-shaped tinting and fading.

`WorldOverlayPass` gives both visual bridges a shared `END_MAIN` phase:

1. Default world/fog callbacks.
2. Celeste world overlays.
3. The existing Sky Lyrics phase.

Both bridges finish only their own fill/line batches before their private
chams/volume composites. World depth is preserved. Existing depth-tested block
markers remain occluded, while intentional X-ray effects retain their behavior.
Ambient particles keep their native per-vertex fog, but are no longer fogged a
second time using distant terrain depth. Opaque replacement player models keep
their entity render stage and scene depth.

Arrow ESP already draws after world rendering through the HUD/GUI pass. Its GUI
texture shader has no scene-depth or fog input; the normal and Plus hooks both
flush it after Minecraft's GUI call. Its optional Distance fade remains separate.

Use the `visual-fog` QA suite for compiled callback and GPU checks. Its imagery is
synthetic GPU evidence. Actual Minecraft appearance and mod interactions still
need a local-world check using the identified edited build.
