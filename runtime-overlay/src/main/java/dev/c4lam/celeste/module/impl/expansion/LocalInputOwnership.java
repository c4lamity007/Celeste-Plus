package dev.c4lam.celeste.module.impl.expansion;

import dev.c4lam.celeste.compat.MinecraftAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;

/** Owns only synthetic Minecraft key state and never releases physical input. */
public final class LocalInputOwnership {
    private LocalInputOwnership() {
    }

    public static boolean physicalDown(MinecraftClient client, KeyBinding binding) {
        Window window = client.getWindow();
        if (window == null) {
            return binding.isPressed();
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
        // InputUtil cannot reliably poll physical scan-code state here. Treat
        // it as unsupported; setSynthetic already refuses to claim a binding
        // that vanilla currently reports pressed, while an owned synthetic
        // press can still be released deterministically.
        return false;
    }

    public static boolean setSynthetic(MinecraftClient client, KeyBinding binding, boolean requested, boolean owned) {
        if (requested) {
            if (physicalDown(client, binding) || binding.isPressed()) {
                return false;
            }
            binding.setPressed(true);
            return true;
        }
        release(client, binding, owned);
        return false;
    }

    public static void release(MinecraftClient client, KeyBinding binding, boolean owned) {
        if (owned && !physicalDown(client, binding)) {
            binding.setPressed(false);
        }
    }
}
