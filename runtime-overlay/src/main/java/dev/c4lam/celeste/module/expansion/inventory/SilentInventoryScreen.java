package dev.c4lam.celeste.module.expansion.inventory;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.entity.player.PlayerEntity;

/**
 * Intentionally blank player-inventory context for Silent Totem's ordinary
 * staged left-click transfer. It never moves the physical cursor.
 */
public final class SilentInventoryScreen extends InventoryScreen implements dev.c4lam.celeste.gui.HudTransparentScreen {
    SilentInventoryScreen(PlayerEntity player) {
        super(player);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /**
     * This is a transport-only screen. Letting InventoryScreen receive a real
     * mouse click while it is invisible can pick up whichever slot happens to
     * be under the pointer and leave an unexpected item on the cursor. The
     * module uses interactionManager.clickSlot itself for its staged clicks,
     * so every physical click is deliberately consumed here.
     */
    @Override
    public boolean mouseClicked(Click click, boolean doubled) {
        return true;
    }

    @Override
    public boolean mouseReleased(Click click) {
        return true;
    }

    @Override
    public boolean mouseDragged(Click click, double deltaX, double deltaY) {
        return true;
    }

    /**
     * A staged click is only valid while this exact blank player-inventory
     * context owns the screen. Input is intentionally neutralized for that
     * short window so a normal movement/sprint packet cannot overlap it.
     */
    public static boolean blocksMovement(MinecraftClient client) {
        return client != null && client.currentScreen instanceof SilentInventoryScreen;
    }
}
