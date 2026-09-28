package dev.c4lam.celeste.module.impl.visual.expansion;

import org.joml.Matrix4f;

/** Relative grip motion. Idle, disabled translation and the other hand remain unchanged. */
public final class SwingAnimationPose {
    private SwingAnimationPose() { }

    public static Matrix4f transform(String style, boolean right, double progress,
                                     double strength, boolean affectPosition) {
        Matrix4f result = new Matrix4f();
        if (!Double.isFinite(progress + strength) || progress <= 0 || progress >= 1) return result;
        double amount = Math.clamp(strength, 0, 100) / 100;
        double side = right ? 1 : -1;
        double arc = Math.sin(Math.PI * progress);
        double quick = Math.sin(Math.PI * Math.sqrt(progress));
        double x = 0, y = 0, z = 0, pitch, yaw, roll;
        switch (style) {
            case "Slice" -> {
                pitch = -32 * quick; yaw = side * 20 * arc; roll = side * -70 * quick;
                x = side * -.13 * arc; y = -.08 * arc; z = -.04 * quick;
            }
            case "Sweep" -> {
                pitch = -18 * arc; yaw = side * -75 * quick; roll = side * 28 * arc;
                x = side * -.18 * quick; y = .035 * arc; z = -.055 * arc;
            }
            case "Thrust" -> {
                pitch = -28 * quick; yaw = side * 9 * arc; roll = side * -12 * arc;
                z = -.26 * quick; y = .04 * arc;
            }
            case "Spin" -> {
                // Roll forward and recover smoothly so every strength returns to the idle pose.
                pitch = -360 * arc * arc;
                yaw = 0; roll = side * 12 * arc;
                y = .055 * arc; z = -.035 * arc;
            }
            default -> {
                pitch = -24 * quick; yaw = side * -14 * arc; roll = side * -18 * quick;
                x = side * -.055 * arc; y = .035 * arc; z = -.055 * quick;
            }
        }
        double rotationAmount = amount;
        float grip = right ? .56F : -.56F;
        result.translate(grip, -.52F, -.72F);
        if (affectPosition) result.translate((float) (x * amount), (float) (y * amount), (float) (z * amount));
        result.rotateY((float) Math.toRadians(yaw * amount))
            .rotateZ((float) Math.toRadians(roll * amount))
            .rotateX((float) Math.toRadians(pitch * rotationAmount));
        return result.translate(-grip, .52F, .72F);
    }
}
