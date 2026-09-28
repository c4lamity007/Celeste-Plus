package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Stationary spinning floor rings with grow, hold and fade phases. */
public final class JumpCirclesFeature implements ModuleFeature {
    private static final int MAX_PULSES = 6;
    private static final int MAX_QUADS = MAX_PULSES;
    private final JumpCircleAnimation.Clock clock = new JumpCircleAnimation.Clock();
    private volatile List<Pulse> pulses = List.of();
    private volatile boolean enabled;
    private ClientWorld observedWorld;
    private Vec3d startPosition;
    private boolean startGrounded;
    private boolean lean;
    private boolean shrink;
    private double size = 1.8;
    private double duration = 1.2;
    private double intensity = 0.85;

    @Override public String name() { return "Jump Circles"; }

    @Override public void onEnable(ModuleContext context) {
        clear();
        update(context);
        enabled = true;
    }

    @Override public void onDisable(ModuleContext context) {
        enabled = false;
        clear();
    }

    @Override public void onWorldChanged(ModuleContext context) { clear(); }

    @Override public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        if (observedWorld != client.world) {
            clear();
            observedWorld = client.world;
        }
        update(context);
        double now = clock.sample(System.nanoTime(), client.isPaused());
        pulses = pulses.stream().filter(pulse -> now - pulse.born() < pulse.duration()).toList();
        ClientPlayerEntity player = client.player;
        startGrounded = allowed(player) && player.isOnGround();
        startPosition = player == null ? null : player.getEntityPos();
    }

    @Override public void onEndTick(ModuleContext context) {
        MinecraftClient client = context.client();
        ClientPlayerEntity player = client.player;
        if (!enabled || player == null || startPosition == null || observedWorld != client.world) return;
        Vec3d position = player.getEntityPos();
        boolean jumping = player.input.playerInput.jump() || client.options.jumpKey.isPressed();
        boolean takeoff = JumpCircleAnimation.takeoff(startGrounded, player.isOnGround(), jumping,
            allowed(player), position.y - startPosition.y, position.squaredDistanceTo(startPosition));
        Vec3d origin = startPosition.add(0.0, 0.028, 0.0);
        startGrounded = false;
        startPosition = null;
        if (!takeoff) return;
        double now = clock.sample(System.nanoTime(), client.isPaused());
        List<Pulse> next = new ArrayList<>(pulses);
        while (next.size() >= MAX_PULSES) next.removeFirst();
        // The captured grounded position keeps the effect fixed, including on slabs/stairs.
        next.add(new Pulse(origin, now, duration, size, intensity, lean, shrink, now % VisualEffects.TAU));
        pulses = List.copyOf(next);
    }

    void extract(WorldExtractionContext context, List<WorldGlowRenderer.Quad> output) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!enabled || client.player == null || !client.player.isAlive()
            || observedWorld != context.world() || pulses.isEmpty()) return;
        double now = clock.sample(System.nanoTime(), client.isPaused());
        int limit = Math.min(768, output.size() + MAX_QUADS);
        List<Pulse> framePulses = pulses;
        Vec3d eye = context.camera().getCameraPos();
        for (int index = framePulses.size() - 1; index >= 0; index--) {
            Pulse pulse = framePulses.get(index);
            if (output.size() >= limit || eye.squaredDistanceTo(pulse.origin()) > 48.0 * 48.0) continue;
            JumpCircleAnimation.Frame frame = JumpCircleAnimation.frame(now - pulse.born(),
                pulse.duration(), pulse.size(), pulse.shrink());
            double alpha = frame.opacity() * pulse.intensity();
            if (alpha < .003) continue;
            int color = VisualEffects.color("Celeste", 0, now, pulse.phase() / VisualEffects.TAU);
            int accent = VisualEffects.color("Celeste", 0, now, pulse.phase() / VisualEffects.TAU + .4);
            ground(output, pulse.lean() ? WorldGlowRenderer.Texture.JUMP_LEAN : WorldGlowRenderer.Texture.JUMP,
                pulse.origin(), frame.radius(), frame.rotation() + pulse.phase(), color, accent, alpha);
        }
    }

    private static void ground(List<WorldGlowRenderer.Quad> output, WorldGlowRenderer.Texture texture,
                               Vec3d center, double radius, double rotation, int color, int accent, double alpha) {
        Vec3d u = new Vec3d(Math.cos(rotation) * radius, 0, Math.sin(rotation) * radius);
        Vec3d v = new Vec3d(-Math.sin(rotation) * radius, 0, Math.cos(rotation) * radius);
        output.add(new WorldGlowRenderer.Quad(texture, center.subtract(u).subtract(v), center.add(u).subtract(v),
            center.add(u).add(v), center.subtract(u).add(v), VisualExpansionRenderer.argb((float) alpha, color),
            VisualExpansionRenderer.argb((float) alpha, accent)));
    }

    private static boolean allowed(ClientPlayerEntity player) {
        return player != null && player.isAlive() && !player.isSpectator() && !player.hasVehicle()
            && !player.getAbilities().flying && !player.isGliding() && !player.isClimbing()
            && !player.isTouchingWater() && !player.isInLava();
    }

    private void update(ModuleContext context) {
        lean = "Lean".equals(context.mode("Style", "Glow"));
        shrink = context.toggle("Shrink", false);
        size = MathHelper.clamp(context.slider("Size", 1.8), 0.6, 3.0);
        duration = MathHelper.clamp(context.slider("Duration", 1.2), 0.5, 2.5);
        intensity = MathHelper.clamp(context.slider("Intensity", 0.85), 0.2, 1.0);
    }

    private void clear() {
        pulses = List.of();
        observedWorld = null;
        startPosition = null;
        startGrounded = false;
        clock.reset();
    }

    private record Pulse(Vec3d origin, double born, double duration, double size,
                         double intensity, boolean lean, boolean shrink, double phase) { }
}
