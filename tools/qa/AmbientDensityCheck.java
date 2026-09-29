package dev.c4lam.celeste.module.impl.visual.expansion;

import java.util.List;
import java.util.HashSet;

/** Behavior checks for the expanded population, independent local colors and terminal caps. */
public final class AmbientDensityCheck {
    static int checks;
    static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    static void density() {
        for(var style:List.of(AmbientField.Style.FIREFLIES,AmbientField.Style.CRYSTALS,AmbientField.Style.SNOW,AmbientField.Style.LEAVES)) {
            int oldCount=0,newCount=0;
            for(int x=-5;x<=5;x++)for(int y=-3;y<=3;y++)for(int z=-5;z<=5;z++)if(AmbientField.occupied(x,y,z,1)) {
                oldCount++;
                var distinct=new HashSet<AmbientField.Mote>();
                for(int lane=0;lane<AmbientField.DENSITY_COPIES;lane++) {
                    var mote=AmbientField.sample(x,y,z,style,12,lane);distinct.add(mote);newCount++;
                    check(Double.isFinite(mote.x()+mote.y()+mote.z()),"Invalid high density sample");
                    if(lane==0)check(mote.equals(AmbientField.sample(x,y,z,style,12)),"Legacy lane changed");
                }
                check(distinct.size()==3,"Three lanes duplicate particle positions");
            }
            check(newCount==oldCount*3,"Maximum population is not exactly tripled for "+style);
        }
        for(int seed=0;seed<8;seed++) {
            var cubes=AmbientTesseractField.candidates(2.5,3,2.5,12,1,seed);
            check(cubes.size()==192*3,"Tesseract maximum quietly clamped");
            check(cubes.stream().map(AmbientTesseractField.Candidate::mote).distinct().count()==cubes.size(),"Duplicated tesseracts");
        }
        check(AmbientTronField.trailCount(1)==24*3,"Tron maximum not tripled");
        check(AmbientTronField.trailCount(1.0/3)==24,"Tron one-third should recover old maximum");
        check(AmbientTronField.trailCount(0)==0,"Zero density must remain empty");
        for(var style:AmbientField.Style.values()) {
            var one=List.of(new AmbientEffectsLayers.Layer(style,1,1));
            int expected=style==AmbientField.Style.TRON_TRAILS?0:style==AmbientField.Style.TESSERACTS?576:384;
            check(AmbientEffectsLayers.particleCap(one,style)==expected,"Single-style draw cap did not expand");
        }
    }
    static void colors() {
        for(var style:AmbientField.Style.values()) {
            var layer=new AmbientEffectsLayers.Layer(style,1,1,0xFD3188);
            VisualAtmosphere.clear();
            check(AmbientEffectsLayers.color(layer,12,.4)==0xFD3188,"Manual layer color lost "+style);
            VisualAtmosphere.configure(true,"Aurora",false);
            check(AmbientEffectsLayers.color(layer,12,.4)==VisualAtmosphere.sample(VisualAtmosphere.Preset.AURORA,12,.4),"Atmosphere not shared by "+style);
            VisualAtmosphere.configure(true,"Aurora",true);
            check(AmbientEffectsLayers.color(layer,12,.4)==0xFD3188,"Advanced failed to restore custom color");
            check(layer.color()==0xFD3188,"Atmosphere modified saved RGB");
        }
        VisualAtmosphere.clear();
    }
    public static void main(String[] args){density();colors();System.out.println("PASS Ambient density/color: "+checks+" checks; three distinct copies per occupied cell,576 cubes,72 trails, expanded draw budgets and six independent colors restored in Advanced.");}
}
