package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AmbientLayersCheck {
    private static int checks;
    private static void check(boolean value,String message) { checks++;if(!value)throw new AssertionError(message); }
    private static AmbientEffectsLayers.Layer layer(AmbientField.Style style) { return new AmbientEffectsLayers.Layer(style,.6,1); }
    private static void close(double actual,double expected,String message) { check(Math.abs(actual-expected)<1e-8,message+": "+actual); }
    private static void limits() {
        var styles=AmbientField.Style.values();
        for(int mask=0;mask<(1<<styles.length);mask++) {
            List<AmbientEffectsLayers.Layer> requested=new ArrayList<>();
            for(int i=0;i<styles.length;i++)if((mask&(1<<i))!=0)requested.add(layer(styles[i]));
            List<AmbientEffectsLayers.Layer> layers=AmbientEffectsLayers.normalize(requested);
            int total=0,minimum=Integer.MAX_VALUE,maximum=0;
            for(var selected:layers)if(selected.style()!=AmbientField.Style.TRON_TRAILS) {
                int cap=AmbientEffectsLayers.particleCap(layers,selected.style());
                check(cap>=51,"A selected style is starved");total+=cap;minimum=Math.min(minimum,cap);maximum=Math.max(maximum,cap);
            }
            check(total<=AmbientEffectsLayers.MAX_PARTICLES,"Combined particle budget exceeded");
            if(layers.stream().filter(l->l.style()!=AmbientField.Style.TRON_TRAILS).count()>1)check(maximum-minimum<=1,"Unfair layer reservation");
        }
        for(var style:styles) {
            var one=List.of(layer(style));
            int expected=style==AmbientField.Style.TRON_TRAILS?0:style==AmbientField.Style.TESSERACTS?192:128;
            check(AmbientEffectsLayers.particleCap(one,style)==expected,"Single-style cap changed");
        }
        var normalized=AmbientEffectsLayers.normalize(Arrays.asList(null,layer(styles[0]),layer(styles[0]),new AmbientEffectsLayers.Layer(null,.6,1)));
        check(normalized.size()==1,"Duplicate/null layer accepted");
        var zero=List.of(new AmbientEffectsLayers.Layer(styles[0],0,1),layer(styles[4]));
        check(AmbientEffectsLayers.particleCap(zero,styles[4])==192&&AmbientEffectsLayers.particleCap(zero,styles[0])==0,"Zero density stole capacity");
        var invalid=new AmbientEffectsLayers.Layer(styles[0],Double.NaN,Double.POSITIVE_INFINITY);
        close(invalid.density(),.6,"Density fallback");close(invalid.size(),1,"Size fallback");
        try { normalized.clear();throw new AssertionError("Mutable snapshot"); } catch(UnsupportedOperationException expected) { checks++; }
    }
    private static void lifecycle() {
        var state=new AmbientEffectsLayers.State(991);
        Object world=new Object();
        var layers=List.of(layer(AmbientField.Style.TESSERACTS),layer(AmbientField.Style.TRON_TRAILS));
        close(state.sample(world,0,false,layers),0,"Initial time");
        close(state.sample(world,1_000_000_000L,false,layers),1,"Advancing time");
        var originalTron=state.trails().sample(2,5,2,1,.6,1,(x,y,z)->true);
        var originalCubes=state.tesseracts().candidates(2,5,2,1,.6);
        close(state.sample(world,2_000_000_000L,true,layers),1,"Pause transition");
        close(state.sample(world,92_000_000_000L,true,layers),1,"Paused time");
        check(originalTron.equals(state.trails().sample(2,5,2,1,.6,1,(x,y,z)->true)),"Tron moved during pause");
        check(originalCubes.equals(state.tesseracts().candidates(2,5,2,1,.6)),"Tesseracts moved during pause");
        close(state.sample(world,93_000_000_000L,false,layers),1,"Resume includes pause time");
        close(state.sample(world,94_000_000_000L,false,layers),2,"Resume time");
        state.sample(world,95_000_000_000L,false,List.of());
        state.sample(world,96_000_000_000L,false,layers);
        var resetTron=state.trails().sample(2,5,2,4,.6,1,(x,y,z)->true);
        var freshTron=new AmbientTronField(991).sample(2,5,2,4,.6,1,(x,y,z)->true);
        check(resetTron.equals(freshTron),"Removing/readding Tron retained old trails");
        check(!originalCubes.equals(state.tesseracts().candidates(2,5,2,1,.6)),"Removing Tesseracts retained old lifetimes");
        state.sample(world,97_000_000_000L,false,List.of(new AmbientEffectsLayers.Layer(AmbientField.Style.TRON_TRAILS,0,1)));
        check(state.trails().sample(2,5,2,1,.6,1,(x,y,z)->true).equals(new AmbientTronField(991).sample(2,5,2,1,.6,1,(x,y,z)->true)),"Density zero did not clear trail state");
        Object nextWorld=new Object();
        close(state.sample(nextWorld,100_000_000_000L,false,layers),0,"World switch did not reset time");
        check(state.isWorld(nextWorld),"World identity not updated");
        state.reset();check(!state.hasWorld(),"Disable retained world");
        close(state.sample(nextWorld,110_000_000_000L,false,layers),0,"Disable retained elapsed time");
    }
    private static void cache() {
        int[] worldReads={0,0};
        var cache=new AmbientEffectsAirCache(new AmbientEffectsAirCache.Cells() {
            public boolean clear(int x,int y,int z) {worldReads[0]++;return x>=0;}
            public boolean skyVisible(int x,int y,int z) {worldReads[1]++;return y>=0;}
        });
        cache.beginLayer(6);
        for(int i=0;i<30;i++)check(cache.openSky(1,1,1),"Cached cell unavailable");
        check(worldReads[0]==1&&worldReads[1]==1,"Repeated cell read more than once");
        check(!cache.clear(-1,0,0)&&!cache.clear(-1,0,0),"Solid cell should be blocked");
        check(worldReads[0]==2,"Blocked cell was not cached");
        for(int layer=0;layer<6;layer++) {
            cache.beginLayer(6);
            int before=cache.probes();
            for(int i=0;i<10_000;i++)cache.openSky(layer*20_000+i,2,7);
            check(cache.probes()-before<=AmbientEffectsAirCache.MAX_WORLD_PROBES/6,"Layer exceeded fair read budget");
            if(layer<5)check(cache.probes()>before,"Earlier effect consumed later effect budget");
        }
        check(cache.probes()<=AmbientEffectsAirCache.MAX_WORLD_PROBES,"Total read budget exceeded");
        check(cache.probes()==worldReads[0]+worldReads[1],"Probe accounting mismatch");
    }
    public static void main(String[] args) {
        limits();lifecycle();cache();
        System.out.println("PASS Ambient layers behavior: "+checks+" checks; 64 combinations, fair caps, independent removal, pause/world/disable resets and bounded shared air/sky reads.");
    }
}
