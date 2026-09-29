package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.compat.RenderCompatibility;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.block.enums.CameraSubmersionType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;

/** Local presentation only: no time, weather, dimension, world or server mutations. */
public final class SkyboxFeature implements ModuleFeature {
    private static volatile Settings settings = new Settings(false, 0, 0, .85F, .7F);
    private static final JumpCircleAnimation.Clock CLOCK = new JumpCircleAnimation.Clock();

    private record Settings(boolean enabled, long generation, int mode, float brightness, float stars) { }

    @Override public String name() { return "Custom Sky"; }

    @Override public void onEnable(ModuleContext context) {
        Settings previous = settings;
        CLOCK.reset();
        settings = configured(context, previous.generation() + 1);
    }

    @Override public void onStartTick(ModuleContext context) {
        Settings previous = settings;
        settings = configured(context, previous.generation());
    }

    @Override public void onRenderFrame(ModuleContext context) { onStartTick(context); }

    @Override public void onDisable(ModuleContext context) { reset(); }
    @Override public void onWorldChanged(ModuleContext context) { reset(); }

    private static Settings configured(ModuleContext context, long generation) {
        return new Settings(true, generation, SkyboxMath.mode(VisualAtmosphere.sky(context.mode("Mode", "Aurora"))),
            SkyboxMath.finiteClamp(context.slider("Brightness", .85), .85, .35, 1.4),
            SkyboxMath.finiteClamp(context.slider("Stars", .7), .7, 0, 1));
    }

    private static void reset() {
        Settings previous = settings;
        settings = new Settings(false, previous.generation() + 1, previous.mode(), previous.brightness(), previous.stars());
        CLOCK.reset();
    }

    /** Extract immutable presentation values while Minecraft's sky state is being prepared. */
    public static SkyboxRenderer.Frame extract(ClientWorld world, Camera camera, SkyRenderState state) {
        Settings options = settings;
        if (!options.enabled() || RenderCompatibility.isShadowPass() || RenderCompatibility.isShaderPackActive())
            return SkyboxRenderer.Frame.EMPTY;
        MinecraftClient client = MinecraftClient.getInstance();
        // Existing tested clock excludes paused intervals, including the first resumed frame.
        double elapsed = CLOCK.sample(System.nanoTime(), client.isPaused());
        if (world == null || camera == null || state == null || client.world != world || !camera.isReady())
            return SkyboxRenderer.Frame.EMPTY;
        CameraSubmersionType medium = camera.getSubmersionType();
        boolean clearMedium = medium == CameraSubmersionType.NONE || medium == CameraSubmersionType.ATMOSPHERIC;
        boolean normalVision = !(camera.getFocusedEntity() instanceof LivingEntity living)
            || !living.hasStatusEffect(StatusEffects.BLINDNESS) && !living.hasStatusEffect(StatusEffects.DARKNESS);
        normalVision &= !client.inGameHud.getBossBarHud().shouldThickenFog()
            && !client.inGameHud.getBossBarHud().shouldDarkenSky();
        float voidRange = world.getLevelProperties().getVoidDarknessRange();
        boolean aboveVoid = !state.shouldRenderSkyDark && (voidRange <= 0
            || camera.getCameraPos().y >= world.getBottomY() + voidRange);
        boolean overworld = World.OVERWORLD.equals(world.getRegistryKey()) && state.skybox == DimensionType.Skybox.OVERWORLD;
        if (!SkyboxMath.shouldRender(options.enabled() && AuthBootstrap.authorized(), overworld,
            clearMedium, normalVision, aboveVoid)) return SkyboxRenderer.Frame.EMPTY;
        return new SkyboxRenderer.Frame(options.generation(), options.mode(), options.brightness(), options.stars(),
            SkyboxMath.animationPhase((long) (elapsed * 1_000_000_000.0), 0),
            SkyboxMath.finiteClamp(1 - state.rainGradient, 0, 0, 1));
    }

    /** A frame extracted before a disable/world transition must not replace vanilla later. */
    public static boolean isCurrent(SkyboxRenderer.Frame frame) {
        Settings current = settings;
        return frame != null && frame != SkyboxRenderer.Frame.EMPTY && current.enabled()
            && frame.generation() == current.generation() && AuthBootstrap.authorized()
            && !RenderCompatibility.isShadowPass() && !RenderCompatibility.isShaderPackActive();
    }

    /** Fog matches the selected sky palette without importing renderer state or GPU objects. */
    static int fogMode() {
        Settings current = settings;
        return current.enabled() ? current.mode() : 0;
    }
}
