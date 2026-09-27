package net.minecraft.client.world;
import java.util.*;import net.minecraft.block.*;import net.minecraft.entity.player.PlayerEntity;import net.minecraft.util.math.*;import net.minecraft.util.hit.BlockHitResult;import net.minecraft.world.RaycastContext;
public final class ClientWorld {
 public final Map<BlockPos,BlockState> blocks=new HashMap<>();public final List<PlayerEntity> players=new ArrayList<>();
 public boolean loaded=true,insideBorder=true,anchorWorks;
 public final Map<BlockPos,Boolean> anchorWorksAt=new HashMap<>();
 public BlockHitResult obstruction;
 public BlockState getBlockState(BlockPos pos){return blocks.getOrDefault(pos,Blocks.AIR.getDefaultState());}
 public net.minecraft.fluid.FluidState getFluidState(BlockPos pos){return new net.minecraft.fluid.FluidState();}
 public List<PlayerEntity> getPlayers(){return players;}
 public boolean isChunkLoaded(int x,int z){return loaded;}public ClientWorld getWorldBorder(){return this;}public boolean contains(BlockPos pos){return insideBorder;}
 public ClientWorld getEnvironmentAttributes(){return this;}public boolean getAttributeValue(Object key,Vec3d pos){return anchorWorks;}
 public boolean getAttributeValue(Object key,BlockPos pos){return anchorWorksAt.getOrDefault(pos,anchorWorks);}
 public BlockHitResult raycast(RaycastContext context){return obstruction==null?BlockHitResult.miss():obstruction;}
}
