package dev.c4lam.celeste.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.sound.SoundEvents;

/** Quiet vanilla notes; entirely in-process and controlled by Minecraft sound volume. */
public final class ModuleToggleSounds {
    private static final ModuleToggleSoundQueue QUEUE = ModuleToggleSoundQueue.getInstance();
    private ModuleToggleSounds() { }
    public static void publish(boolean enabled) { QUEUE.publish(enabled); }
    public static void reset() { QUEUE.reset(); }
    public static void tick(MinecraftClient client, boolean active) {
        Boolean enabled = QUEUE.poll(active && client.player != null ? client.world : null, System.nanoTime());
        if (enabled != null) client.getSoundManager().play(PositionedSoundInstance.ui(
            SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), enabled ? 1.55F : .95F, .22F));
    }
}
