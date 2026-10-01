# ESP and loaded Minecraft data

Block ESP and Storage ESP use Minecraft's existing client chunks. They never ask the server to load additional chunks, generate terrain, or infer blocks that were not sent.

## Block and Storage ESP

`Loaded chunks` is enabled by default. It indexes the loaded chunk area around the player through the world's full height. The discovery area follows the configured client view distance plus Minecraft's surrounding chunk margin. Turning this option off restores a manual spherical `Range`, from 8 to 512 blocks.

The index performs an initial palette check and scans only sections containing selected blocks. Each matching section keeps a compact bit mask of its positions. The previous 1,024-position cache ceiling is removed from the production index. A dense nearby patch therefore does not discard distant matches merely because those matches are not currently being drawn.

Single-block packets, section-update packets and chunk load events invalidate affected data. Unchanged sections are not repeatedly palette-tested or walked. Cheap loaded-section identity checks detect replacement and unavailable data becoming available. Chunk unloads, disabling the module, changing the block selection and world changes clear the appropriate data.

Storage uses Minecraft's existing block-entity positions, including pending server NBT entries, without creating block entities. Positions are grouped once per chunk during each scanner tick. If a selected storage section has no indexed positions, or contains selected ordinary blocks, it uses the bounded block-state scan instead.

The scanner budgets discovery, palette checks, state walks and reordering separately. At most 96 palette checks, two full section walks, or 16 indexed section collections occur per tick. Cached sections are capped at 131,072; unusually tall custom dimensions use a smaller scan square to respect that bound. Initial discovery of a large view still takes time.

Rendering is separate from detection. Entire sections outside the camera frustum are skipped before inspecting their matches. Each frame visits at most 4,096 matching sections in near-first order, examines at most 1,024 block candidates from visible sections, and emits at most 512 highlights. Dense selections can therefore show a subset at once while the underlying match data stays cached.

## Item ESP

Box targets come from Minecraft's typed spatial index once per client tick. The closest 96 eligible items are selected using a bounded heap. Unrelated entities cannot hide an item by appearing earlier in the world's entity iteration order.

Frames reuse the selected targets while preserving interpolated movement, fade and actual-box frustum checks. Highlight-only mode does not run the box-target query. The existing vanilla item-model outline path and manual range remain intact.

## Other visuals

Player/entity ESP already uses Minecraft's loaded entity lists. Its distance and expensive model-effect budgets are separate from block chunk availability. Suspicious Chunk Finder already caches server-supplied observations; recognizing growth populations and repaired routes still requires its pattern analysis. These modules are not rewritten by this change.

## Verification limits

Deterministic Java fixtures verify index updates, cache lifecycle, distance modes and query counts. A full runtime build checks integration with the actual Minecraft/Fabric APIs. These checks do not measure in-game FPS or prove visibility beyond the block/entity data the server supplies.
