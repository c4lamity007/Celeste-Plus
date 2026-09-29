package dev.c4lam.celeste.module.impl.visual.expansion;

import dev.c4lam.celeste.gui.model.*;
import dev.c4lam.celeste.module.ModuleContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import java.util.*;

/** Runs the real catalog, settings and feature code; only game/GPU/audio boundaries are stubbed. */
public final class VisualAtmosphereCheck {
    private static int checks;
    private static void check(boolean value,String why) { checks++;if(!value)throw new AssertionError(why); }
    private static GuiModule module(String name) { return GuiCatalog.modules().stream().filter(m->m.name().equals(name)).findFirst().orElseThrow(); }
    private static GuiSetting setting(String name,String label) { return module(name).settings().stream().filter(s->s.label().equals(label)).findFirst().orElseThrow(); }
    private static GuiSetting.Mode mode(String name,String label) { return (GuiSetting.Mode)setting(name,label); }
    private static boolean visible(String name,String label) { return module(name).visibleSettings().stream().anyMatch(s->s.label().equals(label)); }
    private static String values() {
        StringBuilder out=new StringBuilder();
        for(GuiModule m:GuiCatalog.modules()) {
            if(m.name().equals("Atmosphere"))continue;
            out.append(m.name()).append('=').append(m.enabled()).append(':');
            for(GuiSetting s:m.settings()) {
                out.append(s.label()).append('=');
                if(s instanceof GuiSetting.Mode v)out.append(v.value());
                else if(s instanceof GuiSetting.ColorPick v)out.append(v.rgb());
                else if(s instanceof GuiSetting.Toggle v)out.append(v.value());
                else if(s instanceof GuiSetting.Slider v)out.append(v.value());
                else if(s instanceof GuiSetting.Range v)out.append(v.serialized());
                else if(s instanceof GuiSetting.Keybind v)out.append(v.keyCode());
                else if(s instanceof GuiSetting.ItemPick v)out.append(v.itemId());
                else out.append(System.identityHashCode(s));
                out.append(';');
            }
        }
        return out.toString();
    }
    public static void main(String[] args) throws Exception {
        GuiModule atmosphere=module("Atmosphere");
        GuiSetting.Toggle advanced=(GuiSetting.Toggle)setting("Atmosphere","Advanced");
        GuiSetting.Mode preset=mode("Atmosphere","Preset");
        check(!atmosphere.enabled()&&!advanced.value()&&preset.value().equals("Abyss"),"safe catalog defaults");
        check(GuiCatalog.modules().stream().filter(m->m.name().equals("Atmosphere")).count()==1,"unique module");
        mode("Custom Sky","Mode").setValue("Black Hole");
        mode("Custom Fog","Rendering").setValue("Native");
        mode("Custom Fog","Shader").setValue("Sunset");
        mode("Custom Fog","Style").setValue("Frost");
        ((GuiSetting.ColorPick)setting("Hand Shaders","Color")).setRgb(0xC73965);
        mode("Target ESP","Theme").setValue("Custom");
        ((GuiSetting.ColorPick)setting("Target ESP","Color")).setRgb(0xB17121);
        module("Trails").setEnabled(true);
        String original=values();
        Map<String,List<String>> originalRows=new HashMap<>();
        for(GuiModule m:GuiCatalog.modules())if(m!=atmosphere)originalRows.put(m.name(),m.visibleSettings().stream().map(GuiSetting::label).toList());
        MinecraftClient client=new MinecraftClient();client.world=new ClientWorld();client.player=new Object();client.interactionManager=new Object();
        ModuleContext ac=new ModuleContext(client,atmosphere),hc=new ModuleContext(client,module("Hand Shaders")),fc=new ModuleContext(client,module("Custom Fog"));
        AtmosphereFeature feature=new AtmosphereFeature();HandShadersFeature hand=new HandShadersFeature();CustomFogFeature fog=new CustomFogFeature();
        check(!VisualAtmosphere.usesPreset()&&VisualAtmosphere.tint(0x12ABEF,10,.7)==0x12ABEF,"disabled RGB exact");
        check(VisualAtmosphere.sky("local").equals("local"),"disabled sky exact");
        atmosphere.setEnabled(true);feature.onEnable(ac);hand.onEnable(hc);fog.onEnable(fc);
        String[][] hidden={{"Custom Sky","Mode"},{"Custom Fog","Rendering"},{"Custom Fog","Shader"},{"Custom Fog","Style"},{"Hand Shaders","Color"},{"Target ESP","Theme"},{"Target ESP","Color"},{"Trails","Theme"},{"Trails","Color"},{"Chams","Color mode"},{"Chams","Color"},{"Player ESP","Theme"},{"Player ESP","Color"}};
        for(String[] row:hidden)check(!visible(row[0],row[1]),"basic hides "+Arrays.toString(row));
        check(visible("Custom Sky","Brightness")&&visible("Custom Fog","Density")&&visible("Target ESP","Style")&&visible("Hand Shaders","Mode"),"geometry and intensity remain editable");
        check(VisualAtmosphere.sky("Black Hole").equals("Abyss"),"effective sky matches preset");
        check(fog.settings(client.world).shader()&&fog.settings(client.world).shaderPreset().equals("Abyss"),"effective fog matches preset");
        check(hand.settings().color()==0x2EAFD6,"stable default hand tint");
        Set<Integer> distinct=new HashSet<>();
        for(VisualAtmosphere.Preset p:VisualAtmosphere.Preset.values()) {
            preset.setValue(p.sky());feature.onRenderFrame(ac);hand.onRenderFrame(hc);fog.onRenderFrame(fc);
            check(VisualAtmosphere.sky("local").equals(p.sky())&&fog.settings(client.world).shaderPreset().equals(p.sky()),"frame callback updates sky/fog without tick");
            check(values().equals(original),"palette selection never writes saved settings or toggles");
            HandShadersFeature.Settings first=hand.settings();
            for(int i=0;i<100;i++){feature.onRenderFrame(ac);hand.onRenderFrame(hc);check(hand.settings().equals(first),"stable hand history key");}
            distinct.add(first.color());
            for(int i=-1000;i<=1000;i++) {
                double t=i*.071,phase=(i%71)/71.0;
                int rgb=VisualAtmosphere.tint(0,t,phase);
                check((rgb&0xFF000000)==0,"RGB finite output");
                for(int shift=0;shift<=16;shift+=8) {
                    int c=rgb>>shift&255,a=p.primary()>>shift&255,b=p.accent()>>shift&255;
                    check(c>=Math.min(a,b)&&c<=Math.max(a,b),"saturated palette bounds");
                }
                check(rgb==VisualAtmosphere.sample(p,t,phase+1),"phase wrap");
            }
            for(double t:new double[]{Double.NaN,Double.POSITIVE_INFINITY,Double.NEGATIVE_INFINITY,Double.MAX_VALUE,-Double.MAX_VALUE})
                check((VisualAtmosphere.tint(0,t,t)&0xFF000000)==0,"invalid time/phase finite");
        }
        check(distinct.size()==5,"five distinct presets");
        advanced.setValue(true);feature.onRenderFrame(ac);hand.onRenderFrame(hc);fog.onRenderFrame(fc);
        check(!VisualAtmosphere.usesPreset()&&!visible("Atmosphere","Preset"),"advanced releases preset");
        check(hand.settings().color()==0xC73965&&!fog.settings(client.world).shader()&&fog.settings(client.world).shaderPreset().equals("Sunset"),"advanced restores exact effective local values");
        check(VisualAtmosphere.sky(mode("Custom Sky","Mode").value()).equals("Black Hole"),"advanced sky restored");
        for(GuiModule m:GuiCatalog.modules())if(m!=atmosphere)check(m.visibleSettings().stream().map(GuiSetting::label).toList().equals(originalRows.get(m.name())),"advanced exact visible rows: "+m.name());
        check(values().equals(original),"advanced saved state exact");
        advanced.setValue(false);feature.onRenderFrame(ac);atmosphere.setEnabled(false);feature.onRenderFrame(ac);hand.onRenderFrame(hc);fog.onRenderFrame(fc);
        check(!VisualAtmosphere.usesPreset()&&hand.settings().color()==0xC73965,"off refreshes during paused frame");
        for(GuiModule m:GuiCatalog.modules())if(m!=atmosphere)check(m.visibleSettings().stream().map(GuiSetting::label).toList().equals(originalRows.get(m.name())),"off exact visible rows: "+m.name());
        check(values().equals(original),"off saved values and unrelated toggles unchanged");
        atmosphere.setEnabled(true);feature.onStartTick(ac);feature.onWorldChanged(ac);check(!VisualAtmosphere.usesPreset(),"world transition clears");
        feature.onEnable(ac);client.world=null;feature.onRenderFrame(ac);check(!VisualAtmosphere.usesPreset(),"disconnect clears");
        client.world=new ClientWorld();client.player=null;feature.onStartTick(ac);check(!VisualAtmosphere.usesPreset(),"missing player clears");
        client.player=new Object();feature.onEnable(ac);feature.onDisable(ac);check(!VisualAtmosphere.usesPreset(),"disable clears");
        check(VisualAtmosphere.preset(null)==VisualAtmosphere.Preset.ABYSS&&VisualAtmosphere.preset("unknown")==VisualAtmosphere.Preset.ABYSS,"invalid preset safe default");
        if(args.length>0) {
            StringBuilder csv=new StringBuilder("preset,primary,accent,hand,samples\n");
            for(VisualAtmosphere.Preset p:VisualAtmosphere.Preset.values()) {
                csv.append(p.sky()).append(',').append(String.format("%06X,%06X,%06X",p.primary(),p.accent(),VisualAtmosphere.sample(p,0,0)));
                for(int i=0;i<256;i++)csv.append(',').append(String.format("%06X",VisualAtmosphere.sample(p,0,i/256.0)));
                csv.append('\n');
            }
            java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]),csv);
        }
        System.out.println("PASS VisualAtmosphere: "+checks+" assertions; actual GUI catalog/settings and feature lifecycle, frame refresh, saved-value preservation, unrelated toggle preservation, stable hand color and bounded palettes. Game/GPU/audio boundaries stubbed; not game verification.");
    }
}
