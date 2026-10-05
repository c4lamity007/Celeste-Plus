package dev.c4lam.celeste.module.impl.combat;

import java.util.function.Predicate;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttackRangeComponent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;

/** TBOT's vanilla hit authority, queried at the silent rotation without retaining a camera change. */
final class SwordAuraTargeting {
    private SwordAuraTargeting() { }

    static EntityHitResult playerHit(ClientPlayerEntity self, Entity camera, float yaw, float pitch,
                                     Predicate<PlayerEntity> validTarget) {
        if (self == null || camera == null || !Float.isFinite(yaw) || !Float.isFinite(pitch)) return null;
        float previousYaw = camera.getYaw(), previousPitch = camera.getPitch(), previousHeadYaw = camera.getHeadYaw();
        HitResult result;
        try {
            camera.setYaw(yaw);
            camera.setPitch(pitch);
            // 1.21.11 component weapons query getHeadRotationVector(), not the ordinary camera yaw.
            camera.setHeadYaw(yaw);
            // Exactly the TBOT query: component-specific reach, otherwise the vanilla entity-range attribute.
            // The aura acquisition slider is deliberately not an input to this operation.
            result = self.getCrosshairTarget(1.0F, camera);
        } finally {
            camera.setYaw(previousYaw);
            camera.setPitch(previousPitch);
            camera.setHeadYaw(previousHeadYaw);
        }
        if (!(result instanceof EntityHitResult hit) || !(hit.getEntity() instanceof PlayerEntity target)
            || !validTarget.test(target)) return null;
        AttackRangeComponent range = self.getMainHandStack().get(DataComponentTypes.ATTACK_RANGE);
        return range != null && !range.isWithinRange(self, hit.getPos()) ? null : hit;
    }
}
