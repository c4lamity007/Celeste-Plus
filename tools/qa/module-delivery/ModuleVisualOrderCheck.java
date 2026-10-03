package dev.c4lam.celeste.render;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import java.util.*;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.util.Identifier;
/** Real Fabric dispatch with scoped dummy draw handlers. No Minecraft or GL context. */
public final class ModuleVisualOrderCheck {
 private record Pass(String module,String suffix,WorldOverlayPass.Stage stage) {String key(){return module+"/"+suffix;}}
 private static int assertions;
 private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args) {
  List<String> drawn=new ArrayList<>();
  WorldRenderEvents.END_MAIN.register(context->drawn.add("terrain/fog"));
  WorldRenderEvents.END_MAIN.register(Identifier.of("celeste","sky_lyrics/after_fog"),context->drawn.add("labels"));
  List<Pass> passes=List.of(
   new Pass("ambient_effects","ambient",null),
   new Pass("target_esp","fills",WorldOverlayPass.Stage.FILLS),
   new Pass("trails","fills",WorldOverlayPass.Stage.FILLS),
   new Pass("target_esp","lines",WorldOverlayPass.Stage.LINES),
   new Pass("trails","lines",WorldOverlayPass.Stage.LINES),
   new Pass("target_esp","glows",WorldOverlayPass.Stage.GLOWS),
   new Pass("trails","glows",WorldOverlayPass.Stage.GLOWS),
   new Pass("fake_lag","glows",WorldOverlayPass.Stage.GLOWS),
   new Pass("block_highlight","markers",null),
   new Pass("anchor_aura","markers",null),
   new Pass("fake_lag","echoes",null),
   new Pass("chams","silhouettes",null),
   new Pass("hit_effects","impacts",null));
  List<String> expected=new ArrayList<>();expected.add("terrain/fog");passes.forEach(p->expected.add(p.key()));expected.add("labels");
  for(int seed=0;seed<64;seed++) {
   List<Pass> order=new ArrayList<>(passes);Collections.shuffle(order,new Random(seed));
   List<ModuleScope> scopes=new ArrayList<>();
   for(Pass pass:order) {
    ModuleScope scope=new ModuleScope(pass.module,()->true,Runnable::run);scopes.add(scope);
    try(var ownership=scope.enter()) {
     String name="celeste:module/"+pass.key();
     if(pass.stage==null)WorldOverlayPass.register(name,context->drawn.add(pass.key()));
     else WorldOverlayPass.register(name,pass.stage,context->drawn.add(pass.key()));
    }
   }
   drawn.clear();WorldRenderEvents.END_MAIN.invoker().endMain(null);
   check(drawn.equals(expected),"same logical composition after shuffled activation, seed "+seed+": "+drawn);
   scopes.forEach(ModuleScope::close);scopes.forEach(scope->scope.retired().toCompletableFuture().join());
   drawn.clear();WorldRenderEvents.END_MAIN.invoker().endMain(null);
   check(drawn.equals(List.of("terrain/fog","labels")),"retired render handlers detached");
  }
  System.out.println("{\"status\":\"passed\",\"assertions\":"+assertions+",\"activation_orders\":64,\"game_verification\":\"not-run\"}");
 }
}
