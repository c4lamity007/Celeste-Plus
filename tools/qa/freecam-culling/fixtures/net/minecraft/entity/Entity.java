package net.minecraft.entity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.Vec3d;
public class Entity {
    protected final ClientWorld world;
    private double x, y, z;
    private float yaw, pitch, headYaw, bodyYaw;
    private boolean alive = true, removed;
    private Object pose = "standing";
    public boolean noClip;
    public double lastX, lastY, lastZ, lastRenderX, lastRenderY, lastRenderZ;
    public float lastYaw, lastPitch, lastHeadYaw, lastBodyYaw;
    public Entity(ClientWorld world) { this.world = world; }
    public ClientWorld getEntityWorld() { return world; }
    public double getX() { return x; }
    public double getY() { return y; }
    public double getZ() { return z; }
    public void setPosition(Vec3d value) { x = value.x; y = value.y; z = value.z; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public float getHeadYaw() { return headYaw; }
    public float getBodyYaw() { return bodyYaw; }
    public void setYaw(float value) { yaw = value; }
    public void setPitch(float value) { pitch = value; }
    public void setHeadYaw(float value) { headYaw = value; }
    public void setBodyYaw(float value) { bodyYaw = value; }
    public Object getPose() { return pose; }
    public void setPose(Object value) { pose = value; }
    public boolean isAlive() { return alive; }
    public boolean isRemoved() { return removed; }
    public void setAlive(boolean value) { alive = value; }
    public void setRemoved(boolean value) { removed = value; }
    public void changeLookDirection(double dx, double dy) {
        yaw += (float)dx * 0.15F;
        pitch = Math.max(-90.0F, Math.min(90.0F, pitch + (float)dy * 0.15F));
    }
}
