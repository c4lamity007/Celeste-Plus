package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.OreEspExpansionFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.OreEspModuleVisualRenderer;
public final class OreEspEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new OreEspExpansionFeature();
        OreEspModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->{ feature.extract(context,boxes,lines,glows,dev.c4lam.celeste.render.ModuleVisualBudget.remaining("ore_esp")); });
        return feature;
    }
}
