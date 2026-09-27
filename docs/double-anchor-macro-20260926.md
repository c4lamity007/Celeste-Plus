# Double Anchor Macro

Enable **Anchor Macro** and **Double Anchor Macro** in the Crystal tab. Set the
**Hold** key, then hold it while starting Anchor Macro with right-click.

Each modifier-key hold allows one double sequence. After it finishes, keeping the
key held uses ordinary Anchor Macro. Release and press the modifier again to rearm.
Releasing right-click cancels the active sequence; releasing only the modifier
lets that sequence finish. Anchor Macro's silent swap and Hold to loop still apply.

## Timing

With **2 tick swap chance** at zero:

| Tick | Selected item | Use |
| --- | --- | --- |
| 1 | Anchor | Place first anchor |
| 2 | Glowstone | Charge first anchor |
| 3 | Anchor | Explode, then place the replacement in the same tick |
| 4 | Glowstone | Charge the replacement |
| 5 | Totem | Explode the replacement |

The two uses on tick 3 share the original anchor hit and selected anchor slot.
There is no slot change, support-block search or tick boundary between them. Once
the server removes the first anchor, the replacement targets that same block
space, including when adjacent supports are gone.

The existing swap-delay setting can add a tick before an item swap's use. It never
splits the two uses on tick 3. With the anchor already selected and the chance at
100%, the six uses occur on ticks **1, 3, 5, 5, 7, 9**. Double mode always sends one
glowstone use per anchor, regardless of **Double glowstone**.

## Interaction handling

The existing input lease, primary action scheduler and slot ownership handle the
whole sequence. Both tick-3 uses go through Minecraft's interaction manager, with
its separate prediction sequence IDs. The held physical use input remains consumed.

The client can still show the original charged anchor after the server explodes
it. The replacement and its two followup uses therefore advance when dispatched,
without waiting for local block acknowledgements or retrying a rejected local
prediction. This keeps delayed updates from adding a second glowstone click or
stopping the sequence after its fourth use. The macro does not edit the client
world to simulate placement.

The first anchor still requires valid placement and charge state. The continuation
checks item availability, reach, world border, loaded chunks and dimension. It
stops on menu/focus/player/world changes or inventory/input takeover. Sneaking and
offhand glowstone stop the double sequence because they change vanilla use behavior.
Starting with placement requires two anchors, two glowstone and a hotbar totem.

Cancellation consumes held use until release. Silent-slot cleanup after an
interaction defers restoration until the next tick. A manual totem selection is
preserved. A cancelled double sequence does not rearm under the same modifier hold.

These ticks describe client action scheduling. Server acceptance, network arrival
and third-party checks are not guaranteed by that schedule.

## Verification

Run the focused behavior fixture from the Plus repository:

```powershell
& ./tools/qa/run-double-anchor-five-tick.ps1
```

It compiles the real overlay coordinator with the pinned runtime's scheduler and
slot manager. Minecraft prediction and server handling are bounded test fixtures;
they are not live gameplay evidence.

For an incremental Minecraft build after preparing the verified overlay:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot'
& ./build/runtime-core/gradlew.bat -p ./build/runtime-core :build --offline --no-daemon --console=plain
```

This revision passed 336 focused checks and the game module's `:build`. The older
QA runner's unqualified `build` also selects an unused legacy bootstrap loader,
which fails on missing `EnvironmentGate` / `PortableCredentialProtection` imports.
That broader build failure remains separate from the successful game build.

The five-tick revision supersedes the six-tick replacement timing documented in
[the previous release](releases/20260926-anchor-packets.md). Source/build validation
and a protected production release are separate; this revision needs live gameplay
verification with its own loaded build identity.
