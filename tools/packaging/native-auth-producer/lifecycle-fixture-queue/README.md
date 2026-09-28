# Lifecycle queue publication fixture correction

The recovery-exhaustion test observed `locked()` before the supervisor finished queuing owner-thread cleanup. Its immediate queue drain could run too early, leaving the displayed identity uncleared in the fixture. Production authorization was already closed.

`fixture-source.patch` adds one bounded wait for queue publication before draining. All authorization, secret clearing, identity clearing, cloud rejection, retry-count and exactly-once cleanup assertions stay intact. No production method or native body changes.

`rebuild.py` accepts a pinned existing verification plan, pinned remapped fixture source, exact final payload, JDK21 and fresh output. It compiles only `AuthBootstrapLifecycleTest.java`, verifies the nested interface is unchanged, replaces exactly one class in the lifecycle fixture JAR, preserves every other fixture entry and all 41 checks, then writes a new plan with the correction/source/build provenance pinned. It never edits the input plan, sources or payload and never executes the payload. Run the final checks separately with fresh owned custody roots.

For a fresh named fixture source, the existing fixture adaptation is exact textual replacement of `net.minecraft.client.MinecraftClient` with `net.minecraft.class_310`, then remaining `MinecraftClient` identifiers with `class_310`, then `class_310.getInstance()` with `class_310.method_1551()`. The input source must be the original fixture before this queue correction; the rebuild helper applies the checked correction itself. Supply all input hashes explicitly. Do not apply the repository test patch while resuming an older sealed source checkpoint; preserve it for future source preparation or a separately reviewed fixture snapshot.

The final release fixture must pass the affected protected case and all 41 existing protocol/lifecycle checks with the new plan. Local synthetic keys and transports are used; this is not live-game or production authentication evidence. No fixture JAR is committed with these sources.
