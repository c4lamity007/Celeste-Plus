package dev.c4lam.celeste.module.impl.combat;

import dev.c4lam.celeste.rotation.Rotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.Random;

/**
 * Priv Client KillA / aim-assist turn model. Applies to a silent Rotation so
 * the camera never moves.
 */
final class HumanAim {
    private static final Random RANDOM = new Random();
    private static final int YAW = 0;
    private static final int PITCH = 1;

    private int aimTargetId = -1;
    private float aimYawVelocity;
    private float aimPitchVelocity;
    private double aimSpeedEnvelope = -1.0D;
    private double aimSpeedTarget = -1.0D;
    private int aimSpeedTargetFrames;
    private long aimLastFrameNanos;
    private int aimPointTargetId = -1;
    private int aimPointFrames;
    private int aimPointStyle;
    private float aimPointVertical;
    private float aimPointLateral;
    private float aimPointDepth;
    private float aimPointVerticalTarget;
    private float aimPointLateralTarget;
    private float aimPointDepthTarget;
    private Vec3d filteredAimPoint;
    private final int[] aimMovementFrames = new int[2];
    private final int[] aimMovementTotalFrames = new int[2];
    private final int[] aimMovementProfile = new int[2];
    private final int[] aimMovementPauseFrames = new int[2];
    private final double[] aimMovementGain = new double[] {1.0D, 1.0D};
    private final double[] aimMovementShape = new double[] {1.0D, 1.0D};
    private final double[] aimJitterValue = new double[2];
    private final double[] aimJitterTarget = new double[2];
    private final int[] aimJitterFrames = new int[2];
    private final double[] aimQuantizationCarry = new double[2];
    private final double[] aimLastError = new double[2];
    private boolean aimHasCameraBaseline;
    private float aimLastCameraYaw;
    private float aimLastCameraPitch;
    private double aimManualYawEnergy;
    private double aimManualPitchEnergy;
    private Vec3d lastPlayerSample;
    private Vec3d lastTargetSample;
    private int lastMoveTargetId = -1;
    private long lastMoveSampleNanos;

    Rotation step(
        ClientPlayerEntity player,
        Entity target,
        Rotation current,
        double speedPercent,
        double smoothnessPercent,
        double curvePercent,
        double jitterPercent,
        double varietyPercent
    ) {
        double frameScale = aimFrameScale();
        boolean stationary = !isEitherMoving(player, target);
        ManualAimSignal manual = sampleManualAim(player, frameScale);
        Vec3d from = player.getEyePos();
        Vec3d to = humanAimPoint(player, target, frameScale, varietyPercent, stationary);
        Vec3d delta = to.subtract(from);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float targetYaw = (float) (MathHelper.atan2(delta.z, delta.x) * (180D / Math.PI)) - 90.0F;
        float targetPitch = (float) (-(MathHelper.atan2(delta.y, horizontal) * (180D / Math.PI)));
        if (target.getId() != aimTargetId) {
            aimTargetId = target.getId();
            aimYawVelocity *= 0.35F;
            aimPitchVelocity *= 0.35F;
            aimMovementFrames[YAW] = 0;
            aimMovementFrames[PITCH] = 0;
        }
        double speed = MathHelper.clamp(speedPercent, 0.0D, 200.0D);
        double smoothness = MathHelper.clamp(smoothnessPercent / 100.0D, 0.0D, 1.0D);
        double curve = MathHelper.clamp(curvePercent / 100.0D, 0.0D, 1.0D);
        double sensitivity = currentMouseSensitivity();
        float yawDelta = MathHelper.wrapDegrees(targetYaw - current.yaw());
        float pitchDelta = targetPitch - current.pitch();
        double readiness = 0.72D + MathHelper.clamp(player.getAttackCooldownProgress(0.0F), 0.0F, 1.0F) * 0.28D;

        float yaw = current.yaw();
        float pitch = current.pitch();
        float yawStep = stochasticAimStep(yawDelta, speed * readiness, smoothness, curve, jitterPercent,
            sensitivity, frameScale, false, manual, stationary);
        if (yawStep != 0.0F) {
            yaw += yawStep;
        } else {
            aimYawVelocity *= 0.72F;
        }
        float pitchStep = stochasticAimStep(pitchDelta, speed * 0.52D * readiness, smoothness, curve,
            jitterPercent, sensitivity, frameScale, true, manual, stationary);
        if (pitchStep != 0.0F) {
            pitch = MathHelper.clamp(pitch + pitchStep, -89.5F, 89.5F);
        } else {
            aimPitchVelocity *= 0.72F;
        }
        storeAimCameraBaseline(player);
        return new Rotation(yaw, pitch);
    }

