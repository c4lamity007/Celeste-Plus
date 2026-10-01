# Freecam culling regression

Compile the actual feature against a small Minecraft lifecycle fixture:

```powershell
python tools/qa/freecam-culling/run.py --source runtime-overlay/src/main/java/dev/c4lam/celeste/module/impl/movement/FreecamFeature.java --java-home "C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot" --out build/module-qa/freecam-culling
```

Checks activation, steady frames, restoring the prior culling state, world/player
changes and detached camera/body behavior. These are synthetic JVM checks, not
in-game rendering evidence. The original implementation fails the culling cases.

In-game smoke check: from a stationary detached camera, view a loaded cave across
several sections, toggle Freecam, then repeat with Sodium. Verify disable,
disconnect and dimension changes restore normal visibility. Frustum, render
distance and server-loaded chunk limits still apply.
