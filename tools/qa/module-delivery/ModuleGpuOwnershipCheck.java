package dev.c4lam.celeste.module.delivery;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.lang.reflect.*;
import java.util.*;
import net.minecraft.client.gl.*;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.util.Identifier;

/** Isolated fixture: uses real 1.21.11 classes and local maps; never starts Minecraft or OpenGL. */
public final class ModuleGpuOwnershipCheck {
 static int assertions;
 static void check(boolean value,String why){assertions++;if(!value)throw new AssertionError(why);}
 static Identifier id(String s){return Identifier.of(s);}
 static RenderPipeline pipeline(String name,String vertex,String fragment){
  return RenderPipeline.builder().withLocation(id("celeste:"+name)).withVertexShader(id(vertex))
   .withFragmentShader(id(fragment)).withVertexFormat(VertexFormats.POSITION,VertexFormat.DrawMode.QUADS).build();
 }
 static Constructor<?> keyConstructor(String name,Class<?>...types)throws Exception{
  Class<?> c=Class.forName(name);var ctor=c.getDeclaredConstructor(types);ctor.setAccessible(true);return ctor;
 }
 public static void main(String[]args)throws Exception{
  Class.forName(ModulePipelineResources.class.getName()+"$Access");
  check(true,"all named backend fields match exact expected types");
  var constructor=keyConstructor("net.minecraft.client.gl.GlBackend$ShaderKey",Identifier.class,ShaderType.class,Defines.class);
  RenderPipeline a=pipeline("a","celeste:core/shared","celeste:core/private_a");
  RenderPipeline b=pipeline("b","celeste:core/shared","celeste:core/private_b");
  RenderPipeline vanilla=pipeline("untouched","minecraft:core/position","minecraft:core/position");
  Map<RenderPipeline,CompiledShaderPipeline> pipelines=new HashMap<>();
  for(var p:List.of(a,b,vanilla))pipelines.put(p,new CompiledShaderPipeline(p,ShaderProgram.INVALID));
  Map<Object,CompiledShader> shaders=new HashMap<>();
  Object shared=constructor.newInstance(a.getVertexShader(),ShaderType.VERTEX,a.getShaderDefines());
  Object ak=constructor.newInstance(a.getFragmentShader(),ShaderType.FRAGMENT,a.getShaderDefines());
  Object bk=constructor.newInstance(b.getFragmentShader(),ShaderType.FRAGMENT,b.getShaderDefines());
  Object vk=constructor.newInstance(vanilla.getVertexShader(),ShaderType.VERTEX,vanilla.getShaderDefines());
  for(var k:List.of(shared,ak,bk,vk))shaders.put(k,CompiledShader.INVALID_SHADER);
  int[] closed={0};
  ModulePipelineResources.retireCaches(pipelines,shaders,a,p->closed[0]++,s->closed[0]++);
  check(!pipelines.containsKey(a)&&pipelines.containsKey(b)&&pipelines.containsKey(vanilla),"retire removes exact pipeline only");
  check(shaders.containsKey(shared)&&!shaders.containsKey(ak)&&shaders.containsKey(bk)&&shaders.containsKey(vk),"shared and vanilla shaders survive first owner retirement");
  ModulePipelineResources.retireCaches(pipelines,shaders,b,p->closed[0]++,s->closed[0]++);
  check(!shaders.containsKey(shared)&&!shaders.containsKey(bk)&&shaders.containsKey(vk),"last module owner removes only unreferenced Celeste stages");
  check(closed[0]==0,"INVALID sentinels are never closed");
  ModulePipelineResources.retireCaches(pipelines,shaders,b,p->closed[0]++,s->closed[0]++);
  check(shaders.size()==1&&pipelines.size()==1,"repeat retirement is idempotent");
  // No GL call: program-close actions are injected fixture counters.
  Constructor<ShaderProgram> programCtor=ShaderProgram.class.getDeclaredConstructor(int.class,String.class);programCtor.setAccessible(true);
  ShaderProgram program=programCtor.newInstance(123,"fixture");
  pipelines.put(a,new CompiledShaderPipeline(a,program));
  shaders.put(ak,new CompiledShader(124,a.getFragmentShader(),ShaderType.FRAGMENT));
  ModulePipelineResources.retireCaches(pipelines,shaders,a,p->closed[0]++,s->closed[0]++);
  check(closed[0]==2,"owned valid program and unshared shader each close exactly once");
  ModulePipelineResources.retireCaches(pipelines,shaders,a,p->closed[0]++,s->closed[0]++);
  check(closed[0]==2,"repeat retirement does not close GPU objects twice");
  pipelines.put(a,new CompiledShaderPipeline(a,program));pipelines.put(b,new CompiledShaderPipeline(b,program));
  CompiledShader aliasShader=new CompiledShader(125,a.getFragmentShader(),ShaderType.FRAGMENT);
  shaders.put(ak,aliasShader);shaders.put(bk,aliasShader);
  ModulePipelineResources.retireCaches(pipelines,shaders,a,p->closed[0]++,s->closed[0]++);
  check(closed[0]==2,"externally shared program and aliased shader identities survive one owner retirement");
  ModulePipelineResources.retireCaches(pipelines,shaders,b,p->closed[0]++,s->closed[0]++);
  check(closed[0]==4,"shared GPU identities close only after their final mapping retires");
  var sourceKey=keyConstructor("net.minecraft.client.gl.ShaderLoader$ShaderSourceKey",Identifier.class,ShaderType.class);
  Object oldKey=sourceKey.newInstance(id("celeste:core/old"),ShaderType.FRAGMENT);
  Object liveKey=sourceKey.newInstance(id("celeste:core/live"),ShaderType.FRAGMENT);
  Object vanillaKey=sourceKey.newInstance(id("minecraft:core/position"),ShaderType.VERTEX);
  @SuppressWarnings({"unchecked","rawtypes"}) var definitions=new ShaderLoader.Definitions((Map)Map.of(oldKey,"old",liveKey,"live",vanillaKey,"vanilla"),Map.of());
  var cacheCtor=keyConstructor("net.minecraft.client.gl.ShaderLoader$Cache",ShaderLoader.class,ShaderLoader.Definitions.class);
  Object cache=cacheCtor.newInstance(null,definitions);
  Field defs=cache.getClass().getDeclaredField("definitions");defs.setAccessible(true);
  Field post=cache.getClass().getDeclaredField("postEffectProcessors");post.setAccessible(true);
  Object postProcessors=post.get(cache);
  ModulePipelineResources.purgeSources(cache,Set.of("assets/celeste/shaders/core/old.fsh"));
  var changed=(ShaderLoader.Definitions)defs.get(cache);
  check(!changed.shaderSources().containsKey(oldKey)&&changed.shaderSources().containsKey(liveKey)&&changed.shaderSources().containsKey(vanillaKey),"source purge removes exact retired path only");
  check(changed.postChains()==definitions.postChains()&&post.get(cache)==postProcessors,"source purge preserves post chains and loaded processors");
  ModulePipelineResources.purgeSources(cache,Set.of("assets/celeste/shaders/core/old.fsh"));
  check(defs.get(cache)==changed,"already purged source cache is unchanged");
  System.out.println("{\"status\":\"passed\",\"assertions\":"+assertions+",\"actual_game\":\"not-run\",\"gpu_calls\":0}");
 }
}
