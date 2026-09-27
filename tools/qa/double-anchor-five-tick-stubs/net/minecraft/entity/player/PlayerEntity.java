package net.minecraft.entity.player;
import net.minecraft.client.world.ClientWorld;import net.minecraft.item.ItemStack;import net.minecraft.util.math.Vec3d;import net.minecraft.util.Hand;
public class PlayerEntity extends net.minecraft.entity.Entity {
 public int hurtTime;public boolean spectator,creative,crouching;public final PlayerInventory inventory=new PlayerInventory();public ClientWorld world;public ItemStack offhand=ItemStack.EMPTY;
 public boolean isSpectator(){return spectator;}public boolean isCreative(){return creative;}public PlayerInventory getInventory(){return inventory;}
 public float getYaw(){return 0;}public float getPitch(){return 0;}public Vec3d getRotationVec(float delta){return new Vec3d(0,0,1);}
 public ItemStack getMainHandStack(){return inventory.getSelectedStack();}public void swingHand(Hand hand){}
 public boolean hasVehicle(){return false;}
 public boolean shouldCancelInteraction(){return crouching;}public ItemStack getOffHandStack(){return offhand;}public Vec3d getEntityPos(){return position;}
}
