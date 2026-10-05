"""Exercise the actual manager, GUI intent, hooks and delivery retirement with inert child code."""
from pathlib import Path
import argparse, json, subprocess

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--java-home', type=Path, required=True)
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
classes = out / 'classes'
classes.mkdir(exist_ok=True)
stubs = {p.relative_to(ROOT/'tools/qa/ambient-layers-stubs').as_posix(): p.read_text(encoding='utf-8')
         for p in (ROOT/'tools/qa/ambient-layers-stubs').rglob('*.java')}
stubs.update({
 'net/minecraft/client/MinecraftClient.java': '''package net.minecraft.client;
public class MinecraftClient {
 public static final MinecraftClient INSTANCE=new MinecraftClient();
 public Object world=new Object(),player=new Object(),currentScreen; public boolean focused=true;
 private final Thread owner=Thread.currentThread(); private final java.util.Queue<Runnable> tasks=new java.util.concurrent.ConcurrentLinkedQueue<>();
 public Object getWindow(){return this;} public Object getOverlay(){return null;} public boolean isWindowFocused(){return focused;}
 public static MinecraftClient getInstance(){return INSTANCE;} public boolean isOnThread(){return Thread.currentThread()==owner;}
 public void execute(Runnable task){tasks.add(task);} public void drain(){for(Runnable task;(task=tasks.poll())!=null;)task.run();}
}''',
 'net/minecraft/client/util/InputUtil.java': '''package net.minecraft.client.util;
public class InputUtil { public static int pressed=-1; public static boolean isKeyPressed(Object window,int key){return key==pressed;} }''',
 'dev/c4lam/celeste/gui/model/GuiCatalog.java': '''package dev.c4lam.celeste.gui.model;
public final class GuiCatalog {
 public static final GuiSetting.Keybind BIND=new GuiSetting.Keybind("Bind","",66);
 public static final GuiModule FREECAM=new GuiModule("Freecam","",CelestePage.VISUALS,false,BIND);
 public static final GuiModule OTHER=new GuiModule("Other","",CelestePage.VISUALS,false,new GuiSetting.Keybind("Bind","",67));
 public static java.util.List<GuiModule> modules(){return java.util.List.of(FREECAM,OTHER);}
}''',
 'dev/c4lam/celeste/module/delivery/ModulePayloadCatalog.java': '''package dev.c4lam.celeste.module.delivery;
public final class ModulePayloadCatalog {public record Descriptor(String id,String name){}
 public static final java.util.List<Descriptor> MODULES=java.util.List.of(new Descriptor("freecam","Freecam"),new Descriptor("other","Other"));}''',
 'dev/c4lam/celeste/render/ModuleBoxRenderer.java': '''package dev.c4lam.celeste.render; public class ModuleBoxRenderer {public static void initialize(){}}''',
 'dev/c4lam/celeste/render/ModuleVisualBudget.java': '''package dev.c4lam.celeste.render; public class ModuleVisualBudget {public static void initialize(){}}''',
 'dev/c4lam/celeste/module/delivery/ModulePipelineResources.java': '''package dev.c4lam.celeste.module.delivery; public class ModulePipelineResources {public static void initialize(){} public static void maintenance(){}}''',
 'fixture/ChildFreecamFeature.java': '''package fixture;
public final class ChildFreecamFeature implements dev.c4lam.celeste.module.ModuleFeature {
 public boolean flight;public int starts,stops,toggles;
 public String name(){return "Freecam";}public boolean canRun(net.minecraft.client.MinecraftClient client){return client.world!=null&&client.player!=null;}
 public void onEnable(dev.c4lam.celeste.module.ModuleContext context){starts++;flight=true;}
 public void onDisable(dev.c4lam.celeste.module.ModuleContext context){stops++;flight=false;}
 public boolean toggle(){toggles++;return flight=!flight;}
}''',
})
sources = []
for name, text in stubs.items():
    path = out/'source'/name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding='utf-8')
    sources.append(path)
overlay = ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste'
sources += [overlay/n for n in ['module/ModuleManager.java', 'gui/model/GuiModule.java', 'gui/model/GuiSetting.java', 'gui/model/AmbientEffectsSettings.java']]
sources += [overlay/'module/delivery'/n for n in ['ModuleEntrypoint.java', 'ModulePayloadLease.java', 'ModulePayloadProvider.java', 'ModuleServices.java', 'ModuleScope.java', 'ModuleHookRegistry.java', 'ModulePayloadRuntime.java', 'ModuleRetirement.java', 'ModuleHookAbi.java']]
sources += [ROOT/'tools/qa/module-delivery/FreecamBindCheck.java']
compile_result = subprocess.run([str(args.java_home/'bin/javac.exe'), '-encoding', 'UTF-8', '-d', str(classes), *map(str, sources)], capture_output=True, text=True)
(out/'compile.log').write_text(compile_result.stdout+compile_result.stderr, encoding='utf-8')
if compile_result.returncode:
    print(compile_result.stderr)
    raise SystemExit(compile_result.returncode)
run = subprocess.run([str(args.java_home/'bin/java.exe'), '-ea', '-Dceleste.qa.config='+str(out/'config'), '-cp', str(classes), 'fixture.FreecamBindCheck', str(classes/'fixture/ChildFreecamFeature.class')], capture_output=True, text=True)
(out/'test.log').write_text(run.stdout+run.stderr, encoding='utf-8')
print(run.stdout+run.stderr, end='')
if run.returncode:
    raise SystemExit(run.returncode)
report = json.loads(run.stdout)
report.update(scope='actual manager, GUI, hook and lifecycle code; inert child module and game fixtures', game_verification='not-run')
(out/'result.json').write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
