package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.ArrowEspFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;

public final class ArrowEspEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new ArrowEspFeature();
        ModuleRenderPass.hud(services,(context,counter)->{
            var client=net.minecraft.client.MinecraftClient.getInstance();
            if(client.currentScreen instanceof dev.c4lam.celeste.gui.HandModelsEditorScreen) return;
            feature.render(client,context.getScaledWindowWidth(),context.getScaledWindowHeight(),counter.getTickProgress(false));
        });
        return feature;
    }
}
