package dev.c4lam.celeste.rotation;

import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/** Pure movement-correction math shared by the input and jump mixins. */
public final class MoveFix {
    private static final float INPUT_EPSILON = 1.0E-4F;

    private MoveFix() {
    }

    public static Correction correct(
        Vec2f original,
        PlayerInput originalKeys,
        float cameraYaw,
        float serverYaw,
        MoveFixMode mode
    ) {
        mode = mode.packetRepresentable();
        if (mode == MoveFixMode.OFF || mode == MoveFixMode.ANARCHY
            || original.lengthSquared() < INPUT_EPSILON) {
            return new Correction(original, originalKeys);
        }

        float magnitude = original.length();
        Vec2f direction = original.normalize();
        double radians = Math.toRadians(cameraYaw - serverYaw);
        float cosine = (float) Math.cos(radians);
        float sine = (float) Math.sin(radians);
        float sideways = direction.x * cosine - direction.y * sine;
        float forward = direction.y * cosine + direction.x * sine;

        // PlayerInput transmits four directional booleans, not arbitrary analog
        // values. Always project the counter-rotated vector onto one of the eight
        // directions vanilla can reconstruct from those booleans.
        float snappedSideways = snapAxis(sideways);
        float snappedForward = snapAxis(forward);
        if (snappedSideways == 0.0F && snappedForward == 0.0F) {
            if (Math.abs(sideways) > Math.abs(forward)) {
                snappedSideways = Math.copySign(1.0F, sideways);
            } else {
                snappedForward = Math.copySign(1.0F, forward);
            }
        }
        // KeyboardInput has already applied vanilla modifiers such as sneak
        // slowdown by this point. Preserve that magnitude while changing only
        // the packet-representable direction.
        Vec2f corrected = new Vec2f(snappedSideways, snappedForward)
            .normalize()
            .multiply(magnitude);

        PlayerInput remappedKeys = remapDirectionalKeys(corrected, originalKeys);
        return new Correction(corrected, remappedKeys);
    }

    public static float movementYaw(float cameraYaw, float serverYaw, MoveFixMode mode) {
        return mode.packetRepresentable() == MoveFixMode.OFF ? cameraYaw : serverYaw;
    }

    public static float sprintJumpYaw(float cameraYaw, float serverYaw, MoveFixMode mode) {
        return mode.packetRepresentable() == MoveFixMode.OFF ? cameraYaw : serverYaw;
    }

    private static float snapAxis(float value) {
        if (value >= 0.5F) {
            return 1.0F;
        }
        if (value <= -0.5F) {
            return -1.0F;
        }
        return 0.0F;
    }

    private static PlayerInput remapDirectionalKeys(Vec2f movement, PlayerInput original) {
        float sideways = movement.x;
        float forward = movement.y;
        return new PlayerInput(
            forward > INPUT_EPSILON,
            forward < -INPUT_EPSILON,
            sideways > INPUT_EPSILON,
            sideways < -INPUT_EPSILON,
            original.jump(),
            original.sneak(),
            original.sprint()
        );
    }

    public record Correction(Vec2f movement, PlayerInput keys) {
    }
}
