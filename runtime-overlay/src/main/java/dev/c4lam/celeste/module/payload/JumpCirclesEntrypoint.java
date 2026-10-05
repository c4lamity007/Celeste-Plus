package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.JumpCirclesFeature;
import dev.c4lam.celeste.module.impl.visual.expansion.JumpCirclesModuleVisualRenderer;
public final class JumpCirclesEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new JumpCirclesFeature();
        JumpCirclesModuleVisualRenderer.attach(services,(context,boxes,lines,glows)->{ feature.extract(context,glows); });
        return feature;
    }
}
