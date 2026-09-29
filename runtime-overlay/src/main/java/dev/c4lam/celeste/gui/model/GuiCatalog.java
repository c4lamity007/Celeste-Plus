package dev.c4lam.celeste.gui.model;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Click GUI catalog. Card blurbs stay short so two-column rows do not ellipsis. */
public final class GuiCatalog {
    private static final GuiSetting.Toggle ATMOSPHERE_ADVANCED = toggle("Advanced",
        "Use the sky and color settings in each module", false);
    private static final GuiModule ATMOSPHERE = new GuiModule("Atmosphere",
        "One palette across enabled visuals", CelestePage.VISUALS, false,
        mode("Preset", "Match your sky, fog and effects", "Abyss", "Aurora", "Nebula", "Sunset", "Abyss", "Black Hole")
            .visibleWhen(() -> !ATMOSPHERE_ADVANCED.value()), ATMOSPHERE_ADVANCED);
    private static final List<GuiModule> MODULES = List.of(
        // Sword
        new GuiModule("Sword Aura", "Silent-aims and hits players in range", CelestePage.SWORD, false,
            slider("Range", "Aim acquisition distance; swings still use vanilla weapon reach", 6, 2, 6, .1),
            mode("Mode", "Locked tracks. Flick snaps on the hit then back", "Locked", "Locked", "Flick"),
            mode("Movefix mode", "Move with your view or silent aim", "Default", "Default", "Anarchy"),
            slider("FOV", "Lock cone from your camera. 180 is all around", 180, 10, 180, 5),
            slider("Speed Min", "Lower bound of silent turn speed", 116, 0, 200, 1),
            slider("Speed Max", "Upper bound of silent turn speed", 200, 0, 200, 1),
            slider("Bezier Curve", "How much the turn eases near the target", 100, 0, 100, 1),
            slider("Smoothness", "How slowly silent aim settles", 100, 0, 100, 1),
            slider("Jitter", "Random micro-movement while tracking", 7, 0, 100, 1),
            slider("Target Variety", "How much the silent aim point wanders on the hitbox", 100, 0, 100, 1),
            slider("Swing Delay Min", "Minimum attack-strength percent required to swing", 90, 90, 100, 1),
            slider("Swing Delay Max", "Maximum attack-strength percent required to swing", 96, 90, 100, 1),
            slider("Delay Ticks Min", "Minimum ticks after a swing before the next", 1, 1, 10, 1),
            slider("Delay Ticks Max", "Maximum ticks after a swing before the next", 2, 1, 10, 1),
            toggle("Prioritize Crits", "Wait for a crit window while airborne", true),
            toggle("Prioritize Sprint", "Wait for sprint while airborne", false),
            toggle("Shield Break No Cooldown", "Axe-swap a blocking target without waiting", true),
            slider("Stun %", "Chance of two rapid axe clicks instead of a normal one-click break", 100, 0, 100, 1),
            slider("Miss %", "Chance to skip a shield-break attempt", 0, 0, 100, 1),
            slider("Stun delay", "Extra milliseconds after a shield is ready to block", 0, 0, 500, 25),
            toggle("Shield silent swap", "Keep the axe swap hidden; restore next tick", true),
            toggle("See Rotations", "Show silent aim on your player in third person", false),
            bind()),
        new GuiModule("Auto Sword", "Picks the sword for attacks", CelestePage.SWORD, false,
            toggle("Prefer axe", "Use an axe while a shield is raised", true),
            slider("Swap delay", "Ticks between weapon policy changes", 1, 0, 10, 1), silentSwap()),
        new GuiModule("Shield Break", "Axe-swaps raised shields", CelestePage.SWORD, false,
            slider("Stun %", "Chance of two rapid axe clicks instead of a normal one-click break", 100, 0, 100, 1),
            slider("Miss %", "Chance to skip a shield-break attempt", 0, 0, 100, 1),
            slider("Stun delay", "Extra milliseconds after a shield is ready to block", 0, 0, 500, 25),
            silentSwap()),
        new GuiModule("Trigger Bot", "Hits the player under your crosshair", CelestePage.SWORD, false,
            bind(),
            slider("Swing Delay Min", "Minimum attack-strength percent required to swing", 90, 90, 100, 1),
            slider("Swing Delay Max", "Maximum attack-strength percent required to swing", 96, 90, 100, 1),
            slider("Reaction Time", "Ticks the crosshair must stay on a player before swinging", 0, 0, 5, 1),
            toggle("Prioritize Crits", "Wait for a crit window while airborne", true),
            toggle("Prioritize Sprint", "Wait for sprint while airborne", false),
            toggle("Shield Break No Cooldown", "Axe-swap a blocking target without waiting", true)),
        new GuiModule("Aim Assist", "Smooths your aim onto players", CelestePage.SWORD, false,
            bind(),
            slider("Speed Min", "Lower bound of sampled turn speed", 56, 0, 200, 1),
            slider("Speed Max", "Upper bound of sampled turn speed", 124, 0, 200, 1),
            slider("Bezier Curve", "How much the turn eases near the target", 54, 0, 100, 1),
            slider("Smoothness", "How slowly the assist settles on target", 68, 0, 100, 1),
            slider("Jitter", "Random micro-movement while tracking", 0, 0, 100, 1),
            slider("Target Variety", "How much the aim point wanders on the hitbox", 64, 0, 100, 1),
            toggle("Only Weapons", "Only assist while holding a sword, axe, mace, spear, or trident", false)),
        new GuiModule("W Tap", "Releases W after a hit", CelestePage.SWORD, false,
            slider("Duration ticks", "Forward release duration", 1, 1, 4, 1),
            toggle("Ground only", "Only reset while grounded", true)),
        new GuiModule("S Tap", "Taps S after a hit", CelestePage.SWORD, false,
            slider("Duration ticks", "Backward tap duration", 1, 1, 4, 1),
            toggle("Ground only", "Only tap while grounded", true)),
        new GuiModule("Jump Reset", "Jumps after taking knockback", CelestePage.SWORD, false,
            slider("Chance", "Chance to perform a reset", 100, 0, 100, 1),
            slider("Delay ticks", "Ticks before the jump", 0, 0, 3, 1)),
        new GuiModule("Anti Miss", "Blocks swings that miss", CelestePage.SWORD, false,
            toggle("Protect blocks", "Do not suppress mining clicks", true)),
        new GuiModule("Fake Lag", "Buffers movement while it keeps you farther away", CelestePage.SWORD, false,
            mode("Mode", "Dynamic only lags when the stale position helps", "Dynamic", "Dynamic", "Constant"),
            slider("Range", "Opponent range for Dynamic mode", 5, 2, 12, .25),
            range("Delay", "Latency per episode; original packet timing is preserved", 200, 400, 50, 500, 25),
            slider("Recoil", "Milliseconds before lagging resumes after an action", 250, 0, 1000, 25),
            toggle("Flush attacks", "Release before attacking or interacting with an entity", true),
            toggle("Flush blocks", "Release before using a block", true),
            toggle("Flush actions", "Release before swinging or using an item", true),
            toggle("Release on damage", "Release the buffer when you are hurt", true),
            mode("Visual style", "Marker at your last transmitted position", "Ghost", "Ghost", "Box", "Rings", "Off"),
            colorPick("Visual color", "Marker and path accent", 109, 228, 255),
            toggle("Trail", "Fading path of buffered movement", true),
            toggle("Tether", "Connect the marker to your current position", true),
            new GuiSetting.Keybind("Cancel", "Release buffered packets and disable", GLFW.GLFW_KEY_N),
            bind()),
        new GuiModule("Breach Swap", "Swaps to a Breach mace", CelestePage.MACE, false,
            toggle("Ground only", "Swap only while grounded", true),
            toggle("Sword only", "Require a sword in the visible hand", true), silentSwap()),
        new GuiModule("Totem Hit", "Swords once while holding totem", CelestePage.CRYSTAL, false,
            slider("Range", "Maximum target distance", 3.25, 2, 4.5, .05), silentSwap()),
        new GuiModule("Lunge Swap", "Jabs with a Lunge weapon", CelestePage.MACE, false,
            slider("Range", "Maximum jab distance", 3.35, 2, 4.5, .05),
            toggle("Ground only", "Require a grounded jab", false),
            slider("Swap back delay", "Ticks before swapping back", 1, 1, 20, 1),
            slider("Cooldown", "Extra ticks after a jab", 0, 0, 40, 1),
            actionKey(GLFW.GLFW_KEY_G), silentSwap()),

        // Mace / survival combat (11)
        new GuiModule("Mace Aura", "Smashes players in range", CelestePage.MACE, false,
            slider("Range", "Early swing past 3 blocks", 3, 2, 6, .1),
            slider("FOV", "Maximum silent aim field of view", 60, 10, 180, 5),
            mode("Mode", "When to perform silent aim", "Mace only", "Mace only", "Weapon only", "All items"),
            mode("Target", "Target selection order", "Distance", "Distance", "Health", "Angle"),
            toggle("Fall check", "Require a valid smash-attack fall", true),
            slider("Mace cooldown", "Milliseconds between Auto Mace attacks", 100, 0, 1000, 25)),
        new GuiModule("Wind Charge", "Throws a wind charge", CelestePage.MACE, false,
            silentSwap(),
            slider("Delay", "Ticks between charge actions", 2, 0, 10, 1)),
        new GuiModule("Catch", "Pearl-catches with an upward wind charge", CelestePage.MACE, false,
            actionKey(GLFW.GLFW_KEY_V),
            range("Charge delay", "Milliseconds between the pearl and charge", 200, 300, 50, 750, 10),
            range("Return delay", "Milliseconds before restoring the visible slot", 50, 100, 0, 500, 10),
            toggle("Return slot", "Restore the slot held before the catch", true),
            silentSwap()),
        new GuiModule("Horizontal Pearl Catch", "Predicts a wind-charge catch along your pearl throw", CelestePage.MACE, false,
            actionKey(GLFW.GLFW_KEY_UNKNOWN),
            toggle("Silent aim", "Keep the camera unchanged while aiming the charge", false),
            toggle("Return slot", "Restore the slot held before a visible catch swap", true),
            silentSwap()),
        new GuiModule("Mace Swap", "Swaps to mace for one hit", CelestePage.MACE, false,
            silentSwap(),
            toggle("Auto stun slam", "On a raised shield: axe hit, then next-tick mace hit", false)),
        new GuiModule("Smash Guide", "Shows smash-hit height", CelestePage.MACE, false,
            toggle("Show height", "Include fall distance in the HUD", true)),
        new GuiModule("Auto Pot", "Splashes pots at low health", CelestePage.SWORD, false,
            slider("Health", "Health threshold", 10, 2, 20, .5),
            slider("Delay", "Ticks between attempts", 10, 1, 40, 1), silentSwap()),
        new GuiModule("Throw Pot", "Throws pots on a key", CelestePage.SWORD, false,
            slider("Health", "Optional maximum health", 20, 2, 20, .5),
            slider("Count", "Maximum potions per action", 1, 1, 3, 1),
            slider("Delay", "Ticks between throws", 4, 1, 20, 1),
            actionKey(GLFW.GLFW_KEY_UNKNOWN), silentSwap()),
        new GuiModule("Auto Extinguish", "Dumps water when on fire", CelestePage.SWORD, false,
            slider("Delay", "Ticks between bucket actions", 2, 0, 20, 1),
            toggle("Pick up", "Collect placed water afterward", true), silentSwap()),
        new GuiModule("Auto Double Hand", "Puts a totem in main hand", CelestePage.CRYSTAL, false,
            slider("Health", "Health threshold", 8, 1, 20, .5),
            toggle("Falling", "Also respond to dangerous falls", true),
            slider("Fall distance", "Dangerous fall threshold", 8, 3, 30, 1)),
        new GuiModule("Elytra Swap", "Equips or removes elytra", CelestePage.MACE, false,
            silentSwap()),
        new GuiModule("Hover Totem", "Clicks a hovered totem", CelestePage.CRYSTAL, false,
            slider("Delay", "Ticks the cursor must remain stable", 3, 1, 20, 1),
            toggle("Replace offhand", "Allow replacing the current offhand", false),
            slider("Hotbar slot", "Chosen reserve slot; replaces its current item when it has no totem", 3, 1, 9, 1)),
        legitAutoTotem(),

        // Crystal / placement and inventory utilities (14)
        new GuiModule("Auto Crystal Aura", "Silent sword pressure and crystal follow-ups", CelestePage.CRYSTAL, false,
            slider("Range", "Target search distance; actions stay within interaction reach", 6, 2, 10, .25),
            slider("Min damage", "Minimum estimated explosion damage to the target", 4, 0, 20, .5),
            slider("Max self damage", "Maximum estimated explosion damage to you", 6, 0, 20, .5),
            slider("Delay", "Minimum ticks between primary actions", 2, 1, 10, 1)),
        new GuiModule("Surround", "Obsidians around your feet", CelestePage.CRYSTAL, false,
            mode("Blocks", "Preferred hotbar block", "Obsidian", "Obsidian", "Ender Chest"),
            slider("Range", "Maximum support-face reach", 4.5, 2, 4.5, .1),
            slider("Delay", "Ticks between placements", 1, 1, 10, 1), silentSwap()),
        new GuiModule("Auto Trap", "Boxes players in obsidian", CelestePage.CRYSTAL, false,
            slider("Range", "Maximum target and support-face reach", 4.5, 2, 6, .1),
            silentSwap()),
        new GuiModule("Anchor Aura", "Silently places, charges and detonates nearby anchors", CelestePage.CRYSTAL, false,
            slider("Range", "Target search distance; actions stay within interaction reach", 6, 2, 10, .25),
            slider("Min damage", "Minimum estimated explosion damage to the target", 4, 0, 20, .5),
            slider("Max self damage", "Maximum estimated explosion damage to you", 6, 0, 20, .5),
            slider("Delay", "Minimum ticks between primary actions", 2, 1, 10, 1),
            toggle("Preview", "Shader highlight of the next planned anchor", true)),
        new GuiModule("Auto Crystal", "Places and breaks crystals", CelestePage.CRYSTAL, false,
            slider("Place delay min", "Minimum ticks after a placement", 1, 1, 10, 1),
            slider("Place delay max", "Maximum ticks after a placement", 2, 1, 10, 1),
            slider("Break delay min", "Minimum ticks after a break", 1, 1, 10, 1),
            slider("Break delay max", "Maximum ticks after a break", 2, 1, 10, 1),
            toggle("Damage tick", "Pause while the tracked target is still hurt", true),
            toggle("Stop on kill", "Latch the sequence after the tracked target dies", true)),
        new GuiModule("Auto Hit Crystal", "Breaks crystals and bases", CelestePage.CRYSTAL, false,
            slider("2 tick swap chance", "Chance a slot swap gets its own tick", 18, 0, 100, 1),
            silentSwap()),
        new GuiModule("Anchor Macro", "Charges and blows anchors", CelestePage.CRYSTAL, false,
            slider("2 tick swap chance", "Chance a slot swap gets its own tick", 18, 0, 100, 1),
            slider("Double glowstone", "Chance to charge a new anchor twice", 0, 0, 100, 1),
            toggle("Auto swap anchor", "Select an anchor when one must be placed", true),
            toggle("Only own anchors", "Only operate anchors confirmed as yours", true),
            toggle("Hold to loop", "Start another sequence while Use remains held", false),
            silentSwap()),
        new GuiModule("Double Anchor Macro", "Requires Anchor Macro", CelestePage.CRYSTAL, false,
            new GuiSetting.Keybind("Hold", "One double sequence per hold", GLFW.GLFW_KEY_UNKNOWN), bind()),
        safeAnchor(true),
        safeAnchor(false),
        new GuiModule("Silent Totem", "Refills totems quietly", CelestePage.CRYSTAL, false,
            slider("Hotbar slot", "Reserve hotbar slot to fill after the offhand", 3, 1, 9, 1),
            toggle("Only after pop", "Only refill after the offhand totem is consumed", false)),
        new GuiModule("Auto Web", "Places cobwebs on a key", CelestePage.SWORD, false,
            actionKey(GLFW.GLFW_KEY_B), slider("Count", "Maximum placements", 1, 1, 3, 1),
            slider("Delay", "Ticks between placements", 2, 1, 10, 1), silentSwap()),
        new GuiModule("Auto Drain", "Flicks to bucket nearby water or lava", CelestePage.SWORD, false,
            slider("Delay", "Ticks between collection attempts", 4, 1, 20, 1), silentSwap()),
        new GuiModule("Web Breaker", "Waters you out of webs", CelestePage.SWORD, false,
            toggle("Pick up", "Collect placed water afterward", true), silentSwap()),
        new GuiModule("Potion Refill", "Refills splash pots", CelestePage.SWORD, false,
            mode("Mode", "How potions are chosen", "Automatic", "Hover", "Automatic"),
            slider("Strength Slot", "Hotbar slot for strength (0 off)", 2, 0, 9, 1),
            slider("Speed Slot", "Hotbar slot for speed (0 off)", 3, 0, 9, 1),
            range("Health Slots", "Hotbar range for health pots", 4, 9, 1, 9, 1),
            slider("Delay", "Ticks between automatic swaps", 3, 1, 20, 1),
            slider("CPS Min", "Minimum hover swaps per second", 8, 1, 20, 1),
            slider("CPS Max", "Maximum hover swaps per second", 12, 1, 20, 1)),
        new GuiModule("Fast Place", "Repeats block place clicks", CelestePage.CRYSTAL, false,
            slider("Delay", "Ticks between repeated use clicks", 1, 0, 4, 1),
            toggle("Blocks only", "Only repeat while holding a block item", true)),
        new GuiModule("Fast EXP", "Spam-uses XP bottles", CelestePage.CRYSTAL, false,
            slider("Delay", "Ticks between repeated use clicks", 0, 0, 4, 1)),
        // Movement / player helpers
        new GuiModule("Freecam", "Fly the camera; keep actions at your player", CelestePage.MOVEMENT, false,
            slider("Speed", "Camera speed in blocks per second", 10, 1, 30, 1), bind()),
        new GuiModule("Sprint", "Holds sprint for you", CelestePage.MOVEMENT, false),
        new GuiModule("Safe Walk", "Sneaks at block edges", CelestePage.MOVEMENT, false,
            slider("Look ahead", "Distance beyond the leading foot", .15, .05, .4, .05),
            toggle("Require blocks", "Only protect while holding a block", false)),
        new GuiModule("Elytra", "Locks elytra travel pitch", CelestePage.MOVEMENT, false,
            slider("Pitch", "Preferred travel pitch", -2, -45, 45, 1)),
        new GuiModule("Auto Head Hitter", "Jumps under low ceilings", CelestePage.MOVEMENT, false,
            slider("Delay", "Ticks between jumps", 5, 0, 20, 1)),
        new GuiModule("Auto Firework", "Boosts with fireworks", CelestePage.MOVEMENT, false,
            silentSwap()),
        new GuiModule("Auto Tool", "Swaps to the fastest tool", CelestePage.MOVEMENT, false,
            slider("Min durability", "Minimum remaining durability", 10, 0, 100, 1),
            slider("Restore delay", "Visible-swap restore delay in ticks", 1, 0, 10, 1), silentSwap()),
        new GuiModule("Keep Sprint", "Keeps sprint after hits", CelestePage.MOVEMENT, false),
        new GuiModule("Auto MLG", "Waters a deadly fall", CelestePage.MOVEMENT, false,
            slider("Fall distance", "Minimum fall distance", 6, 3, 30, 1),
            toggle("Ignore windcharge", "Skip when windcharge blocks fall damage", true),
            toggle("Pick up", "Collect placed water afterward", true), silentSwap()),
        new GuiModule("Cover Up", "Drops cobwebs on yourself", CelestePage.MOVEMENT, false,
            slider("Delay", "Ticks between placements", 2, 1, 10, 1), silentSwap()),
        new GuiModule("Trap Save", "Warns of nearby traps", CelestePage.MOVEMENT, false,
            slider("Range", "Warning scan radius", 5, 2, 8, 1)),

        // Visuals
        ATMOSPHERE,
        new GuiModule("Fullbright", "Lights up the world", CelestePage.VISUALS, false,
            mode("Mode", "Brightness technique", "Gamma", "Gamma", "Night Vision"),
            slider("Strength", "Amount of additional light", 1, 0, 1, .05),
            slider("Transition", "Brightness fade duration in milliseconds", 300, 0, 1500, 25)),
        playerModels(),
        new GuiModule("Custom Sky", "Seamless celestial skies with luminous atmospheric depth", CelestePage.VISUALS, false,
            mode("Mode", "Sky atmosphere", "Aurora", "Aurora", "Nebula", "Sunset", "Abyss", "Black Hole")
                .visibleWhen(GuiCatalog::individualAtmosphereSettings),
            slider("Brightness", "Sky illumination", .85, .35, 1.4, .05),
            slider("Stars", "Celestial detail", .7, 0, 1, .05)),
        customFog(),
        new GuiModule("Hit Effects", "Particles, ground trails and a small ripple when you hit", CelestePage.VISUALS, false,
            toggle("Particles", "Burst at the target", true),
            toggle("Ground Effect", "Light trails along block edges", false),
            toggle("Displacement", "Brief distortion at the crosshair", false),
            mode("Particle Style", "Particle shape", "Sparks", "Sparks", "Stars", "Dots"),
            slider("Amount", "Particles per hit", 12, 4, 24, 1),
            slider("Size", "Effect size", 1, .5, 2, .1),
            atmosphereColor("Color", "Effect tint", 75, 210, 255)),
        new GuiModule("Ground Shaders", "Soft ambient color across the terrain", CelestePage.VISUALS, false,
            slider("Intensity", "Color strength", .35, 0, 1, .05),
            atmosphereColor("Color", "Terrain tint", 46, 175, 214)),
        new GuiModule("Ambient Effects", "Leaves, glowing motes and small floating shapes", CelestePage.VISUALS, false,
            mode("Style", "Ambient geometry", "Fireflies", "Fireflies", "Crystal Motes", "Snowfall", "Falling Leaves", "Tesseracts", "Tron Trails"),
            slider("Density", "Amount of ambient detail", .6, 0, 1, .05),
            slider("Size", "Ambient element size", 1, .5, 2, .1),
            toggle("Sky Lines", "Slow luminous arcs above the horizon", true),
            mode("Sky Pattern", "Sky line design", "Orbital Ribbons", "Orbital Ribbons", "Constellation Arcs", "Light Rails"),
            slider("Sky Intensity", "Sky line brightness", .65, 0, 1, .05),
            slider("Sky Speed", "Sky line motion", .6, 0, 2, .1)),
        new GuiModule("Sky Lyrics", "One floating lyric line that dissolves into light as the song advances", CelestePage.VISUALS, false,
            colorPick("Color", "Lyric text color", 244, 240, 255),
            mode("Gradient", "Blend across the fixed lyric line", "Solid", "Solid", "Horizontal", "Vertical"),
            colorPick("End Color", "Second gradient color", 156, 229, 242),
            slider("Distance", "Blocks from your view; applies when the next phrase begins", 18, 2, 64, 1),
            slider("Size", "Lyric scale", 1, .5, 2, .1),
            slider("Opacity", "Text visibility", .9, .1, 1, .05)),
        dev.c4lam.celeste.module.impl.visual.BlockEspSettings.storage(),
        dev.c4lam.celeste.module.impl.visual.BlockEspSettings.blocks(),
        new GuiModule("Block Highlight", "Highlight the block you're looking at", CelestePage.VISUALS, false,
            colorPick("Color", "Highlight color", 116, 223, 255),
            slider("Opacity", "Unified block fill visibility", .35, .05, 1, .05)),
        new GuiModule("Player ESP", "Glowing boxes around players", CelestePage.VISUALS, false,
            slider("Range", "Maximum player render distance", 64, 8, 128, 4),
            mode("Theme", "Box accent palette", "Ice", "Celeste", "Ice", "Sunset", "Mono", "Custom")
                .visibleWhen(GuiCatalog::individualAtmosphereSettings),
            atmosphereColor("Color", "Custom box color", 116, 223, 255),
            slider("Opacity", "Box visibility", .72, .1, 1, .05)),
        new GuiModule("Chams", "Animated player silhouettes", CelestePage.VISUALS, false,
            mode("Material", "Surface style", "Pearl", "Pearl", "Flow", "Mist", "Glow"),
            slider("Range", "Maximum silhouette distance", 64, 8, 96, 4),
            mode("Color mode", "Silhouette palette", "Celeste", "Celeste", "Ice", "Sunset", "Mono", "Custom")
                .visibleWhen(GuiCatalog::individualAtmosphereSettings),
            atmosphereColor("Color", "Custom silhouette color", 116, 223, 255),
            slider("Opacity", "Effect visibility", .68, .2, .95, .05),
            toggle("Self", "Show your silhouette in third person", false),
            toggle("Invisible", "Include invisible players", false)),
        new GuiModule("Entity ESP", "Clean silhouettes around players", CelestePage.VISUALS, false,
            slider("Range", "Maximum player render distance", 64, 8, 128, 4),
            mode("Style", "Player marker geometry", "Corners", "Corners", "Box"),
            mode("Color mode", "Accent source for each player", "Celeste", "Celeste", "Health", "Custom"),
            colorPick("Color", "Custom marker color", 255, 79, 163),
            slider("Opacity", "Marker opacity", .85, .1, 1, .05),
            slider("Fill opacity", "Subtle translucent body fill", .07, 0, .35, .01),
            slider("Line width", "Edge weight", 1.65, .75, 3, .05),
            toggle("Glow", "Soft outer edge", true), toggle("Pulse", "Gently breathe the marker", false)),
        holeEsp(),
        new GuiModule("Trajectories", "Projectile paths and landing points", CelestePage.VISUALS, false,
            mode("Path style", "Path appearance", "Normal", "Normal", "Glow"),
            toggle("Pearls", "Show pearl paths", true), toggle("Bows", "Show arrow paths", true),
            toggle("Others", "Show flying pearls and arrows from others", true),
            toggle("Landing", "Mark the predicted landing", true), slider("Steps", "Simulation budget", 80, 40, 160, 10),
            visualTheme(), atmosphereColor("Color", "Custom path color", 255, 79, 163),
            slider("Glow", "Path glow", .55, 0, 1, .05),
            slider("Opacity", "Path visibility", .9, .1, 1, .05),
            toggle("Trail fade", "Fade along the path", true)),
        new GuiModule("Trails", "Soft light behind you", CelestePage.VISUALS, false,
            slider("Length", "Seconds before the trail fades", 1.5, .5, 3, .1),
            slider("Size", "Trail width", .2, .05, .45, .01),
            slider("Opacity", "Trail visibility", .8, .1, 1, .05),
            toggle("First person", "Show your trail in first person", false),
            visualTheme(), atmosphereColor("Color", "Custom trail color", 255, 79, 163)),
        oreEsp(),
        new GuiModule("Arrow ESP", "Arrows toward players", CelestePage.VISUALS, false,
            slider("Range", "Maximum render distance", 64, 16, 128, 4),
            slider("Opacity", "Indicator opacity", .8, .1, 1, .05),
            mode("Arrow Style", "Arrow style", "Arrow 2", "Arrow 2", "Arrow 3"),
            slider("Radius", "Distance from the crosshair", 58, 28, 120, 2),
            slider("Size", "Arrow size", 22, 12, 36, 1),
            slider("Glow", "Soft directional halo", .45, 0, 1, .05),
            toggle("Distance fade", "Fade distant players gently", true),
            toggle("Show distance", "Distance below each indicator", false),
            colorPick("Color", "Arrow color", 255, 79, 163)),
        itemEsp(),
        new GuiModule("Target ESP", "Animated focus around your target", CelestePage.VISUALS, false,
            slider("Range", "Maximum target distance", 64, 8, 128, 4),
            mode("Style", "Target marker", "Box", "Box", "Crystals", "Cubes", "Sparks"),
            visualTheme(), atmosphereColor("Color", "Custom target color", 255, 79, 163),
            slider("Speed", "Animation speed", 1, .2, 3, .1),
            slider("Radius", "Marker radius around the target", 1, .7, 1.6, .05),
            slider("Glow", "Soft halo strength", .65, 0, 1, .05),
            slider("Opacity", "Highlight opacity", .86, .1, 1, .05)),
        new GuiModule("Jump Circles", "Rings and water waves where you jump", CelestePage.VISUALS, false,
            mode("Style", "Glow rings or 3D water splashes", "Glow", "Glow", "Lean", "Classic Glow", "Splash"),
            slider("Size", "Maximum effect radius in blocks", 1.8, .6, 3, .1),
            slider("Duration", "Effect lifetime in seconds", 1.2, .5, 2.5, .1),
            slider("Intensity", "Ring visibility", .85, .2, 1, .05),
            toggle("Shrink", "Shrink as the ring fades", false)),
        new GuiModule("Hand Models", "Move and resize your held items", CelestePage.VISUALS, false,
            new GuiSetting.Action("Edit positions", "Drag your held items into place", dev.c4lam.celeste.gui.HandModelsEditorScreen::open),
            slider("Right X", "Right hand horizontal position", .72, .08, .92, .001).hidden(),
            slider("Right Y", "Right hand vertical position", .78, .08, .92, .001).hidden(),
            slider("Left X", "Left hand horizontal position", .28, .08, .92, .001).hidden(),
            slider("Left Y", "Left hand vertical position", .78, .08, .92, .001).hidden(),
            slider("Right Size", "Right hand model size", 1, .50, 1.50, .05).hidden(),
            slider("Left Size", "Left hand model size", 1, .50, 1.50, .05).hidden()),
        new GuiModule("Swing Animations", "Change how your hand swings", CelestePage.VISUALS, false,
            mode("Style", "Swing motion", "Smooth", "Smooth", "Slice", "Sweep", "Thrust", "Spin"),
            slider("Strength", "Amount of motion", 40, 0, 100, 1),
            toggle("Affect position", "Move the item from its Hand Models position", true)),
        new GuiModule("Hand Shaders", "Smoke and flame around held items", CelestePage.VISUALS, false,
            mode("Mode", "Effect style", "Smoke", "Smoke", "Flame"),
            atmosphereColor("Color", "Effect color", 116, 223, 255),
            slider("Opacity", "Effect opacity", .85, .05, 1, .01),
            slider("Glow strength", "Glow brightness", .75, 0, 2, .05)),
        swingSpeed(),
        new GuiModule("FPS Counter", "FPS, speed and server tick rate", CelestePage.VISUALS, false),
        new GuiModule("Watermark", "Shows the Celeste mark", CelestePage.VISUALS, false),
        new GuiModule("Array List", "Enabled modules", CelestePage.VISUALS, false,
            colorPick("Gradient start", "First color", 255, 79, 163),
            colorPick("Gradient end", "Second color", 169, 135, 255)),
        new GuiModule("Fake Gamble", "Middle-click dispensers to mark them", CelestePage.VISUALS, false,
            toggle("Selection markers", "Highlight the two selected dispensers", true),
            toggle("Round display", "Show the local round preview", true),
            slider("Display duration", "Seconds to show the preview", 3.5, 1, 8, .5),
            slider("1 Win %", "Odds the first middle-clicked dispenser gets the higher number. Leftover after 1+2 is the tie roll", 50, 0, 100, 1),
            slider("2 Win %", "Odds the second middle-clicked dispenser gets the higher number", 50, 0, 100, 1),
            mode("Tie", "If the leftover tie % hits: first middle-click wins, reroll, or loses", "Reroll", "Win", "Reroll", "Lose"),
            itemPick("1", "Dispenser slot 1. Click while holding an item to set. Always named 1", "minecraft:dirt"),
            itemPick("2", "Dispenser slot 2. Click while holding an item to set. Always named 2", "minecraft:oak_log"),
            itemPick("3", "Dispenser slot 3. Click while holding an item to set. Always named 3", "minecraft:oak_planks"),
            itemPick("4", "Dispenser slot 4. Click while holding an item to set. Always named 4", "minecraft:iron_ore"),
            itemPick("5", "Dispenser slot 5. Click while holding an item to set. Always named 5", "minecraft:iron_block"),
            itemPick("6", "Dispenser slot 6. Click while holding an item to set. Always named 6", "minecraft:diamond_ore"),
            itemPick("7", "Dispenser slot 7. Click while holding an item to set. Always named 7", "minecraft:netherite_sword"),
            itemPick("8", "Dispenser slot 8. Click while holding an item to set. Always named 8", "minecraft:trident"),
            itemPick("9", "Dispenser slot 9. Click while holding an item to set. Always named 9", "minecraft:mace"))
        );
    private GuiCatalog() { }
    public static List<GuiModule> modules() { return MODULES; }

