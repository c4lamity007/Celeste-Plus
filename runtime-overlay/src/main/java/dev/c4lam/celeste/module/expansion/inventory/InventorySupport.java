package dev.c4lam.celeste.module.expansion.inventory;

import dev.c4lam.celeste.compat.MinecraftAccess;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.rotation.MoveFixMode;
import dev.c4lam.celeste.rotation.Rotation;
import dev.c4lam.celeste.rotation.RotationManager;
import dev.c4lam.celeste.rotation.RotationTask;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Predicate;

public final class InventorySupport {
    private static final Map<ClientPlayerEntity, StationarySample> STATIONARY_SINCE = new WeakHashMap<>();

    private InventorySupport() {
    }

    public static boolean playable(MinecraftClient client) {
        return client.player != null && client.world != null && client.interactionManager != null
            && client.currentScreen == null && client.player.isAlive() && !client.player.isSpectator()
            && !client.player.hasVehicle() && !client.player.isUsingItem()
            && !client.interactionManager.isBreakingBlock();
    }

    public static int hotbarSlot(MinecraftClient client, Item item) {
        return hotbarSlot(client, stack -> stack.isOf(item));
    }

    public static int hotbarSlot(MinecraftClient client, Predicate<ItemStack> matcher) {
        if (client.player == null) {
            return -1;
        }
        PlayerInventory inventory = client.player.getInventory();
        int selected = inventory.getSelectedSlot();
        if (selected >= 0 && selected < 9 && matcher.test(inventory.getStack(selected))) {
            return selected;
        }
        for (int slot = 0; slot < 9; slot++) {
            if (matcher.test(inventory.getStack(slot))) {
                return slot;
            }
        }
        return -1;
    }

    public static boolean isSplashHealing(ItemStack stack) {
        if (!stack.isOf(Items.SPLASH_POTION)) {
            return false;
        }
        PotionContentsComponent potion = stack.get(DataComponentTypes.POTION_CONTENTS);
        if (potion == null) {
            return false;
        }
        for (var effect : potion.getEffects()) {
            if (effect.getEffectType().equals(StatusEffects.INSTANT_HEALTH)) {
                return true;
            }
        }
        return false;
    }

    public static boolean useItem(MinecraftClient client, Hand hand) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        ActionResult result = client.interactionManager.interactItem(client.player, hand);
        if (result instanceof ActionResult.Success success) {
            if (success.swingSource() == ActionResult.SwingSource.CLIENT) {
                client.player.swingHand(hand);
            }
            client.gameRenderer.firstPersonRenderer.resetEquipProgress(hand);
            return true;
        }
        return false;
    }

    public static boolean interactBlock(MinecraftClient client, Hand hand, BlockHitResult hit) {
        if (client.player == null || client.world == null || client.interactionManager == null
            || !exactBlockHit(client, hit)) {
            return false;
        }
        ActionResult result = client.interactionManager.interactBlock(client.player, hand, hit);
        if (result instanceof ActionResult.Success success) {
            if (success.swingSource() == ActionResult.SwingSource.CLIENT) {
                client.player.swingHand(hand);
            }
            return true;
        }
        return false;
    }

    private static boolean exactBlockHit(MinecraftClient client, BlockHitResult expected) {
        if (expected == null || !client.player.canInteractWithBlockAt(expected.getBlockPos(), 0.0D)) {
            return false;
        }
        double reach = client.player.getBlockInteractionRange();
        Vec3d eyes = client.player.getEyePos();
        if (eyes.squaredDistanceTo(expected.getPos()) > reach * reach + 1.0E-7D) {
            return false;
        }
        Vec3d end = eyes.add(RotationManager.getInstance().serverRotation().direction().multiply(reach));
        var result = client.world.raycast(new net.minecraft.world.RaycastContext(
            eyes, end, net.minecraft.world.RaycastContext.ShapeType.COLLIDER,
            net.minecraft.world.RaycastContext.FluidHandling.NONE, client.player
        ));
        return result instanceof BlockHitResult hit
            && hit.getType() == net.minecraft.util.hit.HitResult.Type.BLOCK
            && hit.getBlockPos().equals(expected.getBlockPos())
            && hit.getSide() == expected.getSide()
            && hit.getPos().squaredDistanceTo(expected.getPos()) <= 0.04D;
    }

