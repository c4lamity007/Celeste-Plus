package dev.c4lam.celeste.beta.visual;

import dev.c4lam.celeste.CelesteClient;
import dev.c4lam.celeste.compat.SkyboxHooks;
import dev.c4lam.celeste.compat.PresentationHooks;
import dev.c4lam.celeste.module.impl.visual.expansion.SwingSpeedFeature;
import dev.celestebeta.host.VisualHookBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.Click;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
import net.minecraft.client.render.state.SkyRenderState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;

/** Payload-side bindings; the host retains only the JDK interface until close. */
public final class BetaVisualHooks implements VisualHookBridge.Handler, AutoCloseable {
    private final VisualHookBridge.Registration registration;

    private BetaVisualHooks() { registration = VisualHookBridge.install(this); }

    public static BetaVisualHooks connect(MinecraftClient client) {
        if (!client.isOnThread()) throw new IllegalStateException("Connect beta visuals on the client thread");
        VisualHookBridge.assertVisibleFrom(client.getClass().getClassLoader());
        VisualHookBridge.assertVisibleFrom(BetaVisualHooks.class.getClassLoader());
        return new BetaVisualHooks();
    }

    public void extractSky(Object renderer, Object world, Object camera, Object state) {
        if (CelesteClient.active()) {
            SkyboxHooks.extract(renderer, (ClientWorld) world, (Camera) camera, (SkyRenderState) state);
        } else {
            SkyboxHooks.clear();
        }
    }

    public boolean drawSky(Object renderer) {
        return CelesteClient.active() && SkyboxHooks.draw(renderer);
    }

    public boolean ownsSky(Object renderer) {
        return CelesteClient.active() && SkyboxHooks.ownsSky(renderer);
    }

    public int swingDuration(Object entity, int vanilla) {
        return CelesteClient.active() ? SwingSpeedFeature.durationFor((LivingEntity) entity, vanilla) : vanilla;
    }

    public void flushGui() {
        // The authorization screen also uses this batch before modules activate.
        PresentationHooks.flushGui();
    }

    public boolean hideHud(Object hud, Object context, Object counter) {
        return CelesteClient.active() && PresentationHooks.hideHud((InGameHud) hud, (DrawContext) context, (RenderTickCounter) counter);
    }

    public boolean mediaClick(Object click) {
        return CelesteClient.active() && PresentationHooks.handleChatMediaClick((Click) click);
    }

    public void applyRotations(Object entity, Object state) {
        if (CelesteClient.active()) PresentationHooks.applyServerRotation((LivingEntity) entity, (LivingEntityRenderState) state);
    }

    public boolean replacePlayerModel(Object state) {
        return CelesteClient.active() && PresentationHooks.replacePlayerModel((LivingEntityRenderState) state);
    }

    @Override public void close() {
        registration.close();
        SkyboxHooks.clear();
    }
}
