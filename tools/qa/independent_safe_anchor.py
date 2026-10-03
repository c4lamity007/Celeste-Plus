"""Actual independent Safe Anchor coordinators against original bounded world fixtures."""
from pathlib import Path
import argparse,subprocess
ROOT=Path(__file__).resolve().parents[2]
def main():
 p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--core-root',type=Path,default=ROOT.parent/'Celeste - Updated');p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 out=a.output.resolve();classes=out/'classes';classes.mkdir(parents=True,exist_ok=True)
 base=ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste';core=a.core_root.resolve()
 sources=[f for f in (core/'tools/qa/safe-anchor-runtime-stubs').rglob('*.java') if f.name!='UseInputLease.java']
 sources += [base/'module/impl/combat/SafeAnchorCoordinator.java',base/'action/UseInputLease.java',base/'action/ActionScheduler.java',core/'src/main/java/dev/c4lam/celeste/action/PrimaryAction.java',base/'module/delivery/ModuleScope.java',base/'module/delivery/ScopedActionOwners.java',ROOT/'tools/qa/module-delivery/IndependentSafeAnchorCheck.java']
 result=subprocess.run([str(a.java_home/'bin/javac.exe'),'--release','21','-proc:none','-encoding','UTF-8','-Xlint:all','-Werror','-d',str(classes),*map(str,sources)],capture_output=True,text=True)
 (out/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8')
 if result.returncode:print(result.stderr);raise SystemExit(result.returncode)
 result=subprocess.run([str(a.java_home/'bin/java.exe'),'-ea','-cp',str(classes),'dev.c4lam.celeste.module.impl.combat.IndependentSafeAnchorCheck'],capture_output=True,text=True)
 (out/'behavior.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout+result.stderr)
 raise SystemExit(result.returncode)
if __name__=='__main__':main()
