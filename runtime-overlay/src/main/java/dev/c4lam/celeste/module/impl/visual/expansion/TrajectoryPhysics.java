package dev.c4lam.celeste.module.impl.visual.expansion;

import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;

/** One exact vanilla-style projectile step, shared by held and live predictions. */
final class TrajectoryPhysics {
    private TrajectoryPhysics() {
    }

    static ArrayList<Vec3d> beginRemainingPath(Vec3d currentPosition, int remainingSteps) {
        ArrayList<Vec3d> points = new ArrayList<>(Math.max(1, remainingSteps + 1));
        points.add(currentPosition);
        return points;
    }

    static State advance(State current, boolean gravityBeforeMove, boolean inWater,
                         double gravity, double airDrag, double waterDrag) {
        Vec3d velocity = current.velocity();
        if (gravityBeforeMove) {
            velocity = velocity.add(new Vec3d(0.0D, -gravity, 0.0D))
                .multiply(inWater ? waterDrag : airDrag);
            return new State(current.position().add(velocity), velocity);
        }

        Vec3d position = current.position().add(velocity);
        velocity = velocity.multiply(inWater ? waterDrag : airDrag)
            .add(new Vec3d(0.0D, -gravity, 0.0D));
        return new State(position, velocity);
    }

    record State(Vec3d position, Vec3d velocity) {
    }
}
