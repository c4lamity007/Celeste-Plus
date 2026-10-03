package dev.c4lam.celeste.module.payload;
import dev.c4lam.celeste.module.ModuleFeature;
import dev.c4lam.celeste.module.delivery.ModuleEntrypoint;
import dev.c4lam.celeste.module.delivery.ModuleServices;
import dev.c4lam.celeste.module.impl.combat.SwordAuraFeature;
import dev.c4lam.celeste.module.impl.expansion.SeeRotationsFeature;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.client.render.entity.state.LivingEntityRenderState;
/** Independent payload entrypoint; no eager catalogue implementation construction. */
public final class SwordAuraEntrypoint implements ModuleEntrypoint {
    @Override public ModuleFeature create(ModuleServices services) {
        var feature = new SwordAuraFeature();
        services.hook("rotation.quantize", a -> SeeRotationsFeature.shouldQuantizeSensitivity());
        services.hook("rotation.visible", a -> SeeRotationsFeature.shouldApply((MinecraftClient)a[0]));
        services.hook("rotation.apply", a -> {
            LivingEntity entity=(LivingEntity)a[0]; LivingEntityRenderState state=(LivingEntityRenderState)a[1];
            if (entity == MinecraftClient.getInstance().player && SeeRotationsFeature.shouldApply(MinecraftClient.getInstance()) && !entity.isGliding()) {
                state.bodyYaw=SeeRotationsFeature.getRenderYaw(entity); state.relativeHeadYaw=0;
                state.pitch=SeeRotationsFeature.getRenderPitch(entity);
            }
            return null;
        });
        services.scope().own(() -> SeeRotationsFeature.syncFromAura(false, false));
        return feature;
    }
}
