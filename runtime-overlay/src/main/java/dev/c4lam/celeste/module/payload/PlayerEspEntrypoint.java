package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.PlayerBoxEspFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.PlayerEspModuleVisualRenderer;
public final class PlayerEspEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new PlayerBoxEspFeature();
        PlayerEspModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->{ feature.extract(context,glows,dev.c4lam.celeste.render.ModuleVisualBudget.remaining("player_esp")); });
        return feature;
    }
}
