package dev.c4lam.celeste.rotation;

public enum MoveFixMode {
    /** Send the silent look without changing local movement processing. */
    OFF,
    /**
     * Legacy name retained for config/source compatibility. Modern clients send
     * directional input as booleans, so this is normalized to {@link #STRICT}
     * before it can affect gameplay.
     */
    @Deprecated
    NORMAL,
    /** Counter-rotate and snap to a legal eight-way key direction. */
    STRICT,
    /**
     * Keep the user's raw directional keys and camera-space local movement.
     * The silent yaw remains available for outgoing packets and combat actions,
     * but does not redirect local movement into the target.
     */
    ANARCHY;

    /** Returns a movement mode that the vanilla input packet can represent. */
    public MoveFixMode packetRepresentable() {
        return this == NORMAL ? STRICT : this;
    }
}
