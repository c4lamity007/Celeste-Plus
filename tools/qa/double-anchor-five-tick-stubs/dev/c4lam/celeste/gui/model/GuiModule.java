package dev.c4lam.celeste.gui.model;
import java.util.*;
public final class GuiModule {
 private final String name; public boolean enabled; public boolean held;
 public final Map<String,Double> sliders=new HashMap<>();
 public final Map<String,Boolean> toggles=new HashMap<>();
 public GuiModule(String name,boolean enabled){this.name=name;this.enabled=enabled;}
 public String name(){return name;} public boolean enabled(){return enabled;}
}
