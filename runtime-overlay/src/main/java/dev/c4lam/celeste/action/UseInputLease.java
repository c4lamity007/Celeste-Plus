package dev.c4lam.celeste.action;

import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.RotationManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import org.lwjgl.glfw.GLFW;

/**
 * Exclusive ownership for modules that replace a physically held Use input.
 * The winner drains vanilla's queued press and restores the real key state
 * when it releases ownership.
 */
public final class UseInputLease {
    private static final UseInputLease INSTANCE = new UseInputLease();

    private Object owner;
    private int ownerPriority = Integer.MIN_VALUE;
    private long leaseTick = Long.MIN_VALUE;

    private UseInputLease() {
    }

    public static UseInputLease getInstance() {
        return INSTANCE;
    }

    public boolean acquire(Object candidate, int priority, long tick, MinecraftClient client) {
        if (candidate == null || client == null || client.options == null) {
            return false;
        }
        if (leaseTick != tick) {
            Object previous = owner;
            owner = candidate;
            ownerPriority = priority;
            leaseTick = tick;
            if (previous != null && previous != candidate) {
                preempt(previous);
            }
        } else if (owner != candidate) {
            if (owner != null && !beats(candidate, priority, owner, ownerPriority)) {
                return false;
            }
            Object previous = owner;
            owner = candidate;
            ownerPriority = priority;
            if (previous != null) {
                preempt(previous);
            }
        }

        KeyBinding use = client.options.useKey;
        use.setPressed(false);
        while (use.wasPressed()) {
            // The owning module replaces this physical input through the
            // shared one-action scheduler.
        }
        return true;
    }

    public void release(Object releasingOwner, MinecraftClient client) {
        if (owner != releasingOwner) {
            return;
        }
        owner = null;
        ownerPriority = Integer.MIN_VALUE;
        if (client != null && client.options != null) {
            client.options.useKey.setPressed(physicalDown(client));
        }
    }

    public boolean owns(Object candidate) {
        return owner == candidate;
    }

    /** Drops stale ownership during disconnect/shutdown without retaining input. */
    public void clear(MinecraftClient client) {
        owner = null;
        ownerPriority = Integer.MIN_VALUE;
        leaseTick = Long.MIN_VALUE;
        if (client != null && client.options != null) {
            client.options.useKey.setPressed(false);
        }
    }

    public static boolean physicalDown(MinecraftClient client) {
        if (client == null || client.options == null) {
            return false;
        }
        KeyBinding binding = client.options.useKey;
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

    private static boolean beats(Object candidate, int candidatePriority,
                                 Object current, int currentPriority) {
        if (candidatePriority != currentPriority) {
            return candidatePriority > currentPriority;
        }
        return stableId(candidate).compareTo(stableId(current)) < 0;
    }

    private static String stableId(Object candidate) {
        return candidate instanceof ModuleFeature feature
            ? feature.name()
            : candidate.getClass().getName();
    }

    private static void preempt(Object previous) {
        ActionScheduler.getInstance().cancel(previous);
        RotationManager.getInstance().release(previous);
    }
}
