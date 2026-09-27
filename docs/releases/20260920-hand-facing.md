# Celeste+ sword facing — 2026-09-20

Published and verified: `celeste-plus-20260920-hand-facing-01`.
Deployment image:
`registry.fly.io/qb0f90cf11f4d1d52:deployment-01M306590PKF7VPGE09X7EV1BF`.
Image digest:
`sha256:add3b8022cb1f9fbfa2fd2716f3af7f8631ae9fa42a34653d655e96572b34be0`.

## Change

The vanilla handheld sword model has an effective -90 degree Y rotation in
either hand display context. Rotating only the surrounding hand frame made its
broad face edge-on near the editor center and exposed the back during crossover.
The sword pose now corrects that authored basis, faces the eye from its grip
position, and keeps a small relative away tilt. Lean changes left/right through
a smooth in-plane roll, not a mesh reflection or a front/back flip.
Swords retain their physical hand display context while crossing the center,
avoiding the unrelated authored translation/depth jump between those contexts.

The correction is deliberately scoped to sword-tagged items with the vanilla
handheld orientation. Shields, blocks and other authored models retain their
existing matrices. Arbitrary resource-pack sword orientations are not inferred.
Grip position, circular placement, snapping, positive uniform sizing, and
vanilla equip/swing animations are retained. Animated swings are not billboards;
the front-facing guarantee concerns the resting/editor placement.

The existing standard and Celeste+ pose/display callbacks now pass the actual rendered
stack. During equip transitions that can differ from the selected inventory
stack. The shared bridge retains its previous entries and handler fallbacks.
No new hook target, native-launcher or authentication change is needed.

## Verification and delivery

Focused matrix tests use the complete vanilla physical-hand and handheld-model
transform chain, checking signed front-face visibility and projected area as
well as grip location, size, handedness and crossover lean. The previous tests
checked tip direction but did not catch the edge-on/back-face defect.

The focused pose fixture passed 31,911 checks across both hands, portrait through
ultrawide aspect ratios, 60/70-degree hand FOV, center/edge positions and 0.5–1.5
sizes. Minimum signed front-face/eye dot was 0.8240 and minimum normalized
projected area was 0.9324. Negative controls reproduce the old defect. Lean-angle
and center-boundary tip-position checks cover smooth context-preserving crossing.
The actual rendered-stack bridge passed 388 late-hook and 4,734 combined-hook
checks, including old/new callback compatibility and item identity during swaps.

An inspected software-projected comparison using the tested matrices, cached
vanilla sword texture and generated-item slab geometry is at
`build/module-qa/hand-models-sword-final-20260920/hand-models-sword-pose-preview.png`.
It is not a GPU benchmark or gameplay screenshot.

The final incremental build and complete hand-visuals suite passed in
`build/module-qa/20260920T195058Z-582cb1d5/`, including Hand Models, Hand Shaders,
Fake Gamble and both hook harnesses. The QA report correctly records runtime
verification as blocked rather than treating fixtures as an in-game test.

Actual game access to the edited build in an authorized local world is not
available through the current native UI tools. Any pose preview is offscreen
evidence, not a Minecraft screenshot.

Publication uses the existing protected bootstraps and two-part
payload pipeline. Standard Celeste stays `celeste-20260920-dpop-02`; auth source,
launcher compatibility, signing secrets, licenses and cloud configs stay intact.

Protection/split validation, rebuilt A/B bootstrap checks, protected DPoP tests,
disposable-database auth/delivery smoke and staged-context verification passed.
The 31,911 pose checks also passed with the protected payload first on the
fixture classpath. Full Minecraft restart is required for the updated shared
hand callback signatures; the existing EXE remains compatible.

Independent read-only provenance confirmed both new/legacy bridge overload sets
inside rebuilt Bootstrap A and the protected payload, exact source/QA artifact
identity, and all four release-manifest hashes/sizes. Post-deploy verification
matched all 13 expected live artifacts, unchanged installer and database/cloud
paths, and the existing DPoP module/replay table. Fly HTTP health passed.
Operational logs and the v96 rollback snapshot are in
`build/module-qa/hand-facing-release-20260920/`.
