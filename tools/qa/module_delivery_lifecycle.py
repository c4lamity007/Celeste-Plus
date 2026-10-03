"""Compile real module retirement code with minimal game-independent fixtures."""
from pathlib import Path
import argparse,json,subprocess
ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--host-callback-source',type=Path,required=True)
a=p.parse_args()
out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
classes=out/'classes';classes.mkdir()
src=ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste/module/delivery'
fixture=ROOT/'tools/qa/module-delivery'
sources=[src/n for n in ['ModuleEntrypoint.java','ModulePayloadLease.java','ModulePayloadProvider.java','ModuleServices.java','ModuleScope.java','ModuleHookRegistry.java','ModulePayloadRuntime.java','ModuleRetirement.java','ScopedRegistry.java','ModuleHookAbi.java','ModuleFrame.java']]
sources += [a.host_callback_source, src.parents[1]/'beta/lifecycle/BetaSubscriptions.java']
sources+=list((fixture/'fixtures').glob('*.java'))+[fixture/'ModulePayloadRuntimeCheck.java']
compile=subprocess.run([str(a.java_home/'bin/javac.exe'),'-encoding','UTF-8','-d',str(classes),*map(str,sources)],capture_output=True,text=True)
(out/'compile.log').write_text(compile.stdout+compile.stderr,encoding='utf-8')
if compile.returncode: raise SystemExit(compile.returncode)
run=subprocess.run([str(a.java_home/'bin/java.exe'),'-cp',str(classes),'fixture.ModulePayloadRuntimeCheck',str(classes/'fixture/ChildFeature.class')],capture_output=True,text=True)
(out/'test.log').write_text(run.stdout+run.stderr,encoding='utf-8')
if run.returncode: raise SystemExit(run.returncode)
report=json.loads(run.stdout);report['game_verification']='not-run';report['boundary']='real lifecycle and classloader, minimal game API stubs'
(out/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report))

