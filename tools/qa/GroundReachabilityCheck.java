import dev.c4lam.celeste.module.impl.visual.expansion.GroundWalkability;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

/** Actual controller/immutable atlas bytecode; only external Minecraft boundary types are substituted. */
public final class GroundReachabilityCheck {
    static final String ROOT="dev/c4lam/celeste/module/impl/visual/expansion/GroundReachability";
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    public interface ProbeBlockView { }
    public static class ProbeEntity { }
    public static final class ProbePlayer extends ProbeEntity {
        double x=.5,y,z=.5,width=.6,height=1.8;boolean ground=true;
        public double getX(){return x;}public double getY(){return y;}public double getZ(){return z;}
        public boolean isOnGround(){return ground;}
        public ProbeBox getBoundingBox(){return new ProbeBox(x-width*.5,y,z-width*.5,x+width*.5,y+height,z+width*.5);}
    }
    public static final class ProbeBox {
        public final double minX,minY,minZ,maxX,maxY,maxZ;
        public ProbeBox(double x,double y,double z,double xx,double yy,double zz){minX=x;minY=y;minZ=z;maxX=xx;maxY=yy;maxZ=zz;}
        public double getLengthX(){return maxX-minX;}public double getLengthY(){return maxY-minY;}
    }
    public static class ProbePos {
        int x,y,z;
        public static long asLong(int x,int y,int z){return ((long)x&0x3FFFFFF)<<38|((long)z&0x3FFFFFF)<<12|((long)y&0xFFF);}
    }
    public static final class ProbeMutable extends ProbePos {public ProbeMutable set(int x,int y,int z){this.x=x;this.y=y;this.z=z;return this;}}
    public static final class ProbeBlock { }
    public static final class ProbeBlocks {public static final ProbeBlock POWDER_SNOW=new ProbeBlock(),SCAFFOLDING=new ProbeBlock();}
    public static final class ProbeFluid {public boolean isEmpty(){return true;}}
    public interface ProbeShapeContext {static ProbeShapeContext of(ProbeEntity entity){return new ProbeShapeContext() { };}}
    public static final class ProbeShape {
        final List<ProbeBox> boxes;ProbeShape(List<ProbeBox> boxes){this.boxes=boxes;}
        public List<ProbeBox> getBoundingBoxes(){return boxes;}
    }
    public static final class ProbeState {
        final ProbeShape shape;ProbeState(boolean solid){shape=new ProbeShape(solid?List.of(new ProbeBox(0,0,0,1,1,1)):List.of());}
        public ProbeFluid getFluidState(){return new ProbeFluid();}public boolean isOf(ProbeBlock block){return false;}
        public ProbeShape getCollisionShape(ProbeBlockView view,ProbePos position,ProbeShapeContext context){return shape;}
    }
    public static final class ProbeWorld implements ProbeBlockView {
        static final ProbeState AIR=new ProbeState(false),STONE=new ProbeState(true);
        final Map<Long,ProbeState> edits=new HashMap<>();final Map<Long,ProbeObservation> observations=new HashMap<>();long revision;boolean delayObservation;int reads,level=-1,extent=4;
        public boolean isOutOfHeightLimit(ProbePos p){return p.y< -64||p.y>320;}
        public boolean isChunkLoaded(ProbePos p){return true;}
        public ProbeState getBlockState(ProbePos p){reads++;return edits.getOrDefault(ProbePos.asLong(p.x,p.y,p.z),p.y==level&&Math.abs(p.x)<=extent&&Math.abs(p.z)<=extent?STONE:AIR);}
    }
    public record ProbeObservation(long revision,ProbeState state) { }
    public static final class ProbeAuraServerState {
        public static long revision(ProbeWorld world){return world==null?0:world.revision;}
        public static ProbeObservation block(ProbeWorld world,ProbePos pos){if(world.delayObservation){world.delayObservation=false;java.util.concurrent.locks.LockSupport.parkNanos(4_000_000);}
            return world.observations.get(ProbePos.asLong(pos.x,pos.y,pos.z));}
    }
    static final class FixtureLoader extends ClassLoader {
        final Path classes;
        FixtureLoader(Path classes){super(GroundReachabilityCheck.class.getClassLoader());this.classes=classes;}
        @Override protected Class<?> findClass(String name)throws ClassNotFoundException{
            String internal=name.replace('.','/');if(!internal.equals(ROOT)&&!internal.startsWith(ROOT+"$"))throw new ClassNotFoundException(name);
            Map<String,String> map=new HashMap<>();
            String[][] types={{"dev/c4lam/celeste/module/impl/combat/AuraServerState","ProbeAuraServerState"},{"dev/c4lam/celeste/module/impl/combat/AuraServerState$BlockObservation","ProbeObservation"},{"net/minecraft/client/world/ClientWorld","ProbeWorld"},{"net/minecraft/client/network/ClientPlayerEntity","ProbePlayer"},
                {"net/minecraft/entity/Entity","ProbeEntity"},{"net/minecraft/world/BlockView","ProbeBlockView"},
                {"net/minecraft/util/math/Box","ProbeBox"},{"net/minecraft/util/math/BlockPos","ProbePos"},{"net/minecraft/util/math/BlockPos$Mutable","ProbeMutable"},
                {"net/minecraft/block/BlockState","ProbeState"},{"net/minecraft/block/Block","ProbeBlock"},{"net/minecraft/block/Blocks","ProbeBlocks"},
                {"net/minecraft/block/ShapeContext","ProbeShapeContext"},{"net/minecraft/fluid/FluidState","ProbeFluid"},{"net/minecraft/util/shape/VoxelShape","ProbeShape"}};
            for(String[] type:types)map.put(type[0],"GroundReachabilityCheck$"+type[1]);
            try{ClassWriter writer=new ClassWriter(0);new ClassReader(Files.readAllBytes(classes.resolve(internal+".class"))).accept(new ClassRemapper(writer,new Remapper(){@Override public String map(String value){return map.getOrDefault(value,value);}}),0);
                byte[] bytes=writer.toByteArray();return defineClass(name,bytes,0,bytes.length);
            }catch(Exception e){throw new ClassNotFoundException(name,e);}
        }
    }
    static Object invoke(Method method,Object...args)throws Exception{return method.invoke(null,args);}
    static int number(Object mask,String method)throws Exception{return (int)mask.getClass().getMethod(method).invoke(mask);}
    static byte[] bytes(Object mask)throws Exception {ByteBuffer buffer=ByteBuffer.allocate(number(mask,"sizeX")*number(mask,"sizeY")*number(mask,"sizeZ")*4);
        mask.getClass().getMethod("copyTo",ByteBuffer.class).invoke(mask,buffer);check(buffer.position()==buffer.capacity(),"mask copy length is not RGBA32");return buffer.array();}
    static boolean bit(Object mask,int x,double top,int z)throws Exception {int worldY=(int)Math.floor(top-.015),level=GroundWalkability.heightLevel(top);
        x-=number(mask,"originX");int y=worldY-number(mask,"originY");z-=number(mask,"originZ");int sx=number(mask,"sizeX"),sy=number(mask,"sizeY"),sz=number(mask,"sizeZ");
        if(x<0||x>=sx||y<0||y>=sy||z<0||z>=sz)return false;return ((bytes(mask)[((y*sz+z)*sx+x)*4+(level>>3)]&255)&(1<<(level&7)))!=0;}
    static void tick(Method tick,ProbeWorld world,ProbePlayer player)throws Exception{int reads=world.reads;invoke(tick,world,player);check(world.reads-reads<=768,"tick exceeded bounded state reads");}
    static Object awaitMask(Method tick,Method snapshot,ProbeWorld world,ProbePlayer player)throws Exception{
        for(int i=0;i<300;i++){tick(tick,world,player);Object mask=invoke(snapshot);if(mask!=null)return mask;}throw new AssertionError("controller never published supported seed");}
    static Object member(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    static Object accessor(Object owner,String name)throws Exception{Method method=owner.getClass().getDeclaredMethod(name);method.setAccessible(true);return method.invoke(owner);}
    static void serverEdits(Method tick,Method snapshot,Field state,ProbeWorld world,ProbePlayer player)throws Exception{
        awaitMask(tick,snapshot,world,player);Object before=state.get(null);for(int i=0;i<300&&!((GroundWalkability.Search)member(before,"search")).complete();i++)tick(tick,world,player);
        check(((GroundWalkability.Search)member(before,"search")).complete(),"small graph did not settle before server observation test");byte[] settled=bytes(invoke(snapshot));
        List<?> cached=(List<?>)member(state.get(null),"order");check(cached.size()>64,"server scan fixture needs multiple probe batches");
        world.revision++;world.delayObservation=true;tick(tick,world,player);
        check(state.get(null)==before&&invoke(snapshot)==null,"server revision scan budget did not hide mask while retaining graph");
        awaitMask(tick,snapshot,world,player);check(state.get(null)==before,"entity-only server revision reset reachable graph");check(Arrays.equals(settled,bytes(invoke(snapshot))),"completed entity-only scan failed to restore unchanged mask/count");
        cached=(List<?>)member(state.get(null),"order");Object last=cached.get(cached.size()-1);int x=(int)accessor(last,"x"),y=(int)accessor(last,"y"),z=(int)accessor(last,"z");
        ProbeState old=(ProbeState)accessor(last,"state"),changed=old==ProbeWorld.AIR?ProbeWorld.STONE:ProbeWorld.AIR;long key=ProbePos.asLong(x,y,z);
        world.edits.put(key,changed);world.observations.put(key,new ProbeObservation(++world.revision,changed));tick(tick,world,player);
        check(invoke(snapshot)==null,"server edit at end of cache retained stale rendered mask");
        for(int i=0;i<300&&state.get(null)==before;i++)tick(tick,world,player);check(state.get(null)!=before,"server route edit was never consumed");
        world.edits.remove(key);world.observations.clear();awaitMask(tick,snapshot,world,player);before=state.get(null);world.revision+=1025;tick(tick,world,player);
        check(invoke(snapshot)==null&&state.get(null)!=before,"lost server history did not fail closed and restart");
    }
    public static void main(String[] args)throws Exception {
        Class<?> c=new FixtureLoader(Path.of(args[0])).loadClass(ROOT.replace('/','.'));
        Method tick=c.getMethod("tick",ProbeWorld.class,ProbePlayer.class),snapshot=c.getMethod("snapshot"),reset=c.getMethod("reset"),invalidate=c.getMethod("invalidate");
        Field state=c.getDeclaredField("state");state.setAccessible(true);
        ProbeWorld world=new ProbeWorld();ProbePlayer player=new ProbePlayer();check(invoke(snapshot)==null,"initial mask active");
        Object mask=awaitMask(tick,snapshot,world,player);check(bit(mask,0,0,0),"supported seed missing from atlas");byte[] pristine=bytes(mask),edited=bytes(mask);edited[0]^=127;check(Arrays.equals(pristine,bytes(mask)),"caller buffer changed immutable mask");
        Object grounded=state.get(null);player.ground=false;player.y=.8;player.x=.7;for(int i=0;i<3;i++)tick(tick,world,player);
        check(state.get(null)==grounded&&invoke(snapshot)!=null&&bit(invoke(snapshot),0,0,0),"local jump discarded connected ground mask");player.ground=true;player.y=0;player.x=.5;
        invoke(invalidate);check(invoke(snapshot)==null,"invalidate left old mask published");awaitMask(tick,snapshot,world,player);check(state.get(null)!=grounded,"invalidate retained old graph");
        Object beforeEdit=state.get(null);world.edits.put(ProbePos.asLong(0,-1,0),ProbeWorld.AIR);boolean invalidated=false;
        for(int i=0;i<400;i++){tick(tick,world,player);if(state.get(null)!=beforeEdit){invalidated=true;break;}}
        check(invalidated&&invoke(snapshot)==null,"cached collision edit retained previous reachable mask");
        ProbeWorld other=new ProbeWorld();other.level=0;player.y=1;mask=awaitMask(tick,snapshot,other,player);check(bit(mask,0,1,0)&&!bit(mask,0,0,0),"new world inherited previous height mask");
        serverEdits(tick,snapshot,state,other,player);
        awaitMask(tick,snapshot,other,player);player.width=3;tick(tick,other,player);check(invoke(snapshot)==null&&state.get(null)==null,"oversized player retained mask for shrunken collision body");player.width=.6;
        awaitMask(tick,snapshot,other,player);player.height=Double.NaN;tick(tick,other,player);check(invoke(snapshot)==null&&state.get(null)==null,"nonfinite body retained reachable mask");player.height=1.8;
        invoke(reset);check(invoke(snapshot)==null&&state.get(null)==null,"reset retains mask/controller");awaitMask(tick,snapshot,other,player);invoke(tick,null,player);check(invoke(snapshot)==null&&state.get(null)==null,"disconnect retains mask/controller");
        Class<?> maskClass=c.getClassLoader().loadClass(ROOT.replace('/','.')+"$Mask");Constructor<?> constructor=maskClass.getDeclaredConstructor(long.class,GroundWalkability.Search.class);constructor.setAccessible(true);
        GroundWalkabilityCheck.World partial=new GroundWalkabilityCheck.World().shape(1,0,0,1,0,0,2,.5,1).shape(1,0,0,1.5,.5,0,2,1,1).shape(1,2,0,1,2.6,0,2,2.7,1);
        var half=GroundWalkabilityCheck.run(partial,1.2,.5,.5,.6,3);mask=constructor.newInstance(999L,half);check(bit(mask,1,.5,0)&&!bit(mask,1,1,0),"lower stair atlas bit admits upper top in same voxel");
        var full=GroundWalkabilityCheck.run(new GroundWalkabilityCheck.World().block(1,0,0),1.5,1,.5,.6,3);mask=constructor.newInstance(1000L,full);check(!bit(mask,1,.5,0)&&bit(mask,1,1,0),"upper stair atlas bit admits lower top in same voxel");
        System.out.println("PASS GroundReachability: "+checks+" actual controller/atlas assertions; bounded sampling, jump retention, immutable copies, invalidate/edit/world/disconnect reset, observed server changes with bounded continuation, and independent fractional-height bits.");
    }
}
