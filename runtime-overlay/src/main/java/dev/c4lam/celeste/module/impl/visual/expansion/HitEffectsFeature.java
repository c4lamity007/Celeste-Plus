package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.auth.AuthBootstrap;
import dev.c4lam.celeste.module.ModuleContext;
import dev.c4lam.celeste.module.ModuleFeature;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
import net.minecraft.block.ShapeContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Visual feedback for attacks; no combat input, packets or game objects are created. */
public final class HitEffectsFeature implements ModuleFeature {
    private static final org.slf4j.Logger LOGGER=dev.c4lam.celeste.compat.RuntimeLogging.getLogger("Celeste/HitEffects");
    private static volatile HitEffectsFeature active;
    private final HitEffectsState state=new HitEffectsState();
    private final HitEffectsState.Clock clock=new HitEffectsState.Clock();
    private boolean enabled;
    private ClientWorld world;
    private Object token=new Object();
    private Options options=Options.DEFAULT;
    private long sequence;
    private record Options(boolean particles,boolean ground,boolean displacement,
                           HitEffectsState.Style style,int amount,double size,int color) {
        static final Options DEFAULT=new Options(true,false,false,HitEffectsState.Style.SPARKS,12,1,0x4BD2FF);
    }
    @Override public String name() { return "Hit Effects"; }
    @Override public synchronized void onEnable(ModuleContext context) {
        clear(); enabled=true; active=this; update(context);
    }
    @Override public synchronized void onDisable(ModuleContext context) {
        enabled=false; if(active==this) active=null; clear();
    }
    @Override public synchronized void onWorldChanged(ModuleContext context) { clear(); }
    @Override public synchronized void onStartTick(ModuleContext context) { update(context); }
    @Override public synchronized void onRenderFrame(ModuleContext context) { update(context); }

    /** Called once the existing attack hook has accepted a living target. */
    public static void recordAttack(MinecraftClient client,LivingEntity target) {
        HitEffectsFeature feature=active;
        if(feature==null) return;
        try { feature.hit(client,target); }
        catch(RuntimeException | LinkageError failure) {
            // Visual feedback must never interrupt the actual attack, even with an incompatible block shape.
            feature.failClosed();
            LOGGER.warn("Hit Effects stopped after a visual error; toggle it to retry",failure);
        }
    }
    private synchronized void hit(MinecraftClient client,LivingEntity target) {
        if(!enabled || !AuthBootstrap.authorized() || client==null || client.world==null || client.player==null
            || client.isPaused() || target==null || target==client.player || !target.isAlive() || target.isRemoved()
            || !client.player.isAlive() || client.player.isSpectator() || target.isSpectator()
            || target.getEntityWorld()!=client.world || client.player.getEntityWorld()!=client.world) return;
        if(world!=client.world) { clear(); world=client.world; }
        if(!state.accept(client.world.getTime(),target.getId())) return;
        Options settings=options;
        if(!settings.particles && !settings.ground && !settings.displacement) return;
        double now=clock.sample(System.nanoTime(),false);
        int color=VisualAtmosphere.tint(settings.color,now,0);
        Vec3d position=target.getEntityPos();
        long seed=System.nanoTime() ^ ++sequence*0x9E3779B97F4A7C15L ^ target.getId();
        if(settings.particles) state.burst(new HitEffectsState.Point(position.x,
            position.y+target.getHeight()*.52,position.z),now,settings.amount,settings.size,color,settings.style,seed);
        if(settings.ground) {
            // A fresh, tightly capped collision snapshot exists only during this call.
            Surface surface=new Surface(client.world,ShapeContext.of(target));
            int start=VisualAtmosphere.tint(settings.color,0,.75);
            int end=VisualAtmosphere.tint(HitEffectsState.gradient(settings.color,0xFFFFFF,.22),0,.25);
            state.ground(HitGroundPath.build(position.x,position.y,position.z,seed,surface),now,settings.size,start,end);
        }
        if(settings.displacement) HitDisplacementRenderer.pulse(client,color,settings.size);
    }

