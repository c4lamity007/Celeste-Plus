package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.visual.expansion.SwingSpeedFeature;
import dev.c4lam.celeste.render.ModuleRenderPass;

public final class SwingSpeedEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature=new SwingSpeedFeature();
        services.hook("swing.duration",args->SwingSpeedFeature.durationFor((net.minecraft.entity.LivingEntity)args[0],(int)args[1]));
        return feature;
    }
}
