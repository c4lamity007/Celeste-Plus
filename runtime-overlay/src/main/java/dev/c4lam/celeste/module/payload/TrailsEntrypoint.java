package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.TrailsFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.TrailsModuleVisualRenderer;
public final class TrailsEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new TrailsFeature();
        TrailsModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->{ feature.extract(context,glows,dev.c4lam.celeste.render.ModuleVisualBudget.remaining("trails")); });
        return feature;
    }
}