    double smoothedSpeed(double min, double max) {
        double[] range = {min, max};
        if (aimSpeedEnvelope < 0.0D || aimSpeedTarget < 0.0D) {
            aimSpeedEnvelope = sampleRange(range);
            aimSpeedTarget = aimSpeedEnvelope;
            aimSpeedTargetFrames = randomInt(14, 32);
        }
        if (aimSpeedTargetFrames-- <= 0) {
            aimSpeedTarget = sampleRange(range);
            aimSpeedTargetFrames = randomInt(14, 32);
        }
        aimSpeedEnvelope += (aimSpeedTarget - aimSpeedEnvelope) * 0.045D;
        return aimSpeedEnvelope;
    }

    void reset() {
        aimTargetId = -1;
        aimYawVelocity = 0.0F;
        aimPitchVelocity = 0.0F;
        aimSpeedEnvelope = -1.0D;
        aimSpeedTarget = -1.0D;
        aimSpeedTargetFrames = 0;
        aimLastFrameNanos = 0L;
        aimPointTargetId = -1;
        aimPointFrames = 0;
        aimPointStyle = 0;
        aimPointVertical = 0.0F;
        aimPointLateral = 0.0F;
        aimPointDepth = 0.0F;
        aimPointVerticalTarget = 0.0F;
        aimPointLateralTarget = 0.0F;
        aimPointDepthTarget = 0.0F;
        filteredAimPoint = null;
        aimHasCameraBaseline = false;
        aimLastCameraYaw = 0.0F;
        aimLastCameraPitch = 0.0F;
        aimManualYawEnergy = 0.0D;
        aimManualPitchEnergy = 0.0D;
        lastPlayerSample = null;
        lastTargetSample = null;
        lastMoveTargetId = -1;
        lastMoveSampleNanos = 0L;
        for (int axis = 0; axis < 2; axis++) {
            aimQuantizationCarry[axis] = 0.0D;
            aimLastError[axis] = 0.0D;
            aimMovementFrames[axis] = 0;
            aimMovementTotalFrames[axis] = 0;
            aimMovementProfile[axis] = 0;
            aimMovementPauseFrames[axis] = 0;
            aimMovementGain[axis] = 1.0D;
            aimMovementShape[axis] = 1.0D;
            aimJitterValue[axis] = 0.0D;
            aimJitterTarget[axis] = 0.0D;
            aimJitterFrames[axis] = 0;
        }
    }