public static void requestRotation(Object owner, ClientPlayerEntity player, Vec3d point,
                                 int priority, float yawStep, float pitchStep) {
        RotationManager.getInstance().submit(RotationTask.builder(owner, lookAt(player, point))
            .priority(priority).lifetimeTicks(1).lifetime(Duration.ofMillis(250L))
            .smoothing(yawStep, pitchStep).sensitivityQuantized(true)
            .moveFix(MoveFixMode.STRICT).build());
    }

    public static void requestDown(Object owner, ClientPlayerEntity player, int priority) {
        RotationManager.getInstance().submit(RotationTask.builder(owner,
                new Rotation(player.getYaw(), 89.0F))
            .priority(priority).lifetimeTicks(1).lifetime(Duration.ofMillis(250L))
            .smoothing(180.0F, 45.0F).sensitivityQuantized(true)
            .moveFix(MoveFixMode.STRICT).build());
    }

    public static boolean rotationReady(Object owner, Rotation target, float tolerance) {
        RotationManager manager = RotationManager.getInstance();
        if (!manager.isOwnedBy(owner)) {
            return false;
        }
        Rotation current = manager.serverRotation();
        return Math.abs(MathHelper.wrapDegrees(target.yaw() - current.yaw())) <= tolerance
            && Math.abs(target.pitch() - current.pitch()) <= tolerance;
    }

    public static boolean downReady(Object owner) {
        RotationManager manager = RotationManager.getInstance();
        return manager.isOwnedBy(owner) && manager.serverRotation().pitch() >= 84.0F;
    }

    public static Rotation lookAt(ClientPlayerEntity player, Vec3d point) {
        Vec3d eyes = player.getEyePos();
        double dx = point.x - eyes.x;
        double dy = point.y - eyes.y;
        double dz = point.z - eyes.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return new Rotation((float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(dz, dx)) - 90.0D),
            (float) MathHelper.clamp(-Math.toDegrees(Math.atan2(dy, horizontal)), -90.0D, 90.0D));
    }

    public static boolean sameHit(Object current, BlockHitResult expected) {
        return current instanceof BlockHitResult hit && expected != null
            && hit.getBlockPos().equals(expected.getBlockPos()) && hit.getSide() == expected.getSide();
    }

    public static boolean actionKeyDown(ModuleContext context) {
        int key = context.keybind("Action");
        MinecraftClient client = context.client();
        return key != GLFW.GLFW_KEY_UNKNOWN && key >= 0 && client.getWindow() != null
            && client.currentScreen == null && InputUtil.isKeyPressed(client.getWindow(), key);
    }

    public static boolean playerInventoryAvailable(MinecraftClient client) {
        if (client.player == null || client.interactionManager == null) {
            return false;
        }
        if (client.currentScreen != null && !(client.currentScreen instanceof InventoryScreen)) {
            return false;
        }
        return client.player.currentScreenHandler == client.player.playerScreenHandler
            && client.player.playerScreenHandler.getCursorStack().isEmpty();
    }

    /**
     * A 1.21.11 inventory click is only emitted after a complete stationary
     * client tick. This accounts for the server still knowing the prior tick's
     * movement input when START_CLIENT_TICK runs.
     */
    public static boolean stationaryInventoryClickAllowed(MinecraftClient client) {
        if (!playerInventoryAvailable(client) || client.player.isSprinting()
            || !client.player.isOnGround() || client.player.isGliding()
            || client.player.isTouchingWater() || client.player.isClimbing()) {
            if (client.player != null) STATIONARY_SINCE.remove(client.player);
            return false;
        }
        var input = client.player.input.playerInput;
        if (input.forward() || input.backward() || input.left() || input.right()) {
            STATIONARY_SINCE.remove(client.player);
            return false;
        }
        Vec3d position = client.player.getEntityPos();
        StationarySample sample = STATIONARY_SINCE.get(client.player);
        if (sample == null
            || client.player.age <= sample.age()
            || position.squaredDistanceTo(sample.position()) > 1.0E-6D
            || client.player.getVelocity().lengthSquared() > 1.0E-4D) {
            STATIONARY_SINCE.put(client.player,
                new StationarySample(client.player.age, position));
            return false;
        }
        return true;
    }

public static int findPlayerSlotId(MinecraftClient client, int minimumInventoryIndex,
                                 int maximumInventoryIndex, Predicate<ItemStack> matcher) {
        if (!playerInventoryAvailable(client)) {
            return -1;
        }
        for (Slot slot : client.player.playerScreenHandler.slots) {
            if (slot.inventory == client.player.getInventory()
                && slot.getIndex() >= minimumInventoryIndex && slot.getIndex() <= maximumInventoryIndex
                && matcher.test(slot.getStack())) {
                return slot.id;
            }
        }
        return -1;
    }

    public static ClickIntent captureClick(MinecraftClient client, int slotId, Predicate<ItemStack> matcher) {
        if (!playerInventoryAvailable(client) || slotId < 0
            || slotId >= client.player.playerScreenHandler.slots.size()) {
            return null;
        }
        ScreenHandler handler = client.player.playerScreenHandler;
        Slot slot = handler.slots.get(slotId);
        if (slot.id != slotId || !matcher.test(slot.getStack())) {
            return null;
        }
        return new ClickIntent(client.world, handler, handler.syncId, slotId, matcher);
    }

public static boolean executeClick(MinecraftClient client, ClickIntent intent, int button,
                                 SlotActionType actionType) {
        if (intent == null || !stationaryInventoryClickAllowed(client) || client.world != intent.world()
            || client.player.playerScreenHandler != intent.handler()
            || intent.handler().syncId != intent.syncId()
            || intent.slotId() < 0 || intent.slotId() >= intent.handler().slots.size()) {
            return false;
        }
        Slot slot = intent.handler().slots.get(intent.slotId());
        if (slot.id != intent.slotId() || !intent.matcher().test(slot.getStack())) {
            return false;
        }
        client.interactionManager.clickSlot(intent.syncId(), intent.slotId(), button, actionType, client.player);
        return true;
    }

    public static boolean executeInventoryClick(MinecraftClient client, ClickIntent intent, int button,
                                               SlotActionType actionType) {
        if (intent == null || !playerInventoryAvailable(client) || client.world != intent.world()
            || client.player.playerScreenHandler != intent.handler()
            || intent.handler().syncId != intent.syncId()
            || intent.slotId() < 0 || intent.slotId() >= intent.handler().slots.size()) {
            return false;
        }
        Slot slot = intent.handler().slots.get(intent.slotId());
        if (slot.id != intent.slotId() || !intent.matcher().test(slot.getStack())) {
            return false;
        }
        client.interactionManager.clickSlot(intent.syncId(), intent.slotId(), button, actionType, client.player);
        return true;
    }

    public static Slot focusedSlot(MinecraftClient client) {
        if (!(client.currentScreen instanceof HandledScreen<?> handled)) {
            return null;
        }
        return MinecraftAccess.focusedSlot(handled);
    }

    record ClickIntent(ClientWorld world, ScreenHandler handler, int syncId, int slotId,
                       Predicate<ItemStack> matcher) {
    }

    private record StationarySample(int age, Vec3d position) {
    }
}
