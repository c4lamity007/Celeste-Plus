package dev.c4lam.celeste.render;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.delivery.ModuleFrame;
import java.util.function.Function;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.util.Identifier;

/** Generic detachable rendering transport. Effect algorithms and GPU objects remain in children. */
public final class ModuleRenderPass {
    private ModuleRenderPass() { }
    public static <T> void attach(ModuleServices services, Function<WorldExtractionContext,T> extractor,
                                  BiConsumer<WorldRenderContext,T> draw) {
        attach(services,extractor,draw,false);
    }
    public static <T> void attach(ModuleServices services, Function<WorldExtractionContext,T> extractor,
                                  BiConsumer<WorldRenderContext,T> draw, boolean lettering) {
        ModuleFrame<T> frame = ModuleFrame.create();
        String name = "celeste:module/" + services.moduleId();
        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION,name+"/extract_pass",
            WorldRenderEvents.EndExtraction.class,context->{
                if(context.worldState()!=null && !dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass())
                    frame.set(context.worldState(),extractor.apply(context));
            },WorldRenderEvents.END_EXTRACTION::register);
        WorldRenderEvents.EndMain render = context -> {
            if(dev.c4lam.celeste.compat.RenderCompatibility.isShadowPass()) return;
            T value=frame.get(context.worldState()); if(value!=null) draw.accept(context,value);
        };
        if(lettering) {
            Identifier phase=Identifier.of("celeste","sky_lyrics/after_fog");
            WorldRenderEvents.END_MAIN.addPhaseOrdering(Event.DEFAULT_PHASE,phase);
            WorldRenderEvents.END_MAIN.addPhaseOrdering(Identifier.of("celeste","world_overlays/after_fog"),phase);
            BetaSubscriptions.register(WorldRenderEvents.END_MAIN,name+"/draw_pass",WorldRenderEvents.EndMain.class,
                render,proxy->WorldRenderEvents.END_MAIN.register(phase,proxy));
        } else WorldOverlayPass.register(name+"/draw_pass",render);
    }
    public static void hud(ModuleServices services,HudElement element) {
        BetaSubscriptions.register(HudElementRegistry.class,"celeste:module/"+services.moduleId()+"/hud",HudElement.class,
            element,proxy->HudElementRegistry.addLast(Identifier.of("celeste","module/"+services.moduleId()),proxy));
    }
}
