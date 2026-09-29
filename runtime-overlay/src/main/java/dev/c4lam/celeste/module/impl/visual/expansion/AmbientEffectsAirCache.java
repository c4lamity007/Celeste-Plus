package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.HashMap;
import java.util.Map;

/** One-frame cache: effects share observations, with a fair fixed budget and no terrain search. */
public final class AmbientEffectsAirCache {
    public static final int MAX_WORLD_PROBES = 8192 * AmbientField.DENSITY_COPIES;
    public interface Cells {
        boolean clear(int x,int y,int z);
        boolean skyVisible(int x,int y,int z);
    }
    private record Cell(int x,int y,int z) { }
    private static final class Entry {
        final boolean clear;
        Boolean sky;
        Entry(boolean clear) { this.clear=clear; }
    }
    private final Cells world;
    private final Map<Cell,Entry> observations=new HashMap<>();
    private int remaining=MAX_WORLD_PROBES,layerRemaining;

    public AmbientEffectsAirCache(Cells world) { this.world=world; }
    public void beginLayer(int count) { layerRemaining=MAX_WORLD_PROBES/Math.max(1,count); }
    public int probes() { return MAX_WORLD_PROBES-remaining; }
    private boolean reserve() {
        if(remaining<=0||layerRemaining<=0)return false;
        remaining--;layerRemaining--;return true;
    }
    public boolean clear(int x,int y,int z) {
        Cell key=new Cell(x,y,z);
        Entry found=observations.get(key);
        if(found!=null)return found.clear;
        if(!reserve())return false;
        boolean clear=world.clear(x,y,z);
        observations.put(key,new Entry(clear));
        return clear;
    }
    public boolean openSky(int x,int y,int z) {
        if(!clear(x,y,z))return false;
        Entry entry=observations.get(new Cell(x,y,z));
        if(entry.sky==null) {
            if(!reserve())return false;
            entry.sky=world.skyVisible(x,y,z);
        }
        return entry.sky;
    }
}
