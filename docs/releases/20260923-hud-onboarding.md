# Celeste+ — compact HUDs and first-use tour

Published: `celeste-plus-20260923-hud-onboarding-02`.
Verified live: `2026-09-24T01:49:35Z` (September 23 local time).

## Changes

- Configs and Effects HUDs fit their contents with reduced padding and simpler labels.
- Effects below ten seconds leave the icon tray for compact name/countdown cards.
- Coordinates shows only comma-separated numbers, with four units of side padding
  and a 15-unit height. No icon or axis labels.
- The optional tour points beside real controls and checks real toggle, slider,
  search, config-save and HUD-editor actions. Skip and manual Replay are available.
- Automatic onboarding is claimed before the first authenticated GUI opening in
  `~/.celeste/ui/first-use-plus.properties`. Reinjection, restarting, an interrupted
  tour and switching game instances do not reset it. Legacy completion is migrated.
  This local preference belongs to the current OS user and is outside config exports.

## Verification

Build, HUD layout and text shader checks passed. Protected-payload checks verified
all seven tour class origins and passed 153 behavior plus 48 placement assertions.
Persistence checks include a fresh JVM before dismissal and concurrent first-use
claims. Protected bootstraps passed Java verification, DPoP checks and disposable
authenticated delivery of all four Plus artifacts.

The payload and both bootstraps were obfuscated; payload A and B were validated
under the same signed release. Live verification matched all 13 deployed standard
and Plus artifact hashes. Health is passing; unauthenticated manifest and payload
requests return 401. Standard Celeste remains
`celeste-20260922-bootstrap-verifyfix-01`. Installer identity, auth code, storage
paths and mounts match the baseline.

Image: `sha256:0d87e2976ca5188a4a0c52479fe9c558f129b0feae258b724086fd1505428efb`.
Evidence: `build/module-qa/hud-onboarding-once-release-20260923/receipt.json`.
Source QA: `build/module-qa/20260924T014606Z-aa243281/result.json`.

The walkthrough video is simulated. No authenticated local-world test or game
screenshot of this exact build is claimed. Candidate `hud-onboarding-01` was
superseded locally and never deployed.

Fully close Minecraft, then load Celeste+ through the existing launcher. A
replacement launcher executable is not required.
