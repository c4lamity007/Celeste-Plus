package dev.c4lam.celeste.render;
import java.util.*;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
/** Pure allocation/phase fixture. Actual Fabric event dispatcher, no game or GPU started. */
public final class ModuleVisualBudgetCheck {
 private static final String[] IDS={"jump_circles","target_esp","player_esp","trajectories","ore_esp","item_esp","trails","fake_lag"};
 private static final int[] LIMITS={300,456,480,576,592,640,704,768};
 private static int assertions;
 private static void check(boolean ok,String why){assertions++;if(!ok)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception {
  try(var scope=new ModuleScope();var context=scope.enter()) {
   try {ModuleVisualBudget.initialize();throw new AssertionError("scoped initialize accepted");}
   catch(IllegalStateException expected){check(true,"host initializes phases outside payload scope");}
  }
  for(int enabled=0;enabled<256;enabled++)for(int sample=0;sample<8;sample++) {
   ModuleVisualBudget.reset();int original=0;
   for(int i=0;i<IDS.length;i++) {
    check(ModuleVisualBudget.remaining(IDS[i])==Math.max(0,LIMITS[i]-original),"same remaining capacity as original shared batch");
    if((enabled&(1<<i))==0)continue;
    int demand=sample==0?1000:((sample*47+i*131)%380);
    int emitted=Math.min(demand,Math.max(0,LIMITS[i]-original));
    ModuleVisualBudget.commit(IDS[i],emitted);original+=emitted;
   }
   check(original<=768,"all module combinations preserve shared ceiling");
  }
  ModuleVisualBudget.reset();
  try{ModuleVisualBudget.commit("target_esp",457);throw new AssertionError("oversubscription accepted");}
  catch(IllegalStateException expected){check(ModuleVisualBudget.remaining("target_esp")==456,"bad producer cannot consume another module reservation");}
  ModuleVisualBudget.initialize();ModuleVisualBudget.initialize();
  List<String> order=new ArrayList<>();
  for(int i=IDS.length-1;i>=0;i--){String id=IDS[i];WorldRenderEvents.END_EXTRACTION.register(ModuleVisualBudget.phase(id),context->{order.add(id);ModuleVisualBudget.commit(id,1);});}
  WorldRenderEvents.END_EXTRACTION.invoker().endExtraction(null);
  check(order.equals(List.of(IDS)),"Fabric phase order stable under reverse activation order");
  order.clear();WorldRenderEvents.END_EXTRACTION.invoker().endExtraction(null);
  check(order.equals(List.of(IDS))&&ModuleVisualBudget.remaining("fake_lag")==760,"budget reset precedes every extraction frame");
  System.out.println("{\"status\":\"passed\",\"assertions\":"+assertions+",\"module_combinations\":256,\"game_verification\":\"not-run\"}");
 }
}