    private static GuiModule legitAutoTotem() {
        GuiSetting.Toggle autoOpen = toggle("Auto open", "Open the visible inventory after local totem pops", true);
        return new GuiModule("Legit Auto Totem", "Visible cursor refill, offhand first then hotbar", CelestePage.CRYSTAL, false,
            slider("Hotbar slot", "Reserve slot to fill after offhand; replaces non-totems", 3, 1, 9, 1),
            slider("Speed", "Cursor speed in GUI pixels per second", 650, 100, 2000, 50),
            slider("Reaction", "Milliseconds on each totem before its keybind; keeps moving gently", 80, 0, 500, 10),
            autoOpen,
            slider("Open after pops", "Local totem activations before automatically opening", 1, 1, 2, 1)
                .visibleWhen(autoOpen::value),
            toggle("Auto close", "Close after a refill only when offhand and the chosen hotbar slot both contain totems", true));
    }

    private static GuiModule itemEsp() {
        GuiSetting.Mode style = mode("Style", "Marker style", "Item Highlight", "Box", "Item Highlight", "Both");
        return new GuiModule("Item ESP", "Marks dropped items", CelestePage.VISUALS, false,
            slider("Range", "Maximum render distance", 64, 8, 128, 4), style,
            visualTheme(), atmosphereColor("Color", "Custom item color", 240, 155, 208),
            slider("Opacity", "Box marker opacity", .76, .1, 1, .05)
                .visibleWhen(() -> !style.value().equals("Item Highlight")));
    }

