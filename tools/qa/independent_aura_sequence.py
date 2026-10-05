"""Preserve original aura decisions while checking independent scoped sequence arbitration."""
from pathlib import Path
import argparse,subprocess
ROOT=Path(__file__).resolve().parents[2]
STUBS={
 'Rotation.java':'package dev.c4lam.celeste.rotation; public final class Rotation {}',
 'MoveFixMode.java':'package dev.c4lam.celeste.rotation; public enum MoveFixMode {STRICT}',
 'RotationTask.java':'package dev.c4lam.celeste.rotation; public record RotationTask(Object owner){ public static Builder builder(Object owner,Rotation look){return new Builder(owner);} public record Builder(Object owner){public Builder priority(int n){return this;}public Builder lifetimeTicks(int n){return this;}public Builder smoothing(int a,int b){return this;}public Builder sensitivityQuantized(boolean b){return this;}public Builder moveFix(MoveFixMode m){return this;}public RotationTask build(){return new RotationTask(owner);}}}',
 'RotationManager.java':'package dev.c4lam.celeste.rotation; public final class RotationManager { private static final RotationManager INSTANCE=new RotationManager(); public final java.util.Set<Object> owners=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());public static RotationManager getInstance(){return INSTANCE;}public void release(Object o){owners.remove(o);}public void submit(RotationTask t){owners.add(t.owner());}}'
}
def main():
 p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 out=a.output.resolve();out.mkdir(parents=True,exist_ok=True);src=out/'stubs';classes=out/'classes';classes.mkdir(exist_ok=True)
 sources=[]
 for name,text in STUBS.items():
  path=src/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8',newline='\n');sources.append(path)
 base=ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste'
 sources += [base/'action/SequenceTurnBroker.java',base/'module/delivery/ModuleScope.java']
 for owner in ['Anchor','Crystal']:
  path=src/(owner+'AuraRulesCheck.java')
  text=(ROOT/'tools/qa/module-delivery/IndependentAuraRulesCheck.java').read_text(encoding='utf-8').replace('IndependentAuraRulesCheck',owner+'AuraRulesCheck').replace('AuraRules.',owner+'AuraRules.')
  path.write_text(text,encoding='utf-8',newline='\n');sources += [path,base/('module/impl/combat/'+owner+'AuraRules.java')]
 result=subprocess.run([str(a.java_home/'bin/javac.exe'),'--release','21','-proc:none','-encoding','UTF-8','-d',str(classes),*map(str,sources)],capture_output=True,text=True)
 (out/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8')
 if result.returncode:print(result.stderr);raise SystemExit(result.returncode)
 logs=[]
 for owner in ['Anchor','Crystal']:
  result=subprocess.run([str(a.java_home/'bin/java.exe'),'-ea','-cp',str(classes),'dev.c4lam.celeste.module.impl.combat.'+owner+'AuraRulesCheck'],capture_output=True,text=True)
  logs.append(result.stdout+result.stderr);print(logs[-1])
  (out/'behavior.log').write_text('\n'.join(logs),encoding='utf-8')
  if result.returncode:raise SystemExit(result.returncode)
 print('Actual independent aura rules and resident turn broker; Minecraft rotation API fixture, not in-game verification.')
if __name__=='__main__':main()
