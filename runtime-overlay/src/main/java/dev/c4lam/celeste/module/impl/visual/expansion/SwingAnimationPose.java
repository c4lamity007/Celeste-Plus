package dev.c4lam.celeste.module.impl.visual.expansion;

import org.joml.Matrix4f;
import org.joml.Vector3f;

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
        double pitch, yaw, roll;
        switch (style) {
            case "Slice" -> {
                pitch = -32 * quick; yaw = side * 20 * arc; roll = side * -70 * quick;
            }
            case "Sweep" -> {
                pitch = -18 * arc; yaw = side * -75 * quick; roll = side * 28 * arc;
            }
            case "Thrust" -> {
                pitch = -28 * quick; yaw = side * 9 * arc; roll = side * -12 * arc;
            }
            case "Spin" -> {
                // Roll forward and recover smoothly so every strength returns to the idle pose.
                pitch = -360 * arc * arc;
                yaw = 0; roll = side * 12 * arc;
            }
            default -> {
                pitch = -24 * quick; yaw = side * -14 * arc; roll = side * -18 * quick;
            }
        }
        double rotationAmount = amount;
        float grip = right ? .56F : -.56F;
        if (affectPosition) result.translate(position(style, right, progress, strength));
        result.translate(grip, -.52F, -.72F);
        result.rotateY((float) Math.toRadians(yaw * amount))
            .rotateZ((float) Math.toRadians(roll * amount))
            .rotateX((float) Math.toRadians(pitch * rotationAmount));
        return result.translate(-grip, .52F, .72F);
    }

    /** Camera-space travel stays visible regardless of model facing, scale or editor position. */
    public static Vector3f position(String style, boolean right, double progress, double strength) {
        if (!Double.isFinite(progress + strength) || progress <= 0 || progress >= 1) return new Vector3f();
        double amount = Math.clamp(strength, 0, 100) / 100;
        double side = right ? 1 : -1;
        double arc = Math.sin(Math.PI * progress);
        double quick = Math.sin(Math.PI * Math.sqrt(progress));
        Vector3f offset = switch (style) {
            case "Slice" -> new Vector3f((float) (-side * .38 * arc), (float) (-.18 * arc), (float) (-.10 * quick));
            case "Sweep" -> new Vector3f((float) (-side * .42 * quick), (float) (.12 * arc), (float) (-.14 * arc));
            case "Thrust" -> new Vector3f(0, (float) (.14 * arc), (float) (-.42 * quick));
            case "Spin" -> new Vector3f(0, (float) (.22 * arc), (float) (-.14 * arc));
            default -> new Vector3f((float) (-side * .28 * arc), (float) (.16 * arc), (float) (-.20 * quick));
        };
        return offset.mul((float) amount);
    }

    /** Rotation keeps the configured grip; optional movement is applied outside its facing/scale. */
    public static Matrix4f compose(Matrix4f placement, String style, boolean right, double progress,
                                   double strength, boolean affectPosition) {
        Matrix4f result = new Matrix4f();
        if (affectPosition) result.translate(position(style, right, progress, strength));
        return result.mul(placement).mul(transform(style, right, progress, strength, false));
    }

}
