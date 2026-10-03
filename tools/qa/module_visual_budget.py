"""Verify original shared visual allocation and actual Fabric phase ordering, without a game/GL context."""
from pathlib import Path
import argparse,json,subprocess
ROOT=Path(__file__).resolve().parents[2]
def main():
 p=argparse.ArgumentParser();p.add_argument('--java-home',type=Path,required=True);p.add_argument('--classpath-file',type=Path,required=True);p.add_argument('--host-callback-source',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 out=a.output.resolve();out.mkdir(parents=True,exist_ok=False);classes=out/'classes';classes.mkdir()
 cp=a.classpath_file.read_text(encoding='utf-8').strip();src=ROOT/'runtime-overlay/src/main/java/dev/c4lam/celeste'
 sources=[src/'render/ModuleVisualBudget.java',src/'render/WorldOverlayPass.java',src/'module/delivery/ModuleScope.java',src/'beta/lifecycle/BetaSubscriptions.java',a.host_callback_source,ROOT/'tools/qa/module-delivery/ModuleVisualBudgetCheck.java',ROOT/'tools/qa/module-delivery/ModuleVisualOrderCheck.java']
 def run(name,binary,arguments):
  argfile=out/(name+'.args');argfile.write_text('\n'.join('"'+str(value).replace('\\','/')+'"' for value in arguments),encoding='utf-8')
  r=subprocess.run([str(a.java_home/'bin'/(binary+'.exe')),'@'+str(argfile)],capture_output=True,text=True);(out/(name+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8')
  if r.returncode:print(r.stdout+r.stderr);raise SystemExit(r.returncode)
  return r
 run('compile','javac',['--release','21','-proc:none','-encoding','UTF-8','-classpath',cp,'-d',classes,*sources])
 r=run('test','java',['-classpath',str(classes)+';'+cp,'dev.c4lam.celeste.render.ModuleVisualBudgetCheck']);report=json.loads(r.stdout)
 ordered=run('render-order','java',['-classpath',str(classes)+';'+cp,'dev.c4lam.celeste.render.ModuleVisualOrderCheck']);report['render_order']=json.loads(ordered.stdout)
 report['boundary']='real allocation policy and Fabric dispatcher; no gameplay or GPU';(out/'result.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps(report))
if __name__=='__main__':main()
