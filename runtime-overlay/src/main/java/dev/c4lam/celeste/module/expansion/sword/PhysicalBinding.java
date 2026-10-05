package dev.c4lam.celeste.module.expansion.sword;

import dev.c4lam.celeste.compat.MinecraftAccess;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;

/** Reads a binding's physical state without synthesizing operating-system input. */
final class PhysicalBinding {
    private PhysicalBinding() {
    }

    static boolean isDown(MinecraftClient client, KeyBinding binding) {
        Window window = client == null ? null : client.getWindow();
        if (window == null || binding == null) {
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

    static boolean isSupported(KeyBinding binding) {
        if (binding == null) {
            return false;
        }
        InputUtil.Key key = MinecraftAccess.boundKey(binding);
        return key.getCategory() == InputUtil.Type.KEYSYM
            || key.getCategory() == InputUtil.Type.MOUSE;
    }
}
