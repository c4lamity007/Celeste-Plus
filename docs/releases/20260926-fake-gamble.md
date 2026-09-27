# Celeste+ Fake Gamble follow-up — published

Release: `celeste-plus-20260926-fake-gamble-03`.
Published and verified September 26, 2026 at 23:38 UTC (4:38 PM Pacific).

## Changes

- Dispensed local items wait 10 ticks before pickup.
- Normal right-click opens real dispensers/droppers while holding local prizes.
- Real and local shift transfers respect occupied fake slots, native transfer
  order, slot limits and source leftovers. Mixed real/local dispenser slots and
  stale server updates no longer allow local clicks to overwrite real items.
- Repeated updates with the original server block state preserve placed ghosts.
  Native collision, landing/jumping and crouch-edge/corner support remain present.

Full behavior and limits: [follow-up notes](../fake-gamble-fidelity-20260926.md).

## Update

When upgrading from `fake-gamble-02`, use a successful **Stop**, then inject the
new release. All six resident support JARs, all 32 canonical bridge classes and
the hook target implementations are unchanged. Restart Minecraft if cleanup
fails/requires a restart or an older pre-02 build remains loaded.

## Verification

- Incremental build and all seven recorded QA steps passed, including 166 local
  item checks, 301 ghost service checks, 29 native Minecraft physics checks and
  52 dispenser routing checks.
- Selection (129), movement (49), named/intermediary hooks (1,082) and combined
  hooks (4,924) passed. The QA workflow's own 37 checks also passed.
- Obfuscation, implementation boundaries, payload split/provenance, protected
  bootstraps and DPoP checks passed.
- Java 21 verified pristine baseline/prepared forms of all 24 hook targets and
  440 visible/70 implementation protected Bootstrap A classes.
- Disposable authentication tests passed all four Plus artifacts and six standard
  artifacts, including rejected replay/invalid installer digest cases.
- Live service health, all 13 deployed artifact hashes, and protected manifest,
  payload-a and payload-b endpoints verified. Unauthenticated access returns 401.
- Standard Celeste, authentication code, launcher identity and persistent storage
  configuration match the previous deployment.

QA: `build/module-qa/20260926T233143Z-9c2a3768/result.json`.
Release: `build/module-qa/fake-gamble-release-20260926/receipt.json`.
Live: `build/module-qa/fake-gamble-release-20260926/live-after.json`.
Resident compatibility: `build/module-qa/fake-gamble-release-20260926/support-compatibility.json`.

Image: `registry.fly.io/qb0f90cf11f4d1d52@sha256:40301c451f03b1ef155fcff8fe418592e86dbe8c9c4d0ec11829d84aa5a8816f`.

## Remaining limits

No authenticated local-world test of this exact candidate was performed. Native
engine/behavior checks do not establish live animation, inventory networking or
reinjection behavior. No older multiplayer screenshot is claimed as evidence.

Client-only blocks remain subject to server position corrections. Special recipe,
trade and unsupported-screen shift transfers are blocked while fake slots exist.
Connected placements and custom loot retain the limitations in the follow-up notes.
