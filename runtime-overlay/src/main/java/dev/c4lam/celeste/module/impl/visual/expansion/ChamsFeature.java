package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Current posed player silhouettes only: no history, entity changes or per-player draw passes. */
public final class ChamsFeature implements ModuleFeature {
    private static final int MAX_PLAYERS = 12;
    private static volatile boolean active;
    private final PlayerEchoRenderer snapshots = new PlayerEchoRenderer();
    private volatile boolean enabled;
    private volatile double range = 64.0;
    private volatile float opacity = 0.68F;
    private volatile String colorMode = "Celeste";
    private volatile int customColor = 0x74DFFF;
    private volatile boolean includeArmor;
    private volatile boolean includeSelf;
    private volatile boolean includeInvisible;
    private volatile int material;
    private Object observedWorld;

    @Override public String name() { return "Chams"; }

    /** One registered feature owns this flag; legacy ESP can avoid submitting duplicate player chams. */
    public static boolean isActive() { return active; }
    public int material() { return material; }

    @Override public void onEnable(ModuleContext context) {
        clear();
        update(context);
        enabled = true;
        active = true;
    }

    @Override public void onStartTick(ModuleContext context) {
        update(context);
        active = enabled && context.client().player != null && context.client().world != null;
    }

    @Override public void onDisable(ModuleContext context) {
        enabled = false;
        active = false;
        clear();
    }

    @Override public void onWorldChanged(ModuleContext context) { active = false; clear(); }

    /** Call once during END_EXTRACTION; store the returned immutable frame for drawing. */
    public PlayerEchoRenderer.Frame extract(WorldExtractionContext context) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || context == null || context.world() == null || context.camera() == null
            || client.player == null || !client.player.isAlive() || client.player.isRemoved()
            || client.player.getEntityWorld() != context.world()) return PlayerEchoRenderer.Frame.EMPTY;
        if (observedWorld != context.world()) {
            snapshots.clear();
            observedWorld = context.world();
        }
        Vec3d camera = context.camera().getCameraPos();
        if (!finite(camera)) return PlayerEchoRenderer.Frame.EMPTY;
        double frameRange = range, rangeSquared = frameRange * frameRange;
        double fadeWidth = Math.max(3.0, frameRange * 0.16);
        int fps = client.getCurrentFps();
        int budget = fps > 0 && fps < 45 ? 6 : MAX_PLAYERS;
        boolean selfVisible = includeSelf && !client.options.getPerspective().isFirstPerson();
        List<AbstractClientPlayerEntity> candidates = context.world().getPlayers().stream()
            .filter(player -> player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && (player != client.player || selfVisible) && (includeInvisible || !player.isInvisible())
                && finite(player.getEntityPos()) && player.squaredDistanceTo(camera) <= rangeSquared
                && (context.frustum() == null || context.frustum().isVisible(player.getBoundingBox().expand(includeArmor ? 3.0 : .35))))
            .sorted(Comparator.comparingDouble((AbstractClientPlayerEntity player) -> player.squaredDistanceTo(camera))
                .thenComparingInt(AbstractClientPlayerEntity::getId))
            .limit(budget).toList();
        if (candidates.isEmpty()) return PlayerEchoRenderer.Frame.EMPTY;
        List<PlayerEchoRenderer.Echo> echoes = new ArrayList<>(candidates.size());
        // The shader supplies the animated material; a stable input color keeps CPU capture independent of time.
        int color = VisualEffects.color(colorMode, customColor, 0.0, 0.0);
        for (AbstractClientPlayerEntity player : candidates) {
            float tickDelta = context.tickCounter().getTickProgress(!context.world().getTickManager().shouldSkipTick(player));
            PlayerModelSnapshot mesh = snapshots.snapshot(player, tickDelta, includeArmor);
            if (mesh == null || !finite(mesh.position())) continue;
            double distance = Math.sqrt(camera.squaredDistanceTo(mesh.position()));
            double edge = MathHelper.clamp((frameRange - distance) / fadeWidth, 0.0, 1.0);
            edge = edge * edge * (3.0 - 2.0 * edge);
            double near = MathHelper.clamp((camera.distanceTo(mesh.position().add(0, .9, 0)) - .50) / .55, 0.0, 1.0);
            float alpha = (float) (opacity * edge * near);
            if (alpha <= .003F) continue;
            PlayerEchoRenderer.Echo echo = new PlayerEchoRenderer.Echo(mesh, mesh.position(), mesh.playerYaw(),
                color, color, alpha, 0.0F);
            if (PlayerEchoRenderer.visible(context.frustum(), camera, echo)) echoes.add(echo);
        }
        return echoes.isEmpty() ? PlayerEchoRenderer.Frame.EMPTY : new PlayerEchoRenderer.Frame(echoes);
    }

    private void update(ModuleContext context) {
        material = switch (context.mode("Material", "Pearl")) {
            case "Flow" -> 1;
            case "Mist" -> 2;
            case "Glow" -> 3;
            default -> 0;
        };
        range = MathHelper.clamp(context.slider("Range", 64.0), 8.0, 96.0);
        opacity = (float) MathHelper.clamp(context.slider("Opacity", 0.68), 0.2, 0.95);
        colorMode = context.mode("Color mode", "Celeste");
        customColor = context.colorPick("Color", 0x74DFFF) & 0xFFFFFF;
        includeArmor = context.toggle("Include armor", false);
        includeSelf = context.toggle("Self", false);
        includeInvisible = context.toggle("Invisible", false);
    }

    private void clear() {
        snapshots.clear();
        observedWorld = null;
    }

    private static boolean finite(Vec3d value) {
        return value != null && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