    private Vec3d humanAimPoint(ClientPlayerEntity player, Entity target, double frameScale,
                                double targetVarietyPercent, boolean stationary) {
        double variety = MathHelper.clamp(targetVarietyPercent / 100.0D, 0.0D, 1.0D);
        if (variety <= 0.0D || stationary) {
            pinAimPoint(target, true);
        } else if (target.getId() == aimPointTargetId && aimPointFrames > 0) {
            --aimPointFrames;
        } else {
            rerollAimPoint(target, variety);
        }
        if (variety > 0.0D && !stationary && RANDOM.nextDouble() < (0.045D + variety * 0.04D) * frameScale) {
            aimPointVerticalTarget = MathHelper.clamp(aimPointVerticalTarget + (float) (RANDOM.nextGaussian() * 0.055D), 0.58F, 0.95F);
            aimPointLateralTarget = MathHelper.clamp(aimPointLateralTarget + (float) (RANDOM.nextGaussian() * 0.35D), -0.9F, 0.9F);
            aimPointDepthTarget = MathHelper.clamp(aimPointDepthTarget + (float) (RANDOM.nextGaussian() * 0.28D), -0.75F, 0.75F);
        }
        if (variety > 0.0D && !stationary) {
            float drift = (float) MathHelper.clamp((0.05D + variety * 0.09D) * frameScale, 0.015D, 0.28D);
            aimPointVertical += (aimPointVerticalTarget - aimPointVertical) * drift;
            aimPointLateral += (aimPointLateralTarget - aimPointLateral) * drift;
            aimPointDepth += (aimPointDepthTarget - aimPointDepth) * drift;
        }
        Box box = target.getBoundingBox();
        double height = Math.max(0.8D, box.maxY - box.minY);
        double width = Math.max(0.35D, Math.max(box.maxX - box.minX, box.maxZ - box.minZ));
        Vec3d towardTarget = new Vec3d(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
        Vec3d side = towardTarget.lengthSquared() > 1.0E-5D
            ? new Vec3d(-towardTarget.z, 0.0D, towardTarget.x).normalize()
            : new Vec3d(1.0D, 0.0D, 0.0D);
        Vec3d depth = towardTarget.lengthSquared() > 1.0E-5D
            ? towardTarget.normalize()
            : new Vec3d(0.0D, 0.0D, 1.0D);
        double y = box.minY + height * (double) aimPointVertical;
        double lateral = width * 0.42D * (double) aimPointLateral * (0.45D + variety * 0.55D);
        double forward = width * 0.2D * (double) aimPointDepth * (0.35D + variety * 0.65D);
        double x = box.getCenter().x + side.x * lateral + depth.x * forward;
        double z = box.getCenter().z + side.z * lateral + depth.z * forward;
        double margin = Math.min(0.09D, width * 0.16D);
        x = MathHelper.clamp(x, box.minX + margin, box.maxX - margin);
        y = MathHelper.clamp(y, box.minY + 0.38D, box.maxY - 0.035D);
        z = MathHelper.clamp(z, box.minZ + margin, box.maxZ - margin);
        Vec3d rawPoint = new Vec3d(x, y, z);
        if (!stationary) {
            double predictiveLead = MathHelper.clamp(player.distanceTo(target) / 95.0D, 0.018D, 0.065D)
                * (0.72D + RANDOM.nextDouble() * 0.22D);
            rawPoint = rawPoint.add(target.getVelocity().multiply(predictiveLead));
        }
        if (stationary) {
            filteredAimPoint = rawPoint;
            return rawPoint;
        }
        double smoothing = 1.0D - Math.exp(-(0.2D + variety * 0.13D) * frameScale);
        filteredAimPoint = filteredAimPoint == null ? rawPoint : filteredAimPoint.lerp(rawPoint, smoothing);
        return filteredAimPoint;
    }

    private boolean isEitherMoving(ClientPlayerEntity player, Entity target) {
        boolean playerMoving = isLocalPlayerMoving(player);
        boolean targetMoving = isDisplaced(target, lastTargetSample, lastMoveTargetId);
        lastPlayerSample = new Vec3d(player.getX(), player.getY(), player.getZ());
        lastTargetSample = new Vec3d(target.getX(), target.getY(), target.getZ());
        lastMoveTargetId = target.getId();
        lastMoveSampleNanos = System.nanoTime();
        return playerMoving || targetMoving;
    }

    private boolean isLocalPlayerMoving(ClientPlayerEntity player) {
        if (player.input != null) {
            var keys = player.input.playerInput;
            if (keys.forward() || keys.backward() || keys.left() || keys.right() || keys.jump()) {
                return true;
            }
        }
        return isDisplaced(player, lastPlayerSample, player.getId());
    }

    private boolean isDisplaced(Entity entity, Vec3d previous, int previousId) {
        if (entity == null || previous == null || entity.getId() != previousId) {
            return false;
        }
        double elapsed = Math.max((System.nanoTime() - lastMoveSampleNanos) / 1.0E9D, 1.0D / 200.0D);
        return Math.sqrt(entity.squaredDistanceTo(previous)) / elapsed > 0.45D;
    }

    private void pinAimPoint(Entity target, boolean snap) {
        aimPointTargetId = target.getId();
        aimPointFrames = 0;
        aimPointStyle = 0;
        aimPointVerticalTarget = 0.86F;
        aimPointLateralTarget = 0.0F;
        aimPointDepthTarget = 0.0F;
        if (snap) {
            aimPointVertical = aimPointVerticalTarget;
            aimPointLateral = aimPointLateralTarget;
            aimPointDepth = aimPointDepthTarget;
        }
    }

    private void rerollAimPoint(Entity target, double variety) {
        aimPointTargetId = target.getId();
        aimPointFrames = randomInt(5, 14 + (int) Math.round((1.0D - variety) * 10.0D));
        double roll = RANDOM.nextDouble();
        if (roll < 0.42D) {
            aimPointStyle = 0;
            aimPointVerticalTarget = randomFloat(0.84F, 0.94F);
        } else if (roll < 0.68D) {
            aimPointStyle = 1;
            aimPointVerticalTarget = randomFloat(0.74F, 0.86F);
        } else if (roll < 0.9D) {
            aimPointStyle = 2;
            aimPointVerticalTarget = randomFloat(0.66F, 0.82F);
        } else {
            aimPointStyle = 3;
            aimPointVerticalTarget = randomFloat(0.58F, 0.74F);
        }
        float lateralLimit = aimPointStyle == 2 ? 0.95F : 0.72F;
        aimPointLateralTarget = MathHelper.clamp((float) (RANDOM.nextGaussian() * (0.18D + variety * 0.5D)), -lateralLimit, lateralLimit);
        aimPointDepthTarget = MathHelper.clamp((float) (RANDOM.nextGaussian() * (0.14D + variety * 0.34D)), -0.75F, 0.75F);
        if (filteredAimPoint == null || target.getId() != aimTargetId) {
            aimPointVertical = aimPointVerticalTarget;
            aimPointLateral = aimPointLateralTarget;
            aimPointDepth = aimPointDepthTarget;
        }
    }

    private float stochasticAimStep(float delta, double speed, double smoothness, double curve,
                                    double jitterPercent, double sensitivity, double frameScale,
                                    boolean vertical, ManualAimSignal manual, boolean stationary) {
        int axis = vertical ? PITCH : YAW;
        float abs = Math.abs(delta);
        double tolerance = (vertical ? 0.055D : 0.045D) + smoothness * (vertical ? 0.08D : 0.065D);
        if (stationary) {
            tolerance = Math.max(tolerance, vertical ? 0.55D : 0.45D);
        }
        double assistScale = aimIntentScale(delta, manual, vertical);
        if ((double) abs < tolerance && (stationary || jitterPercent <= 0.0D || RANDOM.nextDouble() > jitterPercent / 220.0D)) {
            setAimVelocity(vertical, getAimVelocity(vertical) * 0.64F);
            return 0.0F;
        }
        if (assistScale <= 0.025D) {
            setAimVelocity(vertical, getAimVelocity(vertical) * 0.7F);
            aimMovementFrames[axis] = 0;
            return 0.0F;
        }
        if (aimMovementFrames[axis] <= 0 || (double) abs > aimLastError[axis] * 1.55D + (vertical ? 0.28D : 0.38D)) {
            rerollAimMovement(axis, abs, speed, smoothness, vertical);
        }
        double speed01 = MathHelper.clamp(speed / 200.0D, 0.0D, 1.0D);
        double distance01 = MathHelper.clamp((double) abs / (vertical ? 13.0D : 26.0D), 0.0D, 1.0D);
        double difficulty = Math.log(1.0D + (double) abs / (vertical ? 1.4D : 2.0D)) / Math.log(2.0D);
        double gain = (0.065D + speed01 * 0.24D + Math.sqrt(distance01) * 0.18D + difficulty * 0.022D)
            * aimMovementGain[axis] * assistScale;
        double maxStep = ((vertical ? 0.16D : 0.22D) + speed01 * (vertical ? 4.7D : 8.4D))
            * (0.55D + Math.sqrt(distance01) * 0.75D) * frameScale;
        maxStep = Math.min(maxStep, vertical ? 6.8D : 13.5D);
        double profile = aimMovementProfile(axis);
        double nearBrake = 1.0D - curve * 0.3D * (1.0D - distance01);
        double desired = (double) delta * gain * profile * nearBrake;
        if (aimMovementPauseFrames[axis] > 0) {
            --aimMovementPauseFrames[axis];
            desired *= vertical ? 0.36D : 0.44D;
        }
        desired += updateAimJitter(axis, abs, speed01, smoothness, jitterPercent, frameScale, vertical, stationary);
        desired = MathHelper.clamp(desired, -maxStep, maxStep);
        double reverseLimit = Math.max(0.025D, (double) abs * (vertical ? 0.16D : 0.18D));
        if (Math.signum(desired) != Math.signum(delta)) {
            desired = MathHelper.clamp(desired, -reverseLimit, reverseLimit);
        } else if (Math.abs(desired) > (double) abs) {
            desired = delta;
        }
        float velocity = getAimVelocity(vertical);
        double accel = ((vertical ? 0.035D : 0.045D) + speed01 * (vertical ? 0.23D : 0.34D)
            + distance01 * (vertical ? 0.08D : 0.12D)) * (1.0D - smoothness * 0.38D) * frameScale;
        velocity += MathHelper.clamp((float) (desired - (double) velocity), (float) -accel, (float) accel);
        if (Math.signum(velocity) == Math.signum(delta) && Math.abs(velocity) > abs) {
            velocity = delta;
        }
        float step = quantizeToMouseSensitivity(velocity, delta, sensitivity, vertical, stationary || jitterPercent <= 0.0D);
        if (step == 0.0F) {
            velocity *= 0.54F;
        }
        setAimVelocity(vertical, velocity);
        aimLastError[axis] = abs;
        if (aimMovementFrames[axis] > 0) {
            --aimMovementFrames[axis];
        }
        return step;
    }

    private double aimIntentScale(float targetDelta, ManualAimSignal manual, boolean vertical) {
        double manualScale = manualAssistScale(targetDelta, manual, vertical);
        double abs = Math.abs(targetDelta);
        double passiveCone = vertical ? 13.0D : 24.0D;
        double passive = MathHelper.clamp((passiveCone - abs) / passiveCone, 0.0D, 1.0D);
        passive = Math.pow(passive, vertical ? 1.45D : 1.25D) * (vertical ? 0.46D : 0.58D);
        double acquisitionCone = vertical ? 80.0D : 180.0D;
        double acquire = MathHelper.clamp((acquisitionCone - abs) / acquisitionCone, 0.0D, 1.0D);
        acquire = acquire <= 0.0D ? 0.22D
            : (vertical ? 0.12D : 0.18D) + Math.pow(acquire, vertical ? 1.35D : 1.15D) * (vertical ? 0.55D : 0.72D);
        return Math.max(Math.max(manualScale * (vertical ? 0.82D : 0.92D), passive), acquire);
    }

    private void rerollAimMovement(int axis, float error, double speed, double smoothness, boolean vertical) {
        double speed01 = MathHelper.clamp(speed / 200.0D, 0.0D, 1.0D);
        double error01 = MathHelper.clamp((double) error / (vertical ? 12.0D : 24.0D), 0.0D, 1.0D);
        int minFrames = error > (vertical ? 6.0F : 10.0F) ? 4 : 5;
        int maxFrames = error > (vertical ? 6.0F : 10.0F) ? 10 : 15;
        aimMovementTotalFrames[axis] = randomInt(minFrames, maxFrames + (int) Math.round(smoothness * 4.0D));
        aimMovementFrames[axis] = aimMovementTotalFrames[axis];
        aimMovementProfile[axis] = RANDOM.nextInt(4);
        aimMovementGain[axis] = MathHelper.clamp(0.82D + RANDOM.nextDouble() * 0.46D + error01 * 0.28D
            + speed01 * 0.12D - smoothness * 0.14D, 0.62D, 1.54D);
        aimMovementShape[axis] = MathHelper.clamp(0.72D + RANDOM.nextDouble() * 0.76D + smoothness * 0.22D, 0.62D, 1.72D);
        if (RANDOM.nextDouble() < (vertical ? 0.13D : 0.08D) + smoothness * 0.08D) {
            aimMovementPauseFrames[axis] = randomInt(1, 2);
        }
    }

    private double aimMovementProfile(int axis) {
        int total = Math.max(1, aimMovementTotalFrames[axis]);
        int elapsed = Math.max(0, total - aimMovementFrames[axis]);
        double t = MathHelper.clamp((elapsed + 1.0D) / (double) total, 0.0D, 1.0D);
        return switch (aimMovementProfile[axis]) {
            case 0 -> 0.52D + minimumJerkVelocity(t) * 0.58D;
            case 1 -> 0.42D + Math.pow(1.0D - Math.pow(1.0D - t, 2.25D), aimMovementShape[axis]) * 0.76D;
            case 2 -> 0.5D + (1.0D / (1.0D + Math.exp(-8.0D * (t - 0.48D)))) * 0.68D;
            default -> 0.48D + Math.pow(Math.sin(t * Math.PI), aimMovementShape[axis]) * 0.64D;
        };
    }

    private static double minimumJerkVelocity(double t) {
        return MathHelper.clamp(30.0D * t * t * (1.0D - t) * (1.0D - t) / 1.875D, 0.0D, 1.25D);
    }

    private double updateAimJitter(int axis, double error, double speed01, double smoothness,
                                   double jitterPercent, double frameScale, boolean vertical,
                                   boolean stationary) {
        if (stationary || jitterPercent <= 0.0D) {
            aimJitterTarget[axis] = 0.0D;
            aimJitterValue[axis] *= 0.45D;
            return Math.abs(aimJitterValue[axis]) < 1.0E-4D ? 0.0D : aimJitterValue[axis];
        }
        if (aimJitterFrames[axis]-- <= 0) {
            double jitter01 = MathHelper.clamp(jitterPercent / 100.0D, 0.0D, 1.0D);
            double distanceScale = 0.45D + MathHelper.clamp(error / (vertical ? 9.0D : 15.0D), 0.0D, 1.0D) * 0.65D;
            double amount = jitter01 * (vertical ? 0.113D : 0.156D)
                * distanceScale * (0.62D + speed01 * 0.42D) * (1.0D - smoothness * 0.36D);
            aimJitterTarget[axis] = RANDOM.nextGaussian() * amount;
            aimJitterFrames[axis] = randomInt(2, 7 + (int) Math.round(smoothness * 4.0D));
        }
        double blend = MathHelper.clamp((0.18D + RANDOM.nextDouble() * 0.1D) * frameScale, 0.04D, 0.42D);
        aimJitterValue[axis] += (aimJitterTarget[axis] - aimJitterValue[axis]) * blend;
        return aimJitterValue[axis];
    }

    private double manualAssistScale(float targetDelta, ManualAimSignal manual, boolean vertical) {
        float input = vertical ? manual.pitchDelta : manual.yawDelta;
        double energy = vertical ? manual.pitchEnergy + manual.yawEnergy * 0.08D
            : manual.yawEnergy + manual.pitchEnergy * 0.05D;
        double floor = vertical ? 0.016D : 0.012D;
        double passiveCone = vertical ? 12.0D : 22.0D;
        double idleCone = vertical ? 3.4D : 4.8D;
        double passive = Math.abs(targetDelta) < passiveCone ? (vertical ? 0.2D : 0.28D) : 0.0D;
        if (energy < floor) {
            return Math.abs(targetDelta) < idleCone ? passive : 0.0D;
        }
        double scale = MathHelper.clamp((energy - floor) / (vertical ? 0.48D : 0.62D), 0.0D, 1.0D);
        scale = Math.max(passive, (vertical ? 0.5D : 0.68D) + scale * (vertical ? 0.58D : 0.62D));
        if (Math.abs(input) > floor && Math.abs(targetDelta) > 0.75F
            && Math.signum(input) != Math.signum(targetDelta)) {
            scale *= vertical ? 0.7D : 0.62D;
        }
        return scale;
    }

    private ManualAimSignal sampleManualAim(ClientPlayerEntity player, double frameScale) {
        if (!aimHasCameraBaseline) {
            storeAimCameraBaseline(player);
            aimManualYawEnergy = 0.0D;
            aimManualPitchEnergy = 0.0D;
            return new ManualAimSignal(0.0F, 0.0F, 0.0D, 0.0D);
        }
        float yawDelta = MathHelper.wrapDegrees(player.getYaw() - aimLastCameraYaw);
        float pitchDelta = MathHelper.wrapDegrees(player.getPitch() - aimLastCameraPitch);
        double decay = Math.pow(0.8D, MathHelper.clamp(frameScale, 0.25D, 3.0D));
        aimManualYawEnergy = Math.max(Math.abs(yawDelta), aimManualYawEnergy * decay);
        aimManualPitchEnergy = Math.max(Math.abs(pitchDelta), aimManualPitchEnergy * decay);
        return new ManualAimSignal(yawDelta, pitchDelta, aimManualYawEnergy, aimManualPitchEnergy);
    }

    private void storeAimCameraBaseline(ClientPlayerEntity player) {
        aimLastCameraYaw = player.getYaw();
        aimLastCameraPitch = player.getPitch();
        aimHasCameraBaseline = true;
    }

    private float getAimVelocity(boolean vertical) {
        return vertical ? aimPitchVelocity : aimYawVelocity;
    }

    private void setAimVelocity(boolean vertical, float velocity) {
        if (vertical) {
            aimPitchVelocity = velocity;
        } else {
            aimYawVelocity = velocity;
        }
    }

    private double aimFrameScale() {
        long now = System.nanoTime();
        if (aimLastFrameNanos == 0L) {
            aimLastFrameNanos = now;
            return 1.0D;
        }
        double elapsed = (double) (now - aimLastFrameNanos) / 1.0E9D;
        aimLastFrameNanos = now;
        return MathHelper.clamp(elapsed * 60.0D, 0.25D, 3.0D);
    }

    private static double currentMouseSensitivity() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client == null ? 0.5D : MathHelper.clamp(client.options.getMouseSensitivity().getValue(), 0.0D, 1.0D);
    }

