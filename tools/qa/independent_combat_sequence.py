"""Real independently loaded combat algorithms against bounded prediction/server fixtures."""
from pathlib import Path
import argparse, subprocess, json
ROOT=Path(__file__).resolve().parents[2]
def main():
 p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 out=a.output.resolve();source=out/'stubs';classes=out/'classes';classes.mkdir(parents=True,exist_ok=True)
 files=[]
 for f in (ROOT/'tools/qa/double-anchor-five-tick-stubs').rglob('*.java'):
  rel=f.relative_to(ROOT/'tools/qa/double-anchor-five-tick-stubs');t=f.read_text(encoding='utf-8')
  if f.name=='MinecraftClient.java':t=t.replace('public Object getOverlay()', 'public Object getWindow(){return this;}public void execute(Runnable r){r.run();}public Object getOverlay()')
  if f.name=='DoubleAnchorMacroFeature.java':t=t.replace(' static boolean holdKeyDown', ' static int holdKeyCode(){return 72;}\n static boolean holdKeyDown')
  dst=source/rel;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_text(t,encoding='utf-8');files.append(dst)
 extra={'net/minecraft/client/util/InputUtil.java':'package net.minecraft.client.util; public final class InputUtil { public static boolean isKeyPressed(Object window,int key){return key==72 && dev.c4lam.celeste.module.impl.combat.DoubleAnchorMacroFeature.held(net.minecraft.client.MinecraftClient.getInstance());}}'}
 for n,t in extra.items():
  dst=source/n;dst.parent.mkdir(parents=True,exist_ok=True);dst.write_text(t,encoding='utf-8');files.append(dst)
 base=ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste'
 files += [base/'action/HeldUseBroker.java',base/'module/delivery/ModuleScope.java',base/'module/delivery/ScopedActionOwners.java',base/'gui/HudTransparentScreen.java']
 files += [base/('module/impl/combat/'+name+'Controller.java') for name in ['Anchor','DoubleAnchor','AutoHitCrystal','AutoCrystal']]
 files += [(base/n if (base/n).exists() else ROOT/('build/runtime-core/src/main/java/dev/c4lam/celeste/'+n)) for n in ['action/ActionScheduler.java','action/PrimaryAction.java','silent/SilentSlotManager.java']]
 files += [ROOT/'tools/qa/module-delivery/IndependentCombatSequenceCheck.java']
 r=subprocess.run([str(a.java_home/'bin/javac.exe'),'--release','21','-proc:none','-encoding','UTF-8','-Xlint:all','-Werror','-d',str(classes),*map(str,files)],capture_output=True,text=True)
 (out/'compile.log').write_text(r.stdout+r.stderr,encoding='utf-8')
 if r.returncode:print(r.stderr[-8000:]);raise SystemExit(r.returncode)
 r=subprocess.run([str(a.java_home/'bin/java.exe'),'-ea','-cp',str(classes),'dev.c4lam.celeste.module.impl.combat.IndependentCombatSequenceCheck'],capture_output=True,text=True)
 (out/'behavior.log').write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout+r.stderr)
 raise SystemExit(r.returncode)
if __name__=='__main__':main()
