package dev.c4lam.celeste.compat;

import dev.c4lam.celeste.gui.CelesteScreen;
import dev.c4lam.celeste.module.delivery.ModuleHooks;
import dev.c4lam.celeste.hud.HudEditorScreen;
import dev.c4lam.celeste.hud.HudManager;
import dev.c4lam.celeste.render.GuiRenderer;
import dev.c4lam.celeste.rotation.ElytraSilentAim;
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
        Object warning=dev.c4lam.celeste.auth.SecurityWarningRenderer.beforeFlush();
        GuiRenderer.getInstance().flush();
        dev.c4lam.celeste.auth.SecurityWarningRenderer.afterFlush(warning);
    }

    /** At InGameHud.render HEAD; true means return without rendering the rest of the HUD. */
    public static boolean hideHud(InGameHud hud, DrawContext context, RenderTickCounter tickCounter) {
        if (MinecraftClient.getInstance().currentScreen instanceof HudEditorScreen) {
            MinecraftAccess.renderHotbar(hud, context, tickCounter);
            return true;
        }
        return MinecraftClient.getInstance().currentScreen instanceof CelesteScreen
            || MinecraftClient.getInstance().currentScreen instanceof dev.c4lam.celeste.gui.HandModelsEditorScreen;
    }

    /** At ChatScreen.mouseClicked HEAD; true means the click was consumed. */
    public static boolean handleChatMediaClick(Click click) {
        return click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT
            && HudManager.getInstance().handleMediaClick(click.x(), click.y());
    }

    /** At LivingEntityRenderer.updateRenderState TAIL, after vanilla calculates the pose. */
    public static void applyServerRotation(LivingEntity entity, LivingEntityRenderState state) {
        ModuleHooks.invoke("player_models", "model.capture", null, entity, state);
        ModuleHooks.invoke("sword_aura", "rotation.apply", null, entity, state);
    }

    /** At LivingEntityRenderer.render HEAD; an unavailable replacement must retain vanilla. */
    public static boolean replacePlayerModel(LivingEntityRenderState state) {
        ElytraSilentAim.finishRender(state);
        return (boolean) ModuleHooks.invoke("player_models", "model.replace", false, state);
    }
}
