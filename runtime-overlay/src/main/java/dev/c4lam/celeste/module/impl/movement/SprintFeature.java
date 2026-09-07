package dev.c4lam.celeste.module.impl.movement;

import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.Vec2f;
import org.lwjgl.glfw.GLFW;

/** Holds only Minecraft's ordinary sprint binding while moving forward. */
public final class SprintFeature implements ModuleFeature {
    private static final float INPUT_EPSILON = 1.0E-4F;

    private boolean ownsSprintKey;

    @Override
    public String name() {
        return "Sprint";
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null) {
            stopOwningKey(client);
            return;
        }

        Vec2f movement = player.input.getMovementInput();
        setSprintKey(client, movement.y > INPUT_EPSILON);
    }

    @Override
    public void onDisable(ModuleContext context) {
        stopOwningKey(context.client());
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        stopOwningKey(context.client());
    }

    private void setSprintKey(MinecraftClient client, boolean pressed) {
        KeyBinding binding = client.options.sprintKey;
        if (!pressed) {
            stopOwningKey(client);
            return;
        }

        if (isPhysicalKeyboardOrMouseKeyDown(client, binding)) {
            ownsSprintKey = false;
            return;
        }

        InputUtil.Key boundKey = MinecraftAccess.boundKey(binding);
        if (boundKey.getCategory() == InputUtil.Type.SCANCODE) {
            // GLFW cannot safely poll an arbitrary scan-code through InputUtil's key API.
            return;
        }

        binding.setPressed(true);
        ownsSprintKey = true;
    }

    private void stopOwningKey(MinecraftClient client) {
        if (!ownsSprintKey) {
            return;
        }

        KeyBinding binding = client.options.sprintKey;
        if (!isPhysicalKeyboardOrMouseKeyDown(client, binding)) {
            binding.setPressed(false);
        }
        ownsSprintKey = false;
    }

    private static boolean isPhysicalKeyboardOrMouseKeyDown(MinecraftClient client, KeyBinding binding) {
        Window window = client.getWindow();
        if (window == null) {
            return false;
        }

        InputUtil.Key key = MinecraftAccess.boundKey(binding);
        if (key.getCode() < 0) {
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