    synchronized HitEffectsRenderer.Frame extract(WorldExtractionContext context) {
        MinecraftClient client=MinecraftClient.getInstance();
        if(!enabled || client.player==null || !client.player.isAlive() || client.player.isSpectator()
            || client.world!=world || world!=context.world() || context.camera()==null || !context.camera().isReady()) {
            if(world!=client.world || client.player==null || !client.player.isAlive()) clear();
            return HitEffectsRenderer.Frame.EMPTY;
        }
        HitEffectsState.Frame effects=state.frame(clock.sample(System.nanoTime(),client.isPaused()));
        if(effects.isEmpty()) return HitEffectsRenderer.Frame.EMPTY;
        Vector3f right=context.camera().getRotation().transform(new Vector3f(1,0,0));
        Vector3f up=context.camera().getRotation().transform(new Vector3f(0,1,0));
        return new HitEffectsRenderer.Frame(token,world,effects,new Vec3d(right.x,right.y,right.z),new Vec3d(up.x,up.y,up.z));
    }
    synchronized boolean current(HitEffectsRenderer.Frame frame) {
        MinecraftClient client=MinecraftClient.getInstance();
        return enabled && frame!=null && token==frame.token() && world==frame.world()
            && client.world==world && client.player!=null && client.player.isAlive() && !client.player.isSpectator();
    }
    private void update(ModuleContext context) {
        if(!enabled) return;
        if(world!=context.client().world) { clear(); world=context.client().world; }
        HitEffectsState.Style style=switch(context.mode("Particle Style","Sparks")) {
            case "Stars" -> HitEffectsState.Style.STARS;
            case "Dots" -> HitEffectsState.Style.DOTS;
            default -> HitEffectsState.Style.SPARKS;
        };
        Options next=new Options(context.toggle("Particles",true),context.toggle("Ground Effect",false),
            context.toggle("Displacement",false),style,
            (int)Math.round(HitEffectsState.finite(context.slider("Amount",12),12,4,24)),
            HitEffectsState.finite(context.slider("Size",1),1,.5,2),context.colorPick("Color",0x4BD2FF)&0xFFFFFF);
        if(options.particles && !next.particles) { state.clearParticles(); token=new Object(); }
        if(options.ground && !next.ground) { state.clearGround(); token=new Object(); }
        if(options.displacement && !next.displacement) HitDisplacementRenderer.reset();
        options=next;
    }
    private void clear() {
        state.clear(); clock.clear(); world=null; token=new Object(); HitDisplacementRenderer.reset();
    }
    private synchronized void failClosed() {
        enabled=false; if(active==this) active=null;
        state.clear(); clock.clear(); world=null; token=new Object();
        try { HitDisplacementRenderer.reset(); } catch(RuntimeException | LinkageError ignored) { }
    }

    /** Cached read-only collision probes: at most 384 loaded block states per accepted hit. */
    private static final class Surface implements HitGroundPath.Surface {
        private static final int MAX_BLOCK_READS=384;
        private final ClientWorld world;
        private final ShapeContext shapeContext;
        private final Map<BlockPos,List<Box>> cells=new HashMap<>();
        private int reads;
        Surface(ClientWorld world,ShapeContext context) { this.world=world; shapeContext=context; }
        @Override public double height(double x,double z,double expected,double rise,double drop) {
            double best=Double.NaN,bestDistance=Double.POSITIVE_INFINITY;
            // Both sides of a seam can support the line; geometry depth clips its buried half at a wall.
            for(int side=0;side<4;side++) {
                double sx=x+((side&1)==0?-.012:.012),sz=z+((side&2)==0?-.012:.012);
                double candidate=column(sx,sz,expected,rise,drop);
                double distance=Math.abs(candidate-expected);
                if(Double.isFinite(candidate) && distance<bestDistance) { best=candidate; bestDistance=distance; }
            }
            return best;
        }
        private double column(double x,double z,double expected,double rise,double drop) {
            int bx=(int)Math.floor(x),bz=(int)Math.floor(z);
            double best=Double.NaN;
            int low=(int)Math.floor(expected-drop)-1,high=(int)Math.floor(expected+rise);
            for(int by=low;by<=high;by++) {
                List<Box> boxes=boxes(new BlockPos(bx,by,bz));
                if(boxes==null) return Double.NaN;
                for(Box box:boxes) {
                    if(x<bx+box.minX || x>bx+box.maxX || z<bz+box.minZ || z>bz+box.maxZ) continue;
                    double top=by+box.maxY;
                    if(top>=expected-drop-.001 && top<=expected+rise+.001 && (!Double.isFinite(best)||top>best)) best=top;
                }
            }
            if(!Double.isFinite(best)) return best;
            // Reject a floor covered by another collision shape, including an inaccessible wall top.
            for(int by=(int)Math.floor(best);by<=high;by++) {
                List<Box> boxes=boxes(new BlockPos(bx,by,bz));
                if(boxes==null) return Double.NaN;
                for(Box box:boxes) if(x>=bx+box.minX && x<=bx+box.maxX && z>=bz+box.minZ && z<=bz+box.maxZ
                    && by+box.minY<best+.04 && by+box.maxY>best+.025) return Double.NaN;
            }
            return best;
        }
        private List<Box> boxes(BlockPos pos) {
            List<Box> known=cells.get(pos);
            if(known!=null) return known;
            if(reads>=MAX_BLOCK_READS || world.isOutOfHeightLimit(pos) || !world.isChunkLoaded(pos)) return null;
            reads++;
            List<Box> result=List.copyOf(world.getBlockState(pos).getCollisionShape(world,pos,shapeContext).getBoundingBoxes());
            cells.put(pos,result);
            return result;
        }
    }
}
