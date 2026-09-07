package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.gui.CelesteScreen;
import dev.c4lam.celeste.hud.HudEditorScreen;
import dev.c4lam.celeste.hud.HudManager;
import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import dev.c4lam.celeste.module.impl.visual.PlayerModelsFeature;
import dev.c4lam.celeste.render.GuiRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.entity.LivingEntity;
import org.lwjgl.glfw.GLFW;

/** Shared bodies for ordinary presentation mixins and the optional late hook host. */
public final class PresentationHooks {
    private PresentationHooks() { }

    /** Call immediately after Minecraft's GuiRenderer.render(GpuBufferSlice). */
    public static void flushGui() {
        GuiRenderer.getInstance().flush();
    }

    /** At InGameHud.render HEAD; true means return without rendering the rest of the HUD. */
    public static boolean hideHud(InGameHud hud, DrawContext context, RenderTickCounter tickCounter) {
        if (MinecraftClient.getInstance().currentScreen instanceof HudEditorScreen) {
            MinecraftAccess.renderHotbar(hud, context, tickCounter);
            return true;
        }
        return MinecraftClient.getInstance().currentScreen instanceof CelesteScreen;
    }

    /** At ChatScreen.mouseClicked HEAD; true means the click was consumed. */
    public static boolean handleChatMediaClick(Click click) {
        return click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
            && HudManager.getInstance().handleMediaClick(click.x(), click.y());
    }

    /** At LivingEntityRenderer.updateRenderState TAIL, after vanilla calculates the pose. */
    public static void applyServerRotation(LivingEntity entity, LivingEntityRenderState state) {
        PlayerModelsFeature.capture(entity, state);
        MinecraftClient client = MinecraftClient.getInstance();
        if (entity != client.player || !SeeRotationsFeature.shouldApply(client)) {
            return;
        }
        state.bodyYaw = SeeRotationsFeature.getRenderYaw(entity);
        state.relativeHeadYaw = 0.0F;
        state.pitch = SeeRotationsFeature.getRenderPitch(entity);
    }

    /** At LivingEntityRenderer.render HEAD; an unavailable replacement must retain vanilla. */
    public static boolean replacePlayerModel(LivingEntityRenderState state) {
        return PlayerModelsFeature.replaceVanilla(state);
    }
}