    private static GuiModule customFog() {
        GuiSetting.Mode rendering = mode("Rendering", "Shader atmosphere or classic distance fog", "Shader", "Shader", "Native")
            .visibleWhen(GuiCatalog::individualAtmosphereSettings);
        return new GuiModule("Custom Fog", "Depth-aware shader atmospheres that match your custom sky", CelestePage.VISUALS, false,
            rendering,
            mode("Shader", "Match Custom Sky automatically, or choose a fog shader", "Match Sky", "Match Sky", "Aurora", "Nebula", "Sunset", "Abyss", "Black Hole")
                .visibleWhen(() -> individualAtmosphereSettings() && rendering.value().equals("Shader")),
            mode("Style", "Palette when Rendering is Native", "Mist", "Mist", "Dusk", "Frost")
                .visibleWhen(() -> individualAtmosphereSettings() && rendering.value().equals("Native")),
            slider("Density", "Atmospheric depth", .45, 0, 1, .05),
            slider("Distance", "Fog distance in blocks", 160, 32, 512, 16));
    }

    private static GuiModule holeEsp() {
        GuiSetting.Mode style = mode("Style", "Safe-hole marker geometry", "Footprint", "Footprint", "Box", "Beacon");
        return new GuiModule("Hole ESP", "Marks nearby holes", CelestePage.VISUALS, false,
            slider("Range", "Incremental scan radius", 8, 4, 16, 1),
            toggle("Obsidian", "Render obsidian holes", true), toggle("Bedrock", "Render bedrock holes", true), style,
            slider("Opacity", "Marker opacity", .8, .1, 1, .05),
            slider("Fill opacity", "Floor tint strength", .14, 0, .4, .01),
            slider("Height", "Box and beacon height", .6, .15, 1.5, .05)
                .visibleWhen(() -> !style.value().equals("Footprint")),
            slider("Line width", "Edge weight", 1.5, .75, 3, .05),
            toggle("Glow", "Soft outer edge", true),
            colorPick("Obsidian color", "Obsidian hole accent", 215, 96, 255),
            colorPick("Bedrock color", "Bedrock hole accent", 104, 228, 255));
    }

