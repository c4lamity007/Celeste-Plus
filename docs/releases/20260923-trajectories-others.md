# Celeste+ airborne projectile trails — published

Release: `celeste-plus-20260923-trajectories-others-01`.
Published and verified September 24, 2026 at 04:08 UTC
(September 23 at 9:08 PM Pacific).

Trajectories now shows observed flight trails for other pearls and arrows,
including spectral arrows. `Others` defaults to on. The existing `Pearls`,
`Bows`, color, glow, opacity and fade controls apply to these trails.

Only already-spawned airborne projectiles are considered. Remote paths contain
observed positions from the last second, with the current interpolated head
first. They have no future arc or landing marker. Other players' held items are
never inspected for an aiming preview. Your existing held and live predictions
remain available, and remote projectiles do not suppress them.

Tracks clear on landing, removal, disabled projectile kinds, module disable,
world change or leaving client tracking range. History is bounded to eight
remote projectiles and 21 observed points each. Unresolved owners are accepted
because a shooter's entity can be outside the client's tracking range.

The embedded-arrow filter uses the Minecraft 1.21.11 tracked in-ground state
through its public `isPushedByFluids()` implementation. This also prevents a
stationary remote arrow with stale network velocity from leaving a trail.

## Verification

- Final incremental build passed.
- 2,855 observed-history checks, including capacity replacement without resetting
  retained trails, and 309 checks executing the actual extraction implementation
  with isolated Minecraft adapters passed.
- Existing geometry (10,554) and actual material GPU (2,473) assertions passed.
- Exact Minecraft 1.21.11 embedded-arrow bytecode contract passed.
- Obfuscation, payload split/validation, protected bootstrap and authentication
  smoke checks passed.
- Live service health, all 13 artifact hashes and both Plus payload endpoints
  verified. Unauthenticated manifest and payload requests return HTTP 401.

QA receipt: `build/module-qa/20260924T040443Z-7c18c608/result.json`.
Release receipt: `build/module-qa/trajectories-others-release-20260923/receipt.json`.
Live checks: `build/module-qa/trajectories-others-release-20260923/live-after.json`.

Deployed image:
`registry.fly.io/qb0f90cf11f4d1d52@sha256:a8b3e39c716a74e9de0110657f25ab38f66613d25d159e3e75fcdd57caffa8f3`.

Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. The launcher,
authentication implementation, volume mounts and database/cloud paths are unchanged.

No authenticated local-world verification of this exact build is available.
Offscreen shader fixtures do not establish in-game integration or appearance.
