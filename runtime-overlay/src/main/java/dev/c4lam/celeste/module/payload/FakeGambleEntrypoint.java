package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;
import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleModuleHooks;
import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleGhostBlocks;
import dev.c4lam.celeste.module.impl.visual.expansion.FakeGambleModuleVisualRenderer;
public final class FakeGambleEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new FakeGambleFeature();
        FakeGambleModuleHooks.bind(services);
        services.hook("mining.active",args->FakeGambleGhostBlocks.isMining());
        FakeGambleModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->FakeGambleFeature.extract(context,boxes,lines));
        ModuleRenderPass.hud(services,(context,counter)->{
            var client=net.minecraft.client.MinecraftClient.getInstance();
            if(client.currentScreen instanceof dev.c4lam.celeste.gui.HandModelsEditorScreen) return;
            FakeGambleFeature.renderHud(client,context.getScaledWindowWidth(),context.getScaledWindowHeight());
        });
        return feature;
    }
}