    private static GuiModule oreEsp() {
        GuiSetting.Mode style = mode("Style", "Ore marker geometry", "Corners", "Corners", "Box", "Diamond");
        return new GuiModule("Ore ESP", "Highlights nearby ores", CelestePage.VISUALS, false,
            slider("Range", "Incremental scan radius", 16, 4, 32, 1), style,
            mode("Colors", "Ore-specific or shared palette", "Ore", "Ore", "Celeste", "Ice", "Mono", "Custom"),
            colorPick("Color", "Custom ore accent", 255, 79, 163),
            slider("Opacity", "Marker visibility", .85, .1, 1, .05),
            slider("Fill opacity", "Subtle ore tint", .06, 0, .3, .01)
                .visibleWhen(() -> !style.value().equals("Diamond")),
            slider("Line width", "Marker edge weight", 1.2, .75, 3, .05),
            slider("Glow", "Soft edges on nearby ores", .35, 0, 1, .05),
            toggle("Diamond", "Show diamond ore", true), toggle("Emerald", "Show emerald ore", true),
            toggle("Gold", "Show gold ore", true), toggle("Iron", "Show iron ore", true),
            toggle("Ancient Debris", "Show ancient debris", true), toggle("Copper", "Show copper ore", true),
            toggle("Redstone", "Show redstone ore", true), toggle("Lapis", "Show lapis ore", true),
            toggle("Coal", "Show coal ore", true), toggle("Nether Gold", "Show nether gold ore", false),
            toggle("Quartz", "Show nether quartz ore", false));
    }

