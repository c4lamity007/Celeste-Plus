package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.TargetEspFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.TargetEspModuleVisualRenderer;
public final class TargetEspEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new TargetEspFeature();
        TargetEspModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->{ feature.extract(context,boxes,lines,glows,dev.c4lam.celeste.render.ModuleVisualBudget.remaining("target_esp")); });
        return feature;
    }
}
