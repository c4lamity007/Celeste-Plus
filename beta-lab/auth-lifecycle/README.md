# Runtime authorization lifecycle checks

`run-tests.ps1 -JdkHome <Java 21>` compiles the actual Plus overlay `AuthBootstrap` with the pinned core lifecycle helpers and controlled game/network/storage fixtures. Prepare `build/runtime-core` first with the repository runtime preparation tool.

Each scenario starts a new fixture JVM. The full run checks the real 30-second heartbeat schedule; `-SkipTimedHeartbeat` runs only the deterministic cases. It does not attach to Minecraft, read credentials, or call a real authentication endpoint.

Coverage includes fresh startup proof, monotonic 90-second lease expiry independently of worker progress, late response/stop races, exact-once transient cleanup, and preservation of durable installation keys on typed remote failures. `token_revoked` and `reactivation_required` can mean an access grant or protocol transition; deleting the installation identity would remove the keys needed for fresh dual-proof authentication. Session lock clears its in-memory grant and credential references. Durable key deletion remains an explicit user action.
