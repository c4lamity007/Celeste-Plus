package dev.c4lam.celeste.render;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import java.util.Map;
import java.util.List;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.util.Identifier;

/** Host allocation policy only: stable ordering preserves the original shared visual geometry ceiling. */
public final class ModuleVisualBudget {
    private static final List<String> ORDER=List.of("jump_circles","target_esp","player_esp","trajectories","ore_esp","item_esp","trails","fake_lag");
    private static final Map<String,Integer> CEILINGS=Map.of("jump_circles",300,"target_esp",456,"player_esp",480,
        "trajectories",576,"ore_esp",592,"item_esp",640,"trails",704,"fake_lag",768);
    private static boolean initialized;
    private static int used;
    private ModuleVisualBudget() { }
    public static synchronized void initialize() {
        if(initialized)return;
        if(dev.c4lam.celeste.module.delivery.ModuleScope.current()!=null)
            throw new IllegalStateException("Initialize shared render transport before entering a module");
        Identifier begin=Identifier.of("celeste","module_extract/begin");
        WorldRenderEvents.END_EXTRACTION.addPhaseOrdering(begin,Event.DEFAULT_PHASE);
        Identifier previous=Event.DEFAULT_PHASE;
        for(String id:ORDER){Identifier next=identifier(id);WorldRenderEvents.END_EXTRACTION.addPhaseOrdering(previous,next);previous=next;}
        BetaSubscriptions.register(WorldRenderEvents.END_EXTRACTION,"celeste:module_extract/budget",
            WorldRenderEvents.EndExtraction.class,context->reset(),proxy->WorldRenderEvents.END_EXTRACTION.register(begin,proxy));
        initialized=true;
    }
    public static Identifier phase(String id) {
        if(!initialized)throw new IllegalStateException("Shared render transport is not initialized");
        return CEILINGS.containsKey(id)?identifier(id):Event.DEFAULT_PHASE;
    }
    private static Identifier identifier(String id){return Identifier.of("celeste","module_extract/"+id);}
    static synchronized void reset(){used=0;}
    public static synchronized int remaining(String id){return Math.max(0,CEILINGS.getOrDefault(id,768)-used);}
    public static synchronized void commit(String id,int count){
        if(!CEILINGS.containsKey(id))return;
        if(count<0 || count>remaining(id))throw new IllegalStateException("Module exceeded its allocated render budget");
        used+=count;
    }
}