    private static double mouseSensitivityQuantum(double sensitivity) {
        double base = sensitivity * 0.6D + 0.2D;
        return base * base * base * 1.2D;
    }

    private float quantizeToMouseSensitivity(float step, float remainingDelta, double sensitivity,
                                             boolean vertical, boolean stable) {
        int axis = vertical ? 1 : 0;
        double adjustedStep = (double) step + aimQuantizationCarry[axis];
        float absStep = (float) Math.abs(adjustedStep);
        if (absStep <= 0.0F) {
            return 0.0F;
        }
        double quantum = mouseSensitivityQuantum(sensitivity);
        if (quantum <= 1.0E-4D) {
            return step;
        }
        float remaining = Math.abs(remainingDelta);
        if ((double) remaining < quantum * 0.75D) {
            return 0.0F;
        }
        double snapped = Math.max(quantum, (double) Math.round((double) absStep / quantum) * quantum);
        if (!stable && RANDOM.nextDouble() < (vertical ? 0.1D : 0.14D) && (double) absStep > quantum * 2.5D) {
            snapped += (RANDOM.nextBoolean() ? 1.0D : -1.0D) * quantum;
        }
        snapped = Math.max(quantum, snapped);
        if (snapped > (double) remaining) {
            snapped = Math.floor((double) remaining / quantum) * quantum;
        }
        if (snapped <= 0.0D) {
            return 0.0F;
        }
        float result = (float) (Math.signum(adjustedStep) * snapped);
        aimQuantizationCarry[axis] = MathHelper.clamp(adjustedStep - (double) result, -quantum * 0.66D, quantum * 0.66D);
        return result;
    }

    private static double sampleRange(double[] range) {
        double low = Math.min(range[0], range[1]);
        double high = Math.max(range[0], range[1]);
        return low + RANDOM.nextDouble() * Math.max(0.0D, high - low);
    }

    private static int randomInt(int min, int maxInclusive) {
        return min + RANDOM.nextInt(Math.max(1, maxInclusive - min + 1));
    }

    private static float randomFloat(float min, float max) {
        return min + RANDOM.nextFloat() * Math.max(0.0F, max - min);
    }

    private record ManualAimSignal(float yawDelta, float pitchDelta, double yawEnergy, double pitchEnergy) {
    }
}
