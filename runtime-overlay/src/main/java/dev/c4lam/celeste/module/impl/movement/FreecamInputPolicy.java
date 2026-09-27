package dev.c4lam.celeste.module.impl.movement;

import dev.c4lam.celeste.rotation.MoveFix;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;

/** Pure input decision shared by startup and late-loaded Freecam hooks. */
public final class FreecamInputPolicy {
    private static final MoveFix.Correction NEUTRAL =
        new MoveFix.Correction(Vec2f.ZERO, PlayerInput.DEFAULT);

    private FreecamInputPolicy() { }

    /**
     * Null preserves the supplied vanilla state; a detached camera discards that
     * movement state in favor of the canonical neutral vector and key packet.
     */
    public static MoveFix.Correction correction(boolean detached, Vec2f movement, PlayerInput keys) {
        return detached ? NEUTRAL : null;
    }
}
