import dev.c4lam.celeste.module.impl.visual.expansion.AmbientField;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath;
import dev.c4lam.celeste.module.impl.visual.expansion.CustomFogMath.*;
import dev.c4lam.celeste.module.impl.visual.expansion.SkyboxMath;

/** Deterministic production atmosphere math; does not claim Minecraft lifecycle integration. */
public final class AtmosphereRulesCheck {
    static int checks;
    static void require(boolean condition,String message) { checks++;if(!condition)throw new AssertionError(message); }
    static void near(double actual,double expected,double tolerance,String message) {
        require(Double.isFinite(actual)&&Math.abs(actual-expected)<=tolerance,message+": "+actual+" vs "+expected);
    }
    static final Fog VANILLA=new Fog(new Color(.42,.57,.66,.73),0,1024,96,128,192,224);
    static final Conditions AIR=new Conditions(true,true,Medium.AIR,false,false,false,false);
    static void fog() {
        Settings normal=new Settings("Mist",.45,160);
        require(CustomFogMath.apply(VANILLA,null,AIR)==VANILLA,"Missing settings must preserve vanilla identity");
        require(CustomFogMath.apply(VANILLA,normal,null)==VANILLA,"Missing conditions must preserve vanilla identity");
        require(CustomFogMath.apply(null,normal,AIR)==null,"Missing vanilla must remain missing");
        require(CustomFogMath.apply(VANILLA,new Settings("Mist",0,160),AIR)==VANILLA,"Zero density changed vanilla");
        for(int flags=0;flags<64;flags++)for(Medium medium:Medium.values()) {
            Conditions condition=new Conditions((flags&1)!=0,(flags&2)!=0,medium,(flags&4)!=0,
                (flags&8)!=0,(flags&16)!=0,(flags&32)!=0);
            Fog result=CustomFogMath.apply(VANILLA,normal,condition);
            if(!condition.allowed())require(result==VANILLA,"Unsafe environment changed vanilla fog: "+condition);
        }
        for(Fog original:new Fog[]{
            new Fog(new Color(Double.NaN,.5,.5,1),0,1024,96,128,192,224),
            new Fog(new Color(.5,.5,.5,1),10,10,96,128,192,224),
            new Fog(new Color(.5,.5,.5,1),0,1024,128,96,192,224),
            new Fog(new Color(.5,.5,.5,1),0,1024,4,8,192,224),
            new Fog(new Color(.5,.5,.5,1),-8,128,96,128,192,224),
            new Fog(new Color(.5,.5,.5,1),0,64,96,128,192,224)})
            require(CustomFogMath.apply(original,normal,AIR)==original,"Invalid/dense fog fallback changed vanilla");
        for(String style:new String[]{"Mist","Dusk","Frost"})for(double distance:new double[]{32,64,160,512}) {
            double previousEnd=Double.POSITIVE_INFINITY;
            for(double density:new double[]{.01,.10,.30,.60,1}) {
                Fog result=CustomFogMath.apply(VANILLA,new Settings(style,density,distance),AIR);
                require(result.environmentalStart()>=8&&result.environmentalEnd()>result.environmentalStart(),"Fog lost clear near range/ordered distances");
                require(result.environmentalEnd()<=previousEnd,"Density did not decrease fog end monotonically");
                require(result.environmentalEnd()<=VANILLA.environmentalEnd(),"Custom fog extended the vanilla environment end");
                require(result.renderDistanceStart()==VANILLA.renderDistanceStart()&&result.renderDistanceEnd()==VANILLA.renderDistanceEnd()
                    &&result.skyEnd()==VANILLA.skyEnd()&&result.cloudEnd()==VANILLA.cloudEnd(),"Native render/sky/cloud distance changed");
                require(result.color().a()==VANILLA.color().a(),"Fog alpha changed");
                for(double value:new double[]{result.color().r(),result.color().g(),result.color().b()})
                    require(Double.isFinite(value)&&value>=0&&value<=.66,"Fog became nonfinite or brighter than native atmosphere");
                previousEnd=result.environmentalEnd();
                Fog nearby=CustomFogMath.apply(VANILLA,new Settings(style,density-1e-6,distance),AIR);
                near(nearby.color().r(),result.color().r(),1e-6,"Fog color density discontinuity");
            }
        }
        Fog fallback=CustomFogMath.apply(VANILLA,new Settings(null,Double.NaN,Double.POSITIVE_INFINITY),AIR);
        Fog tiny=CustomFogMath.apply(VANILLA,new Settings("Mist",1e-9,160),AIR);
        near(tiny.environmentalEnd(),VANILLA.environmentalEnd(),1e-5,"Near-zero fog density causes an end-distance jump");
        near(tiny.color().r(),VANILLA.color().r(),1e-8,"Near-zero fog density causes a color jump");
        near(tiny.environmentalStart(),8,1e-6,"Near-zero fog preserves the clear foreground floor");
        Fog full=CustomFogMath.apply(VANILLA,new Settings("Mist",1,64),AIR);
        near(full.environmentalEnd(),64*.90,1e-10,"Full-density fog end changed");
        near(full.environmentalStart(),64*.16,1e-10,"Full-density clear foreground changed");
        require(fallback.equals(CustomFogMath.apply(VANILLA,normal,AIR)),"Nonfinite settings did not use documented defaults");
        require(CustomFogMath.apply(VANILLA,new Settings("Mist",-10,-10),AIR)==VANILLA,"Negative density did not clamp to disabled");
        Fog dark=new Fog(new Color(0,0,0,.4),0,1024,96,128,192,224);
        require(CustomFogMath.apply(dark,normal,AIR).color().equals(dark.color()),"Fog illuminates a dark cave/night");
        Color mist=CustomFogMath.apply(VANILLA,new Settings("Mist",.8,160),AIR).color();
        Color dusk=CustomFogMath.apply(VANILLA,new Settings("Dusk",.8,160),AIR).color();
        Color frost=CustomFogMath.apply(VANILLA,new Settings("Frost",.8,160),AIR).color();
        require(!mist.equals(dusk)&&!mist.equals(frost)&&!dusk.equals(frost),"Fog styles are indistinguishable");
    }
    static void mote(AmbientField.Mote mote,int x,int y,int z) {
        for(double value:new double[]{mote.x(),mote.y(),mote.z(),mote.size(),mote.yaw(),mote.pitch(),mote.opacity()})
            require(Double.isFinite(value),"Nonfinite ambient sample");
        require(mote.opacity()>=0&&mote.opacity()<=1&&mote.size()>0&&mote.size()<=.204,"Invalid mote size/alpha");
        require(mote.x()>x*AmbientField.CELL&&mote.x()<(x+1)*AmbientField.CELL
            &&mote.y()>=y*AmbientField.CELL&&mote.y()<=(y+1)*AmbientField.CELL
            &&mote.z()>z*AmbientField.CELL&&mote.z()<(z+1)*AmbientField.CELL,"Mote escaped its stable world cell");
    }
    static void ambient() {
        for(int x=-4;x<=4;x++)for(int y=-2;y<=2;y++)for(int z=-4;z<=4;z++) {
            boolean previous=false;
            for(double density:new double[]{0,.25,.5,.75,1}) {
                boolean occupied=AmbientField.occupied(x,y,z,density);
                if(density==0)require(!occupied,"Zero ambient density must disable all cells");
                require(!previous||occupied,"Increasing density removed an existing world-cell mote");previous=occupied;
            }
            for(AmbientField.Style style:AmbientField.Style.values()) {
            if(style==AmbientField.Style.TESSERACTS)continue; // Random births have their own AmbientTesseractCheck contract.
                AmbientField.Mote first=AmbientField.sample(x,y,z,style,2.125),later=AmbientField.sample(x,y,z,style,2.125+1.0/60);
                mote(first,x,y,z);mote(later,x,y,z);
                require(first.equals(AmbientField.sample(x,y,z,style,2.125)),"Camera-independent world cell is nondeterministic");
                if(Math.abs(first.y()-later.y())<1) {
                    double distance=Math.sqrt(Math.pow(first.x()-later.x(),2)+Math.pow(first.y()-later.y(),2)+Math.pow(first.z()-later.z(),2));
                    require(distance>0&&distance<.02,"Ambient fractional-frame motion jumps/stops");
                }else require(first.opacity()<.001&&later.opacity()<.001,"Ambient recycle is visible");
                near(first.size(),later.size(),0,"Camera/time changed stable mote size");
                require(first.color()==later.color(),"Camera/time reseeded mote color");
            }
        }
        for(AmbientField.Style style:AmbientField.Style.values()) {
            if(style==AmbientField.Style.TESSERACTS)continue; // Random births have their own AmbientTesseractCheck contract.
            AmbientField.Mote zero=AmbientField.sample(2,-1,3,style,0);
            require(zero.equals(AmbientField.sample(2,-1,3,style,Double.NaN)),"Nonfinite ambient time fallback changed");
            double cycle=zero.y()/AmbientField.CELL+1;
            double speed=style==AmbientField.Style.SNOW?-.085:style==AmbientField.Style.CRYSTALS?.018:.035;
            if(style==AmbientField.Style.LEAVES) {
                double phaseDelta=(AmbientField.sample(2,-1,3,style,.001).y()-zero.y())/AmbientField.CELL;
                phaseDelta-=Math.rint(phaseDelta); // Unwrap the phase if this short sample crosses a cell recycle.
                speed=phaseDelta/.001;
                require(speed>=-.070001&&speed<=-.044999,"Leaves must drift downward at their bounded per-cell rate");
            }
            double wrap=speed<0?cycle/-speed:(1-cycle)/speed;
            AmbientField.Mote before=AmbientField.sample(2,-1,3,style,wrap-1e-5),after=AmbientField.sample(2,-1,3,style,wrap+1e-5);
            require(before.opacity()<1e-6&&after.opacity()<1e-6,"Cell wrap must be transparent on both sides");
        }
        near(AmbientField.visibility(0,0,0),0,0,"Motes obscure near camera");
        near(AmbientField.visibility(AmbientField.RANGE,0,0),0,0,"Far horizontal boundary is visible");
        near(AmbientField.visibility(0,AmbientField.HEIGHT,0),0,0,"Far vertical boundary is visible");
        near(AmbientField.visibility(Double.NaN,0,0),0,0,"Invalid camera offset remains visible");
        require(AmbientField.visibility(6,0,0)>.99,"Ordinary midrange motes disappeared");
        double previous=1;
        for(int i=0;i<=100;i++) {
            double distance=6+(AmbientField.RANGE-6)*i/100;
            double value=AmbientField.visibility(distance,0,0);
            require(value>=0&&value<=previous+1e-12,"Far visibility is not monotonic");previous=value;
            near(value,AmbientField.visibility(0,0,-distance),1e-12,"Distance fade depends on camera heading");
        }
    }
    static void sky() {
        require(SkyboxMath.mode("Aurora")==0&&SkyboxMath.mode("Nebula")==1&&SkyboxMath.mode("Sunset")==2
            &&SkyboxMath.mode("Abyss")==3&&SkyboxMath.mode("Black Hole")==4
            &&SkyboxMath.mode("black hole")==4,"Sky mode mapping changed");
        near(SkyboxMath.finiteClamp(Double.NaN,.4,0,1),.4f,0,"Sky nonfinite setting fallback");
        near(SkyboxMath.finiteClamp(2,.4,0,1),1,0,"Sky setting maximum clamp");
        long origin=1_000_000_000_000L;
        double phase=SkyboxMath.animationPhase(origin+1_000_000_000L,origin);
        near(phase,Math.PI*2/240,1e-6,"Sky animation phase is not smooth real time");
        near(SkyboxMath.animationPhase(origin+241_000_000_000L,origin),phase,1e-6,"Sky phase does not wrap at240seconds");
        for(int flags=0;flags<32;flags++)
            require(SkyboxMath.shouldRender((flags&1)!=0,(flags&2)!=0,(flags&4)!=0,(flags&8)!=0,(flags&16)!=0)==(flags==31),"Sky safety/lifecycle predicate failed");
    }
    public static void main(String[] args) {
        fog();ambient();sky();
        System.out.println("PASS AtmosphereRules: "+checks+" checks; actual fog safety/near clarity/density/colors; deterministic world-cell ambient field/fades/wrap; sky modes/clock/render eligibility. No in-game claim.");
    }
}
