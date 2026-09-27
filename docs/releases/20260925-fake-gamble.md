# Celeste+ Fake Gamble and UX fixes — published

Release: `celeste-plus-20260925-fake-gamble-02`.
Published and verified September 26, 2026 at 04:37 UTC
(September 25 at 9:37 PM Pacific).

Fake Gamble now uses Minecraft's pickup presentation and delays, preserves partial
pickups, and supports local block placement and timed mining with normal names
on mined drops. Its inventory fixes cover whole-stack throws, right-click leftovers,
stack merging, offhand ownership and creative action isolation. Only owned local
actions are intercepted; ordinary networking continues.

This release also includes the preceding UI and module UX audit fixes. Auto Double
Hand visibly selects a totem. Auto Tool visibly selects a tool with Silent swap
off, including during local ghost mining. Restoration respects newer manual choices.

**Restart Minecraft before injecting this release** if an older Celeste+ build has
been loaded. The canonical resident bridge changed; detaching alone cannot replace
it in an already-running JVM. The existing launcher can fetch this update.

## Verification

- Incremental build and all six recorded build/regression steps passed.
- Selection: 129 checks; movement: 49; local items: 136; ghost blocks: 279.
- Hook fixtures: 1,082 named/intermediary checks and 4,924 combined checks.
- Release verification found and fixed missing argument retention in attack/item
  return hooks, mining cleanup and server-packet callbacks before deployment.
- Exact Java 21 verification passed baseline and prepared forms of all 24 current
  targets, using pristine Minecraft bytecode. Captured runtime bytecode plus the
  two new pristine targets also passed.
- Protected Bootstrap A: 440 visible classes and 70 implementation classes passed
  Java verification. Payload obfuscation, boundaries, split, provenance and both
  protected bootstraps passed their release gates.
- Disposable authentication smoke checks exercised all four Plus artifacts;
  authentication replay and invalid installer-digest checks rejected as expected.
- Live service health, 13 deployed artifact hashes and both Plus payload endpoints
  verified. Unauthenticated manifest/payload access returns HTTP 401.

QA receipt: `build/module-qa/20260926T043456Z-ec002ef0/result.json`.
Release receipt: `build/module-qa/fake-gamble-release-20260925-r2/receipt.json`.
Live checks: `build/module-qa/fake-gamble-release-20260925-r2/live-after.json`.

Image:
`registry.fly.io/qb0f90cf11f4d1d52@sha256:96af580c00c8e3d9398dada9f08f7e3c51d4df26faa273abfd7cb1fa8a808fd4`.

Standard Celeste remains `celeste-20260922-bootstrap-verifyfix-01`. Authentication,
launcher identity, volume mounts and database/cloud paths match the prior release.
Candidate 01 was rejected locally by the JVM verifier and was never deployed.

## Known limits

No authenticated local-world test of this exact build is claimed. Connected
placements that reshape nonreplaceable neighbors are rejected and rolled back.
Custom configured blocks use their placed-item drop rather than evaluating server
loot tables. Default iron/diamond ore drops honor harvesting, Silk Touch and Fortune.
