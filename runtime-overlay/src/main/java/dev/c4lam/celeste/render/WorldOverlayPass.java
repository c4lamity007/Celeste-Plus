package dev.c4lam.celeste.render;

import dev.c4lam.celeste.beta.lifecycle.BetaSubscriptions;
import dev.c4lam.celeste.module.delivery.ModuleScope;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.fabricmc.fabric.api.event.Event;
import net.minecraft.util.Identifier;

/** Stable host scheduling only; every effect's draw code and GPU state remain module-owned. */
public final class WorldOverlayPass {
    public enum Stage { AMBIENT, FILLS, LINES, GLOWS, BLOCK_MARKERS, ECHOES, CHAMS, IMPACTS }
    private static final Identifier OVERLAYS=Identifier.of("celeste","world_overlays/after_fog");
    private static final Identifier LABELS=Identifier.of("celeste","sky_lyrics/after_fog");
    // Original shared extraction order, with separately rendered ESP placed deterministically first.
    private static final List<String> ORDER=List.of("block_esp","storage_esp","suspicious_chunk_finder","entity_esp","hole_esp",
        "ambient_effects","jump_circles","target_esp","player_esp","trajectories","ore_esp","item_esp","trails",
        "fake_lag","fake_gamble","block_highlight","anchor_aura","chams","hit_effects");
    private static final EnumMap<Stage,TreeMap<String,Identifier>> phases=new EnumMap<>(Stage.class);
    private static boolean initialized;
    private WorldOverlayPass() { }

    /** Normal single-pass modules select their logical layer; primitive batches supply explicit stages. */
    public static void register(String name,WorldRenderEvents.EndMain draw) {
        ModuleScope scope=ModuleScope.current();
        String id=scope==null?"":scope.moduleId();
        Stage stage=switch(id) {
            case "ambient_effects" -> Stage.AMBIENT;
            case "block_highlight","anchor_aura" -> Stage.BLOCK_MARKERS;
            case "fake_lag" -> Stage.ECHOES;
            case "chams","entity_esp" -> Stage.CHAMS;
            case "hit_effects" -> Stage.IMPACTS;
            case "hole_esp" -> Stage.GLOWS;
            default -> Stage.FILLS;
        };
        register(name,stage,draw);
    }

    public static void register(String name,Stage stage,WorldRenderEvents.EndMain draw) {
        ModuleScope scope=ModuleScope.current();
        String id=scope==null?"":scope.moduleId();
        Identifier phase=phase(name,id,stage);
        BetaSubscriptions.register(WorldRenderEvents.END_MAIN,name,WorldRenderEvents.EndMain.class,draw,
            proxy->WorldRenderEvents.END_MAIN.register(phase,proxy));
    }

    private static synchronized Identifier phase(String name,String module,Stage stage) {
        if(!initialized) {
            WorldRenderEvents.END_MAIN.addPhaseOrdering(Event.DEFAULT_PHASE,OVERLAYS);
            Identifier previous=OVERLAYS;
            for(Stage value:Stage.values()) {
                Identifier begin=boundary(value,"begin"),end=boundary(value,"end");
                WorldRenderEvents.END_MAIN.addPhaseOrdering(previous,begin);
                WorldRenderEvents.END_MAIN.addPhaseOrdering(begin,end);
                phases.put(value,new TreeMap<>());previous=end;
            }
            WorldRenderEvents.END_MAIN.addPhaseOrdering(previous,LABELS);
            initialized=true;
        }
        int rank=ORDER.indexOf(module);
        String key=String.format(Locale.ROOT,"%03d/%s/%s",rank<0?ORDER.size():rank,module,name);
        TreeMap<String,Identifier> ordered=phases.get(stage);
        Identifier known=ordered.get(key);if(known!=null)return known;
        String clean=name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]","_");
        Identifier current=Identifier.of("celeste","world_overlays/"+stage.name().toLowerCase(Locale.ROOT)+"/callback/"+clean);
        var lower=ordered.lowerEntry(key);var higher=ordered.higherEntry(key);
        Identifier before=lower==null?boundary(stage,"begin"):lower.getValue();
        Identifier after=higher==null?boundary(stage,"end"):higher.getValue();
        WorldRenderEvents.END_MAIN.addPhaseOrdering(before,current);
        WorldRenderEvents.END_MAIN.addPhaseOrdering(current,after);
        ordered.put(key,current);return current;
    }
    private static Identifier boundary(Stage stage,String suffix) {
        return Identifier.of("celeste","world_overlays/"+stage.name().toLowerCase(Locale.ROOT)+"/"+suffix);
    }
}
