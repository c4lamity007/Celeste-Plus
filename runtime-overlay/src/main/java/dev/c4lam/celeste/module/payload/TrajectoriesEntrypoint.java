package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.TrajectoriesFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.TrajectoriesModuleVisualRenderer;
public final class TrajectoriesEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new TrajectoriesFeature();
        TrajectoriesModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->{ feature.extract(context,lines,boxes,glows,dev.c4lam.celeste.render.ModuleVisualBudget.remaining("trajectories")); });
        return feature;
    }
}
