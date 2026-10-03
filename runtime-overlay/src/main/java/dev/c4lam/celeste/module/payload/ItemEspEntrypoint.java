package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.ItemEspFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.ItemEspModuleVisualRenderer;
public final class ItemEspEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new ItemEspFeature();
        ItemEspModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->{ feature.extract(context,boxes,lines,glows,dev.c4lam.celeste.render.ModuleVisualBudget.remaining("item_esp")); });
        return feature;
    }
}
