"""Focused actual-source Java fixture, using deterministic Minecraft API stand-ins."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess

PREFIX='dev/c4lam/celeste/module/impl/visual/expansion/'
STUBS={
'dev/c4lam/celeste/module/ModuleFeature.java':'''package dev.c4lam.celeste.module; public interface ModuleFeature { String name(); default void onEnable(ModuleContext c){} default void onStartTick(ModuleContext c){} default void onDisable(ModuleContext c){} default void onWorldChanged(ModuleContext c){} }''',
'dev/c4lam/celeste/module/ModuleContext.java':'''package dev.c4lam.celeste.module; import net.minecraft.client.MinecraftClient; public class ModuleContext { public String style="Box"; public double range=64; public MinecraftClient client(){return MinecraftClient.getInstance();} public String mode(String n,String d){return n.equals("Style")?style:d;} public double slider(String n,double d){return n.equals("Range")?range:d;} public int colorPick(String n,int d){return d;} }''',
'net/minecraft/util/math/Vec3d.java':'''package net.minecraft.util.math; public class Vec3d {public final double x,y,z;public Vec3d(double x,double y,double z){this.x=x;this.y=y;this.z=z;}public Vec3d subtract(Vec3d v){return new Vec3d(x-v.x,y-v.y,z-v.z);}public double squaredDistanceTo(Vec3d v){return (x-v.x)*(x-v.x)+(y-v.y)*(y-v.y)+(z-v.z)*(z-v.z);} }''',
'net/minecraft/util/math/Box.java':'''package net.minecraft.util.math;public record Box(double minX,double minY,double minZ,double maxX,double maxY,double maxZ){public Box offset(Vec3d v){return new Box(minX+v.x,minY+v.y,minZ+v.z,maxX+v.x,maxY+v.y,maxZ+v.z);}public Box expand(double v){return new Box(minX-v,minY-v,minZ-v,maxX+v,maxY+v,maxZ+v);} public boolean contains(Vec3d v){return v.x>=minX&&v.x<=maxX&&v.y>=minY&&v.y<=maxY&&v.z>=minZ&&v.z<=maxZ;} }''',
'net/minecraft/util/math/MathHelper.java':'''package net.minecraft.util.math;public class MathHelper {public static double clamp(double v,double a,double b){return Math.max(a,Math.min(b,v));}}''',
'net/minecraft/util/math/ColorHelper.java':'''package net.minecraft.util.math;public class ColorHelper {public static int fullAlpha(int c){return c|0xff000000;}}''',
'net/minecraft/util/Util.java':'''package net.minecraft.util;public class Util {public static long getMeasuringTimeMs(){return 1000;}}''',
'net/minecraft/entity/ItemEntity.java':'''package net.minecraft.entity;import net.minecraft.client.world.ClientWorld;import net.minecraft.util.math.*; public class ItemEntity {public final int id;public ClientWorld world;public Vec3d pos,previous;public boolean alive=true,removed=false,empty=false; public ItemEntity(ClientWorld w,int i,double x,double y,double z){world=w;id=i;pos=previous=new Vec3d(x,y,z);}public ClientWorld getEntityWorld(){return world;}public int getId(){return id;}public Vec3d getEntityPos(){return pos;}public Vec3d getLerpedPos(float t){return new Vec3d(previous.x+(pos.x-previous.x)*t,previous.y+(pos.y-previous.y)*t,previous.z+(pos.z-previous.z)*t);}public boolean isAlive(){return alive;}public boolean isRemoved(){return removed;}public Stack getStack(){return new Stack(empty);}public record Stack(boolean empty){public boolean isEmpty(){return empty;}}public Box getBoundingBox(){return new Box(pos.x-.1,pos.y,pos.z-.1,pos.x+.1,pos.y+.2,pos.z+.1);} }''',
'net/minecraft/client/network/ClientPlayerEntity.java':'''package net.minecraft.client.network;import net.minecraft.client.world.ClientWorld;import net.minecraft.entity.ItemEntity;public class ClientPlayerEntity extends ItemEntity {public ClientPlayerEntity(ClientWorld w){super(w,-1,0,0,0);}public double squaredDistanceTo(ItemEntity e){return pos.squaredDistanceTo(e.pos);}}''',
'net/minecraft/client/world/ClientWorld.java':'''package net.minecraft.client.world;import java.util.*;import java.util.function.Predicate;import net.minecraft.entity.ItemEntity;import net.minecraft.util.math.Box;public class ClientWorld {public final List<Object> all=new ArrayList<>();public int typedQueries,globalScans;public Box lastBox;public <T extends ItemEntity> List<T> getEntitiesByClass(Class<T> type,Box box,Predicate<? super T> predicate){typedQueries++;lastBox=box;List<T> found=new ArrayList<>();for(Object o:all)if(type.isInstance(o)){T item=type.cast(o);if(box.contains(item.pos)&&predicate.test(item))found.add(item);}return found;}public List<Object> getEntities(){globalScans++;return all;}public ItemEntity add(int id,double x,double y,double z){ItemEntity e=new ItemEntity(this,id,x,y,z);all.add(e);return e;}}''',
'net/minecraft/client/MinecraftClient.java':'''package net.minecraft.client;import net.minecraft.client.world.ClientWorld;import net.minecraft.client.network.ClientPlayerEntity;public class MinecraftClient{private static final MinecraftClient INSTANCE=new MinecraftClient();public ClientWorld world;public ClientPlayerEntity player;public boolean onThread=true;public static MinecraftClient getInstance(){return INSTANCE;}public boolean isOnThread(){return onThread;}}''',
'net/minecraft/client/render/entity/state/ItemEntityRenderState.java':'''package net.minecraft.client.render.entity.state;public class ItemEntityRenderState {public int outlineColor;}''',
'net/fabricmc/fabric/api/client/rendering/v1/world/WorldExtractionContext.java':'''package net.fabricmc.fabric.api.client.rendering.v1.world;import net.minecraft.client.world.ClientWorld;import net.minecraft.util.math.Box;public class WorldExtractionContext {public final ClientWorld world;public boolean visible=true;public float progress=1;public Box checked;public WorldExtractionContext(ClientWorld w){world=w;}public ClientWorld world(){return world;}public Counter tickCounter(){return new Counter();}public Frustum frustum(){return new Frustum();}public class Counter{public float getTickProgress(boolean ignored){return progress;}}public class Frustum {public boolean isVisible(Box b){checked=b;return visible;}}}''',
PREFIX+'VisualExpansionRenderer.java':'''package dev.c4lam.celeste.module.impl.visual.expansion;import net.minecraft.util.math.Box;public class VisualExpansionRenderer{public record WorldBox(Box box,int fillColor,int lineColor,float lineWidth){}public static class WorldLineStrip{}public static int argb(float a,int c){return (int)(a*255)<<24|c;}}''',
PREFIX+'VisualEffects.java':'''package dev.c4lam.celeste.module.impl.visual.expansion;public class VisualEffects{public static int color(String theme,int custom,double time,double seed){return custom;}}''',
PREFIX+'WorldGlowRenderer.java':'''package dev.c4lam.celeste.module.impl.visual.expansion;public class WorldGlowRenderer{public static class Quad{}}''',
}
FIXTURE='''package dev.c4lam.celeste.module.impl.visual.expansion;
import java.lang.reflect.*;import java.util.*;
import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.client.*;import net.minecraft.client.world.*;import net.minecraft.client.network.*;
import net.minecraft.client.render.entity.state.ItemEntityRenderState;import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Vec3d;import net.fabricmc.fabric.api.client.rendering.v1.world.WorldExtractionContext;
public class ItemEspFixture {
 static int assertions,cases;static final MinecraftClient client=MinecraftClient.getInstance();
 static ModuleContext settings;static ItemEspFeature feature;static ClientWorld world;
 static void check(boolean yes,String message){assertions++;if(!yes)throw new AssertionError(message);}
 static void setup(String style){world=new ClientWorld();client.world=world;client.player=new ClientPlayerEntity(world);client.onThread=true;settings=new ModuleContext();settings.style=style;feature=new ItemEspFeature();feature.onEnable(settings);}
 @SuppressWarnings("unchecked") static List<ItemEntity> selected()throws Exception{Field f=ItemEspFeature.class.getDeclaredField("selection");f.setAccessible(true);Object value=f.get(feature);Method m=value.getClass().getDeclaredMethod("items");m.setAccessible(true);return (List<ItemEntity>)m.invoke(value);}
 static List<VisualExpansionRenderer.WorldBox> draw(WorldExtractionContext c){List<VisualExpansionRenderer.WorldBox> boxes=new ArrayList<>();feature.extract(c,boxes,new ArrayList<>());return boxes;}
 static List<Integer> ids(List<ItemEntity> items){return items.stream().map(ItemEntity::getId).toList();}
 public static void main(String[]args)throws Exception{
  setup("Box");for(int i=0;i<1500;i++)world.all.add("other"+i);world.add(9,2,0,0);feature.onStartTick(settings);check(ids(selected()).equals(List.of(9)),"item after1500unrelated entities retained");check(world.globalScans==0&&world.typedQueries==1,"typed index only");cases++;
  setup("Box");List<ItemEntity> expected=new ArrayList<>();for(int i=0;i<500;i++)expected.add(world.add(i,2+(i%100)*.2,0,0));Collections.shuffle(world.all,new Random(41));expected.sort(Comparator.comparingDouble((ItemEntity i)->client.player.squaredDistanceTo(i)).thenComparingInt(ItemEntity::getId));feature.onStartTick(settings);check(ids(selected()).equals(ids(expected.subList(0,96))),"nearest96 and deterministic id ties");cases++;
  setup("Box");world.add(1,63,0,0);world.add(2,65,0,0);world.add(3,50,50,0);world.add(4,2,0,0).alive=false;world.add(5,2,0,0).removed=true;world.add(6,2,0,0).empty=true;feature.onStartTick(settings);check(ids(selected()).equals(List.of(1)),"range sphere and eligibility");check(world.lastBox.minX()==-64&&world.lastBox.maxX()==64,"manual range used for index bounds");cases++;
  setup("Box");world.add(1,4,0,0);feature.onStartTick(settings);for(int i=0;i<120;i++)check(draw(new WorldExtractionContext(world)).size()==1,"cached frame");check(world.typedQueries==1&&world.globalScans==0,"no perframe query");feature.onStartTick(settings);check(world.typedQueries==2,"nexttick updates");cases++;
  setup("Item Highlight");ItemEntity item=world.add(1,4,0,0);feature.onStartTick(settings);check(world.typedQueries==0&&selected().isEmpty(),"highlightonly skips index query");ItemEntityRenderState state=new ItemEntityRenderState();ItemEspFeature.applyModelHighlight(item,state);check(state.outlineColor!=0,"vanilla model highlight preserved");check(draw(new WorldExtractionContext(world)).isEmpty(),"highlightonly no boxes");cases++;
  setup("Both");item=world.add(1,4,0,0);feature.onStartTick(settings);state=new ItemEntityRenderState();ItemEspFeature.applyModelHighlight(item,state);check(world.typedQueries==1&&draw(new WorldExtractionContext(world)).size()==1&&state.outlineColor!=0,"both contracts");settings.style="Box";feature.onStartTick(settings);state=new ItemEntityRenderState();ItemEspFeature.applyModelHighlight(item,state);check(state.outlineColor==0,"boxonly no model outline");cases++;
  setup("Box");item=world.add(1,4,0,0);feature.onStartTick(settings);item.removed=true;check(draw(new WorldExtractionContext(world)).isEmpty(),"removed after capture suppressed");item.removed=false;item.empty=true;check(draw(new WorldExtractionContext(world)).isEmpty(),"empty after capture suppressed");item.empty=false;item.alive=false;check(draw(new WorldExtractionContext(world)).isEmpty(),"dead after capture suppressed");cases++;
  setup("Both");item=world.add(1,4,0,0);feature.onStartTick(settings);feature.onDisable(settings);check(selected().isEmpty()&&draw(new WorldExtractionContext(world)).isEmpty(),"disable clears selection");state=new ItemEntityRenderState();ItemEspFeature.applyModelHighlight(item,state);check(state.outlineColor==0,"disable clears active outline owner");cases++;
  setup("Box");world.add(1,4,0,0);feature.onStartTick(settings);ClientWorld other=new ClientWorld();check(draw(new WorldExtractionContext(other)).isEmpty(),"different renderworld rejected");client.world=other;client.player=new ClientPlayerEntity(other);feature.onWorldChanged(settings);check(selected().isEmpty(),"worldchange clears old refs");other.add(2,3,0,0);feature.onStartTick(settings);check(ids(selected()).equals(List.of(2)),"newworld refreshed");cases++;
  setup("Box");item=world.add(1,5,0,0);item.previous=new Vec3d(3,0,0);feature.onStartTick(settings);WorldExtractionContext frame=new WorldExtractionContext(world);frame.progress=.5f;var boxes=draw(frame);check(boxes.size()==1&&Math.abs((boxes.getFirst().box().minX()+boxes.getFirst().box().maxX())*.5-4)<1e-9,"tickinterpolated position");frame.visible=false;check(draw(frame).isEmpty(),"frustum rejects actual interpolated box");cases++;
  setup("Box");world.add(1,4,0,0);feature.onStartTick(settings);client.onThread=false;feature.onStartTick(settings);check(world.typedQueries==1&&selected().isEmpty(),"worldquery constrained to client thread");cases++;
  setup("Box");item=world.add(1,80,0,0);feature.onStartTick(settings);check(selected().isEmpty(),"outofrange before movement");item.pos=item.previous=new Vec3d(4,0,0);feature.onStartTick(settings);check(ids(selected()).equals(List.of(1)),"movement included nexttick");settings.range=8;item.pos=item.previous=new Vec3d(9,0,0);feature.onStartTick(settings);check(selected().isEmpty()&&world.lastBox.maxX()==8,"changed range respected");cases++;
  setup("Both");for(int i=0;i<150;i++)world.add(i,4,0,0);feature.onStartTick(settings);check(draw(new WorldExtractionContext(world)).size()==96,"96 box cap preserved");state=new ItemEntityRenderState();ItemEspFeature.applyModelHighlight((ItemEntity)world.all.get(149),state);check(state.outlineColor!=0,"model outline behavior not accidentally capped by boxselection");cases++;
  System.out.println("PASS "+cases+" cases "+assertions+" assertions");
 }
}
'''

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project',type=Path,help='Celeste+ checkout containing runtime-overlay')
    parser.add_argument('--java-home',type=Path,default=Path(os.environ.get('JAVA_HOME','C:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot')))
    parser.add_argument('--output',type=Path,required=True,help='Fresh fixture output directory')
    args=parser.parse_args()
    if args.project is None:
        candidate=Path(__file__).resolve().parents[3]
        args.project=candidate if (candidate/'runtime-overlay').is_dir() else Path('C:/Users/c4lam/Downloads/New folder (5)/Celeste-Plus')
    project=args.project.resolve(strict=True)
    source=project/'runtime-overlay/src/main/java/dev/c4lam/celeste/module/impl/visual/expansion/ItemEspFeature.java'
    style=source.with_name('ItemEspStyle.java')
    java=args.java_home.resolve(strict=True)/'bin'
    if not source.is_file() or not style.is_file():
        parser.error('The project must contain ItemEspFeature.java and ItemEspStyle.java in its runtime overlay')
    out=args.output.resolve()
    if out.exists():
        parser.error('Use a fresh output directory; fixture evidence is not overwritten')
    src=out/'src';classes=out/'classes'
    src.mkdir(parents=True);classes.mkdir()
    source_bytes=source.read_bytes();style_bytes=style.read_bytes()
    files={**STUBS,PREFIX+'ItemEspFeature.java':source_bytes.decode('utf-8'),PREFIX+'ItemEspStyle.java':style_bytes.decode('utf-8'),PREFIX+'ItemEspFixture.java':FIXTURE}
    for name,content in files.items():
        path=src/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8')
    suffix='.exe' if os.name=='nt' else ''
    compile_run=subprocess.run([str(java/('javac'+suffix)),'--release','21','-d',str(classes),*map(str,src.rglob('*.java'))],capture_output=True,text=True)
    (out/'compile.log').write_text(compile_run.stdout+compile_run.stderr,encoding='utf-8')
    if compile_run.returncode:raise RuntimeError(compile_run.stderr)
    run=subprocess.run([str(java/('java'+suffix)),'-cp',str(classes),'dev.c4lam.celeste.module.impl.visual.expansion.ItemEspFixture'],capture_output=True,text=True)
    (out/'run.log').write_text(run.stdout+run.stderr,encoding='utf-8')
    unchanged=source.read_bytes()==source_bytes and style.read_bytes()==style_bytes
    result={'kind':'actual-source Java fixture with Minecraft API stand-ins','game_verified':False,'project':str(project),'java_home':str(args.java_home),'exit_code':run.returncode,'source_sha256':hashlib.sha256(source_bytes).hexdigest(),'style_sha256':hashlib.sha256(style_bytes).hexdigest(),'source_unchanged':unchanged,'summary':run.stdout.strip(),'log':str(out/'run.log')}
    (out/'result.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))
    if run.returncode:raise RuntimeError(run.stderr)
    if not unchanged:raise RuntimeError('Project sources changed while the fixture was running')
if __name__=='__main__':main()
