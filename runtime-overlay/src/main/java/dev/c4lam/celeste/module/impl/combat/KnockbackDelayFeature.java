package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ChatMessageC2SPacket;
import net.minecraft.network.packet.c2s.play.CommandExecutionC2SPacket;
import net.minecraft.network.packet.s2c.common.DisconnectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityVelocityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExplosionS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Knockback Delay feature ported from skidding client.
 */
public final class KnockbackDelayFeature implements ModuleFeature {
    private static final String HOLDING = "Holding";
    private static final String RANGE_SEPARATOR = "-";
    private static final String MS_SUFFIX = "ms";

    private final Queue<QueuedPacket> delayedPackets = new ConcurrentLinkedDeque<>();
    private boolean active;
    private float activeDelayMs;
    private int lastTargetId = -1;

    private MinecraftClient client;
    private ModuleContext context;

    // Static instance for mixins to access
    private static KnockbackDelayFeature INSTANCE;

    public KnockbackDelayFeature() {
        INSTANCE = this;
    }

    public static KnockbackDelayFeature getInstance() {
        return INSTANCE;
    }

    @Override
    public String name() {
        return "Knockback Delay";
    }

    public String concat() {
        if (active) return HOLDING;

        boolean inAir = client != null && client.player != null && !client.player.isOnGround();
        int airDelayMin = 100;
        int airDelayMax = 250;
        int groundDelayMin = 50;
        int groundDelayMax = 150;

        int min = inAir ? airDelayMin : groundDelayMin;
        int max = inAir ? airDelayMax : groundDelayMax;

        return min + RANGE_SEPARATOR + max + MS_SUFFIX;
    }

    @Override
    public void onEnable(ModuleContext context) {
        resetState();
    }

    @Override
    public void onDisable(ModuleContext context) {
        flushAll();
        resetState();
    }

    @Override
    public void onWorldChanged(ModuleContext context) {
        flushAll();
        resetState();
    }

    @Override
    public void onStartTick(ModuleContext context) {
        this.client = context.client();
        this.context = context;
    }

    @Override
    public void onActionTick(ModuleContext context) {
        // Noop
    }

    // Called from mixin when a packet is received
    public void onPacketReceive(Packet<?> packet) {
        if (client == null || client.player == null || client.getNetworkHandler() == null) {
            return;
        }
        // Ignore in singleplayer
        if (client.isInSingleplayer()) {
            dispatchNow(packet);
            return;
        }

        // Ignore certain packets
        if (packet instanceof ChatMessageC2SPacket
                || packet instanceof GameMessageS2CPacket
                || packet instanceof CommandExecutionC2SPacket) {
            dispatchNow(packet);
            return;
        }

        if (packet instanceof PlayerPositionLookS2CPacket
                || packet instanceof DisconnectS2CPacket) {
            flushAll();
            dispatchNow(packet);
            return;
        }

        if (packet instanceof HealthUpdateS2CPacket healthPacket
                && healthPacket.getHealth() <= 0f) {
            flushAll();
            dispatchNow(packet);
            return;
        }

        boolean removingLastTarget = false;
        if (packet instanceof EntitiesDestroyS2CPacket removePacket
                && lastTargetId != -1
                && removePacket.getEntityIds().contains(lastTargetId)) {
            lastTargetId = -1;
            removingLastTarget = true;
        }

        if (!active && isSelfKnockback(packet)) {
            if (rollChance(this.context)) {
                active = true;
                activeDelayMs = pickDelay(this.context);
            }
        }

        if (!active) {
            dispatchNow(packet);
            return;
        }

        delayedPackets.add(new QueuedPacket(packet, System.currentTimeMillis()));
        // Mixin should cancel the packet

        if (removingLastTarget) {
            flushAll();
            return;
        }

        QueuedPacket oldest = delayedPackets.peek();
        if (oldest != null && System.currentTimeMillis() - oldest.timeStamp >= activeDelayMs) {
            flushAll();
        }
    }

    private boolean rollChance(ModuleContext context) {
        int chance = context == null ? 100 : (int) context.slider("Chance", 100);
        int value = chance;
        if (value >= 100) return true;
        if (value <= 0) return false;
        return Math.random() * 100.0 < value;
    }

    private float pickDelay(ModuleContext context) {
        boolean inAir = client != null && client.player != null && !client.player.isOnGround();
        float airDelayMin = context == null ? 100f : (float) context.slider("Air Delay", 100f);
        float airDelayMax = context == null ? 250f : (float) context.slider("Air Delay", 250f);
        float groundDelayMin = context == null ? 50f : (float) context.slider("Ground Delay", 50f);
        float groundDelayMax = context == null ? 150f : (float) context.slider("Ground Delay", 150f);

        float min = inAir ? airDelayMin : groundDelayMin;
        float max = inAir ? airDelayMax : groundDelayMax;

        return (float) (Math.random() * (max - min) + min);
    }

    private boolean isSelfKnockback(Packet<?> packet) {
        if (client.player == null) {
            return false;
        }

        if (packet instanceof EntityVelocityUpdateS2CPacket motion) {
            // Compare to player ID
            return motion.getEntityId() == client.player.getId();
        }

        if (packet instanceof ExplosionS2CPacket explode) {
            // Check if player knockback is present
            return explode.playerKnockback().isPresent();
        }

        return false;
    }

    private void dispatchNow(Packet<?> packet) {
        ClientPlayNetworkHandler handler = client == null ? null : client.getNetworkHandler();
        if (handler == null) {
            return;
        }
        try {
            dispatch(packet, handler);
        } catch (Throwable ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static void dispatch(Packet<?> packet, ClientPlayNetworkHandler handler) {
        Packet<PacketListener> cast = (Packet<PacketListener>) packet;
        cast.apply(handler);
    }

    private void flushAll() {
        if (client == null || client.getNetworkHandler() == null) {
            delayedPackets.clear();
            active = false;
            activeDelayMs = 0f;
            return;
        }

        while (true) {
            QueuedPacket queued = delayedPackets.poll();
            if (queued == null) {
                break;
            }
            try {
                dispatch(queued.packet, client.getNetworkHandler());
            } catch (Throwable ignored) {
            }
        }

        active = false;
        activeDelayMs = 0f;
    }

    private boolean isLastTargetUnavailable() {
        if (lastTargetId == -1) {
            return false;
        }
        if (client.world == null) {
            return true;
        }

        net.minecraft.entity.Entity entity = client.world.getEntityById(lastTargetId);
        if (entity == null) {
            return true;
        }
        if (!entity.isAlive()) {
            return true;
        }
        if (entity.isRemoved()) {
            return true;
        }

        if (entity instanceof net.minecraft.entity.LivingEntity living) {
            if (living.getHealth() <= 0.0f) {
                return true;
            }
            // Placeholder for target allowed check - simplified
            return true; // Assume target is allowed for now
        }

        return false;
    }

    private void resetState() {
        delayedPackets.clear();
        active = false;
        activeDelayMs = 0f;
        lastTargetId = -1;
    }

    private static class QueuedPacket {
        final Packet<?> packet;
        final long timeStamp;

        QueuedPacket(Packet<?> packet, long timeStamp) {
            this.packet = packet;
            this.timeStamp = timeStamp;
        }
    }
}