    private static GuiModule swingSpeed() {
        GuiSetting.Mode mode = mode("Mode", "Visible swing timing", "Custom", "Custom", "Vanilla", "Cinematic", "Snappy");
        return new GuiModule("Swing Speed", "Changes swing animation", CelestePage.VISUALS, false,
            mode, slider("Speed", "Custom swing duration in ticks", 6, 1, 20, 1)
                .visibleWhen(() -> mode.value().equals("Custom")),
            toggle("Ease changes", "Apply new timing between swings", true));
    }

    private static GuiModule playerModels() {
        var settings = new java.util.ArrayList<GuiSetting>();
        if (dev.c4lam.celeste.render.model.BundledPlayerModel.available()) {
            settings.add(new GuiSetting.Action("Load Sahur", "Use Sahur with walking and post-kill celebrations",
                dev.c4lam.celeste.module.impl.visual.PlayerModelsFeature::loadSahur));
        }
        settings.add(new GuiSetting.Action("Load model", "Choose a local .glb with embedded textures and animations",
            dev.c4lam.celeste.gui.ModelImportScreen::open));
        settings.add(mode("Apply to", "Players whose appearance is replaced", "Self", "Self", "Others", "Everyone"));
        settings.add(slider("Size", "Model height relative to a player", 1, .25, 3, .05));
        settings.add(toggle("Secondary motion", "Movement physics on supported paired secondary bones", true));
        settings.add(slider("Motion strength", "Secondary bone motion strength", .5, 0, 1, .05));
        settings.add(toggle("Kill animations", "Play a random clip after a recent target dies, even while moving", true));
        return new GuiModule("Player Models", "Rigged models and animated player appearances", CelestePage.VISUALS,
            false, settings.toArray(GuiSetting[]::new));
    }

