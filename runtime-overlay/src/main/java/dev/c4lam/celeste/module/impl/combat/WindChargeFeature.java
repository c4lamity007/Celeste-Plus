package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.action.ActionScheduler;
import dev.c4lam.celeste.action.GameplayFoundation;
import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import dev.c4lam.celeste.silent.SilentSlotManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.Window;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.lwjgl.glfw.GLFW;

/** A single vanilla wind-charge use for each physical use-key press. */
public final class WindChargeFeature implements ModuleFeature {
    private static final int PRIORITY = 120;

    private boolean physicalUseDown;
    private boolean suppressingPress;
    private int lastActionAge = Integer.MIN_VALUE / 2;

    @Override
    public String name() {
        return "Wind Charge";
    }

    @Override
    public void onEnable(ModuleContext context) {
        // Enabling while Use is already held is not a physical rising edge.
        physicalUseDown = isPhysicalKeyDown(context.client(), context.client().options.useKey);
        suppressingPress = false;
    }

    @Override
    public void onStartTick(ModuleContext context) {
        MinecraftClient client = context.client();
        KeyBinding use = client.options.useKey;
        boolean down = isPhysicalKeyDown(client, use);
        boolean rising = down && !physicalUseDown;
        physicalUseDown = down;

        if (!down) {
            suppressingPress = false;
            return;
        }
        if (client.currentScreen != null || client.player == null || client.interactionManager == null) {
            if (suppressingPress) {
                use.setPressed(false);
            }
            return;
        }
        if (suppressingPress) {
            // The module owns only this physical press. Keep vanilla's held-key
            // repeat path from producing a second use until the key is released.
            use.setPressed(false);
        }
        if (!rising) {
            return;
        }

        UseIntent intent = selectIntent(context);
        if (intent == null) {
            return;
        }
        int delay = Math.max(0, (int) Math.round(context.slider("Delay", 2.0D)));
        if (!delayElapsed(client, delay)) {
            // This press is not ours; leave it queued for Minecraft's normal
            // input handler instead of swallowing it during module cooldown.
            return;
        }
        suppressingPress = true;
        use.setPressed(false);
        // GLFW increments KeyBinding's queued press counter before the client
        // tick. Drain that vanilla click only after we have a valid charge
        // intent, otherwise Minecraft would also run doItemUse later this tick.
        while (use.wasPressed()) {
        }

        RotationManager.getInstance().submit(RotationTask.builder(this,
                new Rotation(client.player.getYaw(), client.player.getPitch()))
            .priority(PRIORITY).lifetimeTicks(1).smoothing(180.0F, 180.0F)
            .sensitivityQuantized(true).moveFix(MoveFixMode.STRICT).build());
        ActionScheduler.getInstance().submit(this, PRIORITY,
            actionClient -> execute(actionClient, context, intent, delay));
    }

    @Override
    public void onDisable(ModuleContext context) {
        restoreUseBinding(context.client());
        reset();
        GameplayFoundation.releaseOwner(this);
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        restoreUseBinding(context.client());
        reset();
        GameplayFoundation.releaseOwner(this);
    }

    private UseIntent selectIntent(ModuleContext context) {
        MinecraftClient client = context.client();
        if (client.player == null) {
            return null;
        }
        if (usable(client.player.getMainHandStack(), client)) {
            return new UseIntent(Hand.MAIN_HAND, -1);
        }
        if (usable(client.player.getOffHandStack(), client)) {
            return new UseIntent(Hand.OFF_HAND, -1);
        }
        int slot = CombatLoadout.findItem(client, Items.WIND_CHARGE);
        return slot < 0 ? null : new UseIntent(Hand.MAIN_HAND, slot);
    }

    private void execute(MinecraftClient client, ModuleContext original, UseIntent intent, int delay) {
        if (client.currentScreen != null || client.player == null || client.interactionManager == null
            || client.player.hasVehicle() || client.player.isUsingItem()
            || client.interactionManager.isBreakingBlock()
            || !RotationManager.getInstance().isOwnedBy(this)
            || !original.module().enabled() || !delayElapsed(client, delay)) {
            return;
        }
        if (intent.slot() >= 0) {
            if (intent.slot() > 8 || !usable(client.player.getInventory().getStack(intent.slot()), client)) {
                return;
            }
            SilentSlotManager.getInstance().runWithSlot(this, intent.slot(), PRIORITY,
                original.toggle("Silent swap", true),
                () -> useVanilla(client, Hand.MAIN_HAND));
            return;
        }
        if (usable(client.player.getStackInHand(intent.hand()), client)) {
            useVanilla(client, intent.hand());
        }
    }

    private void useVanilla(MinecraftClient client, Hand hand) {
        if (client.player == null || client.interactionManager == null
            || !usable(client.player.getStackInHand(hand), client)) {
            return;
        }
        ActionResult result = client.interactionManager.interactItem(client.player, hand);
        if (result instanceof ActionResult.Success success) {
            if (success.swingSource() == ActionResult.SwingSource.CLIENT) {
                client.player.swingHand(hand);
            }
            client.gameRenderer.firstPersonRenderer.resetEquipProgress(hand);
            lastActionAge = client.player.age;
        }
    }

    private boolean delayElapsed(MinecraftClient client, int delay) {
        if (client.player == null) {
            return false;
        }
        if (client.player.age < lastActionAge) {
            lastActionAge = Integer.MIN_VALUE / 2;
        }
        return client.player.age - lastActionAge >= delay;
    }

    private static boolean usable(ItemStack stack, MinecraftClient client) {
        return client.player != null && stack.isOf(Items.WIND_CHARGE)
            && !client.player.getItemCooldownManager().isCoolingDown(stack);
    }

    private void restoreUseBinding(MinecraftClient client) {
        if (suppressingPress && client != null && client.options != null) {
            client.options.useKey.setPressed(isPhysicalKeyDown(client, client.options.useKey));
        }
    }

    private void reset() {
        physicalUseDown = false;
        suppressingPress = false;
        lastActionAge = Integer.MIN_VALUE / 2;
    }

    private static boolean isPhysicalKeyDown(MinecraftClient client, KeyBinding binding) {
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

    private record UseIntent(Hand hand, int slot) {
    }
}
