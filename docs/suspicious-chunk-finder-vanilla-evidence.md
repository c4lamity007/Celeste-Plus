# Suspicious Chunk Finder: vanilla assumptions checked

Checked against the actual named Minecraft 1.21.11 jar used by the checkout, using Java 21 `javap -c -p` on 2026-10-01.

- Jar: `minecraft-merged-1.21.11-net.fabricmc.yarn.1_21_11.1.21.11+build.6-v2.jar`
- Jar SHA-256: `a2edf9600d61ec1903e63cce730d9a39e268162dd3e168cca0422d25d8b1514b`
- Full local diagnostic outputs: `BuddingAmethystBlock.javap.txt` and `KelpFeature.javap.txt`, retained in `C:/Users/c4lam/OneDrive/Documents/ChatGPT/Celeste Client/build/suspicious-engine-source/` as verification artifacts.

## KelpFeature.generate

- The normal placement branch requires both the current block and the block immediately above it to be `Blocks.WATER` (bytecode offsets 117–151).
- The normal terminal tip gets `KelpBlock.AGE = random.nextInt(4) + 20`, hence 20–23 inclusive (offsets 177–194).
- The fallback moves the tip position down by one block (offsets 233–238), and assigns the same `nextInt(4) + 20` age (offsets 276–293).
- Consequently, normal ocean generation does not itself assign tip ages 24–25. A tip with air directly above is also outside the normal water-above generation result. Subsequent growth, shearing, placement, or changed water can account for these states; they are not proof of a player or a base.

## BuddingAmethystBlock

- `canGrowIn` accepts any state whose `isAir()` is true, or an actual `Blocks.WATER` state with fluid level exactly 8 (offsets 0–34).
- Flowing water, waterlogged stairs/plants, and merely replaceable vegetation are not interchangeable with eligible growth space.
- Existing small, medium, and large buds grow only when their stored FACING equals the direction from the budding support block to that bud. The new bud or cluster keeps that direction.
- The compact scanner therefore distinguishes source/full-level water (`WATER`) from flowing water (`FLOWING_WATER`) and counts an existing crystal as attached only when its direction matches its support.

## Heightmap convention

- `Chunk.sampleHeightmap` returns `Heightmap.get(x,z) - 1` (offsets 89–104). It is the top occupied block Y, not the free block above it.
- The adapter uses this value directly for snapshot surface heights, and the flat marker adds its own top-face offset.

## Boundaries of this verification

This confirms the version-specific block-state assumptions. It does not establish the target server's custom chunk update policy, visibility masking, or the statistical accuracy of suspicion weights. Scan floor Y=0 is the user-approved provisional cutoff and remains configurable.