    private static GuiModule safeAnchor(boolean silent) {
        return new GuiModule(silent ? "Silent Safe Anchor" : "Safe Anchor",
            silent ? "Adds glowstone cover; silent aim" : "Adds glowstone cover; visible aim",
            CelestePage.CRYSTAL, false,
            slider("2 tick swap chance", "Chance a slot swap gets its own tick", 18, 0, 100, 1),
            toggle("Auto swap anchor", "Select an anchor when one must be placed", true),
            toggle("Only own anchors", "Only operate anchors confirmed as yours", true),
            toggle("Hold to loop", "Start another sequence while Use remains held", false),
            silentSwap(), bind());
    }

    private static GuiSetting.Toggle toggle(String label, String description, boolean value) {
        return new GuiSetting.Toggle(label, description, value);
    }
    private static GuiSetting.Toggle silentSwap() {
        return toggle("Silent swap", "On: server only. Off: visible vanilla swap", true);
    }
    private static GuiSetting.Slider slider(String label, String description, double value, double min, double max, double step) {
        return new GuiSetting.Slider(label, description, value, min, max, step);
    }
    private static GuiSetting.Range range(String label, String description, double low, double high, double min, double max, double step) {
        return new GuiSetting.Range(label, description, low, high, min, max, step);
    }
    private static GuiSetting.Mode mode(String label, String description, String selected, String... values) {
        return new GuiSetting.Mode(label, description, List.of(values), selected);
    }
    private static boolean individualAtmosphereSettings() {
        return !ATMOSPHERE.enabled() || ATMOSPHERE_ADVANCED.value();
    }
    private static GuiSetting.ColorPick atmosphereColor(String label, String description, int red, int green, int blue) {
        return colorPick(label, description, red, green, blue).visibleWhen(GuiCatalog::individualAtmosphereSettings);
    }
    private static GuiSetting.Mode visualTheme() {
        return mode("Theme", "Visual accent palette", "Celeste", "Celeste", "Ice", "Sunset", "Mono", "Custom")
            .visibleWhen(GuiCatalog::individualAtmosphereSettings);
    }
    private static GuiSetting.Keybind bind() {
        return new GuiSetting.Keybind("Bind", "Toggle this module", GLFW.GLFW_KEY_UNKNOWN);
    }
    private static GuiSetting.Keybind actionKey(int key) {
        return new GuiSetting.Keybind("Action", "Perform this module's action", key);
    }
    private static GuiSetting[] actionKey(int key, GuiSetting... rest) {
        GuiSetting[] settings = new GuiSetting[rest.length + 1];
        settings[0] = actionKey(key);
        System.arraycopy(rest, 0, settings, 1, rest.length);
        return settings;
    }

    private static GuiSetting.ColorPick colorPick(String label, String description, int red, int green, int blue) {
        return new GuiSetting.ColorPick(label, description, red, green, blue);
    }

    private static GuiSetting itemPick(String label, String description, String itemId) {
        return new GuiSetting.ItemPick(label, description, itemId);
    }
}
