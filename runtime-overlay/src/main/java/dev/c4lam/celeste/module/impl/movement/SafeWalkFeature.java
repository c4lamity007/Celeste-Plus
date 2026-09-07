package dev.c4lam.celeste.module.impl.movement;

import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.item.BlockItem;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec2f;
import org.lwjgl.glfw.GLFW;

/** Uses vanilla sneak input when the player's leading foot probe reaches a ledge. */
public final class SafeWalkFeature implements ModuleFeature {
    private static final double INPUT_EPSILON = 1.0E-6D;
    private static final double PROBE_RADIUS = 0.015D;
    private static final double PROBE_DEPTH = 0.18D;

    private boolean ownsSneakKey;
    private InputUtil.Key ownedKey;

    @Override
    public String name() {
        return "Safe Walk";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        KeyBinding binding = client.options.sneakKey;
        InputUtil.Key boundKey = MinecraftAccess.boundKey(binding);

        if (isPhysicalKeyDown(client, boundKey)) {
            // From this point vanilla owns the pressed state; never release it.
            ownsSneakKey = false;
            ownedKey = null;
            return;
        }
        if (!canPoll(boundKey) || !shouldProtectEdge(context)) {
            stopOwningKey(client);
            return;
        }

        binding.setPressed(true);
        ownsSneakKey = true;
        ownedKey = boundKey;
    }

    @Override
    public void onDisable(ModuleContext context) {
        stopOwningKey(context.client());
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        stopOwningKey(context.client());
    }

    private static boolean shouldProtectEdge(ModuleContext context) {
        MinecraftClient client = context.client();
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.currentScreen != null
            || !player.isAlive() || player.isSpectator() || !player.isOnGround()
            || player.hasVehicle() || player.isGliding() || player.isSwimming()
            || player.getAbilities().flying) {
            return false;
        }
        if (context.toggle("Require blocks", false)
            && !(player.getMainHandStack().getItem() instanceof BlockItem)
            && !(player.getOffHandStack().getItem() instanceof BlockItem)) {
            return false;
        }

        Vec2f input = player.input.getMovementInput();
        double sideways = input.x;
        double forward = input.y;
        double length = Math.hypot(sideways, forward);
        if (length <= INPUT_EPSILON) {
            return false;
        }
        sideways /= length;
        forward /= length;

        double yaw = Math.toRadians(player.getYaw());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        double directionX = sideways * cos - forward * sin;
        double directionZ = forward * cos + sideways * sin;

        double halfWidth = player.getWidth() * 0.5D;
        double leadingRadius = halfWidth * (Math.abs(directionX) + Math.abs(directionZ));
        double lookAhead = context.slider("Look ahead", 0.15D);
        double distance = leadingRadius + lookAhead;
        double x = player.getX() + directionX * distance;
        double z = player.getZ() + directionZ * distance;
        double feetY = player.getBoundingBox().minY;
        Box probe = new Box(
            x - PROBE_RADIUS, feetY - PROBE_DEPTH, z - PROBE_RADIUS,
            x + PROBE_RADIUS, feetY - 0.01D, z + PROBE_RADIUS
        );
        return client.world.isBlockSpaceEmpty(player, probe);
    }

    private void stopOwningKey(MinecraftClient client) {
        if (!ownsSneakKey) {
            return;
        }
        if (ownedKey == null || !isPhysicalKeyDown(client, ownedKey)) {
            client.options.sneakKey.setPressed(false);
        }
        ownsSneakKey = false;
        ownedKey = null;
    }

    private static boolean canPoll(InputUtil.Key key) {
        return key != null && (key.getCategory() == InputUtil.Type.KEYSYM
            || key.getCategory() == InputUtil.Type.MOUSE);
    }

    private static boolean isPhysicalKeyDown(MinecraftClient client, InputUtil.Key key) {
        Window window = client.getWindow();
        if (window == null || key == null || key.getCode() < 0) {
            return false;
        }
        if (key.getCategory() == InputUtil.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window.getHandle(), key.getCode()) == GLFW.GLFW_PRESS;
        }
        if (key.getCategory() == InputUtil.Type.KEYSYM) {
            return InputUtil.isKeyPressed(window, key.getCode());
        }
        return false;
    }
}
