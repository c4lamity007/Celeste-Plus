# Runtime source overlay

This directory contains only changed or newly added Celeste+ runtime source relative to the Celeste commit pinned in `../core.lock.json`. The manifest records each exact overlay SHA-256 and its required base SHA-256; `null` means the source file must be absent in the base. Source is stored with LF line endings so checkout conversion does not create false changes.

This is the current integrated development source, including the media, sky, model-import and lifecycle dependencies needed to compile it. It is not byte-identical to any previously published protected runtime payload. Preparing or compiling it does not publish, license, sign, attach, or install it.

Run `python tools/prepare_runtime.py --core-checkout /path/to/Celeste` from the Celeste+ repository. The tool reads the exact locked Git commit through `git archive`, verifies all required base hashes and overlay hashes, rejects links/traversal/collisions, and writes only a fresh `build/runtime-core`. Without a local checkout it clones the known Celeste GitHub repository into that fresh output's preparation directory. Existing output is refused and never automatically deleted. After a failed preparation, move the owned output aside explicitly before retrying.

The root lock pins the reviewed Celeste core commit. Preparation fails closed if that exact commit is unavailable or any base source hash differs. Once prepared, use that tree's Gradle wrapper to compile/remap the runtime, or pass `--build` to the preparation tool. An optional external media bridge may be supplied with `--build --media-dll /path/to/MediaPlayerInfo.dll`, which sets `MEDIA_PLAYER_INFO_DLL` for the portable core build. The runtime cleanup native source remains in the base; no compiled DLL is bundled here.

No creator-owned GLB models, compiled artifacts, credentials, local profiles, authentication data, or release/signing material are included in this overlay. Model importing remains source functionality; external models and native tools must be supplied separately with their required permissions.
