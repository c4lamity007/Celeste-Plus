package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleManager;

/** Registers the visual modules and their shared render services. */
public final class VisualExpansion {
    private static boolean registered;

    private VisualExpansion() {
    }

    public static synchronized void register(ModuleManager manager) {
        if (registered) return;
        registered = true;

        TrajectoriesFeature trajectories = new TrajectoriesFeature();
        TrailsFeature trails = new TrailsFeature();
        OreEspExpansionFeature ores = new OreEspExpansionFeature();
        ArrowEspFeature arrows = new ArrowEspFeature();
        ItemEspFeature items = new ItemEspFeature();
        TargetEspFeature targets = new TargetEspFeature();
        JumpCirclesFeature jumps = new JumpCirclesFeature();
        PlayerBoxEspFeature players = new PlayerBoxEspFeature();
        ChamsFeature chams = new ChamsFeature();
        BlockHighlightFeature blockHighlight = new BlockHighlightFeature();
        AmbientEffectsFeature ambient = new AmbientEffectsFeature();
        SkyLyricsFeature lyrics = new SkyLyricsFeature();

        manager.register(new AtmosphereFeature());
        manager.register(trajectories);
        manager.register(trails);
        manager.register(ores);
        manager.register(arrows);
        manager.register(items);
        manager.register(targets);
        manager.register(jumps);
        manager.register(players);
        manager.register(chams);
        manager.register(blockHighlight);
        manager.register(ambient);
        manager.register(lyrics);
        manager.register(new SkyboxFeature());
        manager.register(new CustomFogFeature());
        manager.register(new SwingSpeedFeature());
        manager.register(new HandModelsFeature());
        manager.register(new SwingAnimationFeature());
        manager.register(new HandShadersFeature());
        manager.register(new FpsCounterFeature());
        manager.register(new WatermarkFeature());
        manager.register(new ArrayListFeature());
        manager.register(new FakeGambleFeature());

        VisualExpansionRenderer.initialize(trajectories, trails, ores, arrows, items, targets, jumps, players, chams, blockHighlight, ambient, lyrics);
        SkyboxRenderer.initialize();
        ShaderFogRenderer.initialize();
        HandShadersRenderer.initialize();
    }
}
