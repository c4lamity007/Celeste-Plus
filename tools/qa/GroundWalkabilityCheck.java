import dev.c4lam.celeste.module.impl.visual.expansion.GroundWalkability;
import java.util.*;

/** Real stance search with deterministic collision-shape worlds; no Minecraft stubs in the algorithm. */
public final class GroundWalkabilityCheck {
    static int checks;
    static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    record Key(int x,int y,int z) { }
    static final class World implements GroundWalkability.Probe {
        final Map<Key,List<GroundWalkability.Box>> boxes=new HashMap<>(),supportOverride=new HashMap<>();
        final Set<Key> unloaded=new HashSet<>(),nonSupporting=new HashSet<>();int queries;
        public GroundWalkability.Cell cell(int x,int y,int z){queries++;Key key=new Key(x,y,z);
            List<GroundWalkability.Box> collision=boxes.getOrDefault(key,List.of());return new GroundWalkability.Cell(!unloaded.contains(key),collision,supportOverride.getOrDefault(key,nonSupporting.contains(key)?List.of():collision));}
        World shape(int cellX,int cellY,int cellZ,double x0,double y0,double z0,double x1,double y1,double z1){
            boxes.computeIfAbsent(new Key(cellX,cellY,cellZ),k->new ArrayList<>()).add(new GroundWalkability.Box(x0,y0,z0,x1,y1,z1));return this;}
        World block(int x,int y,int z){return shape(x,y,z,x,y,z,x+1,y+1,z+1);}
        World floor(int minX,int maxX,int minZ,int maxZ){for(int x=minX;x<=maxX;x++)for(int z=minZ;z<=maxZ;z++)block(x,-1,z);return this;}
    }
    static GroundWalkability.Search search(double x,double feet,double z,double width){return new GroundWalkability.Search(x,feet,z,width,1.8,6,6);}
    static void drain(GroundWalkability.Search search,GroundWalkability.Probe world,int budget){int frames=0;
        while(!search.complete()){int used=search.advance(world,budget);check(used>=0&&used<=budget,"per-call edge budget exceeded: "+used+"/"+budget);check(++frames<10000,"search never completed");}}
    static GroundWalkability.Search run(World world,double x,double feet,double z,double width,int budget){var s=search(x,feet,z,width);check(s.seed(world),"valid stance seed rejected");drain(s,world,budget);return s;}
    static Set<GroundWalkability.Surface> result(GroundWalkability.Search s){return Set.copyOf(s.surfaces());}
    static void flatAndBudget(){World world=new World().floor(-7,7,-7,7);GroundWalkability.Search one=run(world,.5,0,.5,.6,1),many=run(world,.5,0,.5,.6,37);
        check(result(one).equals(result(many)),"edge budget changes reachable result");check(one.reached(.5,0,.5)&&one.reached(4.5,0,.5),"flat walking failed");
        check(!one.reached(100.5,0,.5),"radius escaped");check(one.surfaces().size()>20&&one.surfaces().size()<400,"unexpected unbounded flat frontier");
        for(var surface:one.surfaces())check(surface.y()==(int)Math.floor(surface.topY()-.015),"surface owner voxel disagrees with GPU atlas");}
    static void raisedRoute(){World world=new World().block(0,-1,0);
        for(int x=1;x<=3;x++)for(int y=0;y<x;y++)world.block(x,y,0);
        var s=run(world,.5,0,.5,.6,4);for(int x=1;x<=3;x++)check(s.reached(x+.5,x,.5),"one-block easy jump route failed at "+x);
        World isolated=new World().floor(-5,5,-5,5);for(int y=0;y<3;y++)isolated.block(3,y,0);s=run(isolated,.5,0,.5,.6,7);
        check(!s.reached(3.5,3,.5),"isolated three-block tower marked reachable");check(s.reached(2.5,0,.5),"tower incorrectly blocks nearby floor");
        World floating=new World().floor(-5,5,-5,5).block(3,3,0);s=run(floating,.5,0,.5,.6,9);check(!s.reached(3.5,4,.5),"floating top marked reachable");}
    static void slabs(){World world=new World().block(0,-1,0).shape(1,0,0,1,0,0,2,.5,1).block(2,0,0)
            .block(3,0,0).shape(3,1,0,3,1,0,4,1.5,1);var s=run(world,.5,0,.5,.6,3);
        check(s.reached(1.5,.5,.5)&&s.reached(2.5,1,.5)&&s.reached(3.5,1.5,.5),"fractional slab/stair route failed");
        check(s.surfaces().stream().anyMatch(v->v.x()==1&&v.y()==0&&Math.abs(v.topY()-.5)<1e-6),"fractional top not assigned its support cell");}
    static void obstructions(){World wall=new World().floor(0,2,0,0);for(int y=0;y<3;y++)wall.block(1,y,0);var s=run(wall,.5,0,.5,.6,8);
        check(!s.reached(2.5,0,.5),"wall bypassed without a walkable route");
        World low=new World().block(0,-1,0).block(1,0,0);for(int x=0;x<=1;x++)low.shape(x,2,0,x,2.1,0,x+1,2.2,1);
        s=run(low,.5,0,.5,.6,8);check(!s.reached(1.5,1,.5),"landing without standing headroom accepted");
        World jump=new World().block(0,-1,0).block(1,0,0);for(int x=0;x<=1;x++)jump.shape(x,2,0,x,2.95,0,x+1,3,1);
        s=run(jump,.5,0,.5,.6,8);check(!s.reached(1.5,1,.5),"jump arc passed through low ceiling although landing fits");
        World gap=new World().block(0,-1,0).block(2,-1,0);s=run(gap,.5,0,.5,.6,8);check(!s.reached(2.5,0,.5),"unsupported gap jump accepted");
        World diagonal=new World().block(0,-1,0).block(1,-1,1);s=run(diagonal,.5,0,.5,.6,8);check(s.reached(1.5,0,1.5),"ordinary adjacent diagonal jump rejected");
        World corner=new World().block(0,-1,0).block(1,-1,1);for(int y=0;y<3;y++){corner.block(1,y,0);corner.block(0,y,1);}
        s=run(corner,.5,0,.5,.6,8);check(!s.reached(1.5,0,1.5),"diagonal path squeezed between blocking corners");}
    static void partialStair(){World world=new World().shape(1,0,0,1,0,0,2,.5,1).shape(1,0,0,1.5,.5,0,2,1,1)
            .shape(1,2,0,1,2.6,0,2,2.7,1);var s=run(world,1.2,.5,.5,.6,3);
        check(s.reached(1.2,.5,.5),"reachable lower stair under ceiling missing");check(!s.reached(1.5,1,.5),"unreachable upper stair under ceiling admitted");
        check(s.surfaces().stream().anyMatch(v->v.x()==1&&v.y()==0&&v.topY()==.5)&&s.surfaces().stream().noneMatch(v->v.x()==1&&v.y()==0&&v.topY()==1),"same-voxel stair heights merged in graph");}
    static void widthAndUnknown(){World corridor=new World().floor(0,2,0,0)
            .shape(1,0,0,1,0,0,2,3,.15).shape(1,0,0,1,0,.85,2,3,1);
        GroundWalkability.Search thin=run(corridor,.5,0,.5,.6,8),wide=run(corridor,.5,0,.5,.9,8);
        check(thin.reached(2.5,0,.5),"normal-width player rejected by clear .7-wide corridor");check(!wide.reached(2.5,0,.5),"player width ignored in corridor");
        World missing=new World().floor(0,2,0,0);missing.unloaded.add(new Key(1,-1,0));var s=run(missing,.5,0,.5,.6,8);check(!s.reached(2.5,0,.5),"unloaded support treated as traversable");
        World missingBody=new World().floor(0,2,0,0);missingBody.unloaded.add(new Key(1,0,0));s=run(missingBody,.5,0,.5,.6,8);check(!s.reached(2.5,0,.5),"unloaded body clearance treated as air");}
    static void specialSupport(){World fluid=new World().block(0,-1,0).block(1,0,0).block(2,0,0);fluid.nonSupporting.add(new Key(1,0,0));var s=run(fluid,.5,0,.5,.6,8);
        check(!s.reached(1.5,1,.5)&&!s.reached(2.5,1,.5),"fluid/special barrier used as standing/jump support");
        World wet=new World().block(0,-1,0).block(1,0,0);wet.supportOverride.put(new Key(1,0,0),List.of(new GroundWalkability.Box(1.5,.5,0,2,1,1)));s=run(wet,.5,0,.5,.6,8);check(s.reached(1.5,1,.5),"waterlogged solid upper stair incorrectly rejected as pure fluid");
        World maxRise=new World().block(0,-1,0).block(1,0,0).shape(1,1,0,1,1,0,2,1.25,1);s=run(maxRise,.5,0,.5,.6,8);check(!s.reached(1.5,1.25,.5),"zero-duration max-apex jump accepted");
        check(GroundWalkability.heightLevel(.5)==15&&GroundWalkability.heightLevel(1)==31&&GroundWalkability.heightLevel(1.5)==15&&GroundWalkability.heightLevel(0)==31&&GroundWalkability.heightLevel(-.5)==15,"height bits differ from exact half/full GPU contract");}
    static void bounds(){for(double[] body:new double[][]{{3,1.8},{.6,5}}){boolean rejected=false;try{new GroundWalkability.Search(.5,0,.5,body[0],body[1],6,6);}catch(IllegalArgumentException expected){rejected=true;}check(rejected,"oversized body shrunk instead of rejected");}}
    static void seedsAndResume(){World world=new World().block(0,-1,0).block(2,-1,0);var unsupported=search(.5,1,.5,.6);check(!unsupported.seed(world),"unsupported seed inherited distant ground");
        GroundWalkability.Search left=run(world,.5,0,.5,.6,2),right=run(world,2.5,0,.5,.6,2);check(!left.reached(2.5,0,.5)&&!right.reached(.5,0,.5),"new seed inherited old reachable island");
        World open=new World().floor(-7,7,-7,7);var interrupted=search(.5,0,.5,.6);check(interrupted.seed(open),"resume seed failed");
        boolean[] fail={true};GroundWalkability.Probe probe=(x,y,z)->{if(fail[0]){fail[0]=false;throw new IllegalStateException("fixture query budget");}return open.cell(x,y,z);};
        boolean caught=false;try{interrupted.advance(probe,1);}catch(IllegalStateException expected){caught=true;}
        check(caught,"fixture did not reach query boundary");drain(interrupted,open,1);check(result(interrupted).equals(result(run(open,.5,0,.5,.6,11))),"query-budget interruption lost or invented a reachable edge");}
    public static void main(String[] args){flatAndBudget();raisedRoute();slabs();obstructions();partialStair();widthAndUnknown();specialSupport();bounds();seedsAndResume();
        System.out.println("PASS GroundWalkability: "+checks+" actual graph assertions; flat/jump/slab routes, disconnected elevated tops, walls/ceilings, width, unloaded cells, no gaps/corner cuts, bounded edges and isolated/resumable seeds.");}
}
